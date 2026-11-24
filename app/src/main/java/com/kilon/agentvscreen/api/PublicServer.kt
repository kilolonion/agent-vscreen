package com.kilon.agentvscreen.api

import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.host.quiet
import com.kilon.agentvscreen.tr
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 2.0.0-beta7 (7.1, 7.3, 7.7, 7.8): dedicated loopback-only HTTP listener for public MCP traffic
 * forwarded from `tsbridge`'s Tailscale Funnel (`:443`).
 *
 * - Separate socket from [ApiServer]; binds ONLY `127.0.0.1` (default port [DEFAULT_PORT] = 18785).
 * - Requires constant-time `X-AVS-Public: <secret>` on every request, else 403.
 * - Reuses [HttpCore] for request parsing, limits, response writing, and [ApiServer.failLimiter]
 *   for Bearer failure lockout, and [Mcp.preAuthCheck] / [Mcp.handle] for `/mcp`.
 * - Exposes ONLY `/mcp` and the OAuth 2.1 + discovery endpoints; every other path returns 404.
 * - Never accepts local/tailnet tokens or `X-VScreen-Tailnet` headers.
 */
class PublicServer(
    private val ctx: Context,
    private val apiServer: ApiServer,
    private val secretProvider: () -> String = { PublicAccess.secret },
    private val baseProvider: () -> String? = { PublicAccess.publicBaseUrl() },
    private val wakeService: () -> Unit = { if (!HostService.isAlive) HostService.wakeFor(ctx, "public mcp") },
) {
    private val TAG = "PublicServer"
    private val pool = ThreadPoolExecutor(
        2,
        HttpCore.MAX_CONN,
        30,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { r -> Thread(r, "vscreen-pub-http").apply { isDaemon = true } }
    private val reading = LinkedHashMap<Socket, Long>()
    private val bindLock = Any()

    @Volatile private var server: ServerSocket? = null
    @Volatile var boundPort: Int = 0; private set
    val running: Boolean get() = server?.isClosed == false

    fun start(preferredPort: Int = DEFAULT_PORT): Int = synchronized(bindLock) {
        if (running && boundPort > 0) return@synchronized boundPort
        stopLocked()
        for (p in preferredPort until (preferredPort + PORT_SPAN).coerceAtMost(65536)) {
            val ss = ServerSocket()
            try {
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 50)
                server = ss
                boundPort = p
                Thread({ acceptLoop(ss) }, "vscreen-pub-accept").apply { isDaemon = true }.start()
                AppLogger.info(TAG, "listening on 127.0.0.1:$p")
                return@synchronized p
            } catch (e: Exception) {
                quiet("PublicServer:bind") { ss.close() }
                AppLogger.info(TAG, "bind $p: ${e.message}")
            }
        }
        0
    }

    fun stop() = synchronized(bindLock) { stopLocked() }

    private fun stopLocked() {
        quiet("PublicServer:close") { server?.close() }
        server = null
        boundPort = 0
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (_: SocketException) { break } catch (_: Exception) { continue }
            var placed = try { pool.execute { serve(s) }; true } catch (_: RejectedExecutionException) { false }
            if (!placed) {
                val victim = synchronized(reading) { reading.keys.firstOrNull()?.also { reading.remove(it) } }
                if (victim != null) {
                    quiet("pub-evict") { victim.close() }
                    for (i in 0 until 20) {
                        placed = try { pool.execute { serve(s) }; true } catch (_: RejectedExecutionException) { false }
                        if (placed) break
                        try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                    }
                }
            }
            if (!placed) {
                quiet("pub-busy") {
                    s.soTimeout = 500
                    HttpCore.writeResponse(
                        s.getOutputStream(),
                        ApiServer.Resp.err(503, "SERVER_BUSY", tr("同时进来的连接太多，请稍后重试", "Too many concurrent connections; retry shortly")),
                        apiServer.cliVersion(),
                    )
                }
                quiet("pub-busy-close") { s.close() }
            }
        }
    }

    private fun serve(s: Socket) {
        var afterSend: (() -> Unit)? = null
        s.use { sock ->
            runCatching {
                sock.soTimeout = 5_000
                val inp = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                synchronized(reading) { reading[sock] = System.currentTimeMillis() }
                val req = try {
                    HttpCore.readRequest(inp)
                } catch (e: HttpError) {
                    HttpCore.writeResponse(out, ApiServer.Resp.err(e.status, e.code, e.message ?: e.code), apiServer.cliVersion())
                    return
                } catch (_: java.net.SocketTimeoutException) {
                    quiet("pub-408") {
                        HttpCore.writeResponse(
                            out,
                            ApiServer.Resp.err(408, "REQUEST_TIMEOUT", "the request did not arrive in time"),
                            apiServer.cliVersion(),
                        )
                    }
                    return
                } finally {
                    synchronized(reading) { reading.remove(sock) }
                } ?: return
                req.peer = sock.inetAddress
                req.sock = sock
                sock.soTimeout = 0
                val resp = try {
                    route(req)
                } catch (t: Throwable) {
                    AppLogger.error(TAG, "route ${req.path}", t)
                    ApiServer.Resp.err(500, "INTERNAL_ERROR", "${t.javaClass.simpleName}: ${t.message}")
                }
                afterSend = resp.afterSend
                HttpCore.writeResponse(out, resp, apiServer.cliVersion())
            }.onFailure { if (it !is SocketException && it !is java.io.IOException) AppLogger.warning(TAG, "serve: ${it.message}") }
        }
        afterSend?.let { quiet("pub-after-send") { it() } }
    }

    /** Route one public request; directly testable without opening a socket. */
    internal fun route(r: ApiServer.Req, baseOverride: String? = null): ApiServer.Resp {
        val expectedSecret = secretProvider()
        val givenSecret = r.headers["x-avs-public"]
        if (expectedSecret.isEmpty() || givenSecret == null || !ApiServer.constTimeEq(givenSecret, expectedSecret)) {
            return ApiServer.Resp.err(403, "NOT_ALLOWED", tr("缺少或无效的公网通道凭证", "Missing or invalid public bridge header"))
        }

        // 7.7: mark public BEFORE any downstream logic so X-VScreen-Tailnet can NEVER elevate this request.
        r.viaPublic = true
        PublicAccess.noteActivity()
        val base = baseOverride ?: PublicOAuth.publicBase(r, baseProvider())

        return when (r.path) {
            "/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/mcp",
            -> if (r.method == "GET") PublicOAuth.protectedResourceMetadata(base)
            else methodNotAllowed("GET")

            "/.well-known/oauth-authorization-server" ->
                if (r.method == "GET") PublicOAuth.authorizationServerMetadata(base)
                else methodNotAllowed("GET")

            "/oauth/register" ->
                if (r.method == "POST") PublicOAuth.handleRegister(r)
                else methodNotAllowed("POST")

            "/oauth/authorize" ->
                if (r.method == "GET") PublicOAuth.handleAuthorize(ctx, r, base)
                else methodNotAllowed("GET")

            "/oauth/authorize/status" ->
                if (r.method == "GET") PublicOAuth.handleAuthorizeStatus(r)
                else methodNotAllowed("GET")

            "/oauth/token" ->
                if (r.method == "POST") PublicOAuth.handleToken(r, base)
                else methodNotAllowed("POST")

            "/mcp" -> handleMcp(r, base)

            else -> ApiServer.Resp.err(404, "NOT_FOUND", r.path)
        }
    }

    private fun methodNotAllowed(allow: String): ApiServer.Resp =
        ApiServer.Resp(405, "text/plain; charset=utf-8", "Method Not Allowed".toByteArray(), mapOf("Allow" to allow))

    private fun handleMcp(r: ApiServer.Req, base: String): ApiServer.Resp {
        // 7.8 #3: GET / DELETE on /mcp return 405 + Allow: POST BEFORE auth, sharing Mcp.preAuthCheck with ApiServer.
        Mcp.preAuthCheck(r)?.let { return it }

        apiServer.failLimiter.checkLocked(PUBLIC_FAIL_KEY, remote = true)?.let { return it }

        val prmUrl = "${base.trimEnd('/')}/.well-known/oauth-protected-resource"
        val wwwAuth = mapOf("WWW-Authenticate" to """Bearer resource_metadata="$prmUrl"""")

        val authHeader = r.headers["authorization"]?.trim()
        if (authHeader.isNullOrEmpty()) {
            // Standard initial unauthenticated discovery probe (RFC 9728): 401 + WWW-Authenticate without lockout penalty.
            return ApiServer.Resp.err(401, "BAD_TOKEN", tr("需要 OAuth 访问令牌", "OAuth Bearer access token required")).plus(wwwAuth)
        }
        val bearer = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
            authHeader.substring(7).trim()
        } else {
            null
        }
        val access = PublicOAuth.validateAccessToken(bearer, PublicOAuth.mcpResource(base))
            ?: return apiServer.failLimiter.recordFailure(
                who = PUBLIC_FAIL_KEY,
                remote = true,
                path = r.path,
                code = "BAD_TOKEN",
                msg = tr("公网访问令牌无效、已过期或目标地址不匹配", "Public access token is invalid, expired, or bound to another resource"),
                status = 401,
                headers = wwwAuth,
            )

        r.publicClientId = access.clientId
        r.auth = ApiServer.Auth(access.token, signed = false, nonce = null)
        Tokens.used(access.token.id)
        wakeService()
        apiServer.notePublicCaller(r)
        return apiServer.withLang(r) { Mcp.handle(apiServer, r) }
    }

    companion object {
        const val DEFAULT_PORT = 18785
        private const val PORT_SPAN = 5
        private const val PUBLIC_FAIL_KEY = "public"
    }
}
