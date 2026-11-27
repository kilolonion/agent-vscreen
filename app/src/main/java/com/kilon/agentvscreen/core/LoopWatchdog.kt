package com.kilon.agentvscreen.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Unified supervisor for critical background loops in both the app process and the privileged display-service
 * process.
 *
 * Every supervised loop gets:
 *  - non-fatal exception isolation with exponential backoff (`1s -> 2s -> 5s -> 30s`)
 *  - a sliding-window circuit breaker (`>= 5` crashes within `60s` marks `tripped = true` and stops spinning)
 *  - strict rethrow of [VirtualMachineError] (never masks OOM / stack overflow)
 *  - explicit clean-stop semantics (`handle.stop()`, `ctx.stopCleanly()`, `!shouldRun()`, or `InterruptedException`)
 *    so intentional shutdowns never pollute `Readiness`, while tripped or unexpectedly dead critical loops surface
 *    in `Liveness.json()`, `Gate.statusJson()`, `diag`, and `Readiness`.
 */
class LoopWatchdog(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val sleepFn: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** Immutable view of one supervised loop. */
    data class EntrySnapshot(
        val name: String,
        val critical: Boolean,
        val expected: Boolean,
        val alive: Boolean,
        val restarts: Int,
        val tripped: Boolean,
        val lastError: String?,
        val lastBeatAt: Long,
    ) {
        val unhealthy: Boolean get() = critical && (tripped || (expected && !alive))

        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("critical", critical)
            .put("expected", expected)
            .put("alive", alive)
            .put("restarts", restarts)
            .put("tripped", tripped)
            .put("last_error", lastError ?: JSONObject.NULL)
            .put("last_beat_at", lastBeatAt)
    }

    /** Lifecycle handle for a watchdog-managed thread loop. */
    interface Handle {
        val name: String
        val alive: Boolean
        val tripped: Boolean
        fun stop()
    }

    /** Execution context passed to [startLoop] bodies. */
    class LoopContext internal constructor(
        private val shouldRun: () -> Boolean,
        private val isStopped: () -> Boolean,
        private val onBeat: () -> Unit,
        private val onCleanStop: () -> Unit,
    ) {
        /** True while the loop is expected to keep running. */
        val active: Boolean
            get() = !isStopped() && !Thread.currentThread().isInterrupted && shouldRun()

        /** Record forward progress (heartbeat). */
        fun beat() = onBeat()

        /** Request a clean exit without triggering restart or degradation. */
        fun stopCleanly() = onCleanStop()
    }

    /** Handle for coroutine or HandlerThread loops managed outside [startLoop]. */
    class ExternalHandle internal constructor(
        private val owner: LoopWatchdog,
        private val record: Record,
        private val generation: Long,
    ) {
        val name: String get() = record.name
        val tripped: Boolean get() = synchronized(owner.lock) { record.generation == generation && record.tripped }

        fun beat() {
            synchronized(owner.lock) {
                if (record.generation != generation) return
                record.alive = true
                record.expected = true
                record.lastBeatAt = owner.wallClockMs()
            }
        }

        /**
         * Record a non-fatal failure in this external loop.
         * Returns the recommended backoff delay in milliseconds, or `-1L` when the circuit breaker has tripped.
         */
        fun recordError(t: Throwable): Long {
            if (t is VirtualMachineError) throw t
            return owner.recordFailure(record, generation, t)
        }

        fun markAlive() {
            synchronized(owner.lock) {
                if (record.generation != generation) return
                record.expected = true
                record.alive = true
                record.tripped = false
                record.lastBeatAt = owner.wallClockMs()
            }
        }

        fun markStopped() {
            synchronized(owner.lock) {
                if (record.generation != generation) return
                record.expected = false
                record.alive = false
                record.aliveProbe = null
            }
        }
    }

    internal class Record(
        val name: String,
        @Volatile var critical: Boolean,
    ) {
        var generation: Long = 0L
        var expected: Boolean = false
        var alive: Boolean = false
        var restarts: Int = 0
        var tripped: Boolean = false
        var lastError: String? = null
        var lastBeatAt: Long = 0L
        var lastCrashMonoMs: Long = 0L
        var consecutiveFailures: Int = 0
        val crashWindow = ArrayDeque<Long>()
        var currentStop: (() -> Unit)? = null
        var aliveProbe: (() -> Boolean)? = null
    }

    private val lock = Any()
    private val records = LinkedHashMap<String, Record>()
    @Volatile private var logger: ((String, String, Throwable?) -> Unit)? = null

    /** Attach a process-level log callback (e.g. AppLogger in app process, stderr in privileged process). */
    fun attachLogger(fn: (name: String, message: String, error: Throwable?) -> Unit) {
        logger = fn
    }

    /**
     * Start a supervised daemon thread for [name]. If a previous handle with the same [name] exists, it is
     * superseded cleanly via generation gating so late exits from the old thread cannot overwrite the new state.
     */
    fun startLoop(
        name: String,
        critical: Boolean = true,
        priority: Int = Thread.NORM_PRIORITY,
        shouldRun: () -> Boolean = { true },
        body: (LoopContext) -> Unit,
    ): Handle {
        val prevStop: (() -> Unit)?
        val rec: Record
        val gen: Long
        synchronized(lock) {
            rec = records.getOrPut(name) { Record(name, critical) }
            prevStop = rec.currentStop
            rec.critical = critical
            rec.generation++
            gen = rec.generation
            rec.expected = true
            rec.alive = true
            rec.tripped = false
            rec.consecutiveFailures = 0
            rec.crashWindow.clear()
            rec.aliveProbe = null
            rec.lastBeatAt = wallClockMs()
        }
        runCatching { prevStop?.invoke() }

        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = java.util.concurrent.atomic.AtomicReference<Thread?>(null)

        val stopFn: () -> Unit = {
            stopped.set(true)
            synchronized(lock) {
                if (rec.generation == gen) {
                    rec.expected = false
                }
            }
            runCatching { worker.get()?.interrupt() }
        }
        synchronized(lock) {
            if (rec.generation == gen) rec.currentStop = stopFn
        }

        val ctx = LoopContext(
            shouldRun = shouldRun,
            isStopped = { stopped.get() },
            onBeat = {
                synchronized(lock) {
                    if (rec.generation == gen) {
                        rec.alive = true
                        rec.lastBeatAt = wallClockMs()
                    }
                }
            },
            onCleanStop = {
                stopped.set(true)
                synchronized(lock) {
                    if (rec.generation == gen) rec.expected = false
                }
            },
        )

        val t = Thread({
            try {
                while (!stopped.get() && !Thread.currentThread().isInterrupted && shouldRun()) {
                    try {
                        body(ctx)
                        if (stopped.get() || Thread.currentThread().isInterrupted || !shouldRun()) break
                        val delay = recordFailure(rec, gen, IllegalStateException("loop exited unexpectedly"))
                        if (delay < 0L) break
                        if (!sleepBackoff(delay, shouldRun) { stopped.get() }) break
                    } catch (_: InterruptedException) {
                        break
                    } catch (vm: VirtualMachineError) {
                        synchronized(lock) {
                            if (rec.generation == gen) {
                                rec.alive = false
                                rec.lastError = formatError(vm)
                            }
                        }
                        log(name, "fatal VirtualMachineError", vm)
                        throw vm
                    } catch (e: Throwable) {
                        if (stopped.get() || Thread.currentThread().isInterrupted || !shouldRun()) break
                        val delay = recordFailure(rec, gen, e)
                        if (delay < 0L) break
                        if (!sleepBackoff(delay, shouldRun) { stopped.get() }) break
                    }
                }
            } finally {
                synchronized(lock) {
                    if (rec.generation == gen) {
                        rec.alive = false
                        if ((stopped.get() || !shouldRun()) && !rec.tripped) {
                            rec.expected = false
                        }
                        if (rec.currentStop === stopFn) rec.currentStop = null
                    }
                }
            }
        }, name).apply {
            isDaemon = true
            this.priority = priority
        }
        worker.set(t)
        t.start()

        return object : Handle {
            override val name: String = name
            override val alive: Boolean
                get() = synchronized(lock) { rec.generation == gen && rec.alive }
            override val tripped: Boolean
                get() = synchronized(lock) { rec.generation == gen && rec.tripped }
            override fun stop() = stopFn()
        }
    }

    /**
     * Register a coroutine or [android.os.HandlerThread]-backed loop whose execution happens outside [startLoop].
     * When [aliveProbe] is provided, [snapshot] dynamically reflects whether the backing thread is still alive.
     */
    fun registerExternal(
        name: String,
        critical: Boolean = true,
        aliveProbe: (() -> Boolean)? = null,
    ): ExternalHandle {
        val prevStop: (() -> Unit)?
        val rec: Record
        val gen: Long
        synchronized(lock) {
            rec = records.getOrPut(name) { Record(name, critical) }
            prevStop = rec.currentStop
            rec.currentStop = null
            rec.critical = critical
            rec.generation++
            gen = rec.generation
            rec.expected = true
            rec.alive = aliveProbe?.invoke() ?: true
            rec.tripped = false
            rec.consecutiveFailures = 0
            rec.crashWindow.clear()
            rec.aliveProbe = aliveProbe
            rec.lastBeatAt = wallClockMs()
        }
        runCatching { prevStop?.invoke() }
        return ExternalHandle(this, rec, gen)
    }

    /** Compute backoff delay in ms for the given 1-based [consecutiveFailures] count. */
    fun backoffMs(consecutiveFailures: Int): Long {
        val idx = (consecutiveFailures - 1).coerceIn(0, BACKOFF_MS.lastIndex)
        return BACKOFF_MS[idx]
    }

    private fun recordFailure(rec: Record, gen: Long, t: Throwable): Long {
        val now = clockMs()
        val trippedNow: Boolean
        val delayMs: Long
        val restartsNow: Int
        synchronized(lock) {
            if (rec.generation != gen) return -1L
            if (rec.lastCrashMonoMs > 0L && now - rec.lastCrashMonoMs >= TRIP_WINDOW_MS) {
                rec.consecutiveFailures = 0
            }
            rec.lastCrashMonoMs = now
            rec.restarts++
            restartsNow = rec.restarts
            rec.consecutiveFailures++
            rec.lastError = formatError(t)
            while (rec.crashWindow.isNotEmpty() && now - rec.crashWindow.first() >= TRIP_WINDOW_MS) {
                rec.crashWindow.removeFirst()
            }
            rec.crashWindow.addLast(now)
            if (rec.crashWindow.size >= TRIP_THRESHOLD) {
                rec.tripped = true
                rec.alive = false
                trippedNow = true
                delayMs = -1L
            } else {
                trippedNow = false
                delayMs = backoffMs(rec.consecutiveFailures)
            }
        }
        if (trippedNow) {
            log(rec.name, "tripped after $TRIP_THRESHOLD crashes within ${TRIP_WINDOW_MS / 1000}s (restarts=$restartsNow)", t)
        } else {
            log(rec.name, "restarting in ${delayMs}ms (restarts=$restartsNow)", t)
        }
        return delayMs
    }

    private fun sleepBackoff(delayMs: Long, shouldRun: () -> Boolean, isStopped: () -> Boolean): Boolean {
        if (delayMs <= 0L) return !isStopped() && shouldRun()
        return try {
            sleepFn(delayMs)
            !isStopped() && !Thread.currentThread().isInterrupted && shouldRun()
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun log(name: String, message: String, error: Throwable?) {
        runCatching { logger?.invoke(name, message, error) }
    }

    fun snapshot(): List<EntrySnapshot> {
        val copy = synchronized(lock) {
            records.values.map { r ->
                val probedAlive = if (r.expected && !r.tripped) r.aliveProbe else null
                EntrySnapshot(
                    name = r.name,
                    critical = r.critical,
                    expected = r.expected,
                    alive = r.alive,
                    restarts = r.restarts,
                    tripped = r.tripped,
                    lastError = r.lastError,
                    lastBeatAt = r.lastBeatAt,
                ) to probedAlive
            }
        }
        return copy.map { (snap, probe) ->
            if (probe != null) {
                val live = runCatching { probe() }.getOrDefault(false)
                if (live != snap.alive) snap.copy(alive = live) else snap
            } else {
                snap
            }
        }
    }

    /** Names of critical loops that are currently tripped or unexpectedly not alive. */
    fun unhealthyCriticalNames(): List<String> = snapshot().filter { it.unhealthy }.map { it.name }

    fun jsonArray(): JSONArray = JSONArray().apply {
        snapshot().forEach { put(it.toJson()) }
    }

    fun summary(): String {
        val list = snapshot()
        if (list.isEmpty()) return "none"
        return list.joinToString(", ") { e ->
            val st = when {
                e.tripped -> "TRIPPED"
                e.alive -> "up"
                e.expected -> "DEAD"
                else -> "stopped"
            }
            val extra = if (e.restarts > 0) "(restarts=${e.restarts})" else ""
            "${e.name}=$st$extra"
        }
    }

    internal fun resetForTests() {
        val stops = synchronized(lock) {
            records.values.mapNotNull { it.currentStop }.also { records.clear() }
        }
        stops.forEach { runCatching { it() } }
    }

    companion object {
        val BACKOFF_MS: LongArray = longArrayOf(1_000L, 2_000L, 5_000L, 30_000L)
        const val TRIP_WINDOW_MS: Long = 60_000L
        const val TRIP_THRESHOLD: Int = 5

        /** Process-wide watchdog registry (separate instances exist in app process vs privileged service process). */
        val process: LoopWatchdog = LoopWatchdog()

        internal fun formatError(t: Throwable): String =
            "${t.javaClass.simpleName}: ${t.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(160) ?: ""}".trimEnd(':', ' ')

        /** Parse remote watchdog JSON array (e.g. from `Gate.statusJson().optJSONArray("watchdogs")`). */
        fun unhealthyFromJson(arr: JSONArray?, prefix: String = ""): List<String> {
            if (arr == null || arr.length() == 0) return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val critical = o.optBoolean("critical", true)
                val tripped = o.optBoolean("tripped", false)
                val expected = o.optBoolean("expected", true)
                val alive = o.optBoolean("alive", true)
                if (critical && (tripped || (expected && !alive))) {
                    val n = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                    out += if (prefix.isNotEmpty()) "$prefix$n" else n
                }
            }
            return out
        }
    }
}
