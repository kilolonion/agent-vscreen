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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
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

    /** 1.2: the extra socket on this phone's Tailscale address (null: off, or no tailnet address now). */
    @Volatile private var tailServer: ServerSocket? = null
    @Volatile var tailAddress: String? = null; private set
    @Volatile private var tailWatch: Thread? = null

    /** Called when [lastError] / listening changes (the notification shows it). */
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile private var retry: Thread? = null
    @Volatile var bindFailures = 0; private set

    fun start(port: Int) {
        stop()
        if (ApiConfig.tailnet) startTailnet(port)
        if (!bindLocal(port)) retryBind(port)
    }

    /**
     * Bind 127.0.0.1:[port]. SO_REUSEADDR must be set BEFORE bind (setting it on a bound socket does nothing), so a
     * restart never trips over connections of the previous run still in TIME_WAIT.
     */
    private fun bindLocal(port: Int, owner: String? = null): Boolean {
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
        } catch (e: Exception) {
            com.kilon.agentvscreen.host.quiet("ApiServer:104") { ss.close() }
            bindFailures++
            lastError = tr("端口 $port 被占用：${owner ?: "正在查是谁占着"}，正在自动重试（第 $bindFailures 次）", "Port $port is taken: ${owner ?: "checking by whom"}; retrying automatically (attempt $bindFailures)")
            AppLogger.warning(TAG, "bind $port: ${e.message} — ${owner ?: "?"}")
            onChange?.invoke()
            return false
        }
        server = ss; lastError = null; portBusy = false
        if (bindFailures > 0) AppLogger.info(TAG, "port $port free again after $bindFailures failed tries")
        bindFailures = 0
        Thread({ acceptLoop(ss) }, "vscreen-http-accept").apply { isDaemon = true }.start()
        AppLogger.info(TAG, "listening on 127.0.0.1:$port")
        onChange?.invoke()
        return true
    }

    /**
     * The port is taken. Almost always by our own previous process that is still going away (after an update, a crash
     * restart, or a kill while frozen): it lets go within seconds to a minute. So keep trying (1 s, 2 s, 4 s … 15 s),
     * and say who holds it, so the user is never left with a dead server and no hint.
     */
    private fun retryBind(port: Int) {
        retry = Thread({
            var wait = 1_000L
            while (!Thread.currentThread().isInterrupted && server == null) {
                val who = portOwner(port)
                try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
                if (bindLocal(port, who)) break
                wait = (wait * 2).coerceAtMost(15_000L)
            }
        }, "vscreen-http-rebind").apply { isDaemon = true }.also { it.start() }
    }

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

    fun stop() {
        retry?.interrupt(); retry = null
        runCatching { server?.close() }; server = null
        tailWatch?.interrupt(); tailWatch = null
        runCatching { tailServer?.close() }; tailServer = null; tailAddress = null
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
                    if (want != null) runCatching {
                        val ss = ServerSocket(port, 50, want).apply { reuseAddress = true }
                        tailServer = ss; tailAddress = want.hostAddress
                        AppLogger.info(TAG, "also listening on tailnet ${want.hostAddress}:$port")
                        Thread({ acceptLoop(ss) }, "vscreen-http-tail").apply { isDaemon = true }.start()
                    }.onFailure { AppLogger.warning(TAG, "tailnet bind: ${it.message}") }
                }
                try { Thread.sleep(20_000) } catch (_: InterruptedException) { break }
            }
        }, "vscreen-tailnet-watch").apply { isDaemon = true }.also { it.start() }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (e: SocketException) { break } catch (e: Exception) { continue }
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
        /** Forwarded by the built-in Tailscale node (loopback + its secret). */
        val viaBuiltinTailnet: Boolean get() = peer?.isLoopbackAddress == true &&
            headers["x-vscreen-tailnet"]?.let { constTimeEq(it, com.kilon.agentvscreen.host.Tailnet.secret) } == true
        val remote: Boolean get() = viaBuiltinTailnet || peer?.isLoopbackAddress == false
        /** The other device, as the tailnet names it (null on this phone). */
        val peerName: String? get() = when {
            viaBuiltinTailnet -> headers["x-vscreen-peer"] ?: "?"
            peer?.isLoopbackAddress == false -> peer?.hostAddress
            else -> null
        }
        /** Who is calling, for the app's "last caller" line. */
        val caller: String get() = (peerName?.let { "Tailscale: $it" } ?: tr("本机", "This phone")) + (auth?.token?.takeIf { it.id != Tokens.MAIN }?.let { " · ${it.name}" } ?: "")
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
               val stream: ((Emitter) -> Unit)? = null) {
        fun plus(h: Map<String, String>) = Resp(status, type, body, headers + h, stream)
        companion object {
            fun json(o: Any, status: Int = 200, headers: Map<String, String> = emptyMap()) =
                Resp(status, "application/json; charset=utf-8", o.toString().toByteArray(), headers)
            fun text(t: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
                Resp(status, "text/plain; charset=utf-8", t.toByteArray(), headers)
            fun err(status: Int, code: String, msg: String) = json(JSONObject().put("ok", false).put("code", code).put("error", msg)
                .put("next", com.kilon.agentvscreen.cmd.Codes.next(code)), status, mapOf("X-VScreen-Code" to code))
            fun sse(block: (Emitter) -> Unit) = Resp(200, "text/event-stream; charset=utf-8", ByteArray(0), stream = block)
        }
    }

    private class HttpError(val status: Int, val code: String, message: String) : Exception(message)

    private fun serve(s: Socket) {
        s.use { sock ->
            runCatching {
                // 1.2: the request must arrive promptly and be of sane size: a client that opens a connection and
                // sends nothing (or drips bytes) holds a worker for at most HEAD_MS, not forever.
                sock.soTimeout = 5_000
                val inp = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                synchronized(reading) { reading[sock] = System.currentTimeMillis() }
                val req = try { read(inp) } catch (e: HttpError) { write(out, Resp.err(e.status, e.code, e.message ?: e.code)); return }
                    catch (e: java.net.SocketTimeoutException) { quiet("http-408") { write(out, Resp.err(408, "REQUEST_TIMEOUT", "the request did not arrive in time")) }; return }
                    finally { synchronized(reading) { reading.remove(sock) } } ?: return
                req.peer = sock.inetAddress
                req.sock = sock
                sock.soTimeout = 0 // a command (act --watch, events --wait) may take minutes
                val resp = try { route(req) } catch (t: Throwable) {
                    AppLogger.error(TAG, "route ${req.path}", t)
                    Resp.err(500, "INTERNAL_ERROR", "${t.javaClass.simpleName}: ${t.message}")
                }
                write(out, resp)
            }.onFailure { if (it !is SocketException && it !is java.io.IOException) AppLogger.warning(TAG, "serve: ${it.message}") }
        }
    }

    private fun read(inp: InputStream): Req? {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = inp.read(); if (b < 0) return null
            head.write(b); last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > MAX_HEAD) throw HttpError(431, "HEADERS_TOO_LARGE", "request headers over $MAX_HEAD bytes")
            if (head.size() % 512 == 0 && android.os.SystemClock.elapsedRealtime() - t0 > HEAD_MS) throw HttpError(408, "REQUEST_TIMEOUT", "request headers took too long")
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) throw HttpError(400, "BAD_REQUEST", "bad request line")
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) { val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
        val body: ByteArray = if (headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true) readChunked(inp, t0) else {
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len < 0 || len > MAX_BODY) throw HttpError(413, "BODY_TOO_LARGE", "request body over $MAX_BODY bytes")
            val b = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = inp.read(b, off, len - off); if (n < 0) throw HttpError(400, "BAD_REQUEST", "body shorter than Content-Length")
                off += n
                if (android.os.SystemClock.elapsedRealtime() - t0 > BODY_MS) throw HttpError(408, "REQUEST_TIMEOUT", "request body took too long")
            }
            b
        }
        val target = parts[1]
        return Req(parts[0].uppercase(), target, parseForm(target.substringAfter('?', "")), headers, body)
    }

    /** 1.2: Transfer-Encoding: chunked request bodies (some HTTP clients and proxies send nothing else). */
    private fun readChunked(inp: InputStream, t0: Long): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(): String { val sb = StringBuilder(); while (true) { val c = inp.read(); if (c < 0) throw HttpError(400, "BAD_REQUEST", "chunked body cut off"); if (c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()); if (sb.length > 256) throw HttpError(400, "BAD_REQUEST", "bad chunk header") }; return sb.toString() }
        while (true) {
            val size = line().substringBefore(';').trim().toIntOrNull(16) ?: throw HttpError(400, "BAD_REQUEST", "bad chunk size")
            if (size == 0) { while (line().isNotEmpty()) { /* trailers */ }; break }
            if (size < 0 || out.size() + size > MAX_BODY) throw HttpError(413, "BODY_TOO_LARGE", "request body over $MAX_BODY bytes")
            val b = ByteArray(size); var off = 0
            while (off < size) { val n = inp.read(b, off, size - off); if (n < 0) throw HttpError(400, "BAD_REQUEST", "chunked body cut off"); off += n }
            out.write(b); line()
            if (android.os.SystemClock.elapsedRealtime() - t0 > BODY_MS) throw HttpError(408, "REQUEST_TIMEOUT", "request body took too long")
        }
        return out.toByteArray()
    }

    private fun write(out: OutputStream, r: Resp) {
        val reason = when (r.status) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 408 -> "Request Timeout"; 413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"; 503 -> "Service Unavailable"; else -> "Error" }
        val sb = StringBuilder("HTTP/1.1 ${r.status} $reason\r\nContent-Type: ${r.type}\r\n")
        if (r.stream == null) sb.append("Content-Length: ${r.body.size}\r\n")
        sb.append("Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-VScreen-Version: ${cliVersion()}\r\n")
        r.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v.replace("\r", "").replace("\n", " ")).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        if (r.stream == null) { out.write(r.body); out.flush() } else { out.flush(); r.stream.invoke(Emitter(out)) }
    }

    // ── routing ───────────────────────────────────────────────────────────

    private fun route(r: Req): Resp {
        // No browser may drive the phone (CSRF / DNS rebinding): only known hosts, no Origin.
        val host = r.headers["host"]?.substringBeforeLast(':')?.lowercase()?.takeIf { it.isNotEmpty() } ?: r.headers["host"]?.lowercase()
        val remote = r.peer?.let { !it.isLoopbackAddress } == true
        if (r.viaBuiltinTailnet) {
            // Forwarded by our own Tailscale node (it alone knows the secret). The caller must still name the node by
            // its tailnet address or name, so a web page cannot rebind a hostname onto it.
            if (!com.kilon.agentvscreen.host.Tailnet.status.value.loggedIn) return Resp.err(403, "NOT_ALLOWED", tr("内置 Tailscale 没有登录", "Built-in Tailscale is not logged in"))
            if (host != null && host !in com.kilon.agentvscreen.host.Tailnet.hostNames() && !ApiConfig.tailnetHostOk(host)) return Resp.err(403, "BAD_HOST", tr("用这台手机的 Tailscale 地址或名字访问（自建控制服务器的域名后缀在 App › 其它设备 里填）", "Use this phone's Tailscale address or name (for a self-hosted control server, enter its name suffix under Other devices in the app)"))
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
                .put("version", BuildConfig.VERSION_NAME).put("code", BuildConfig.VERSION_CODE).put("pid", android.os.Process.myPid())
                .put("time", System.currentTimeMillis() / 1000).put("api", JSONArray(listOf("v2", "v1")))
                .put("endpoints", JSONArray(listOf("/v2/run", "/v2/shot/<name>", "/v2/events", "/v2/help", "/v2/codes", "/mcp", "/cli"))))
            "/v1/help", "/v2/help" -> return langOf(r) { Resp.text(VScreenOffloadHandler.HELP + "\n") }
            "/v2/codes" -> return langOf(r) { Resp.json(JSONObject().put("codes", JSONArray(com.kilon.agentvscreen.cmd.Codes.all.map { JSONObject().put("code", it.name).put("next", it.next.key).put("text", it.text) }))) }
            "/cli" -> { if (r.q("via") == "install") AgentLedger.installed(); return Resp.text(cliScript()) }
            "/skill" -> return langOf(r) { Resp.text(AgentGuide.skill()) }
            "/memory" -> return langOf(r) { Resp.text(AgentGuide.memory() + "\n") }
        }
        val auth = when (val a = authenticate(r)) { is Auth -> a; is Resp -> return a; else -> return Resp.err(401, "BAD_TOKEN", "?") }
        r.auth = auth
        Tokens.used(auth.token.id)
        lastClient = r.headers["user-agent"]?.take(60)
        lastCaller = r.caller
        val resp = langOf(r) { when {
            r.path == "/v2/run" -> if (r.method == "POST") run(r, auth, v2 = true) else Resp.err(405, "METHOD", "POST")
            r.path == "/v1/run" -> if (r.method == "POST") run(r, auth, v2 = false) else Resp.err(405, "METHOD", "POST")
            r.path.startsWith("/v1/shot/") || r.path.startsWith("/v2/shot/") -> shot(r.path.substringAfterLast('/'))
            r.path == "/v1/events" || r.path == "/v2/events" -> events(r, auth)
            r.path == "/v2/cli" -> Resp.text(cliScript())
            r.path == "/mcp" -> if (r.method == "POST") Mcp.handle(this, r) else Resp(405, "text/plain", "MCP: POST JSON-RPC".toByteArray(), mapOf("Allow" to "POST"))
            else -> Resp.err(404, "NOT_FOUND", r.path)
        } }
        // 1.2: a signed request gets a signed answer, so the caller knows the reply (and a CLI update) came from this
        // phone and was not changed on the way.
        return if (auth.signed && auth.nonce != null && resp.stream == null) resp.plus(mapOf("X-VScreen-Proof" to proof(auth.token.key, auth.nonce, resp.body))) else resp
    }

    private fun <T> langOf(r: Req, block: () -> T): T = com.kilon.agentvscreen.I18n.with((r.headers["x-vscreen-lang"] ?: r.q("lang"))?.lowercase()?.take(2), block)

    // ── who is calling ───────────────────────────────────────────────────

    /** Failed checks per caller in the last minute; a remote caller that keeps failing is shut out for a while. */
    private val fails = HashMap<String, ArrayDeque<Long>>()
    private val lockedUntil = HashMap<String, Long>()
    private val nonces = LinkedHashMap<String, Long>()

    private fun failed(r: Req, code: String, msg: String, status: Int = 401): Resp {
        val who = r.peerName ?: "local"
        val now = System.currentTimeMillis()
        val n = synchronized(fails) {
            val q = fails.getOrPut(who) { ArrayDeque() }
            q.addLast(now); while (q.isNotEmpty() && now - q.first() > 60_000L) q.removeFirst()
            if (fails.size > 500) fails.keys.firstOrNull()?.let { fails.remove(it) }
            q.size
        }
        Audit.security("auth_failed", "$who $code ${r.path}")
        if (r.remote && n >= MAX_FAILS) {
            val first = synchronized(fails) { val was = lockedUntil[who] ?: 0L; lockedUntil[who] = now + LOCK_MS; was < now }
            if (first) {
                Audit.security("locked_out", who)
                quiet("lockout-notify") { com.kilon.agentvscreen.core.VScreenHandoff.alert(ctx, 7390, tr("有设备在反复尝试连接 Agent-VScreen", "A device keeps trying to connect to Agent-VScreen"),
                    tr("$who 一分钟内 $n 次验证失败，已暂停接受它的请求 ${LOCK_MS / 60_000} 分钟。不是你的设备的话，在 其它设备 里关掉远程访问或重置令牌", "$who failed verification $n times within a minute; its requests are refused for ${LOCK_MS / 60_000} minutes. If it is not your device, turn off remote access or reset the token under Other devices")) }
            }
        }
        // Slow every wrong guess down a little (bounded: it holds one worker).
        Thread.sleep(if (r.remote) 400 else 100)
        return Resp.err(status, code, msg)
    }

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
        if (r.remote) synchronized(fails) { lockedUntil[who] }?.let { until ->
            if (until > System.currentTimeMillis()) return Resp.err(429, "RATE_LIMITED", tr("验证失败次数太多，${(until - System.currentTimeMillis()) / 1000 + 1} 秒后再试", "Too many failed verifications; try again in ${(until - System.currentTimeMillis()) / 1000 + 1} s"))
        }
        val kid = r.headers["x-vscreen-key"]
        if (kid != null) {
            val t = Tokens.byId(kid) ?: return failed(r, "BAD_TOKEN", tr("没有这个令牌（可能已被用户撤销）：请用户在 Agent-VScreen 里重新复制接入命令", "No such token (the user may have revoked it): ask the user to copy the connect message in Agent-VScreen again"))
            val ts = r.headers["x-vscreen-time"]?.toLongOrNull() ?: return failed(r, "BAD_SIGNATURE", "X-VScreen-Time missing")
            val nonce = r.headers["x-vscreen-nonce"]?.takeIf { it.length in 8..64 && it.all { c -> c.isLetterOrDigit() } } ?: return failed(r, "BAD_SIGNATURE", "X-VScreen-Nonce missing (8-64 letters/digits)")
            val sign = r.headers["x-vscreen-sign"]?.lowercase() ?: return failed(r, "BAD_SIGNATURE", "X-VScreen-Sign missing")
            val now = System.currentTimeMillis() / 1000
            if (kotlin.math.abs(now - ts) > SKEW_S) return failed(r, "TIME_SKEW", tr("请求的时间和手机差了 ${now - ts} 秒（最多 $SKEW_S 秒）：校准调用方的时钟。手机时间（unix 秒）：$now", "The request's time is ${now - ts} s off the phone's (at most $SKEW_S s): fix the caller's clock. Phone time (unix seconds): $now"))
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
        if (r.remote && ApiConfig.remoteSigned) return failed(r, "SIGNATURE_REQUIRED", tr("从其它设备来的请求必须签名（令牌不在网络上传）：用最新的 vscreen 命令（它会签名），或请用户在 Agent-VScreen › 其它设备 里关掉“只接受签名请求”",
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
            null, "guide" -> return NativeOffloadResult(0, AgentGuide.guide())
            "remembered" -> {
                val where = argv.drop(1).joinToString(" ").trim()
                if (where.isEmpty()) return NativeOffloadResult(2, tr("用法：vscreen remembered \"<你把说明存在了哪里>\"（例如：长期记忆 / skills/agent-vscreen）\n", "Usage: vscreen remembered \"<where you saved the note>\" (e.g. long-term memory / skills/agent-vscreen)\n"))
                AgentLedger.remembered(where)
                return NativeOffloadResult(0, tr("已记录：你说把 Agent-VScreen 的说明存在了「${where.take(80)}」。App 首页会显示给用户。以后的新对话里，要操作手机应用时用 vscreen（不知道怎么用就先 vscreen guide）。\n", "Recorded: you saved the Agent-VScreen note in \"${where.take(80)}\". The app shows this to the user. In future conversations, use vscreen to operate apps on the phone (run vscreen guide first if unsure how).\n"))
            }
        }
        com.kilon.agentvscreen.host.A11yHeal.maybeHeal(ctx, "command")
        Turns.begin(session)
        try {
            val res = handler.handle(NativeOffloadRequest(listOf("vscreen") + argv, session))
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
        val t = Tokens.byId(auth.token.id) ?: auth.token
        val call = Call(session, t.id, t.name, t.scopes, r.peerName, requestId, com.kilon.agentvscreen.I18n.code, t.trustUntil)
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
        }
        val (res, replayed) = runFor(r, argv, session, requestId)
        val env = envelope(res, session, requestId, replayed, r, v2)
        val headers = mapOf("X-VScreen-Exit" to env.optInt("exit").toString(), "X-VScreen-Code" to env.optString("code"), "X-VScreen-Images" to ShotStore.refs(res.output).joinToString(","), "X-VScreen-Session" to session) +
            (if (v2) emptyMap() else mapOf("Deprecation" to "true", "Link" to "</v2/run>; rel=\"successor-version\""))
        return if (r.q("format") == "text" || r.headers["accept"]?.startsWith("text/plain") == true) Resp.text(env.optString("text"), 200, headers)
        else Resp.json(env, 200, headers)
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
        val shots = ShotStore.refs(res.output)
        val text = ShotStore.IN_TEXT.replace(res.output) { shotUrl(it.groupValues[1], r, v2) }
        val exit = if (!v2 && res.softFail) 0 else res.exitCode
        val code = if (!v2 && res.softFail) "OK" else res.codeOrDefault
        val o = JSONObject()
        if (v2) res.data?.let { d -> for (k in d.keys()) o.put(k, d.get(k)) }
        else o.put("data", text.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
        if (v2) fixShots(o, r)
        o.put("ok", exit == 0).put("exit", exit).put("code", if (v2 || exit != 0) code else JSONObject.NULL).put("session", session).put("text", text)
            .put("images", JSONArray(shots.map { JSONObject().put("name", it).put("url", shotUrl(it, r, v2)) }))
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
        const val MAX_CONN = 24
        const val MAX_HEAD = 16_384
        const val MAX_BODY = 1_048_576
        const val HEAD_MS = 10_000L
        const val BODY_MS = 20_000L
        const val SKEW_S = 300L
        const val MAX_FAILS = 8
        const val LOCK_MS = 5 * 60_000L
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

        fun parseForm(s: String): Map<String, List<String>> {
            if (s.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, MutableList<String>>()
            for (kv in s.split('&')) {
                if (kv.isEmpty()) continue
                val k = URLDecoder.decode(kv.substringBefore('='), "UTF-8")
                val v = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                out.getOrPut(k) { ArrayList() } += v
            }
            return out
        }

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
