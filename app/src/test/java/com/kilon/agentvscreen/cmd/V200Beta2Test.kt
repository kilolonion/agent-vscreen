package com.kilon.agentvscreen.cmd

import android.os.Parcel
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.ApiServer
import com.kilon.agentvscreen.api.PortHolder
import com.kilon.agentvscreen.core.Gate
import com.kilon.agentvscreen.core.VScreenDisplayService
import com.kilon.agentvscreen.host.Liveness
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 2.0.0-beta2: Gate TCP forwarder, wake/cooldown/503 NOT_READY, stale finger cancel, PortHolder and Liveness. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V200Beta2Test {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun readHttpRaw(port: Int, rawRequest: String = "GET /v2/ping HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"): String {
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2_000)
            s.soTimeout = 5_000
            s.getOutputStream().write(rawRequest.toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            val inp = s.getInputStream()
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }

    @Test
    fun heartbeatStalenessBoundariesAndRefresh() {
        val now = AtomicLong(10_000L)
        val gate = Gate(service = null, clock = { now.get() }, shell = { _, _ -> 0 }, startWatchThread = false)
        try {
            assertTrue("stale before start/beat", gate.isBeatStale())
            val p = gate.start(freePort(), 18775, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertTrue(p > 0)
            assertFalse("fresh immediately after start", gate.isBeatStale())
            now.addAndGet(Gate.BEAT_STALE_MS)
            assertFalse("exact BEAT_STALE_MS is still fresh", gate.isBeatStale())
            now.addAndGet(1L)
            assertTrue("past BEAT_STALE_MS is stale", gate.isBeatStale())
            gate.beat()
            assertFalse("beat refreshes freshness", gate.isBeatStale())
        } finally {
            gate.stop()
        }
    }

    @Test
    fun gateForwardsBytesUntouchedIncludingTailnetAuthHeaders() {
        val now = AtomicLong(1_000L)
        val seenReq = StringBuilder()
        val upstream = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val upThread = Thread {
            runCatching {
                upstream.accept().use { s ->
                    s.soTimeout = 3_000
                    s.getOutputStream().apply { write(Gate.ACCEPT_ACK_BYTE); flush() }
                    val buf = ByteArray(4096)
                    val n = s.getInputStream().read(buf)
                    if (n > 0) seenReq.append(String(buf, 0, n, Charsets.UTF_8))
                    val body = """{"ok":true,"via":"upstream"}"""
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    s.getOutputStream().write(resp.toByteArray(Charsets.UTF_8))
                    s.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }

        val gate = Gate(service = null, clock = { now.get() }, shell = { _, _ -> 0 }, startWatchThread = false)
        try {
            val gp = gate.start(freePort(), upstream.localPort, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertTrue(gp > 0)
            val raw = "POST /v2/run HTTP/1.1\r\nHost: 127.0.0.1:$gp\r\nX-VScreen-Tailnet: secret123\r\nX-VScreen-Peer: laptop\r\nAuthorization: Bearer tok\r\nContent-Length: 2\r\n\r\n{}"
            val reply = readHttpRaw(gp, raw)
            upThread.join(3_000)
            assertTrue(reply.contains("200 OK"))
            assertTrue(reply.contains("\"via\":\"upstream\""))
            assertEquals(raw, seenReq.toString())
        } finally {
            gate.stop()
            runCatching { upstream.close() }
        }
    }

    @Test
    fun staleWakeSdk34RunsUnfreezeAndStartForegroundService() {
        val now = AtomicLong(1_000L)
        val cmds = Collections.synchronizedList(ArrayList<List<String>>())
        val upstream = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val upThread = Thread {
            runCatching {
                upstream.accept().use { s ->
                    s.soTimeout = 3_000
                    s.getOutputStream().apply { write(Gate.ACCEPT_ACK_BYTE); flush() }
                    s.getInputStream().read(ByteArray(1024))
                    val body = """{"ok":true}"""
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }

        lateinit var gate: Gate
        gate = Gate(
            service = null,
            clock = { now.get() },
            shell = { argv, _ ->
                cmds += argv
                now.addAndGet(50L)
                0
            },
            sleepFn = { ms ->
                now.addAndGet(ms)
                // Simulate app waking up and sending a heartbeat on the first poll.
                gate.beat()
            },
            startWatchThread = false,
        )
        try {
            val gp = gate.start(freePort(), upstream.localPort, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertTrue(gp > 0)
            now.addAndGet(Gate.BEAT_STALE_MS + 500L)
            val reply = readHttpRaw(gp)
            upThread.join(3_000)
            assertTrue(reply, reply.startsWith("HTTP/1.1 200 OK"))
            assertEquals(
                listOf(
                    listOf("am", "unfreeze", "com.kilon.agentvscreen"),
                    listOf("am", "start-foreground-service", "-n", "com.kilon.agentvscreen/com.kilon.agentvscreen.host.HostService"),
                ),
                cmds,
            )
            val st = gate.statusJson()
            assertEquals(1L, st.getLong("stale_count"))
            assertEquals(1L, st.getLong("wake_ok"))
            assertEquals(0L, st.getLong("wake_fail"))
            assertEquals(0L, st.getLong("not_ready"))
            val recorded = st.getJSONArray("last_wake_cmds")
            assertEquals(2, recorded.length())
            assertEquals("am unfreeze com.kilon.agentvscreen", recorded.getString(0))
            assertTrue(recorded.getString(1).startsWith("am start-foreground-service -n "))
        } finally {
            gate.stop()
            runCatching { upstream.close() }
        }
    }

    @Test
    fun staleWakeSdk30SkipsUnfreeze() {
        val now = AtomicLong(1_000L)
        val cmds = Collections.synchronizedList(ArrayList<List<String>>())
        lateinit var gate: Gate
        gate = Gate(
            service = null,
            clock = { now.get() },
            shell = { argv, _ -> cmds += argv; 0 },
            sleepFn = { ms -> now.addAndGet(ms); gate.beat() },
            startWatchThread = false,
        )
        try {
            val gp = gate.start(freePort(), 18775, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 30)
            assertTrue(gp > 0)
            now.addAndGet(Gate.BEAT_STALE_MS + 100L)
            assertTrue(gate.ensureAwake())
            assertEquals(
                listOf(listOf("am", "start-foreground-service", "-n", "com.kilon.agentvscreen/com.kilon.agentvscreen.host.HostService")),
                cmds,
            )
            val recorded = gate.statusJson().getJSONArray("last_wake_cmds")
            assertEquals(1, recorded.length())
            assertFalse("SDK 30 must never run am unfreeze", recorded.getString(0).contains("unfreeze"))
        } finally {
            gate.stop()
        }
    }

    @Test
    fun freshBeatWithRefusedInternalPortWakesAndForwardsToNewInternalPort() {
        val now = AtomicLong(1_000L)
        val deadPort = freePort() // closed immediately -> ConnectException
        val newUpstream = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val upThread = Thread {
            runCatching {
                newUpstream.accept().use { s ->
                    s.soTimeout = 3_000
                    s.getOutputStream().apply { write(Gate.ACCEPT_ACK_BYTE); flush() }
                    s.getInputStream().read(ByteArray(1024))
                    val body = """{"ok":true,"reborn":true}"""
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }

        val cmds = Collections.synchronizedList(ArrayList<List<String>>())
        lateinit var gate: Gate
        var publicPort = 0
        gate = Gate(
            service = null,
            clock = { now.get() },
            shell = { argv, _ -> cmds += argv; 0 },
            sleepFn = { ms ->
                now.addAndGet(ms)
                // Simulate newly started app process calling gateStart with the new internal port.
                gate.start(publicPort, newUpstream.localPort, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            },
            startWatchThread = false,
        )
        try {
            publicPort = gate.start(freePort(), deadPort, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertTrue(publicPort > 0)
            // Heartbeat is still fresh (0ms elapsed), but deadPort refuses connections.
            assertFalse(gate.isBeatStale())
            val reply = readHttpRaw(publicPort)
            upThread.join(3_000)
            assertTrue(reply, reply.contains("\"reborn\":true"))
            assertEquals(2, cmds.size)
            assertEquals(1L, gate.statusJson().getLong("wake_ok"))
        } finally {
            gate.stop()
            runCatching { newUpstream.close() }
        }
    }

    @Test
    fun wakeFailureReturns503NotReadyAndEnforcesThreeFailCooldown() {
        val now = AtomicLong(1_000L)
        val wakeAttempts = AtomicInteger(0)
        val gate = Gate(
            service = null,
            clock = { now.get() },
            shell = { argv, _ ->
                if (argv.firstOrNull() == "am" && argv.getOrNull(1) == "start-foreground-service") {
                    wakeAttempts.incrementAndGet()
                }
                0
            },
            sleepFn = { ms -> now.addAndGet(ms) },
            startWatchThread = false,
        )
        try {
            val gp = gate.start(freePort(), freePort(), "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertTrue(gp > 0)
            now.addAndGet(Gate.BEAT_STALE_MS + 100L)

            // 3 consecutive failures trigger 60s cooldown.
            repeat(3) { idx ->
                val resp = readHttpRaw(gp)
                assertTrue("attempt ${idx + 1} HTTP 503", resp.startsWith("HTTP/1.1 503 Service Unavailable\r\n"))
                assertTrue("has Content-Type", resp.contains("Content-Type: application/json; charset=utf-8\r\n"))
                assertTrue("has Content-Length", resp.contains("Content-Length: "))
                assertTrue("has Retry-After", resp.contains("Retry-After: 3\r\n"))
                assertTrue("has Connection: close", resp.contains("Connection: close\r\n"))
                val bodyStr = resp.substringAfter("\r\n\r\n")
                val json = JSONObject(bodyStr)
                assertEquals(5, json.length())
                assertFalse(json.getBoolean("ok"))
                assertEquals("NOT_READY", json.getString("code"))
                assertEquals(1, json.getInt("exit"))
                assertEquals("ask_user", json.getString("next"))
                assertEquals(Gate.NOT_READY_TEXT, json.getString("text"))
            }
            assertEquals(3, wakeAttempts.get())
            assertTrue("cooldown active after 3 failures", gate.isInCooldown())

            // 4th connection within 60s must skip shell wake and return 503 immediately.
            now.addAndGet(10_000L)
            val resp4 = readHttpRaw(gp)
            assertTrue(resp4.startsWith("HTTP/1.1 503 Service Unavailable\r\n"))
            assertEquals("no wake during cooldown", 3, wakeAttempts.get())
            val stCooldown = gate.statusJson()
            assertEquals(3L, stCooldown.getLong("wake_fail"))
            assertEquals(4L, stCooldown.getLong("not_ready"))

            // Advancing past COOLDOWN_MS (60s) lifts cooldown and attempts wake again.
            now.addAndGet(Gate.COOLDOWN_MS)
            assertFalse(gate.isInCooldown())
            val resp5 = readHttpRaw(gp)
            assertTrue(resp5.startsWith("HTTP/1.1 503 Service Unavailable\r\n"))
            assertEquals("wake resumed after cooldown", 4, wakeAttempts.get())

            // Re-enter cooldown (2 more fails), then verify beat() immediately clears cooldown.
            readHttpRaw(gp)
            readHttpRaw(gp)
            assertTrue(gate.isInCooldown())
            gate.beat()
            assertFalse("beat clears cooldown immediately", gate.isInCooldown())
        } finally {
            gate.stop()
        }
    }

    @Test
    fun concurrentStaleConnectionsShareSingleFlightWake() {
        val now = AtomicLong(1_000L)
        val wakeStarts = AtomicInteger(0)
        val inShell = CountDownLatch(1)
        val releaseShell = CountDownLatch(1)
        lateinit var gate: Gate
        gate = Gate(
            service = null,
            clock = { now.get() },
            shell = { argv, _ ->
                if (argv.getOrNull(1) == "start-foreground-service") {
                    wakeStarts.incrementAndGet()
                    inShell.countDown()
                    releaseShell.await(3, TimeUnit.SECONDS)
                    gate.beat()
                }
                0
            },
            sleepFn = { ms -> now.addAndGet(ms) },
            startWatchThread = false,
        )
        try {
            val gp = gate.start(freePort(), 18775, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertTrue(gp > 0)
            now.addAndGet(Gate.BEAT_STALE_MS + 100L)

            var r1 = false; var r2 = false
            val t1 = Thread { r1 = gate.ensureAwake() }
            val t2 = Thread { r2 = gate.ensureAwake() }
            t1.start()
            assertTrue(inShell.await(2, TimeUnit.SECONDS))
            t2.start()
            Thread.sleep(80)
            releaseShell.countDown()
            t1.join(3_000); t2.join(3_000)
            assertTrue(r1 && r2)
            assertEquals("only one wake ran for concurrent callers", 1, wakeStarts.get())
        } finally {
            gate.stop()
        }
    }

    @Test
    fun startReusesListenerInSameSpanAndRebindsWhenSpanChanges() {
        val now = AtomicLong(1_000L)
        val gate = Gate(service = null, clock = { now.get() }, shell = { _, _ -> 0 }, startWatchThread = false)
        try {
            val base1 = freePort()
            val p1 = gate.start(base1, 18775, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertEquals(base1, p1)
            now.addAndGet(Gate.BEAT_STALE_MS + 500L)
            assertTrue(gate.isBeatStale())
            // Calling start again with same wantPort reuses the port, updates internalPort, and refreshes beat.
            val p2 = gate.start(base1, 18776, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertEquals(p1, p2)
            assertFalse("reused start refreshes heartbeat", gate.isBeatStale())

            // Changing wantPort rebinds to the new span.
            val base2 = freePort()
            val p3 = gate.start(base2, 18777, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertEquals(base2, p3)
            if (base1 != base2) assertNotEquals(p1, p3)
        } finally {
            gate.stop()
        }
    }

    @Test
    fun staleHeartbeatCancelsAgentFingersOncePerEpisodeOnlyWhenAppUnresponsive() {
        val now = AtomicLong(1_000L)
        val cancels = AtomicInteger(0)
        val responsive = java.util.concurrent.atomic.AtomicBoolean(true)
        val gate = Gate(
            service = null,
            clock = { now.get() },
            shell = { _, _ -> 0 },
            cancelAgentFingers = { cancels.incrementAndGet() },
            startWatchThread = false,
            appResponsive = { responsive.get() },
        )
        try {
            val gp = gate.start(freePort(), 18775, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertTrue(gp > 0)
            assertFalse(gate.checkStaleFingers())
            assertEquals(0, cancels.get())

            // Stale heartbeat alone, while internal port still responds, must NOT cancel fingers.
            now.addAndGet(Gate.BEAT_STALE_MS + 1L)
            assertFalse("stale heartbeat with responsive app must not cancel fingers", gate.checkStaleFingers())
            assertEquals(0, cancels.get())

            // Once both signals hold (stale + unresponsive), cancel fires once for this stale episode.
            responsive.set(false)
            assertTrue("first check with stale + unresponsive cancels fingers", gate.checkStaleFingers())
            assertEquals(1, cancels.get())
            assertFalse("second check in same stale episode does not repeat", gate.checkStaleFingers())
            assertEquals(1, cancels.get())

            gate.beat()
            assertFalse(gate.checkStaleFingers())
            now.addAndGet(Gate.BEAT_STALE_MS + 1L)
            assertTrue("new stale episode cancels again", gate.checkStaleFingers())
            assertEquals(2, cancels.get())
        } finally {
            gate.stop()
        }
    }

    @Test
    fun serviceGateBinderTransactionLifecycleAndProtocolVersion() {
        assertEquals(16, VScreenDisplayService.PROTOCOL)
        assertEquals(VScreenDisplayService.GATE, VScreenDisplayService.LAST_CALL)
        val svc = VScreenDisplayService()
        val wantPort = freePort()

        fun transactGate(write: (Parcel) -> Unit, read: (Parcel) -> Unit) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                write(data)
                assertTrue(svc.transact(VScreenDisplayService.GATE, data, reply, 0))
                reply.setDataPosition(0)
                assertEquals(0, reply.readInt())
                read(reply)
            } finally {
                data.recycle(); reply.recycle()
            }
        }

        try {
            var bound = -1
            transactGate({
                it.writeInt(0); it.writeInt(wantPort); it.writeInt(18775)
                it.writeString("com.kilon.agentvscreen"); it.writeString("com.kilon.agentvscreen"); it.writeInt(34)
            }) { bound = it.readInt() }
            assertEquals(wantPort, bound)

            transactGate({ it.writeInt(1) }) {}

            var statusStr = ""
            transactGate({ it.writeInt(3) }) { statusStr = it.readString().orEmpty() }
            val st = JSONObject(statusStr)
            assertEquals(wantPort, st.getInt("port"))
            assertTrue(st.has("stale_count") && st.has("wake_ok") && st.has("wake_fail") && st.has("not_ready") && st.has("last_wake_ms") && st.has("last_wake_cmds"))
        } finally {
            transactGate({ it.writeInt(2) }) {}
        }

        var afterStop = ""
        transactGate({ it.writeInt(3) }) { afterStop = it.readString().orEmpty() }
        assertEquals(0, JSONObject(afterStop).getInt("port"))
    }

    @Test
    fun portHolderRecognizesOwnGateForShellAndRootUidsOnly() {
        assertTrue(PortHolder.isOwnGate(uid = 2000, port = 18765, gatePort = 18765))
        assertTrue(PortHolder.isOwnGate(uid = 0, port = 18766, gatePort = 18766))
        assertFalse(PortHolder.isOwnGate(uid = 2000, port = 18765, gatePort = 18766))
        assertFalse(PortHolder.isOwnGate(uid = 2000, port = 18765, gatePort = 0))
        assertFalse(PortHolder.isOwnGate(uid = 10234, port = 18765, gatePort = 18765))
    }

    @Test
    fun livenessJsonAndDiagReportGateNullWhenOff() {
        com.kilon.agentvscreen.core.VScreenOcr.resetSelfTestForTests(ctx)
        try {
            val j = Liveness.json()
            assertTrue("Liveness.json contains gate key", j.has("gate"))
            assertTrue("gate is null when Gate is off", j.isNull("gate"))
            assertTrue("Liveness.json contains ocr_selftest key", j.has("ocr_selftest"))
            val diag = diagText(ctx)
            assertTrue("diagText includes gate line", diag.contains("gate: off"))
        } finally {
            com.kilon.agentvscreen.core.VScreenOcr.resetSelfTestForTests(ctx)
        }
    }

    @Test
    fun manifestExportsHostServiceWithDumpAndKeepsNoteActionsPrivate() {
        val pm = ctx.packageManager
        val svcInfo = pm.getServiceInfo(android.content.ComponentName(ctx, com.kilon.agentvscreen.host.HostService::class.java), 0)
        assertTrue("HostService must be exported for uid 2000 wake", svcInfo.exported)
        assertEquals("android.permission.DUMP", svcInfo.permission)
        val rcvInfo = pm.getReceiverInfo(android.content.ComponentName(ctx, com.kilon.agentvscreen.host.NoteActions::class.java), 0)
        assertFalse("NoteActions must NOT be exported", rcvInfo.exported)
    }

    @Test
    fun hostServiceBlockedOnCreateExitsCleanlyWithoutStartingServerOrSelfTest() {
        com.kilon.agentvscreen.core.VScreenOcr.resetSelfTestForTests(ctx)
        val ocrCalls = AtomicInteger(0)
        com.kilon.agentvscreen.core.VScreenOcr.mlkitSelfTestRunner = { ocrCalls.incrementAndGet(); "ok" to "ML Kit: ok" }
        com.kilon.agentvscreen.core.VScreenOcr.paddleSelfTestRunner = { ocrCalls.incrementAndGet(); "ok" to "PP-OCRv6: ok" }
        com.kilon.agentvscreen.host.HostService.dropServer()
        com.kilon.agentvscreen.host.Prefs.setServiceOn(ctx, false)
        com.kilon.agentvscreen.host.Prefs.setBlocked(ctx, true)
        try {
            val controller = org.robolectric.Robolectric.buildService(com.kilon.agentvscreen.host.HostService::class.java)
            controller.create()
            assertFalse("BLOCKED HostService must not mark isAlive", com.kilon.agentvscreen.host.HostService.isAlive)
            assertEquals("BLOCKED HostService must not start ApiServer", null, com.kilon.agentvscreen.host.HostService.server)
            val startRes = controller.get().onStartCommand(android.content.Intent(ctx, com.kilon.agentvscreen.host.HostService::class.java), 0, 1)
            assertEquals(android.app.Service.START_NOT_STICKY, startRes)
            assertEquals("BLOCKED HostService must not start OCR self-test", 0, ocrCalls.get())
            controller.destroy()
            assertFalse(com.kilon.agentvscreen.host.Liveness.foreground)
        } finally {
            com.kilon.agentvscreen.host.Prefs.setBlocked(ctx, false)
            com.kilon.agentvscreen.host.Prefs.setServiceOn(ctx, true)
            com.kilon.agentvscreen.core.VScreenOcr.resetSelfTestForTests(ctx)
        }
    }

    @Test
    fun hostServiceIgnoresSpoofedActionsWhileNoteActionsHandlesThem() {
        com.kilon.agentvscreen.host.Prefs.setBlocked(ctx, false)
        com.kilon.agentvscreen.host.Prefs.setServiceOn(ctx, true)
        com.kilon.agentvscreen.host.UserAsk.reset()
        com.kilon.agentvscreen.core.VScreenOcr.resetSelfTestForTests(ctx)
        com.kilon.agentvscreen.core.VScreenOcr.mlkitSelfTestRunner = { "ok" to "ML Kit: ok" }
        com.kilon.agentvscreen.core.VScreenOcr.paddleSelfTestRunner = { "ok" to "PP-OCRv6: ok" }
        val controller = org.robolectric.Robolectric.buildService(com.kilon.agentvscreen.host.HostService::class.java)
        try {
            controller.create()
            val ask = com.kilon.agentvscreen.host.UserAsk.create(ctx, com.kilon.agentvscreen.host.UserAsk.Kind.CONFIRM, "k", "Approve?", "detail")
            // Spoofed Consent.ACTION delivered to exported HostService must be ignored.
            controller.get().onStartCommand(
                android.content.Intent(ctx, com.kilon.agentvscreen.host.HostService::class.java)
                    .setAction(com.kilon.agentvscreen.host.Consent.ACTION)
                    .putExtra("id", ask.id)
                    .putExtra("allow", true),
                0,
                1,
            )
            assertEquals("HostService must not answer Consent.ACTION", null, ask.answer)

            // Spoofed ACTION_STOP delivered to exported HostService must be ignored.
            controller.get().onStartCommand(
                android.content.Intent(ctx, com.kilon.agentvscreen.host.HostService::class.java)
                    .setAction(com.kilon.agentvscreen.host.HostService.ACTION_STOP),
                0,
                2,
            )
            assertNotEquals(com.kilon.agentvscreen.host.ServiceControl.State.BLOCKED, com.kilon.agentvscreen.host.ServiceControl.now(ctx))
            assertTrue(com.kilon.agentvscreen.host.HostService.isAlive)

            // Non-exported NoteActions DOES answer Consent.ACTION.
            com.kilon.agentvscreen.host.NoteActions().onReceive(
                ctx,
                android.content.Intent(ctx, com.kilon.agentvscreen.host.NoteActions::class.java)
                    .setAction(com.kilon.agentvscreen.host.Consent.ACTION)
                    .putExtra("id", ask.id)
                    .putExtra("allow", true),
            )
            assertEquals(true, ask.answer)
        } finally {
            controller.destroy()
            com.kilon.agentvscreen.host.HostService.dropServer()
            com.kilon.agentvscreen.host.Prefs.setBlocked(ctx, false)
            com.kilon.agentvscreen.host.Prefs.setServiceOn(ctx, true)
            com.kilon.agentvscreen.host.UserAsk.reset()
            com.kilon.agentvscreen.core.VScreenOcr.resetSelfTestForTests(ctx)
        }
    }

    @Test
    fun ocrSelfTestResidualRunningMarkRecordsCrashedAndDisablesEngine() {
        com.kilon.agentvscreen.core.VScreenOcr.resetSelfTestForTests(ctx)
        val ver = AtomicInteger(59)
        val mlCalls = AtomicInteger(0)
        val pdCalls = AtomicInteger(0)
        com.kilon.agentvscreen.core.VScreenOcr.versionCodeProvider = { ver.get() }
        com.kilon.agentvscreen.core.VScreenOcr.mlkitSelfTestRunner = { mlCalls.incrementAndGet(); "ok" to "ML Kit: ok 4/4" }
        com.kilon.agentvscreen.core.VScreenOcr.paddleSelfTestRunner = { pdCalls.incrementAndGet(); "ok" to "PP-OCRv6: ok 4/4" }
        try {
            // Simulate a native crash (SIGABRT) while ML Kit was running on versionCode 59.
            val prefs = ctx.getSharedPreferences("agent_vscreen_ocr_selftest", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("ver", 59).putString("running", "mlkit@59").commit()

            com.kilon.agentvscreen.core.VScreenOcr.recoverIfNeeded(ctx)
            assertTrue("ML Kit must be disabled after residual running mark", com.kilon.agentvscreen.core.VScreenOcr.isMlkitDisabled(ctx))
            assertFalse("PP-OCRv6 must stay enabled", com.kilon.agentvscreen.core.VScreenOcr.isPaddleDisabled(ctx))
            assertTrue("report records ML Kit CRASHED", com.kilon.agentvscreen.core.VScreenOcr.selfTestReport.orEmpty().contains("ML Kit: CRASHED"))
            assertEquals(null, prefs.getString("running", null))

            // Startup self-test should skip crashed ML Kit and run only PP-OCRv6.
            val started = com.kilon.agentvscreen.core.VScreenOcr.maybeStartSelfTest(ctx)
            assertTrue(started)
            val deadline = System.currentTimeMillis() + 5_000L
            while (com.kilon.agentvscreen.core.VScreenOcr.selfTestStatusJson(ctx).getString("state") == "running" && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals("crashed ML Kit must not be re-run", 0, mlCalls.get())
            assertEquals("PP-OCRv6 should run once", 1, pdCalls.get())
            val st = com.kilon.agentvscreen.core.VScreenOcr.selfTestStatusJson(ctx)
            assertEquals("crashed", st.getString("state"))
            assertEquals("mlkit", st.getString("engine"))
            assertTrue("engineInfo mentions ML Kit crash", com.kilon.agentvscreen.core.VScreenOcr.engineInfo().contains("ML Kit"))
            val readiness = com.kilon.agentvscreen.host.Readiness.now(ctx)
            assertTrue("Readiness reports ocr_degraded", readiness.any { it.code == "ocr_degraded" && !it.blocking })

            // Subsequent startup on same versionCode must NOT re-run self-test.
            assertFalse("completed versionCode must not re-run self-test", com.kilon.agentvscreen.core.VScreenOcr.maybeStartSelfTest(ctx))
            assertEquals(0, mlCalls.get())
            assertEquals(1, pdCalls.get())

            // Both engines crashed -> OCR unavailable in Readiness and recognize() refuses both.
            prefs.edit().putInt("ver", 59).putString("running", "paddle@59").commit()
            com.kilon.agentvscreen.core.VScreenOcr.resetSelfTestForTests(ctx, clearPrefs = false)
            com.kilon.agentvscreen.core.VScreenOcr.versionCodeProvider = { ver.get() }
            com.kilon.agentvscreen.core.VScreenOcr.mlkitSelfTestRunner = { mlCalls.incrementAndGet(); "ok" to "ML Kit: ok 4/4" }
            com.kilon.agentvscreen.core.VScreenOcr.paddleSelfTestRunner = { pdCalls.incrementAndGet(); "ok" to "PP-OCRv6: ok 4/4" }
            com.kilon.agentvscreen.core.VScreenOcr.recoverIfNeeded(ctx)
            assertTrue(com.kilon.agentvscreen.core.VScreenOcr.isMlkitDisabled(ctx))
            assertTrue(com.kilon.agentvscreen.core.VScreenOcr.isPaddleDisabled(ctx))
            assertFalse(com.kilon.agentvscreen.core.VScreenOcr.accurateEnabled())
            val bothReadiness = com.kilon.agentvscreen.host.Readiness.now(ctx)
            assertTrue("Readiness reports ocr_unavailable", bothReadiness.any { it.code == "ocr_unavailable" && !it.blocking })
            val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val res = com.kilon.agentvscreen.core.VScreenOcr.recognize(1, bmp, 1L, accurate = true)
                assertTrue("recognize returns error when both engines crashed", !res.error.isNullOrBlank())
            } finally {
                bmp.recycle()
            }

            // Manual diag (`selfTest`) clears crashed state and re-runs both engines.
            val manualReport = com.kilon.agentvscreen.core.VScreenOcr.selfTest(ctx)
            assertTrue(manualReport.contains("ML Kit: ok"))
            assertTrue(manualReport.contains("PP-OCRv6: ok"))
            assertFalse(com.kilon.agentvscreen.core.VScreenOcr.isMlkitDisabled(ctx))
            assertFalse(com.kilon.agentvscreen.core.VScreenOcr.isPaddleDisabled(ctx))
            assertEquals("done", com.kilon.agentvscreen.core.VScreenOcr.selfTestStatusJson(ctx).getString("state"))
            assertEquals(1, mlCalls.get())
            assertEquals(2, pdCalls.get())

            // Version upgrade (59 -> 60) clears previous results and allows self-test to run again.
            ver.set(60)
            assertTrue("new versionCode should run self-test again", com.kilon.agentvscreen.core.VScreenOcr.maybeStartSelfTest(ctx))
            val dl2 = System.currentTimeMillis() + 5_000L
            while (com.kilon.agentvscreen.core.VScreenOcr.selfTestStatusJson(ctx).getString("state") == "running" && System.currentTimeMillis() < dl2) {
                Thread.sleep(20)
            }
            assertEquals("done", com.kilon.agentvscreen.core.VScreenOcr.selfTestStatusJson(ctx).getString("state"))
            assertEquals(2, mlCalls.get())
            assertEquals(3, pdCalls.get())
        } finally {
            com.kilon.agentvscreen.core.VScreenOcr.resetSelfTestForTests(ctx)
        }
    }

    @Test
    fun apiServerBehindGatePreservesAuthenticationAndDirectModeOmitsAcceptAck() {
        ApiConfig.init(ctx)
        com.kilon.agentvscreen.api.Tokens.init(ctx)
        com.kilon.agentvscreen.host.Audit.init(ctx)
        val aliveField = com.kilon.agentvscreen.host.HostService::class.java.getDeclaredField("isAlive").apply { isAccessible = true }
        val wasAlive = aliveField.getBoolean(null)
        aliveField.setBoolean(null, true)
        val srv = ApiServer(ctx)
        val directSrv = ApiServer(ctx)
        val gate = Gate(service = null, clock = { System.currentTimeMillis() }, shell = { _, _ -> 0 }, startWatchThread = false)
        try {
            // Internal-port mode emits 0x06 accept-ack for Gate and preserves HTTP authentication.
            assertTrue(srv.startInternalForTests(freePort()))
            assertTrue(srv.internalPort > 0)
            val gp = gate.start(freePort(), srv.internalPort, ctx.packageName, ctx.packageName, 34)
            assertTrue(gp > 0)

            // Unauthenticated /v2/run through Gate must still be rejected 401 by ApiServer.
            val unauth = (URL("http://127.0.0.1:$gp/v2/run").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Host", "127.0.0.1:$gp")
                setRequestProperty("Content-Type", "application/json")
            }
            unauth.outputStream.use { it.write("""{"args":["status"]}""".toByteArray()) }
            assertEquals(401, unauth.responseCode)

            // Unauthenticated /v2/ping through Gate succeeds cleanly (0x06 consumed by Gate, never leaked to client).
            val ping = (URL("http://127.0.0.1:$gp/v2/ping").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                setRequestProperty("Host", "127.0.0.1:$gp")
            }
            assertEquals(200, ping.responseCode)
            val body = JSONObject(ping.inputStream.bufferedReader().readText())
            assertTrue(body.getBoolean("ok"))

            // Direct public-port mode (without Gate) must NOT write 0x06 accept-ack.
            directSrv.start(freePort())
            assertTrue(directSrv.boundPort > 0)
            val directRaw = readHttpRaw(directSrv.boundPort)
            assertTrue("direct public port must start with HTTP/1.1, not 0x06", directRaw.startsWith("HTTP/1.1 200 OK"))
        } finally {
            gate.stop()
            srv.stop()
            directSrv.stop()
            aliveField.setBoolean(null, wasAlive)
        }
    }

    @Test
    fun acceptAckScenarioA_disconnectBeforeAckSendsZeroBytesToFirstUpstreamAndFullRequestToSecond() {
        val now = AtomicLong(1_000L)
        val firstUpstream = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val secondUpstream = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val firstBytesRead = AtomicInteger(0)
        val secondReq = StringBuilder()

        // First upstream accepts TCP (SIGKILL teardown window), waits 50ms to verify 0 client bytes arrive, then closes without sending 0x06.
        val t1 = Thread {
            runCatching {
                firstUpstream.accept().use { s ->
                    firstUpstream.close()
                    s.soTimeout = 50
                    val n = try { s.getInputStream().read(ByteArray(64)) } catch (_: java.net.SocketTimeoutException) { 0 }
                    if (n > 0) firstBytesRead.addAndGet(n)
                }
            }
        }.apply { isDaemon = true; start() }

        val t2 = Thread {
            runCatching {
                secondUpstream.accept().use { s ->
                    s.soTimeout = 3_000
                    s.getOutputStream().apply { write(Gate.ACCEPT_ACK_BYTE); flush() }
                    val buf = ByteArray(4096)
                    val n = s.getInputStream().read(buf)
                    if (n > 0) secondReq.append(String(buf, 0, n, Charsets.UTF_8))
                    val body = """{"ok":true,"upstream":2}"""
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }

        lateinit var gate: Gate
        val wantPort = freePort()
        gate = Gate(
            service = null,
            clock = { now.get() },
            shell = { cmd, _ ->
                if (cmd.contains("start-foreground-service")) {
                    Thread {
                        Thread.sleep(20)
                        gate.start(wantPort, secondUpstream.localPort, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
                    }.start()
                }
                0
            },
            startWatchThread = false,
        )
        try {
            val gp = gate.start(wantPort, firstUpstream.localPort, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertEquals(wantPort, gp)
            val rawReq = "POST /v2/run HTTP/1.1\r\nHost: 127.0.0.1:$gp\r\nX-VScreen-Tailnet: sec\r\nContent-Length: 16\r\n\r\n{\"cmd\":\"status\"}"
            val reply = readHttpRaw(gp, rawReq)
            t1.join(3_000)
            t2.join(3_000)
            assertEquals("first upstream must receive 0 client bytes before ack", 0, firstBytesRead.get())
            assertEquals("second upstream must receive full request untouched", rawReq, secondReq.toString())
            assertTrue("expected 200 OK from second upstream, got: $reply", reply.startsWith("HTTP/1.1 200 OK"))
            assertTrue(reply.contains("\"upstream\":2"))
            assertEquals(1L, gate.statusJson().getLong("wake_ok"))
        } finally {
            gate.stop()
            runCatching { firstUpstream.close() }
            runCatching { secondUpstream.close() }
        }
    }

    @Test
    fun acceptAckScenarioB_disconnectAfterAckBeforeReplyReturns503HostRestartedWithoutResend() {
        val now = AtomicLong(1_000L)
        val crashingUpstream = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val wokenUpstream = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val wokenConnections = AtomicInteger(0)
        val bgWakeRan = CountDownLatch(1)

        // Upstream sends 0x06, reads the client's command, then exits before writing any response byte.
        val tCrash = Thread {
            runCatching {
                crashingUpstream.accept().use { s ->
                    s.soTimeout = 3_000
                    s.getOutputStream().apply { write(Gate.ACCEPT_ACK_BYTE); flush() }
                    val n = s.getInputStream().read(ByteArray(4096))
                    assertTrue("upstream must read client request before crashing", n > 0)
                    // Crash without sending any response bytes!
                }
            }
        }.apply { isDaemon = true; start() }

        // Background-woken upstream must NEVER receive the failed command again.
        val tWoken = Thread {
            runCatching {
                wokenUpstream.soTimeout = 600
                wokenUpstream.accept().use {
                    wokenConnections.incrementAndGet()
                }
            }
        }.apply { isDaemon = true; start() }

        lateinit var gate: Gate
        val wantPort = freePort()
        gate = Gate(
            service = null,
            clock = { now.get() },
            shell = { cmd, _ ->
                if (cmd.contains("start-foreground-service")) {
                    gate.start(wantPort, wokenUpstream.localPort, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
                    bgWakeRan.countDown()
                }
                0
            },
            startWatchThread = false,
        )
        try {
            val gp = gate.start(wantPort, crashingUpstream.localPort, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertEquals(wantPort, gp)
            val rawReq = "POST /v2/run HTTP/1.1\r\nHost: 127.0.0.1:$gp\r\nContent-Length: 19\r\n\r\n{\"cmd\":\"act tap 1\"}"
            val reply = readHttpRaw(gp, rawReq)
            tCrash.join(3_000)
            assertTrue("background single-flight wake must run", bgWakeRan.await(3, TimeUnit.SECONDS))
            tWoken.join(1_500)

            assertTrue("must return HTTP 503, got: $reply", reply.startsWith("HTTP/1.1 503 Service Unavailable\r\n"))
            assertTrue("has Content-Length", reply.contains("Content-Length: "))
            val json = JSONObject(reply.substringAfter("\r\n\r\n"))
            assertEquals(5, json.length())
            assertFalse(json.getBoolean("ok"))
            assertEquals("HOST_RESTARTED", json.getString("code"))
            assertEquals(1, json.getInt("exit"))
            assertEquals("look", json.getString("next"))
            assertEquals(Gate.HOST_RESTARTED_TEXT, json.getString("text"))
            assertEquals("command must NOT be resent to woken upstream", 0, wokenConnections.get())
        } finally {
            gate.stop()
            runCatching { crashingUpstream.close() }
            runCatching { wokenUpstream.close() }
        }
    }

    @Test
    fun acceptAckScenarioC_frozenUpstreamAcceptsWithoutAckAndTriggersWake() {
        val now = AtomicLong(1_000L)
        val frozenUpstream = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val wokenUpstream = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val cmds = Collections.synchronizedList(ArrayList<List<String>>())
        val frozenHeld = Collections.synchronizedList(ArrayList<Socket>())

        // Frozen upstream accepts TCP in kernel backlog (or holds socket silent) and never sends 0x06.
        val tFrozen = Thread {
            runCatching {
                val s = frozenUpstream.accept()
                frozenHeld += s
                // Stay silent past ACCEPT_ACK_MS (300ms) to simulate frozen process.
                Thread.sleep(1_000)
            }
        }.apply { isDaemon = true; start() }

        val tWoken = Thread {
            runCatching {
                wokenUpstream.accept().use { s ->
                    s.soTimeout = 3_000
                    s.getOutputStream().apply { write(Gate.ACCEPT_ACK_BYTE); flush() }
                    s.getInputStream().read(ByteArray(4096))
                    val body = """{"ok":true,"unfrozen":true}"""
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }

        lateinit var gate: Gate
        val wantPort = freePort()
        gate = Gate(
            service = null,
            clock = { now.get() },
            shell = { cmd, _ ->
                cmds += cmd
                if (cmd.contains("start-foreground-service")) {
                    Thread {
                        Thread.sleep(20)
                        gate.start(wantPort, wokenUpstream.localPort, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
                    }.start()
                }
                0
            },
            startWatchThread = false,
        )
        try {
            val gp = gate.start(wantPort, frozenUpstream.localPort, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertEquals(wantPort, gp)
            assertFalse("heartbeat is still fresh when frozen", gate.isBeatStale())
            val reply = readHttpRaw(gp)
            tWoken.join(3_000)
            assertTrue("expected 200 OK after waking frozen upstream, got: $reply", reply.startsWith("HTTP/1.1 200 OK"))
            assertTrue(reply.contains("\"unfrozen\":true"))
            assertTrue("wake must run am unfreeze on SDK 34", cmds.any { it.contains("unfreeze") })
            assertTrue("wake must run start-foreground-service", cmds.any { it.contains("start-foreground-service") })
            assertEquals(1L, gate.statusJson().getLong("wake_ok"))
        } finally {
            gate.stop()
            frozenHeld.forEach { runCatching { it.close() } }
            runCatching { frozenUpstream.close() }
            runCatching { wokenUpstream.close() }
            tFrozen.interrupt()
        }
    }

    @Test
    fun shouldStartServerEarlyCoversBlockedPrivilegeAndAlreadyRunning() {
        assertFalse(
            "BLOCKED must never start server early",
            com.kilon.agentvscreen.host.VScreenApp.shouldStartServerEarly(
                state = com.kilon.agentvscreen.host.ServiceControl.State.BLOCKED,
                privilegedReady = true,
                serverRunningOrStarting = false,
            ),
        )
        assertFalse(
            "HIDDEN uses on-demand path, not early ON pre-start",
            com.kilon.agentvscreen.host.VScreenApp.shouldStartServerEarly(
                state = com.kilon.agentvscreen.host.ServiceControl.State.HIDDEN,
                privilegedReady = true,
                serverRunningOrStarting = false,
            ),
        )
        assertFalse(
            "not ready privilege must not pre-start",
            com.kilon.agentvscreen.host.VScreenApp.shouldStartServerEarly(
                state = com.kilon.agentvscreen.host.ServiceControl.State.ON,
                privilegedReady = false,
                serverRunningOrStarting = false,
            ),
        )
        assertFalse(
            "already running or starting server must not pre-start again",
            com.kilon.agentvscreen.host.VScreenApp.shouldStartServerEarly(
                state = com.kilon.agentvscreen.host.ServiceControl.State.ON,
                privilegedReady = true,
                serverRunningOrStarting = true,
            ),
        )
        assertTrue(
            "ON + privilegedReady + not running may pre-start off main thread",
            com.kilon.agentvscreen.host.VScreenApp.shouldStartServerEarly(
                state = com.kilon.agentvscreen.host.ServiceControl.State.ON,
                privilegedReady = true,
                serverRunningOrStarting = false,
            ),
        )
    }
}
