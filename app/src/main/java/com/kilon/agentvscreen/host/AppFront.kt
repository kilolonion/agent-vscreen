package com.kilon.agentvscreen.host

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock

/**
 * 1.3 beta7: is one of the app's own pages on screen? Logged when it changes, so a problem that only happens "after
 * I leave the app" (the built-in Tailscale going offline) can be lined up with the moment the user left.
 */
object AppFront : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0
    @Volatile var visible = false; private set
    /** uptimeMillis when the app last went to the background (0 = never). */
    @Volatile var leftAt = 0L; private set

    fun init(app: Application) = app.registerActivityLifecycleCallbacks(this)

    /** How long the app has been in the background, or 0 while one of its pages is on screen. */
    fun awayMs(): Long = if (visible || leftAt == 0L) 0L else SystemClock.uptimeMillis() - leftAt

    override fun onActivityStarted(a: Activity) {
        started++
        if (!visible) { visible = true; AppLogger.info("AppFront", "app in front (${a.javaClass.simpleName})") }
    }

    override fun onActivityStopped(a: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && visible) { visible = false; leftAt = SystemClock.uptimeMillis(); AppLogger.info("AppFront", "app left the front") }
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}

/**
 * 1.3 beta8: why the previous app process ended (the system keeps the last few reasons). A process that restarts
 * by itself (killed in the background, crashed, low memory) is otherwise invisible: the log just starts over.
 */
object ExitInfo {
    @Volatile var last: String? = null; private set

    fun init(ctx: android.content.Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { AppLogger.error("Crash", "uncaught in ${t.name}: ${e.stackTraceToString().take(4000)}") }
            prev?.uncaughtException(t, e)
        }
        runCatching {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return
            val r = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return
            val at = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(r.timestamp))
            last = "$at ${reason(r.reason)} (pid ${r.pid}, importance ${r.importance}${r.description?.let { ", $it" } ?: ""})"
            AppLogger.info("ExitInfo", "previous process ended: $last")
        }
    }

    private fun reason(r: Int): String = when (r) {
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
        android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        android.app.ApplicationExitInfo.REASON_OTHER -> "other (system)"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested (force stop / update)"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        android.app.ApplicationExitInfo.REASON_FREEZER -> "freezer"
        else -> "reason $r"
    }
}
