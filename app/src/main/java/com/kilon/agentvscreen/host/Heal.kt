package com.kilon.agentvscreen.host

import android.content.Context
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.tr

/**
 * 1.4.0-beta2: the one way to repair what Shizuku can repair. Before, turning accessibility on was written three times
 * (setup, the self-heal, `vscreen fix`), each with its own wait and its own words. Policy (when to try, how often, when
 * to stop trying) stays with the callers: A11yHeal (quietly in the background), Setup (the user tapped), fix (an agent
 * asked). The doing is here.
 */
object Heal {
    private fun comp(ctx: Context) = "${ctx.packageName}/${VScreenAccessibilityService::class.java.name}"

    private fun enabledList(): List<String> =
        VScreenShell.run(arrayOf("settings", "get", "secure", "enabled_accessibility_services")).stdout.trim()
            .split(':').filter { it.isNotBlank() && it != "null" }

    private fun connected(waitMs: Long): Boolean {
        val t0 = System.currentTimeMillis()
        while (true) {
            if (VScreenAccessibilityService.getInstance() != null) return true
            if (System.currentTimeMillis() - t0 >= waitMs) return false
            Thread.sleep(200)
        }
    }

    /**
     * Turn this app's read-only accessibility service on, keeping every other service in the list. [restart]: take it
     * out first and put it back (re-binds a service that is on but sees nothing). Needs Shizuku.
     */
    fun accessibility(ctx: Context, restart: Boolean = false): Pair<Boolean, String> {
        if (!Privileged.isReady()) {
            val srcName = Privileged.name()
            return false to tr("需要先修好 $srcName（fix no_privilege），或请用户在 系统设置 › 无障碍 › 已安装的应用 › Agent-VScreen 手动打开",
                "Fix $srcName first (fix no_privilege), or ask the user to turn it on in Settings › Accessibility › Installed apps › Agent-VScreen")
        }
        val me = comp(ctx)
        val rest = enabledList().filter { it != me }
        if (restart) {
            if (rest.isEmpty()) VScreenShell.run(arrayOf("settings", "delete", "secure", "enabled_accessibility_services"))
            else VScreenShell.run(arrayOf("settings", "put", "secure", "enabled_accessibility_services", rest.joinToString(":")))
            Thread.sleep(500)
        }
        // Writing the list (ours last) also re-binds a crashed service.
        val w = VScreenShell.run(arrayOf("settings", "put", "secure", "enabled_accessibility_services", (rest + me).joinToString(":")))
        VScreenShell.run(arrayOf("settings", "put", "secure", "accessibility_enabled", "1"))
        if (w.exitCode != 0) return false to tr("无障碍：写系统设置失败（${w.combined.take(80)}），请在 系统设置 › 无障碍 › Agent-VScreen 手动打开",
            "Accessibility: writing system settings failed (${w.combined.take(80)}); turn it on in Settings › Accessibility › Agent-VScreen")
        return if (connected(4_000)) true to tr("无障碍已打开（其它无障碍服务保持不变）", "Accessibility turned on (other accessibility services unchanged)")
        else false to tr("无障碍：已写入设置但 4 秒内没连上，部分系统会拦截，请在 系统设置 › 无障碍 › Agent-VScreen 手动打开一次",
            "Accessibility: the setting was written but did not connect within 4 s (some systems block it); turn it on once in Settings › Accessibility › Agent-VScreen")
    }

    /** Battery exemption and background running, as a user would grant them by hand. */
    fun power(ctx: Context): Pair<Boolean, String> = com.kilon.agentvscreen.core.VScreenPower.whitelist(ctx)

    private fun waitFor(ms: Long, ok: () -> Boolean): Boolean {
        val t0 = System.currentTimeMillis()
        while (true) { if (ok()) return true; if (System.currentTimeMillis() - t0 >= ms) return false; Thread.sleep(250) }
    }

