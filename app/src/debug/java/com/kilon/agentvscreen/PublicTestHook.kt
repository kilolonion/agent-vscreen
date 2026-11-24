package com.kilon.agentvscreen

import android.content.Context
import com.kilon.agentvscreen.api.PublicOAuth
import com.kilon.agentvscreen.api.PublicServer
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.host.Tailnet
import org.json.JSONObject
import java.io.File

/**
 * 2.0.0-beta7 (7.6, 7.7), DEBUG BUILDS ONLY:
 * Lets `tools/android-smoke/public_local.py` test `PublicServer` on 127.0.0.1:18785 without a live
 * Tailscale Funnel account — exposes the bridge secret via private `files/ci-public.txt` (never logcat),
 * installs a debug-only pairing presenter so pairings can be settled without touching `StrongConfirm.approve`,
 * and advances `PublicOAuth`'s clock to verify 1-hour access-token expiry.
 * Never compiled into release builds (verified by `tools/android-smoke/check_release_hooks.sh`).
 */
internal object PublicTestHook {
    private val lock = Any()
    private var pendingApprove: ((String) -> Unit)? = null
    private var pendingDeny: (() -> Unit)? = null

    fun handle(app: Context, cmd: String) {
        val out = JSONObject().put("cmd", cmd)
        when {
            cmd == "start" -> {
                PublicOAuth.clockOffsetMs = 0L
                PublicOAuth.deviceSecureCheck = { true }
                synchronized(lock) {
                    pendingApprove = null
                    pendingDeny = null
                }
                PublicOAuth.pairingPresenterOverride = { _, pairing, onApprove, onDeny ->
                    synchronized(lock) {
                        pendingApprove = onApprove
                        pendingDeny = onDeny
                    }
                    "debug_${pairing.id}"
                }
                val sec = PublicAccess.rotateSecret()
                val srv = PublicAccess.ensureServer(app, PublicServer.DEFAULT_PORT)
                HostService.server?.failLimiter?.resetForTests()
                out.put("ok", srv.running)
                    .put("port", srv.boundPort)
                    .put("secret", sec)
                    .put("local_port", HostService.server?.boundPort ?: 0)
                    .put("tailnet_secret", Tailnet.secret)
            }
            cmd == "approve" || cmd.startsWith("approve:") -> {
                val scope = cmd.substringAfter(':', "look").ifBlank { "look" }
                val fn = synchronized(lock) { pendingApprove.also { pendingApprove = null; pendingDeny = null } }
                val ok = if (fn != null) { fn(scope); true } else false
                out.put("ok", ok).put("scope", scope)
            }
            cmd == "deny" -> {
                val fn = synchronized(lock) { pendingDeny.also { pendingApprove = null; pendingDeny = null } }
                val ok = if (fn != null) { fn(); true } else false
                out.put("ok", ok)
            }
            cmd.startsWith("advance_ms:") -> {
                val delta = cmd.removePrefix("advance_ms:").toLongOrNull() ?: 0L
                PublicOAuth.clockOffsetMs += delta
                out.put("ok", true).put("clock_offset_ms", PublicOAuth.clockOffsetMs)
            }
            cmd == "clear_lockout" -> {
                HostService.server?.failLimiter?.resetForTests()
                out.put("ok", true)
            }
            cmd == "reset" -> {
                PublicOAuth.clockOffsetMs = 0L
                PublicOAuth.pairingPresenterOverride = null
                synchronized(lock) {
                    pendingApprove = null
                    pendingDeny = null
                }
                PublicAccess.stopServer()
                HostService.server?.failLimiter?.resetForTests()
                out.put("ok", true)
            }
            else -> out.put("ok", false).put("error", "unknown public hook command")
        }
        File(app.filesDir, "ci-public.txt").writeText(out.toString() + "\n")
    }
}
