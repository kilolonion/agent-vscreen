package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.tr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject

/**
 * Is this process really kept alive while it sits in the background?
 *
 * Not a Minis question: inside Minis the screen lived in the agent app itself, which the user keeps in front. Here the
 * agent is in front and we are always in the background, so everything depends on two things the system may refuse:
 *  - the foreground service (a background start of it can be rejected; then nothing keeps the process running);
 *  - the vendor's own background manager (MagicOS / EMUI / MIUI …) may still freeze us. A frozen process keeps its
 *    listening socket, so the kernel accepts the agent's connection and the request just hangs until the user opens
 *    the app — the "a command takes tens of seconds / only finishes when I switch to the app" symptom.
 *
 * Freezes are measured, not guessed: a 1 s ticker compares [SystemClock.uptimeMillis] gaps (uptime stops in deep
 * sleep, so a sleeping phone does not count; a frozen process with the CPU running does).
 *
 * 1.1.6: only freezes while the screen is on count against the setup ("screen-on freezes"). Vendor ROMs freeze even
 * allowed apps for a while after the screen goes off (screen-off power saving); nobody is driving the phone then, and
 * counting those made 系统后台管理 light up again every time the user came back to the app.
 */
object Liveness {
    @Volatile var foreground = false; private set
    @Volatile var foregroundError: String? = null; private set
    @Volatile var freezes = 0; private set
    @Volatile var lastFreezeAt = 0L; private set        // elapsedRealtime
    @Volatile var lastFreezeMs = 0L; private set
    @Volatile var longestFreezeMs = 0L; private set
    @Volatile var lastFreezeWall = 0L; private set     // currentTimeMillis
    /** 1.1.6: freezes while the screen stayed on (these hang a user's agent; screen-off ones do not count). */
    @Volatile var screenOnFreezes = 0; private set
    @Volatile var lastScreenOnFreezeWall = 0L; private set
    @Volatile var lastScreenOnFreezeMs = 0L; private set
    /** Screen-on freezes since the user last opened the background settings page (or since start). */
    @Volatile var screenOnFreezesSinceVisit = 0; private set
    @Volatile private var pm: android.os.PowerManager? = null
    @Volatile private var app: Context? = null
    fun init(ctx: Context) { app = ctx.applicationContext; pm = ctx.getSystemService(android.os.PowerManager::class.java) }
    private fun interactive(): Boolean = runCatching { pm?.isInteractive }.getOrNull() ?: true
    private var ticker: Thread? = null

    fun foregroundOk() { foreground = true; foregroundError = null }
    fun foregroundFailed(why: String) {
        foreground = false; foregroundError = why
        AppLogger.warning("Liveness", "not a foreground service: $why")
    }
    fun serviceGone() { foreground = false }

    @Synchronized fun start() {
        if (ticker?.isAlive == true) return
        ticker = Thread({
            var last = SystemClock.uptimeMillis()
            var wasOn = interactive()
            while (true) {
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { return@Thread }
                val now = SystemClock.uptimeMillis()
                val gap = now - last - 1_000
                last = now
                val on = interactive()
                if (gap >= 3_000) {
                    freezes++; lastFreezeMs = gap; lastFreezeAt = SystemClock.elapsedRealtime(); lastFreezeWall = System.currentTimeMillis()
                    if (gap > longestFreezeMs) longestFreezeMs = gap
                    // On before and after the gap: the user was using the phone while we were frozen.
                    val screenOn = wasOn && on
                    if (screenOn) { screenOnFreezes++; screenOnFreezesSinceVisit++; lastScreenOnFreezeMs = gap; lastScreenOnFreezeWall = lastFreezeWall }
                    AppLogger.warning("Liveness", "process was frozen for ${gap / 1000}s (${if (screenOn) "screen on" else "screen off: ignored for setup"})")
                }
                wasOn = on
            }
        }, "vscreen-liveness").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.also { it.start() }
    }

    private fun visitedAt(): Long = app?.let { prefs(it).getLong("vendor_bg_visited", 0L) } ?: 0L

