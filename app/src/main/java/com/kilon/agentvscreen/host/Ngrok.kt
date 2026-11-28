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
        if (!rawToken.isNullOrBlank() && tok == null) return tr(S.ngrok_no_authtoken_found_paste_the)
        val dom = rawDomain?.let { normalizeDomain(it) }
        if (!rawDomain.isNullOrBlank() && dom == null) return tr(S.ngrok_not_a_valid_domain_use)
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
        if (!_config.value.complete) { flows.status.value = TunnelStatus(TunnelState.ERROR, problem = tr(S.ngrok_the_authtoken_and_domain_are)); return }
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
        if (!bin.canExecute()) { flows.status.value = TunnelStatus(TunnelState.ERROR, problem = tr(S.ngrok_built_in_component_missing, bin)); return null }
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
                    problem = if (mismatch) tr(S.ngrok_ngrok_gave_not_the_domain, url) else null)
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
            "105", "106", "107" -> tr(S.ngrok_ngrok_does_not_accept_this)
            "108" -> tr(S.ngrok_this_ngrok_account_already_has)
            "313", "319", "320" -> tr(S.ngrok_this_domain_is_not_on)
            "4018" -> tr(S.ngrok_the_ngrok_account_needs_to)
            "334" -> tr(S.ngrok_another_online_ngrok_endpoint_already)
            else -> when {
                reason.contains("no such host", true) || reason.contains("lookup", true) ->
                    tr(S.ngrok_cannot_reach_ngrok_its_servers)
                reason.contains("timeout", true) || reason.contains("refused", true) || reason.contains("reset", true) ->
                    tr(S.ngrok_connecting_to_ngrok_is_blocked)
                reason.isBlank() -> tr(S.ngrok_the_ngrok_client_stopped_unexpectedly)
                else -> tr(S.ngrok_ngrok_reported_an_error_see)
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
        body.contains("ERR_NGROK_3200") -> TunnelSelfTest.Failed(tr(S.ngrok_ngrok_says_this_address_is),
            tr(S.ngrok_no_ngrok_client_is_online), "HTTP $code")
        body.contains("ERR_NGROK_6024") -> TunnelSelfTest.Failed(tr(S.ngrok_stopped_by_ngrok_s_browser),
            tr(S.ngrok_free_accounts_show_a_warning), "HTTP $code")
        code == 502 || code == 503 || code == 504 || body.contains("ERR_NGROK_8012") -> TunnelSelfTest.Failed(tr(S.ngrok_reaches_ngrok_but_not_the),
            tr(S.ngrok_the_public_server_on_the), "HTTP $code")
        else -> TunnelSelfTest.Failed(tr(S.ngrok_this_address_does_not_lead),
            tr(S.ngrok_what_came_back_is_not, code), body.take(200))
    }

    private fun judgeError(e: Throwable): TunnelSelfTest = when (e) {
        is java.net.UnknownHostException -> TunnelSelfTest.Failed(tr(S.ngrok_the_domain_does_not_resolve),
            tr(S.ngrok_check_the_domain_is_spelled), e.message)
        else -> TunnelProbe.commonError(e)
    }
}