    /**
     * 1.4.0-beta13: the service runs but not as a foreground service. Exempt it from battery limits first (a background
     * start of a foreground service is allowed for an exempt app), then start it again and wait until it says it is up.
     */
    fun foreground(ctx: Context): Pair<Boolean, String> {
        if (Liveness.foreground) return true to tr("服务已是前台服务", "the service is a foreground service")
        if (Privileged.isReady()) power(ctx)
        HostService.start(ctx.applicationContext)
        return if (waitFor(4_000) { Liveness.foreground }) true to tr("已把服务重新启动为前台服务", "restarted the service as a foreground service")
        else false to tr("系统不让后台启动前台服务：请用户打开一次 Agent-VScreen", "the system won't start a foreground service from the background: ask the user to open Agent-VScreen once")
    }

    /** 1.4.0-beta13: the built-in Tailscale node is on but not connected. Restart it and wait for it to connect. */
    fun remote(ctx: Context): Pair<Boolean, String> {
        if (com.kilon.agentvscreen.api.ApiConfig.tailnetMode != Tailnet.Mode.BUILTIN)
            return false to tr("用的是系统里的 Tailscale：请用户打开 Tailscale 应用连上", "this uses the system's Tailscale: ask the user to open the Tailscale app and connect")
        if (Tailnet.phase(ctx) == Tailnet.Phase.CONNECTED) return true to tr("Tailscale 已连接", "Tailscale is connected")
        Tailnet.restart(ctx.applicationContext)
        if (waitFor(20_000) { Tailnet.phase(ctx) == Tailnet.Phase.CONNECTED }) return true to tr("已重启内置 Tailscale，现在连上了", "restarted the built-in Tailscale; it is connected now")
        val line = runCatching { Tailnet.line(ctx) }.getOrNull()
        return false to when (Tailnet.phase(ctx)) {
            Tailnet.Phase.NEEDS_LOGIN, Tailnet.Phase.EXPIRED -> tr("需要用户重新登录 Tailscale：打开 Agent-VScreen › 接入", "the user must sign in to Tailscale again: open Agent-VScreen › Connect")
            Tailnet.Phase.NEEDS_APPROVAL -> tr("等管理员在 Tailscale 后台批准这台设备", "waiting for the tailnet admin to approve this device")
            else -> tr("重启后 20 秒内没连上（${line ?: "原因不明"}）：手机开着别的 VPN 时请用户看 接入 页的引导", "not connected 20 s after a restart (${line ?: "no detail"}): if another VPN is on, ask the user to follow the guide on the Connect page")
        }
    }

    /**
     * 1.4.0-beta13 (rainbow: put the sound repair into fix). Apps left silent: every user's apps whose PLAY_AUDIO is
     * not allowed, except the ones muted by hand and the ones on the screen right now (those are meant to be quiet).
     * Each gets allow on both records and is read back. Slow (reads every package): `fix` runs it, `status` does not.
     */
    fun sound(): Pair<Boolean, String> {
        val M = com.kilon.agentvscreen.core.VScreenMute
        val r = M.repair(keepIntended = true) ?: return false to tr("需要 ${Privileged.name()} 已连接", "${Privileged.name()} must be connected")
        if (r.fixed.isEmpty() && r.failed.isEmpty()) return true to tr("没有被留下静音的应用", "no app was left silent")
        val names = r.fixed.joinToString(tr("、", ", ")) { it.pkg }
        return r.failed.isEmpty() to (tr("恢复了 ${r.fixed.size} 个应用的声音（$names），要在应用里重新点一次播放", "gave sound back to ${r.fixed.size} apps ($names); playback must be started again in the app") +
            (if (r.failed.isNotEmpty()) tr("；${r.failed.size} 个没恢复（${r.failed.joinToString { it.pkg }}）", "; ${r.failed.size} could not be restored (${r.failed.joinToString { it.pkg }})") else "") +
            (if (r.denied) tr("。系统拒绝修改：小米 / 红米请用户打开“USB 调试（安全设置）”", ". The system refused: on Xiaomi / Redmi ask the user to turn on \"USB debugging (Security settings)\"") else ""))
    }
}
