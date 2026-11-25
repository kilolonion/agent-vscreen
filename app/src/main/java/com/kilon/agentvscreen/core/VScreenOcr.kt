package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.kilon.agentvscreen.host.AppLogger
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * [vscreen] On-device text layer for the background screen.
 *
 * ML Kit's bundled Chinese recognizer runs inside the app, offline, on the
 * full-resolution frame, so every box is already in virtual-screen pixels.
 *
 * Segmentation: ML Kit's own line grouping is not stable (the same tab bar
 * came back as one line, then as two). We therefore rebuild "items" from the
 * character boxes (Text.Symbol): characters of a line are split into
 * separate items wherever the visual gap is wider than 0.6 x the character
 * height. A row of tabs becomes one item per tab, every time, and each item
 * keeps its character boxes so a substring can be tapped at its own centre.
 *
 * Results are cached per display and frame version (or an unchanged frame).
 */
internal object VScreenOcr {
    private const val TAG = "VScreenOcr"
    const val ENGINE = "mlkit-zh (on-device)"

    data class Glyph(val ch: String, val box: Rect)

    data class Item(val text: String, val box: Rect, val glyphs: List<Glyph>) {
        val cx: Int get() = box.centerX()
        val cy: Int get() = box.centerY()
    }

    /** A match of a query: the item and the box of the matched characters only. */
    data class Match(val item: Item, val box: Rect, val exact: Boolean, val fuzzy: Boolean = false) {
        val cx: Int get() = box.centerX()
        val cy: Int get() = box.centerY()
    }

    /** Items whose vertical centres line up: one visual row of the screen. */
    data class Row(val items: List<Item>) {
        val top: Int = items.minOf { it.box.top }
        val bottom: Int = items.maxOf { it.box.bottom }
        val cy: Int get() = (top + bottom) / 2
        val height: Int get() = bottom - top
        val text: String get() = items.joinToString(" ") { it.text }
    }

    data class Result(
        val items: List<Item>,
        val displayId: Int,
        val frameVersion: Long,
        val ms: Long,
        val error: String?,
        val sig: ScreenSig? = null,
        val engine: String = ENGINE,
        /** Set when the accurate engine was asked for but failed and ML Kit answered instead. */
        val fallback: String? = null,
    )

