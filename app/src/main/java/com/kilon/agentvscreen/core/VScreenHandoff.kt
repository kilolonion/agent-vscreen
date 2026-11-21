package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.kilon.agentvscreen.host.AppLogger

/**
 * [vscreen] v22 接力: move an app's task, as it is (page, login, half-filled form), between
 * the user's main screen and the background screen.
 *
 *  - in:  the user's app on the main screen -> the background screen, for the agent to go on.
 *  - out: the app on the background screen -> the front of the main screen, for a step only the
 *         user can do (pay, face/fingerprint, a code).
 *
 * The move is the system's own (moveRootTaskToDisplay, shell permission MANAGE_ACTIVITY_TASKS):
 * first `am display move-stack`, then the same call through the Shizuku display service. A
 * maker's system may refuse it; then nothing is moved and the caller says so plainly.
 * The app sees a configuration change (screen size/density) and normally keeps its page.
 */
internal object VScreenHandoff {
    private const val TAG = "VScreenHandoff"

    data class TaskRef(val taskId: Int, val pkg: String, val visible: Boolean, val foreground: Boolean = visible)

    /** Standard (app) tasks on [displayId], top first. Home/recents/assistant tasks are left out. */
    fun tasksOn(displayId: Int): List<TaskRef> = tasksOrNull(displayId).orEmpty()

    /** [tasksOn], but null when the system's list could not be read (unknown, not "no tasks"). */
    fun tasksOrNull(displayId: Int): List<TaskRef>? {
        // The system's own per-display list (through the display service).
        VScreenShell.taskSource(displayId)?.let { list ->
            val frontId = list.firstOrNull { it.isAppTask && it.visible }?.taskId
            return list.filter { it.isAppTask }.mapNotNull { t ->
                val pkg = (t.component ?: t.rootTop)?.substringBefore('/')?.takeUnless { it == "unknown" }
                pkg?.let { TaskRef(t.taskId, it, t.visible, t.taskId == frontId) }
            }
                .distinctBy { it.taskId }.sortedByDescending { it.foreground }
        }
        // 1.2: no dumpsys fallback. Text scraped from dumpsys differs per maker and Android version and mixed up
        // displays; when the system's own list cannot be read the answer is "unknown" (null), never a guess.
        return null
    }

