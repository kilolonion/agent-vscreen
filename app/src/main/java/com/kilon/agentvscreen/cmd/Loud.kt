package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.kilon.agentvscreen.core.Mask
import com.kilon.agentvscreen.core.ScreenSig

/**
 * 2.0.0-beta3 (3.2 & 3.12): pure decision of whether an `act` reply must be loud (full four blocks)
 * or may use the short quiet reply format.
 *
 * The 8 reason codes and their exact order are fixed by spec 3.2:
 * FAILED, NO_EXPECT, NOT_MET, BEFORE, AFTER, APP_SWITCH, NO_TEXT, FORCED.
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
    val elementCount: Int = Loud.MIN_ELEMENTS,
    val forced: Boolean = false,
)

/** Pure function: returns empty list iff the reply may be short (quiet). */
internal fun loudReasons(input: LoudInput): List<String> {
    val out = ArrayList<String>(8)
    if (input.outcome != "done" || input.hasBlockingNote) out += Loud.FAILED
    if (input.hasNoExpect) out += Loud.NO_EXPECT
    if (input.hasNotMet) out += Loud.NOT_MET
    if (input.beforeChanged) out += Loud.BEFORE
    if (input.afterChanged) out += Loud.AFTER
    if (input.startPkg != null && input.endPkg != null && input.startPkg != input.endPkg && !input.hasAppUntil) out += Loud.APP_SWITCH
    if (input.elementCount < Loud.MIN_ELEMENTS) out += Loud.NO_TEXT
    if (input.forced) out += Loud.FORCED
    return out
}

internal object Loud {
    const val FAILED = "FAILED"
    const val NO_EXPECT = "NO_EXPECT"
    const val NOT_MET = "NOT_MET"
    const val BEFORE = "BEFORE"
    const val AFTER = "AFTER"
    const val APP_SWITCH = "APP_SWITCH"
    const val NO_TEXT = "NO_TEXT"
    const val FORCED = "FORCED"

    /** Post-expectation quiet-observation window (ms), run only when no other loud reason holds. */
    const val AFTER_MS = 800L
    /** Minimum ScreenSig.Change.ratio (with kind != NONE) counted as a BEFORE/AFTER change. */
    const val CHANGE_RATIO = 0.02
    /** Fewer than this many elements triggers NO_TEXT (canvas / video screen). */
    const val MIN_ELEMENTS = 3

    /** 3.11 auto-escalation constants: >= 3 refetches in last 10 quiet replies -> force full for 10 turns. */
    const val ESCALATE_WINDOW = 10
    const val ESCALATE_THRESHOLD = 3
    const val ESCALATE_TURNS = 10
    /** 3.8: NO_EXPECT coaching hint at most once every 10 turns per session. */
    const val NO_EXPECT_HINT_GAP = 10

    private const val MASK_FILL_COLOR = Color.GRAY
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

    /**
     * 3.2 & 3.12: compare two frames after painting any moving-mask cells to the same solid color on both.
     * Missing frames return true ("uncertain counts as changed").
     */
    fun compareWithMask(a: Bitmap?, b: Bitmap?, mask: Mask?): Boolean {
        if (a == null || b == null || a.isRecycled || b.isRecycled) return true
        val ma = applyMask(a, mask)
        val mb = applyMask(b, mask)
        return try {
            val sigA = ScreenSig.of(ma) ?: return true
            val sigB = ScreenSig.of(mb) ?: return true
            val ch = sigA.compare(sigB)
            ch.kind != ScreenSig.Kind.NONE && ch.ratio >= CHANGE_RATIO
        } finally {
            if (ma !== a) ma.recycle()
            if (mb !== b) mb.recycle()
        }
    }

    private fun applyMask(src: Bitmap, mask: Mask?): Bitmap {
        if (mask == null) return src
        val rects = mask.rectsIn(src.width, src.height)
        if (rects.isEmpty()) return src
        val copy = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val canvas = Canvas(copy)
        val paint = Paint().apply { color = MASK_FILL_COLOR; style = Paint.Style.FILL }
        for (r in rects) canvas.drawRect(r, paint)
        return copy
    }

    // ── per-session reply tracking (3.8 NO_EXPECT hint + 3.11 auto-escalation + act --full window) ──

    internal class SessionState {
        var lastWasQuiet: Boolean = false
        val recentQuietRefetches = ArrayDeque<Boolean>()
        var forcedFullLeft: Int = 0
        var escalateNoticePending: Boolean = false
        var refetchCount: Int = 0
        var lastNoExpectHintTurn: Int = -NO_EXPECT_HINT_GAP
        var lastActWindow: LongArray? = null
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