    private val recognizer by lazy {
        // 1.4.0-beta2: beta1 on a phone failed inside getClient (its component lookup returned null). Initialise ML Kit
        // here with the app context, which is a no-op when its provider already did it.
        BackgroundOperation.appContextOrNull()?.let { runCatching { com.google.mlkit.common.MlKit.initialize(it) } }
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    @Volatile private var cache: Result? = null

    /**
     * 1.4.2: ML Kit runs one image at a time, and Tasks.await giving up does not stop its task. Before, a timed-out task
     * kept the queue (the service-start self-test held it while the agent's first look waited and timed out: OCR_DOWN on
     * API 35 CI) and kept reading a bitmap its caller had already recycled. Now one task of ours is in ML Kit at a time
     * (the slot is given back when the task really completes), and the task reads its own copy, recycled with it.
     */
    private val mlSlot = java.util.concurrent.Semaphore(1)
    private fun mlkit(frame: Bitmap, timeoutS: Long): Text {
        if (!mlSlot.tryAcquire(timeoutS, TimeUnit.SECONDS)) throw java.util.concurrent.TimeoutException("ML Kit busy: an earlier reading has not finished in ${timeoutS}s")
        val own: Bitmap
        val task: com.google.android.gms.tasks.Task<Text>
        try {
            own = frame.copy(Bitmap.Config.ARGB_8888, false) ?: throw IllegalStateException("ML Kit: could not copy the frame")
            task = try { recognizer.process(InputImage.fromBitmap(own, 0)) } catch (t: Throwable) { own.recycle(); throw t }
        } catch (t: Throwable) {
            mlSlot.release()
            throw t
        }
        task.addOnCompleteListener({ it.run() }) { own.recycle(); mlSlot.release() }
        return Tasks.await(task, timeoutS, TimeUnit.SECONDS)
    }
    /** The fast engine's last failure, cleared by its next success: lets a reused or skipped reading still say OCR is down. */
    @Volatile private var fastError: String? = null

    /**
     * 1.4.0-beta2: the line for 【注意】 when no engine could read the screen ([err], or the fast engine's last failure);
     * null when reading works. Never silent: an element list from accessibility alone looks complete but is not.
     */
    fun downLine(err: String?): String? {
        // 2.0.0-beta12: the heat limit (ThermalGuard) pauses reading on purpose; say so, not "OCR is broken".
        if (err == THERMAL_LIMIT || (err == null && com.kilon.agentvscreen.host.ThermalGuard.limited)) return thermalLine()
        BackgroundOperation.appContextOrNull()?.let { recoverIfNeeded(it) }
        if (mlkitCrashed && paddleCrashed) {
            return tr("文字识别不可用：PP-OCRv6 与 ML Kit 在自检时均崩溃，本版本已禁用。下面的元素只来自无障碍，画面上的字可能没列出来，以截图为准；vscreen diag 可重新自检，并告诉用户",
                "Text recognition is unavailable: both PP-OCRv6 and ML Kit crashed during self-test and are disabled for this version. The elements below come from accessibility only and words on the picture may be missing; trust the screenshot, run vscreen diag to retry self-test, and tell the user")
        }
        val e = err ?: fastError ?: return null
        val acc = if (paddleCrashed) tr("自检时进程崩溃，本版本已禁用", "crashed during self-test, disabled for this version") else VScreenPaddleOcr.loadError()
        val mlNote = if (mlkitCrashed) tr("ML Kit 自检时进程崩溃，本版本已禁用", "ML Kit crashed during self-test, disabled for this version") else tr("ML Kit 也失败（${e.take(140)}）", "ML Kit failed too (${e.take(140)})")
        return tr("文字识别不可用：" + (acc?.let { "PP-OCRv6 不可用（${it.take(140)}）；" } ?: "") + "$mlNote。下面的元素只来自无障碍，画面上的字可能没列出来，以截图为准；vscreen diag 看详情，并告诉用户",
            "Text recognition is unavailable: " + (acc?.let { "PP-OCRv6 unavailable (${it.take(140)}); " } ?: "") + "$mlNote. The elements below come from accessibility only and words on the picture may be missing; trust the screenshot, see vscreen diag, and tell the user")
    }
    @Volatile private var accurateCache: Result? = null

    const val THERMAL_LIMIT = "THERMAL_LIMIT"
    fun thermalLine(): String = tr(
        "手机发热，为降温暂停了文字识别：下面的元素只来自无障碍，画面上的字可能没列出来，以截图为准。不是故障，手机凉下来后自动恢复；少发命令、别让用户以为坏了",
        "The phone is hot, so text recognition is paused to cool it down: the elements below come from accessibility only and words on the picture may be missing; trust the screenshot. Not a fault: it comes back by itself when the phone cools; send fewer commands meanwhile")

    private fun reusable(c: Result?, displayId: Int, frame: Bitmap, version: Long): Boolean {
        if (c == null || c.displayId != displayId || c.error != null) return false
        if (c.frameVersion == version) return true
        // A new frame number with the same picture (cursor blink, redraw) reuses the result.
        val sig = ScreenSig.of(frame) ?: return false
        val old = c.sig ?: return false
        val ch = sig.compare(old)
        return ch.kind == ScreenSig.Kind.NONE && ch.ratio < Change.Level.STILL.max
    }

    /**
     * How long a caller waits for the reading now running ([runningMs] in) to end: what is left of a usual reading
     * ([avgMs]) plus half again, at least 3 s, never past the reading's deadline.
     */
    internal fun shareWaitMs(avgMs: Long, runningMs: Long): Long {
        val usual = if (avgMs > 0L) avgMs * 3 / 2 else VScreenPaddleOcr.SLOT_WAIT_MS
        return (usual - runningMs).coerceIn(VScreenPaddleOcr.SLOT_WAIT_MS, (VScreenPaddleOcr.TIMEOUT_MS - runningMs).coerceAtLeast(VScreenPaddleOcr.SLOT_WAIT_MS))
    }

    /** Average time of an accurate reading on this phone (0 = none yet). */
    @Volatile private var accurateMs = 0L

    // ── 2.0.0-beta2: persistent per-versionCode self-test & crash guard ──

    private const val PREFS_NAME = "agent_vscreen_ocr_selftest"
    private const val KEY_VER = "ver"
    private const val KEY_RUNNING = "running"
    private const val KEY_MLKIT_STATUS = "mlkit_status"
    private const val KEY_MLKIT_LINE = "mlkit_line"
    private const val KEY_PADDLE_STATUS = "paddle_status"
    private const val KEY_PADDLE_LINE = "paddle_line"
    const val ENGINE_MLKIT = "mlkit"
    const val ENGINE_PADDLE = "paddle"

    private val selfTestLock = Any()
    private val selfTestRunLock = Any()
    @Volatile private var recoveredVer: Int = -1
    @Volatile private var selfTestActive: Boolean = false
    @Volatile private var mlkitCrashed: Boolean = false
    @Volatile private var paddleCrashed: Boolean = false
    @Volatile private var selfTestState: String = "pending"
    @Volatile private var selfTestEngine: String? = null

    internal var versionCodeProvider: () -> Int = { com.kilon.agentvscreen.BuildConfig.VERSION_CODE }
    internal var mlkitSelfTestRunner: (android.content.Context) -> Pair<String, String> = { ctx -> runMlkitSelfTest(ctx) }
    internal var paddleSelfTestRunner: (android.content.Context) -> Pair<String, String> = { ctx -> runPaddleSelfTest(ctx) }

    private fun selfTestPrefs(ctx: android.content.Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    internal fun isMlkitDisabled(ctx: android.content.Context? = BackgroundOperation.appContextOrNull()): Boolean {
        if (ctx != null) recoverIfNeeded(ctx)
        return mlkitCrashed
    }

    internal fun isPaddleDisabled(ctx: android.content.Context? = BackgroundOperation.appContextOrNull()): Boolean {
        if (ctx != null) recoverIfNeeded(ctx)
        return paddleCrashed
    }

    fun crashedEngines(ctx: android.content.Context? = BackgroundOperation.appContextOrNull()): Set<String> {
        if (ctx != null) recoverIfNeeded(ctx)
        return buildSet {
            if (mlkitCrashed) add(ENGINE_MLKIT)
            if (paddleCrashed) add(ENGINE_PADDLE)
        }
    }

    fun selfTestStatusJson(ctx: android.content.Context? = BackgroundOperation.appContextOrNull()): org.json.JSONObject {
        if (ctx != null) recoverIfNeeded(ctx)
        return org.json.JSONObject()
            .put("state", selfTestState)
            .put("engine", selfTestEngine ?: org.json.JSONObject.NULL)
    }

    internal fun resetSelfTestForTests(ctx: android.content.Context? = null, clearPrefs: Boolean = true) {
        synchronized(selfTestLock) {
            if (clearPrefs && ctx != null) selfTestPrefs(ctx).edit().clear().commit()
            recoveredVer = -1
            selfTestActive = false
            mlkitCrashed = false
            paddleCrashed = false
            selfTestReport = null
            selfTestState = "pending"
            selfTestEngine = null
            fastError = null
            versionCodeProvider = { com.kilon.agentvscreen.BuildConfig.VERSION_CODE }
            mlkitSelfTestRunner = { c -> runMlkitSelfTest(c) }
            paddleSelfTestRunner = { c -> runPaddleSelfTest(c) }
        }
    }

    internal fun recoverIfNeeded(ctx: android.content.Context) {
        val ver = versionCodeProvider()
        if (recoveredVer == ver) return
        synchronized(selfTestLock) {
            if (recoveredVer == ver) return
            val p = selfTestPrefs(ctx)
            val storedVer = p.getInt(KEY_VER, 0)
            if (storedVer != ver) {
                p.edit().clear().putInt(KEY_VER, ver).commit()
                mlkitCrashed = false
                paddleCrashed = false
                selfTestReport = null
                selfTestState = "pending"
                selfTestEngine = null
                recoveredVer = ver
                return
            }
            val running = p.getString(KEY_RUNNING, null)
            if (running != null) {
                val parts = running.split('@', limit = 2)
                val eng = parts.getOrNull(0)
                val runVer = parts.getOrNull(1)?.toIntOrNull()
                val ed = p.edit().remove(KEY_RUNNING)
                if (runVer == ver) {
                    when (eng) {
                        ENGINE_MLKIT -> {
                            ed.putString(KEY_MLKIT_STATUS, "crashed")
                                .putString(KEY_MLKIT_LINE, "ML Kit: CRASHED（自检时进程退出）")
                            AppLogger.warning(TAG, "detected previous self-test crash in $eng (versionCode=$ver); disabled for this version")
                        }
                        ENGINE_PADDLE -> {
                            ed.putString(KEY_PADDLE_STATUS, "crashed")
                                .putString(KEY_PADDLE_LINE, "PP-OCRv6: CRASHED（自检时进程退出）")
                            AppLogger.warning(TAG, "detected previous self-test crash in $eng (versionCode=$ver); disabled for this version")
                        }
                    }
                }
                ed.commit()
            }
            syncStateFromPrefsLocked(p)
            recoveredVer = ver
        }
    }

    private fun syncStateFromPrefsLocked(p: android.content.SharedPreferences) {
        val ms = p.getString(KEY_MLKIT_STATUS, null)
        val ml = p.getString(KEY_MLKIT_LINE, null)
        val ps = p.getString(KEY_PADDLE_STATUS, null)
        val pl = p.getString(KEY_PADDLE_LINE, null)
        mlkitCrashed = (ms == "crashed")
        paddleCrashed = (ps == "crashed")
        val lines = listOfNotNull(pl, ml)
        selfTestReport = if (lines.isEmpty()) null else lines.joinToString("\n")
        if (!selfTestActive) {
            if (ms != null && ps != null) {
                val crashed = listOfNotNull(ENGINE_MLKIT.takeIf { mlkitCrashed }, ENGINE_PADDLE.takeIf { paddleCrashed })
                if (crashed.isNotEmpty()) {
                    selfTestState = "crashed"
                    selfTestEngine = crashed.joinToString(",")
                } else {
                    selfTestState = "done"
                    selfTestEngine = null
                }
            } else {
                selfTestState = "pending"
                selfTestEngine = null
            }
        }
    }

    /** Whether [recognize] with accurate = true will try PP-OCRv6 now. */
    fun accurateEnabled(): Boolean {
        val ctx = BackgroundOperation.appContextOrNull() ?: return false
        recoverIfNeeded(ctx)
        return !paddleCrashed && BackgroundOperation.state.value.accurateOcr && VScreenPaddleOcr.available(ctx)
    }

    /**
     * 1.4.0-beta2 / 2.0.0-beta2: both engines read the self-test picture once per versionCode, with a crash guard
     * written synchronously via commit() before each engine runs. Startup runs [maybeStartSelfTest] on a background
     * priority thread only when this versionCode has no completed result yet; `vscreen diag` calls [selfTest] to
     * clear saved results/crash marks and re-run both engines.
     */
    @Volatile var selfTestReport: String? = null; private set

    fun maybeStartSelfTest(ctx: android.content.Context): Boolean {
        if (com.kilon.agentvscreen.host.ThermalGuard.limited) return false
        val app = ctx.applicationContext
        recoverIfNeeded(app)
        synchronized(selfTestLock) {
            if (selfTestActive) return false
            val p = selfTestPrefs(app)
            if (p.getString(KEY_MLKIT_STATUS, null) != null && p.getString(KEY_PADDLE_STATUS, null) != null) {
                return false
            }
            selfTestActive = true
            selfTestState = "running"
            selfTestEngine = if (p.getString(KEY_MLKIT_STATUS, null) == null) ENGINE_MLKIT else ENGINE_PADDLE
        }
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            try {
                runSelfTest(app, manual = false)
            } catch (_: Throwable) {
            } finally {
                synchronized(selfTestLock) {
                    selfTestActive = false
                    syncStateFromPrefsLocked(selfTestPrefs(app))
                }
            }
        }, "ocr-selftest").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        return true
    }

