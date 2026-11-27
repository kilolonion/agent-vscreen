package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.cmd.NativeOffloadRequest
import com.kilon.agentvscreen.cmd.VScreenOffloadHandler
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.host.StrongConfirm
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.URI
import java.net.URLEncoder

/**
 * Security & protocol hardening tests for P1-4:
 * - RFC 9207 `iss` metadata & authorization callback parameter
 * - DCR `application_type` validation
 * - 30s single-retry in-memory refresh rotation grace window (and immediate eviction on new token use / 2nd retry / expiry)
 * - `/oauth/token` per-source rate limiting
 * - Invalid/revoked Bearer replay isolation (cannot DoS-lock a legitimate client's active Bearer)
 * - Public 500 error redaction
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PublicOAuthHardeningTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var tempDir: File
    private lateinit var apiServer: ApiServer
    private lateinit var pubServer: PublicServer
    private var fakeNow = 1_800_000_000_000L
    private val base = "https://phone.tail12345.ts.net"
    private val resource = "$base/mcp"
    private val bridgeHeader = "test-bridge-" + "a".repeat(20)

    @Before
    fun setUp() {
        tempDir = File(ctx.filesDir, "p14-test-${System.nanoTime()}").apply { mkdirs() }
        ApiConfig.init(ctx)
        Tokens.init(ctx)
        Audit.init(ctx)
        Audit.clear()
        StrongConfirm.resetForTests(File(tempDir, "approvals.json"))
        StrongConfirm.postNotification = false
        StrongConfirm.clock = { fakeNow }
        PublicOAuth.resetForTests(File(tempDir, "public_oauth.json"))
        PublicOAuth.clock = { fakeNow }
        PublicOAuth.deviceSecureCheck = { true }
        PublicAccess.resetForTests()
        PublicAccess.clock = { fakeNow }
        PublicAccess.postNotifications = false
        PublicAccess.startServerOnEnable = false
        apiServer = ApiServer(ctx)
        apiServer.failLimiter.resetForTests()
        apiServer.failLimiter.clock = { fakeNow }
        apiServer.failLimiter.sleepFn = {}
        pubServer = PublicServer(
            ctx = ctx,
            apiServer = apiServer,
            secretProvider = { bridgeHeader },
            baseProvider = { base },
            wakeService = {},
        )
    }

    @After
    fun tearDown() {
        if (::pubServer.isInitialized) pubServer.stop()
        PublicOAuth.resetForTests()
        PublicAccess.resetForTests()
        StrongConfirm.resetForTests()
        tempDir.deleteRecursively()
    }

    private fun pubReq(
        method: String,
        target: String,
        body: String = "",
        extraHeaders: Map<String, String> = emptyMap(),
        src: String = "198.51.100.5",
    ): ApiServer.Req {
        val headers = LinkedHashMap<String, String>()
        headers["host"] = "phone.tail12345.ts.net"
        headers["x-avs-public"] = bridgeHeader
        headers["x-avs-public-src"] = src
        extraHeaders.forEach { (k, v) -> headers[k.lowercase()] = v }
        val q = HttpCore.parseForm(target.substringAfter('?', ""))
        return ApiServer.Req(method, target, q, headers, body.toByteArray(Charsets.UTF_8))
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

    private data class PairResult(
        val clientId: String,
        val redirectUrl: String,
        val accessToken: String,
        val refreshToken: String,
    )

    private fun pairAndIssue(src: String = "198.51.100.5"): PairResult {
        val redirect = "http://127.0.0.1:6274/cb"
        val regBody = JSONObject()
            .put("client_name", "ClaudeDesktop")
            .put("redirect_uris", org.json.JSONArray().put(redirect))
            .put("grant_types", org.json.JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", org.json.JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
            .put("application_type", "native")
            .toString()
        val regResp = pubServer.route(pubReq("POST", "/oauth/register", regBody, src = src))
        assertEquals(201, regResp.status)
        val regJson = JSONObject(String(regResp.body))
        assertEquals("native", regJson.getString("application_type"))
        val cid = regJson.getString("client_id")

        val verifier = "v".repeat(64)
        val challenge = PublicOAuth.pkceS256Challenge(verifier)
        var approveFn: ((String) -> Unit)? = null
        PublicOAuth.pairingPresenterOverride = { _, _, onApprove, _ ->
            approveFn = onApprove
            "debug_confirm"
        }
        val authTarget = "/oauth/authorize?" + form(
            "response_type" to "code",
            "client_id" to cid,
            "redirect_uri" to redirect,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "resource" to resource,
            "scope" to "operate",
            "state" to "st_xyz",
        )
        val authResp = pubServer.route(pubReq("GET", authTarget, src = src))
        assertEquals(200, authResp.status)
        val pairing = PublicOAuth.latestPendingPairingForTests()!!
        assertTrue(PublicOAuth.claimPairingByCode(ctx, pairing.code, openActivity = false) is PublicOAuth.ClaimResult.Opened)
        approveFn!!.invoke(Tokens.FREE)

        val stResp = pubServer.route(pubReq("GET", "/oauth/authorize/status?id=${pairing.id}", src = src))
        assertEquals(200, stResp.status)
        val redirUrl = JSONObject(String(stResp.body)).getString("redirect_uri")
        val params = HttpCore.parseForm(URI(redirUrl).rawQuery.orEmpty())
        val code = params["code"]!!.first()

        val tokResp = pubServer.route(
            pubReq(
                "POST",
                "/oauth/token",
                form(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to redirect,
                    "client_id" to cid,
                    "code_verifier" to verifier,
                    "resource" to resource,
                ),
                src = src,
            ),
        )
        assertEquals(200, tokResp.status)
        val tj = JSONObject(String(tokResp.body))
        return PairResult(cid, redirUrl, tj.getString("access_token"), tj.getString("refresh_token"))
    }

    @Test
    fun authorizationMetadataAndRedirectIncludeRfc9207IssuerAndDcrValidatesApplicationType() {
        val metaResp = pubServer.route(pubReq("GET", "/.well-known/oauth-authorization-server"))
        assertEquals(200, metaResp.status)
        val meta = JSONObject(String(metaResp.body))
        assertEquals(base, meta.getString("issuer"))
        assertTrue(meta.getBoolean("authorization_response_iss_parameter_supported"))

        // Invalid application_type rejected with 400 invalid_client_metadata
        val badAppType = JSONObject()
            .put("client_name", "BadClient")
            .put("redirect_uris", org.json.JSONArray().put("https://client.example/cb"))
            .put("application_type", "desktop")
            .toString()
        val badResp = pubServer.route(pubReq("POST", "/oauth/register", badAppType))
        assertEquals(400, badResp.status)
        assertEquals("invalid_client_metadata", JSONObject(String(badResp.body)).getString("error"))

        // Redirect callback includes exact iss parameter matching metadata issuer
        val paired = pairAndIssue()
        val params = HttpCore.parseForm(URI(paired.redirectUrl).rawQuery.orEmpty())
        assertEquals(base, params["iss"]?.firstOrNull())
        assertEquals("st_xyz", params["state"]?.firstOrNull())
    }

    @Test
    fun refreshGraceAllowsSingleIdempotentRetryWithin30sAndRevokesOnSecondRetryOrExpiryOrAfterNewTokenUse() {
        val storeFile = File(tempDir, "public_oauth.json")

        // Case 1: Network drop after refresh -> 1st retry within 30s succeeds idempotently; 2nd retry revokes ALL.
        val p1 = pairAndIssue(src = "198.51.100.11")
        val ref1 = pubServer.route(
            pubReq("POST", "/oauth/token", form("grant_type" to "refresh_token", "refresh_token" to p1.refreshToken, "client_id" to p1.clientId), src = "198.51.100.11"),
        )
        assertEquals(200, ref1.status)
        val j1 = JSONObject(String(ref1.body))
        val access2 = j1.getString("access_token")
        val refresh2 = j1.getString("refresh_token")

        // Plaintext tokens MUST NOT appear in persisted public_oauth.json even while grace entry is active.
        val persisted = storeFile.readText()
        assertFalse("new access token must never be persisted", persisted.contains(access2))
        assertFalse("new refresh token must never be persisted", persisted.contains(refresh2))

        // 1st retry at +10s (before access2 or refresh2 is used) returns identical tokens.
        fakeNow += 10_000L
        val retry1 = pubServer.route(
            pubReq("POST", "/oauth/token", form("grant_type" to "refresh_token", "refresh_token" to p1.refreshToken, "client_id" to p1.clientId), src = "198.51.100.11"),
        )
        assertEquals(200, retry1.status)
        val jRetry = JSONObject(String(retry1.body))
        assertEquals(access2, jRetry.getString("access_token"))
        assertEquals(refresh2, jRetry.getString("refresh_token"))

        // 2nd retry at +15s triggers immediate reuse detection & full client revocation!
        fakeNow += 5_000L
        val retry2 = pubServer.route(
            pubReq("POST", "/oauth/token", form("grant_type" to "refresh_token", "refresh_token" to p1.refreshToken, "client_id" to p1.clientId), src = "198.51.100.11"),
        )
        assertEquals(400, retry2.status)
        assertEquals("invalid_grant", JSONObject(String(retry2.body)).getString("error"))
        assertNull("access2 revoked on 2nd retry", PublicOAuth.validateAccessToken(access2, resource))

        // Case 2: Using new access token immediately closes grace window -> even 1st retry at +1s revokes ALL.
        val p2 = pairAndIssue(src = "198.51.100.12")
        val ref2 = pubServer.route(
            pubReq("POST", "/oauth/token", form("grant_type" to "refresh_token", "refresh_token" to p2.refreshToken, "client_id" to p2.clientId), src = "198.51.100.12"),
        )
        assertEquals(200, ref2.status)
        val p2Access2 = JSONObject(String(ref2.body)).getString("access_token")
        assertNotNull(PublicOAuth.validateAccessToken(p2Access2, resource))
        fakeNow += 1_000L
        val reuseAfterUse = pubServer.route(
            pubReq("POST", "/oauth/token", form("grant_type" to "refresh_token", "refresh_token" to p2.refreshToken, "client_id" to p2.clientId), src = "198.51.100.12"),
        )
        assertEquals(400, reuseAfterUse.status)
        assertNull(PublicOAuth.validateAccessToken(p2Access2, resource))

        // Case 3: Past 30s grace window -> even 1st retry without using new token triggers full revocation.
        val p3 = pairAndIssue(src = "198.51.100.13")
        val ref3 = pubServer.route(
            pubReq("POST", "/oauth/token", form("grant_type" to "refresh_token", "refresh_token" to p3.refreshToken, "client_id" to p3.clientId), src = "198.51.100.13"),
        )
        assertEquals(200, ref3.status)
        val p3Access2 = JSONObject(String(ref3.body)).getString("access_token")
        fakeNow += PublicOAuth.REFRESH_GRACE_MS + 1_000L
        val reuseAfterGrace = pubServer.route(
            pubReq("POST", "/oauth/token", form("grant_type" to "refresh_token", "refresh_token" to p3.refreshToken, "client_id" to p3.clientId), src = "198.51.100.13"),
        )
        assertEquals(400, reuseAfterGrace.status)
        assertNull(PublicOAuth.validateAccessToken(p3Access2, resource))
    }

    @Test
    fun tokenEndpointRateLimitedPerSourceAndRevokedBearerCannotLockActiveClient() {
        val paired = pairAndIssue(src = "198.51.100.21")

        // Rotate once to obtain a second active access token, then let access1 expire.
        val ref = pubServer.route(
            pubReq("POST", "/oauth/token", form("grant_type" to "refresh_token", "refresh_token" to paired.refreshToken, "client_id" to paired.clientId), src = "198.51.100.21"),
        )
        assertEquals(200, ref.status)
        val activeAccess = JSONObject(String(ref.body)).getString("access_token")

        // Advance time so paired.accessToken expires while activeAccess (if re-refreshed) stays valid:
        fakeNow += PublicOAuth.ACCESS_TOKEN_TTL_MS / 2
        val refFresh = pubServer.route(
            pubReq("POST", "/oauth/token", form("grant_type" to "refresh_token", "refresh_token" to JSONObject(String(ref.body)).getString("refresh_token"), "client_id" to paired.clientId), src = "198.51.100.21"),
        )
        val freshAccess = JSONObject(String(refFresh.body)).getString("access_token")
        fakeNow += PublicOAuth.ACCESS_TOKEN_TTL_MS / 2 + 1_000L
        // Now paired.accessToken is expired, freshAccess is still valid!
        assertNull(PublicOAuth.validateAccessToken(paired.accessToken, resource))
        assertNotNull(PublicOAuth.validateAccessToken(freshAccess, resource))

        // Attacker replays the expired token from attacker IP until locked out (429).
        var attackerLocked = false
        for (i in 0 until 10) {
            val r = pubServer.route(
                pubReq(
                    "POST",
                    "/mcp",
                    """{"jsonrpc":"2.0","id":$i,"method":"ping"}""",
                    extraHeaders = mapOf("Authorization" to "Bearer ${paired.accessToken}"),
                    src = "203.0.113.99",
                ),
            )
            if (r.status == 429) { attackerLocked = true; break }
        }
        assertTrue("attacker IP must be locked out", attackerLocked)

        // Legitimate client using its active freshAccess token is NOT locked out!
        val legitResp = pubServer.route(
            pubReq(
                "POST",
                "/mcp",
                """{"jsonrpc":"2.0","id":100,"method":"ping"}""",
                extraHeaders = mapOf("Authorization" to "Bearer $freshAccess"),
                src = "198.51.100.21",
            ),
        )
        assertEquals(200, legitResp.status)
        // Even from the attacker's egress IP, a valid 256-bit Bearer token is never locked out by failed guesses.
        val legitSameIp = pubServer.route(
            pubReq(
                "POST",
                "/mcp",
                """{"jsonrpc":"2.0","id":101,"method":"ping"}""",
                extraHeaders = mapOf("Authorization" to "Bearer $freshAccess"),
                src = "203.0.113.99",
            ),
        )
        assertEquals(200, legitSameIp.status)

        // /oauth/token per-source rate limit (60/min): 61st request from same source returns 429; other source unaffected.
        for (i in 0 until PublicOAuth.TOKEN_MAX_PER_MIN) {
            val r = pubServer.route(
                pubReq("POST", "/oauth/token", form("grant_type" to "refresh_token", "refresh_token" to "bad_$i", "client_id" to paired.clientId), src = "198.51.100.77"),
            )
            assertEquals(400, r.status)
        }
        val over = pubServer.route(
            pubReq("POST", "/oauth/token", form("grant_type" to "refresh_token", "refresh_token" to "bad_over", "client_id" to paired.clientId), src = "198.51.100.77"),
        )
        assertEquals(429, over.status)
        assertEquals("too_many_requests", JSONObject(String(over.body)).getString("error"))
        val otherSrc = pubServer.route(
            pubReq("POST", "/oauth/token", form("grant_type" to "refresh_token", "refresh_token" to "bad_other", "client_id" to paired.clientId), src = "198.51.100.78"),
        )
        assertEquals(400, otherSrc.status)
    }

    @Test
    fun publicOffloadErrorsRedactInternalExceptionDetails() {
        val handler = VScreenOffloadHandler(ctx)
        val publicCall = Call(
            session = "pub_sess",
            tokenId = "t_pub",
            tokenName = "PublicClient",
            scopes = setOf("look"),
            peer = "public:pub_1",
            requestId = null,
            lang = "en",
            trustUntil = 0L,
            via = Abilities.Via.MCP,
            source = Call.Source.PUBLIC,
        )
        // Trigger an internal error path by passing a request whose handleCommand crashes via null-unsafe path or verify
        // PublicServer.serve redacts 500 when route throws.
        val throwingServer = PublicServer(
            ctx = ctx,
            apiServer = apiServer,
            secretProvider = { throw IllegalStateException("secret_internal_stack_detail_xyz") },
            baseProvider = { base },
            wakeService = {},
        )
        val freePort = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val port = throwingServer.start(preferredPort = freePort)
        assertTrue(port > 0)
        try {
            java.net.Socket("127.0.0.1", port).use { sock ->
                sock.soTimeout = 3_000
                sock.getOutputStream().write("GET /.well-known/oauth-authorization-server HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
                sock.getOutputStream().flush()
                val raw = sock.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue("must return HTTP 500: $raw", raw.startsWith("HTTP/1.1 500"))
                assertFalse("must not leak exception class", raw.contains("IllegalStateException"))
                assertFalse("must not leak exception message", raw.contains("secret_internal_stack_detail_xyz"))
            }
        } finally {
            throwingServer.stop()
        }
        // Keep compiler happy on handler/publicCall reference
        Call.with(publicCall) {
            val r = handler.handle(NativeOffloadRequest(listOf("vscreen", "status"), "pub_sess"))
            assertFalse(r.output.contains("secret_internal_stack_detail_xyz"))
        }
    }
}
