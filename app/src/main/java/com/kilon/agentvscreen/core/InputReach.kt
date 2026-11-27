package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.host.AppLogger

/**
 * Probes whether a virtual display can receive injected input events while the phone's main screen is off.
 *
 * Some Android systems keep an `OWN_DISPLAY_GROUP` virtual display in `Display.STATE_ON` while still marking it
 * non-interactive in InputDispatcher when the main screen turns off, silently stripping `POLICY_FLAG_INTERACTIVE`
 * and `POLICY_FLAG_PASS_TO_USER` from injected touch events. Separating "display powered" from "input reachable"
 * lets the action router switch to accessibility node actions or report `INPUT_BLOCKED_SCREEN_OFF` instead of
 * failing blindly with `STEP_MISSED`.
 */
internal object InputReach {
    private const val TAG = "InputReach"

    enum class Reach(val key: String) {
        OK("ok"),
        BLOCKED("blocked"),
        UNKNOWN("unknown"),
    }

    data class ProbeResult(val reach: Reach, val via: String)

    private data class CacheKey(val displayId: Int, val screenOff: Boolean)

    private val cache = HashMap<CacheKey, ProbeResult>()
    @Volatile private var last: ProbeResult = ProbeResult(Reach.OK, "n/a")

    // ICU-safe regex (no bare `{` or `}`): matches lines reporting non-interactive display sets in `dumpsys input`.
    private val NON_INTERACTIVE_LINE_RE =
        Regex("(?i)(?:display\\s+not\\s+interactive|non[-\\s]?interactive\\s+displays?)\\s*[:=]\\s*(.*)")
    private val NON_EMPTY_NONE_RE = Regex("(?i)^(?:none|<none>|empty|null)$")
    private val INT_RE = Regex("^\\d+$")

    /**
     * Pure parser for `dumpsys input` non-interactive display listings, e.g.:
     *  - `Display not interactive: {0, 37}` -> `setOf(0, 37)`
     *  - `Display not interactive: {}` / `none` -> `emptySet()`
     *  - missing line or unrecognized value format -> `null` (caller maps to [Reach.UNKNOWN])
     */
    internal fun parseNonInteractive(dump: String): Set<Int>? {
        for (rawLine in dump.lineSequence()) {
            val m = NON_INTERACTIVE_LINE_RE.find(rawLine) ?: continue
            val rhs = m.groupValues[1].trim()
            if (rhs.isEmpty()) return null
            if (NON_EMPTY_NONE_RE.matches(rhs)) return emptySet()
            val stripped = when {
                rhs.startsWith("{") && rhs.endsWith("}") -> rhs.substring(1, rhs.length - 1).trim()
                rhs.startsWith("[") && rhs.endsWith("]") -> rhs.substring(1, rhs.length - 1).trim()
                rhs.contains('{') || rhs.contains('}') || rhs.contains('[') || rhs.contains(']') -> return null
                else -> rhs
            }
            if (stripped.isEmpty() || NON_EMPTY_NONE_RE.matches(stripped)) return emptySet()
            val parts = stripped.split(',', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return emptySet()
            val out = LinkedHashSet<Int>(parts.size)
            for (p in parts) {
                if (!INT_RE.matches(p)) return null
                val id = p.toIntOrNull() ?: return null
                out += id
            }
            return out
        }
        return null
    }

    /** Probe input reachability for [displayId] (returns [Reach.OK] immediately when the main screen is on). */
    fun probe(displayId: Int, force: Boolean = false): Reach = probeResult(displayId, force).reach

    /** Full probe result including the source mechanism (`isInteractive`, `dumpsys`, or `n/a`). */
    fun probeResult(displayId: Int, force: Boolean = false): ProbeResult {
        if (!VScreenAwake.screenOff) {
            return ProbeResult(Reach.OK, "n/a").also { last = it }
        }
        val key = CacheKey(displayId, true)
        if (!force) {
            synchronized(cache) { cache[key] }?.let { return it }
        }
        val computed = probeScreenOff(displayId)
        synchronized(cache) {
            cache[key] = computed
            last = computed
        }
        AppLogger.info(TAG, "display=$displayId reach=${computed.reach.key} via=${computed.via}")
        return computed
    }

    private fun probeScreenOff(displayId: Int): ProbeResult {
        val interactive = runCatching { VScreenRemoteDisplay.isDisplayInteractive(displayId) }.getOrNull()
        if (interactive != null) {
            return ProbeResult(if (interactive) Reach.OK else Reach.BLOCKED, "isInteractive")
        }
        val dump = runCatching { VScreenShell.run(arrayOf("dumpsys", "input"), 4_000L) }.getOrNull()
        if (dump != null && dump.exitCode == 0) {
            val nonInteractive = parseNonInteractive(dump.stdout)
            if (nonInteractive != null) {
                return ProbeResult(if (displayId in nonInteractive) Reach.BLOCKED else Reach.OK, "dumpsys")
            }
            return ProbeResult(Reach.UNKNOWN, "dumpsys")
        }
        return ProbeResult(Reach.UNKNOWN, "n/a")
    }

    /** Invalidate cached results for [displayId], or all displays when null. */
    fun invalidate(displayId: Int? = null) = synchronized(cache) {
        if (displayId == null) cache.clear()
        else cache.keys.removeAll { it.displayId == displayId }
    }

    /** True when the latest probe reported [Reach.BLOCKED] (for UI recommendation hints). */
    fun lastBlocked(): Boolean = last.reach == Reach.BLOCKED

    /** Concise diagnostic line for `vscreen diag`. */
    fun diagSummary(displayId: Int?): String {
        val r = if (!VScreenAwake.screenOff) ProbeResult(Reach.OK, "n/a")
        else if (displayId != null) synchronized(cache) { cache[CacheKey(displayId, true)] } ?: last
        else last
        return "${r.reach.key} (via ${r.via})"
    }

    /** Test-only injection hook to set a cached screen-off result without running shell/Binder. */
    internal fun seedForTest(displayId: Int, screenOff: Boolean, result: ProbeResult) = synchronized(cache) {
        cache[CacheKey(displayId, screenOff)] = result
        last = result
    }
}
