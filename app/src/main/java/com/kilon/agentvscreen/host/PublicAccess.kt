package com.kilon.agentvscreen.host

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

    /** Bump only when the 4-point risk disclosure text changes and requires re-acceptance (beta8 B1). */
    const val RISK_CONSENT_VERSION = 1

    const val ADMIN_DNS_URL = "https://login.tailscale.com/admin/dns"
    const val ADMIN_ACL_URL = "https://login.tailscale.com/admin/acls/file"
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

    enum class CheckId {
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
    private val rng = SecureRandom()
    private val _enabled = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabled.asStateFlow()
    val enabled: Boolean get() = _enabled.value

    private val _riskConsentAccepted = MutableStateFlow(false)
    val riskConsentAcceptedFlow: StateFlow<Boolean> = _riskConsentAccepted.asStateFlow()
    val riskConsentAccepted: Boolean get() = _riskConsentAccepted.value
    @Volatile private var savedConsentVersion: Int = 0

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
                title = tr("内置 Tailscale 已登录官方服务器", "Built-in Tailscale connected to official server"),
                detail = tr(
                    "当前使用自建控制服务器（Headscale），不支持 Funnel；请切回官方 Tailscale。",
                    "Self-hosted control server (Headscale) does not support Funnel; switch back to official Tailscale.",
                ),
                action = CheckAction.OPEN_REMOTE,
                actionLabel = tr("去切换", "Open settings"),
            )
            tailnetMode != Tailnet.Mode.BUILTIN -> CheckItem(
                id = CheckId.TAILSCALE_OFFICIAL,
                state = CheckState.FAIL,
                title = tr("内置 Tailscale 已登录官方服务器", "Built-in Tailscale connected to official server"),
                detail = tr(
                    "请先在“其它设备（Tailscale）”里选择“内置 Tailscale”并登录。",
                    "Select Built-in Tailscale under Other devices (Tailscale) and sign in first.",
                ),
                action = CheckAction.OPEN_REMOTE,
                actionLabel = tr("去连接", "Connect"),
            )
            tsStatus.loggedIn -> CheckItem(
                id = CheckId.TAILSCALE_OFFICIAL,
                state = CheckState.OK,
                title = tr("内置 Tailscale 已登录官方服务器", "Built-in Tailscale connected to official server"),
                detail = tsStatus.dnsName?.takeIf { it.isNotBlank() } ?: tsStatus.ip.orEmpty(),
            )
            tsStatus.running && tsStatus.authUrl == null && tsStatus.error == null && tsStatus.loginError == null &&
                tsStatus.state in setOf("Starting", "NoState", "starting") -> CheckItem(
                id = CheckId.TAILSCALE_OFFICIAL,
                state = CheckState.CHECKING,
                title = tr("内置 Tailscale 已登录官方服务器", "Built-in Tailscale connected to official server"),
                detail = tr("正在连接 Tailscale…", "Connecting to Tailscale…"),
                action = CheckAction.OPEN_REMOTE,
                actionLabel = tr("查看状态", "View status"),
            )
            else -> CheckItem(
                id = CheckId.TAILSCALE_OFFICIAL,
                state = CheckState.FAIL,
                title = tr("内置 Tailscale 已登录官方服务器", "Built-in Tailscale connected to official server"),
                detail = tr("请先登录官方内置 Tailscale 账号。", "Sign in to your official built-in Tailscale account first."),
                action = CheckAction.OPEN_REMOTE,
                actionLabel = tr("去登录", "Sign in"),
            )
        }

        val tailscaleOk = tailscaleItem.state == CheckState.OK
        val httpsItem: CheckItem
        val funnelItem: CheckItem
        val port443Item: CheckItem

        if (!tailscaleOk) {
            val depState = if (tailscaleItem.state == CheckState.CHECKING) CheckState.CHECKING else CheckState.FAIL
            val depDetail = if (isHeadscale) {
                tr("Headscale 不支持 Funnel", "Headscale does not support Funnel")
            } else {
                tr("连上官方内置 Tailscale 后自动检查", "Checked automatically once built-in Tailscale is connected")
            }
            httpsItem = CheckItem(CheckId.HTTPS_CERT, depState, tr("HTTPS 证书已开启", "HTTPS certificates enabled"), depDetail)
            funnelItem = CheckItem(CheckId.FUNNEL_ACL, depState, tr("Funnel 权限已开启", "Funnel ACL enabled"), depDetail)
            port443Item = CheckItem(CheckId.FUNNEL_PORT_443, depState, tr("443 端口可用于 Funnel", "Port 443 allowed for Funnel"), depDetail)
        } else if (funnelCheck == null || !funnelCheck.loggedIn) {
            val st = if (funnelChecking || funnelCheck == null) CheckState.CHECKING else CheckState.FAIL
            val detail = funnelCheck?.error?.takeIf { it.isNotBlank() }
                ?: tr("正在向 Tailscale 查询 HTTPS 与 Funnel 权限…", "Querying Tailscale for HTTPS and Funnel readiness…")
            httpsItem = CheckItem(
                CheckId.HTTPS_CERT, st, tr("HTTPS 证书已开启", "HTTPS certificates enabled"), detail,
                action = if (st == CheckState.FAIL) CheckAction.RETRY_CHECK else CheckAction.NONE,
                actionLabel = if (st == CheckState.FAIL) tr("重试", "Retry") else "",
            )
            funnelItem = CheckItem(CheckId.FUNNEL_ACL, st, tr("Funnel 权限已开启", "Funnel ACL enabled"), detail)
            port443Item = CheckItem(CheckId.FUNNEL_PORT_443, st, tr("443 端口可用于 Funnel", "Port 443 allowed for Funnel"), detail)
        } else {
            val serveUrl = funnelCheck.https.url?.takeIf { it.isNotBlank() }
            httpsItem = if (funnelCheck.https.ok) {
                CheckItem(
                    CheckId.HTTPS_CERT,
                    CheckState.OK,
                    tr("HTTPS 证书已开启", "HTTPS certificates enabled"),
                    funnelCheck.dnsName?.takeIf { it.isNotBlank() } ?: tr("已开启", "Enabled"),
                )
            } else {
                CheckItem(
                    id = CheckId.HTTPS_CERT,
                    state = CheckState.FAIL,
                    title = tr("HTTPS 证书已开启", "HTTPS certificates enabled"),
                    detail = funnelCheck.https.text?.takeIf { it.isNotBlank() }
                        ?: funnelCheck.https.error?.takeIf { it.isNotBlank() }
                        ?: tr("需在 Tailscale 开启 HTTPS Certificates，完成后回到本页自动重查。", "Enable HTTPS Certificates in Tailscale, then return here to recheck automatically."),
                    action = CheckAction.OPEN_URL,
                    actionLabel = if (serveUrl != null) tr("去开启 HTTPS", "Enable HTTPS") else tr("打开 Tailscale DNS", "Open Tailscale DNS"),
                    actionUrl = serveUrl ?: ADMIN_DNS_URL,
                )
            }

            val funnelUrl = funnelCheck.funnel.url?.takeIf { it.isNotBlank() }
            funnelItem = if (funnelCheck.funnel.ok) {
                CheckItem(
                    CheckId.FUNNEL_ACL,
                    CheckState.OK,
                    tr("Funnel 权限已开启", "Funnel ACL enabled"),
                    tr("已允许当前设备使用 Funnel", "Funnel allowed for this device"),
                )
            } else {
                CheckItem(
                    id = CheckId.FUNNEL_ACL,
                    state = CheckState.FAIL,
                    title = tr("Funnel 权限已开启", "Funnel ACL enabled"),
                    detail = funnelCheck.funnel.text?.takeIf { it.isNotBlank() }
                        ?: funnelCheck.funnel.error?.takeIf { it.isNotBlank() }
                        ?: tr("需在 Tailscale 策略中开启 Funnel 权限，完成后回到本页自动重查。", "Enable Funnel in your Tailscale policy, then return here to recheck automatically."),
                    action = CheckAction.OPEN_URL,
                    actionLabel = if (funnelUrl != null) tr("去开启 Funnel", "Enable Funnel") else tr("打开 Tailscale ACL", "Open Tailscale ACL"),
                    actionUrl = funnelUrl ?: ADMIN_ACL_URL,
                    copySnippet = if (funnelUrl == null) FUNNEL_NODE_ATTRS_SNIPPET else null,
                    copyGuide = if (funnelUrl == null) tr(
                        "切到 JSON editor，在最外层加上这段；已有 nodeAttrs 就只加中间那一条。",
                        "Switch to the JSON editor and add this at the top level; if nodeAttrs already exists, add only the middle entry.",
                    ) else null,
                )
            }

            port443Item = if (funnelCheck.port443.ok) {
                CheckItem(
                    CheckId.FUNNEL_PORT_443,
                    CheckState.OK,
                    tr("443 端口可用于 Funnel", "Port 443 allowed for Funnel"),
                    tr("允许 443 端口", "Port 443 allowed"),
                )
            } else {
                CheckItem(
                    id = CheckId.FUNNEL_PORT_443,
                    state = CheckState.FAIL,
                    title = tr("443 端口可用于 Funnel", "Port 443 allowed for Funnel"),
                    detail = funnelCheck.port443.error?.takeIf { it.isNotBlank() }
                        ?: funnelCheck.port443.text?.takeIf { it.isNotBlank() }
                        ?: tr("Tailscale 策略不允许 443 端口开启 Funnel。", "Tailscale policy does not allow port 443 for Funnel."),
                    action = CheckAction.OPEN_URL,
                    actionLabel = if (funnelUrl != null) tr("去开启", "Open Tailscale") else tr("打开 Tailscale ACL", "Open Tailscale ACL"),
                    actionUrl = funnelUrl ?: ADMIN_ACL_URL,
                )
            }
        }

        val lockItem = if (deviceSecure) {
            CheckItem(
                CheckId.LOCKSCREEN,
                CheckState.OK,
                tr("手机已设置锁屏密码", "Phone screen lock is set"),
                tr("可用于生物识别或锁屏密码确认", "Ready for biometric or screen-lock confirmation"),
            )
        } else {
            CheckItem(
                id = CheckId.LOCKSCREEN,
                state = CheckState.FAIL,
                title = tr("手机已设置锁屏密码", "Phone screen lock is set"),
                detail = tr(
                    "公网开启与客户端配对必须通过生物识别或锁屏密码确认，不允许长按确认。",
                    "Enabling public access and approving cloud clients require biometric or PIN lock confirmation.",
                ),
                action = CheckAction.OPEN_LOCKSCREEN,
                actionLabel = tr("去设置锁屏密码", "Set screen lock"),
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

    fun publicBaseUrl(): String? =
        Tailnet.status.value.dnsName?.trim()?.takeIf { it.isNotEmpty() }?.let { "https://$it" }

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
        val app = ctx.applicationContext
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
        stopServer()
        cancelOngoingNotification(ctx.applicationContext)
    }

    /** Enable public MCP after warning + `Risk.PUBLIC_ENABLE` biometric/PIN confirmation (7.4). */
    fun enable(ctx: Context) {
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
            ensureServer(app)
        }
        showOngoingNotification(app)
        Audit.security("public_enabled", "v=$ver")
        AppLogger.info(TAG, "public access enabled (port=$boundPort)")
        if (startServerOnEnable && ApiConfig.tailnetMode == Tailnet.Mode.BUILTIN && ApiConfig.tailnetControlUrl == null) {
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
        prefs?.edit()?.putBoolean(KEY_ENABLED, false)?.apply()
        stopServer()
        cancelOngoingNotification(app)
        if (notifyIdle) showIdleClosedNotification(app)
        if (wasOn) {
            Audit.security("public_disabled", why)
            AppLogger.info(TAG, "public access disabled: $why")
            if (ApiConfig.tailnetMode == Tailnet.Mode.BUILTIN && Tailnet.status.value.running) {
                Thread({ Tailnet.restart(app) }, "vscreen-public-ts-stop").apply { isDaemon = true }.start()
            }
        }
    }

    /** Record activity from any valid public HTTP request for the 24 h idle auto-close timer. */
    fun noteActivity() {
        val now = clock()
        if (now - lastActivityAt >= 60_000L || lastActivityAt == 0L) {
            lastActivityAt = now
            prefs?.edit()?.putLong(KEY_LAST_ACTIVITY, now)?.apply()
        } else {
            lastActivityAt = now
        }
    }

    /** Called periodically by [HostService]; auto-closes Funnel after 24 h of public inactivity (7.4). */
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
                title = tr("Tailscale 未开启 HTTPS 证书", "HTTPS certificates are not enabled in Tailscale"),
                fix = tr(
                    "在 Tailscale 管理页 › DNS 中开启 HTTPS Certificates，然后关闭再重新开启公网接入。",
                    "Enable HTTPS Certificates under Tailscale Admin › DNS, then turn public access off and on again.",
                ),
            )
            text.contains("\"funnel\" node attribute not set") -> FunnelMessage(
                title = tr("Tailscale ACL 未开启 Funnel 权限", "Funnel is not enabled in Tailscale ACL"),
                fix = tr(
                    "在 Tailscale 管理页 › Access Controls 中为该设备开启 funnel 节点属性（nodeAttrs），然后重试。",
                    "Grant the \"funnel\" node attribute in Tailscale Admin › Access Controls for this device, then retry.",
                ),
            )
            text.contains("port 443 is not allowed") -> FunnelMessage(
                title = tr("Tailscale 不允许 443 端口开启 Funnel", "Port 443 is not allowed for Funnel"),
                fix = tr(
                    "在 Tailscale 管理页的 ACL / Funnel 配置中允许 443 端口，然后重试。",
                    "Allow port 443 in your Tailscale Admin ACL / Funnel policy, then retry.",
                ),
            )
            else -> FunnelMessage(
                title = tr("Funnel 启动失败", "Funnel failed to start"),
                fix = text + "\n" + tr("在 Tailscale 管理页确认 HTTPS 和 Funnel 已开启", "Confirm HTTPS and Funnel are enabled in Tailscale Admin"),
            )
        }
    }

    // ── Notifications (7.4) ────────────────────────────────────────────────

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(NOTE_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTE_CHANNEL,
                    tr("公网接入状态", "Public access status"),
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
            val title = when (funnelState) {
                "on" -> tr("公网接入已开启", "Public access is on")
                "cert" -> tr("公网接入正在申请证书", "Public access: fetching certificate")
                "error" -> tr("公网接入启动失败", "Public access failed to start")
                else -> tr("公网接入启动中", "Public access starting")
            }
            val body = when (funnelState) {
                "on" -> addr ?: tr("正在通过 Tailscale Funnel 提供公网 MCP 入口；不使用时可随时关闭。", "Serving public MCP via Tailscale Funnel; tap Off when not needed.")
                "cert" -> tr("正在申请 HTTPS 证书，首次约 30–60 秒。", "Requesting HTTPS certificate; first time takes ~30–60 seconds.")
                "error" -> Tailnet.status.value.funnelReason ?: tr("请打开 App 查看原因", "Open the app to inspect the error")
                else -> addr ?: tr("正在启动 Tailscale Funnel…", "Starting Tailscale Funnel…")
            }
            val n = NotificationCompat.Builder(ctx, NOTE_CHANNEL)
                .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(openPublicIntent(ctx))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, tr("关闭", "Off"), offPi)
                .build()
            nm.notify(NOTE_ON_ID, n)
        }
    }

    /** Called when tsbridge reports a new Funnel state so the ongoing notification reflects real cert/on readiness (A.3 / C1). */
    fun onFunnelStateChanged(ctx: Context, funnelState: String?) {
        if (enabled) showOngoingNotification(ctx.applicationContext, funnelState)
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
                .setContentTitle(tr("公网接入已自动关闭", "Public access turned off automatically"))
                .setContentText(tr("24 小时没有任何公网请求，已关闭 Funnel。需要时可在设置里重新开启。", "No public requests for 24 hours, so Funnel was turned off. Re-enable in Settings when needed."))
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
        lastActivityAt = 0L
        clock = { System.currentTimeMillis() }
        currentVersionCode = { BuildConfig.VERSION_CODE }
        postNotifications = false
        startServerOnEnable = true
        requestFunnelCheckAction = { Tailnet.requestFunnelCheck() }
        secret = newSecret()
    }
}
