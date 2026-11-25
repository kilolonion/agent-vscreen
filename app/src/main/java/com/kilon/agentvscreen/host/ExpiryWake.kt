package com.kilon.agentvscreen.host

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 2.0.0-beta11 (rainbow: an expired strong confirmation stayed in 等你处理 and could not be dismissed).
 *
 * A waiting request ends in one of two ways: an event (answered, cancelled, the agent left) or its time running out.
 * Every owner of waiting requests already republished on events, but nothing republished when time ran out, so an
 * expired request stayed in the published list until some unrelated event happened. This is the one place that turns
 * "time ran out" into an event: each owner, after it publishes, tells its wake the earliest moment one of its live
 * requests stops being live, and the wake calls the owner back at that moment so it prunes and republishes itself.
 *
 * Only the earliest due time is kept; a later [at] replaces it. [onWake] runs on the wake's own daemon thread and must
 * take the owner's lock itself.
 */
internal class ExpiryWake(name: String, private val onWake: () -> Unit) {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "expiry-$name").apply { isDaemon = true }
    }
    private var future: ScheduledFuture<*>? = null

    /** The due time of the scheduled wake, or null when none is scheduled (for tests and diagnostics). */
    @Volatile var dueAt: Long? = null
        private set

    /**
     * Schedule the next wake at [dueMs] (same clock as [nowMs]), or cancel it when [dueMs] is null. A small margin is
     * added so the owner's own "still live" check, which uses `<`, sees the request as expired when it runs.
     */
    @Synchronized fun at(dueMs: Long?, nowMs: Long) {
        if (dueMs == dueAt && future?.isDone == false) return
        future?.cancel(false)
        future = null
        dueAt = dueMs
        if (dueMs == null) return
        val delay = (dueMs - nowMs).coerceAtLeast(0L) + MARGIN_MS
        future = exec.schedule({
            synchronized(this) { if (dueAt == dueMs) dueAt = null }
            runCatching { onWake() }
        }, delay, TimeUnit.MILLISECONDS)
    }

    companion object {
        const val MARGIN_MS = 50L
    }
}