    /**
     * The one answer to "is the background setting still a problem?", shared by the home banner, the setup item, the
     * notification and the agent's degraded list: a screen-on freeze measured after the user last went to the
     * background settings page. Going there clears it at once; only a new screen-on freeze brings it back.
     * (1.1.7 fix: the banner used a separate 10-minute window and so stayed up after the user had fixed the setting,
     * while the setup item already showed it done.)
     */
    fun frozenUnresolved(): Boolean = screenOnFreezes > 0 && lastScreenOnFreezeWall > visitedAt()

    /** What the user should know, or null when all is well. */
    fun problem(): String? = when {
        !HostService.isAlive -> null
        !foreground -> run { val why = foregroundError ?: tr("原因未知", "unknown reason"); tr("服务没能成为前台服务（$why），切到后台后系统会冻结或杀掉它。打开一次本应用会自动补上。", "The service could not become a foreground service ($why), so the system freezes or kills it in the background. Opening this app once fixes it.") }
        frozenUnresolved() -> tr("亮屏时系统在后台冻结过本应用 $screenOnFreezesSinceVisit 次（最近一次 ${lastScreenOnFreezeMs / 1000} 秒）：冻结期间 agent 的命令会一直等，直到你打开本应用。", "With the screen on, the system froze this app in the background $screenOnFreezesSinceVisit times (last time ${lastScreenOnFreezeMs / 1000} s): while frozen, the agent's commands wait until you open this app.") +
            tr("请在系统的后台管理里把 Agent-VScreen 设为“允许后台活动”。", "Set Agent-VScreen to \"allow background activity\" in the system's background management.")
        else -> null
    }

    fun json(): JSONObject = JSONObject()
        .put("foreground_service", foreground)
        .put("foreground_error", foregroundError ?: JSONObject.NULL)
        .put("frozen_count", freezes)
        .put("last_frozen_s", if (freezes > 0) lastFreezeMs / 1000 else JSONObject.NULL)
        .put("last_frozen_ago_s", if (freezes > 0) (SystemClock.elapsedRealtime() - lastFreezeAt) / 1000 else JSONObject.NULL)
        .put("longest_frozen_s", if (freezes > 0) longestFreezeMs / 1000 else JSONObject.NULL)
        .put("frozen_screen_on_count", screenOnFreezes)
        .put("frozen_unresolved", frozenUnresolved())
        .put("keep_alive_window", Presence.up)
        .put("pid", android.os.Process.myPid())

    /**
     * Open the vendor page where an app is allowed to keep running in the background (launch manager / autostart);
     * fall back to the app's own settings page. Returns what was opened.
     */
    private val PAGES get() = listOf(
            tr("荣耀 应用启动管理", "Honor App launch") to ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            tr("华为 应用启动管理", "Huawei App launch") to ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            tr("小米 自启动管理", "Xiaomi Autostart") to ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            tr("OPPO 自启动", "OPPO Auto-launch") to ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            tr("vivo 后台高耗电", "vivo High background power use") to ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
        )

    /** This phone's own background manager page (Honor / Huawei / Xiaomi / OPPO / vivo …), or null on stock Android. */
    fun vendorPage(ctx: Context): String? = PAGES.firstOrNull { (_, cn) ->
        runCatching { ctx.packageManager.getActivityInfo(cn, 0); true }.getOrDefault(false)
    }?.first

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("agent_vscreen", Context.MODE_PRIVATE)

    /**
     * The vendor page cannot be read back, so the setup item counts as done once the user went there — and stops
     * counting as soon as a freeze is measured after that (the setting did not take, or was reset).
     */
    fun vendorSetupDone(ctx: Context): Boolean {
        if (app == null) init(ctx)
        return visitedAt() > 0L && !frozenUnresolved()
    }

    fun openBackgroundSettings(ctx: Context): String {
        prefs(ctx).edit().putLong("vendor_bg_visited", System.currentTimeMillis()).commit()
        screenOnFreezesSinceVisit = 0
        for ((label, cn) in PAGES) {
            val i = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return label
        }
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return tr("应用信息（在“电池”里选“不限制 / 允许后台活动”）", "App info (under Battery choose \"Unrestricted / allow background activity\")")
    }
}
