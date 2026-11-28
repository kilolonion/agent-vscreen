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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/**
 * P0-3 regression tests:
 * - Remote `/oauth/authorize` & `/oauth/device_authorization` show a confirmation card right away (with source).
 * - Multi-IP flood is capped (at most MAX_PENDING_PAIRINGS_GLOBAL cards, bounded FIFO queue, open cards never evicted).
 * - `claimPairingByCode` opens a queued request's card with `sourceAddress` & default `Tokens.FREE`.
 * - Optional strict pairing window gates register/authorize/device_authorization and auto-closes on approval.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublicPairingCodeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var apiServer: ApiServer
    private lateinit var pubServer: PublicServer
    private val secret = "pairing-code-secret"
    private val base = "https://phone.tailnet.ts.net"
    private val resource = "$base/mcp"
    private var fakeNow = 1_760_000_000_000L

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
        apiServer = ApiServer(ctx)
        apiServer.failLimiter.clock = { fakeNow }
        apiServer.failLimiter.sleepFn = {}
        pubServer = PublicServer(ctx, apiServer, secretProvider = { secret }, baseProvider = { base }, wakeService = {})
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
        src: String? = "203.0.113.10",
        headers: Map<String, String> = emptyMap(),
    ): ApiServer.Req {
        val h = LinkedHashMap<String, String>()
        h["host"] = "phone.tailnet.ts.net"
        h["x-avs-public"] = secret
        if (src != null) h["x-avs-public-src"] = src
        for ((k, v) in headers) h[k.lowercase()] = v
        val q = if ('?' in target) ApiServer.parseForm(target.substringAfter('?')) else emptyMap()
        return ApiServer.Req(method, target.substringBefore('?'), q, h, body.toByteArray(Charsets.UTF_8)).apply {
            peer = InetAddress.getLoopbackAddress()
        }
    }

    private fun formEncode(vararg kv: Pair<String, String>): String =
        kv.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

    private fun registerClient(name: String = "CloudAgent", src: String = "203.0.113.10"): String {
        val body = JSONObject()
            .put("client_name", name)
            .put("redirect_uris", JSONArray().put("https://client.example/cb"))
            .put("token_endpoint_auth_method", "none")
            .toString()
        val resp = pubServer.route(req("POST", "/oauth/register", body, src = src))
        assertEquals(201, resp.status)
        return JSONObject(String(resp.body)).getString("client_id")
    }

    private fun authorizeUrl(cid: String, state: String = "s1"): String {
        val challenge = PublicOAuth.pkceS256Challenge("p".repeat(48))
        return "/oauth/authorize?" + formEncode(
            "response_type" to "code",
            "client_id" to cid,
            "redirect_uri" to "https://client.example/cb",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "resource" to resource,
            "state" to state,
        )
    }

    private fun extractCode(resp: ApiServer.Resp): String {
        val m = Regex("""<div class="code">([A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4})</div>""").find(String(resp.body))
        assertNotNull("pairing HTML must contain 8-char code", m)
        return m!!.groupValues[1]
    }

    @Test
    fun authorizeAndDeviceFlowShowTheCardOnThePhoneRightAway() {
        val cid = registerClient("RemoteClient", src = "198.51.100.1")
        val authResp = pubServer.route(req("GET", authorizeUrl(cid), src = "198.51.100.1"))
        assertEquals(200, authResp.status)
        val code = extractCode(authResp)
        val cards = StrongConfirm.pending.value
        assertEquals("authorize shows one confirmation card without any extra step", 1, cards.size)
        assertEquals("198.51.100.1", cards.single().sourceAddress)
        assertEquals(code, cards.single().pairingCode)

        val devResp = pubServer.route(
            req("POST", "/oauth/device_authorization", formEncode("client_id" to cid, "resource" to resource), src = "198.51.100.2"),
        )
        assertEquals(200, devResp.status)
        assertEquals("device_authorization shows its card too", 2, StrongConfirm.pending.value.size)

        // Entering the code by hand for a request that already has a card reopens the same card.
        val again = PublicOAuth.claimPairingByCode(ctx, code, openActivity = false)
        assertTrue(again is PublicOAuth.ClaimResult.Opened)
        assertEquals(cards.single().id, (again as PublicOAuth.ClaimResult.Opened).confirmId)
        assertEquals(2, StrongConfirm.pending.value.size)
    }

    @Test
    fun floodFrom35IpsIsCappedAndCannotBlockOrEvictTheLegitimatePairing() {
        val cid = registerClient("FloodedClient", src = "198.51.100.1")

        // 35 distinct attacker IPs flood /oauth/authorize: at most MAX_PENDING_PAIRINGS_GLOBAL cards appear,
        // the rest wait in the bounded queue (MAX_QUEUED_PAIRINGS_GLOBAL, FIFO).
        for (i in 1..35) {
            val r = pubServer.route(req("GET", authorizeUrl(cid, "atk$i"), src = "198.51.100.$i"))
            assertEquals("attacker $i accepted", 200, r.status)
            fakeNow += 10L
        }
        assertEquals("cards on the phone are capped", PublicOAuth.MAX_PENDING_PAIRINGS_GLOBAL, StrongConfirm.pending.value.size)

        // The legitimate request still gets a code; it waits in the queue while the card slots are busy.
        val legitResp = pubServer.route(req("GET", authorizeUrl(cid, "legit"), src = "203.0.113.77"))
        assertEquals(200, legitResp.status)
        val legitCode = extractCode(legitResp)
        assertTrue(StrongConfirm.pending.value.none { it.sourceAddress == "203.0.113.77" })

        // The user sees the unknown source addresses, denies those cards, then opens their own by its code.
        StrongConfirm.pending.value.toList().forEach { assertTrue(StrongConfirm.deny(ctx, it.id)) }
        val claim = PublicOAuth.claimPairingByCode(ctx, legitCode, openActivity = false)
        assertTrue("expected ClaimResult.Opened, got $claim", claim is PublicOAuth.ClaimResult.Opened)
        val opened = claim as PublicOAuth.ClaimResult.Opened

        val confirmReq = StrongConfirm.byId(opened.confirmId)
        assertNotNull("StrongConfirm request must exist after claim", confirmReq)
        assertEquals("203.0.113.77", confirmReq!!.sourceAddress)
        assertEquals("FloodedClient", confirmReq.publicClientName)
        assertEquals("default tier stays Easy (Tokens.FREE)", Tokens.FREE, confirmReq.defaultScope)

        // Another wave of 35 attacker IPs must NOT evict the open legitimate card.
        for (i in 101..135) {
            val r = pubServer.route(req("GET", authorizeUrl(cid, "atk$i"), src = "198.51.100.$i"))
            assertEquals(200, r.status)
            fakeNow += 10L
        }
        assertTrue(StrongConfirm.pending.value.size <= PublicOAuth.MAX_PENDING_PAIRINGS_GLOBAL)
        assertTrue("legitimate card can still be approved", StrongConfirm.approve(ctx, opened.confirmId, StrongConfirm.BY_BIOMETRIC, Tokens.FREE))
        val status = JSONObject(String(pubServer.route(req("GET", "/oauth/authorize/status?id=${opened.pairingId}")).body))
        assertEquals("approved", status.getString("status"))
    }

    @Test
    fun normalizeCodeAndClaimValidationAndGraceWindow() {
        assertEquals("ABCD-EFGH", PublicOAuth.normalizePairingCode("  abcd efgh  "))
        assertEquals("ABCD-EFGH", PublicOAuth.normalizePairingCode("abcd_efgh"))
        assertEquals("ABCD-EFGH", PublicOAuth.normalizePairingCode("ABCD－EFGH"))
        assertNull(PublicOAuth.normalizePairingCode("ABCD-0000")) // 0 not in alphabet
        assertNull(PublicOAuth.normalizePairingCode("ABCD-EFGI")) // I not in alphabet
        assertNull(PublicOAuth.normalizePairingCode("SHORT"))

        assertEquals(PublicOAuth.ClaimResult.InvalidFormat, PublicOAuth.claimPairingByCode(ctx, "bad!", openActivity = false))
        assertEquals(PublicOAuth.ClaimResult.NotFound, PublicOAuth.claimPairingByCode(ctx, "AAAA-BBBB", openActivity = false))

        val cid = registerClient("GraceClient")
        // No lock screen: no card is shown, the request stays queued, and a manual claim says why.
        PublicOAuth.deviceSecureCheck = { false }
        val r = pubServer.route(req("GET", authorizeUrl(cid), src = "203.0.113.9"))
        val code = extractCode(r)
        assertTrue(StrongConfirm.pending.value.isEmpty())
        assertEquals(PublicOAuth.ClaimResult.NoLockscreen, PublicOAuth.claimPairingByCode(ctx, code, openActivity = false))
        PublicOAuth.deviceSecureCheck = { true }

        // Claim 5 seconds before the 5-minute TTL -> gets at least CLAIM_MIN_CONFIRM_MS (60s) grace window.
        fakeNow += PublicOAuth.PAIRING_TTL_MS - 5_000L
        val firstClaim = PublicOAuth.claimPairingByCode(ctx, code.lowercase().replace("-", " "), openActivity = false)
        assertTrue(firstClaim is PublicOAuth.ClaimResult.Opened)
        val cid1 = (firstClaim as PublicOAuth.ClaimResult.Opened).confirmId

        // Re-claiming the same active code reuses the same confirmation card.
        val secondClaim = PublicOAuth.claimPairingByCode(ctx, code, openActivity = false)
        assertEquals(firstClaim, secondClaim)

        // 20 seconds later (past original 5-min TTL, within 60s grace window) -> approval still succeeds!
        fakeNow += 20_000L
        assertTrue(StrongConfirm.approve(ctx, cid1, StrongConfirm.BY_BIOMETRIC, Tokens.LOOK))

        // Unclaimed code expired after PAIRING_TTL_MS returns Expired.
        PublicOAuth.deviceSecureCheck = { false }
        val r2 = pubServer.route(req("GET", authorizeUrl(cid, "exp"), src = "203.0.113.11"))
        PublicOAuth.deviceSecureCheck = { true }
        val code2 = extractCode(r2)
        fakeNow += PublicOAuth.PAIRING_TTL_MS + 1_000L
        assertEquals(PublicOAuth.ClaimResult.Expired, PublicOAuth.claimPairingByCode(ctx, code2, openActivity = false))
    }

    @Test
    fun strictPairingWindowGatesNewPairingsWithoutBlockingExistingClientsAndAutoClosesOnApproval() {
        // First pair a legitimate existing client while strict window is off.
        val existingCid = registerClient("ExistingClient", src = "203.0.113.50")
        val verifier = "e".repeat(48)
        val existingAuth = pubServer.route(
            req(
                "GET",
                "/oauth/authorize?" + formEncode(
                    "response_type" to "code",
                    "client_id" to existingCid,
                    "redirect_uri" to "https://client.example/cb",
                    "code_challenge" to PublicOAuth.pkceS256Challenge(verifier),
                    "code_challenge_method" to "S256",
                    "resource" to resource,
                ),
                src = "203.0.113.50",
            ),
        )
        val existingCode = extractCode(existingAuth)
        val existingClaim = PublicOAuth.claimPairingByCode(ctx, existingCode, openActivity = false) as PublicOAuth.ClaimResult.Opened
        assertTrue(StrongConfirm.approve(ctx, existingClaim.confirmId, StrongConfirm.BY_BIOMETRIC, Tokens.LOOK))
        val existingRedirect = JSONObject(String(pubServer.route(req("GET", "/oauth/authorize/status?id=${existingClaim.pairingId}")).body)).getString("redirect_uri")
        val existingAuthCode = ApiServer.parseForm(existingRedirect.substringAfter('?'))["code"]!!.first()
        val existingTok = JSONObject(
            String(
                pubServer.route(
                    req(
                        "POST",
                        "/oauth/token",
                        formEncode(
                            "grant_type" to "authorization_code",
                            "code" to existingAuthCode,
                            "redirect_uri" to "https://client.example/cb",
                            "client_id" to existingCid,
                            "code_verifier" to verifier,
                            "resource" to resource,
                        ),
                    ),
                ).body,
            ),
        )
        val existingAccess = existingTok.getString("access_token")
        val existingRefresh = existingTok.getString("refresh_token")

        // Turn ON strict pairing window mode (window initially closed).
        PublicAccess.setStrictPairingWindow(ctx, true)
        assertFalse(PublicAccess.isPairingAllowed(fakeNow))

        // Register, authorize, and device_authorization are all rejected with 403 access_denied.
        val regClosed = pubServer.route(
            req("POST", "/oauth/register", JSONObject().put("client_name", "NewClient").put("redirect_uris", JSONArray().put("https://new.example/cb")).toString(), src = "203.0.113.60"),
        )
        assertEquals(403, regClosed.status)
        assertEquals("access_denied", JSONObject(String(regClosed.body)).getString("error"))

        val authClosed = pubServer.route(req("GET", authorizeUrl(existingCid, "closed"), src = "203.0.113.60"))
        assertEquals(403, authClosed.status)
        assertEquals("access_denied", JSONObject(String(authClosed.body)).getString("error"))

        val devClosed = pubServer.route(req("POST", "/oauth/device_authorization", formEncode("client_id" to existingCid), src = "203.0.113.60"))
        assertEquals(403, devClosed.status)

        // Existing authorized client STILL works on /mcp AND refresh_token while window is closed!
        val mcpOk = pubServer.route(
            req(
                "POST",
                "/mcp",
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"e","version":"1"}}}""",
                headers = mapOf("Authorization" to "Bearer $existingAccess", "Content-Type" to "application/json"),
            ),
        )
        assertEquals(200, mcpOk.status)
        val refreshOk = pubServer.route(
            req(
                "POST",
                "/oauth/token",
                formEncode("grant_type" to "refresh_token", "refresh_token" to existingRefresh, "client_id" to existingCid, "resource" to resource),
            ),
        )
        assertEquals(200, refreshOk.status)

        // Open manual 3-minute pairing window -> new client can register, authorize, claim, and approve.
        PublicAccess.openPairingWindow()
        assertTrue(PublicAccess.isPairingAllowed(fakeNow))
        val newCid = registerClient("WindowClient", src = "203.0.113.61")
        val newAuth = pubServer.route(req("GET", authorizeUrl(newCid, "win"), src = "203.0.113.61"))
        assertEquals(200, newAuth.status)
        val newClaim = PublicOAuth.claimPairingByCode(ctx, extractCode(newAuth), openActivity = false) as PublicOAuth.ClaimResult.Opened
        assertTrue(StrongConfirm.approve(ctx, newClaim.confirmId, StrongConfirm.BY_BIOMETRIC, Tokens.LOOK))

        // Approval automatically closes the strict pairing window!
        assertFalse("pairing window must auto-close after approval", PublicAccess.isPairingAllowed(fakeNow))
        assertEquals(403, pubServer.route(req("GET", authorizeUrl(newCid, "after"), src = "203.0.113.62")).status)

        // Opening again and waiting > 3 minutes closes the window automatically.
        PublicAccess.openPairingWindow()
        assertTrue(PublicAccess.isPairingAllowed(fakeNow))
        fakeNow += PublicAccess.PAIRING_WINDOW_MS + 1L
        assertFalse(PublicAccess.isPairingAllowed(fakeNow))
    }
}
