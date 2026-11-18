package com.kilon.agentvscreen.host

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.core.BackgroundOperation

class VScreenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 1.4.0-beta5: shares of the screen in steps (`50% 80%`) become pixels of the screen the agent is using.
        com.kilon.agentvscreen.cmd.Steps.frame = {
            runCatching { BackgroundOperation.state.value.ownerSessionId?.let { BackgroundOperation.previewDisplayFor(it) }?.let { it.width to it.height } }.getOrNull()
        }
        AppLogger.init(this)
        AppFront.init(this)
        ExitInfo.init(this)
        com.kilon.agentvscreen.I18n.init(this)
        ApiConfig.init(this)
        com.kilon.agentvscreen.api.Tokens.init(this)
        Audit.init(this)
        com.kilon.agentvscreen.core.TaskLog.init(this)
        com.kilon.agentvscreen.api.EventHub.init(this)
        com.kilon.agentvscreen.api.AgentLedger.init(this)
        ShizukuManager.init(this)
        BackgroundOperation.init(this)
        if (Prefs.serviceOn(this)) HostService.start(this)
        // 1.4.0-beta5: on demand, listen from the start: an agent's request wakes the service (HostService.wakeFor).
        else if (!Prefs.blocked(this)) quiet("server-on-demand") { HostService.ensureServer(this) }
        quiet("wake-arm") { Wake.arm(this) }
    }
}

/** Boot / app update: bring the service back when the user left it on. (Shizuku itself may still need a manual start.) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.serviceOn(context)) HostService.start(context)
        quiet("wake-arm-boot") { Wake.arm(context.applicationContext) }
    }
}
