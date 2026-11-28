package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.tr

import android.content.Context
import android.net.ConnectivityManager
import com.kilon.agentvscreen.api.ApiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.security.SecureRandom

/**
 * 1.1.6: the built-in Tailscale node, so other devices can reach this phone without the Tailscale app.
 *
 * The node is `libtsbridge.so` (tsnet, userspace networking: no VPN slot, no root), shipped in the APK and run as a
 * child process of this app. It joins the user's tailnet as its own device ("agent-vscreen") and forwards HTTP sent to
 * <node>:port to our local server, adding [secret] so [com.kilon.agentvscreen.api.ApiServer] knows the request really
 * came from the tailnet, and the peer's identity. The token is still required: tailnet membership alone gives nothing.
 *
 * The child dies with us (its stdin is our pipe: EOF ends it) and is restarted with backoff while the mode is on.
 * Login: the bridge reports an auth URL (the user signs in in a browser) or uses an auth key given once.
 */
object Tailnet {
    private const val TAG = "Tailnet"

    enum class Mode(val key: String) { OFF("off"), BUILTIN("builtin"), SYSTEM("system");
        companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } ?: OFF }
    }

    data class Status(
        val running: Boolean = false,
        val state: String = "Stopped",
        val authUrl: String? = null,
        val ip: String? = null,
        val dnsName: String? = null,
        val tailnet: String? = null,
        val user: String? = null,
        val peers: Int = 0,
        val online: Int = 0,
        val error: String? = null,
        val lastPeer: String? = null,
        /** 1.1.7: NeedsLogin with no link yet: seconds waited and the node's health notes (e.g. control unreachable). */
        val waitingS: Int = 0,
        val health: String? = null,
        /** 1.1.8: the control server in use (null = Tailscale's own) and the backend's last login error. */
        val control: String? = null,
        val loginError: String? = null,
        /** 1.3 beta4: when this node's login runs out (epoch ms, 0 = never / unknown), and how old the login link is. */
        val keyExpiry: Long = 0L,
        val linkAgeS: Int = 0,
        /**
         * 1.3 beta6: "Running" only means logged in. Whether the node can be reached is whether the coordination
         * server is in touch and the home relay is connected; [flaps] = times either went down in the last 10 min.
         */
        val controlOk: Boolean = true,
        val relayOk: Boolean = true,
        val relay: String? = null,
        val flaps: Int = 0,
        val problem: String? = null,
        val downS: Int = 0,
        /** 1.3.0: logged in, relay not reached yet since this start: connecting, not connected and not dropped. */
        val connecting: Boolean = false,
        /** 2.0.0-beta7 (7.1, 7.8): Tailscale Funnel state ("starting", "on", "error") and exact error reason. */
        val funnel: String? = null,
        val funnelReason: String? = null,
    ) {
        /** Logged in and actually reachable. */
        val linkUp get() = loggedIn && controlOk && relayOk && !connecting
        /** The server's host for messages: "controlplane.tailscale.com" or the user's own (Headscale). */
        val serverName get() = control?.let { runCatching { java.net.URI(it).host }.getOrNull() ?: it } ?: "controlplane.tailscale.com"
        val ownServer get() = control != null
        val loggedIn get() = state == "Running" && ip != null
        val needsLogin get() = state == "NeedsLogin" || state == "NeedsMachineAuth"
        fun json(): JSONObject = JSONObject().put("mode", "builtin").put("state", state).put("ip", ip ?: JSONObject.NULL)
            .put("dns_name", dnsName ?: JSONObject.NULL).put("tailnet", tailnet ?: JSONObject.NULL)
            .put("peers_online", online).put("error", error ?: JSONObject.NULL).put("last_peer", lastPeer ?: JSONObject.NULL)
            .put("control", control ?: "tailscale").put("login_error", loginError ?: JSONObject.NULL)
            .put("control_ok", controlOk).put("relay_ok", relayOk).put("relay", relay ?: JSONObject.NULL)
            .put("flaps_10m", flaps).put("problem", problem ?: JSONObject.NULL)
            .put("funnel", funnel ?: JSONObject.NULL).put("funnel_reason", funnelReason ?: JSONObject.NULL)
    }

    /** 2.0.0-beta8 (B1): one prerequisite check item returned by `tsbridge`'s `funnel-check` command. */
    data class FunnelFeature(
        val ok: Boolean = false,
        val text: String? = null,
        val url: String? = null,
        val shouldWait: Boolean = false,
        val error: String? = null,
    )

    /** 2.0.0-beta8 (B1): structured result of `tsbridge`'s `funnel-check` control command. */
    data class FunnelCheck(
        val seq: String = "",
        val official: Boolean = true,
        val loggedIn: Boolean = false,
        val dnsName: String? = null,
        val https: FunnelFeature = FunnelFeature(),
        val funnel: FunnelFeature = FunnelFeature(),
        val port443: FunnelFeature = FunnelFeature(),
        val error: String? = null,
        val checkedAt: Long = 0L,
    ) {
        val shouldWait: Boolean get() = (!https.ok && https.shouldWait) || (!funnel.ok && funnel.shouldWait)
    }

    internal fun parseFunnelCheck(o: JSONObject, nowMs: Long = System.currentTimeMillis()): FunnelCheck {
        fun feat(key: String): FunnelFeature {
            val f = o.optJSONObject(key) ?: return FunnelFeature()
            return FunnelFeature(
                ok = f.optBoolean("ok", false),
                text = f.optString("text").takeIf { it.isNotBlank() },
                url = f.optString("url").takeIf { it.isNotBlank() },
                shouldWait = f.optBoolean("should_wait", false),
                error = f.optString("error").takeIf { it.isNotBlank() },
            )
        }
        return FunnelCheck(
            seq = o.optString("seq", ""),
            official = o.optBoolean("official", true),
            loggedIn = o.optBoolean("logged_in", false),
            dnsName = o.optString("dns_name").takeIf { it.isNotBlank() },
            https = feat("https"),
            funnel = feat("funnel"),
            port443 = feat("port443"),
            error = o.optString("error").takeIf { it.isNotBlank() },
            checkedAt = nowMs,
        )
    }

    /**
     * 1.3 beta4: what the user (and an agent) should understand about the built-in node, in one word. "Not logged in"
     * used to show whenever the node was not running, e.g. with the service off, though the login was kept; now the
     * last good login is remembered per control server, so a stopped or starting node says so and keeps its address.
     */
    enum class Phase(val key: String) {
        OFF("off"), SERVICE_OFF("service_off"), STARTING("starting"), NEEDS_LOGIN("needs_login"), EXPIRED("login_expired"),
        NEEDS_APPROVAL("needs_approval"), CONNECTED("connected"), UNSTABLE("unstable"), ERROR("error")
    }

    /** The last login that worked with the current control server. */
    data class Known(val ip: String, val dnsName: String?, val user: String?, val at: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tailnet_known", Context.MODE_PRIVATE)
    private fun knownKey() = ApiConfig.tailnetControlUrl ?: "tailscale"

    fun known(ctx: Context): Known? = runCatching {
        val o = JSONObject(prefs(ctx).getString(knownKey(), null) ?: return null)
        Known(o.getString("ip"), o.optString("dns").ifBlank { null }, o.optString("user").ifBlank { null }, o.optLong("at"))
    }.getOrNull()

    private fun remember(ctx: Context, st: Status) {
        val ip = st.ip ?: return
        val k = known(ctx)
        if (k != null && k.ip == ip && k.dnsName == st.dnsName && System.currentTimeMillis() - k.at < 3_600_000L) return
        prefs(ctx).edit().putString(knownKey(), JSONObject().put("ip", ip).put("dns", st.dnsName ?: "").put("user", st.user ?: "")
            .put("at", System.currentTimeMillis()).toString()).apply()
    }

    fun forget(ctx: Context) { prefs(ctx).edit().remove(knownKey()).apply() }

    fun phase(ctx: Context): Phase {
        val s = _status.value
        return when {
            ApiConfig.tailnetMode != Mode.BUILTIN -> Phase.OFF
            !HostService.isAlive -> Phase.SERVICE_OFF
            s.loggedIn && s.connecting -> Phase.STARTING
            s.loggedIn && !s.linkUp -> Phase.UNSTABLE
            // 1.3 beta7: the saved login is kept while the server cannot be reached (not "log in again").
            s.state == "Reconnecting" -> Phase.UNSTABLE
            s.loggedIn -> Phase.CONNECTED
            s.state == "NeedsMachineAuth" -> Phase.NEEDS_APPROVAL
            s.state == "NeedsLogin" -> if (known(ctx) != null) Phase.EXPIRED else Phase.NEEDS_LOGIN
            s.state == "Error" -> Phase.ERROR
            else -> Phase.STARTING
        }
    }

    /** One short line for the home tile and for agents (status / ping). */
    fun line(ctx: Context): String {
        val k = known(ctx)
        return when (phase(ctx)) {
            Phase.OFF -> tr(S.tailnet_this_phone_only)
            Phase.SERVICE_OFF -> (if (ServiceControl.now(ctx) == ServiceControl.State.BLOCKED) tr(S.overview_service_off) else tr(S.tailnet_on_demand_standby)) +
                (k?.let { " · ${it.ip}" } ?: "")
            Phase.STARTING -> tr(S.tailnet_tailscale_connecting)
            Phase.NEEDS_LOGIN -> tr(S.tailnet_tailscale_log_in)
            Phase.EXPIRED -> tr(S.tailnet_tailscale_log_in_again)
            Phase.NEEDS_APPROVAL -> tr(S.tailnet_waiting_for_approval)
            Phase.CONNECTED -> "Tailscale · ${_status.value.ip}" + if (_status.value.flaps >= 2) tr(S.tailnet_dropped_in_10_min, _status.value.flaps) else ""
            Phase.UNSTABLE -> tr(S.tailnet_tailscale_dropped_reconnecting)
            Phase.ERROR -> tr(S.tailnet_tailscale_error_retrying)
        }
    }

    /** For status: the phase, what to do about it, and the last address that worked. */
    fun json(ctx: Context): JSONObject {
        val p = phase(ctx)
        val k = known(ctx)
        return _status.value.json().put("through_vpn", throughVpn(ctx)).put("vpn_trouble", vpnTrouble(ctx))
            .put("connecting", _status.value.connecting).put("waiting_s", waitedMs() / 1000)
            .put("app_in_front", AppFront.visible).put("phase", p.key).put("summary", line(ctx)).put("last_ip", k?.ip ?: JSONObject.NULL)
            .put("key_expiry", _status.value.keyExpiry.takeIf { it > 0 }?.let { it / 1000 } ?: JSONObject.NULL)
            .put("user_action", if (vpnTrouble(ctx)) vpnAdvice() else when (p) {
                Phase.UNSTABLE -> tr(S.tailnet_the_phone_s_tailscale_node, _status.value.problem ?: "原因未知", _status.value.problem ?: "cause unknown")
                Phase.NEEDS_LOGIN, Phase.EXPIRED -> tr(S.tailnet_ask_the_user_to_log)
                Phase.NEEDS_APPROVAL -> tr(S.tailnet_ask_the_user_to_approve)
                else -> JSONObject.NULL
            })
    }

    /** Random per process: only our own child knows it, so no app on the phone can pose as a tailnet peer. */
    val secret: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** The user went to the background settings (kept for callers; nothing is counted here any more). */
    fun resetSilences() {}

    /**
     * 1.3.0: when the node is not connected, since when (elapsedRealtime; 0 = connected or stopped). The page shows
     * how long it has waited and why after [SLOW_MS], instead of "connecting…" for minutes.
     */
    @Volatile var waitingSince = 0L; private set
    const val SLOW_MS = 15_000L
    fun waitedMs(): Long = waitingSince.takeIf { it > 0 }?.let { android.os.SystemClock.elapsedRealtime() - it } ?: 0L

    /**
     * 1.3.0: the phone's traffic goes through another app's VPN (a proxy app's virtual interface). On that path the
     * node's long connections were seen to get through only for their first seconds, then nothing until Tailscale's
     * own 120 s timeout: online a few seconds, gone two minutes. The app cannot get around another app's VPN; the user
     * can (exclude Agent-VScreen in the proxy app, or switch it off). Said only when the node is actually struggling.
     */
    fun throughVpn(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    fun vpnTrouble(ctx: Context): Boolean {
        if (ApiConfig.tailnetMode != Mode.BUILTIN || !HostService.isAlive) return false
        val s = _status.value
        val struggling = s.flaps >= 2 || (s.loggedIn && !s.linkUp && !s.connecting) || waitedMs() > SLOW_MS
        return struggling && throughVpn(ctx)
    }

    fun vpnAdvice(): String = tr(S.tailnet_the_phone_s_traffic_goes)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _funnelCheck = MutableStateFlow<FunnelCheck?>(null)
    val funnelCheck: StateFlow<FunnelCheck?> = _funnelCheck.asStateFlow()
    private val _funnelChecking = MutableStateFlow(false)
    val funnelChecking: StateFlow<Boolean> = _funnelChecking.asStateFlow()
    private val funnelCheckSeq = java.util.concurrent.atomic.AtomicInteger(0)

    internal fun setFunnelCheckForTests(fc: FunnelCheck?, checking: Boolean = false) {
        _funnelCheck.value = fc
        _funnelChecking.value = checking
    }

    /** 2.0.0-beta8 (B1): ask running `tsbridge` to check HTTPS / Funnel ACL / port 443 via LocalAPI + QueryFeature. */
    fun requestFunnelCheck(): Boolean {
        val w = stdin ?: return false
        val seq = funnelCheckSeq.incrementAndGet()
        _funnelChecking.value = true
        return runCatching {
            synchronized(w) {
                w.write("funnel-check $seq\n")
                w.flush()
            }
            true
        }.getOrElse {
            _funnelChecking.value = false
            false
        }
    }

    @Volatile private var proc: Process? = null
    @Volatile private var stdin: OutputStreamWriter? = null
    @Volatile private var wanted = false
    @Volatile private var restarts = 0
    /**
     * 1.3 beta6: which supervisor owns the node. Before, a stop followed quickly by a start (the service restarted,
     * the switch flipped, the built-in mode turned off and on) could leave the old supervisor alive: the interrupt
     * meant to end it was swallowed by Process.waitFor, and it started a second bridge on the same state directory.
     * Two nodes with one identity take turns on the control server and the relay: online for a while, then gone.
     */
    @Volatile private var gen = 0
    private var supervisor: Thread? = null

    fun binary(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libtsbridge.so")
    fun available(ctx: Context): Boolean = binary(ctx).canExecute()

    /** Called from HostService start and whenever the setting changes. */
    /** The local port the running bridge forwards to (0: not started). */
    @Volatile private var target = 0

    /** 1.4.0-beta15: the service moved to another local port: a running bridge is restarted to forward there. */
    fun retarget(ctx: Context, port: Int) {
        if (wanted && target != 0 && target != port) { AppLogger.info(TAG, "local port now $port (was $target): restarting the bridge"); restart(ctx.applicationContext) }
    }

    @Synchronized fun sync(ctx: Context) {
        if (ThermalGuard.locked) return
        if (ApiConfig.tailnetMode == Mode.BUILTIN) start(ctx.applicationContext)
        else {
            if (PublicAccess.enabled && PublicAccess.channel == PublicAccess.Channel.TAILSCALE) PublicAccess.disable(ctx.applicationContext, "tailnet_mode_${ApiConfig.tailnetMode.key}")
            stop("mode ${ApiConfig.tailnetMode}")
        }
    }

    @Synchronized private fun start(ctx: Context) {
        wanted = true
        if (supervisor?.isAlive == true) return
        val g = ++gen
        supervisor = Thread({ supervise(ctx, g) }, "vscreen-tailnet").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop(why: String) {
        if (wanted || proc != null) AppLogger.info(TAG, "stop: $why")
        wanted = false
        gen++
        com.kilon.agentvscreen.host.quiet("Tailnet:98") { stdin?.apply { write("quit\n"); flush() } }
        proc?.let { p -> Thread { if (!runCatching { p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)) runCatching { p.destroyForcibly() } }.apply { isDaemon = true }.start() }
        supervisor?.interrupt(); supervisor = null
        _status.value = Status()
        _funnelCheck.value = null
        _funnelChecking.value = false
        waitingSince = 0L
    }

    /** Sign out of the tailnet (the node stays listed in the admin console until removed there). */
    fun logout(ctx: Context? = null) {
        ctx?.let {
            if (PublicAccess.enabled) PublicAccess.disable(it.applicationContext, "tailnet_logout")
            forget(it)
        }
        _funnelCheck.value = null
        _funnelChecking.value = false
        runCatching { stdin?.apply { write("logout\n"); flush() } }
    }

    /** 1.1.7: ask the control server for a fresh login link (the bridge also does this by itself every 20 s). */
    fun login() { runCatching { stdin?.apply { write("login\n"); flush() } } }

    /** Restart now, e.g. with a new auth key or control server. */
    fun restart(ctx: Context) { stop("restart"); sync(ctx) }

    /**
     * 1.1.8: one state directory per control server. A node's identity (its keys and login) belongs to the server it
     * registered with; one shared directory carried the Tailscale login over to a Headscale server (the backend keeps
     * the old keys when only the server changes), and an auth key was ignored while that old login looked valid.
     * Tailscale's own server keeps the 1.1.6 directory, so existing logins stay.
     */
    fun stateDir(ctx: Context, control: String? = ApiConfig.tailnetControlUrl): File {
        val name = if (control == null) "tailscale" else "tailscale-" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(control.lowercase().toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        return File(ctx.filesDir, name)
    }

    /**
     * Save the control server and, optionally, an auth key, then reconnect. A new auth key means "register this node
     * with this key": the node's old state for that server is dropped first, or the backend would keep the old login
     * and ignore the key. Another server's login is left alone.
     */
    @Synchronized fun configure(ctx: Context, control: String?, authKey: String?) {
        val old = proc
        stop("new settings")
        // Let the old node finish writing its state before it may be deleted.
        com.kilon.agentvscreen.host.quiet("Tailnet:134") { old?.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
        ApiConfig.configureTailnet(control, authKey)
        if (ApiConfig.tailnetControlUrl != null && PublicAccess.enabled) {
            PublicAccess.disable(ctx.applicationContext, "headscale_selected")
        }
        if (!authKey.isNullOrBlank()) {
            com.kilon.agentvscreen.host.quiet("Tailnet:138") { stateDir(ctx).deleteRecursively() }
        }
        sync(ctx)
    }

    private fun supervise(ctx: Context, g: Int) {
        val bin = binary(ctx)
        while (wanted && g == gen) {
            // Never two nodes on one identity: the previous bridge must be gone before the next starts.
            proc?.let { old ->
                if (old.isAlive) {
                    AppLogger.info(TAG, "waiting for the previous bridge to exit")
                    if (!runCatching { old.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)) { runCatching { old.destroyForcibly(); old.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) } }
                }
            }
            if (!wanted || g != gen) break
            if (!bin.canExecute()) {
                _status.value = Status(state = "Error", error = tr(S.tailnet_built_in_tailscale_component_missing, bin)); return
            }
            val t0 = System.currentTimeMillis()
            runCatching { runOnce(ctx, bin, g) }.onFailure { e ->
                AppLogger.warning(TAG, "bridge: ${e.javaClass.simpleName}: ${e.message}")
                _status.value = _status.value.copy(running = false, state = "Error", error = e.message)
            }
            if (!wanted || g != gen) break
            if (System.currentTimeMillis() - t0 > 60_000) restarts = 0
            val wait = (1_000L shl restarts.coerceAtMost(5)).coerceAtMost(30_000L)
            restarts++
            AppLogger.info(TAG, "bridge ended, restarting in ${wait / 1000}s")
            try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
        }
    }

    private fun runOnce(ctx: Context, bin: File, g: Int) {
        val dir = stateDir(ctx).apply { mkdirs() }
        val pb = ProcessBuilder(bin.absolutePath).directory(dir)
        val env = pb.environment()
        env["TSB_DIR"] = dir.absolutePath
        env["TSB_HOSTNAME"] = ApiConfig.tailnetHostname
        env["TSB_PORT"] = ApiConfig.port.toString()
        // 1.4.0-beta15: peers keep reaching the set port; it is forwarded to the port the service really took.
        val local = HostService.server?.boundPort?.takeIf { it > 0 } ?: ApiConfig.port
        target = local
        env["TSB_TARGET"] = "127.0.0.1:$local"
        env["TSB_SECRET"] = secret
        env["TSB_DNS"] = dnsServers(ctx).joinToString(",")
        env["HOME"] = dir.absolutePath
        ApiConfig.tailnetControlUrl?.let { env["TSB_CONTROL_URL"] = it }
        // An auth key is only needed until the node is registered; it is dropped as soon as the node runs.
        ApiConfig.tailnetAuthKey?.let { env["TSB_AUTHKEY"] = it }
        // 2.0.0-beta7 (7.1, 7.7): enable Tailscale Funnel on official Tailscale with a freshly rotated secret (never logged).
        if (PublicAccess.enabled && PublicAccess.channel == PublicAccess.Channel.TAILSCALE && ApiConfig.tailnetControlUrl == null) {
            val pubSrv = PublicAccess.ensureServer(ctx)
            val pubPort = pubSrv.boundPort.takeIf { it > 0 } ?: PublicAccess.boundPort
            env["TSB_FUNNEL"] = "1"
            env["TSB_PUBLIC_TARGET"] = "127.0.0.1:$pubPort"
            env["TSB_PUBLIC_SECRET"] = PublicAccess.rotateSecret()
        }
        val p = synchronized(this) {
            if (g != gen || !wanted) return
            pb.start().also {
                proc = it
                stdin = OutputStreamWriter(it.outputStream)
                if (waitingSince == 0L) waitingSince = android.os.SystemClock.elapsedRealtime()
                _status.value = _status.value.copy(running = true, error = null)
            }
        }
        AppLogger.info(TAG, "bridge started (pid-less child), host=${ApiConfig.tailnetHostname}")
        Thread({
            // The stream is closed under this reader when the bridge is stopped or replaced: that is its normal end.
            runCatching { p.errorStream.bufferedReader().forEachLine { l -> if (l.isNotBlank()) AppLogger.info(TAG, l.take(300)) } }
        }, "vscreen-tailnet-err").apply { isDaemon = true }.start()
        p.inputStream.bufferedReader().forEachLine { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            if (g != gen) return@forEachLine // a bridge on its way out says nothing about the current one
            if (o.optString("type") == "funnel_check") {
                _funnelCheck.value = parseFunnelCheck(o)
                _funnelChecking.value = false
                return@forEachLine
            }
            val st = Status(
                running = true,
                state = o.optString("state", "Unknown"),
                authUrl = o.optString("auth_url").takeIf { it.isNotBlank() },
                ip = o.optString("ip").takeIf { it.isNotBlank() },
                dnsName = o.optString("dns_name").takeIf { it.isNotBlank() },
                tailnet = o.optString("tailnet").takeIf { it.isNotBlank() },
                user = o.optString("user").takeIf { it.isNotBlank() },
                peers = o.optInt("peers"), online = o.optInt("online"),
                error = o.optString("error").takeIf { it.isNotBlank() },
                lastPeer = o.optString("last_peer").takeIf { it.isNotBlank() },
                waitingS = o.optInt("waiting_s"),
                health = o.optString("health").takeIf { it.isNotBlank() },
                control = o.optString("control").takeIf { it.isNotBlank() },
                loginError = o.optString("login_error").takeIf { it.isNotBlank() },
                keyExpiry = o.optLong("key_expiry") * 1000L,
                linkAgeS = o.optInt("link_age_s"),
                controlOk = o.optBoolean("control_ok", true), relayOk = o.optBoolean("relay_ok", true),
                relay = o.optString("relay").takeIf { it.isNotBlank() }, flaps = o.optInt("flaps"),
                problem = o.optString("problem").takeIf { it.isNotBlank() }, downS = o.optInt("down_s"),
                connecting = o.optBoolean("connecting"),
                funnel = o.optString("funnel").takeIf { it.isNotBlank() },
                funnelReason = o.optString("reason").takeIf { it.isNotBlank() },
            )
            var funnelChangedTo: String? = null
            var funnelChanged = false
            synchronized(this) {
                if (g != gen) return@forEachLine
                if (st.linkUp) waitingSince = 0L else if (waitingSince == 0L) waitingSince = android.os.SystemClock.elapsedRealtime()
                if (st.loggedIn && st.linkUp != _status.value.linkUp && _status.value.loggedIn)
                    AppLogger.info(TAG, (if (st.linkUp) "link up (relay ${st.relay})" else "link down: ${st.problem ?: "relay ${st.relay}"}") +
                        AppFront.awayMs().let { if (it > 0) ", app in background ${it / 1000}s" else ", app in front" })
                if (st.loggedIn) remember(ctx, st)
                if (st.state != _status.value.state) AppLogger.info(TAG, "state ${st.state}${st.ip?.let { " $it" } ?: ""}${st.error?.let { " ($it)" } ?: ""}")
                if (st.loggedIn && ApiConfig.tailnetAuthKey != null) ApiConfig.tailnetAuthKey = null
                if (st.funnel != _status.value.funnel) {
                    funnelChanged = true
                    funnelChangedTo = st.funnel
                }
                _status.value = st
            }
            if (funnelChanged) PublicAccess.onFunnelStateChanged(ctx, funnelChangedTo)
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        AppLogger.info(TAG, "bridge exited with code $code" + (if (g != gen) " (replaced)" else if (!wanted) " (stopped)" else ""))
        synchronized(this) {
            if (g != gen || proc !== p) return
            proc = null
            stdin = null
            _status.value = _status.value.copy(running = false, state = if (wanted) "Restarting" else "Stopped",
                error = if (code != 0 && wanted) tr(S.tailnet_built_in_tailscale_exited, code) else null)
        }
    }

    /** The phone's resolvers: Android has no /etc/resolv.conf for the Go resolver. */
    /**
     * 1.4.1-beta1: the resolvers of every network, the active one first. With a proxy VPN the active network is the
     * VPN and its DNS may be unreachable for this app (bypassed in the proxy); Wi-Fi's or mobile data's still are.
     * The bridge asks all of them at once (tsbridge/dns.go), so a dead one costs nothing.
     */
    internal fun dnsServers(ctx: Context): List<String> = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION") val nets = listOfNotNull(cm.activeNetwork) + cm.allNetworks.filter { it != cm.activeNetwork }
        nets.flatMap { n -> runCatching { cm.getLinkProperties(n)?.dnsServers.orEmpty().mapNotNull { it.hostAddress } }.getOrDefault(emptyList()) }
            .distinct().take(8)
    }.getOrDefault(emptyList())
}
