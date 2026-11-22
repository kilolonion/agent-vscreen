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
    fun staleHeartbeatCancelsAgentFingersOncePerEpisode() {
        val now = AtomicLong(1_000L)
        val cancels = AtomicInteger(0)
        val gate = Gate(
            service = null,
            clock = { now.get() },
            shell = { _, _ -> 0 },
            cancelAgentFingers = { cancels.incrementAndGet() },
            startWatchThread = false,
        )
        try {
            val gp = gate.start(freePort(), 18775, "com.kilon.agentvscreen", "com.kilon.agentvscreen", 34)
            assertTrue(gp > 0)
            assertFalse(gate.checkStaleFingers())
            assertEquals(0, cancels.get())

            now.addAndGet(Gate.BEAT_STALE_MS + 1L)
            assertTrue("first check after stale cancels fingers", gate.checkStaleFingers())
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
        val j = Liveness.json()
        assertTrue("Liveness.json contains gate key", j.has("gate"))
        assertTrue("gate is null when Gate is off", j.isNull("gate"))
        val diag = diagText(ctx)
        assertTrue("diagText includes gate line", diag.contains("gate: off"))
    }

    @Test
    fun apiServerBehindGatePreservesAuthentication() {
        ApiConfig.init(ctx)
        com.kilon.agentvscreen.api.Tokens.init(ctx)
        com.kilon.agentvscreen.host.Audit.init(ctx)
        val aliveField = com.kilon.agentvscreen.host.HostService::class.java.getDeclaredField("isAlive").apply { isAccessible = true }
        val wasAlive = aliveField.getBoolean(null)
        aliveField.setBoolean(null, true)
        val srv = ApiServer(ctx)
        val gate = Gate(service = null, clock = { System.currentTimeMillis() }, shell = { _, _ -> 0 }, startWatchThread = false)
        try {
            srv.start(freePort())
            assertTrue(srv.boundPort > 0)
            val gp = gate.start(freePort(), srv.boundPort, ctx.packageName, ctx.packageName, 34)
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

            // Authenticated /v2/ping through Gate succeeds.
            val ping = (URL("http://127.0.0.1:$gp/v2/ping").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                setRequestProperty("Host", "127.0.0.1:$gp")
            }
            assertEquals(200, ping.responseCode)
            val body = JSONObject(ping.inputStream.bufferedReader().readText())
            assertTrue(body.getBoolean("ok"))
        } finally {
            gate.stop()
            srv.stop()
            aliveField.setBoolean(null, wasAlive)
        }
    }
}
