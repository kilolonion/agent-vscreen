package com.kilon.agentvscreen.api

import android.app.Application
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.StrongConfirm
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Unit tests for P2-2 OAuth CIMD (`CimdResolver` + `PublicOAuth` integration) and PR #66 items 02, 03, and 12:
 * - URL syntax validation (https only, non-empty path, default port 443, no IP literal/userinfo/query/fragment)
 * - SSRF IP blocking (loopback, RFC1918, link-local, CGNAT/Tailscale 100.64.0.0/10, IPv6 ULA, IPv4-mapped IPv6, NAT64)
 * - Pinned-IP TLS transport: connects strictly to validated InetAddress with original host SNI/TLS, rejects TCP failure,
 *   3xx redirects (no follow), certificate/SSL errors (fail-fast without fallback), and oversized chunked bodies (Fix 03)
 * - Per-pairing grant isolation for identical CIMD URL: independent tokenId/grantId/levels, isolated MCP tasks,
 *   and isolated revocation & refresh-token reuse revocation preserving sibling grants and shared CIMD metadata (Fix 02)
 * - Device-only CIMD and DCR persistence across process restarts (`initFile`) preserving client & grant records (Fix 12)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CimdResolverTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var tempDir: File
    private lateinit var oauthFile: File

    @Before
    fun setUp() {
        tempDir = File(ctx.filesDir, "p22-cimd-test-${System.nanoTime()}").apply { mkdirs() }
        oauthFile = File(tempDir, "oauth.json")
        ApiConfig.init(ctx)
        Tokens.init(ctx)
        Audit.init(ctx)
        McpTasks.resetForTest()
        StrongConfirm.resetForTests(File(tempDir, "approvals.json"))
        StrongConfirm.postNotification = false
        PublicOAuth.resetForTests(oauthFile)
        PublicOAuth.deviceSecureCheck = { true }
        PublicOAuth.pairingAllowedCheck = { true }
        CimdResolver.resetForTest()
    }

    @After
    fun tearDown() {
        McpTasks.resetForTest()
        CimdResolver.resetForTest()
        PublicOAuth.resetForTests()
        StrongConfirm.resetForTests()
        tempDir.deleteRecursively()
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

    private fun fakeSocket(responseWire: ByteArray, capturedOut: ByteArrayOutputStream = ByteArrayOutputStream()): Socket =
        object : Socket() {
            private val ins = ByteArrayInputStream(responseWire)
            override fun getInputStream(): InputStream = ins
            override fun getOutputStream(): OutputStream = capturedOut
            override fun setSoTimeout(timeout: Int) {}
            override fun close() {}
        }

    @Test
    fun urlSyntaxValidationRejectsUnsafeUrlsAndAcceptsValidHttpsMetadataUrl() {
        assertNotNull(CimdResolver.validateClientIdUrl("https://app.example.com/oauth/client.json"))
        assertNotNull(CimdResolver.validateClientIdUrl("https://app.example.com:443/client"))

        // Rejected forms
        assertNull(CimdResolver.validateClientIdUrl("http://app.example.com/client.json"))
        assertNull(CimdResolver.validateClientIdUrl("https://app.example.com"))
        assertNull(CimdResolver.validateClientIdUrl("https://app.example.com/"))
        assertNull(CimdResolver.validateClientIdUrl("https://user:pass@app.example.com/client.json"))
        assertNull(CimdResolver.validateClientIdUrl("https://app.example.com/client.json?x=1"))
        assertNull(CimdResolver.validateClientIdUrl("https://app.example.com/client.json#frag"))
        assertNull(CimdResolver.validateClientIdUrl("https://app.example.com:8443/client.json"))
        assertNull(CimdResolver.validateClientIdUrl("https://127.0.0.1/client.json"))
        assertNull(CimdResolver.validateClientIdUrl("https://[::1]/client.json"))
        assertNull(CimdResolver.validateClientIdUrl("https://localhost/client.json"))
        assertNull(CimdResolver.validateClientIdUrl("https://app.local/client.json"))
        assertNull(CimdResolver.validateClientIdUrl("https://app.example.com/../secret.json"))
    }

    @Test
    fun ssrfFilterBlocksAllPrivateLoopbackTailscaleLinkLocalAndUlaAddresses() {
        val forbidden = listOf(
            "127.0.0.1",
            "10.0.0.1",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.10",
            "169.254.169.254",
            "100.64.0.1",      // Tailscale / CGNAT start
            "100.127.255.254", // Tailscale / CGNAT end
            "0.0.0.0",
            "224.0.0.1",
            "::1",
            "fe80::1",
            "fc00::1",
            "fd12:3456:789a::1",
            "::ffff:127.0.0.1",
            "::ffff:100.64.0.5",
            "64:ff9b::10.0.0.1",
        )
        for (ip in forbidden) {
            assertTrue("Expected $ip to be blocked by SSRF filter", CimdResolver.isForbiddenAddress(InetAddress.getByName(ip)))
        }
        val allowed = listOf("93.184.216.34", "1.1.1.1", "8.8.8.8", "2606:2800:220:1:248:1893:25c8:1946")
        for (ip in allowed) {
            assertFalse("Expected public IP $ip to be allowed", CimdResolver.isForbiddenAddress(InetAddress.getByName(ip)))
        }
    }

    @Test
    fun resolverEnforcesRedirectsContentTypeSizeClientIdMatchCachingAndRateLimit() {
        val url = "https://client.example.com/oauth/metadata.json"
        var now = 1_000_000L
        var fetches = 0
        CimdResolver.clock = { now }
        CimdResolver.dnsResolver = { arrayOf(InetAddress.getByName("93.184.216.34")) }

        // Redirect blocked
        CimdResolver.httpFetcher = { _, _ -> CimdResolver.FetchResponse(302, "application/json", null, ByteArray(0)) }
        assertTrue(CimdResolver.resolve(url, "203.0.113.1") is CimdResolver.Result.Error)

        // Wrong Content-Type blocked
        CimdResolver.httpFetcher = { _, _ -> CimdResolver.FetchResponse(200, "text/html", null, "{}".toByteArray()) }
        assertTrue(CimdResolver.resolve(url, "203.0.113.2") is CimdResolver.Result.Error)

        // Oversized > 64KB blocked
        CimdResolver.httpFetcher = { _, _ ->
            CimdResolver.FetchResponse(200, "application/json", null, ByteArray(CimdResolver.MAX_BODY_BYTES + 1))
        }
        assertTrue(CimdResolver.resolve(url, "203.0.113.3") is CimdResolver.Result.Error)

        // client_id mismatch blocked
        val wrongIdDoc = JSONObject()
            .put("client_id", "https://other.example.com/oauth/metadata.json")
            .put("client_name", "Spoof")
            .put("redirect_uris", JSONArray().put("https://client.example.com/cb"))
            .put("token_endpoint_auth_method", "none")
        CimdResolver.httpFetcher = { _, _ ->
            CimdResolver.FetchResponse(200, "application/json; charset=utf-8", "max-age=600", wrongIdDoc.toString().toByteArray())
        }
        assertTrue(CimdResolver.resolve(url, "203.0.113.4") is CimdResolver.Result.Error)

        // Valid document resolves and caches for max-age=600s
        val validDoc = JSONObject()
            .put("client_id", url)
            .put("client_name", "Example Cloud Agent")
            .put("redirect_uris", JSONArray().put("https://client.example.com/cb"))
            .put("token_endpoint_auth_method", "none")
        CimdResolver.httpFetcher = { _, addrs ->
            fetches++
            assertEquals(listOf(InetAddress.getByName("93.184.216.34")), addrs)
            CimdResolver.FetchResponse(200, "application/json", "public, max-age=600", validDoc.toString().toByteArray())
        }
        val ok1 = CimdResolver.resolve(url, "203.0.113.5") as CimdResolver.Result.Ok
        assertEquals("client.example.com", ok1.doc.verifiedDomain)
        assertEquals("Example Cloud Agent", ok1.doc.clientName)
        assertEquals(1, fetches)

        // Cache hit does not refetch
        now += 300_000L
        val ok2 = CimdResolver.resolve(url, "203.0.113.5") as CimdResolver.Result.Ok
        assertEquals("client.example.com", ok2.doc.verifiedDomain)
        assertEquals(1, fetches)

        // After expiry, refetches
        now += 400_000L
        assertTrue(CimdResolver.resolve(url, "203.0.113.5") is CimdResolver.Result.Ok)
        assertEquals(2, fetches)

        // Cache-Control max-age capped at 24h
        assertEquals(CimdResolver.MAX_CACHE_TTL_MS, CimdResolver.parseCacheTtlMs("max-age=99999999"))
        assertEquals(0L, CimdResolver.parseCacheTtlMs("no-store"))

        // Per-source rate limit (10/h)
        CimdResolver.httpFetcher = { _, _ -> CimdResolver.FetchResponse(404, "application/json", null, ByteArray(0)) }
        for (i in 1..CimdResolver.MAX_FETCH_PER_HOUR) {
            val r = CimdResolver.resolve("https://rl$i.example.com/client.json", "198.51.100.9") as CimdResolver.Result.Error
            assertEquals(400, r.httpStatus)
        }
        val limited = CimdResolver.resolve("https://rl99.example.com/client.json", "198.51.100.9") as CimdResolver.Result.Error
        assertEquals(429, limited.httpStatus)
    }

    // ── Fix 03: Pinned-IP TLS transport over defaultFetch ────────────────────

    @Test
    fun pinnedTlsTransportDialsValidatedIpWithOriginalHostSniAndRejectsFailureRedirectTlsAndOversize() {
        val url = "https://pinned.example.com/oauth/client.json"
        val validJson = JSONObject()
            .put("client_id", url)
            .put("client_name", "Pinned Client")
            .put("redirect_uris", JSONArray().put("https://pinned.example.com/cb"))
            .put("token_endpoint_auth_method", "none")
            .toString()
        val ip1 = InetAddress.getByName("93.184.216.34")
        val ip2 = InetAddress.getByName("93.184.216.35")

        // 1. Valid chunked response via REAL defaultFetch -> asserts exact pinned IP + SNI host + Host header
        val dialed = ArrayList<Triple<InetAddress, String, Int>>()
        val sentWire = ByteArrayOutputStream()
        val bodyBytes = validJson.toByteArray(Charsets.UTF_8)
        val chunkedWire = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "${bodyBytes.size.toString(16)}\r\n" +
                validJson + "\r\n" +
                "0\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        CimdResolver.dnsResolver = { arrayOf(ip1) }
        CimdResolver.tlsSocketOpener = { addr, host, port, _ ->
            dialed += Triple(addr, host, port)
            fakeSocket(chunkedWire, sentWire)
        }
        val ok = CimdResolver.resolve(url, "203.0.113.10") as CimdResolver.Result.Ok
        assertEquals("pinned.example.com", ok.doc.verifiedDomain)
        assertEquals(listOf(Triple(ip1, "pinned.example.com", 443)), dialed)
        val reqSent = sentWire.toString("ISO-8859-1")
        assertTrue(reqSent.startsWith("GET /oauth/client.json HTTP/1.1\r\n"))
        assertTrue(reqSent.contains("Host: pinned.example.com\r\n"))

        // 2. TCP connect failure across all validated IPs -> Error
        CimdResolver.resetForTest()
        val failedDialed = ArrayList<InetAddress>()
        CimdResolver.dnsResolver = { arrayOf(ip1, ip2) }
        CimdResolver.tlsSocketOpener = { addr, _, _, _ ->
            failedDialed += addr
            throw IOException("connection refused")
        }
        val connErr = CimdResolver.resolve(url, "203.0.113.11")
        assertTrue(connErr is CimdResolver.Result.Error)
        assertEquals(listOf(ip1, ip2), failedDialed)

        // 3. 3xx Redirect -> rejected without opening any second socket
        CimdResolver.resetForTest()
        var redirectDials = 0
        val redirectWire = (
            "HTTP/1.1 302 Found\r\n" +
                "Location: https://127.0.0.1/evil.json\r\n" +
                "Content-Length: 0\r\n\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        CimdResolver.dnsResolver = { arrayOf(ip1) }
        CimdResolver.tlsSocketOpener = { _, _, _, _ ->
            redirectDials++
            fakeSocket(redirectWire)
        }
        val redirErr = CimdResolver.resolve(url, "203.0.113.12")
        assertTrue(redirErr is CimdResolver.Result.Error)
        assertEquals("Redirect must never be followed", 1, redirectDials)

        // 4. TLS / certificate error -> fails fast on first address without fallback
        CimdResolver.resetForTest()
        var tlsDials = 0
        CimdResolver.dnsResolver = { arrayOf(ip1, ip2) }
        CimdResolver.tlsSocketOpener = { _, _, _, _ ->
            tlsDials++
            throw SSLPeerUnverifiedException("certificate hostname mismatch")
        }
        val tlsErr = CimdResolver.resolve(url, "203.0.113.13")
        assertTrue(tlsErr is CimdResolver.Result.Error)
        assertEquals("SSLException must fail fast without retrying alternate IP", 1, tlsDials)

        // 5. Oversized (> 64 KB) chunked response -> rejected
        CimdResolver.resetForTest()
        val hugeChunk = "a".repeat(CimdResolver.MAX_BODY_BYTES + 16)
        val hugeWire = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "${hugeChunk.length.toString(16)}\r\n" +
                hugeChunk + "\r\n0\r\n\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        CimdResolver.dnsResolver = { arrayOf(ip1) }
        CimdResolver.tlsSocketOpener = { _, _, _, _ -> fakeSocket(hugeWire) }
        assertTrue(CimdResolver.resolve(url, "203.0.113.14") is CimdResolver.Result.Error)
    }

    @Test
    fun publicOAuthAdvertisesCimdAndPropagatesVerifiedDomainToStrongConfirm() {
        val metaResp = PublicOAuth.authorizationServerMetadata("https://phone.example.com")
        val meta = JSONObject(String(metaResp.body, Charsets.UTF_8))
        assertTrue(meta.getBoolean("client_id_metadata_document_supported"))
        assertTrue(meta.has("registration_endpoint"))

        val cimdUrl = "https://mcp.trusted.example/oauth/client.json"
        val doc = JSONObject()
            .put("client_id", cimdUrl)
            .put("client_name", "Trusted Agent")
            .put("redirect_uris", JSONArray().put("https://mcp.trusted.example/callback"))
            .put("token_endpoint_auth_method", "none")
        CimdResolver.dnsResolver = { arrayOf(InetAddress.getByName("93.184.216.34")) }
        CimdResolver.httpFetcher = { _, _ ->
            CimdResolver.FetchResponse(200, "application/json", "max-age=3600", doc.toString().toByteArray())
        }

        val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        val authReq = ApiServer.Req(
            "GET",
            "/oauth/authorize",
            mapOf(
                "response_type" to listOf("code"),
                "client_id" to listOf(cimdUrl),
                "redirect_uri" to listOf("https://mcp.trusted.example/callback"),
                "code_challenge_method" to listOf("S256"),
                "code_challenge" to listOf(challenge),
                "scope" to listOf("operate"),
            ),
            mapOf("x-avs-public-src" to "203.0.113.42"),
            ByteArray(0),
        )
        val authResp = PublicOAuth.handleAuthorize(ctx, authReq, "https://phone.example.com")
        assertEquals(200, authResp.status)

        // Extract pairing code from HTML and claim on phone
        val html = String(authResp.body, Charsets.UTF_8)
        val codeMatch = Regex("[A-Z2-9]{4}-[A-Z2-9]{4}").find(html)
        assertNotNull("Expected pairing code in authorize HTML", codeMatch)

        val claim = PublicOAuth.claimPairingByCode(ctx, codeMatch!!.value, openActivity = false)
        assertTrue(claim is PublicOAuth.ClaimResult.Opened)
        val confirmId = (claim as PublicOAuth.ClaimResult.Opened).confirmId
        val req = StrongConfirm.byId(confirmId)
        assertNotNull(req)
        assertEquals("mcp.trusted.example", req!!.clientVerifiedDomain)
    }

    // ── Fix 02: Per-pairing grant isolation for identical CIMD URL ───────────

    @Test
    fun twoIndependentPairingsWithSameCimdUrlHaveIsolatedTokensLevelsTasksAndRevocation() {
        val base = "https://phone.example.com"
        val resource = "$base/mcp"
        val cimdUrl = "https://mcp.shared.example/oauth/client.json"
        val redirect = "https://mcp.shared.example/callback"
        val doc = JSONObject()
            .put("client_id", cimdUrl)
            .put("client_name", "Shared Cloud Software")
            .put("redirect_uris", JSONArray().put(redirect))
            .put("token_endpoint_auth_method", "none")
        CimdResolver.dnsResolver = { arrayOf(InetAddress.getByName("93.184.216.34")) }
        CimdResolver.httpFetcher = { _, _ ->
            CimdResolver.FetchResponse(200, "application/json", "max-age=3600", doc.toString().toByteArray())
        }

        var fakeNow = 1_900_000_000_000L
        PublicOAuth.clock = { fakeNow }

        data class IssuedPairing(
            val accessToken: String,
            val refreshToken: String,
            val auth: PublicOAuth.AccessAuth,
        )

        fun pairCimd(level: String, src: String, verifier: String): IssuedPairing {
            var approveFn: ((String) -> Unit)? = null
            PublicOAuth.pairingPresenterOverride = { _, _, onApprove, _ ->
                approveFn = onApprove
                "cfm_${System.nanoTime()}"
            }
            val challenge = PublicOAuth.pkceS256Challenge(verifier)
            val authReq = ApiServer.Req(
                "GET",
                "/oauth/authorize",
                mapOf(
                    "response_type" to listOf("code"),
                    "client_id" to listOf(cimdUrl),
                    "redirect_uri" to listOf(redirect),
                    "code_challenge_method" to listOf("S256"),
                    "code_challenge" to listOf(challenge),
                    "resource" to listOf(resource),
                    "scope" to listOf("operate"),
                ),
                mapOf("x-avs-public-src" to src),
                ByteArray(0),
            )
            assertEquals(200, PublicOAuth.handleAuthorize(ctx, authReq, base).status)
            val pairing = PublicOAuth.latestPendingPairingForTests()!!
            assertTrue(PublicOAuth.claimPairingByCode(ctx, pairing.code, openActivity = false) is PublicOAuth.ClaimResult.Opened)
            approveFn!!.invoke(level)

            val stResp = PublicOAuth.handleAuthorizeStatus(
                ApiServer.Req("GET", "/oauth/authorize/status", mapOf("id" to listOf(pairing.id)), mapOf("x-avs-public-src" to src), ByteArray(0)),
            )
            val redirUri = JSONObject(String(stResp.body, Charsets.UTF_8)).getString("redirect_uri")
            val code = HttpCore.parseForm(java.net.URI(redirUri).rawQuery.orEmpty())["code"]!!.first()
            val tokResp = PublicOAuth.handleToken(
                ApiServer.Req(
                    "POST",
                    "/oauth/token",
                    emptyMap(),
                    mapOf("x-avs-public-src" to src, "content-type" to "application/x-www-form-urlencoded"),
                    form(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to redirect,
                        "client_id" to cimdUrl,
                        "code_verifier" to verifier,
                        "resource" to resource,
                    ).toByteArray(Charsets.UTF_8),
                ),
                base,
            )
            assertEquals(200, tokResp.status)
            val tj = JSONObject(String(tokResp.body, Charsets.UTF_8))
            val at = tj.getString("access_token")
            val rt = tj.getString("refresh_token")
            val check = PublicOAuth.inspectAccessToken(at, resource) as PublicOAuth.AccessCheck.Valid
            return IssuedPairing(at, rt, check.auth)
        }

        // Pairing A: approved with LOOK only; Pairing B: approved with FREE (operate).
        val pairingA = pairCimd(Tokens.LOOK, "203.0.113.101", "a".repeat(64))
        val pairingB = pairCimd(Tokens.FREE, "203.0.113.102", "b".repeat(64))

        // Shared CIMD client metadata stays unbound (`tokenId == null`) while each pairing owns an independent token & grant.
        assertNull(PublicOAuth.registeredClient(cimdUrl)!!.tokenId)
        assertNotEquals("Independent CIMD pairings must get distinct tokenIds", pairingA.auth.token.id, pairingB.auth.token.id)
        assertNotEquals("Independent CIMD pairings must get distinct grantIds", pairingA.auth.grantId, pairingB.auth.grantId)
        assertEquals(2, PublicOAuth.grantsForClient(cimdUrl).size)

        // Approving Pairing B with FREE must NOT elevate Pairing A's level or scope!
        assertEquals(Tokens.LOOK, Tokens.byId(pairingA.auth.token.id)!!.level)
        assertEquals("look", pairingA.auth.scope)
        assertEquals(Tokens.FREE, Tokens.byId(pairingB.auth.token.id)!!.level)
        assertEquals("operate", pairingB.auth.scope)

        // MCP tasks ownership strictly isolated between Pairing A and Pairing B!
        val apiServer = ApiServer(ctx)
        val (cfmA, _) = StrongConfirm.getOrCreateRequest(
            risk = com.kilon.agentvscreen.host.Risk.HIGH_RISK,
            key = "HIGH_RISK:${pairingA.auth.token.id}:t1",
            tokenId = pairingA.auth.token.id,
            tokenName = pairingA.auth.token.name,
            tokenScope = "look",
            client = com.kilon.agentvscreen.host.Risk.CLIENT_ANY,
            commandName = "pay",
            detail = "pairA",
            validMs = 60_000L,
            reusable = false,
            allowHold = false,
            nowMs = fakeNow,
        )
        fun pubMcpReq(p: IssuedPairing, body: String, hdrs: Map<String, String> = emptyMap()): ApiServer.Req =
            ApiServer.Req("POST", "/mcp", emptyMap(), hdrs.mapKeys { it.key.lowercase() }, body.toByteArray(Charsets.UTF_8)).apply {
                auth = ApiServer.Auth(p.auth.token, signed = false, nonce = null)
                viaPublic = true
                publicClientId = p.auth.clientId
                publicGrantId = p.auth.grantId
                publicScope = p.auth.scope
            }

        val wrappedA = McpTasks.maybeWrap(
            pubMcpReq(pairingA, "{}"),
            listOf("act", "tap Pay"),
            "s1",
            NativeOffloadResult(1, "pending\n", "CONFIRM_PENDING", data = JSONObject().put("confirm", JSONObject().put("id", cfmA.id))),
        )!!
        val taskIdA = wrappedA.getString("taskId")
        val tasksMeta = JSONObject().put(
            McpProtocol.META_CLIENT_CAPABILITIES,
            JSONObject().put("extensions", JSONObject().put(McpProtocol.TASKS_EXTENSION, JSONObject())),
        )

        // Pairing A sees its own task; Pairing B cannot list, get, or cancel Pairing A's task.
        val listBody = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tasks/list").put("params", JSONObject().put("_meta", tasksMeta)).toString()
        val listA = JSONObject(String(Mcp.handle(apiServer, pubMcpReq(pairingA, listBody, mapOf("MCP-Protocol-Version" to "2026-07-28"))).body))
            .getJSONObject("result").getJSONArray("tasks")
        val listB = JSONObject(String(Mcp.handle(apiServer, pubMcpReq(pairingB, listBody, mapOf("MCP-Protocol-Version" to "2026-07-28"))).body))
            .getJSONObject("result").getJSONArray("tasks")
        assertEquals(1, listA.length())
        assertEquals(0, listB.length())

        val getBody = JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tasks/get").put("params", JSONObject().put("taskId", taskIdA).put("_meta", tasksMeta)).toString()
        val getB = JSONObject(String(Mcp.handle(apiServer, pubMcpReq(pairingB, getBody, mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "tasks/get", "Mcp-Name" to taskIdA))).body))
        assertTrue("Pairing B must not read Pairing A's task", getB.has("error"))

        val cancelBody = JSONObject().put("jsonrpc", "2.0").put("id", 3).put("method", "tasks/cancel").put("params", JSONObject().put("taskId", taskIdA).put("_meta", tasksMeta)).toString()
        val cancelB = JSONObject(String(Mcp.handle(apiServer, pubMcpReq(pairingB, cancelBody, mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "tasks/cancel", "Mcp-Name" to taskIdA))).body))
        assertTrue("Pairing B must not cancel Pairing A's task", cancelB.has("error"))

        // Trigger refresh-token reuse revocation on Pairing A (rotate once, advance past 30s grace, replay old refresh token).
        val rotA = PublicOAuth.handleToken(
            ApiServer.Req(
                "POST", "/oauth/token", emptyMap(), mapOf("x-avs-public-src" to "203.0.113.101"),
                form("grant_type" to "refresh_token", "refresh_token" to pairingA.refreshToken, "client_id" to cimdUrl, "resource" to resource).toByteArray(),
            ),
            base,
        )
        assertEquals(200, rotA.status)
        val newAccessA = JSONObject(String(rotA.body)).getString("access_token")
        fakeNow += 60_000L
        val reuseA = PublicOAuth.handleToken(
            ApiServer.Req(
                "POST", "/oauth/token", emptyMap(), mapOf("x-avs-public-src" to "203.0.113.101"),
                form("grant_type" to "refresh_token", "refresh_token" to pairingA.refreshToken, "client_id" to cimdUrl, "resource" to resource).toByteArray(),
            ),
            base,
        )
        assertEquals(400, reuseA.status)

        // Pairing A is revoked, while Pairing B's access token, refresh token, AND shared CIMD client stay completely intact!
        assertFalse(PublicOAuth.inspectAccessToken(newAccessA, resource) is PublicOAuth.AccessCheck.Valid)
        assertTrue("Sibling Pairing B access token must stay valid", PublicOAuth.inspectAccessToken(pairingB.accessToken, resource) is PublicOAuth.AccessCheck.Valid)
        val rotB = PublicOAuth.handleToken(
            ApiServer.Req(
                "POST", "/oauth/token", emptyMap(), mapOf("x-avs-public-src" to "203.0.113.102"),
                form("grant_type" to "refresh_token", "refresh_token" to pairingB.refreshToken, "client_id" to cimdUrl, "resource" to resource).toByteArray(),
            ),
            base,
        )
        assertEquals("Sibling Pairing B refresh token must still work", 200, rotB.status)
        assertNotNull("Shared CIMD client metadata must survive sibling grant revocation", PublicOAuth.registeredClient(cimdUrl))
        assertEquals(1, PublicOAuth.grantsForClient(cimdUrl).size)
    }

    // ── Fix 12: Device-only CIMD & DCR persistence across process restarts ───

    @Test
    fun deviceOnlyCimdAndDcrClientsSurviveProcessRestartWithGrantsAndRefreshIntact() {
        val base = "https://phone.example.com"
        val resource = "$base/mcp"
        var fakeNow = 1_950_000_000_000L
        PublicOAuth.clock = { fakeNow }

        // 1. Device-only CIMD client (no redirect_uris, device_code grant only)
        val cimdDeviceUrl = "https://cli.deviceonly.example/oauth/client.json"
        val cimdDoc = JSONObject()
            .put("client_id", cimdDeviceUrl)
            .put("client_name", "Headless CIMD CLI")
            .put("grant_types", JSONArray().put(PublicOAuth.DEVICE_GRANT).put("refresh_token"))
            .put("token_endpoint_auth_method", "none")
        CimdResolver.dnsResolver = { arrayOf(InetAddress.getByName("93.184.216.34")) }
        CimdResolver.httpFetcher = { _, _ ->
            CimdResolver.FetchResponse(200, "application/json", "max-age=3600", cimdDoc.toString().toByteArray())
        }

        // 2. Device-only DCR client (no redirect_uris, device_code grant only)
        val dcrRegResp = PublicOAuth.handleRegister(
            ApiServer.Req(
                "POST",
                "/oauth/register",
                emptyMap(),
                mapOf("x-avs-public-src" to "203.0.113.200"),
                JSONObject()
                    .put("client_name", "Headless DCR CLI")
                    .put("grant_types", JSONArray().put(PublicOAuth.DEVICE_GRANT).put("refresh_token"))
                    .put("token_endpoint_auth_method", "none")
                    .toString().toByteArray(Charsets.UTF_8),
            ),
        )
        assertEquals(201, dcrRegResp.status)
        val dcrClientId = JSONObject(String(dcrRegResp.body, Charsets.UTF_8)).getString("client_id")

        fun completeDeviceFlow(clientId: String, src: String): Pair<String, String> {
            var approveFn: ((String) -> Unit)? = null
            PublicOAuth.pairingPresenterOverride = { _, _, onApprove, _ ->
                approveFn = onApprove
                "cfm_dev_${System.nanoTime()}"
            }
            val devResp = PublicOAuth.handleDeviceAuthorization(
                ctx,
                ApiServer.Req(
                    "POST",
                    "/oauth/device_authorization",
                    emptyMap(),
                    mapOf("x-avs-public-src" to src),
                    form("client_id" to clientId, "resource" to resource, "scope" to "operate").toByteArray(Charsets.UTF_8),
                ),
                base,
            )
            assertEquals(200, devResp.status)
            val dj = JSONObject(String(devResp.body, Charsets.UTF_8))
            val deviceCode = dj.getString("device_code")
            val userCode = dj.getString("user_code")
            assertTrue(PublicOAuth.claimPairingByCode(ctx, userCode, openActivity = false) is PublicOAuth.ClaimResult.Opened)
            approveFn!!.invoke(Tokens.FREE)

            val tokResp = PublicOAuth.handleToken(
                ApiServer.Req(
                    "POST",
                    "/oauth/token",
                    emptyMap(),
                    mapOf("x-avs-public-src" to src),
                    form(
                        "grant_type" to PublicOAuth.DEVICE_GRANT,
                        "device_code" to deviceCode,
                        "client_id" to clientId,
                        "resource" to resource,
                    ).toByteArray(Charsets.UTF_8),
                ),
                base,
            )
            assertEquals(200, tokResp.status)
            val tj = JSONObject(String(tokResp.body, Charsets.UTF_8))
            return tj.getString("access_token") to tj.getString("refresh_token")
        }

        val (cimdAccess, cimdRefresh) = completeDeviceFlow(cimdDeviceUrl, "203.0.113.201")
        val (dcrAccess, dcrRefresh) = completeDeviceFlow(dcrClientId, "203.0.113.202")

        // Simulate process restart by reloading directly from persisted oauthFile!
        PublicOAuth.initFile(oauthFile)

        // Both device-only clients MUST survive reload with empty redirectUris, deviceOnly=true, and intact grants!
        val reloadedCimd = PublicOAuth.registeredClient(cimdDeviceUrl)
        assertNotNull("Device-only CIMD client must survive restart", reloadedCimd)
        assertTrue(reloadedCimd!!.deviceOnly)
        assertTrue(reloadedCimd.redirectUris.isEmpty())
        assertEquals(1, PublicOAuth.grantsForClient(cimdDeviceUrl).size)

        val reloadedDcr = PublicOAuth.registeredClient(dcrClientId)
        assertNotNull("Device-only DCR client must survive restart", reloadedDcr)
        assertTrue(reloadedDcr!!.deviceOnly)
        assertTrue(reloadedDcr.redirectUris.isEmpty())
        assertEquals(1, PublicOAuth.grantsForClient(dcrClientId).size)

        val allIds = PublicOAuth.listClients().map { it.clientId }.toSet()
        assertTrue(cimdDeviceUrl in allIds)
        assertTrue(dcrClientId in allIds)

        // Existing access tokens and refresh-token rotation continue working after restart!
        assertTrue(PublicOAuth.inspectAccessToken(cimdAccess, resource) is PublicOAuth.AccessCheck.Valid)
        assertTrue(PublicOAuth.inspectAccessToken(dcrAccess, resource) is PublicOAuth.AccessCheck.Valid)

        val postRestartRefreshCimd = PublicOAuth.handleToken(
            ApiServer.Req(
                "POST", "/oauth/token", emptyMap(), mapOf("x-avs-public-src" to "203.0.113.201"),
                form("grant_type" to "refresh_token", "refresh_token" to cimdRefresh, "client_id" to cimdDeviceUrl, "resource" to resource).toByteArray(),
            ),
            base,
        )
        assertEquals("Device-only CIMD refresh must succeed after restart", 200, postRestartRefreshCimd.status)

        val postRestartRefreshDcr = PublicOAuth.handleToken(
            ApiServer.Req(
                "POST", "/oauth/token", emptyMap(), mapOf("x-avs-public-src" to "203.0.113.202"),
                form("grant_type" to "refresh_token", "refresh_token" to dcrRefresh, "client_id" to dcrClientId, "resource" to resource).toByteArray(),
            ),
            base,
        )
        assertEquals("Device-only DCR refresh must succeed after restart", 200, postRestartRefreshDcr.status)
    }
}
