package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Parcel
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.AgentLedger
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.Gate
import com.kilon.agentvscreen.core.VScreenDisplayService
import com.kilon.agentvscreen.core.VScreenPerception
import com.kilon.agentvscreen.core.VScreenStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 2.0.0-beta5 (Stage 5: `--hold`, short-range `--on` rules, time hints, 5.7 carryover, 5.8 decisions) unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V200Beta5Test {

    private var savedLang: I18n.Lang = I18n.Lang.ZH

    @Before
    fun setUp() {
        savedLang = I18n.choice
        I18n.choice = I18n.Lang.ZH
        ActHold.resetForTest()
        Loud.resetForTest()
        AgentLedger.resetHoldDefaultsForTest()
        takeAdjusted()
    }

    @After
    fun tearDown() {
        I18n.choice = savedLang
        ActHold.resetForTest()
        Loud.resetForTest()
        AgentLedger.resetHoldDefaultsForTest()
        VScreenStore.setRule("com.example.askapp", null)
        VScreenStore.setRule("com.example.roapp", null)
        VScreenStore.setRule("com.example.blkapp", null)
        takeAdjusted()
    }

    private fun expectCode(expectedCode: String, block: () -> Unit) {
        try {
            block()
            fail("Expected ActionError($expectedCode), but succeeded")
        } catch (e: ActionError) {
            assertEquals("Unexpected error code (${e.message})", expectedCode, e.code)
        }
    }

    // ── 5.2 & 5.5: parseRule valid & invalid forms ───────────────────────────

    @Test
    fun parseRuleValidForms() {
        val r1 = parseRule("text:限时优惠 => tap 980,420")
        assertEquals(CondKind.TEXT, r1.cond.kind)
        assertEquals("限时优惠", r1.cond.text)
        assertEquals(StepKind.TAP, r1.step.kind)
        assertEquals(StepTarget.At(980, 420), r1.step.target)
        assertEquals(1, r1.times)

        val r2 = parseRule("text:点我 => tap 点我 x3")
        assertEquals(CondKind.TEXT, r2.cond.kind)
        assertEquals(StepKind.TAP, r2.step.kind)
        assertEquals(StepTarget.Words("点我"), r2.step.target)
        assertEquals(3, r2.times)

        val r3 = parseRule("color:540,600=#4CAF50 => key back x2")
        assertEquals(CondKind.COLOR, r3.cond.kind)
        assertEquals(StepKind.KEY, r3.step.kind)
        assertEquals("back", r3.step.text)
        assertEquals(2, r3.times)

        val r4 = parseRule("gone:加载中 => scroll down")
        assertEquals(CondKind.GONE, r4.cond.kind)
        assertEquals(StepKind.SCROLL, r4.step.kind)
        assertEquals("down", r4.step.text)
        assertEquals(1, r4.times)

        val r5 = parseRule("change:0,0,500,500 => wait 500ms x1")
        assertEquals(CondKind.CHANGE, r5.cond.kind)
        assertEquals(StepKind.SLEEP, r5.step.kind)
        assertEquals(500L, r5.step.ms)
        assertEquals(1, r5.times)

        // Quoted text containing `x5` must NOT be mistaken for a repeat suffix.
        val r6 = parseRule("app:com.example.foo => tap \"关闭 x5\"")
        assertEquals(CondKind.APP, r6.cond.kind)
        assertEquals(StepKind.TAP, r6.step.kind)
        assertEquals(StepTarget.Words("关闭 x5"), r6.step.target)
        assertEquals(1, r6.times)

        // Up to 3 rules allowed.
        val all3 = ActRules.parseAll(
            listOf(
                "text:限时优惠 => tap 980,420",
                "text:稍后再说 => tap 稍后再说 x2",
                "color:100,100=#FF0000 => key back",
            )
        )
        assertEquals(3, all3.size)
    }

    @Test
    fun parseRuleInvalidFormsRejectWithBadRule() {
        val bad = listOf(
            "",
            "text:弹窗 tap 100,100",
            "text:a => tap 100,100 => key back",
            "=> tap 100,100",
            "text:弹窗 =>",
            "stable => tap 100,100",
            "still => tap 100,100",
            "still:0,0,200,200 => tap 100,100",
            "text:a or text:b => tap 100,100",
            "text:弹窗 => type hello",
            "text:弹窗 => press 100 100 800ms",
            "text:弹窗 => drag 100 100 200 200",
            "text:弹窗 => swipe 100 100 200 200",
            "text:弹窗 => key enter",
            "text:弹窗 => key home",
            "text:弹窗 => use myseg",
            "text:弹窗 => try { tap 100,100 }",
            "text:弹窗 => if text:x { tap 100,100 }",
            "text:弹窗 => repeat 2 { tap 100,100 }",
            "text:弹窗 => tap 100,100; tap 200,200",
            "text:弹窗 => tap 100,100 until text:已关闭",
            "text:弹窗 => wait text:好了 2s",
            "text:弹窗 => tap 100,100 x0",
            "text:弹窗 => tap 100,100 x4",
            "text:弹窗 => tap 100,100 x-1",
        )
        for (raw in bad) {
            expectCode("BAD_RULE") { parseRule(raw) }
        }
        // More than 3 rules -> BAD_RULE
        expectCode("BAD_RULE") {
            ActRules.parseAll(
                listOf(
                    "text:a => tap 10,10",
                    "text:b => tap 20,20",
                    "text:c => tap 30,30",
                    "text:d => tap 40,40",
                )
            )
        }
    }

    @Test
    fun repeatableOnOptionAndScopeCheck() {
        val argv = CommandSpec.argvOf(
            CommandSpec.of("act")!!,
            "tap 确定 until text:已确定",
            JSONObject()
                .put("on", JSONArray(listOf("text:A => tap 10,10", "text:B => key back")))
                .put("hold", true),
        )
        val parsed = VScreenOptions.args("act", argv.drop(1))
        assertEquals(listOf("text:A => tap 10,10", "text:B => key back"), parsed.getAll("on"))
        assertTrue(parsed.hasFlag("hold"))

        val actCmd = CommandSpec.of("act")!!
        // Bare look is Scope.LOOK, but adding --on or --hold requires Scope.OPERATE.
        assertEquals(CommandSpec.Scope.LOOK, actCmd.scopeFor(VScreenOptions.args("act", emptyList())))
        assertEquals(CommandSpec.Scope.OPERATE, actCmd.scopeFor(VScreenOptions.args("act", listOf("--hold"))))
        assertEquals(CommandSpec.Scope.OPERATE, actCmd.scopeFor(VScreenOptions.args("act", listOf("--on", "text:A => tap 10,10"))))
    }

    // ── 5.2 & 5.8 #3: shared Persistence & RuleLive debounce ─────────────────

    @Test
    fun sharedPersistenceDeduplicatesCachedObservations() {
        // Same cached reading polled 4 times counts as 1 observation, NOT 2.
        val cached = listOf(Obs(-10L, true), Obs(-10L, true), Obs(-10L, true), Obs(-10L, true))
        assertEquals(1, Persistence.maxRun(cached))
        assertEquals(1, Persistence.trailingRun(cached))
        assertFalse(Persistence.persisted(cached))
        assertFalse(Persistence.trailingPersisted(cached))

        // Two distinct observation IDs persist.
        val twoDistinct = listOf(Obs(-10L, true), Obs(-10L, true), Obs(-11L, true))
        assertEquals(2, Persistence.maxRun(twoDistinct))
        assertEquals(2, Persistence.trailingRun(twoDistinct))
        assertTrue(Persistence.persisted(twoDistinct))
        assertTrue(Persistence.trailingPersisted(twoDistinct))

        // Flicker resets trailing run.
        val flicker = listOf(Obs(1L, true), Obs(2L, false), Obs(3L, true))
        assertEquals(1, Persistence.maxRun(flicker))
        assertEquals(1, Persistence.trailingRun(flicker))
        assertFalse(Persistence.persisted(flicker))

        // 3.6 wordFactsFromReadings uses the same Persistence (1-reading glitch ignored; 2-reading kept).
        val base = VScreenPerception.Reading(1000L, listOf("首页"), mapOf("首页" to Rect(0, 0, 100, 40)))
        val r1 = VScreenPerception.Reading(
            1500L,
            listOf("首页", "闪现噪点", "新标签"),
            mapOf("首页" to Rect(0, 0, 100, 40), "闪现噪点" to Rect(0, 50, 100, 90), "新标签" to Rect(0, 100, 100, 140)),
        )
        val r2 = VScreenPerception.Reading(
            2000L,
            listOf("首页", "新标签"),
            mapOf("首页" to Rect(0, 0, 100, 40), "新标签" to Rect(0, 100, 100, 140)),
        )
        val facts = wordFactsFromReadings(listOf(base, r1, r2), screenH = 2400, toMs = 2500L)
        assertTrue("persisted word appears: ${facts.staticLines}", facts.staticLines.any { it.contains("新标签") })
        assertFalse("1-reading glitch filtered out: ${facts.staticLines}", facts.staticLines.any { it.contains("闪现噪点") })
        assertFalse("1-reading glitch not in between: ${facts.betweenLines}", facts.betweenLines.any { it.contains("闪现噪点") })
    }

    @Test
    fun ruleLiveDebounceCooldownAndDeactivationReasons() {
        val specText = parseRule("text:限时优惠 => tap 980,420 x2")
        val live = RuleLive(specText, index = 1, initialLastFalseEpochMs = 1_000L)
        assertTrue(live.needsDebounce)
        assertTrue(live.active)

        // Cached OCR reading (-5) polled repeatedly while false only records 1st timestamp (1_200L).
        assertFalse(live.recordObservation(-5L, false, 1_200L))
        assertFalse(live.recordObservation(-5L, false, 2_500L))
        assertEquals(1_200L, live.lastFalseObsEpochMs)

        // First true observation (-6) polled 3 times -> still only 1 independent obs, does not fire.
        assertFalse(live.recordObservation(-6L, true, 3_000L))
        assertFalse(live.recordObservation(-6L, true, 3_100L))
        assertFalse(live.recordObservation(-6L, true, 3_200L))
        assertEquals(1, live.trailingTrueCount())

        // Second distinct true observation (-7) -> fires!
        assertTrue(live.recordObservation(-7L, true, 3_500L))
        assertEquals(1_200L, live.lastFalseObsEpochMs)
        live.markFired(nowElapsedMs = 5_000L)
        assertEquals(1, live.fired)
        assertTrue(live.active)

        // 500ms cooldown enforced.
        assertFalse(live.cooldownReady(5_200L))
        assertFalse(live.cooldownReady(5_499L))
        assertTrue(live.cooldownReady(5_500L))

        // Fire 2nd time -> reaches x2 limit (Deactivation Case 1).
        assertFalse(live.recordObservation(10L, true, 6_000L))
        assertTrue(live.recordObservation(11L, true, 6_100L))
        live.markFired(nowElapsedMs = 6_100L)
        assertFalse(live.active)
        assertNotNull(live.off)
        assertTrue(live.off!!.contains("2"))

        // Color rule does NOT need 2-reading debounce.
        val colorLive = RuleLive(parseRule("color:100,100=#FF0000 => tap 100,100"), index = 2, initialLastFalseEpochMs = 500L)
        assertFalse(colorLive.needsDebounce)
        assertFalse(colorLive.recordObservation(null, false, 600L))
        assertEquals(600L, colorLive.lastFalseObsEpochMs)
        assertTrue(colorLive.recordObservation(null, true, 700L))

        // Deactivation Cases 2, 3, 4 via disable() and non-blocking RULE_OFF note.
        val notes = Notes()
        for (reason in listOf("前台应用已切换", "找不到目标", "执行后画面没有变化")) {
            val r = RuleLive(specText, index = 1)
            r.disable(reason)
            assertFalse(r.active)
            assertEquals(reason, r.off)
            notes.add("RULE_OFF", reason)
        }
        notes.add("HOLD_UNSUPPORTED", "API 30")
        assertNull("RULE_OFF and HOLD_UNSUPPORTED must be non-blocking INFO notes", notes.firstBlocking())
    }

    // ── 5.2 & 5.5: Rule step hitting payGuard / Guard.Ask refuses act without Consent ──

    @Test
    fun ruleStepHittingPayGuardOrAppRuleRefusesImmediatelyWithoutConsent() {
        val ctx = RuntimeEnvironment.getApplication()
        VScreenStore.init(ctx)
        val handler = VScreenOffloadHandler(ctx)

        // 1) Payment button -> payGuardCheck(fromRule = true) throws StepRefused("CONFIRM_PENDING") immediately.
        try {
            handler.payGuardCheck("sid-rule-pay", 77, "立即支付", 540, 1800, fromRule = true)
            fail("Expected StepRefused for payment button in rule")
        } catch (e: StepRefused) {
            assertEquals("CONFIRM_PENDING", e.code)
        }

        // 2) Irreversible button -> also refused immediately in rule.
        try {
            handler.payGuardCheck("sid-rule-del", 77, "确认删除", 540, 1800, fromRule = true)
            fail("Expected StepRefused for irreversible button in rule")
        } catch (e: StepRefused) {
            assertEquals("CONFIRM_PENDING", e.code)
        }

        // 3) Ordinary safe button -> allowed (does not throw).
        handler.payGuardCheck("sid-rule-ok", 77, "确定", 540, 1800, fromRule = true)

        // 4) Per-app Guard rules (ASK / READONLY / BLOCK) all refuse immediately for rule steps without Consent!
        VScreenStore.setRule("com.example.askapp", VScreenStore.Rule.ASK)
        VScreenStore.setRule("com.example.roapp", VScreenStore.Rule.READONLY)
        VScreenStore.setRule("com.example.blkapp", VScreenStore.Rule.BLOCK)

        val tapStep = Steps.parse("tap 500 500")
        try {
            handler.appRuleForPkg("com.example.askapp", tapStep, fromRule = true)
            fail("Expected StepRefused(CONFIRM_PENDING) for ASK app in rule")
        } catch (e: StepRefused) {
            assertEquals("CONFIRM_PENDING", e.code)
        }
        try {
            handler.appRuleForPkg("com.example.roapp", tapStep, fromRule = true)
            fail("Expected StepRefused(APP_READ_ONLY) for READONLY app in rule")
        } catch (e: StepRefused) {
            assertEquals("APP_READ_ONLY", e.code)
        }
        try {
            handler.appRuleForPkg("com.example.blkapp", tapStep, fromRule = true)
            fail("Expected StepRefused(APP_BLOCKED) for BLOCK app in rule")
        } catch (e: StepRefused) {
            assertEquals("APP_BLOCKED", e.code)
        }

        // Outcome "refused" produces Loud.FAILED.
        assertTrue(Loud.FAILED in loudReasons(LoudInput(outcome = "refused")))
    }

    // ── 5.2 & 5.8 #2: excludeWindows claims popup appearance + dismissal ─────

    @Test
    fun excludeWindowsClaimsPopupAppearanceAndDismissalWhileCatchingOutsideChanges() {
        fun makeScreen(withPopup: Boolean, bannerRed: Boolean = false): Bitmap {
            val bmp = Bitmap.createBitmap(160, 320, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            val p = Paint()
            if (bannerRed) {
                p.color = Color.RED
                c.drawRect(0f, 0f, 160f, 100f, p)
            }
            if (withPopup) {
                c.drawColor(Color.argb(170, 0, 0, 0))
                p.color = Color.WHITE
                c.drawRect(12f, 50f, 148f, 180f, p)
            }
            return bmp
        }

        val f0 = makeScreen(withPopup = false)
        val f1 = makeScreen(withPopup = true)   // popup appears at t=1000
        val f2 = makeScreen(withPopup = false)  // rule closes popup at t=1400 (settled)
        val f3 = makeScreen(withPopup = false)  // later steady frame at t=3200
        val fOutside = makeScreen(withPopup = false, bannerRed = true) // unrelated change at t=3500
        try {
            val seq = listOf(0L to f0, 1_000L to f1, 1_400L to f2, 3_200L to f3)

            // Without excludeWindows: popup appearance & dismissal is detected as significant change.
            val raw = analyzeFrameSequence(seq, 1080, 2400, mask = null)
            assertTrue("without excludeWindows, popup must be detected", raw.hasSignificantChange)

            // With claimed window from lastFalseObs+1 (800) to ruleEnd+1500 (2900): both popup show & close are claimed!
            val claimed = analyzeFrameSequence(seq, 1080, 2400, mask = null, excludeWindows = listOf(800L..2_900L))
            assertFalse("with excludeWindows, claimed popup must not trigger BEFORE/AFTER", claimed.hasSignificantChange)
            assertTrue(claimed.spans.isEmpty())

            // Unrelated change AFTER the claimed window is still detected.
            val withOutsideAfter = analyzeFrameSequence(
                seq + (3_500L to fOutside), 1080, 2400, mask = null, excludeWindows = listOf(800L..2_900L)
            )
            assertTrue("unrelated change after claimed window must still trigger", withOutsideAfter.hasSignificantChange)

            // Unrelated change BEFORE the claimed window is still detected.
            val withOutsideBefore = analyzeFrameSequence(
                listOf(0L to f0, 400L to fOutside, 1_000L to f1, 1_400L to f2, 3_200L to f3),
                1080, 2400, mask = null, excludeWindows = listOf(800L..2_900L),
            )
            assertTrue("unrelated change before claimed window must still trigger", withOutsideBefore.hasSignificantChange)
        } finally {
            listOf(f0, f1, f2, f3, fOutside).forEach { it.recycle() }
        }
    }

    // ── 5.1 & 5.8 #1: Gate HOLD / UNHOLD, WCHAN retry, startup cleanup, auto-unhold ──

    @Test
    fun gateHoldUnholdWchanRetryAndAutoExpiry() {
        val tmpDir = RuntimeEnvironment.getApplication().cacheDir
        val holdFile = File(tmpDir, "test-avs-holds-${System.nanoTime()}.txt")
        val now = AtomicLong(10_000L)
        val cmds = ArrayList<String>()
        var wchanReads = 0

        val gate = Gate(
            service = null,
            clock = Gate.Clock { now.get() },
            shell = Gate.Shell { argv, _ ->
                cmds += argv.joinToString(" ")
                0
            },
            startWatchThread = false,
            holdFile = holdFile,
            readWchan = { _ ->
                wchanReads++
                // First WCHAN check reports still in do_freezer_trap -> triggers 1 retry of `am unfreeze`!
                val state = if (wchanReads == 1) Gate.FREEZER_WCHAN else "ep_poll"
                listOf(state to "com.example.game")
            },
            cleanupOnInit = false,
        )
        try {
            val frozen = gate.hold(listOf("com.example.game", "com.example.game:push"), Gate.HOLD_MAX_MS)
            assertEquals(2, frozen)
            assertTrue("holdFile created", holdFile.isFile)
            assertEquals(listOf("com.example.game", "com.example.game:push"), holdFile.readLines())
            assertEquals(2, gate.statusJson().getJSONArray("held").length())
            assertTrue(cmds.contains("am freeze --sticky com.example.game"))
            assertTrue(cmds.contains("am freeze --sticky com.example.game:push"))

            // 5.8 #1: unhold runs `am unfreeze --sticky` THEN `am unfreeze`, checks WCHAN, retries `am unfreeze` on do_freezer_trap.
            cmds.clear()
            val thawed = gate.unhold()
            assertEquals(2, thawed)
            assertFalse("holdFile deleted on unhold", holdFile.exists())
            assertEquals(0, gate.statusJson().getJSONArray("held").length())
            val idxSticky = cmds.indexOf("am unfreeze --sticky com.example.game")
            val idxPlain = cmds.indexOf("am unfreeze com.example.game")
            assertTrue("sticky must run before plain unfreeze: $cmds", idxSticky in 0 until idxPlain)
            assertEquals("still-trapped process gets 1 retry am unfreeze: $cmds", 2, cmds.count { it == "am unfreeze com.example.game" })

            // Max duration auto-unhold (120s).
            gate.beat()
            assertEquals(1, gate.hold(listOf("com.example.game"), 30_000L))
            now.addAndGet(5_000L)
            gate.beat()
            assertFalse(gate.checkHoldExpiry())
            now.addAndGet(26_000L)
            gate.beat()
            assertTrue("expired after maxMs", gate.checkHoldExpiry())
            assertEquals(0, gate.statusJson().getJSONArray("held").length())

            // App heartbeat stale > 10s auto-unhold.
            gate.beat()
            assertEquals(1, gate.hold(listOf("com.example.game"), Gate.HOLD_MAX_MS))
            now.addAndGet(Gate.HOLD_STALE_MS - 1L)
            assertFalse(gate.checkHoldExpiry())
            now.addAndGet(2L)
            assertTrue("auto-unhold when app heartbeat stale > 10s", gate.checkHoldExpiry())
            assertEquals(0, gate.statusJson().getJSONArray("held").length())

            // stop() must also unhold.
            gate.beat()
            assertEquals(1, gate.hold(listOf("com.example.game"), Gate.HOLD_MAX_MS))
            gate.stop()
            assertEquals(0, gate.statusJson().getJSONArray("held").length())
            assertFalse(holdFile.exists())
        } finally {
            gate.stop()
            holdFile.delete()
        }
    }

    @Test
    fun gateStartupCleansLeftoverHoldFileAndServiceBinderOp4And5() {
        val tmpDir = RuntimeEnvironment.getApplication().cacheDir
        val holdFile = File(tmpDir, "test-avs-leftover-${System.nanoTime()}.txt")
        holdFile.writeText("com.example.leftover\n")
        val cmds = ArrayList<String>()
        val gate = Gate(
            service = null,
            shell = Gate.Shell { argv, _ ->
                cmds += argv.joinToString(" ")
                0
            },
            startWatchThread = false,
            holdFile = holdFile,
            readWchan = { _ -> listOf("ep_poll" to "com.example.leftover") },
            cleanupOnInit = true,
        )
        try {
            assertFalse("leftover holdFile deleted on Gate init", holdFile.exists())
            assertTrue("leftover sticky cleared: $cmds", cmds.contains("am unfreeze --sticky com.example.leftover"))
            assertTrue("leftover plain unfreeze ran: $cmds", cmds.contains("am unfreeze com.example.leftover"))
        } finally {
            gate.stop()
            holdFile.delete()
        }

        // Pure WCHAN parser check.
        val parsedWchan = Gate.parseWchanOutput("WCHAN                            NAME\ndo_freezer_trap                  com.example.a\nep_poll                          com.example.b\n")
        assertEquals(listOf("do_freezer_trap" to "com.example.a", "ep_poll" to "com.example.b"), parsedWchan)

        // VScreenDisplayService GATE binder op 4 (HOLD) and op 5 (UNHOLD) + PROTOCOL == 17.
        assertEquals(17, VScreenDisplayService.PROTOCOL)
        val svc = VScreenDisplayService()
        fun transactGate(write: (Parcel) -> Unit, read: (Parcel) -> Unit) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                write(data)
                assertTrue(svc.transact(VScreenDisplayService.GATE, data, reply, 0))
                reply.setDataPosition(0)
                assertEquals(0, reply.readInt())
                read(reply)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        try {
            // Op 5 UNHOLD with nothing held returns 0 cleanly.
            var unheld = -1
            transactGate({ it.writeInt(5) }) { unheld = it.readInt() }
            assertEquals(0, unheld)
        } finally {
            transactGate({ it.writeInt(2) }) { /* STOP */ }
        }
    }

    // ── 5.1 & 5.6: ActHold coordinator, exclusions, race cancellation ────────

    @Test
    fun actHoldCoordinatorExclusionsApi30FallbackAndPostSendRace() {
        // Pure process-name parser: main process first, deduplicated, capped at 8.
        val psOut = buildString {
            appendLine("NAME")
            appendLine("com.other.app")
            appendLine("com.example.app:remote")
            appendLine("com.example.app")
            appendLine("com.example.app:remote")
            for (k in 1..10) appendLine("com.example.app:w$k")
        }
        val procs = ActHold.parseProcessNames(psOut, "com.example.app")
        assertEquals(Gate.HOLD_MAX_PROCS, procs.size)
        assertEquals("com.example.app", procs.first())
        assertEquals(procs.distinct(), procs)

        // Exclusions: handoffSkip, main-screen foreground, takeover, teach, manual.
        val skip = setOf("com.kilon.agentvscreen", "com.android.systemui")
        assertNotNull(ActHold.exclusionReason("com.kilon.agentvscreen", skip, null, takeover = false, teaching = false, manual = false))
        assertNotNull(ActHold.exclusionReason("com.example.app", skip, "com.example.app", takeover = false, teaching = false, manual = false))
        assertNotNull(ActHold.exclusionReason("com.example.app", skip, null, takeover = true, teaching = false, manual = false))
        assertNotNull(ActHold.exclusionReason("com.example.app", skip, null, takeover = false, teaching = true, manual = false))
        assertNotNull(ActHold.exclusionReason("com.example.app", skip, null, takeover = false, teaching = false, manual = true))
        assertNull(ActHold.exclusionReason("com.example.app", skip, "com.other.main", takeover = false, teaching = false, manual = false))

        // API 30 -> Unsupported (HOLD_UNSUPPORTED), never freezes.
        ActHold.sdkIntFn = { 30 }
        ActHold.gateReadyFn = { true }
        val api30 = ActHold.prepare("s1", 2, "com.example.app", "Demo", skip, null)
        assertTrue("API 30 must return Unsupported: $api30", api30 is ActHold.PrepareOutcome.Unsupported)

        // API 34 + eligible -> Armed; post-send freezes; next command unholds with paused duration.
        val fakeClock = AtomicLong(100_000L)
        val heldCalls = ArrayList<List<String>>()
        var unholdCalls = 0
        ActHold.sdkIntFn = { 34 }
        ActHold.clockMsFn = { fakeClock.get() }
        ActHold.gateReadyFn = { true }
        ActHold.isDisplayBusyFn = { null }
        ActHold.psNamesFn = { "NAME\ncom.example.app\ncom.example.app:sub\n" }
        ActHold.gateHoldFn = { ps, _ -> heldCalls += ps; ps.size }
        ActHold.gateUnholdFn = { unholdCalls++; 1 }

        val armed = ActHold.prepare("s1", 2, "com.example.app", "Demo", skip, null) as ActHold.PrepareOutcome.Armed
        assertTrue(armed.footerLine.contains("已暂停 Demo"))
        assertTrue(armed.footerLine.contains("120"))
        assertTrue("not frozen before afterSend", heldCalls.isEmpty())

        armed.afterSend()
        assertEquals(listOf(listOf("com.example.app", "com.example.app:sub")), heldCalls)
        assertTrue(ActHold.isHeld())

        fakeClock.addAndGet(14_200L)
        val resumed = ActHold.unholdBeforeCommand()
        assertNotNull(resumed)
        assertEquals(1, unholdCalls)
        assertEquals(14_200L, resumed!!.pausedMs)
        assertEquals("已恢复（暂停了 14.2s）", resumed.line())
        assertFalse(ActHold.isHeld())

        // Race guard: if next command arrives BEFORE afterSend runs, afterSend must NOT freeze.
        heldCalls.clear()
        val armedRace = ActHold.prepare("s1", 2, "com.example.app", "Demo", skip, null) as ActHold.PrepareOutcome.Armed
        assertNull(ActHold.unholdBeforeCommand())
        armedRace.afterSend()
        assertTrue("cancelled pending hold must not call gateHold", heldCalls.isEmpty())
        assertFalse(ActHold.isHeld())

        // If gateHold fails (returns 0 / -1), subsequent prepare() returns Unsupported.
        ActHold.gateHoldFn = { _, _ -> 0 }
        val armedFail = ActHold.prepare("s1", 2, "com.example.app", "Demo", skip, null) as ActHold.PrepareOutcome.Armed
        armedFail.afterSend()
        assertFalse(ActHold.isHeld())
        val nextAfterFail = ActHold.prepare("s1", 2, "com.example.app", "Demo", skip, null)
        assertTrue(nextAfterFail is ActHold.PrepareOutcome.Unsupported)

        // Per-agent note hold on|off default.
        assertFalse(AgentLedger.holdDefault("agent-a"))
        AgentLedger.setHoldDefault("agent-a", true)
        assertTrue(AgentLedger.holdDefault("agent-a"))
        AgentLedger.setHoldDefault("agent-a", false)
        assertFalse(AgentLedger.holdDefault("agent-a"))
    }

    // ── 5.3: Time hints & consecutive BEFORE coaching note ───────────────────

    @Test
    fun timeHintFormattingAndConsecutiveBeforeCoachingNote() {
        assertEquals("", timeHintPart(null))
        assertEquals("", timeHintPart(-5L))
        assertEquals(" · 距上次回复 0.0s", timeHintPart(0L))
        assertEquals(" · 距上次回复 14.2s", timeHintPart(14_200L))
        assertEquals(" · 距上次回复 600.0s", timeHintPart(Report.TIME_HINT_MAX_MS))
        assertEquals("", timeHintPart(Report.TIME_HINT_MAX_MS + 1L))

        val card = PageBook.Card(
            id = 1,
            activity = "com.example.app/.ConfirmActivity",
            els = emptyList(),
            title = "确认页",
            seenTurn = 2,
            version = 2,
        ).apply {
            isNew = false
            canRef = true
            prevSeenTurn = 1
            prevEls = emptyList()
        }
        val step = StepOut("1", "tap 确定 until text:已确定", "done", "").apply {
            waitedFor = "text:已确定"; waitMs = 300L; waitMet = true
        }
        val rf = RuleFired(1, 10_000L, 1, "text:限时优惠", "tap 980,420", RuleFired.ContextKind.DURING_WAIT)
        val shortText = formatShortReply(
            turn = 2,
            card = card,
            steps = listOf(step),
            totalMs = 1_200L,
            els = emptyList(),
            sinceLastMs = 14_200L,
            rulesFired = listOf(rf),
            nowMs = 13_100L,
        )
        val firstLine = shortText.lineSequence().first()
        assertTrue("first line carries time hint: $firstLine", firstLine.contains("第 2 轮 · 距上次回复 14.2s"))
        assertTrue("short reply lists fired rule: $shortText", shortText.contains("规则 1：看到 text:限时优惠 → tap 980,420"))

        // Consecutive BEFORE hint: 3 times on SDK 34 without --hold -> true ONCE per session.
        assertFalse(Loud.recordBeforeAndShouldHint("sid-b", listOf(Loud.BEFORE), sdkInt = 34, usedHold = false))
        assertFalse(Loud.recordBeforeAndShouldHint("sid-b", listOf(Loud.BEFORE), sdkInt = 34, usedHold = false))
        assertTrue(Loud.recordBeforeAndShouldHint("sid-b", listOf(Loud.BEFORE), sdkInt = 34, usedHold = false))
        // Never repeats in the same session.
        assertFalse(Loud.recordBeforeAndShouldHint("sid-b", listOf(Loud.BEFORE), sdkInt = 34, usedHold = false))

        // Quiet reply resets streak.
        assertFalse(Loud.recordBeforeAndShouldHint("sid-r", listOf(Loud.BEFORE), sdkInt = 34, usedHold = false))
        assertFalse(Loud.recordBeforeAndShouldHint("sid-r", listOf(Loud.BEFORE), sdkInt = 34, usedHold = false))
        assertFalse(Loud.recordBeforeAndShouldHint("sid-r", emptyList(), sdkInt = 34, usedHold = false))
        assertFalse(Loud.recordBeforeAndShouldHint("sid-r", listOf(Loud.BEFORE), sdkInt = 34, usedHold = false))
        assertFalse(Loud.recordBeforeAndShouldHint("sid-r", listOf(Loud.BEFORE), sdkInt = 34, usedHold = false))
        assertTrue(Loud.recordBeforeAndShouldHint("sid-r", listOf(Loud.BEFORE), sdkInt = 34, usedHold = false))

        // SDK < 34 never shows hint.
        repeat(4) {
            assertFalse(Loud.recordBeforeAndShouldHint("sid-33", listOf(Loud.BEFORE), sdkInt = 33, usedHold = false))
        }
        // Using --hold never shows hint.
        repeat(4) {
            assertFalse(Loud.recordBeforeAndShouldHint("sid-hold", listOf(Loud.BEFORE), sdkInt = 34, usedHold = true))
        }
    }

    // ── 5.7 #2 & #3: img_age_ms JSON & plain 2-point drag omitting unknown ───

    @Test
    fun carryoverImgAgeMsAndPlainDragUnknownOmission() {
        // 5.7 #2: GestureRecord & StepOut emit img_age_ms when present.
        val gWithAge = GestureRecord(pickedUp = null, followed = null, afterRelease = Gesture.AFTER_UNKNOWN, score = 0.0, imgAgeMs = 42L)
        assertEquals(42L, gWithAge.json().getLong("img_age_ms"))

        val so = StepOut("1", "press 540 1600 hold until color:220,1340=#16A34A~40 8s", "done", "ok").apply {
            pollMsP50 = 2L
            pollMsMax = 8L
            imgAgeMs = 37L
        }
        val gj = so.json().getJSONObject("gesture")
        assertEquals(37L, gj.getLong("img_age_ms"))
        assertEquals(2L, gj.getLong("poll_ms_p50"))
        assertEquals(8L, gj.getLong("poll_ms_max"))

        // 5.7 #3: plain 2-point drag omits "松手后位置不明" when picked_up == null && after_release == unknown.
        val unpickedUnknown = GestureRecord(pickedUp = null, followed = null, afterRelease = Gesture.AFTER_UNKNOWN, score = 0.0)
        assertTrue(unpickedUnknown.describe(omitUnknownWhenNotPicked = false).contains("松手后位置不明"))
        assertEquals("", unpickedUnknown.describe(omitUnknownWhenNotPicked = true))
        assertEquals("", unpickedUnknown.describeSuffix(omitUnknownWhenNotPicked = true))

        // When pickedUp == true, still reports "松手后位置不明".
        val pickedUnknown = GestureRecord(pickedUp = true, followed = true, afterRelease = Gesture.AFTER_UNKNOWN, score = 0.9)
        assertTrue(pickedUnknown.describe(omitUnknownWhenNotPicked = true).contains("松手后位置不明"))

        // 5.7 #1: lightweight colorFastMode refreshes preview + full-frame recording every 500ms.
        assertEquals(500L, com.kilon.agentvscreen.core.VScreenDisplay.COLOR_FAST_INTERVAL_MS)
        assertTrue(com.kilon.agentvscreen.core.VScreenDisplay.colorFastFrameDue(nowMs = 1_000L, lastFrameMs = 0L))
        assertFalse(com.kilon.agentvscreen.core.VScreenDisplay.colorFastFrameDue(nowMs = 1_499L, lastFrameMs = 1_000L))
        assertTrue(com.kilon.agentvscreen.core.VScreenDisplay.colorFastFrameDue(nowMs = 1_500L, lastFrameMs = 1_000L))
    }
}
