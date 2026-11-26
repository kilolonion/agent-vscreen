package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.kilon.agentvscreen.host.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Passive subscribers don't change the cadence. A direct request bypasses it once, without falsifying frame age. */
internal class CaptureThrottle {
    private val urgent = AtomicBoolean(false)
    private var lastStartMs: Long? = null // pump thread only

    fun request() { urgent.set(true) }
    fun isUrgent(): Boolean = urgent.get()

    fun delayMs(now: Long, intervalMs: Long): Long = when {
        urgent.get() -> 0L
        else -> lastStartMs?.let { (intervalMs - (now - it)).coerceAtLeast(0) } ?: 0L
    }

    fun onConvert(now: Long) {
        urgent.getAndSet(false)
        lastStartMs = now
    }
}

/**
 * [vscreen] One agent-owned virtual display plus its frame pump.
 *
 * Surface contract (learned from the OpenMinis-Linux vscreen work): the
 * display is created WITH an ImageReader surface and keeps it for its whole
 * lifetime. A surface-less virtual display is put to sleep by WMS, which
 * hides its windows and drops injected input.
 *
 * Frame contract (the reason this class exists instead of reading the
 * reader only on demand): the reader is drained on EVERY frame by a
 * dedicated HandlerThread, whether or not anybody is looking. Holding
 * images in the reader's queue stalls the producer, so the display stops
 * updating and eventually sleeps. Collapsing the chat preview therefore only
 * stops the UI from redrawing; it never stops this pump. The pump converts
 * at most one frame per [minFrameIntervalMs] into a Bitmap that the
 * preview and the agent's screenshots share.
 *
 * Flags: PUBLIC | OWN_CONTENT_ONLY. PUBLIC lets the shell (`am start
 * --display`) put other apps' activities on it and lets the accessibility
 * framework track its windows; OWN_CONTENT_ONLY keeps it from mirroring the
 * user's screen and needs no CAPTURE_VIDEO_OUTPUT permission. If the ROM
 * refuses a public display we retry private and say so in [note].
 */
