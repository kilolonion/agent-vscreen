package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

import android.graphics.Bitmap
import android.os.SystemClock
import com.kilon.agentvscreen.host.AppLogger

/**
 * [vscreen] v49: the perception thread keeps LOOKING, it does not judge.
 *
 * While a session works on the background screen, a small thread looks at it whenever a new frame arrives (at most
 * 10 times a second; a static screen wakes it twice a second): every new frame goes to the recording track ([VScreenRecorder]), and about once a second (sooner when the picture
 * changed, or when someone asks) the words on it are read (OCR + the accessibility texts) and kept with the time.
 * That is all: what appeared, what went, what moved is told by the act return from these readings and the track
 * (facts), and what it means is the model's call. There are no event kinds, no progress, no pulses, no background
 * map any more. It stops by itself 5 minutes after the last command.
 */
object VScreenPerception {
    private const val TAG = "VScreenPerception"
    /** The shortest time between two looks, and the longest sleep without a new frame. */
    private const val MIN_GAP_MS = 100L
    private const val HEARTBEAT_MS = 500L
    private const val IDLE_STOP_MS = 300_000L
    private const val MAX_READINGS = 900
    const val BLANK_MS = 1_500L

    /** The words on the screen at one moment (epoch ms), as written there (trimmed), de-duplicated. */
    class Reading(
        val atMs: Long,
        val words: List<String>,
        val boxes: Map<String, android.graphics.Rect> = emptyMap(),
        var hits: Int = 1,
        var lastAtMs: Long = atMs,
    )

    /** 2.0.0-beta5 (5.8 #2/#3): latest OCR result with its frame version and capture epoch ms. */
    internal data class OcrReading(val result: VScreenOcr.Result, val version: Long, val epochMs: Long)

    private class Watcher(val d: VScreenDisplay) {
        @Volatile var running = true
        @Volatile var lastUse = SystemClock.elapsedRealtime()
        val readings = ArrayDeque<Reading>()
        @Volatile var reading: VScreenOcr.Result? = null
        @Volatile var readingAt = 0L            // elapsedRealtime of the frame behind [reading]
        @Volatile var readingEpoch = 0L         // wall-clock epoch ms of the frame behind [reading]
        @Volatile var readingVersion = -1L      // frame version behind [reading]
        @Volatile var readingSmall: Bitmap? = null  // small copy of that frame (is the picture still the same?)
        @Volatile var forceOcr = false
        @Volatile var ocrRunning = false
        @Volatile var ocrMs = 0L
        @Volatile var ocrEngine: String = VScreenOcr.ENGINE
        @Volatile var blankSince = 0L
        /** 1.3: which cells keep moving on their own (video, carousel); fed with every frame. */
        @Volatile var motion: MotionTrack? = null
        var prevTiny: android.graphics.Bitmap? = null
        val lock = Object()
        var thread: Thread? = null
    }

