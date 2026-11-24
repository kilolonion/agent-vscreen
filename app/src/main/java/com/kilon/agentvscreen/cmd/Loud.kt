package com.kilon.agentvscreen.cmd

/**
 * 2.0.0-beta3 (3.2, 3.12 & 3.13): pure decision of whether an `act` reply must be loud (full four blocks)
 * or may use the short quiet reply format.
 *
 * The reason codes and their exact order are fixed by spec 3.2 & 4.4:
 * FAILED, NO_EXPECT, NOT_MET, BEFORE, AFTER, APP_SWITCH, SNAP_BACK, NO_TEXT, FORCED.
 */
internal data class LoudInput(
    val outcome: String = "done",
    val hasBlockingNote: Boolean = false,
    val hasNoExpect: Boolean = false,
    val hasNotMet: Boolean = false,
    val beforeChanged: Boolean = false,
    val afterChanged: Boolean = false,
    val startPkg: String? = null,
    val endPkg: String? = null,
    val hasAppUntil: Boolean = false,
    val snappedBack: Boolean = false,
    val elementCount: Int = 1,
    val lastExpectOnlyVisual: Boolean = false,
    val hasPageTextDiff: Boolean = false,
    val hasToast: Boolean = false,
    val forced: Boolean = false,
)

/** Pure function: returns empty list iff the reply may be short (quiet). */
internal fun loudReasons(input: LoudInput): List<String> {
    val out = ArrayList<String>(10)
    if (input.outcome != "done" || input.hasBlockingNote) out += Loud.FAILED
    if (input.hasNoExpect) out += Loud.NO_EXPECT
    if (input.hasNotMet) out += Loud.NOT_MET
    if (input.beforeChanged) out += Loud.BEFORE
    if (input.afterChanged) out += Loud.AFTER
    if (input.startPkg != null && input.endPkg != null && input.startPkg != input.endPkg && !input.hasAppUntil) out += Loud.APP_SWITCH
    if (input.snappedBack) out += Loud.SNAP_BACK
    if (input.hasToast) out += Loud.TOAST
    if (input.elementCount == 0 || (input.lastExpectOnlyVisual && !input.hasPageTextDiff)) out += Loud.NO_TEXT
    if (input.forced) out += Loud.FORCED
    return out
}

internal object Loud {
    /**
     * 2.0 (detector): the elements NO_TEXT is about. Places only the optional detector found ("(no text icon)") are
     * pictures, not text: a canvas full of detected icons still has no text, and still gets its picture.
     */
    fun textElements(els: List<El>): List<El> = els.filter { !it.detected }

    const val FAILED = "FAILED"
    const val NO_EXPECT = "NO_EXPECT"
    const val NOT_MET = "NOT_MET"
    const val BEFORE = "BEFORE"
    const val AFTER = "AFTER"
    const val APP_SWITCH = "APP_SWITCH"
    const val SNAP_BACK = "SNAP_BACK"
    const val TOAST = "TOAST"
    const val NO_TEXT = "NO_TEXT"
    const val FORCED = "FORCED"

    /** Post-expectation quiet-observation window (ms), run only when no other loud reason holds. */
    const val AFTER_MS = 800L
    /** Minimum pictureEvents span share counted as a BEFORE/AFTER change. */
    const val CHANGE_RATIO = 0.02

    /** 3.13: expectation kinds that only observe pixel motion without explaining textual/semantic result. */
    val VISUAL_ONLY_CONDS: Set<CondKind> = setOf(CondKind.CHANGE, CondKind.STILL, CondKind.STABLE)

    /** 3.11 auto-escalation constants: >= 3 refetches in last 10 quiet replies -> force full for 10 turns. */
    const val ESCALATE_WINDOW = 10
    const val ESCALATE_THRESHOLD = 3
    const val ESCALATE_TURNS = 10
    /** 3.8: NO_EXPECT coaching hint at most once every 10 turns per session. */
    const val NO_EXPECT_HINT_GAP = 10
    /** 5.3: consecutive BEFORE full replies before showing the one-time --hold / --on hint. */
    const val BEFORE_HINT_STREAK = 3

    private const val MAX_USE_DEPTH = 3

