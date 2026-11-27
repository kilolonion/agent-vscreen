package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import com.kilon.agentvscreen.host.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Keeps the background screen awake while work runs on it, including with the phone's screen off,
 * driven reactively by [BackgroundOperation.state], [BackgroundOperation.activeTurns],
 * [BackgroundOperation.awakeHolds], and [VScreenStore.phoneScreenMode].
 */
object VScreenAwake {
    private val wakeBudget = com.kilon.agentvscreen.host.WakeBudget()
    private val phoneWakeBudget = com.kilon.agentvscreen.host.WakeBudget()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal fun suspendForHeat() {
        keeper?.interrupt(); releaseLock(); releaseScreenLock()
        val ctx = appContext ?: return
        val pi = PendingIntent.getBroadcast(ctx, 7291, Intent(ACTION_TICK).setPackage(ctx.packageName), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pi)
    }

    private const val TAG = "VScreenAwake"
    private const val CHECK_MS = 15_000L
    private const val ALARM_MS = 60_000L
    private const val ACTION_TICK = "com.kilon.agentvscreen.core.AWAKE_TICK"
    private const val OWN_DISPLAY_GROUP = 1 shl 11
    private const val ALWAYS_UNLOCKED = 1 shl 12

    @Volatile var wakes = 0; private set
    @Volatile var lastWake: String? = null; private set
    @Volatile var screenOff = false; private set
    @Volatile var screenWokeCount = 0; private set
    @Volatile private var appContext: Context? = null
    @Volatile private var keeper: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private data class LifecycleKey(
        val displayId: Int?,
        val ownerSessionId: String?,
        val userStopped: Boolean,
        val takeoverSessionId: String?,
        val turns: Set<String>,
        val holds: Int,
        val screenMode: VScreenStore.PhoneScreenMode,
    )

