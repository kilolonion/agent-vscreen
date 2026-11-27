package com.kilon.agentvscreen.core

import android.app.Application
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.host.Liveness
import com.kilon.agentvscreen.host.Readiness
import com.kilon.agentvscreen.host.Swallowed
import com.kilon.agentvscreen.host.quiet
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for P2-7a stability & background loop supervision:
 * [LoopWatchdog], [ThreadGuard.normalizeThreadName], [quiet] VirtualMachineError handling,
 * [Liveness.json], [Gate.statusJson], and [Readiness.evaluate] watchdog degradation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LoopWatchdogTest {

    @Test
    fun restartsWithExponentialBackoffAndRecoversOnCleanStop() {
        val now = AtomicLong(1_000L)
        val sleeps = CopyOnWriteArrayList<Long>()
        val dog = LoopWatchdog(
            clockMs = { now.get() },
            wallClockMs = { 100_000L + now.get() },
            sleepFn = { ms -> sleeps += ms; now.addAndGet(ms) },
        )
        val attempts = AtomicInteger(0)
        val recovered = CountDownLatch(1)

        val h = dog.startLoop("vscreen-test-recover", critical = true) { ctx ->
            val n = attempts.incrementAndGet()
            if (n <= 3) throw IllegalStateException("boom #$n")
            ctx.beat()
            recovered.countDown()
            ctx.stopCleanly()
        }

        assertTrue("loop should recover after 3 transient crashes", recovered.await(5, TimeUnit.SECONDS))
        // Wait briefly for thread finally block to mark clean stop
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (h.alive && System.nanoTime() < deadline) Thread.sleep(10)

        assertEquals(listOf(1_000L, 2_000L, 5_000L), sleeps.toList())
        assertFalse(h.tripped)
        assertFalse(h.alive)
        val snap = dog.snapshot().single { it.name == "vscreen-test-recover" }
        assertEquals(3, snap.restarts)
        assertFalse(snap.expected)
        assertFalse(snap.tripped)
        assertFalse(snap.unhealthy)
        assertTrue(dog.unhealthyCriticalNames().isEmpty())
    }

    @Test
    fun tripsCircuitBreakerAfterFiveCrashesWithinSixtySeconds() {
        val now = AtomicLong(10_000L)
        val sleeps = CopyOnWriteArrayList<Long>()
        val dog = LoopWatchdog(
            clockMs = { now.get() },
            wallClockMs = { 200_000L + now.get() },
            sleepFn = { ms -> sleeps += ms; now.addAndGet(ms) },
        )
        val attempts = AtomicInteger(0)
        val h = dog.startLoop("vscreen-test-trip", critical = true) {
            val n = attempts.incrementAndGet()
            throw RuntimeException("crash #$n")
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!h.tripped && System.nanoTime() < deadline) Thread.sleep(10)

        assertTrue("circuit breaker must trip on 5th crash in 60s", h.tripped)
        assertFalse(h.alive)
        assertEquals(5, attempts.get())
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 30_000L), sleeps.toList())
        val snap = dog.snapshot().single { it.name == "vscreen-test-trip" }
        assertEquals(5, snap.restarts)
        assertTrue(snap.tripped)
        assertTrue(snap.unhealthy)
        assertTrue(snap.lastError?.contains("RuntimeException: crash #5") == true)
        assertEquals(listOf("vscreen-test-trip"), dog.unhealthyCriticalNames())
    }

    @Test
    fun slidingWindowResetsBackoffAfterSixtySecondsQuiet() {
        val now = AtomicLong(0L)
        val dog = LoopWatchdog(
            clockMs = { now.get() },
            wallClockMs = { 300_000L + now.get() },
        )
        val ext = dog.registerExternal("vscreen-test-window", critical = true)
        assertEquals(1_000L, ext.recordError(RuntimeException("e1")))
        now.addAndGet(2_000L)
        assertEquals(2_000L, ext.recordError(RuntimeException("e2")))
        // Advance past the 60s window: backoff resets to 1s and window clears so it does not trip.
        now.addAndGet(LoopWatchdog.TRIP_WINDOW_MS + 1_000L)
        assertEquals(1_000L, ext.recordError(RuntimeException("e3")))
        assertFalse(ext.tripped)
        assertEquals(2_000L, ext.recordError(RuntimeException("e4")))
        assertEquals(5_000L, ext.recordError(RuntimeException("e5")))
        assertEquals(30_000L, ext.recordError(RuntimeException("e6")))
        assertEquals(-1L, ext.recordError(RuntimeException("e7")))
        assertTrue(ext.tripped)
    }

    @Test
    fun externalAliveProbeDetectsSilentThreadDeathAndCleanStopClearsHealth() {
        val dog = LoopWatchdog()
        val aliveFlag = AtomicBoolean(true)
        val ext = dog.registerExternal("minis-vscreen-frames", critical = true, aliveProbe = { aliveFlag.get() })
        ext.beat()
        assertTrue(dog.unhealthyCriticalNames().isEmpty())

        // Backing HandlerThread dies silently -> snapshot dynamically reports alive=false & unhealthy.
        aliveFlag.set(false)
        val dead = dog.snapshot().single { it.name == "minis-vscreen-frames" }
        assertFalse(dead.alive)
        assertTrue(dead.expected)
        assertTrue(dead.unhealthy)
        assertEquals(listOf("minis-vscreen-frames"), dog.unhealthyCriticalNames())

        // Rebuilt thread comes back alive.
        aliveFlag.set(true)
        ext.markAlive()
        assertTrue(dog.unhealthyCriticalNames().isEmpty())

        // Clean release clears expected state so Readiness does not warn after display release.
        aliveFlag.set(false)
        ext.markStopped()
        assertTrue(dog.unhealthyCriticalNames().isEmpty())
    }

    @Test
    fun interruptStopsCleanlyWithoutSpinningOrTripping() {
        val dog = LoopWatchdog()
        val started = CountDownLatch(1)
        val h = dog.startLoop("vscreen-test-interrupt", critical = true) { ctx ->
            started.countDown()
            while (ctx.active) {
                Thread.sleep(60_000L)
                ctx.beat()
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        h.stop()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (h.alive && System.nanoTime() < deadline) Thread.sleep(10)

        assertFalse(h.alive)
        assertFalse(h.tripped)
        assertTrue(dog.unhealthyCriticalNames().isEmpty())
        val snap = dog.snapshot().single { it.name == "vscreen-test-interrupt" }
        assertEquals(0, snap.restarts)
        assertNull(snap.lastError)
    }

    @Test
    fun quietRethrowsVirtualMachineErrorAndTracksSwallowedTotal() {
        val before = Swallowed.totalCount()
        assertNull(quiet("LoopWatchdogTest:nonfatal") { throw IllegalArgumentException("minor") })
        assertEquals(before + 1, Swallowed.totalCount())

        try {
            quiet("LoopWatchdogTest:vm") { throw OutOfMemoryError("simulated oom") }
            fail("quiet() must never swallow VirtualMachineError")
        } catch (e: OutOfMemoryError) {
            assertEquals("simulated oom", e.message)
        }
    }

    @Test
    fun threadGuardNormalizeThreadNamePreservesDistinctVScreenThreads() {
        assertEquals("vscreen-http-accept", ThreadGuard.normalizeThreadName("vscreen-http-accept"))
        assertEquals("vscreen-pub-accept", ThreadGuard.normalizeThreadName("vscreen-pub-accept"))
        assertEquals("vscreen-fingers", ThreadGuard.normalizeThreadName("vscreen-fingers"))
        assertEquals("minis-vscreen-frames", ThreadGuard.normalizeThreadName("minis-vscreen-frames"))
        assertEquals("vscreen-http", ThreadGuard.normalizeThreadName("vscreen-http-12"))
        assertEquals("pool-thread", ThreadGuard.normalizeThreadName("pool-3-thread-7"))
        assertEquals("DefaultDispatcher-worker", ThreadGuard.normalizeThreadName("DefaultDispatcher-worker-4"))
        assertEquals("OkHttp", ThreadGuard.normalizeThreadName("OkHttp https://secret.example.com/path?token=1"))
        assertEquals("unnamed", ThreadGuard.normalizeThreadName(""))
        assertEquals("unnamed", ThreadGuard.normalizeThreadName("   "))
    }

    @Test
    fun livenessGateAndReadinessReportWatchdogsAndDegradation() {
        val live = Liveness.json()
        assertTrue("Liveness.json contains watchdogs", live.has("watchdogs"))
        assertTrue("Liveness.json contains swallowed_total", live.has("swallowed_total"))

        val gate = Gate(service = null, startWatchThread = false)
        val gJson = gate.statusJson()
        assertTrue("Gate.statusJson contains watchdogs", gJson.has("watchdogs"))
        assertTrue("Gate.statusJson contains service_thread_deaths", gJson.has("service_thread_deaths"))

        // Remote watchdog JSON parser flags tripped or expected-dead critical loops only.
        val remoteArr = JSONArray()
            .put(JSONObject().put("name", "vscreen-reaper").put("critical", true).put("expected", true).put("alive", true).put("tripped", false))
            .put(JSONObject().put("name", "vscreen-fingers").put("critical", true).put("expected", true).put("alive", false).put("tripped", true))
            .put(JSONObject().put("name", "vscreen-sound").put("critical", true).put("expected", false).put("alive", false).put("tripped", false))
            .put(JSONObject().put("name", "vscreen-gate-watch").put("critical", true).put("expected", true).put("alive", false).put("tripped", false))
            .put(JSONObject().put("name", "vscreen-optional").put("critical", false).put("expected", true).put("alive", false).put("tripped", true))
        assertEquals(
            listOf("service/vscreen-fingers", "service/vscreen-gate-watch"),
            LoopWatchdog.unhealthyFromJson(remoteArr, prefix = "service/"),
        )

        // Readiness.evaluate surfaces watchdog_unhealthy cleanly in both ZH and EN (zero EN fullwidth punctuation).
        val baseInputs = Readiness.Inputs(
            serviceOn = true,
            serviceAlive = true,
            foreground = true,
            privilege = "READY",
            connection = "CONNECTED",
            needsReconnect = false,
            userStopped = false,
            frozen = false,
            remoteMode = "off",
            remoteUp = true,
            unhealthyWatchdogs = listOf("vscreen-liveness", "service/vscreen-fingers"),
        )
        val fullwidth = Regex("[，。；：（）“”‘’？！、【】]")
        val zhItems = I18n.with("zh") { Readiness.evaluate(baseInputs) }
        val enItems = I18n.with("en") { Readiness.evaluate(baseInputs) }
        val zhItem = zhItems.firstOrNull { it.code == "watchdog_unhealthy" }
        val enItem = enItems.firstOrNull { it.code == "watchdog_unhealthy" }
        assertNotNull(zhItem)
        assertNotNull(enItem)
        assertFalse(zhItem!!.blocking)
        assertTrue(zhItem.text.contains("vscreen-liveness"))
        assertTrue(enItem!!.text.contains("service/vscreen-fingers"))
        assertFalse("EN Readiness watchdog text must have zero fullwidth punctuation: ${enItem.text}", fullwidth.containsMatchIn(enItem.text))
    }
}
