package com.kilon.agentvscreen.host

import android.content.Context
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place the service is turned on and off, and the one answer to "is it on".
 *
 * 1.3 beta4: "off" meant two different things to the user: "get out of my notification shade and my background" and
 * "agents may not use my phone". They are now two states:
 *  - ON: always running (foreground service, its notification); agents on this phone and on other devices reach it
 *    at any time.
 *  - HIDDEN (the switch off, the default way to turn it off): no service, no notification, nothing in the background.
 *    Whatever needs it wakes it ([wake]): a booked window (exact alarm), a local agent's command (the CLI's wake
 *    broadcast), and when the system refuses a background start, Shizuku starts it as the shell. Woken, it runs as a
 *    foreground service for that use and goes back to hidden by itself once nothing needs it ([shouldSleep]).
 *    Agents on other devices reach it only while it is awake: the built-in Tailscale node lives in the service.
 *  - BLOCKED: agents may not use the phone. Nothing wakes it; only the user turns it on.
 *
 * Every way to change it goes through here (the switch, the notification, the alarm, the CLI wake). What does not
 * change it, on purpose: swiping the app away from recents, and the × on the floating window (that closes the screen).
 */
object ServiceControl {
    enum class State { ON, HIDDEN, BLOCKED }

    /** A woken hidden service sleeps again this long after its last use. */
    const val SLEEP_AFTER_MS = 5 * 60_000L

    private val _state = MutableStateFlow(State.ON)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile var wokeAt = 0L; private set
    @Volatile var wokeBy: String? = null; private set

    fun now(ctx: Context): State = when {
        ThermalGuard.locked -> State.BLOCKED
        Prefs.serviceOn(ctx) -> State.ON
        Prefs.blocked(ctx) -> State.BLOCKED
        else -> State.HIDDEN
    }.also { _state.value = it }

    /** Hidden, but running right now because something woke it. */
    fun awakeOnDemand(ctx: Context) = now(ctx) == State.HIDDEN && HostService.isAlive

    /** The start of the next booked window not opened yet (epoch ms), or null. */
    fun nextBooking(ctx: Context): Long? = runCatching { Wake.list(ctx).mapNotNull { it.pending(System.currentTimeMillis()) }.minOrNull() }.getOrNull()

    fun hhmm(at: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(at))

    fun turnOn(ctx: Context, why: String) {
        if (ThermalGuard.locked) return
        val wasOff = Prefs.blocked(ctx)
        Prefs.setServiceOn(ctx, true); Prefs.setBlocked(ctx, false)
        HostService.start(ctx)
        if (wasOff) { restoreAfterOff(ctx); runCatching { Wake.arm(ctx) } }
        now(ctx)
        AppLogger.info("ServiceControl", "on: $why")
    }

    /** Out of the shade and the background; still woken by whatever needs it. */
    fun hide(ctx: Context, why: String) {
        if (ThermalGuard.locked) return
        Prefs.setServiceOn(ctx, false); Prefs.setBlocked(ctx, false)
        HostService.ensureServer(ctx)
        BackgroundOperation.stopAndRelease(tr("服务改成按需（agent 用到时会再叫醒）", "The service is on demand now (an agent wakes it when needed)"))
        HostService.stop(ctx)
        now(ctx)
        AppLogger.info("ServiceControl", "hidden: $why")
    }

