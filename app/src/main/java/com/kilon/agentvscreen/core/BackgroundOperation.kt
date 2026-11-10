package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.kilon.agentvscreen.host.VScreenAccessibilityService
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [vscreen] 后台操作 — the coordinator behind Settings › 会话 › 后台操作,
 * the in-chat preview window and the `vscreen` agent command.
 *
 * Rules this object enforces in code (not in the prompt):
 *  - Switching the feature on does NOT connect, and never grants Shizuku,
 *    accessibility or wireless debugging by itself.
 *  - While the feature is off, not connected, or the connection failed,
 *    every action is refused. Nothing ever falls back to the main screen.
 *  - One background screen at a time, owned by one chat session. Another
 *    session gets BUSY instead of sharing it.
 *  - Every action names the screen generation it was planned against, so a
 *    late action from a finished task cannot land on a newer screen.
 *  - When the user takes over (接管), agent actions wait; after the user
 *    hands back, the agent must read the screen again before acting.
 *  - A normal task end does NOT destroy the screen: it stays (with the app
 *    and the page it was on) for [IDLE_TTL_MS] so the next turn can pick up
 *    where this one stopped. After that idle time it is released; the
 *    connection is kept either way. Another session that needs the screen
 *    while it only idles takes it over. 停止并释放, turning the feature
 *    off, or a connection failure require an explicit reconnect.
 */
object BackgroundOperation {
    private const val TAG = "BackgroundOperation"
    private const val PREFS = "vscreen_background_operation"
    private const val K_ENABLED = "enabled"
    private const val K_PREVIEW = "show_preview"
    private const val K_ACCURATE_OCR = "accurate_ocr_enabled"
    private const val K_RECORDING = "recording_enabled"
    private const val K_WIN_SCALE = "window_scale"
    private const val K_WIN_RIGHT = "window_right"
    private const val K_WIN_Y = "window_y_fraction"
    private const val K_SCREEN_SIZE = "screen_size"

    /** How long a screen survives with no task using it. */
    const val IDLE_TTL_MS = 10 * 60_000L

    enum class Connection { DISCONNECTED, CONNECTING, READY, ERROR }

    enum class Privilege { NOT_INSTALLED, NOT_RUNNING, NOT_AUTHORIZED, READY }

    data class State(
        val enabled: Boolean = false,
        val showPreview: Boolean = true,
        /** 精确 OCR: read / tap --text use PP-OCRv6 (on-device); ML Kit stays for fast paths. */
        val accurateOcr: Boolean = true,
        /** 后台录屏 (v42): keep a recording track of the background screen for `rewind` (deleted on release). */
        val recording: Boolean = true,
        val connection: Connection = Connection.DISCONNECTED,
        val connectionError: String? = null,
        /** True after 停止并释放 / feature off / failure: the agent may not auto-connect. */
        val needsExplicitReconnect: Boolean = false,
        val displayId: Int? = null,
        val generation: Long = 0,
        val ownerSessionId: String? = null,
        val privilege: Privilege = Privilege.NOT_INSTALLED,
        val accessibilityConnected: Boolean = false,
        val screenWidth: Int = 0,
        val screenHeight: Int = 0,
        val screenDpi: Int = 0,
        /** Session whose user currently holds the screen (接管), or null. */
        val takeoverSessionId: String? = null,
        /** An agent action is executing right now (drives the 操作中 badge). */
        val operating: Boolean = false,
        /** v27: what the agent is doing right now (shown on the preview badge, like a window title). */
        val action: String? = null,
        /** The owning chat's turn has ended: the screen only idles until the TTL (the window collapses). */
        val idle: Boolean = false,
        /** v21: the idle screen is paused (display off, apps stopped) to save power; any command wakes it. */
        val powerSaving: Boolean = false,
        val note: String? = null,
        /** 1.1: the user pressed Stop on the window: every agent action is refused until the user hands back. */
        val userStopped: Boolean = false,
        /** 1.1.6: when the user pressed Stop (wall-clock ms; 0 = not stopped). Shown to the agent and in the notification. */
        val stoppedAt: Long = 0L,
        /**
         * 1.1.6: the user closed the floating window with × while the AI was still working. Hiding the window is only
         * about the user's view: it never stops or locks the AI. The window comes back with the agent's next turn, or
         * from the notification (显示小窗).
         */
        val windowHidden: Boolean = false,
        /** 1.1: what the agent says it is doing (`vscreen title`), shown on the window's pill; cleared with the turn. */
        val agentTitle: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    private lateinit var prefs: SharedPreferences
    private val lock = Any()
    @Volatile private var display: VScreenDisplay? = null

    /** Per-session task bookkeeping (re-read gate). */
    private class TaskInfo(
        val sessionId: String,
        @Volatile var rereadRequired: Boolean = false,
        /** Why a re-read is required (null = the user took over). */
        @Volatile var rereadReason: String? = null,
    )
    private val tasks = HashMap<String, TaskInfo>()
    /** Adoption of a screen left by a previous process is tried once per process. */
    @Volatile private var adoptTried = false
    /** Set when this process adopted a screen left by a dead one (shown by status). */
    @Volatile var hostRestartNote: String? = null
    private const val K_HOST_PID = "host_pid"
    private fun rememberHostPid() { runCatching { prefs.edit().putInt(K_HOST_PID, android.os.Process.myPid()).apply() } }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    /** Elapsed-realtime when the idle screen will be released, 0 = not scheduled. */
    @Volatile var idleReleaseAt: Long = 0L
        private set
    private val idleRelease = Runnable {
        Thread {
            val owner = _state.value.ownerSessionId
            val busy = owner != null && synchronized(lock) { tasks.containsKey(owner) }
            if (!busy && holds == 0) releaseScreen(tr("空闲 ${IDLE_TTL_MS / 60_000} 分钟，已释放画面（保留连接）", "Idle for ${IDLE_TTL_MS / 60_000} minutes, picture released (connection kept)"))
            idleReleaseAt = 0L
            // A background rule is watching this screen: keep it, look again later.
            if (holds > 0 && display != null) mainHandler.post { scheduleIdleRelease() }
        }.apply { isDaemon = true; name = "vscreen-idle-release" }.start()
    }

    private fun scheduleIdleRelease() {
        mainHandler.removeCallbacks(idleRelease)
        idleReleaseAt = SystemClock.elapsedRealtime() + IDLE_TTL_MS
        mainHandler.postDelayed(idleRelease, IDLE_TTL_MS)
    }

    // ── v21: idle power saving ─────────────────────────────────────────────

    /** How long an idle screen keeps running before it is paused. */
    const val PARK_AFTER_MS = 30_000L

    private val idlePark = Runnable {
        Thread {
            val s = _state.value
            val owner = s.ownerSessionId
            val busy = owner != null && synchronized(lock) { tasks.containsKey(owner) }
            val d = display
            if (!busy && holds == 0 && s.idle && s.takeoverSessionId == null && d != null && !d.released && VScreenStore.idlePowerSave.value) {
                if (d.park()) {
                    _state.update { it.copy(powerSaving = true) }
                    AppLogger.info(TAG, "idle display ${d.displayId} paused to save power")
                }
            }
        }.apply { isDaemon = true; name = "vscreen-idle-park" }.start()
    }

    private fun scheduleIdlePark() {
        mainHandler.removeCallbacks(idlePark)
        if (VScreenStore.idlePowerSave.value) mainHandler.postDelayed(idlePark, PARK_AFTER_MS)
    }

    /** Wake a paused screen (display back on, frames flowing). Returns a note when it had been paused. */
    fun unpark(d: VScreenDisplay): String? {
        mainHandler.removeCallbacks(idlePark)
        if (!d.parked) return null
        val r = d.unpark()
        _state.update { it.copy(powerSaving = false) }
        return r
    }

    /** The settings switch: turning it off wakes a paused screen at once. */
    fun setIdlePowerSave(on: Boolean) {
        VScreenStore.setIdlePowerSave(on)
        if (!on) display?.let { d -> Thread { runCatching { unpark(d) } }.apply { isDaemon = true }.start() }
        else if (_state.value.idle) scheduleIdlePark()
    }

    // ── background rules hold the idle screen ─────────────────────────────

    /** Background rules watching the screen between turns: while > 0 the idle screen is neither paused nor released. */
    @Volatile private var holds = 0
    /** Sessions with a chat turn running right now (onTaskStarted .. onTaskFinished). */
    private val turns = HashSet<String>()

    fun hold(on: Boolean) {
        val now = synchronized(lock) { holds = (holds + if (on) 1 else -1).coerceAtLeast(0); holds }
        if (on) {
            VScreenAwake.kick(tr("盯守", "Watching"))
            mainHandler.removeCallbacks(idlePark)
            display?.takeIf { it.parked }?.let { d -> runCatching { unpark(d) } }
        } else if (now == 0 && _state.value.idle && display != null) {
            mainHandler.post { scheduleIdleRelease(); scheduleIdlePark() }
        }
    }

    fun holding(): Int = holds

    /** A background rule ran a command outside a chat turn: the screen goes back to idle (it is not a running task). */
    fun backgroundDone(sid: String) {
        if (synchronized(lock) { sid in turns }) return
        synchronized(lock) { tasks.remove(sid) }
        if (_state.value.ownerSessionId == sid && display != null) {
            _state.update { it.copy(operating = false, idle = true) }
            mainHandler.post { scheduleIdleRelease(); if (holds == 0) scheduleIdlePark() }
        }
    }

    private fun cancelIdleRelease() {
        if (idleReleaseAt == 0L) return
        mainHandler.removeCallbacks(idleRelease)
        idleReleaseAt = 0L
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        VScreenDownscale.init(context)
        VScreenStore.init(context)
        VScreenMap.init(context)
        VScreenRules.init(context)
        VScreenAwake.init(context)
        runCatching {
            val home = context.packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0,
            )?.activityInfo?.packageName
            VScreenShell.protectedPackages = setOfNotNull(context.packageName, "com.android.systemui", "android", home)
        }
        _state.update {
            it.copy(
                enabled = prefs.getBoolean(K_ENABLED, false),
                showPreview = prefs.getBoolean(K_PREVIEW, true),
                accurateOcr = prefs.getBoolean(K_ACCURATE_OCR, true),
                recording = prefs.getBoolean(K_RECORDING, true),
            )
        }
        // Tracks of a previous process belong to screens that are gone.
        VScreenRecorder.clearAll()
        refreshStatus()
    }