    fun selfTest(ctx: android.content.Context): String {
        val app = ctx.applicationContext
        synchronized(selfTestLock) { selfTestActive = true }
        return try {
            runSelfTest(app, manual = true)
        } finally {
            synchronized(selfTestLock) {
                selfTestActive = false
                syncStateFromPrefsLocked(selfTestPrefs(app))
            }
        }
    }

    private fun runSelfTest(ctx: android.content.Context, manual: Boolean): String = synchronized(selfTestRunLock) {
        if (com.kilon.agentvscreen.host.ThermalGuard.limited) return@synchronized "THERMAL_LIMIT"
        val ver = versionCodeProvider()
        val p = selfTestPrefs(ctx)
        if (manual) {
            synchronized(selfTestLock) {
                p.edit().clear().putInt(KEY_VER, ver).commit()
                mlkitCrashed = false
                paddleCrashed = false
                selfTestReport = null
                recoveredVer = ver
            }
        } else {
            recoverIfNeeded(ctx)
        }

        // ML Kit first: it warms up in seconds, so an agent whose accurate read waits behind the slow PP-OCRv6 build
        // falls back to a warm ML Kit instead of a cold one. The report keeps PP-OCRv6 on the first line.
        if (manual || p.getString(KEY_MLKIT_STATUS, null) == null) {
            synchronized(selfTestLock) { selfTestState = "running"; selfTestEngine = ENGINE_MLKIT }
            p.edit().putInt(KEY_VER, ver).putString(KEY_RUNNING, "$ENGINE_MLKIT@$ver").commit()
            val (status, line) = mlkitSelfTestRunner(ctx)
            p.edit().remove(KEY_RUNNING).putString(KEY_MLKIT_STATUS, status).putString(KEY_MLKIT_LINE, line).commit()
        }

        if (manual || p.getString(KEY_PADDLE_STATUS, null) == null) {
            synchronized(selfTestLock) { selfTestState = "running"; selfTestEngine = ENGINE_PADDLE }
            p.edit().putInt(KEY_VER, ver).putString(KEY_RUNNING, "$ENGINE_PADDLE@$ver").commit()
            val (status, line) = paddleSelfTestRunner(ctx)
            p.edit().remove(KEY_RUNNING).putString(KEY_PADDLE_STATUS, status).putString(KEY_PADDLE_LINE, line).commit()
        }

        val report = synchronized(selfTestLock) {
            selfTestActive = false
            syncStateFromPrefsLocked(p)
            selfTestReport.orEmpty()
        }
        AppLogger.info(TAG, "self-test\n$report")
        report
    }

