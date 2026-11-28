package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.kilon.agentvscreen.R
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenAwake
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.tr
import com.kilon.agentvscreen.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Locale

internal object ThermalGuard {
    private const val PREF_NAME = "thermal_safety_v1"
    private const val K_LOCKED = "locked"
    private const val K_STRICT_UNLOCK = "strict_unlock"
    private const val K_LAST_RESUMED_WALL = "last_resumed_wall_ms"
    private const val K_HARD_STOP_ENABLED = "heat_stop_enabled"
    private const val NOTE_ID_LOCK = 7910
    private const val NOTE_ID_WARN = 7911

    @Volatile private var appOrNull: Context? = null
    private val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    @Volatile private var policy = HeatPolicy(false)
    @Volatile private var ready = false
    @Volatile private var cleaning = false
    @Volatile private var persisted = false
    private val _hardStopEnabled = MutableStateFlow(true)
    val hardStopEnabled: StateFlow<Boolean> = _hardStopEnabled.asStateFlow()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val locked get() = policy.locked
    val limited get() = policy.limited
    val ocrPaused get() = policy.ocrPaused

    /** For pure code paths run in unit tests, where the guard was never set up. */
    fun limitedOrFalse(): Boolean = runCatching { limited }.getOrDefault(false)
    fun ocrPausedOrFalse(): Boolean = runCatching { ocrPaused }.getOrDefault(false)

