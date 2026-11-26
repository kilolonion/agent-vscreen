package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.AgentGuide
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.Mcp
import com.kilon.agentvscreen.core.Meter
import com.kilon.agentvscreen.core.TaskLog
import com.kilon.agentvscreen.core.VScreenActivity
import com.kilon.agentvscreen.core.VScreenMap
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenRecorder
import com.kilon.agentvscreen.core.VScreenSpots
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 2.0.0-beta16 unit tests locking in the root-cause rules for all 9 items across ZH/EN,
 * multiple screen resolutions, and multiple UI layouts.
 */
@RunWith(RobolectricTestRunner::class)
class V200Beta16Test {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        I18n.choice = I18n.Lang.ZH
        ApiConfig.init(ctx)
    }

    @After
    fun tearDown() {
        I18n.choice = I18n.Lang.AUTO
    }

    private fun el(
        text: String,
        l: Int, t: Int, r: Int, b: Int,
        n: Int = 0,
        a11y: Boolean = true,
        click: Boolean = false,
        checked: Boolean? = null,
        kind: ElKind = ElKind.TEXT,
        textBoxes: List<Rect> = emptyList(),
    ) = El(n, text, Rect(l, t, r, b), a11y, clickable = click, checked = checked, kind = kind, textBoxes = textBoxes)

    // ── Item 1: type X until text:X reason-aware rejection & timeout wording ──

    @Test
    fun item1_countsCarriesRejectionReasonAndClassifiesOnlyInField() {
        val fieldBox = Rect(40, 120, 1040, 220)
        val priorBox = Rect(40, 500, 420, 560)

        // Rejection inside typed field carries IN_TYPED_FIELD (even on a new page).
        assertEquals(CountDecision.IN_TYPED_FIELD, counts(Rect(100, 145, 360, 195), emptyList(), fieldBox, newPage = true))
        assertEquals(CountDecision.IN_TYPED_FIELD, counts(Rect(100, 145, 360, 195), listOf(priorBox), fieldBox, newPage = false))
        // Pre-existing at same spot carries PRE_EXISTING; moved or new page carries COUNTED.
        assertEquals(CountDecision.PRE_EXISTING, counts(Rect(42, 502, 418, 558), listOf(priorBox), fieldBox, newPage = false))
        assertEquals(CountDecision.COUNTED, counts(Rect(40, 720, 420, 780), listOf(priorBox), fieldBox, newPage = false))
        assertEquals(CountDecision.COUNTED, counts(Rect(42, 502, 418, 558), listOf(priorBox), fieldBox, newPage = true))

        // Classification distinguishes ONLY_IN_FIELD from PRE_EXISTING and ABSENT.
        assertEquals(
            TextTimeoutKind.ONLY_IN_FIELD,
            classifyTextTimeout(
                preExisting = false,
                hadPostActionOcr = true,
                hadPostActionA11y = true,
                ocrBusy = false,
                waitingForChange = false,
                onlyInField = true,
            ),
        )
        assertEquals(
            TextTimeoutKind.PRE_EXISTING,
            classifyTextTimeout(
                preExisting = true,
                hadPostActionOcr = true,
                hadPostActionA11y = true,
                ocrBusy = false,
                waitingForChange = false,
                onlyInField = false,
            ),
        )
        assertEquals(
            TextTimeoutKind.ABSENT,
            classifyTextTimeout(
                preExisting = false,
                hadPostActionOcr = true,
                hadPostActionA11y = true,
                ocrBusy = false,
                waitingForChange = false,
                onlyInField = false,
            ),
        )

        // Both ZH and EN messages for ONLY_IN_FIELD explain the rule and never say "not on screen".
        for (lang in listOf(I18n.Lang.ZH, I18n.Lang.EN)) {
            I18n.choice = lang
            val confirmed = formatTextTimeout("hello", TextTimeoutKind.ONLY_IN_FIELD, ocrBusy = false, typedMatchesTarget = true)
            val unconfirmed = formatTextTimeout("hello", TextTimeoutKind.ONLY_IN_FIELD, ocrBusy = false, typedMatchesTarget = false)
            assertTrue("($lang) confirmed mentions until stable: $confirmed", confirmed.contains("until stable"))
            assertTrue("($lang) unconfirmed mentions until stable: $unconfirmed", unconfirmed.contains("until stable"))
            if (lang == I18n.Lang.ZH) {
                assertTrue(confirmed, confirmed.contains("输入已成功") && confirmed.contains("条件写错了"))
                assertFalse(confirmed, confirmed.contains("画面上没有"))
                assertTrue(unconfirmed, unconfirmed.contains("刚输入的框"))
                assertFalse(unconfirmed, unconfirmed.contains("画面上没有"))
            } else {
                assertTrue(confirmed, confirmed.contains("typing already succeeded") && confirmed.contains("wrong condition"))
                assertFalse(confirmed, confirmed.contains("not on the screen"))
                assertTrue(unconfirmed, unconfirmed.contains("box just typed into"))
                assertFalse(unconfirmed, unconfirmed.contains("not on the screen"))
            }
            // AgentGuide prompt & wait topic document that until text: ignores the typed box.
            val prompt = AgentGuide.prompt()
            val waitTopic = AgentGuide.topic("wait")!!
            if (lang == I18n.Lang.ZH) {
                assertTrue("ZH prompt should note text: ignores typed box", prompt.contains("刚输入的框"))
                assertTrue("ZH wait topic should note text: ignores typed box: $waitTopic", waitTopic.contains("刚输入的框"))
            } else {
                assertTrue("EN prompt should note text: ignores typed box", prompt.contains("box just typed into"))
                assertTrue("EN wait topic should note text: ignores typed box: $waitTopic", waitTopic.contains("box just typed into"))
            }
        }
    }

    // ── Item 2: MCP tools/list description dedup, hidden OLD_FORMS, size budget ──

    @Test
    fun item2_mcpToolsHaveNoRepeatedLinesHideOldFormsAndFitBudget() {
        val oldNames = CommandSpec.OLD_FORMS.keys
        val oldToolNames = oldNames.flatMap { setOf(it, "vscreen_$it") }.toSet()

        for (lang in listOf(I18n.Lang.ZH, I18n.Lang.EN)) {
            I18n.choice = lang
            val tools = Mcp.toolsList()
            assertTrue("tools/list should not be empty", tools.length() > 10)
            val listedNames = HashSet<String>()
            for (i in 0 until tools.length()) {
                val t = tools.getJSONObject(i)
                val name = t.getString("name")
                listedNames += name
                assertFalse("($lang) OLD_FORM leaked into tools/list: $name", name in oldToolNames)
                val desc = t.getString("description")
                assertTrue("($lang) $name description blank", desc.isNotBlank())
                val lines = desc.lines().map { it.trim() }.filter { it.isNotEmpty() }
                for (j in 1 until lines.size) {
                    assertFalse(
                        "($lang) $name has consecutive repeated description line: ${lines[j]}",
                        lines[j] == lines[j - 1],
                    )
                }
            }
            // Hidden commands must not appear as command entries in help lists, yet remain usable via CommandSpec.of / helpFor.
            val fullHelp = CommandSpec.fullHelp()
            val quickHelp = CommandSpec.quickHelp()
            for (old in oldNames) {
                assertFalse("($lang) fullHelp still lists hidden command '$old'", Regex("(?m)^  $old(\\s|$)").containsMatchIn(fullHelp))
                assertFalse("($lang) quickHelp still lists hidden command '$old'", Regex("(?m)^  $old(\\s|$)").containsMatchIn(quickHelp))
                assertNotNull("CLI compatibility broken for '$old'", CommandSpec.of(old))
                assertNotNull("help <cmd> compatibility broken for '$old'", CommandSpec.helpFor(old))
            }
            // Budget baseline check for total serialized tools/list payload (beta16 baseline: ZH 16,191 / EN 27,164 chars).
            val totalChars = tools.toString().length
            val budget = if (lang == I18n.Lang.ZH) 18_000 else 30_000
            println("MCP tools/list baseline ($lang): ${tools.length()} tools, $totalChars chars")
            assertTrue("($lang) tools/list grew past budget ($totalChars > $budget)", totalChars <= budget)
        }
    }

    // ── Item 3: short & diff replies carry only shown elements; meter tracks structured size ──

    @Test
    fun item3_shortAndDiffRepliesCarryOnlyShownElementsAndMeterTracksStructured() {
        val prev = (1..20).map { i -> el("Row $i", 40, i * 100, 900, i * 100 + 80, n = i, click = true) }
        val added = el("New Item", 40, 2100, 900, 2180, n = 21, click = true)
        val modified = el("Row 2 Updated", 40, 200, 900, 280, n = 2, click = true)
        val now = prev.map { if (it.n == 2) modified else it } + added
        val card = PageBook.Card(
            id = 3,
            activity = "com.example/.Settings",
            els = now,
            title = "Settings",
            seenTurn = 2,
            version = 1,
        ).apply {
            isNew = false
            canRef = true
            prevSeenTurn = 1
            prevEls = prev
        }
        val step = StepOut("1", "tap Row 2 until text:New Item", "done", "1 tap Row 2").apply {
            waitedFor = "text:New Item"; waitMet = true; waitMs = 400L
        }

        // Quiet short reply: only the 2 shown (added + modified) elements go into screen.elements.
        val shortReply = formatShortReplyWithShown(2, card, listOf(step), 500L, now)
        assertEquals(2, shortReply.shown.size)
        val shortScreen = putScreenElements(JSONObject(), all = now, shown = shortReply.shown, fullElements = false)
        assertEquals(2, shortScreen.getJSONArray("elements").length())
        assertEquals(21, shortScreen.getInt("elements_total"))
        assertEquals(19, shortScreen.getInt("elements_omitted"))

        // Quiet short reply with 0 diffs: 0 elements sent, all omitted.
        val unchangedCard = PageBook.Card(
            id = 3,
            activity = "com.example/.Settings",
            els = prev,
            title = "Settings",
            seenTurn = 3,
            version = 1,
        ).apply {
            isNew = false
            canRef = true
            prevSeenTurn = 2
            prevEls = prev
        }
        val zeroShort = formatShortReplyWithShown(3, unchangedCard, listOf(step), 200L, prev)
        assertTrue(zeroShort.shown.isEmpty())
        val zeroScreen = putScreenElements(JSONObject(), all = prev, shown = zeroShort.shown, fullElements = false)
        assertEquals(0, zeroScreen.getJSONArray("elements").length())
        assertEquals(20, zeroScreen.getInt("elements_total"))
        assertEquals(20, zeroScreen.getInt("elements_omitted"))

        // --full / launch / look path: all elements included, 0 omitted.
        val fullScreen = putScreenElements(JSONObject(), all = now, shown = shortReply.shown, fullElements = true)
        assertEquals(21, fullScreen.getJSONArray("elements").length())
        assertEquals(21, fullScreen.getInt("elements_total"))
        assertEquals(0, fullScreen.getInt("elements_omitted"))

        // Meter reports structured_chars and structured_est_tokens, and short reply is much smaller than full.
        val shortCost = Meter.of(shortReply.text, emptyList(), shortScreen.toString())
        val fullCost = Meter.of(shortReply.text, emptyList(), fullScreen.toString())
        assertTrue(shortCost.json().has("structured_chars"))
        assertTrue(shortCost.json().has("structured_est_tokens"))
        assertEquals(shortScreen.toString().length, shortCost.structuredChars)
        assertTrue(
            "short structured (${shortCost.structuredChars}) should be much smaller than full (${fullCost.structuredChars})",
            shortCost.structuredChars!! * 4 < fullCost.structuredChars!!,
        )
    }

    // ── Item 4: launch lists only 5 most recently used segment names without steps ──

    @Test
    fun item4_launchLimitsSegmentsToFiveMostRecentNamesWithoutExpandingSteps() {
        val m = VScreenMap.AppMap(pkg = "com.example.shop")
        for (i in 1..15) {
            m.notes += VScreenMap.Note(id = i, text = "note-$i", at = 1_000L * i, appVersion = "1.0")
        }
        for (i in 1..8) {
            m.segments += VScreenMap.Segment(
                name = "seg-$i",
                steps = "tap Button$i; swipe up; tap Confirm$i",
                at = 1_000L * i,
                uses = i,
                // Make seg-2, seg-4, seg-6, seg-7, seg-8 the 5 most recently used (out of insertion order).
                lastUsedAt = when (i) {
                    2 -> 90_000L
                    4 -> 80_000L
                    6 -> 70_000L
                    7 -> 60_000L
                    8 -> 50_000L
                    else -> 1_000L * i
                },
            )
        }

        // Segment JSON round-trip preserves last_used_at.
        val segJson = m.segments.first { it.name == "seg-2" }.json()
        assertEquals(90_000L, segJson.getLong("last_used_at"))

        for (lang in listOf(I18n.Lang.ZH, I18n.Lang.EN)) {
            I18n.choice = lang
            val lines = formatLaunchMapLines(m, verNow = "1.0")
            val joined = lines.joinToString("\n")
            // Notes capped at LaunchLimits.MAX_NOTES (12), overflow count reported.
            assertTrue(joined, joined.contains("note-15"))
            assertFalse(joined, Regex("""(?<!\d)1\. note-1(?!\d)""").containsMatchIn(joined))
            assertTrue(joined, joined.contains("3"))

            // Segments line lists ONLY the 5 most recent names, no expanded steps.
            val segLine = lines.last()
            for (recent in listOf("seg-2", "seg-4", "seg-6", "seg-7", "seg-8")) {
                assertTrue("($lang) missing recent segment $recent in: $segLine", segLine.contains(recent))
            }
            for (older in listOf("seg-1", "seg-3", "seg-5")) {
                assertFalse("($lang) older segment $older should not be listed in: $segLine", segLine.contains(older))
            }
            assertFalse("($lang) launch must not expand segment steps: $segLine", segLine.contains("tap Button"))
            assertFalse("($lang) launch must not expand segment steps: $segLine", segLine.contains("swipe up"))
            assertTrue("($lang) missing overflow hint for 3 remaining segments: $segLine", segLine.contains("3") && segLine.contains("note"))
        }
    }

    // ── Item 5: leading icon OCR noise absorbed across multiple row layouts + collect ──

    @Test
    fun item5_rowIconGlyphsAbsorbedAcrossLayoutsAndDoNotPolluteCollect() {
        // Test across two resolutions (1080x2400 ZH, 1440x3200 EN) and 3 row layouts:
        // 1) single-label clickable row; 2) title + summary clickable row (with digits in summary);
        // 3) title + summary + right-side switch clickable row.
        val configs = listOf(
            Triple(1080, 2400, I18n.Lang.ZH),
            Triple(1440, 3200, I18n.Lang.EN),
        )
        for ((w, h, lang) in configs) {
            I18n.choice = lang
            val sx = w / 1080.0
            val sy = h / 2400.0
            fun rx(v: Int) = (v * sx).toInt()
            fun ry(v: Int) = (v * sy).toInt()

            // Row 1: single-label row with leading icon misread by OCR as "1L".
            val row1TitleBox = Rect(rx(160), ry(240), rx(520), ry(290))
            val row1 = el(
                if (lang == I18n.Lang.ZH) "声音和振动" else "Sound & vibration",
                rx(0), ry(200), rx(1080), ry(330),
                click = true,
                textBoxes = listOf(row1TitleBox),
            )
            // Row 2: title + summary (with digits!) + leading icon misread as "88".
            val row2TitleBox = Rect(rx(160), ry(370), rx(480), ry(415))
            val row2SubBox = Rect(rx(160), ry(425), rx(620), ry(465))
            val row2 = el(
                if (lang == I18n.Lang.ZH) "电池 · 剩余 85%" else "Battery · 85% left",
                rx(0), ry(350), rx(1080), ry(490),
                click = true,
                textBoxes = listOf(row2TitleBox, row2SubBox),
            )
            // Row 3: title + summary + right-side switch, leading icon misread as "11", switch icon misread as "O" on detected icon box.
            val row3TitleBox = Rect(rx(160), ry(530), rx(450), ry(575))
            val row3SubBox = Rect(rx(160), ry(585), rx(560), ry(625))
            val row3 = el(
                if (lang == I18n.Lang.ZH) "飞行模式 · 已关闭" else "Airplane mode · Off",
                rx(0), ry(510), rx(1080), ry(650),
                click = true,
                textBoxes = listOf(row3TitleBox, row3SubBox),
            )
            val row3Switch = el("", rx(900), ry(550), rx(1020), ry(610), click = true, checked = false, kind = ElKind.NO_TEXT)

            val ocr = listOf(
                VScreenOcr.Item("1L", Rect(rx(45), ry(235), rx(105), ry(295)), emptyList()),
                VScreenOcr.Item("88", Rect(rx(45), ry(390), rx(105), ry(450)), emptyList()),
                VScreenOcr.Item("11", Rect(rx(45), ry(550), rx(105), ry(610)), emptyList()),
                VScreenOcr.Item("O", Rect(rx(935), ry(560), rx(985), ry(600)), emptyList()),
                // Genuine OCR badge on the right side (not in leading zone, not on detected icon): must stay.
                VScreenOcr.Item("NEW", Rect(rx(760), ry(240), rx(860), ry(290)), emptyList()),
            )
            val detectedIcons = listOf(
                Rect(rx(900), ry(550), rx(1020), ry(610)),
            )

            val fused = mergeElements(h, listOf(row1, row2, row3, row3Switch), ocr, detectedIcons)
            val texts = fused.map { it.text }
            for (noise in listOf("1L", "88", "11", "O")) {
                assertFalse(
                    "(${w}x$h $lang) icon noise '$noise' leaked into fused elements: $texts",
                    texts.any { t -> t == noise || t.split(" · ").any { tok -> tok.trim() == noise } },
                )
            }
            // Genuine 3-char OCR label outside leading zone stays visible.
            assertTrue("(${w}x$h $lang) genuine OCR label NEW should not be dropped: $texts", texts.contains("NEW"))
            // Digit-bearing row2 kept its real text ("85%") and wasn't overwritten by "88".
            assertTrue("(${w}x$h $lang) row2 text damaged: $texts", texts.any { it.contains("85%") })

            // collect (`entriesOfEls`) inherits the clean fused elements with no noise prefix.
            val collected = entriesOfEls(fused, w, h).map { it.first }
            for (noise in listOf("1L", "88", "11")) {
                assertFalse(
                    "(${w}x$h $lang) icon noise '$noise' leaked into collect: $collected",
                    collected.any { t -> t.split(" · ").any { tok -> tok.trim() == noise } },
                )
            }
        }
    }

    private fun fillRect(bmp: Bitmap, r: Rect, color: Int) {
        val l = r.left.coerceIn(0, bmp.width)
        val t = r.top.coerceIn(0, bmp.height)
        val right = r.right.coerceIn(l, bmp.width)
        val bottom = r.bottom.coerceIn(t, bmp.height)
        val rw = right - l
        val rh = bottom - t
        if (rw <= 0 || rh <= 0) return
        val row = IntArray(rw) { color }
        for (y in t until bottom) bmp.setPixels(row, 0, rw, l, y, rw, 1)
    }

    // ── Item 6: look --last shares [Meanwhile] clear-change filter ──

    @Test
    fun item6_replaySharesClearChangeFilterAndCollapsesStillCursorBlinks() {
        // Verify pixel-level clear-change filter on two resolutions: blinking cursor is NOT a clear change,
        // while a real content change IS a clear change.
        for ((w, h) in listOf(160 to 356, 160 to 284)) {
            val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val blink = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val real = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            try {
                // Thin blinking cursor (2x10 px on 160px thumbnail = < 0.2% of cells).
                fillRect(blink, Rect(24, 40, 26, 50), Color.BLACK)
                // Real popup / content block covering > 15% of frame.
                fillRect(real, Rect(20, 80, 140, 200), Color.rgb(30, 80, 180))
                assertEquals(Color.WHITE, base.getPixel(0, 0))
                assertEquals(Color.BLACK, blink.getPixel(25, 45))
                assertTrue("cursor blink should have >0 changed share but < CLEAR_CHANGE_SHARE", VScreenActivity.clearChangeShare(base, blink, null) < VScreenActivity.CLEAR_CHANGE_SHARE)
                assertFalse("cursor blink must not count as clear change (${w}x$h)", VScreenActivity.isClearChange(base, blink, null))
                assertTrue("real content block must count as clear change (${w}x$h)", VScreenActivity.isClearChange(base, real, null))
            } finally {
                base.recycle(); blink.recycle(); real.recycle()
            }
        }

        val dummyFile = java.io.File("dummy.jpg")
        // Still stretch with 10 cursor-blink frames (clearChange = false): returns only start + end (2 frames),
        // distinct == 1, and summary matches [Meanwhile] no-clear-change wording in ZH & EN.
        val stillFrames = (0..10).map { i ->
            VScreenRecorder.Frame(atMs = 1_000L + i * 500L, file = dummyFile, bytes = 0L, clearChange = (i == 0))
        }
        val stillSel = selectFrames(stillFrames, emptyList(), from = 1_000L, to = 6_000L, max = 12, every = null)
        assertEquals(2, stillSel.picks.size)
        assertEquals(stillFrames.first().atMs, stillSel.picks.first().frame.atMs)
        assertEquals(stillFrames.last().atMs, stillSel.picks.last().frame.atMs)
        assertEquals(1, stillSel.distinct)
        assertEquals(0, stillSel.clearChanges)

        for (lang in listOf(I18n.Lang.ZH, I18n.Lang.EN)) {
            I18n.choice = lang
            val summary = formatFramesSummary(
                distinct = stillSel.distinct,
                clearChanges = stillSel.clearChanges,
                actions = stillSel.actions,
                shownCount = stillSel.picks.size,
                leftOut = stillSel.leftOut,
                every = null,
            )
            if (lang == I18n.Lang.ZH) {
                assertTrue(summary, summary.contains("没有明显变化") && summary.contains("光标"))
            } else {
                assertTrue(summary, summary.contains("no clear change") && summary.contains("cursor"))
            }
        }

        // Mixed stretch: 6 cursor-blink frames + 2 clear-change frames -> only clear-change frames fill slots.
        val mixedFrames = listOf(
            VScreenRecorder.Frame(1_000L, dummyFile, 0L, clearChange = true),
            VScreenRecorder.Frame(1_500L, dummyFile, 0L, clearChange = false),
            VScreenRecorder.Frame(2_000L, dummyFile, 0L, clearChange = false),
            VScreenRecorder.Frame(2_500L, dummyFile, 0L, clearChange = true),
            VScreenRecorder.Frame(3_000L, dummyFile, 0L, clearChange = false),
            VScreenRecorder.Frame(3_500L, dummyFile, 0L, clearChange = true),
            VScreenRecorder.Frame(4_000L, dummyFile, 0L, clearChange = false),
            VScreenRecorder.Frame(4_500L, dummyFile, 0L, clearChange = false),
        )
        val mixedSel = selectFrames(mixedFrames, emptyList(), from = 1_000L, to = 4_500L, max = 12, every = null)
        assertEquals(3, mixedSel.distinct) // start + 2 clear changes
        assertEquals(2, mixedSel.clearChanges)
        // Picked frames are start + the 2 clear changes — none of the blink-only frames fill remaining slots.
        assertEquals(listOf(1_000L, 2_500L, 3_500L), mixedSel.picks.map { it.frame.atMs })
    }

    // ── Item 7: spots filters both OCR and a11y text boxes, and scales bounds by resolution ──

    @Test
    fun item7_spotsFiltersA11yAndOcrTextAndFindsLargeButtonsAcrossResolutions() {
        // Test across multiple resolutions (720x1600, 1080x2400, 1440x3200, landscape 2400x1080).
        val resolutions = listOf(
            720 to 1600,
            1080 to 2400,
            1440 to 3200,
            2400 to 1080,
        )
        for ((w, h) in resolutions) {
            val shortSide = minOf(w, h)
            val (minS, maxS) = VScreenSpots.defaultSideBounds(w, h)
            assertTrue("maxSide ($maxS) should scale with shortSide ($shortSide)", maxS >= shortSide / 4 && maxS <= shortSide / 2)
            assertTrue("minSide ($minS) < maxSide ($maxS)", minS in 4 until maxS)

            // Draw a screen with:
            // 1) A large colored graphic button (~22% of shortSide: > 160px on 1080p/1440p!) with NO text.
            // 2) A small dark digit stroke inside an a11y text node (OCR missed it).
            // 3) A small dark word stroke inside an OCR word box.
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            try {
                assertEquals(Color.WHITE, bmp.getPixel(0, 0))
                val btnSize = (shortSide * 0.22).toInt() // 158px@720, 237px@1080, 316px@1440
                val btnRect = Rect(shortSide / 10, shortSide / 10, shortSide / 10 + btnSize, shortSide / 10 + btnSize)
                fillRect(bmp, btnRect, Color.rgb(240, 110, 20))

                val digitSize = (shortSide * 0.04).toInt().coerceAtLeast(16)
                val digitX = btnRect.right + shortSide / 8
                val digitY = btnRect.top + digitSize
                val digitRect = Rect(digitX, digitY, digitX + digitSize, digitY + (digitSize * 1.4).toInt())
                fillRect(bmp, digitRect, Color.rgb(30, 30, 30))

                val ocrX = digitRect.right + shortSide / 8
                val ocrRect = Rect(ocrX, digitY, ocrX + digitSize, digitY + digitSize)
                fillRect(bmp, ocrRect, Color.rgb(40, 90, 200))

                val found = VScreenSpots.find(bmp)
                assertTrue(
                    "(${w}x$h) large colored button (${btnSize}px) should be found by VScreenSpots.find; found=${found.map { it.box }}",
                    found.any { Rect.intersects(it.box, btnRect) },
                )
                assertTrue(
                    "(${w}x$h) digit spot $digitRect should be in raw found=${found.map { it.box }}",
                    found.any { Rect.intersects(it.box, digitRect) },
                )
                assertTrue(
                    "(${w}x$h) ocr spot $ocrRect should be in raw found=${found.map { it.box }}",
                    found.any { Rect.intersects(it.box, ocrRect) },
                )

                // A11y button wrapping the digit (no OCR box for it), plus OCR box for the second glyph.
                val a11yDigitBtn = el(
                    "7",
                    digitRect.left - 20, digitRect.top - 20, digitRect.right + 20, digitRect.bottom + 20,
                    click = true,
                )
                val filtered = filterSpotsOnText(found, ocrBoxes = listOf(ocrRect), a11yEls = listOf(a11yDigitBtn))
                assertTrue("(${w}x$h) large non-text button must survive text filtering", filtered.kept.any { Rect.intersects(it.box, btnRect) })
                assertFalse("(${w}x$h) a11y digit stroke must be filtered out", filtered.kept.any { Rect.intersects(it.box, digitRect) })
                assertFalse("(${w}x$h) OCR glyph must be filtered out", filtered.kept.any { Rect.intersects(it.box, ocrRect) })
                assertTrue(
                    "(${w}x$h) skippedText (${filtered.skippedText}) should count both a11y and OCR text spots; found=${found.map { it.box }}, kept=${filtered.kept.map { it.box }}",
                    filtered.skippedText >= 2,
                )
            } finally {
                bmp.recycle()
            }
        }

        // Row with inner textBoxes: a colored icon beside the text stays, while a blob on the textBox is filtered.
        val iconSpot = VScreenSpots.Spot(Rect(40, 330, 100, 390), 70, 360, Color.BLUE, 3600, 0.9, false, "square")
        val textSpot = VScreenSpots.Spot(Rect(180, 335, 240, 375), 210, 355, Color.BLACK, 2400, 0.5, false, "blob")
        val rowEl = el(
            "Wi-Fi · Connected",
            0, 300, 1080, 440,
            click = true,
            textBoxes = listOf(Rect(160, 320, 420, 380), Rect(160, 385, 480, 425)),
        )
        val rowFiltered = filterSpotsOnText(listOf(iconSpot, textSpot), emptyList(), listOf(rowEl))
        assertEquals(listOf(iconSpot), rowFiltered.kept)
        assertEquals(1, rowFiltered.skippedText)
    }

    // ── Item 8: task recap compact control-flow markers & bracket-safe truncation ──

    @Test
    fun item8_recapUsesCompactControlFlowMarkersAndNeverCutsInsideBrackets() {
        // Explicit recap labels from StepOut (with closing duplicates marked "") produce compact markers without `{ ... }`.
        val stepsArr = JSONArray()
            .put(StepOut("1", "if text:允许 { tap 允许; sleep 1s } else { tap 拒绝 }", "done", "1 if").apply {
                recapLabel = "if text:允许 → 是"
            }.json())
            .put(StepOut("1.1", "tap 允许 until text:主页", "done", "1.1 tap").json())
            .put(StepOut("2", "try { tap 跳过广告 }", "done", "2 try").apply {
                recapLabel = "try → 跳过"
            }.json())
            .put(StepOut("2", "try { tap 跳过广告 }", "done", "2 try skipped").apply {
                recapLabel = ""
            }.json())
            .put(StepOut("3", "repeat 5 { swipe up } until text:关于手机", "done", "3 repeat").apply {
                recapLabel = "repeat ×2 until text:关于手机"
            }.json())
            .put(StepOut("3", "repeat 5 { swipe up } until text:关于手机", "done", "3 repeat ok").apply {
                recapLabel = ""
            }.json())
        val data = JSONObject()
            .put("steps", stepsArr)
            .put("screen", JSONObject().put("page", JSONObject().put("id", "P2").put("title", "主页")))
        val recap = TaskLog.extractRecap(listOf("act", "…"), "OK", 0, NativeOffloadResult(0, "", "OK", data))!!
        assertEquals(
            listOf("if text:允许 → 是", "tap 允许", "try → 跳过", "repeat ×2 until text:关于手机"),
            recap.steps.map { it.first },
        )
        assertTrue("no braces should appear in recap steps", recap.steps.none { '{' in it.first || '}' in it.first })

        // Fallback compaction when step JSON has no explicit recap field.
        assertEquals("if text:Allow", TaskLog.compactStepForRecap("if text:Allow { tap Allow; sleep 1s } else { tap Deny }"))
        assertEquals("try", TaskLog.compactStepForRecap("try { tap Skip }"))
        assertEquals("repeat 4 until text:End", TaskLog.compactStepForRecap("repeat 4 { swipe up } until text:End"))

        // Bracket-safe truncation across paired delimiters (both ZH and EN).
        val samples = listOf(
            "if text:确认继续操作 { tap 确认; sleep 1s }",
            "tap 设置项（包含非常长的副标题内容说明文字）",
            "tap Action button (with a very long parenthetical description)",
            "swipe very long description [120,340,560,780]",
            "tap 「超长的中文书名号或者引号标题内容在这里」",
            "tap 【很长的一个方括号标题内容在这里测试】",
            "type \"a very long search query string typed into the field\"",
            "type \"hello (nested very long parenthetical text)\"",
            "(a label starting directly with a very long parenthetical text)",
        )
        fun hasBalancedDelimiters(s: String): Boolean {
            val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '（' to '）', '「' to '」', '【' to '】', '“' to '”')
            val closers = pairs.values.toSet()
            val st = ArrayDeque<Char>()
            for (ch in s) {
                when {
                    ch == '"' -> if (st.isNotEmpty() && st.last() == '"') st.removeLast() else st.addLast('"')
                    ch in pairs -> st.addLast(pairs.getValue(ch))
                    ch in closers -> if (st.isEmpty() || st.removeLast() != ch) return false
                }
            }
            return st.isEmpty()
        }
        for (sample in samples) {
            for (maxLen in listOf(16, 22, 28)) {
                val out = Tasks.truncateStepLabel(sample, maxLen)
                assertTrue("length ${out.length} > $maxLen for '$out'", out.length <= maxLen)
                assertTrue("unbalanced brackets/quotes in '$out' (from '$sample' @ $maxLen)", hasBalancedDelimiters(out))
            }
        }

        // End-to-end formatRecap in ZH and EN stays concise and bracket-balanced.
        for (lang in listOf(I18n.Lang.ZH, I18n.Lang.EN)) {
            I18n.choice = lang
            val rendered = Tasks.formatRecap(2, emptyList(), null, listOf(recap))
            assertFalse("($lang) formatRecap leaked braces: $rendered", '{' in rendered || '}' in rendered)
            assertTrue("($lang) formatRecap over limit: ${rendered.length}", rendered.length <= Tasks.RECAP_MAX_CHARS)
        }
    }

    // ── Item 9: zero fullwidth punctuation in EN outputs & accurate shared-state docs ──

    @Test
    fun item9_englishOutputsHaveNoFullwidthPunctuationAndClarifySharedNotes() {
        val fullwidth = Regex("[\\u3001-\\u301F\\uFF01-\\uFF60]")
        I18n.choice = I18n.Lang.EN

        val surfaces = LinkedHashMap<String, String>()
        surfaces["guide"] = AgentGuide.guide()
        surfaces["prompt"] = AgentGuide.prompt()
        for (topic in AgentGuide.TOPICS) {
            surfaces["topic:$topic"] = AgentGuide.topic(topic)!!
        }
        surfaces["memory"] = AgentGuide.memory("http://127.0.0.1:18765")
        surfaces["memoryMcp"] = AgentGuide.memoryMcp()
        surfaces["memoryHttp"] = AgentGuide.memoryHttp("http://127.0.0.1:18765")
        surfaces["skill"] = AgentGuide.skill()
        surfaces["advice"] = AgentGuide.advice()
        surfaces["quickHelp"] = CommandSpec.quickHelp()
        surfaces["fullHelp"] = CommandSpec.fullHelp()
        surfaces["codesHelp"] = Codes.help()
        for (cmd in CommandSpec.commands) {
            surfaces["help:${cmd.name}"] = CommandSpec.helpFor(cmd.name)!!
        }
        val tools = Mcp.toolsList()
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            surfaces["mcp:${t.getString("name")}"] = t.toString()
        }

        val violations = ArrayList<String>()
        for ((name, text) in surfaces) {
            val m = fullwidth.find(text)
            if (m != null) {
                val start = (m.range.first - 20).coerceAtLeast(0)
                val end = (m.range.last + 21).coerceAtMost(text.length)
                violations += "$name: '${m.value}' (U+%04X) in \"${text.substring(start, end)}\"".format(m.value[0].code)
            }
        }
        assertTrue("English outputs contain fullwidth punctuation:\n${violations.joinToString("\n")}", violations.isEmpty())

        // Both ZH and EN identity guidance accurately distinguish per-agent items from shared notes/segments.
        I18n.choice = I18n.Lang.ZH
        val zhMem = AgentGuide.memoryMcp()
        assertTrue("ZH memory should state notes/segments are shared: $zhMem", zhMem.contains("应用笔记") && zhMem.contains("所有 agent 共享"))

        I18n.choice = I18n.Lang.EN
        val enMem = AgentGuide.memoryMcp()
        assertTrue("EN memory should state notes/segments are shared: $enMem", enMem.contains("app notes") && enMem.contains("shared across all agents"))
    }
}