    private fun runMlkitSelfTest(ctx: android.content.Context): Pair<String, String> = try {
        val bmp = ctx.assets.open("vscreen-ocr/selftest.png").use { android.graphics.BitmapFactory.decodeStream(it) }
        try {
            val t0 = SystemClock.elapsedRealtime()
            // Cold ML Kit takes ~9 s under emulator translation; the self-test may wait longer than an agent's read.
            val text = mlkit(bmp, 30)
            val words = text.textBlocks.flatMap { b -> b.lines.map { it.text } }
            val hit = VScreenPaddleOcr.EXPECT.filter { w -> words.any { it.contains(w) } }
            fastError = null
            val ok = hit.isNotEmpty()
            (if (ok) "ok" else "wrong") to "ML Kit: ${if (ok) "ok" else "WRONG"} ${hit.size}/${VScreenPaddleOcr.EXPECT.size} expected words, ${words.size} lines, ${SystemClock.elapsedRealtime() - t0}ms"
        } finally { bmp.recycle() }
    } catch (t: Throwable) {
        val c = t.cause ?: t
        fastError = "${c.javaClass.simpleName}: ${c.message}"
        "failed" to "ML Kit: FAILED ${c.javaClass.simpleName}: ${c.message?.take(240)}"
    }

    private fun runPaddleSelfTest(ctx: android.content.Context): Pair<String, String> = try {
        val (words, hit, ms) = VScreenPaddleOcr.selfTest(ctx)
        val ok = hit.size == VScreenPaddleOcr.EXPECT.size
        (if (ok) "ok" else "wrong") to ("PP-OCRv6: ${if (ok) "ok" else "WRONG"} ${hit.size}/${VScreenPaddleOcr.EXPECT.size} expected words, ${words.size} lines, ${ms}ms" +
            (VScreenPaddleOcr.optNote?.let { " [$it]" } ?: ""))
    } catch (t: Throwable) {
        "failed" to "PP-OCRv6: FAILED ${t.javaClass.simpleName}: ${t.message?.take(240)}"
    }

