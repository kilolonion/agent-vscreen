package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.AgentGuide
import com.kilon.agentvscreen.api.AgentLedger
import com.kilon.agentvscreen.api.Turns
import com.kilon.agentvscreen.core.Mask
import com.kilon.agentvscreen.core.Meter
import com.kilon.agentvscreen.core.TaskLog
import com.kilon.agentvscreen.core.VScreenPerception
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
import org.robolectric.annotation.GraphicsMode

/**
 * 2.0.0-beta3 (Stage 3: Reply protocol v3 & externalized memory) unit tests per spec 3.9–3.12.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V200Beta3Test {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private var savedLang: I18n.Lang = I18n.Lang.ZH

    @Before
    fun setUp() {
        savedLang = I18n.choice
        I18n.choice = I18n.Lang.ZH
        Loud.resetForTest()
        PageBook.resetForTest()
        Turns.resetTurnsForTest()
        AgentLedger.resetReplyModesForTest()
        TaskLog.resetOpenForTest()
    }

    @After
    fun tearDown() {
        I18n.choice = savedLang
        Loud.resetForTest()
        PageBook.resetForTest()
        Turns.resetTurnsForTest()
        AgentLedger.resetReplyModesForTest()
        TaskLog.resetOpenForTest()
    }

    private fun el(n: Int, text: String, x: Int, y: Int, clickable: Boolean = true): El =
        El(n, text, Rect(x - 40, y - 20, x + 40, y + 20), fromA11y = true, clickable = clickable)

    private fun solidBitmap(w: Int, h: Int, color: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    // ── 3.2 & 3.9: loudReasons ───────────────────────────────────────────

    @Test
    fun loudReasonsReturnsEmptyWhenAllClear() {
        val input = LoudInput(
            outcome = "done",
            hasBlockingNote = false,
            hasNoExpect = false,
            hasNotMet = false,
            beforeChanged = false,
            afterChanged = false,
            startPkg = "com.example.a",
            endPkg = "com.example.a",
            hasAppUntil = false,
            elementCount = 3,
            forced = false,
        )
        assertEquals(emptyList<String>(), loudReasons(input))
    }

    @Test
    fun loudReasonsEachCodeTrueAndFalse() {
        val clean = LoudInput(startPkg = "com.example.a", endPkg = "com.example.a", elementCount = 5)

        // 1. FAILED (outcome != done OR blocking note)
        assertEquals(listOf(Loud.FAILED), loudReasons(clean.copy(outcome = "missed")))
        assertEquals(listOf(Loud.FAILED), loudReasons(clean.copy(hasBlockingNote = true)))
        assertEquals(emptyList<String>(), loudReasons(clean.copy(outcome = "done", hasBlockingNote = false)))

        // 2. NO_EXPECT
        assertEquals(listOf(Loud.NO_EXPECT), loudReasons(clean.copy(hasNoExpect = true)))
        assertEquals(emptyList<String>(), loudReasons(clean.copy(hasNoExpect = false)))

        // 3. NOT_MET
        assertEquals(listOf(Loud.NOT_MET), loudReasons(clean.copy(hasNotMet = true)))
        assertEquals(emptyList<String>(), loudReasons(clean.copy(hasNotMet = false)))

        // 4. BEFORE
        assertEquals(listOf(Loud.BEFORE), loudReasons(clean.copy(beforeChanged = true)))
        assertEquals(emptyList<String>(), loudReasons(clean.copy(beforeChanged = false)))

        // 5. AFTER
        assertEquals(listOf(Loud.AFTER), loudReasons(clean.copy(afterChanged = true)))
        assertEquals(emptyList<String>(), loudReasons(clean.copy(afterChanged = false)))

        // 6. APP_SWITCH (pkg changed without app: until vs with app: until)
        assertEquals(
            listOf(Loud.APP_SWITCH),
            loudReasons(clean.copy(startPkg = "com.example.a", endPkg = "com.example.b", hasAppUntil = false)),
        )
        assertEquals(
            emptyList<String>(),
            loudReasons(clean.copy(startPkg = "com.example.a", endPkg = "com.example.b", hasAppUntil = true)),
        )

        // 7. NO_TEXT (3.13): elementCount == 0 OR (lastExpectOnlyVisual && !hasPageTextDiff)
        assertEquals(listOf(Loud.NO_TEXT), loudReasons(clean.copy(elementCount = 0)))
        assertEquals(emptyList<String>(), loudReasons(clean.copy(elementCount = 1, lastExpectOnlyVisual = false, hasPageTextDiff = false)))
        assertEquals(listOf(Loud.NO_TEXT), loudReasons(clean.copy(elementCount = 5, lastExpectOnlyVisual = true, hasPageTextDiff = false)))
        assertEquals(emptyList<String>(), loudReasons(clean.copy(elementCount = 5, lastExpectOnlyVisual = true, hasPageTextDiff = true)))

        // 8. FORCED
        assertEquals(listOf(Loud.FORCED), loudReasons(clean.copy(forced = true)))
        assertEquals(emptyList<String>(), loudReasons(clean.copy(forced = false)))
    }

    @Test
    fun loudNoTextFollowsSpec313FourRequiredCases() {
        val sid = "s_no_text"
        val act = "com.example/.GameOrPage"
        val clean = LoudInput(startPkg = "com.example", endPkg = "com.example")

        fun doneAction(n: String, step: String, waitedFor: String? = null, kind: CondKind? = null, typeOk: Boolean = false) =
            StepOut(n, step, "done", "").apply {
                this.waitedFor = waitedFor
                this.waitMet = waitedFor != null
                this.metCondKind = kind
                this.typeVerified = typeOk
                this.isActionOrRepeatUntil = true
            }

        // Case 1: 1-line page + until text: -> NOT NO_TEXT
        val oneLine = listOf(el(1, "加载完成", 540, 1200))
        PageBook.match(sid, act, oneLine, turn = 1)
        val stepsText = listOf(doneAction("1", "tap 加载 until text:加载完成", "text:加载完成", CondKind.TEXT))
        assertFalse(Loud.lastExpectOnlyVisual(stepsText))
        assertEquals(
            emptyList<String>(),
            loudReasons(
                clean.copy(
                    elementCount = oneLine.size,
                    lastExpectOnlyVisual = Loud.lastExpectOnlyVisual(stepsText),
                    hasPageTextDiff = PageBook.hasTextDiff(sid, act, oneLine),
                )
            ),
        )

        // Case 2: until change: + no text diff against PageBook card (e.g. Canvas game with 3 HUD labels) -> NO_TEXT
        val hud = listOf(el(1, "得分 0", 200, 100), el(2, "血量 100", 540, 100), el(3, "暂停", 900, 100))
        PageBook.match(sid, act, hud, turn = 2)
        val stepsChange = listOf(doneAction("1", "tap 540 900 until change", "change", CondKind.CHANGE))
        assertTrue(Loud.lastExpectOnlyVisual(stepsChange))
        assertFalse(PageBook.hasTextDiff(sid, act, hud))
        assertEquals(
            listOf(Loud.NO_TEXT),
            loudReasons(
                clean.copy(
                    elementCount = hud.size,
                    lastExpectOnlyVisual = Loud.lastExpectOnlyVisual(stepsChange),
                    hasPageTextDiff = PageBook.hasTextDiff(sid, act, hud),
                )
            ),
        )

        // Case 3: until change: + ~ (text modified in place) diff against PageBook card -> NOT NO_TEXT
        val hudScored = listOf(el(1, "得分 10", 200, 100), el(2, "血量 100", 540, 100), el(3, "暂停", 900, 100))
        assertTrue(PageBook.hasTextDiff(sid, act, hudScored))
        assertEquals(
            emptyList<String>(),
            loudReasons(
                clean.copy(
                    elementCount = hudScored.size,
                    lastExpectOnlyVisual = Loud.lastExpectOnlyVisual(stepsChange),
                    hasPageTextDiff = PageBook.hasTextDiff(sid, act, hudScored),
                )
            ),
        )

        // Case 4: 0 elements -> NO_TEXT (even if until text/color was satisfied)
        assertEquals(
            listOf(Loud.NO_TEXT),
            loudReasons(
                clean.copy(
                    elementCount = 0,
                    lastExpectOnlyVisual = false,
                    hasPageTextDiff = false,
                )
            ),
        )

        // Additional 3.13 checks: repeat ... until, gone/color/app, verified type, and 1-element unchanged HUD
        val repeatText = listOf(
            doneAction("1.1.1", "drag 540 2000 540 500 350ms", "stable", CondKind.STABLE),
            doneAction("1", "repeat 20 { drag 540 2000 540 500 350ms } until text:目标", "text:目标", CondKind.TEXT),
        )
        assertFalse("enclosing repeat ... until text: explains result", Loud.lastExpectOnlyVisual(repeatText))

        val repeatChange = listOf(
            doneAction("1.1.1", "tap 540 900", "stable", CondKind.STABLE),
            doneAction("1", "repeat 3 { tap 540 900 } until change", "change", CondKind.CHANGE),
        )
        assertTrue("enclosing repeat ... until change is visual-only", Loud.lastExpectOnlyVisual(repeatChange))

        assertFalse("gone explains result", Loud.lastExpectOnlyVisual(listOf(doneAction("1", "tap 关 until gone:关", "gone:关", CondKind.GONE))))
        assertFalse("color explains result", Loud.lastExpectOnlyVisual(listOf(doneAction("1", "drag 1 2 3 4 until color:3,4=#4CAF50", "color:3,4=#4CAF50", CondKind.COLOR))))
        assertFalse("app explains result", Loud.lastExpectOnlyVisual(listOf(doneAction("1", "key back until app:设置", "app:设置", CondKind.APP))))
        assertFalse("verified type explains result", Loud.lastExpectOnlyVisual(listOf(doneAction("1", "type 你好", typeOk = true))))
        assertTrue("still is visual-only", Loud.lastExpectOnlyVisual(listOf(doneAction("1", "tap A until still", "still", CondKind.STILL))))
        assertTrue("stable is visual-only", Loud.lastExpectOnlyVisual(listOf(doneAction("1", "tap A until stable", "stable", CondKind.STABLE))))
        assertFalse("1-element HUD with unchanged element has no text diff", PageBook.hasTextDiff(sid, act, oneLine))
    }

    @Test
    fun loudReasonsExactSpecOrder() {
        val all = LoudInput(
            outcome = "refused",
            hasBlockingNote = true,
            hasNoExpect = true,
            hasNotMet = true,
            beforeChanged = true,
            afterChanged = true,
            startPkg = "com.example.a",
            endPkg = "com.example.b",
            hasAppUntil = false,
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
                Loud.NO_TEXT,
                Loud.FORCED,
            ),
            loudReasons(all),
        )
    }

    @Test
    fun loudFirstUnexpectedStepFollows312Rules() {
        // repeat ... until covers inner actions (e.g. T13b)
        val repeatUntil = Steps.parseAll("repeat 20 { drag 540 2000 540 500 350ms } until \"text:目标 147\"; tap \"目标 147\" until text:已打开")
        assertNull(Loud.firstUnexpectedStep(repeatUntil))

        // repeat N without until does NOT cover inner actions
        val repeatN = Steps.parseAll("repeat 3 { tap 下一步 }")
        assertEquals(1, Loud.firstUnexpectedStep(repeatN))

        // if / try do NOT cover inner actions
        assertEquals(1, Loud.firstUnexpectedStep(Steps.parseAll("if text:提示 { tap 确定 }")))
        assertEquals(1, Loud.firstUnexpectedStep(Steps.parseAll("try { tap 跳过 }")))
        assertNull(Loud.firstUnexpectedStep(Steps.parseAll("try { tap 跳过 until gone:跳过 }")))

        // Verified type is allowed without explicit until; unverified type triggers NO_EXPECT
        val typeSteps = Steps.parseAll("tap 100 200 until change; type 测试内容; tap 提交 until text:成功")
        assertEquals(2, Loud.firstUnexpectedStep(typeSteps, verifiedTypeLabels = emptySet()))
        assertNull(Loud.firstUnexpectedStep(typeSteps, verifiedTypeLabels = setOf("2")))

        // use <name> counts as expected only if its last action step has until
        val useStep = Steps.parseAll("use login")
        assertNull(Loud.firstUnexpectedStep(useStep) { Steps.parseAll("tap 账号; tap 登录 until text:首页") })
        assertEquals(1, Loud.firstUnexpectedStep(useStep) { Steps.parseAll("tap 账号 until change; tap 登录") })
    }

    @Test
    fun pictureEventsDetectsABAIntermediateChangePopupAndRespectsMaskAndRetention() {
        val w = 160
        val h = 320
        val frameA = solidBitmap(w, h, Color.rgb(245, 245, 245))
        val frameB = solidBitmap(w, h, Color.rgb(245, 245, 245))
        val frameA2 = solidBitmap(w, h, Color.rgb(245, 245, 245))
        val framePopup = solidBitmap(w, h, Color.rgb(80, 80, 80))
        val frameCarousel = solidBitmap(w, h, Color.rgb(245, 245, 245))
        try {
            // B changes a large center region (>= 2%), while A2 returns to identical A (A -> B -> A)
            Canvas(frameB).drawRect(20f, 100f, 140f, 220f, Paint().apply { color = Color.BLUE })
            // Popup: dimmed scrim + detached centered dialog [16, 48, 144, 176]
            Canvas(framePopup).drawRect(16f, 48f, 144f, 176f, Paint().apply { color = Color.WHITE })
            // Carousel: changes only inside top band [0, 0, 160, 80]
            Canvas(frameCarousel).drawRect(0f, 0f, 160f, 80f, Paint().apply { color = Color.BLUE })

            // 1. A -> B -> A: first and last frames match, yet intermediate change triggers BEFORE/AFTER
            val aba = analyzeFrameSequence(
                listOf(1_000L to frameA, 1_500L to frameB, 2_000L to frameA2),
                fw = 1080,
                fh = 2160,
                mask = null,
            )
            assertTrue("A->B->A must detect intermediate change", aba.hasSignificantChange)
            assertTrue("maxShare >= CHANGE_RATIO: ${aba.maxShare}", aba.maxShare >= Loud.CHANGE_RATIO)
            assertEquals("first vs last should look the same", 0.0, aba.firstLast!!.first, 0.001)

            // 2. A -> popup -> A (like T02a): detects popup and triggers BEFORE/AFTER
            val pop = analyzeFrameSequence(
                listOf(1_000L to frameA, 1_500L to framePopup, 2_000L to frameA2),
                fw = 1080,
                fh = 2160,
                mask = null,
            )
            assertTrue("A->popup->A must detect popup", pop.hasPopup)
            assertTrue("popup span must trigger significant change", pop.hasSignificantChange)

            // 3. Moving mask (carousel in top quarter): masked change is ignored, unmasked change triggers
            val cols = com.kilon.agentvscreen.core.VScreenActivity.COLS
            val rows = com.kilon.agentvscreen.core.VScreenActivity.rowsFor(w, h)
            val maskCells = BooleanArray(cols * rows)
            for (cy in 0 until rows / 4 + 1) for (cx in 0 until cols) maskCells[cy * cols + cx] = true
            val mask = Mask(maskCells, cols, rows, 1080, 2160)

            val maskedCarousel = analyzeFrameSequence(
                listOf(1_000L to frameA, 1_500L to frameCarousel, 2_000L to frameA2),
                fw = 1080,
                fh = 2160,
                mask = mask,
            )
            assertFalse("carousel motion inside mask must not trigger BEFORE/AFTER", maskedCarousel.hasSignificantChange)

            val unmaskedCarousel = analyzeFrameSequence(
                listOf(1_000L to frameA, 1_500L to frameCarousel),
                fw = 1080,
                fh = 2160,
                mask = null,
            )
            assertTrue("same change without mask must trigger", unmaskedCarousel.hasSignificantChange)

            // 4. Recording retention coverage rule (3.13): covered + no frames = unchanged; missing/expired/pruned = changed
            val now = 1_000_000L
            assertTrue(
                "recording on within KEEP_MS with frame at start is covered",
                com.kilon.agentvscreen.core.VScreenRecorder.isCoveredBy(now - 60_000L, now, recordingOn = true, oldestFrameMs = now - 65_000L),
            )
            assertFalse(
                "fromMs older than KEEP_MS is not covered (treat as changed)",
                com.kilon.agentvscreen.core.VScreenRecorder.isCoveredBy(now - com.kilon.agentvscreen.core.VScreenRecorder.KEEP_MS - 1L, now, recordingOn = true, oldestFrameMs = 1L),
            )
            assertFalse(
                "recording off and fromMs > 15s is not covered (treat as changed)",
                com.kilon.agentvscreen.core.VScreenRecorder.isCoveredBy(now - 20_000L, now, recordingOn = false, oldestFrameMs = now - 25_000L),
            )
            assertTrue(
                "recording off within 15s is covered",
                com.kilon.agentvscreen.core.VScreenRecorder.isCoveredBy(now - 10_000L, now, recordingOn = false, oldestFrameMs = now - 12_000L),
            )
            assertFalse(
                "oldest frame newer than fromMs + grace (pruned / started late) is not covered",
                com.kilon.agentvscreen.core.VScreenRecorder.isCoveredBy(now - 10_000L, now, recordingOn = true, oldestFrameMs = now - 5_000L),
            )
            assertFalse(
                "missing fromMs or empty track is not covered",
                com.kilon.agentvscreen.core.VScreenRecorder.isCoveredBy(0L, now, recordingOn = true, oldestFrameMs = now - 1_000L),
            )
        } finally {
            frameA.recycle()
            frameB.recycle()
            frameA2.recycle()
            framePopup.recycle()
            frameCarousel.recycle()
        }
    }

    // ── 3.3 & 3.9 & 3.11: short reply format ─────────────────────────────

    @Test
    fun shortReplyThreeStepsMatchesSpec33LineByLine() {
        val prevEls = (1..22).map { i -> el(i, "项目$i", 200, 100 + i * 40) }
        val addedEl = El(24, "耳机 Pro", Rect(120, 880, 960, 960), fromA11y = true, clickable = true)
        val nowEls = prevEls + addedEl
        val card = PageBook.Card(5, "com.example/.BtActivity", nowEls, "蓝牙", seenTurn = 7, version = 1).apply {
            prevSeenTurn = 6
            this.prevEls = prevEls
            canRef = true
            isNew = false
        }
        val steps = listOf(
            StepOut("1", "tap 蓝牙 until text:已配对的设备", "done", "").apply { waitedFor = "text:已配对的设备"; waitMs = 600L; waitMet = true },
            StepOut("2", "tap #12 until text:已连接", "done", "").apply { waitedFor = "text:已连接"; waitMs = 1_100L; waitMet = true },
            StepOut("3", "key back until app:设置", "done", "").apply { waitedFor = "app:设置"; waitMs = 100L; waitMet = true },
        )

        val out = formatShortReply(turn = 7, card = card, steps = steps, totalMs = 1_800L, els = nowEls)
        val expected = listOf(
            "✓ 第 7 轮 · P5 蓝牙 · 3 步都符合预期 · 1.8s",
            "1 tap 蓝牙 → text:已配对的设备 ✓ 0.6s",
            "2 tap #12 → text:已连接 ✓ 1.1s",
            "3 key back → app:设置 ✓ 0.1s",
            "现在 P5：和第 6 轮相比 +1 −0，其余 22 个不变",
            "+ #24 “耳机 Pro” [120,880,960,960]",
            quietEscapeLine(),
        ).joinToString("\n") + "\n"

        assertEquals(expected, out)
        // 3.11: escape hatch line <= 30 tokens in both ZH and EN
        assertTrue(Meter.of(quietEscapeLine(), emptyList()).estTokens <= 30)
        I18n.choice = I18n.Lang.EN
        assertTrue(Meter.of(quietEscapeLine(), emptyList()).estTokens <= 30)
    }

    @Test
    fun shortReplyElevenStepsCollapsesFirstEightAndCapsDiffsAtTwelve() {
        val prevEls = listOf(el(1, "标题", 200, 100), el(2, "底部", 200, 900))
        val added = (1..15).map { i -> el(10 + i, "新增$i", 200, 150 + i * 40) }
        val nowEls = prevEls + added
        val card = PageBook.Card(2, "com.example/.ListActivity", nowEls, "列表", seenTurn = 4, version = 1).apply {
            prevSeenTurn = 3
            this.prevEls = prevEls
            canRef = true
            isNew = false
        }
        val steps = (1..11).map { i ->
            StepOut("$i", "tap 项$i until text:好$i", "done", "").apply {
                waitedFor = "text:好$i"; waitMs = 200L; waitMet = true
            }
        }
        val out = formatShortReply(turn = 4, card = card, steps = steps, totalMs = 2_200L, els = nowEls)
        val lines = out.trimEnd().lines()
        assertEquals("✓ 第 4 轮 · P2 列表 · 11 步都符合预期 · 2.2s", lines[0])
        assertEquals("前 8 步均符合预期", lines[1])
        assertTrue(lines[2].startsWith("9 tap 项9 → text:好9"))
        assertTrue(lines[3].startsWith("10 tap 项10 → text:好10"))
        assertTrue(lines[4].startsWith("11 tap 项11 → text:好11"))
        assertFalse(out.contains("8 tap 项8"))
        assertEquals(12, lines.count { it.startsWith("+ #") })
        assertTrue(out.contains("另有 3 处变化，act 不带步骤可看全部"))
    }

    // ── 3.4 & 3.9: PageBook ──────────────────────────────────────────────

    @Test
    fun pageBookSamePageGapLimitDiffLimitAndLruEviction() {
        val sid = "s_book"
        val baseEls = (1..10).map { i -> el(i, "条目$i", 300, 100 + i * 60) }

        // First visit creates P1
        val (c1, isNew1) = PageBook.match(sid, "com.example/.Main", baseEls, turn = 1)
        assertTrue(isNew1)
        assertEquals(1, c1.id)
        assertEquals(1, c1.version)
        assertEquals("条目1", c1.title)

        // Same page within 8 turns and < 40% changed -> reuses card and canRef = true
        val smallChange = baseEls.dropLast(1) + el(11, "条目新", 300, 700) // 10% changed
        val (c2, isNew2) = PageBook.match(sid, "com.example/.Main", smallChange, turn = 5)
        assertFalse(isNew2)
        assertEquals(c1.id, c2.id)
        assertTrue(c2.canRef)
        assertEquals(1, c2.prevSeenTurn)
        assertEquals(1, c2.version)
        val pj = c2.pageJson()
        assertEquals("P1", pj.getString("id"))
        assertEquals("条目1", pj.getString("title"))
        assertFalse(pj.getBoolean("new"))
        assertEquals(1, pj.getInt("seen_turn"))

        // Gap > 8 turns (14 - 5 = 9 > 8) -> still matches card, but canRef = false & version++
        val (c3, isNew3) = PageBook.match(sid, "com.example/.Main", smallChange, turn = 14)
        assertFalse(isNew3)
        assertEquals(c1.id, c3.id)
        assertFalse(c3.canRef)
        assertEquals(2, c3.version)

        // >= 40% changed (4 of 10 replaced = 40% >= 0.4, still 60% unchanged so samePage is true) -> canRef = false & version++
        val fortyPctChanged = smallChange.take(6) + (1..4).map { i -> el(20 + i, "改$i", 300, 520 + i * 60) }
        val (c4, isNew4) = PageBook.match(sid, "com.example/.Main", fortyPctChanged, turn = 15)
        assertFalse(isNew4)
        assertEquals(c1.id, c4.id)
        assertFalse(c4.canRef)
        assertEquals(3, c4.version)

        // 32-card LRU eviction
        PageBook.clear(sid)
        for (k in 1..PageBook.MAX_CARDS) {
            val elsK = listOf(el(1, "页$k", 200, 100), el(2, "内容$k", 200, 200), el(3, "按钮$k", 200, 300))
            PageBook.match(sid, "com.example/.Act$k", elsK, turn = k)
        }
        assertEquals(PageBook.MAX_CARDS, PageBook.size(sid))
        // Touch P1 so P2 becomes the LRU eldest
        val els1 = listOf(el(1, "页1", 200, 100), el(2, "内容1", 200, 200), el(3, "按钮1", 200, 300))
        PageBook.match(sid, "com.example/.Act1", els1, turn = 33)
        // Insert 33rd unique page -> evicts P2, keeps P1
        val els33 = listOf(el(1, "页33", 200, 100), el(2, "内容33", 200, 200), el(3, "按钮33", 200, 300))
        PageBook.match(sid, "com.example/.Act33", els33, turn = 34)
        assertEquals(PageBook.MAX_CARDS, PageBook.size(sid))
        val ids = PageBook.cards(sid).map { it.id }.toSet()
        assertTrue("P1 touched recently must remain", 1 in ids)
        assertFalse("P2 least-recently used must be evicted", 2 in ids)
        assertTrue("P33 newly added must exist", 33 in ids)

        // Title rule: skips pure digits / 1-char text, falls back to short activity name
        val numOnly = listOf(el(1, "12345", 200, 50), el(2, "A", 200, 80))
        assertEquals("DetailActivity", PageBook.pickTitle(numOnly, "com.example/.ui.DetailActivity"))
    }

    // ── 3.6 & 3.9: word events, toast, popup ─────────────────────────────

    @Test
    fun wordEventsSuppressesSingleFlashAndEmitsBottomToast() {
        val screenH = 2400
        val bottomBox = Rect(360, 1940, 720, 2040)
        val topBox = Rect(360, 200, 720, 280)
        val toMs = 5_000L

        val readings = listOf(
            VScreenPerception.Reading(1_000L, listOf("保存"), mapOf("保存" to Rect(400, 1000, 680, 1100)), hits = 2, lastAtMs = 1_500L),
            // 2.0.0-beta8 (C5 / Ruling 4): single-flash words are suppressed everywhere; OCR toast guessing was removed
            // in favour of real TYPE_NOTIFICATION_STATE_CHANGED Toast events (tested in V200Beta8Test).
            VScreenPerception.Reading(
                3_600L,
                listOf("保存", "闪现", "已保存"),
                mapOf("闪现" to topBox, "已保存" to bottomBox),
                hits = 1,
                lastAtMs = 3_600L,
            ),
            VScreenPerception.Reading(4_500L, listOf("保存"), mapOf("保存" to Rect(400, 1000, 680, 1100)), hits = 2, lastAtMs = 5_000L),
        )
        val ev = wordFactsFromReadings(readings, screenH, toMs)
        val joined = ev.allLines.joinToString("\n")
        assertFalse("single-flash top word must be suppressed: $joined", joined.contains("闪现"))
        assertFalse("OCR must not guess bottom single-flash as toast: $joined", ev.staticLines.any { it.startsWith("toast：") })

        // Single-flash word at the very end (only 1 reading, not 2 consecutive) must not be reported as appeared
        val singleEnd = listOf(
            VScreenPerception.Reading(1_000L, listOf("保存"), hits = 2, lastAtMs = 1_500L),
            VScreenPerception.Reading(2_000L, listOf("保存", "一瞬字"), hits = 1, lastAtMs = 2_000L),
        )
        assertFalse(wordFactsFromReadings(singleEnd, screenH, 2_000L).allLines.joinToString("\n").contains("一瞬字"))

        // 2 consecutive readings at the end -> reported in appeared
        val stableEnd = listOf(
            VScreenPerception.Reading(1_000L, listOf("保存"), hits = 2, lastAtMs = 1_500L),
            VScreenPerception.Reading(2_000L, listOf("保存", "稳定新字"), hits = 2, lastAtMs = 2_500L),
        )
        assertTrue(wordFactsFromReadings(stableEnd, screenH, 2_500L).staticLines.any { it.contains("出现了") && it.contains("稳定新字") })

        // Bottom words never produce OCR-guessed toast lines; multi-hit vanished words go to betweenLines.
        val slowGap = listOf(
            VScreenPerception.Reading(1_000L, listOf("保存"), hits = 2, lastAtMs = 1_200L),
            VScreenPerception.Reading(2_000L, listOf("保存", "已保存"), mapOf("已保存" to bottomBox), hits = 1, lastAtMs = 2_000L),
            VScreenPerception.Reading(7_000L, listOf("保存"), hits = 2, lastAtMs = 7_500L),
        )
        assertFalse(wordFactsFromReadings(slowGap, screenH, 7_500L).staticLines.any { it.startsWith("toast：") })

        val longBottom = listOf(
            VScreenPerception.Reading(1_000L, listOf("保存"), hits = 2, lastAtMs = 1_200L),
            VScreenPerception.Reading(2_000L, listOf("保存", "久留字"), mapOf("久留字" to bottomBox), hits = 2, lastAtMs = 6_500L),
            VScreenPerception.Reading(7_000L, listOf("保存"), hits = 2, lastAtMs = 7_500L),
        )
        assertFalse(wordFactsFromReadings(longBottom, screenH, 7_500L).staticLines.any { it.startsWith("toast：久留字") })

        // Bulk scroll (many new rows appearing together in one reading) must not spam fake toast lines
        val scrolledWords = (1..8).map { "列表项$it" }
        val scrolledBoxes = scrolledWords.associateWith { bottomBox }
        val bulkScroll = listOf(
            VScreenPerception.Reading(1_000L, listOf("顶部"), hits = 2, lastAtMs = 1_200L),
            VScreenPerception.Reading(2_000L, listOf("顶部") + scrolledWords, scrolledBoxes, hits = 1, lastAtMs = 2_000L),
            VScreenPerception.Reading(3_000L, listOf("顶部"), hits = 2, lastAtMs = 3_200L),
        )
        assertFalse(wordFactsFromReadings(bulkScroll, screenH, 3_200L).staticLines.any { it.startsWith("toast：") })

        // Trailing bare digit in `until text:条目 5` stays part of text (not parsed as 5ms timeout)
        val sDigit = Steps.parse("tap 返回 until text:条目 5")
        assertEquals("条目 5", sDigit.until?.single()?.text)
        assertNull(sDigit.untilMs)
        val sUnit = Steps.parse("tap 保存 until text:已保存 3s")
        assertEquals("已保存", sUnit.until?.single()?.text)
        assertEquals(3_000L, sUnit.untilMs)
    }

    @Test
    fun isPopupRequiresAllThreeConditionsAndFindPopupBoxLocatesScrimDialog() {
        val w = 160
        val h = 320
        val before = solidBitmap(w, h, Color.rgb(245, 245, 245))
        // Dimmed scrim (#505050, ~67% darker outside) + centered white dialog [16, 48, 144, 176] (32% area, detached)
        val after = solidBitmap(w, h, Color.rgb(80, 80, 80))
        val dlg = Rect(16, 48, 144, 176)
        Canvas(after).drawRect(dlg, Paint().apply { color = Color.WHITE })

        // Same dialog without dimmed background (outside stays #F5F5F5)
        val afterNoDim = solidBitmap(w, h, Color.rgb(245, 245, 245))
        Canvas(afterNoDim).drawRect(dlg, Paint().apply { color = Color.rgb(220, 235, 255) })

        try {
            // All 3 conditions met -> true
            assertTrue("valid popup must match", isPopup(before, after, dlg))
            // findPopupBox handles full-screen changedBox when scrim darkened the whole frame
            val found = findPopupBox(before, after, Rect(0, 0, w, h))
            assertNotNull("findPopupBox must extract foreground dialog from dimmed scrim", found)
            assertTrue(isPopup(before, after, found!!))

            // Missing condition 1: area < 15% (40x40 / 51200 = 3.1%) or > 90%
            assertFalse("too small cannot be popup", isPopup(before, after, Rect(60, 120, 100, 160)))
            assertFalse("too large (>90%) cannot be popup", isPopup(before, after, Rect(1, 2, w - 1, h - 2)))

            // Missing condition 2: touches top or touches bottom
            assertFalse("touching top cannot be popup", isPopup(before, after, Rect(16, 0, 144, 128)))
            assertFalse("touching bottom cannot be popup", isPopup(before, after, Rect(16, h - 128, 144, h)))

            // Missing condition 3: outside did not dim > 15%
            assertFalse("undimmed outside cannot be popup", isPopup(before, afterNoDim, dlg))
        } finally {
            before.recycle()
            after.recycle()
            afterNoDim.recycle()
        }
    }

    // ── 3.7 & 3.9: length budget ─────────────────────────────────────────

    @Test
    fun replyBudgetTrimsInStrictOrderAndPreservesNotesAndFailures() {
        val longChunk = "这是一段用于测试超预算裁剪的中文日志内容".repeat(25) // ~500 CJK chars -> ~500 tokens each
        val parts = FullReplyParts(
            header = "第 5 轮 · P1 测试页",
            doneLines = listOf("1 tap 确认：失败 STEP_MISSED，重要失败原因必须保留"),
            timingLine = "手机端这条用了 1.2s：动作和等待 0.5s，读屏 0.4s，这段时间的录像 0.2s，出图 0.1s",
            meanwhileHeader = "上次返回到现在 2.0s",
            maskLine = null,
            wordStaticLines = listOf("出现了：“结果”"),
            betweenLines = listOf("中途出现过又消失：$longChunk"),
            picDetailLines = listOf("-1.5s 变化 [0,0,1080,800] 30% (0.3s) $longChunk", "-0.8s 变化 [0,800,1080,1600] 25% (0.2s) $longChunk"),
            picChangeCount = 2,
            sheetNoteLine = null,
            nowHeader = "【现在】P1 测试页",
            elementLines = (1..80).map { "#$it “元素$it” (100,${it * 20}) 可点" },
            notesLines = listOf("STEP_MISSED：关键提示绝对不能被删"),
        )

        // Stage 1: tight budget forces dropping ① betweenLines, ② picDetailLines, and ③ timingLine
        val trimmed = applyReplyBudget(parts, budgetTokens = 120)
        assertFalse("① betweenLines should be trimmed", trimmed.contains("中途出现过又消失"))
        assertTrue("② picDetailLines replaced by summary", trimmed.contains("画面变过 2 次"))
        assertFalse("③ timingLine should be trimmed", trimmed.contains("手机端这条用了"))
        assertTrue("【做了什么】failure preserved", trimmed.contains("重要失败原因必须保留"))
        assertTrue("【注意】preserved intact", trimmed.contains("STEP_MISSED：关键提示绝对不能被删"))
        assertTrue("omitted notice with look --last appended", trimmed.contains("省略了 4 行，look --last 可回看"))

        // Stage 2: medium budget drops ONLY ① betweenLines, keeps picDetailLines & timingLine
        val medium = applyReplyBudget(
            parts.copy(picDetailLines = listOf("-1.0s 变化 [0,0,500,500] 10% (0.2s)")),
            budgetTokens = 250,
        )
        assertFalse("betweenLines dropped first", medium.contains("中途出现过又消失"))
        assertTrue("picDetailLines kept when dropping between was enough", medium.contains("-1.0s 变化"))
        assertTrue("timingLine kept when dropping between was enough", medium.contains("手机端这条用了"))
        assertTrue("omitted count is 1", medium.contains("省略了 1 行，look --last 可回看"))

        // Stage 3: huge elementLines does NOT count toward budget
        val smallNonElem = parts.copy(
            betweenLines = listOf("中途出现过又消失：“短字”"),
            picDetailLines = listOf("-0.5s 变化 [0,0,200,200] 5% (0.1s)"),
            elementLines = (1..200).map { "#$it “很长的元素列表不应该算进文字预算$it” (100,${it * 10})" },
        )
        val untouched = applyReplyBudget(smallNonElem, budgetTokens = Report.BUDGET_TOKENS)
        assertTrue("element list excluded from budget -> betweenLines untouched", untouched.contains("中途出现过又消失"))
        assertFalse("no omission line when under budget", untouched.contains("省略了"))
    }

    // ── 3.8 & 3.9: externalized memory (Turns, task recap, two-layer guide) ─

    @Test
    fun externalMemoryTurnsRecapAndCoreGuideBudgetZhAndEn() {
        // Turns counter per sid
        assertEquals(0, Turns.turnOf("s1"))
        assertEquals(1, Turns.bumpTurn("s1"))
        assertEquals(2, Turns.bumpTurn("s1"))
        assertEquals(0, Turns.turnOf("s2"))
        Turns.resetTurn("s1")
        assertEquals(0, Turns.turnOf("s1"))

        // task recap: <= 600 chars, keeps at most 12 steps with "…前 N 步", includes clickables
        val current = PageBook.Card(
            id = 6,
            activity = "com.example/.Detail",
            els = listOf(el(1, "断开连接", 200, 200), el(2, "取消配对", 200, 300), el(3, "返回", 100, 100)),
            title = "设备详情",
            seenTurn = 18,
            version = 1,
        )
        val manySteps = (1..16).map { i ->
            TaskLog.RecapStep(
                cmd = "act",
                steps = listOf("tap 按钮$i" to (if (i == 10) "NO_CHANGE" else null)),
                pageId = if (i == 16) "P6" else "P${(i % 5) + 1}",
                pageTitle = if (i == 16) "设备详情" else "页$i",
                code = if (i == 10) "NO_CHANGE" else "OK",
                exit = if (i == 10) 1 else 0,
            )
        }
        val recap = Tasks.formatRecap(turns = 18, cards = listOf(current), current = current, entries = manySteps)
        assertTrue("recap <= 600 chars, got ${recap.length}: $recap", recap.length <= Tasks.RECAP_MAX_CHARS)
        assertTrue("recap summarizes dropped steps: $recap", recap.contains("…前 4 步"))
        assertTrue("recap lists current page clickables: $recap", recap.contains("现在 P6") && recap.contains("断开连接"))
        assertTrue("recap includes failure code: $recap", recap.contains("NO_CHANGE"))

        // Two-layer guide: core guide <= 1500 tokens in BOTH ZH and EN, and all 8 topics exist
        I18n.choice = I18n.Lang.ZH
        val zhGuide = AgentGuide.guide()
        val zhTokens = Meter.of(zhGuide, emptyList()).estTokens
        assertTrue("ZH core guide must be <= 1500 tokens, got $zhTokens", zhTokens <= AgentGuide.CORE_GUIDE_MAX_TOKENS)
        assertTrue("ZH core guide leads with until rule", zhGuide.contains("每个动作都写 `until`"))
        assertTrue("ZH core guide includes 3.11 guidance", zhGuide.contains("短回复是程序的判断"))

        I18n.choice = I18n.Lang.EN
        val enGuide = AgentGuide.guide()
        val enTokens = Meter.of(enGuide, emptyList()).estTokens
        assertTrue("EN core guide must be <= 1500 tokens, got $enTokens", enTokens <= AgentGuide.CORE_GUIDE_MAX_TOKENS)
        assertTrue("EN core guide leads with until rule", enGuide.contains("Write `until` on every action"))
        assertTrue("EN core guide includes 3.11 guidance", enGuide.contains("Short replies are the phone's judgment"))

        assertEquals(
            listOf("gesture", "wait", "teach", "schedule", "keep", "screen", "safety", "codes"),
            AgentGuide.TOPICS,
        )
        for (t in AgentGuide.TOPICS) {
            val content = AgentGuide.topic(t)
            assertNotNull("topic $t must exist", content)
            assertTrue("topic $t must be non-blank", content!!.isNotBlank())
        }
        assertNull(AgentGuide.topic("nonexistent_topic"))
    }

    // ── 3.1, 3.8, 3.11: escape hatches, auto-escalation, NO_EXPECT hint ──

    @Test
    fun escapeHatchesAutoEscalationAndNoExpectHintThrottling() {
        // Per-agent reply mode via AgentLedger + `note reply quiet|full` without open app
        AgentLedger.init(ctx)
        assertEquals(AgentLedger.REPLY_QUIET, AgentLedger.replyMode("agent_a"))
        val handler = VScreenOffloadHandler(ctx)
        Call.with(Call("s1", "agent_a")) {
            val rFull = handler.noteCmd("s1", OffloadArgs(listOf("reply", "full")))
            assertEquals(0, rFull.exitCode)
            assertEquals(AgentLedger.REPLY_FULL, AgentLedger.replyMode("agent_a"))
            assertEquals(AgentLedger.REPLY_QUIET, AgentLedger.replyMode("agent_b"))

            val rQuiet = handler.noteCmd("s1", OffloadArgs(listOf("reply", "quiet")))
            assertEquals(0, rQuiet.exitCode)
            assertEquals(AgentLedger.REPLY_QUIET, AgentLedger.replyMode("agent_a"))
        }

        // Auto-escalation: 3 refetches within 10 quiet replies -> force full for 10 turns, notice once
        val sid = "s_esc"
        for (i in 1..3) {
            Loud.recordActReply(sid, quiet = true, fromMs = i * 1000L, toMs = i * 1000L + 500L)
            assertTrue(Loud.onCommand(sid, "look", isRefetchCmd = true))
        }
        assertEquals(3, Loud.refetchCount(sid))
        val first = Loud.consumeEscalation(sid)
        assertEquals(true to true, first)
        for (i in 2..Loud.ESCALATE_TURNS) {
            assertEquals(true to false, Loud.consumeEscalation(sid))
        }
        assertEquals(false to false, Loud.consumeEscalation(sid))

        // NO_EXPECT coaching hint at most once every 10 turns per sid
        assertTrue(Loud.allowNoExpectHint(sid, 1))
        for (t in 2..10) assertFalse("turn $t must be throttled", Loud.allowNoExpectHint(sid, t))
        assertTrue("turn 11 allowed again", Loud.allowNoExpectHint(sid, 11))

        // Bare `look --last` normalizes cleanly for 3.7/3.11 escape hatch
        assertEquals(listOf("--last=window"), VScreenOptions.normalize("look", listOf("--last")))
        assertEquals(listOf("--last", "30000"), VScreenOptions.normalize("look", listOf("--last", "30s")))
    }
}