class VScreenDisplay private constructor(
    val displayId: Int,
    /** Size of the surface (natural orientation). The agent works in [width] x [height]. */
    val physWidth: Int,
    val physHeight: Int,
    val dpi: Int,
    /** Monotonic per-process counter; a late action aimed at an older screen is refused. */
    val generation: Long,
    val isPublic: Boolean,
    private val virtualDisplay: VirtualDisplay?,
    private val reader: ImageReader,
    private val thread: HandlerThread,
    /** Created by the Shizuku shell service: owned by the shell uid, so `am start --display` may use it. */
    val shellOwned: Boolean = false,
    /** VIRTUAL_DISPLAY_FLAG_TRUSTED was granted (Android 13+ shell display). */
    val trusted: Boolean = false,
    /** VIRTUAL_DISPLAY_FLAG_* actually granted (0 = unknown / app-owned fallback). */
    val flags: Int = 0,
) {
    // ── orientation ─────────────────────────────────────────────────────────
    // The display is created ROTATES_WITH_CONTENT: when a landscape app (a game) asks for
    // landscape, the LOGICAL display rotates (input and the app use 2800x1264) while the surface
    // stays 1264x2800, so the picture arrives turned by 90 degrees. Every frame is turned back
    // here, so OCR, vision, screenshots, the preview and all coordinates are in the logical,
    // upright space, which is also the space injected input uses. Nobody does rotation maths.

    /** Logical rotation of the display (Surface.ROTATION_*), from DisplayManager or the shell. */
    @Volatile var rotation: Int = 0
        private set
    /** Calibration: the default turn for this rotation came out upside down (text check). */
    @Volatile var flip180: Boolean = false
    /** Calibration: this system letterboxes the rotated content upright instead of turning it (no turn needed). */
    @Volatile var noTurn: Boolean = false
    /** Fixed turn in degrees set by `screen rotate`, or null for automatic. */
    @Volatile var turnOverride: Int? = null
    /** Rotations whose direction has been checked against on-screen text. */
    val calibrated = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** Called (any thread) when the logical size changes. */
    @Volatile var onGeometryChanged: (() -> Unit)? = null

    /** Clockwise degrees applied to each surface frame to make it upright. */
    val bufferTurn: Int get() {
        turnOverride?.let { return ((it % 360) + 360) % 360 }
        if (noTurn) return 0
        val base = when (rotation) { 1 -> 270; 2 -> 180; 3 -> 90; else -> 0 }
        return if (flip180 && base != 0) (base + 180) % 360 else base
    }
    /** Logical (agent / input) width and height. */
    val width: Int get() = if (bufferTurn % 180 != 0) physHeight else physWidth
    val height: Int get() = if (bufferTurn % 180 != 0) physWidth else physHeight

    /** Size of the display as the system and injected input see it (rotates with the content). 0 = unknown. */
    @Volatile var inputWidth: Int = 0
        private set
    @Volatile var inputHeight: Int = 0
        private set

    /** [r] = Surface.ROTATION_*, [lw]x[lh] = the logical size the system reports (0 = derive from [r]). */
    fun setRotation(r: Int, lw: Int = 0, lh: Int = 0) {
        val n = ((r % 4) + 4) % 4
        val nw = if (lw > 0) lw else if (n % 2 == 1) physHeight else physWidth
        val nh = if (lh > 0) lh else if (n % 2 == 1) physWidth else physHeight
        if (n == rotation && nw == inputWidth && nh == inputHeight) return
        val oldW = width; val oldR = rotation
        rotation = n; inputWidth = nw; inputHeight = nh
        if (n != oldR) requestFreshFrame()
        if (width != oldW || n != oldR) onGeometryChanged?.invoke()
    }

    /**
     * Frame (agent) pixel -> input pixel. Identity when the upright frame and the logical display
     * have the same size (the normal case); otherwise the logical display is fitted into the
     * frame (letterbox) and the point is mapped back through that fit.
     */
    fun toInput(x: Float, y: Float): Pair<Float, Float> {
        val iw = inputWidth.takeIf { it > 0 } ?: width
        val ih = inputHeight.takeIf { it > 0 } ?: height
        val fw = width; val fh = height
        if (iw == fw && ih == fh) return x to y
        val s = minOf(fw.toFloat() / iw, fh.toFloat() / ih)
        val ox = (fw - iw * s) / 2f; val oy = (fh - ih * s) / 2f
        return ((x - ox) / s).coerceIn(0f, iw - 1f) to ((y - oy) / s).coerceIn(0f, ih - 1f)
    }

    /** 1.4.0-beta13: input pixel -> frame (agent) pixel, the inverse of [toInput], for bounds the system reports. */
    fun toFrame(r: android.graphics.Rect): android.graphics.Rect {
        val iw = inputWidth.takeIf { it > 0 } ?: width
        val ih = inputHeight.takeIf { it > 0 } ?: height
        val fw = width; val fh = height
        if (iw == fw && ih == fh) return r
        val s = minOf(fw.toFloat() / iw, fh.toFloat() / ih)
        val ox = (fw - iw * s) / 2f; val oy = (fh - ih * s) / 2f
        return android.graphics.Rect((r.left * s + ox).toInt(), (r.top * s + oy).toInt(), (r.right * s + ox).toInt(), (r.bottom * s + oy).toInt())
    }

    /** Human description of frame space vs input space, for results and `screen info`. */
    fun geometryJson(): org.json.JSONObject {
        val iw = inputWidth.takeIf { it > 0 } ?: width
        val ih = inputHeight.takeIf { it > 0 } ?: height
        val identity = iw == width && ih == height
        return org.json.JSONObject()
            .put("display_rotation", rotation * 90)
            .put("surface", "${physWidth}x$physHeight")
            .put("frame_space", tr("${width}x$height（已转正，read/look/截图/tap 的坐标都在这里）", "${width}x$height (upright; read/look/screenshot/tap coordinates are all in this space)"))
            .put("input_space", tr("${iw}x$ih（系统注入触摸用的逻辑坐标）", "${iw}x$ih (logical coordinates the system uses for injected touches)"))
            .put("frame_turn_deg", bufferTurn)
            .put("turn_source", if (turnOverride != null) tr("screen rotate 手动", "screen rotate (manual)") else if (flip180) tr("自动（文字校准后翻转 180°）", "auto (flipped 180° after text calibration)") else tr("自动", "auto"))
            .put("transform", if (identity) tr("identity：画面坐标 = 输入坐标，工具不需要也不会再换算", "identity: picture coordinates = input coordinates, nothing is converted")
                else tr("letterbox：逻辑屏 ${iw}x$ih 缩放后居中放在 ${width}x$height 画面里，工具自动逆变换", "letterbox: the ${iw}x$ih logical screen is scaled and centered in the ${width}x$height picture; the tool converts back automatically"))
    }

    private var listener: DisplayManager.DisplayListener? = null

    /** Follow rotation through DisplayManager (works when the display is PUBLIC). False if not visible. */
    fun watchRotation(context: Context): Boolean {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
        val disp = runCatching { dm.getDisplay(displayId) }.getOrNull() ?: return false
        applyDisplay(disp)
        if (listener == null) {
            val l = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(id: Int) {}
                override fun onDisplayRemoved(id: Int) {}
                override fun onDisplayChanged(id: Int) {
                    if (id == displayId) runCatching { dm.getDisplay(id) }.getOrNull()?.let { applyDisplay(it) }
                }
            }
            listener = l
            com.kilon.agentvscreen.host.quiet("VScreenDisplay:158") { dm.registerDisplayListener(l, pumpHandler ?: Handler(thread.looper)) }
            watchContext = context.applicationContext
        }
        return true
    }
    private var watchContext: Context? = null

    private fun applyDisplay(disp: android.view.Display) {
        val pt = android.graphics.Point()
        @Suppress("DEPRECATION") runCatching { disp.getRealSize(pt) }
        setRotation(disp.rotation, pt.x, pt.y)
    }

    /** The display has its own input focus (Android 14+ OWN_FOCUS): key events reach it without stealing focus. */
    val ownFocus: Boolean get() = flags and (1 shl 14) != 0

    @Volatile private var latestRaw: Bitmap? = null
    /** Turn applied to [latestRaw]. */
    @Volatile private var latestTurn = 0
    /**
     * The newest frame, turned the way [bufferTurn] says NOW: when the turn changes while the
     * screen is static (no new frame arrives) the kept frame is re-turned here, once.
     */
    private val latest: Bitmap?
        get() {
            val b = latestRaw ?: return null
            val want = bufferTurn
            if (want == latestTurn) return b
            synchronized(frameLock) {
                if (released) return null
                val cur = latestRaw ?: return null
                if (want == latestTurn) return cur
                val m = android.graphics.Matrix().apply { postRotate(((want - latestTurn + 360) % 360).toFloat()) }
                val t = runCatching { Bitmap.createBitmap(cur, 0, 0, cur.width, cur.height, m, false) }.getOrNull() ?: return cur
                latestRaw = t; latestTurn = want
                _frameVersion.value = _frameVersion.value + 1
                return t
            }
        }
    private val throttle = CaptureThrottle()
    @Volatile private var latestCaptureMs = -1L
    /** Elapsed-realtime when [latestFrame] was captured (-1 = none yet). */
    val latestCaptureAtMs: Long get() = latestCaptureMs
    @Volatile private var fullConversions = 0L
    @Volatile var released = false
        private set

    /** Frame counter; the preview collects it to know when to redraw. */
    private val _frameVersion = MutableStateFlow(0L)
    val frameVersion: StateFlow<Long> = _frameVersion.asStateFlow()

    @Volatile var minFrameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS

    /** Until this elapsed-realtime, frames are converted at ~30 fps (held fingers, watch, fast reactions). */
    @Volatile var boostUntilMs: Long = 0L
    fun boost(ms: Long) { boostUntilMs = maxOf(boostUntilMs, SystemClock.elapsedRealtime() + ms) }
    private val frameInterval: Long get() = if (SystemClock.elapsedRealtime() < boostUntilMs) minOf(33L, minFrameIntervalMs) else minFrameIntervalMs

    /** Elapsed-realtime of the last frame the producer delivered, 0 = none yet. */
    @Volatile var lastFrameAtMs: Long = 0L
        private set

    /** Live single-point mean color watch sampled directly from each incoming ImageReader frame with zero allocation. */
    internal class ColorWatch(val x: Int, val y: Int, val radius: Int = 2) {
        @Volatile var color: Int = 0
        @Volatile var sampledAtMs: Long = -1L
        @Volatile var samples: Int = 0
        @Volatile var lastImgAgeMs: Long = -1L
        @Volatile var maxImgAgeMs: Long = -1L
    }

    /**
     * 2.0.0-beta5 (5.7 #1): lightweight color-fast mode during pure-color `press … hold`.
     * Samples [ColorWatch] at native fps without holding pending ImageReader buffers, while still refreshing
     * preview and full-frame conversion (and thus `VScreenRecorder`) at least every [COLOR_FAST_INTERVAL_MS] (500ms).
     */
    @Volatile var colorFastMode: Boolean = false
    private var lastColorFastFrameMs: Long = 0L
    private val colorFastRecLock = Any()
    private var colorFastRecFrame: Pair<Bitmap, Long>? = null
    private var colorFastRecSeq: Long = 0L

    /** Drain the latest lightweight 480px thumbnail produced during [colorFastMode] for [VScreenRecorder]; caller recycles. */
    internal fun takeColorFastRecordFrame(): Pair<Bitmap, Long>? = synchronized(colorFastRecLock) {
        colorFastRecFrame.also { colorFastRecFrame = null }
    }

    private val colorWatches = java.util.concurrent.CopyOnWriteArrayList<ColorWatch>()
    private val colorSignal = Object()
    @Volatile private var colorSampleSeq = 0L
    internal val latestColorSampleSeq: Long get() = colorSampleSeq

    internal fun watchColor(x: Int, y: Int, radius: Int = 2): ColorWatch {
        val w = ColorWatch(x, y, radius)
        colorWatches.add(w)
        return w
    }

    internal fun unwatchColor(w: ColorWatch) {
        colorWatches.remove(w)
    }

    /** Wait up to [timeoutMs] for a live color sample newer than [afterSeq]; wakes immediately on the next frame. */
    internal fun awaitColorSample(afterSeq: Long, timeoutMs: Long): Long {
        if (colorSampleSeq > afterSeq || timeoutMs <= 0L) return colorSampleSeq
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        synchronized(colorSignal) {
            while (!released && colorSampleSeq <= afterSeq) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0L) break
                colorSignal.wait(left)
            }
            return colorSampleSeq
        }
    }

    /** Pump thread only: sample all active [colorWatches] directly from [img]'s raw RGBA buffer (zero allocations). */
    private fun sampleColors(img: android.media.Image, capturedAt: Long) {
        if (released || colorWatches.isEmpty()) return
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val turn = bufferTurn
            val imgAge = (capturedAt - img.timestamp / 1_000_000L).coerceAtLeast(0L)
            var updated = false
            for (w in colorWatches) {
                val c = sampleRgbaColor(buf, img.width, img.height, rowStride, pixelStride, turn, w.x, w.y, w.radius)
                if (c != null) {
                    w.color = c
                    w.lastImgAgeMs = imgAge
                    if (imgAge > w.maxImgAgeMs) w.maxImgAgeMs = imgAge
                    w.samples = w.samples + 1
                    w.sampledAtMs = capturedAt
                    updated = true
                }
            }
            if (updated) {
                synchronized(colorSignal) {
                    colorSampleSeq++
                    colorSignal.notifyAll()
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "sampleColors failed on display $displayId: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Pump-thread only: the newest frame skipped by the throttle, converted when the throttle opens. */
    private var pending: android.media.Image? = null
    private var pumpHandler: Handler? = null
    private var pendingCaptureMs = 0L
    private var pendingWasPreviewed = false
    private val flushPending = Runnable {
        val p = pending ?: return@Runnable
        pending = null
        if (colorFastMode && latestRaw != null) {
            pendingWasPreviewed = false
            com.kilon.agentvscreen.host.quiet("VScreenDisplay:fast-flush") { p.close() }
            return@Runnable
        }
        // The trailing frame is also the picture the viewer must end on.
        val copiedScratch = if (previewIntervalMs != Long.MAX_VALUE && !pendingWasPreviewed) drawPreview(p) else null
        pendingWasPreviewed = false
        convert(p, pendingCaptureMs, preCopied = copiedScratch)
    }

    private fun startPump() {
        val h = Handler(thread.looper).also { pumpHandler = it }
        reader.setOnImageAvailableListener({ r ->
            // Always acquire, and never hold more than one image (the pending one):
            // the queue must never fill up.
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val dropForHeat = com.kilon.agentvscreen.host.ThermalGuard.ocrPaused ||
                (com.kilon.agentvscreen.host.ThermalGuard.limited && !throttle.isUrgent() && latestRaw != null)
            if (dropForHeat || (VScreenAwake.screenOff && !BackgroundOperation.needsAwake())) {
                img.close(); return@setOnImageAvailableListener
            }
            val now = SystemClock.elapsedRealtime()
            lastFrameAtMs = now
            if (colorWatches.isNotEmpty()) sampleColors(img, now)
            if (colorFastMode && latestRaw != null) {
                // 5.7 #1: never hold a pending ImageReader buffer or allocate a 10MB full-res LOS bitmap during
                // pure-color hold; sample ColorWatch on every frame, and every 500ms update preview (reusing scratch)
                // plus a lightweight 480px thumbnail for VScreenRecorder on the low-priority perception thread.
                h.removeCallbacks(flushPending)
                com.kilon.agentvscreen.host.quiet("VScreenDisplay:fast-pending") { pending?.close() }
                pending = null
                pendingWasPreviewed = false
                val ref = maxOf(lastColorFastFrameMs, latestCaptureAtMs)
                if (!colorFastFrameDue(now, ref)) {
                    com.kilon.agentvscreen.host.quiet("VScreenDisplay:fast-close") { img.close() }
                    return@setOnImageAvailableListener
                }
                lastColorFastFrameMs = now
                val iw = img.width
                val ih = img.height
                val turn = bufferTurn
                val sc = runCatching { copyToScratch(img) }.getOrNull()
                com.kilon.agentvscreen.host.quiet("VScreenDisplay:fast-close") { img.close() }
                if (sc != null && !sc.isRecycled) {
                    if (previewIntervalMs != Long.MAX_VALUE) {
                        runCatching { paintPreview(sc, iw, ih, turn) }
                    }
                    val small = runCatching { scaleForRecorder(sc, iw, ih, turn) }.getOrNull()
                    if (small != null) {
                        val recVer = _frameVersion.value * 1_000L + (++colorFastRecSeq)
                        val old = synchronized(colorFastRecLock) {
                            colorFastRecFrame.also { colorFastRecFrame = small to recVer }
                        }
                        old?.first?.recycle()
                    }
                }
                return@setOnImageAvailableListener
            }
            val previewBefore = _previewVersion.value
            val copiedScratch = if (now - lastPreviewMs >= previewIntervalMs) drawPreview(img) else null
            val previewed = _previewVersion.value != previewBefore
            val wait = throttle.delayMs(now, frameInterval)
            if (wait > 0 && latestRaw != null) {
                // Throttled. Keep this frame as the trailing one instead of dropping it:
                // otherwise the LAST frame of an animation (a list snapping back, a page
                // sliding in) can be lost and a static screen keeps showing a mid-animation
                // picture (e.g. two stacked title bars) until something else redraws.
                com.kilon.agentvscreen.host.quiet("VScreenDisplay:238") { pending?.close() }
                pending = img
                pendingCaptureMs = now
                pendingWasPreviewed = previewed
                h.removeCallbacks(flushPending)
                h.postDelayed(flushPending, wait)
                return@setOnImageAvailableListener
            }
            h.removeCallbacks(flushPending)
            com.kilon.agentvscreen.host.quiet("VScreenDisplay:245") { pending?.close() }
            pending = null
            pendingWasPreviewed = false
            convert(img, now, preCopied = copiedScratch)
        }, h)
    }

    /** Pump thread only: copy [img]'s raw RGBA plane into the reused pump-local [scratch] bitmap. */
    private fun copyToScratch(img: android.media.Image): Bitmap {
        val plane = img.planes[0]
        val rowPx = plane.rowStride / plane.pixelStride
        var sc = scratch
        if (sc == null || sc.isRecycled || sc.width != rowPx || sc.height != img.height) {
            sc?.recycle()
            sc = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
            scratch = sc
        }
        val buf = plane.buffer
        buf.rewind()
        sc.copyPixelsFromBuffer(buf)
        buf.rewind()
        return sc
    }

    /** Convert [img] into the shared bitmap and close it. Pump thread only. */
    private fun convert(img: android.media.Image, capturedAt: Long, preCopied: Bitmap? = null) {
        try {
            if (released) return
            throttle.onConvert(SystemClock.elapsedRealtime())
            val plane = img.planes[0]
            val rowPx = plane.rowStride / plane.pixelStride
            val turn = bufferTurn
            val exact: Bitmap = if (rowPx == img.width && turn == 0 && preCopied == null) {
                Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888).also {
                    val buf = plane.buffer
                    buf.rewind()
                    it.copyPixelsFromBuffer(buf)
                }
            } else {
                val src = if (preCopied != null && !preCopied.isRecycled) preCopied else copyToScratch(img)
                if (turn == 0) {
                    Bitmap.createBitmap(src, 0, 0, img.width, img.height)
                } else {
                    val m = android.graphics.Matrix().apply { postRotate(turn.toFloat()) }
                    Bitmap.createBitmap(src, 0, 0, img.width, img.height, m, false)
                }
            }
            // The old bitmap is NOT recycled: a preview frame may still be
            // drawing it. Letting the GC reclaim it is the safe choice.
            synchronized(frameLock) {
                if (released) return
                latestRaw = exact
                latestTurn = turn
                fullConversions++
                latestCaptureMs = capturedAt
                _frameVersion.value = _frameVersion.value + 1
            }
            synchronized(frameSignal) { frameSignal.notifyAll() }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "frame convert failed on display $displayId: ${t.message}")
        } finally {
            com.kilon.agentvscreen.host.quiet("VScreenDisplay:282") { img.close() }
        }
    }

    private val frameLock = Any()
    private val frameSignal = Object()

    // ── 1.2.0-beta2: the picture for the user's eyes (floating window, full-screen page) ─────────────
    //
    // The agent's frames are full-size copies made at most every 120 ms; the window used to show those, so it moved
    // at 8 pictures a second and every one was a full-screen texture upload. The viewer now has its own, light path:
    // while someone is looking, each incoming frame (at the viewer's rate) is copied into a reused scratch bitmap and
    // drawn, scaled to the size it is shown at, into one of three reused small bitmaps. No allocation per frame, and
    // the agent's frames and their timing are untouched.

    /** Milliseconds between viewer pictures; Long.MAX_VALUE = nobody is looking. */
    @Volatile var previewIntervalMs: Long = Long.MAX_VALUE
        private set
    @Volatile private var previewLongSide = 0
    private val previewRing = arrayOfNulls<Bitmap>(3)
    private var previewAt = 0
    @Volatile private var previewBitmap: Bitmap? = null
    @Volatile private var lastPreviewMs = 0L
    private var scratch: Bitmap? = null
    private val previewPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val _previewVersion = MutableStateFlow(0L)
    val previewVersion: StateFlow<Long> = _previewVersion.asStateFlow()

    private val viewers = mutableMapOf<Any, Pair<Long, Int>>()
    // Pump-owned. The UI/RenderThread may still hold a published bitmap, so drop references only.
    private fun dropPreviewBuffers() {
        previewBitmap = null
        previewRing.fill(null)
        scratch?.recycle() // Scratch never leaves the pump.
        scratch = null
    }
    private val refreshPreview = Runnable {
        if (!released && previewLongSide > 0) latest?.let { paintPreview(it, it.width, it.height, 0) }
    }
    private val trimPreview = Runnable { if (previewLongSide == 0) dropPreviewBuffers() }

    @Synchronized internal fun frameWork(): String =
        "${physWidth}x$physHeight, source_latest_ms=${lastFrameAtMs.takeIf { it != 0L }?.let { SystemClock.elapsedRealtime() - it } ?: -1}, " +
            "full_conversions=$fullConversions, preview_frames=${_previewVersion.value}, viewers=${viewers.size}"

    @Synchronized fun setPreview(viewer: Any, intervalMs: Long, longSidePx: Int) {
        if (released) return
        val request = intervalMs to longSidePx
        if (viewers.put(viewer, request) != request) updatePreview()
    }

    @Synchronized fun clearPreview(viewer: Any) {
        if (viewers.remove(viewer) != null) updatePreview()
    }

    private fun updatePreview() {
        val longSidePx = viewers.values.maxOfOrNull { it.second } ?: 0
        val intervalMs = viewers.values.minOfOrNull { it.first } ?: Long.MAX_VALUE
        val resized = longSidePx != previewLongSide
        val first = previewIntervalMs == Long.MAX_VALUE
        previewLongSide = longSidePx
        previewIntervalMs = intervalMs.coerceAtLeast(16L)
        // A still screen sends no frames: paint the viewer's picture from the frame we already have.
        pumpHandler?.let { h ->
            h.removeCallbacks(trimPreview)
            if (resized || first) {
                h.removeCallbacks(refreshPreview)
                if (longSidePx > 0) h.post(refreshPreview)
            }
            if (longSidePx == 0) h.postDelayed(trimPreview, 750L)
        }
    }

    /** The viewer's picture (scaled to the size it is shown at); null before the first one. Do not recycle it. */
    fun previewFrame(): Bitmap? = previewBitmap

    /** Pump thread only. Returns the populated [scratch] bitmap so [convert] can reuse it without re-copying. */
    private fun drawPreview(img: android.media.Image): Bitmap? {
        return try {
            if (released) return null
            val sc = copyToScratch(img)
            paintPreview(sc, img.width, img.height, bufferTurn)
            sc
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "preview frame failed on display $displayId: ${t.message}")
            null
        }
    }

    /** Pump thread only: [src]'s top-left [sw]×[sh], turned by [turn], scaled to the viewer's size. */
    private fun paintPreview(src: Bitmap, sw: Int, sh: Int, turn: Int) {
        val want = previewLongSide
        if (want <= 0 || sw <= 0 || sh <= 0 || src.isRecycled) return
        val k = minOf(1f, want.toFloat() / maxOf(sw, sh))
        val turned = turn % 180 != 0
        val ow = ((if (turned) sh else sw) * k).toInt().coerceAtLeast(2)
        val oh = ((if (turned) sw else sh) * k).toInt().coerceAtLeast(2)
        previewAt = (previewAt + 1) % previewRing.size
        var b = previewRing[previewAt]
        if (b == null || b.width != ow || b.height != oh) {
            b = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { it.setHasAlpha(false) }
            previewRing[previewAt] = b
        }
        val m = android.graphics.Matrix().apply { setScale(ow.toFloat() / (if (turned) sh else sw), oh.toFloat() / (if (turned) sw else sh)) }
        if (turn != 0) {
            m.setScale(k, k); m.postRotate(turn.toFloat())
            val r = android.graphics.RectF(0f, 0f, sw.toFloat(), sh.toFloat()); m.mapRect(r); m.postTranslate(-r.left, -r.top)
        }
        val c = android.graphics.Canvas(b!!)
        c.concat(m)
        val box = android.graphics.Rect(0, 0, sw, sh)
        c.drawBitmap(src, box, box, previewPaint)
        previewBitmap = b
        lastPreviewMs = SystemClock.elapsedRealtime()
        _previewVersion.value = _previewVersion.value + 1
    }

    /** Pump thread only: scale [src]'s top-left [sw]×[sh] (rotated by [turn]) down to [VScreenRecorder.MAX_SIDE]. */
    private fun scaleForRecorder(src: Bitmap, sw: Int, sh: Int, turn: Int): Bitmap? {
        if (sw <= 0 || sh <= 0 || src.isRecycled) return null
        val k = minOf(1f, VScreenRecorder.MAX_SIDE.toFloat() / maxOf(sw, sh))
        val turned = turn % 180 != 0
        val ow = ((if (turned) sh else sw) * k).toInt().coerceAtLeast(2)
        val oh = ((if (turned) sw else sh) * k).toInt().coerceAtLeast(2)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
        val m = android.graphics.Matrix().apply { setScale(ow.toFloat() / (if (turned) sh else sw), oh.toFloat() / (if (turned) sw else sh)) }
        if (turn != 0) {
            m.setScale(k, k); m.postRotate(turn.toFloat())
            val r = android.graphics.RectF(0f, 0f, sw.toFloat(), sh.toFloat()); m.mapRect(r); m.postTranslate(-r.left, -r.top)
        }
        val c = android.graphics.Canvas(out)
        c.concat(m)
        val box = android.graphics.Rect(0, 0, sw, sh)
        c.drawBitmap(src, box, box, previewPaint)
        return out
    }

    /**
     * 1.2: wait for a frame newer than [afterVersion], at most [timeoutMs]; true when one came. Waiters are woken by
     * the frame itself, so loops that watch the screen run when the picture changes instead of on a fixed clock, and a
     * static screen costs nothing.
     */
    fun awaitNewFrame(afterVersion: Long, timeoutMs: Long, urgent: Boolean = false): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        // The background watcher must not defeat the rate limit whenever it waits for the next frame.
        if (urgent && _frameVersion.value <= afterVersion) requestFreshFrame()
        synchronized(frameSignal) {
            while (!released && _frameVersion.value <= afterVersion) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) return false
                frameSignal.wait(left)
            }
        }
        return !released
    }

    val version: Long get() = _frameVersion.value

    /** The most recent converted frame, or null before the first frame. Do not recycle it. */
    fun latestFrame(): Bitmap? = latest

    // ── v21: power saving ───────────────────────────────────────────────────

    /** The display has no surface: the system turns it off and stops the apps on it (pages are kept). */
    @Volatile var parked = false
        private set

    /** Detach the surface. False when it could not be done (the screen keeps running). */
    fun park(): Boolean {
        if (released || parked) return parked
        val ok = if (shellOwned) VScreenRemoteDisplay.park(displayId).isSuccess
        else runCatching { virtualDisplay?.surface = null; virtualDisplay != null }.getOrDefault(false)
        if (ok) parked = true
        return ok
    }

    /** Give the surface back and wait (max ~3 s) for the first new frame. Returns a short note. */
    fun unpark(): String = unparkResult().let { resumeText(it) }

    /** How a wake from the power-save pause went: kept as facts so the note is worded in the reader's language. */
    data class Resume(val kind: Int, val ms: Long)

    fun resumeText(r: Resume): String = when (r.kind) {
        RESUME_RUNNING -> tr("副屏在运行", "screen running")
        RESUME_WOKE -> tr("副屏刚从省电暂停中恢复（${r.ms}ms）。暂停时应用不在前台，有的应用（游戏、视频、录音）会自己暂停或弹出菜单：以下面的画面为准，不要按上次看到的操作", "The screen just resumed from its power-save pause (${r.ms}ms). While paused the app was not in front, and some apps (games, video, recorders) pause themselves or show a menu then: go by the picture below, not by what you saw last time")
        RESUME_NO_FRAME -> tr("副屏已从省电暂停中恢复，但还没收到新画面（静止页面可能不重绘）；先 read 确认", "The screen resumed from its power-save pause but no new frame arrived yet (a still page may not redraw); check with read first")
        else -> tr("副屏从省电暂停恢复失败：画面可能不更新。用 release 后重新 launch", "Resuming the screen from its power-save pause failed: the picture may not update. release, then launch again")
    }

    /**
     * 2.0.0-beta12 (test: one SCREEN_RESUMED note in Chinese in an English reply): the wake can happen on a thread
     * that is not the agent's request (the turn starting), and the note was worded there, in the app's language.
     */
    fun unparkResult(): Resume {
        if (!parked || released) { parked = false; return Resume(RESUME_RUNNING, 0L) }
        val t0 = SystemClock.elapsedRealtime()
        fun attach(): Boolean = if (shellOwned) VScreenRemoteDisplay.setSurface(displayId, reader.surface).isSuccess
            else runCatching { virtualDisplay?.surface = reader.surface; true }.getOrDefault(false)
        var ok = attach()
        parked = false
        boost(3_000)
        val deadline = t0 + 3_000
        var halfRetried = false
        while (SystemClock.elapsedRealtime() < deadline && lastFrameAtMs < t0) {
            if (!halfRetried && SystemClock.elapsedRealtime() - t0 > 1_500) { halfRetried = true; ok = attach() || ok }
            Thread.sleep(50)
        }
        val woke = lastFrameAtMs >= t0
        return when {
            woke -> Resume(RESUME_WOKE, SystemClock.elapsedRealtime() - t0)
            ok -> Resume(RESUME_NO_FRAME, 0L)
            else -> Resume(RESUME_FAILED, 0L)
        }
    }

    /** Latest frame together with its version number (for OCR caching and stale checks). */
    fun latestWithVersion(): Pair<Bitmap, Long>? {
        val b = latest ?: return null
        return synchronized(frameLock) { (latestRaw ?: b) to _frameVersion.value }
    }

    /** Make the pump convert the very next frame regardless of the throttle. */
    fun requestFreshFrame() { throttle.request() }

    /**
     * A frame at least as new as [notOlderThanMs] (elapsed realtime), waiting
     * up to [timeoutMs] for one. Used by screenshots taken right after an
     * action, so the agent sees the result of what it just did.
     */
    fun awaitFrameAfter(notOlderThanMs: Long, timeoutMs: Long): Bitmap? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        // Force the next frame to be converted regardless of the throttle.
        requestFreshFrame()
        while (SystemClock.elapsedRealtime() < deadline) {
            if (latestCaptureMs >= notOlderThanMs) latest?.let { return it }
            synchronized(frameSignal) { frameSignal.wait((deadline - SystemClock.elapsedRealtime()).coerceIn(1L, 30L)) }
        }
        // A static screen produces no new frames; the last one is still correct.
        return latest
    }

    fun release() {
        if (released) return
        released = true
        listener?.let { l -> runCatching { (watchContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(l) } }
        listener = null
        com.kilon.agentvscreen.host.quiet("VScreenDisplay:403") { reader.setOnImageAvailableListener(null, null) }
        val h = pumpHandler
        synchronized(this) { viewers.clear(); previewLongSide = 0; previewIntervalMs = Long.MAX_VALUE }
        colorWatches.clear()
        takeColorFastRecordFrame()?.first?.recycle()
        h?.removeCallbacks(flushPending)
        h?.removeCallbacks(refreshPreview)
        h?.removeCallbacks(trimPreview)
        synchronized(frameSignal) { frameSignal.notifyAll() }
        synchronized(colorSignal) { colorSignal.notifyAll() }
        if (shellOwned) runCatching { VScreenRemoteDisplay.release(displayId) }
        else runCatching { virtualDisplay?.release() }
        // The reader is closed on the pump thread: convert() runs there and reads the image's
        // native buffer. Closing it from this thread while a frame was being copied crashed the
        // whole process (seen right after a rebuild), which took the screen and the chat down.
        val queued = h != null && runCatching {
            h.post {
                runCatching { pending?.close() }; pending = null
                dropPreviewBuffers()
                com.kilon.agentvscreen.host.quiet("VScreenDisplay:414") { reader.close() }
            }
        }.getOrDefault(false)
        if (!queued) { runCatching { pending?.close() }; pending = null; dropPreviewBuffers(); runCatching { reader.close() } }
        com.kilon.agentvscreen.host.quiet("VScreenDisplay:418") { thread.quitSafely() }
        synchronized(frameLock) { latestRaw = null }
    }

    companion object {
        const val RESUME_RUNNING = 0
        const val RESUME_WOKE = 1
        const val RESUME_NO_FRAME = 2
        const val RESUME_FAILED = 3
        private const val TAG = "VScreenDisplay"
        const val DEFAULT_FRAME_INTERVAL_MS = 120L
        private var generationCounter = 0L

        /**
         * Pure mean-color sampler over `(2*radius+1)^2` pixels around upright coordinate ([x], [y]) directly from
         * a raw RGBA_8888 [buf] of size [rawW]x[rawH] rotated clockwise by [turn] degrees. Does not mutate [buf]'s
         * position or limit. Returns null when ([x], [y]) is out of bounds.
         */
        internal fun sampleRgbaColor(
            buf: java.nio.ByteBuffer,
            rawW: Int,
            rawH: Int,
            rowStride: Int,
            pixelStride: Int,
            turn: Int,
            x: Int,
            y: Int,
            radius: Int = 2,
        ): Int? {
            if (rawW <= 0 || rawH <= 0 || pixelStride < 3 || rowStride < rawW * pixelStride) return null
            val t = ((turn % 360) + 360) % 360
            val fw = if (t == 90 || t == 270) rawH else rawW
            val fh = if (t == 90 || t == 270) rawW else rawH
            if (x !in 0 until fw || y !in 0 until fh) return null
            val lim = buf.limit()
            var sr = 0
            var sg = 0
            var sb = 0
            var n = 0
            for (dy in -radius..radius) {
                val py = (y + dy).coerceIn(0, fh - 1)
                for (dx in -radius..radius) {
                    val px = (x + dx).coerceIn(0, fw - 1)
                    val sx: Int
                    val sy: Int
                    when (t) {
                        90 -> { sx = py; sy = rawH - 1 - px }
                        180 -> { sx = rawW - 1 - px; sy = rawH - 1 - py }
                        270 -> { sx = rawW - 1 - py; sy = px }
                        else -> { sx = px; sy = py }
                    }
                    val off = sy * rowStride + sx * pixelStride
                    if (off < 0 || off + 2 >= lim) continue
                    sr += buf.get(off).toInt() and 0xFF
                    sg += buf.get(off + 1).toInt() and 0xFF
                    sb += buf.get(off + 2).toInt() and 0xFF
                    n++
                }
            }
            if (n == 0) return null
            return android.graphics.Color.rgb(sr / n, sg / n, sb / n)
        }

        /** 2.0.0-beta5 (5.7 #1): minimum preview & full-frame refresh cadence during colorFastMode (500ms). */
        const val COLOR_FAST_INTERVAL_MS = 500L

        /** Pure check for whether a preview + full-frame conversion is due during [colorFastMode]. */
        internal fun colorFastFrameDue(
            nowMs: Long,
            lastFrameMs: Long,
            intervalMs: Long = COLOR_FAST_INTERVAL_MS,
        ): Boolean = lastFrameMs <= 0L || nowMs - lastFrameMs >= intervalMs

        /** Why the last shell-display attempt failed (null = it worked). Shown in launch errors. */
        @Volatile var lastRemoteError: String? = null
            private set

        /**
         * Take over a display the Shizuku service still holds from a previous
         * app process: give it a fresh ImageReader surface. Null on failure.
         */
        internal fun adopt(e: VScreenRemoteDisplay.Existing): VScreenDisplay? {
            // 1.4.2-beta6: the reader first, the thread only once it exists (a reader that failed left a thread behind).
            val reader = runCatching { ImageReader.newInstance(e.width, e.height, PixelFormat.RGBA_8888, 3) }
                .onFailure { AppLogger.warning(TAG, "adopt ${e.displayId}: no image reader: ${it.message}") }.getOrNull() ?: return null
            val thread = HandlerThread("minis-vscreen-frames").apply { start() }
            val r = runCatching { VScreenRemoteDisplay.setSurface(e.displayId, reader.surface) }.getOrElse { Result.failure(it) }
            if (r.isFailure) {
                AppLogger.warning(TAG, "adopt ${e.displayId} failed: ${r.exceptionOrNull()?.message}")
                runCatching { reader.close() }; thread.quitSafely()
                return null
            }
            val gen = synchronized(this) { ++generationCounter }
            val d = VScreenDisplay(e.displayId, e.width, e.height, e.dpi, gen, true, null, reader, thread,
                shellOwned = true, trusted = VScreenDisplayService.isTrusted(e.flags), flags = e.flags)
            d.startPump()
            AppLogger.info(TAG, "adopted shell display ${e.displayId} gen=$gen")
            return d
        }

        /**
         * Create the display. Returns the display or an error string, never
         * throws. Runs on any thread.
         */
        fun create(context: Context, name: String, width: Int, height: Int, dpi: Int): Result<VScreenDisplay> {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return Result.failure(IllegalStateException("DisplayManager unavailable"))
            val reader = runCatching { ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3) }
                .getOrElse { return Result.failure(IllegalStateException("ImageReader ${width}x$height: ${it.message}", it)) }
            val thread = HandlerThread("minis-vscreen-frames").apply { start() }
            // Preferred: a shell-owned (and on Android 13+ trusted) display made by
            // the Shizuku UserService. An app-owned display is untrusted: the system
            // refuses `am start --display` from the shell uid and silently opens the
            // app on the main screen instead.
            val remote = VScreenRemoteDisplay.create(name, width, height, dpi, reader.surface)
            remote.onSuccess { r ->
                lastRemoteError = null
                val gen = synchronized(this) { ++generationCounter }
                val d = VScreenDisplay(r.displayId, width, height, dpi, gen, true, null, reader, thread,
                    shellOwned = true, trusted = r.trusted, flags = r.flags)
                d.startPump()
                AppLogger.info(TAG, "created shell display ${d.displayId} ${width}x$height@$dpi flags=${r.flags} trusted=${r.trusted} gen=$gen")
                return Result.success(d)
            }
            lastRemoteError = remote.exceptionOrNull()?.message ?: "unknown"
            AppLogger.warning(TAG, "shell display unavailable ($lastRemoteError); falling back to an app-owned display")
            fun attempt(flags: Int): VirtualDisplay? = runCatching {
                dm.createVirtualDisplay(name, width, height, dpi, reader.surface, flags, null, Handler(thread.looper))
            }.onFailure {
                AppLogger.warning(TAG, "createVirtualDisplay(flags=$flags) failed: ${it.message}")
            }.getOrNull()

            val publicFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            var isPublic = true
            val vd = attempt(publicFlags)
                ?: attempt(DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY).also { isPublic = false }
            if (vd == null) {
                com.kilon.agentvscreen.host.quiet("VScreenDisplay:489") { reader.close() }
                thread.quitSafely()
                return Result.failure(
                    IllegalStateException(tr("系统拒绝创建虚拟显示屏（ROM 策略或显示屏数量上限）", "The system refused to create the virtual display (ROM policy or display limit)")),
                )
            }
            val gen = synchronized(this) { ++generationCounter }
            val d = VScreenDisplay(vd.display.displayId, width, height, dpi, gen, isPublic, vd, reader, thread)
            d.startPump()
            AppLogger.info(TAG, "created display ${d.displayId} ${width}x$height@$dpi public=$isPublic gen=$gen")
            return Result.success(d)
        }
    }
}
