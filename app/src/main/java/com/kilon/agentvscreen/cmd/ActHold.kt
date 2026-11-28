package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import android.provider.Settings
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.Gate
import com.kilon.agentvscreen.core.ManualScreen
import com.kilon.agentvscreen.core.VScreenRemoteDisplay
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2.0.0-beta5 (5.1, 5.6) + 2.0.0-beta8 (C2): App-side coordinator for `act --hold` (pausing the foreground app
 * on the virtual display between turns via [Gate] op 4 `HOLD` / op 5 `UNHOLD`, Android 14+ only).
 *
 * Beta8 C2 guarantees:
 *  - **Freeze and verify BEFORE replying**: never claims "已暂停" until the target display process is observed
 *    frozen (`cgroup.events` `frozen 1`, or `/proc/<pid>/wchan` in `{do_freezer_trap, __refrigerator}` when
 *    cgroup v2 is absent; `wchan=0` or unreadable counts as unverifiable, NEVER frozen).
 *  - **UID process enumeration**: authoritative PID set from `/sys/fs/cgroup/uid_<uid>/pid_*`, enriched with
 *    process names from `dumpsys activity processes` (works identically under Shizuku, `root su 2000`, and `root uid 0`).
 *  - **Display-process criterion**: identifies the process hosting the virtual display's top Activity via
 *    `dumpsys activity activities`. If the display process is verified frozen, hold succeeds and any unfrozen
 *    child process (e.g. `:worker` on API 34) is reported honestly; if the display process fails verification
 *    or dies (e.g. Android 15 binder-freeze kill), remaining processes are immediately thawed and `HOLD_REFUSED`
 *    is reported with a per-app circuit breaker.
 *  - **Upfront `HOLD_REFUSED`**: refuses `uid < 10000`, `FLAG_PERSISTENT`, current IME, and system-owned active
 *    media sessions BEFORE calling `am freeze`.
 *  - **Per-reason, per-app circuit breaker**: only an OS/command-level unsupported `am freeze` sets the device-wide
 *    flag; single-app failures or kills only trip that app for the current session.
 *  - **Diagnostics**: every hold attempt's per-process verification snapshot is kept for `vscreen diag`.
 */
internal object ActHold {
    private const val TAG = "ActHold"
    const val FIRST_APP_UID = 10_000
    internal const val VERIFY_SETTLE_MS = 160L

    enum class ProcFreezeState(val label: String) {
        FROZEN("frozen 1"),
        NOT_FROZEN("frozen 0"),
        DIED("died"),
        UNVERIFIABLE("unverifiable"),
    }

    data class TargetProcess(
        val pid: Int,
        val name: String,
        val isDisplayProc: Boolean,
    )

    data class ProcVerifyResult(
        val pid: Int,
        val name: String,
        val isDisplayProc: Boolean,
        val state: ProcFreezeState,
        val source: String, // "cgroup" | "wchan" | "none"
    ) {
        fun json(): JSONObject = JSONObject()
            .put("pid", pid)
            .put("name", name)
            .put("display_proc", isDisplayProc)
            .put("state", state.label)
            .put("source", source)
    }

    data class HoldDiagRecord(
        val atMs: Long,
        val pkg: String,
        val uid: Int,
        val outcome: String,
        val detail: String,
        val procs: List<ProcVerifyResult>,
    ) {
        fun formatLine(): String {
            val procStr = if (procs.isEmpty()) "none" else procs.joinToString("; ") { p ->
                val tag = if (p.isDisplayProc) "*" else ""
                "$tag${p.name}(${p.pid})=${p.state.label}[${p.source}]"
            }
            return "pkg=$pkg uid=$uid outcome=$outcome ($detail) procs=[$procStr]"
        }
    }

