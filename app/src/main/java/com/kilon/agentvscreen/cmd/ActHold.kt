package com.kilon.agentvscreen.cmd

import android.os.Build
import android.os.Looper
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.Gate
import com.kilon.agentvscreen.core.ManualScreen
import com.kilon.agentvscreen.core.VScreenRemoteDisplay
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.tr

/**
 * 2.0.0-beta5 (5.1, 5.6 & 5.8 #1): App-side coordinator for `act --hold` (pausing the foreground app on the
 * virtual display between turns via [Gate] op 4 `HOLD` / op 5 `UNHOLD`, Android 14+ only).
 *
 * Boundary guarantees (5.6):
 *  - Freezing runs in [NativeOffloadResult.afterSend] AFTER the HTTP/MCP reply is flushed, never slowing down the reply.
 *  - Unfreezing runs synchronously at the top of [VScreenOffloadHandler.handleCommand] before ANY command logic
 *    (including `look`, `status`, `release`, or commands from another session).
 *  - A single [holdLock] + monotonic [holdGen] serializes post-send freeze vs fast-arriving next command / takeover /
 *    release / display close so a pending freeze can never fire after the next command already started.
 *  - Never freezes `handoffSkip()` packages, the main display's foreground package, or an app on a screen currently
 *    being taught, taken over, or used manually.
 */
internal object ActHold {
    private const val TAG = "ActHold"

    private class PendingHold(
        val gen: Long,
        val sid: String,
        val displayId: Int,
        val pkg: String,
        val appName: String,
        val maxMs: Long,
    )

    private class ActiveHold(
        val sid: String,
        val displayId: Int,
        val pkg: String,
        val appName: String,
        val procs: List<String>,
        val frozenAtMs: Long,
    )

    data class ResumedHold(
        val sid: String,
        val pkg: String,
        val appName: String,
        val pausedMs: Long,
    ) {
        fun line(): String {
            val sec = "${(pausedMs.coerceAtLeast(0L) / 100) / 10.0}s"
            return tr("已恢复（暂停了 $sec）", "Resumed (paused for $sec)")
        }
    }

    sealed class PrepareOutcome {
        /** Android <= 13 or freeze unavailable/rejected: non-blocking `HOLD_UNSUPPORTED` note, act succeeds normally. */
        data class Unsupported(val text: String) : PrepareOutcome()
        /** Safety exclusion (system/launcher/main-screen/takeover/teach): silently skipped, no freeze. */
        data class Skipped(val reason: String) : PrepareOutcome()
        /** Eligible on Android 14+: append [footerLine] to reply and invoke [afterSend] once reply is flushed. */
        data class Armed(
            val pkg: String,
            val appName: String,
            val maxMs: Long,
            val footerLine: String,
            val afterSend: () -> Unit,
        ) : PrepareOutcome()
    }

    private val holdLock = Any()
    private var holdGen: Long = 0L
    private var pendingHold: PendingHold? = null
    private var activeHold: ActiveHold? = null
    @Volatile private var deviceFreezeUnsupported: Boolean = false

    // Test seams (overridden only by unit tests in V200Beta5Test; reset via resetForTest()).
    internal var sdkIntFn: () -> Int = { Build.VERSION.SDK_INT }
    internal var clockMsFn: () -> Long = { System.currentTimeMillis() }
    internal var gateReadyFn: () -> Boolean = { VScreenRemoteDisplay.isConnected() }
    internal var gateHoldFn: (List<String>, Long) -> Int = { procs, maxMs ->
        VScreenRemoteDisplay.gateHold(procs, maxMs).getOrDefault(-1)
    }
    internal var gateUnholdFn: () -> Int = {
        VScreenRemoteDisplay.gateUnhold().getOrDefault(0)
    }
    internal var psNamesFn: () -> String? = {
        val r = VScreenShell.run(arrayOf("ps", "-A", "-o", "NAME"))
        if (r.exitCode == 0) r.stdout else null
    }
    internal var isDisplayBusyFn: (String) -> String? = { sid -> defaultBusyReason(sid) }

