package com.kilon.agentvscreen.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.kilon.agentvscreen.host.AppLogger
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [vscreen] 2.0: the optional textless-element detector. It finds things a finger can tap that OCR cannot name
 * (icons, ×, switches, drawn buttons), so an agent gets a point for them instead of guessing coordinates.
 *
 * The model is NOT in the APK: its weights are AGPL-3.0 (trained from Ultralytics YOLO / OmniParser icon_detect,
 * tools/detector), the app is MIT. [DetectorModels] downloads it on request and checks its sha256; this object only
 * reads what is installed. Nothing installed, switched off, or failed to load = [detect] returns null and every
 * caller behaves exactly as without the feature.
 *
 * Model contract (tools/detector/README.md): input [1,3,H,W] float 0..1 RGB, the frame scaled to fit and anchored
 * top-left, the rest filled with 114/255; output [1,5,N] = cx, cy, w, h (input pixels), score. NMS happens here.
 *
 * A native crash inside the runtime would take the screen service down with it, so (like the OCR engines) a run is
 * marked in prefs before it starts and cleared after; a mark found on the next start means the last run died, and
 * the detector stays off until the model is installed again.
 */
object VScreenDetector {
    private const val TAG = "VScreenDetector"
    private const val PREFS = "vscreen_detector"
    private const val K_ON = "on"
    private const val K_RUNNING = "running"
    private const val K_CRASHED = "crashed"
    /** At most this many hits are kept per frame (the list is for tapping, not for counting). */
    const val MAX_HITS = 60

    data class Hit(val box: Rect, val score: Float)

    private class Engine(val env: OrtEnvironment, val session: OrtSession, val model: DetectorModels.Installed)

    @Volatile private var engine: Engine? = null
    @Volatile private var loadError: String? = null
    @Volatile var lastMs = 0L; private set
    @Volatile private var checked = false
    /** The last frame looked at, per display: a snapshot of an unchanged frame does not run the model again. */
    private val last = HashMap<Int, Pair<Long, List<Hit>?>>()

    /** [detect] for frame [version] of [displayId], reusing the answer for the same frame. */
    fun detectFrame(displayId: Int, frame: Bitmap, version: Long): List<Hit>? {
        synchronized(last) { last[displayId]?.let { if (it.first == version) return it.second } }
        val r = detect(frame)
        synchronized(last) { last[displayId] = version to r }
        return r
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The user's switch (on by default once a model is installed). */
    fun switchedOn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ON, true)
    fun setSwitchedOn(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(K_ON, on).apply(); if (!on) close() }

    /** Why the detector is not running even though a model is installed (a crash, a load error), or null. */
    fun problem(ctx: Context): String? = prefs(ctx).getString(K_CRASHED, null) ?: loadError

    /** Installed, switched on, and not disabled by a crash. */
    fun active(ctx: Context? = BackgroundOperation.appContextOrNull()): Boolean =
        ctx != null && switchedOn(ctx) && prefs(ctx).getString(K_CRASHED, null) == null && loadError == null && DetectorModels.installed(ctx) != null

