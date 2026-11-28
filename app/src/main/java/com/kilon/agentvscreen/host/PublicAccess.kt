package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.api.Agents
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.PublicOAuth
import com.kilon.agentvscreen.api.PublicServer
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * 2.0.0-beta7 (7.1, 7.4, 7.7, 7.8): lifecycle coordinator for public MCP access via Tailscale Funnel.
 *
 * - Default OFF.
 * - Automatically resets to OFF when [BuildConfig.VERSION_CODE] changes across app updates (7.4, 7.7).
 * - Automatically closes after [IDLE_CLOSE_MS] (24 h) with no public requests and posts a notification (7.4).
 * - Posts an ongoing notification `"公网接入已开启"` with a one-tap `"关闭"` action (no confirmation needed).
 * - Rotates `TSB_PUBLIC_SECRET` on every `tsbridge` start (never logged).
 * - Translates Tailscale Funnel error strings per 7.8 #2.
 */
object PublicAccess {
    private const val TAG = "PublicAccess"
    private const val PREFS_NAME = "agent_vscreen_public"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VERSION_CODE = "enabled_version_code"
    private const val KEY_LAST_ACTIVITY = "last_activity_at"
    private const val KEY_RISK_CONSENT_VERSION = "risk_consent_version"
    private const val KEY_STRICT_PAIRING_WINDOW = "strict_pairing_window"

    /** Bump only when the risk disclosure text changes and requires re-acceptance. */
    const val RISK_CONSENT_VERSION = 2
    const val PAIRING_WINDOW_MS = 3L * 60_000L

    const val ADMIN_DNS_URL = "https://login.tailscale.com/admin/dns"
    const val ADMIN_ACL_URL = "https://login.tailscale.com/admin/acls/file"
    const val DOCS_HTTPS_URL = "https://tailscale.com/docs/how-to/set-up-https-certificates"
    const val DOCS_FUNNEL_URL = "https://tailscale.com/kb/1223/funnel"
    const val FUNNEL_NODE_ATTRS_SNIPPET = """"nodeAttrs": [
  { "target": ["autogroup:member"], "attr": ["funnel"] }
]"""

    const val IDLE_CLOSE_MS = 24L * 60 * 60_000L
    const val ACTION_PUBLIC_OFF = "com.kilon.agentvscreen.PUBLIC_OFF"

    private const val NOTE_CHANNEL = "agent_vscreen_public"
    private const val NOTE_ON_ID = 7011
    private const val NOTE_IDLE_ID = 7012

    enum class BlockReason {
        HEADSCALE,
        TAILNET_NOT_READY,
        NO_LOCKSCREEN,
    }

    /**
     * 2.0.0-beta11 (rainbow: support Cloudflare beside Tailscale): which public channel carries public MCP. Only one is
     * on at a time; switching turns public access off, so the new channel is enabled with its own confirmation.
     */
    enum class Channel(val key: String) {
        TAILSCALE("tailscale"), CLOUDFLARE("cloudflare"), NGROK("ngrok");
        val title: String get() = when (this) { TAILSCALE -> "Tailscale Funnel"; CLOUDFLARE -> "Cloudflare Tunnel"; NGROK -> "ngrok" }
        /** The tunnel client of this channel; Tailscale Funnel runs inside the built-in Tailscale node instead. */
        val tunnel: PublicTunnel? get() = when (this) { TAILSCALE -> null; CLOUDFLARE -> Cloudflared; NGROK -> Ngrok }
        companion object { fun of(k: String?): Channel = entries.firstOrNull { it.key == k } ?: TAILSCALE }
    }

    enum class CheckId {
        CF_TOKEN,
        CF_HOSTNAME,
        NGROK_TOKEN,
        NGROK_DOMAIN,
        TAILSCALE_OFFICIAL,
        HTTPS_CERT,
        FUNNEL_ACL,
        FUNNEL_PORT_443,
        LOCKSCREEN,
    }

    enum class CheckState { OK, FAIL, CHECKING }

    enum class CheckAction {
        NONE,
        OPEN_REMOTE,
        OPEN_URL,
        OPEN_LOCKSCREEN,
        RETRY_CHECK,
    }

    data class CheckItem(
        val id: CheckId,
        val state: CheckState,
        val title: String,
        val detail: String = "",
        val action: CheckAction = CheckAction.NONE,
        val actionLabel: String = "",
        val actionUrl: String? = null,
        val copySnippet: String? = null,
        val copyGuide: String? = null,
    )

    data class ChecklistSnapshot(
        val items: List<CheckItem>,
        val allOk: Boolean,
        val checking: Boolean,
        val shouldWait: Boolean,
        val dnsName: String? = null,
    )

