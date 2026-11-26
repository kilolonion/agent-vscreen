package com.kilon.agentvscreen.host

import android.content.Context
import android.content.SharedPreferences
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * 2.0.0-beta11 (rainbow: also support ngrok). ngrok as the third public channel: the user signs up at ngrok (free),
 * copies their authtoken and their free static domain (each free account has one, e.g. name.ngrok-free.app), and
 * pastes both here. The built-in node binary (`libtsbridge.so`, TSB_MODE=ngrok, tsbridge/ngrok.go) runs the ngrok
 * agent library and forwards to [com.kilon.agentvscreen.api.PublicServer] with the bridge header, like Funnel.
 *
 * The domain is required, not optional: OAuth tokens are bound to the public address, and a random address would
 * change on every start and log every client out.
 */
object Ngrok : PublicTunnel {
    private const val TAG = "Ngrok"
    private const val PREFS = "agent_vscreen_ngrok"
    private const val K_TOKEN = "authtoken"
    private const val K_DOMAIN = "domain"

    const val SIGNUP_URL = "https://dashboard.ngrok.com/signup"
    const val TOKEN_URL = "https://dashboard.ngrok.com/get-started/your-authtoken"
    const val DOMAINS_URL = "https://dashboard.ngrok.com/domains"
    const val DOCS_URL = "https://ngrok.com/docs/start"

    data class Config(val token: String? = null, val domain: String? = null) {
        val complete: Boolean get() = token != null && domain != null
        val baseUrl: String? get() = domain?.let { "https://$it" }
    }

