package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.AgentGuide
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.Meter
import com.kilon.agentvscreen.core.VScreenPerception
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2.0.0-beta4 (Stage 4: Closed-loop gestures & drag tracking) unit tests per spec 4.1–4.7.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V200Beta4Test {

    private var savedLang: I18n.Lang = I18n.Lang.ZH

    @Before
    fun setUp() {
        savedLang = I18n.choice
        I18n.choice = I18n.Lang.ZH
        takeAdjusted()
    }

    @After
    fun tearDown() {
        I18n.choice = savedLang
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

    // ── 4.1 & 4.2: Parsing hold / seek & validation errors ──────────────────

    @Test
    fun parseClosedLoopValidSyntax() {
        val s1 = Steps.parse("drag 200 1500 540 600 hold until color:540,600=#4CAF50 2s")
        assertEquals(StepKind.DRAG, s1.kind)
        assertEquals(Loop.HOLD, s1.loop)
        assertEquals(listOf(200 to 1500, 540 to 600), s1.points)
        assertEquals(600L, s1.ms)
        assertEquals(2_000L, s1.untilMs)
        assertEquals(CondKind.COLOR, s1.until!!.single().kind)

        // Multi-point drag hold with explicit glide duration and default max wait (5s)
        val s2 = Steps.parse("drag 200 1500 350 1000 540 600 800ms hold until color:540,600=#4CAF50")
        assertEquals(StepKind.DRAG, s2.kind)
        assertEquals(Loop.HOLD, s2.loop)
        assertEquals(3, s2.points.size)
        assertEquals(800L, s2.ms)
        assertEquals(Steps.HOLD_DEFAULT_MS, s2.untilMs)

        // drag seek with 2 points, area-scoped text condition, default max wait (15s)
        val s3 = Steps.parse("drag 100 800 980 800 seek until text:70 in 400,600,700,700")
        assertEquals(StepKind.DRAG, s3.kind)
        assertEquals(Loop.SEEK, s3.loop)
        assertEquals(listOf(100 to 800, 980 to 800), s3.points)
        assertEquals(Steps.SEEK_DEFAULT_MS, s3.untilMs)
        assertEquals(Rect(400, 600, 700, 700), s3.until!!.single().area)

        // press hold with coordinate target & wildcard text in area
        val s4 = Steps.parse("press 540 1600 hold until text:9* in 300,1400,800,1500 8s")
        assertEquals(StepKind.PRESS, s4.kind)
        assertEquals(Loop.HOLD, s4.loop)
        assertEquals(StepTarget.At(540, 1600), s4.target)
        assertEquals(8_000L, s4.untilMs)
        assertEquals("9*", s4.until!!.single().text)
        assertEquals(Rect(300, 1400, 800, 1500), s4.until.single().area)

        // press hold with word target & gone: in area
        val s5 = Steps.parse("press 长按此处 hold until gone:加载中 in 100,200,500,400 3s")
        assertEquals(StepKind.PRESS, s5.kind)
        assertEquals(Loop.HOLD, s5.loop)
        assertEquals(StepTarget.Words("长按此处"), s5.target)
        assertEquals(CondKind.GONE, s5.until!!.single().kind)
        assertEquals(Rect(100, 200, 500, 400), s5.until.single().area)

        // Non-text conditions (change / still / stable) allowed without area
        val s6 = Steps.parse("drag 100 800 980 800 seek until change 20s")
        assertEquals(Loop.SEEK, s6.loop)
        assertEquals(20_000L, s6.untilMs)

        // Duration caps: hold <= 10s, seek <= 30s
        val cappedHold = Steps.parse("press 540 1600 hold until color:540,1400=#4CAF50 25s")
        assertEquals(Steps.HOLD_MAX_MS, cappedHold.untilMs)
        val cappedSeek = Steps.parse("drag 100 800 980 800 seek until color:540,600=#4CAF50 45s")
        assertEquals(Steps.SEEK_MAX_MS, cappedSeek.untilMs)

        // Legacy press syntax unchanged
        val legacy = Steps.parse("press 540 1200 800ms")
        assertEquals(StepKind.PRESS, legacy.kind)
        assertEquals(Loop.NONE, legacy.loop)
        assertEquals(800L, legacy.ms)

        val legacyAlias = Steps.parse("hold 540 1200 1200ms")
        assertEquals(StepKind.PRESS, legacyAlias.kind)
        assertEquals(Loop.NONE, legacyAlias.loop)
        assertEquals(1_200L, legacyAlias.ms)
    }

    @Test
    fun parseClosedLoopErrorCodes() {
        // Missing until -> GESTURE_NEEDS_UNTIL
        expectCode("GESTURE_NEEDS_UNTIL") { Steps.parse("drag 200 1500 540 600 hold") }
        expectCode("GESTURE_NEEDS_UNTIL") { Steps.parse("drag 200 1500 540 600 600ms hold") }
        expectCode("GESTURE_NEEDS_UNTIL") { Steps.parse("drag 100 800 980 800 seek") }
        expectCode("GESTURE_NEEDS_UNTIL") { Steps.parse("press 540 1600 hold") }
        expectCode("GESTURE_NEEDS_UNTIL") { Steps.parse("press 长按此处 hold") }

        // text: / gone: without region -> GESTURE_NEEDS_AREA
        expectCode("GESTURE_NEEDS_AREA") { Steps.parse("drag 100 800 980 800 seek until text:70") }
        expectCode("GESTURE_NEEDS_AREA") { Steps.parse("press 540 1600 hold until text:9* 8s") }
        expectCode("GESTURE_NEEDS_AREA") { Steps.parse("press 540 1600 hold until gone:加载中 5s") }
        expectCode("GESTURE_NEEDS_AREA") {
            Steps.parse("drag 100 800 980 800 seek until text:70 in 400,600,700,700 or text:71")
        }

        // seek with >2 points -> INVALID_ARGS
        expectCode("INVALID_ARGS") {
            Steps.parse("drag 100 800 500 800 980 800 seek until text:70 in 400,600,700,700")
        }
    }

    // ── 4.4 & 4.6: GestureRecord on synthetic recording frames (all 4 outcomes) ──

    /** Draw a distinct 64x64 textured card centered at (cx, cy) on a 288x640 synthetic recording frame. */
    private fun frameWithCard(cardAt: Pair<Int, Int>?): Bitmap {
        val bmp = Bitmap.createBitmap(288, 640, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(245, 246, 250))
        if (cardAt != null) {
            val (cx, cy) = cardAt
            val half = Gesture.PATCH / 2
            val p = Paint()
            p.color = Color.rgb(30, 58, 138)
            c.drawRect((cx - half).toFloat(), (cy - half).toFloat(), (cx + half).toFloat(), (cy + half).toFloat(), p)
            p.color = Color.WHITE
            c.drawRect((cx - half + 8).toFloat(), (cy - half + 8).toFloat(), (cx + half - 8).toFloat(), (cy + half - 8).toFloat(), p)
            p.color = Color.rgb(220, 38, 38)
            c.drawRect((cx - half + 12).toFloat(), (cy - half + 12).toFloat(), (cx - 4).toFloat(), (cy - 4).toFloat(), p)
            p.color = Color.rgb(22, 163, 74)
            c.drawCircle((cx + 8).toFloat(), (cy + 8).toFloat(), 12f, p)
        }
        return bmp
    }

    @Test
    fun gestureRecordAllFourSyntheticOutcomes() {
        val start = 70 to 460
        val pickupMoved = 84 to 420
        val mid = 135 to 310
        val end = 200 to 160
        val elsewherePos = 210 to 540

        val pre = frameWithCard(start)
        val pick = frameWithCard(pickupMoved)
        val midF = frameWithCard(mid)
        val settledAtEnd = frameWithCard(end)
        val settledAtStart = frameWithCard(start)
        val settledElsewhere = frameWithCard(elsewherePos)
        val settledGone = frameWithCard(null)
        try {
            // 1. stayed
            val rStayed = Gesture.analyzeGestureFrames(pre, listOf(pick), midF, settledAtEnd, start, mid, end)
            assertEquals("rStayed=$rStayed", true, rStayed.pickedUp)
            assertEquals("rStayed=$rStayed", true, rStayed.followed)
            assertEquals("rStayed=$rStayed", Gesture.AFTER_STAYED, rStayed.afterRelease)
            assertTrue("stayed score=${rStayed.score}", rStayed.score >= Gesture.TRACK_SCORE_MIN)

            // 2. snapped_back
            val rSnap = Gesture.analyzeGestureFrames(pre, listOf(pick), midF, settledAtStart, start, mid, end)
            assertEquals(true, rSnap.pickedUp)
            assertEquals(true, rSnap.followed)
            assertEquals(Gesture.AFTER_SNAPPED_BACK, rSnap.afterRelease)
            assertTrue("snap score=${rSnap.score}", rSnap.score >= Gesture.TRACK_SCORE_MIN)

            // 3. elsewhere
            val rElse = Gesture.analyzeGestureFrames(pre, listOf(pick), midF, settledElsewhere, start, mid, end)
            assertEquals(true, rElse.pickedUp)
            assertEquals(true, rElse.followed)
            assertEquals(Gesture.AFTER_ELSEWHERE, rElse.afterRelease)
            assertTrue("elsewhere score=${rElse.score}", rElse.score >= Gesture.TRACK_SCORE_MIN)

            // 4. unknown
            val rUnk = Gesture.analyzeGestureFrames(pre, listOf(pick), midF, settledGone, start, mid, end)
            assertEquals(true, rUnk.pickedUp)
            assertEquals(true, rUnk.followed)
            assertEquals(Gesture.AFTER_UNKNOWN, rUnk.afterRelease)
            assertEquals(0.0, rUnk.score, 0.001)

            // Extra: not picked up / no pickup frames / did not follow
            val rNotPicked = Gesture.analyzeGestureFrames(pre, listOf(pre), pre, settledAtStart, start, mid, end)
            assertEquals(false, rNotPicked.pickedUp)
            assertEquals(false, rNotPicked.followed)

            val rNoPickup = Gesture.analyzeGestureFrames(pre, emptyList(), null, settledAtEnd, start, mid, end)
            assertNull(rNoPickup.pickedUp)
            assertNull(rNoPickup.followed)
            assertEquals(Gesture.AFTER_STAYED, rNoPickup.afterRelease)
        } finally {
            listOf(pre, pick, midF, settledAtEnd, settledAtStart, settledElsewhere, settledGone).forEach { it.recycle() }
        }
    }

    @Test
    fun gestureRecordDescribeAndStepOutJson() {
        val rec = GestureRecord(
            pickedUp = true,
            followed = true,
            afterRelease = Gesture.AFTER_SNAPPED_BACK,
            score = 0.914,
            pollMsP50 = 18L,
            pollMsMax = 65L,
        )
        I18n.choice = I18n.Lang.ZH
        assertEquals("拿起了 · 跟手 · 松手后弹回起点", rec.describe())
        I18n.choice = I18n.Lang.EN
        assertEquals("picked up · followed · snapped back to start", rec.describe())
        I18n.choice = I18n.Lang.ZH

        val stepDrag = StepOut("1", "drag 200 1500 540 600 hold until color:540,600=#4CAF50 2s", "done", "ok").apply {
            gesture = rec
        }
        val gJson = stepDrag.json().getJSONObject("gesture")
        assertEquals(true, gJson.getBoolean("picked_up"))
        assertEquals(true, gJson.getBoolean("followed"))
        assertEquals("snapped_back", gJson.getString("after_release"))
        assertEquals(0.91, gJson.getDouble("score"), 0.001)
        assertEquals(18L, gJson.getLong("poll_ms_p50"))
        assertEquals(65L, gJson.getLong("poll_ms_max"))

        // press hold has no drag GestureRecord, yet reports poll_ms_p50 / poll_ms_max in gesture JSON (4.7)
        val stepPress = StepOut("1", "press 540 1600 hold until text:9* in 300,1400,800,1500 8s", "done", "ok").apply {
            pollMsP50 = 24L
            pollMsMax = 90L
        }
        val pg = stepPress.json().getJSONObject("gesture")
        assertEquals(24L, pg.getLong("poll_ms_p50"))
        assertEquals(90L, pg.getLong("poll_ms_max"))
        assertFalse(pg.has("after_release"))
    }

    // ── 4.4 & 4.7: SNAP_BACK loud reason & closed-loop metCondKind NO_TEXT avoidance ──

    @Test
    fun snapBackLoudReasonPlacedRightAfterAppSwitch() {
        val clean = LoudInput(startPkg = "com.example.a", endPkg = "com.example.a", elementCount = 5)
        assertEquals(emptyList<String>(), loudReasons(clean.copy(snappedBack = false)))
        assertEquals(listOf(Loud.SNAP_BACK), loudReasons(clean.copy(snappedBack = true)))

        val all = LoudInput(
            outcome = "missed",
            hasBlockingNote = true,
            hasNoExpect = true,
            hasNotMet = true,
            beforeChanged = true,
            afterChanged = true,
            startPkg = "com.example.a",
            endPkg = "com.example.b",
            hasAppUntil = false,
            snappedBack = true,
            elementCount = 0,
            forced = true,
        )
        assertEquals(
            listOf(
                Loud.FAILED,
                Loud.NO_EXPECT,
                Loud.NOT_MET,
                Loud.BEFORE,
                Loud.AFTER,
                Loud.APP_SWITCH,
                Loud.SNAP_BACK,
                Loud.NO_TEXT,
                Loud.FORCED,
            ),
            loudReasons(all),
        )
    }

    @Test
    fun closedLoopMetCondKindPreventsFalseNoText() {
        // 4.7: closed-loop step setting metCondKind=COLOR and isActionOrRepeatUntil=true explains the result -> no NO_TEXT.
        val colorStep = StepOut("1", "drag 200 1500 540 600 hold until color:540,600=#4CAF50 2s", "done", "ok").apply {
            waitedFor = "color:540,600=#4CAF50"
            waitMet = true
            waitMs = 450L
            metCondKind = CondKind.COLOR
            isActionOrRepeatUntil = true
        }
        assertFalse(Loud.lastExpectOnlyVisual(listOf(colorStep)))
        assertEquals(
            emptyList<String>(),
            loudReasons(
                LoudInput(
                    elementCount = 1,
                    lastExpectOnlyVisual = Loud.lastExpectOnlyVisual(listOf(colorStep)),
                    hasPageTextDiff = false,
                )
            ),
        )

        // Closed-loop step with text: condition also explains result -> no NO_TEXT
        val textStep = StepOut("1", "drag 100 800 980 800 seek until text:70 in 400,600,700,700", "done", "ok").apply {
            waitedFor = "text:70 in 400,600,700,700"
            waitMet = true
            waitMs = 1200L
            metCondKind = CondKind.TEXT
            isActionOrRepeatUntil = true
        }
        assertFalse(Loud.lastExpectOnlyVisual(listOf(textStep)))

        // Closed-loop step with change: only + no page text diff -> triggers NO_TEXT
        val changeStep = StepOut("1", "drag 100 800 980 800 seek until change:400,600,700,700", "done", "ok").apply {
            waitedFor = "change:400,600,700,700"
            waitMet = true
            waitMs = 800L
            metCondKind = CondKind.CHANGE
            isActionOrRepeatUntil = true
        }
        assertTrue(Loud.lastExpectOnlyVisual(listOf(changeStep)))
        assertEquals(
            listOf(Loud.NO_TEXT),
            loudReasons(
                LoudInput(
                    elementCount = 1,
                    lastExpectOnlyVisual = Loud.lastExpectOnlyVisual(listOf(changeStep)),
                    hasPageTextDiff = false,
                )
            ),
        )
    }

    // ── 4.7: Per-display OCR pause switch & 4.1 docs / codes ────────────────

    @Test
    fun perceptionOcrPauseSwitchIsPerDisplayAndRefCounted() {
        val d1 = 9101
        val d2 = 9102
        assertFalse(VScreenPerception.isOcrPaused(d1))
        assertFalse(VScreenPerception.isOcrPaused(d2))

        VScreenPerception.setOcrPaused(d1, true)
        assertTrue(VScreenPerception.isOcrPaused(d1))
        assertFalse(VScreenPerception.isOcrPaused(d2))

        VScreenPerception.setOcrPaused(d1, true)
        VScreenPerception.setOcrPaused(d1, false)
        assertTrue(VScreenPerception.isOcrPaused(d1))

        VScreenPerception.setOcrPaused(d1, false)
        assertFalse(VScreenPerception.isOcrPaused(d1))

        // Extra unpause does not underflow
        VScreenPerception.setOcrPaused(d1, false)
        assertFalse(VScreenPerception.isOcrPaused(d1))
    }

    @Test
    fun codesAndGestureHelpAndCoreGuideBudget() {
        assertEquals(Codes.Next.FIX, Codes.of("GESTURE_NEEDS_UNTIL")?.next)
        assertEquals(Codes.Next.FIX, Codes.of("GESTURE_NEEDS_AREA")?.next)

        for (lang in listOf(I18n.Lang.ZH, I18n.Lang.EN)) {
            I18n.choice = lang
            val topic = AgentGuide.topic("gesture")
            assertNotNull(topic)
            assertTrue(topic!!.contains("drag 200 1500 540 600 hold until color:540,600=#4CAF50 2s"))
            assertTrue(topic.contains("drag 100 800 980 800 seek until text:70 in 400,600,700,700"))
            assertTrue(topic.contains("press 540 1600 hold until text:9* in 300,1400,800,1500 8s"))
            assertTrue(topic.contains("repeat { swipe"))

            val coreTokens = Meter.of(AgentGuide.guide(), emptyList()).estTokens
            assertTrue("Core guide ($lang) exceeded budget: $coreTokens", coreTokens <= AgentGuide.CORE_GUIDE_MAX_TOKENS)
        }
    }
}
