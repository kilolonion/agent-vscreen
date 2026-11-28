package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
import android.content.Context
import android.content.SharedPreferences
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Base64

/**
 * 2.0.0-beta11 (rainbow: support Cloudflare for public MCP, with detailed, friendly guidance for it and Tailscale).
 *
 * Cloudflare Tunnel as a second public channel beside Tailscale Funnel. The user makes a tunnel in their own
 * Cloudflare account (dashboard), points a public hostname of their domain at [ORIGIN_URL], and pastes the tunnel
 * token here. The phone runs Cloudflare's own client (`libcloudflared.so`, built from the pinned source by
 * cloudflared/build.sh) as a child process with the token; it dials out to Cloudflare, so no port is opened on the
 * phone. Requests arrive at the Cloudflare origin listener of [com.kilon.agentvscreen.api.PublicServer], which treats
 * every one as public: OAuth pairing on this phone, the agent's permission table, the same limits as Funnel.
 *
 * What the user sees is measured, never assumed: the client's own ready count (its metrics endpoint), its log lines
 * for the reason it is not connected, and a request from this phone to the public address that must come back with
 * this phone's own OAuth metadata ([selfTest]).
 */
object Cloudflared : PublicTunnel {
    private const val TAG = "Cloudflared"
    private const val PREFS = "agent_vscreen_cloudflare"
    private const val K_TOKEN = "tunnel_token"
    private const val K_HOST = "hostname"

    /** The fixed local address the tunnel's public hostname must point at (set in the Cloudflare dashboard). */
    const val ORIGIN_PORT = 18790
    const val ORIGIN_URL = "http://127.0.0.1:$ORIGIN_PORT"
    const val ADD_SITE_URL = "https://dash.cloudflare.com/"
    /** Deep link straight to Networking › Tunnels of whichever account the user picks (2.0.0-beta13). */
    const val TUNNELS_URL = "https://dash.cloudflare.com/?to=/:account/tunnels"
    const val DOCS_URL = "https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel/"
    const val DOCS_ROUTES_URL = "https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/routing-to-tunnel/"

    private var prefs: SharedPreferences? = null
    private val flows = TunnelFlows()
    private val _status get() = flows.status
    private val _selfTest get() = flows.selfTest
    override val provider = "Cloudflare"
    override val status: StateFlow<TunnelStatus> = flows.statusFlow
    override val selfTest: StateFlow<TunnelSelfTest?> = flows.selfTestFlow
    override val baseUrl: String? get() = _config.value.baseUrl
    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()

    data class Config(val token: String? = null, val hostname: String? = null) {
        val tunnelId: String? get() = token?.let { parseToken(it)?.tunnelId }
        val complete: Boolean get() = token != null && hostname != null
        val baseUrl: String? get() = hostname?.let { "https://$it" }
    }

