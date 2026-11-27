package com.kilon.agentvscreen.api

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.host.StrongConfirm
import com.kilon.agentvscreen.host.ThermalGuard
import com.kilon.agentvscreen.ui.MainActivity
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for P1-2:
 * - Sensitive clipboard flag (`android.content.extra.IS_SENSITIVE`) and hidden preview
 * - 10-minute single-use onboarding codes (`avc_...`), FIFO cap, expiry, revocation
 * - `POST /v2/pair/exchange` on ApiServer only (never PublicServer), rejecting query params/GET/browser Origin/replays
 * - `AgentGuide.prompt()` never leaks `ApiConfig.token`; `onboardWithCode` / `onboardHttpWithCode` bilingual hygiene
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OnboardingCodesTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var tempDir: File
    private lateinit var apiServer: ApiServer
    private lateinit var pubServer: PublicServer
    private var fakeNow = 1_850_000_000_000L
    private val bridgeHeader = "test-bridge-" + "b".repeat(20)

    @Before
    fun setUp() {
        I18n.choice = I18n.Lang.ZH
        tempDir = File(ctx.filesDir, "p12-test-${System.nanoTime()}").apply { mkdirs() }
        ApiConfig.init(ctx)
        Tokens.init(ctx)
        AgentLedger.init(ctx)
        Audit.init(ctx)
        Audit.clear()
        StrongConfirm.resetForTests(File(tempDir, "approvals.json"))
        StrongConfirm.postNotification = false
        PublicOAuth.resetForTests(File(tempDir, "public_oauth.json"))
        PublicAccess.resetForTests()
        PublicAccess.postNotifications = false
        PublicAccess.startServerOnEnable = false
        OnboardingCodes.resetForTests()
        OnboardingCodes.clock = { fakeNow }
        ctx.getSharedPreferences("thermal_safety_v1", Context.MODE_PRIVATE).edit().putBoolean("locked", false).commit()
        ThermalGuard.init(ctx)
        apiServer = ApiServer(ctx)
        apiServer.failLimiter.resetForTests()
        apiServer.failLimiter.clock = { fakeNow }
        apiServer.failLimiter.sleepFn = {}
        pubServer = PublicServer(
            ctx = ctx,
            apiServer = apiServer,
            secretProvider = { bridgeHeader },
            baseProvider = { "https://phone.tail12345.ts.net" },
            wakeService = {},
        )
    }

    @After
    fun tearDown() {
        if (::pubServer.isInitialized) pubServer.stop()
        OnboardingCodes.resetForTests()
        PublicOAuth.resetForTests()
        PublicAccess.resetForTests()
        StrongConfirm.resetForTests()
        I18n.choice = I18n.Lang.ZH
        tempDir.deleteRecursively()
    }

    private fun localReq(
        method: String,
        target: String,
        body: String = "",
        extraHeaders: Map<String, String> = emptyMap(),
    ): ApiServer.Req {
        val headers = LinkedHashMap<String, String>()
        headers["host"] = "127.0.0.1:${ApiConfig.port}"
        headers["content-type"] = "application/json"
        extraHeaders.forEach { (k, v) -> headers[k.lowercase()] = v }
        val q = HttpCore.parseForm(target.substringAfter('?', ""))
        return ApiServer.Req(method, target, q, headers, body.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun onboardingCodesEnforceSingleUseTtlReissueAndFifoCap() {
        val c1 = OnboardingCodes.issue("tok001")
        assertTrue(c1.startsWith("avc_"))
        assertTrue(OnboardingCodes.looksLikeCode(c1))
        assertTrue(OnboardingCodes.hasActive("tok001"))

        // Reissuing for the same token invalidates the prior code immediately
        val c2 = OnboardingCodes.issue("tok001")
        assertNotEquals(c1, c2)
        assertNull("Previous code must be invalidated on reissue", OnboardingCodes.consume(c1))
        assertEquals("tok001", OnboardingCodes.consume(c2))
        assertNull("Code must be strictly single-use", OnboardingCodes.consume(c2))

        // TTL expiry (10 min)
        val c3 = OnboardingCodes.issue("tok002")
        fakeNow += OnboardingCodes.TTL_MS
        assertFalse(OnboardingCodes.hasActive("tok002"))
        assertNull("Expired code must not redeem", OnboardingCodes.consume(c3))

        // FIFO cap of 16 active codes
        val issued = (1..20).map { i -> "cap$i" to OnboardingCodes.issue("cap$i") }
        assertEquals(OnboardingCodes.MAX_ACTIVE, OnboardingCodes.activeCount())
        for (i in 0 until 4) {
            assertNull("Oldest code #$i should have been evicted", OnboardingCodes.consume(issued[i].second))
        }
        assertEquals("cap5", OnboardingCodes.consume(issued[4].second))
    }

    @Test
    fun exchangeEndpointActivatesPendingTokenOnceAndRejectsUnsafeCalls() {
        val pending = Tokens.createPending("Termux Bot", Tokens.CAREFUL)
        assertTrue(pending.isPending)
        assertNull("Pending token cannot authenticate before exchange", Tokens.byBearer("avs_${pending.id}_anything1234567890"))

        val code = OnboardingCodes.issue(pending.id)

        // 1) GET rejected -> 405
        assertEquals(405, apiServer.route(localReq("GET", "/v2/pair/exchange")).status)

        // 2) Query param rejected -> 400 (keeps code out of URL logs)
        val qResp = apiServer.route(localReq("POST", "/v2/pair/exchange?code=$code", """{"code":"$code"}"""))
        assertEquals(400, qResp.status)
        assertEquals("BAD_REQUEST", JSONObject(String(qResp.body)).getString("code"))
        assertTrue("Rejected query attempt must not consume code", OnboardingCodes.hasActive(pending.id))

        // 3) Browser Origin rejected -> 403
        val browserResp = apiServer.route(
            localReq("POST", "/v2/pair/exchange", """{"code":"$code"}""", mapOf("Origin" to "https://evil.example")),
        )
        assertEquals(403, browserResp.status)
        assertEquals("BROWSER_REFUSED", JSONObject(String(browserResp.body)).getString("code"))

        // 4) NEVER exposed on PublicServer -> 404
        val pubResp = pubServer.route(
            ApiServer.Req(
                "POST",
                "/v2/pair/exchange",
                emptyMap(),
                mapOf(
                    "host" to "phone.tail12345.ts.net",
                    "x-avs-public" to bridgeHeader,
                    "x-avs-public-src" to "198.51.100.7",
                ),
                """{"code":"$code"}""".toByteArray(),
            ),
        )
        assertEquals(404, pubResp.status)

        // 5) Valid POST /v2/pair/exchange succeeds once
        val okResp = apiServer.route(localReq("POST", "/v2/pair/exchange", """{"code":"$code"}"""))
        assertEquals(200, okResp.status)
        val okJson = JSONObject(String(okResp.body))
        assertTrue(okJson.getBoolean("ok"))
        val rawTok = okJson.getString("token")
        assertTrue(rawTok.startsWith("avs_${pending.id}_"))
        assertEquals(pending.id, okJson.getString("token_id"))
        assertEquals(Tokens.CAREFUL, okJson.getString("level"))

        // Permanent token now authenticates and cannot be re-activated
        val live = Tokens.byBearer(rawTok)
        assertNotNull(live)
        assertEquals(pending.id, live!!.id)
        assertFalse(live.isPending)
        assertNull("Already-activated token cannot be activated again", Tokens.activateWithSecret(pending.id))

        // 6) Replay of the same code returns 401 CODE_INVALID_OR_EXPIRED
        val replayResp = apiServer.route(localReq("POST", "/v2/pair/exchange", """{"code":"$code"}"""))
        assertEquals(401, replayResp.status)
        assertEquals("CODE_INVALID_OR_EXPIRED", JSONObject(String(replayResp.body)).getString("code"))

        // Audit logs must NEVER contain raw code or raw token
        val auditText = Audit.tail(20).joinToString("\n") { it.toString() }
        assertTrue(auditText.contains("onboard_code_exchanged"))
        assertFalse("Raw onboarding code leaked to audit", auditText.contains(code))
        assertFalse("Raw permanent token leaked to audit", auditText.contains(rawTok))

        // Revoking a pending token also invalidates its onboarding code
        val pending2 = Tokens.createPending("Revoked Bot", Tokens.FREE)
        val code2 = OnboardingCodes.issue(pending2.id)
        Tokens.revoke(pending2.id)
        assertFalse(OnboardingCodes.hasActive(pending2.id))
        val revokedResp = apiServer.route(localReq("POST", "/v2/pair/exchange", """{"code":"$code2"}"""))
        assertEquals(401, revokedResp.status)
        assertEquals("CODE_INVALID_OR_EXPIRED", JSONObject(String(revokedResp.body)).getString("code"))
    }

    @Test
    fun publicOnlyTokenCannotBeActivatedWithSecret() {
        val pubTok = Tokens.createPublic("Cloud Client", "operate")
        assertFalse(pubTok.isPending)
        assertNull(Tokens.activateWithSecret(pubTok.id))
    }

    @Test
    fun agentGuideNeverEmbedsMainTokenInPromptAndFormatsCodeOnboardingCleanly() {
        val fullwidth = Regex("[\\u3001-\\u301F\\uFF01-\\uFF60]")
        val mainToken = ApiConfig.token
        val sampleCode = "avc_" + "a".repeat(24)
        assertTrue(mainToken.isNotBlank())

        for (lang in listOf(I18n.Lang.ZH, I18n.Lang.EN)) {
            I18n.choice = lang
            val promptText = AgentGuide.prompt(skill = false)
            assertFalse("prompt() must never leak ApiConfig.token ($lang)", promptText.contains(mainToken))
            assertFalse("guide() must never leak ApiConfig.token ($lang)", AgentGuide.guide().contains(mainToken))

            val cliMsg = AgentGuide.onboardWithCode("http://127.0.0.1:18765", sampleCode)
            assertTrue(cliMsg.contains("VSCREEN_CODE=$sampleCode"))
            assertFalse(cliMsg.contains(mainToken))

            val httpMsg = AgentGuide.onboardHttpWithCode("http://100.64.0.9:18765", sampleCode)
            assertTrue(httpMsg.contains("/v2/pair/exchange"))
            assertTrue(httpMsg.contains(sampleCode))
            assertFalse(httpMsg.contains(mainToken))

            if (lang == I18n.Lang.EN) {
                assertFalse("EN prompt has fullwidth punctuation", fullwidth.containsMatchIn(promptText))
                assertFalse("EN onboardWithCode has fullwidth punctuation", fullwidth.containsMatchIn(cliMsg))
                assertFalse("EN onboardHttpWithCode has fullwidth punctuation", fullwidth.containsMatchIn(httpMsg))
            }
        }
    }

    @Test
    fun mainActivityCopyMarksSensitiveClipboardExtra() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val cm = act.getSystemService(ClipboardManager::class.java)!!

        act.copy("Token", "avs_test_" + "z".repeat(20), sensitive = true)
        val sensitiveClip = cm.primaryClip
        assertNotNull(sensitiveClip)
        assertEquals(true, sensitiveClip!!.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false))

        act.copy("Note", "plain non-sensitive text", sensitive = false)
        val plainClip = cm.primaryClip
        assertNotNull(plainClip)
        assertFalse(plainClip!!.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true)
    }
}
