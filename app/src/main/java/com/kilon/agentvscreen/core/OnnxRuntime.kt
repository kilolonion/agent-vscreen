package com.kilon.agentvscreen.core

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 2.0.0-beta8 (D): unified ONNX Runtime execution configuration and always-on inference metrics
 * shared by [VScreenPaddleOcr] (PP-OCRv6 det/rec) and [VScreenDetector] (textless icon detector).
 *
 * Strategy (fixed, no runtime mode switch): global shared ORT thread pool (`intra = min(4, max(1, cores/2))`,
 * `inter = 1`, `globalSpinControl = false`) with per-session thread pools disabled and spinning off
 * (`session.intra_op.allow_spinning = 0`, `session.inter_op.allow_spinning = 0`). OCR and detector remain
 * concurrent while sharing the same non-spinning thread pool.
 *
 * Diagnostics (always on in debug & release): keeps the last [MAX_RECORDS] inferences (start/end, duration,
 * model, shape, concurrent count, thermal status, OCR slot wait, and late completion after timeout) plus any
 * still-running timed-out inferences, surfaced in `vscreen diag` and `status.perception`.
 */
internal object OnnxRuntime {
    private const val TAG = "Onnx"
    const val MAX_RECORDS = 50

    data class Record(
        val seq: Long,
        val model: String,
        val startEpochMs: Long,
        val endEpochMs: Long,
        val durMs: Long,
        val concurrentAtStart: Int,
        val peakConcurrent: Int,
        val inputShape: String,
        val thermalStatus: String,
        val slotWaitMs: Long = 0L,
        /** Non-zero when this inference finished AFTER its caller had already timed out. */
        val lateAfterTimeoutMs: Long = 0L,
        val ok: Boolean,
        val err: String? = null,
    ) {
        fun formatLine(): String {
            val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
            val status = when {
                lateAfterTimeoutMs > 0L -> "LATE(+${lateAfterTimeoutMs}ms after timeout, ${if (ok) "ok" else "ERR"})"
                ok -> "ok"
                else -> "ERR(${err?.take(80) ?: "?"})"
            }
            val waitPart = if (slotWaitMs > 0L) " slotWait=${slotWaitMs}ms" else ""
            return "#$seq ${fmt.format(Date(startEpochMs))}..${fmt.format(Date(endEpochMs))} " +
                "${durMs}ms model=$model shape=$inputShape conc=$concurrentAtStart/$peakConcurrent " +
                "thermal=$thermalStatus$waitPart $status"
        }
    }

    /** Tracks an active inference so a timed-out caller can mark it and report late completion or hang. */
    class ActiveHandle internal constructor(
        val seq: Long,
        val model: String,
        val inputShape: String,
        val startMonoMs: Long,
        val startEpochMs: Long,
        @Volatile var peak: Int,
        @Volatile var timedOutAtMonoMs: Long = 0L,
        @Volatile var timeoutBudgetMs: Long = 0L,
    ) {
        fun markTimedOut(budgetMs: Long) {
            if (timedOutAtMonoMs == 0L) {
                timedOutAtMonoMs = clockMonoFn()
                timeoutBudgetMs = budgetMs
            }
        }
    }

    private val envLock = Any()
    private val metricsLock = Any()
    private val seqGen = AtomicLong(0L)
    private val inFlight = AtomicInteger(0)
    private val activeHandles = ArrayList<ActiveHandle>()
    private val records = ArrayDeque<Record>(MAX_RECORDS)

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var envNote: String? = null
    @Volatile private var lastOcrMs: Long = 0L
    @Volatile private var lastDetectorMs: Long = 0L
    @Volatile private var lateCount: Int = 0

    // Test seams (overridden only in unit tests, reset via resetForTests()).
    internal var clockMonoFn: () -> Long = { SystemClock.elapsedRealtime() }
    internal var clockEpochFn: () -> Long = { System.currentTimeMillis() }
    internal var thermalProvider: (Context?) -> String = { ctx -> readThermalStatus(ctx) }

    fun defaultIntraThreads(): Int = max(1, min(4, Runtime.getRuntime().availableProcessors() / 2))