    /**
     * Pure parser for `ps -A -o NAME` output: picks processes equal to [pkg] or starting with `"$pkg:"`,
     * main process first, deduplicated, up to [max] (8).
     */
    fun parseProcessNames(psOutput: String, pkg: String, max: Int = Gate.HOLD_MAX_PROCS): List<String> {
        if (pkg.isBlank() || max <= 0) return emptyList()
        val prefix = "$pkg:"
        val seen = LinkedHashSet<String>()
        var hasMain = false
        for (raw in psOutput.lineSequence()) {
            val name = raw.trim()
            if (name.isEmpty() || name.equals("NAME", ignoreCase = true)) continue
            if (name == pkg) {
                hasMain = true
            } else if (name.startsWith(prefix)) {
                seen += name
            }
        }
        val ordered = ArrayList<String>(seen.size + 1)
        if (hasMain) ordered += pkg
        ordered.addAll(seen)
        return ordered.take(max)
    }

    /** Pure safety exclusion check per 5.1 & 5.6. Returns non-null reason when freezing must be skipped. */
    fun exclusionReason(
        pkg: String?,
        skipPkgs: Set<String>,
        mainTopPkg: String?,
        takeover: Boolean,
        teaching: Boolean,
        manual: Boolean,
    ): String? = when {
        pkg.isNullOrBlank() -> "no foreground app"
        pkg in skipPkgs -> "protected package ($pkg)"
        mainTopPkg != null && pkg == mainTopPkg -> "app active on main screen ($pkg)"
        takeover -> "user takeover active"
        teaching -> "lesson recording/prep active"
        manual -> "manual user screen"
        else -> null
    }

    private fun defaultBusyReason(sid: String): String? {
        val st = BackgroundOperation.state.value
        val lesson = Teach.lesson.value
        val teaching = Teach.recording || lesson is Lesson.Ready || lesson is Lesson.Recording || lesson is Lesson.Ending
        return when {
            st.takeoverSessionId != null -> "user takeover active"
            teaching -> "lesson recording/prep active"
            ManualScreen.isManual(sid) -> "manual user screen"
            else -> null
        }
    }

    fun prepare(
        sid: String,
        displayId: Int,
        pkg: String?,
        appName: String,
        skipPkgs: Set<String>,
        mainTopPkg: String?,
        maxMs: Long = Gate.HOLD_MAX_MS,
    ): PrepareOutcome {
        val sdk = sdkIntFn()
        if (sdk < Gate.MIN_UNFREEZE_SDK) {
            return PrepareOutcome.Unsupported(
                tr(
                    "当前系统为 Android API $sdk（--hold 仅支持 Android 14 / API 34 及以上），本次未暂停应用",
                    "This device is Android API $sdk (--hold requires Android 14 / API 34+); the app was not paused",
                )
            )
        }
        if (!gateReadyFn()) {
            return PrepareOutcome.Unsupported(
                tr(
                    "守门人特权服务未连接，本次未暂停应用",
                    "Privileged Gate service is not connected; the app was not paused",
                )
            )
        }
        if (deviceFreezeUnsupported) {
            return PrepareOutcome.Unsupported(
                tr(
                    "系统不支持 am freeze（上次暂停返回非 0），本次未暂停应用",
                    "The system rejected am freeze (previous freeze returned non-zero); the app was not paused",
                )
            )
        }
        val busy = isDisplayBusyFn(sid)
        val excl = exclusionReason(
            pkg = pkg,
            skipPkgs = skipPkgs,
            mainTopPkg = mainTopPkg,
            takeover = busy == "user takeover active",
            teaching = busy == "lesson recording/prep active",
            manual = busy == "manual user screen",
        ) ?: busy
        if (excl != null || pkg.isNullOrBlank()) {
            return PrepareOutcome.Skipped(excl ?: "no foreground app")
        }
        val effMaxMs = maxMs.coerceIn(1_000L, Gate.HOLD_MAX_MS)
        val gen = synchronized(holdLock) {
            val g = ++holdGen
            pendingHold = PendingHold(g, sid, displayId, pkg, appName, effMaxMs)
            g
        }
        val secs = effMaxMs / 1000L
        val footer = tr(
            "已暂停 $appName，下一条命令时恢复（最长 $secs 秒）",
            "Paused $appName; will resume on the next command (max ${secs}s)",
        )
        return PrepareOutcome.Armed(
            pkg = pkg,
            appName = appName,
            maxMs = effMaxMs,
            footerLine = footer,
            afterSend = { executeArmedHold(gen) },
        )
    }

