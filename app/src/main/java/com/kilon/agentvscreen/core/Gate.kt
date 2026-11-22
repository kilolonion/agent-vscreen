package com.kilon.agentvscreen.core

import android.os.SystemClock
import com.kilon.agentvscreen.api.ApiConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 2.0.0-beta2 [Gate]: byte-level TCP forwarder living in the privileged [VScreenDisplayService] process
 * (Shizuku daemon or RootMain).
 *
 * Holds the public loopback port (18765..18774 by default) and forwards bytes untouched to the app's
 * internal ApiServer port (18775..18784). When the app process is frozen or killed (heartbeat stale or
 * internal connect refused), Gate wakes it via `am unfreeze` (SDK >= 34) + `am start-foreground-service`
 * and waits up to [WAKE_WAIT_MS] for a heartbeat before returning an explicit HTTP 503 `NOT_READY`.
 */
internal class Gate(
    private val service: VScreenDisplayService?,
    private val clock: Clock = SystemClockImpl,
    private val shell: Shell = ProcessShell,
    private val cancelAgentFingers: () -> Int = { service?.cancelAgentFingersOnAllDisplays() ?: 0 },
    private val sleepFn: (Long) -> Unit = { Thread.sleep(it) },
    private val startWatchThread: Boolean = true,
    private val appResponsive: (() -> Boolean)? = null,
) {
    constructor(service: VScreenDisplayService) : this(service, SystemClockImpl, ProcessShell)

    fun interface Clock {
        fun nowMs(): Long
    }

    fun interface Shell {
        fun run(argv: List<String>, timeoutMs: Long): Int
    }

    private object SystemClockImpl : Clock {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()
    }

    private object ProcessShell : Shell {
        override fun run(argv: List<String>, timeoutMs: Long): Int {
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            val drain = Thread {
                runCatching { p.inputStream.use { inp -> val b = ByteArray(1024); while (inp.read(b) >= 0) {} } }
            }.apply { isDaemon = true; name = "vscreen-gate-sh"; start() }
            val done = runCatching { p.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!done) runCatching { p.destroyForcibly() }
            drain.join(500)
            return if (done) p.exitValue() else 124
        }
    }

    private val lifecycleLock = Any()
    private val wakeLock = ReentrantLock()
    private val wakeCond = wakeLock.newCondition()

    @Volatile var boundPort: Int = 0; private set
    @Volatile private var stopped: Boolean = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var wantPortStart: Int = 0
    @Volatile private var internalPort: Int = 0
    @Volatile private var pkg: String = ""
    @Volatile private var processName: String = ""
    @Volatile private var sdk: Int = 0

    @Volatile private var lastBeatMs: Long = 0L
    @Volatile private var hasBeat: Boolean = false
    private val beatSeq = AtomicLong(0L)
    private val cancelledForCurrentStale = AtomicBoolean(false)

    private val activeConns = AtomicInteger(0)
    private val openSockets = Collections.synchronizedSet(HashSet<Socket>())
    @Volatile private var watchThread: Thread? = null

    // Stats & wake state (guarded by wakeLock for updates, read cleanly via statusJson).
    @Volatile private var staleCount: Long = 0L
    @Volatile private var wakeOk: Long = 0L
    @Volatile private var wakeFail: Long = 0L
    @Volatile private var notReady: Long = 0L
    @Volatile private var lastWakeMs: Long = 0L
    @Volatile private var lastWakeCmds: List<String> = emptyList()

    private var wakeInProgress: Boolean = false
    private var wakeGen: Long = 0L
    private var lastWakeSucceeded: Boolean = false
    private var consecutiveWakeFails: Int = 0
    private var cooldownUntilMs: Long = 0L

    fun start(wantPort: Int, internalPort: Int, pkg: String, processName: String, sdk: Int): Int {
        if (wantPort !in 1..65535 || internalPort !in 1..65535) return -1
        val spanEnd = minOf(wantPort + ApiConfig.PORT_SPAN, 65536)
        synchronized(lifecycleLock) {
            stopped = false
            val cur = server
            if (cur != null && !cur.isClosed && wantPortStart == wantPort && boundPort in wantPort until spanEnd) {
                this.internalPort = internalPort
                this.pkg = pkg
                this.processName = processName
                this.sdk = sdk
                beat()
                ensureWatchThreadLocked()
                return boundPort
            }
            closeListenerLocked()
            for (p in wantPort until spanEnd) {
                val ss = ServerSocket()
                try {
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 50)
                } catch (_: Exception) {
                    runCatching { ss.close() }
                    continue
                }
                server = ss
                boundPort = p
                wantPortStart = wantPort
                this.internalPort = internalPort
                this.pkg = pkg
                this.processName = processName
                this.sdk = sdk
                beat()
                Thread({ acceptLoop(ss) }, "vscreen-gate-accept").apply { isDaemon = true }.start()
                ensureWatchThreadLocked()
                return p
            }
            return -1
        }
    }

    fun beat() {
        lastBeatMs = clock.nowMs()
        hasBeat = true
        beatSeq.incrementAndGet()
        cancelledForCurrentStale.set(false)
        wakeLock.withLock {
            consecutiveWakeFails = 0
            cooldownUntilMs = 0L
            wakeCond.signalAll()
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            stopped = true
            watchThread?.interrupt()
            watchThread = null
            closeListenerLocked()
            hasBeat = false
            cancelledForCurrentStale.set(false)
        }
    }

    private fun closeListenerLocked() {
        val ss = server
        server = null
        boundPort = 0
        wantPortStart = 0
        internalPort = 0
        runCatching { ss?.close() }
        val snap = synchronized(openSockets) { openSockets.toList().also { openSockets.clear() } }
        snap.forEach { s -> runCatching { s.close() } }
    }

    fun statusJson(): JSONObject = JSONObject()
        .put("port", boundPort)
        .put("stale_count", staleCount)
        .put("wake_ok", wakeOk)
        .put("wake_fail", wakeFail)
        .put("not_ready", notReady)
        .put("last_wake_ms", lastWakeMs)
        .put("last_wake_cmds", JSONArray(lastWakeCmds))

    internal fun isBeatStale(nowMs: Long = clock.nowMs()): Boolean =
        !hasBeat || (nowMs - lastBeatMs > BEAT_STALE_MS)

    internal fun isInCooldown(nowMs: Long = clock.nowMs()): Boolean = wakeLock.withLock {
        cooldownUntilMs > 0L && nowMs < cooldownUntilMs
    }

    private fun isAppResponsive(): Boolean = appResponsive?.invoke() ?: defaultProbeAppResponsive()

    /**
     * Probe whether the app's internal ApiServer is still alive and unfrozen.
     * - Killed app: TCP connect immediately fails with ConnectException -> false.
     * - Frozen app: kernel accepts TCP, but frozen process never reads/answers -> SocketTimeoutException -> false.
     * - Alive & busy app: ApiServer answers `/v2/ping` with `HTTP/1.1 200 OK` -> true.
     */
    private fun defaultProbeAppResponsive(): Boolean {
        val port = internalPort
        if (port !in 1..65535) return false
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), CONNECT_TIMEOUT_MS)
                s.soTimeout = PROBE_READ_TIMEOUT_MS
                s.getOutputStream().apply {
                    write("GET /v2/ping HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val buf = ByteArray(16)
                val n = s.getInputStream().read(buf)
                n >= 7 && String(buf, 0, n, Charsets.US_ASCII).startsWith("HTTP/1.")
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun logInfo(msg: String) {
        runCatching { android.util.Log.i(TAG, msg) }
    }

    private fun logWarn(msg: String) {
        runCatching { android.util.Log.w(TAG, msg) }
    }

    /**
     * 2.5: if the heartbeat is stale past [BEAT_STALE_MS] AND the internal port is unresponsive (killed or frozen),
     * cancel held agent fingers on all displays created by [VScreenDisplayService]. Fires at most once per stale
     * episode; resets on the next [beat].
     */
    internal fun checkStaleFingers(): Boolean {
        if (boundPort <= 0 || !hasBeat) return false
        val now = clock.nowMs()
        if (!isBeatStale(now)) return false
        if (cancelledForCurrentStale.get()) return false
        if (isAppResponsive()) return false
        if (!cancelledForCurrentStale.compareAndSet(false, true)) return false
        val beatGapMs = (now - lastBeatMs).coerceAtLeast(0L)
        val cancelled = runCatching { cancelAgentFingers() }.getOrDefault(0)
        if (cancelled > 0) {
            logWarn("cancelled $cancelled stale agent finger(s) (beat_gap_ms=$beatGapMs)")
        }
        return true
    }

    private fun ensureWatchThreadLocked() {
        if (!startWatchThread || watchThread?.isAlive == true) return
        watchThread = Thread({
            while (!Thread.currentThread().isInterrupted && !stopped) {
                try {
                    Thread.sleep(FINGER_WATCH_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (stopped) return@Thread
                runCatching { checkStaleFingers() }
            }
        }, "vscreen-gate-watch").apply { isDaemon = true; start() }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                continue
            }
            if (activeConns.incrementAndGet() > MAX_CONN) {
                activeConns.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            Thread({ handleClient(client) }, "vscreen-gate-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handleClient(client: Socket) {
        openSockets.add(client)
        try {
            val stale = isBeatStale(clock.nowMs())
            if (!stale) {
                val up = connectInternal()
                if (up != null) {
                    relay(client, up)
                    return
                }
                // Heartbeat was still fresh, but the internal port refused / timed out (e.g. just force-stopped):
                // transition immediately into the wake path and require a fresh heartbeat / gateStart.
                if (tryWakeAndForward(client, requireNewBeat = true)) return
                sendNotReady(client)
                return
            }
            if (tryWakeAndForward(client, requireNewBeat = false)) return
            sendNotReady(client)
        } finally {
            openSockets.remove(client)
            runCatching { client.close() }
            activeConns.decrementAndGet()
        }
    }

    private fun tryWakeAndForward(client: Socket, requireNewBeat: Boolean): Boolean {
        if (!ensureAwake(requireNewBeat)) return false
        val up = connectInternal() ?: return false
        relay(client, up)
        return true
    }

    private fun connectInternal(): Socket? {
        val port = internalPort
        if (port !in 1..65535) return null
        val s = Socket()
        return try {
            s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), CONNECT_TIMEOUT_MS)
            s
        } catch (_: IOException) {
            runCatching { s.close() }
            null
        }
    }

    /**
     * Single-flight wake: only one wake sequence runs at a time; concurrent connections wait for the same result.
     * Enforces cooldown after [MAX_CONSECUTIVE_WAKE_FAILS] consecutive failures for [COOLDOWN_MS].
     */
    internal fun ensureAwake(requireNewBeat: Boolean = false): Boolean {
        val myGen: Long
        val beatBefore: Long
        val beatGapMs: Long
        wakeLock.withLock {
            staleCount++
            val now = clock.nowMs()
            beatGapMs = if (hasBeat) (now - lastBeatMs).coerceAtLeast(0L) else -1L
            if (cooldownUntilMs > 0L) {
                if (now < cooldownUntilMs) {
                    logWarn("wake skipped in cooldown (beat_gap_ms=$beatGapMs, wake_fail=$wakeFail)")
                    return false
                }
                cooldownUntilMs = 0L
                consecutiveWakeFails = 0
            }
            if (wakeInProgress) {
                val waitGen = wakeGen
                val limit = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAKE_WAIT_MS + SHELL_TIMEOUT_MS * 2 + 1_000L)
                while (wakeInProgress && wakeGen == waitGen) {
                    val leftMs = TimeUnit.NANOSECONDS.toMillis(limit - System.nanoTime())
                    if (leftMs <= 0L) return false
                    try {
                        wakeCond.await(minOf(200L, leftMs), TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        return false
                    }
                }
                return lastWakeSucceeded
            }
            wakeInProgress = true
            myGen = ++wakeGen
            beatBefore = beatSeq.get()
        }

        val t0 = clock.nowMs()
        var woke = false
        try {
            val cmds = buildWakeCommands()
            lastWakeCmds = cmds.map { it.joinToString(" ") }
            logInfo("wake start (beat_gap_ms=$beatGapMs, require_new_beat=$requireNewBeat, cmds=${lastWakeCmds.joinToString("; ")})")
            for (cmd in cmds) {
                runCatching { shell.run(cmd, SHELL_TIMEOUT_MS) }
            }
            fun beatArrived(): Boolean {
                if (beatSeq.get() > beatBefore) return true
                if (!requireNewBeat && !isBeatStale(clock.nowMs())) return true
                return false
            }
            woke = beatArrived()
            while (!woke && !stopped) {
                val elapsed = clock.nowMs() - t0
                if (elapsed >= WAKE_WAIT_MS) break
                val step = minOf(WAKE_POLL_MS, WAKE_WAIT_MS - elapsed)
                try {
                    sleepFn(step)
                } catch (_: InterruptedException) {
                    break
                }
                woke = beatArrived()
            }
        } finally {
            var took = 0L
            var okNow = 0L
            var failNow = 0L
            wakeLock.withLock {
                took = (clock.nowMs() - t0).coerceAtLeast(0L)
                lastWakeMs = took
                if (woke) {
                    wakeOk++
                    consecutiveWakeFails = 0
                    cooldownUntilMs = 0L
                } else {
                    wakeFail++
                    consecutiveWakeFails++
                    if (consecutiveWakeFails >= MAX_CONSECUTIVE_WAKE_FAILS) {
                        cooldownUntilMs = clock.nowMs() + COOLDOWN_MS
                    }
                }
                okNow = wakeOk
                failNow = wakeFail
                lastWakeSucceeded = woke
                if (wakeGen == myGen) wakeInProgress = false
                wakeCond.signalAll()
            }
            logInfo("wake ${if (woke) "ok" else "fail"} in ${took}ms (beat_gap_ms=$beatGapMs, wake_ok=$okNow, wake_fail=$failNow)")
        }
        return woke
    }

    internal fun buildWakeCommands(): List<List<String>> {
        val cmds = ArrayList<List<String>>(2)
        val pName = processName
        if (sdk >= MIN_UNFREEZE_SDK && pName.isNotBlank()) {
            cmds += listOf("am", "unfreeze", pName)
        }
        val appPkg = pkg
        if (appPkg.isNotBlank()) {
            cmds += listOf("am", "start-foreground-service", "-n", "$appPkg/com.kilon.agentvscreen.host.HostService")
        }
        return cmds
    }

    private fun relay(client: Socket, upstream: Socket) {
        openSockets.add(upstream)
        val closeBoth = {
            runCatching { client.close() }
            runCatching { upstream.close() }
        }
        val t = Thread({
            try {
                copyStream(client.getInputStream(), upstream.getOutputStream())
            } catch (_: Exception) {
            } finally {
                closeBoth()
            }
        }, "vscreen-gate-c2s").apply { isDaemon = true }
        t.start()
        try {
            copyStream(upstream.getInputStream(), client.getOutputStream())
        } catch (_: Exception) {
        } finally {
            closeBoth()
            runCatching { t.join(2_000L) }
            openSockets.remove(upstream)
        }
    }

    private fun copyStream(inp: InputStream, out: OutputStream) {
        val buf = ByteArray(COPY_BUF_SIZE)
        while (true) {
            val n = inp.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            out.flush()
        }
    }

    private fun sendNotReady(client: Socket) {
        wakeLock.withLock { notReady++ }
        // Read the client's request headers (up to \r\n\r\n, max 16KB, max 1s) before writing 503.
        runCatching {
            client.soTimeout = NOT_READY_READ_TIMEOUT_MS
            val inp = client.getInputStream()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(NOT_READY_READ_TIMEOUT_MS.toLong())
            var read = 0
            var last4 = 0
            while (read < NOT_READY_HEAD_MAX && System.nanoTime() < deadline) {
                val b = inp.read()
                if (b < 0) break
                read++
                last4 = (last4 shl 8) or (b and 0xFF)
                if (last4 == 0x0D0A0D0A) break
            }
        }
        runCatching {
            val out = client.getOutputStream()
            out.write(notReadyHttpBytes())
            out.flush()
        }
    }

    companion object {
        private const val TAG = "Gate"
        const val MAX_CONN = 64
        const val BEAT_STALE_MS = 2_500L
        const val CONNECT_TIMEOUT_MS = 500
        const val PROBE_READ_TIMEOUT_MS = 500
        const val COPY_BUF_SIZE = 16_384
        const val WAKE_POLL_MS = 100L
        const val WAKE_WAIT_MS = 3_000L
        const val NOT_READY_HEAD_MAX = 16_384
        const val NOT_READY_READ_TIMEOUT_MS = 1_000
        const val MAX_CONSECUTIVE_WAKE_FAILS = 3
        const val COOLDOWN_MS = 60_000L
        const val SHELL_TIMEOUT_MS = 2_000L
        const val FINGER_WATCH_MS = 500L
        const val MIN_UNFREEZE_SDK = 34

        /** Fixed bilingual text: Gate runs in the privileged service without the app's language preference. */
        const val NOT_READY_TEXT =
            "Agent-VScreen 应用没有响应，已尝试唤醒一次。请用户打开 App 或在系统里允许它后台运行。 / " +
                "The Agent-VScreen app did not respond; it was woken once. Ask the user to open the app or allow it to run in the background."

        internal fun notReadyBodyJson(): JSONObject = JSONObject()
            .put("ok", false)
            .put("code", "NOT_READY")
            .put("exit", 1)
            .put("next", "ask_user")
            .put("text", NOT_READY_TEXT)

        internal fun notReadyHttpBytes(): ByteArray {
            val body = notReadyBodyJson().toString().toByteArray(Charsets.UTF_8)
            val head = ("HTTP/1.1 503 Service Unavailable\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Retry-After: 3\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            return head + body
        }
    }
}
