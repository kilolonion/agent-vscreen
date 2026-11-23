package com.kilon.agentvscreen.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.kilon.agentvscreen.host.AppLogger
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [vscreen] Accurate on-device OCR: PaddleOCR PP-OCRv6 (tiny detection + small recognition), run with ONNX Runtime
 * inside the app. Official ONNX exports huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx and PP-OCRv6_small_rec_onnx
 * (Apache-2.0), weights stored as float16 (inputs and outputs stay float32; ONNX Runtime folds the casts at load).
 *
 * 1.4: chosen by tools/ocr-bench (rendered phone screens with known text, the same pipeline as here): tiny detection
 * finds as much text as small and is faster; tiny recognition misses every character outside its 6.9k-character
 * dictionary (names, rare words), so recognition stays small. Float16 storage reads the same as float32 on the bench.
 * 11.5 MB instead of 31 MB, and reads slightly more exactly than 1.3's small + small.
 *
 * Pipeline (mirrors PaddleOCR's DBPostProcess + CTCLabelDecode, simplified for screens,
 * where text is axis-aligned):
 *  1. det: resize so the long side is [DET_LONG] (multiples of 32), BGR, ImageNet mean/std,
 *     probability map -> threshold 0.2 -> connected components -> box score >= 0.45 ->
 *     axis-aligned box grown by the DB "unclip" distance (area * 1.4 / perimeter); the
 *     thresholds are the ones in the model's inference.yml.
 *  2. rec: crop each box from the full-resolution frame, height 48, (x/255-0.5)/0.5, BGR,
 *     batched by width; greedy CTC decode (class 0 = blank, last class = space).
 *  3. character boxes: each decoded character's CTC time steps give its centre; edges are
 *     the midpoints to the neighbours. Items are split where the gap between characters is
 *     wide (two labels inside one detected box), like the ML Kit path.
 *
 * Models live in assets/vscreen-ocr/ (fetched at build time by the porting kit).
 * Any failure is thrown to the caller, which falls back to ML Kit.
 */
internal object VScreenPaddleOcr {
    private const val TAG = "VScreenPaddleOcr"
    const val ENGINE = "pp-ocrv6 tiny-det + small-rec (fp16 weights, fp32 compute; on-device, onnxruntime)"
    private const val DIR = "vscreen-ocr"
    /** v41: 1280 (was 960) — small game text on a 2800-wide screen is lost at 960. */
    private const val DET_LONG = 1280
    private const val DET_THRESH = 0.2f
    private const val BOX_THRESH = 0.45f
    private const val UNCLIP = 1.4f
    private const val REC_H = 48
    private const val REC_MAX_W = 3200
    /** Sum of padded widths per rec batch: the output is B x (W/8) x 18710 floats. */
    private const val REC_BATCH_W = 2400
    private const val REC_MIN_SCORE = 0.5f

    private class Engine(val env: OrtEnvironment, val det: OrtSession, val rec: OrtSession, val chars: Array<String>)

    @Volatile private var engine: Engine? = null
    @Volatile private var loadError: String? = null

    fun available(context: Context): Boolean = loadError == null &&
        runCatching { context.assets.open("$DIR/det.onnx").close(); true }.getOrDefault(false)

    fun loadError(): String? = loadError
    /** Set when a model had to be built without the CPU-specific rewrite (see engine()). */
    @Volatile var optNote: String? = null; private set

    /**
     * 1.4.0-beta2: one real reading of a known picture (assets/vscreen-ocr/selftest.png) with this phone's runtime.
     * Unit tests run on a computer and cannot catch a model this phone's runtime cannot build or read; this can.
     * Returns words found, which of [EXPECT] were among them, and the time; throws when the engine fails.
     */
    val EXPECT = listOf("取消", "Search", "收藏", "已开启")
    fun selfTest(context: Context): Triple<List<String>, List<String>, Long> {
        val bmp = context.assets.open("$DIR/selftest.png").use { android.graphics.BitmapFactory.decodeStream(it) }
        try {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val words = recognize(context, bmp).map { it.text }
            return Triple(words, EXPECT.filter { w -> words.any { it.contains(w) } }, android.os.SystemClock.elapsedRealtime() - t0)
        } finally { bmp.recycle() }
    }

