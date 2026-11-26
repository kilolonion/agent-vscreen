package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.Change
import com.kilon.agentvscreen.core.Mask
import com.kilon.agentvscreen.core.ScreenSig
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenGame
import com.kilon.agentvscreen.core.VScreenPerception
import com.kilon.agentvscreen.core.VScreenRecorder
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.tr
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 2.0.0-beta4 (Stage 4: Closed-loop gestures & drag tracking, spec 4.3–4.8).
 *
 * All threshold constants live here with exact spec names so tuning or auditing has a single source.
 */
internal object Gesture {
    // 4.3 closed-loop execution constants
    const val HOLD_POLL_MS = 50L
    const val HOLD_KEEPALIVE_MS = 500L
    const val SEEK_STEP_PX = 12
    const val SEEK_MAX_STEPS = 400
    const val SEEK_FRAME_DELAY_MS = 32L
    const val SEEK_FRAME_WAIT_MS = 260L
    const val SEEK_READ_TIMEOUT_MS = 1_500L
    const val SEEK_MAX_CONSEC_TIMEOUTS = 3
    const val SEEK_END_HOLD_MS = 120L
    const val DRAG_DOWN_HOLD_MS = 150L
    const val DRAG_MOVE_TICK_MS = 16L
    const val DEFAULT_DRAG_MS = 600L
    const val BOOST_EXTRA_MS = 3_000L
    const val HUMAN_START_DRIFT_PX = 3
    private const val INTERRUPT_SLICE_MS = 50L
    private const val MAX_PICKUP_FRAMES = 8
    private const val SETTLE_FRAME_WAIT_MS = 250L
    private const val SETTLE_FRAME_SLACK_MS = 30L
    const val HOLD_ARRIVE_FRAME_WAIT_MS = 100L
    const val SEEK_FRAME_POLL_SLICE_MS = 20L
    private const val DRAG_MARK_MARGIN_MS = 500L
    const val OCR_IDLE_WAIT_MS = 5_000L

    // 4.4 & 4.8 drag GestureRecord constants (recording-frame coordinates, long side 640)
    const val PATCH = 64
    const val TEXTURE_MIN = 12.0
    const val TRACK_RADIUS = 160
    const val TRACK_SCORE_MIN = 0.8
    const val MATCH_DIST_PX = 60.0
    const val PICKUP_FROM_MS = 150L
    const val SETTLE_MS = 350L
    private const val PATCH_TRACK_SCALE = 0.5f
    private const val REFINE_RADIUS = 4

    const val AFTER_STAYED = "stayed"
    const val AFTER_SNAPPED_BACK = "snapped_back"
    const val AFTER_ELSEWHERE = "elsewhere"
    const val AFTER_DISAPPEARED = "disappeared"
    const val AFTER_UNKNOWN = "unknown"

    /** R(p): [size]x[size] square centered on ([cx], [cy]), clamped to [frameW]x[frameH]. */
    fun patchRect(cx: Int, cy: Int, frameW: Int, frameH: Int, size: Int = PATCH): Rect {
        val bw = minOf(size, frameW).coerceAtLeast(1)
        val bh = minOf(size, frameH).coerceAtLeast(1)
        val left = (cx - bw / 2).coerceIn(0, maxOf(0, frameW - bw))
        val top = (cy - bh / 2).coerceIn(0, maxOf(0, frameH - bh))
        return Rect(left, top, left + bw, top + bh)
    }

