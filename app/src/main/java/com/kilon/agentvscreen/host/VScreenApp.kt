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
        StrongConfirm.init(this)
        StrongConfirm.migrateIfNeeded(this, com.kilon.agentvscreen.api.Tokens.list.value)
        com.kilon.agentvscreen.core.TaskLog.init(this)
        com.kilon.agentvscreen.api.EventHub.init(this)
        com.kilon.agentvscreen.api.AgentLedger.init(this)
        Privileged.init(this)
        BackgroundOperation.init(this)
        val st = ServiceControl.now(this)
        val alreadyUp = HostService.server?.let { it.running || it.starting } == true
        if (shouldStartServerEarly(st, Privileged.isReady(), alreadyUp)) {
            // 2.0.0-beta2: start the shared HostService.ensureServer instance off the main thread in parallel
            // with HostService.onCreate() so Gate receives gateStart faster on cold wake.
            val app = applicationContext
            Thread({
                quiet("server-early") {
                    if (ServiceControl.now(app) == ServiceControl.State.ON) {
                        HostService.ensureServer(app)
                    }
                }
            }, "vscreen-early-server").apply { isDaemon = true }.start()
        }
        if (st == ServiceControl.State.ON) HostService.start(this)
        // 1.4.0-beta5: on demand, listen from the start: an agent's request wakes the service (HostService.wakeFor).
        else if (st == ServiceControl.State.HIDDEN) quiet("server-on-demand") { HostService.ensureServer(this) }
        // 1.4.2: the phone's app list, read once in the background (pickers, sound page, launch by name).
        com.kilon.agentvscreen.core.AppCatalog.init(this)
        quiet("wake-arm") { Wake.arm(this) }
    }

    companion object {
        /**
         * 2.0.0-beta2: whether [VScreenApp.onCreate] may pre-start the shared [ApiServer] + `gateStart`
         * off the main thread before [HostService.onCreate] runs. Never starts when [ServiceControl.State.BLOCKED],
         * when privileged access is not ready, or when an instance is already running / starting.
         */
        internal fun shouldStartServerEarly(
            state: ServiceControl.State,
            privilegedReady: Boolean,
            serverRunningOrStarting: Boolean,
        ): Boolean = state == ServiceControl.State.ON && privilegedReady && !serverRunningOrStarting
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