    /**
     * 3.2 & 3.12: check whether any written action step lacks an expectation (`until` or verified `type`).
     * Returns the 1-based top-level step number of the first violating step, or null if every action is expected.
     *
     * Rules from 3.12:
     * - `repeat { ... } until <cond>` covers all action steps inside its block.
     * - `repeat N { ... }`, `if`, `try` do NOT cover inner actions; each inner action still needs its own `until`.
     * - `use <name>` counts as expected only when the segment's last action step carries `until` (or when covered
     *   by an enclosing `repeat ... until`).
     * - A `type` step with no `until` counts as expected only when verified (typed text confirmed inside box).
     */
    fun firstUnexpectedStep(
        steps: List<Step>,
        verifiedTypeLabels: Set<String> = emptySet(),
        resolveUse: ((Step) -> List<Step>?)? = null,
    ): Int? {
        fun lastActionHasUntil(list: List<Step>, depth: Int): Boolean {
            val actions = ArrayList<Step>()
            fun collect(xs: List<Step>, d: Int) {
                for (st in xs) when (st.kind) {
                    StepKind.REPEAT -> if (st.until != null) actions += st else collect(st.body, d)
                    StepKind.TRY -> collect(st.body, d)
                    StepKind.IF -> { collect(st.body, d); collect(st.alt, d) }
                    StepKind.USE -> if (d < MAX_USE_DEPTH) resolveUse?.invoke(st)?.let { collect(it, d + 1) }
                    else -> if (st.isAction) actions += st
                }
            }
            collect(list, depth)
            val last = actions.lastOrNull() ?: return true
            return last.until != null
        }

        lateinit var checkList: (List<Step>, String, Boolean, Int) -> Boolean

        fun checkStep(st: Step, label: String, covered: Boolean, depth: Int): Boolean = when (st.kind) {
            StepKind.REPEAT -> checkList(st.body, label, covered || st.until != null, depth)
            StepKind.TRY -> checkList(st.body, label, covered, depth)
            StepKind.IF -> checkList(st.body, label, covered, depth) && checkList(st.alt, label, covered, depth)
            StepKind.USE -> if (covered) {
                true
            } else if (depth >= MAX_USE_DEPTH) {
                false
            } else {
                resolveUse?.invoke(st)?.let { lastActionHasUntil(it, depth + 1) } ?: false
            }
            else -> if (!st.isAction || covered || st.until != null) {
                true
            } else {
                st.kind == StepKind.TYPE && (
                    label in verifiedTypeLabels ||
                        verifiedTypeLabels.any { it.startsWith("$label.") }
                    )
            }
        }

        checkList = { list, prefix, covered, depth ->
            list.withIndex().all { (i, st) ->
                val label = if (prefix.isEmpty()) "${i + 1}" else "$prefix.${i + 1}"
                checkStep(st, label, covered, depth)
            }
        }

        for ((i, st) in steps.withIndex()) {
            if (!checkStep(st, "${i + 1}", covered = false, depth = 0)) return i + 1
        }
        return null
    }

    /**
     * 3.13: whether the last action step's satisfied expectation (including an enclosing `repeat … until`)
     * consists solely of `change` / `still` / `stable`. `text` / `gone` / `color` / `app` and verified `type`
     * count as explaining the result (returns false).
     */
    fun lastExpectOnlyVisual(executed: List<StepOut>): Boolean {
        val done = executed.filter { it.status == "done" && it.isActionOrRepeatUntil }
        val last = done.lastOrNull() ?: return false
        if (stepExplainsResult(last)) return false
        if (last.step.trimStart().startsWith("repeat", ignoreCase = true)) {
            val prefix = "${last.n}."
            val lastInner = done.lastOrNull { it !== last && it.n.startsWith(prefix) }
            if (lastInner != null && stepExplainsResult(lastInner)) return false
        }
        return stepHasVisualOnlyExpect(last)
    }

    private fun stepExplainsResult(so: StepOut): Boolean {
        if (so.typeVerified) return true
        if (so.waitMet != true) return false
        val metKind = so.metCondKind
        if (metKind != null) return metKind !in VISUAL_ONLY_CONDS
        val waited = so.waitedFor ?: return false
        val conds = runCatching { Steps.conds(waited) }.getOrNull().orEmpty()
        return conds.isNotEmpty() && conds.any { it.kind !in VISUAL_ONLY_CONDS }
    }

    private fun stepHasVisualOnlyExpect(so: StepOut): Boolean {
        if (so.typeVerified || so.waitMet != true) return false
        val metKind = so.metCondKind
        if (metKind != null) return metKind in VISUAL_ONLY_CONDS
        val waited = so.waitedFor ?: return false
        val conds = runCatching { Steps.conds(waited) }.getOrNull().orEmpty()
        return conds.isNotEmpty() && conds.all { it.kind in VISUAL_ONLY_CONDS }
    }

    /** Whether any written step (including inside blocks / `use`) has an `app:` condition. */
    fun hasAppUntil(steps: List<Step>, resolveUse: ((Step) -> List<Step>?)? = null): Boolean {
        fun walk(list: List<Step>, depth: Int): Boolean {
            for (st in list) {
                if (st.until?.any { it.kind == CondKind.APP } == true) return true
                if (st.body.isNotEmpty() && walk(st.body, depth)) return true
                if (st.alt.isNotEmpty() && walk(st.alt, depth)) return true
                if (st.kind == StepKind.USE && depth < MAX_USE_DEPTH) {
                    val inner = resolveUse?.invoke(st)
                    if (inner != null && walk(inner, depth + 1)) return true
                }
            }
            return false
        }
        return walk(steps, 0)
    }

