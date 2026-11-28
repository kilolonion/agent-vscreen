package com.kilon.agentvscreen.host

import android.content.Context
import com.kilon.agentvscreen.api.AgentLedger
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.tr

/**
 * 1.3 beta5: how agents reach this phone, one line per way, each with its own state.
 *
 * Before, the Connect tile showed the built-in Tailscale's state alone, so a user whose agent runs on this phone saw
 * "Tailscale connected" / "hidden" whatever happened locally. Now this phone (127.0.0.1) is always one way, the
 * Tailscale way is listed only when it is turned on, and the way the last agent call came by is marked as in use.
 */
object Access {
    /** A call within this long marks its way as in use. */
    private const val IN_USE_MS = 30 * 60_000L

    class Way(val name: String, val state: String, val ok: Boolean, val inUse: Boolean) {
        fun line(): String = "$name · $state" + if (inUse) tr(" · 在用", " · in use") else ""
    }

    fun ways(ctx: Context, now: Long = System.currentTimeMillis()): List<Way> {
        val st = ServiceControl.now(ctx)
        val led = AgentLedger.snap.value
        val local = Way(tr("本机", "This phone"), when (st) {
            ServiceControl.State.ON -> tr("可用", "ready")
            ServiceControl.State.HIDDEN -> tr("按需启动", "on demand")
            ServiceControl.State.BLOCKED -> tr("已禁止", "blocked")
        }, st != ServiceControl.State.BLOCKED, led.lastLocalAt > 0 && now - led.lastLocalAt < IN_USE_MS)
        val remoteInUse = led.lastRemoteAt > 0 && now - led.lastRemoteAt < IN_USE_MS
        val remote = when (ApiConfig.tailnetMode) {
            Tailnet.Mode.OFF -> null
            Tailnet.Mode.SYSTEM -> Way("Tailscale App", when (st) {
                ServiceControl.State.ON -> tr("可用", "ready")
                ServiceControl.State.HIDDEN -> tr("待机时不可连接", "unavailable in standby")
                ServiceControl.State.BLOCKED -> tr("已禁止", "blocked")
            }, st == ServiceControl.State.ON, remoteInUse)
            Tailnet.Mode.BUILTIN -> {
                val p = Tailnet.phase(ctx)
                Way("Tailscale", when (p) {
                    Tailnet.Phase.CONNECTED -> tr("已连接", "connected")
                    Tailnet.Phase.SERVICE_OFF -> if (st == ServiceControl.State.BLOCKED) tr("已禁止", "blocked") else tr("待机时离线", "offline in standby")
                    Tailnet.Phase.STARTING -> tr("连接中", "connecting")
                    Tailnet.Phase.NEEDS_LOGIN -> tr("要登录", "log in")
                    Tailnet.Phase.EXPIRED -> tr("要重新登录", "log in again")
                    Tailnet.Phase.NEEDS_APPROVAL -> tr("等批准", "awaiting approval")
                    Tailnet.Phase.ERROR -> tr("出错，重试中", "error, retrying")
                    Tailnet.Phase.UNSTABLE -> tr("断开了，重连中", "dropped, reconnecting")
                    Tailnet.Phase.OFF -> tr("没开", "off")
                }, p == Tailnet.Phase.CONNECTED, remoteInUse)
            }
        }
        return listOfNotNull(local, remote)
    }

    /** The Connect tile: one line per way. */
    fun tile(ctx: Context): String = if (ServiceControl.now(ctx) == ServiceControl.State.BLOCKED) tr("服务已关闭", "Service off")
        else ways(ctx).joinToString("\n") { it.line() }

    /** Which way the Connect page opens on: the one the last call came by (0 = this phone, 1 = another device). */
    fun lastWay(): Int = AgentLedger.snap.value.let { if (it.lastRemoteAt > it.lastLocalAt) 1 else 0 }
}