    /** Executed immediately after the HTTP/MCP reply is flushed. Never throws. */
    private fun executeArmedHold(gen: Long) {
        synchronized(holdLock) {
            val p = pendingHold
            if (p == null || p.gen != gen) return
            pendingHold = null
            // Re-check dynamic busy state right before freezing in case user took over during flush.
            if (isDisplayBusyFn(p.sid) != null) return
            val psOut = runCatching { psNamesFn() }.getOrNull()
            val parsed = if (psOut != null) {
                parseProcessNames(psOut, p.pkg, Gate.HOLD_MAX_PROCS)
            } else {
                emptyList()
            }
            // Under Magisk/KernelSU `su 2000`, setgroups(0, NULL) drops gid 3009 (readproc) so `/proc` hidepid=2
            // hides app UIDs from `ps -A`. Fall back to the known foreground package name, which `am freeze` resolves
            // via ActivityManagerService without reading `/proc`.
            val procs = parsed.ifEmpty { listOf(p.pkg) }
            val frozen = runCatching { gateHoldFn(procs, p.maxMs) }.getOrDefault(-1)
            if (frozen > 0) {
                activeHold = ActiveHold(p.sid, p.displayId, p.pkg, p.appName, procs, clockMsFn())
            } else {
                deviceFreezeUnsupported = true
                AppLogger.warning(TAG, "post-send gateHold failed for ${p.pkg} (procs=$procs, frozen=$frozen)")
            }
        }
    }

    /**
     * 5.1 & 5.6: called at the very top of [VScreenOffloadHandler.handleCommand] before ANY command logic
     * (including `look` and `status`). Cancels any pending post-send freeze and synchronously unholds any active hold.
     * Returns [ResumedHold] iff an active hold was thawed.
     */
    fun unholdBeforeCommand(): ResumedHold? {
        synchronized(holdLock) {
            holdGen++
            pendingHold = null
            val act = activeHold ?: return null
            activeHold = null
            runCatching { gateUnholdFn() }
                .onFailure { AppLogger.warning(TAG, "unholdBeforeCommand failed: ${it.message}") }
            val pausedMs = (clockMsFn() - act.frozenAtMs).coerceAtLeast(0L)
            return ResumedHold(act.sid, act.pkg, act.appName, pausedMs)
        }
    }

    /**
     * Unhold on non-command events (user takeover, user stop, display close, session release).
     * Safe to invoke from the Android main thread (cancels pending freeze synchronously; dispatches binder unhold off main).
     */
    fun unholdNow(reason: String) {
        val needUnhold = synchronized(holdLock) {
            holdGen++
            pendingHold = null
            activeHold != null
        }
        if (!needUnhold) return
        val task = {
            synchronized(holdLock) {
                val act = activeHold ?: return@synchronized
                activeHold = null
                AppLogger.info(TAG, "unholdNow($reason): thawing ${act.pkg}")
                runCatching { gateUnholdFn() }
                    .onFailure { AppLogger.warning(TAG, "unholdNow($reason) failed: ${it.message}") }
            }
        }
        val onMain = runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(false)
        if (onMain) {
            Thread(task, "vscreen-hold-unhold").apply { isDaemon = true }.start()
        } else {
            task()
        }
    }

    fun isHeld(): Boolean = synchronized(holdLock) { activeHold != null }
    fun isPending(): Boolean = synchronized(holdLock) { pendingHold != null }

    internal fun resetForTest() {
        synchronized(holdLock) {
            holdGen = 0L
            pendingHold = null
            activeHold = null
            deviceFreezeUnsupported = false
        }
        sdkIntFn = { Build.VERSION.SDK_INT }
        clockMsFn = { System.currentTimeMillis() }
        gateReadyFn = { VScreenRemoteDisplay.isConnected() }
        gateHoldFn = { procs, maxMs -> VScreenRemoteDisplay.gateHold(procs, maxMs).getOrDefault(-1) }
        gateUnholdFn = { VScreenRemoteDisplay.gateUnhold().getOrDefault(0) }
        psNamesFn = {
            val r = VScreenShell.run(arrayOf("ps", "-A", "-o", "NAME"))
            if (r.exitCode == 0) r.stdout else null
        }
        isDisplayBusyFn = { sid -> defaultBusyReason(sid) }
    }
}
