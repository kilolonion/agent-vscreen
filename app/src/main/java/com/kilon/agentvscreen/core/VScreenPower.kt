package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.tr

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.ShizukuManager
import com.kilon.agentvscreen.host.HostService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps Agent-VScreen's network and process alive while it is in the background.
 *
 * On Honor/Huawei and other OEM ROMs an app that is not exempt gets its sockets cut a few seconds after it leaves the
 * screen, even while its foreground service runs: a remote agent's request then fails with "Software caused connection
 * abort" or "Failed to connect".
 *
 * [check] reads the system's view; [whitelist] uses Shizuku (uid 2000) to grant the same exemptions a user would grant
 * by hand (called through host.Heal). [ensureOnce] runs it once per process as soon as Shizuku is ready.
 */
internal object VScreenPower {
    private const val TAG = "VScreenPower"
    @Volatile var lastFix: String? = null; private set

    data class Check(
        val batteryExempt: Boolean,
        val backgroundRestricted: Boolean,
        /** ConnectivityManager.RESTRICT_BACKGROUND_STATUS_*: 1 disabled, 2 whitelisted, 3 enabled (blocked). */
        val dataSaver: Int,
        val standbyBucket: Int,
        val foregroundServiceAlive: Boolean,
    ) {
        val dataBlocked get() = dataSaver == 3
        val bucketRestricted get() = standbyBucket >= 45 // RARE(40) still has network; RESTRICTED(45) does not
        val ok get() = batteryExempt && !backgroundRestricted && !dataBlocked && !bucketRestricted
        fun problems(): List<String> = buildList {
            if (!batteryExempt) add(tr(S.vscreen_power_not_exempt_from_battery_optimization))
            if (backgroundRestricted) add(tr(S.vscreen_power_background_activity_restricted_by_the))
            if (dataBlocked) add(tr(S.vscreen_power_data_saver_blocks_background_data))
            if (bucketRestricted) add(tr(S.vscreen_power_app_standby_bucket_is_restricted, standbyBucket))
        }
        fun json(): JSONObject = JSONObject()
            .put("ok", ok)
            .put("battery_optimization_exempt", batteryExempt)
            .put("background_restricted", backgroundRestricted)
            .put("data_saver", when (dataSaver) { 1 -> "off"; 2 -> "allowed"; 3 -> "blocked"; else -> "unknown" })
            .put("standby_bucket", standbyBucket)
            .put("agent_foreground_service", foregroundServiceAlive)
            .put("problems", JSONArray(problems()))
            .also { o -> lastFix?.let { o.put("last_fix", it) } }
    }

