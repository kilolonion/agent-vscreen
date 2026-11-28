package com.kilon.agentvscreen.host

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
        if (!rawToken.isNullOrBlank() && tok == null) return tr("没有认出隧道令牌：请粘贴 Cloudflare 隧道页面上“安装并运行连接器”里的整条命令，或其中以 eyJ 开头的那一长串", "No tunnel token found: paste the whole command under \"Install and run a connector\" on the tunnel page, or the long string in it that starts with eyJ")
        val host = rawHost?.let { normalizeHost(it) }
        if (!rawHost.isNullOrBlank() && host == null) return tr("域名格式不对：填你在隧道里添加的公网主机名，例如 phone.example.com", "Not a valid hostname: use the public hostname you added to the tunnel, e.g. phone.example.com")
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
        if (!_config.value.complete) { _status.value = TunnelStatus(TunnelState.ERROR, problem = tr("还没填隧道令牌和域名", "The tunnel token and hostname are not set yet")); return }
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
        if (!bin.canExecute()) { _status.value = TunnelStatus(TunnelState.ERROR, problem = tr("内置 Cloudflare 组件缺失（$bin）", "Built-in Cloudflare component missing ($bin)")); return null }
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
            tr("Cloudflare 不认这个隧道令牌：在 Cloudflare 隧道页面重新复制令牌（或整条安装命令）粘贴进来。隧道被删除或令牌被轮换后，旧令牌会失效。",
                "Cloudflare does not accept this tunnel token: copy the token (or the whole install command) again from the tunnel page. A deleted tunnel or a rotated token makes the old one stop working.")
        line.contains("Tunnel not found", true) ->
            tr("这个隧道在 Cloudflare 上已不存在：在 Cloudflare 后台新建隧道，再粘贴新令牌。", "This tunnel no longer exists on Cloudflare: create a new one in the dashboard and paste its token.")
        line.contains("Unable to reach the origin service", true) || line.contains("connection refused", true) ->
            tr("隧道已连接，但 Cloudflare 后台里这个域名的服务地址不对：应填 $ORIGIN_URL（类型 HTTP；不要写 localhost，也不要写别的端口）。",
                "The tunnel is up, but the hostname's service in the Cloudflare dashboard is wrong: it must be $ORIGIN_URL (type HTTP; not localhost, no other port).")
        line.contains("edge discovery", true) || line.contains("lookup", true) && line.contains("argotunnel", true) ->
            tr("连不上 Cloudflare：查不到 Cloudflare 的接入地址。检查网络；开着代理 VPN 时，把 Agent-VScreen 设为直连或分流放行。",
                "Cannot reach Cloudflare: its edge addresses could not be looked up. Check the network; with a proxy VPN on, let Agent-VScreen bypass it.")
        line.contains("failed to dial to edge", true) || line.contains("i/o timeout", true) || line.contains("Serve tunnel error", true) ->
            tr("连接 Cloudflare 受阻，正在自动重试（会自动从 QUIC 切到 HTTP/2）。长时间连不上时，检查网络或代理 VPN 是否拦截了 7844 端口。",
                "Connecting to Cloudflare is blocked; retrying automatically (it falls back from QUIC to HTTP/2 by itself). If it stays down, check whether the network or a proxy VPN blocks port 7844.")
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
            body.contains("1033") -> TunnelSelfTest.Failed(tr("Cloudflare 显示隧道未连接", "Cloudflare says the tunnel is not connected"),
                tr("这个域名指向的隧道当前没有在线的连接器。确认粘贴的是同一个隧道的令牌，并等待上方“隧道连接”变为已连接。", "No connector is online for the tunnel this hostname points at. Check that the token is from the same tunnel and wait for Tunnel connection above to show connected."), "HTTP $code")
            code == 502 || code == 503 || code == 504 || body.contains("1016") -> TunnelSelfTest.Failed(tr("域名已到 Cloudflare，但转不到手机上的服务", "The hostname reaches Cloudflare but not the service on the phone"),
                tr("在 Cloudflare 隧道的“公共主机名”里，把这个域名的服务改为 HTTP，地址 127.0.0.1:$ORIGIN_PORT。", "In the tunnel's Public Hostname, set this hostname's service to HTTP, address 127.0.0.1:$ORIGIN_PORT."), "HTTP $code")
            code in 300..399 -> TunnelSelfTest.Failed(tr("域名被重定向了", "The hostname redirects"),
                tr("这个域名开了跳转或 Cloudflare Access 登录。公网 MCP 需要直接访问：去掉这个域名上的 Access 应用或重定向规则。", "This hostname has a redirect or a Cloudflare Access login. Public MCP needs direct access: remove the Access application or redirect rule from it."), "HTTP $code")
            code == 403 && body.contains("cloudflare", true) -> TunnelSelfTest.Failed(tr("Cloudflare 拦下了请求", "Cloudflare blocked the request"),
                tr("这个域名上的 WAF、机器人防护或 Access 规则拦下了访问。为这个子域名关闭“机器人对抗模式”等防护，或单独放行。", "A WAF, bot protection or Access rule on this hostname blocked it. Turn off Bot Fight Mode and similar protection for this subdomain, or allow it."), "HTTP $code")
            else -> TunnelSelfTest.Failed(tr("这个域名没有指向这台手机", "This hostname does not lead to this phone"),
                tr("返回的不是本机的内容（HTTP $code）。确认 Cloudflare 隧道的公共主机名就是这里填的域名，服务为 HTTP 127.0.0.1:$ORIGIN_PORT。", "What came back is not this phone (HTTP $code). Check that the tunnel's public hostname is the one entered here, with service HTTP 127.0.0.1:$ORIGIN_PORT."), body.take(200))
        }
    }

    private fun judgeError(e: Throwable): TunnelSelfTest = when (e) {
        is java.net.UnknownHostException -> TunnelSelfTest.Failed(tr("域名解析不到", "The hostname does not resolve"),
            tr("Cloudflare 还没有这个域名的 DNS 记录：在隧道的“公共主机名”里添加它（会自动建 DNS 记录），刚添加的等 1–2 分钟再试。域名本身也要已托管在这个 Cloudflare 账号下。", "Cloudflare has no DNS record for this hostname yet: add it under the tunnel's Public Hostname (that creates the record); if just added, wait 1–2 minutes. The domain itself must be on this Cloudflare account."), e.message)
        is javax.net.ssl.SSLException -> TunnelSelfTest.Failed(tr("HTTPS 证书还没准备好", "The HTTPS certificate is not ready"),
            tr("Cloudflare 为新域名签发证书通常需要几分钟。多级子域名（如 a.b.example.com）不在免费证书范围内，请用一级子域名（如 phone.example.com）。", "Cloudflare usually needs a few minutes to issue a certificate for a new hostname. Multi-level subdomains (a.b.example.com) are not covered by the free certificate; use one level (phone.example.com)."), e.message)
        else -> TunnelProbe.commonError(e)
    }
}
