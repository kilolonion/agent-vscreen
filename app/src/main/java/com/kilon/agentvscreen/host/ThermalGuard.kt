package com.kilon.agentvscreen.host

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

internal object ThermalGuard {
    private lateinit var app: Context
    private val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    @Volatile private var policy = HeatPolicy(false)
    @Volatile private var ready = false
    @Volatile private var cleaning = false
    @Volatile private var persisted = false
    private val pref get() = app.getSharedPreferences("thermal_safety_v1", Context.MODE_PRIVATE)
    val locked get() = policy.locked
    val limited get() = policy.limited
    fun reason() = tr("过热保护：后台任务已停止。电池低于 39°C 且系统降温 3 分钟后，在首页电源菜单手动恢复。", "Overheat protection: work stopped. After 3 min below 39°C with low system thermal status, resume in the Home power menu.")
    private fun temperature(i: Intent?): Int? = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it in -400..1500 }
    private fun battery() = runCatching { app.registerReceiver(null, filter) }.getOrNull()
    private fun status() = runCatching { app.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull()?.takeIf { it in 0..6 }
    fun init(ctx: Context) {
        app = ctx.applicationContext
        persisted = pref.getBoolean("locked", false); policy = HeatPolicy(persisted)
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { sample(temperature(i), status()) }
        }
        @Suppress("UnspecifiedRegisterReceiverFlag") // protected, system-only BATTERY_CHANGED
        val sticky = app.registerReceiver(rx, filter)
        sample(temperature(sticky), status())
        runCatching { app.getSystemService(PowerManager::class.java)?.addThermalStatusListener { s -> sample(temperature(battery()), s.takeIf { it in 0..6 }) } }
    }
    /** Called only after the background-operation store and rule receiver are initialized. */
    @Synchronized fun runtimeReady() { ready = true; if (locked) stopWork() }
    @Synchronized private fun sample(t: Int?, s: Int?) {
        val wasLimited = limited
        val trip = policy.observe(t, s, SystemClock.elapsedRealtime())
        if (locked && !persisted) persisted = pref.edit().putBoolean("locked", true).commit()
        if (ready && !wasLimited && limited) VScreenAwake.suspendForHeat()
        if (ready && trip) stopWork()
    }
    private fun stopWork() {
        if (cleaning) return
        cleaning = true
        ServiceControl.now(app)
        quiet("heat-note") {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return@quiet
            nm.createNotificationChannel(NotificationChannel("heat_guard", tr("过热保护", "Thermal protection"), NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(app, 7910, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(7910, NotificationCompat.Builder(app, "heat_guard").setSmallIcon(R.drawable.ic_stat).setContentTitle(tr("Agent-VScreen 已因过热停止", "Agent-VScreen stopped due to heat")).setContentText(reason()).setStyle(NotificationCompat.BigTextStyle().bigText(reason())).setContentIntent(open).setOnlyAlertOnce(true).build())
        }
        val active = HostService.isAlive || BackgroundOperation.currentDisplay() != null
        // The main handler never waits for Binder, OCR or display teardown. If native work won't unwind,
        // kill only this app's process, only after the latch is durable (no hot crash/restart loop).
        if (active) Thread({
            Thread.sleep(15_000)
            if (locked && persisted && (cleaning || com.kilon.agentvscreen.core.VScreenPaddleOcr.busy() || com.kilon.agentvscreen.core.OnnxRuntime.computing() || com.kilon.agentvscreen.cmd.Tasks.running() > 0)) android.os.Process.killProcess(android.os.Process.myPid())
        }, "vscreen-heat-deadline").apply { isDaemon = true; start() }
        Thread({
            try {
                quiet("heat-cancel") { com.kilon.agentvscreen.cmd.Tasks.cancelForHeat() }
                quiet("heat-awake") { VScreenAwake.suspendForHeat() }
                quiet("heat-rules") { VScreenRules.discardForHeat(reason()) }
                quiet("heat-screen") { BackgroundOperation.stopAndRelease(reason()) }
                quiet("heat-server") { HostService.dropServer() }
                quiet("heat-host") { HostService.stop(app) }
                quiet("heat-alarms") { Wake.arm(app) }
            } finally { cleaning = false }
        }, "vscreen-thermal-stop").apply { isDaemon = true; start() }
    }
    /** Called exclusively by a deliberate local power-sheet selection. Never by boot/wake/HTTP. */
    @Synchronized fun resumeFromPowerSheet(): Boolean {
        if (!locked) return true
        sample(temperature(battery()), status())
        if (cleaning || !policy.resume(SystemClock.elapsedRealtime())) return false
        if (!pref.edit().putBoolean("locked", false).commit()) { policy = HeatPolicy(true); persisted = false; return false }
        persisted = false
        app.getSystemService(NotificationManager::class.java)?.cancel(7910)
        quiet("heat-rearm") { Wake.arm(app) }; return true
    }
}
