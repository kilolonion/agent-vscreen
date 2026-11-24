package com.kilon.agentvscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.core.VScreenRemoteDisplay
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.StrongConfirm
import org.json.JSONObject
import java.io.File

/**
 * 1.4.2, debug builds only: lets the CI emulator tests (tools/android-smoke/agent_e2e.py, gate.py, confirm_attack.py)
 * reach the app the way an agent does, without tapping through the UI to copy the token.
 * `adb shell am broadcast -a com.kilon.agentvscreen.CI_SEED` turns the service on and writes "<main token>\n<port>"
 * to files/ci.txt, read with `run-as` (debug builds are debuggable). Release builds have neither this class nor its
 * receiver (src/debug), and only the shell may send it (DUMP).
 */
class CiHook : BroadcastReceiver() {
    companion object {
        @Volatile private var l1TempVd: android.hardware.display.VirtualDisplay? = null
        @Volatile private var l1TempReader: android.media.ImageReader? = null
    }

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
                // 1.4.2-beta6: `--es control takeover|handback|stop|resume|quiet:<ms>` does what the user's buttons do.
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
                // 2.0.0-beta6 (6.8): `--es wait_ms <ms>` adjusts StrongConfirm wait duration for fast attack smoke runs.
                intent.getStringExtra("wait_ms")?.toLongOrNull()?.let { ms ->
                    StrongConfirm.waitMsOverride = if (ms <= 0L) null else ms
                    File(app.filesDir, "ci-wait.txt").writeText("wait_ms=$ms\n")
                    return@Thread
                }
                // 2.0.0-beta6 (6.8): `--es mk_token <name>:<scope>` creates a brand-new token with ZERO approvals.
                intent.getStringExtra("mk_token")?.let { spec ->
                    val name = spec.substringBefore(':').ifBlank { "ci-fresh" }
                    val scope = spec.substringAfter(':', "operate").ifBlank { "operate" }
                    val (t, secret) = Tokens.create(name, scope)
                    File(app.filesDir, "ci-token.txt").writeText("${t.id}\n$secret\n")
                    return@Thread
                }
                // 2.0.0-beta6 (6.8 / 6.10): debug-only hook letting confirm_attack.py invoke low-level service/a11y attacks
                // directly (display-0 injection, MOVE_TASK/FOCUS_TASK/REMOVE_TASK on display 0, a11y display-0 read).
                intent.getStringExtra("attack")?.let { what ->
                    val res = JSONObject().put("what", what)
                    when {
                        what == "inject0" -> {
                            val drag = VScreenRemoteDisplay.injectDrag(0, 540, 1200, 540, 1200, 0, 50)
                            val key = VScreenRemoteDisplay.injectKey(0, 4)
                            val txt = VScreenRemoteDisplay.injectText(0, "a")
                            val combo = VScreenRemoteDisplay.injectCombo(0, 29, listOf(113))
                            val ptr = VScreenRemoteDisplay.pointer(0, 0, 0, 540f, 1200f)
                            res.put("inject_drag", drag.getOrNull() ?: "err:${drag.exceptionOrNull()?.message}")
                                .put("inject_key", key.getOrNull() ?: "err:${key.exceptionOrNull()?.message}")
                                .put("inject_text", txt.getOrNull() ?: "err:${txt.exceptionOrNull()?.message}")
                                .put("inject_combo", combo.getOrNull() ?: "err:${combo.exceptionOrNull()?.message}")
                                .put("pointer", ptr.getOrNull()?.first ?: "err:${ptr.exceptionOrNull()?.message}")
                        }
                        what.startsWith("service_tasks:") -> {
                            val parts = what.removePrefix("service_tasks:").split(':')
                            val ownTask = parts.getOrNull(0)?.toIntOrNull() ?: -1
                            val vd = parts.getOrNull(1)?.toIntOrNull() ?: 1
                            val mv = VScreenRemoteDisplay.moveTask(ownTask, vd)
                            val foc0 = VScreenRemoteDisplay.focusTask(ownTask, 0)
                            val focNull = VScreenRemoteDisplay.focusTask(ownTask, null)
                            val rem0 = VScreenRemoteDisplay.removeTask(ownTask, 0)
                            res.put("move_own_to_vd", mv.getOrNull()?.first ?: "err:${mv.exceptionOrNull()?.message}")
                                .put("move_own_msg", mv.getOrNull()?.second ?: "")
                                .put("focus_d0", foc0.getOrNull() ?: "err:${foc0.exceptionOrNull()?.message}")
                                .put("focus_null", focNull.getOrNull() ?: "err:${focNull.exceptionOrNull()?.message}")
                                .put("remove_d0", rem0.getOrNull() ?: "err:${rem0.exceptionOrNull()?.message}")
                        }
                        what == "a11y_d0" -> {
                            val svc = com.kilon.agentvscreen.host.VScreenAccessibilityService.getInstance()
                            res.put("connected", svc != null)
                                .put("d0_roots", svc?.rootNodesForDisplay(0)?.size ?: -1)
                        }
                        what == "confirm_btn_bounds" -> {
                            val b = com.kilon.agentvscreen.ui.StrongConfirmActivity.liveButtonState
                            if (b != null) {
                                res.put("present", true)
                                    .put("id", b.requestId)
                                    .put("biometric", b.biometricMode)
                                    .put("cx", (b.left + b.right) / 2)
                                    .put("cy", (b.top + b.bottom) / 2)
                                    .put("bounds", "${b.left},${b.top},${b.right},${b.bottom}")
                            } else {
                                res.put("present", false)
                            }
                        }
                        what.startsWith("open_confirm:") -> {
                            val cid = what.removePrefix("open_confirm:").trim()
                            com.kilon.agentvscreen.ui.StrongConfirmActivity.open(app, cid)
                            res.put("opened", true).put("id", cid)
                        }
                        what == "finish_confirm" -> {
                            com.kilon.agentvscreen.ui.StrongConfirmActivity.finishLiveForCi()
                            res.put("closed", true)
                        }
                        what.startsWith("open_confirm_vd:") -> {
                            val parts = what.removePrefix("open_confirm_vd:").split(':')
                            val cid = parts.getOrNull(0).orEmpty()
                            val vd = parts.getOrNull(1)?.toIntOrNull() ?: 1
                            val i = Intent(app, com.kilon.agentvscreen.ui.StrongConfirmActivity::class.java)
                                .putExtra(com.kilon.agentvscreen.ui.StrongConfirmActivity.EXTRA_ID, cid)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(vd).toBundle()
                            val launched = try {
                                app.startActivity(i, opts)
                                true
                            } catch (t: Throwable) {
                                res.put("err", t.message ?: t.javaClass.simpleName)
                                false
                            }
                            res.put("launched", launched).put("id", cid).put("vd", vd)
                        }
                        what == "l1_vd_open" -> {
                            runCatching { l1TempVd?.release() }
                            runCatching { l1TempReader?.close() }
                            l1TempVd = null
                            l1TempReader = null
                            val tvd = try {
                                val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java)
                                val reader = android.media.ImageReader.newInstance(480, 800, android.graphics.PixelFormat.RGBA_8888, 2)
                                val vdObj = dm?.createVirtualDisplay(
                                    "ci-l1-test",
                                    480,
                                    800,
                                    160,
                                    reader.surface,
                                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                                )
                                l1TempReader = reader
                                l1TempVd = vdObj
                                vdObj?.display?.displayId ?: -1
                            } catch (t: Throwable) {
                                res.put("err", t.message ?: t.javaClass.simpleName)
                                -1
                            }
                            res.put("vd", tvd)
                        }
                        what == "l1_vd_close" -> {
                            runCatching { l1TempVd?.release() }
                            runCatching { l1TempReader?.close() }
                            l1TempVd = null
                            l1TempReader = null
                            res.put("closed", true)
                        }
                    }
                    File(app.filesDir, "ci-attack.txt").writeText(res.toString() + "\n")
                    return@Thread
                }
                com.kilon.agentvscreen.host.Prefs.setServiceOn(app, true)
                HostService.start(app)
                // Seed main token local approvals for existing smoke/eval scripts unless `--ez fresh_risk true` is requested.
                if (!intent.getBooleanExtra("fresh_risk", false)) {
                    StrongConfirm.seedLocalForCi(Tokens.MAIN, setOf("look", "operate", "system"))
                }
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