    // ── per-session reply tracking (3.8 NO_EXPECT hint + 3.11 auto-escalation + 5.3 BEFORE hint + act --full window) ──

    internal class SessionState {
        var lastWasQuiet: Boolean = false
        val recentQuietRefetches = ArrayDeque<Boolean>()
        var forcedFullLeft: Int = 0
        var escalateNoticePending: Boolean = false
        var refetchCount: Int = 0
        var lastNoExpectHintTurn: Int = -NO_EXPECT_HINT_GAP
        var lastActWindow: LongArray? = null
        var consecutiveBeforeFull: Int = 0
        var beforeHintShown: Boolean = false
    }

    private val sessions: HashMap<String, SessionState> = VScreenOffloadHandler.recent()

    private fun state(sid: String): SessionState = synchronized(sessions) {
        sessions.getOrPut(sid) { SessionState() }
    }

    /**
     * Called when a command arrives in [sid]. Detects refetch-after-quiet (`act --full`, `look`, `zoom`,
     * `frames`, or `act` without steps immediately following a quiet reply) and triggers 10-turn escalation
     * when >= [ESCALATE_THRESHOLD] of the last [ESCALATE_WINDOW] quiet replies were refetched.
     */
    fun onCommand(sid: String, sub: String, isRefetchCmd: Boolean): Boolean = synchronized(sessions) {
        val st = sessions[sid] ?: return false
        if (!st.lastWasQuiet) return false
        st.lastWasQuiet = false
        if (!isRefetchCmd) return false
        st.refetchCount++
        if (st.recentQuietRefetches.isNotEmpty()) {
            st.recentQuietRefetches.removeLast()
            st.recentQuietRefetches.addLast(true)
        }
        if (st.recentQuietRefetches.count { it } >= ESCALATE_THRESHOLD) {
            st.forcedFullLeft = ESCALATE_TURNS
            st.escalateNoticePending = true
            st.recentQuietRefetches.clear()
        }
        true
    }

    /** Consume one turn of auto-escalated full replies; returns (forced, shouldExplainInNotes). */
    fun consumeEscalation(sid: String): Pair<Boolean, Boolean> = synchronized(sessions) {
        val st = sessions[sid] ?: return false to false
        if (st.forcedFullLeft <= 0) return false to false
        st.forcedFullLeft--
        val explain = st.escalateNoticePending
        st.escalateNoticePending = false
        true to explain
    }

    /** Record whether this `act` reply was quiet, and its [from, to] window for a follow-up `act --full`. */
    fun recordActReply(sid: String, quiet: Boolean, fromMs: Long, toMs: Long) = synchronized(sessions) {
        val st = state(sid)
        st.lastActWindow = longArrayOf(fromMs, toMs)
        st.lastWasQuiet = quiet
        if (quiet) {
            st.recentQuietRefetches.addLast(false)
            while (st.recentQuietRefetches.size > ESCALATE_WINDOW) st.recentQuietRefetches.removeFirst()
        }
    }

    /**
     * 2.0.0-beta5 (5.3): track consecutive full replies caused by `BEFORE`.
     * Returns true once per session when reaching [BEFORE_HINT_STREAK] (3) on Android 14+ without `--hold`.
     */
    fun recordBeforeAndShouldHint(
        sid: String,
        reasons: List<String>,
        sdkInt: Int,
        usedHold: Boolean,
    ): Boolean = synchronized(sessions) {
        val st = state(sid)
        val isBeforeFull = reasons.isNotEmpty() && BEFORE in reasons
        if (!isBeforeFull) {
            st.consecutiveBeforeFull = 0
            return false
        }
        st.consecutiveBeforeFull++
        if (st.beforeHintShown || sdkInt < com.kilon.agentvscreen.core.Gate.MIN_UNFREEZE_SDK || usedHold) {
            return false
        }
        if (st.consecutiveBeforeFull >= BEFORE_HINT_STREAK) {
            st.beforeHintShown = true
            return true
        }
        false
    }

    fun lastActWindow(sid: String): LongArray? = synchronized(sessions) { sessions[sid]?.lastActWindow?.copyOf() }

    /** 3.8: allow the NO_EXPECT coaching note at most once per [NO_EXPECT_HINT_GAP] turns per session. */
    fun allowNoExpectHint(sid: String, turn: Int): Boolean = synchronized(sessions) {
        val st = state(sid)
        if (turn - st.lastNoExpectHintTurn < NO_EXPECT_HINT_GAP) return false
        st.lastNoExpectHintTurn = turn
        true
    }

    fun refetchCount(sid: String): Int = synchronized(sessions) { sessions[sid]?.refetchCount ?: 0 }

    fun clear(sid: String) { synchronized(sessions) { sessions.remove(sid) } }

    internal fun resetForTest() { synchronized(sessions) { sessions.clear() } }
}
