package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.tr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.ApiServer
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.ui.MainActivity
import com.kilon.agentvscreen.ui.Overlay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground start with the type this app declares (specialUse on 14+, none before). */
object Fg {
    fun start(s: Service, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) s.startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else s.startForeground(id, n)
    }
}

/**
 * The always-on part: keeps the process alive in the foreground, owns the local API server and the floating window.
 * Started by the app (and on boot / package update when the user left it on); the notification says where it listens.
 */
class HostService : Service() {
    companion object {
        @Volatile var isAlive = false; private set
        @Volatile var server: ApiServer? = null; private set
        private const val CHANNEL = "agent_vscreen_host"
        private const val NOTE_ID = 7001
        const val ACTION_STOP = "com.kilon.agentvscreen.STOP"
        const val ACTION_PAUSE = "com.kilon.agentvscreen.PAUSE"
        const val ACTION_ON = "com.kilon.agentvscreen.ON"
        const val ACTION_ALLOW = "com.kilon.agentvscreen.ALLOW"
        const val ACTION_SHOW_WINDOW = "com.kilon.agentvscreen.SHOW_WINDOW"
        const val ACTION_HAND_BACK = "com.kilon.agentvscreen.HAND_BACK"
        private const val STOP_CHANNEL = "agent_vscreen_stopped"
        private const val STOP_NOTE_ID = 7002

        /**
         * 1.3.0: a start sent and not yet foreground. Stopping the service then makes Android kill the app
         * (ForegroundServiceDidNotStartInTimeException); such a stop waits until the service is foreground.
         */
        @Volatile private var pendingAt = 0L
        @Volatile private var stopWhenUp = false
        /** Android allows 10 s for startForeground; a start older than that is not waited for. */
        private val pending get() = pendingAt > 0 && android.os.SystemClock.elapsedRealtime() - pendingAt < 10_000L

        fun start(ctx: Context) {
            if (ThermalGuard.locked) return
            val i = Intent(ctx, HostService::class.java)
            stopWhenUp = false
            if (!isAlive) pendingAt = android.os.SystemClock.elapsedRealtime()
            runCatching { ctx.startForegroundService(i) }
                .onFailure {
                    // Android 12+ refuses to start a foreground service from the background (e.g. the process was
                    // restarted by the system). Opening the app starts it again (MainActivity.onResume).
                    Liveness.foregroundFailed(tr("后台启动服务被系统拒绝：${it.javaClass.simpleName}", "The system refused to start the service in the background: ${it.javaClass.simpleName}"))
                    AppLogger.warning("HostService", "start: ${it.message}")
                    startFromShell(ctx.applicationContext)
                }
        }

        /**
         * Learned from android-vdisplay: a foreground service started by the shell (`am start-foreground-service`) is
         * not subject to the app's background-start ban. So when the system refuses our own start (process restarted
         * in the background), ask Shizuku — once it is up — to start it as the shell does. Still append-only: it only
         * starts this app's own service.
         */
        private fun startFromShell(app: Context) {
            Thread({
                val until = android.os.SystemClock.elapsedRealtime() + 60_000L
                while (!Privileged.isReady() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(1_000)
                if (!Privileged.isReady() || isAlive || ThermalGuard.locked) return@Thread
                val r = runCatching {
                    com.kilon.agentvscreen.core.VScreenShell.run(arrayOf("am", "start-foreground-service", "-n", "${app.packageName}/${HostService::class.java.name}"), 6_000L)
                }.getOrNull()
                AppLogger.info("HostService", "start via shell: ${r?.exitCode ?: "failed"} ${r?.combined?.take(120) ?: ""}")
            }, "vscreen-shell-start").apply { isDaemon = true }.start()
        }

        fun stop(ctx: Context) { if (pending) stopWhenUp = true else ctx.stopService(Intent(ctx, HostService::class.java)) }

        /**
         * 1.4.0-beta5: the local server outlives the service unless the user turned it off. On demand, the app's
         * process often lives on without the service (the accessibility service keeps it), and a server that went with
         * the service left agents a refused connection they could do nothing about. Now the process keeps listening and
         * the first request wakes the service ([wakeFor]).
         */
        fun ensureServer(ctx: Context): ApiServer {
            var created = false
            val srv = synchronized(this) {
                server ?: ApiServer(ctx.applicationContext).also { server = it; created = true }
            }
            if (created && synchronized(this) { server === srv }) srv.start(ApiConfig.port)
            return srv
        }

        @Volatile private var lastHeal = 0L

        /**
         * 2.0.1-beta1: make sure agents can connect now, in every mode but Off: the app came to the front, a local
         * agent's wake broadcast arrived, or the app was updated. Creates the server if needed, and binds it again
         * when its port does not answer (see [ApiServer.heal]). Off the main thread, at most every 5 s.
         */
        fun healServer(ctx: Context, why: String) {
            val app = ctx.applicationContext
            if (ServiceControl.now(app) == ServiceControl.State.BLOCKED || ThermalGuard.locked) return
            val now = android.os.SystemClock.elapsedRealtime()
            synchronized(this) { if (now - lastHeal < 5_000L) return; lastHeal = now }
            Thread({ quiet("server-heal") { ensureServer(app).heal(why) } }, "vscreen-server-heal").apply { isDaemon = true }.start()
        }

        fun dropServer() = synchronized(this) { server?.onChange = null; server?.stop(); server = null }

        /** A request arrived while the service sleeps: wake it and wait up to 8 s for it (false: off, or it did not come). */
        fun wakeFor(ctx: Context, why: String): Boolean {
            if (!ServiceControl.wake(ctx, why)) return false
            val until = android.os.SystemClock.elapsedRealtime() + 8_000L
            while (!isAlive && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(100)
            return isAlive
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (ServiceControl.now(this) == ServiceControl.State.BLOCKED) {
            refusedBlocked = true
            pendingAt = 0L
            // 2.0.0-beta2: an external start (uid 2000 / 0) must still respect the user's "Off" (BLOCKED).
            // Satisfy the foreground service contract, then exit without starting ApiServer, Gate, heartbeat, or OCR self-test.
            goForeground()
            AppLogger.info("HostService", "start ignored: service is off (BLOCKED)")
            stopSelf()
            return
        }
        isAlive = true
        Liveness.init(applicationContext)
        Liveness.start()
        goForeground()
        pendingAt = 0L
        BackgroundOperation.setEnabled(true)
        ensureServer(applicationContext).onChange = { renotify() }
        startGateBeat()
        Overlay.attach(applicationContext)
        WaitingPill.attach(applicationContext)
        Presence.start(applicationContext)
        A11yHeal.maybeHeal(applicationContext, "service start")
        Tailnet.sync(applicationContext)
        PublicAccess.sync(applicationContext)
        com.kilon.agentvscreen.core.VScreenPower.heal(applicationContext, "service start")
        // 1.4.0-beta2 / 2.0.0-beta2: read a known picture once per app versionCode with both OCR engines, with a
        // crash guard persisted in private prefs so cold wakes do not repeat it or enter a crash loop.
        com.kilon.agentvscreen.core.VScreenOwnership.start(applicationContext)
        com.kilon.agentvscreen.core.VScreenOcr.maybeStartSelfTest(applicationContext)
        // 1.3 beta4: woken while hidden: go back once nothing needs the service (ServiceControl.shouldSleep).
        val sleeperDog = com.kilon.agentvscreen.core.LoopWatchdog.process.registerExternal("vscreen-host-sleeper", critical = true)
        sleeperWatchdog = sleeperDog
        sleeper = scope.launch {
            try {
                while (true) {
                    kotlinx.coroutines.delay(60_000L)
                    try {
                        sleeperDog.beat()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            PublicAccess.checkIdle(applicationContext)
                        }
                        val sleepNow = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            ServiceControl.shouldSleep(applicationContext)
                        }
                        if (sleepNow) {
                            AppLogger.info("HostService", "nothing needs the service: hidden again")
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                BackgroundOperation.stopAndRelease(tr("已完成，自动隐藏", "Done, hidden automatically"))
                            }
                            stopSelf()
                            break
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        if (t is VirtualMachineError) throw t
                        val backoff = sleeperDog.recordError(t)
                        if (backoff < 0L) break
                        kotlinx.coroutines.delay(backoff)
                    }
                }
            } finally {
                if (!sleeperDog.tripped) sleeperDog.markStopped()
            }
        }
        // 1.1.6: the Stop lock and a hidden window are never silent: both live in the notification shade.
        val watchDog = com.kilon.agentvscreen.core.LoopWatchdog.process.registerExternal("vscreen-host-watch", critical = true)
        watchWatchdog = watchDog
        watch = scope.launch {
            try {
                while (true) {
                    try {
                        kotlinx.coroutines.flow.combine(
                            BackgroundOperation.state.map { Triple(it.userStopped, it.windowHidden && it.displayId != null, it.stoppedAt) }.distinctUntilChanged(),
                            Tailnet.status.map { it.state to it.ip }.distinctUntilChanged(),
                        ) { a, _ -> a }.collect { (stopped, _, _) ->
                            try {
                                watchDog.beat()
                                renotify()
                                stoppedNote(stopped)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                if (t is VirtualMachineError) throw t
                                AppLogger.warning("HostService", "watch update failed: ${t.javaClass.simpleName}: ${t.message}")
                                Swallowed.note("HostService:watch", t)
                            }
                        }
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        if (t is VirtualMachineError) throw t
                        val backoff = watchDog.recordError(t)
                        if (backoff < 0L) break
                        kotlinx.coroutines.delay(backoff)
                    }
                }
            } finally {
                if (!watchDog.tripped) watchDog.markStopped()
            }
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.Dispatchers.Main +
            kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                if (t is VirtualMachineError) throw t
                AppLogger.error("HostService", "uncaught coroutine error", t)
                Swallowed.note("HostService:scope", t)
            },
    )
    private var watch: kotlinx.coroutines.Job? = null
    private var sleeper: kotlinx.coroutines.Job? = null
    private var watchWatchdog: com.kilon.agentvscreen.core.LoopWatchdog.ExternalHandle? = null
    private var sleeperWatchdog: com.kilon.agentvscreen.core.LoopWatchdog.ExternalHandle? = null
    private var refusedBlocked = false
    @Volatile private var beatHandle: com.kilon.agentvscreen.core.LoopWatchdog.Handle? = null

    /** 2.0.0-beta2: periodic heartbeat to Gate off the main thread; stops when HostService is destroyed. */
    private fun startGateBeat() {
        if (beatHandle?.alive == true) return
        beatHandle = com.kilon.agentvscreen.core.LoopWatchdog.process.startLoop(
            name = "vscreen-gate-beat",
            critical = true,
            shouldRun = { isAlive },
        ) { ctx ->
            while (ctx.active) {
                ctx.beat()
                if (Privileged.isReady() && server?.usingGate == true) {
                    runCatching { com.kilon.agentvscreen.core.VScreenRemoteDisplay.gateBeat() }
                }
                Thread.sleep(1_000L)
            }
        }
    }

    /**
     * A separate, visible notification while the user's Stop holds: the user may have pressed it (or closed the screen
     * after it) and walked away; without this the agent just "stops working" for no visible reason.
     */
    private fun stoppedNote(stopped: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (!stopped) { nm.cancel(STOP_NOTE_ID); return }
        if (nm.getNotificationChannel(STOP_CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(STOP_CHANNEL, tr("agent 已停止", "Agent stopped"), NotificationManager.IMPORTANCE_DEFAULT))
        val allow = PendingIntent.getBroadcast(this, 3, Intent(this, NoteActions::class.java).setAction(ACTION_ALLOW), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE, com.kilon.agentvscreen.ui.MainDisplay.options())
        val at = BackgroundOperation.state.value.stoppedAt.takeIf { it > 0 }?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(it)) }
        val n = NotificationCompat.Builder(this, STOP_CHANNEL)
            .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
            .setContentTitle(tr("已停止 agent", "Agent stopped") + (at?.let { tr("（$it）", " ($it)") } ?: ""))
            .setContentText(tr("agent 的操作将被拒绝，点“解除停止”后恢复", "The agent's actions are refused until you tap \"Resume agent\""))
            .setStyle(NotificationCompat.BigTextStyle().bigText(tr("你已在悬浮窗点击“停止 agent”。解除前，agent 的所有操作都会被拒绝，并提示它向你确认。要让 agent 继续，请点下方“解除停止”。", "You tapped \"Stop agent\" on the floating window. Until you lift it, every action of the agent is refused and it is told to check with you. To let the agent continue, tap \"Resume agent\" below.")))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFFE5484D.toInt())
            .addAction(0, tr("解除停止", "Resume agent"), allow)
            .build()
        com.kilon.agentvscreen.host.quiet("HostService:132") { nm.notify(STOP_NOTE_ID, n) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (refusedBlocked || ServiceControl.now(this) == ServiceControl.State.BLOCKED) {
            if (!Liveness.foreground) goForeground()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (stopWhenUp) { stopWhenUp = false; AppLogger.info("HostService", "stopped as asked while it was starting"); stopSelf(); return START_NOT_STICKY }
        // 2.0.0-beta2: exported entry is start-only — ignore intent action and extras. Notification buttons dispatch
        // through the non-exported NoteActions BroadcastReceiver so uid 2000 callers cannot spoof user actions.
        // Started again (the user opened the app, boot, update): whatever failed before gets another go now.
        if (!Liveness.foreground) goForeground()
        if (server?.running != true && server?.starting != true) server?.start(ApiConfig.port)
        startGateBeat()
        Presence.start(applicationContext) // the overlay permission may have arrived since
        Tailnet.sync(applicationContext)
        PublicAccess.sync(applicationContext)
        com.kilon.agentvscreen.core.VScreenPower.heal(applicationContext, "service restart")
        renotify()
        return START_STICKY
    }

    /** Without a real foreground service nothing keeps this process running in the background: check, don't assume. */
    private fun goForeground() {
        runCatching { Fg.start(this, NOTE_ID, notification()) }
            .onSuccess { Liveness.foregroundOk() }
            .onFailure { Liveness.foregroundFailed("${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun renotify() {
        com.kilon.agentvscreen.host.quiet("HostService:161") { getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, notification()) }
    }

    override fun onDestroy() {
        if (refusedBlocked) {
            Liveness.serviceGone()
            super.onDestroy()
            return
        }
        isAlive = false
        beatHandle?.stop(); beatHandle = null
        watch?.cancel(); sleeper?.cancel()
        watchWatchdog?.markStopped(); watchWatchdog = null
        sleeperWatchdog?.markStopped(); sleeperWatchdog = null
        Tailnet.stop("service destroyed")
        PublicAccess.onServiceDestroyed(applicationContext)
        com.kilon.agentvscreen.host.quiet("HostService:167") { getSystemService(NotificationManager::class.java)?.cancel(STOP_NOTE_ID) }
        Overlay.detach()
        WaitingPill.detach()
        Presence.stop()
        // On demand the server (and Gate) stay so the next request wakes the service; off (BLOCKED), dropServer stops Gate too.
        if (ServiceControl.now(this) == ServiceControl.State.BLOCKED) dropServer() else server?.onChange = null
        Liveness.serviceGone()
        if (!Prefs.serviceOn(this)) BackgroundOperation.setEnabled(false)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, tr("Agent-VScreen 服务", "Agent-VScreen service"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE, com.kilon.agentvscreen.ui.MainDisplay.options())
        val stop = PendingIntent.getBroadcast(this, 1, Intent(this, NoteActions::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val err = server?.lastError ?: Liveness.problem()
        val s = BackgroundOperation.state.value
        val ts = Tailnet.status.value
        val where = "127.0.0.1:${ApiConfig.port}" + (if (ts.loggedIn) " · Tailscale ${ts.ip}" else "")
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
            .setContentTitle(when {
                s.userStopped -> tr("Agent-VScreen · agent 已停止", "Agent-VScreen · agent stopped")
                s.windowHidden && s.displayId != null -> tr("Agent-VScreen · agent 正在后台工作（小窗已隐藏）", "Agent-VScreen · agent working in the background (mini window hidden)")
                ServiceControl.awakeOnDemand(this) -> tr("Agent-VScreen · 按需运行中，空闲后自动停止", "Agent-VScreen · running on demand, stops when idle")
                else -> tr("Agent-VScreen 服务运行中", "Agent-VScreen service running")
            })
            .setContentText(err ?: "$where · CLI / HTTP / MCP")
            .setContentIntent(open)
            .setOngoing(true)
        if (s.windowHidden && s.displayId != null)
            b.addAction(0, tr("显示小窗", "Show window"), PendingIntent.getBroadcast(this, 5, Intent(this, NoteActions::class.java).setAction(ACTION_SHOW_WINDOW), PendingIntent.FLAG_IMMUTABLE))
        else b.addAction(0, tr("查看副屏", "View screen"), PendingIntent.getActivity(this, 2, com.kilon.agentvscreen.ui.Surface.liveIntent(this), PendingIntent.FLAG_IMMUTABLE, com.kilon.agentvscreen.ui.MainDisplay.options()))
        // 1.3.0: the button says what it does to remote access: with the built-in Tailscale on, hiding stops it.
        val remote = Tailnet.Mode.BUILTIN == com.kilon.agentvscreen.api.ApiConfig.tailnetMode
        return b.addAction(0, if (remote) tr("关闭（远程断开）", "Off (remote goes offline)") else tr("关闭", "Off"), stop).build()
    }
}

/** Small app-level switches. */
object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("agent_vscreen", Context.MODE_PRIVATE)
    fun serviceOn(ctx: Context) = p(ctx).getBoolean("service_on", true)
    fun setServiceOn(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("service_on", on).apply() }
    /**
     * 1.3 beta2: the service is off only until the next booked window ("pause"), not for good. Only the user sets it
     * (the switch on the home page); a booking alone never turns a service the user switched off back on.
     */
    /** 1.3 beta4: the user blocked agent access (really off; nothing wakes the service). */
    fun blocked(ctx: Context) = p(ctx).getBoolean("agents_blocked", false)
    fun setBlocked(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("agents_blocked", on).apply() }
}