    /**
     * Agents may not use the phone until the user says so.
     *
     * 2.0.0-beta12 (rainbow: "关闭" must close it like killing it from recents, or we are as hard to get rid of as
     * malware): off leaves nothing running: public access (its tunnels and server; remembered, and turned back on with
     * the service), the built-in Tailscale node, watches, tasks, booked-window alarms, the screen and the privileged
     * screen service process, and at last this app's own process once none of its pages is open.
     */
    fun block(ctx: Context, why: String) {
        val app = ctx.applicationContext
        val publicWasOn = PublicAccess.enabled
        offPrefs(app).edit().putBoolean(K_PUBLIC_WAS_ON, publicWasOn).apply()
        if (publicWasOn) runCatching { PublicAccess.disable(app, "service_off") }
        Prefs.setServiceOn(ctx, false); Prefs.setBlocked(ctx, true)
        runCatching { com.kilon.agentvscreen.cmd.Tasks.cancelForHeat() }
        // 1.4.2-beta6: off at once, not when the service gets round to onDestroy: until then a command still running
        // reconnected by itself (acquire connects whenever the service is on) and built a new screen to go on with.
        BackgroundOperation.setEnabled(false, tr("用户关闭了 Agent-VScreen", "The user turned Agent-VScreen off"))
        if (!HostService.isAlive) HostService.dropServer()
        // 1.4.0-beta4: off is off: watches (their own notification) end too.
        runCatching { com.kilon.agentvscreen.core.VScreenRules.stopAll(tr("用户关闭了服务", "the user turned the service off")) }
        HostService.stop(ctx)
        runCatching { Tailnet.stop("service off") }
        runCatching { Wake.arm(app) }
        now(ctx)
        AppLogger.info("ServiceControl", "blocked: $why")
        Thread({
            // The screen is released above; then the privileged screen service process goes (it is a daemon that
            // otherwise outlives us so a restarted app can adopt its screen), then our own process when unseen.
            runCatching { Thread.sleep(1_500) }
            if (!Prefs.blocked(app)) return@Thread
            runCatching { com.kilon.agentvscreen.core.VScreenRemoteDisplay.shutdown() }
            exitWhenUnseen(app)
        }, "vscreen-off").apply { isDaemon = true }.start()
    }

    private const val K_PUBLIC_WAS_ON = "public_was_on"
    private fun offPrefs(ctx: Context) = ctx.getSharedPreferences("service_off_v1", Context.MODE_PRIVATE)

    /** Back on after "off": public access comes back if it was on when the service was turned off. */
    private fun restoreAfterOff(ctx: Context) {
        val p = offPrefs(ctx)
        if (!p.getBoolean(K_PUBLIC_WAS_ON, false)) return
        p.edit().remove(K_PUBLIC_WAS_ON).apply()
        if (!PublicAccess.enabled) runCatching { PublicAccess.enable(ctx) }
            .onFailure { AppLogger.warning("ServiceControl", "public access not restored: ${it.message}") }
    }

    @Volatile private var exitWatch = false
    private var startedPages = 0

