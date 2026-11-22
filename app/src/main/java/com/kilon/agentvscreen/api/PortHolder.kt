package com.kilon.agentvscreen.api

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

    /** True when [port] is held by uid 2000 or 0 and matches our own Gate's bound port. */
    fun isOwnGate(port: Int): Boolean {
        val gatePort = currentGatePort()
        if (gatePort <= 0 || gatePort != port) return false
        val table = VScreenShell.run(arrayOf("sh", "-c", "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null"), 3_000L).combined
        val uid = uidIn(table, port) ?: return false
        return isOwnGate(uid, port, gatePort)
    }

    fun name(ctx: Context, port: Int): String? {
        val table = VScreenShell.run(arrayOf("sh", "-c", "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null"), 3_000L).combined
        val uid = uidIn(table, port) ?: return null
        if (isOwnGate(uid, port, currentGatePort())) return null
        val user = uid / 100_000
        if (uid % 100_000 < 10_000) return tr("系统服务（uid $uid）", "a system service (uid $uid)")
        val pkg = VScreenShell.run(arrayOf("sh", "-c", "cmd package list packages --uid $uid 2>/dev/null || pm list packages --uid $uid"), 3_000L)
            .combined.lineSequence().mapNotNull { it.substringAfter("package:", "").substringBefore(' ').takeIf { p -> p.isNotBlank() } }.firstOrNull()
        val me = android.os.Process.myUid() / 100_000
        if (pkg == ctx.packageName) return if (user != me) tr("分身或平行空间（用户 $user）里的 Agent-VScreen", "Agent-VScreen in a parallel space or clone (user $user)")
            else tr("本 App 还没退出的旧进程", "an old process of this app that has not exited yet")
        val label = pkg?.let { p -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() ?: p }
        return (label ?: "uid $uid") + if (user != me) tr("（用户 $user）", " (user $user)") else ""
    }
}