    /**
     * 1.4.1-beta1: unpack an asset ONNX model to noBackupFilesDir once per app version via a small streaming buffer,
     * so ONNX Runtime loads directly from a file path instead of copying megabytes into a JVM ByteArray on cold start.
     */
    internal fun modelFile(context: Context, name: String): java.io.File {
        val root = java.io.File(context.noBackupFilesDir ?: context.filesDir, DIR).apply { mkdirs() }
        val out = java.io.File(root, name)
        val stamp = java.io.File(root, "$name.ver")
        val want = com.kilon.agentvscreen.BuildConfig.VERSION_CODE.toString()
        if (out.isFile && out.length() > 0L && stamp.isFile && runCatching { stamp.readText().trim() }.getOrNull() == want) return out
        val tmp = java.io.File(root, "$name.tmp")
        context.assets.open("$DIR/$name").use { input ->
            java.io.FileOutputStream(tmp).use { output -> input.copyTo(output, 8192) }
        }
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        runCatching { stamp.writeText(want) }
        return out
    }

    @Synchronized
    private fun engine(context: Context): Engine {
        engine?.let { return it }
        loadError?.let { throw IllegalStateException(it) }
        try {
            val env = OrtEnvironment.getEnvironment()
            // 1.4.0-beta2: the full optimisation level rewrites the graph per CPU (layout to NHWC, special kernels); on
            // beta1's phone that rewrite asked for a kernel the runtime does not have. A model that cannot be built that
            // way is built again without the CPU-specific rewrite (plain kernels, the same answers), and that is logged.
            fun session(name: String): OrtSession {
                val path = modelFile(context, name).absolutePath
                fun build(level: OrtSession.SessionOptions.OptLevel): OrtSession = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(max(1, min(4, Runtime.getRuntime().availableProcessors() / 2)))
                    setOptimizationLevel(level)
                }.use { opts -> env.createSession(path, opts) }
                return try { build(OrtSession.SessionOptions.OptLevel.ALL_OPT) } catch (e: ai.onnxruntime.OrtException) {
                    AppLogger.warning(TAG, "$name at ALL_OPT failed (${e.message?.take(200)}); building at EXTENDED_OPT")
                    optNote = "$name: EXTENDED_OPT (${e.message?.take(120)})"
                    build(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
                }
            }
            val dict = context.assets.open("$DIR/dict.txt").bufferedReader(Charsets.UTF_8).use { r ->
                r.readLines().filter { it.isNotEmpty() }
            }
            val chars = (listOf("") + dict + listOf(" ")).toTypedArray()
            val det = session("det.onnx")
            val rec = try { session("rec.onnx") } catch (t: Throwable) { runCatching { det.close() }; throw t }
            return Engine(env, det, rec, chars).also { engine = it }
        } catch (t: Throwable) {
            loadError = "${t.javaClass.simpleName}: ${t.message}"
            AppLogger.warning(TAG, "PP-OCRv6 load failed: $loadError")
            throw t
        }
    }

    /** 1.4.2: the longest one accurate reading may take before the caller falls back to ML Kit. */
    internal const val TIMEOUT_MS = 15_000L
    /**
     * 1.4.2: the one reading slot. A caller takes it before a reader thread exists, and only that reader gives it back,
     * when it really ends (also after its caller gave up). So at most one reader thread and one frame copy exist,
     * however many callers come or time out, and the ONNX sessions are used by one reader.
     */
    private val slot = java.util.concurrent.Semaphore(1)
    @Volatile private var busySince = 0L
    internal const val SLOT_WAIT_MS = 3_000L

    /** A reading is running (maybe one its caller gave up on): it takes CPU from whatever reads the screen meanwhile. */
    fun busy(): Boolean = slot.availablePermits() == 0

