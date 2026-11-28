package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.S
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
    /**
     * 2.0.0-beta11: this listener is Cloudflare Tunnel's origin ([com.kilon.agentvscreen.host.Cloudflared]). cloudflared
     * cannot add our bridge header, so every request here is public by where it arrived: incoming X-AVS-* and tailnet
     * headers are dropped, the bridge header is set, and the source is Cloudflare's Cf-Connecting-IP. Another app on
     * this phone could connect here too, but it would only reach the public surface (OAuth pairing on this phone);
     * at most it could pick its own rate-limit bucket.
     */
    private val cloudflareOrigin: Boolean = false,
    private val onAuthenticatedActivity: () -> Unit = { PublicAccess.noteActivity() },
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
    @Volatile private var acceptHandle: com.kilon.agentvscreen.core.LoopWatchdog.Handle? = null
    @Volatile var boundPort: Int = 0; private set
    val running: Boolean get() = server?.isClosed == false

    fun start(preferredPort: Int = DEFAULT_PORT, span: Int = PORT_SPAN): Int = synchronized(bindLock) {
        if (com.kilon.agentvscreen.host.ThermalGuard.locked) return@synchronized -1
        if (running && boundPort > 0) return@synchronized boundPort
        stopLocked()
        for (p in preferredPort until (preferredPort + span).coerceAtMost(65536)) {
            val ss = ServerSocket()
            try {
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 50)
                server = ss
                boundPort = p
                acceptHandle = com.kilon.agentvscreen.core.LoopWatchdog.process.startLoop(
                    name = "vscreen-pub-accept",
                    critical = true,
                    shouldRun = { !ss.isClosed },
                ) { ctx -> acceptLoop(ss, ctx) }
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
        acceptHandle?.stop()
        acceptHandle = null
        quiet("PublicServer:close") { server?.close() }
        server = null
        boundPort = 0
    }

    private fun acceptLoop(ss: ServerSocket, ctx: com.kilon.agentvscreen.core.LoopWatchdog.LoopContext? = null) {
        ctx?.beat()
        while (!ss.isClosed && (ctx?.active != false)) {
            val s = try { ss.accept() } catch (_: SocketException) { break } catch (_: Exception) { continue }
            ctx?.beat()
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
                        ApiServer.Resp.err(503, "SERVER_BUSY", tr(S.public_server_too_many_concurrent_connections_retry)),
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
                val req0 = try {
                    HttpCore.readRequest(inp, sock)
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
                val req = if (cloudflareOrigin) fromCloudflare(req0) else req0
                req.peer = sock.inetAddress
                req.sock = sock
                sock.soTimeout = 0
                val resp = try {
                    route(req)
                } catch (t: Throwable) {
                    AppLogger.error(TAG, "route ${req.path}", t)
                    ApiServer.Resp.err(500, "INTERNAL_ERROR", tr(S.public_server_internal_server_error_please_retry))
                }
                afterSend = resp.afterSend
                HttpCore.writeResponse(out, resp, apiServer.cliVersion())
            }.onFailure { if (it !is SocketException && it !is java.io.IOException) AppLogger.warning(TAG, "serve: ${it.message}") }
        }
        afterSend?.let { quiet("pub-after-send") { it() } }
    }

    /** A request that came through the Cloudflare tunnel, as if the bridge had forwarded it (see [cloudflareOrigin]). */
    internal fun fromCloudflare(r: ApiServer.Req): ApiServer.Req {
        val h = r.headers.filterKeys { k -> !k.startsWith("x-avs-") && k != "x-vscreen-tailnet" }.toMutableMap()
        h["x-avs-public"] = secretProvider()
        // Only a syntactically valid IPv4/IPv6 literal (never resolved as a name).
        r.headers["cf-connecting-ip"]?.trim()?.takeIf { PublicOAuth.isValidIpLiteral(it) }
            ?.let { h["x-avs-public-src"] = it }
        return ApiServer.Req(r.method, r.target, r.query, h, r.body)
    }

    /** Route one public request; directly testable without opening a socket. */
    internal fun route(r: ApiServer.Req, baseOverride: String? = null): ApiServer.Resp {
        val expectedSecret = secretProvider()
        val givenSecret = r.headers["x-avs-public"]
        if (expectedSecret.isEmpty() || givenSecret == null || !ApiServer.constTimeEq(givenSecret, expectedSecret)) {
            return ApiServer.Resp.err(403, "NOT_ALLOWED", tr(S.public_server_missing_or_invalid_public_bridge))
        }

        // 7.7: mark public BEFORE any downstream logic so X-VScreen-Tailnet can NEVER elevate this request.
        r.viaPublic = true
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

            "/oauth/device_authorization" ->
                if (r.method == "POST") PublicOAuth.handleDeviceAuthorization(ctx, r, base)
                else methodNotAllowed("POST")

            "/oauth/device" ->
                if (r.method == "GET") PublicOAuth.devicePageHtml()
                else methodNotAllowed("GET")

            "/oauth/token" ->
                if (r.method == "POST") PublicOAuth.handleToken(r, base).also { resp ->
                    if (resp.status == 200) onAuthenticatedActivity()
                }
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

        val srcBucket = PublicOAuth.sourceBucket(r.headers["x-avs-public-src"])
        val srcFailKey = "public-src:$srcBucket"
        // 2.0.0-beta11 (test P7: ten bad Bearers from one address locked the paired client there out for 5 minutes):
        // the source lock stops guessing, so it applies to requests without a valid credential only. A valid token
        // (random, 256 bits) is not a guess and cannot be locked out by an attacker replaying a stale/revoked token.

        val prmUrl = "${base.trimEnd('/')}/.well-known/oauth-protected-resource"
        val wwwAuth = mapOf("WWW-Authenticate" to """Bearer resource_metadata="$prmUrl"""")

        val authHeader = r.headers["authorization"]?.trim()
        if (authHeader.isNullOrEmpty()) {
            // Standard initial unauthenticated discovery probe (RFC 9728): 401 + WWW-Authenticate without lockout penalty.
            return ApiServer.Resp.err(401, "BAD_TOKEN", tr(S.public_server_not_paired_yet_this_address, prmUrl)).plus(wwwAuth)
        }
        val bearer = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
            authHeader.substring(7).trim()
        } else {
            null
        }
        val access = when (val chk = PublicOAuth.inspectAccessToken(bearer, PublicOAuth.mcpResource(base))) {
            is PublicOAuth.AccessCheck.Valid -> chk.auth
            is PublicOAuth.AccessCheck.Invalid -> {
                apiServer.failLimiter.checkLocked(srcFailKey, remote = true)?.let { return it }
                // Charge invalid/expired/revoked Bearer failures strictly to the caller's source bucket so
                // replaying an expired or revoked token can never DoS-lock a legitimate client's active token.
                return apiServer.failLimiter.recordFailure(
                    who = srcFailKey,
                    remote = true,
                    path = r.path,
                    code = "BAD_TOKEN",
                    msg = tr(S.public_server_public_access_token_is_invalid, prmUrl),
                    status = 401,
                    // RFC 6750 §3.1: a token that was sent and refused says so, so a client knows to refresh or re-pair.
                    headers = mapOf("WWW-Authenticate" to """Bearer error="invalid_token", resource_metadata="$prmUrl""""),
                )
            }
        }

        onAuthenticatedActivity()
        r.publicClientId = access.clientId
        r.publicGrantId = access.grantId
        r.publicScope = access.scope
        r.auth = ApiServer.Auth(access.token, signed = false, nonce = null)
        Tokens.used(access.token.id)
        wakeService()
        apiServer.notePublicCaller(r)
        return apiServer.withLang(r) { Mcp.handle(apiServer, r) }
    }

    companion object {
        const val DEFAULT_PORT = 18785
        private const val PORT_SPAN = 5
    }
}
