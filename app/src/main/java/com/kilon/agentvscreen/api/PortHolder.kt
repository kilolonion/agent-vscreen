package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.S
import android.content.Context
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.tr

/**
 * 1.4.0-beta15: who listens on a local port, in the user's words. The kernel's socket table (/proc/net/tcp, tcp6)
 * names the uid of every listening socket; apps cannot read it, the shell (Shizuku) can. The uid gives the app and the
 * user it runs in, so a copy of this app in a parallel space or clone says so. Null when it cannot be read.
 */
object PortHolder {
    /** The uid listening on [port] in a socket table text (state 0A = LISTEN), or null. Pure. */
    fun uidIn(table: String, port: Int): Int? {
        val hex = ":%04X".format(port)
        return table.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { f -> f.size > 7 && f[1].uppercase().endsWith(hex) && f[3] == "0A" }?.get(7)?.toIntOrNull()
    }

    /** Pure check: [uid] is shell (2000) or root (0) and [gatePort] matches [port]. */
    fun isOwnGate(uid: Int, port: Int, gatePort: Int): Boolean =
        (uid == 2000 || uid == 0) && gatePort > 0 && gatePort == port

    private fun currentGatePort(): Int = runCatching {
        val raw = com.kilon.agentvscreen.core.VScreenRemoteDisplay.gateStatusIfConnected() ?: return@runCatching 0
        org.json.JSONObject(raw).optInt("port", 0)
    }.getOrDefault(0)

    private fun readSocketTables(): String {
        val tcp = runCatching { VScreenShell.run(arrayOf("cat", "/proc/net/tcp"), 3_000L).stdout }.getOrDefault("")
        val tcp6 = runCatching { VScreenShell.run(arrayOf("cat", "/proc/net/tcp6"), 3_000L).stdout }.getOrDefault("")
        return if (tcp.isEmpty()) tcp6 else if (tcp6.isEmpty()) tcp else "$tcp\n$tcp6"
    }

    private fun firstPackageForUid(uid: Int): String? {
        if (uid < 0) return null
        val u = uid.toString()
        val first = runCatching { VScreenShell.run(arrayOf("cmd", "package", "list", "packages", "--uid", u), 3_000L).combined }.getOrDefault("")
        val parsed = first.lineSequence().mapNotNull { it.substringAfter("package:", "").substringBefore(' ').takeIf { p -> p.isNotBlank() } }.firstOrNull()
        if (parsed != null) return parsed
        val fallback = runCatching { VScreenShell.run(arrayOf("pm", "list", "packages", "--uid", u), 3_000L).combined }.getOrDefault("")
        return fallback.lineSequence().mapNotNull { it.substringAfter("package:", "").substringBefore(' ').takeIf { p -> p.isNotBlank() } }.firstOrNull()
    }

    /** True when [port] is held by uid 2000 or 0 and matches our own Gate's bound port. */
    fun isOwnGate(port: Int): Boolean {
        val gatePort = currentGatePort()
        if (gatePort <= 0 || gatePort != port) return false
        val table = readSocketTables()
        val uid = uidIn(table, port) ?: return false
        return isOwnGate(uid, port, gatePort)
    }

    fun name(ctx: Context, port: Int): String? {
        val table = readSocketTables()
        val uid = uidIn(table, port) ?: return null
        if (isOwnGate(uid, port, currentGatePort())) return null
        val user = uid / 100_000
        if (uid % 100_000 < 10_000) return tr(S.port_holder_a_system_service_uid, uid)
        val pkg = firstPackageForUid(uid)
        val me = android.os.Process.myUid() / 100_000
        if (pkg == ctx.packageName) return if (user != me) tr(S.port_holder_agent_vscreen_in_a_parallel, user)
            else tr(S.port_holder_an_old_process_of_this)
        val label = pkg?.let { p -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() ?: p }
        return (label ?: "uid $uid") + if (user != me) tr(S.port_holder_user, user) else ""
    }
}
