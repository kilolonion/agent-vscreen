package com.kilon.agentvscreen.api

import android.app.Application
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.StrongConfirm
import org.json.JSONArray
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
import java.net.InetAddress

/**
 * Unit tests for P2-2 OAuth CIMD (`CimdResolver` + `PublicOAuth` integration):
 * - URL syntax validation (https only, non-empty path, default port 443, no IP literal/userinfo/query/fragment)
 * - SSRF IP blocking (loopback, RFC1918, link-local, CGNAT/Tailscale 100.64.0.0/10, IPv6 ULA, IPv4-mapped IPv6, NAT64)
 * - Redirect rejection, 64 KB cap, JSON Content-Type check, verbatim client_id matching, token_endpoint_auth_method=none
 * - Bounded cache honoring Cache-Control max-age (capped at 24h) and per-source 10/h rate limit
 * - PublicOAuth metadata advertisement and pairing verifiedDomain propagation
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CimdResolverTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(ctx.filesDir, "p22-cimd-test-${System.nanoTime()}").apply { mkdirs() }
        ApiConfig.init(ctx)
        Tokens.init(ctx)
        Audit.init(ctx)
        StrongConfirm.resetForTests(File(tempDir, "approvals.json"))
        StrongConfirm.postNotification = false
        PublicOAuth.resetForTests(File(tempDir, "oauth.json"))
        PublicOAuth.deviceSecureCheck = { true }
        PublicOAuth.pairingAllowedCheck = { true }
        CimdResolver.resetForTest()
    }

    @After
    fun tearDown() {
        CimdResolver.resetForTest()
        PublicOAuth.resetForTests()
        StrongConfirm.resetForTests()
        tempDir.deleteRecursively()
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
        CimdResolver.httpFetcher = { CimdResolver.FetchResponse(302, "application/json", null, ByteArray(0)) }
        assertTrue(CimdResolver.resolve(url, "203.0.113.1") is CimdResolver.Result.Error)

        // Wrong Content-Type blocked
        CimdResolver.httpFetcher = { CimdResolver.FetchResponse(200, "text/html", null, "{}".toByteArray()) }
        assertTrue(CimdResolver.resolve(url, "203.0.113.2") is CimdResolver.Result.Error)

        // Oversized > 64KB blocked
        CimdResolver.httpFetcher = {
            CimdResolver.FetchResponse(200, "application/json", null, ByteArray(CimdResolver.MAX_BODY_BYTES + 1))
        }
        assertTrue(CimdResolver.resolve(url, "203.0.113.3") is CimdResolver.Result.Error)

        // client_id mismatch blocked
        val wrongIdDoc = JSONObject()
            .put("client_id", "https://other.example.com/oauth/metadata.json")
            .put("client_name", "Spoof")
            .put("redirect_uris", JSONArray().put("https://client.example.com/cb"))
            .put("token_endpoint_auth_method", "none")
        CimdResolver.httpFetcher = {
            CimdResolver.FetchResponse(200, "application/json; charset=utf-8", "max-age=600", wrongIdDoc.toString().toByteArray())
        }
        assertTrue(CimdResolver.resolve(url, "203.0.113.4") is CimdResolver.Result.Error)

        // Valid document resolves and caches for max-age=600s
        val validDoc = JSONObject()
            .put("client_id", url)
            .put("client_name", "Example Cloud Agent")
            .put("redirect_uris", JSONArray().put("https://client.example.com/cb"))
            .put("token_endpoint_auth_method", "none")
        CimdResolver.httpFetcher = {
            fetches++
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
        CimdResolver.httpFetcher = { CimdResolver.FetchResponse(404, "application/json", null, ByteArray(0)) }
        for (i in 1..CimdResolver.MAX_FETCH_PER_HOUR) {
            val r = CimdResolver.resolve("https://rl$i.example.com/client.json", "198.51.100.9") as CimdResolver.Result.Error
            assertEquals(400, r.httpStatus)
        }
        val limited = CimdResolver.resolve("https://rl99.example.com/client.json", "198.51.100.9") as CimdResolver.Result.Error
        assertEquals(429, limited.httpStatus)
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
        CimdResolver.httpFetcher = {
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
}
