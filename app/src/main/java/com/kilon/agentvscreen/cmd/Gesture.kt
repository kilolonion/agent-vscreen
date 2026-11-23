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
    const val SEEK_FRAME_WAIT_MS = 400L
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
            when {
                pickedUp == true && !startChanged && wholeUnchanged -> AFTER_SNAPPED_BACK
                startChanged && endChanged -> AFTER_STAYED
                startChanged && !endChanged && !wholeUnchanged -> AFTER_ELSEWHERE
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

/** 2.0.0-beta4 (4.3) & 2.0.0-beta5 (5.7): result of one closed-loop `hold` / `seek` gesture. */
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
)

/**
 * 2.0.0-beta4 (4.3 & 4.7): execute a closed-loop `press … hold`, `drag … hold`, or `drag … seek` gesture.
 *
 * - Runs existing `payGuard` (press) or `bounds` (drag) before finger down.
 * - Boosts display frame rate and pauses background full-screen OCR (`VScreenPerception.setOcrPaused`), always
 *   restoring both OCR and `finger("cancel")` in `finally`.
 * - Evaluates conditions exclusively through [Probe] (`cropOnly = true` so `text:`/`gone:` use `readCrop` only).
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
        payGuard(sid, d, t.label, t.x, t.y)
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

    try {
        Gesture.checkLoopInterrupts(d, call)
        VScreenPerception.awaitOcrIdle(d.displayId, Gesture.OCR_IDLE_WAIT_MS) {
            Gesture.checkLoopInterrupts(d, call)
        }
        if (isDrag) {
            d.latestWithVersion()?.let { (bmp, ver) -> VScreenRecorder.offerFresh(d, bmp, ver, pkg) }
        }
        preDownAt = System.currentTimeMillis()
        tStart = SystemClock.elapsedRealtime()
        Gesture.checkLoopInterrupts(d, call)
        val down = VScreenShell.finger(d.displayId, "down", 0, startPt.first, startPt.second).first
        if (!down.ok) throw ActionError(down.code, down.message)
        downAt = System.currentTimeMillis()
        midAt = downAt

        if (step.loop == Loop.HOLD) {
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
            upAt = System.currentTimeMillis()
            upElapsed = SystemClock.elapsedRealtime()
        } else {
            // Loop.SEEK (2-point straight line, 12px steps)
            Gesture.sleepChecked(d, call, Gesture.DRAG_DOWN_HOLD_MS)
            val p0 = path[0]
            val p1 = path[1]
            val dx = (p1.first - p0.first).toDouble()
            val dy = (p1.second - p0.second).toDouble()
            val totalDist = hypot(dx, dy)
            val stepCount = minOf(Gesture.SEEK_MAX_STEPS, ceil(totalDist / Gesture.SEEK_STEP_PX).toInt().coerceAtLeast(1))
            val seekStart = SystemClock.elapsedRealtime()
            val trail = ArrayList<Triple<Long, Int, Int>>(stepCount)
            for (s in 1..stepCount) {
                Gesture.checkLoopInterrupts(d, call)
                if (SystemClock.elapsedRealtime() - seekStart >= maxMs) break
                val traveled = s * Gesture.SEEK_STEP_PX.toDouble()
                val (nx, ny) = if (totalDist <= 0.0) {
                    p1
                } else if (traveled >= totalDist) {
                    p1
                } else {
                    val f = traveled / totalDist
                    (p0.first + dx * f).roundToInt() to (p0.second + dy * f).roundToInt()
                }
                curPt = nx to ny
                val verBefore = d.version
                val moveElapsed = SystemClock.elapsedRealtime()
                val mv = VScreenShell.finger(d.displayId, "move", 0, nx, ny).first
                if (!mv.ok) throw ActionError(mv.code, mv.message)
                trail += Triple(System.currentTimeMillis(), nx, ny)
                d.requestFreshFrame()
                val minWaitUntil = moveElapsed + Gesture.SEEK_FRAME_DELAY_MS
                val frameDeadline = moveElapsed + Gesture.SEEK_FRAME_WAIT_MS
                while (SystemClock.elapsedRealtime() < frameDeadline) {
                    val now = SystemClock.elapsedRealtime()
                    if (d.latestCaptureAtMs > minWaitUntil) break
                    if (now >= minWaitUntil && d.version > verBefore) break
                    val left = (frameDeadline - now).coerceIn(1L, Gesture.SEEK_FRAME_POLL_SLICE_MS)
                    d.awaitNewFrame(d.version, left, urgent = true)
                }
                Gesture.checkLoopInterrupts(d, call)
                d.latestWithVersion()?.let { (bmp, ver) -> VScreenRecorder.offer(d, bmp, ver, pkg) }
                val hit = timedPoll()
                if (hit != null) {
                    hitElapsed = SystemClock.elapsedRealtime()
                    metFact = hit
                    metKind = probe.lastMetKind
                    hitImgAgeMs = probe.lastHitImgAgeMs
                    break
                }
            }
            if (trail.isNotEmpty()) {
                val mid = trail[trail.size / 2]
                midAt = mid.first
                midPt = mid.second to mid.third
            }
            if (metFact != null) {
                Gesture.sleepChecked(d, call, Gesture.SEEK_END_HOLD_MS)
            }
            d.latestWithVersion()?.let { (bmp, ver) -> VScreenRecorder.offerFresh(d, bmp, ver, pkg) }
            preUpAt = System.currentTimeMillis()
            VScreenShell.finger(d.displayId, "up", 0, curPt.first, curPt.second)
            upAt = System.currentTimeMillis()
            upElapsed = SystemClock.elapsedRealtime()
        }
    } finally {
        if (colorOnlyPress) {
            val diag = probe.colorDiag(hitElapsed, upElapsed, tStart)
            com.kilon.agentvscreen.host.AppLogger.info("Gesture", "colorHold $diag")
        }
        com.kilon.agentvscreen.host.quiet("closedLoop-cancel") { VScreenShell.finger(d.displayId, "cancel", 0, -1, -1) }
        probe.close()
        VScreenPerception.setOcrPaused(d.displayId, false)
    }

    val totalMs = (upElapsed - tStart).coerceAtLeast(0L)
    val stats = Gesture.pollStats(pollDurations)
    val record = if (isDrag) {
        Gesture.evaluateDragRecord(
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
    )
}