    private fun ctx(): Context = appContext ?: error("BackgroundOperation.init was not called")

    fun appContextOrNull(): Context? = appContext

    // ── settings ───────────────────────────────────────────────────────────

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        if (!on) {
            // Off = stop everything; using it again needs an explicit connect.
            stopAndRelease(reason = tr("已关闭后台操作", "Background operation turned off"))
            _state.update { it.copy(enabled = false) }
        } else {
            // Enabling is not connecting: a previous 停止并释放 / off still needs 连接.
            _state.update { it.copy(enabled = true, connectionError = null) }
        }
    }

    fun setAccurateOcr(on: Boolean) {
        prefs.edit().putBoolean(K_ACCURATE_OCR, on).apply()
        _state.update { it.copy(accurateOcr = on) }
    }

    fun setRecording(on: Boolean) {
        prefs.edit().putBoolean(K_RECORDING, on).apply()
        _state.update { it.copy(recording = on) }
    }

    fun setShowPreview(on: Boolean) {
        prefs.edit().putBoolean(K_PREVIEW, on).apply()
        _state.update { it.copy(showPreview = on) }
    }

    /** Re-read Shizuku, accessibility and main-screen metrics. Cheap; call from UI. */
    fun refreshStatus() {
        val context = appContext ?: return
        ShizukuManager.refresh()
        val snap = ShizukuManager.snapshot.value
        val priv = when (snap.state) {
            ShizukuManager.State.READY -> Privilege.READY
            ShizukuManager.State.NEED_PERMISSION -> Privilege.NOT_AUTHORIZED
            ShizukuManager.State.NOT_RUNNING -> Privilege.NOT_RUNNING
            ShizukuManager.State.NOT_INSTALLED -> Privilege.NOT_INSTALLED
        }
        if (priv == Privilege.READY) VScreenPower.ensureOnce(context)
        val (mw, mh, dpi) = mainScreenMetrics(context)
        val live = display?.takeIf { !it.released }
        val w = live?.width ?: mw; val h = live?.height ?: mh
        _state.update {
            var next = it.copy(
                privilege = priv,
                accessibilityConnected = VScreenAccessibilityService.getInstance() != null,
                screenWidth = w, screenHeight = h, screenDpi = dpi,
            )
            // A READY connection whose privilege disappeared is no longer real.
            if (next.connection == Connection.READY && priv != Privilege.READY) {
                next = next.copy(
                    connection = Connection.ERROR,
                    connectionError = tr("特权连接已断开（Shizuku ${priv.label()}）", "Privileged connection lost (Shizuku ${priv.label()})"),
                    needsExplicitReconnect = true,
                )
            }
            next
        }
        if (_state.value.connection == Connection.ERROR && display != null) releaseScreen(tr("连接失效", "Connection lost"))
    }

    // ── connection ─────────────────────────────────────────────────────────

    /** Explicit 连接. Verifies the privileged channel for real; does not create a screen. */
    fun connect(): State {
        if (!_state.value.enabled) {
            _state.update { it.copy(connection = Connection.ERROR, connectionError = tr("请先启用后台操作", "Turn on background operation first")) }
            return _state.value
        }
        _state.update { it.copy(connection = Connection.CONNECTING, connectionError = null) }
        refreshStatus()
        val s = _state.value
        val error: String? = when (s.privilege) {
            Privilege.NOT_INSTALLED -> tr("未安装 Shizuku：请用户安装并启动 Shizuku（或 Sui），再打开 Agent-VScreen 授权", "Shizuku is not installed: ask the user to install and start Shizuku (or Sui), then grant it in Agent-VScreen")
            Privilege.NOT_RUNNING -> tr("Shizuku 服务未启动，请用无线调试启动后重试", "The Shizuku service is not running: start it (e.g. with wireless debugging) and retry")
            Privilege.NOT_AUTHORIZED -> tr("Agent-VScreen 尚未获得 Shizuku 授权", "Agent-VScreen has no Shizuku permission yet")
            Privilege.READY -> {
                val r = VScreenShell.run(arrayOf("id"), 5_000L)
                if (r.exitCode != 0) tr("特权通道无响应：${r.combined.take(160)}", "The privileged channel does not answer: ${r.combined.take(160)}")
                else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) tr("需要 Android 10 及以上（副屏输入定位）", "Needs Android 10 or later (input targeting on the screen)")
                else null
            }
        }
        _state.update {
            if (error == null) it.copy(connection = Connection.READY, connectionError = null, needsExplicitReconnect = false)
            else it.copy(connection = Connection.ERROR, connectionError = error, needsExplicitReconnect = true)
        }
        AppLogger.info(TAG, "connect -> ${_state.value.connection} ${error ?: ""}")
        return _state.value
    }

    /** 停止并释放: stop operating, destroy the screen; reuse needs an explicit connect. */
    fun stopAndRelease(reason: String = tr("已停止并释放", "Stopped and released")) {
        releaseScreen(reason)
        synchronized(lock) { tasks.clear() }
        _state.update {
            it.copy(
                connection = Connection.DISCONNECTED,
                connectionError = null,
                needsExplicitReconnect = true,
                takeoverSessionId = null,
                operating = false,
                note = reason,
            )
        }
    }

    /** Apps whose resolution downscaling we turned off for the background screen; restored on release. */
    private val unscaled = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Turn off vendor/game downscaling for [pkg] until the screen is released. */
    /** [changed] = a setting really changed, so a cold start can help; otherwise do not restart. */
    data class DownscaleFix(val changed: Boolean, val status: String, val text: String)

    fun disableDownscale(pkg: String): DownscaleFix {
        val aps = runCatching { VScreenDownscale.apsDisable(pkg) }
            .getOrElse { VScreenDownscale.Outcome(VScreenDownscale.Status.FAILED, tr("荣耀APS：${it.message}", "Honor APS: ${it.message}")) }
        return when (aps.status) {
            VScreenDownscale.Status.CHANGED -> { unscaled.add(pkg); DownscaleFix(true, "aps_changed", aps.text) }
            // No vendor APS on this phone (vivo, Xiaomi, ...): Android's own per-app switches are the lever.
            VScreenDownscale.Status.UNSUPPORTED -> { unscaled.add(pkg); DownscaleFix(true, "android_switches", VScreenShell.disableDownscale(pkg)) }
            VScreenDownscale.Status.DENIED -> DownscaleFix(false, "permission_denied", aps.text)
            VScreenDownscale.Status.ALREADY_FULL -> DownscaleFix(false, "not_from_aps", aps.text)
            VScreenDownscale.Status.FAILED -> DownscaleFix(false, "failed", aps.text)
        }
    }

    /** Was downscaling already turned off for [pkg] on this screen? */
    fun isUnscaled(pkg: String): Boolean = unscaled.contains(pkg)

    fun currentDisplayId(): Int? = display?.takeIf { !it.released }?.displayId

    /** v0.5: the live background screen (null when none). */
    fun currentDisplay(): VScreenDisplay? = display?.takeIf { !it.released }

    /** v0.5: work is pending on the background screen — the owner's chat turn is running, or a watch holds it. */
    fun needsAwake(): Boolean {
        val d = display ?: return false
        if (d.released) return false
        val owner = _state.value.ownerSessionId
        return holds > 0 || synchronized(lock) { owner != null && owner in turns }
    }

    private fun restoreDownscale() {
        val pk = synchronized(unscaled) { unscaled.toList().also { unscaled.clear() } }
        val leftovers = VScreenDownscale.pending()
        if (pk.isNotEmpty() || leftovers.isNotEmpty()) Thread {
            pk.forEach { VScreenShell.resetDownscale(it) }
            VScreenDownscale.restoreAll()
        }.apply { isDaemon = true }.start()
    }

    /** `screen downscale restore`: put every saved setting back now (screen stays). */
    fun restoreDownscaleNow(): List<String> {
        val pk = synchronized(unscaled) { unscaled.toList().also { unscaled.clear() } }
        pk.forEach { VScreenShell.resetDownscale(it) }
        return pk.map { tr("已重置 $it 的 Android 降分辨率开关", "Reset Android's downscaling switch for $it") } + VScreenDownscale.restoreAll()
    }

    private val releaseListeners = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()

    /** Called with the display id after a screen is released, so per-display caches can be dropped. */
    fun onScreenReleased(listener: (Int) -> Unit) { releaseListeners += listener }

    private fun releaseScreen(reason: String) {
        cancelIdleRelease()
        mainHandler.removeCallbacks(idlePark)
        _state.update { it.copy(powerSaving = false) }
        val d = synchronized(lock) { display.also { display = null } } ?: return
        // Close whatever app was left on the screen so it does not hop to the
        // main display when its display disappears.
        runCatching { VScreenShell.finger(d.displayId, "cancel", 0, -1, -1) }
        runCatching { VScreenShell.closeAllOnDisplay(d.displayId) }
            .onSuccess { AppLogger.info(TAG, "release close: ${it.message}") }
        d.release()
        releaseListeners.forEach { l -> runCatching { l(d.displayId) }.onFailure { AppLogger.warning(TAG, "release listener: ${it.message}") } }
        restoreDownscale()
        // The page the flags were about is gone with the screen: the next screen starts with nothing to re-read.
        synchronized(lock) { tasks.values.forEach { it.rereadRequired = false; it.rereadReason = null } }
        _state.update { it.copy(displayId = null, ownerSessionId = null, takeoverSessionId = null, operating = false, note = reason) }
        AppLogger.info(TAG, "released display ${d.displayId} gen=${d.generation}: $reason")
    }

    // ── task lifecycle (driven by ChatViewModel's isStreaming edge) ────────

    fun onTaskStarted(sessionId: String) {
        if (sessionId.isEmpty()) return
        if (_state.value.ownerSessionId == sessionId) {
            cancelIdleRelease(); mainHandler.removeCallbacks(idlePark); _state.update { it.copy(idle = false) }
            display?.takeIf { it.parked }?.let { d -> Thread { runCatching { unpark(d) } }.apply { isDaemon = true }.start() }
        }
        synchronized(lock) {
            tasks[sessionId] = TaskInfo(sessionId)
            turns.add(sessionId)
        }
        // A window the user hid during the previous turn comes back for the new one.
        if (_state.value.windowHidden) _state.update { it.copy(windowHidden = false) }
        VScreenAwake.kick(tr("任务开始", "Task started"))
    }

    /**
     * Normal end, stop, or error of a chat turn: keep the screen (and the
     * page it is on) for [IDLE_TTL_MS], then release it. The connection is kept.
     */
    fun onTaskFinished(sessionId: String) {
        if (sessionId.isEmpty()) return
        synchronized(lock) { tasks.remove(sessionId); turns.remove(sessionId) }
        // A finger the agent left down (run … --keep without a release) never outlives the turn.
        if (_state.value.ownerSessionId == sessionId) display?.let { d ->
            Thread { runCatching { VScreenShell.finger(d.displayId, "cancel", 0, -1, -1) } }.apply { isDaemon = true }.start()
        }
        if (_state.value.ownerSessionId == sessionId && display != null) {
            // A user takeover ends with the turn: nobody is holding the screen any more.
            _state.update { it.copy(takeoverSessionId = null, operating = false, idle = true, agentTitle = null, note = tr("本轮结束，副屏保留 ${IDLE_TTL_MS / 60_000} 分钟", "Turn over; the screen is kept for ${IDLE_TTL_MS / 60_000} minutes")) }
            scheduleIdleRelease()
            scheduleIdlePark()
        }
    }

    /** `vscreen release`: destroy the screen now (connection kept). */
    fun releaseNow(sessionId: String) {
        if (_state.value.ownerSessionId == sessionId) releaseScreen(tr("已按要求释放画面（保留连接）", "Picture released as asked (connection kept)"))
    }

    // ── takeover (接管) ────────────────────────────────────────────────────

    fun beginTakeover(sessionId: String): Boolean {
        val s = _state.value
        if (s.displayId == null || s.ownerSessionId != sessionId) return false
        mainHandler.removeCallbacks(idlePark)
        display?.takeIf { it.parked }?.let { d -> Thread { runCatching { unpark(d) } }.apply { isDaemon = true }.start() }
        _state.update { it.copy(takeoverSessionId = sessionId) }
        return true
    }

    /** 1.1: the user's Stop: refuse the agent (USER_STOPPED) until [userResume]; a finger the agent holds is lifted. */
    fun userStop() {
        val d = display
        _state.update { it.copy(userStopped = true, stoppedAt = System.currentTimeMillis(), takeoverSessionId = null, operating = false) }
        d?.let { Thread { runCatching { VScreenShell.finger(it.displayId, "cancel", 0, -1, -1) } }.apply { isDaemon = true }.start() }
        AppLogger.info(TAG, "user stop")
    }

    /** 1.1: hand back to the agent after Stop or takeover; its next action is told to look first. */
    fun userResume() {
        val s = _state.value
        synchronized(lock) { s.ownerSessionId?.let { tasks[it]?.rereadRequired = true } }
        _state.update { it.copy(userStopped = false, stoppedAt = 0L, takeoverSessionId = null) }
        AppLogger.info(TAG, "user allowed the AI to continue")
    }

    /**
     * 1.1.6: the window's ×, always one tap. It only answers "I don't want to look at this now":
     *  - while a turn runs (AI acting / waiting / you holding the screen): the window goes away for the rest of this turn,
     *    the AI keeps working in the background (a takeover is handed back: a hidden screen cannot be driven);
     *  - when nothing runs (idle, or you stopped the AI): the screen itself is closed; the agent's next launch builds a
     *    new one. A Stop lock is NOT lifted by this: it stays, and the notification keeps saying so.
     * Returns what happened, for a short toast.
     */
    fun closeWindow(): String {
        val s = _state.value
        val owner = s.ownerSessionId ?: return tr("没有副屏", "No screen")
        val turnRunning = synchronized(lock) { owner in turns }
        if (s.userStopped || !turnRunning) {
            releaseScreen(if (s.userStopped) tr("你从悬浮窗关闭了副屏（AI 仍处于停止状态）", "You closed the screen from the floating window (the AI is still stopped)") else tr("你从悬浮窗关闭了副屏", "You closed the screen from the floating window"))
            _state.update { it.copy(windowHidden = false) }
            return if (s.userStopped) tr("副屏已关闭。AI 仍是停止状态：要让它继续，在通知里点“允许继续”", "Screen closed. The AI is still stopped: to let it go on, tap \"Allow again\" in the notification") else tr("副屏已关闭", "Screen closed")
        }
        if (s.takeoverSessionId != null) {
            synchronized(lock) { tasks[owner]?.rereadRequired = true }
            _state.update { it.copy(takeoverSessionId = null) }
        }
        _state.update { it.copy(windowHidden = true) }
        AppLogger.info(TAG, "window hidden by the user (AI keeps working)")
        return tr("小窗已收起，AI 继续在后台工作。通知栏的“显示小窗”可以再打开", "Window hidden; the AI keeps working in the background. \"Show window\" in the notification brings it back")
    }

    /** 1.1.6: bring the hidden window back (notification action, app home). */
    fun showWindow() { _state.update { it.copy(windowHidden = false) } }

    /**
     * 1.1.6: one text for every USER_STOPPED refusal, so the agent can tell the user exactly when it happened and how to
     * undo it (the user may not have seen the app since). The agent itself can never lift it.
     */
    fun userStoppedMessage(): String {
        val at = _state.value.stoppedAt
        val whenText = if (at > 0) java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(at)) + " " else ""
        return tr("用户在 ${whenText}按了悬浮窗上的“停止 AI”，所以你的操作现在都会被拒绝（动作没有执行）。这是用户的安全开关，你解除不了，不要重试。" +
            "请原样转告用户：要让我继续，下拉通知栏，在“已停止 AI”那条通知上点“允许继续”；也可以点悬浮窗上的“交还 AI”，或打开 Agent-VScreen 首页点“允许继续”。",
            "The user pressed \"Stop AI\" on the floating window ${if (whenText.isNotEmpty()) "at $whenText" else ""}, so all your actions are refused now (nothing was done). This is the user's safety switch: you cannot lift it, don't retry. " +
            "Tell the user exactly this: to let me go on, pull down the notifications and tap \"Allow again\" on the \"AI stopped\" notification; or tap \"Hand back\" on the floating window, or open Agent-VScreen and tap \"Allow again\" on its home page.")
    }

    fun setTitle(sessionId: String, title: String?) {
        if (_state.value.ownerSessionId != null && _state.value.ownerSessionId != sessionId) return
        _state.update { it.copy(agentTitle = title?.trim()?.take(40)?.takeIf { t -> t.isNotEmpty() }) }
    }

    fun endTakeover(sessionId: String) {
        if (_state.value.takeoverSessionId != sessionId) return
        synchronized(lock) { tasks[sessionId]?.rereadRequired = true }
        _state.update { it.copy(takeoverSessionId = null) }
    }

    /** User touch while holding the screen. Maps preview coordinates already converted to screen px. */
    fun userTap(sessionId: String, x: Int, y: Int) = userInput(sessionId) { VScreenShell.tap(it, x, y) }

    fun userSwipe(sessionId: String, x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) =
        userInput(sessionId) { VScreenShell.swipe(it, x1, y1, x2, y2, ms) }

    fun userBack(sessionId: String) = userInput(sessionId) { VScreenShell.key(it, "back") }

    private fun userInput(sessionId: String, op: (Int) -> VScreenShell.Outcome) {
        val s = _state.value
        val id = s.displayId ?: return
        if (s.takeoverSessionId != sessionId) return
        Thread { runCatching { op(id) } }.apply { isDaemon = true; name = "vscreen-user-input" }.start()
    }

    // ── agent side ─────────────────────────────────────────────────────────

    class ActionError(val code: String, message: String) : Exception(message)

    /** Current screen if [sessionId] may act on it; creates one when allowed. */
    @Throws(ActionError::class)
    fun acquire(sessionId: String?, create: Boolean, landscape: Boolean? = null, scale: Double = 1.0): VScreenDisplay {
        val d = acquireInner(sessionId, create, landscape, scale)
        mainHandler.removeCallbacks(idlePark)
        if (d.parked) unpark(d)?.let { _state.update { st -> st.copy(note = it) } }
        return d
    }

    private fun acquireInner(sessionId: String?, create: Boolean, landscape: Boolean?, scale: Double): VScreenDisplay {
        val sid = sessionId?.takeIf { it.isNotEmpty() }
            ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
        val s0 = _state.value
        if (!s0.enabled) throw ActionError("DISABLED", tr("Agent-VScreen 的服务已被用户关闭。请用户打开 Agent-VScreen 点“启动服务”", "The user turned off the Agent-VScreen service. Ask the user to open Agent-VScreen and turn the service on"))
        if (s0.connection != Connection.READY) {
            // Standalone: only the user's own 停止并释放 needs the user; a lost Shizuku that came back reconnects here.
            if (s0.needsExplicitReconnect && s0.connection == Connection.DISCONNECTED) {
                throw ActionError(
                    "NOT_CONNECTED",
                    tr("后台连接未就绪（${s0.connectionError ?: "需要明确重连"}）。请用户打开 Agent-VScreen 点“连接”，或执行 vscreen fix not_connected。不会改为在主屏操作。",
                        "The background connection is not ready (${s0.connectionError ?: "needs an explicit reconnect"}). Ask the user to open Agent-VScreen, or run vscreen fix not_connected. It will not fall back to the main screen."),
                )
            }
            // Enabled, never connected and never stopped: verify the channel once.
            val after = connect()
            if (after.connection != Connection.READY) {
                throw ActionError("NOT_CONNECTED", tr("后台连接失败：${after.connectionError}。不会改为在主屏操作。", "Background connection failed: ${after.connectionError}. It will not fall back to the main screen."))
            }
        }
        synchronized(lock) {
            val cur = display
            val s = _state.value
            if (cur != null && !cur.released) {
                if (s.ownerSessionId == sid) {
                    cancelIdleRelease()
                    if (s.idle) _state.update { it.copy(idle = false) }
                    return cur
                }
                val ownerBusy = s.ownerSessionId != null && s.ownerSessionId != sid && tasks.containsKey(s.ownerSessionId)
                if (ownerBusy) throw busy(s.ownerSessionId)
                // The other session's screen is only idling (or its owner is gone): take it over
                // as it is, page included. Never build a second screen next to it.
                cancelIdleRelease()
                val prev = s.ownerSessionId
                if (!tasks.containsKey(sid)) tasks[sid] = TaskInfo(sid)
                _state.update {
                    it.copy(ownerSessionId = sid, idle = false, takeoverSessionId = null,
                        note = tr("接手了上一轮留下的副屏（display ${cur.displayId}）", "Took over the screen left by the last turn (display ${cur.displayId})"))
                }
                AppLogger.info(TAG, "session $sid took over idle display ${cur.displayId} from $prev")
                return cur
            }
        }
        synchronized(lock) {
            val cur = display
            val s = _state.value
            if (cur != null && !cur.released) {
                if (s.ownerSessionId != sid) throw busy(s.ownerSessionId)
                return cur
            }
            // A previous process of this app may have left its screen in the Shizuku service: adopt it.
            if (!adoptTried) {
                adoptTried = true
                val existing = VScreenRemoteDisplay.list().getOrNull().orEmpty()
                // No screen survived a crash: settings saved for it are stale, put them back first.
                if (existing.isEmpty()) runCatching { VScreenDownscale.restoreAll() }
                existing.drop(1).forEach { runCatching { VScreenRemoteDisplay.release(it.displayId) } }
                existing.firstOrNull()?.let { e ->
                    runCatching { VScreenDisplay.adopt(e) }
                        .onFailure { AppLogger.warning(TAG, "adopt failed: ${it.message}"); runCatching { VScreenRemoteDisplay.release(e.displayId) } }
                        .getOrNull()
                }?.let { d ->
                    display = d
                    setupGeometry(d)
                    if (!tasks.containsKey(sid)) tasks[sid] = TaskInfo(sid)
                    val prevPid = runCatching { prefs.getInt(K_HOST_PID, 0) }.getOrDefault(0)
                    val why = tr("Agent-VScreen 进程重启过（${if (prevPid > 0) "原进程 $prevPid，" else ""}现进程 ${android.os.Process.myPid()}），" +
                        "上一轮的任务已中断：副屏上的页面、计数和计划都可能和你记得的不一样",
                        "The Agent-VScreen process restarted (${if (prevPid > 0) "old process $prevPid, " else ""}now ${android.os.Process.myPid()}); " +
                        "the last turn's task was cut off: the page, counts and plan on the screen may differ from what you remember")
                    tasks[sid]?.let { it.rereadRequired = true; it.rereadReason = why }
                    hostRestartNote = why
                    rememberHostPid()
                    _state.update {
                        it.copy(displayId = d.displayId, generation = d.generation, ownerSessionId = sid, idle = false,
                            note = tr("接回了上次留下的副屏（$why）", "Picked up the screen left last time ($why)"))
                    }
                    AppLogger.info(TAG, "adopted display ${d.displayId} for $sid")
                    return d
                }
            }
            if (!create) throw ActionError("NO_SCREEN", tr("当前没有后台副屏。先用 `vscreen launch <包名>` 打开应用（会自动建屏）", "There is no background screen. Open an app first with `vscreen launch <package>` (the screen is created automatically)"))
            val (w0, h0, dpi) = screenSizeFor(ctx(), scale)
            // Build the screen in the app's own orientation: a landscape app on a landscape screen
            // never rotates it, so its window, surface and our frames all have one size.
            val (w, h) = when (landscape) { true -> maxOf(w0, h0) to minOf(w0, h0); false -> minOf(w0, h0) to maxOf(w0, h0); null -> w0 to h0 }
            val d = VScreenDisplay.create(ctx(), "agent-vscreen-$sid".take(48), w, h, dpi).getOrElse {
                _state.update { st -> st.copy(note = it.message) }
                throw ActionError("CREATE_FAILED", it.message ?: tr("创建虚拟显示屏失败", "Creating the virtual display failed"))
            }
            display = d
            setupGeometry(d)
            rememberHostPid()
            if (!tasks.containsKey(sid)) tasks[sid] = TaskInfo(sid)
            _state.update {
                it.copy(displayId = d.displayId, generation = d.generation, ownerSessionId = sid, idle = false, note = null)
            }
            // Keyboard must never pop up on the user's screen for this display.
            val ime = runCatching { VScreenShell.hideImeOn(ctx(), d.displayId) }.getOrNull()
            if (ime != null && !ime.ok) AppLogger.warning(TAG, ime.message)
            return d
        }
    }

    // ── orientation / size ─────────────────────────────────────────────────

    private val mapperInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Wire a new screen: preview size follows its upright size; input goes through its frame->input map. */
    private fun setupGeometry(d: VScreenDisplay) {
        if (mapperInstalled.compareAndSet(false, true)) {
            VScreenShell.mapper = { id, x, y -> display?.takeIf { it.displayId == id && !it.released }?.toInput(x, y) ?: (x to y) }
        }
        d.onGeometryChanged = {
            _state.update { st -> if (display === d) st.copy(screenWidth = d.width, screenHeight = d.height) else st }
            AppLogger.info(TAG, "display ${d.displayId}: rotation ${d.rotation * 90}°, frame ${d.width}x${d.height}, input ${d.inputWidth}x${d.inputHeight}, turn ${d.bufferTurn}°")
        }
        if (!d.watchRotation(ctx())) syncRotation(d)
        _state.update { it.copy(screenWidth = d.width, screenHeight = d.height) }
    }

    /** Ask the system for the display's rotation and logical size now (service first, then DisplayManager). */
    fun syncRotation(d: VScreenDisplay) {
        if (d.shellOwned) {
            VScreenRemoteDisplay.rotation(d.displayId).getOrNull()?.let { (r, w, h) -> d.setRotation(r, w, h); return }
        }
        val dm = ctx().getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager ?: return
        val disp = runCatching { dm.getDisplay(d.displayId) }.getOrNull() ?: return
        val pt = android.graphics.Point()
        @Suppress("DEPRECATION") runCatching { disp.getRealSize(pt) }
        d.setRotation(disp.rotation, pt.x, pt.y)
    }

    /** Last orientation check per display: rotation -> scores, for `screen info`. */
    @Volatile var lastCalibration: org.json.JSONObject? = null
        private set

    /**
     * Before an action: current rotation, and (once per rotation) which way up the turned frame
     * really is, judged by how much text OCR can read in each candidate: the default turn, the
     * default turn + 180°, and no turn (a system that letterboxes instead of rotating).
     */
    fun ensureUpright(d: VScreenDisplay, force: Boolean = false) {
        syncRotation(d)
        if (d.turnOverride != null) return
        val rot = d.rotation
        if (rot == 0) return
        if (!force && d.calibrated.contains(rot)) return
        d.requestFreshFrame()
        val since = SystemClock.elapsedRealtime()
        val frame = d.awaitFrameAfter(since, 1_500) ?: return
        val cur = frame.copy(Bitmap.Config.ARGB_8888, false) ?: return
        try {
            val turn = d.bufferTurn
            val sCur = VScreenGame.uprightScore(cur)
            val flipped = VScreenGame.turned(cur, 180)
            val sFlip = VScreenGame.uprightScore(flipped).also { if (flipped !== cur) flipped.recycle() }
            // Undo the current turn to get the raw surface picture.
            val raw = VScreenGame.turned(cur, (360 - turn) % 360)
            val sRaw = if (turn % 180 != 0) VScreenGame.uprightScore(raw) else -1
            if (raw !== cur) raw.recycle()
            val best = maxOf(sCur, sFlip, sRaw)
            var decision = "keep"
            if (best >= 6) {
                when {
                    sRaw == best && sRaw > sCur * 1.5 && sRaw > sFlip * 1.5 -> { d.noTurn = !d.noTurn; d.flip180 = false; decision = "no_turn" }
                    sFlip == best && sFlip > sCur * 1.5 -> { d.flip180 = !d.flip180; decision = "flip180" }
                }
            }
            if (best >= 6 || force) d.calibrated.add(rot)
            if (decision != "keep") { d.requestFreshFrame(); d.onGeometryChanged?.invoke() }
            lastCalibration = org.json.JSONObject().put("rotation", rot * 90).put("score_current", sCur).put("score_flipped", sFlip)
                .put("score_unturned", sRaw).put("decision", decision).put("turn_now", d.bufferTurn)
                .put("note", if (best < 6) tr("画面上可读的文字太少，暂按默认方向；有文字后会再校准，或用 screen rotate 固定", "Too little readable text on the screen, so the default orientation is used for now; it is re-checked once there is text, or fix it with screen rotate") else tr("按可读文字量选择方向", "Orientation chosen by the amount of readable text"))
            AppLogger.info(TAG, "orientation check display ${d.displayId}: $lastCalibration")
        } finally { cur.recycle() }
    }

    /** Size for a new screen: the main screen, or the `screen size` override (same density). */
    private fun screenSizeFor(context: Context, scale: Double = 1.0): Triple<Int, Int, Int> {
        val (w, h, dpi) = mainScreenMetrics(context)
        val pref = if (::prefs.isInitialized) prefs.getString(K_SCREEN_SIZE, null) else null
        val (bw, bh) = parseSize(pref, w, h) ?: (w to h)
        // (A 1080p cap was tried against vendor downscaling and did not help: the ratio is per app.)
        val k = scale
        fun even(x: Double) = (Math.round(x).toInt() / 2) * 2
        return Triple(even(bw * k), even(bh * k), Math.round(dpi * k).toInt().coerceAtLeast(120))
    }

    /** "16:9" / "20:9" / "4:3" keep the short side; "WxH" exact; "phone"/"native"/null = main screen. Portrait (natural) order. */
    fun parseSize(v: String?, mw: Int, mh: Int): Pair<Int, Int>? {
        if (v.isNullOrBlank() || v == "phone" || v == "reset") return null
        val short = minOf(mw, mh)
        Regex("^(\\d+(?:\\.\\d+)?):(\\d+(?:\\.\\d+)?)$").find(v)?.let { m ->
            val a = m.groupValues[1].toDouble(); val b = m.groupValues[2].toDouble()
            if (a <= 0 || b <= 0) return null
            val ratio = maxOf(a, b) / minOf(a, b)
            if (ratio < 1.0 || ratio > 3.0) return null
            val long = (short * ratio).toInt() and 1.inv()
            return short to long
        }
        Regex("^(\\d{3,4})x(\\d{3,4})$").find(v)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            if (a < 320 || b < 320 || a > 4096 || b > 4096) return null
            return minOf(a, b) to maxOf(a, b)
        }
        return null
    }

    fun screenSizePref(): String? = if (::prefs.isInitialized) prefs.getString(K_SCREEN_SIZE, null) else null

    /** `screen size`: remember the size and release the screen so the next launch rebuilds it. */
    fun setScreenSize(sessionId: String?, v: String?) {
        if (!::prefs.isInitialized) return
        if (v == null || v == "phone" || v == "reset") prefs.edit().remove(K_SCREEN_SIZE).apply()
        else prefs.edit().putString(K_SCREEN_SIZE, v).apply()
        if (sessionId != null && _state.value.ownerSessionId == sessionId) releaseScreen(tr("副屏尺寸改为 ${v ?: "跟随手机"}，下次 launch 时按新尺寸重建", "Screen size set to ${v ?: "follow the phone"}; the next launch rebuilds it at that size"))
    }

    /** Set by the transport: when the owner's turn ends ("它的上一条命令 40 秒前结束，再安静 2 分 20 秒自动让出"). */
    @Volatile var describeOwner: (String) -> String = { "" }

    /** 1.1: BUSY says who owns the screen, when it will be free by itself, and how the owner frees it now. */
    private fun busy(owner: String?): ActionError = ActionError("BUSY",
        tr("副屏现在归会话“${owner ?: "?"}”", "The screen belongs to session \"${owner ?: "?"}\"") + (owner?.let { describeOwner(it) } ?: "") +
            tr("。要现在接手：在那个会话执行 vscreen done（结束它这一轮，页面留着给你接手）或 vscreen release（关掉副屏）", ". To take it now: in that session run vscreen done (ends its turn, leaves the page for you) or vscreen release (closes the screen)"))

    fun mainMetrics(): Triple<Int, Int, Int>? = appContext?.let { mainScreenMetrics(it) }

    /** True when the screen was built landscape (wider than tall) before any rotation. */
    fun nativeLandscape(d: VScreenDisplay): Boolean = d.physWidth > d.physHeight

    /**
     * Rebuild this session's screen natively in [landscape] orientation (the app on it is closed).
     * Used when an app would otherwise rotate the screen in place, which can leave its window or
     * surface sized for the old orientation (content cut off on the right/bottom, or squeezed).
     */
    fun rebuild(sessionId: String?, landscape: Boolean, scale: Double = 1.0, reason: String? = null): VScreenDisplay {
        val sid = sessionId?.takeIf { it.isNotEmpty() } ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
        synchronized(lock) {
            val s = _state.value
            if (display != null && s.ownerSessionId != null && s.ownerSessionId != sid && tasks.containsKey(s.ownerSessionId))
                throw busy(s.ownerSessionId)
        }
        releaseScreen(reason ?: if (landscape) tr("按横屏重建副屏（应用横屏运行，避免旋转导致画面溢出/拉伸）", "Rebuilt the screen in landscape (the app runs landscape; avoids overflow / stretching from rotation)") else tr("按竖屏重建副屏", "Rebuilt the screen in portrait"))
        return acquire(sid, create = true, landscape = landscape, scale = scale)
    }

    // ── action log (diagnostics) ───────────────────────────────────────────

    /** One agent action as it happened: what, when, how long, and the verdict or error. No typed text. */
    class LogEntry(val atMs: Long, val sessionId: String, val generation: Long, val describe: String, val ok: Boolean,
                   val code: String?, val effect: String?, val verification: String?, val ms: Long, val read: Boolean) {
        /** v21: small screenshot taken right after the action (timeline), path inside the chat sandbox. */
        @Volatile var shot: String? = null
        /** v43: this action's number on the recording track (rewind's action #N). */
        @Volatile var trackN: Int? = null
    }
    private val actionLogBuf = ArrayDeque<LogEntry>()
    private const val LOG_MAX = 300

    /** v44: one log entry = one numbered mark on the recording track (its n is rewind's action:n). */
    private fun logAction(e: LogEntry, displayId: Int, startedMs: Long) = synchronized(actionLogBuf) {
        VScreenRecorder.mark(displayId, if (e.read) "read" else "action", e.describe, atMs = startedMs).takeIf { it > 0 }?.let { e.trackN = it }
        actionLogBuf.addLast(e)
        while (actionLogBuf.size > LOG_MAX) actionLogBuf.removeFirst()
    }

    /** The last [last] actions of [sessionId] (all sessions when null), oldest first. */
    /** v21: attach the timeline screenshot to [sessionId]'s newest action (taken within [withinMs]). */
    fun attachShot(sessionId: String, path: String, withinMs: Long = 120_000L) = synchronized(actionLogBuf) {
        val e = actionLogBuf.lastOrNull { it.sessionId == sessionId } ?: return@synchronized
        if (e.shot == null && !e.read && System.currentTimeMillis() - e.atMs < withinMs) e.shot = path
    }

    fun actionLog(sessionId: String?, last: Int): List<LogEntry> = synchronized(actionLogBuf) {
        actionLogBuf.filter { sessionId == null || it.sessionId == sessionId }.takeLast(last.coerceIn(1, LOG_MAX))
    }

    /** v42: when the last input action on a display started (epoch ms). */
    val actionStarted = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /**
     * Run one agent action: takeover wait, generation check, the 操作中 badge, the IME guard and the log entry
     * (a numbered mark on the recording track). v49: no planner, no before/after judgement, no PLANNING_LIMIT.
     */
    @Throws(ActionError::class)
    fun <T> agentAction(
        sessionId: String?,
        expectedGeneration: Long?,
        isRead: Boolean,
        describe: String,
        freshPage: Boolean = false,
        block: (VScreenDisplay) -> T,
    ): T {
        val sid = sessionId ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
        // 1.2: what the screen is in right now is one derived value (ScreenPhase), and whether an action may run in
        // it is one table (ScreenPhase.refusal), instead of a chain of flag checks in every caller.
        ScreenPhase.of(_state.value, sid).let { ph -> if (ph == ScreenPhase.STOPPED) throw ActionError("USER_STOPPED", userStoppedMessage()) }
        waitWhileTakenOver(sid)
        val d = acquire(sid, create = false)
        val startedMs = System.currentTimeMillis()
        if (!isRead) actionStarted[d.displayId] = startedMs
        if (d.shellOwned) runCatching { VScreenRemoteDisplay.touch(d.displayId) }
        if (expectedGeneration != null && expectedGeneration != d.generation) {
            throw ActionError("STALE_SCREEN", tr("这个操作是针对旧副屏（第 $expectedGeneration 代）规划的，当前是第 ${d.generation} 代。", "This action was planned for an older screen (generation $expectedGeneration); the current one is generation ${d.generation}."))
        }
        val task = synchronized(lock) { tasks.getOrPut(sid) { TaskInfo(sid) } }
        if (!isRead && !freshPage && task.rereadRequired) {
            val why = task.rereadReason
            if (why != null) throw ActionError("HOST_RESTARTED", tr("$why。动作没有执行：看清现在的画面再继续，不要假设上一轮的步骤已经完成", "$why. The action was not done: look at the screen now before going on, and don't assume the last turn's steps were completed"))
            throw ActionError("REREAD_REQUIRED", tr("用户刚接管过副屏，页面可能已变化。动作没有执行：看清现在的画面再继续", "The user just took over the screen and the page may have changed. The action was not done: look at the screen now before going on"))
        }
        runCatching { ensureUpright(d) }.onFailure { AppLogger.warning(TAG, "orientation check failed: ${it.message}") }
        // v27 IME guard: the system focus (and with it the keyboard) belongs to the user's screen.
        val focus0 = if (!isRead && !describe.startsWith(tr("接力", "Relay"))) focusedNow() else null
        _state.update { it.copy(operating = true, action = describe.take(24)) }
        val tStart = SystemClock.elapsedRealtime()
        try {
            val result = block(d)
            // A read, or an action that starts a new page (launch), leaves nothing stale to re-read.
            if (isRead || freshPage) { task.rereadRequired = false; task.rereadReason = null }
            logAction(LogEntry(System.currentTimeMillis(), sid, d.generation, describe, true, null, null, null,
                SystemClock.elapsedRealtime() - tStart, isRead), d.displayId, startedMs)
            return result
        } catch (e: ActionError) {
            logAction(LogEntry(System.currentTimeMillis(), sid, d.generation, describe, false, e.code, null, e.message?.take(200),
                SystemClock.elapsedRealtime() - tStart, isRead), d.displayId, startedMs)
            throw e
        } finally {
            if (focus0 != null) runCatching { guardFocus(focus0, d.displayId) }
            Handler(Looper.getMainLooper()).postDelayed({ _state.update { it.copy(operating = false) } }, 600)
        }
    }

    /** v27: the agent was handed the current page with a REREAD/HOST_RESTARTED error: that counts as the re-read. */
    fun acknowledgeReread(sessionId: String) = synchronized(lock) {
        tasks[sessionId]?.let { it.rereadRequired = false; it.rereadReason = null }
    }

    /** Times the guard gave the system focus back to the main screen (status: ime_guard). */
    @Volatile var focusRestores = 0
    @Volatile var focusRestoreFailures = 0

    /** (displayId, taskId) with the system focus, only when the service is already connected. */
    private fun focusedNow(): Pair<Int, Int>? =
        if (VScreenRemoteDisplay.isConnected()) VScreenRemoteDisplay.focused().getOrNull()?.takeIf { it.first >= 0 } else null

    /** The action moved the focus from the main screen to [displayId]: hand it back to the task that had it. */
    private fun guardFocus(before: Pair<Int, Int>, displayId: Int) {
        if (before.first != 0 || before.second < 0) return
        val now = focusedNow() ?: return
        if (now.first != displayId) return
        val ok = VScreenRemoteDisplay.focusTask(before.second).getOrNull() == true && focusedNow()?.first == 0
        if (ok) focusRestores++ else focusRestoreFailures++
        AppLogger.info(TAG, "ime guard: focus moved to display $displayId, restored=$ok")
    }

    private fun waitWhileTakenOver(sessionId: String) {
        // v27: 15 s, not 10 min: a held tool call blocks the whole turn (android-vdisplay answers 423 at once).
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        var waited = false
        while (_state.value.takeoverSessionId == sessionId) {
            waited = true
            if (SystemClock.elapsedRealtime() > deadline) {
                throw ActionError("USER_TAKEOVER", tr("用户正在接管副屏（已等 15 秒）。动作没有执行：停下来告诉用户，等用户点“交还”或告诉你继续，再重发；不要反复重试", "The user is controlling the screen (waited 15 s). The action was not done: stop and tell the user; send it again once the user taps \"Hand back\" or tells you to go on. Don't keep retrying"))
            }
            Thread.sleep(300)
        }
        if (waited) {
            throw ActionError("REREAD_REQUIRED", tr("用户已交还副屏。页面可能已变化：这次没有执行，先 act（不带步骤）看一眼再继续。", "The user handed the screen back. The page may have changed: this was not done; look first with act (no steps), then go on."))
        }
    }

    // ── helpers used by the command ───────────────────────────────────────

    fun nodeRoots(displayId: Int): List<AccessibilityNodeInfo> =
        VScreenAccessibilityService.getInstance()?.rootNodesForDisplay(displayId) ?: emptyList()


    /** Visible texts / descriptions of [displayId]'s accessibility tree, or null when the service sees nothing there. */
    fun a11yTexts(displayId: Int, max: Int = 400): List<String>? {
        val roots = nodeRoots(displayId)
        if (roots.isEmpty()) return null
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || out.size >= max) return
            if (n.isVisibleToUser) {
                (n.text ?: n.contentDescription)?.toString()?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it.take(120) }
            }
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        }
        roots.forEach { walk(it, 0) }
        return out
    }

    private fun mainScreenMetrics(context: Context): Triple<Int, Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), context.resources.configuration.densityDpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, m.densityDpi)
        }
    }

    fun Privilege.label(): String = when (this) {
        Privilege.NOT_INSTALLED -> tr("未安装", "not installed")
        Privilege.NOT_RUNNING -> tr("服务未启动", "service not running")
        Privilege.NOT_AUTHORIZED -> tr("未授权", "not granted")
        Privilege.READY -> tr("已连接且已授权", "connected and granted")
    }

    /** The live screen for the preview of [sessionId], or null. */
    fun previewDisplayFor(sessionId: String): VScreenDisplay? {
        val s = _state.value
        if (s.ownerSessionId != sessionId) return null
        return display?.takeIf { !it.released }
    }

    // ── floating window placement (remembered across chats) ───────────────

    // Stored values are validated on the way in AND out: a NaN that an older build saved
    // would otherwise crash the chat (Float.roundToInt) the moment the next window appears.
    private fun prefFloat(key: String, def: Float, min: Float, max: Float): Float {
        if (!::prefs.isInitialized) return def
        val v = runCatching { prefs.getFloat(key, def) }.getOrDefault(def)
        return if (v.isFinite() && v in min..max) v else def
    }
    fun windowScale(): Float = prefFloat(K_WIN_SCALE, 1f, 0.3f, 4f)
    fun windowOnRight(): Boolean = if (::prefs.isInitialized) runCatching { prefs.getBoolean(K_WIN_RIGHT, true) }.getOrDefault(true) else true
    fun windowYFraction(): Float = prefFloat(K_WIN_Y, 0.02f, 0f, 1f)
    fun saveWindow(scale: Float, onRight: Boolean, yFraction: Float) {
        if (!::prefs.isInitialized) return
        val e = prefs.edit().putBoolean(K_WIN_RIGHT, onRight)
        if (scale.isFinite() && scale in 0.3f..4f) e.putFloat(K_WIN_SCALE, scale)
        if (yFraction.isFinite() && yFraction in 0f..1f) e.putFloat(K_WIN_Y, yFraction)
        e.apply()
    }

    /** True while a task of [sessionId] is running (a chat turn is streaming). */
    fun isTaskActive(sessionId: String?): Boolean = sessionId != null && synchronized(lock) { tasks.containsKey(sessionId) }

    /** Latest preview frame without creating anything (查看状态或小窗不会重建). */
    fun latestFrame(sessionId: String): Bitmap? = previewDisplayFor(sessionId)?.latestFrame()
}