    data class FunnelMessage(val title: String, val fix: String)

    private var prefs: SharedPreferences? = null
    private const val KEY_CHANNEL = "channel"
    private val _channel = MutableStateFlow(Channel.TAILSCALE)
    val channelFlow: StateFlow<Channel> = _channel.asStateFlow()
    val channel: Channel get() = _channel.value
    /** Cloudflare's local origin: the [PublicServer] the tunnel forwards to ([Cloudflared.ORIGIN_PORT]). */
    @Volatile var cfOrigin: PublicServer? = null; private set
    private val rng = SecureRandom()
    private val _enabled = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabled.asStateFlow()
    val enabled: Boolean get() = _enabled.value

    private val _riskConsentAccepted = MutableStateFlow(false)
    val riskConsentAcceptedFlow: StateFlow<Boolean> = _riskConsentAccepted.asStateFlow()
    val riskConsentAccepted: Boolean get() = _riskConsentAccepted.value
    @Volatile private var savedConsentVersion: Int = 0

    private val _strictPairingWindow = MutableStateFlow(false)
    val strictPairingWindowFlow: StateFlow<Boolean> = _strictPairingWindow.asStateFlow()
    val strictPairingWindow: Boolean get() = _strictPairingWindow.value

    private val _pairingWindowUntil = MutableStateFlow(0L)
    val pairingWindowUntilFlow: StateFlow<Long> = _pairingWindowUntil.asStateFlow()
    val pairingWindowUntil: Long get() = _pairingWindowUntil.value

    @Volatile var secret: String = newSecret(); private set
    @Volatile var server: PublicServer? = null; private set
    @Volatile var lastActivityAt: Long = 0L; private set
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile internal var currentVersionCode: () -> Int = { BuildConfig.VERSION_CODE }
    @Volatile internal var postNotifications: Boolean = true
    @Volatile internal var startServerOnEnable: Boolean = true
    @Volatile internal var requestFunnelCheckAction: () -> Boolean = { Tailnet.requestFunnelCheck() }