    private fun temperature(i: Intent?): Int? =
        i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it in -400..1500 }

    private fun battery(ctx: Context) = runCatching { ctx.registerReceiver(null, filter) }.getOrNull()
    private fun status(ctx: Context) = runCatching { ctx.getSystemService(PowerManager::class.java)?.currentThermalStatus }
        .getOrNull()?.takeIf { it in 0..6 }

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        appOrNull = app
        val p = prefs(app)
        val enabled = p.getBoolean(K_HARD_STOP_ENABLED, true)
        _hardStopEnabled.value = enabled
        val wasLocked = p.getBoolean(K_LOCKED, false) && enabled
        val wasStrict = p.getBoolean(K_STRICT_UNLOCK, false) && wasLocked
        val lastResumedWall = p.getLong(K_LAST_RESUMED_WALL, 0L)
        val monoNow = SystemClock.elapsedRealtime()
        val wallNow = System.currentTimeMillis()
        val restoredResumedMono = if (lastResumedWall > 0L && wallNow >= lastResumedWall && (wallNow - lastResumedWall) < HeatPolicy.RECURRENCE_WINDOW_MS) {
            monoNow - (wallNow - lastResumedWall)
        } else null
        persisted = wasLocked
        policy = HeatPolicy(
            initiallyLocked = wasLocked,
            hardStopEnabled = enabled,
            lastResumedAt = restoredResumedMono,
            initiallyStrictUnlock = wasStrict,
        )
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { sample(temperature(i), status(app)) }
        }
        @Suppress("UnspecifiedRegisterReceiverFlag") // protected, system-only BATTERY_CHANGED
        val sticky = app.registerReceiver(rx, filter)
        sample(temperature(sticky), status(app))
        runCatching {
            app.getSystemService(PowerManager::class.java)?.addThermalStatusListener { s ->
                sample(temperature(battery(app)), s.takeIf { it in 0..6 })
            }
        }
    }

    /** Called only after the background-operation store and rule receiver are initialized. */
    @Synchronized fun runtimeReady() { ready = true; if (locked) stopWork() }

    /** Force an immediate sensor sample when initialized (safe no-op before [init]). */
    @Synchronized fun sampleNow() {
        val app = appOrNull ?: return
        sample(temperature(battery(app)), status(app))
    }

    @Synchronized private fun sample(t: Int?, s: Int?) {
        val wasLimited = limited
        val trip = policy.observe(t, s, SystemClock.elapsedRealtime())
        val app = appOrNull
        if (locked && !persisted && app != null) {
            persisted = prefs(app).edit()
                .putBoolean(K_LOCKED, true)
                .putBoolean(K_STRICT_UNLOCK, policy.strictUnlock)
                .commit()
        }
        if (ready && !wasLimited && limited) VScreenAwake.suspendForHeat()
        if (ready && trip) stopWork()
        if (policy.consumeDangerAlert()) notifyDangerWarning()
    }

    @Synchronized fun setHardStopEnabled(enabled: Boolean) {
        val app = appOrNull
        if (app != null) sample(temperature(battery(app)), status(app))
        _hardStopEnabled.value = enabled
        val res = policy.setHardStopEnabled(enabled, SystemClock.elapsedRealtime())
        if (app != null) {
            val ed = prefs(app).edit().putBoolean(K_HARD_STOP_ENABLED, enabled)
            if (res.unlocked) {
                ed.putBoolean(K_LOCKED, false).putBoolean(K_STRICT_UNLOCK, false)
                persisted = false
            } else if (res.tripped) {
                ed.putBoolean(K_LOCKED, true).putBoolean(K_STRICT_UNLOCK, policy.strictUnlock)
                persisted = true
            }
            ed.commit()
            if (res.unlocked) {
                app.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID_LOCK)
                quiet("heat-unlock-rearm") { Wake.arm(app) }
                ServiceControl.now(app)
            } else if (res.tripped && ready) {
                stopWork()
            }
        }
    }

    fun snapshot(): HeatPolicy.Snapshot {
        sampleNow()
        return policy.snapshot(SystemClock.elapsedRealtime())
    }

    internal fun thermalStatusName(code: Int?): String = when (code) {
        0 -> tr(S.thermal_guard_none)
        1 -> tr(S.thermal_guard_light)
        2 -> tr(S.thermal_guard_moderate)
        3 -> tr(S.thermal_guard_severe)
        4 -> tr(S.thermal_guard_critical)
        5 -> tr(S.thermal_guard_emergency)
        6 -> tr(S.thermal_guard_shutdown)
        else -> tr(S.thermal_guard_unknown)
    }

    private fun phaseLabel(phase: HeatPolicy.Phase): String = when (phase) {
        HeatPolicy.Phase.NORMAL -> tr(S.thermal_guard_normal)
        HeatPolicy.Phase.LOCKED_HOT -> tr(S.thermal_guard_locked_hot)
        HeatPolicy.Phase.COOLING -> tr(S.thermal_guard_cooling)
        HeatPolicy.Phase.READY -> tr(S.thermal_guard_ready_to_resume)
    }

    private fun fmtC(tenths: Int?): String =
        if (tenths == null) tr(S.thermal_guard_unknown) else String.format(Locale.ROOT, "%.1f°C", tenths / 10.0)

    internal fun formatReason(s: HeatPolicy.Snapshot): String {
        val tempStr = fmtC(s.tempTenths)
        val stStr = thermalStatusName(s.status)
        val unlockC = fmtC(s.unlockTempTenths)
        val statusLimit = if (s.unlockMaxStatusExclusive <= HeatPolicy.MODERATE_STATUS) tr(S.thermal_guard_moderate) else tr(S.thermal_guard_severe)
        val head = tr(S.thermal_guard_overheat_protection_battery_thermal_stat, phaseLabel(s.phase), tempStr, stStr)
        return when (s.phase) {
            HeatPolicy.Phase.READY -> head + tr(S.thermal_guard_cooling_requirements_met_resume_manually)
            HeatPolicy.Phase.LOCKED_HOT -> head + tr(S.thermal_guard_still_in_danger_zone_and, unlockC, statusLimit)
            HeatPolicy.Phase.COOLING -> {
                val missing = ArrayList<String>()
                if (s.tempTenths == null) missing += tr(S.thermal_guard_battery_temperature_reading)
                else if (s.tempTenths >= s.unlockTempTenths) missing += tr(S.thermal_guard_wait_for_battery_below, unlockC)
                if (s.status == null) missing += tr(S.thermal_guard_system_thermal_status_reading)
                else if (s.status >= s.unlockMaxStatusExclusive) missing += tr(S.thermal_guard_wait_for_thermal_status_below, statusLimit)
                val rem = s.retryAfterS
                when {
                    missing.isNotEmpty() -> head + tr(S.thermal_guard_not_ready_to_resume_then, missing.joinToString("，"), s.holdMs / 1000, missing.joinToString(", "))
                    rem != null && rem > 0 -> head + tr(S.thermal_guard_temperature_and_status_are_safe, rem)
                    else -> head + tr(S.thermal_guard_cooling_requirements_met_resume_manually)
                }
            }
            HeatPolicy.Phase.NORMAL -> tr(S.thermal_guard_thermal_protection_normal_battery_therma, tempStr, stStr)
        }
    }

    fun reason(): String = formatReason(snapshot())

    fun thermalJson(): JSONObject {
        val s = snapshot()
        return JSONObject()
            .put("battery_c", s.tempTenths?.let { it / 10.0 } ?: JSONObject.NULL)
            .put("status", s.status ?: JSONObject.NULL)
            .put("phase", s.phase.key)
            .put("hard_stop_enabled", s.hardStopEnabled)
            .put("retry_after_s", s.retryAfterS ?: JSONObject.NULL)
    }

    fun thermalErrorData(): JSONObject = JSONObject().put("thermal", thermalJson())

    fun throwStopped(): Nothing = throw BackgroundOperation.ActionError("THERMAL_STOP", reason(), thermalErrorData())

    private fun ensureChannel(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel("heat_guard", tr(S.settings_thermal_protection), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun notifyDangerWarning() {
        val app = appOrNull ?: return
        quiet("heat-warn-note") {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return@quiet
            ensureChannel(nm)
            val s = policy.snapshot(SystemClock.elapsedRealtime())
            val msg = tr(S.thermal_guard_battery_thermal_status_auto_stop, fmtC(s.tempTenths), thermalStatusName(s.status))
            val open = PendingIntent.getActivity(app, NOTE_ID_WARN, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(
                NOTE_ID_WARN,
                NotificationCompat.Builder(app, "heat_guard")
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(tr(S.thermal_guard_high_phone_temperature_warning))
                    .setContentText(msg)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun stopWork() {
        val app = appOrNull ?: return
        if (cleaning) return
        cleaning = true
        ServiceControl.now(app)
        val why = reason()
        quiet("heat-note") {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return@quiet
            ensureChannel(nm)
            val open = PendingIntent.getActivity(app, NOTE_ID_LOCK, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(
                NOTE_ID_LOCK,
                NotificationCompat.Builder(app, "heat_guard")
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(tr(S.thermal_guard_agent_vscreen_stopped_due_to))
                    .setContentText(why)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(why))
                    .setContentIntent(open)
                    .setOnlyAlertOnce(true)
                    .build(),
            )
        }
        val active = HostService.isAlive || BackgroundOperation.currentDisplay() != null
        // The main handler never waits for Binder, OCR or display teardown. If native work won't unwind,
        // kill only this app's process, only after the latch is durable (no hot crash/restart loop).
        if (active) Thread({
            Thread.sleep(15_000)
            if (locked && persisted && (cleaning || com.kilon.agentvscreen.core.VScreenPaddleOcr.busy() || com.kilon.agentvscreen.core.OnnxRuntime.computing() || com.kilon.agentvscreen.cmd.Tasks.running() > 0)) {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }, "vscreen-heat-deadline").apply { isDaemon = true; start() }
        Thread({
            try {
                quiet("heat-cancel") { com.kilon.agentvscreen.cmd.Tasks.cancelForHeat() }
                quiet("heat-awake") { VScreenAwake.suspendForHeat() }
                quiet("heat-rules") { VScreenRules.discardForHeat(why) }
                quiet("heat-screen") { BackgroundOperation.stopAndRelease(why) }
                quiet("heat-server") { HostService.dropServer() }
                quiet("heat-host") { HostService.stop(app) }
                quiet("heat-alarms") { Wake.arm(app) }
            } finally { cleaning = false }
        }, "vscreen-thermal-stop").apply { isDaemon = true; start() }
    }

    /** Called exclusively by a deliberate local power-sheet selection. Never by boot/wake/HTTP. */
    @Synchronized fun resumeFromPowerSheet(): Boolean {
        if (!locked) return true
        val app = appOrNull ?: return false
        sample(temperature(battery(app)), status(app))
        if (cleaning || !policy.resume(SystemClock.elapsedRealtime())) return false
        val ok = prefs(app).edit()
            .putBoolean(K_LOCKED, false)
            .putBoolean(K_STRICT_UNLOCK, false)
            .putLong(K_LAST_RESUMED_WALL, System.currentTimeMillis())
            .commit()
        if (!ok) {
            policy = HeatPolicy(initiallyLocked = true, hardStopEnabled = _hardStopEnabled.value)
            persisted = false
            return false
        }
        persisted = false
        app.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID_LOCK)
        quiet("heat-rearm") { Wake.arm(app) }
        return true
    }
}