    /** Engines for status: what read / tap --text use, and what the fast paths use. */
    fun engineInfo(): String {
        val ctx = BackgroundOperation.appContextOrNull()
        if (ctx != null) recoverIfNeeded(ctx)
        val base = when {
            mlkitCrashed && paddleCrashed -> tr(
                "OCR 不可用（PP-OCRv6 与 ML Kit 在自检时均崩溃，本版本已禁用；运行 diag 或升级应用后重试）",
                "OCR unavailable (both PP-OCRv6 and ML Kit crashed in self-test and are disabled for this version; run diag or update the app to retry)"
            )
            mlkitCrashed -> {
                val accOk = ctx != null && VScreenPaddleOcr.available(ctx)
                if (accOk) tr(
                    "${VScreenPaddleOcr.ENGINE}（ML Kit 自检崩溃，本版本已禁用，由 PP-OCRv6 接手全部识别）",
                    "${VScreenPaddleOcr.ENGINE} (ML Kit crashed in self-test and is disabled for this version; PP-OCRv6 handles all recognition)"
                ) else tr(
                    "OCR 不可用（ML Kit 自检崩溃已在本版本禁用" + (VScreenPaddleOcr.loadError()?.let { "；PP-OCRv6 加载失败：$it" } ?: "；PP-OCRv6 不可用") + "）",
                    "OCR unavailable (ML Kit crashed in self-test and is disabled for this version" + (VScreenPaddleOcr.loadError()?.let { "; PP-OCRv6 failed to load: $it" } ?: "; PP-OCRv6 unavailable") + ")"
                )
            }
            paddleCrashed -> ENGINE + tr(
                "（PP-OCRv6 自检崩溃，本版本已禁用，由 ML Kit 接手）",
                " (PP-OCRv6 crashed in self-test and is disabled for this version; ML Kit took over)"
            )
            accurateEnabled() -> tr("${VScreenPaddleOcr.ENGINE}；滚动等快速路径用 $ENGINE；后台感知（state 的 now.words）也用精确引擎", "${VScreenPaddleOcr.ENGINE}; fast paths such as scrolling use $ENGINE; background perception (now.words in state) uses the accurate engine too") +
                (if (accurateMs > 0L) tr("（约 ${accurateMs}ms/次，慢时降低频率）", " (about ${accurateMs}ms each, less often when slow)") else "")
            else -> ENGINE + (VScreenPaddleOcr.loadError()?.let { tr("（PP-OCRv6 加载失败：$it）", " (PP-OCRv6 failed to load: $it)") } ?: "")
        }
        return base + (selfTestReport?.takeIf { "FAILED" in it || "WRONG" in it || "CRASHED" in it }?.let { tr("；自检：", "; self-test: ") + it.replace("\n", "; ") } ?: "")
    }

    /**
     * OCR of [frame] (version [version]) on display [displayId]. Never throws. Not on the main thread.
     * [accurate]: read / tap --text / vision marks ask for PP-OCRv6 (slower, fewer misreads) when it
     * is enabled; scroll measurement, waits and other loops keep ML Kit (fast). PP-OCRv6 failures
     * fall back to ML Kit and say so in [Result.fallback]. When one engine crashed during self-test,
     * it is not loaded in this versionCode and the other engine takes over.
     */
    fun recognize(displayId: Int, frame: Bitmap, version: Long, accurate: Boolean = false): Result {
        if (com.kilon.agentvscreen.host.ThermalGuard.limited) return Result(emptyList(), displayId, version, 0L, THERMAL_LIMIT)
        val ctx = BackgroundOperation.appContextOrNull()
        if (ctx != null) recoverIfNeeded(ctx)
        if (mlkitCrashed && paddleCrashed) {
            val msg = tr("OCR 不可用：PP-OCRv6 与 ML Kit 在自检时均崩溃，本版本已禁用", "OCR unavailable: both PP-OCRv6 and ML Kit crashed in self-test and are disabled for this version")
            fastError = msg
            return Result(emptyList(), displayId, version, 0L, msg)
        }
        var fallback: String? = null
        val wantPaddle = !paddleCrashed && (accurateEnabled() && accurate || (mlkitCrashed && ctx != null && VScreenPaddleOcr.available(ctx)))
        if (wantPaddle && ctx != null) {
            val ac = accurateCache
            if (reusable(ac, displayId, frame, version)) return ac!!
            // 2.0.0-beta12 (test: 19 "PP-OCRv6 busy … switched to ML Kit" in one round): a reading already running is
            // almost always the background watcher reading this same picture. Wait for it as long as a reading takes
            // on this phone now (a hot phone took ~7 s, more than the old fixed 3 s), then share its result when the
            // picture is the same, instead of failing over to the other engine.
            if (VScreenPaddleOcr.busy() && VScreenPaddleOcr.busyMs() < VScreenPaddleOcr.TIMEOUT_MS) {
                val budget = shareWaitMs(accurateMs, VScreenPaddleOcr.busyMs())
                if (VScreenPaddleOcr.awaitIdle(budget)) {
                    val shared = accurateCache
                    if (reusable(shared, displayId, frame, version)) return shared!!
                }
            }
            val t0 = SystemClock.elapsedRealtime()
            try {
                val items = VScreenPaddleOcr.recognize(ctx, frame)
                val took = SystemClock.elapsedRealtime() - t0
                accurateMs = if (accurateMs == 0L) took else (accurateMs * 3 + took) / 4
                fastError = null
                return Result(items, displayId, version, took, null, ScreenSig.of(frame),
                    engine = VScreenPaddleOcr.ENGINE).also { accurateCache = it }
            } catch (t: Throwable) {
                if (mlkitCrashed) {
                    val msg = tr("PP-OCRv6 失败（${t.javaClass.simpleName}: ${t.message}），且 ML Kit 已因自检崩溃禁用", "PP-OCRv6 failed (${t.javaClass.simpleName}: ${t.message}), and ML Kit is disabled after self-test crash")
                    AppLogger.warning(TAG, msg)
                    fastError = msg
                    return Result(emptyList(), displayId, version, SystemClock.elapsedRealtime() - t0, msg)
                }
                fallback = tr("PP-OCRv6 失败（${t.javaClass.simpleName}: ${t.message}），已改用 ML Kit", "PP-OCRv6 failed (${t.javaClass.simpleName}: ${t.message}), switched to ML Kit")
                AppLogger.warning(TAG, fallback)
            }
        } else if (mlkitCrashed) {
            val msg = tr("OCR 不可用：ML Kit 已因自检崩溃禁用，且 PP-OCRv6 不可用", "OCR unavailable: ML Kit is disabled after self-test crash and PP-OCRv6 is unavailable")
            fastError = msg
            return Result(emptyList(), displayId, version, 0L, msg)
        }
        val r = recognizeFast(displayId, frame, version)
        return if (fallback != null) r.copy(fallback = fallback) else r
    }