    /** Luminance standard deviation inside [rect] of [frame] (0 = flat solid color). */
    fun regionLumStdDev(frame: Bitmap, rect: Rect): Double {
        if (frame.isRecycled) return 0.0
        val r = Rect(rect)
        if (!r.intersect(0, 0, frame.width, frame.height) || r.width() <= 0 || r.height() <= 0) return 0.0
        val w = r.width()
        val h = r.height()
        val n = w * h
        val px = IntArray(n)
        frame.getPixels(px, 0, w, r.left, r.top, w, h)
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until n) {
            val p = px[i]
            val lum = (((p shr 16) and 0xff) * 3 + ((p shr 8) and 0xff) * 6 + (p and 0xff)) / 10.0
            sum += lum
            sumSq += lum * lum
        }
        val mean = sum / n
        return sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
    }

    /**
     * 4.8: Paint motion-mask rectangles to a flat color on both frames, then compare via [ScreenSig.compare]
     * together with the pixel-level `fine` measure ([Change.ratio] vs [ScreenSig.FINE_MIN]).
     * Pixels inside [excludeRects] (the dragged object's start/end regions) are never erased by the motion mask.
     */
    fun wholeScreenUnchanged(
        pre: Bitmap,
        after: Bitmap,
        mask: Mask? = null,
        excludeRects: List<Rect> = emptyList(),
    ): Boolean {
        if (pre.isRecycled || after.isRecycled || pre.width != after.width || pre.height != after.height) return false
        val rects = if (mask == null || mask.mostly) emptyList() else mask.rectsIn(pre.width, pre.height)
        var preWork = pre
        var afterWork = after
        var copied = false
        try {
            if (rects.isNotEmpty()) {
                val pCopy = pre.copy(Bitmap.Config.ARGB_8888, true)
                val aCopy = after.copy(Bitmap.Config.ARGB_8888, true)
                val paint = Paint().apply { color = Color.GRAY; style = Paint.Style.FILL }
                val cPre = Canvas(pCopy)
                val cAfter = Canvas(aCopy)
                for (r in rects) {
                    cPre.drawRect(r, paint)
                    cAfter.drawRect(r, paint)
                }
                for (ex in excludeRects) {
                    val clipped = Rect(ex)
                    if (clipped.intersect(0, 0, pre.width, pre.height) && clipped.width() > 0 && clipped.height() > 0) {
                        cPre.drawBitmap(pre, clipped, clipped, null)
                        cAfter.drawBitmap(after, clipped, clipped, null)
                    }
                }
                preWork = pCopy
                afterWork = aCopy
                copied = true
            }
            val sigPre = ScreenSig.of(preWork) ?: return false
            val sigAfter = ScreenSig.of(afterWork) ?: return false
            val fine = Change.ratio(preWork, afterWork).coerceAtLeast(0.0)
            return sigPre.compare(sigAfter).copy(fine = fine).kind == ScreenSig.Kind.NONE
        } finally {
            if (copied) {
                preWork.recycle()
                afterWork.recycle()
            }
        }
    }

    /** Build a grayscale [VScreenGame.Patch] of [size]x[size] centered on ([cx], [cy]) in [frame] at [scale]. */
    fun makePatch(frame: Bitmap, cx: Int, cy: Int, size: Int = PATCH, scale: Float = 1f): VScreenGame.Patch? {
        if (frame.isRecycled || frame.width < 4 || frame.height < 4) return null
        val box = patchRect(cx, cy, frame.width, frame.height, size)
        val bw = box.width()
        val bh = box.height()
        val w = (bw * scale).roundToInt().coerceAtLeast(2)
        val h = (bh * scale).roundToInt().coerceAtLeast(2)
        val px = IntArray(w * h)
        if (w == bw && h == bh) {
            frame.getPixels(px, 0, w, box.left, box.top, w, h)
        } else {
            val sub = Bitmap.createBitmap(frame, box.left, box.top, bw, bh)
            val sc = Bitmap.createScaledBitmap(sub, w, h, true)
            if (sub !== frame && sub !== sc) sub.recycle()
            sc.getPixels(px, 0, w, 0, 0, w, h)
            if (sc !== frame) sc.recycle()
        }
        val gray = IntArray(w * h) { i ->
            val p = px[i]
            (((p shr 16) and 0xff) * 3 + ((p shr 8) and 0xff) * 6 + (p and 0xff)) / 10
        }
        return VScreenGame.Patch(box, gray, w, h, scale)
    }

    /** Re-anchor [patch]'s search box around ([cx], [cy]) without altering [VScreenGame.track]. */
    fun shiftPatch(patch: VScreenGame.Patch, cx: Int, cy: Int, frameW: Int, frameH: Int): VScreenGame.Patch {
        val bw = patch.box.width()
        val bh = patch.box.height()
        val left = (cx - bw / 2).coerceIn(0, maxOf(0, frameW - bw))
        val top = (cy - bh / 2).coerceIn(0, maxOf(0, frameH - bh))
        return VScreenGame.Patch(Rect(left, top, left + bw, top + bh), patch.px, patch.w, patch.h, patch.scale)
    }

    /** Refine a coarse [VScreenGame.track] hit at full 1x resolution within [REFINE_RADIUS] px (<1ms). */
    private fun refineHit(patch1x: VScreenGame.Patch, frame: Bitmap, coarse: Pair<Rect, Double>): Pair<Rect, Double> {
        val fine = VScreenGame.track(
            shiftPatch(patch1x, coarse.first.centerX(), coarse.first.centerY(), frame.width, frame.height),
            frame,
            radius = REFINE_RADIUS,
        )
        return if (fine != null && fine.second >= coarse.second) fine else coarse
    }

    /** Search around ([cx], [cy]) within [radius] using 0.5x patch (~25ms) and 1x local refinement (<1ms). */
    private fun trackNear(
        patchHalf: VScreenGame.Patch,
        patch1x: VScreenGame.Patch,
        frame: Bitmap,
        cx: Int,
        cy: Int,
        radius: Int,
    ): Pair<Rect, Double>? {
        val shifted = shiftPatch(patchHalf, cx, cy, frame.width, frame.height)
        val hit = VScreenGame.track(shifted, frame, radius = radius) ?: return null
        return refineHit(patch1x, frame, hit)
    }

    /**
     * 2.0.0-beta8 (C6): search the entire [frame] for [patchHalf] in overlapping tiles sized so
     * [VScreenGame.track] always uses exact step=1 at 0.5x scale (never skipping odd coordinates).
     */
    /**
     * 2.0.0-beta11 (G5): how many places on [frame] look like the dragged item. Rows of tabs/cards look alike,
     * so "found somewhere" is no evidence the item moved; one copy fewer than before is evidence it went away.
     * Hits closer than [PATCH] px are one place.
     */
    internal fun countPatch(patchHalf: VScreenGame.Patch, frame: Bitmap): Int {
        val radius = 150
        val stride = 120 // each tile reports only its best match, so tiles are dense enough for one item per tile
        val xs = if (frame.width <= 360) listOf(frame.width / 2) else generateSequence(180) { it + stride }.takeWhile { it - 180 < frame.width }.toList()
        val ys = if (frame.height <= 360) listOf(frame.height / 2) else generateSequence(180) { it + stride }.takeWhile { it - 180 < frame.height }.toList()
        val centers = ArrayList<Pair<Int, Int>>()
        for (cy in ys) for (cx in xs) {
            val shifted = shiftPatch(patchHalf, cx.coerceIn(0, frame.width - 1), cy.coerceIn(0, frame.height - 1), frame.width, frame.height)
            val hit = VScreenGame.track(shifted, frame, radius = radius) ?: continue
            val c = hit.first.centerX() to hit.first.centerY()
            if (centers.none { hypot((it.first - c.first).toDouble(), (it.second - c.second).toDouble()) < PATCH }) centers += c
        }
        return centers.size
    }

    private fun findPatchAnywhere(patchHalf: VScreenGame.Patch, frame: Bitmap): Pair<Rect, Double>? {
        val radius = 150 // 360x360 tile -> 180x180 = 32,400 <= 40,000 at 0.5x -> guaranteed step=1
        val stride = 240 // >= 60px overlap between adjacent tiles
        val xs = if (frame.width <= 360) listOf(frame.width / 2) else generateSequence(180) { it + stride }.takeWhile { it - 180 < frame.width }.toList()
        val ys = if (frame.height <= 360) listOf(frame.height / 2) else generateSequence(180) { it + stride }.takeWhile { it - 180 < frame.height }.toList()
        for (cy in ys) {
            for (cx in xs) {
                val shifted = shiftPatch(patchHalf, cx.coerceIn(0, frame.width - 1), cy.coerceIn(0, frame.height - 1), frame.width, frame.height)
                val hit = VScreenGame.track(shifted, frame, radius = radius)
                if (hit != null) return hit
            }
        }
        return null
    }

    /** Convert display pixel coordinate [pt] into recording frame coordinate space ([frameW]x[frameH]). */
    fun toFrameCoord(pt: Pair<Int, Int>, dispW: Int, dispH: Int, frameW: Int, frameH: Int): Pair<Int, Int> {
        val sx = frameW.toDouble() / maxOf(1, dispW)
        val sy = frameH.toDouble() / maxOf(1, dispH)
        return (pt.first * sx).roundToInt().coerceIn(0, maxOf(0, frameW - 1)) to
            (pt.second * sy).roundToInt().coerceIn(0, maxOf(0, frameH - 1))
    }

    /** Geometric midpoint along a polyline path [pts]. */
    fun pointAtHalfPath(pts: List<Pair<Int, Int>>): Pair<Int, Int> {
        if (pts.isEmpty()) return 0 to 0
        if (pts.size == 1) return pts[0]
        if (pts.size == 2) return ((pts[0].first + pts[1].first) / 2) to ((pts[0].second + pts[1].second) / 2)
        val lens = pts.zipWithNext { a, b -> hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) }
        var dist = lens.sum() / 2.0
        var i = 0
        while (i < lens.size - 1 && dist > lens[i]) {
            dist -= lens[i]
            i++
        }
        val a = pts[i]
        val b = pts[i + 1]
        val k = if (lens[i] <= 0.0) 0.5 else (dist / lens[i]).coerceIn(0.0, 1.0)
        return (a.first + (b.first - a.first) * k).roundToInt() to (a.second + (b.second - a.second) * k).roundToInt()
    }

    /** Compute `(p50, max)` of poll duration samples (ms); null when empty. */
    fun pollStats(samples: List<Long>): Pair<Long, Long>? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val p50 = sorted[sorted.size / 2]
        return p50 to sorted.last()
    }

    /**
     * Pure 4.4 & 4.8 analysis on recording-sized bitmaps (long side 640):
     * - Low-texture guard: luminance stddev of R([startF]) in [preFrame] < [TEXTURE_MIN] -> picked_up=null, unknown.
     * - Picked up: any frame in [pickupFrames] (150ms..release) has `Change.ratio(pre, f, R(start)) >= REGION_CHANGED`.
     * - After release (frame-diff, independent of template score):
     *   - `snapped_back`: `picked_up == true` and motion-masked `ScreenSig.compare` + `fine` == `Kind.NONE`.
     *   - `stayed`: both `R(start)` and `R(end)` changed (`>= REGION_CHANGED`).
     *   - `elsewhere`: `R(start)` changed, `R(end)` unchanged, and whole screen changed.
     *   - otherwise `unknown`.
     * - `followed` and `score` retain template tracking (informational only; never trigger loud/SNAP_BACK).
     */
    fun analyzeGestureFrames(
        preFrame: Bitmap?,
        pickupFrames: List<Bitmap>,
        midFrame: Bitmap?,
        settleFrame: Bitmap?,
        startF: Pair<Int, Int>,
        midF: Pair<Int, Int>,
        endF: Pair<Int, Int>,
        mask: Mask? = null,
        pollMsP50: Long? = null,
        pollMsMax: Long? = null,
        keyTimesMs: LongArray? = null,
    ): GestureRecord {
        if (preFrame == null || preFrame.isRecycled || preFrame.width < 4 || preFrame.height < 4) {
            return GestureRecord(null, null, AFTER_UNKNOWN, 0.0, pollMsP50, pollMsMax, keyTimesMs)
        }
        val startRect = patchRect(startF.first, startF.second, preFrame.width, preFrame.height, PATCH)
        // 4.8: Low-texture guard — blank/untextured start cannot be judged reliably.
        if (regionLumStdDev(preFrame, startRect) < TEXTURE_MIN) {
            return GestureRecord(null, null, AFTER_UNKNOWN, 0.0, pollMsP50, pollMsMax, keyTimesMs)
        }

        // 1. Picked up (150ms after down until release): Change.ratio on R(start) >= REGION_CHANGED
        val pickedUp: Boolean? = if (pickupFrames.isEmpty()) {
            null
        } else {
            pickupFrames.any { f ->
                !f.isRecycled && f.width == preFrame.width && f.height == preFrame.height &&
                    Change.ratio(preFrame, f, startRect) >= Change.REGION_CHANGED
            }
        }

        val patch1x = makePatch(preFrame, startF.first, startF.second, PATCH, 1f)
        val patchHalf = patch1x?.let { makePatch(preFrame, startF.first, startF.second, PATCH, PATCH_TRACK_SCALE) ?: it }
        val offX = (patch1x?.box?.centerX() ?: startRect.centerX()) - startF.first
        val offY = (patch1x?.box?.centerY() ?: startRect.centerY()) - startF.second
        val nearR = MATCH_DIST_PX.toInt()

        // 2. Followed (informational template tracking at drag midpoint)
        var midScore = 0.0
        val followed: Boolean? = if (midFrame == null || midFrame.isRecycled || patch1x == null || patchHalf == null) {
            null
        } else {
            val midCx = (midF.first + offX).coerceIn(0, maxOf(0, midFrame.width - 1))
            val midCy = (midF.second + offY).coerceIn(0, maxOf(0, midFrame.height - 1))
            val hit = trackNear(patchHalf, patch1x, midFrame, midCx, midCy, nearR)
            if (hit != null && hit.second >= TRACK_SCORE_MIN &&
                hypot(
                    (hit.first.centerX() - midCx).toDouble(),
                    (hit.first.centerY() - midCy).toDouble(),
                ) <= MATCH_DIST_PX
            ) {
                midScore = hit.second
                true
            } else {
                false
            }
        }

        // 3. After release (4.8 frame-diff classification)
        val afterRelease: String = if (settleFrame == null || settleFrame.isRecycled ||
            settleFrame.width != preFrame.width || settleFrame.height != preFrame.height
        ) {
            AFTER_UNKNOWN
        } else {
            val endRect = patchRect(endF.first, endF.second, settleFrame.width, settleFrame.height, PATCH)
            val wholeUnchanged = wholeScreenUnchanged(preFrame, settleFrame, mask, excludeRects = listOf(startRect, endRect))
            val startChanged = Change.ratio(preFrame, settleFrame, startRect) >= Change.REGION_CHANGED
            val endChanged = Change.ratio(preFrame, settleFrame, endRect) >= Change.REGION_CHANGED
            // 2.0.0-beta11 (G5, Edge tab closed reported as "stayed"/"elsewhere"): when the item has look-alikes (a row
            // of tabs or cards), a change at the drop point (the row closing up) and a match somewhere (a neighbour)
            // are no evidence it is still there; one copy fewer than before is evidence it went away. A unique item
            // keeps the plain frame-difference rule (it may be restyled where it lands).
            val preCopies = patchHalf?.let { countPatch(it, preFrame) } ?: 0
            val fewerCopies = preCopies >= 2 && countPatch(patchHalf!!, settleFrame) < preCopies
            when {
                pickedUp == true && !startChanged && wholeUnchanged -> AFTER_SNAPPED_BACK
                startChanged && endChanged && !fewerCopies -> AFTER_STAYED
                startChanged && !endChanged && wholeUnchanged -> AFTER_DISAPPEARED
                startChanged -> {
                    // 2.0.0-beta8 (C6): a wide tab/card extends past startRect so wholeUnchanged can be false
                    // even when the item was swiped away.
                    if (patchHalf == null) AFTER_ELSEWHERE
                    else if (fewerCopies) AFTER_DISAPPEARED
                    else if (findPatchAnywhere(patchHalf, settleFrame) != null) AFTER_ELSEWHERE
                    else AFTER_DISAPPEARED
                }
                else -> AFTER_UNKNOWN
            }
        }

        // Informational template score (does not affect afterRelease or SNAP_BACK)
        val score: Double = if (settleFrame == null || settleFrame.isRecycled || patch1x == null || patchHalf == null) {
            midScore
        } else {
            val probePt = when (afterRelease) {
                AFTER_STAYED -> endF
                AFTER_SNAPPED_BACK -> startF
                else -> null
            }
            val settleHit = probePt?.let { pt ->
                val cx = (pt.first + offX).coerceIn(0, maxOf(0, settleFrame.width - 1))
                val cy = (pt.second + offY).coerceIn(0, maxOf(0, settleFrame.height - 1))
                trackNear(patchHalf, patch1x, settleFrame, cx, cy, nearR)?.takeIf { (box, sc) ->
                    sc >= TRACK_SCORE_MIN && hypot(
                        (box.centerX() - cx).toDouble(),
                        (box.centerY() - cy).toDouble(),
                    ) <= MATCH_DIST_PX
                }
            }
            settleHit?.second ?: midScore
        }

        return GestureRecord(
            pickedUp = pickedUp,
            followed = followed,
            afterRelease = afterRelease,
            score = score,
            pollMsP50 = pollMsP50,
            pollMsMax = pollMsMax,
            keyTimesMs = keyTimesMs,
        )
    }

    /**
     * Wait [SETTLE_MS] after release, record the settled frame, decode recording frames, and compute [GestureRecord].
     * Used by both normal `drag` (4.4, 4.7, 4.8) and closed-loop `drag` (`hold` / `seek`).
     */
    fun evaluateDragRecord(
        d: VScreenDisplay,
        startPt: Pair<Int, Int>,
        midPt: Pair<Int, Int>,
        endPt: Pair<Int, Int>,
        preDownAt: Long,
        downAt: Long,
        midAt: Long,
        preUpAt: Long,
        upAt: Long,
        upElapsed: Long,
        pkg: String?,
        pollMsP50: Long? = null,
        pollMsMax: Long? = null,
        /** 2.0.0-beta12: the screen's elements (accessibility, frame pixels) read before the finger went down. */
        before: List<El>? = null,
    ): GestureRecord {
        val pixels = evaluateDragFrames(d, startPt, midPt, endPt, preDownAt, downAt, midAt, preUpAt, upAt, upElapsed, pkg, pollMsP50, pollMsMax)
        if (before.isNullOrEmpty()) return pixels
        val after = runCatching { a11yRaw(d).filter { !it.placeholder } }.getOrNull() ?: return pixels
        val byElements = elementAfter(before, after, startPt, endPt) ?: return pixels
        return pixels.copy(afterRelease = byElements)
    }

    /**
     * 2.0.0-beta12 (test F7: closing Edge tabs by dragging gave "disappeared", "moved elsewhere" or nothing at all, for
     * the same action; three identical 新建标签页 tabs fooled the picture match): what the screen's elements say
     * happened to the dragged one. The element under the start point is named by its words; then
     *  - fewer elements with those words than before: it went away (closed, deleted, swiped off);
     *  - one with those words now at the drop point and none left where it was: it stayed where it was dropped;
     *  - still where it was, as many as before: it went back (or never moved).
     * Null when the start point has no element with words, or the elements do not settle it (the picture decides).
     */
    internal fun elementAfter(before: List<El>, after: List<El>, start: Pair<Int, Int>, end: Pair<Int, Int>): String? {
        fun key(e: El) = com.kilon.agentvscreen.core.VScreenOcr.norm(e.label)
        val worded = before.filter { key(it).isNotEmpty() }
        val item = worded.filter { it.box.contains(start.first, start.second) }.minByOrNull { it.box.width().toLong() * it.box.height() }
            ?: return null
        val k = key(item)
        val was = worded.count { key(it) == k }
        val now = after.filter { key(it) == k }
        if (now.size < was) return AFTER_DISAPPEARED
        fun near(r: Rect, x: Int, y: Int, pad: Int) = Rect(r).apply { inset(-pad, -pad) }.contains(x, y)
        val atStart = now.any { kotlin.math.abs(it.box.centerX() - item.box.centerX()) <= 24 && kotlin.math.abs(it.box.centerY() - item.box.centerY()) <= 24 }
        val atEnd = now.any { near(it.box, end.first, end.second, 48) }
        val moved = kotlin.math.hypot((end.first - start.first).toDouble(), (end.second - start.second).toDouble()) > minOf(item.box.width(), item.box.height()) / 2.0
        return when {
            !moved -> null
            atEnd && !atStart -> AFTER_STAYED
            atStart && now.size == was -> AFTER_SNAPPED_BACK
            else -> null
        }
    }

    private fun evaluateDragFrames(
        d: VScreenDisplay,
        startPt: Pair<Int, Int>,
        midPt: Pair<Int, Int>,
        endPt: Pair<Int, Int>,
        preDownAt: Long,
        downAt: Long,
        midAt: Long,
        preUpAt: Long,
        upAt: Long,
        upElapsed: Long,
        pkg: String?,
        pollMsP50: Long?,
        pollMsMax: Long?,
    ): GestureRecord {
        val targetElapsed = upElapsed + SETTLE_MS
        val call = Call.current()
        while (true) {
            val rem = targetElapsed - SystemClock.elapsedRealtime()
            if (rem <= 0L) break
            checkInterrupts(d, call)
            Thread.sleep(minOf(INTERRUPT_SLICE_MS, rem))
        }
        d.awaitFrameAfter(targetElapsed - SETTLE_FRAME_SLACK_MS, SETTLE_FRAME_WAIT_MS)
        d.latestWithVersion()?.let { (bmp, ver) -> VScreenRecorder.offerFresh(d, bmp, ver, pkg) }
        val settleAt = System.currentTimeMillis()
        val keyTimes = longArrayOf(preDownAt, midAt, preUpAt, settleAt)

        val preRec = VScreenRecorder.frameAt(d.displayId, preDownAt)
        val pickupFrom = downAt + PICKUP_FROM_MS
        val pickupRecs = if (upAt >= pickupFrom) {
            VScreenRecorder.frames(d.displayId, pickupFrom, upAt).let { list ->
                if (list.size <= MAX_PICKUP_FRAMES) list
                else (0 until MAX_PICKUP_FRAMES).map { k -> list[(k * (list.size - 1)) / maxOf(1, MAX_PICKUP_FRAMES - 1)] }
            }
        } else emptyList()
        val duringRecs = VScreenRecorder.frames(d.displayId, downAt, maxOf(downAt, preUpAt))
        val midRec = duringRecs.minByOrNull { kotlin.math.abs(it.atMs - midAt) }
            ?: VScreenRecorder.frameAt(d.displayId, midAt)?.takeIf { it.atMs >= downAt }
        val settleRec = VScreenRecorder.frameAt(d.displayId, settleAt)

        val preBmp = preRec?.let { VScreenRecorder.decode(it, VScreenRecorder.MAX_SIDE) }
        val pickupBmps = pickupRecs.mapNotNull { VScreenRecorder.decode(it, VScreenRecorder.MAX_SIDE) }
        val midBmp = midRec?.let { VScreenRecorder.decode(it, VScreenRecorder.MAX_SIDE) }
        val settleBmp = settleRec?.let { VScreenRecorder.decode(it, VScreenRecorder.MAX_SIDE) }
        try {
            val ref = preBmp ?: settleBmp
            if (ref == null) {
                return GestureRecord(null, null, AFTER_UNKNOWN, 0.0, pollMsP50, pollMsMax, keyTimes)
            }
            val startF = toFrameCoord(startPt, d.width, d.height, ref.width, ref.height)
            val midF = toFrameCoord(midPt, d.width, d.height, ref.width, ref.height)
            val endF = toFrameCoord(endPt, d.width, d.height, ref.width, ref.height)
            return analyzeGestureFrames(
                preFrame = preBmp,
                pickupFrames = pickupBmps,
                midFrame = midBmp,
                settleFrame = settleBmp,
                startF = startF,
                midF = midF,
                endF = endF,
                mask = VScreenPerception.mask(d.displayId),
                pollMsP50 = pollMsP50,
                pollMsMax = pollMsMax,
                keyTimesMs = keyTimes,
            )
        } finally {
            preBmp?.recycle()
            pickupBmps.forEach { it.recycle() }
            midBmp?.recycle()
            settleBmp?.recycle()
        }
    }

    /**
     * 4.4: fixed 4-cell contact strip for a drag in full replies:
     * before down, drag midpoint, before release, 350ms after release.
     */
    fun pickDragCells(displayId: Int, keyTimesMs: LongArray, maxSide: Int): List<Cell> {
        if (keyTimesMs.size < 4) return emptyList()
        val labels = listOf(
            tr("按下前", "before down"),
            tr("滑动中点", "drag midpoint"),
            tr("松手前", "before release"),
            tr("松手后 350ms", "350ms after release"),
        )
        val dragMark = VScreenRecorder.marks(displayId, keyTimesMs[0] - DRAG_MARK_MARGIN_MS, keyTimesMs[3] + DRAG_MARK_MARGIN_MS)
            .lastOrNull { it.kind == "action" && it.pts.size >= 2 }
        val out = ArrayList<Cell>(4)
        for (i in 0 until 4) {
            val t = keyTimesMs[i]
            val fr = VScreenRecorder.frameAt(displayId, t) ?: continue
            val bmp = VScreenRecorder.decode(fr, maxSide) ?: continue
            val marks = if (i == 0 && dragMark != null) listOf(dragMark) else emptyList()
            val nums = if (marks.isNotEmpty()) listOf(1) else emptyList()
            out += Cell(fr.atMs, bmp, labels[i], marks, nums)
        }
        return out
    }

    /** Sleep [ms] in short slices, checking takeover and interrupts on each slice. */
    internal fun sleepChecked(d: VScreenDisplay, call: Call?, ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        while (true) {
            checkLoopInterrupts(d, call)
            val left = end - SystemClock.elapsedRealtime()
            if (left <= 0L) break
            Thread.sleep(minOf(INTERRUPT_SLICE_MS, left))
        }
    }

    internal fun checkLoopInterrupts(d: VScreenDisplay, call: Call?) {
        if (BackgroundOperation.state.value.takeoverSessionId != null) {
            throw ActionError("USER_TAKEOVER", tr("用户接管了副屏，已抬起手指", "The user took over the screen; fingers lifted"))
        }
        checkInterrupts(d, call)
    }
}

