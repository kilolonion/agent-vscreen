package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
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
        /**
         * 1.1: what the agent says it is doing (`vscreen title`), shown on the window's pill; cleared with the turn.
         * 1.4.2-beta6: kept with the session that set it ([titleSession]) and shown only while that session owns the
         * screen. Before, it was one field for the whole app: a turn that ended after its screen was gone, or another
         * agent taking the screen over, left the last agent's title on the window for the next one.
         */
        val titleText: String? = null,
        val titleSession: String? = null,
    ) {
        val agentTitle: String? get() = titleText?.takeIf { titleSession != null && titleSession == ownerSessionId }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Tests only: set the control flags without a screen (1.4.2-beta6 gate tests). */
    internal fun updateStateForTest(f: (State) -> State) = _state.update(f)

    @Volatile internal var appContext: Context? = null
        private set
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
    private val parkingLock = Any()
    private val parkingOps = Any()
    private var parking: ParkedApps? = null

    private class ParkedApps(val sessionId: String, val active: VScreenDisplay) {
        // Only read/written under parkingOps, including cleanup of a detached slot.
        var displayId: Int? = null
        // Both fields are protected by parkingLock. A pending move also blocks accidental input.
        val apps = mutableMapOf<String, Int>()
        var selected: String? = null
    }

    internal fun requireNotParked(sessionId: String?, pkg: String? = null) {
        val blocked = synchronized(parkingLock) {
            parking?.takeIf { it.sessionId == sessionId }?.let { slot ->
                (pkg ?: slot.selected)?.takeIf { it in slot.apps }
            }
        } ?: return
        throw ActionError("APP_PARKED", tr("$blocked 已停放，先用 `vscreen handoff unpark $blocked` 移回副屏，或用 launch 切到别的应用。",
            "$blocked is parked. Bring it back with `vscreen handoff unpark $blocked`, or use launch to select another app."),
            org.json.JSONObject().put("package", blocked))
    }

    internal fun hasParkedApps(sessionId: String) = synchronized(parkingLock) {
        parking?.takeIf { it.sessionId == sessionId }?.apps?.isNotEmpty() == true
    }

    internal fun appSelected(sessionId: String?, pkg: String) = synchronized(parkingLock) {
        parking?.takeIf { it.sessionId == sessionId && pkg !in it.apps }?.selected = null
    }

    // lifecycle callers may be on the UI thread: invalidate synchronously, do binder work off-thread.
    internal fun clearParkedApps(sessionId: String) = clearParking(sessionId)

    private fun clearParking(sessionId: String? = null) {
        val slot = synchronized(parkingLock) {
            parking?.takeIf { sessionId == null || it.sessionId == sessionId }?.also { parking = null }
        } ?: return
        val close = Runnable {
            synchronized(parkingOps) {
                slot.displayId?.let { id ->
                    // No force-stop: Android finishes the tasks on this display when it is removed.
                    VScreenRemoteDisplay.releaseParking(id).onFailure { AppLogger.warning(TAG, "parking cleanup $id: ${it.message}") }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) Thread(close, "vscreen-parking-cleanup").apply { isDaemon = true }.start()
        else close.run()
    }

    private fun checkParkingOwner(sid: String, d: VScreenDisplay) {
        val s = _state.value
        if (s.userStopped) throw ActionError("USER_STOPPED", userStoppedMessage())
        if (s.takeoverSessionId == sid) throw ActionError("USER_TAKEOVER", tr("用户正在接管副屏", "The user is controlling the screen"))
        if (s.ownerSessionId != sid || display !== d || d.released)
            throw ActionError("NO_SCREEN", tr("会话不再持有副屏", "This session no longer owns the screen"))
    }

    private fun parkingFailure(detail: String? = null) = ActionError("HANDOFF_DENIED",
        tr("系统未能完成停放或取回任务", "The system could not park or restore the task") + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))

    private fun missingParkingTask(pkg: String) = ActionError("HANDOFF_NO_TASK",
        tr("找不到 $pkg 的任务", "No task found for $pkg"))

    internal fun parkApp(sid: String, d: VScreenDisplay, pkg: String): Int = synchronized(parkingOps) {
        checkParkingOwner(sid, d)
        requireNotParked(sid, pkg)
        val task = (VScreenHandoff.tasksOrNull(d.displayId) ?: throw parkingFailure())
            .firstOrNull { it.pkg == pkg } ?: throw missingParkingTask(pkg)
        if (VScreenShell.isProtected(pkg)) throw parkingFailure(tr("这是受保护的应用", "This app is protected"))
        val slot = synchronized(parkingLock) {
            checkParkingOwner(sid, d)
            parking?.takeIf { it.sessionId == sid && it.active === d } ?: ParkedApps(sid, d).also { parking = it }
        }
        val hidden = slot.displayId ?: VScreenRemoteDisplay.createParking(d.displayId, d.physWidth, d.physHeight, d.dpi)
            .getOrElse { throw parkingFailure(it.message) }.also { slot.displayId = it }
        val previous = synchronized(parkingLock) {
            checkParkingOwner(sid, d)
            if (parking !== slot) throw parkingFailure()
            val previous = slot.selected
            slot.apps[pkg] = task.taskId
            if (task.foreground) slot.selected = pkg
            previous
        }
        val moved = VScreenRemoteDisplay.moveTask(task.taskId, hidden, source = d.displayId)
        val landed = VScreenHandoff.tasksOrNull(hidden)?.any { it.taskId == task.taskId }
        // Keep the record when relocation cannot be verified: a timed-out binder call can still finish.
        if (landed != true) {
            if (landed == false && moved.isSuccess) synchronized(parkingLock) {
                slot.apps.remove(pkg)
                slot.selected = previous
            }
            throw parkingFailure(moved.getOrNull()?.second ?: moved.exceptionOrNull()?.message)
        }
        checkParkingOwner(sid, d)
        if (synchronized(parkingLock) { parking !== slot }) throw parkingFailure()
        task.taskId
    }

    internal fun unparkApp(sid: String, d: VScreenDisplay, pkg: String): Int = synchronized(parkingOps) {
        checkParkingOwner(sid, d)
        val (slot, task) = synchronized(parkingLock) {
            val slot = parking?.takeIf { it.sessionId == sid && it.active === d } ?: throw missingParkingTask(pkg)
            slot to (slot.apps[pkg] ?: throw missingParkingTask(pkg))
        }
        val hidden = slot.displayId ?: throw missingParkingTask(pkg)
        val onActive = VScreenHandoff.tasksOrNull(d.displayId)?.any { it.taskId == task } == true
        if (!onActive) {
            val parked = VScreenHandoff.tasksOrNull(hidden) ?: throw parkingFailure()
            if (parked.none { it.taskId == task }) {
                synchronized(parkingLock) {
                    slot.apps.remove(pkg)
                    if (slot.selected == pkg) slot.selected = null
                }
                throw missingParkingTask(pkg)
            }
            val moved = VScreenRemoteDisplay.moveTask(task, d.displayId, source = hidden)
            if (VScreenHandoff.tasksOrNull(d.displayId)?.none { it.taskId == task } != false)
                throw parkingFailure(moved.getOrNull()?.second ?: moved.exceptionOrNull()?.message)
        }
        checkParkingOwner(sid, d)
        if (!VScreenShell.bringToFront(d.displayId, task)) throw parkingFailure(tr("任务没有回到副屏前台", "The task is not in front on the screen"))
        synchronized(parkingLock) {
            if (parking !== slot) throw parkingFailure()
            slot.apps.remove(pkg)
            slot.selected = null
        }
        task
    }
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
            // 1.4.2-beta4: never release the screen the user is holding; the hand-back starts the idle time again.
            val inTurn = owner != null && synchronized(lock) { tasks.containsKey(owner) }
            if (TakeoverRules.mayIdle(inTurn, holds, _state.value.takeoverSessionId)) releaseScreen(tr("空闲 ${IDLE_TTL_MS / 60_000} 分钟，已释放画面（保留连接）", "Idle for ${IDLE_TTL_MS / 60_000} minutes, picture released (connection kept)"))
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

    private fun scheduleIdlePark(afterMs: Long = PARK_AFTER_MS) {
        mainHandler.removeCallbacks(idlePark)
        if (VScreenStore.idlePowerSave.value) mainHandler.postDelayed(idlePark, afterMs)
    }

    /** The session said `done`: when its turn ends the screen pauses right away, as the reply says, not 30 s later. */
    @Volatile var parkSoonFor: String? = null

    /** Wake a paused screen (display back on, frames flowing). Returns a note when it had been paused. */
    fun unpark(d: VScreenDisplay): String? {
        mainHandler.removeCallbacks(idlePark)
        if (!d.parked) return null
        val r = d.unpark()
        _state.update { it.copy(powerSaving = false) }
        wakeNote = d.displayId to r
        return r
    }

    /**
     * 1.4.2-beta6 (test: after `done` the screen paused to save power; the game lost focus and showed its own pause
     * menu, and the next four taps landed on that menu with nothing said): the note made when a paused screen wakes
     * reached only the app's own window. Now the next reply on that screen carries it, whichever path woke it (the
     * turn starting, or the command itself).
     */
    @Volatile private var wakeNote: Pair<Int, String>? = null
    fun takeWakeNote(displayId: Int): String? = wakeNote?.takeIf { it.first == displayId }?.let { wakeNote = null; it.second }

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
        // Apps muted on the background screen: the screen service owns their sound and gives it back by itself (also when
        // this app dies or is uninstalled); a release here only makes it immediate.
        VScreenMute.init(context)
        com.kilon.agentvscreen.cmd.Draft.init(context)
        onScreenReleased { Thread { runCatching { VScreenMute.restoreAll() } }.apply { isDaemon = true; name = "vscreen-unmute" }.start() }
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

    fun setEnabled(on: Boolean, reason: String = tr("已关闭后台操作", "Background operation turned off")) {
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        if (!on) {
            // Off = stop everything; using it again needs an explicit connect. 1.4.2-beta6: off first, so a command
            // still running cannot reconnect and build a new screen while the screen is being released.
            _state.update { it.copy(enabled = false) }
            stopAndRelease(reason = reason)
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
        com.kilon.agentvscreen.host.Privileged.refresh()
        val snap = com.kilon.agentvscreen.host.Privileged.snapshot.value
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
                    connectionError = tr("特权连接已断开（${com.kilon.agentvscreen.host.Privileged.name()} ${priv.label()}）", "Privileged connection lost (${com.kilon.agentvscreen.host.Privileged.name()} ${priv.label()})"),
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
            Privilege.NOT_INSTALLED, Privilege.NOT_RUNNING, Privilege.NOT_AUTHORIZED -> com.kilon.agentvscreen.host.Privileged.notReadyHint()
            Privilege.READY -> {
                val r = VScreenShell.run(arrayOf("id"), 5_000L)
                if (r.exitCode != 0) tr("特权通道无响应：${r.combined.take(160)}", "The privileged channel does not answer: ${r.combined.take(160)}")
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

    /**
     * 1.4.2-beta3: the user switched the way in (Shizuku <-> root). The screen belongs to the old service, so it is
     * released; the next command connects through the new one by itself (this is not a stop).
     */
    fun privilegeChanged() {
        releaseScreen(tr("换了权限来源", "Permission source changed"))
        _state.update { it.copy(connection = Connection.DISCONNECTED, connectionError = null, needsExplicitReconnect = false, operating = false) }
        refreshStatus()
    }

    /** 停止并释放: stop operating, destroy the screen; reuse needs an explicit connect. */
    fun stopAndRelease(reason: String = tr("已停止并释放", "Stopped and released")) {
        // 1.4.2-beta6: nothing planned before this (service off, on demand, stopped and released) may act afterwards.
        Control.bump()
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

    fun currentDisplayId(): Int? = display?.takeIf { !it.released }?.displayId

    /** v0.5: the live background screen (null when none). */
    fun currentDisplay(): VScreenDisplay? = display?.takeIf { !it.released }

    /** v0.5: work is pending on the background screen — the owner's chat turn is running, or a watch holds it. */
    fun needsAwake(): Boolean {
        val d = display ?: return false
        if (d.released) return false
        val owner = _state.value.ownerSessionId
        // 1.4.2-beta4: the user holding the screen is work on it too (it may outlive the agent's turn).
        return TakeoverRules.needsAwake(synchronized(lock) { owner != null && owner in turns }, holds, _state.value.takeoverSessionId)
    }

    private fun restoreDownscale() {
        val pk = synchronized(unscaled) { unscaled.toList().also { unscaled.clear() } }
        val leftovers = VScreenDownscale.pending()
        if (pk.isNotEmpty() || leftovers.isNotEmpty()) Thread {
            pk.forEach { VScreenShell.resetDownscale(it) }
            VScreenDownscale.restoreAll()
        }.apply { isDaemon = true }.start()
    }

    private val releaseListeners = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()

    /** Called with the display id after a screen is released, so per-display caches can be dropped. */
    fun onScreenReleased(listener: (Int) -> Unit) { releaseListeners += listener }

    private fun releaseScreen(reason: String) {
        com.kilon.agentvscreen.cmd.ActHold.unholdNow("release_screen")
        cancelIdleRelease()
        mainHandler.removeCallbacks(idlePark)
        _state.update { it.copy(powerSaving = false) }
        // 1.4.0-beta11: a lesson being recorded ends with what the screen shows NOW, before its apps are closed and it
        // is released (before, it read a destroyed screen for up to 10 s and the end picture was blank).
        if (com.kilon.agentvscreen.cmd.Teach.lesson.value is com.kilon.agentvscreen.cmd.Lesson.Recording)
            runCatching { com.kilon.agentvscreen.cmd.Teach.endRecording(appContext, byDone = false, screenGone = true, endWaitMs = 800L) }
        // 1.4.2: the open task keeps the screen as it is left (the home page's resting picture), before it goes.
        _state.value.ownerSessionId?.let { TaskLog.captureNow(it) }
        val d = synchronized(lock) { display.also { display = null } }
        clearParking()
        if (d == null) return
        // Close whatever app was left on the screen so it does not hop to the
        // main display when its display disappears.
        com.kilon.agentvscreen.host.quiet("BackgroundOperation:439") { VScreenShell.finger(d.displayId, "cancel", 0, -1, -1) }
        runCatching { VScreenShell.closeAllOnDisplay(d.displayId) }
            .onSuccess { AppLogger.info(TAG, "release close: ${it.message}") }
        d.release()
        releaseListeners.forEach { l -> runCatching { l(d.displayId) }.onFailure { AppLogger.warning(TAG, "release listener: ${it.message}") } }
        restoreDownscale()
        // The page the flags were about is gone with the screen: the next screen starts with nothing to re-read.
        synchronized(lock) { tasks.values.forEach { it.rereadRequired = false; it.rereadReason = null }; rereadOwed.clear() }
        // 1.4.0-beta4: the task on a screen ends with the screen, even when its agent never said done.
        _state.value.ownerSessionId?.let { TaskLog.onTurnEnd(it, _state.value.userStopped) }
        _state.update { it.copy(displayId = null, ownerSessionId = null, takeoverSessionId = null, operating = false, note = reason) }
        AppLogger.info(TAG, "released display ${d.displayId} gen=${d.generation}: $reason")
        // A lesson being recorded and the questions answered on this screen end with it (1.3.0-beta14).
        com.kilon.agentvscreen.cmd.Teach.screenClosed(appContext)
        com.kilon.agentvscreen.host.UserAsk.screenGone(appContext, reason)
    }

    // ── task lifecycle (driven by ChatViewModel's isStreaming edge) ────────

    fun onTaskStarted(sessionId: String) {
        if (sessionId.isEmpty()) return
        if (_state.value.ownerSessionId == sessionId) {
            cancelIdleRelease(); mainHandler.removeCallbacks(idlePark); _state.update { it.copy(idle = false) }
            display?.takeIf { it.parked }?.let { d -> Thread { runCatching { unpark(d) } }.apply { isDaemon = true }.start() }
        }
        synchronized(lock) {
            // 1.4.2-beta4: a re-read owed from between turns (hand-back after the turn ended) carries into the new turn.
            tasks[sessionId] = TaskInfo(sessionId).also { t ->
                if (rereadOwed.owes(sessionId)) { t.rereadRequired = true; t.rereadReason = rereadOwed.settle(sessionId) }
            }
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
        clearParking(sessionId)
        // 1.4.2-beta6: the turn's title goes with the turn, whether or not its screen is still there.
        _state.update { if (it.titleSession == sessionId) it.copy(titleText = null, titleSession = null) else it }
        TaskLog.captureNow(sessionId)
        TaskLog.onTurnEnd(sessionId, _state.value.userStopped)
        appContext?.let { c -> com.kilon.agentvscreen.host.UserAsk.withdrawSession(c, sessionId) }
        // 1.4.0-beta10: a lesson the user took is theirs to finish; the agent's turn ending does not end it (withdraw
        // above leaves the question the lesson answers alone).
        // A finger the agent left down (run … --keep without a release) never outlives the turn. 1.4.2-beta6: the
        // cancel takes only the agent's own pointers (InputFence.cancels), so it is safe under the user's finger too.
        if (_state.value.ownerSessionId == sessionId) display?.let { d ->
            Thread { runCatching { VScreenShell.finger(d.displayId, "cancel", 0, -1, -1) } }.apply { isDaemon = true }.start()
        }
        if (_state.value.ownerSessionId == sessionId && display != null) {
            // 1.4.2-beta4: a user takeover is NOT ended by the agent's turn ending. Before, an agent that met
            // USER_TAKEOVER and went quiet ended its turn after Turns.quietMs (3 min): the takeover was dropped under the
            // user's finger without a hand-back (no handback event, no re-read), the idle screen was then paused and
            // released, and the agent coming back met NO_SCREEN / a frozen picture and had to rebuild the screen. Only
            // the user ends a takeover ([userResume], Stop, closing the screen or hiding the window); while they hold it
            // the screen is neither paused nor released (a lesson being shown was the one case kept before).
            // The user's own screen (ManualScreen) is the exception: its turn IS the user's, so ending it ends the hold
            // (handed back, closed, or the launch failed) and the next agent may take the screen over as before.
            val held = !ManualScreen.isManual(sessionId) && TakeoverRules.survivesTurnEnd(_state.value.takeoverSessionId, sessionId)
            if (ManualScreen.isManual(sessionId) && _state.value.takeoverSessionId == sessionId) controlChange(agentAllowedAfter = true) { it.copy(takeoverSessionId = null) }
            _state.update { it.copy(operating = false, idle = true, note = if (held) tr("本轮结束，你还在接管副屏；交还后 AI 会先看一眼画面再继续", "Turn over; you still hold the screen. Once you hand it back, the AI looks at it before going on")
                else tr("本轮结束，副屏保留 ${IDLE_TTL_MS / 60_000} 分钟", "Turn over; the screen is kept for ${IDLE_TTL_MS / 60_000} minutes")) }
            if (!held) {
                scheduleIdleRelease()
                scheduleIdlePark(if (parkSoonFor == sessionId) 1_500L else PARK_AFTER_MS)
            }
            parkSoonFor = null
        }
    }

    /**
     * 1.4.2-beta4: "look first" owed to a session between its turns. A turn's TaskInfo is dropped when the turn ends and
     * a new one starts empty, so a hand-back that came after the turn was lost and the agent acted on a page the user
     * had changed. Guarded by [lock].
     */
    private val rereadOwed = RereadDebts()

    /** Mark [sid] as owing a look before its next action, whether or not its turn is running. Caller holds [lock]. */
    private fun oweReread(sid: String, reason: String? = null) {
        val t = tasks[sid]
        if (t != null) { t.rereadRequired = true; if (reason != null) t.rereadReason = reason }
        else rereadOwed.owe(sid, reason)
    }

    /** `vscreen release`: destroy the screen now (connection kept). */
    fun releaseNow(sessionId: String) {
        val s = _state.value
        // 1.4.2-beta4: the agent cannot close the screen out of the user's hands; the user ends the takeover.
        // 1.4.2-beta6: nor while the user has stopped it (every agent action is refused then).
        if (s.ownerSessionId == sessionId && s.userStopped) throw ActionError("USER_STOPPED", userStoppedMessage())
        if (s.ownerSessionId == sessionId && s.takeoverSessionId != null) throw ActionError("USER_TAKEOVER",
            tr("用户正在接管副屏，不能现在释放（副屏没有动）。停下来告诉用户，等用户交还后再 release", "The user is controlling the screen, so it cannot be released now (nothing was changed). Stop and tell the user; release once the user hands it back"))
        if (s.ownerSessionId == sessionId) releaseScreen(tr("已按要求释放画面（保留连接）", "Picture released as asked (connection kept)"))
    }

    /** 1.4.2-beta4: the user's own "close screen" (full screen page): always allowed, a takeover included. */
    fun userRelease(sessionId: String) {
        if (_state.value.ownerSessionId == sessionId) { Control.bump(); releaseScreen(tr("你关闭了副屏", "You closed the screen")) }
    }

    /** 1.4.2-beta4: the user holds [sessionId]'s screen right now (for replies that must not say otherwise). */
    fun heldByUser(sessionId: String?): Boolean = sessionId != null && _state.value.takeoverSessionId == sessionId

    // ── takeover (接管) ────────────────────────────────────────────────────

    /**
     * 1.4.2-beta6: every change the user makes to who controls the screen goes through here: the state change, the
     * control epoch ([Control]) and the screen service's fence ([InputFence]) move together. When the agent loses
     * control the state changes first (an injection checking in between already sees the takeover / Stop); when it
     * gets control back the epoch moves first (one checking in between sees its plan is old). Either way nothing the
     * agent planned before the change gets through.
     */
    private fun controlChange(agentAllowedAfter: Boolean, update: (State) -> State) {
        val e = synchronized(controlLock) {
            if (agentAllowedAfter) { val e = Control.bump(); _state.update(update); e }
            else { _state.update(update); Control.bump() }
        }
        Control.fence(display?.takeIf { !it.released }?.displayId, e, agentAllowedAfter && agentMayAct(_state.value))
    }

    private val controlLock = Any()

    private fun agentMayAct(s: State) = !s.userStopped && s.takeoverSessionId == null

    /** Tell the service the current control on a screen this process just made or adopted. */
    private fun fenceNow(d: VScreenDisplay) {
        runCatching { VScreenRemoteDisplay.fence(d.displayId, Control.epoch, agentMayAct(_state.value)) }
            .onFailure { AppLogger.warning(TAG, "fence on ${d.displayId}: ${it.message}") }
    }

    fun beginTakeover(sessionId: String): Boolean {
        val s = _state.value
        if (s.displayId == null || s.ownerSessionId != sessionId) return false
        com.kilon.agentvscreen.cmd.ActHold.unholdNow("takeover")
        // 1.4.2-beta4: the screen the user holds is never paused or released under them. Before, taking over an idle
        // screen left its 10-minute release timer running, and the screen went away in the user's hands.
        cancelIdleRelease()
        mainHandler.removeCallbacks(idlePark)
        display?.takeIf { it.parked }?.let { d -> Thread { runCatching { unpark(d) } }.apply { isDaemon = true }.start() }
        // 1.4.2-beta6: taking over a stopped screen is one change. Before, callers lifted the Stop first (userResume) and
        // then took over: for that moment the agent was allowed again, and it was told the screen had been handed back.
        val wasStopped = s.userStopped
        controlChange(agentAllowedAfter = false) { it.copy(takeoverSessionId = sessionId, userStopped = false, stoppedAt = 0L) }
        if (wasStopped) AppLogger.info(TAG, "user took over a stopped screen")
        clearParking(sessionId)
        VScreenAwake.kick(tr("用户接管", "User takeover"))
        return true
    }

    /**
     * 1.4.2-beta4: a takeover just ended. When the owner's turn is no longer running the screen idles from now (paused
     * after [PARK_AFTER_MS], released after [IDLE_TTL_MS]) like any finished turn; while it runs, the turn keeps it.
     */
    private fun idleAfterTakeover() {
        val owner = _state.value.ownerSessionId ?: return
        if (display == null || synchronized(lock) { owner in turns }) return
        mainHandler.post { scheduleIdleRelease(); if (holds == 0) scheduleIdlePark() }
    }

    /** 1.1: the user's Stop: refuse the agent (USER_STOPPED) until [userResume]; a finger the agent holds is lifted. */
    fun userStop() {
        com.kilon.agentvscreen.cmd.ActHold.unholdNow("user_stop")
        _state.value.ownerSessionId?.let { TaskLog.stoppedByUser(it) }
        val wasHeld = _state.value.takeoverSessionId != null
        // 1.4.2-beta6: the fence cancels the agent's gesture in flight and its held fingers in the screen service (as a
        // cancel, not a lift) before any later input; before, a finger was lifted on a separate thread, after the fact.
        controlChange(agentAllowedAfter = false) { it.copy(userStopped = true, stoppedAt = System.currentTimeMillis(), takeoverSessionId = null, operating = false) }
        clearParking()
        if (wasHeld) com.kilon.agentvscreen.cmd.Teach.onTakeoverEnded(appContext)
        // 1.4.2-beta4: a held screen had no idle timers; once it is only stopped it idles again (the Stop lock stays).
        if (wasHeld) idleAfterTakeover()
        AppLogger.info(TAG, "user stop")
    }

    /** 1.1: hand back to the agent after Stop or takeover; its next action is told to look first. */
    fun userResume() {
        val s = _state.value
        // 1.4.2-beta4: owed across turns too (the agent's turn may have ended while the user held the screen).
        synchronized(lock) { s.ownerSessionId?.let { oweReread(it) } }
        controlChange(agentAllowedAfter = true) { it.copy(userStopped = false, stoppedAt = 0L, takeoverSessionId = null) }
        if (s.takeoverSessionId != null) com.kilon.agentvscreen.cmd.Teach.onTakeoverEnded(appContext)
        AppLogger.info(TAG, "user allowed the AI to continue (takeover=${s.takeoverSessionId != null}, stopped=${s.userStopped})")
        // 1.4.2: handing back the user's own screen ends their turn: it idles, and the next agent may take it over.
        if (ManualScreen.isManual(s.ownerSessionId)) { ManualScreen.end(); return }
        if (s.takeoverSessionId != null || s.userStopped) {
            postHandback(s.ownerSessionId, if (s.userStopped) "resume" else "takeover")
            idleAfterTakeover()
        }
    }

    /** 1.3.0-beta16: the agent hears a hand-back (vscreen events) instead of finding out on its next call. */
    private fun postHandback(sid: String?, what: String) {
        sid ?: return
        if (ManualScreen.isManual(sid)) return
        appContext?.let { c -> com.kilon.agentvscreen.host.quiet("handback-event") {
            com.kilon.agentvscreen.api.EventHub.post(c, sid, "handback", what,
                tr("用户把副屏交还给你了：先看一眼现在的画面再继续", "The user handed the screen back to you: look at it before going on"))
        } }
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
        // 1.4.2: the user's own screen (opened from the app) closes when they close its window.
        if (ManualScreen.isManual(owner)) {
            _state.update { it.copy(windowHidden = false) }
            ManualScreen.end { Control.bump(); releaseScreen(tr("你关闭了自己打开的副屏", "You closed the screen you opened")) }
            return tr("副屏已关闭", "Screen closed")
        }
        val turnRunning = synchronized(lock) { owner in turns }
        if (s.userStopped || !turnRunning) {
            Control.bump()
            releaseScreen(if (s.userStopped) tr("你从悬浮窗关闭了副屏（AI 仍处于停止状态）", "You closed the screen from the floating window (the AI is still stopped)") else tr("你从悬浮窗关闭了副屏", "You closed the screen from the floating window"))
            _state.update { it.copy(windowHidden = false) }
            return if (s.userStopped) tr("副屏已关闭。AI 仍是停止状态：要让它继续，在通知里点“允许继续”", "Screen closed. The AI is still stopped: to let it go on, tap \"Allow again\" in the notification") else tr("副屏已关闭", "Screen closed")
        }
        if (s.takeoverSessionId != null) {
            synchronized(lock) { oweReread(owner) }
            controlChange(agentAllowedAfter = true) { it.copy(takeoverSessionId = null) }
            com.kilon.agentvscreen.cmd.Teach.onTakeoverEnded(appContext)
            // 1.4.2-beta4: hiding the window hands a takeover back: the agent hears it like any other hand-back.
            postHandback(owner, "takeover")
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
        val t = title?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
        _state.update { it.copy(titleText = t, titleSession = t?.let { sessionId }) }
    }

    // ── agent side ─────────────────────────────────────────────────────────

    class ActionError(val code: String, message: String, val data: org.json.JSONObject? = null) : Exception(message)

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
        return acquireOwned(sid, create, landscape, scale).also { com.kilon.agentvscreen.host.Needs.got(sid) }
    }

    private fun acquireOwned(sid: String, create: Boolean, landscape: Boolean?, scale: Double): VScreenDisplay {
        val s0 = _state.value
        if (!s0.enabled) throw ActionError("DISABLED", tr("Agent-VScreen 的服务已被用户关闭。请用户打开 Agent-VScreen 点“启动服务”", "The user turned off the Agent-VScreen service. Ask the user to open Agent-VScreen and turn the service on"))
        if (s0.connection != Connection.READY) {
            // 1.3: the service is on (checked above), so the user wants it working: connect now, whatever ended the last
            // connection (the user's stop is undone by turning the service back on; a lost Shizuku may be back). Before,
            // a connection the user had stopped once needed `vscreen fix not_connected` even after the service was
            // turned on again, and `launch` failed with NOT_CONNECTED although the guide says it builds the screen.
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
                if (ownerBusy) throw busy(s.ownerSessionId, sid)
                // 1.4.2-beta4: the user still holds the screen of a turn that ended: it is not idle, nobody takes it.
                if (!TakeoverRules.mayAdoptIdle(s.takeoverSessionId, sid)) throw ActionError("USER_TAKEOVER",
                    tr("用户正在接管副屏（之前是会话“${s.ownerSessionId ?: "?"}”的）。动作没有执行：停下来告诉用户，等用户交还后再用；不要反复重试",
                        "The user is controlling the screen (it was session \"${s.ownerSessionId ?: "?"}\"'s). The action was not done: stop and tell the user, and use it once the user hands it back. Don't keep retrying"))
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
                if (s.ownerSessionId != sid) throw busy(s.ownerSessionId, sid)
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
                    fenceNow(d)
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
            // 1.4.2-beta6: no new screen while the user has stopped the AI (before, one was built and then refused).
            Control.admitInput(checkPlan = false)
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
            fenceNow(d)
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

    /** `screen size`: remember the size and release the screen so the next launch rebuilds it. */
    fun setScreenSize(sessionId: String?, v: String?) {
        if (!::prefs.isInitialized) return
        if (v == null || v == "phone" || v == "reset") prefs.edit().remove(K_SCREEN_SIZE).apply()
        else prefs.edit().putString(K_SCREEN_SIZE, v).apply()
        if (sessionId != null && _state.value.ownerSessionId == sessionId) releaseScreen(tr("副屏尺寸改为 ${v ?: "跟随手机"}，下次 launch 时按新尺寸重建", "Screen size set to ${v ?: "follow the phone"}; the next launch rebuilds it at that size"))
    }

    /** Set by the transport: when the owner's turn ends ("它的上一条命令 40 秒前结束，再安静 2 分 20 秒自动让出"). */
    @Volatile var describeOwner: (String) -> String = { "" }
    /** Set by the transport: milliseconds until the owner's turn ends by itself (null = it is running a command). */
    @Volatile var ownerFreesInMs: (String) -> Long? = { null }

    /** 1.1: BUSY says who owns the screen, when it will be free by itself, and how the owner frees it now. */
    private fun busy(owner: String?, sid: String): ActionError = com.kilon.agentvscreen.host.Needs.refused(sid, owner).let { busyError(owner) }

    private fun busyError(owner: String?): ActionError = if (ManualScreen.isManual(owner)) ActionError("BUSY",
        data = org.json.JSONObject().put("owner", "user").put("frees_in_s", org.json.JSONObject.NULL),
        message = tr("用户正在副屏上手动用应用（自己打开的）。等用户交还副屏后再用；不要催，也不要换别的办法绕开",
            "The user is using an app on the background screen themselves (they opened it). Wait until they hand the screen back; don't press them or work around it")) else ActionError("BUSY",
        data = org.json.JSONObject().put("owner", owner ?: org.json.JSONObject.NULL)
            .put("frees_in_s", owner?.let { ownerFreesInMs(it) }?.let { (it + 999) / 1000 } ?: org.json.JSONObject.NULL),
        message =
        tr("副屏现在归会话“${owner ?: "?"}”", "The screen belongs to session \"${owner ?: "?"}\"") + (owner?.let { describeOwner(it) } ?: "") +
            tr("。要现在接手：在那个会话执行 vscreen done（结束它这一轮，页面留着给你接手）或 vscreen release（关掉副屏）", ". To take it now: in that session run vscreen done (ends its turn, leaves the page for you) or vscreen release (closes the screen)"))

    /** True when the screen was built landscape (wider than tall) before any rotation. */
    fun nativeLandscape(d: VScreenDisplay): Boolean = d.physWidth > d.physHeight

    /**
     * Rebuild this session's screen natively in [landscape] orientation (the app on it is closed).
     * Used when an app would otherwise rotate the screen in place, which can leave its window or
     * surface sized for the old orientation (content cut off on the right/bottom, or squeezed).
     */
    fun rebuild(sessionId: String?, landscape: Boolean, scale: Double = 1.0, reason: String? = null): VScreenDisplay {
        val sid = sessionId?.takeIf { it.isNotEmpty() } ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
        // 1.4.2-beta6: a rebuild closes the app on the screen and releases it: never while the user holds the screen or
        // has stopped the AI. Before, `launch` rebuilt (for the app's orientation) before any of those checks.
        Control.admitInput(checkPlan = false)
        synchronized(lock) {
            val s = _state.value
            if (display != null && s.ownerSessionId != null && s.ownerSessionId != sid && tasks.containsKey(s.ownerSessionId))
                throw busy(s.ownerSessionId, sid)
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
        allowParked: Boolean = freshPage,
        block: (VScreenDisplay) -> T,
    ): T {
        val sid = sessionId ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
        // 1.2: what the screen is in right now is one derived value (ScreenPhase), and whether an action may run in
        // it is one table (ScreenPhase.refusal), instead of a chain of flag checks in every caller.
        // 1.4.2-beta6: the user's own commands (ManualScreen) are not the agent's: no Stop, no takeover wait.
        val user = Control.isUser()
        if (!user) ScreenPhase.of(_state.value, sid).let { ph -> if (ph == ScreenPhase.STOPPED) throw ActionError("USER_STOPPED", userStoppedMessage()) }
        if (!user) waitWhileTakenOver(sid, isRead, freshPage)
        // 1.4.2-beta6: what this action was planned under ([Control]). A command that opens a new page (launch) needs no
        // earlier page, so it plans afresh here (after any takeover it waited out); everything else keeps the epoch its
        // command arrived in, and every input it sends is refused once the user has changed control since.
        val plan = if (freshPage) Control.epoch else Control.planned()
        // 1.4.0-beta2: the app this session was working on is with the user on the main screen. Whatever is under it
        // on the background screen now is not what the agent planned for: refuse, never act on it.
        if (!isRead && !freshPage) Guard.require(Guard.Act.INPUT.takeIf { VScreenOwnership.blocking(sid) != null } ?: Guard.Act.READ, null, sid)
        val d = acquire(sid, create = false)
        if (!allowParked) requireNotParked(sid)
        val startedMs = System.currentTimeMillis()
        if (!isRead) actionStarted[d.displayId] = startedMs
        if (d.shellOwned) runCatching { VScreenRemoteDisplay.touch(d.displayId) }
        if (expectedGeneration != null && expectedGeneration != d.generation) {
            throw ActionError("STALE_SCREEN", tr("这个操作是针对旧副屏（第 $expectedGeneration 代）规划的，当前是第 ${d.generation} 代。", "This action was planned for an older screen (generation $expectedGeneration); the current one is generation ${d.generation}."))
        }
        val task = synchronized(lock) {
            tasks.getOrPut(sid) { TaskInfo(sid) }.also { t ->
                // 1.4.2-beta4: a re-read owed from between turns applies to a task made without onTaskStarted too.
                if (rereadOwed.owes(sid)) { t.rereadRequired = true; t.rereadReason = rereadOwed.settle(sid) ?: t.rereadReason }
            }
        }
        if (!isRead && !freshPage && task.rereadRequired) {
            val why = task.rereadReason
            // 1.4.2-beta4: the refusal is said once. Before, only act/look/launch cleared the flag (their reply shows the
            // screen), so collect, stop and handoff out kept answering REREAD_REQUIRED for ever and the agent ended up
            // rebuilding the screen. The refused command's reply carries the code; the agent's next command runs.
            synchronized(lock) { task.rereadRequired = false; task.rereadReason = null }
            if (why != null) throw ActionError("HOST_RESTARTED", tr("$why。动作没有执行：看清现在的画面再继续，不要假设上一轮的步骤已经完成", "$why. The action was not done: look at the screen now before going on, and don't assume the last turn's steps were completed"))
            throw ActionError("REREAD_REQUIRED", tr("用户刚接管过副屏，页面可能已变化。动作没有执行：先 act（不带步骤）看一眼现在的画面，再重发", "The user just took over the screen and the page may have changed. The action was not done: look first with act (no steps), then send it again"))
        }
        runCatching { ensureUpright(d) }.onFailure { AppLogger.warning(TAG, "orientation check failed: ${it.message}") }
        // 1.4.0-beta14 (test: collect stopped after one screen while the screen had gone to sleep): every command on
        // the screen wakes it first, here, not only the ones that remembered to (act, look).
        VScreenAwake.ensure(d)
        // v27 IME guard: the system focus (and with it the keyboard) belongs to the user's screen.
        val focus0 = if (!isRead && !describe.startsWith(tr("接力", "Relay"))) focusedNow() else null
        _state.update { it.copy(operating = true, action = describe.take(24)) }
        val tStart = SystemClock.elapsedRealtime()
        try {
            val result = Control.withPlan(plan) {
                // The entry check for what is not a touch or a key (moving tasks, parking): input checks itself again.
                if (!isRead) Control.admitInput()
                block(d)
            }
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
        rereadOwed.settle(sessionId)
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

    private fun waitWhileTakenOver(sessionId: String, isRead: Boolean, freshPage: Boolean) {
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
        // 1.4.2-beta4: a look (or a launch, which opens a new page) that waited out the takeover just runs: it is the
        // re-read. Before, it was refused too, so even the "look first" the error asked for failed once more.
        if (TakeoverRules.refuseAfterWait(waited, isRead, freshPage)) {
            acknowledgeReread(sessionId)
            throw ActionError("REREAD_REQUIRED", tr("用户已交还副屏。页面可能已变化：这次没有执行，先 act（不带步骤）看一眼再继续。", "The user handed the screen back. The page may have changed: this was not done; look first with act (no steps), then go on."))
        }
    }

    // ── helpers used by the command ───────────────────────────────────────

    fun nodeRoots(displayId: Int): List<AccessibilityNodeInfo> =
        VScreenAccessibilityService.getInstance()?.rootNodesForDisplay(displayId) ?: emptyList()

    private fun mainScreenMetrics(context: Context): Triple<Int, Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = wm.maximumWindowMetrics.bounds
        return Triple(b.width(), b.height(), context.resources.configuration.densityDpi)
    }

    fun Privilege.label(): String = when (this) {
        Privilege.NOT_INSTALLED -> tr("未安装", "not installed")
        Privilege.NOT_RUNNING -> tr("服务未启动", "service not running")
        Privilege.NOT_AUTHORIZED -> tr("未授权", "not granted")
        Privilege.READY -> tr("已连接且已授权", "connected and granted")
    }

    /** The live screen for the preview of [sessionId], or null. */
    /** 1.4.0-beta13: the live screen with this id (its geometry maps system bounds to frame space), or null. */
    fun displayById(id: Int): VScreenDisplay? = display?.takeIf { it.displayId == id && !it.released }

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

    /** True while a task of [sessionId] is running (a chat turn is streaming). */
    fun isTaskActive(sessionId: String?): Boolean = sessionId != null && synchronized(lock) { tasks.containsKey(sessionId) }

    /** Latest preview frame without creating anything (查看状态或小窗不会重建). */
    fun latestFrame(sessionId: String): Bitmap? = previewDisplayFor(sessionId)?.latestFrame()
}
