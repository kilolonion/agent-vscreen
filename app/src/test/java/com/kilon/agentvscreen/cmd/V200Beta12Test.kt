package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.core.Change
import com.kilon.agentvscreen.core.VScreenDisplayService
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenPaddleOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.0.0-beta12: the beta11 real-phone report, fixed at its causes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V200Beta12Test {
    private fun el(text: String, l: Int, t: Int, r: Int, b: Int) = El(0, text, Rect(l, t, r, b), true)
    private fun item(text: String, l: Int, t: Int, r: Int, b: Int) = VScreenOcr.Item(text, Rect(l, t, r, b), emptyList())

    @Test fun backIsSentTheWayTheSystemSendsIt() {
        val f = VScreenDisplayService.keyFlags(KeyEvent.KEYCODE_BACK)
        assertTrue(f and KeyEvent.FLAG_FROM_SYSTEM != 0)
        assertTrue(f and KeyEvent.FLAG_VIRTUAL_HARD_KEY != 0)
        assertTrue(VScreenDisplayService.keyFlags(KeyEvent.KEYCODE_ESCAPE) != 0)
        // Typing keys stay plain keyboard keys.
        assertEquals(0, VScreenDisplayService.keyFlags(KeyEvent.KEYCODE_ENTER))
        assertEquals(0, VScreenDisplayService.keyFlags(KeyEvent.KEYCODE_DEL))
    }

    @Test fun aReadingInProgressIsWaitedForAsLongAsReadingsTakeHere() {
        // Nothing known yet: the old 3 s.
        assertEquals(VScreenPaddleOcr.SLOT_WAIT_MS, VScreenOcr.shareWaitMs(0L, 0L))
        // A hot phone reading in ~7 s, one running for 2 s: wait for the rest (and half again), not 3 s.
        assertEquals(8_500L, VScreenOcr.shareWaitMs(7_000L, 2_000L))
        // Never past the reading's own deadline.
        assertTrue(VScreenOcr.shareWaitMs(14_000L, 1_000L) <= VScreenPaddleOcr.TIMEOUT_MS - 1_000L)
    }

    @Test fun heatPauseIsSaidAsHeatNotAsBrokenOcr() {
        I18n.with("en") {
            val line = VScreenOcr.downLine(VScreenOcr.THERMAL_LIMIT)!!
            assertTrue(line, line.contains("hot") && line.contains("Not a fault"))
            assertFalse(line.contains("unavailable"))
        }
    }

    @Test fun treeWordsTheScreenCoversDoNotCount() {
        val pat = TextPat("排行榜")
        val box = Rect(40, 520, 260, 610)
        // The page on top shows other words in that place: the node is under it.
        assertFalse(pictureAgrees(box, pat, listOf(item("动画", 60, 540, 240, 590))))
        // The picture shows the words (or a misread of them): agrees.
        assertTrue(pictureAgrees(box, pat, listOf(item("排行榜", 60, 540, 240, 590))))
        assertTrue(pictureAgrees(box, pat, listOf(item("排行傍", 60, 540, 240, 590))))
        // Nothing read there (an icon, a picture): no evidence against the tree.
        assertTrue(pictureAgrees(box, pat, listOf(item("动画", 600, 540, 800, 590))))
        assertTrue(pictureAgrees(box, pat, emptyList()))
    }

    private fun canvas(w: Int, h: Int, fill: Int = Color.WHITE): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(fill) }

    private fun paint(b: Bitmap, r: Rect, c: Int) { for (y in r.top until r.bottom) for (x in r.left until r.right) b.setPixel(x, y, c) }

    @Test fun aSmallClearChangeCountsACaretDoesNot() {
        val a = canvas(162, 360); val b = canvas(162, 360)
        // A label changed: a block of a cell.
        paint(b, Rect(60, 300, 80, 312), Color.BLACK)
        assertNotNull(Change.localChange(a, b))
        // Far under the whole-screen 2%.
        assertTrue(Change.ratio(a, b) < Change.CHANGED)
        // A caret (1 px wide line) is not a change.
        val c = canvas(162, 360); paint(c, Rect(70, 100, 71, 112), Color.BLACK)
        assertNull(Change.localChange(a, c))
        // Inside an area already moving: left out.
        assertNull(Change.localChange(a, b, listOf(Rect(50, 290, 90, 320))))
    }

    @Test fun dragOutcomeFromTheElements() {
        val tabs = listOf(el("新建标签页", 40, 300, 600, 900), el("新建标签页", 660, 300, 1220, 900), el("新建标签页", 40, 960, 600, 1560))
        // Swiped one of three identical tabs off: one fewer, whatever the picture says.
        assertEquals(Gesture.AFTER_DISAPPEARED, Gesture.elementAfter(tabs, tabs.drop(1), 300 to 600, 1250 to 600))
        // A row dragged down the list: its words are at the drop point and not where it was.
        val rows = listOf(el("Alpha", 0, 100, 1080, 200), el("Beta", 0, 200, 1080, 300), el("Gamma", 0, 300, 1080, 400))
        val reordered = listOf(el("Beta", 0, 100, 1080, 200), el("Gamma", 0, 200, 1080, 300), el("Alpha", 0, 300, 1080, 400))
        assertEquals(Gesture.AFTER_STAYED, Gesture.elementAfter(rows, reordered, 500 to 150, 500 to 350))
        // Went back: still where it was.
        assertEquals(Gesture.AFTER_SNAPPED_BACK, Gesture.elementAfter(rows, rows, 500 to 150, 500 to 350))
        // No words under the start: the picture decides.
        assertNull(Gesture.elementAfter(rows, reordered, 500 to 700, 500 to 350))
    }

    @Test fun aPlayingAppIsKnownBeforeBlamingTheFreeze() {
        val dump = """
            Sessions Stack - have 1 sessions:
              tv.example.video/VideoSession (userId=0)
                ownerPid=31303, ownerUid=10261, userId=0
                package=tv.example.video
                active=true
                state=PlaybackState {state=3, position=1000}
        """.trimIndent()
        assertTrue(ActHold.isPlayingMedia(dump, "tv.example.video"))
        // An app's own session is not a system session: not refused up front.
        assertFalse(ActHold.hasActiveSystemMediaSession(dump, "tv.example.video"))
        assertFalse(ActHold.isPlayingMedia(dump.replace("state=3", "state=2"), "tv.example.video"))
    }

    @Test fun rowsThatBarelyMovedAreNotBars() {
        // A swipe that collapsed a big title: rows moved a few pixels, nothing scrolled.
        val before = listOf(el("WLAN", 0, 500, 1080, 600), el("蓝牙", 0, 600, 1080, 700), el("移动网络", 0, 700, 1080, 800))
        val after = before.map { it.copy(box = Rect(it.box).apply { offset(0, -6) }) }
        assertTrue(fixedBoxes(before, after).isEmpty())
        // A real scroll: the bar that stayed is a bar.
        val b2 = listOf(el("搜索", 0, 0, 1080, 120), el("WLAN", 0, 900, 1080, 1000))
        val a2 = listOf(el("搜索", 0, 0, 1080, 120), el("WLAN", 0, 300, 1080, 400))
        assertEquals(listOf(Rect(0, 0, 1080, 120)), fixedBoxes(b2, a2))
    }

    @Test fun documentedFormsAreAccepted() {
        val r = Steps.parse("repeat 3: tap 热门")
        assertEquals(StepKind.REPEAT, r.kind)
        assertEquals(3, r.times)
        assertEquals(StepTarget.Words("热门"), r.body!!.single().target)
        assertEquals(StepTarget.Words("热门", 0, 186, 0), Steps.parse("tap 热门 +186,0").target)
        assertEquals(StepTarget.Words("热门", 0, 186, -4), Steps.parse("tap 热门 +186,-4").target)
        // Two plain numbers are still a point, not an offset.
        assertEquals(StepTarget.At(186, 40), Steps.parse("tap 186 40").target)
    }
}
