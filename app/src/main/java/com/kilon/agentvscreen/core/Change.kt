package com.kilon.agentvscreen.core

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * 1.4.0-beta2: the one definition of "the picture changed". Before, six places compared frames with their own pixel
 * test and their own cut-off (0.0005, 0.002, 0.003, 0.004, 0.02…), so "nothing moved" meant something different in
 * the wait engine, the scroll check, the re-read check and the OCR reuse. Now: one pixel test ([differs]), one
 * measure ([ratio]: the share of sampled pixels that differ), and every cut-off is a named [Level] here.
 * ScreenSig (what KIND of change: none, local, page) and VScreenActivity (WHERE: which cells) answer other questions
 * and use [differs] for their pixel test.
 */
object Change {
    /** "Unchanged" means at most this share of sampled pixels differ. */
    enum class Level(val max: Double) {
        /** The same page and the same words (a caret, a tiny spinner); also "the action changed nothing". */
        TINY(0.0005),
        /** The page stopped changing: no need to read it again. */
        SETTLED(0.002),
        /** A swipe did not move the list. */
        SCROLL(0.003),
        /** Still between two looks (`stable`, `still`, a change calming down). */
        STILL(0.004),
    }

    /** At least this share changed: "the screen / region changed" for a `change` condition. */
    const val CHANGED = 0.02
    /** 1.4.0-beta5: on a picture that moves all over by itself, a change counts only from this share (a new page). */
    const val PAGE_OVER_MOTION = 0.4
    /** A region expectation: at least this share is "changed", below [REGION_SAME] is "the same". */
    const val REGION_CHANGED = 0.08
    const val REGION_SAME = 0.03

    /** Colour distance (RGB) that counts as a different pixel: catches a hue change at equal brightness too. */
    private const val STEP = 40

    /** The pixel test, shared by every comparison (plain bit arithmetic: usable on raw pixel arrays). */
    fun differs(a: Int, b: Int): Boolean {
        val dr = ((a shr 16) and 0xff) - ((b shr 16) and 0xff); val dg = ((a shr 8) and 0xff) - ((b shr 8) and 0xff); val db = (a and 0xff) - (b and 0xff)
        return dr * dr + dg * dg + db * db > STEP * STEP
    }

    /** Share of sampled pixels in [region] (whole frame when null) that differ, leaving out [skip]; -1 = cannot compare. */
    fun ratio(a: Bitmap?, b: Bitmap?, region: Rect? = null, skip: List<Rect> = emptyList()): Double {
        if (a == null || b == null || a.isRecycled || b.isRecycled || a.width != b.width || a.height != b.height) return -1.0
        val r = VPixels.clip(region ?: Rect(0, 0, a.width, a.height), a.width, a.height)
        if (r.width() <= 0 || r.height() <= 0) return -1.0
        var n = 0; var diff = 0
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                if (skip.isEmpty() || skip.none { it.contains(x, y) }) { n++; if (differs(a.getPixel(x, y), b.getPixel(x, y))) diff++ }
                x += 2
            }
            y += 2
        }
        return if (n == 0) -1.0 else diff.toDouble() / n
    }

    /**
     * 2.0.0-beta12 (test: a long-press menu and a stopwatch's 开始 → 暂停 never counted as `until change`): a reaction
     * can be small on a big screen (a label, a toggle, a little menu), far under [CHANGED] of the whole picture. The
     * cell of [cell]×[cell] pixels (outside [skip]) that changed most, when at least [LOCAL_CHANGED] of it differs; a
     * caret or a moving dot stays under that.
     */
    fun localChange(a: Bitmap?, b: Bitmap?, skip: List<Rect> = emptyList(), cell: Int = 18): Rect? {
        if (a == null || b == null || a.isRecycled || b.isRecycled || a.width != b.width || a.height != b.height) return null
        var best: Rect? = null; var bestR = LOCAL_CHANGED
        var y = 0
        while (y + cell / 2 < a.height) {
            var x = 0
            while (x + cell / 2 < a.width) {
                val r = Rect(x, y, minOf(x + cell, a.width), minOf(y + cell, a.height))
                if (skip.none { Rect.intersects(it, r) }) {
                    val v = ratio(a, b, r)
                    if (v >= bestR) { bestR = v; best = r }
                }
                x += cell / 2
            }
            y += cell / 2
        }
        return best
    }
    const val LOCAL_CHANGED = 0.2

    /** [a] and [b] are the same at [level] (false when they cannot be compared). */
    fun same(a: Bitmap?, b: Bitmap?, level: Level, region: Rect? = null, skip: List<Rect> = emptyList()): Boolean =
        ratio(a, b, region, skip).let { it >= 0.0 && it <= level.max }
}