/** 2.0.0-beta4 (4.4) & 2.0.0-beta5 (5.7): tracking record generated for every drag (normal, multi-point, hold, seek). */
internal data class GestureRecord(
    val pickedUp: Boolean?,
    val followed: Boolean?,
    val afterRelease: String,
    val score: Double,
    val pollMsP50: Long? = null,
    val pollMsMax: Long? = null,
    val keyTimesMs: LongArray? = null,
    val imgAgeMs: Long? = null,
) {
    fun describe(omitUnknownWhenNotPicked: Boolean = false): String {
        val parts = ArrayList<String>(3)
        when (pickedUp) {
            true -> parts += tr("拿起了", "picked up")
            false -> parts += tr("未拿起", "not picked up")
            null -> {}
        }
        when (followed) {
            true -> parts += tr("跟手", "followed")
            false -> parts += tr("未跟手", "did not follow")
            null -> {}
        }
        val afterText = when (afterRelease) {
            Gesture.AFTER_STAYED -> tr("松手后留在落点", "stayed at release")
            Gesture.AFTER_SNAPPED_BACK -> tr("松手后弹回起点", "snapped back to start")
            Gesture.AFTER_ELSEWHERE -> tr("松手后移到了别处", "moved elsewhere after release")
            Gesture.AFTER_DISAPPEARED -> tr("松手后不见了（可能被划掉或删除）", "disappeared after release (may have been swiped away or deleted)")
            else -> if (omitUnknownWhenNotPicked && pickedUp == null) null else tr("松手后位置不明", "position unknown after release")
        }
        if (afterText != null) parts += afterText
        return parts.joinToString(" · ")
    }

    fun describeSuffix(omitUnknownWhenNotPicked: Boolean = false): String {
        val d = describe(omitUnknownWhenNotPicked)
        return if (d.isEmpty()) "" else tr("：", ": ") + d
    }

    fun json(): JSONObject = JSONObject()
        .put("picked_up", pickedUp ?: JSONObject.NULL)
        .put("followed", followed ?: JSONObject.NULL)
        .put("after_release", afterRelease)
        .put("score", Math.round(score * 100.0) / 100.0)
        .also { o ->
            pollMsP50?.let { o.put("poll_ms_p50", it) }
            pollMsMax?.let { o.put("poll_ms_max", it) }
            imgAgeMs?.let { o.put("img_age_ms", it) }
        }
}

