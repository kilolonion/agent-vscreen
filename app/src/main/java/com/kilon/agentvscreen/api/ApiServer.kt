package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.tr

import android.content.Context
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.cmd.NativeOffloadRequest
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.cmd.ShotStore
import com.kilon.agentvscreen.cmd.VScreenOffloadHandler
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.quiet

/**
 * The one door every agent comes through: a small HTTP server on 127.0.0.1 (and, when the user turns it on, on this
 * phone's tailnet address — never on Wi-Fi or mobile interfaces).
 *
 *   GET  /v2/ping            no token  — is it running, which version, the phone's time
 *   GET  /v2/help            no token  — the full command reference (same text as `vscreen help all`)
 *   GET  /v2/codes           no token  — every result code and what to do next
 *   GET  /cli                no token  — the `vscreen` shell client (first install; updates come signed from /v2/cli)
 *   POST /v2/run             token     — run one command; JSON reply with the typed result (see [envelope])
 *   GET  /v2/shot/<name>     token     — a picture a command referenced
 *   GET  /v2/events          token     — events posted by kept watches (?session=&wait=30s&again=1)
 *   POST /mcp                token     — Model Context Protocol (streamable HTTP)
 *   /v1/run, /v1/shot, /v1/events      — the 1.1 forms, kept for one more version
 *
 * 1.2 hardening: bounded worker pool and connection cap, limits and deadlines on request size and arrival, chunked
 * bodies, per-agent tokens with scopes, signed requests (the token does not travel), replay protection, lockout of
 * a remote caller that keeps failing, cancellation when the caller disconnects, at-most-once execution per request
 * id, and an audit line for every command. Requests that carry a browser Origin, or a Host that is not this phone,
 * are refused, so a web page cannot drive the phone.
 */
class ApiServer(private val ctx: Context) {
    fun ctxFiles(): Context = ctx
    private val TAG = "ApiServer"
    private val handler = VScreenOffloadHandler(ctx)
    private val pool = ThreadPoolExecutor(4, MAX_CONN, 30, TimeUnit.SECONDS, SynchronousQueue()) { r -> Thread(r, "vscreen-http").apply { isDaemon = true } }
    /** Connections turned away because every worker was busy. */
    @Volatile var refused = 0L; private set
    /** Silent connections closed to make room for a new one. */
    @Volatile var evicted = 0L; private set
    /** Connections whose request has not fully arrived yet, oldest first. */
    private val reading = java.util.LinkedHashMap<Socket, Long>()
    internal val commandHandler: VScreenOffloadHandler get() = handler
    @Volatile private var server: ServerSocket? = null

    init {
        // Kept watches come back after an app restart (their conditions are evaluated by the command layer).
        com.kilon.agentvscreen.core.VScreenRules.hostFactory = { cond -> with(com.kilon.agentvscreen.cmd.KeepHosts) { handler.host(cond) } }
        quiet("keeps-restore") { com.kilon.agentvscreen.core.VScreenRules.restore() }
    }
    @Volatile var lastError: String? = null; private set
    /** 1.1.7: the port is held by some other program (the home page then offers another port). */
    @Volatile var portBusy = false; private set
    @Volatile var requests = 0L; private set
    @Volatile var lastClient: String? = null; private set
    @Volatile var lastCaller: String? = null; private set

    val running: Boolean get() = server?.isClosed == false
    @Volatile var starting = false; private set

    /** 1.2: the extra socket on this phone's Tailscale address (null: off, or no tailnet address now). */
    @Volatile private var tailServer: ServerSocket? = null
    @Volatile var tailAddress: String? = null; private set
    @Volatile private var tailWatch: Thread? = null

    /** Called when [lastError] / listening changes (the notification shows it). */
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile private var retry: Thread? = null
    @Volatile var bindFailures = 0; private set

    /**
     * 1.4.0-beta15 (rainbow: the service retried a taken port 51 times and nothing could be tested; "what if it is some
     * other service on my phone"). The port the user set is the one agents are told, not one the service must win: when
     * something else holds it (another app, a clone of this app in a parallel space, an old process of ours), the
     * service takes the next free port of [ApiConfig.PORT_SPAN] at once. The CLI and the Operit package look along the
     * same span and accept only the instance that proves it holds their token (`/v2/ping?me=&n=`), and the built-in
     * Tailscale node keeps answering on the set port and forwards to wherever the service is. Nobody is killed.
     */
    fun start(port: Int) {
        if (com.kilon.agentvscreen.host.ThermalGuard.locked) return
        if (starting && wantedPort == port) return
        wantedPort = port
        if (!com.kilon.agentvscreen.host.Privileged.isReady()) {
            rebindGen.incrementAndGet()
            synchronized(bindLock) {
                closeLocalLocked()
                if (!bindAny(port)) retryBind(port)
                if (ApiConfig.tailnet) startTailnet(port)
            }
            return
        }
        val gen = rebindGen.incrementAndGet()
        starting = true
        val task = {
            try {
                synchronized(bindLock) {
                    if (gen == rebindGen.get() && wantedPort == port) {
                        startWithGateOrFallbackLocked(port)
                    }
                }
            } finally {
                if (gen == rebindGen.get()) starting = false
            }
        }
        val onMain = runCatching { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() }.getOrDefault(false)
        if (onMain) Thread(task, "vscreen-api-start").apply { isDaemon = true }.start() else task()
    }

    /** 2.0.0-beta2: re-run Gate setup when privileged service connects, reconnects (Shizuku restart) or switches (Root). */
    fun onPrivilegeChanged() {
        val port = wantedPort
        if (port <= 0) return
        val gen = rebindGen.incrementAndGet()
        starting = false
        Thread({
            synchronized(bindLock) {
                if (gen != rebindGen.get() || wantedPort != port) return@synchronized
                if (com.kilon.agentvscreen.host.Privileged.isReady()) {
                    if (usingGate && server?.isClosed == false && internalPort > 0) {
                        val gp = com.kilon.agentvscreen.core.VScreenRemoteDisplay.gateStart(
                            port, internalPort, ctx.packageName, processName(), android.os.Build.VERSION.SDK_INT
                        ).getOrDefault(-1)
                        if (gp > 0) {
                            boundPort = gp
                            lastError = null; portBusy = false; bindFailures = 0
                            portNote = if (gp == port) null else tr("端口 $port 被占着，已改用 $gp；agent 会自己找到，不用重新接入",
                                "Port $port is held, so $gp is used; agents find it by themselves, nothing to set up again")
                            com.kilon.agentvscreen.host.Tailnet.retarget(ctx, gp)
                            onChange?.invoke()
                            AppLogger.info(TAG, "gate rebound on 127.0.0.1:$gp -> internal 127.0.0.1:$internalPort")
                            return@synchronized
                        }
                    }
                    startWithGateOrFallbackLocked(port)
                } else if (usingGate) {
                    closeLocalLocked()
                    if (!bindAny(port)) retryBind(port)
                    if (ApiConfig.tailnet) startTailnet(port)
                }
            }
        }, "vscreen-gate-rebind").apply { isDaemon = true }.start()
    }

