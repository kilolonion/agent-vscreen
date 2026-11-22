package com.kilon.agentvscreen.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kilon.agentvscreen.core.BackgroundOperation

/**
 * 2.0.0-beta2: non-exported receiver for user-initiated notification button actions.
 *
 * [HostService] is exported (protected by `android.permission.DUMP`) so Gate running as uid 2000 can wake it via
 * `am start-foreground-service`. Because any Shizuku-granted app can act as uid 2000, [HostService] accepts only
 * a plain start and reads no actions or extras; every privileged user action from notifications lives here instead.
 */
class NoteActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        when (intent?.action) {
            HostService.ACTION_STOP, HostService.ACTION_PAUSE -> {
                // 1.4.0-beta4: the notification's button turns it off (the same as "Off" behind the power button): out of
                // the shade at once and not woken again.
                ServiceControl.block(app, "notification")
            }
            HostService.ACTION_ON -> ServiceControl.turnOn(app, "notification")
            HostService.ACTION_ALLOW -> BackgroundOperation.userResume()
            HostService.ACTION_SHOW_WINDOW -> BackgroundOperation.showWindow()
            HostService.ACTION_HAND_BACK -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                val pending = runCatching { goAsync() }.getOrNull()
                Thread({
                    try {
                        com.kilon.agentvscreen.core.VScreenOwnership.handBack(app, pkg, "notification")
                    } finally {
                        runCatching { pending?.finish() }
                    }
                }, "vscreen-handback").apply { isDaemon = true }.start()
            }
            Consent.ACTION -> Consent.answer(app, intent.getIntExtra("id", -1), intent.getBooleanExtra("allow", false))
        }
    }
}