    /**
     * Items in frame pixels. Throws on any failure. Not on the main thread.
     *
     * 1.4.2: with a deadline. The native inference has no timeout of its own, and before, one stuck reading held the
     * engine forever: every later read queued behind it and the agent's call never came back. Now the reading runs on
     * its own thread and the caller waits at most [TIMEOUT_MS], then gets an error (VScreenOcr falls back to ML Kit and
     * says so). A stuck native call cannot be stopped: its thread stays until the call returns, and while it does,
     * later callers wait at most [TIMEOUT_MS] for the slot and then fail, then recover by themselves.
     *
     * The reader works on its own copy of [frame]: a caller that timed out goes on to recycle or reuse its bitmap, and
     * the reader that is still running must not read a recycled picture (API 35 CI: "getWidth() on a recycle()'d bitmap").
     */
    fun recognize(context: Context, frame: Bitmap): List<VScreenOcr.Item> {
        // 1.4.2-beta6: a caller waits for the slot only as long as a normal reading takes ([SLOT_WAIT_MS]), not the
        // whole [TIMEOUT_MS]. A reader past that is one this CPU cannot finish in time (the cold build under emulator
        // translation took 36 s on API 35 CI); waiting 15 s for it, then giving ML Kit what was left, was the OCR_DOWN.
        if (!slot.tryAcquire(SLOT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
            error("PP-OCRv6 busy: a reading has not finished for ${(android.os.SystemClock.elapsedRealtime() - busySince) / 1000} s")
        val own: Bitmap
        val task: java.util.concurrent.FutureTask<List<VScreenOcr.Item>>
        try {
            own = frame.copy(Bitmap.Config.ARGB_8888, false) ?: error("PP-OCRv6: could not copy the frame")
            busySince = android.os.SystemClock.elapsedRealtime()
            task = java.util.concurrent.FutureTask {
                try {
                    val e = engine(context)
                    val boxes = detect(e, own)
                    if (boxes.isEmpty()) emptyList() else recognizeBoxes(e, own, boxes)
                } finally {
                    own.recycle()
                    slot.release()
                }
            }
            val callerPrio = Thread.currentThread().priority
            Thread(task, "vscreen-ocr").apply { isDaemon = true; priority = callerPrio }.start()
        } catch (t: Throwable) {
            slot.release()
            throw t
        }
        return try {
            task.get(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            AppLogger.warning(TAG, "PP-OCRv6 no result in ${TIMEOUT_MS}ms")
            error("PP-OCRv6 timed out after ${TIMEOUT_MS / 1000} s")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    // ── detection ─────────────────────────────────────────────────────────

    private class DetBox(val crop: Rect, val tight: Rect)

    private fun detect(e: Engine, frame: Bitmap): List<DetBox> {
        val w = frame.width
        val h = frame.height
        val r = min(1f, DET_LONG.toFloat() / max(w, h))
        val rw = max(32, (w * r / 32f).roundToInt() * 32)
        val rh = max(32, (h * r / 32f).roundToInt() * 32)
        val small = Bitmap.createScaledBitmap(frame, rw, rh, true)
        val px = IntArray(rw * rh)
        small.getPixels(px, 0, rw, 0, 0, rw, rh)
        if (small !== frame) small.recycle()
        val plane = rw * rh
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            // BGR order, mean/std applied per channel position as PaddleOCR does.
            input[i] = (((c and 0xFF) / 255f) - 0.485f) / 0.229f
            input[plane + i] = ((((c shr 8) and 0xFF) / 255f) - 0.456f) / 0.224f
            input[2 * plane + i] = ((((c shr 16) and 0xFF) / 255f) - 0.406f) / 0.225f
        }
        val prob = FloatArray(plane)
        OnnxTensor.createTensor(e.env, FloatBuffer.wrap(input), longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { t ->
            e.det.run(mapOf(e.det.inputNames.first() to t)).use { res ->
                val fb = (res.get(0) as OnnxTensor).floatBuffer
                fb.get(prob, 0, min(plane, fb.remaining()))
            }
        }
        // Connected components of the thresholded map (4-neighbour flood fill).
        val label = IntArray(plane)
        val queue = IntArray(plane)
        val sx = w.toFloat() / rw
        val sy = h.toFloat() / rh
        val out = ArrayList<DetBox>()
        var n = 0
        for (start in 0 until plane) {
            if (label[start] != 0 || prob[start] <= DET_THRESH) continue
            n++
            var qh = 0
            var qt = 0
            queue[qt++] = start
            label[start] = n
            var x0 = start % rw; var x1 = x0; var y0 = start / rw; var y1 = y0
            var sum = 0.0
            while (qh < qt) {
                val p = queue[qh++]
                val px0 = p % rw
                val py0 = p / rw
                sum += prob[p]
                if (px0 < x0) x0 = px0; if (px0 > x1) x1 = px0
                if (py0 < y0) y0 = py0; if (py0 > y1) y1 = py0
                if (px0 > 0) { val q = p - 1; if (label[q] == 0 && prob[q] > DET_THRESH) { label[q] = n; queue[qt++] = q } }
                if (px0 < rw - 1) { val q = p + 1; if (label[q] == 0 && prob[q] > DET_THRESH) { label[q] = n; queue[qt++] = q } }
                if (py0 > 0) { val q = p - rw; if (label[q] == 0 && prob[q] > DET_THRESH) { label[q] = n; queue[qt++] = q } }
                if (py0 < rh - 1) { val q = p + rw; if (label[q] == 0 && prob[q] > DET_THRESH) { label[q] = n; queue[qt++] = q } }
            }
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (min(bw, bh) < 3 || sum / qt < BOX_THRESH) continue
            val d = bw * bh * UNCLIP / (2f * (bw + bh))
            val crop = Rect(
                ((x0 - d) * sx).toInt().coerceIn(0, w - 1), ((y0 - d) * sy).toInt().coerceIn(0, h - 1),
                ((x1 + 1 + d) * sx).toInt().coerceIn(1, w), ((y1 + 1 + d) * sy).toInt().coerceIn(1, h),
            )
            // The shrunk text kernel grown by half the unclip distance is close to the ink box.
            val tight = Rect(
                ((x0 - d * 0.5f) * sx).toInt().coerceIn(0, w - 1), ((y0 - d * 0.5f) * sy).toInt().coerceIn(0, h - 1),
                ((x1 + 1 + d * 0.5f) * sx).toInt().coerceIn(1, w), ((y1 + 1 + d * 0.5f) * sy).toInt().coerceIn(1, h),
            )
            if (crop.width() >= 2 && crop.height() >= 2) out += DetBox(crop, tight)
            if (out.size >= 1000) break
        }
        return out
    }

    // ── recognition ───────────────────────────────────────────────────────

    private class Crop(val det: DetBox, val data: FloatArray, val width: Int)

    private fun recognizeBoxes(e: Engine, frame: Bitmap, boxes: List<DetBox>): List<VScreenOcr.Item> {
        val crops = boxes.map { b ->
            val c = b.crop
            val nw = ceil(REC_H * c.width().toDouble() / c.height()).toInt().coerceIn(8, REC_MAX_W)
            val sub = Bitmap.createBitmap(frame, c.left, c.top, c.width(), c.height())
            val scaled = Bitmap.createScaledBitmap(sub, nw, REC_H, true)
            val px = IntArray(nw * REC_H)
            scaled.getPixels(px, 0, nw, 0, 0, nw, REC_H)
            if (scaled !== sub) scaled.recycle()
            if (sub !== frame) sub.recycle()
            val plane = nw * REC_H
            val data = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = px[i]
                data[i] = (p and 0xFF) / 127.5f - 1f
                data[plane + i] = ((p shr 8) and 0xFF) / 127.5f - 1f
                data[2 * plane + i] = ((p shr 16) and 0xFF) / 127.5f - 1f
            }
            Crop(b, data, nw)
        }
        val order = crops.indices.sortedBy { crops[it].width }
        val items = ArrayList<VScreenOcr.Item>()
        var k = 0
        while (k < order.size) {
            // Widths only grow along [order]: take crops while count x widest stays in budget.
            var end = k + 1
            while (end < order.size && end - k < 8 && (end - k + 1) * crops[order[end]].width <= REC_BATCH_W) end++
            val batch = order.subList(k, end).map { crops[it] }
            k = end
            val mw = batch.maxOf { it.width }
            val input = FloatArray(batch.size * 3 * REC_H * mw)
            batch.forEachIndexed { bi, c ->
                for (ch in 0 until 3) for (y in 0 until REC_H) {
                    System.arraycopy(c.data, ch * REC_H * c.width + y * c.width, input,
                        ((bi * 3 + ch) * REC_H + y) * mw, c.width)
                }
            }
            OnnxTensor.createTensor(e.env, FloatBuffer.wrap(input),
                longArrayOf(batch.size.toLong(), 3, REC_H.toLong(), mw.toLong())).use { t ->
                e.rec.run(mapOf(e.rec.inputNames.first() to t)).use { res ->
                    val out = res.get(0) as OnnxTensor
                    val shape = out.info.shape
                    val steps = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val buf = out.floatBuffer
                    batch.forEachIndexed { bi, c ->
                        items += decode(e, buf, bi * steps * classes, steps, classes, mw, c)
                    }
                }
            }
        }
        items.sortWith(compareBy<VScreenOcr.Item>({ it.box.top / 8 }, { it.box.left }))
        return items
    }

    private class Ch(val text: String, val centre: Float, val start: Float, val end: Float, val score: Float)

    /** Greedy CTC decode of one crop, split into items with character boxes. */
    private fun decode(e: Engine, buf: FloatBuffer, base: Int, steps: Int, classes: Int, mw: Int, c: Crop): List<VScreenOcr.Item> {
        val stride = mw.toFloat() / steps
        val usable = min(steps, ceil(c.width / stride).toInt() + 1)
        val chars = ArrayList<Ch>()
        var prev = 0
        var runStart = -1
        var runScore = 0f
        var runLen = 0
        fun close(tEnd: Int) {
            if (prev != 0 && runStart >= 0 && prev < e.chars.size) {
                val s = runStart * stride
                val en = tEnd * stride
                chars += Ch(e.chars[prev], (s + en) / 2f, s, en, runScore / runLen)
            }
        }
        for (t in 0 until usable) {
            var best = 0
            var bestV = -Float.MAX_VALUE
            val off = base + t * classes
            for (ci in 0 until classes) {
                val v = buf.get(off + ci)
                if (v > bestV) { bestV = v; best = ci }
            }
            if (best != prev) {
                close(t)
                prev = best
                runStart = t; runScore = 0f; runLen = 0
            }
            runScore += bestV; runLen++
        }
        close(usable)
        if (chars.isEmpty()) return emptyList()
        if (chars.map { it.score }.average() < REC_MIN_SCORE) return emptyList()

        // Crop pixels -> frame pixels.
        val crop = c.det.crop
        val kx = crop.width().toFloat() / c.width
        val tight = c.det.tight
        val top = tight.top
        val bottom = tight.bottom
        val textH = (bottom - top).coerceAtLeast(1)
        val ink = chars.filter { it.text.isNotBlank() }
        if (ink.isEmpty()) return emptyList()
        val centres = ink.map { crop.left + it.centre * kx }
        val gaps = centres.zipWithNext { a, b -> b - a }
        val pitch = if (gaps.isEmpty()) textH * 0.8f else gaps.sorted()[gaps.size / 2]
        val halfEdge = min(pitch, textH.toFloat()) / 2f
        val glyphs = ink.indices.map { i ->
            val l = if (i == 0) centres[i] - halfEdge else (centres[i - 1] + centres[i]) / 2f
            val r = if (i == ink.size - 1) centres[i] + halfEdge else (centres[i] + centres[i + 1]) / 2f
            VScreenOcr.Glyph(ink[i].text, Rect(max(crop.left.toFloat(), l).toInt(), top, min(crop.right.toFloat(), r).toInt(), bottom))
        }
        // Split at wide gaps (two labels in one detected box); spaces stay inside an item.
        val out = ArrayList<VScreenOcr.Item>()
        var from = 0
        fun flush(to: Int) {
            if (to <= from) return
            val part = ink.subList(from, to)
            val sb = StringBuilder()
            for (i in from until to) {
                if (i > from) {
                    // A decoded space between these two characters?
                    val a = ink[i - 1]
                    val b = ink[i]
                    if (chars.any { it.text.isBlank() && it.centre > a.centre && it.centre < b.centre }) sb.append(' ')
                }
                sb.append(ink[i].text)
            }
            val gs = glyphs.subList(from, to)
            val box = Rect(gs.first().box)
            gs.forEach { box.union(it.box) }
            if (part.isNotEmpty()) out += VScreenOcr.Item(sb.toString(), box, gs.toList())
            from = to
        }
        for (i in 1 until ink.size) {
            // Edge gap ~ centre gap - pitch; split when it exceeds ~0.5 x the ink height (ML Kit path: 0.6 x).
            if (centres[i] - centres[i - 1] > pitch + 0.52f * textH) flush(i)
        }
        flush(ink.size)
        return out
    }
}
