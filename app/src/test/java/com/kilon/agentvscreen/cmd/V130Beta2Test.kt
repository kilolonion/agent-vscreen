package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import com.kilon.agentvscreen.core.Mask
import com.kilon.agentvscreen.core.MotionTrack
import com.kilon.agentvscreen.core.VScreenMute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 1.3.0-beta2: what the 1.2.0 field report found, fixed at the root: waits judge what came after the action, numbers
 * belong to a page, one element per place, moving areas, the main-screen fact, collect by cards, honest time.
 */
@RunWith(RobolectricTestRunner::class)
class V130Beta2Test {
    private fun el(text: String, l: Int, t: Int, r: Int, b: Int, a11y: Boolean = true, click: Boolean = false, kind: ElKind = ElKind.TEXT) =
        El(0, text, Rect(l, t, r, b), a11y, clickable = click, kind = kind)

    @Test fun untilTextCountsOnlyWordsTheActionBrought() {
        val wlanBefore = listOf(Rect(40, 400, 400, 460))
        // Same place, same page: the old page's words (tap 蓝牙 until text:WLAN met at 0.0 s in 1.2).
        assertEquals(CountDecision.PRE_EXISTING, counts(Rect(42, 402, 398, 458), wlanBefore, null, newPage = false))
        // Moved (the new page's title repeats the tapped row's words): counts.
        assertEquals(CountDecision.COUNTED, counts(Rect(40, 100, 400, 160), wlanBefore, null, newPage = false))
        // A new page: whatever it shows counts.
        assertEquals(CountDecision.COUNTED, counts(Rect(42, 402, 398, 458), wlanBefore, null, newPage = true))
        // Inside the field typed into: never (type 地图导航 until text:导航).
        assertEquals(CountDecision.IN_TYPED_FIELD, counts(Rect(100, 200, 300, 240), emptyList(), Rect(0, 180, 1080, 260), newPage = true))
    }

    @Test fun numbersBelongToOnePage() {
        val home = (0 until 8).map { el("row$it", 0, 200 + it * 150, 1080, 330 + it * 150) } + el("显示和亮度", 0, 2620, 1080, 2750)
        // Scrolled by 300: the same page, everything moved together.
        val scrolled = home.map { it.copy(box = Rect(it.box.left, it.box.top - 300, it.box.right, it.box.bottom - 300)) }
        assertTrue(samePage(home, scrolled))
        val shift = commonShift(home, scrolled)!!
        assertEquals(0, shift.first); assertEquals(-300, shift.second)
        // The next page shares only the title's words with the row that was tapped: another page.
        val sub = listOf(el("显示和亮度", 300, 100, 960, 160), el("亮度", 0, 300, 1080, 400), el("深色模式", 0, 500, 1080, 600), el("护眼模式", 0, 700, 1080, 800))
        assertFalse(samePage(home, sub))
    }

    @Test fun movingAreasNeedToLast() {
        val cols = 24; val rows = 40
        val t = MotionTrack(cols, rows)
        val video = BooleanArray(cols * rows) { i -> (i / cols) in 5..12 }
        // 1 s of a page sliding in (everything changes): not a moving area.
        for (k in 0 until 10) t.feed(k * 100L, BooleanArray(cols * rows) { true })
        assertNull(t.moving(1_000L))
        // 3 s of a video: its rows are moving, the rest is not; one blinking cell is not "playing".
        val t2 = MotionTrack(cols, rows)
        for (k in 0 until 30) t2.feed(k * 100L, video.copyOf().also { it[30 * cols + 3] = k % 2 == 0 })
        val m = t2.moving(3_000L)
        assertNotNull(m)
        assertTrue(m!![6 * cols + 10]); assertFalse(m[20 * cols + 10]); assertFalse(m[30 * cols + 3])
        val mask = Mask(m, cols, rows, 1080, 2400)
        assertTrue(mask.covers(540, 9 * 60)); assertFalse(mask.covers(540, 2000))
        assertTrue(mask.coversMostOf(Rect(100, 330, 900, 700)))
    }