    /** The launcher (home) packages, which are never handed over. */
    fun homePackages(): Set<String> {
        val r = VScreenShell.run(arrayOf("cmd", "package", "query-activities",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"), 8_000L)
        return homePackagesFrom(r.combined)
    }

    /**
     * 1.4.2-beta6: real launchers only. Settings carries a HOME activity too (FallbackHome, shown while the phone
     * boots, priority -1000), so a plain list of HOME handlers named Settings a launcher and refused to park or hand
     * it over on every phone. A handler with a negative priority is a fallback, not a launcher.
     */
    internal fun homePackagesFrom(out: String): Set<String> {
        val blocks = out.split(Regex("(?m)^\\s*Activity #\\d+:")).drop(1)
        if (blocks.isEmpty()) // an older shell without the long form: every listed component
            return out.lineSequence().map { it.trim() }.filter { it.contains('/') && !it.contains(' ') }
                .map { it.substringBefore('/') }.toSet()
        return blocks.mapNotNull { b ->
            val pkg = Regex("packageName=(\\S+)").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
            val priority = Regex("priority=(-?\\d+)").find(b)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            pkg.takeIf { priority >= 0 }
        }.toSet()
    }

    data class Moved(val ok: Boolean, val via: String, val detail: String)

    /** Move task [taskId] (of [pkg]) to [toDisplay] and check that it is really there. */
    fun move(taskId: Int, pkg: String, toDisplay: Int): Moved {
        val notes = ArrayList<String>()
        val r = VScreenShell.run(arrayOf("am", "display", "move-stack", taskId.toString(), toDisplay.toString()), 10_000L)
        val out = r.combined.trim()
        val shellErr = r.exitCode != 0 || Regex("Exception|Error|Unknown|unknown|Permission Denial|not found").containsMatchIn(out)
        notes += tr("am display move-stack：", "am display move-stack: ") + if (shellErr) (out.lines().firstOrNull { it.isNotBlank() }?.take(160) ?: "exit ${r.exitCode}") else tr("已执行", "done")
        if (!shellErr && landed(taskId, pkg, toDisplay)) return Moved(true, "am display move-stack", notes.joinToString(tr("；", "; ")))
        val s = VScreenRemoteDisplay.moveTask(taskId, toDisplay)
        val okSvc = s.getOrNull()?.first == true
        notes += tr("副屏服务 moveRootTaskToDisplay：", "screen service moveRootTaskToDisplay: ") + if (okSvc) tr("已执行", "done") else (s.getOrNull()?.second ?: s.exceptionOrNull()?.message ?: tr("失败", "failed"))
        if (okSvc && landed(taskId, pkg, toDisplay)) return Moved(true, "moveRootTaskToDisplay", notes.joinToString(tr("；", "; ")))
        if (okSvc || !shellErr) notes += tr("命令执行了，但这个应用没有出现在目标屏最前面（任务可能只移了记录、没有显示）", "The command ran, but the app is not in front on the target screen (the task may have moved only in the records, not on screen)")
        AppLogger.warning(TAG, "move task $taskId ($pkg) -> display $toDisplay failed: $notes")
        return Moved(false, "", notes.joinToString(tr("；", "; ")))
    }

    /**
     * v23: "landed" = the app is the resumed one on top of [display]. A task whose record moved
     * but stayed STOPPED behind something else (seen on MagicOS after a half move) does not count:
     * it is brought to the front once with a launcher intent, then checked again.
     */
    private fun landed(taskId: Int, pkg: String, display: Int): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        var present = false
        while (SystemClock.elapsedRealtime() - t0 < 2_000) {
            Thread.sleep(250)
            if (VScreenShell.topPackage(display) == pkg) return true
            present = present || tasksOn(display).any { it.taskId == taskId }
        }
        if (!present) return false
        val comp = VScreenShell.resolveLauncher(pkg) ?: return false
        val argv = (if (display == 0) arrayOf("am", "start") else arrayOf("am", "start", "--display", display.toString())) +
            arrayOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", comp)
        VScreenShell.run(argv, 10_000L)
        repeat(8) { Thread.sleep(250); if (VScreenShell.topPackage(display) == pkg) return true }
        return false
    }

    /** The user's own button (full-screen view): put the app on the background screen in front of them. */
    fun userToMain(sessionId: String): Pair<Boolean, String> {
        val d = BackgroundOperation.previewDisplayFor(sessionId) ?: return false to tr("副屏已经不在了", "The screen is gone")
        val pkg = VScreenShell.topPackage(d.displayId) ?: return false to tr("副屏上没有应用", "No app on the screen")
        val task = VScreenShell.taskIdOn(d.displayId, pkg) ?: return false to tr("找不到 $pkg 在副屏上的任务", "Cannot find $pkg's task on the screen")
        val m = move(task, pkg, 0)
        return if (m.ok) true to tr("已把 $pkg 原样移到主屏", "Moved $pkg to the main screen as it was") else false to tr("系统不允许移到主屏（${m.detail.take(120)}），请用“接管”在这里操作", "The system does not allow moving it to the main screen (${m.detail.take(120)}); use \"Take over\" to operate it here")
    }

    // ── telling the user ──────────────────────────────────────────────────

    const val ALERT_CHANNEL = "vscreen_alert"

    fun ensureAlertChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(ALERT_CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, tr("后台操作提醒", "Background operation alerts"), NotificationManager.IMPORTANCE_HIGH))
    }

    /**
     * v48: why the system would drop our notifications, or null when it would show them: the app switch, the runtime
     * permission (13+), the app-op some systems set to ignore, or our alert channel switched off.
     */
    fun notifyBlocked(context: Context): String? = runCatching {
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return@runCatching tr("系统设置里关了 Agent-VScreen 的通知", "Agent-VScreen notifications are off in system settings")
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return@runCatching tr("没有授予通知权限（POST_NOTIFICATIONS）", "Notification permission not granted (POST_NOTIFICATIONS)")
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        if (ops != null) {
            val mode = runCatching { ops.unsafeCheckOpNoThrow("android:post_notification", context.applicationInfo.uid, context.packageName) }.getOrNull()
            if (mode != null && mode != android.app.AppOpsManager.MODE_ALLOWED && mode != android.app.AppOpsManager.MODE_DEFAULT)
                return@runCatching tr("系统的通知开关（appops POST_NOTIFICATION）是拒绝/忽略", "The system's notification switch (appops POST_NOTIFICATION) is deny/ignore")
        }
        ensureAlertChannel(context)
        val ch = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(ALERT_CHANNEL)
        if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return@runCatching tr("“后台操作提醒”这类通知被关了", "The \"Background operation alerts\" channel is turned off")
        null
    }.getOrNull()

    /**
     * A heads-up notification; false when the system does not allow it (see [notifyBlocked]) or posting failed.
     * 1.4.0-beta11: [tap] is required: every notification opens the place where it is handled (host.Needs.open).
     */
    fun alert(context: Context, id: Int, title: String, body: String, tap: android.app.PendingIntent,
              actions: List<Pair<String, android.app.PendingIntent>> = emptyList()): Boolean = runCatching {
        if (notifyBlocked(context) != null) return@runCatching false
        ensureAlertChannel(context)
        val n = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap).apply { actions.forEach { (t, pi) -> addAction(0, t, pi) } }
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(id, n)
        true
    }.getOrElse { AppLogger.warning(TAG, "alert: ${it.message}"); false }
}
