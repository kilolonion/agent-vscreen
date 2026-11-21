package com.kilon.agentvscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kilon.agentvscreen.host.HostService
import java.io.File

/**
 * 1.4.2, debug builds only: lets the CI emulator tests (tools/android-smoke/agent_e2e.py) reach the app the way an agent
 * does, without tapping through the UI to copy the token. `adb shell am broadcast -a com.kilon.agentvscreen.CI_SEED`
 * turns the service on and writes "<main token>\n<port>" to files/ci.txt, read with `run-as` (debug builds are debuggable).
 * Release builds have neither this class nor its receiver (src/debug), and only the shell may send it (DUMP).
 */
class CiHook : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                // 1.4.2-beta3: `--es via shizuku|root` switches the way in, as the setup / settings choice does.
                intent.getStringExtra("via")?.let { v ->
                    com.kilon.agentvscreen.host.Privileged.Via.entries.firstOrNull { it.key == v }?.let { com.kilon.agentvscreen.host.Privileged.choose(app, it) }
                    return@Thread
                }
                // 1.4.2-beta6: `--es control takeover|handback|stop|resume|quiet:<ms>` does what the user's buttons do
                // (take over the agent's screen, hand it back, Stop, allow again) or sets the turn's quiet window, so
                // the emulator tests can check takeover / Stop timing. Answers "done <state>" in files/ci-control.txt.
                intent.getStringExtra("control")?.let { what ->
                    val bo = com.kilon.agentvscreen.core.BackgroundOperation
                    when {
                        what == "takeover" -> bo.state.value.ownerSessionId?.let { bo.beginTakeover(it) }
                        what == "handback" || what == "resume" -> bo.userResume()
                        what == "stop" -> bo.userStop()
                        what.startsWith("quiet:") -> what.removePrefix("quiet:").toLongOrNull()?.let { com.kilon.agentvscreen.api.Turns.quietMs = it }
                    }
                    val st = bo.state.value
                    File(app.filesDir, "ci-control.txt").writeText("done $what takeover=${st.takeoverSessionId} stopped=${st.userStopped}\n")
                    return@Thread
                }
                com.kilon.agentvscreen.host.Prefs.setServiceOn(app, true)
                HostService.start(app)
                // The main token's text (the token list keeps only its hash). Written at once, then again with the port.
                val token = com.kilon.agentvscreen.api.ApiConfig.token
                val out = File(app.filesDir, "ci.txt")
                out.writeText("$token\n0\n")
                var port = 0
                repeat(120) { if (port == 0) { port = HostService.server?.boundPort ?: 0; if (port == 0) Thread.sleep(500) } }
                out.writeText("$token\n$port\n")
            } finally { pending.finish() }
        }, "vscreen-ci-seed").start()
    }
}
