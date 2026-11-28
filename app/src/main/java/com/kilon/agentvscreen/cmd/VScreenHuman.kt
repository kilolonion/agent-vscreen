package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.trPair
import android.graphics.Rect
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 1.3.0-beta19: human pacing (rainbow). With `act --human` or `note human on` for the app, every action is done the way
 * a person's hand does it, not at machine speed and precision:
 *  - a short pause before it (log-normal around 0.4 s, now and then a longer hesitation of 1–2 s);
 *  - a tap lands a little off the middle (a few pixels, always inside the element it names) and is held 55–140 ms;
 *  - presses, swipes, scrolls and drags vary in length and duration (±15–20 %), and their ends drift a little;
 *  - typing goes in pieces of 1–3 characters with 70–260 ms between them (a password box in one piece).
 * Waiting for the screen stays the same: conditions are facts, not timing. Pure (a [Random] in), unit-tested.
 */
internal class Human(private val rnd: Random = Random()) {
    /** The pause before an action. */
    fun pauseMs(): Long {
        val base = exp(ln(420.0) + 0.45 * rnd.nextGaussian())
        val hesitate = if (rnd.nextDouble() < 0.08) 800.0 + rnd.nextInt(1400) else 0.0
        return (base + hesitate).toLong().coerceIn(150L, 2_600L)
    }

    /** A point near ([x], [y]): inside [box] when the element is known, else within ±10 px; always on the screen. */
    fun near(x: Int, y: Int, box: Rect?, w: Int, h: Int): Pair<Int, Int> {
        val sigma = box?.let { min(8.0, min(it.width(), it.height()) / 6.0) }?.coerceAtLeast(1.0) ?: 4.0
        fun off() = (rnd.nextGaussian() * sigma).coerceIn(-2.5 * sigma, 2.5 * sigma).roundToInt()
        var nx = x + off(); var ny = y + off()
        if (box != null && box.width() > 4 && box.height() > 4) { nx = nx.coerceIn(box.left + 2, box.right - 2); ny = ny.coerceIn(box.top + 2, box.bottom - 2) }
        else { nx = nx.coerceIn(x - 10, x + 10); ny = ny.coerceIn(y - 10, y + 10) }
        return nx.coerceIn(0, w - 1) to ny.coerceIn(0, h - 1)
    }

    /** How long a tap's finger stays down. */
    fun holdMs(): Int = 55 + rnd.nextInt(86)

    /** [v] varied by up to ±[share]. */
    fun vary(v: Long, share: Double = 0.2): Long = (v * (1.0 + (rnd.nextDouble() * 2 - 1) * share)).roundToInt().toLong().coerceAtLeast(1L)

    /** A gesture's end, drifted by up to [px] pixels each way, on the screen. */
    fun drift(p: Pair<Int, Int>, px: Int, w: Int, h: Int): Pair<Int, Int> =
        (p.first + rnd.nextInt(2 * px + 1) - px).coerceIn(1, w - 2) to (p.second + rnd.nextInt(2 * px + 1) - px).coerceIn(1, h - 2)

    /** Typing in pieces of 1–3 characters (never splitting a surrogate pair). */
    fun pieces(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            var n = 1 + rnd.nextInt(3)
            var j = i
            while (j < text.length && n > 0) { j += if (Character.isHighSurrogate(text[j]) && j + 1 < text.length) 2 else 1; n-- }
            out += text.substring(i, j); i = j
        }
        return out
    }

    /** The gap between two typed pieces. */
    fun keyGapMs(): Long = 70L + rnd.nextInt(191)

    companion object {
        /** In words, for a step's fact: how far the tap was from the named point and how long the pause was. */
        fun said(pauseMs: Long, from: Pair<Int, Int>? = null, to: Pair<Int, Int>? = null): String {
            val off = if (from != null && to != null) abs(from.first - to.first) + abs(from.second - to.second) else 0
            return trPair("（仿人：先停了 ${pauseMs}ms" + (if (off > 0) "，偏了 ${off}px" else "") + "）",
                " (human pacing: paused ${pauseMs}ms" + (if (off > 0) ", ${off}px off" else "") + ")")
        }
    }
}