    /**
     * End this process once the service is off and no page of the app is on screen (now, or when the last one goes).
     * Waits 2 s after the last page stops so a rotation or a page switch does not count.
     */
    private fun exitWhenUnseen(app: Context) {
        val a = app as? android.app.Application ?: return
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun check() {
            // 2.0.0-beta15 (test B8: after 关闭 and HOME the same process stayed, still listed in recents): the process
            // importance used here never fell below "visible" while the accessibility service is bound by the system,
            // so the process never ended. Whether a page is open is now our own count of started pages, and the app's
            // tasks leave the recents list before the process ends. (With accessibility on, Android starts the process
            // again for that service alone; nothing else runs until the user turns the service back on.)
            if (!Prefs.blocked(app) || startedPages > 0 || AppFront.visible || HostService.isAlive) return
            AppLogger.info("ServiceControl", "off: no page open, process ends")
            runCatching { app.getSystemService(android.app.ActivityManager::class.java)?.appTasks?.forEach { t -> runCatching { t.finishAndRemoveTask() } } }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        main.post {
            if (!exitWatch) {
                exitWatch = true
                startedPages = if (AppFront.visible) 1 else 0
                a.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                    override fun onActivityStarted(act: android.app.Activity) { startedPages++ }
                    override fun onActivityStopped(act: android.app.Activity) {
                        startedPages = (startedPages - 1).coerceAtLeast(0)
                        if (startedPages == 0) main.postDelayed({ check() }, 2_000L)
                    }
                    override fun onActivityCreated(act: android.app.Activity, b: android.os.Bundle?) {}
                    override fun onActivityResumed(act: android.app.Activity) {}
                    override fun onActivityPaused(act: android.app.Activity) {}
                    override fun onActivitySaveInstanceState(act: android.app.Activity, b: android.os.Bundle) {}
                    override fun onActivityDestroyed(act: android.app.Activity) {}
                })
            }
            main.postDelayed({ check() }, 2_000L)
        }
    }

    /** From blocked back to hidden (woken on demand) without turning it on. */
    fun allow(ctx: Context, why: String) {
        if (ThermalGuard.locked) return
        val wasOff = Prefs.blocked(ctx)
        Prefs.setBlocked(ctx, false); now(ctx)
        HostService.ensureServer(ctx)
        if (wasOff) { restoreAfterOff(ctx); runCatching { Wake.arm(ctx) } }
        AppLogger.info("ServiceControl", "allowed (hidden): $why")
    }

    /** Something needs the service now. False when the user blocked agents. */
    fun wake(ctx: Context, why: String): Boolean {
        when (now(ctx)) {
            State.BLOCKED -> { AppLogger.info("ServiceControl", "not woken ($why): blocked"); return false }
            State.ON -> if (!HostService.isAlive) HostService.start(ctx)
            State.HIDDEN -> {
                wokeAt = System.currentTimeMillis(); wokeBy = why
                if (!HostService.isAlive) { AppLogger.info("ServiceControl", "woken: $why"); HostService.start(ctx) }
            }
        }
        return true
    }

    /** A booked window is due. */
    internal fun windowDue(ctx: Context): Boolean = wake(ctx, "booked window")

    /** Asked every minute by the running service: a woken hidden service goes back once nothing needs it. */
    fun shouldSleep(ctx: Context): Boolean {
        if (now(ctx) != State.HIDDEN) return false
        val t = System.currentTimeMillis()
        val lastUse = maxOf(wokeAt, com.kilon.agentvscreen.api.AgentLedger.snap.value.lastCallAt)
        if (t - lastUse < SLEEP_AFTER_MS) return false
        if (BackgroundOperation.state.value.displayId != null) return false
        if (Wake.windowOpen(ctx, t)) return false
        if (UserAsk.open.value.any { it.live }) return false
        if (PublicAccess.enabled) return false
        if (runCatching { com.kilon.agentvscreen.core.VScreenRules.running().isNotEmpty() || com.kilon.agentvscreen.cmd.Tasks.running() > 0 }.getOrDefault(false)) return false
        return true
    }

    /** The home page's status line when the service is not always on: the state, then what it means. */
    fun title(ctx: Context): String = when (now(ctx)) {
        State.ON -> tr("常驻运行中", "Always on")
        State.HIDDEN -> if (HostService.isAlive) tr("按需 · 现在被叫醒了", "On demand · awake right now")
            else if (Tailnet.Mode.BUILTIN == com.kilon.agentvscreen.api.ApiConfig.tailnetMode) tr("按需 · 远程已下线", "On demand · remote offline") else tr("按需", "On demand")
        State.BLOCKED -> tr("已关闭", "Off")
    }

    fun sub(ctx: Context): String? = when (now(ctx)) {
        State.ON -> null
        State.HIDDEN -> if (HostService.isAlive) tr("用完会自己收起", "It hides again when done")
            else tr("agent 用到时自动唤起，用完再收起", "Woken when an agent needs it, hidden again after")
        State.BLOCKED -> if (ThermalGuard.locked) ThermalGuard.reason() else tr("什么都叫不醒它，点右上角的电源键恢复", "Nothing wakes it; the power button at the top resumes it")
    }

    /** The full story behind the "?" next to the status line. */
    fun help(): String = tr(
        "右上角的电源键里选它怎么运行，三选一，选了马上生效：\n\n" +
            "常驻：一直在运行，通知栏里一直有它，本机和其它设备上的 agent 随时连得上。\n\n" +
            "按需：平时不在后台。本机 agent 发命令、预约的时间到了，会把它叫起来；用完、安静 5 分钟后自己收起。按需时其它设备上的 agent 叫不醒它（内置 Tailscale 跑在服务里）。被叫醒时再点一次“按需”会马上收起。\n\n" +
            "关闭：马上停下并离开通知栏，什么都叫不醒它，预约到点只通知你。通知上的“关闭”按钮是同一个意思。\n\n" +
            "另外两个按钮不改变服务：悬浮窗的 × 只关掉副屏；“停止”只拦住 agent 当前的操作，直到你点“允许继续”。从最近任务里划掉 App 也不改变服务。",
        "The power button at the top chooses how it runs, one of three, applied at once:\n\n" +
            "Always on: always running, in the notification shade; agents here and on other devices reach it any time.\n\n" +
            "On demand: not in the background. A local agent's command or a booked time wakes it; it puts itself away after 5 quiet minutes. Agents on other devices cannot wake it (the built-in Tailscale runs in the service). While woken, choosing On demand again puts it away at once.\n\n" +
            "Off: stops now and leaves the shade; nothing wakes it, a booking only notifies you. The notification's Off button means the same.\n\n" +
            "Two other buttons leave the service alone: the × on the floating window only closes the screen; Stop only holds the agent's actions until you tap Resume. Swiping the app away from recents does not change the service either.",
    )
}
