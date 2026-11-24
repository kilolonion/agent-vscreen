package com.kilon.agentvscreen.ui

import kotlin.math.min

/**
 * 1.4.2-beta6: the sizes the floating window snaps to, as pure numbers (tested in both orientations of the phone and
 * of the background screen). [a] is the background screen's aspect (width / height); [sw] × [sh] the phone screen.
 */
internal object WindowSizes {
    /** 2.0.0-beta8 (G.1): unified overlay placement area in full-display coordinates (cutout & system bars excluded). */
    data class Placeable(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(1)
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    /** Compute [Placeable] from full display bounds minus display cutout and system bar insets. Pure. */
    fun computePlaceable(
        fullW: Int,
        fullH: Int,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
        minTop: Int = 0,
        minBottom: Int = 0,
    ): Placeable {
        val w = fullW.coerceAtLeast(1)
        val h = fullH.coerceAtLeast(1)
        val l = insetLeft.coerceIn(0, w / 3)
        val r = (w - insetRight.coerceIn(0, w / 3)).coerceAtLeast(l + 1)
        val t = maxOf(insetTop, minTop).coerceIn(0, h / 3)
        val b = (h - maxOf(insetBottom, minBottom).coerceIn(0, h / 3)).coerceAtLeast(t + 1)
        return Placeable(l, t, r, b)
    }

    /** The finger is in the top right corner zone ([zone] px square): let go there and the window becomes the small one. */
    fun inMiniCorner(x: Float, y: Float, sw: Float, zone: Float): Boolean = x >= sw - zone && y <= zone

    /** 2.0.0-beta8 (G.1/G.2): corner zone relative to the current orientation's [Placeable] area. */
    fun inMiniCorner(x: Float, y: Float, area: Placeable, zone: Float): Boolean {
        val effectiveZone = min(zone, area.height * 0.25f)
        return x >= area.right - effectiveZone && y <= area.top + effectiveZone
    }

    /** The small window parked in the corner: about a quarter of the short side (portrait), never taller than 40 %. */
    fun miniWidth(a: Float, sw: Float, sh: Float, minW: Float): Float {
        val short = min(sw, sh)
        val w = short * (if (a < 1f) 0.26f else 0.46f)
        // The height cap wins over the smallest width (an upright app on a turned phone is short of height).
        val cap = sh * 0.4f * a
        return maxOf(w.coerceAtMost(cap), minOf(minW, cap))
    }

    /** The large window a tap on the small one opens, in the middle: most of the width, at most 70 % of the height. */
    fun bigWidth(a: Float, sw: Float, sh: Float): Float = min(sw * (if (a > 1f) 0.94f else 0.8f), sh * 0.7f * a)

    /** Pulling the bottom bar by [dy] px moves the window's bottom edge by that much (the aspect is kept). */
    fun resized(w0: Float, a: Float, dy: Float): Float = (w0 / a + dy) * a
}