    /** Called once when the service process starts: a run mark left behind means that run killed the process. */
    fun checkLastRun(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(K_RUNNING, false)) {
            AppLogger.warning(TAG, "the last detector run did not finish (native crash?): detector disabled until reinstalled")
            p.edit().putBoolean(K_RUNNING, false).putString(K_CRASHED, "last run crashed the service; reinstall the model to try again").apply()
        }
    }

    /** A new model was installed (or removed): forget the old session and any crash mark. */
    @Synchronized
    fun reset(ctx: Context) {
        close()
        loadError = null
        prefs(ctx).edit().remove(K_CRASHED).putBoolean(K_RUNNING, false).apply()
    }

    @Synchronized
    fun close() {
        engine?.let { runCatching { it.session.close() } }
        engine = null
        synchronized(last) { last.clear() }
    }

    @Synchronized
    private fun engine(ctx: Context): Engine? {
        val m = DetectorModels.installed(ctx) ?: return null
        engine?.let { if (it.model.sha256 == m.sha256) return it; close() }
        if (loadError != null) return null
        return try {
            val env = OrtEnvironment.getEnvironment()
            val s = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(max(1, min(4, Runtime.getRuntime().availableProcessors() / 2)))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }.use { env.createSession(m.file.absolutePath, it) }
            Engine(env, s, m).also { engine = it }
        } catch (t: Throwable) {
            loadError = "${t.javaClass.simpleName}: ${t.message?.take(160)}"
            AppLogger.warning(TAG, "load failed: $loadError")
            null
        }
    }

    /**
     * Tappable things on [frame] (frame pixels), best first; null = the detector is not available (not installed,
     * off, failed). Takes tens to a few hundred ms; call it off the main thread.
     */
    fun detect(frame: Bitmap, ctx: Context? = BackgroundOperation.appContextOrNull()): List<Hit>? {
        if (ctx == null) return null
        if (!checked) { checked = true; checkLastRun(ctx) }
        if (!active(ctx)) return null
        val e = engine(ctx) ?: return null
        val m = e.model
        val p = prefs(ctx)
        val t0 = SystemClock.elapsedRealtime()
        p.edit().putBoolean(K_RUNNING, true).commit()
        try {
            val lb = DetectorMath.letterbox(frame.width, frame.height, m.inW, m.inH)
            val scaled = Bitmap.createScaledBitmap(frame, lb.w, lb.h, true)
            val px = IntArray(lb.w * lb.h)
            scaled.getPixels(px, 0, lb.w, 0, 0, lb.w, lb.h)
            if (scaled !== frame) scaled.recycle()
            val plane = m.inW * m.inH
            val buf = FloatArray(3 * plane) { 114f / 255f }
            for (y in 0 until lb.h) for (x in 0 until lb.w) {
                val c = px[y * lb.w + x]; val i = y * m.inW + x
                buf[i] = ((c shr 16) and 255) / 255f; buf[plane + i] = ((c shr 8) and 255) / 255f; buf[2 * plane + i] = (c and 255) / 255f
            }
            val out = OnnxTensor.createTensor(e.env, FloatBuffer.wrap(buf), longArrayOf(1, 3, m.inH.toLong(), m.inW.toLong())).use { input ->
                e.session.run(mapOf(e.session.inputNames.first() to input)).use { r ->
                    val t = r[0] as OnnxTensor
                    val shape = t.info.shape
                    val fb = t.floatBuffer
                    val arr = FloatArray(fb.remaining()); fb.get(arr)
                    DetectorMath.decode(arr, shape[1].toInt(), shape[2].toInt(), m.threshold, lb.scale, frame.width, frame.height)
                }
            }
            lastMs = SystemClock.elapsedRealtime() - t0
            return out.take(MAX_HITS)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "detect failed: ${t.javaClass.simpleName}: ${t.message?.take(160)}")
            return null
        } finally {
            p.edit().putBoolean(K_RUNNING, false).apply()
        }
    }

    /** One line for `diag` and the settings page. */
    fun status(ctx: Context): String {
        val m = DetectorModels.installed(ctx) ?: return "not installed (optional download, Settings)"
        val prob = problem(ctx)
        return "${m.id} ${m.inW}x${m.inH} thr ${m.threshold} " + when {
            prob != null -> "OFF: $prob"
            !switchedOn(ctx) -> "switched off"
            else -> "on" + if (lastMs > 0) ", last run ${lastMs} ms" else ""
        }
    }
}

/** Pure parts of the detector (unit-tested on the JVM). */
internal object DetectorMath {
    class Letterbox(val w: Int, val h: Int, val scale: Double)

    /** The frame scaled to fit [inW]x[inH] keeping its shape (anchored top-left; the rest is padding). */
    fun letterbox(fw: Int, fh: Int, inW: Int, inH: Int): Letterbox {
        val s = min(inW.toDouble() / fw, inH.toDouble() / fh)
        return Letterbox(max(1, min(inW, (fw * s).roundToInt())), max(1, min(inH, (fh * s).roundToInt())), s)
    }

    /**
     * Raw head output [1, rows, n] (rows = 4 box + 1 score) -> hits in frame pixels: score >= [thr], greedy NMS at
     * IoU [iou], boxes clipped to the frame, best first.
     */
    fun decode(out: FloatArray, rows: Int, n: Int, thr: Float, scale: Double, fw: Int, fh: Int, iou: Float = 0.5f): List<VScreenDetector.Hit> {
        require(rows >= 5 && out.size >= rows * n)
        val cand = ArrayList<Int>()
        for (j in 0 until n) if (out[4 * n + j] >= thr) cand += j
        cand.sortByDescending { out[4 * n + it] }
        val boxes = ArrayList<FloatArray>(); val scores = ArrayList<Float>()
        for (j in cand) {
            val cx = out[j]; val cy = out[n + j]; val w = out[2 * n + j]; val h = out[3 * n + j]
            val b = floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            if (boxes.any { iou(it, b) >= iou }) continue
            boxes += b; scores += out[4 * n + j]
            if (boxes.size >= VScreenDetector.MAX_HITS) break
        }
        return boxes.mapIndexedNotNull { i, b ->
            val r = Rect((b[0] / scale).roundToInt().coerceIn(0, fw), (b[1] / scale).roundToInt().coerceIn(0, fh),
                (b[2] / scale).roundToInt().coerceIn(0, fw), (b[3] / scale).roundToInt().coerceIn(0, fh))
            if (r.width() < 4 || r.height() < 4) null else VScreenDetector.Hit(r, scores[i])
        }
    }

    fun iou(a: FloatArray, b: FloatArray): Float {
        val ix = max(0f, min(a[2], b[2]) - max(a[0], b[0])); val iy = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
        val inter = ix * iy
        val u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (u > 0) inter / u else 0f
    }
}