    private val watchers = HashMap<Int, Watcher>()
    private val ocrPaused = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
    private val ocrPool = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "vscreen-percept-ocr").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /** 2.0.0-beta4 (4.7): pause/resume starting new background full-screen OCR jobs for [displayId] (ref-counted). */
    fun setOcrPaused(displayId: Int, paused: Boolean) {
        if (paused) {
            ocrPaused.computeIfAbsent(displayId) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        } else {
            ocrPaused[displayId]?.let { c ->
                val v = c.decrementAndGet()
                if (v <= 0) {
                    c.set(0)
                    ocrPaused.remove(displayId, c)
                }
            }
        }
    }

    /** Whether new background full-screen OCR jobs are paused for [displayId]. */
    fun isOcrPaused(displayId: Int): Boolean = (ocrPaused[displayId]?.get() ?: 0) > 0

    /** Whether a background full-screen OCR job is currently executing for [displayId]. */
    fun isOcrRunning(displayId: Int): Boolean = watcher(displayId)?.ocrRunning == true

    /**
     * Wait up to [timeoutMs] for any in-flight background full-screen OCR (or PP-OCRv6 job) to finish before a
     * latency-sensitive closed-loop gesture touches down. Returns immediately (0ms) when idle.
     */
    fun awaitOcrIdle(displayId: Int, timeoutMs: Long, onTick: () -> Unit = {}) {
        fun active() = isOcrRunning(displayId) || (VScreenPaddleOcr.busy() && VScreenPaddleOcr.busyMs() < 10_000L)
        if (!active()) return
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (active()) {
            onTick()
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0L) break
            val w = watcher(displayId)
            val slice = minOf(50L, left)
            if (w != null) {
                synchronized(w.lock) {
                    if (active()) w.lock.wait(slice)
                }
            } else {
                Thread.sleep(slice)
            }
        }
    }

    /** Start (or keep alive) the watcher for [d]. Cheap to call on every command. */
    fun ensure(d: VScreenDisplay) {
        if (com.kilon.agentvscreen.host.ThermalGuard.limited || (VScreenAwake.screenOff && !BackgroundOperation.needsAwake())) return
        synchronized(watchers) {
            if (d.released) return
            val w = watchers[d.displayId]
            if (w != null && w.running && w.d === d) { w.lastUse = SystemClock.elapsedRealtime(); return }
            w?.running = false
            val nw = Watcher(d)
            watchers[d.displayId] = nw
            nw.thread = Thread {
                try { loop(nw) }
                catch (t: Throwable) { AppLogger.warning(TAG, "loop: ${t.message}") }
                finally {
                    finish(nw)
                    synchronized(watchers) { if (watchers[d.displayId] === nw) watchers.remove(d.displayId) }
                }
            }
                .apply { isDaemon = true; name = "vscreen-perception-${d.displayId}"; priority = Thread.MIN_PRIORITY; start() }
        }
    }

    fun stop(displayId: Int) { synchronized(watchers) { watchers.remove(displayId)?.running = false } }

    private fun watcher(displayId: Int): Watcher? = synchronized(watchers) { watchers[displayId]?.takeIf { it.running } }

    fun running(displayId: Int): Boolean = watcher(displayId) != null

    /**
     * The latest OCR reading of a frame taken at or after [notBefore] (elapsedRealtime); when the latest is older a
     * new one is asked for and awaited up to [waitMs]. null = none new enough (yet).
     */
    internal fun reading(displayId: Int, notBefore: Long, waitMs: Long): VScreenOcr.Result? {
        val w = watcher(displayId) ?: return null
        fun ok() = w.reading != null && w.readingAt >= notBefore
        if (ok()) return w.reading
        w.forceOcr = true
        if (waitMs > 0) {
            val t = SystemClock.elapsedRealtime()
            synchronized(w.lock) { while (w.running && !ok() && SystemClock.elapsedRealtime() - t < waitMs) w.lock.wait(100L) }
        }
        return if (ok()) w.reading else null
    }

    /**
     * 2.0.0-beta5 (5.8 #2/#3): latest OCR reading together with its frame [Watcher.readingVersion] and capture epoch,
     * so callers can distinguish fresh OCR readings from cached polls and anchor rule claimed windows to frame time.
     */
    internal fun readingWithVersion(displayId: Int, notBefore: Long, waitMs: Long): OcrReading? {
        val w = watcher(displayId) ?: return null
        fun pick(): OcrReading? = synchronized(w.lock) {
            val r = w.reading
            if (r != null && w.readingAt >= notBefore) OcrReading(r, w.readingVersion, w.readingEpoch) else null
        }
        pick()?.let { return it }
        w.forceOcr = true
        if (waitMs > 0) {
            val t = SystemClock.elapsedRealtime()
            synchronized(w.lock) {
                while (w.running && (w.reading == null || w.readingAt < notBefore) && SystemClock.elapsedRealtime() - t < waitMs) {
                    w.lock.wait(100L)
                }
            }
        }
        return pick()
    }

    /**
     * v0.5: the latest reading when it was taken of exactly this picture: the same frame [version], or a frame that
     * looks the same (no sampled pixel differs). Null = the words of [frame] are not known yet.
     */
    internal fun readingFor(displayId: Int, frame: Bitmap, version: Long): VScreenOcr.Result? {
        val w = watcher(displayId) ?: return null
        val (r, v, sm) = synchronized(w.lock) { Triple(w.reading, w.readingVersion, w.readingSmall) }
        if (r == null || r.error != null) return null
        if (v == version) return r
        if (sm == null) return null
        val cur = small(frame)
        return try {
            synchronized(w.lock) {
                if (sm.isRecycled || sm.width != cur.width || sm.height != cur.height) null
                // 1.3: a playing area differs in every frame; outside it the picture is the same, and so are its words.
                // 1.4.0-beta5 (test: collect right after launching another app returned the previous app's words, 3 in
                // 9): when the moving areas cover most of the picture (an animated weather background), "the same
                // outside them" compares almost nothing, and another app's reading passed for this one. Then only the
                // exact frame counts.
                else {
                    val m = mask(displayId)
                    val skip = m?.rectsIn(cur.width, cur.height).orEmpty()
                    if (m?.mostly == true) null
                    else if (Change.same(sm, cur, Change.Level.TINY, skip = skip)) r else null
                }
            }
        } finally { cur.recycle() }
    }

    /** Confirm the latest reading is still on screen (e.g. when snapshot reuses it), counting as a consecutive look. */
    internal fun confirmLatest(displayId: Int) {
        val w = watcher(displayId) ?: return
        synchronized(w.lock) {
            w.readings.lastOrNull()?.let {
                it.hits++
                it.lastAtMs = System.currentTimeMillis()
            }
        }
    }

    private fun recordReadingLocked(
        w: Watcher,
        frameEpochMs: Long,
        words: List<String>,
        boxes: Map<String, android.graphics.Rect>,
    ) {
        val last = w.readings.lastOrNull()
        if (last == null || frameEpochMs >= last.atMs) {
            if (last != null && last.words == words) {
                last.hits++
                last.lastAtMs = maxOf(last.lastAtMs, frameEpochMs)
            } else {
                w.readings.addLast(Reading(frameEpochMs, words, boxes))
            }
        } else {
            val idx = w.readings.indexOfFirst { it.atMs > frameEpochMs }
            if (idx > 0 && w.readings[idx - 1].words == words) {
                w.readings[idx - 1].hits++
                w.readings[idx - 1].lastAtMs = maxOf(w.readings[idx - 1].lastAtMs, frameEpochMs)
            } else if (idx >= 0) {
                w.readings.add(idx, Reading(frameEpochMs, words, boxes))
            } else {
                w.readings.addLast(Reading(frameEpochMs, words, boxes))
            }
        }
        while (w.readings.size > MAX_READINGS) w.readings.removeFirst()
    }

    /** v0.5: a reading someone else made of [frame] becomes the watcher's latest (no second OCR of the same picture). */
    internal fun offerReading(
        displayId: Int,
        frame: Bitmap,
        version: Long,
        r: VScreenOcr.Result,
        a11y: List<String>,
        a11yBoxes: Map<String, android.graphics.Rect> = emptyMap(),
        frameEpochMs: Long = System.currentTimeMillis(),
    ) {
        val w = watcher(displayId) ?: return
        if (r.error != null) return
        val (words, boxes) = if (VScreenActivity.uniform(frame)) emptyList<String>() to emptyMap()
        else wordsAndBoxesOf(still(r.items, mask(displayId)), a11y.map { it to a11yBoxes[VScreenOcr.norm(it)] })
        val sm = small(frame)
        synchronized(w.lock) {
            if (!w.running || w.d.released) { sm.recycle(); return }
            if (w.readingVersion > version) {
                sm.recycle()
                recordReadingLocked(w, frameEpochMs, words, boxes)
                w.lock.notifyAll()
                return
            }
            w.reading = r; w.readingAt = SystemClock.elapsedRealtime(); w.readingEpoch = frameEpochMs; w.readingVersion = version
            w.readingSmall?.recycle(); w.readingSmall = sm
            w.forceOcr = false
            recordReadingLocked(w, frameEpochMs, words, boxes)
            w.lock.notifyAll()
        }
    }

    /** Ask for a reading as soon as possible (a text condition is being waited for). */
    fun hurry(displayId: Int) { watcher(displayId)?.forceOcr = true }

    /** The readings kept between two epoch times (oldest first), plus the last one before [fromMs] first when there is one. */
    fun readings(displayId: Int, fromMs: Long, toMs: Long): List<Reading> = watcher(displayId)?.let { w ->
        synchronized(w.lock) {
            val before = w.readings.lastOrNull { it.atMs < fromMs }
            listOfNotNull(before) + w.readings.filter { it.atMs in fromMs..toMs }
        }
    }.orEmpty()

    /** The newest reading's words (null: none yet). */
    fun words(displayId: Int): Reading? = watcher(displayId)?.let { w -> synchronized(w.lock) { w.readings.lastOrNull() } }

    /** How long the picture has been one flat colour, when that is at least [BLANK_MS]; null = it has content. */
    fun blankFor(displayId: Int): Long? = watcher(displayId)?.blankSince?.takeIf { it > 0 }
        ?.let { SystemClock.elapsedRealtime() - it }?.takeIf { it >= BLANK_MS }

    /**
     * 1.3: the areas of [displayId] that keep moving on their own right now (a playing video, a carousel), or null.
     * Taken before an action, it tells the waits what not to wait for (see [MotionTrack]).
     */
    /** 1.3.0: an action of ours on [displayId] just happened (its motion is not the page's own). */
    fun noteAction(displayId: Int, spanMs: Long = 0L) { val now = SystemClock.elapsedRealtime(); watcher(displayId)?.motion?.action(now - spanMs, now) }

    fun mask(displayId: Int, minSpanMs: Long = MotionTrack.MIN_SPAN_MS): Mask? = watcher(displayId)?.let { w ->
        val m = w.motion ?: return null
        m.moving(SystemClock.elapsedRealtime(), minSpanMs)?.let { Mask(it, m.cols, m.rows, w.d.width, w.d.height) }
    }

    /** OCR items outside the moving areas (the words inside a playing video are not the page's words). */
    // 1.4.0-beta5 (test: a game's title page listed no words, so `tap 进入世界` was NOT_FOUND although the words were
    // read): when nearly everything moves, the moving areas are the app itself, not a video inside a page; its words
    // are the page's words and stay listed.
    internal fun still(items: List<VScreenOcr.Item>, mask: Mask?): List<VScreenOcr.Item> = if (mask == null || mask.mostly) items else items.filter { !mask.coversMostOf(it.box) }

    fun engine(displayId: Int): String? = watcher(displayId)?.let { it.ocrEngine + if (it.ocrMs > 0) tr("（约 ${it.ocrMs}ms/次）", " (about ${it.ocrMs}ms each)") else "" }

    /** The words of one reading: OCR lines (no junk) + accessibility texts, trimmed, one each by normalised form. */
    internal fun wordsOf(items: List<VScreenOcr.Item>, a11y: List<String>): List<String> =
        wordsAndBoxesOf(items, a11y.map { it to null }).first

    /** Same as [wordsOf], also keeping the upright frame bounding box per normalised word key (for 3.6 toast checks). */
    internal fun wordsAndBoxesOf(
        items: List<VScreenOcr.Item>,
        a11y: List<Pair<String, android.graphics.Rect?>>,
    ): Pair<List<String>, Map<String, android.graphics.Rect>> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        val boxes = HashMap<String, android.graphics.Rect>()
        val ocrPairs = joinSplitWithBoxes(items.filter { !VScreenOcr.isJunk(it) })
            .map { (txt, box) -> txt.replace('\n', ' ').trim() to box }
        val a11yPairs = a11y.map { (txt, box) -> txt.replace('\n', ' ').trim() to box }
        for ((t, b) in ocrPairs + a11yPairs) {
            if (t.isEmpty() || t.length > 80) continue
            val k = VScreenOcr.norm(t)
            if (b != null && !b.isEmpty && k !in boxes) boxes[k] = android.graphics.Rect(b)
            if (seen.add(k)) out += t
        }
        return out to boxes
    }

    /**
     * 1.3.0: OCR sometimes cuts one line into pieces ("Auracast 音频广" + "播"). Pieces on one line with almost no gap
     * between them are one text again, so a word that went away is listed once, not as fragments.
     */
    internal fun joinSplit(items: List<VScreenOcr.Item>): List<String> = joinSplitWithBoxes(items).map { it.first }

    internal fun joinSplitWithBoxes(items: List<VScreenOcr.Item>): List<Pair<String, android.graphics.Rect>> {
        val left = items.sortedWith(compareBy({ it.box.top }, { it.box.left })).toMutableList()
        val out = ArrayList<Pair<String, android.graphics.Rect>>()
        while (left.isNotEmpty()) {
            var cur = left.removeAt(0)
            var text = cur.text.trim()
            var box = android.graphics.Rect(cur.box)
            while (true) {
                val h = maxOf(1, box.height())
                val next = left.filter { n -> Math.abs(n.box.centerY() - box.centerY()) <= h / 3 && Math.abs(n.box.height() - h) <= h / 2 &&
                    n.box.left >= box.right - h / 4 && n.box.left - box.right <= h * 2 / 5 }.minByOrNull { it.box.left } ?: break
                left.remove(next)
                val nt = next.text.trim()
                text += if (text.lastOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } == true && nt.firstOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } == true) " $nt" else nt
                box.union(next.box); cur = next
            }
            out += text to box
        }
        return out
    }

    private fun loop(w: Watcher) {
        val d = w.d
        var lastOcr = 0L
        var lastVersion = -1L
        var changedSinceOcr = true
        var pkg: String? = null; var pkgAt = 0L
        var seen = -1L
        while (w.running && !d.released && SystemClock.elapsedRealtime() - w.lastUse < IDLE_STOP_MS) {
            if (com.kilon.agentvscreen.host.ThermalGuard.limited || (VScreenAwake.screenOff && !BackgroundOperation.needsAwake())) break
            val t0 = SystemClock.elapsedRealtime()
            if (d.parked) { Thread.sleep(500); continue }
            if (d.colorFastMode) {
                d.takeColorFastRecordFrame()?.let { (small, recVer) ->
                    try { VScreenRecorder.offer(d, small, recVer, pkg) } finally { small.recycle() }
                }
                Thread.sleep(100)
                continue
            }
            d.takeColorFastRecordFrame()?.let { (small, recVer) ->
                try { VScreenRecorder.offer(d, small, recVer, pkg) } finally { small.recycle() }
            }
            // 1.2: frame-driven. The loop sleeps until the picture changes (or someone asks for a reading, or the
            // heartbeat is due) instead of waking 5 times a second whatever the screen does.
            var slept = 0L
            while (w.running && !w.forceOcr && !d.colorFastMode && slept < HEARTBEAT_MS && !d.awaitNewFrame(seen, MIN_GAP_MS)) slept += MIN_GAP_MS
            seen = d.version
            if (d.colorFastMode) continue
            val fv = d.latestWithVersion()
            if (fv == null) { Thread.sleep(MIN_GAP_MS); continue }
            val now = SystemClock.elapsedRealtime()
            if (now - pkgAt > 5_000L || pkg == null) { pkg = VScreenShell.topPackage(d.displayId); pkgAt = now }
            if (!w.running || d.released) break
            VScreenRecorder.offer(d, fv.first, fv.second, pkg)
            if (fv.second != lastVersion) {
                changedSinceOcr = fv.second != w.readingVersion; lastVersion = fv.second
                // 1.3: what changed since the last frame, cell by cell, for the moving-area track.
                runCatching {
                    val tiny = small(fv.first, 160)
                    val rows = VScreenActivity.rowsFor(tiny.width, tiny.height)
                    val mt = w.motion?.takeIf { it.rows == rows } ?: MotionTrack(VScreenActivity.COLS, rows).also { w.motion = it }
                    VScreenActivity.cells(w.prevTiny, tiny, VScreenActivity.COLS, rows)?.let { mt.feed(now, it) }
                    w.prevTiny?.recycle(); w.prevTiny = tiny
                }
            } else w.motion?.let { it.feed(now, BooleanArray(it.cols * it.rows)) }
            val uniform = VScreenActivity.uniform(fv.first)
            if (uniform) { if (w.blankSince == 0L) w.blankSince = now } else w.blankSince = 0L
            val forced = w.forceOcr
            val gap = maxOf(1_000L, w.ocrMs * 2)
            if (!w.ocrRunning && !isOcrPaused(d.displayId) && (forced || (now - lastOcr >= gap && (changedSinceOcr || now - lastOcr >= 5_000L)))) {
                val frameCopy = runCatching { fv.first.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                if (frameCopy != null) {
                    lastOcr = now; w.forceOcr = false; changedSinceOcr = false; w.ocrRunning = true
                    val frameAt = now
                    val frameEpoch = System.currentTimeMillis()
                    val frameVer = fv.second
                    ocrPool.execute {
                        try {
                            if (!w.running || d.released || isOcrPaused(d.displayId)) return@execute
                            val a11yPairs = runCatching {
                                A11yTree.snapshot(d.displayId).nodes.asSequence()
                                    .filter { it.visible }
                                    .mapNotNull { n -> n.words?.take(120)?.let { it to d.toFrame(n.box) } }
                                    .take(200).toList()
                            }.getOrNull().orEmpty()
                            if (!w.running || d.released || isOcrPaused(d.displayId)) return@execute
                            val r = runCatching { VScreenOcr.recognize(d.displayId, frameCopy, frameVer, accurate = VScreenOcr.accurateEnabled()) }.getOrNull()
                            if (r != null) { w.ocrMs = r.ms; w.ocrEngine = r.engine }
                            if (r != null && r.error == null && w.running && !d.released) {
                                val (words, boxes) = if (uniform) emptyList<String>() to emptyMap()
                                else wordsAndBoxesOf(still(r.items, mask(d.displayId)), a11yPairs)
                                val sm = small(frameCopy)
                                synchronized(w.lock) {
                                    if (!w.running || d.released) {
                                        sm.recycle()
                                    } else {
                                        if (frameVer >= w.readingVersion) {
                                            w.reading = r; w.readingAt = frameAt; w.readingEpoch = frameEpoch; w.readingVersion = frameVer
                                            w.readingSmall?.recycle(); w.readingSmall = sm
                                        } else {
                                            sm.recycle()
                                        }
                                        recordReadingLocked(w, frameEpoch, words, boxes)
                                    }
                                    w.lock.notifyAll()
                                }
                            }
                        } catch (t: Throwable) {
                            AppLogger.warning(TAG, "ocr: ${t.message}")
                        } finally {
                            frameCopy.recycle()
                            w.ocrRunning = false
                            synchronized(w.lock) { w.lock.notifyAll() }
                        }
                    }
                }
            }
            synchronized(w.lock) { w.lock.notifyAll() }
            // Never faster than [MIN_GAP_MS] (a video would otherwise spin this thread at the frame rate).
            val left = MIN_GAP_MS - (SystemClock.elapsedRealtime() - t0)
            if (left > 0) Thread.sleep(left)
        }
    }

    private fun finish(w: Watcher) {
        w.running = false
        w.prevTiny?.recycle(); w.prevTiny = null
        synchronized(w.lock) {
            w.readingSmall?.recycle(); w.readingSmall = null
            w.lock.notifyAll()
        }
    }

    /** Small copy of [f] (long side [side]) for pixel comparisons; never the frame itself. */
    fun small(f: Bitmap, side: Int = 360): Bitmap {
        val k = side.toDouble() / maxOf(f.width, f.height)
        if (k >= 1.0) return f.copy(Bitmap.Config.ARGB_8888, false)
        val s = Bitmap.createScaledBitmap(f, maxOf(1, (f.width * k).toInt()), maxOf(1, (f.height * k).toInt()), true)
        return if (s === f) f.copy(Bitmap.Config.ARGB_8888, false) else s
    }
}