    fun init(ctx: Context) {
        if (appContext != null) return
        val app = ctx.applicationContext
        appContext = app
        val pm = app.getSystemService(PowerManager::class.java)
        screenOff = pm?.isInteractive == false
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOff = true
                        InputReach.invalidate()
                        syncScreenLock()
                        kick(tr("熄屏", "screen off"))
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        screenOff = false
                        InputReach.invalidate()
                        releaseLock()
                        syncScreenLock()
                    }
                    ACTION_TICK -> kick(tr("定时", "timer"))
                }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(ACTION_TICK) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(rx, f)
        }.onFailure { AppLogger.warning(TAG, "receiver: ${it.message}") }

        // Drive screen lock and keeper reactively from BackgroundOperation state, active turns, watches, and screen mode.
        scope.launch {
            var lastDisplayId: Int? = null
            combine(
                BackgroundOperation.state.map { listOf(it.displayId, it.ownerSessionId, it.userStopped, it.takeoverSessionId) }.distinctUntilChanged(),
                BackgroundOperation.activeTurns,
                BackgroundOperation.awakeHolds,
                VScreenStore.phoneScreenMode,
            ) { s, turns, holds, mode ->
                LifecycleKey(
                    displayId = s[0] as Int?,
                    ownerSessionId = s[1] as String?,
                    userStopped = s[2] as Boolean,
                    takeoverSessionId = s[3] as String?,
                    turns = turns,
                    holds = holds,
                    screenMode = mode,
                )
            }.distinctUntilChanged().collect { key ->
                runCatching {
                    if (key.displayId != lastDisplayId) {
                        InputReach.invalidate(lastDisplayId)
                        InputReach.invalidate(key.displayId)
                        lastDisplayId = key.displayId
                    }
                    reconcile(tr("状态变化", "state changed"))
                }.onFailure { AppLogger.warning(TAG, "lifecycle observer: ${it.message}") }
            }
        }
    }

    /** Synchronize screen wake lock and keeper immediately with current state (also invoked by the reactive collector). */
    internal fun reconcile(why: String = tr("状态变化", "state changed")) {
        syncScreenLock()
        if (needed()) kick(why) else {
            releaseLock()
            keeper?.interrupt()
        }
    }

    /** Whether the background screen can be woken by itself on this phone (else waking it would wake the main screen). */
    fun canWakeAlone(d: VScreenDisplay): Boolean = Build.VERSION.SDK_INT >= 33 && (d.flags and OWN_DISPLAY_GROUP) != 0

    /** Display.STATE_* of [d] (null: unknown). */
    fun stateOf(d: VScreenDisplay): Int? = runCatching {
        appContext?.getSystemService(DisplayManager::class.java)?.getDisplay(d.displayId)?.state
    }.getOrNull()

    /**
     * Before an action or a look: when [d] is asleep, wake it and wait (<= 2 s) until it is ON. Returns a fact for the
     * caller's notes when something was done or could not be done; null when it was awake. Never throws.
     */
    fun ensure(d: VScreenDisplay): String? {
        if (com.kilon.agentvscreen.host.ThermalGuard.limited || (screenOff && !BackgroundOperation.needsAwake())) return null
        if (d.released || d.parked) return null
        val st = stateOf(d) ?: return null
        if (st == Display.STATE_ON) return null
        if (!canWakeAlone(d)) return tr("副屏处于休眠（状态 ${stateName(st)}），这台手机上它和主屏一起休眠，单独唤醒会点亮主屏，所以没唤醒：画面可能不动", "The screen is asleep (state ${stateName(st)}); on this phone it sleeps with the main screen and waking it alone would light the main screen, so it was not woken: the picture may not move") + keepScreenHint()
        require(d.displayId != Display.DEFAULT_DISPLAY) { "VScreenAwake must never run input on display 0" }
        if (!wakeBudget.allow(SystemClock.elapsedRealtime())) return tr("副屏唤醒过于频繁，已暂停发键", "Repeated display wake suppressed")
        val ok = runCatching { VScreenShell.run(arrayOf("input", "-d", d.displayId.toString(), "keyevent", "KEYCODE_WAKEUP"), 4_000L).exitCode == 0 }.getOrDefault(false)
        val t0 = SystemClock.elapsedRealtime()
        while (ok && SystemClock.elapsedRealtime() - t0 < 2_000L && stateOf(d) != Display.STATE_ON) Thread.sleep(100)
        val on = stateOf(d) == Display.STATE_ON
        wakes++
        lastWake = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())} " + tr("${if (on) "已唤醒" else "唤醒失败"}（原状态 ${stateName(st)}${if (screenOff) "，主屏熄屏中" else ""}）", "${if (on) "woken" else "wake failed"} (was ${stateName(st)}${if (screenOff) ", phone screen off" else ""})")
        AppLogger.info(TAG, "display ${d.displayId}: $lastWake")
        if (on) { d.requestFreshFrame(); d.awaitFrameAfter(SystemClock.elapsedRealtime(), 400) }
        return if (on) tr("副屏刚才休眠了（${stateName(st)}${if (screenOff) "，手机熄屏中" else ""}），已唤醒", "The screen had gone to sleep (${stateName(st)}${if (screenOff) ", phone screen off" else ""}); woken") else tr("副屏休眠（${stateName(st)}），唤醒没成功：画面可能不动", "The screen is asleep (${stateName(st)}) and waking it failed: the picture may not move")
    }

    private fun stateName(s: Int) = when (s) {
        Display.STATE_OFF -> "OFF"; Display.STATE_DOZE -> "DOZE"; Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON -> "ON"; else -> s.toString()
    }

    /** Work is pending on the background screen: a chat turn owns it, or a watch runs. */
    private fun needed(): Boolean = !com.kilon.agentvscreen.host.ThermalGuard.limited && BackgroundOperation.needsAwake()

    /** Start the keeper if work is pending (called on screen off, on the timer, and on lifecycle changes). */
    fun kick(why: String) {
        val ctx = appContext ?: return
        syncScreenLock()
        if (!needed()) { releaseLock(); return }
        if (screenOff) { holdLock(ctx); scheduleTick(ctx) }
        synchronized(this) {
            if (keeper?.isAlive == true) return
            keeper = Thread {
                AppLogger.info(TAG, "keeper start ($why)")
                try {
                    while (needed()) {
                        syncScreenLock()
                        BackgroundOperation.currentDisplay()?.let { ensure(it) }
                        Thread.sleep(CHECK_MS)
                    }
                } catch (_: InterruptedException) {
                } finally { releaseLock(); syncScreenLock() }
            }.apply { isDaemon = true; name = "vscreen-awake"; start() }
        }
    }

    private fun holdLock(ctx: Context) = synchronized(this) {
        if (wakeLock?.isHeld == true) return@synchronized
        wakeLock = runCatching {
            ctx.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minis:vscreen-awake")?.apply {
                setReferenceCounted(false); acquire(10 * 60_000L)
            }
        }.getOrNull()
    }

    private fun releaseLock() = synchronized(this) { runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }; wakeLock = null }

    /** The timer: an inexact alarm allowed in Doze, re-armed while the screen is off and work is pending. */
    private fun scheduleTick(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(ctx, 7291, Intent(ACTION_TICK).setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ALARM_MS, pi)
        }.onFailure { AppLogger.warning(TAG, "alarm: ${it.message}") }
    }

    // ── Phone screen policy during active turns (issue #48 & #60) ──────────────────────────────────────────────────

    /** Plain keep-on lock (never carries ACQUIRE_CAUSES_WAKEUP). */
    private var keepOnLock: PowerManager.WakeLock? = null
    /** Dedicated active-wake lock used exclusively by [wakePhoneForInput] under `WAKE_WHEN_NEEDED`. */
    private var wakeInputLock: PowerManager.WakeLock? = null
    @Volatile var screenLockHeld = false; private set
    @Volatile internal var lastKeepOnLockFlags: Int = 0; private set
    @Volatile internal var lastInputWakeLockFlags: Int = 0; private set

    @Volatile internal var wakeLockFactory: ((Context, Int, String) -> PowerManager.WakeLock?)? = null
    @Volatile internal var isInteractiveFn: ((Context) -> Boolean)? = null
    @Volatile internal var monoClockMs: () -> Long = { SystemClock.elapsedRealtime() }
    @Volatile internal var sleepFn: (Long) -> Unit = { Thread.sleep(it) }
    @Volatile internal var wakeTimeoutMs: Long = 2_000L
    @Volatile internal var wakePollStepMs: Long = 100L

    /** Whether the screen lock should be held now under the 3-mode setting: pure, for unit tests. */
    internal fun wantsScreenLock(
        mode: VScreenStore.PhoneScreenMode,
        turnActive: Boolean,
        phoneScreenOff: Boolean,
        heatLimited: Boolean,
    ): Boolean = mode != VScreenStore.PhoneScreenMode.ALLOW_SLEEP && turnActive && !phoneScreenOff && !heatLimited

    /** Compatibility overload used by earlier tests. */
    internal fun wantsScreenLock(enabled: Boolean, turnActive: Boolean, phoneScreenOff: Boolean, heatLimited: Boolean): Boolean =
        wantsScreenLock(if (enabled) VScreenStore.PhoneScreenMode.KEEP_ON else VScreenStore.PhoneScreenMode.ALLOW_SLEEP, turnActive, phoneScreenOff, heatLimited)

    /** Take or let go of the screen lock to match the setting and the turn. Cheap; called on every change and tick. */
    fun syncScreenLock() {
        val ctx = appContext ?: return
        val want = wantsScreenLock(
            VScreenStore.phoneScreenMode.value,
            BackgroundOperation.agentTurnActive(),
            screenOff,
            com.kilon.agentvscreen.host.ThermalGuard.limited,
        )
        if (want) holdScreenLock(ctx) else releaseScreenLock()
    }

    private fun acquireWakeLock(ctx: Context, flags: Int, tag: String): PowerManager.WakeLock? {
        val custom = wakeLockFactory
        val lock = if (custom != null) custom(ctx, flags, tag) else runCatching {
            ctx.getSystemService(PowerManager::class.java)?.newWakeLock(flags, tag)
        }.getOrNull()
        lock?.setReferenceCounted(false)
        return lock
    }

    @Suppress("DEPRECATION") // SCREEN_DIM_WAKE_LOCK: still works; a background service has no window for FLAG_KEEP_SCREEN_ON.
    internal fun holdScreenLock(ctx: Context) = synchronized(this) {
        val flags = PowerManager.SCREEN_DIM_WAKE_LOCK
        val lock = keepOnLock ?: acquireWakeLock(ctx, flags, "agentvscreen:keep-screen-on")
            ?.also { keepOnLock = it } ?: return@synchronized
        lastKeepOnLockFlags = flags
        // Renewed on every keeper tick (15 s); the limit only matters if the process dies holding it.
        runCatching { lock.acquire(SCREEN_LOCK_MS) }.onFailure { AppLogger.warning(TAG, "screen lock: ${it.message}") }
        if (!screenLockHeld) AppLogger.info(TAG, "keeping the phone screen on during the agent turn")
        screenLockHeld = lock.isHeld
    }

    internal fun releaseScreenLock() = synchronized(this) {
        if (keepOnLock?.isHeld == true) { runCatching { keepOnLock?.release() }; AppLogger.info(TAG, "phone screen let go") }
        if (wakeInputLock?.isHeld == true) runCatching { wakeInputLock?.release() }
        screenLockHeld = false
    }

    /**
     * Wake the phone's main screen (to the lock screen, never unlocking) when `WAKE_WHEN_NEEDED` is active and a
     * touch/gesture requires an interactive display. Rate-limited with an independent [WakeBudget] and suppressed
     * whenever the device is heat-limited. Returns `null` only once the target virtual display [d.displayId]
     * itself probes interactive via [InputReach.probeDirect], or an error reason when refused / timed out.
     */
    internal fun wakePhoneForInput(d: VScreenDisplay): String? = wakePhoneForInputDisplay(d.displayId)

    @Suppress("DEPRECATION")
    internal fun wakePhoneForInputDisplay(displayId: Int): String? {
        if (VScreenStore.phoneScreenMode.value != VScreenStore.PhoneScreenMode.WAKE_WHEN_NEEDED) {
            return tr("当前未开启“需要时点亮”", "Wake-when-needed mode is not enabled")
        }
        if (com.kilon.agentvscreen.host.ThermalGuard.limited) {
            return tr("手机当前处于过热降负载状态，暂停自动点亮屏幕", "Phone is thermally throttled; automatic screen wake is paused")
        }
        val ctx = appContext ?: return tr("服务未初始化，无法点亮屏幕", "Service not initialized; cannot wake the screen")
        if (!phoneWakeBudget.allow(monoClockMs())) {
            return tr("短时间内点亮屏幕过于频繁，已暂停自动点亮，请稍后再试或手动点亮屏幕", "Screen was woken too frequently; automatic wake paused, retry later or turn the screen on manually")
        }
        val wakeFlags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
        val wakeHeld = synchronized(this) {
            if (wakeInputLock?.isHeld == true) runCatching { wakeInputLock?.release() }
            val lock = wakeInputLock ?: acquireWakeLock(ctx, wakeFlags, "agentvscreen:wake-for-input")
                ?.also { wakeInputLock = it }
            lastInputWakeLockFlags = wakeFlags
            if (lock != null) {
                runCatching { lock.acquire(SCREEN_LOCK_MS) }.onFailure { AppLogger.warning(TAG, "wake lock: ${it.message}") }
            }
            lock?.isHeld == true
        }
        if (!wakeHeld) {
            return tr("未能获取亮屏锁点亮主屏", "Failed to acquire wake lock to light the main screen")
        }
        screenWokeCount++
        AppLogger.info(TAG, "woke phone screen for input on display $displayId (count=$screenWokeCount)")
        fun checkMainInteractive(): Boolean = isInteractiveFn?.invoke(ctx)
            ?: runCatching { ctx.getSystemService(PowerManager::class.java)?.isInteractive == true }.getOrDefault(false)

        var ready = false
        try {
            val t0 = monoClockMs()
            while (true) {
                if (checkMainInteractive()) screenOff = false
                InputReach.invalidate(displayId)
                // Always probe the target virtual display directly (never shortcut on !screenOff).
                if (InputReach.probeDirect(displayId).reach == InputReach.Reach.OK) {
                    ready = true
                    break
                }
                if (monoClockMs() - t0 >= wakeTimeoutMs) break
                sleepFn(wakePollStepMs)
            }
        } finally {
            if (ready) {
                // Hand off screen-on retention to the plain keep-on lock (never reusing ACQUIRE_CAUSES_WAKEUP).
                if (wantsScreenLock(VScreenStore.phoneScreenMode.value, BackgroundOperation.agentTurnActive(), screenOff, com.kilon.agentvscreen.host.ThermalGuard.limited)) {
                    holdScreenLock(ctx)
                }
            }
            synchronized(this) {
                if (wakeInputLock?.isHeld == true) runCatching { wakeInputLock?.release() }
                screenLockHeld = keepOnLock?.isHeld == true
            }
        }
        if (ready) return null
        return tr("已尝试点亮主屏，但 2 秒内副屏输入仍未恢复可交互", "Attempted to wake the main screen, but virtual display input did not become interactive within 2 s")
    }

    private const val SCREEN_LOCK_MS = 2 * 60_000L

    /** One line for agents when a screen slept with the phone: what the user can do about it. */
    internal fun keepScreenHint(): String = if (VScreenStore.keepScreenOnEnabled) "" else
        tr("。可请用户在设置里把“熄屏时”改为“任务中保持亮屏”或“需要时点亮”", ". The user can set \"When screen is off\" in Settings to \"Keep on during tasks\" or \"Wake when needed\"")

    /** Test-only hook to simulate main-screen off/on transitions in unit tests. */
    internal fun setScreenOffForTest(off: Boolean) {
        screenOff = off
        InputReach.invalidate()
    }

    internal fun resetWakeForTest(ctx: Context? = null) = synchronized(this) {
        appContext = ctx?.applicationContext
        if (keepOnLock?.isHeld == true) runCatching { keepOnLock?.release() }
        if (wakeInputLock?.isHeld == true) runCatching { wakeInputLock?.release() }
        keepOnLock = null
        wakeInputLock = null
        screenLockHeld = false
        lastKeepOnLockFlags = 0
        lastInputWakeLockFlags = 0
        wakeLockFactory = null
        isInteractiveFn = null
        monoClockMs = { SystemClock.elapsedRealtime() }
        sleepFn = { Thread.sleep(it) }
        wakeTimeoutMs = 2_000L
        wakePollStepMs = 100L
        phoneWakeBudget.reset()
    }

    /** For status. */
    fun json(d: VScreenDisplay?): JSONObject {
        val mode = VScreenStore.phoneScreenMode.value
        val inputReach = when {
            !screenOff -> InputReach.Reach.OK.key
            d != null -> InputReach.probe(d.displayId).key
            else -> InputReach.Reach.UNKNOWN.key
        }
        return JSONObject()
            .put("phone_screen_off", screenOff)
            .put("display_state", d?.let { stateOf(it)?.let { s -> stateName(s) } } ?: JSONObject.NULL)
            .put("input", inputReach)
            .put("own_power_group", d?.let { canWakeAlone(it) } ?: JSONObject.NULL)
            .put("runs_when_locked", d?.let { (it.flags and ALWAYS_UNLOCKED) != 0 } ?: JSONObject.NULL)
            .put(
                "keep_screen_on",
                JSONObject()
                    .put("enabled", mode != VScreenStore.PhoneScreenMode.ALLOW_SLEEP)
                    .put("mode", mode.key)
                    .put("held", screenLockHeld)
                    .put("woke", screenWokeCount),
            )
            .put("flags_refused", org.json.JSONArray(VScreenRemoteDisplay.rejectedFlags))
            .put("wakes", wakes)
            .put("last_wake", lastWake ?: JSONObject.NULL)
            .put(
                "note",
                when {
                    d != null && !canWakeAlone(d) -> tr(
                        "这台手机上副屏随主屏休眠：熄屏后后台任务会停住，亮屏后继续",
                        "On this phone the screen sleeps with the main screen: background tasks pause while the screen is off and go on when it is on",
                    ) + (if (mode != VScreenStore.PhoneScreenMode.ALLOW_SLEEP) tr("；已开启任务中保持亮屏（${mode.key}）", "; keep-screen-on is active (${mode.key})") else keepScreenHint())
                    inputReach == InputReach.Reach.BLOCKED.key -> tr(
                        "主屏熄屏时系统禁止副屏接收触摸输入；元素点击、滚动与输入可改走无障碍通道，手势或坐标操作需要亮屏或开启“需要时点亮”",
                        "While the phone screen is off, the system blocks touch input on the background display; element taps, scrolls, and typing can use accessibility, while gestures or coordinate taps require the screen on or 'Wake when needed'",
                    )
                    else -> tr(
                        "熄屏时副屏单独保持唤醒（每 15 秒检查，熄屏时另有定时器），主屏不会被点亮",
                        "While the phone screen is off the background screen is kept awake on its own (checked every 15 s, plus a timer while off); the main screen is not lit",
                    )
                },
            )
    }
}