    /**
     * The words inside [r]: [full] (the screen reading, see VScreenText) whose centre is in [r], plus a padded,
     * enlarged reading of the crop of [frame] for what the whole reading missed (small HUD text).
     */
    fun readRegion(full: List<Item>, frame: Bitmap, r: Rect): List<Item> {
        val inside = full.filter { r.contains(it.cx, it.cy) }
        // v48: the crop is grown to the whole lines it cuts (a cut line reads as fragments: "1.吸平", "廷"), and what
        // it reads counts only where the full reading has nothing.
        val touched = full.filter { Rect.intersects(it.box, r) }
        val grown = Rect(r).also { g -> touched.forEach { g.union(it.box) } }
        val extra = runCatching { readCrop(frame, grown) }.onFailure { AppLogger.warning(TAG, "readCrop: ${it.message}") }.getOrDefault(emptyList())
            .filter { e -> r.contains(e.cx, e.cy) && touched.none { Rect.intersects(it.box, e.box) } }
        return (inside + extra).sortedWith(compareBy<Item>({ it.box.top / 8 }, { it.box.left }))
    }

    /** OCR of [r0] alone: enlarged (small regions up to 3×) and set on a plain margin of its own edge colour. */
    fun readCrop(frame: Bitmap, r0: Rect): List<Item> {
        if (com.kilon.agentvscreen.host.ThermalGuard.limited) return emptyList()
        val r = Rect(r0)
        if (!r.intersect(0, 0, frame.width, frame.height) || r.width() < 4 || r.height() < 4) return emptyList()
        val zoom = (160.0 / minOf(r.width(), r.height())).coerceIn(1.0, 3.0)
        val zw = (r.width() * zoom).toInt().coerceIn(4, 3000); val zh = (r.height() * zoom).toInt().coerceIn(4, 3000)
        val crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
        val big = Bitmap.createScaledBitmap(crop, zw, zh, true)
        if (crop !== big && crop !== frame) crop.recycle()
        val pad = maxOf(24, zh / 3)
        val padded = Bitmap.createBitmap(zw + 2 * pad, zh + 2 * pad, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(padded).apply { drawColor(edgeColor(big)); drawBitmap(big, pad.toFloat(), pad.toFloat(), null) }
        if (big !== frame) big.recycle()
        try {
            val ctx = BackgroundOperation.appContextOrNull()
            if (ctx != null) recoverIfNeeded(ctx)
            val items = when {
                mlkitCrashed && paddleCrashed -> emptyList()
                ctx != null && !paddleCrashed && (accurateEnabled() || (mlkitCrashed && VScreenPaddleOcr.available(ctx))) -> {
                    if (VScreenPaddleOcr.busy() && !mlkitCrashed) {
                        recognizeFast(-4, padded, -System.nanoTime()).items
                    } else {
                        try {
                            VScreenPaddleOcr.recognize(ctx, padded)
                        } catch (t: Throwable) {
                            AppLogger.warning(TAG, "readCrop PP-OCRv6 failed (${t.javaClass.simpleName}: ${t.message}); fallback to ML Kit")
                            if (!mlkitCrashed) recognizeFast(-4, padded, -System.nanoTime()).items else emptyList()
                        }
                    }
                }
                !mlkitCrashed -> recognizeFast(-4, padded, -System.nanoTime()).items
                else -> emptyList()
            }
            fun back(b: Rect) = Rect(r.left + ((b.left - pad) / zoom).toInt(), r.top + ((b.top - pad) / zoom).toInt(),
                r.left + ((b.right - pad) / zoom).toInt(), r.top + ((b.bottom - pad) / zoom).toInt())
            return items.map { Item(it.text, back(it.box), it.glyphs.map { g -> Glyph(g.ch, back(g.box)) }) }
                .filter { Rect.intersects(it.box, r) }
        } finally { padded.recycle() }
    }

    /** The most common colour along [b]'s border (the margin a crop is set on). */
    private fun edgeColor(b: Bitmap): Int {
        val counts = HashMap<Int, Int>()
        fun add(x: Int, y: Int) { val c = b.getPixel(x, y) and 0xF0F0F0; counts[c] = (counts[c] ?: 0) + 1 }
        val stepX = maxOf(1, b.width / 40); val stepY = maxOf(1, b.height / 40)
        for (x in 0 until b.width step stepX) { add(x, 0); add(x, b.height - 1) }
        for (y in 0 until b.height step stepY) { add(0, y); add(b.width - 1, y) }
        return (counts.maxByOrNull { it.value }?.key ?: 0xFFFFFF) or 0xFF000000.toInt()
    }

    private fun recognizeFast(displayId: Int, frame: Bitmap, version: Long): Result {
        if (mlkitCrashed) {
            val msg = tr("ML Kit 在自检时崩溃，本版本已禁用", "ML Kit crashed in self-test and is disabled for this version")
            fastError = msg
            return Result(emptyList(), displayId, version, 0L, msg)
        }
        val c = cache
        if (reusable(c, displayId, frame, version)) return c!!
        val t0 = SystemClock.elapsedRealtime()
        return try {
            // 1.4.2-beta6: ML Kit gets longer while an accurate reading still runs beside it (they share the CPU; on
            // API 35 CI the fallback timed out at 10 s under a PP-OCRv6 cold build and the agent saw OCR_DOWN).
            val text = mlkit(frame, if (VScreenPaddleOcr.busy()) 25 else 10)
            val items = ArrayList<Item>()
            for (block in text.textBlocks) for (line in block.lines) items += segment(line)
            items.sortWith(compareBy<Item>({ it.box.top / 8 }, { it.box.left }))
            fastError = null
            Result(items, displayId, version, SystemClock.elapsedRealtime() - t0, null, ScreenSig.of(frame)).also { cache = it }
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            AppLogger.warning(TAG, "OCR failed: ${cause.javaClass.simpleName}: ${cause.message}")
            fastError = "${cause.javaClass.simpleName}: ${cause.message}"
            Result(emptyList(), displayId, version, SystemClock.elapsedRealtime() - t0,
                "${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    // ── segmentation ──────────────────────────────────────────────────────

    private class G(val ch: String, val box: Rect, val element: Int)

    /** Split one ML Kit line into items at wide visual gaps, using character boxes. */
    private fun segment(line: Text.Line): List<Item> {
        val glyphs = ArrayList<G>()
        line.elements.forEachIndexed { ei, el ->
            val eb = el.boundingBox ?: return@forEachIndexed
            val syms = runCatching { el.symbols }.getOrNull().orEmpty().filter { it.boundingBox != null && it.text.isNotBlank() }
            if (syms.isNotEmpty()) {
                syms.forEach { glyphs += G(it.text, Rect(it.boundingBox!!), ei) }
            } else {
                // No symbol boxes: share the element box evenly between its characters.
                val chars = el.text.filterNot { it.isWhitespace() }.map { it.toString() }
                if (chars.isEmpty()) return@forEachIndexed
                val w = eb.width().toFloat() / chars.size
                chars.forEachIndexed { i, ch ->
                    glyphs += G(ch, Rect((eb.left + w * i).toInt(), eb.top, (eb.left + w * (i + 1)).toInt(), eb.bottom), ei)
                }
            }
        }
        if (glyphs.isEmpty()) {
            val b = line.boundingBox ?: return emptyList()
            return listOf(Item(line.text.trim(), Rect(b), emptyList()))
        }
        glyphs.sortBy { it.box.left }
        val h = glyphs.map { it.box.height() }.sorted()[glyphs.size / 2].coerceAtLeast(1)
        val out = ArrayList<Item>()
        var cur = ArrayList<G>()
        fun flush() {
            if (cur.isEmpty()) return
            val sb = StringBuilder()
            for (i in cur.indices) {
                val g = cur[i]
                if (i > 0) {
                    val p = cur[i - 1]
                    // Separate Latin words that ML Kit reported as different elements.
                    if (g.element != p.element && p.ch.last().isLetterOrDigit() && p.ch.last().code < 128 &&
                        g.ch.first().isLetterOrDigit() && g.ch.first().code < 128) sb.append(' ')
                }
                sb.append(g.ch)
            }
            val box = Rect(cur.first().box)
            cur.forEach { box.union(it.box) }
            out += Item(sb.toString(), box, cur.map { Glyph(it.ch, it.box) })
            cur = ArrayList()
        }
        for (g in glyphs) {
            val p = cur.lastOrNull()
            if (p != null) {
                val gap = g.box.left - p.box.right
                val misaligned = abs(g.box.centerY() - p.box.centerY()) > h
                if (gap > h * 0.6f || misaligned) flush()
            }
            cur += g
        }
        flush()
        return out
    }

    // ── queries ───────────────────────────────────────────────────────────

    fun norm(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

    /** Box of the characters of [item] that spell [query] (normalised), or null. */
    private fun subBox(item: Item, query: String): Rect? {
        if (item.glyphs.isEmpty()) return null
        val chars = StringBuilder()
        val owner = ArrayList<Int>()
        item.glyphs.forEachIndexed { gi, g ->
            for (c in g.ch) if (!c.isWhitespace()) { chars.append(c.lowercaseChar()); owner += gi }
        }
        val at = chars.indexOf(query)
        if (at < 0) return null
        val first = owner[at]
        val last = owner[at + query.length - 1]
        val box = Rect(item.glyphs[first].box)
        for (i in first..last) box.union(item.glyphs[i].box)
        return box
    }

    /**
     * Places where [query] appears: whole items that equal it first; then
     * items containing it, each with the box of just the matched characters.
     */
    fun find(items: List<Item>, query: String, exactOnly: Boolean = false, fuzzy: Boolean = false): List<Match> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val exact = items.filter { norm(it.text) == q }.map { Match(it, it.box, true) }
        if (exact.isNotEmpty() || exactOnly) return exact
        val contains = items.filter { norm(it.text).contains(q) }.map { Match(it, subBox(it, q) ?: it.box, false) }
        if (contains.isNotEmpty()) return contains
        // v42: a line OCR cut into two or three boxes ("完成赛季作业第二章的3个" + "课题(1/3)").
        val joined = joinedFind(items, q)
        if (joined.isNotEmpty() || !fuzzy || q.length < 2) return joined
        return fuzzyFind(items, q)
    }

    /**
     * v42: neighbours on one row joined (up to 3 boxes, gaps under two text heights) and searched as one line.
     * The match is the joined item (its full text) with the union box.
     */
    fun joinedFind(items: List<Item>, q: String): List<Match> {
        if (q.length < 3) return emptyList()
        val sorted = items.filter { !isJunk(it) }.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val out = ArrayList<Match>()
        for (a in sorted) {
            var cur = a
            var box = Rect(a.box)
            var text = a.text
            val glyphs = ArrayList(a.glyphs)
            for (step in 0 until 2) {
                val h = maxOf(8, box.height())
                val next = sorted.filter { it !== cur && it.box.left >= box.right - h / 2 && it.box.left - box.right <= h * 2 &&
                    kotlin.math.abs(it.cy - box.centerY()) <= h / 2 }.minByOrNull { it.box.left } ?: break
                text += next.text; box.union(next.box); glyphs.addAll(next.glyphs); cur = next
                val t = norm(text)
                if (t.contains(q)) {
                    if (out.none { Rect.intersects(it.box, box) }) out += Match(Item(text, Rect(box), glyphs), Rect(box), t == q)
                    break
                }
            }
        }
        return out
    }

    /**
     * Tolerate OCR misreads (地图 → 地, 模式 → 摸式). Short labels (≤3 chars) only match a
     * whole item one character off; longer ones match a substring within 1 (≤6 chars) or 2 edits.
     */
    private fun fuzzyFind(items: List<Item>, q: String): List<Match> {
        val out = ArrayList<Match>()
        for (it in items) {
            if (isJunk(it)) continue
            val t = norm(it.text)
            if (t.isEmpty()) continue
            if (q.length <= 3) {
                if (abs(t.length - q.length) <= 1 && editDistance(t, q) <= 1) out += Match(it, it.box, false, fuzzy = true)
            } else {
                val k = if (q.length <= 6) 1 else 2
                if (t.length < q.length - k) continue
                val (dist, end) = substringDistance(q, t)
                if (dist <= k) {
                    val part = t.substring((end - q.length).coerceAtLeast(0), end)
                    out += Match(it, subBox(it, part) ?: it.box, false, fuzzy = true)
                }
            }
        }
        return out
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Smallest edit distance of [q] to any substring of [t], and where that substring ends. */
    private fun substringDistance(q: String, t: String): Pair<Int, Int> {
        var prev = IntArray(t.length + 1) // empty query matches anywhere at cost 0
        for (i in 1..q.length) {
            val cur = IntArray(t.length + 1)
            cur[0] = i
            for (j in 1..t.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (q[i - 1] == t[j - 1]) 0 else 1)
            }
            prev = cur
        }
        var best = Int.MAX_VALUE; var end = 0
        for (j in 0..t.length) if (prev[j] < best) { best = prev[j]; end = j }
        return best to end
    }

    /** OCR noise: mostly symbols (video thumbnails, textures), not readable text. */
    fun isJunk(item: Item): Boolean {
        val t = norm(item.text)
        if (t.isEmpty()) return true
        val good = t.count { it.isLetterOrDigit() }
        if (good * 2 < t.length) return true
        // A lone tiny Latin letter / symbol is usually texture noise; lone CJK chars and digits are kept.
        return t.length == 1 && !t[0].isDigit() && t[0].code < 0x2E80 && item.box.height() < 18
    }

    /** Group items into visual rows (vertical centres within half a row height). */
    fun rows(items: List<Item>): List<Row> {
        val sorted = items.sortedBy { it.cy }
        val rows = ArrayList<MutableList<Item>>()
        for (it in sorted) {
            val r = rows.lastOrNull()
            if (r != null) {
                val top = r.minOf { x -> x.box.top }; val bottom = r.maxOf { x -> x.box.bottom }
                val rc = (top + bottom) / 2
                val h = maxOf(bottom - top, it.box.height())
                if (abs(it.cy - rc) <= h / 2) { r += it; continue }
            }
            rows += mutableListOf(it)
        }
        return rows.map { r -> Row(r.sortedBy { it.box.left }) }
    }

    /**
     * v47: could [t] be a page title? Short (2–16 characters), not a value (a clock, a count) and not a piece of a
     * sentence (body text at the top of a reader page, a wallpaper preview's time were taken as screen names).
     */
    fun titleLike(t: String): Boolean {
        val n = norm(t)
        return n.length in 2..16 && !VScreenGame.isValue(n) && t.count { it in "，。,.;；:：!！?？|()（）、" } <= 1
    }

    // ── movement between two OCR results ─────────────────────────────────

    data class Movement(
        /** Content movement along the axis (px, signed), null when it cannot be measured. */
        val delta: Int?,
        val movingMatches: Int,
        /** Items of the newer result that did not move while the content did (sticky bars, bottom nav). */
        val fixed: List<Item>,
    )

    /**
     * How far the content moved between [before] and [after], from texts
     * that appear exactly once in both. Items that stay put while others
     * move are reported as fixed.
     */
    fun movement(before: List<Item>, after: List<Item>, vertical: Boolean): Movement {
        fun uniq(list: List<Item>) = list.filter { norm(it.text).length >= 2 }.groupBy { norm(it.text) }
            .filterValues { it.size == 1 }.mapValues { it.value[0] }
        val a = uniq(before); val b = uniq(after)
        val moving = ArrayList<Int>()
        val still = ArrayList<Item>()
        for ((k, ia) in a) {
            val ib = b[k] ?: continue
            val cross = if (vertical) ib.cx - ia.cx else ib.cy - ia.cy
            if (abs(cross) > 24) continue
            val d = if (vertical) ib.cy - ia.cy else ib.cx - ia.cx
            if (abs(d) <= 4) still += ib else moving += d
        }
        if (moving.size >= 2) {
            moving.sort()
            val med = moving[moving.size / 2]
            // Only call items fixed when the content clearly moved.
            return Movement(med, moving.size, if (abs(med) > 30) still else emptyList())
        }
        if (moving.isEmpty() && still.size >= 2) return Movement(0, 0, emptyList())
        if (moving.size == 1 && still.isEmpty()) return Movement(moving[0], 1, emptyList())
        return Movement(null, moving.size, emptyList())
    }
}