/** 2.0.0-beta4 (4.3), beta5 (5.7) & beta8 (C4+E): result of one closed-loop `hold` / `seek` gesture. */
internal class GestureOut(
    val met: Boolean,
    val releasedAt: Pair<Int, Int>,
    val ms: Long,
    val record: GestureRecord?,
    val metFact: String? = null,
    val metKind: CondKind? = null,
    val pollMsP50: Long? = null,
    val pollMsMax: Long? = null,
    val imgAgeMs: Long? = null,
    val actionFact: String = "",
    val points: List<IntArray> = emptyList(),
    val missReason: String? = null,
    val notRead: Boolean = false,
    val alreadyAtStart: Boolean = false,
)

/**
 * 2.0.0-beta8 (C4 + E): pure closed-loop search engine for `drag … seek until …`.
 *
 * - Pre-checks the start state before touching the screen (`alreadyAtStart`).
 * - Parses numeric values (`170%`, decimals, `mm:ss` / `hh:mm:ss` durations) to know direction and overshoot:
 *   advances at current step, reverses & halves step (down to `1px`) on overshoot, and stops cleanly with the
 *   closest seen value when `1px` resolution still brackets the target without hitting it.
 * - For non-numeric text targets, adapts step size downward whenever consecutive readings change.
 * - Enforces per-read timeout accounting: timed-out steps do not corrupt search state, and consecutive timeouts
 *   stop cleanly with `notRead = true`.
 */
