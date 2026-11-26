package com.kilon.agentvscreen.api

import android.content.Context
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.host.Risk
import com.kilon.agentvscreen.host.StrongConfirm
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0-2 regression tests: public 24-hour idle auto-close must only reset on authenticated requests
 * (valid Bearer on `/mcp` or HTTP 200 `/oauth/token`), never on public scans, discovery probes,
 * 404/405/401/429 responses, or unauthenticated OAuth traffic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublicIdleTimeoutTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var apiServer: ApiServer
    private lateinit var pubServer: PublicServer
    private val secret = "idle-timeout-secret"
    private val base = "https://phone.tailnet.ts.net"
    private val resource = "$base/mcp"
    private var fakeNow = 1_750_000_000_000L
    private val activityTicks = AtomicInteger(0)

    @Before
    fun setUp() {
        I18n.choice = I18n.Lang.ZH
        ApiConfig.init(ctx)
        Tokens.init(ctx)
        Audit.init(ctx)
        Audit.clear()
        Risk.resetForTests()
        Risk.clock = { fakeNow }
        StrongConfirm.resetForTests(tmp.newFile("strong_confirm.json"))
        StrongConfirm.postNotification = false
        StrongConfirm.clock = { fakeNow }
        PublicOAuth.resetForTests(tmp.newFile("public_oauth.json"))
        PublicOAuth.clock = { fakeNow }
        PublicOAuth.deviceSecureCheck = { true }
        PublicAccess.resetForTests()
        PublicAccess.clock = { fakeNow }
        PublicAccess.postNotifications = false
        PublicAccess.startServerOnEnable = false
        activityTicks.set(0)
        apiServer = ApiServer(ctx)
        apiServer.failLimiter.clock = { fakeNow }
        apiServer.failLimiter.sleepFn = {}
        pubServer = PublicServer(
            ctx = ctx,
            apiServer = apiServer,
            secretProvider = { secret },
            baseProvider = { base },
            wakeService = {},
            onAuthenticatedActivity = {
                activityTicks.incrementAndGet()
                PublicAccess.noteActivity()
            },
        )
    }

    @After
    fun tearDown() {
        I18n.choice = I18n.Lang.ZH
        PublicAccess.resetForTests()
        PublicOAuth.resetForTests()
        StrongConfirm.resetForTests()
        Risk.resetForTests()
    }

    private fun req(
        method: String,
        target: String,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
        src: String = "198.51.100.42",
    ): ApiServer.Req {
        val h = LinkedHashMap<String, String>()
        h["host"] = "phone.tailnet.ts.net"
        h["x-avs-public"] = secret
        h["x-avs-public-src"] = src
        for ((k, v) in headers) h[k.lowercase()] = v
        val q = if ('?' in target) ApiServer.parseForm(target.substringAfter('?')) else emptyMap()
        return ApiServer.Req(method, target.substringBefore('?'), q, h, body.toByteArray(Charsets.UTF_8)).apply {
            peer = InetAddress.getLoopbackAddress()
        }
    }

    private fun formEncode(vararg kv: Pair<String, String>): String =
        kv.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

    @Test
    fun unauthenticatedScansAndFailedRequestsNeverExtend24hIdleTimer() {
        val enabledAt = fakeNow
        PublicAccess.enable(ctx)
        assertTrue(PublicAccess.enabled)
        assertEquals(enabledAt, PublicAccess.lastActivityAt)

        // Advance 12 hours and send a variety of unauthenticated/scanner requests (Funnel & Cloudflare paths).
        fakeNow += 12L * 60 * 60_000L

        assertEquals(200, pubServer.route(req("GET", "/.well-known/oauth-protected-resource")).status)
        assertEquals(200, pubServer.route(req("GET", "/.well-known/oauth-protected-resource/mcp")).status)
        assertEquals(200, pubServer.route(req("GET", "/.well-known/oauth-authorization-server")).status)
        assertEquals(404, pubServer.route(req("GET", "/wp-login.php")).status)
        assertEquals(405, pubServer.route(req("GET", "/mcp")).status)
        assertEquals(401, pubServer.route(req("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"initialize"}""")).status)
        assertEquals(401, pubServer.route(req("POST", "/mcp", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", mapOf("Authorization" to "Bearer bogus"))).status)

        val regBody = JSONObject()
            .put("client_name", "Scanner")
            .put("redirect_uris", JSONArray().put("https://scanner.example/cb"))
            .put("token_endpoint_auth_method", "none")
            .toString()
        val regResp = pubServer.route(req("POST", "/oauth/register", regBody, src = "198.51.100.77"))
        assertEquals(201, regResp.status)
        val cid = JSONObject(String(regResp.body)).getString("client_id")
        val challenge = PublicOAuth.pkceS256Challenge("s".repeat(48))
        val authQuery = formEncode(
            "response_type" to "code",
            "client_id" to cid,
            "redirect_uri" to "https://scanner.example/cb",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "resource" to resource,
        )
        assertEquals(200, pubServer.route(req("GET", "/oauth/authorize?$authQuery", src = "198.51.100.77")).status)
        assertEquals(400, pubServer.route(req("POST", "/oauth/token", formEncode("grant_type" to "authorization_code", "code" to "bad", "redirect_uri" to "https://scanner.example/cb", "client_id" to cid, "code_verifier" to "s".repeat(48)))).status)

        // Cloudflare tunnel path probes must also not extend idle timer.
        val cfReq = pubServer.fromCloudflare(
            ApiServer.Req("GET", "/.well-known/oauth-protected-resource", emptyMap(), mapOf("host" to "phone.example.com", "cf-connecting-ip" to "203.0.113.5"), ByteArray(0)),
        )
        assertEquals(200, pubServer.route(cfReq).status)

        assertEquals("unauthenticated traffic must not call onAuthenticatedActivity", 0, activityTicks.get())
        assertEquals("lastActivityAt must stay at enable time", enabledAt, PublicAccess.lastActivityAt)

        // Advance another 12h + 1ms (> 24h since enable, only 12h since scans) -> MUST auto-close!
        fakeNow += 12L * 60 * 60_000L + 1L
        assertTrue("checkIdle must auto-close after 24h without authenticated traffic", PublicAccess.checkIdle(ctx))
        assertFalse("PublicAccess must be disabled after idle timeout", PublicAccess.enabled)
    }

    @Test
    fun authenticatedTokenExchangeAndMcpRequestsExtend24hIdleTimer() {
        PublicAccess.enable(ctx)
        var lastApprove: ((String) -> Unit)? = null
        PublicOAuth.pairingPresenterOverride = { _, pairing, onApprove, _ ->
            lastApprove = onApprove
            "idle_${pairing.id}"
        }

        // Advance 12 hours, then complete a legitimate OAuth pairing + token exchange.
        fakeNow += 12L * 60 * 60_000L
        val regBody = JSONObject()
            .put("client_name", "LegitAgent")
            .put("redirect_uris", JSONArray().put("https://legit.example/cb"))
            .put("token_endpoint_auth_method", "none")
            .toString()
        val cid = JSONObject(String(pubServer.route(req("POST", "/oauth/register", regBody)).body)).getString("client_id")
        val verifier = "v".repeat(48)
        val challenge = PublicOAuth.pkceS256Challenge(verifier)
        val authQuery = formEncode(
            "response_type" to "code",
            "client_id" to cid,
            "redirect_uri" to "https://legit.example/cb",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "resource" to resource,
        )
        assertEquals(200, pubServer.route(req("GET", "/oauth/authorize?$authQuery")).status)
        val pairing = PublicOAuth.latestPendingPairingForTests()!!
        assertTrue(PublicOAuth.claimPairingByCode(ctx, pairing.code, openActivity = false) is PublicOAuth.ClaimResult.Opened)
        lastApprove!!.invoke("look")

        val stJson = JSONObject(String(pubServer.route(req("GET", "/oauth/authorize/status?id=${pairing.id}")).body))
        val code = ApiServer.parseForm(stJson.getString("redirect_uri").substringAfter('?'))["code"]!!.first()
        assertEquals("activity must still be 0 before token exchange", 0, activityTicks.get())

        val tokTime = fakeNow
        val tokResp = pubServer.route(
            req(
                "POST",
                "/oauth/token",
                formEncode(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to "https://legit.example/cb",
                    "client_id" to cid,
                    "code_verifier" to verifier,
                    "resource" to resource,
                ),
            ),
        )
        assertEquals(200, tokResp.status)
        val accessToken = JSONObject(String(tokResp.body)).getString("access_token")
        assertEquals("HTTP 200 /oauth/token must note authenticated activity", 1, activityTicks.get())
        assertEquals(tokTime, PublicAccess.lastActivityAt)

        // Advance +18h (30h since enable, 18h since token issue) -> still active!
        fakeNow += 18L * 60 * 60_000L
        assertFalse("must stay enabled 18h after token exchange", PublicAccess.checkIdle(ctx))
        assertTrue(PublicAccess.enabled)

        // Make a valid authenticated MCP call (refresh token is NOT needed yet if we check before 1h or refresh first).
        // Access token is 1h TTL, so refresh first (which also succeeds HTTP 200) and then call /mcp.
        val refreshToken = JSONObject(String(tokResp.body)).getString("refresh_token")
        val refResp = pubServer.route(
            req(
                "POST",
                "/oauth/token",
                formEncode(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to cid,
                    "resource" to resource,
                ),
            ),
        )
        assertEquals(200, refResp.status)
        val freshAccess = JSONObject(String(refResp.body)).getString("access_token")
        assertEquals(2, activityTicks.get())

        fakeNow += 10L * 60_000L
        val mcpTime = fakeNow
        val mcpResp = pubServer.route(
            req(
                "POST",
                "/mcp",
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
                mapOf("Authorization" to "Bearer $freshAccess", "Content-Type" to "application/json"),
            ),
        )
        assertEquals(200, mcpResp.status)
        assertEquals("valid Bearer /mcp must note authenticated activity", 3, activityTicks.get())
        assertEquals(mcpTime, PublicAccess.lastActivityAt)

        // Advance 23h -> still enabled; advance another 2h (>24h idle) -> auto-closed.
        fakeNow += 23L * 60 * 60_000L
        assertFalse(PublicAccess.checkIdle(ctx))
        assertTrue(PublicAccess.enabled)

        fakeNow += 2L * 60 * 60_000L
        assertTrue(PublicAccess.checkIdle(ctx))
        assertFalse(PublicAccess.enabled)
        assertTrue(accessToken.isNotBlank())
    }
}
