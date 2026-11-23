package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.0: places only the optional detector found are pictures, not text: NO_TEXT and the page text diff ignore them. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DetectorNoTextTest {
    @Before fun setUp() { PageBook.resetForTest() }
    @After fun tearDown() { PageBook.resetForTest() }

    private fun text(n: Int, t: String, x: Int, y: Int) = El(n, t, Rect(x - 40, y - 20, x + 40, y + 20), fromA11y = true, clickable = true)
    private fun icon(n: Int, x: Int, y: Int) = El(n, "", Rect(x - 30, y - 30, x + 30, y + 30), false, clickable = true,
        kind = ElKind.NO_TEXT, hint = "icon", detected = true)

    @Test fun aCanvasWithOnlyDetectedIconsStillHasNoText() {
        val canvas = listOf(icon(1, 200, 800), icon(2, 600, 1200), icon(3, 900, 300))
        assertEquals(0, Loud.textElements(canvas).size)
        assertTrue(Loud.NO_TEXT in loudReasons(LoudInput(elementCount = Loud.textElements(canvas).size)))
        // An app's own wordless node is not a detector place: it still counts as an element.
        val own = El(1, "", Rect(0, 0, 80, 80), true, clickable = true, kind = ElKind.NO_TEXT, hint = "ImageButton")
        assertEquals(1, Loud.textElements(listOf(own)).size)
    }

    @Test fun movingIconBoxesAreNotATextDifference() {
        val sid = "s"; val act = "game/.Main"
        val hud = listOf(text(1, "得分 0", 200, 100), text(2, "暂停", 900, 100))
        PageBook.match(sid, act, hud + icon(3, 300, 900), turn = 1)
        assertFalse(PageBook.hasTextDiff(sid, act, hud + icon(3, 640, 1500) + icon(4, 100, 1800)))
        assertTrue(Loud.NO_TEXT in loudReasons(LoudInput(lastExpectOnlyVisual = true,
            hasPageTextDiff = PageBook.hasTextDiff(sid, act, hud + icon(3, 640, 1500)))))
        // Real words still count.
        assertTrue(PageBook.hasTextDiff(sid, act, listOf(text(1, "得分 10", 200, 100), text(2, "暂停", 900, 100))))
    }
}
