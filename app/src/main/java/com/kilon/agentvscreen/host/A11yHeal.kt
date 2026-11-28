package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.tr

import android.content.Context
import android.os.SystemClock

/**
 * Keeps the read-only accessibility service on after the user asked for it once (setup), the way android-vdisplay
 * does, with three guard rails:
 *  - append-only (Heal.accessibility keeps every other service), at most once a minute;
 *  - when it goes off again shortly after we turned it back on, that is the user (or the ROM) saying no: we stop and
 *    the app says so, until the user runs setup again;
 *  - when the system blocks the write (MagicOS / HarmonyOS), we remember it and stop trying; the app shows the manual way.
 * A force-stop or an app update drops the service on some ROMs; this is what brings it back without the user.
 */
object A11yHeal {
    private const val MIN_GAP_MS = 60_000L
    private const val USER_OFF_WINDOW_MS = 10 * 60_000L
    @Volatile private var lastTry = 0L
    @Volatile private var healedAt = 0L
    @Volatile var lastResult: String? = null; private set

    private fun p(ctx: Context) = ctx.getSharedPreferences("agent_vscreen", Context.MODE_PRIVATE)
    fun wanted(ctx: Context) = p(ctx).getBoolean("a11y_wanted", false)
    fun blocked(ctx: Context) = p(ctx).getBoolean("a11y_blocked", false)
    fun userOff(ctx: Context) = p(ctx).getBoolean("a11y_user_off", false)

    /** The user asked for it (setup): heal from now on, forget earlier refusals. */
    fun want(ctx: Context) { p(ctx).edit().putBoolean("a11y_wanted", true).putBoolean("a11y_blocked", false).putBoolean("a11y_user_off", false).apply() }

    /** Called when the service disconnects while the app runs. */
    fun onLost(ctx: Context) {
        if (healedAt > 0 && SystemClock.elapsedRealtime() - healedAt < USER_OFF_WINDOW_MS) {
            p(ctx).edit().putBoolean("a11y_user_off", true).apply()
            lastResult = tr(S.a11y_heal_accessibility_was_turned_off_right)
            AppLogger.info("A11yHeal", "turned off again after heal: stop healing")
        }
    }

    /** Turn the service back on if the user wants it and nothing says no. Cheap when there is nothing to do. */
    fun maybeHeal(ctx: Context, why: String) {
        if (VScreenAccessibilityService.getInstance() != null) return
        if (!wanted(ctx) || blocked(ctx) || userOff(ctx) || !Privileged.isReady()) return
        val now = SystemClock.elapsedRealtime()
        if (lastTry != 0L && now - lastTry < MIN_GAP_MS) return
        lastTry = now
        Thread {
            val (ok, msg) = runCatching { Heal.accessibility(ctx) }.getOrElse { false to tr(S.a11y_heal_accessibility, it.message) }
            lastResult = msg
            if (ok) healedAt = SystemClock.elapsedRealtime()
            else if (msg.contains(tr(S.a11y_heal_did_not_connect)) || msg.contains(tr(S.a11y_heal_writing_system_settings_failed))) p(ctx).edit().putBoolean("a11y_blocked", true).apply()
            AppLogger.info("A11yHeal", "$why: $msg")
        }.apply { isDaemon = true; name = "a11y-heal" }.start()
    }
}
