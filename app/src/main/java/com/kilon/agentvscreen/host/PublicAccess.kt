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

    data class FunnelMessage(val title: String, val fix: String)

    private var prefs: SharedPreferences? = null
    private val rng = SecureRandom()
    private val _enabled = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabled.asStateFlow()
    val enabled: Boolean get() = _enabled.value

    @Volatile var secret: String = newSecret(); private set
    @Volatile var server: PublicServer? = null; private set
    @Volatile var lastActivityAt: Long = 0L; private set
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile internal var currentVersionCode: () -> Int = { BuildConfig.VERSION_CODE }
    @Volatile internal var postNotifications: Boolean = true
    @Volatile internal var startServerOnEnable: Boolean = true

    val boundPort: Int get() = server?.boundPort?.takeIf { it > 0 } ?: PublicServer.DEFAULT_PORT

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        PublicOAuth.init(ctx)
        val savedEnabled = p.getBoolean(KEY_ENABLED, false)
        val savedVersion = p.getInt(KEY_VERSION_CODE, -1)
        val nowVersion = currentVersionCode()
        lastActivityAt = p.getLong(KEY_LAST_ACTIVITY, 0L)
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
        server?.stop()
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

    private fun showOngoingNotification(ctx: Context) {
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
            val body = addr ?: tr("正在通过 Tailscale Funnel 提供公网 MCP 入口；不使用时可随时关闭。", "Serving public MCP via Tailscale Funnel; tap Off when not needed.")
            val n = NotificationCompat.Builder(ctx, NOTE_CHANNEL)
                .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
                .setContentTitle(tr("公网接入已开启", "Public access is on"))
                .setContentText(body)
                .setContentIntent(openPublicIntent(ctx))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, tr("关闭", "Off"), offPi)
                .build()
            nm.notify(NOTE_ON_ID, n)
        }
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
        lastActivityAt = 0L
        clock = { System.currentTimeMillis() }
        currentVersionCode = { BuildConfig.VERSION_CODE }
        postNotifications = false
        startServerOnEnable = true
        secret = newSecret()
    }
}