    fun check(ctx: Context): Check {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        return Check(
            batteryExempt = runCatching { (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(true),
            backgroundRestricted = runCatching { am?.isBackgroundRestricted == true }.getOrDefault(false),
            dataSaver = runCatching { cm?.restrictBackgroundStatus ?: 0 }.getOrDefault(0),
            standbyBucket = runCatching { usm?.appStandbyBucket ?: 0 }.getOrDefault(0),
            foregroundServiceAlive = HostService.isAlive,
        )
    }

    /** Grants the background exemptions through Shizuku. Returns (all-ok-after, what was done). */
    fun whitelist(ctx: Context): Pair<Boolean, String> {
        if (!com.kilon.agentvscreen.host.Privileged.isReady()) return false to tr(S.vscreen_power_unavailable, com.kilon.agentvscreen.host.Privileged.name())
        val pkg = ctx.packageName
        val uid = ctx.applicationInfo.uid.toString()
        val steps = listOf(
            tr(S.vscreen_power_battery_exemption) to arrayOf("dumpsys", "deviceidle", "whitelist", "+$pkg"),
            tr(S.vscreen_power_background_running) to arrayOf("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow"),
            tr(S.vscreen_power_background_start) to arrayOf("cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow"),
            tr(S.vscreen_power_background_data_under_data_saver) to arrayOf("cmd", "netpolicy", "add", "restrict-background-whitelist", uid),
            tr(S.vscreen_power_standby_bucket_set_to_active) to arrayOf("am", "set-standby-bucket", pkg, "active"),
        )
        val done = mutableListOf<String>(); val failed = mutableListOf<String>()
        for ((label, argv) in steps) {
            val r = runCatching { VScreenShell.run(argv, 6_000L) }.getOrNull()
            if (r != null && r.exitCode == 0) done += label else failed += label
        }
        val after = check(ctx)
        val msg = buildString {
            if (done.isNotEmpty()) append(tr(S.vscreen_power_set) + done.joinToString(tr(S.automation_list_sep)))
            if (failed.isNotEmpty()) { if (isNotEmpty()) append(tr(S.agent_guide_sep)); append(tr(S.vscreen_power_failed) + failed.joinToString(tr(S.automation_list_sep))) }
            if (!after.ok) append(tr(S.vscreen_power_still) + after.problems().joinToString(tr(S.automation_list_sep)))
        }
        lastFix = msg
        AppLogger.info(TAG, "background whitelist: $msg")
        return after.ok to msg
    }

    /** Kept for callers: now the same self-healing as [heal], in the background. Never throws. */
    fun ensureOnce(ctx: Context) {
        if (!com.kilon.agentvscreen.host.Privileged.isReady()) return
        Thread { heal(ctx, "status") }.apply { isDaemon = true; name = "vscreen-power" }.start()
    }

    /** How often the system took back an exemption we had granted (vendor power managers do; Honor's on each open). */
    @Volatile var resets = 0; private set
    @Volatile var lastResetWhat: String? = null; private set
    @Volatile private var lastHealAt = 0L
    @Volatile private var everOk = false

    /**
     * 1.1.6 root fix for "电池优化和后台运行 shows as not set every time I open the app": these exemptions are not the
     * user's to keep re-granting. Some ROMs take them back (on reboot, after a while, or when their own manager
     * re-evaluates the app), and 1.1.5 re-granted only once per process, so the home page kept asking the user. Now
     * every check that finds one missing grants it again through Shizuku (at most every 20 s), counts the reset, and the
     * setup item only asks the user when Shizuku cannot fix it. Returns the state after healing. Never throws.
     */
    fun heal(ctx: Context, why: String): Check {
        val before = runCatching { check(ctx) }.getOrNull() ?: return check(ctx)
        if (before.ok) { everOk = true; return before }
        if (!com.kilon.agentvscreen.host.Privileged.isReady()) return before
        synchronized(this) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastHealAt < 20_000L) return check(ctx)
            lastHealAt = now
            if (everOk) { resets++; lastResetWhat = before.problems().joinToString(tr(S.automation_list_sep)) }
        }
        val (ok, msg) = runCatching { whitelist(ctx) }.getOrElse { false to (it.message ?: tr(S.fullscreen_activity_failed)) }
        if (ok) everOk = true
        AppLogger.info(TAG, "heal ($why): missing ${before.problems().joinToString(tr(S.automation_list_sep))} -> ${if (ok) "fixed" else "still missing"}: $msg" +
            if (resets > 0) " (system took it back $resets times)" else "")
        return check(ctx)
    }

    /** What the user does by hand on this phone when Shizuku is not available. */
    fun manualSteps(): String = when (Vendor.device.vendor.lowercase()) {
        "honor", "huawei" -> tr(S.vscreen_power_phone_manager_app_launch_agent)
        "xiaomi", "redmi" -> tr(S.vscreen_power_settings_apps_manage_apps_agent)
        "vivo", "iqoo" -> tr(S.vscreen_power_i_manager_app_manager_permissions)
        "oppo", "oneplus", "realme" -> tr(S.vscreen_power_settings_battery_app_battery_management)
        else -> tr(S.vscreen_power_settings_apps_agent_vscreen_battery)
    }
}
