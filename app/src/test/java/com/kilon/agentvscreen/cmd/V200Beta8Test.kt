package com.kilon.agentvscreen.cmd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.kilon.agentvscreen.Changes
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.AgentGuide
import com.kilon.agentvscreen.api.AgentLedger
import com.kilon.agentvscreen.api.AgentNews
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.ApiServer
import com.kilon.agentvscreen.api.PublicOAuth
import com.kilon.agentvscreen.api.PublicServer
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.core.Gate
import com.kilon.agentvscreen.core.OnnxRuntime
import com.kilon.agentvscreen.core.VScreenDetector
import com.kilon.agentvscreen.core.VScreenPerception
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.host.Risk
import com.kilon.agentvscreen.host.StrongConfirm
import com.kilon.agentvscreen.host.Tailnet
import com.kilon.agentvscreen.host.VScreenAccessibilityService
import com.kilon.agentvscreen.ui.Route
import com.kilon.agentvscreen.ui.WindowSizes
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * 2.0.0-beta8 comprehensive unit tests covering sections B1, B2, C2, C3, C4, C5, C6, D, E, G, H, I, L.
 * (Section A & tsbridge Section I/B1 are covered in `tsbridge/beta8_test.go`.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V200Beta8Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val fakeNow = AtomicLong(1_760_000_000_000L)

    @Before
    fun setUp() {
        I18n.choice = I18n.Lang.ZH
        ApiConfig.init(ctx)
        Tokens.init(ctx)
        Audit.init(ctx)
        Audit.clear()
        StrongConfirm.resetForTests(tmp.newFile("approvals.json"))
        StrongConfirm.postNotification = false
        StrongConfirm.waitMsOverride = 0L
        StrongConfirm.clock = { fakeNow.get() }
        Risk.resetForTests()
        Risk.clock = { fakeNow.get() }
        PublicOAuth.resetForTests(tmp.newFile("public_oauth.json"))
        PublicOAuth.clock = { fakeNow.get() }
        PublicOAuth.deviceSecureCheck = { true }
        PublicAccess.resetForTests()
        PublicAccess.clock = { fakeNow.get() }
        PublicAccess.postNotifications = false
        PublicAccess.startServerOnEnable = false
        ActHold.resetForTest()
        OnnxRuntime.resetForTests()
        VScreenAccessibilityService.clearToastsForTests()
        VScreenDetector.reset(ctx)
    }

    @After
    fun tearDown() {
        I18n.choice = I18n.Lang.ZH
        PublicAccess.resetForTests()
        PublicOAuth.resetForTests()
        StrongConfirm.resetForTests()
        Risk.resetForTests()
        ActHold.resetForTest()
        OnnxRuntime.resetForTests()
        VScreenAccessibilityService.clearToastsForTests()
    }

    private fun spec(name: String): CommandSpec.Cmd = CommandSpec.of(name)!!
    private fun emptyArgs() = VScreenOptions.args("apps", emptyList())

    // ── B1: funnel-check parsing, 5-item checklist state machine, ON_RESUME auto-recheck, once-only consent ──

    @Test
    fun b1FunnelCheckParsingChecklistStateMachineAutoRecheckAndOnceOnlyConsent() {
        val raw = JSONObject(
            """
            {
              "seq": "7",
              "official": true,
              "logged_in": true,
              "dns_name": "phone.tailnet.ts.net",
              "https": {"ok": false, "text": "Enable HTTPS", "url": "https://login.tailscale.com/f/serve?node=abc", "should_wait": true},
              "funnel": {"ok": false, "text": "Enable Funnel", "url": "https://login.tailscale.com/f/funnel?node=abc", "should_wait": true},
              "port443": {"ok": false, "text": "port 443 is not allowed"}
            }
            """.trimIndent()
        )
        val parsed = Tailnet.parseFunnelCheck(raw, nowMs = 1234L)
        assertEquals("7", parsed.seq)
        assertTrue(parsed.official && parsed.loggedIn)
        assertEquals("phone.tailnet.ts.net", parsed.dnsName)
        assertFalse(parsed.https.ok)
        assertEquals("https://login.tailscale.com/f/serve?node=abc", parsed.https.url)
        assertTrue(parsed.shouldWait)

        val loggedInTs = Tailnet.Status(state = "Running", ip = "100.64.0.1", dnsName = "phone.tailnet.ts.net")

        // 1) Headscale blocks item 1 directly and explains Headscale does not support Funnel.
        val hsSnap = PublicAccess.evaluateChecklist(
            tailnetMode = Tailnet.Mode.BUILTIN,
            controlUrl = "https://hs.example.com",
            tsStatus = loggedInTs,
            funnelCheck = null,
            funnelChecking = false,
            deviceSecure = true,
        )
        assertEquals(5, hsSnap.items.size)
        assertFalse(hsSnap.allOk)
        assertEquals(PublicAccess.CheckState.FAIL, hsSnap.items[0].state)
        assertEquals(PublicAccess.CheckAction.OPEN_REMOTE, hsSnap.items[0].action)
        assertTrue(hsSnap.items[0].detail.contains("Headscale"))

        // 2) Missing HTTPS + Funnel + port443 + lockscreen: uses QueryFeature URLs when provided and sets shouldWait.
        val missingWithUrl = PublicAccess.evaluateChecklist(
            tailnetMode = Tailnet.Mode.BUILTIN,
            controlUrl = null,
            tsStatus = loggedInTs,
            funnelCheck = parsed,
            funnelChecking = false,
            deviceSecure = false,
        )
        assertEquals(5, missingWithUrl.items.size)
        assertFalse(missingWithUrl.allOk)
        assertTrue(missingWithUrl.shouldWait)
        val httpsItem = missingWithUrl.items.first { it.id == PublicAccess.CheckId.HTTPS_CERT }
        val funnelItem = missingWithUrl.items.first { it.id == PublicAccess.CheckId.FUNNEL_ACL }
        val lockItem = missingWithUrl.items.first { it.id == PublicAccess.CheckId.LOCKSCREEN }
        assertEquals("https://login.tailscale.com/f/serve?node=abc", httpsItem.actionUrl)
        assertEquals("https://login.tailscale.com/f/funnel?node=abc", funnelItem.actionUrl)
        assertNull("QueryFeature URL needs no manual nodeAttrs snippet", funnelItem.copySnippet)
        assertEquals(PublicAccess.CheckAction.OPEN_LOCKSCREEN, lockItem.action)

        // 3) Fallback when QueryFeature has no URL: links to Tailscale Admin DNS/ACL + copyable nodeAttrs snippet.
        val noUrlCheck = Tailnet.FunnelCheck(
            official = true,
            loggedIn = true,
            dnsName = "phone.tailnet.ts.net",
            https = Tailnet.FunnelFeature(ok = false),
            funnel = Tailnet.FunnelFeature(ok = false),
            port443 = Tailnet.FunnelFeature(ok = false),
        )
        val fallbackSnap = PublicAccess.evaluateChecklist(
            tailnetMode = Tailnet.Mode.BUILTIN,
            controlUrl = null,
            tsStatus = loggedInTs,
            funnelCheck = noUrlCheck,
            funnelChecking = false,
            deviceSecure = true,
        )
        val fbHttps = fallbackSnap.items.first { it.id == PublicAccess.CheckId.HTTPS_CERT }
        val fbFunnel = fallbackSnap.items.first { it.id == PublicAccess.CheckId.FUNNEL_ACL }
        assertEquals(PublicAccess.ADMIN_DNS_URL, fbHttps.actionUrl)
        assertEquals(PublicAccess.ADMIN_ACL_URL, fbFunnel.actionUrl)
        assertEquals(PublicAccess.FUNNEL_NODE_ATTRS_SNIPPET, fbFunnel.copySnippet)
        assertNotNull(fbFunnel.copyGuide)

        // 4) All 5 checks satisfied -> allOk = true, but enable still requires once-only risk consent.
        val allOkCheck = Tailnet.FunnelCheck(
            official = true,
            loggedIn = true,
            dnsName = "phone.tailnet.ts.net",
            https = Tailnet.FunnelFeature(ok = true),
            funnel = Tailnet.FunnelFeature(ok = true),
            port443 = Tailnet.FunnelFeature(ok = true),
        )
        val okSnap = PublicAccess.evaluateChecklist(
            tailnetMode = Tailnet.Mode.BUILTIN,
            controlUrl = null,
            tsStatus = loggedInTs,
            funnelCheck = allOkCheck,
            funnelChecking = false,
            deviceSecure = true,
        )
        assertTrue(okSnap.allOk)
        assertFalse(okSnap.shouldWait)
        assertFalse("cannot enable before consent accepted", PublicAccess.canEnable(okSnap))

        // 5) Versioned once-only risk consent persists across init() and only re-prompts if version bumps.
        PublicAccess.init(ctx)
        PublicAccess.acceptRiskConsent(ctx, PublicAccess.RISK_CONSENT_VERSION)
        assertTrue(PublicAccess.hasAcceptedRiskConsent())
        assertTrue(PublicAccess.canEnable(okSnap))
        PublicAccess.init(ctx)
        assertTrue("consent must survive reload without re-prompting", PublicAccess.hasAcceptedRiskConsent())
        assertFalse("bumped consent text version must require re-acceptance", PublicAccess.hasAcceptedRiskConsent(PublicAccess.RISK_CONSENT_VERSION + 1))

        // 6) ON_RESUME auto-recheck triggers funnel-check only when official built-in Tailscale is logged in.
        val recheckCalls = AtomicInteger(0)
        PublicAccess.requestFunnelCheckAction = { recheckCalls.incrementAndGet(); true }
        assertFalse(PublicAccess.recheckPrerequisites(Tailnet.Mode.SYSTEM, null, loggedInTs))
        assertFalse(PublicAccess.recheckPrerequisites(Tailnet.Mode.BUILTIN, "https://hs.example.com", loggedInTs))
        assertFalse(PublicAccess.recheckPrerequisites(Tailnet.Mode.BUILTIN, null, Tailnet.Status(state = "NeedsLogin")))
        assertEquals(0, recheckCalls.get())
        assertTrue(PublicAccess.recheckPrerequisites(Tailnet.Mode.BUILTIN, null, loggedInTs))
        assertEquals(1, recheckCalls.get())
    }

    // ── B2: evaluateAll returns all missing dimensions at once + STANDARD vs STRICT security levels ──

    @Test
    fun b2RiskEvaluateAllBatchedConfirmAndStandardVsStrictLevels() {
        // 2.0.0-beta9 (changed on purpose): scope approvals, the 8 h / 24 h re-confirmations and the global security
        // level are gone (one table per agent). What stays: every missing dimension in one confirmation, no expiry.
        val t0 = fakeNow.get()
        val call = Call("s1", tokenId = "tok_b2", tokenName = "agent-b2", scopes = setOf("look", "operate", "system"), peer = null)
        val forceArgs = VScreenOptions.args("stop", listOf("com.example.a", "--force"))
        repeat(Risk.RATE_MAX_PER_MIN + 1) { Risk.recordInputAction("tok_b2", t0) }
        assertEquals(listOf(Risk.NEW_CLIENT, Risk.RATE), Risk.evaluateAll(call, spec("stop"), forceArgs, t0))
        var err: com.kilon.agentvscreen.core.BackgroundOperation.ActionError? = null
        try { Risk.check(ctx, call, spec("stop"), forceArgs) } catch (e: com.kilon.agentvscreen.core.BackgroundOperation.ActionError) { err = e }
        assertEquals("NEEDS_CONFIRM", err!!.code)
        val confirmObj = err.data!!.getJSONObject("confirm")
        val risksArr = confirmObj.getJSONArray("risks")
        assertEquals(listOf("NEW_CLIENT", "RATE"), (0 until risksArr.length()).map { risksArr.getString(it) })
        // A new agent with it is a fingerprint, not a tap.
        assertFalse(StrongConfirm.approve(ctx, confirmObj.getString("id"), StrongConfirm.BY_TAP))
        assertTrue(StrongConfirm.approve(ctx, confirmObj.getString("id"), StrongConfirm.BY_BIOMETRIC))
        // The same fingerprint covers the high rate for this run: no second stop right after.
        assertEquals(emptyList<Risk>(), Risk.evaluateAll(call, spec("stop"), forceArgs, t0 + 1L))
        assertEquals(emptyList<Risk>(), Risk.evaluateAll(call, spec("stop"), forceArgs, t0 + 30L * 24 * 60 * 60_000L))
    }

    // ── C2: ActHold synchronous verification, upfront HOLD_REFUSED, partial child note, API 35 kill breaker ──

    @Test
    fun c2ActHoldVerificationUpfrontRefusalPartialChildAndApi35KillBreaker() {
        // Pure parsers: cgroup PIDs, dumpsys processes, display top process, freeze state classification.
        assertEquals(listOf(101, 202), ActHold.parseCgroupPids("pid_101\ncgroup.procs\npid_202\n"))
        val dsProc = """
            *APP* UID 10300 ProcessRecord{111 101:com.demo.app/u0a300}
            *APP* UID 10300 ProcessRecord{222 202:com.demo.app:worker/u0a300}
            *APP* UID 10301 ProcessRecord{333 303:com.other.app/u0a301}
        """.trimIndent()
        assertEquals(mapOf(101 to "com.demo.app", 202 to "com.demo.app:worker"), ActHold.parseDumpsysProcessesForUid(dsProc, 10300))

        val dsAct = """
            Display #0
              mResumedActivity: ActivityRecord{0 u0 com.android.launcher/.Home}
            Display #2
              * ActivityRecord{a u0 com.demo.app/.Player}
                app=ProcessRecord{111 101:com.demo.app/u0a300}
        """.trimIndent()
        assertEquals(101 to "com.demo.app", ActHold.parseDisplayTopProcess(dsAct, 2, "com.demo.app"))

        // Cgroup v2 & wchan classification: wchan "0" is UNVERIFIABLE (never FROZEN!).
        assertEquals(ActHold.ProcFreezeState.FROZEN to "cgroup", ActHold.classifyProcessFreeze("populated 1\nfrozen 1\n", null, true, true))
        assertEquals(ActHold.ProcFreezeState.NOT_FROZEN to "cgroup", ActHold.classifyProcessFreeze("populated 1\nfrozen 0\n", null, true, true))
        assertEquals(ActHold.ProcFreezeState.DIED to "cgroup", ActHold.classifyProcessFreeze(null, null, false, true))
        assertEquals(ActHold.ProcFreezeState.FROZEN to "wchan", ActHold.classifyProcessFreeze(null, Gate.FREEZER_WCHAN, true, false))
        assertEquals(ActHold.ProcFreezeState.FROZEN to "wchan", ActHold.classifyProcessFreeze(null, "__refrigerator", true, false))
        assertEquals(ActHold.ProcFreezeState.UNVERIFIABLE to "wchan", ActHold.classifyProcessFreeze(null, "0", true, false))
        assertEquals(ActHold.ProcFreezeState.UNVERIFIABLE to "none", ActHold.classifyProcessFreeze(null, "", true, false))

        // Upfront HOLD_REFUSED: uid < 10000, persistent, IME, active system media session (never calls gateHoldFn).
        val skip = emptySet<String>()
        val holdInvocations = AtomicInteger(0)
        val unholdInvocations = AtomicInteger(0)
        ActHold.sdkIntFn = { 34 }
        ActHold.sleepFn = {}
        ActHold.clockMsFn = { fakeNow.get() }
        ActHold.gateReadyFn = { true }
        ActHold.isDisplayBusyFn = { null }
        ActHold.gateHoldFn = { ps, _ -> holdInvocations.incrementAndGet(); ps.size }
        ActHold.gateUnholdFn = { unholdInvocations.incrementAndGet(); 1 }
        ActHold.checkFreezeCommandSupportedFn = { true }

        // 1) System uid (< 10000) refused upfront.
        ActHold.appInfoFn = { _, _ -> ActHold.AppInfoSnapshot(uid = 1000, persistent = false) }
        ActHold.currentImePkgFn = { null }
        ActHold.mediaSessionDumpFn = { null }
        assertTrue(ActHold.holdBeforeReply(null, "s1", 2, "com.android.settings", "设置", skip, null) is ActHold.HoldOutcome.Refused)

        // 2) Persistent app refused upfront.
        ActHold.appInfoFn = { _, _ -> ActHold.AppInfoSnapshot(uid = 10100, persistent = true) }
        assertTrue(ActHold.holdBeforeReply(null, "s1", 2, "com.vendor.persist", "Persist", skip, null) is ActHold.HoldOutcome.Refused)

        // 3) Current IME refused upfront.
        ActHold.appInfoFn = { _, _ -> ActHold.AppInfoSnapshot(uid = 10101, persistent = false) }
        ActHold.currentImePkgFn = { "com.example.ime" }
        assertTrue(ActHold.holdBeforeReply(null, "s1", 2, "com.example.ime", "IME", skip, null) is ActHold.HoldOutcome.Refused)

        // 4) System-owned active media session refused upfront.
        ActHold.currentImePkgFn = { null }
        ActHold.mediaSessionDumpFn = { "MediaSessionRecord package=com.demo.music userId=0 uid=1000 state=3 (PLAYING)" }
        assertTrue(ActHold.holdBeforeReply(null, "s1", 2, "com.demo.music", "Music", skip, null) is ActHold.HoldOutcome.Refused)
        assertEquals("upfront refusals must never call gateHold", 0, holdInvocations.get())

        // 5) API 34 partial :worker unfrozen -> succeeds because display proc is FROZEN, honestly annotates :worker.
        ActHold.mediaSessionDumpFn = { null }
        ActHold.appInfoFn = { _, _ -> ActHold.AppInfoSnapshot(uid = 10300, persistent = false) }
        ActHold.cgroupUidLsFn = { "pid_101\npid_202\n" }
        ActHold.dumpsysProcessesFn = { dsProc }
        ActHold.dumpsysActivitiesFn = { dsAct }
        ActHold.verifyPidsFn = { _, targets ->
            targets.map { t ->
                val st = if (t.isDisplayProc) ActHold.ProcFreezeState.FROZEN else ActHold.ProcFreezeState.NOT_FROZEN
                ActHold.ProcVerifyResult(t.pid, t.name, t.isDisplayProc, st, "cgroup")
            }
        }
        val partial = ActHold.holdBeforeReply(null, "s1", 2, "com.demo.app", "Demo", skip, null) as ActHold.HoldOutcome.Held
        assertTrue("partial hold must note unfrozen :worker: ${partial.footerLine}", partial.footerLine.contains("com.demo.app:worker"))
        assertTrue("diag must contain per-process snapshot", ActHold.diagSummary().contains("com.demo.app(101)=frozen 1"))
        ActHold.unholdBeforeCommand()

        // 6) Display proc unverifiable -> immediate unhold, Refused (never claims paused).
        ActHold.verifyPidsFn = { _, targets ->
            targets.map { t -> ActHold.ProcVerifyResult(t.pid, t.name, t.isDisplayProc, ActHold.ProcFreezeState.UNVERIFIABLE, "wchan") }
        }
        val unholdBefore = unholdInvocations.get()
        val unverif = ActHold.holdBeforeReply(null, "s1", 2, "com.demo.unverif", "Unverif", skip, null)
        assertTrue(unverif is ActHold.HoldOutcome.Refused)
        assertFalse(ActHold.isHeld())
        assertTrue("unverifiable freeze must thaw immediately", unholdInvocations.get() > unholdBefore)

        // 7) API 35 display proc killed on freeze -> immediate thaw + Refused(appKilled=true) + per-app breaker ONLY.
        ActHold.sdkIntFn = { 35 }
        ActHold.verifyPidsFn = { _, targets ->
            targets.map { t ->
                val st = if (t.isDisplayProc) ActHold.ProcFreezeState.DIED else ActHold.ProcFreezeState.FROZEN
                ActHold.ProcVerifyResult(t.pid, t.name, t.isDisplayProc, st, "cgroup")
            }
        }
        val killed = ActHold.holdBeforeReply(null, "s1", 2, "com.demo.killme", "KillMe", skip, null) as ActHold.HoldOutcome.Refused
        assertTrue(killed.appKilled)
        assertTrue(killed.text.contains("结束"))
        assertFalse(ActHold.isHeld())

        // Same app in same session hits per-app breaker immediately without calling gateHold again.
        val beforeBreakerCalls = holdInvocations.get()
        val tripped = ActHold.holdBeforeReply(null, "s1", 2, "com.demo.killme", "KillMe", skip, null)
        assertTrue(tripped is ActHold.HoldOutcome.Refused)
        assertEquals("tripped app breaker must not invoke gateHold", beforeBreakerCalls, holdInvocations.get())

        // Different app in same session still works!
        ActHold.verifyPidsFn = { _, targets ->
            targets.map { t -> ActHold.ProcVerifyResult(t.pid, t.name, t.isDisplayProc, ActHold.ProcFreezeState.FROZEN, "cgroup") }
        }
        val otherOk = ActHold.holdBeforeReply(null, "s1", 2, "com.demo.healthy", "Healthy", skip, null)
        assertTrue("per-app breaker must NOT affect other apps: $otherOk", otherOk is ActHold.HoldOutcome.Held)
        ActHold.unholdBeforeCommand()
    }

    // ── C3: until text timeout distinguishes ABSENT, PRE_EXISTING, NOT_READ ──

    @Test
    fun c3UntilTextTimeoutThreeClassifications() {
        assertNotNull(Codes.of("NOT_READ"))

        assertEquals(
            TextTimeoutKind.ABSENT,
            classifyTextTimeout(waitingForChange = false, preExisting = false, hadPostActionOcr = true, hadPostActionA11y = true, ocrBusy = false),
        )
        assertEquals(
            TextTimeoutKind.PRE_EXISTING,
            classifyTextTimeout(waitingForChange = false, preExisting = true, hadPostActionOcr = true, hadPostActionA11y = true, ocrBusy = false),
        )
        assertEquals(
            TextTimeoutKind.NOT_READ,
            classifyTextTimeout(waitingForChange = false, preExisting = false, hadPostActionOcr = false, hadPostActionA11y = false, ocrBusy = true),
        )
        assertEquals(
            TextTimeoutKind.NOT_READ,
            classifyTextTimeout(waitingForChange = false, preExisting = false, hadPostActionOcr = false, hadPostActionA11y = false, ocrBusy = false),
        )

        I18n.choice = I18n.Lang.ZH
        val zhAbsent = formatTextTimeout("简介", TextTimeoutKind.ABSENT, ocrBusy = false)
        val zhPre = formatTextTimeout("简介", TextTimeoutKind.PRE_EXISTING, ocrBusy = false)
        val zhBusy = formatTextTimeout("简介", TextTimeoutKind.NOT_READ, ocrBusy = true)
        assertTrue(zhAbsent.contains("画面上没有"))
        assertTrue(zhPre.contains("动作前就有") && zhPre.contains("until change"))
        assertTrue(zhBusy.contains("OCR 忙"))

        I18n.choice = I18n.Lang.EN
        val enAbsent = formatTextTimeout("Details", TextTimeoutKind.ABSENT, ocrBusy = false)
        val enPre = formatTextTimeout("Details", TextTimeoutKind.PRE_EXISTING, ocrBusy = false)
        val enBusy = formatTextTimeout("Details", TextTimeoutKind.NOT_READ, ocrBusy = true)
        assertTrue(enAbsent.contains("not on the screen"))
        assertTrue(enPre.contains("already on the screen") && enPre.contains("until change"))
        assertTrue(enBusy.contains("OCR busy"))
    }

    // ── C5: system Toast events feed Loud.TOAST, main-screen annotation, OCR toast guessing removed ──

    @Test
    fun c5SystemToastEventsEnterLoudAndOcrNoiseNeverEmitsToast() {
        VScreenAccessibilityService.recordToast("com.demo.app", "您已处于开发者模式", epochMs = 10_200L, monoMs = 1_200L)
        // Duplicate within 500ms is deduplicated.
        VScreenAccessibilityService.recordToast("com.demo.app", "您已处于开发者模式", epochMs = 10_400L, monoMs = 1_400L)
        // Unrelated package & out-of-window toast must not match.
        VScreenAccessibilityService.recordToast("com.other.app", "OTHER_TOAST", epochMs = 10_300L, monoMs = 1_300L)
        VScreenAccessibilityService.recordToast("com.demo.app", "OLD_TOAST", epochMs = 8_000L, monoMs = 100L)

        val matched = VScreenAccessibilityService.recentToasts("com.demo.app", 10_000L, 11_000L)
        assertEquals(1, matched.size)

        val lines = formatSystemToasts(matched, vdPkg = "com.demo.app", mainTopPkg = "com.launcher", fromMs = 10_000L, toMs = 11_000L)
        assertEquals(listOf("toast：“您已处于开发者模式”（-0.8s）"), lines)

        val sameMainLines = formatSystemToasts(matched, vdPkg = "com.demo.app", mainTopPkg = "com.demo.app", fromMs = 10_000L, toMs = 11_000L)
        assertEquals(1, sameMainLines.size)
        assertTrue("same package on main screen must be annotated: ${sameMainLines[0]}", sameMainLines[0].contains("可能来自主屏上的同一应用"))

        // Loud.TOAST makes an otherwise quiet reply become full.
        val quietReasons = loudReasons(LoudInput(elementCount = 5, hasToast = false))
        assertTrue(quietReasons.isEmpty())
        val toastReasons = loudReasons(LoudInput(elementCount = 5, hasToast = true))
        assertEquals(listOf(Loud.TOAST), toastReasons)

        // Scrolling bottom OCR text must NEVER be guessed as a toast.
        val bottomBox = Rect(300, 1900, 760, 2000)
        val ocrNoise = listOf(
            VScreenPerception.Reading(1_000L, listOf("标题"), hits = 2, lastAtMs = 1_200L),
            VScreenPerception.Reading(2_000L, listOf("标题", "弹幕乱码123"), mapOf("弹幕乱码123" to bottomBox), hits = 1, lastAtMs = 2_000L),
            VScreenPerception.Reading(3_000L, listOf("标题"), hits = 2, lastAtMs = 3_200L),
        )
        val wf = wordFactsFromReadings(ocrNoise, 2400, 3_200L)
        assertFalse("OCR must not emit toast lines: ${wf.allLines}", wf.allLines.any { it.contains("toast") })
    }

    // ── C6: capabilities.drag, AFTER_DISAPPEARED, SNAP_BACK code ──

    @Test
    fun c6DragCapabilitiesAfterDisappearedAndSnapBackCode() {
        // 1) capabilities.drag reflects actual privilege + display state.
        I18n.choice = I18n.Lang.ZH
        assertTrue(dragCapabilityText(shellOwned = true, privilegeReady = true).contains("POINTER"))
        assertTrue(dragCapabilityText(shellOwned = null, privilegeReady = true).contains("建副屏后可用"))
        assertTrue(dragCapabilityText(shellOwned = null, privilegeReady = false).contains("不可用"))

        // 2) Dragging an item away so start changes, end stays blank, rest of screen unchanged -> AFTER_DISAPPEARED.
        fun makeFrame(drawCardAt: Pair<Int, Int>?): Bitmap {
            val w = 288; val h = 640
            val px = IntArray(w * h) { Color.WHITE }
            if (drawCardAt != null) {
                val (cx, cy) = drawCardAt
                for (y in (cy - 28).coerceAtLeast(0)..(cy + 28).coerceAtMost(h - 1)) {
                    for (x in (cx - 36).coerceAtLeast(0)..(cx + 36).coerceAtMost(w - 1)) {
                        val dx = x - cx; val dy = y - cy
                        px[y * w + x] = if (dx * dx + dy * dy <= 14 * 14) Color.rgb(250, 204, 21) else Color.rgb(37, 99, 235)
                    }
                }
            }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
        }
        val start = 80 to 200
        val mid = 140 to 200
        val end = 220 to 200
        val pre = makeFrame(start)
        val pick = makeFrame(105 to 200)
        val midF = makeFrame(mid)
        val settledGone = makeFrame(null)
        try {
            val rec = Gesture.analyzeGestureFrames(pre, listOf(pick), midF, settledGone, start, mid, end)
            assertEquals("swiped-away item must classify as AFTER_DISAPPEARED: $rec", Gesture.AFTER_DISAPPEARED, rec.afterRelease)
            assertTrue("description must explain disappeared: ${rec.describe()}", rec.describe().contains("松手后不见了"))
        } finally {
            pre.recycle(); pick.recycle(); midF.recycle(); settledGone.recycle()
        }

        // 3) SNAP_BACK code exists and advises look.
        assertNotNull(Codes.of("SNAP_BACK"))
        assertEquals("look", Codes.next("SNAP_BACK"))
    }

    // ── D: OnnxRuntime metrics ring buffer + VScreenDetector 3-run verification sentinel ──

    @Test
    fun dOnnxRuntimeMetricsRingBufferAndDetectorThreeRunSentinel() {
        var mono = 1_000L
        var epoch = 1_760_000_000_000L
        OnnxRuntime.clockMonoFn = { mono }
        OnnxRuntime.clockEpochFn = { epoch }
        OnnxRuntime.thermalProvider = { "MODERATE" }

        // 1) Normal inference records thermal status, slotWaitMs, shape, duration, and perceptionJson.
        val res = OnnxRuntime.runInference(ctx, "ppocr-det", longArrayOf(1, 3, 640, 640), slotWaitMs = 45L) {
            mono += 120L
            epoch += 120L
            "ok"
        }
        assertEquals("ok", res)
        val rec1 = OnnxRuntime.recent(1).single()
        assertEquals("MODERATE", rec1.thermalStatus)
        assertEquals(45L, rec1.slotWaitMs)
        assertEquals(120L, rec1.durMs)
        assertEquals("1x3x640x640", rec1.inputShape)
        assertEquals(0L, rec1.lateAfterTimeoutMs)
        assertEquals(120L, OnnxRuntime.perceptionJson(ctx).getLong("last_ocr_ms"))
        assertEquals("shared_nospin", OnnxRuntime.perceptionJson(ctx).getString("pool"))

        // 2) Timed-out inference reports stuckTimedOut while running, then records lateAfterTimeoutMs on return.
        var stuckWhileRunning = 0
        OnnxRuntime.runInference(ctx, "ppocr-rec", longArrayOf(1, 3, 48, 320), onHandle = { h ->
            mono += 15_000L
            h.markTimedOut(15_000L)
        }) {
            stuckWhileRunning = OnnxRuntime.stuckTimedOut().size
            mono += 2_300L
            epoch += 17_300L
        }
        assertEquals(1, stuckWhileRunning)
        assertEquals(0, OnnxRuntime.stuckTimedOut().size)
        val lateRec = OnnxRuntime.recent(1).single()
        assertEquals(2_300L, lateRec.lateAfterTimeoutMs)
        assertEquals(1, OnnxRuntime.perceptionJson(ctx).getInt("late_completions"))
        assertTrue(lateRec.formatLine().contains("LATE(+2300ms"))

        // 3) Ring buffer caps strictly at MAX_RECORDS (50).
        repeat(60) {
            OnnxRuntime.runInference(ctx, "detector:student", longArrayOf(1, 3, 320, 704)) {
                mono += 10L
                epoch += 10L
            }
        }
        assertEquals(OnnxRuntime.MAX_RECORDS, OnnxRuntime.recentCount())

        // 4) VScreenDetector 3-run verification sentinel per (sha256, versionCode).
        val shaA = "a".repeat(64)
        val shaB = "b".repeat(64)
        assertFalse(VScreenDetector.isVerified(ctx, shaA, 65))
        assertEquals(1, VScreenDetector.noteVerifiedRun(ctx, shaA, 65))
        assertFalse(VScreenDetector.isVerified(ctx, shaA, 65))
        assertEquals(2, VScreenDetector.noteVerifiedRun(ctx, shaA, 65))
        assertFalse(VScreenDetector.isVerified(ctx, shaA, 65))
        assertEquals(3, VScreenDetector.noteVerifiedRun(ctx, shaA, 65))
        assertTrue("3 clean runs must mark (sha, versionCode) verified", VScreenDetector.isVerified(ctx, shaA, 65))
        assertFalse("new versionCode must re-verify", VScreenDetector.isVerified(ctx, shaA, 66))
        assertFalse("new model sha must re-verify", VScreenDetector.isVerified(ctx, shaB, 65))
    }

    // ── E + C4: SeekSearch numeric parsing, overshoot reversal, start pre-check, timeouts, non-numeric ──

    @Test
    fun eAndC4SeekSearchNumericOvershootStartMatchTimeoutsAndNonNumeric() {
        // 1) Number parsing (%, decimal, mm:ss, hh:mm:ss, fullwidth).
        assertEquals(170.0, SeekSearch.parseNumber("170%")!!, 1e-6)
        assertEquals(1.5, SeekSearch.parseNumber("1.5x")!!, 1e-6)
        assertEquals(90.0, SeekSearch.parseNumber("1:30")!!, 1e-6)
        assertEquals(3723.0, SeekSearch.parseNumber("01:02:03")!!, 1e-6)
        assertEquals(170.0, SeekSearch.parseNumber("１７０％")!!, 1e-6)
        assertNull(SeekSearch.parseNumber("普通文字"))
        assertEquals(170.0, SeekSearch.extractTargetNumber(listOf(Cond("text:170%", CondKind.TEXT, text = "170%")))!!, 1e-6)
        assertNull(SeekSearch.extractTargetNumber(listOf(Cond("text:17*", CondKind.TEXT, text = "17*"))))

        // 2) C4: Start already satisfied -> returns alreadyAtStart=true with ZERO touch down / move calls.
        var downCount = 0
        var moveCount = 0
        val startHit = SeekSearch.run(
            p0 = 600 to 800,
            p1 = 100 to 800,
            targetNum = null,
            maxMs = 10_000L,
            clockMs = { 0L },
            checkInterrupt = {},
            readStart = { SeekSearch.SeekReading(hit = "174%", metKind = CondKind.TEXT, text = "174%") },
            onDown = { downCount++ },
            readAfterDown = { SeekSearch.SeekReading(null) },
            moveAndRead = { _, _ -> moveCount++; SeekSearch.SeekReading(null) },
        )
        assertTrue(startHit.met && startHit.alreadyAtStart)
        assertEquals(0, downCount)
        assertEquals(0, moveCount)
        val quietC4Step = StepOut("1", "drag 610 800 100 800 seek until text:17* in 360,560,740,740 10s", "done", "").apply {
            fact = "开始时已经是 174%，没有拖"
            alreadyAtStart = true
            waitedFor = "text:17* in 360,560,740,740"
            waitMet = true
            waitMs = 100L
        }
        val quietCard = PageBook.Card(1, null, emptyList(), "174%", 1, 1)
        val quietText = formatShortReply(1, quietCard, listOf(quietC4Step), 200L, emptyList())
        assertTrue("quiet reply must keep C4 start-match wording: $quietText", quietText.contains("开始时已经是 174%，没有拖"))

        // 3) 0..300 step-1 slider on 880px track: 10 distinct (init, target) pairs (increasing & decreasing) -> 10/10 exact!
        fun sliderVal(x: Int, stepSize: Int = 1): Int {
            val ratio = ((x - 100) / 880.0).coerceIn(0.0, 1.0)
            val raw = (ratio * 300.0).roundToInt().coerceIn(0, 300)
            return (raw / stepSize) * stepSize
        }
        val pairs = listOf(
            15 to 170, 260 to 170, 50 to 223, 285 to 88, 10 to 145,
            290 to 42, 80 to 275, 210 to 119, 120 to 201, 195 to 63,
        )
        var exactHits = 0
        var totalReversals = 0
        for ((initV, targetV) in pairs) {
            val initX = 100 + (880.0 * initV / 300.0).roundToInt()
            val endX = if (targetV > initV) 980 else 100
            var clock = 0L
            fun readAt(x: Int): SeekSearch.SeekReading {
                val v = sliderVal(x)
                val label = "${v}%"
                return SeekSearch.SeekReading(
                    hit = if (v == targetV) label else null,
                    metKind = if (v == targetV) CondKind.TEXT else null,
                    text = label,
                )
            }
            val r = SeekSearch.run(
                p0 = initX to 800,
                p1 = endX to 800,
                targetNum = targetV.toDouble(),
                maxMs = 20_000L,
                clockMs = { clock },
                checkInterrupt = {},
                readStart = { readAt(initX) },
                onDown = {},
                readAfterDown = { readAt(initX) },
                moveAndRead = { pt, _ -> clock += 40L; readAt(pt.first) },
            )
            if (r.met && sliderVal(r.finalPt.first) == targetV) exactHits++
            totalReversals += r.reversals
        }
        assertEquals("all 10 exact slider trials must hit exact target", 10, exactHits)
        assertTrue("overshoot search must reverse & narrow step", totalReversals > 0)

        // 4) Unreachable odd target on step-2 slider -> terminates cleanly with closest value + coordinates.
        var cUnreach = 0L
        val unreach = SeekSearch.run(
            p0 = 100 to 800,
            p1 = 980 to 800,
            targetNum = 171.0,
            maxMs = 20_000L,
            clockMs = { cUnreach },
            checkInterrupt = {},
            readStart = { SeekSearch.SeekReading(null, text = "0%") },
            onDown = {},
            readAfterDown = { SeekSearch.SeekReading(null, text = "0%") },
            moveAndRead = { pt, _ ->
                cUnreach += 30L
                val v = sliderVal(pt.first, stepSize = 2)
                SeekSearch.SeekReading(hit = null, text = "${v}%")
            },
        )
        assertFalse(unreach.met)
        assertTrue("must report closest value & coords: ${unreach.missReason}", unreach.missReason!!.contains("最接近的是") && (unreach.bestText == "170%" || unreach.bestText == "172%"))
        assertNotNull(unreach.bestPt)

        // 5) Consecutive read timeouts stop cleanly with notRead=true and "读数跟不上".
        var cTo = 0L
        val timedOut = SeekSearch.run(
            p0 = 100 to 800,
            p1 = 980 to 800,
            targetNum = 170.0,
            maxMs = 20_000L,
            clockMs = { cTo },
            checkInterrupt = {},
            readStart = { SeekSearch.SeekReading(null, text = "10%") },
            onDown = {},
            readAfterDown = { SeekSearch.SeekReading(null, text = "10%") },
            moveAndRead = { _, _ -> cTo += 1500L; SeekSearch.SeekReading(null, timedOut = true) },
        )
        assertFalse(timedOut.met)
        assertTrue(timedOut.notRead)
        assertTrue(timedOut.missReason!!.contains("读数跟不上"))

        // 6) Non-numeric adaptive step: consecutive text changes shrink step size.
        val movedXs = ArrayList<Int>()
        var cNon = 0L
        val nonNum = SeekSearch.run(
            p0 = 100 to 800,
            p1 = 500 to 800,
            targetNum = null,
            maxMs = 10_000L,
            maxSteps = 10,
            clockMs = { cNon },
            checkInterrupt = {},
            readStart = { SeekSearch.SeekReading(null, text = "item-0") },
            onDown = {},
            readAfterDown = { SeekSearch.SeekReading(null, text = "item-0") },
            moveAndRead = { pt, stepIdx ->
                cNon += 30L
                movedXs += pt.first
                SeekSearch.SeekReading(hit = if (stepIdx == 6) "TARGET" else null, metKind = CondKind.TEXT, text = "item-$stepIdx")
            },
        )
        assertTrue(nonNum.met)
        assertTrue("step delta must shrink on rapidly changing non-numeric text: $movedXs", (movedXs[3] - movedXs[2]) < (movedXs[1] - movedXs[0]))
    }

    // ── G: Unified Placeable coordinates (cutout + system bars) and landscape corner zone ──

    @Test
    fun gOverlayPlaceableCoordinatesAndLandscapeCornerZone() {
        // Landscape phone 2400x1080 with 120px left display cutout and 48px bottom nav bar.
        val area = WindowSizes.computePlaceable(
            fullW = 2400,
            fullH = 1080,
            insetLeft = 120,
            insetTop = 36,
            insetRight = 0,
            insetBottom = 48,
        )
        assertEquals(120, area.left)
        assertEquals(36, area.top)
        assertEquals(2400, area.right)
        assertEquals(1032, area.bottom)
        assertEquals(2280, area.width)
        assertEquals(996, area.height)

        // Effective corner zone in landscape is capped at 25% of placeable height (996 * 0.25 = 249px), never taking half the screen.
        assertTrue(WindowSizes.inMiniCorner(2350f, 80f, area, zone = 330f))
        assertFalse("below 25% height cap must NOT trigger mini corner", WindowSizes.inMiniCorner(2350f, 350f, area, zone = 330f))
        assertFalse("away from right edge must NOT trigger mini corner", WindowSizes.inMiniCorner(2000f, 80f, area, zone = 330f))
    }

    // ── H: Changelog selection, audience filtering, Route mapping, and shared AgentNews source ──

    @Test
    fun hChangelogSelectionAudienceRoutingAndSharedAgentNews() {
        // Fresh install must NOT pop changelog.
        assertTrue(Changes.selectAutoShow(savedCode = -1, isExistingInstall = false, currentCode = 65).isEmpty())

        // Existing install upgrading from pre-changelog build pops beta8.
        val preUpgrade = Changes.selectAutoShow(savedCode = -1, isExistingInstall = true, currentCode = 65)
        assertEquals(listOf(65), preUpgrade.map { it.versionCode })

        // Beta7 -> Beta8 upgrade shows beta8 user-visible items only (no AGENT-only items).
        val betaUpgrade = Changes.selectAutoShow(savedCode = 64, isExistingInstall = true, currentCode = 65)
        assertEquals(listOf(65), betaUpgrade.map { it.versionCode })
        val items = betaUpgrade.single().items
        assertTrue(items.all { it.audience == Changes.Audience.USER || it.audience == Changes.Audience.BOTH })
        assertTrue("must cover public checklist route", items.any { it.route == Route.PublicAccess })
        assertTrue("must cover security level route", items.any { it.route == Route.Security })
        assertTrue("must cover floating window route", items.any { it.route == Route.FloatingWindow })
        assertTrue("must mention changelog itself", items.any { it.zhTitle.contains("更新日志") })

        // Already seen current version -> empty.
        assertTrue(Changes.selectAutoShow(savedCode = 65, isExistingInstall = true, currentCode = 65).isEmpty())

        // Real VScreenApp startup order: fresh install runs Changes.init BEFORE ApiConfig.init and must NOT pop.
        ctx.getSharedPreferences("agent_vscreen_changes", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("agent_vscreen_api", Context.MODE_PRIVATE).edit().clear().commit()
        Changes.init(ctx, currentCode = 65)
        ApiConfig.init(ctx)
        assertTrue("fresh install must not pop changelog even after ApiConfig.init writes token",
            Changes.consumeLaunchAutoShow(ctx, currentCode = 65).isEmpty())

        // Pre-changelog upgrade: agent_vscreen_api already populated before Changes.init -> must pop beta8.
        ctx.getSharedPreferences("agent_vscreen_changes", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("agent_vscreen_api", Context.MODE_PRIVATE).edit().putString("token", "legacy-token").commit()
        Changes.init(ctx, currentCode = 65)
        ApiConfig.init(ctx)
        val upgradeShown = Changes.consumeLaunchAutoShow(ctx, currentCode = 65)
        assertEquals(listOf(65), upgradeShown.map { it.versionCode })
        Changes.markSeen(ctx, 65)
        assertTrue(Changes.consumeLaunchAutoShow(ctx, currentCode = 65).isEmpty())

        // AgentNews shares the exact same Changes data source.
        assertEquals("2.0.0-beta8", AgentNews.nameOf(65))
        val agentItems = Changes.agentItemsSince(64, 65)
        assertTrue("agent items must include AGENT and BOTH beta8 entries", agentItems.isNotEmpty())
        assertTrue(agentItems.all { it.second.audience == Changes.Audience.AGENT || it.second.audience == Changes.Audience.BOTH })
        AgentLedger.init(ctx)
        val notice = AgentNews.notice("tok_news_test", freshStretch = true)
        assertNotNull(notice)
        assertTrue(notice!!.contains(com.kilon.agentvscreen.BuildConfig.VERSION_NAME))
        assertTrue(notice.contains("seek"))
    }

    // ── I: Public source bucketing (/32 IPv4, /64 IPv6), per-source lockout isolation, coalesced notifications ──

    @Test
    fun iPublicSourceBucketingLockoutIsolationAndCoalescedPairingNotification() {
        // 1) Source bucketing: IPv4 /32, IPv6 /64, tailnet prefix.
        assertEquals("203.0.113.10", PublicOAuth.sourceBucket("203.0.113.10"))
        assertNotEquals(PublicOAuth.sourceBucket("203.0.113.10"), PublicOAuth.sourceBucket("203.0.113.11"))
        assertEquals(
            PublicOAuth.sourceBucket("2001:db8:aaaa:1111::1"),
            PublicOAuth.sourceBucket("2001:db8:aaaa:1111::cafe"),
        )
        assertNotEquals(
            PublicOAuth.sourceBucket("2001:db8:aaaa:1111::1"),
            PublicOAuth.sourceBucket("2001:db8:aaaa:2222::1"),
        )
        assertEquals("tailnet:100.64.1.2", PublicOAuth.sourceBucket("tailnet:100.64.1.2"))

        // 2) Per-source lockout isolation on PublicServer (two sources never affect each other; unknown Bearer never global).
        val secret = "beta8-test-public-secret"
        val base = "https://phone.tailnet.ts.net"
        val apiServer = ApiServer(ctx)
        apiServer.failLimiter.clock = { fakeNow.get() }
        apiServer.failLimiter.sleepFn = {}
        val pubServer = PublicServer(ctx, apiServer, secretProvider = { secret }, baseProvider = { base }, wakeService = {})

        fun pubReq(method: String, target: String, body: String = "", src: String? = null, auth: String? = null): ApiServer.Req {
            val h = LinkedHashMap<String, String>()
            h["host"] = "phone.tailnet.ts.net"
            h["x-avs-public"] = secret
            if (src != null) h["x-avs-public-src"] = src
            if (auth != null) h["authorization"] = auth
            if (body.isNotEmpty()) h["content-type"] = "application/json"
            val q = if ('?' in target) ApiServer.parseForm(target.substringAfter('?')) else emptyMap()
            return ApiServer.Req(method, target.substringBefore('?'), q, h, body.toByteArray(Charsets.UTF_8)).apply { peer = InetAddress.getLoopbackAddress() }
        }

        // Source A sends bad unknown Bearer tokens until locked out (429).
        var srcALocked = false
        for (i in 0 until 9) {
            val r = pubServer.route(pubReq("POST", "/mcp", """{"jsonrpc":"2.0","id":$i,"method":"tools/list"}""", src = "198.51.100.10", auth = "Bearer bogus_$i"))
            if (r.status == 429) { srcALocked = true; break }
        }
        assertTrue("source A must hit 429 lockout", srcALocked)

        // Source B is completely unaffected (401 discovery, NOT 429!).
        val srcBResp = pubServer.route(pubReq("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"initialize"}""", src = "198.51.100.20"))
        assertEquals("source B must not be locked out by source A", 401, srcBResp.status)

        // 3) Per-source pending pairing cap = 1, card shown right away, and open card cap = 3 (extra requests queue).
        val regBody = """{"client_name":"ClientA","redirect_uris":["http://127.0.0.1:6274/cb"],"grant_types":["authorization_code","refresh_token"],"response_types":["code"],"token_endpoint_auth_method":"none"}"""
        val regResp = pubServer.route(pubReq("POST", "/oauth/register", regBody, src = "198.51.100.20"))
        assertEquals(201, regResp.status)
        val cid = JSONObject(String(regResp.body)).getString("client_id")
        val challenge = "A".repeat(43)
        fun authQuery(state: String) = "/oauth/authorize?response_type=code&client_id=$cid&redirect_uri=${URLEncoder.encode("http://127.0.0.1:6274/cb", "UTF-8")}&code_challenge=$challenge&code_challenge_method=S256&resource=${URLEncoder.encode("$base/mcp", "UTF-8")}&scope=look&state=$state"
        fun extractCode(resp: ApiServer.Resp): String {
            val m = Regex("""<div class="code">([A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4})</div>""").find(String(resp.body))
            assertNotNull("pairing HTML must contain 8-char code", m)
            return m!!.groupValues[1]
        }

        // Source 1 creates 1st queued pairing -> 200; same source 2nd -> 429; its card is shown right away.
        val r1 = pubServer.route(pubReq("GET", authQuery("s1a"), src = "203.0.113.1"))
        assertEquals(200, r1.status)
        assertEquals(429, pubServer.route(pubReq("GET", authQuery("s1b"), src = "203.0.113.1")).status)
        assertNotNull("remote /oauth/authorize shows its StrongConfirm card right away", StrongConfirm.publicPairingNotificationSpec(fakeNow.get()))

        // Entering source 1's code reopens the StrongConfirm card with source address and single-pairing spec.
        val c1 = PublicOAuth.claimPairingByCode(ctx, extractCode(r1), openActivity = false)
        assertTrue("expected Opened, got $c1", c1 is PublicOAuth.ClaimResult.Opened)
        val spec1 = StrongConfirm.publicPairingNotificationSpec(fakeNow.get())
        assertNotNull(spec1)
        assertTrue("single pairing notification should show pairing code: ${spec1!!.second}", spec1.second.contains("配对码"))

        // Distinct sources 2, 3, and 4 are all accepted (no global DoS at /oauth/authorize).
        val r2 = pubServer.route(pubReq("GET", authQuery("s2"), src = "203.0.113.2"))
        val r3 = pubServer.route(pubReq("GET", authQuery("s3"), src = "203.0.113.3"))
        val r4 = pubServer.route(pubReq("GET", authQuery("s4"), src = "203.0.113.4"))
        assertEquals(200, r2.status)
        assertEquals(200, r3.status)
        assertEquals(200, r4.status)

        // Claiming 2 & 3 succeeds (active claimed cap = 3); claiming 4th returns TooManyActive; spec coalesces to "有 3 个配对请求".
        assertTrue(PublicOAuth.claimPairingByCode(ctx, extractCode(r2), openActivity = false) is PublicOAuth.ClaimResult.Opened)
        assertTrue(PublicOAuth.claimPairingByCode(ctx, extractCode(r3), openActivity = false) is PublicOAuth.ClaimResult.Opened)
        assertEquals(PublicOAuth.ClaimResult.TooManyActive, PublicOAuth.claimPairingByCode(ctx, extractCode(r4), openActivity = false))
        val spec3 = StrongConfirm.publicPairingNotificationSpec(fakeNow.get())
        assertNotNull(spec3)
        assertEquals("有 3 个配对请求", spec3!!.first)
    }

    // ── L: guide <topic>, no old command forms in agent copy, PageBook.pickTitle excludes iconButton ──

    @Test
    fun lGuideTopicAliasNoOldFormsInAgentTextAndPageTitleExcludesIconButtons() {
        val apiServer = ApiServer(ctx)

        // 1) `guide <topic>` matches `help <topic>` (`AgentGuide.topic`), unknown topic returns INVALID_ARGS listing TOPICS.
        for (topic in AgentGuide.TOPICS) {
            val r = apiServer.exec(listOf("guide", topic), "s_guide")
            assertEquals("guide $topic exitCode", 0, r.exitCode)
            assertEquals(AgentGuide.topic(topic) + "\n", r.output)
        }
        val bad = apiServer.exec(listOf("guide", "nonexistent_topic"), "s_guide")
        assertEquals(2, bad.exitCode)
        assertEquals("INVALID_ARGS", bad.code)
        for (topic in AgentGuide.TOPICS) {
            assertTrue("unknown topic reply must list $topic: ${bad.output}", bad.output.contains(topic))
        }

        // 2) Agent-facing prompts, guides, help, and report hints must NOT suggest any command in CommandSpec.OLD_FORMS.
        val oldCmds = CommandSpec.OLD_FORMS.keys
        assertTrue("OLD_FORMS should contain zoom and frames", "zoom" in oldCmds && "frames" in oldCmds)
        val oldCmdPatterns = oldCmds.map { k ->
            k to Regex("""(?:^|[\s`：:“"(])(?:vscreen\s+)?$k(?:\s+(?:x1|--|[-\d])|[`”")])""")
        }
        for (lang in listOf(I18n.Lang.ZH, I18n.Lang.EN)) {
            I18n.choice = lang
            val texts = LinkedHashMap<String, String>()
            texts["AgentGuide.guide"] = AgentGuide.guide()
            texts["AgentGuide.advice"] = AgentGuide.advice()
            texts["AgentGuide.memory"] = AgentGuide.memory("http://127.0.0.1:18765")
            texts["AgentGuide.onboard"] = AgentGuide.onboard("http://127.0.0.1:18765", "tok")
            texts["Codes.help"] = Codes.help()
            for (topic in AgentGuide.TOPICS) {
                texts["AgentGuide.topic($topic)"] = AgentGuide.topic(topic).orEmpty()
            }
            for (c in CommandSpec.commands) {
                if (c.name in oldCmds) continue
                texts["CommandSpec(${c.name})"] = c.help().joinToString("\n")
            }
            for ((label, body) in texts) {
                for ((oldName, re) in oldCmdPatterns) {
                    assertFalse(
                        "[$lang] $label must not suggest deprecated command '$oldName': ${re.find(body)?.value}",
                        re.containsMatchIn(body),
                    )
                }
            }
        }
        I18n.choice = I18n.Lang.ZH

        // 3) PageBook.pickTitle excludes clickable iconButton ("向上导航") and prefers toolbarTitle / heading.
        val navUp = El(1, "向上导航", Rect(0, 10, 120, 120), true, clickable = true, iconButton = true)
        val toolbarTitle = El(2, "显示和亮度", Rect(160, 24, 520, 110), true, toolbarTitle = true)
        val bodyText = El(3, "护眼模式", Rect(40, 260, 400, 340), true)
        assertEquals("显示和亮度", PageBook.pickTitle(listOf(navUp, bodyText, toolbarTitle), "com.android.settings/.DisplaySettings"))

        // Without toolbarTitle, heading=true wins over ordinary topmost text and iconButton.
        val headingEl = El(4, "播放设置", Rect(40, 200, 360, 270), true, heading = true)
        val smallChip = El(5, "全部", Rect(40, 130, 160, 180), true)
        assertEquals("播放设置", PageBook.pickTitle(listOf(navUp, smallChip, headingEl), "com.demo/.SettingsActivity"))

        // If only iconButton exists, falls back to activity name (never uses "向上导航").
        assertEquals("DisplaySettings", PageBook.pickTitle(listOf(navUp), "com.android.settings/.DisplaySettings"))
    }
}