    data class AppInfoSnapshot(
        val uid: Int,
        val persistent: Boolean,
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
            return tr(S.act_hold_resumed_paused_for, sec)
        }
    }

    sealed class HoldOutcome {
        /** Android <= 13 or OS/Gate does not support `am freeze`: non-blocking `HOLD_UNSUPPORTED` note. */
        data class Unsupported(val text: String) : HoldOutcome()
        /** Upfront refusal (system/persistent/IME/media/circuit-breaker) or failed/died/unverifiable freeze: `HOLD_REFUSED`. */
        data class Refused(val text: String, val appKilled: Boolean = false) : HoldOutcome()
        /** Safety exclusion (protected pkg, main screen, takeover, teach, manual): silently skipped. */
        data class Skipped(val reason: String) : HoldOutcome()
        /** Verified frozen BEFORE sending reply: append [footerLine] and attach [holdJson]. */
        data class Held(
            val pkg: String,
            val appName: String,
            val maxMs: Long,
            val footerLine: String,
            val procs: List<ProcVerifyResult>,
        ) : HoldOutcome() {
            fun json(): JSONObject = JSONObject()
                .put("app", pkg)
                .put("name", appName)
                .put("max_ms", maxMs)
                .put("verified", true)
                .put("procs", JSONArray(procs.map { it.json() }))
        }
    }

    private val holdLock = Any()
    private var activeHold: ActiveHold? = null
    @Volatile private var deviceFreezeUnsupported: Boolean = false
    private val refusedBySession = HashMap<String, HashMap<String, String>>() // sid -> (pkg -> reason)
    private val diagHistory = ArrayDeque<HoldDiagRecord>(10)

    // Test seams (overridden only in unit tests; reset via resetForTest()).
    internal var sdkIntFn: () -> Int = { Build.VERSION.SDK_INT }
    internal var clockMsFn: () -> Long = { System.currentTimeMillis() }
    internal var sleepFn: (Long) -> Unit = { ms -> if (ms > 0L) try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
    internal var gateReadyFn: () -> Boolean = { VScreenRemoteDisplay.isConnected() }
    internal var gateHoldFn: (List<String>, Long) -> Int = { procs, maxMs ->
        VScreenRemoteDisplay.gateHold(procs, maxMs).getOrDefault(-1)
    }
    internal var gateUnholdFn: () -> Int = {
        VScreenRemoteDisplay.gateUnhold().getOrDefault(0)
    }
    internal var isDisplayBusyFn: (String) -> String? = { sid -> defaultBusyReason(sid) }
    internal var appInfoFn: (Context?, String) -> AppInfoSnapshot? = { ctx, pkg -> defaultAppInfo(ctx, pkg) }
    internal var currentImePkgFn: (Context?) -> String? = { ctx -> defaultImePkg(ctx) }
    internal var mediaSessionDumpFn: () -> String? = {
        val r = VScreenShell.run(arrayOf("dumpsys", "media_session"), 3_000L)
        if (r.exitCode == 0) r.stdout else null
    }
    internal var cgroupUidLsFn: (Int) -> String? = { uid ->
        val r = VScreenShell.run(arrayOf("ls", "-1", "/sys/fs/cgroup/uid_$uid"), 3_000L)
        if (r.exitCode == 0) r.stdout else null
    }
    internal var dumpsysProcessesFn: () -> String? = {
        val r = VScreenShell.run(arrayOf("dumpsys", "activity", "processes"), 4_000L)
        if (r.exitCode == 0) r.stdout else null
    }
    internal var dumpsysActivitiesFn: () -> String? = {
        val r = VScreenShell.run(arrayOf("dumpsys", "activity", "activities"), 4_000L)
        if (r.exitCode == 0) r.stdout else null
    }
    internal var psNamesFn: () -> String? = {
        val r = VScreenShell.run(arrayOf("ps", "-A", "-o", "NAME"), 3_000L)
        if (r.exitCode == 0) r.stdout else null
    }
    internal var verifyPidsFn: (Int, List<TargetProcess>) -> List<ProcVerifyResult> = { uid, targets ->
        defaultVerifyPids(uid, targets)
    }
    internal var checkFreezeCommandSupportedFn: () -> Boolean = { defaultCheckFreezeSupported() }

    // ── Pure parsers (unit-tested on JVM) ─────────────────────────────────

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
            if (name == pkg) hasMain = true
            else if (name.startsWith(prefix)) seen += name
        }
        val ordered = ArrayList<String>(seen.size + 1)
        if (hasMain) ordered += pkg
        ordered.addAll(seen)
        return ordered.take(max)
    }

    /** Pure parser for `ls -1 /sys/fs/cgroup/uid_<uid>`: extracts sorted PIDs from `pid_<N>` entries. */
    fun parseCgroupPids(lsOutput: String?): List<Int> {
        if (lsOutput.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<Int>()
        for (line in lsOutput.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("pid_")) {
                t.removePrefix("pid_").toIntOrNull()?.takeIf { it > 0 }?.let { out += it }
            }
        }
        return out.toList()
    }

    private val APP_PROC_RE = Regex("""\*APP\*\s+UID\s+(\d+)\s+ProcessRecord\{[^\s]+\s+(\d+):([^/\s]+)/""")

    /** Pure parser for `dumpsys activity processes`: maps PID -> processName for the given [uid]. */
    fun parseDumpsysProcessesForUid(dumpsysOut: String?, uid: Int): Map<Int, String> {
        if (dumpsysOut.isNullOrBlank() || uid < 0) return emptyMap()
        val map = LinkedHashMap<Int, String>()
        for (line in dumpsysOut.lineSequence()) {
            val m = APP_PROC_RE.find(line) ?: continue
            val lineUid = m.groupValues[1].toIntOrNull() ?: continue
            if (lineUid != uid) continue
            val pid = m.groupValues[2].toIntOrNull() ?: continue
            val name = m.groupValues[3].trim()
            if (pid > 0 && name.isNotEmpty()) map[pid] = name
        }
        return map
    }

    private val PROC_REC_RE = Regex("""ProcessRecord\{[^\s]+\s+(\d+):([^/\s]+)/""")
    private val DISPLAY_HDR_RE = Regex("""^\s*Display\s+#(\d+)\b""")

    /**
     * Pure parser for `dumpsys activity activities`: finds the top Activity's hosting process `(pid, procName)`
     * on [displayId] matching [pkg] (either `procName == pkg` or `procName.startsWith("$pkg:")`).
     */
    fun parseDisplayTopProcess(dumpsysActivities: String?, displayId: Int, pkg: String): Pair<Int, String>? {
        if (dumpsysActivities.isNullOrBlank() || pkg.isBlank()) return null
        var inTargetDisplay = false
        var best: Pair<Int, String>? = null
        val prefix = "$pkg:"
        for (line in dumpsysActivities.lineSequence()) {
            val hdr = DISPLAY_HDR_RE.find(line)
            if (hdr != null) {
                val id = hdr.groupValues[1].toIntOrNull()
                if (inTargetDisplay && id != displayId) break
                inTargetDisplay = (id == displayId)
                continue
            }
            if (!inTargetDisplay) continue
            val isResumedLine = line.contains("mResumedActivity") || line.contains("topResumedActivity") ||
                line.contains("app=ProcessRecord{")
            if (!isResumedLine) continue
            val m = PROC_REC_RE.find(line) ?: continue
            val pid = m.groupValues[1].toIntOrNull() ?: continue
            val name = m.groupValues[2].trim()
            if (name == pkg || name.startsWith(prefix)) {
                if (line.contains("mResumedActivity") || line.contains("topResumedActivity")) {
                    return pid to name
                }
                if (best == null) best = pid to name
            }
        }
        return best
    }

    /**
     * Pure merge of cgroup PIDs (authoritative) + `dumpsys activity processes` (names) + display top process.
     * Logs mismatch when cgroup and dumpsys disagree and trusts cgroup as authoritative.
     */
    fun buildTargetProcesses(
        pkg: String,
        cgroupPids: List<Int>,
        dumpsysMap: Map<Int, String>,
        displayTop: Pair<Int, String>?,
        max: Int = Gate.HOLD_MAX_PROCS,
    ): Pair<List<TargetProcess>, String?> {
        var mismatchNote: String? = null
        val pidList: List<Int> = if (cgroupPids.isNotEmpty()) {
            val cgSet = cgroupPids.toSet()
            val dmSet = dumpsysMap.keys
            if (dmSet.isNotEmpty() && cgSet != dmSet) {
                mismatchNote = "cgroup pids=$cgSet vs dumpsys pids=$dmSet (using cgroup)"
            }
            cgroupPids
        } else {
            dumpsysMap.keys.toList()
        }
        if (pidList.isEmpty()) return emptyList<TargetProcess>() to mismatchNote

        val displayPid = displayTop?.first?.takeIf { it in pidList }
        val displayName = displayTop?.second
        val rawTargets = pidList.map { pid ->
            val name = dumpsysMap[pid] ?: (if (pid == displayPid && displayName != null) displayName else pkg)
            val isDisp = when {
                displayPid != null -> pid == displayPid
                displayName != null -> name == displayName
                else -> name == pkg
            }
            TargetProcess(pid = pid, name = name, isDisplayProc = isDisp)
        }.toMutableList()

        // Ensure at least one process is marked as the display process (prefer exact pkg name, else first).
        if (rawTargets.none { it.isDisplayProc }) {
            val idx = rawTargets.indexOfFirst { it.name == pkg }.takeIf { it >= 0 } ?: 0
            rawTargets[idx] = rawTargets[idx].copy(isDisplayProc = true)
        }
        // Order: display process first, then main pkg, then remaining sorted by pid.
        val ordered = rawTargets.sortedWith(
            compareByDescending<TargetProcess> { it.isDisplayProc }
                .thenByDescending { it.name == pkg }
                .thenBy { it.pid }
        ).take(max)
        return ordered to mismatchNote
    }

    /**
     * Pure parser for cgroup v2 `cgroup.events` + fallback `/proc/<pid>/wchan`.
     * - If [cgroupEvents] contains `frozen 1` -> [ProcFreezeState.FROZEN]
     * - If [cgroupEvents] contains `frozen 0` -> [ProcFreezeState.NOT_FROZEN]
     * - If [pidExists] is false -> [ProcFreezeState.DIED]
     * - Else if [wchan] is `do_freezer_trap` or `__refrigerator` -> [ProcFreezeState.FROZEN]
     * - Else (`0`, empty, unreadable, or other kernel wait channel when cgroup unavailable) -> [ProcFreezeState.UNVERIFIABLE]
     */
    fun classifyProcessFreeze(
        cgroupEvents: String?,
        wchan: String?,
        pidExists: Boolean,
        cgroupAvailable: Boolean,
    ): Pair<ProcFreezeState, String> {
        if (!cgroupEvents.isNullOrBlank()) {
            val tokens = cgroupEvents.trim().split(Regex("\\s+"))
            val idx = tokens.indexOf("frozen")
            if (idx >= 0 && idx + 1 < tokens.size) {
                return when (tokens[idx + 1]) {
                    "1" -> ProcFreezeState.FROZEN to "cgroup"
                    "0" -> ProcFreezeState.NOT_FROZEN to "cgroup"
                    else -> ProcFreezeState.UNVERIFIABLE to "cgroup"
                }
            }
        }
        if (!pidExists) return ProcFreezeState.DIED to (if (cgroupAvailable) "cgroup" else "wchan")
        if (!cgroupAvailable && !wchan.isNullOrBlank()) {
            val w = wchan.trim()
            if (w == Gate.FREEZER_WCHAN || w == "__refrigerator") {
                return ProcFreezeState.FROZEN to "wchan"
            }
            // Note: on Android 14/15 uid 2000 (Shizuku), /proc/<pid>/wchan always reads "0" even when frozen!
            return ProcFreezeState.UNVERIFIABLE to "wchan"
        }
        return ProcFreezeState.UNVERIFIABLE to "none"
    }

    /** Pure check for whether `dumpsys media_session` shows an active playing session owned by a system process for [pkg]. */
    fun hasActiveSystemMediaSession(dumpsysMedia: String?, pkg: String): Boolean = playingSession(dumpsysMedia, pkg, systemOnly = true)

    /**
     * 2.0.0-beta12 (test H1: Bilibili playing a video stayed `frozen 0` while a clock and a calculator froze): whether
     * [pkg] has a media session that is playing. Android keeps an app that plays sound or video running (its media
     * service), and freezing it does not take.
     */
    fun isPlayingMedia(dumpsysMedia: String?, pkg: String): Boolean = playingSession(dumpsysMedia, pkg, systemOnly = false)

    private fun playingSession(dumpsysMedia: String?, pkg: String, systemOnly: Boolean): Boolean {
        if (dumpsysMedia.isNullOrBlank() || pkg.isBlank()) return false
        var blockPkg: String? = null
        var blockUid: Int? = null
        var blockPlaying = false
        fun flush(): Boolean {
            val match = blockPlaying && blockPkg == pkg && (!systemOnly || (blockUid != null && blockUid!! < FIRST_APP_UID))
            blockPkg = null; blockUid = null; blockPlaying = false
            return match
        }
        val pkgRe = Regex("""package=([^\s,]+)""")
        val uidRe = Regex("""userId=\d+.*uid=(\d+)|ownerUid=(\d+)|uid=(\d+)""")
        for (line in dumpsysMedia.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.contains("MediaSessionRecord") || trimmed.startsWith("com.") || trimmed.startsWith("android.")) {
                if (flush()) return true
            }
            pkgRe.find(trimmed)?.let { blockPkg = it.groupValues[1] }
            uidRe.find(trimmed)?.let { m ->
                val u = (1..3).firstNotNullOfOrNull { idx -> m.groupValues[idx].toIntOrNull() }
                if (u != null) blockUid = u
            }
            if (trimmed.contains("state=3") || trimmed.contains("PLAYING", ignoreCase = true) && trimmed.contains("state=")) {
                blockPlaying = true
            }
        }
        return flush()
    }

    /** Pure upfront refusal reason for dangerous/unfreezable targets (Ruling 3 & PLAN C2). */
    fun upfrontRefusalReason(
        pkg: String,
        appInfo: AppInfoSnapshot?,
        currentImePkg: String?,
        dumpsysMedia: String?,
        protectedManagers: Collection<String> = emptyList(),
    ): String? {
        if (pkg in protectedManagers) {
            return tr(S.act_hold_privilege_manager_app_refusing_to)
        }
        if (appInfo != null && appInfo.uid < FIRST_APP_UID) {
            return tr(S.act_hold_system_process_uid_refusing_to, appInfo.uid)
        }
        if (appInfo != null && appInfo.persistent) {
            return tr(S.act_hold_persistent_system_app_refusing_to)
        }
        if (!currentImePkg.isNullOrBlank() && currentImePkg == pkg) {
            return tr(S.act_hold_current_input_method_ime_refusing)
        }
        if (hasActiveSystemMediaSession(dumpsysMedia, pkg)) {
            return tr(S.act_hold_active_system_media_session_refusing)
        }
        return null
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

    // ── Main entry: freeze & verify synchronously BEFORE replying ─────────

    /**
     * Execute `--hold` BEFORE sending the reply:
     *  1. Check SDK / Gate / device-level unsupported / safety exclusions / upfront `HOLD_REFUSED` / per-app circuit breaker.
     *  2. Enumerate UID processes (cgroup + dumpsys) and display top-activity process.
     *  3. Freeze via [Gate], wait briefly, and verify every PID via `cgroup.events` (fallback `/proc/<pid>/wchan`).
     *  4. Return verified [HoldOutcome.Held], or unfreeze immediately and return [HoldOutcome.Refused] / [HoldOutcome.Unsupported].
     */
    fun holdBeforeReply(
        context: Context?,
        sid: String,
        displayId: Int,
        pkg: String?,
        appName: String,
        skipPkgs: Set<String>,
        mainTopPkg: String?,
        maxMs: Long = Gate.HOLD_MAX_MS,
    ): HoldOutcome {
        val sdk = sdkIntFn()
        if (sdk < Gate.MIN_UNFREEZE_SDK) {
            return HoldOutcome.Unsupported(
                tr(S.act_hold_this_device_is_android_api, sdk)
            )
        }
        if (!gateReadyFn()) {
            return HoldOutcome.Unsupported(
                tr(S.act_hold_privileged_gate_service_is_not)
            )
        }
        if (deviceFreezeUnsupported) {
            return HoldOutcome.Unsupported(
                tr(S.act_hold_this_device_does_not_support)
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
            return HoldOutcome.Skipped(excl ?: "no foreground app")
        }

        // Per-app circuit breaker within this session.
        val prevRefusal = synchronized(holdLock) { refusedBySession[sid]?.get(pkg) }
        if (prevRefusal != null) {
            val msg = tr(S.act_hold_skipping_pause_for_in_this, appName, prevRefusal)
            recordDiag(pkg, -1, "REFUSED_BREAKER", prevRefusal, emptyList())
            return HoldOutcome.Refused(msg)
        }

        // Upfront HOLD_REFUSED: uid < 10000, persistent, current IME, system media session.
        val info = runCatching { appInfoFn(context, pkg) }.getOrNull()
        val imePkg = runCatching { currentImePkgFn(context) }.getOrNull()
        val mediaDump = if (info != null && info.uid < FIRST_APP_UID) null else runCatching { mediaSessionDumpFn() }.getOrNull()
        val managers = runCatching { com.kilon.agentvscreen.host.ShizukuManager.discoveredManagerPackages() }.getOrDefault(emptyList())
        val upfront = upfrontRefusalReason(pkg, info, imePkg, mediaDump, managers)
        if (upfront != null) {
            val msg = tr(S.act_hold_refused_to_pause, appName, upfront)
            recordDiag(pkg, info?.uid ?: -1, "REFUSED_UPFRONT", upfront, emptyList())
            return HoldOutcome.Refused(msg)
        }

        val effMaxMs = maxMs.coerceIn(1_000L, Gate.HOLD_MAX_MS)
        val uid = info?.uid ?: -1
        return synchronized(holdLock) {
            // Re-check dynamic busy state right before freezing.
            isDisplayBusyFn(sid)?.let { return@synchronized HoldOutcome.Skipped(it) }

            val cgPids = if (uid >= 0) parseCgroupPids(runCatching { cgroupUidLsFn(uid) }.getOrNull()) else emptyList()
            val dsMap = if (uid >= 0) parseDumpsysProcessesForUid(runCatching { dumpsysProcessesFn() }.getOrNull(), uid) else emptyMap()
            val dispTop = parseDisplayTopProcess(runCatching { dumpsysActivitiesFn() }.getOrNull(), displayId, pkg)
            val (targets, mismatch) = buildTargetProcesses(pkg, cgPids, dsMap, dispTop, Gate.HOLD_MAX_PROCS)
            if (mismatch != null) AppLogger.warning(TAG, "UID $uid ($pkg) process mismatch: $mismatch")

            val procNames: List<String> = when {
                targets.isNotEmpty() -> targets.map { it.name }.distinct().take(Gate.HOLD_MAX_PROCS)
                else -> {
                    val psOut = runCatching { psNamesFn() }.getOrNull()
                    val parsed = if (psOut != null) parseProcessNames(psOut, pkg, Gate.HOLD_MAX_PROCS) else emptyList()
                    parsed.ifEmpty { listOf(pkg) }
                }
            }

            val frozenReported = runCatching { gateHoldFn(procNames, effMaxMs) }.getOrDefault(-1)
            if (frozenReported <= 0) {
                runCatching { gateUnholdFn() }
                val cmdSupported = runCatching { checkFreezeCommandSupportedFn() }.getOrDefault(true)
                if (!cmdSupported) {
                    deviceFreezeUnsupported = true
                    val msg = tr(S.act_hold_this_device_does_not_support)
                    recordDiag(pkg, uid, "UNSUPPORTED", "am freeze command unavailable", emptyList())
                    return@synchronized HoldOutcome.Unsupported(msg)
                }
                val reason = tr(S.act_hold_am_freeze_returned_failure)
                tripAppBreakerLocked(sid, pkg, reason)
                val msg = tr(S.act_hold_could_not_pause, appName, reason)
                recordDiag(pkg, uid, "FAILED_RC", "gateHold=$frozenReported procs=$procNames", emptyList())
                return@synchronized HoldOutcome.Refused(msg)
            }

            // Wait briefly and verify actual kernel freeze state BEFORE replying!
            sleepFn(VERIFY_SETTLE_MS)
            val verified = if (targets.isNotEmpty() && uid >= 0) {
                runCatching { verifyPidsFn(uid, targets) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val screenRes = verified.firstOrNull { it.isDisplayProc } ?: verified.firstOrNull()
            when (screenRes?.state) {
                ProcFreezeState.DIED -> {
                    runCatching { gateUnholdFn() }
                    val reason = tr(S.act_hold_killed_by_the_system_on)
                    tripAppBreakerLocked(sid, pkg, reason)
                    val msg = tr(S.act_hold_was_killed_by_the_system, appName)
                    recordDiag(pkg, uid, "DIED", "display proc ${screenRes.name}(${screenRes.pid}) vanished after freeze", verified)
                    HoldOutcome.Refused(msg, appKilled = true)
                }
                ProcFreezeState.FROZEN -> {
                    activeHold = ActiveHold(sid, displayId, pkg, appName, procNames, clockMsFn())
                    val secs = effMaxMs / 1000L
                    val unfrozenOthers = verified.filter { !it.isDisplayProc && it.state != ProcFreezeState.FROZEN }
                    val footer = if (unfrozenOthers.isEmpty()) {
                        tr(S.act_hold_paused_will_resume_on_the, appName, secs)
                    } else {
                        val names = unfrozenOthers.joinToString("、") { it.name }
                        val namesEn = unfrozenOthers.joinToString(", ") { it.name }
                        tr(S.act_hold_paused_display_process_background_proces, appName, names, secs, namesEn)
                    }
                    recordDiag(pkg, uid, if (unfrozenOthers.isEmpty()) "HELD" else "HELD_PARTIAL", "verified via ${screenRes.source}", verified)
                    HoldOutcome.Held(pkg, appName, effMaxMs, footer, verified)
                }
                ProcFreezeState.NOT_FROZEN -> {
                    runCatching { gateUnholdFn() }
                    val playing = isPlayingMedia(mediaDump ?: runCatching { mediaSessionDumpFn() }.getOrNull(), pkg)
                    val msg = if (playing) {
                        // Not a fault of this app or this phone, and it can change (playback paused): no breaker.
                        tr(S.act_hold_could_not_pause_it_is, appName)
                    } else {
                        val reason = tr(S.act_hold_process_remained_unfrozen)
                        tripAppBreakerLocked(sid, pkg, reason)
                        tr(S.act_hold_could_not_pause, appName, reason)
                    }
                    recordDiag(pkg, uid, if (playing) "NOT_FROZEN_PLAYING" else "NOT_FROZEN", "display proc ${screenRes.name}(${screenRes.pid}) stayed frozen 0", verified)
                    HoldOutcome.Refused(msg)
                }
                ProcFreezeState.UNVERIFIABLE, null -> {
                    runCatching { gateUnholdFn() }
                    val reason = tr(S.act_hold_could_not_verify_frozen_state)
                    tripAppBreakerLocked(sid, pkg, reason)
                    val msg = tr(S.act_hold_sent_pause_for_but_could, appName)
                    recordDiag(pkg, uid, "UNVERIFIABLE", "targets=${targets.size} verified=${verified.size}", verified)
                    HoldOutcome.Refused(msg)
                }
            }
        }
    }

    private fun tripAppBreakerLocked(sid: String, pkg: String, reason: String) {
        refusedBySession.getOrPut(sid) { HashMap() }[pkg] = reason
    }

    private fun recordDiag(pkg: String, uid: Int, outcome: String, detail: String, procs: List<ProcVerifyResult>) {
        val rec = HoldDiagRecord(clockMsFn(), pkg, uid, outcome, detail, procs)
        AppLogger.info(TAG, rec.formatLine())
        synchronized(diagHistory) {
            if (diagHistory.size >= 10) diagHistory.removeFirst()
            diagHistory.addLast(rec)
        }
    }

    fun diagSummary(): String = synchronized(diagHistory) {
        if (diagHistory.isEmpty()) return "no holds recorded"
        diagHistory.joinToString("\n  ") { it.formatLine() }
    }

    fun clearSessionBreaker(sid: String) {
        synchronized(holdLock) { refusedBySession.remove(sid) }
    }

    /**
     * 5.1 & 5.6: called at the very top of [VScreenOffloadHandler.handleCommand] before ANY command logic
     * (including `look` and `status`). Synchronously unholds any active hold and returns [ResumedHold].
     */
    fun unholdBeforeCommand(): ResumedHold? {
        synchronized(holdLock) {
            val act = activeHold ?: return null
            activeHold = null
            runCatching { gateUnholdFn() }
                .onFailure { AppLogger.warning(TAG, "unholdBeforeCommand failed: ${it.message}") }
            val pausedMs = (clockMsFn() - act.frozenAtMs).coerceAtLeast(0L)
            return ResumedHold(act.sid, act.pkg, act.appName, pausedMs)
        }
    }

    /** Unhold on non-command events (user takeover, user stop, display close, session release). */
    fun unholdNow(reason: String) {
        val needUnhold = synchronized(holdLock) { activeHold != null }
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

    // ── Default Android implementations ───────────────────────────────────

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

    private fun defaultAppInfo(ctx: Context?, pkg: String): AppInfoSnapshot? {
        val c = ctx ?: BackgroundOperation.appContextOrNull() ?: return null
        return runCatching {
            val ai = c.packageManager.getApplicationInfo(pkg, 0)
            AppInfoSnapshot(uid = ai.uid, persistent = (ai.flags and ApplicationInfo.FLAG_PERSISTENT) != 0)
        }.getOrNull()
    }

    private fun defaultImePkg(ctx: Context?): String? {
        val c = ctx ?: BackgroundOperation.appContextOrNull() ?: return null
        return runCatching {
            Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun defaultCheckFreezeSupported(): Boolean {
        val r = runCatching { VScreenShell.run(arrayOf("am", "help"), 3_000L) }.getOrNull() ?: return true
        val txt = r.combined
        return txt.contains("freeze", ignoreCase = true)
    }

    private fun defaultVerifyPids(uid: Int, targets: List<TargetProcess>): List<ProcVerifyResult> {
        if (targets.isEmpty()) return emptyList()
        var cgAvail = uid >= 0 && runCatching {
            VScreenShell.run(arrayOf("ls", "-1d", "/sys/fs/cgroup/uid_$uid"), 2_000L).exitCode == 0
        }.getOrDefault(false)
        val byPid = HashMap<Int, Triple<Boolean, String?, String?>>()
        val safeCgroupPath = Regex("^/[A-Za-z0-9_./:-]+$")
        for (tgt in targets) {
            val pid = tgt.pid
            if (pid <= 0) continue
            var exists = false
            var ev: String? = null
            var wc: String? = null
            // 2.0.0-beta11: read each process's cgroup v2 path from /proc/<pid>/cgroup ("0::<path>") first.
            val cgRes = runCatching { VScreenShell.run(arrayOf("cat", "/proc/$pid/cgroup"), 2_000L) }.getOrNull()
            if (cgRes != null && cgRes.exitCode == 0) {
                exists = true
                val rel = cgRes.stdout.lineSequence()
                    .firstOrNull { it.startsWith("0::") }
                    ?.removePrefix("0::")
                    ?.trim()
                if (rel != null && safeCgroupPath.matches(rel) && !rel.contains("..")) {
                    val evPath = "/sys/fs/cgroup${rel.trimEnd('/')}/cgroup.events"
                    val evRes = runCatching { VScreenShell.run(arrayOf("cat", evPath), 2_000L) }.getOrNull()
                    if (evRes != null && evRes.exitCode == 0 && evRes.stdout.isNotBlank()) {
                        cgAvail = true
                        ev = evRes.stdout.replace('\n', ' ').trim()
                    }
                }
            }
            if (ev == null && uid >= 0) {
                val fallbackEv = "/sys/fs/cgroup/uid_$uid/pid_$pid/cgroup.events"
                val evRes = runCatching { VScreenShell.run(arrayOf("cat", fallbackEv), 2_000L) }.getOrNull()
                if (evRes != null && evRes.exitCode == 0 && evRes.stdout.isNotBlank()) {
                    cgAvail = true
                    exists = true
                    ev = evRes.stdout.replace('\n', ' ').trim()
                }
            }
            if (ev == null) {
                val wcRes = runCatching { VScreenShell.run(arrayOf("cat", "/proc/$pid/wchan"), 2_000L) }.getOrNull()
                if (wcRes != null && wcRes.exitCode == 0) {
                    exists = true
                    wc = wcRes.stdout.trim().takeIf { it.isNotEmpty() }
                }
            }
            byPid[pid] = Triple(exists, ev, wc)
        }
        // Double-check any apparently-missing PID via dumpsys activity processes so we never misclassify hidepid=2 as DIED
        // when cgroup wasn't available.
        val missingPids = targets.filter { t -> byPid[t.pid]?.first != true }
        val liveFromDumpsys: Set<Int> = if (!cgAvail && missingPids.isNotEmpty()) {
            parseDumpsysProcessesForUid(runCatching { dumpsysProcessesFn() }.getOrNull(), uid).keys
        } else emptySet()

        return targets.map { tgt ->
            val raw = byPid[tgt.pid]
            val exists = (raw?.first == true) || (tgt.pid in liveFromDumpsys)
            val (state, src) = classifyProcessFreeze(raw?.second, raw?.third, exists, cgAvail)
            ProcVerifyResult(tgt.pid, tgt.name, tgt.isDisplayProc, state, src)
        }
    }

    internal fun resetForTest() {
        synchronized(holdLock) {
            activeHold = null
            deviceFreezeUnsupported = false
            refusedBySession.clear()
        }
        synchronized(diagHistory) { diagHistory.clear() }
        sdkIntFn = { Build.VERSION.SDK_INT }
        clockMsFn = { System.currentTimeMillis() }
        sleepFn = { ms -> if (ms > 0L) try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
        gateReadyFn = { VScreenRemoteDisplay.isConnected() }
        gateHoldFn = { procs, maxMs -> VScreenRemoteDisplay.gateHold(procs, maxMs).getOrDefault(-1) }
        gateUnholdFn = { VScreenRemoteDisplay.gateUnhold().getOrDefault(0) }
        isDisplayBusyFn = { sid -> defaultBusyReason(sid) }
        appInfoFn = { ctx, pkg -> defaultAppInfo(ctx, pkg) }
        currentImePkgFn = { ctx -> defaultImePkg(ctx) }
        mediaSessionDumpFn = {
            val r = VScreenShell.run(arrayOf("dumpsys", "media_session"), 3_000L)
            if (r.exitCode == 0) r.stdout else null
        }
        cgroupUidLsFn = { uid ->
            val r = VScreenShell.run(arrayOf("ls", "-1", "/sys/fs/cgroup/uid_$uid"), 3_000L)
            if (r.exitCode == 0) r.stdout else null
        }
        dumpsysProcessesFn = {
            val r = VScreenShell.run(arrayOf("dumpsys", "activity", "processes"), 4_000L)
            if (r.exitCode == 0) r.stdout else null
        }
        dumpsysActivitiesFn = {
            val r = VScreenShell.run(arrayOf("dumpsys", "activity", "activities"), 4_000L)
            if (r.exitCode == 0) r.stdout else null
        }
        psNamesFn = {
            val r = VScreenShell.run(arrayOf("ps", "-A", "-o", "NAME"), 3_000L)
            if (r.exitCode == 0) r.stdout else null
        }
        verifyPidsFn = { uid, targets -> defaultVerifyPids(uid, targets) }
        checkFreezeCommandSupportedFn = { defaultCheckFreezeSupported() }
    }
}
