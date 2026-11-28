package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
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
            return false to tr(S.heal_fix_first_fix_no_privilege, srcName)
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
        if (w.exitCode != 0) return false to tr(S.heal_accessibility_writing_system_settings_fa, w.combined.take(80))
        return if (connected(4_000)) true to tr(S.heal_accessibility_turned_on_other_accessibil)
        else false to tr(S.heal_accessibility_the_setting_was_written)
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
        if (Liveness.foreground) return true to tr(S.heal_the_service_is_a_foreground)
        if (Privileged.isReady()) power(ctx)
        HostService.start(ctx.applicationContext)
        return if (waitFor(4_000) { Liveness.foreground }) true to tr(S.heal_restarted_the_service_as_a)
        else false to tr(S.heal_the_system_won_t_start)
    }

    /** 1.4.0-beta13: the built-in Tailscale node is on but not connected. Restart it and wait for it to connect. */
    fun remote(ctx: Context): Pair<Boolean, String> {
        if (com.kilon.agentvscreen.api.ApiConfig.tailnetMode != Tailnet.Mode.BUILTIN)
            return false to tr(S.heal_this_uses_the_system_s)
        if (Tailnet.phase(ctx) == Tailnet.Phase.CONNECTED) return true to tr(S.heal_tailscale_is_connected)
        Tailnet.restart(ctx.applicationContext)
        if (waitFor(20_000) { Tailnet.phase(ctx) == Tailnet.Phase.CONNECTED }) return true to tr(S.heal_restarted_the_built_in_tailscale)
        val line = runCatching { Tailnet.line(ctx) }.getOrNull()
        return false to when (Tailnet.phase(ctx)) {
            Tailnet.Phase.NEEDS_LOGIN, Tailnet.Phase.EXPIRED -> tr(S.heal_the_user_must_sign_in)
            Tailnet.Phase.NEEDS_APPROVAL -> tr(S.heal_waiting_for_the_tailnet_admin)
            else -> tr(S.heal_not_connected_20_s_after, line ?: "原因不明", line ?: "no detail")
        }
    }

    /**
     * 1.4.0-beta13 (rainbow: put the sound repair into fix). Apps left silent: every user's apps whose PLAY_AUDIO is
     * not allowed, except the ones muted by hand and the ones on the screen right now (those are meant to be quiet).
     * Each gets allow on both records and is read back. Slow (reads every package): `fix` runs it, `status` does not.
     */
    fun sound(): Pair<Boolean, String> {
        val M = com.kilon.agentvscreen.core.VScreenMute
        val r = M.repair(keepIntended = true) ?: return false to tr(S.vscreen_sound_cmd_must_be_connected, Privileged.name())
        if (r.fixed.isEmpty() && r.failed.isEmpty()) return true to tr(S.heal_no_app_was_left_silent)
        val names = r.fixed.joinToString(tr(S.automation_list_sep)) { it.pkg }
        return r.failed.isEmpty() to (tr(S.heal_gave_sound_back_to_apps, r.fixed.size, names) +
            (if (r.failed.isNotEmpty()) tr(S.heal_could_not_be_restored, r.failed.size, r.failed.joinToString { it.pkg }) else "") +
            (if (r.denied) tr(S.heal_the_system_refused_on_xiaomi) else ""))
    }
}