internal object SeekSearch {
    private val TIME_RE = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})(?:\.(\d+))?""")
    private val NUM_RE = Regex("""(-?\d+(?:\.\d+)?)""")

    private fun normalizeDigits(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when (ch) {
                    in '０'..'９' -> ('0'.code + (ch.code - '０'.code)).toChar()
                    '％' -> '%'
                    '：' -> ':'
                    '．' -> '.'
                    '－', '−' -> '-'
                    else -> ch
                },
            )
        }
        return sb.toString()
    }

    /** Parse a numeric value from slider/media readout text (`170%`, `70`, `1.5x`, `1:30`, `01:02:03`), or null. */
    fun parseNumber(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val norm = normalizeDigits(raw).trim()
        TIME_RE.find(norm)?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val min = m.groupValues[2].toLongOrNull() ?: return null
            val sec = m.groupValues[3].toLongOrNull() ?: return null
            val frac = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { "0.$it".toDoubleOrNull() } ?: 0.0
            return h * 3600.0 + min * 60.0 + sec + frac
        }
        val nm = NUM_RE.find(norm) ?: return null
        return nm.groupValues[1].toDoubleOrNull()
    }

    /** Extract an exact numeric target from [conds] when it is a single non-wildcard numeric `text:` condition. */
    fun extractTargetNumber(conds: List<Cond>): Double? {
        if (conds.size != 1) return null
        val c = conds[0]
        if (c.kind != CondKind.TEXT) return null
        val t = c.text ?: return null
        if ('*' in t) return null
        return parseNumber(t)
    }

    data class SeekReading(
        val hit: String?,
        val metKind: CondKind? = null,
        val text: String? = null,
        val imgAgeMs: Long? = null,
        val timedOut: Boolean = false,
    )

    data class SeekResult(
        val met: Boolean,
        val alreadyAtStart: Boolean = false,
        val metFact: String? = null,
        val metKind: CondKind? = null,
        val imgAgeMs: Long? = null,
        val finalPos: Double,
        val finalPt: Pair<Int, Int>,
        val bestText: String? = null,
        val bestPt: Pair<Int, Int>? = null,
        val notRead: Boolean = false,
        val missReason: String? = null,
        val stepsTaken: Int = 0,
        val reversals: Int = 0,
    )

    fun run(
        p0: Pair<Int, Int>,
        p1: Pair<Int, Int>,
        targetNum: Double?,
        maxMs: Long,
        maxSteps: Int = Gesture.SEEK_MAX_STEPS,
        maxConsecTimeouts: Int = Gesture.SEEK_MAX_CONSEC_TIMEOUTS,
        clockMs: () -> Long,
        checkInterrupt: () -> Unit,
        readStart: () -> SeekReading,
        onDown: () -> Unit,
        readAfterDown: () -> SeekReading,
        moveAndRead: (pt: Pair<Int, Int>, stepIndex: Int) -> SeekReading,
    ): SeekResult {
        checkInterrupt()
        val startRead = readStart()
        if (startRead.hit != null) {
            return SeekResult(
                met = true,
                alreadyAtStart = true,
                metFact = startRead.hit,
                metKind = startRead.metKind,
                imgAgeMs = startRead.imgAgeMs,
                finalPos = 0.0,
                finalPt = p0,
                bestText = startRead.text,
                bestPt = p0,
            )
        }

        val dx = (p1.first - p0.first).toDouble()
        val dy = (p1.second - p0.second).toDouble()
        val totalDist = hypot(dx, dy)
        fun ptAt(pos: Double): Pair<Int, Int> {
            if (totalDist <= 0.0) return p0
            val f = (pos / totalDist).coerceIn(0.0, 1.0)
            return (p0.first + dx * f).roundToInt() to (p0.second + dy * f).roundToInt()
        }

        onDown()
        val t0 = clockMs()
        val r0 = readAfterDown()
        if (r0.hit != null) {
            return SeekResult(
                met = true,
                metFact = r0.hit,
                metKind = r0.metKind,
                imgAgeMs = r0.imgAgeMs,
                finalPos = 0.0,
                finalPt = p0,
                bestText = r0.text,
                bestPt = p0,
            )
        }

        var pos = 0.0
        var curPt = p0
        var dir = 1
        val initialStep = if (targetNum != null) {
            (totalDist / 20.0).coerceIn(Gesture.SEEK_STEP_PX.toDouble(), 44.0)
        } else {
            Gesture.SEEK_STEP_PX.toDouble()
        }
        var step = initialStep
        var prevVal: Double? = parseNumber(r0.text)
        var prevPos = 0.0
        var prevText: String? = r0.text?.trim()?.takeIf { it.isNotEmpty() }
        var slopeSign: Int? = null
        var bestDist = if (targetNum != null && prevVal != null) abs(prevVal - targetNum) else Double.POSITIVE_INFINITY
        var bestText: String? = r0.text?.trim()?.takeIf { it.isNotEmpty() }
        var bestPt: Pair<Int, Int>? = if (prevVal != null) p0 else null
        var consecTimeouts = if (r0.timedOut) 1 else 0
        var stepsTaken = 0
        var reversals = 0
        var onePxOvershoots = 0
        var nonNumChangeStreak = 0
        var nonNumSameStreak = 0

        while (stepsTaken < maxSteps) {
            checkInterrupt()
            if (clockMs() - t0 >= maxMs) break
            if (consecTimeouts >= maxConsecTimeouts) {
                return SeekResult(
                    met = false,
                    finalPos = pos,
                    finalPt = curPt,
                    bestText = bestText,
                    bestPt = bestPt,
                    notRead = true,
                    missReason = tr("读数跟不上（连续 $consecTimeouts 次读屏超时）", "screen reading could not keep up ($consecTimeouts consecutive read timeouts)"),
                    stepsTaken = stepsTaken,
                    reversals = reversals,
                )
            }

            var nextPos = (pos + dir * step).coerceIn(0.0, totalDist)
            if (ptAt(nextPos) == curPt) {
                nextPos = (pos + dir * 1.0).coerceIn(0.0, totalDist)
                if (ptAt(nextPos) == curPt) {
                    val pv = prevVal
                    val ss = slopeSign
                    if (targetNum != null && pv != null && ss != null) {
                        val desiredDir = if ((targetNum - pv) * ss > 0.0) 1 else -1
                        if (desiredDir != dir) {
                            dir = desiredDir
                            step = maxOf(1.0, step / 2.0)
                            reversals++
                            continue
                        }
                    }
                    break
                }
            }

            val nextPt = ptAt(nextPos)
            stepsTaken++
            val reading = moveAndRead(nextPt, stepsTaken)
            pos = nextPos
            curPt = nextPt

            if (reading.timedOut) {
                consecTimeouts++
                continue
            }
            consecTimeouts = 0

            if (reading.hit != null) {
                return SeekResult(
                    met = true,
                    metFact = reading.hit,
                    metKind = reading.metKind,
                    imgAgeMs = reading.imgAgeMs,
                    finalPos = pos,
                    finalPt = curPt,
                    bestText = reading.text ?: bestText,
                    bestPt = curPt,
                    stepsTaken = stepsTaken,
                    reversals = reversals,
                )
            }

            val curText = reading.text?.trim()?.takeIf { it.isNotEmpty() }
            if (targetNum != null) {
                val num = parseNumber(curText)
                if (num != null) {
                    val dist = abs(num - targetNum)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestText = curText
                        bestPt = curPt
                    }
                    val pv = prevVal
                    if (pv != null && num != pv && abs(pos - prevPos) >= 1.0) {
                        slopeSign = if ((num - pv) * (pos - prevPos) > 0.0) 1 else -1
                    }
                    if (pv != null && (pv - targetNum) * (num - targetNum) < 0.0) {
                        if (step <= 1.0) {
                            onePxOvershoots++
                            if (onePxOvershoots >= 2) break
                            dir = -dir
                            reversals++
                        } else {
                            dir = -dir
                            step = maxOf(1.0, step / 2.0)
                            reversals++
                        }
                    } else {
                        val s = slopeSign
                        if (s != null && num != targetNum) {
                            val desiredDir = if ((targetNum - num) * s > 0.0) 1 else -1
                            if (desiredDir != dir) {
                                dir = desiredDir
                                if (reversals > 0) step = maxOf(1.0, step / 2.0)
                                reversals++
                            }
                        }
                    }
                    prevVal = num
                    prevPos = pos
                }
            } else if (curText != null) {
                if (prevText != null) {
                    if (curText != prevText) {
                        nonNumChangeStreak++
                        nonNumSameStreak = 0
                        if (nonNumChangeStreak >= 2) {
                            step = maxOf(1.0, step / 2.0)
                            nonNumChangeStreak = 0
                        }
                    } else {
                        nonNumSameStreak++
                        nonNumChangeStreak = 0
                        if (nonNumSameStreak >= 3 && step < initialStep) {
                            step = minOf(initialStep, step * 1.5)
                            nonNumSameStreak = 0
                        }
                    }
                }
                prevText = curText
            }
        }

        val miss = when {
            consecTimeouts >= maxConsecTimeouts -> tr(
                "读数跟不上（连续 $consecTimeouts 次读屏超时）",
                "screen reading could not keep up ($consecTimeouts consecutive read timeouts)",
            )
            bestText != null && bestPt != null -> tr(
                "最接近的是 $bestText，在 (${bestPt.first},${bestPt.second})",
                "closest was $bestText at (${bestPt.first},${bestPt.second})",
            )
            consecTimeouts > 0 -> tr("读数跟不上", "screen reading could not keep up")
            else -> null
        }
        return SeekResult(
            met = false,
            finalPos = pos,
            finalPt = curPt,
            bestText = bestText,
            bestPt = bestPt,
            notRead = bestText == null && consecTimeouts > 0,
            missReason = miss,
            stepsTaken = stepsTaken,
            reversals = reversals,
        )
    }
}

private val SEEK_READ_POOL = Executors.newCachedThreadPool { r ->
    Thread(r, "vscreen-seek-read").apply { isDaemon = true }
}

/**
 * 2.0.0-beta4 (4.3 & 4.7) + 2.0.0-beta8 (C4 + E): execute a closed-loop `press … hold`, `drag … hold`, or `drag … seek` gesture.
 *
 * - Runs existing `payGuard` (press) or `bounds` (drag) before finger down.
 * - Boosts display frame rate and pauses background full-screen OCR (`VScreenPerception.setOcrPaused`), always
 *   restoring both OCR and `finger("cancel")` in `finally`.
 * - For `seek`: pre-checks start before touching the screen, runs [SeekSearch] with strict post-move frames and a
 *   1.5 s per-read timeout cap.
 */
internal fun VScreenOffloadHandler.closedLoop(sid: String, d: VScreenDisplay, step: Step, ctx: ActCtx): GestureOut {
    val hu = ctx.human
    val call = Call.current()
    val conds = step.until ?: throw ActionError("GESTURE_NEEDS_UNTIL", tr("闭环手势必须带 until <条件>", "Closed-loop gesture requires until <condition>"))
    val maxMs = step.untilMs ?: if (step.loop == Loop.SEEK) Steps.SEEK_DEFAULT_MS else Steps.HOLD_DEFAULT_MS
    val pkg = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()

    fun there(t: Target) = (t.label?.let { tr("，那里是“${it.take(30)}”", ", which is \"${it.take(30)}\"") } ?: "") +
        (t.moved?.let { tr("（$it）", " ($it)") } ?: "")

    var pressTarget: Target? = null
    val path: List<Pair<Int, Int>>
    if (step.kind == StepKind.PRESS) {
        val t = target(sid, d, step.target!!, ctx)
        payGuard(sid, d, t.atPoint, t.x, t.y)
        pressTarget = t
        val start = if (hu != null) hu.drift(t.x to t.y, Gesture.HUMAN_START_DRIFT_PX, d.width, d.height) else (t.x to t.y)
        path = listOf(start)
        ctx.point = start
    } else {
        bounds(d, step.points)
        val start = if (hu != null) hu.drift(step.points[0], Gesture.HUMAN_START_DRIFT_PX, d.width, d.height) else step.points[0]
        path = listOf(start) + step.points.drop(1)
        ctx.point = null
    }

    val startPt = path[0]
    val isDrag = step.kind == StepKind.DRAG
    val dragMs = (step.ms ?: Gesture.DEFAULT_DRAG_MS).toInt()
    val colorOnlyPress = !isDrag && conds.all { it.kind == CondKind.COLOR }
    if (!colorOnlyPress) {
        d.boost(maxMs + (if (isDrag && step.loop == Loop.HOLD) dragMs.toLong() else 0L) + Gesture.BOOST_EXTRA_MS)
    } else {
        d.colorFastMode = true
    }
    VScreenPerception.setOcrPaused(d.displayId, true)
    val probe = Probe(this, d, conds, afterAction = false, cropOnly = true)
    val pollDurations = ArrayList<Long>()
    fun timedPoll(): String? {
        val p0 = SystemClock.elapsedRealtime()
        val hit = probe.poll()
        pollDurations += (SystemClock.elapsedRealtime() - p0).coerceAtLeast(0L)
        return hit
    }

    /** 2.0.0-beta8 (C4+E): read one seek observation strictly from [frame] + refreshed A11y within [Gesture.SEEK_READ_TIMEOUT_MS]. */
    fun readSeekStep(frame: Bitmap?, targetNum: Double?): SeekSearch.SeekReading {
        val p0 = SystemClock.elapsedRealtime()
        val fut = SEEK_READ_POOL.submit<SeekSearch.SeekReading>(com.kilon.agentvscreen.I18n.carry {
            var observedText: String? = null
            for (c in conds) {
                if (c.kind == CondKind.TEXT || c.kind == CondKind.GONE) {
                    val area = c.area
                    val pat = TextPat(c.text!!)
                    fun inArea(r: Rect) = area == null || area.contains(r.centerX(), r.centerY())
                    val a11yTexts = runCatching {
                        val snap = com.kilon.agentvscreen.core.A11yTree.snapshot(d.displayId)
                        if (!snap.readable) emptyList()
                        else snap.nodes.asSequence()
                            .filter { it.visible && inArea(d.toFrame(it.box)) }
                            .mapNotNull { node ->
                                val fresh = runCatching { node.info.refresh() }.getOrDefault(false)
                                if (fresh && !node.info.isVisibleToUser) null
                                else com.kilon.agentvscreen.core.A11yTree.wordsOf(node.info) ?: node.words
                            }
                            .toList()
                    }.getOrDefault(emptyList())
                    val a11yNumeric = if (targetNum != null) a11yTexts.firstOrNull { SeekSearch.parseNumber(it) != null } else a11yTexts.firstOrNull()
                    if (c.kind == CondKind.TEXT) {
                        val a11yHit = a11yTexts.firstOrNull { pat.hit(it) }
                        if (a11yHit != null) {
                            return@carry SeekSearch.SeekReading(
                                hit = tr("${c.raw}：看到“${a11yHit.take(40)}”", "${c.raw}: saw \"${a11yHit.take(40)}\""),
                                metKind = CondKind.TEXT,
                                text = a11yHit.take(40),
                            )
                        }
                        if (a11yNumeric != null) {
                            observedText = a11yNumeric.take(40)
                            continue
                        }
                    } else if (a11yTexts.any { pat.hit(it) }) {
                        observedText = a11yTexts.firstOrNull { pat.hit(it) }?.take(40)
                        continue
                    }
                    // Fall back to crop OCR on this exact post-move frame when A11y has no matching/numeric text.
                    val items = if (frame != null && !frame.isRecycled && area != null) {
                        runCatching { com.kilon.agentvscreen.core.VScreenOcr.readCrop(frame, area) }.getOrDefault(emptyList())
                    } else emptyList()
                    val joined = VScreenPerception.joinSplit(items)
                    val allTexts = items.map { it.text } + joined
                    val ocrSample = (if (targetNum != null) allTexts.firstOrNull { SeekSearch.parseNumber(it) != null } else null)
                        ?: allTexts.firstOrNull()
                    if (ocrSample != null) observedText = ocrSample.take(40)
                    if (c.kind == CondKind.TEXT) {
                        val hitTxt = allTexts.firstOrNull { pat.hit(it) }?.take(40)
                        if (hitTxt != null) {
                            return@carry SeekSearch.SeekReading(
                                hit = tr("${c.raw}：看到“$hitTxt”", "${c.raw}: saw \"$hitTxt\""),
                                metKind = CondKind.TEXT,
                                text = hitTxt,
                            )
                        }
                    } else if (c.kind == CondKind.GONE && area != null && frame != null && !frame.isRecycled) {
                        if (allTexts.none { pat.hit(it) }) {
                            return@carry SeekSearch.SeekReading(
                                hit = tr("${c.raw}：画面上没有“${c.text}”了", "${c.raw}: \"${c.text}\" is gone from the screen"),
                                metKind = CondKind.GONE,
                                text = observedText,
                            )
                        }
                    }
                } else {
                    val hit = probe.poll()
                    if (hit != null) {
                        return@carry SeekSearch.SeekReading(
                            hit = hit,
                            metKind = probe.lastMetKind,
                            text = observedText,
                            imgAgeMs = probe.lastHitImgAgeMs,
                        )
                    }
                }
            }
            SeekSearch.SeekReading(hit = null, text = observedText)
        })
        return try {
            val res = fut.get(Gesture.SEEK_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            pollDurations += (SystemClock.elapsedRealtime() - p0).coerceAtLeast(0L)
            res
        } catch (_: TimeoutException) {
            pollDurations += (SystemClock.elapsedRealtime() - p0).coerceAtLeast(0L)
            SeekSearch.SeekReading(hit = null, timedOut = true)
        } catch (_: Throwable) {
            pollDurations += (SystemClock.elapsedRealtime() - p0).coerceAtLeast(0L)
            SeekSearch.SeekReading(hit = null)
        }
    }

    /** Wait strictly for a frame captured after [afterMoveStart] / [afterMoveDone], returning `(frame, fresh)`. */
    fun awaitPostMoveFrame(verBefore: Long, afterMoveStart: Long, afterMoveDone: Long): Pair<Bitmap?, Boolean> {
        d.requestFreshFrame()
        val deadline = afterMoveDone + Gesture.SEEK_FRAME_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            val capAt = d.latestCaptureAtMs
            if (capAt > afterMoveDone) {
                return d.latestFrame() to true
            }
            if (now >= afterMoveDone + Gesture.SEEK_FRAME_DELAY_MS && d.version > verBefore && capAt >= afterMoveStart) {
                return d.latestFrame() to true
            }
            val left = (deadline - now).coerceIn(1L, Gesture.SEEK_FRAME_POLL_SLICE_MS)
            d.awaitNewFrame(d.version, left, urgent = true)
        }
        val capAt = d.latestCaptureAtMs
        return d.latestFrame() to (capAt > afterMoveDone || (d.version > verBefore && capAt >= afterMoveStart))
    }

    var curPt = startPt
    var midPt = if (path.size >= 2) Gesture.pointAtHalfPath(path) else startPt
    var metFact: String? = null
    var metKind: CondKind? = null
    var hitImgAgeMs: Long? = null
    var preDownAt = System.currentTimeMillis()
    var tStart = SystemClock.elapsedRealtime()
    var downAt = preDownAt
    var midAt = downAt
    var preUpAt = downAt
    var upAt = downAt
    var upElapsed = tStart
    var hitElapsed = -1L
    var fingerDown = false
    var seekAlreadyAtStart = false
    var seekStartLabel: String? = null
    var seekMissReason: String? = null
    var seekNotRead = false
    var beforeEls: List<El>? = null

    try {
        Gesture.checkLoopInterrupts(d, call)
        VScreenPerception.awaitOcrIdle(d.displayId, Gesture.OCR_IDLE_WAIT_MS) {
            Gesture.checkLoopInterrupts(d, call)
        }
        if (isDrag) {
            d.latestWithVersion()?.let { (bmp, ver) -> VScreenRecorder.offerFresh(d, bmp, ver, pkg) }
            beforeEls = runCatching { a11yRaw(d).filter { !it.placeholder } }.getOrNull()
        }
        preDownAt = System.currentTimeMillis()
        tStart = SystemClock.elapsedRealtime()

        if (step.loop == Loop.HOLD) {
            Gesture.checkLoopInterrupts(d, call)
            val down = VScreenShell.finger(d.displayId, "down", 0, startPt.first, startPt.second).first
            if (!down.ok) throw ActionError(down.code, down.message)
            fingerDown = true
            downAt = System.currentTimeMillis()
            midAt = downAt
            if (isDrag) {
                Gesture.sleepChecked(d, call, Gesture.DRAG_DOWN_HOLD_MS)
                val lens = path.zipWithNext { a, b -> hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) }
                val total = lens.sum().coerceAtLeast(1.0)
                val glideStart = SystemClock.elapsedRealtime()
                var midCaptured = false
                while (true) {
                    Gesture.checkLoopInterrupts(d, call)
                    val f = ((SystemClock.elapsedRealtime() - glideStart).toDouble() / dragMs).coerceIn(0.0, 1.0)
                    var dist = f * total
                    var i = 0
                    while (i < lens.size - 1 && dist > lens[i]) {
                        dist -= lens[i]
                        i++
                    }
                    val a = path[i]
                    val b = path[i + 1]
                    val k = if (lens[i] <= 0.0) 1.0 else (dist / lens[i]).coerceIn(0.0, 1.0)
                    val nx = (a.first + (b.first - a.first) * k).roundToInt()
                    val ny = (a.second + (b.second - a.second) * k).roundToInt()
                    curPt = nx to ny
                    val mv = VScreenShell.finger(d.displayId, "move", 0, nx, ny).first
                    if (!mv.ok) throw ActionError(mv.code, mv.message)
                    if (!midCaptured && f >= 0.5) {
                        midCaptured = true
                        midPt = curPt
                        midAt = System.currentTimeMillis()
                        d.latestWithVersion()?.let { (bmp, ver) -> VScreenRecorder.offerFresh(d, bmp, ver, pkg) }
                    }
                    if (f >= 1.0) break
                    Thread.sleep(Gesture.DRAG_MOVE_TICK_MS)
                }
                curPt = path.last()
                d.awaitFrameAfter(SystemClock.elapsedRealtime(), Gesture.HOLD_ARRIVE_FRAME_WAIT_MS)
            }
            val holdStart = SystemClock.elapsedRealtime()
            var lastKeepAlive = holdStart
            while (true) {
                Gesture.checkLoopInterrupts(d, call)
                val sampleBefore = if (colorOnlyPress) d.latestColorSampleSeq else -1L
                val hit = timedPoll()
                if (hit != null) {
                    hitElapsed = SystemClock.elapsedRealtime()
                    metFact = hit
                    metKind = probe.lastMetKind
                    hitImgAgeMs = probe.lastHitImgAgeMs
                    break
                }
                val afterPoll = SystemClock.elapsedRealtime()
                if (afterPoll - holdStart >= maxMs) break
                if (afterPoll - lastKeepAlive >= Gesture.HOLD_KEEPALIVE_MS) {
                    val mv = VScreenShell.finger(d.displayId, "move", 0, curPt.first, curPt.second).first
                    if (!mv.ok) throw ActionError(mv.code, mv.message)
                    lastKeepAlive = afterPoll
                }
                val waitSlice = minOf(Gesture.HOLD_POLL_MS, maxMs - (afterPoll - holdStart)).coerceAtLeast(1L)
                if (colorOnlyPress) {
                    d.awaitColorSample(sampleBefore, waitSlice)
                } else {
                    Thread.sleep(waitSlice)
                }
            }
            if (isDrag) {
                d.latestWithVersion()?.let { (bmp, ver) -> VScreenRecorder.offerFresh(d, bmp, ver, pkg) }
            }
            preUpAt = System.currentTimeMillis()
            VScreenShell.finger(d.displayId, "up", 0, curPt.first, curPt.second)
            fingerDown = false
            upAt = System.currentTimeMillis()
            upElapsed = SystemClock.elapsedRealtime()
        } else {
            // Loop.SEEK (2.0.0-beta8 C4+E: start pre-check + closed-loop search + strict post-move frames + 1.5s read cap).
            val targetNum = SeekSearch.extractTargetNumber(conds)
            val trail = ArrayList<Triple<Long, Int, Int>>()
            val seekRes = SeekSearch.run(
                p0 = path[0],
                p1 = path[1],
                targetNum = targetNum,
                maxMs = maxMs,
                clockMs = { SystemClock.elapsedRealtime() },
                checkInterrupt = { Gesture.checkLoopInterrupts(d, call) },
                readStart = {
                    val now = SystemClock.elapsedRealtime()
                    d.awaitFrameAfter(now - 50L, 120L)
                    readSeekStep(d.latestFrame(), targetNum)
                },
                onDown = {
                    Gesture.checkLoopInterrupts(d, call)
                    val down = VScreenShell.finger(d.displayId, "down", 0, startPt.first, startPt.second).first
                    if (!down.ok) throw ActionError(down.code, down.message)
                    fingerDown = true
                    downAt = System.currentTimeMillis()
                    midAt = downAt
                    Gesture.sleepChecked(d, call, Gesture.DRAG_DOWN_HOLD_MS)
                },
                readAfterDown = {
                    val afterDown = SystemClock.elapsedRealtime()
                    d.awaitFrameAfter(afterDown - Gesture.DRAG_DOWN_HOLD_MS, Gesture.SEEK_FRAME_WAIT_MS)
                    readSeekStep(d.latestFrame(), targetNum)
                },
                moveAndRead = { pt, _ ->
                    curPt = pt
                    val verBefore = d.version
                    val moveStart = SystemClock.elapsedRealtime()
                    val mv = VScreenShell.finger(d.displayId, "move", 0, pt.first, pt.second).first
                    if (!mv.ok) throw ActionError(mv.code, mv.message)
                    val moveDone = SystemClock.elapsedRealtime()
                    trail += Triple(System.currentTimeMillis(), pt.first, pt.second)
                    val (freshFrame, fresh) = awaitPostMoveFrame(verBefore, moveStart, moveDone)
                    Gesture.checkLoopInterrupts(d, call)
                    d.latestWithVersion()?.let { (bmp, ver) -> VScreenRecorder.offer(d, bmp, ver, pkg) }
                    val res = readSeekStep(freshFrame, targetNum)
                    // C4: never claim a hit from a stale pre-move frame on an unrefreshed screen.
                    if (!fresh && res.hit != null && d.latestCaptureAtMs < moveStart) {
                        res.copy(hit = null)
                    } else res
                },
            )
            curPt = seekRes.finalPt
            if (seekRes.alreadyAtStart) {
                seekAlreadyAtStart = true
                seekStartLabel = seekRes.bestText ?: seekRes.metFact
                metFact = seekRes.metFact
                metKind = seekRes.metKind
                hitImgAgeMs = seekRes.imgAgeMs
                upElapsed = SystemClock.elapsedRealtime()
            } else {
                if (trail.isNotEmpty()) {
                    val mid = trail[trail.size / 2]
                    midAt = mid.first
                    midPt = mid.second to mid.third
                }
                if (seekRes.met) {
                    hitElapsed = SystemClock.elapsedRealtime()
                    metFact = seekRes.metFact
                    metKind = seekRes.metKind
                    hitImgAgeMs = seekRes.imgAgeMs
                    Gesture.sleepChecked(d, call, Gesture.SEEK_END_HOLD_MS)
                } else {
                    seekMissReason = seekRes.missReason
                    seekNotRead = seekRes.notRead
                }
                d.latestWithVersion()?.let { (bmp, ver) -> VScreenRecorder.offerFresh(d, bmp, ver, pkg) }
                preUpAt = System.currentTimeMillis()
                VScreenShell.finger(d.displayId, "up", 0, curPt.first, curPt.second)
                fingerDown = false
                upAt = System.currentTimeMillis()
                upElapsed = SystemClock.elapsedRealtime()
            }
        }
    } finally {
        if (colorOnlyPress) {
            d.colorFastMode = false
            d.requestFreshFrame()
            val diag = probe.colorDiag(hitElapsed, upElapsed, tStart)
            com.kilon.agentvscreen.host.AppLogger.info("Gesture", "colorHold $diag")
        }
        if (fingerDown) {
            com.kilon.agentvscreen.host.quiet("closedLoop-cancel") { VScreenShell.finger(d.displayId, "cancel", 0, -1, -1) }
        }
        probe.close()
        VScreenPerception.setOcrPaused(d.displayId, false)
    }

    val totalMs = (upElapsed - tStart).coerceAtLeast(0L)
    val stats = Gesture.pollStats(pollDurations)
    if (seekAlreadyAtStart) {
        val valLabel = seekStartLabel ?: tr("目标值", "the target")
        return GestureOut(
            met = true,
            releasedAt = startPt,
            ms = totalMs,
            record = null,
            metFact = metFact,
            metKind = metKind,
            pollMsP50 = stats?.first,
            pollMsMax = stats?.second,
            imgAgeMs = hitImgAgeMs,
            actionFact = tr("开始时已经是 $valLabel，没有拖", "already $valLabel at start, did not drag"),
            points = emptyList(),
            alreadyAtStart = true,
        )
    }

    val record = if (isDrag) {
        Gesture.evaluateDragRecord(
            before = beforeEls,
            d = d,
            startPt = startPt,
            midPt = midPt,
            endPt = curPt,
            preDownAt = preDownAt,
            downAt = downAt,
            midAt = midAt,
            preUpAt = preUpAt,
            upAt = upAt,
            upElapsed = upElapsed,
            pkg = pkg,
            pollMsP50 = stats?.first,
            pollMsMax = stats?.second,
        ).let { r -> if (hitImgAgeMs != null) r.copy(imgAgeMs = hitImgAgeMs) else r }
    } else null

    val recPts = if (isDrag) {
        if (step.loop == Loop.SEEK) listOf(intArrayOf(startPt.first, startPt.second), intArrayOf(curPt.first, curPt.second))
        else path.map { intArrayOf(it.first, it.second) }
    } else {
        listOf(intArrayOf(startPt.first, startPt.second))
    }

    val actionFact = if (isDrag) {
        val shownPath = if (step.loop == Loop.SEEK) listOf(startPt, curPt) else path
        tr("按住拖过 ", "held and dragged through ") +
            shownPath.joinToString(" → ") { "(${it.first},${it.second})" } +
            tr("，${totalMs}ms", ", ${totalMs}ms") +
            (record?.describeSuffix() ?: "")
    } else {
        tr("按住 (${startPt.first},${startPt.second}) ${totalMs}ms", "held (${startPt.first},${startPt.second}) ${totalMs}ms") +
            (pressTarget?.let { there(it) } ?: "")
    }

    return GestureOut(
        met = metFact != null,
        releasedAt = curPt,
        ms = totalMs,
        record = record,
        metFact = metFact,
        metKind = metKind,
        pollMsP50 = stats?.first,
        pollMsMax = stats?.second,
        imgAgeMs = hitImgAgeMs,
        actionFact = actionFact,
        points = recPts,
        missReason = seekMissReason,
        notRead = seekNotRead,
    )
}