    @Test fun mainScreenIsReportedOnlyWhenWeCausedIt() {
        // 1.4.0-beta2: one classifier (VScreenOwnership.see) for every "an app of ours is on the main screen".
        val O = com.kilon.agentvscreen.core.VScreenOwnership
        fun t(id: Int, p: String) = com.kilon.agentvscreen.core.VScreenHandoff.TaskRef(id, p, true)
        val ours = mapOf(50 to "com.shop"); val main0 = mapOf(10 to "com.user.chat", 11 to "com.x")
        // The user switched apps on the main screen: not news.
        val a = O.see(ours, ours, main0 + (12 to "com.user.music"), main0, t(12, "com.user.music"), setOf("com.shop"), emptySet())
        assertTrue(a.moved.isEmpty()); assertFalse(a.came)
        // Our task came to the main screen.
        val b = O.see(ours, emptyMap(), main0 + (50 to "com.shop"), main0, t(50, "com.shop"), emptySet(), emptySet())
        assertEquals(listOf(50 to "com.shop"), b.moved); assertFalse(b.came)
        // A page of the app we drive opened on the main screen as a new task.
        val c = O.see(ours, ours, main0 + (60 to "com.shop"), main0, t(60, "com.shop"), setOf("com.shop"), emptySet())
        assertTrue(c.came)
        // Already with the user: said once, not again.
        assertTrue(O.see(ours, emptyMap(), main0 + (50 to "com.shop"), main0, t(50, "com.shop"), emptySet(), setOf("com.shop")).moved.isEmpty())
        // The first look has nothing to compare with.
        assertFalse(O.see(ours, ours, main0 + (60 to "com.shop"), null, t(60, "com.shop"), setOf("com.shop"), emptySet()).came)
    }

    @Test fun collectTakesCardsNotLines() {
        // Two cards side by side, each with a title and a count; a sticky search box at the top.
        val els = listOf(
            el("搜索", 0, 0, 1080, 120),
            el("", 0, 200, 530, 800, click = true, kind = ElKind.TEXT).copy(text = "视频卡片A"),
            el("左边的标题", 20, 600, 510, 660, a11y = false), el("1.2万", 20, 700, 200, 750, a11y = false),
            el("", 550, 200, 1080, 800, click = true).copy(text = "视频卡片B"),
            el("右边的标题", 570, 600, 1060, 660, a11y = false), el("3.4万", 570, 700, 760, 750, a11y = false),
        )
        val e = entriesOfEls(els, 1080, 2400, fixed = listOf(Rect(0, 0, 1080, 120))).map { it.first }
        assertEquals(2, e.size)
        assertTrue(e[0].contains("左边的标题") && e[0].contains("1.2万") && !e[0].contains("右边"))
        assertTrue(e[1].contains("右边的标题") && !e[1].contains("左边"))
        // Bars that stay put while the page scrolls are found by place, whatever their words.
        val before = listOf(el("Q 快手 搜索", 0, 0, 1080, 120), el("row", 0, 500, 1080, 600))
        val after = listOf(el("Q 滴滴 搜索", 0, 0, 1080, 120), el("row", 0, 200, 1080, 300))
        assertEquals(listOf(Rect(0, 0, 1080, 120)), fixedBoxes(before, after))
    }

    @Test fun timeIsSplitByWhereItWasSpent() {
        val h = sinceHeader(from = 1_000L, to = 21_000L, actionMs = 1_000L, receivedAt = 12_000L)
        assertTrue(h, h.contains("11.0s") && h.contains("9.0s") && h.contains("1.0s"))
    }

    @Test fun oneOffSchedulesAndWindowsOpenOnce() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val now = java.time.ZonedDateTime.of(2026, 11, 14, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val today = com.kilon.agentvscreen.host.Wake.parse("today 10:11", now, zone)
        assertEquals(java.time.LocalDate.of(2026, 11, 14), today.date)
        assertEquals("2026-11-14 10:11", today.canonical())
        assertEquals(java.time.LocalDate.of(2026, 11, 15), com.kilon.agentvscreen.host.Wake.parse("once 09:00", now, zone).date)
        assertEquals(java.time.LocalDate.of(2026, 11, 20), com.kilon.agentvscreen.host.Wake.parse("11-20 07:30", now, zone).date)
        try { com.kilon.agentvscreen.host.Wake.parse("today 09:00", now, zone); org.junit.Assert.fail("past accepted") } catch (_: com.kilon.agentvscreen.host.Wake.BadSpec) {}
        // Only that day.
        val at = com.kilon.agentvscreen.host.Wake.next(today, now, zone)!!
        assertNull(com.kilon.agentvscreen.host.Wake.next(today, at, zone))
        // A window in progress stays "next"; once opened it is not pending again.
        val e = com.kilon.agentvscreen.host.Wake.Entry("t1", "10:11", com.kilon.agentvscreen.host.Wake.parse("10:11", now, zone), 10, "s", "main", now)
        val during = at + 60_000L
        val first = e.pending(during)!!
        e.opened = first
        val again = e.pending(during)!!
        assertTrue(again > first + 23 * 3_600_000L)
    }

    @Test fun playAudioModeIsRead() {
        assertEquals("allow", VScreenMute.parseMode("PLAY_AUDIO: allow; time=+1d"))
        assertEquals("ignore", VScreenMute.parseMode("PLAY_AUDIO: ignore"))
        assertEquals("default", VScreenMute.parseMode("No operations."))
        assertNull(VScreenMute.parseMode("Error: bad op"))
    }
}