    data class TokenInfo(val accountTag: String, val tunnelId: String)

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        _config.value = Config(p.getString(K_TOKEN, null), p.getString(K_HOST, null))
    }

    /**
     * Save what the user pasted. [rawToken] may be the token itself or the whole install command the dashboard shows
     * (`cloudflared service install eyJ…`, `… run --token eyJ…`); [rawHost] may carry a scheme or path. Returns the
     * problem in words, or null when both are usable.
     */
    fun save(rawToken: String?, rawHost: String?): String? {
        val tok = rawToken?.let { extractToken(it) }
        if (!rawToken.isNullOrBlank() && tok == null) return tr(S.cloudflared_no_tunnel_token_found_paste)
        val host = rawHost?.let { normalizeHost(it) }
        if (!rawHost.isNullOrBlank() && host == null) return tr(S.cloudflared_not_a_valid_hostname_use)
        val next = Config(tok ?: _config.value.token, host ?: _config.value.hostname)
        prefs?.edit()?.putString(K_TOKEN, next.token)?.putString(K_HOST, next.hostname)?.apply()
        _config.value = next
        _selfTest.value = null
        return null
    }

    fun clear() {
        prefs?.edit()?.remove(K_TOKEN)?.remove(K_HOST)?.apply()
        _config.value = Config()
        _selfTest.value = null
    }

    /** The token inside whatever the user pasted, if it decodes to a tunnel token ({"a","t","s"}). */
    internal fun extractToken(raw: String): String? =
        Regex("eyJ[A-Za-z0-9_\\-+/=]{40,}").findAll(raw).map { it.value }.firstOrNull { parseToken(it) != null }

    internal fun parseToken(tok: String): TokenInfo? = runCatching {
        val bytes = runCatching { Base64.getDecoder().decode(tok) }.getOrElse { Base64.getUrlDecoder().decode(tok.trimEnd('=')) }
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        val a = o.optString("a"); val t = o.optString("t"); val s = o.optString("s")
        if (a.isBlank() || t.isBlank() || s.isBlank()) null else TokenInfo(a, t)
    }.getOrNull()

    internal fun normalizeHost(raw: String): String? {
        val h = raw.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').trimEnd('.').lowercase()
        val label = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
        return h.takeIf { Regex("^($label\\.)+[a-z]{2,63}$").matches(it) && !it.endsWith(".ts.net") }
    }

    fun binary(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libcloudflared.so")

    // ---- the child process ----

    private val runner = TunnelProcess(TAG, "vscreen-cloudflared")
    @Volatile private var metricsPort = 0

    @Synchronized override fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!_config.value.complete) { _status.value = TunnelStatus(TunnelState.ERROR, problem = tr(S.cloudflared_the_tunnel_token_and_hostname)); return }
        if (runner.current) return
        _status.value = TunnelStatus(TunnelState.STARTING)
        runner.start(launch = { g -> launch(app, g) }, onLine = ::onLine, onStarted = { g ->
            Thread({ pollReady(metricsPort, g) }, "vscreen-cloudflared-ready").apply { isDaemon = true; start() }
        }, onEnded = { g ->
            if (runner.live(g)) _status.value = _status.value.copy(state = if (_status.value.problem != null) TunnelState.ERROR else TunnelState.STARTING, connections = 0)
            _status.value.problem != null && _status.value.raw?.let { isFatal(it) } == true
        })
    }

    @Synchronized override fun stop(why: String) {
        runner.stop(why)
        _status.value = TunnelStatus()
        _selfTest.value = null
    }

    private fun freePort(): Int = ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", 0)); it.localPort }

    private fun launch(ctx: Context, g: Int): ProcessBuilder? {
        val bin = binary(ctx)
        if (!bin.canExecute()) { _status.value = TunnelStatus(TunnelState.ERROR, problem = tr(S.cloudflared_built_in_cloudflare_component_missing, bin)); return null }
        val dir = File(ctx.filesDir, "cloudflared").apply { mkdirs() }
        metricsPort = freePort()
        val pb = ProcessBuilder(bin.absolutePath, "tunnel", "--no-autoupdate", "--metrics", "127.0.0.1:$metricsPort",
            "--protocol", "auto", "--loglevel", "info", "run").directory(dir)
        val env = pb.environment()
        // The token goes in the environment, never on the command line (visible to other processes) or in logs.
        env["TUNNEL_TOKEN"] = _config.value.token ?: return null
        env["AVS_DNS"] = Tailnet.dnsServers(ctx).joinToString(",")
        env["HOME"] = dir.absolutePath
        return pb
    }

    /** The client's /ready: {"status":200,"readyConnections":N}. The only source of "connected". */
    private fun pollReady(port: Int, g: Int) {
        while (runner.live(g)) {
            try { Thread.sleep(3_000) } catch (_: InterruptedException) { return }
            val n = runCatching {
                val c = URL("http://127.0.0.1:$port/ready").openConnection() as HttpURLConnection
                c.connectTimeout = 2_000; c.readTimeout = 2_000
                try { JSONObject((if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()).optInt("readyConnections") } finally { c.disconnect() }
            }.getOrNull() ?: continue
            if (!runner.live(g)) return
            val cur = _status.value
            val next = if (n > 0) cur.copy(state = TunnelState.CONNECTED, connections = n, problem = null) else cur.copy(connections = 0, state = if (cur.problem != null) TunnelState.ERROR else TunnelState.STARTING)
            if (next != cur) {
                _status.value = next
                if (n > 0 && cur.state != TunnelState.CONNECTED) Thread({ runSelfTest() }, "vscreen-cloudflared-test").apply { isDaemon = true }.start()
            }
        }
    }

    private fun onLine(line: String) {
        val clean = line.replace(Regex("eyJ[A-Za-z0-9_\\-+/=]{20,}"), "<token>")
        AppLogger.info(TAG, clean.take(300))
        if (clean.contains("Registered tunnel connection")) {
            val loc = Regex("location=([A-Za-z0-9]+)").find(clean)?.groupValues?.get(1)
            val prot = Regex("protocol=([a-z0-9]+)").find(clean)?.groupValues?.get(1)
            _status.value = _status.value.copy(location = _status.value.location ?: loc, protocol = prot ?: _status.value.protocol)
            return
        }
        if (!Regex("\\b(ERR|FTL)\\b").containsMatchIn(clean)) return
        val problem = explain(clean) ?: return
        _status.value = _status.value.copy(problem = problem, raw = clean.take(400),
            state = if (_status.value.connections > 0) TunnelState.CONNECTED else TunnelState.ERROR)
    }

    private fun isFatal(raw: String) = raw.contains("token is not valid", true) || raw.contains("Unauthorized", true) || raw.contains("not found", true)

    /** A client error line → what it means for the user and what to do; null for noise the client recovers from. */
    internal fun explain(line: String): String? = when {
        line.contains("token is not valid", true) || line.contains("Invalid tunnel secret", true) ->
            tr(S.cloudflared_cloudflare_does_not_accept_this)
        line.contains("Tunnel not found", true) ->
            tr(S.cloudflared_this_tunnel_no_longer_exists)
        line.contains("Unable to reach the origin service", true) || line.contains("connection refused", true) ->
            tr(S.cloudflared_the_tunnel_is_up_but, ORIGIN_URL)
        line.contains("edge discovery", true) || line.contains("lookup", true) && line.contains("argotunnel", true) ->
            tr(S.cloudflared_cannot_reach_cloudflare_its_edge)
        line.contains("failed to dial to edge", true) || line.contains("i/o timeout", true) || line.contains("Serve tunnel error", true) ->
            tr(S.cloudflared_connecting_to_cloudflare_is_blocked)
        else -> null
    }

    // ---- the self-test ----

    /**
     * A request from this phone to the public address, through Cloudflare and the tunnel, that must come back with
     * this phone's own OAuth metadata. Proves the hostname, its DNS record and the service URL in one go.
     */
    override fun runSelfTest() {
        val base = _config.value.baseUrl ?: return
        _selfTest.value = TunnelSelfTest.Running
        _selfTest.value = TunnelProbe.run(base, { code, body -> judge(base, code, body) }, ::judgeError)
        AppLogger.info(TAG, "self-test: ${_selfTest.value}")
    }

    internal fun judge(base: String, code: Int, body: String): TunnelSelfTest {
        val mine = TunnelProbe.isMine(base, code, body)
        return when {
            mine -> TunnelSelfTest.Ok
            body.contains("1033") -> TunnelSelfTest.Failed(tr(S.cloudflared_cloudflare_says_the_tunnel_is),
                tr(S.cloudflared_no_connector_is_online_for), "HTTP $code")
            code == 502 || code == 503 || code == 504 || body.contains("1016") -> TunnelSelfTest.Failed(tr(S.cloudflared_the_hostname_reaches_cloudflare_but),
                tr(S.cloudflared_in_the_tunnel_s_public, ORIGIN_PORT), "HTTP $code")
            code in 300..399 -> TunnelSelfTest.Failed(tr(S.cloudflared_the_hostname_redirects),
                tr(S.cloudflared_this_hostname_has_a_redirect), "HTTP $code")
            code == 403 && body.contains("cloudflare", true) -> TunnelSelfTest.Failed(tr(S.cloudflared_cloudflare_blocked_the_request),
                tr(S.cloudflared_a_waf_bot_protection_or), "HTTP $code")
            else -> TunnelSelfTest.Failed(tr(S.cloudflared_this_hostname_does_not_lead),
                tr(S.cloudflared_what_came_back_is_not, code, ORIGIN_PORT), body.take(200))
        }
    }

    private fun judgeError(e: Throwable): TunnelSelfTest = when (e) {
        is java.net.UnknownHostException -> TunnelSelfTest.Failed(tr(S.cloudflared_the_hostname_does_not_resolve),
            tr(S.cloudflared_cloudflare_has_no_dns_record), e.message)
        is javax.net.ssl.SSLException -> TunnelSelfTest.Failed(tr(S.cloudflared_the_https_certificate_is_not),
            tr(S.cloudflared_cloudflare_usually_needs_a_few), e.message)
        else -> TunnelProbe.commonError(e)
    }
}