    val boundPort: Int get() = server?.boundPort?.takeIf { it > 0 } ?: PublicServer.DEFAULT_PORT

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _channel.value = Channel.of(p.getString(KEY_CHANNEL, null))
        _strictPairingWindow.value = p.getBoolean(KEY_STRICT_PAIRING_WINDOW, false)
        _pairingWindowUntil.value = 0L
        Cloudflared.init(ctx)
        Ngrok.init(ctx)
        PublicOAuth.init(ctx)
        val savedEnabled = p.getBoolean(KEY_ENABLED, false)
        val savedVersion = p.getInt(KEY_VERSION_CODE, -1)
        val nowVersion = currentVersionCode()
        lastActivityAt = p.getLong(KEY_LAST_ACTIVITY, 0L)
        savedConsentVersion = p.getInt(KEY_RISK_CONSENT_VERSION, 0)
        _riskConsentAccepted.value = savedConsentVersion == RISK_CONSENT_VERSION
        if (savedEnabled && savedVersion != nowVersion) {
            // 7.4 / 7.7: app version changed — force public access OFF so the user re-reviews warnings & strong confirm.
            p.edit().putBoolean(KEY_ENABLED, false).putInt(KEY_VERSION_CODE, nowVersion).apply()
            _enabled.value = false
            Audit.security("public_disabled_version_change", "from=$savedVersion to=$nowVersion")
            AppLogger.info(TAG, "public access disabled on version change ($savedVersion -> $nowVersion)")
        } else {
            _enabled.value = savedEnabled
        }
    }

    /** Optional strict mode: reject public registration & pairing unless the user explicitly opened a short pairing window. */
    fun setStrictPairingWindow(ctx: Context? = null, strict: Boolean) {
        _strictPairingWindow.value = strict
        if (!strict) _pairingWindowUntil.value = 0L
        val p = prefs ?: ctx?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.also { prefs = it }
        p?.edit()?.putBoolean(KEY_STRICT_PAIRING_WINDOW, strict)?.apply()
        Audit.security("public_strict_pairing_window", "enabled=$strict")
    }

    /** Open the strict pairing window for [durationMs] (default 3 minutes). */
    fun openPairingWindow(durationMs: Long = PAIRING_WINDOW_MS): Long {
        val until = clock() + durationMs.coerceAtLeast(30_000L)
        _pairingWindowUntil.value = until
        Audit.security("public_pairing_window_opened", "ms=${until - clock()}")
        return until
    }

    fun closePairingWindow() {
        if (_pairingWindowUntil.value != 0L) {
            _pairingWindowUntil.value = 0L
            Audit.security("public_pairing_window_closed", "")
        }
    }

    fun isPairingAllowed(nowMs: Long = clock()): Boolean = !strictPairingWindow || nowMs < pairingWindowUntil

    /** Record acceptance of the current risk disclosure version so the user is not prompted again unless the text changes. */
    fun acceptRiskConsent(ctx: Context? = null, version: Int = RISK_CONSENT_VERSION) {
        savedConsentVersion = version
        _riskConsentAccepted.value = savedConsentVersion == RISK_CONSENT_VERSION
        val p = prefs ?: ctx?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.also { prefs = it }
        p?.edit()?.putInt(KEY_RISK_CONSENT_VERSION, version)?.apply()
    }

    fun hasAcceptedRiskConsent(requiredVersion: Int = RISK_CONSENT_VERSION): Boolean =
        savedConsentVersion == requiredVersion

    /** Generate a fresh random secret for `TSB_PUBLIC_SECRET` (never logged). */
    @Synchronized fun rotateSecret(): String {
        secret = newSecret()
        return secret
    }

    private fun newSecret(): String = ByteArray(32).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun isDeviceSecure(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)

    /** Preconditions checked in order before the warning page & `PUBLIC_ENABLE` strong confirmation (7.1, 7.4). */
    fun checkPreconditions(ctx: Context): BlockReason? {
        if (channel.tunnel != null) return if (!isDeviceSecure(ctx)) BlockReason.NO_LOCKSCREEN else null
        if (ApiConfig.tailnetControlUrl != null) return BlockReason.HEADSCALE
        if (ApiConfig.tailnetMode != Tailnet.Mode.BUILTIN || !Tailnet.status.value.loggedIn) {
            return BlockReason.TAILNET_NOT_READY
        }
        if (!isDeviceSecure(ctx)) return BlockReason.NO_LOCKSCREEN
        return null
    }

    /**
     * 2.0.0-beta8 (B1): evaluate all 5 public-access prerequisites in one pass so the user sees every
     * missing item up front with a one-tap remediation action.
     */
    fun evaluateChecklist(
        tailnetMode: Tailnet.Mode,
        controlUrl: String?,
        tsStatus: Tailnet.Status,
        funnelCheck: Tailnet.FunnelCheck?,
        funnelChecking: Boolean,
        deviceSecure: Boolean,
    ): ChecklistSnapshot {
        val isHeadscale = !controlUrl.isNullOrBlank()
        val tailscaleItem = when {
            isHeadscale -> CheckItem(
                id = CheckId.TAILSCALE_OFFICIAL,
                state = CheckState.FAIL,
                title = tr(S.public_access_built_in_tailscale_connected_to),
                detail = tr(S.public_access_self_hosted_control_server_headscale),
                action = CheckAction.OPEN_REMOTE,
                actionLabel = tr(S.public_access_open_settings),
            )
            tailnetMode != Tailnet.Mode.BUILTIN -> CheckItem(
                id = CheckId.TAILSCALE_OFFICIAL,
                state = CheckState.FAIL,
                title = tr(S.public_access_built_in_tailscale_connected_to),
                detail = tr(S.public_access_select_built_in_tailscale_under),
                action = CheckAction.OPEN_REMOTE,
                actionLabel = tr(S.public_access_connect),
            )
            tsStatus.loggedIn -> CheckItem(
                id = CheckId.TAILSCALE_OFFICIAL,
                state = CheckState.OK,
                title = tr(S.public_access_built_in_tailscale_connected_to),
                detail = tsStatus.dnsName?.takeIf { it.isNotBlank() } ?: tsStatus.ip.orEmpty(),
            )
            tsStatus.running && tsStatus.authUrl == null && tsStatus.error == null && tsStatus.loginError == null &&
                tsStatus.state in setOf("Starting", "NoState", "starting") -> CheckItem(
                id = CheckId.TAILSCALE_OFFICIAL,
                state = CheckState.CHECKING,
                title = tr(S.public_access_built_in_tailscale_connected_to),
                detail = tr(S.connect_connecting_to_tailscale),
                action = CheckAction.OPEN_REMOTE,
                actionLabel = tr(S.public_access_view_status),
            )
            else -> CheckItem(
                id = CheckId.TAILSCALE_OFFICIAL,
                state = CheckState.FAIL,
                title = tr(S.public_access_built_in_tailscale_connected_to),
                detail = tr(S.public_access_sign_in_to_your_official),
                action = CheckAction.OPEN_REMOTE,
                actionLabel = tr(S.public_access_sign_in),
            )
        }

        val tailscaleOk = tailscaleItem.state == CheckState.OK
        val httpsItem: CheckItem
        val funnelItem: CheckItem
        val port443Item: CheckItem

        if (!tailscaleOk) {
            val depState = if (tailscaleItem.state == CheckState.CHECKING) CheckState.CHECKING else CheckState.FAIL
            val depDetail = if (isHeadscale) {
                tr(S.public_access_headscale_does_not_support_funnel)
            } else {
                tr(S.public_access_checked_automatically_once_built_in)
            }
            httpsItem = CheckItem(CheckId.HTTPS_CERT, depState, tr(S.public_access_https_certificates_enabled), depDetail)
            funnelItem = CheckItem(CheckId.FUNNEL_ACL, depState, tr(S.public_access_funnel_acl_enabled), depDetail)
            port443Item = CheckItem(CheckId.FUNNEL_PORT_443, depState, tr(S.public_access_port_443_allowed_for_funnel), depDetail)
        } else if (funnelCheck == null || !funnelCheck.loggedIn) {
            val st = if (funnelChecking || funnelCheck == null) CheckState.CHECKING else CheckState.FAIL
            val detail = funnelCheck?.error?.takeIf { it.isNotBlank() }
                ?: tr(S.public_access_querying_tailscale_for_https_and)
            httpsItem = CheckItem(
                CheckId.HTTPS_CERT, st, tr(S.public_access_https_certificates_enabled), detail,
                action = if (st == CheckState.FAIL) CheckAction.RETRY_CHECK else CheckAction.NONE,
                actionLabel = if (st == CheckState.FAIL) tr(S.public_access_retry) else "",
            )
            funnelItem = CheckItem(CheckId.FUNNEL_ACL, st, tr(S.public_access_funnel_acl_enabled), detail)
            port443Item = CheckItem(CheckId.FUNNEL_PORT_443, st, tr(S.public_access_port_443_allowed_for_funnel), detail)
        } else {
            val serveUrl = funnelCheck.https.url?.takeIf { it.isNotBlank() }
            httpsItem = if (funnelCheck.https.ok) {
                CheckItem(
                    CheckId.HTTPS_CERT,
                    CheckState.OK,
                    tr(S.public_access_https_certificates_enabled),
                    funnelCheck.dnsName?.takeIf { it.isNotBlank() } ?: tr(S.public_access_enabled),
                )
            } else {
                CheckItem(
                    id = CheckId.HTTPS_CERT,
                    state = CheckState.FAIL,
                    title = tr(S.public_access_https_certificates_enabled),
                    detail = funnelCheck.https.text?.takeIf { it.isNotBlank() }
                        ?: funnelCheck.https.error?.takeIf { it.isNotBlank() }
                        ?: tr(S.public_access_enable_https_certificates_in_tailscale),
                    action = CheckAction.OPEN_URL,
                    actionLabel = if (serveUrl != null) tr(S.public_access_enable_https) else tr(S.public_access_open_tailscale_dns),
                    actionUrl = serveUrl ?: ADMIN_DNS_URL,
                )
            }

            val funnelUrl = funnelCheck.funnel.url?.takeIf { it.isNotBlank() }
            funnelItem = if (funnelCheck.funnel.ok) {
                CheckItem(
                    CheckId.FUNNEL_ACL,
                    CheckState.OK,
                    tr(S.public_access_funnel_acl_enabled),
                    tr(S.public_access_funnel_allowed_for_this_device),
                )
            } else {
                CheckItem(
                    id = CheckId.FUNNEL_ACL,
                    state = CheckState.FAIL,
                    title = tr(S.public_access_funnel_acl_enabled),
                    detail = funnelCheck.funnel.text?.takeIf { it.isNotBlank() }
                        ?: funnelCheck.funnel.error?.takeIf { it.isNotBlank() }
                        ?: tr(S.public_access_enable_funnel_in_your_tailscale),
                    action = CheckAction.OPEN_URL,
                    actionLabel = if (funnelUrl != null) tr(S.public_access_enable_funnel) else tr(S.public_access_open_tailscale_acl),
                    actionUrl = funnelUrl ?: ADMIN_ACL_URL,
                    copySnippet = if (funnelUrl == null) FUNNEL_NODE_ATTRS_SNIPPET else null,
                    copyGuide = if (funnelUrl == null) tr(S.public_access_switch_to_the_json_editor) else null,
                )
            }

            port443Item = if (funnelCheck.port443.ok) {
                CheckItem(
                    CheckId.FUNNEL_PORT_443,
                    CheckState.OK,
                    tr(S.public_access_port_443_allowed_for_funnel),
                    tr(S.public_access_port_443_allowed),
                )
            } else {
                CheckItem(
                    id = CheckId.FUNNEL_PORT_443,
                    state = CheckState.FAIL,
                    title = tr(S.public_access_port_443_allowed_for_funnel),
                    detail = funnelCheck.port443.error?.takeIf { it.isNotBlank() }
                        ?: funnelCheck.port443.text?.takeIf { it.isNotBlank() }
                        ?: tr(S.public_access_tailscale_policy_does_not_allow),
                    action = CheckAction.OPEN_URL,
                    actionLabel = if (funnelUrl != null) tr(S.public_access_open_tailscale) else tr(S.public_access_open_tailscale_acl),
                    actionUrl = funnelUrl ?: ADMIN_ACL_URL,
                )
            }
        }

        val lockItem = if (deviceSecure) {
            CheckItem(
                CheckId.LOCKSCREEN,
                CheckState.OK,
                tr(S.public_access_phone_screen_lock_is_set),
                tr(S.public_access_ready_for_biometric_or_screen),
            )
        } else {
            CheckItem(
                id = CheckId.LOCKSCREEN,
                state = CheckState.FAIL,
                title = tr(S.public_access_phone_screen_lock_is_set),
                detail = tr(S.public_access_enabling_public_access_and_approving),
                action = CheckAction.OPEN_LOCKSCREEN,
                actionLabel = tr(S.public_access_set_screen_lock),
            )
        }

        val items = listOf(tailscaleItem, httpsItem, funnelItem, port443Item, lockItem)
        val shouldWait = funnelCheck?.shouldWait == true
        return ChecklistSnapshot(
            items = items,
            allOk = items.all { it.state == CheckState.OK },
            checking = funnelChecking || items.any { it.state == CheckState.CHECKING },
            shouldWait = shouldWait,
            dnsName = funnelCheck?.dnsName?.takeIf { it.isNotBlank() } ?: tsStatus.dnsName,
        )
    }

    /** Trigger a live `funnel-check` when official built-in Tailscale is logged in (called on page open & ON_RESUME). */
    fun recheckPrerequisites(
        tailnetMode: Tailnet.Mode = ApiConfig.tailnetMode,
        controlUrl: String? = ApiConfig.tailnetControlUrl,
        tsStatus: Tailnet.Status = Tailnet.status.value,
    ): Boolean {
        if (!controlUrl.isNullOrBlank()) return false
        if (tailnetMode != Tailnet.Mode.BUILTIN || !tsStatus.loggedIn) return false
        return requestFunnelCheckAction()
    }

    /** True only when every checklist item is OK and risk disclosure for the current version has been accepted. */
    fun canEnable(checklist: ChecklistSnapshot, consentAccepted: Boolean = riskConsentAccepted): Boolean =
        checklist.allOk && consentAccepted

    fun publicBaseUrl(): String? = when (channel) {
        Channel.CLOUDFLARE, Channel.NGROK -> channel.tunnel?.baseUrl
        Channel.TAILSCALE -> Tailnet.status.value.dnsName?.trim()?.takeIf { it.isNotEmpty() }?.let { "https://$it" }
    }

    /** Switch the public channel. A running public access is turned off first: the new one is enabled on its own. */
    fun setChannel(ctx: Context, c: Channel) {
        if (c == channel) return
        if (enabled) disable(ctx.applicationContext, "channel_${c.key}")
        _channel.value = c
        prefs?.edit()?.putString(KEY_CHANNEL, c.key)?.apply()
        Audit.security("public_channel", c.key)
    }

    /**
     * The Cloudflare checklist: what can be checked before enabling (token, hostname, screen lock). Whether the tunnel
     * connects and the hostname reaches this phone is shown live once it runs ([Cloudflared.status], [Cloudflared.selfTest]).
     */
    fun cloudflareChecklist(cfg: Cloudflared.Config, deviceSecure: Boolean): ChecklistSnapshot {
        val tok = CheckItem(CheckId.CF_TOKEN, if (cfg.tunnelId != null) CheckState.OK else CheckState.FAIL,
            tr(S.public_access_tunnel_token_entered),
            if (cfg.tunnelId != null) tr(S.public_access_tunnel, cfg.tunnelId!!.take(8))
            else tr(S.public_access_after_creating_a_tunnel_in))
        val host = CheckItem(CheckId.CF_HOSTNAME, if (cfg.hostname != null) CheckState.OK else CheckState.FAIL,
            tr(S.public_access_public_hostname_entered),
            cfg.hostname ?: tr(S.public_access_the_hostname_added_under_the))
        val items = listOf(tok, host, lockItem(deviceSecure))
        return ChecklistSnapshot(items, items.all { it.state == CheckState.OK }, checking = false, shouldWait = false, dnsName = cfg.hostname)
    }

    /** The ngrok checklist: authtoken, domain, screen lock; connection and self-test are shown live ([Ngrok.status]). */
    fun ngrokChecklist(cfg: Ngrok.Config, deviceSecure: Boolean): ChecklistSnapshot {
        val tok = CheckItem(CheckId.NGROK_TOKEN, if (cfg.token != null) CheckState.OK else CheckState.FAIL,
            tr(S.public_access_authtoken_entered),
            if (cfg.token != null) tr(S.public_access_saved, cfg.token.take(4))
            else tr(S.public_access_copy_it_from_your_authtoken))
        val dom = CheckItem(CheckId.NGROK_DOMAIN, if (cfg.domain != null) CheckState.OK else CheckState.FAIL,
            tr(S.public_access_static_domain_entered),
            cfg.domain ?: tr(S.public_access_the_domain_under_domains_in))
        val items = listOf(tok, dom, lockItem(deviceSecure))
        return ChecklistSnapshot(items, items.all { it.state == CheckState.OK }, checking = false, shouldWait = false, dnsName = cfg.domain)
    }

    private fun lockItem(deviceSecure: Boolean): CheckItem {
        return CheckItem(CheckId.LOCKSCREEN, if (deviceSecure) CheckState.OK else CheckState.FAIL,
            tr(S.public_access_phone_screen_lock_is_set),
            if (deviceSecure) tr(S.public_access_ready_for_biometric_or_screen)
            else tr(S.public_access_enabling_public_access_and_approving_2),
            action = if (deviceSecure) CheckAction.NONE else CheckAction.OPEN_LOCKSCREEN,
            actionLabel = if (deviceSecure) "" else tr(S.public_access_set_screen_lock))
    }

    @Synchronized private fun ensureCfOrigin(ctx: Context) {
        if (cfOrigin?.running == true) return
        val api = HostService.ensureServer(ctx.applicationContext)
        val srv = PublicServer(ctx.applicationContext, api, cloudflareOrigin = true)
        if (srv.start(Cloudflared.ORIGIN_PORT, span = 1) == 0) AppLogger.warning(TAG, "Cloudflare origin port ${Cloudflared.ORIGIN_PORT} is taken")
        cfOrigin = srv
    }

    @Synchronized private fun stopCfOrigin() { cfOrigin?.stop(); cfOrigin = null }

    /** Start the current channel's tunnel (and what it forwards to). */
    private fun startTunnel(app: Context) {
        val t = channel.tunnel ?: return
        ensureServer(app)
        if (t === Cloudflared) ensureCfOrigin(app)
        t.start(app)
    }

    /** Stop every tunnel except [keep]'s (all of them when null). */
    private fun stopTunnels(why: String, keep: PublicTunnel? = null) {
        if (keep !== Cloudflared) { Cloudflared.stop(why); stopCfOrigin() }
        if (keep !== Ngrok) Ngrok.stop(why)
    }

    fun mcpUrl(): String? = publicBaseUrl()?.let { "$it/mcp" }

    /** Start or return the loopback-only [PublicServer]. */
    @Synchronized fun ensureServer(ctx: Context, port: Int = PublicServer.DEFAULT_PORT): PublicServer {
        val existing = server
        if (existing != null && existing.running) return existing
        val api = HostService.ensureServer(ctx.applicationContext)
        val srv = PublicServer(ctx.applicationContext, api)
        srv.start(port)
        server = srv
        return srv
    }

    @Synchronized fun stopServer() {
        val cur = server
        if (cur != null) {
            AppLogger.info(TAG, "stopServer(port=${cur.boundPort}, enabled=$enabled)")
            cur.stop()
        }
        server = null
    }

    /** Called by [HostService] on create/start to bring [PublicServer] and ongoing notification in sync. */
    fun sync(ctx: Context) {
        if (ThermalGuard.locked) return
        val app = ctx.applicationContext
        val tunnel = channel.tunnel.takeIf { enabled }
        stopTunnels("channel ${channel.key}, enabled=$enabled", keep = tunnel)
        if (tunnel != null) {
            startTunnel(app)
            showOngoingNotification(app)
            return
        }
        if (enabled && ApiConfig.tailnetControlUrl == null && ApiConfig.tailnetMode == Tailnet.Mode.BUILTIN) {
            ensureServer(app)
            showOngoingNotification(app)
        } else {
            if (enabled) disable(app, "tailnet_unavailable")
            else {
                stopServer()
                cancelOngoingNotification(app)
            }
        }
    }

    fun onServiceDestroyed(ctx: Context) {
        stopTunnels("service destroyed")
        stopServer()
        cancelOngoingNotification(ctx.applicationContext)
    }

    /** Enable public MCP after warning + `Risk.PUBLIC_ENABLE` biometric/PIN confirmation (7.4). */
    fun enable(ctx: Context) {
        if (ThermalGuard.locked) return
        val app = ctx.applicationContext
        val now = clock()
        val ver = currentVersionCode()
        lastActivityAt = now
        _enabled.value = true
        prefs?.edit()
            ?.putBoolean(KEY_ENABLED, true)
            ?.putInt(KEY_VERSION_CODE, ver)
            ?.putLong(KEY_LAST_ACTIVITY, now)
            ?.apply()
        if (startServerOnEnable) {
            if (channel.tunnel != null) startTunnel(app) else ensureServer(app)
        }
        showOngoingNotification(app)
        Audit.security("public_enabled", "v=$ver channel=${channel.key}")
        AppLogger.info(TAG, "public access enabled (port=$boundPort)")
        if (startServerOnEnable && channel == Channel.TAILSCALE && ApiConfig.tailnetMode == Tailnet.Mode.BUILTIN && ApiConfig.tailnetControlUrl == null) {
            Thread({ Tailnet.restart(app) }, "vscreen-public-ts-restart").apply { isDaemon = true }.start()
        }
    }

    /**
     * Turn off public MCP (no confirmation needed). Does NOT revoke already authorized public clients (7.4).
     */
    fun disable(ctx: Context, why: String, notifyIdle: Boolean = false) {
        val app = ctx.applicationContext
        val wasOn = _enabled.value
        _enabled.value = false
        closePairingWindow()
        prefs?.edit()?.putBoolean(KEY_ENABLED, false)?.apply()
        stopTunnels("disabled: $why")
        stopServer()
        cancelOngoingNotification(app)
        if (notifyIdle) showIdleClosedNotification(app)
        if (wasOn) {
            Audit.security("public_disabled", why)
            AppLogger.info(TAG, "public access disabled: $why")
            if (channel == Channel.TAILSCALE && ApiConfig.tailnetMode == Tailnet.Mode.BUILTIN && Tailnet.status.value.running) {
                Thread({ Tailnet.restart(app) }, "vscreen-public-ts-stop").apply { isDaemon = true }.start()
            }
        }
    }

    /** Record authenticated public activity (valid MCP Bearer or HTTP 200 /oauth/token) for the 24 h idle auto-close timer. */
    fun noteActivity() {
        val now = clock()
        if (now - lastActivityAt >= 60_000L || lastActivityAt == 0L) {
            lastActivityAt = now
            prefs?.edit()?.putLong(KEY_LAST_ACTIVITY, now)?.apply()
        } else {
            lastActivityAt = now
        }
    }

    /** Called periodically by [HostService]; auto-closes public access after 24 h without authenticated public activity (7.4). */
    fun checkIdle(ctx: Context, nowMs: Long = clock()): Boolean {
        if (!enabled) return false
        val last = lastActivityAt
        if (last <= 0L) {
            lastActivityAt = nowMs
            return false
        }
        if (nowMs - last >= IDLE_CLOSE_MS) {
            disable(ctx.applicationContext, "idle_24h", notifyIdle = true)
            return true
        }
        return false
    }

    /** Revoke all authorized public clients (Tokens + StrongConfirm approvals + OAuth tokens + AgentLedger/schedules/watches). */
    fun revokeAllPublicClients(ctx: Context): Int {
        val app = ctx.applicationContext
        val pubIds = Tokens.list.value.filter { it.publicOnly && it.id != Tokens.MAIN }.map { it.id }
        pubIds.forEach { id -> Agents.forget(app, id) }
        PublicOAuth.revokeAll()
        Audit.security("public_clients_revoked_all", "count=${pubIds.size}")
        return pubIds.size
    }

    /**
     * 2.0.0-beta7 (7.8 #2): translate Tailscale Funnel error text into user-facing cause + fix.
     * Matches the 3 exact prefixes from `tailscale.com` `ipn/serve.go:656-675`; any other raw text
     * (including cert issuance timeout) is shown verbatim followed by the Tailscale Admin reminder.
     */
    fun translateFunnelError(raw: String?): FunnelMessage? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            text.contains("HTTPS must be enabled") -> FunnelMessage(
                title = tr(S.public_access_https_certificates_are_not_enabled),
                fix = tr(S.public_access_enable_https_certificates_under_tailscal),
            )
            text.contains("\"funnel\" node attribute not set") -> FunnelMessage(
                title = tr(S.public_access_funnel_is_not_enabled_in),
                fix = tr(S.public_access_grant_the_funnel_node_attribute),
            )
            text.contains("port 443 is not allowed") -> FunnelMessage(
                title = tr(S.public_access_port_443_is_not_allowed),
                fix = tr(S.public_access_allow_port_443_in_your),
            )
            else -> FunnelMessage(
                title = tr(S.public_access_funnel_failed_to_start),
                fix = text + "\n" + tr(S.public_access_confirm_https_and_funnel_are),
            )
        }
    }

    // ── Notifications (7.4) ────────────────────────────────────────────────

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(NOTE_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTE_CHANNEL,
                    tr(S.public_access_public_access_status),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    private fun openPublicIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, com.kilon.agentvscreen.ui.MainActivity::class.java)
            .putExtra(com.kilon.agentvscreen.ui.MainActivity.EXTRA_PAGE, "PUBLIC")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx,
            NOTE_ON_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            com.kilon.agentvscreen.ui.MainDisplay.options(),
        )
    }

    private fun showOngoingNotification(ctx: Context, funnelState: String? = Tailnet.status.value.funnel) {
        if (!postNotifications) return
        quiet("public-note-on") {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return@quiet
            ensureChannel(nm)
            val offPi = PendingIntent.getBroadcast(
                ctx,
                NOTE_ON_ID + 100,
                Intent(ctx, NoteActions::class.java).setAction(ACTION_PUBLIC_OFF),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val addr = mcpUrl()
            val chTitle = channel.title
            val isTunnel = channel.tunnel != null
            val title = when {
                isTunnel -> tr(S.public_access_public_access_is_on_2, chTitle)
                funnelState == "on" -> tr(S.public_access_public_access_is_on)
                funnelState == "cert" -> tr(S.public_access_public_access_fetching_certificate)
                funnelState == "error" -> tr(S.public_access_public_access_failed_to_start)
                else -> tr(S.public_access_public_access_starting)
            }
            val body = when {
                isTunnel -> addr ?: tr(S.public_access_serving_public_mcp_via_tap, chTitle)
                funnelState == "on" -> addr ?: tr(S.public_access_serving_public_mcp_via_tap, chTitle)
                funnelState == "cert" -> tr(S.public_access_requesting_https_certificate_first_time)
                funnelState == "error" -> Tailnet.status.value.funnelReason ?: tr(S.public_access_open_the_app_to_inspect)
                else -> addr ?: tr(S.public_access_starting, chTitle)
            }
            val n = NotificationCompat.Builder(ctx, NOTE_CHANNEL)
                .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(openPublicIntent(ctx))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, tr(S.overview_off), offPi)
                .build()
            nm.notify(NOTE_ON_ID, n)
        }
    }

    /** Called when tsbridge reports a new Funnel state so the ongoing notification reflects real cert/on readiness (A.3 / C1). */
    fun onFunnelStateChanged(ctx: Context, funnelState: String?) {
        if (enabled && channel == Channel.TAILSCALE) showOngoingNotification(ctx.applicationContext, funnelState)
    }

    private fun cancelOngoingNotification(ctx: Context) {
        if (!postNotifications) return
        quiet("public-note-cancel") {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ON_ID)
        }
    }

    private fun showIdleClosedNotification(ctx: Context) {
        if (!postNotifications) return
        quiet("public-note-idle") {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return@quiet
            ensureChannel(nm)
            val n = NotificationCompat.Builder(ctx, NOTE_CHANNEL)
                .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
                .setContentTitle(tr(S.public_access_public_access_turned_off_automatically))
                .setContentText(tr(S.public_access_no_authenticated_public_requests_for, channel.title))
                .setContentIntent(openPublicIntent(ctx))
                .setAutoCancel(true)
                .build()
            nm.notify(NOTE_IDLE_ID, n)
        }
    }

    internal fun resetForTests() {
        stopServer()
        _enabled.value = false
        savedConsentVersion = 0
        _riskConsentAccepted.value = false
        _strictPairingWindow.value = false
        _pairingWindowUntil.value = 0L
        lastActivityAt = 0L
        clock = { System.currentTimeMillis() }
        currentVersionCode = { BuildConfig.VERSION_CODE }
        postNotifications = false
        startServerOnEnable = true
        requestFunnelCheckAction = { Tailnet.requestFunnelCheck() }
        secret = newSecret()
    }
}