    private fun readThermalStatus(ctx: Context?): String {
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "n/a"
        return runCatching {
            val pm = ctx.applicationContext.getSystemService(PowerManager::class.java) ?: return "n/a"
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "NONE"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "UNKNOWN(${pm.currentThermalStatus})"
            }
        }.getOrDefault("n/a")
    }

    fun summary(ctx: Context? = BackgroundOperation.appContextOrNull()): String {
        val note = envNote?.let { " [$it]" } ?: " [shared_nospin]"
        val thermal = thermalProvider(ctx)
        val stuck = stuckTimedOut().size
        val stuckPart = if (stuck > 0) ", stuck_timed_out=$stuck" else ""
        return "pool=shared_nospin, threads=${defaultIntraThreads()}, cores=${Runtime.getRuntime().availableProcessors()}, " +
            "thermal=$thermal, records=${recentCount()}, late=$lateCount$stuckPart$note"
    }

    fun perceptionJson(ctx: Context? = BackgroundOperation.appContextOrNull()): JSONObject {
        val recs = recent(MAX_RECORDS)
        val stuck = stuckTimedOut()
        return JSONObject()
            .put("pool", "shared_nospin")
            .put("threads", defaultIntraThreads())
            .put("cores", Runtime.getRuntime().availableProcessors())
            .put("thermal", thermalProvider(ctx))
            .put("last_ocr_ms", lastOcrMs.takeIf { it > 0L } ?: JSONObject.NULL)
            .put("last_detector_ms", lastDetectorMs.takeIf { it > 0L } ?: JSONObject.NULL)
            .put("recent_count", recs.size)
            .put("in_flight", inFlight.get())
            .put("late_completions", lateCount)
            .put("stuck_timed_out", stuck.size)
    }

    fun environment(ctx: Context? = BackgroundOperation.appContextOrNull()): OrtEnvironment {
        env?.let { return it }
        synchronized(envLock) {
            env?.let { return it }
            val threads = defaultIntraThreads()
            val created = try {
                val opts = OrtEnvironment.ThreadingOptions().apply {
                    setGlobalIntraOpNumThreads(threads)
                    setGlobalInterOpNumThreads(1)
                    setGlobalSpinControl(false)
                }
                try {
                    OrtEnvironment.getEnvironment(
                        OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING,
                        "agent-vscreen",
                        opts,
                    ).also {
                        envNote = "global_pool(intra=$threads,inter=1,spin=0)"
                        AppLogger.info(TAG, "OrtEnvironment created: $envNote")
                    }
                } finally {
                    runCatching { opts.close() }
                }
            } catch (t: Throwable) {
                envNote = "global_pool_fallback(${t.javaClass.simpleName}: ${t.message?.take(80)})"
                AppLogger.warning(TAG, "OrtEnvironment ThreadingOptions failed, falling back: $envNote")
                OrtEnvironment.getEnvironment()
            }
            env = created
            return created
        }
    }

    fun sessionOptions(
        @Suppress("UNUSED_PARAMETER") ctx: Context? = BackgroundOperation.appContextOrNull(),
        optLevel: OrtSession.SessionOptions.OptLevel,
    ): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(optLevel)
        disablePerSessionThreads()
        addConfigEntry("session.intra_op.allow_spinning", "0")
        addConfigEntry("session.inter_op.allow_spinning", "0")
    }

    /**
     * Run one ONNX inference and record timing, concurrency, thermal state, slot wait, and late return
     * after caller timeout (via [onHandle]).
     */
    fun <T> runInference(
        ctx: Context?,
        model: String,
        shape: LongArray,
        slotWaitMs: Long = 0L,
        onHandle: ((ActiveHandle) -> Unit)? = null,
        block: () -> T,
    ): T {
        val shapeStr = shape.joinToString("x")
        val seq = seqGen.incrementAndGet()
        val startEpoch = clockEpochFn()
        val startMono = clockMonoFn()
        val thermal = thermalProvider(ctx)
        val handle: ActiveHandle
        val concAtStart: Int
        synchronized(metricsLock) {
            concAtStart = inFlight.incrementAndGet()
            handle = ActiveHandle(seq, model, shapeStr, startMono, startEpoch, concAtStart)
            activeHandles.add(handle)
            for (h in activeHandles) {
                if (concAtStart > h.peak) h.peak = concAtStart
            }
        }
        onHandle?.invoke(handle)
        var ok = true
        var errMsg: String? = null
        try {
            return block()
        } catch (t: Throwable) {
            ok = false
            errMsg = "${t.javaClass.simpleName}: ${t.message}"
            throw t
        } finally {
            val endEpoch = clockEpochFn()
            val endMono = clockMonoFn()
            val durMs = (endMono - startMono).coerceAtLeast(0L)
            val lateMs = if (handle.timedOutAtMonoMs > 0L) (endMono - handle.timedOutAtMonoMs).coerceAtLeast(1L) else 0L
            val peak: Int
            synchronized(metricsLock) {
                inFlight.decrementAndGet()
                activeHandles.remove(handle)
                peak = handle.peak
                if (lateMs > 0L) lateCount++
                if (ok) {
                    if (model.startsWith("detector:")) lastDetectorMs = durMs
                    else if (model.startsWith("ppocr")) lastOcrMs = durMs
                }
                val rec = Record(
                    seq = seq,
                    model = model,
                    startEpochMs = startEpoch,
                    endEpochMs = endEpoch,
                    durMs = durMs,
                    concurrentAtStart = concAtStart,
                    peakConcurrent = peak,
                    inputShape = shapeStr,
                    thermalStatus = thermal,
                    slotWaitMs = slotWaitMs.coerceAtLeast(0L),
                    lateAfterTimeoutMs = lateMs,
                    ok = ok,
                    err = errMsg,
                )
                if (records.size >= MAX_RECORDS) records.removeFirst()
                records.addLast(rec)
            }
            val lateNote = if (lateMs > 0L) " LATE(+${lateMs}ms after ${handle.timeoutBudgetMs}ms timeout)" else ""
            val waitNote = if (slotWaitMs > 0L) " slotWait=${slotWaitMs}ms" else ""
            AppLogger.info(
                TAG,
                "seq=#$seq model=$model dur=${durMs}ms conc=$concAtStart/$peak shape=$shapeStr thermal=$thermal$waitNote ok=$ok$lateNote" +
                    (errMsg?.let { " err=${it.take(120)}" } ?: ""),
            )
        }
    }

    /** Record a top-level late OCR completion (covering full recognize pipeline) when a timed-out reader finally returns. */
    fun recordLatePipelineCompletion(
        ctx: Context?,
        model: String,
        totalDurMs: Long,
        timeoutBudgetMs: Long,
        slotWaitMs: Long,
        shape: String,
        ok: Boolean,
        err: String? = null,
    ) {
        val nowEpoch = clockEpochFn()
        val thermal = thermalProvider(ctx)
        val lateMs = (totalDurMs - timeoutBudgetMs).coerceAtLeast(1L)
        val seq = seqGen.incrementAndGet()
        synchronized(metricsLock) {
            lateCount++
            val rec = Record(
                seq = seq,
                model = model,
                startEpochMs = (nowEpoch - totalDurMs).coerceAtLeast(0L),
                endEpochMs = nowEpoch,
                durMs = totalDurMs,
                concurrentAtStart = inFlight.get().coerceAtLeast(1),
                peakConcurrent = inFlight.get().coerceAtLeast(1),
                inputShape = shape,
                thermalStatus = thermal,
                slotWaitMs = slotWaitMs.coerceAtLeast(0L),
                lateAfterTimeoutMs = lateMs,
                ok = ok,
                err = err,
            )
            if (records.size >= MAX_RECORDS) records.removeFirst()
            records.addLast(rec)
        }
        AppLogger.warning(TAG, "LATE completion #$seq model=$model total=${totalDurMs}ms (+${lateMs}ms after ${timeoutBudgetMs}ms timeout) thermal=$thermal ok=$ok")
    }

    /** Currently in-flight inferences whose caller has already timed out (distinguishes "still stuck" from "finished late"). */
    fun stuckTimedOut(): List<Pair<ActiveHandle, Long>> = synchronized(metricsLock) {
        val now = clockMonoFn()
        activeHandles.filter { it.timedOutAtMonoMs > 0L }.map { it to (now - it.startMonoMs).coerceAtLeast(0L) }
    }

    fun recentCount(): Int = synchronized(metricsLock) { records.size }

    fun recent(limit: Int = MAX_RECORDS): List<Record> = synchronized(metricsLock) {
        if (limit <= 0 || records.isEmpty()) return emptyList()
        val n = min(limit, records.size)
        records.toList().takeLast(n)
    }

    fun clearRecords() = synchronized(metricsLock) { records.clear() }

    fun formatRecent(limit: Int = MAX_RECORDS): String {
        val list = recent(limit)
        val stuck = stuckTimedOut()
        val sb = StringBuilder()
        if (stuck.isNotEmpty()) {
            for ((h, elapsed) in stuck) {
                sb.append("  [STILL RUNNING after ${h.timeoutBudgetMs}ms timeout] #${h.seq} model=${h.model} shape=${h.inputShape} running=${elapsed}ms\n")
            }
        }
        if (list.isEmpty()) {
            if (sb.isEmpty()) return "  (none)"
            return sb.toString().trimEnd('\n')
        }
        for (rec in list) sb.append("  ").append(rec.formatLine()).append('\n')
        return sb.toString().trimEnd('\n')
    }

    internal fun resetForTests() {
        synchronized(metricsLock) {
            records.clear()
            activeHandles.clear()
            inFlight.set(0)
            seqGen.set(0L)
            lastOcrMs = 0L
            lastDetectorMs = 0L
            lateCount = 0
        }
        clockMonoFn = { SystemClock.elapsedRealtime() }
        clockEpochFn = { System.currentTimeMillis() }
        thermalProvider = { ctx -> readThermalStatus(ctx) }
    }
}