    /** The port the service listens on right now (0: none); [ApiConfig.port] is the one agents are told. */
    @Volatile var boundPort = 0; private set
    /** 2.0.0-beta2: internal loopback port when Gate forwards traffic to this server (0: direct mode). */
    @Volatile var internalPort = 0; private set
    /** 2.0.0-beta2: true while public loopback traffic arrives through the privileged Gate. */
    @Volatile var usingGate = false; private set
    /** Not an error: the set port was taken, another one is used (shown as a plain note). */
    @Volatile var portNote: String? = null; private set
    @Volatile private var wantedPort = 0
    private val bindLock = Any()
    private val rebindGen = AtomicInteger(0)

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) runCatching { android.app.Application.getProcessName() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: ctx.applicationInfo?.processName ?: ctx.packageName
        else ctx.applicationInfo?.processName ?: ctx.packageName

    private fun startWithGateOrFallbackLocked(port: Int) {
        closeLocalLocked()
        if (!bindWithGateLocked(port)) {
            if (!bindAny(port)) retryBind(port)
        }
        if (ApiConfig.tailnet) startTailnet(port)
    }

    private fun bindWithGateLocked(port: Int): Boolean {
        if (!com.kilon.agentvscreen.host.Privileged.isReady()) return false
        var ss: ServerSocket? = null
        var intPort = 0
        for (p in ApiConfig.INTERNAL_BASE until ApiConfig.INTERNAL_BASE + ApiConfig.PORT_SPAN) {
            if (p > 65535) break
            val cand = ServerSocket()
            try {
                cand.reuseAddress = true
                cand.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 50)
                ss = cand; intPort = p; break
            } catch (e: Exception) {
                com.kilon.agentvscreen.host.quiet("ApiServer:internal") { cand.close() }
                AppLogger.info(TAG, "bind internal $p: ${e.message}")
            }
        }
        if (ss == null || intPort <= 0) return false
        server = ss; internalPort = intPort
        Thread({ acceptLoop(ss, sendAcceptAck = true) }, "vscreen-http-accept").apply { isDaemon = true }.start()
        val gp = com.kilon.agentvscreen.core.VScreenRemoteDisplay.gateStart(
            port, intPort, ctx.packageName, processName(), android.os.Build.VERSION.SDK_INT
        ).onFailure { AppLogger.warning(TAG, "gateStart failed: ${it.message}") }.getOrDefault(-1)
        if (gp <= 0) {
            com.kilon.agentvscreen.host.quiet("ApiServer:gate-fallback") { ss.close() }
            server = null; internalPort = 0; boundPort = 0; usingGate = false
            return false
        }
        boundPort = gp; usingGate = true; lastError = null; portBusy = false; bindFailures = 0
        portNote = if (gp == port) null else tr("端口 $port 被占着，已改用 $gp；agent 会自己找到，不用重新接入",
            "Port $port is held, so $gp is used; agents find it by themselves, nothing to set up again")
        com.kilon.agentvscreen.host.Tailnet.retarget(ctx, gp)
        onChange?.invoke()
        AppLogger.info(TAG, "gate listening on 127.0.0.1:$gp -> internal 127.0.0.1:$intPort")
        return true
    }

    private fun closeLocalLocked() {
        retry?.interrupt(); retry = null
        usingGate = false
        runCatching { server?.close() }; server = null; boundPort = 0; internalPort = 0; portNote = null
        tailWatch?.interrupt(); tailWatch = null
        runCatching { tailServer?.close() }; tailServer = null; tailAddress = null
    }

    private fun bindAny(port: Int): Boolean {
        val taken = ArrayList<String>()
        var sawOwnGate = false
        for (p in port until port + ApiConfig.PORT_SPAN) {
            if (p > 65535) break
            if (bindLocal(p)) {
                portNote = if (p == port || taken.isEmpty()) null else tr("端口 $port 被${taken.firstOrNull() ?: "别的程序"}占着，已改用 $p；agent 会自己找到，不用重新接入",
                    "Port $port is held by ${taken.firstOrNull() ?: "another program"}, so $p is used; agents find it by themselves, nothing to set up again")
                if (p != port && taken.isNotEmpty()) AppLogger.warning(TAG, "port $port taken (${taken.joinToString()}), listening on $p")
                com.kilon.agentvscreen.host.Tailnet.retarget(ctx, p)
                onChange?.invoke()
                return true
            }
            if (PortHolder.isOwnGate(p)) {
                sawOwnGate = true
            } else {
                taken += holderOf(p)
            }
        }
        bindFailures++
        if (taken.isNotEmpty() || !sawOwnGate) {
            portBusy = true
            lastError = tr("端口 $port 到 ${port + ApiConfig.PORT_SPAN - 1} 都被占着（${taken.distinct().joinToString("、")}）：在“接入”里换一组端口，或关掉占着它们的应用；每 30 秒会自己再试",
                "Ports $port to ${port + ApiConfig.PORT_SPAN - 1} are all taken (${taken.distinct().joinToString(", ")}): pick other ports under Connect, or close the apps holding them; tried again every 30 s")
        }
        onChange?.invoke()
        return false
    }

    /**
     * Bind 127.0.0.1:[port]. SO_REUSEADDR must be set BEFORE bind (setting it on a bound socket does nothing), so a
     * restart never trips over connections of the previous run still in TIME_WAIT.
     */
    private fun bindLocal(port: Int): Boolean {
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
        } catch (e: Exception) {
            com.kilon.agentvscreen.host.quiet("ApiServer:104") { ss.close() }
            AppLogger.info(TAG, "bind $port: ${e.message}")
            return false
        }
        server = ss; boundPort = port; internalPort = 0; usingGate = false; lastError = null; portBusy = false; bindFailures = 0
        Thread({ acceptLoop(ss) }, "vscreen-http-accept").apply { isDaemon = true }.start()
        AppLogger.info(TAG, "listening on 127.0.0.1:$port")
        return true
    }

    /** Every port of the span taken: try the whole span again every 30 s, quietly (no attempt counter on screen). */
    private fun retryBind(port: Int) {
        retry = Thread({
            while (!Thread.currentThread().isInterrupted && server == null) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
                synchronized(bindLock) {
                    if (server != null || wantedPort != port) return@Thread
                    if (bindWithGateLocked(port) || bindAny(port)) return@Thread
                }
            }
        }, "vscreen-http-rebind").apply { isDaemon = true }.also { it.start() }
    }

    /**
     * Who holds 127.0.0.1:[port], in the user's words: read from the kernel's socket table through Shizuku (which app,
     * which user: a parallel space or clone shows as its own user), else asked over the port itself.
     */
    private fun holderOf(port: Int): String = runCatching { PortHolder.name(ctx, port) }.getOrNull() ?: portOwner(port)

    /** Who holds 127.0.0.1:[port] — asked over the port itself (not on the main thread). */
    private fun portOwner(port: Int): String = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 800)
            s.soTimeout = 1_500
            s.getOutputStream().write("GET /v1/ping HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            val buf = ByteArray(4096); var n = 0
            val inp = s.getInputStream()
            while (n < buf.size) { val k = inp.read(buf, n, buf.size - n); if (k <= 0) break; n += k }
            val txt = String(buf, 0, n, Charsets.UTF_8)
            if ("agent-vscreen" in txt) {
                val pid = Regex("\"pid\":(\\d+)").find(txt)?.groupValues?.get(1)
                tr("另一个 Agent-VScreen 进程${pid?.let { "（pid $it）" } ?: ""}还占着它，旧进程退出后就会接上", "Another Agent-VScreen process${pid?.let { " (pid $it)" } ?: ""} still holds it; we take over once it exits")
            } else { portBusy = true; tr("别的程序在用这个端口：可以在“接入 agent”里换一个端口，再重新复制接入命令", "Another program is using this port: change the port under \"Connect an agent\" and copy the connect message again") }
        }
    } catch (e: java.net.SocketTimeoutException) {
        tr("占着它的进程没有响应，多半是被系统冻结、还没退出的旧 Agent-VScreen 进程", "The process holding it does not answer: most likely an old Agent-VScreen process frozen by the system and not yet gone")
    } catch (e: Exception) {
        tr("端口刚刚被放开", "The port was just released")
    }

    fun stop(stopGate: Boolean = true) {
        rebindGen.incrementAndGet()
        starting = false
        val shouldStopGate = synchronized(bindLock) {
            wantedPort = 0
            val wasGate = usingGate
            closeLocalLocked()
            stopGate && wasGate
        }
        if (shouldStopGate) {
            Thread({ runCatching { com.kilon.agentvscreen.core.VScreenRemoteDisplay.gateStop() } }, "vscreen-gate-stop")
                .apply { isDaemon = true }.start()
        }
    }

    /**
     * Other devices, off by default: listen ALSO on this phone's Tailscale address (100.64.0.0/10), never on Wi-Fi / mobile
     * interfaces. Only devices in the user's own tailnet can reach it, over WireGuard. The address can appear or change
     * (Tailscale started later), so it is checked every 20 s.
     */
    private fun startTailnet(port: Int) {
        tailWatch = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val want = tailnetAddress()
                if (want?.hostAddress != tailAddress) {
                    runCatching { tailServer?.close() }; tailServer = null; tailAddress = null
                    // 1.4.0-beta15: the set port on the tailnet address, else the one the service took locally.
                    if (want != null) runCatching {
                        val ss = runCatching { ServerSocket(port, 50, want) }.getOrElse { ServerSocket(boundPort.takeIf { it > 0 } ?: throw it, 50, want) }.apply { reuseAddress = true }
                        tailServer = ss; tailAddress = want.hostAddress
                        AppLogger.info(TAG, "also listening on tailnet ${want.hostAddress}:$port")
                        Thread({ acceptLoop(ss) }, "vscreen-http-tail").apply { isDaemon = true }.start()
                    }.onFailure { AppLogger.warning(TAG, "tailnet bind: ${it.message}") }
                }
                try { Thread.sleep(20_000) } catch (_: InterruptedException) { break }
            }
        }, "vscreen-tailnet-watch").apply { isDaemon = true }.also { it.start() }
    }

    /** 2.0.0-beta2 test helper: bind a loopback port directly in internal-Gate mode (writes 0x06 accept-ack). */
    internal fun startInternalForTests(port: Int): Boolean = synchronized(bindLock) {
        closeLocalLocked()
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
        } catch (_: Exception) {
            quiet("ApiServer:test-internal") { ss.close() }
            return@synchronized false
        }
        server = ss; boundPort = port; internalPort = port; usingGate = true
        Thread({ acceptLoop(ss, sendAcceptAck = true) }, "vscreen-http-accept").apply { isDaemon = true }.start()
        true
    }

    private fun acceptLoop(ss: ServerSocket, sendAcceptAck: Boolean = false) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (e: SocketException) { break } catch (e: Exception) { continue }
            if (sendAcceptAck) {
                // 2.0.0-beta2: prove the app process itself ran accept() (not a dying/frozen kernel listen queue)
                // before Gate forwards any client bytes. Only sent on the internal port behind Gate.
                try {
                    s.getOutputStream().apply {
                        write(com.kilon.agentvscreen.core.Gate.ACCEPT_ACK_BYTE)
                        flush()
                    }
                } catch (_: Exception) {
                    quiet("http-ack-close") { s.close() }
                    continue
                }
            }
            // 1.2: a bounded pool. When every worker is taken the connection is told so at once (503) instead of
            // one more thread being made for it: a flood of connections can no longer exhaust the app.
            // 1.2.0-beta2: a worker waiting for a request that has not come is not doing anything. When every worker
            // is taken, the connection that has been silent longest is closed and the new one gets its place, so
            // idle connections cannot keep real commands out.
            var placed = try { pool.execute { serve(s) }; true } catch (e: RejectedExecutionException) { false }
            if (!placed) {
                val victim = synchronized(reading) { reading.keys.firstOrNull()?.also { reading.remove(it) } }
                if (victim != null) {
                    quiet("http-evict") { victim.close() }
                    evicted++
                    for (i in 0 until 40) {
                        placed = try { pool.execute { serve(s) }; true } catch (e: RejectedExecutionException) { false }
                        if (placed) break
                        try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                    }
                }
            }
            if (!placed) {
                refused++
                quiet("http-busy") { s.soTimeout = 500; write(s.getOutputStream(), Resp.err(503, "SERVER_BUSY", tr("同时进来的连接太多（上限 $MAX_CONN），这个请求没有处理：稍后重试", "Too many connections at once (limit $MAX_CONN); this request was not handled: retry shortly"))) }
                quiet("http-busy-close") { s.close() }
            }
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    /** Who a request is from, once checked. [signed]: it proved the token without sending it. */
    class Auth(val token: Tokens.Token, val signed: Boolean, val nonce: String?)

    class Req(val method: String, val target: String, val query: Map<String, List<String>>, val headers: Map<String, String>, val body: ByteArray) {
        val path: String get() = target.substringBefore('?')
        /** Who connected: loopback (this phone) or a tailnet peer. */
        @Volatile var peer: InetAddress? = null
        @Volatile internal var sock: Socket? = null
        @Volatile var auth: Auth? = null
        /** 2.0.0-beta7 (7.7): true when handled by [PublicServer] via Funnel; never treated as tailnet even if X-VScreen-Tailnet is present. */
        @Volatile var viaPublic: Boolean = false
        /** 2.0.0-beta7 (7.7): OAuth client_id when [viaPublic] is true. */
        @Volatile var publicClientId: String? = null
        /** 2.0.0-beta11: what this public credential may do now ([PublicOAuth.effectiveScope]); null off the public path. */
        @Volatile var publicScope: String? = null
        /** Forwarded by the built-in Tailscale node (loopback + its secret, never on public path). */
        val viaBuiltinTailnet: Boolean get() = !viaPublic && peer?.isLoopbackAddress == true &&
            headers["x-vscreen-tailnet"]?.let { constTimeEq(it, com.kilon.agentvscreen.host.Tailnet.secret) } == true
        val remote: Boolean get() = viaPublic || viaBuiltinTailnet || peer?.isLoopbackAddress == false
        /** The other device, as the tailnet names it (null on this phone). */
        val peerName: String? get() = when {
            viaPublic -> publicClientId?.let { "public:$it" } ?: "public"
            viaBuiltinTailnet -> headers["x-vscreen-peer"] ?: "?"
            peer?.isLoopbackAddress == false -> peer?.hostAddress
            else -> null
        }
        /** Who is calling, for the app's "last caller" line. */
        val caller: String get() = when {
            viaPublic -> tr("公网", "Public") + (auth?.token?.let { " · ${it.name}" } ?: "")
            else -> (peerName?.let { "Tailscale: $it" } ?: tr("本机", "This phone")) + (auth?.token?.takeIf { it.id != Tokens.MAIN }?.let { " · ${it.name}" } ?: "")
        }
        fun q(k: String) = query[k]?.firstOrNull()
        val bodyText: String get() = String(body, Charsets.UTF_8)
        fun accepts(type: String): Boolean = headers["accept"]?.contains(type) == true
    }

    /** Writes server-sent events on a response that stays open while a command runs. */
    class Emitter(private val out: OutputStream) {
        @Synchronized fun send(event: String?, data: String) {
            val sb = StringBuilder()
            event?.let { sb.append("event: ").append(it).append('\n') }
            data.split('\n').forEach { sb.append("data: ").append(it).append('\n') }
            sb.append('\n')
            out.write(sb.toString().toByteArray()); out.flush()
        }
    }

    class Resp(val status: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap(),
               /** When set, the response is a stream (text/event-stream): the body is written by this. */
               val stream: ((Emitter) -> Unit)? = null,
               /** 2.0.0-beta5 (5.6): callback invoked after the HTTP response is flushed & closed (e.g. --hold freeze). */
               val afterSend: (() -> Unit)? = null) {
        fun plus(h: Map<String, String>) = Resp(status, type, body, headers + h, stream, afterSend)
        fun withAfterSend(fn: (() -> Unit)?) = if (fn == null) this else Resp(status, type, body, headers, stream, fn)
        companion object {
            fun json(o: Any, status: Int = 200, headers: Map<String, String> = emptyMap()) =
                Resp(status, "application/json; charset=utf-8", o.toString().toByteArray(), headers)
            fun text(t: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
                Resp(status, "text/plain; charset=utf-8", t.toByteArray(), headers)
            fun html(h: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
                Resp(status, "text/html; charset=utf-8", h.toByteArray(), headers)
            fun err(status: Int, code: String, msg: String) = json(JSONObject().put("ok", false).put("code", code).put("error", msg)
                .put("next", com.kilon.agentvscreen.cmd.Codes.next(code)), status, mapOf("X-VScreen-Code" to code))
            fun sse(block: (Emitter) -> Unit) = Resp(200, "text/event-stream; charset=utf-8", ByteArray(0), stream = block)
        }
    }

    private fun serve(s: Socket) {
        var afterSend: (() -> Unit)? = null
        s.use { sock ->
            runCatching {
                // 1.2: the request must arrive promptly and be of sane size: a client that opens a connection and
                // sends nothing (or drips bytes) holds a worker for at most HEAD_MS, not forever.
                sock.soTimeout = 5_000
                val inp = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                synchronized(reading) { reading[sock] = System.currentTimeMillis() }
                val req = try { HttpCore.readRequest(inp) } catch (e: HttpError) { write(out, Resp.err(e.status, e.code, e.message ?: e.code)); return }
                    catch (e: java.net.SocketTimeoutException) { quiet("http-408") { write(out, Resp.err(408, "REQUEST_TIMEOUT", "the request did not arrive in time")) }; return }
                    finally { synchronized(reading) { reading.remove(sock) } } ?: return
                req.peer = sock.inetAddress
                req.sock = sock
                sock.soTimeout = 0 // a command (act --watch, events --wait) may take minutes
                val resp = try { route(req) } catch (t: Throwable) {
                    AppLogger.error(TAG, "route ${req.path}", t)
                    Resp.err(500, "INTERNAL_ERROR", "${t.javaClass.simpleName}: ${t.message}")
                }
                afterSend = resp.afterSend
                write(out, resp)
            }.onFailure { if (it !is SocketException && it !is java.io.IOException) AppLogger.warning(TAG, "serve: ${it.message}") }
        }
        afterSend?.let { quiet("http-after-send") { it() } }
    }

    private fun write(out: OutputStream, r: Resp) = HttpCore.writeResponse(out, r, cliVersion())

    // ── routing ───────────────────────────────────────────────────────────

    internal fun route(r: Req): Resp {
        // No browser may drive the phone (CSRF / DNS rebinding): only known hosts, no Origin.
        val host = r.headers["host"]?.substringBeforeLast(':')?.lowercase()?.takeIf { it.isNotEmpty() } ?: r.headers["host"]?.lowercase()
        val remote = r.peer?.let { !it.isLoopbackAddress } == true
        if (r.viaBuiltinTailnet) {
            // Forwarded by our own Tailscale node (it alone knows the secret): the peer is a device of the user's tailnet,
            // identified and encrypted by Tailscale. 1.3: its Host is not checked any more. A caller that reaches us
            // through a local forwarder or a proxy names us by whatever it uses (127.0.0.1, a proxy's target); the
            // rebinding a Host check guards against needs a browser, and browsers are refused below, tokens required.
            if (!com.kilon.agentvscreen.host.Tailnet.status.value.loggedIn) return Resp.err(403, "NOT_ALLOWED", tr("内置 Tailscale 没有登录", "Built-in Tailscale is not logged in"))
        } else if (remote) {
            // Only a tailnet peer reaches this (the socket is bound to the Tailscale address); it must still name us by
            // that address or a tailnet name, so a web page cannot rebind a hostname onto us.
            if (!ApiConfig.tailnet || !isTailnet(r.peer)) return Resp.err(403, "NOT_ALLOWED", tr("其它设备访问没有打开", "Access from other devices is off"))
            if (host != null && host != tailAddress && !ApiConfig.tailnetHostOk(host)) return Resp.err(403, "BAD_HOST", tr("用本机的 Tailscale 地址访问", "Use this phone's Tailscale address"))
        } else if (host != null && host !in setOf("127.0.0.1", "localhost", "[::1]")) return Resp.err(403, "BAD_HOST", tr("只接受 127.0.0.1 / localhost", "Only 127.0.0.1 / localhost are accepted"))
        if (r.headers["origin"].let { it != null && it != "null" } || r.headers["sec-fetch-site"].let { it != null && it != "none" })
            return Resp.err(403, "BROWSER_REFUSED", tr("不接受网页发起的请求", "Requests from web pages are not accepted"))
        // 1.2: the user may name the tailnet devices that are let in; every other peer of the tailnet is refused.
        if (r.remote) ApiConfig.tailnetPeers.takeIf { it.isNotEmpty() }?.let { allow ->
            val who = r.peerName.orEmpty().lowercase()
            if (allow.none { who == it || who.split(" @ ", " ").any { part -> part == it } }) {
                Audit.security("peer_denied", who)
                return Resp.err(403, "PEER_DENIED", tr("这台设备（$who）不在允许名单里：名单在 Agent-VScreen › 其它设备", "This device ($who) is not on the allow list: the list is under Other devices in Agent-VScreen"))
            }
        }
        requests++
        when (r.path) {
            "/", "/v1/ping", "/v2/ping" -> return Resp.json(JSONObject().put("ok", true).put("app", "agent-vscreen")
                // 1.4.0-beta15: proof that this instance holds the caller's token (the CLI looks along the port span and
                // takes only the one that answers this): sha256hex(K \n n \n ping \n K). Says nothing to anyone else.
                .also { o -> val kid = r.q("me"); val n = r.q("n")
                    if (kid != null && n != null && n.length in 8..64 && n.all { it.isLetterOrDigit() })
                        Tokens.byId(kid)?.takeIf { !it.publicOnly && it.key.isNotEmpty() }?.let { t -> o.put("you", Tokens.sha256("${t.key}\n$n\nping\n${t.key}")) } }
                .put("version", BuildConfig.VERSION_NAME).put("code", BuildConfig.VERSION_CODE).put("pid", android.os.Process.myPid())
                .put("time", System.currentTimeMillis() / 1000).put("api", JSONArray(listOf("v2", "v1")))
                // 1.3: ready + the codes of what blocks (no details before the token is checked).
                .also { o -> quiet("ping-ready") { val items = com.kilon.agentvscreen.host.Readiness.now(ctx)
                    o.put("ready", com.kilon.agentvscreen.host.Readiness.ready(items)).put("blocking", JSONArray(items.filter { it.blocking }.map { it.code })) } }
                .put("endpoints", JSONArray(listOf("/v2/run", "/v2/shot/<name>", "/v2/events", "/v2/help", "/v2/codes", "/mcp", "/cli"))))
            "/v1/help", "/v2/help" -> return langOf(r) { Resp.text(VScreenOffloadHandler.HELP + "\n") }
            "/v2/codes" -> return langOf(r) { Resp.json(JSONObject().put("codes", JSONArray(com.kilon.agentvscreen.cmd.Codes.all.map { JSONObject().put("code", it.name).put("next", it.next.key).put("text", it.text) }))) }
            "/cli" -> { if (r.q("via") == "install") AgentLedger.installed(r.q("id")?.takeIf { id -> Tokens.list.value.any { it.id == id } } ?: Tokens.MAIN); return Resp.text(cliScript()) }
            "/skill" -> return langOf(r) { Resp.text(AgentGuide.skill()) }
            "/memory" -> return langOf(r) { Resp.text(AgentGuide.memoryFor(when (r.q("via")) { "mcp" -> Abilities.Via.MCP; "http" -> Abilities.Via.HTTP; else -> Abilities.Via.CLI }, if (r.remote) null else ApiConfig.baseUrl) + "\n") }
        }
        // 2.0.0-beta7 (7.8 #3): /mcp method check (GET/DELETE -> 405 + Allow: POST) runs BEFORE auth, shared with PublicServer.
        Mcp.preAuthCheck(r)?.let { return it }
        val auth = when (val a = authenticate(r)) { is Auth -> a; is Resp -> return a; else -> return Resp.err(401, "BAD_TOKEN", "?") }
        r.auth = auth
        Tokens.used(auth.token.id)
        if (com.kilon.agentvscreen.host.ThermalGuard.locked) return Resp.err(503, "THERMAL_STOP", com.kilon.agentvscreen.host.ThermalGuard.reason())
        // 1.4.0-beta5: on demand, the server stays up in the app's process while the service sleeps (no notification,
        // no foreground service). A signed request is the agent needing the phone: wake the service and wait for it,
        // so any client (curl, Operit, MCP) wakes it, not only one with Android's `am` at hand.
        if (!com.kilon.agentvscreen.host.HostService.isAlive) com.kilon.agentvscreen.host.HostService.wakeFor(ctx, "agent request")
        lastClient = r.headers["user-agent"]?.take(60)
        lastCaller = r.caller
        val resp = langOf(r) { when {
            r.path == "/v2/run" -> if (r.method == "POST") run(r, auth, v2 = true) else Resp.err(405, "METHOD", "POST")
            r.path == "/v1/run" -> if (r.method == "POST") run(r, auth, v2 = false) else Resp.err(405, "METHOD", "POST")
            r.path.startsWith("/v1/shot/") || r.path.startsWith("/v2/shot/") -> shot(r.path.substringAfterLast('/'))
            r.path == "/v1/events" || r.path == "/v2/events" -> events(r, auth)
            r.path == "/v2/cli" -> Resp.text(cliScript())
            r.path == "/mcp" -> Mcp.handle(this, r)
            else -> Resp.err(404, "NOT_FOUND", r.path)
        } }
        // 1.2: a signed request gets a signed answer, so the caller knows the reply (and a CLI update) came from this
        // phone and was not changed on the way.
        return if (auth.signed && auth.nonce != null && resp.stream == null) resp.plus(mapOf("X-VScreen-Proof" to proof(auth.token.key, auth.nonce, resp.body))) else resp
    }

    private fun <T> langOf(r: Req, block: () -> T): T = com.kilon.agentvscreen.I18n.with((r.headers["x-vscreen-lang"] ?: r.q("lang") ?: com.kilon.agentvscreen.I18n.agentDefault())?.lowercase()?.take(2), block)
    internal fun <T> withLang(r: Req, block: () -> T): T = langOf(r, block)
    internal fun notePublicCaller(r: Req) {
        requests++
        lastClient = r.headers["user-agent"]?.take(60)
        lastCaller = r.caller
    }

    // ── who is calling ───────────────────────────────────────────────────

    /** Shared auth failure lockout used by both [ApiServer] and [PublicServer] (7.3, 7.7). */
    internal val failLimiter = AuthFailLimiter(ctx)
    private val nonces = LinkedHashMap<String, Long>()

    private fun failed(r: Req, code: String, msg: String, status: Int = 401): Resp =
        failLimiter.recordFailure(r.peerName ?: "local", r.remote, r.path, code, msg, status)

    /**
     * [Auth] when the request proved a live token, else the refusal.
     *
     * Two ways to prove it:
     *  - Bearer (`Authorization: Bearer <token>` / `X-VScreen-Token`): the token itself is sent. Fine on this phone
     *    (loopback); from another device it is accepted only when the user turned "signed requests only" off.
     *  - Signed (1.2): `X-VScreen-Key: <token id>`, `X-VScreen-Time: <unix seconds>`, `X-VScreen-Nonce: <random>`,
     *    `X-VScreen-Sign: sha256hex(K \n time \n nonce \n METHOD \n target \n sha256hex(body) \n K)` with
     *    K = sha256hex(token secret). The token never travels, a captured request cannot be replayed (time window +
     *    nonce) or changed (the body is in the signature). The `?token=` query form of 1.1 is gone: URLs end up in
     *    logs and histories.
     */
    private fun authenticate(r: Req): Any {
        val who = r.peerName ?: "local"
        failLimiter.checkLocked(who, r.remote)?.let { return it }
        val kid = r.headers["x-vscreen-key"]
        if (kid != null) {
            val t = Tokens.byId(kid)?.takeIf { !it.publicOnly && it.key.isNotEmpty() }
                ?: return failed(r, "BAD_TOKEN", tr("没有这个令牌（可能已被用户撤销）：请用户在 Agent-VScreen 里重新复制接入命令", "No such token (the user may have revoked it): ask the user to copy the connect message in Agent-VScreen again"))
            val ts = r.headers["x-vscreen-time"]?.toLongOrNull() ?: return failed(r, "BAD_SIGNATURE", "X-VScreen-Time missing")
            val nonce = r.headers["x-vscreen-nonce"]?.takeIf { it.length in 8..64 && it.all { c -> c.isLetterOrDigit() } } ?: return failed(r, "BAD_SIGNATURE", "X-VScreen-Nonce missing (8-64 letters/digits)")
            val sign = r.headers["x-vscreen-sign"]?.lowercase() ?: return failed(r, "BAD_SIGNATURE", "X-VScreen-Sign missing")
            val now = System.currentTimeMillis() / 1000
            if (kotlin.math.abs(now - ts) > SKEW_S) return failed(r, "TIME_SKEW", tr("请求的时间比手机${if (ts > now) "快" else "慢"} ${kotlin.math.abs(now - ts)} 秒（最多差 $SKEW_S 秒）：校准调用方的时钟。手机时间（unix 秒）：$now", "The request's time is ${kotlin.math.abs(now - ts)} s ${if (ts > now) "ahead of" else "behind"} the phone's (at most $SKEW_S s apart): fix the caller's clock. Phone time (unix seconds): $now"))
            if (!constTimeEq(sign, signature(t.key, ts.toString(), nonce, r.method, r.target, r.body))) return failed(r, "BAD_SIGNATURE", tr("签名不对：令牌不匹配，或请求在路上被改过", "Wrong signature: the token does not match, or the request was changed on the way"))
            synchronized(nonces) {
                val cut = System.currentTimeMillis() - 2 * SKEW_S * 1000
                nonces.entries.removeAll { it.value < cut }
                if (nonces.put("$kid:$nonce", System.currentTimeMillis()) != null) return failed(r, "REPLAYED", tr("这个请求已经收到过一次（重放）", "This request was already received once (replay)"))
            }
            return Auth(t, true, nonce)
        }
        val given = r.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: r.headers["x-vscreen-token"]
            ?: return failed(r, "BAD_TOKEN", tr("缺少令牌：在 Agent-VScreen 里复制接入命令（Authorization: Bearer <令牌>，或签名请求）" + if (r.q("token") != null) "。1.2 起不再接受网址里的 ?token=" else "",
                "Missing token: copy the connect message in Agent-VScreen (Authorization: Bearer <token>, or a signed request)" + if (r.q("token") != null) ". Since 1.2 ?token= in the URL is no longer accepted" else ""))
        val t = Tokens.byBearer(given) ?: return failed(r, "BAD_TOKEN", tr("令牌不对或已被撤销：在 Agent-VScreen 里重新复制接入命令", "Wrong or revoked token: copy the connect message in Agent-VScreen again"))
        // 1.3: through the built-in node the peer is a known tailnet device (WhoIs) on an encrypted tunnel: a token is
        // enough (MCP clients cannot sign). Signing stays required for the other remote way in, when the user wants it.
        val knownPeer = r.viaBuiltinTailnet && r.headers["x-vscreen-peer"].let { !it.isNullOrBlank() && it != "?" }
        if (r.remote && ApiConfig.remoteSigned && !knownPeer) return failed(r, "SIGNATURE_REQUIRED", tr("从其它设备来的请求必须签名（令牌不在网络上传）：用最新的 vscreen 命令（它会签名），或请用户在 Agent-VScreen › 其它设备 里关掉“只接受签名请求”",
            "Requests from other devices must be signed (the token is not sent over the network): use the current vscreen command (it signs), or ask the user to turn off \"signed requests only\" under Other devices in Agent-VScreen"))
        return Auth(t, false, null)
    }

    /** The session a caller means: tokens other than the main one have their own session names (they cannot take each other's screen). */
    fun sessionOf(r: Req, json: JSONObject? = null): String {
        val raw = (json?.optString("session")?.takeIf { it.isNotBlank() } ?: r.headers["x-vscreen-session"] ?: r.q("session"))
            ?.trim()?.take(64)?.replace(Regex("[^A-Za-z0-9_.:-]"), "_")?.takeIf { it.isNotEmpty() } ?: VScreenOffloadHandler.DEFAULT_SESSION
        val t = r.auth?.token
        return if (t == null || t.id == Tokens.MAIN) raw else "${t.id}.$raw"
    }

    // ── running a command ────────────────────────────────────────────────

    /** A command in flight or just finished, by request id: a retry of the same request gets the same result, not a second run. */
    private class Flight(val call: Call) {
        val done = CountDownLatch(1)
        @Volatile var res: NativeOffloadResult? = null
        val at = System.currentTimeMillis()
        val waiters = AtomicInteger(0)
    }
    private val flights = LinkedHashMap<String, Flight>()

    /** Run one command for [session] (no caller identity: the app's own use and tests). */
    fun exec(argv: List<String>, session: String): NativeOffloadResult {
        val fresh = AgentLedger.call()
        // Two words that need no screen. `guide` = the short usage; `remembered <where>` = the agent reports where it
        // saved its note (the app shows it to the user).
        when (argv.firstOrNull()) {
            null, "guide" -> {
                val topicArg = argv.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                if (topicArg != null) {
                    Turns.bumpTurn(session)
                    val body = AgentGuide.topic(topicArg)
                    if (body != null) return NativeOffloadResult(0, body + "\n")
                    val avail = AgentGuide.TOPICS.joinToString(", ")
                    return NativeOffloadResult(
                        2,
                        tr("未知主题“$topicArg”。可用主题：$avail\n", "Unknown topic \"$topicArg\". Available topics: $avail\n"),
                        "INVALID_ARGS",
                    )
                }
                // 1.3 beta4: the guide says which version it is for, and whether this agent's note is older.
                val tid = com.kilon.agentvscreen.cmd.Call.current()?.tokenId ?: "main"
                val ack = AgentLedger.ackVersion(tid)
                val head = "Agent-VScreen ${com.kilon.agentvscreen.BuildConfig.VERSION_NAME}" +
                    (if (ack < com.kilon.agentvscreen.BuildConfig.VERSION_CODE) tr("（你的说明还没按这一版确认：更新后执行 remembered \"<存在哪里>\"）", " (your note is not confirmed for this version yet: refresh it, then run remembered \"<where>\")") else "") + "\n\n"
                Turns.bumpTurn(session)
                return NativeOffloadResult(0, head + AgentGuide.guide())
            }
            "remembered" -> {
                val where = argv.drop(1).joinToString(" ").trim()
                if (where.isEmpty()) return NativeOffloadResult(2, tr("用法：vscreen remembered \"<你把说明存在了哪里>\"（例如：长期记忆 / skills/agent-vscreen）\n", "Usage: vscreen remembered \"<where you saved the note>\" (e.g. long-term memory / skills/agent-vscreen)\n"))
                AgentLedger.remembered(where)
                Turns.bumpTurn(session)
                return NativeOffloadResult(0, tr("已记录（版本 ${com.kilon.agentvscreen.BuildConfig.VERSION_NAME}）：你说把 Agent-VScreen 的说明存在了「${where.take(80)}」。App 首页会显示给用户。以后的新对话里，要操作手机应用时用 vscreen（不知道怎么用就先 vscreen guide）。\n", "Recorded: you saved the Agent-VScreen note in \"${where.take(80)}\". The app shows this to the user. In future conversations, use vscreen to operate apps on the phone (run vscreen guide first if unsure how).\n"))
            }
        }
        com.kilon.agentvscreen.host.A11yHeal.maybeHeal(ctx, "command")
        Turns.begin(session)
        try {
            val res0 = handler.handle(NativeOffloadRequest(listOf("vscreen") + argv, session))
            // 1.3 beta4: the agent's note is for an older version: what changed, and how to refresh it (AgentNews).
            val cur = com.kilon.agentvscreen.cmd.Call.current()
            val notice = AgentNews.notice(cur?.tokenId ?: "main", fresh, cur?.via ?: Abilities.Via.CLI, cur == null || cur.source == com.kilon.agentvscreen.cmd.Call.Source.LOCAL)
            // 2.0.0-beta11: the agent's permissions changed since its last reply (host/Permissions).
            val permNote = com.kilon.agentvscreen.host.Permissions.changeNotice(com.kilon.agentvscreen.cmd.Call.current())
            val res1 = if (permNote != null) res0.copy(output = permNote + "\n" + res0.output) else res0
            val res = if (notice != null) res1.copy(output = notice + "\n" + res1.output) else res1
            if (notice != null) return res
            // First call after a long silence (likely a new conversation): one line pointing at the short usage.
            if (!fresh || argv.first() == "help") return res
            return res.copy(output = res.output.trimEnd() + tr("\n\n（Agent-VScreen：用法速查 `vscreen guide`；完整说明 `vscreen help all`。）\n", "\n\n(Agent-VScreen: quick usage `vscreen guide`; full reference `vscreen help all`.)\n"))
        }
        finally { Turns.end(session) }
    }

    /**
     * Run a command for the caller of [r]: with its identity ([Call]: token, scope, peer), cancelled when the caller
     * goes away, and at most once per request id. Returns the result and whether it was a replay of an earlier run.
     */
    fun runFor(r: Req, argv: List<String>, session: String, requestId: String?, onStep: ((String) -> Unit)? = null): Pair<NativeOffloadResult, Boolean> {
        val auth = r.auth ?: return NativeOffloadResult(1, "", "BAD_TOKEN") to false
        if (com.kilon.agentvscreen.host.ThermalGuard.locked) return NativeOffloadResult(1, "", "THERMAL_STOP: " + com.kilon.agentvscreen.host.ThermalGuard.reason()) to false
        val t = Tokens.byId(auth.token.id) ?: auth.token
        val via = when {
            r.path.startsWith("/mcp") -> Abilities.Via.MCP
            r.headers["x-vscreen-client"] == "cli" -> Abilities.Via.CLI
            else -> Abilities.Via.HTTP
        }
        val source = when {
            r.viaPublic -> Call.Source.PUBLIC
            r.viaBuiltinTailnet || r.peer?.isLoopbackAddress == false -> Call.Source.TAILNET
            else -> Call.Source.LOCAL
        }
        val scopes = if (r.viaPublic || t.publicOnly) t.scopes.intersect(Tokens.PUBLIC_SCOPES.toSet())
            .let { s -> if (r.viaPublic && r.publicScope != "operate") s.intersect(setOf("look")) else s } else t.scopes
        val call = Call(session, t.id, t.name, scopes, r.peerName, requestId, com.kilon.agentvscreen.I18n.code, t.trustUntil, via, source,
            srcAddr = if (r.viaPublic) r.headers["x-avs-public-src"]?.trim()?.take(64)?.takeIf { it.isNotEmpty() } else null)
        call.progress = onStep
        val key = requestId?.let { "${t.id}:$it" }
        var mine: Flight? = null
        if (key != null) {
            val other = synchronized(flights) {
                val now = System.currentTimeMillis()
                flights.entries.removeAll { it.value.res != null && now - it.value.at > IDEM_MS }
                while (flights.size > IDEM_MAX) flights.remove(flights.keys.first())
                flights[key] ?: run { mine = Flight(call).also { flights[key] = it }; null }
            }
            if (other != null) {
                // The same request again (the caller's connection broke and it retried): wait for the first run.
                other.waiters.incrementAndGet()
                val watch = watchDisconnect(r, other)
                try { other.done.await(15, TimeUnit.MINUTES) } finally { watch.interrupt() }
                return (other.res ?: NativeOffloadResult(1, "", "CANCELLED")) to true
            }
        }
        val flight = mine ?: Flight(call)
        flight.waiters.incrementAndGet()
        val watch = watchDisconnect(r, flight)
        try {
            val res = Call.with(call) { exec(argv, session) }
            flight.res = res
            return res to false
        } finally { flight.done.countDown(); watch.interrupt() }
    }

    /**
     * 1.2: when the caller's connection goes away while its command runs, the command stops before its next step
     * (before, the phone kept working for nobody and held the screen). With a request id the caller gets
     * [GRACE_MS] to come back with the same id first.
     */
    private fun watchDisconnect(r: Req, f: Flight): Thread = Thread({
        val gone = try { r.sock?.getInputStream()?.read() ?: return@Thread; true } catch (e: Exception) { true }
        if (!gone || f.res != null || f.done.count == 0L || Thread.currentThread().isInterrupted) return@Thread
        val left = f.waiters.decrementAndGet()
        if (f.call.requestId != null) { try { Thread.sleep(GRACE_MS) } catch (e: InterruptedException) { return@Thread } }
        if ((f.call.requestId == null || f.waiters.get() <= 0) && left <= 0 && f.res == null && f.done.count != 0L) {
            f.call.cancelled = true
            AppLogger.info(TAG, "caller went away: cancelling ${f.call.session}")
        }
    }, "vscreen-http-watch").apply { isDaemon = true; start() }

    /** Picture URLs use the address the caller used (this phone: 127.0.0.1; a tailnet peer: our tailnet address). */
    fun shotUrl(name: String, r: Req? = null, v2: Boolean = true): String {
        val h = r?.headers?.get("host")?.takeIf { r.remote && it.isNotBlank() }
        return (if (h != null) "http://$h" else ApiConfig.baseUrl) + (if (v2) "/v2/shot/" else "/v1/shot/") + name
    }

    /**
     * POST /v2/run (and /v1/run, kept for one version). Body, any of:
     *   {"args":["act","tap Search"]} · {"cmd":"act \"tap Search\""} · {"command":"act","args":"tap Search","options":{"fast":true}}
     *   form a=…&a=… · `Content-Type: application/x-vscreen-hex` with one hex-encoded argument per line (the CLI)
     * plus optional "session", "request_id" (or headers X-VScreen-Session / X-VScreen-Request-Id).
     */
    private fun run(r: Req, auth: Auth, v2: Boolean): Resp {
        val ct = r.headers["content-type"].orEmpty()
        var json: JSONObject? = null
        val argv: List<String> = when {
            ct.startsWith("application/x-www-form-urlencoded") -> parseForm(r.bodyText)["a"].orEmpty()
            ct.startsWith("application/x-vscreen-hex") -> {
                val out = ArrayList<String>()
                for (l in r.bodyText.lines()) { val h = l.trim(); if (h.isEmpty()) continue; out += (unhex(h) ?: return Resp.err(400, "BAD_REQUEST", "bad hex argument")).let { if (it == "\u0000") "" else it } }
                out
            }
            else -> {
                json = runCatching { JSONObject(r.bodyText.ifBlank { "{}" }) }.getOrElse { return Resp.err(400, "BAD_JSON", "body must be JSON: {\"args\":[\"act\",\"tap Search\"]} or {\"cmd\":\"act \\\"tap Search\\\"\"}") }
                val command = json.optString("command").takeIf { it.isNotBlank() }
                if (command != null) {
                    val c = com.kilon.agentvscreen.cmd.CommandSpec.of(command) ?: return Resp.err(400, "UNKNOWN_COMMAND", command)
                    com.kilon.agentvscreen.cmd.CommandSpec.argvOf(c, json.opt("args")?.takeIf { it != JSONObject.NULL }?.toString(), json.optJSONObject("options"))
                } else json.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                    ?: json.optString("cmd").takeIf { it.isNotBlank() }?.let { splitCommand(it) } ?: emptyList()
            }
        }
        val session = sessionOf(r, json)
        val requestId = (json?.optString("request_id")?.takeIf { it.isNotBlank() } ?: r.q("request_id") ?: r.headers["x-vscreen-request-id"])?.take(64)
        if (v2 && r.accepts("text/event-stream")) return Resp.sse { em ->
            val (res, replayed) = runFor(r, argv, session, requestId) { line -> quiet("sse-step") { em.send("step", line) } }
            quiet("sse-result") { em.send("result", envelope(res, session, requestId, replayed, r, true).toString()) }
            res.afterSend?.let { quiet("sse-after-send") { it() } }
        }
        val (res, replayed) = runFor(r, argv, session, requestId)
        val env = envelope(res, session, requestId, replayed, r, v2)
        val headers = mapOf("X-VScreen-Exit" to env.optInt("exit").toString(), "X-VScreen-Code" to env.optString("code"), "X-VScreen-Images" to env.optJSONArray("images").let { a -> (0 until (a?.length() ?: 0)).joinToString(",") { a!!.getJSONObject(it).optString("name") } }, "X-VScreen-Session" to session) +
            (if (v2) emptyMap() else mapOf("Deprecation" to "true", "Link" to "</v2/run>; rel=\"successor-version\""))
        return (if (r.q("format") == "text" || r.headers["accept"]?.startsWith("text/plain") == true) Resp.text(env.optString("text"), 200, headers)
        else Resp.json(env, 200, headers)).withAfterSend(res.afterSend)
    }

    /**
     * The reply to a command.
     *
     * /v2: {ok, code, exit, next, session, request_id, replayed, text, images[], …data}. `ok` is true only when the
     * command did what was asked: a stretch that stopped at a step is ok=false, exit=1, with the code of the step.
     * The command's structured data (outcome, steps[], screen{…elements[]}, notes[] for act / launch) sits beside the text.
     * /v1 (deprecated, one more version): as 1.1 — a stretch that stopped at a step still says ok=true, exit=0.
     */
    private fun envelope(res: NativeOffloadResult, session: String, requestId: String?, replayed: Boolean, r: Req, v2: Boolean): JSONObject {
        val all = ShotStore.refs(res.output)
        // 1.4.0-beta2: clients get only pictures that say something new (ImagePolicy). 1.4.0-beta5: the CLI too (test:
        // it got a fresh copy of an unchanged screen every time and its text never said "no picture"); it still gets
        // them at full size, since it prints paths instead of feeding a model directly.
        val cli = r.headers["x-vscreen-client"] == "cli"
        val shots = if (!v2) all else ImagePolicy.pick(res, all)
        val text = ShotStore.IN_TEXT.replace(res.output) { m -> val n = m.groupValues[1]; if (n in shots) shotUrl(n, r, v2) else ImagePolicy.leftOut() }
        val meter = com.kilon.agentvscreen.core.Meter.of(
            text,
            com.kilon.agentvscreen.core.Meter.sentDimensions(ctx, res, shots, fullSize = cli || !v2),
            structured = if (v2) res.data?.toString() else null,
        )
        val exit = if (!v2 && res.softFail) 0 else res.exitCode
        val code = if (!v2 && res.softFail) "OK" else res.codeOrDefault
        val o = JSONObject()
        if (v2) res.data?.let { d -> for (k in d.keys()) o.put(k, d.get(k)) }
        else o.put("data", text.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
        if (v2) fixShots(o, r)
        o.put("ok", exit == 0).put("exit", exit).put("code", if (v2 || exit != 0) code else JSONObject.NULL).put("session", session).put("text", text)
            .put("images", JSONArray(shots.map { n -> JSONObject().put("name", n).put("full_url", shotUrl(n, r, v2))
                .put("url", if (cli || !v2) shotUrl(n, r, v2) else shotUrl(ImagePolicy.sendName(ctx, res, n), r, v2)) }))
            .put("meter", meter.json())
            .put("returned_at", res.data?.optLong("returned_at")?.takeIf { it > 0L } ?: System.currentTimeMillis())
        if (v2) {
            o.put("next", com.kilon.agentvscreen.cmd.Codes.next(code).takeIf { exit != 0 } ?: "none")
            requestId?.let { o.put("request_id", it) }
            if (replayed) o.put("replayed", true)
        }
        return o
    }

    /** Picture references inside the structured data become URLs too. */
    private fun fixShots(o: JSONObject, r: Req) {
        for (k in o.keys().asSequence().toList()) when (val v = o.opt(k)) {
            is String -> if (v.startsWith(ShotStore.REF)) o.put(k, shotUrl(v.removePrefix(ShotStore.REF), r))
            is JSONObject -> fixShots(v, r)
        }
    }

    private fun shot(name: String): Resp {
        val f = ShotStore.file(ctx, name) ?: return Resp.err(404, "NO_SHOT", tr("没有这张图（只保留最近 300 张，副屏释放后会清掉）：$name", "No such picture (only the last 300 are kept, and they are cleared when the screen is released): $name"))
        return Resp(200, "image/jpeg", f.readBytes())
    }

    private fun events(r: Req, @Suppress("UNUSED_PARAMETER") auth: Auth): Resp {
        val wait = r.q("wait")?.let { com.kilon.agentvscreen.cmd.VScreenOptions.durationMs(it) } ?: 0L
        val evs = EventHub.take(sessionOf(r), wait, r.q("again") in setOf("1", "true"))
        return Resp.json(JSONObject().put("ok", true).put("events", JSONArray(evs)))
    }

    private var cliCache: String? = null
    /** 1.1.7: «中文|English» markers in the script are resolved per language. */
    private fun cliScript(): String = (cliCache ?: ctx.assets.open("cli/vscreen.sh").bufferedReader().readText().also { cliCache = it })
        .replace(CLI_LANG) { if (com.kilon.agentvscreen.I18n.en) it.groupValues[2] else it.groupValues[1] }
        .replace("@@PORT@@", ApiConfig.port.toString()).replace("@@VERSION@@", cliVersion())

    /** The CLI's version carries the language, so an installed client replaces itself (and its skill) when it changes. */
    internal fun cliVersion() = "${BuildConfig.VERSION_NAME}-${com.kilon.agentvscreen.I18n.code}"

    companion object {
        const val MAX_CONN = HttpCore.MAX_CONN
        const val MAX_HEAD = HttpCore.MAX_HEAD
        const val MAX_BODY = HttpCore.MAX_BODY
        const val HEAD_MS = HttpCore.HEAD_MS
        const val BODY_MS = HttpCore.BODY_MS
        const val SKEW_S = 300L
        const val MAX_FAILS = HttpCore.MAX_FAILS
        const val LOCK_MS = HttpCore.LOCK_MS
        const val GRACE_MS = 3_000L
        const val IDEM_MS = 10 * 60_000L
        const val IDEM_MAX = 200

        /** What a signed request signs. K = sha256hex(token secret). Pure. */
        fun signature(key: String, time: String, nonce: String, method: String, target: String, body: ByteArray): String =
            Tokens.sha256("$key\n$time\n$nonce\n${method.uppercase()}\n$target\n${Tokens.sha256(body)}\n$key")

        /** What a reply to a signed request carries in X-VScreen-Proof. Pure. */
        fun proof(key: String, nonce: String, body: ByteArray): String = Tokens.sha256("$key\n$nonce\nresp\n${Tokens.sha256(body)}\n$key")

        fun unhex(h: String): String? {
            if (h.length % 2 != 0 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return String(ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, Charsets.UTF_8)
        }

        private val CLI_LANG = Regex("«([^|»]*)\\|([^»]*)»")

        fun constTimeEq(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var d = 0; for (i in a.indices) d = d or (a[i].code xor b[i].code); return d == 0
        }

        /** Tailscale hands out 100.64.0.0/10 (CGNAT) addresses. */
        fun isTailnet(a: InetAddress?): Boolean {
            val b = (a as? java.net.Inet4Address)?.address ?: return false
            return (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
        }

        /**
         * This phone's address in the system Tailscale app's tailnet, if it is connected. 1.2: only addresses of a VPN
         * network count. Carriers also hand out 100.64.0.0/10 (carrier-grade NAT) on the mobile interface; taking
         * "any interface with such an address" could have put the server on the mobile network.
         */
        fun tailnetAddress(): InetAddress? = runCatching {
            val cm = com.kilon.agentvscreen.core.BackgroundOperation.appContextOrNull()?.getSystemService(android.net.ConnectivityManager::class.java) ?: return@runCatching null
            @Suppress("DEPRECATION")
            cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
                .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }.map { it.address }.firstOrNull { isTailnet(it) }
        }.getOrNull()

        fun parseForm(s: String): Map<String, List<String>> = HttpCore.parseForm(s)

        /** Shell-like split: spaces separate, "…" and '…' group, backslash escapes inside "…" and outside quotes. */
        fun splitCommand(s: String): List<String> {
            val out = ArrayList<String>(); val cur = StringBuilder(); var inTok = false
            var i = 0; var q: Char? = null
            while (i < s.length) {
                val c = s[i]
                when {
                    q == '\'' -> if (c == '\'') q = null else cur.append(c)
                    q == '"' -> when {
                        c == '"' -> q = null
                        c == '\\' && i + 1 < s.length && s[i + 1] in "\"\\$`" -> { cur.append(s[i + 1]); i++ }
                        else -> cur.append(c)
                    }
                    c == '"' || c == '\'' -> { q = c; inTok = true }
                    c == '\\' && i + 1 < s.length -> { cur.append(s[i + 1]); i++; inTok = true }
                    c.isWhitespace() -> { if (inTok) { out += cur.toString(); cur.clear(); inTok = false } }
                    else -> { cur.append(c); inTok = true }
                }
                i++
            }
            if (inTok) out += cur.toString()
            return out.let { if (it.firstOrNull() == "vscreen") it.drop(1) else it }
        }
    }
}