    private var prefs: SharedPreferences? = null
    private val flows = TunnelFlows()
    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()
    override val provider = "ngrok"
    override val status: StateFlow<TunnelStatus> = flows.statusFlow
    override val selfTest: StateFlow<TunnelSelfTest?> = flows.selfTestFlow
    override val baseUrl: String? get() = _config.value.baseUrl

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        _config.value = Config(p.getString(K_TOKEN, null), p.getString(K_DOMAIN, null))
    }

    /** Save what the user pasted; [rawToken] may be the whole `ngrok config add-authtoken …` line. Returns the problem, or null. */
    fun save(rawToken: String?, rawDomain: String?): String? {
        val tok = rawToken?.let { extractToken(it) }
        if (!rawToken.isNullOrBlank() && tok == null) return tr("没有认出 authtoken：请粘贴 ngrok 后台 Your Authtoken 页面上的令牌，或整条 ngrok config add-authtoken 命令", "No authtoken found: paste the token from Your Authtoken in the ngrok dashboard, or the whole ngrok config add-authtoken command")
        val dom = rawDomain?.let { normalizeDomain(it) }
        if (!rawDomain.isNullOrBlank() && dom == null) return tr("域名格式不对：填 ngrok 后台 Domains 里的域名，例如 name.ngrok-free.app", "Not a valid domain: use the one under Domains in the ngrok dashboard, e.g. name.ngrok-free.app")
        val next = Config(tok ?: _config.value.token, dom ?: _config.value.domain)
        prefs?.edit()?.putString(K_TOKEN, next.token)?.putString(K_DOMAIN, next.domain)?.apply()
        _config.value = next
        flows.selfTest.value = null
        return null
    }

    fun clear() {
        prefs?.edit()?.remove(K_TOKEN)?.remove(K_DOMAIN)?.apply()
        _config.value = Config()
        flows.selfTest.value = null
    }

    /** ngrok authtokens are one long word of letters, digits and underscores; the command form puts it last. */
    internal fun extractToken(raw: String): String? =
        Regex("[A-Za-z0-9_]{30,120}").findAll(raw).map { it.value }.lastOrNull { t -> t.any { it.isDigit() } && t.any { it.isLetter() } }

    internal fun normalizeDomain(raw: String): String? {
        val h = raw.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').trimEnd('.').lowercase()
        val label = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
        return h.takeIf { Regex("^($label\\.)+[a-z]{2,63}$").matches(it) && !it.endsWith(".ts.net") }
    }

    // ---- the child process ----

    private val runner = TunnelProcess(TAG, "vscreen-ngrok")

    @Synchronized override fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!_config.value.complete) { flows.status.value = TunnelStatus(TunnelState.ERROR, problem = tr("还没填 authtoken 和域名", "The authtoken and domain are not set yet")); return }
        if (runner.current) return
        flows.status.value = TunnelStatus(TunnelState.STARTING)
        runner.start(launch = { launch(app) }, onLine = ::onLine, onEnded = { g ->
            val cur = flows.status.value
            if (runner.live(g)) flows.status.value = cur.copy(state = if (cur.problem != null) TunnelState.ERROR else TunnelState.STARTING, connections = 0)
            cur.raw?.let { isFatal(it) } == true
        })
    }

    @Synchronized override fun stop(why: String) {
        runner.stop(why)
        flows.status.value = TunnelStatus()
        flows.selfTest.value = null
    }

    private fun launch(ctx: Context): ProcessBuilder? {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libtsbridge.so")
        if (!bin.canExecute()) { flows.status.value = TunnelStatus(TunnelState.ERROR, problem = tr("内置组件缺失（$bin）", "Built-in component missing ($bin)")); return null }
        val cfg = _config.value
        val dir = File(ctx.filesDir, "ngrok").apply { mkdirs() }
        val pub = PublicAccess.ensureServer(ctx)
        val pb = ProcessBuilder(bin.absolutePath).directory(dir)
        val env = pb.environment()
        // Secrets go in the environment, never on the command line or in logs.
        env["TSB_MODE"] = "ngrok"
        env["NGROK_AUTHTOKEN"] = cfg.token ?: return null
        env["AVS_NGROK_URL"] = cfg.baseUrl ?: return null
        env["TSB_PUBLIC_TARGET"] = "127.0.0.1:${pub.boundPort.takeIf { it > 0 } ?: PublicAccess.boundPort}"
        env["TSB_PUBLIC_SECRET"] = PublicAccess.rotateSecret()
        env["TSB_DNS"] = Tailnet.dnsServers(ctx).joinToString(",")
        env["HOME"] = dir.absolutePath
        return pb
    }

    /** The agent's lines: {"ngrok":"connecting|online|error","url":…,"reason":…}; anything else is log. */
    internal fun onLine(line: String) {
        val token = _config.value.token
        val clean = if (token != null) line.replace(token, "<token>") else line
        val o = runCatching { JSONObject(clean) }.getOrNull()?.takeIf { it.has("ngrok") }
        if (o == null) { AppLogger.info(TAG, clean.take(300)); return }
        AppLogger.info(TAG, clean.take(400))
        val reason = o.optString("reason").takeIf { it.isNotBlank() }
        val cur = flows.status.value
        flows.status.value = when (o.optString("ngrok")) {
            "online" -> {
                val url = o.optString("url").trimEnd('/')
                val want = _config.value.baseUrl
                val mismatch = want != null && url.isNotEmpty() && !url.equals(want, ignoreCase = true)
                if (cur.state != TunnelState.CONNECTED && !mismatch) Thread({ runSelfTest() }, "vscreen-ngrok-test").apply { isDaemon = true }.start()
                TunnelStatus(TunnelState.CONNECTED, connections = 1, location = url.removePrefix("https://"),
                    problem = if (mismatch) tr("ngrok 给的地址是 $url，与填写的域名不同：确认域名属于这个账号。", "ngrok gave $url, not the domain entered: check the domain belongs to this account.") else null)
            }
            "connecting" -> cur.copy(state = if (cur.state == TunnelState.CONNECTED) TunnelState.STARTING else cur.state.takeIf { it != TunnelState.OFF } ?: TunnelState.STARTING,
                connections = 0, problem = reason?.let { explain(it) } ?: cur.problem, raw = reason ?: cur.raw)
            else -> cur.copy(state = TunnelState.ERROR, connections = 0, problem = explain(reason.orEmpty()), raw = reason)
        }
    }

    private fun isFatal(raw: String) = Regex("ERR_NGROK_(105|106|107|108|313|319|320|4018)").containsMatchIn(raw)

    /** An agent error → what it means and what to do. ngrok errors carry a stable code (ERR_NGROK_N). */
    internal fun explain(reason: String): String {
        val code = Regex("ERR_NGROK_(\\d+)").find(reason)?.groupValues?.get(1)
        return when (code) {
            "105", "106", "107" -> tr("ngrok 不认这个 authtoken：到 ngrok 后台 Your Authtoken 重新复制粘贴。重置过令牌后旧的会失效。", "ngrok does not accept this authtoken: copy it again from Your Authtoken in the dashboard. A reset makes the old one stop working.")
            "108" -> tr("这个 ngrok 账号已有一个代理在线（免费账号同时只能一个）：关掉电脑等其它地方运行的 ngrok，或在后台 Agents 页面断开它。", "This ngrok account already has an agent online (free accounts allow one at a time): stop ngrok elsewhere, or disconnect it under Agents in the dashboard.")
            "313", "319", "320" -> tr("这个域名不属于这个 ngrok 账号：用 ngrok 后台 Domains 页面里列出的域名（免费账号有一个固定域名）。", "This domain is not on this ngrok account: use the one listed under Domains in the dashboard (free accounts have one static domain).")
            "4018" -> tr("ngrok 账号还需要完成注册验证：登录 ngrok 后台按提示完成（如验证邮箱）。", "The ngrok account needs to finish signing up: log in to the dashboard and complete it (e.g. verify the email).")
            "334" -> tr("这个域名已经被另一个在线的 ngrok 端点占用：关掉其它地方用同一域名运行的 ngrok。", "Another online ngrok endpoint already uses this domain: stop the other ngrok using it.")
            else -> when {
                reason.contains("no such host", true) || reason.contains("lookup", true) ->
                    tr("连不上 ngrok：查不到它的服务器地址。检查网络；开着代理 VPN 时，把 Agent-VScreen 设为直连或分流放行。", "Cannot reach ngrok: its servers could not be looked up. Check the network; with a proxy VPN on, let Agent-VScreen bypass it.")
                reason.contains("timeout", true) || reason.contains("refused", true) || reason.contains("reset", true) ->
                    tr("连接 ngrok 受阻，正在自动重试。长时间连不上时检查网络或代理 VPN。", "Connecting to ngrok is blocked; retrying automatically. If it stays down, check the network or proxy VPN.")
                reason.isBlank() -> tr("ngrok 代理意外退出，正在重试", "The ngrok agent stopped unexpectedly; retrying")
                else -> tr("ngrok 报错，见详情。", "ngrok reported an error; see details.")
            }
        }
    }

    override fun runSelfTest() {
        val base = _config.value.baseUrl ?: return
        flows.selfTest.value = TunnelSelfTest.Running
        flows.selfTest.value = TunnelProbe.run(base, { code, body -> judge(base, code, body) }, ::judgeError)
        AppLogger.info(TAG, "self-test: ${flows.selfTest.value}")
    }

    internal fun judge(base: String, code: Int, body: String): TunnelSelfTest = when {
        TunnelProbe.isMine(base, code, body) -> TunnelSelfTest.Ok
        body.contains("ERR_NGROK_3200") -> TunnelSelfTest.Failed(tr("ngrok 显示这个地址离线", "ngrok says this address is offline"),
            tr("这个域名上没有在线的代理。等上方“隧道连接”变为已连上；仍然离线时，确认填写的域名与 authtoken 属于同一个账号。", "No agent is online for this domain. Wait for Tunnel connection above to show connected; if it stays offline, check the domain and authtoken are on the same account."), "HTTP $code")
        body.contains("ERR_NGROK_6024") -> TunnelSelfTest.Failed(tr("被 ngrok 的浏览器提示页拦下", "Stopped by ngrok's browser warning page"),
            tr("免费账号对浏览器访问会先显示一个提示页。客户端程序访问不受影响；如仍失败，请在 ngrok 后台查看这个域名的设置。", "Free accounts show a warning page to browsers first. Client programs are not affected; if it still fails, check the domain's settings in the ngrok dashboard."), "HTTP $code")
        code == 502 || code == 503 || code == 504 || body.contains("ERR_NGROK_8012") -> TunnelSelfTest.Failed(tr("到了 ngrok，但转不到手机上的服务", "Reaches ngrok but not the service on the phone"),
            tr("手机上的公网服务没有回应。关闭再开启公网接入；仍然失败时，在 设置 › 诊断 导出日志反馈。", "The public server on the phone did not answer. Turn public access off and on; if it still fails, export the log under Settings › Diagnostics."), "HTTP $code")
        else -> TunnelSelfTest.Failed(tr("这个地址没有指向这台手机", "This address does not lead to this phone"),
            tr("返回的不是本机的内容（HTTP $code）。确认填写的域名就是 ngrok 后台 Domains 里的那个，且没有在 ngrok 上给它配置其它转发规则。", "What came back is not this phone (HTTP $code). Check the domain is the one under Domains in the dashboard, with no other forwarding rules set on it in ngrok."), body.take(200))
    }

    private fun judgeError(e: Throwable): TunnelSelfTest = when (e) {
        is java.net.UnknownHostException -> TunnelSelfTest.Failed(tr("域名解析不到", "The domain does not resolve"),
            tr("检查域名是否拼写正确（ngrok 后台 Domains 页面里的那个）。开着代理 VPN 时，它可能拦下了这次请求。", "Check the domain is spelled as under Domains in the dashboard. A proxy VPN may have stopped this request."), e.message)
        else -> TunnelProbe.commonError(e)
    }
}
