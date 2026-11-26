package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.tr
import org.json.JSONObject

/**
 * 2.0.0-beta5 (5.2 & 5.8 #3): one independent observation (monotonically distinct [id]) and whether a predicate held.
 * Duplicate consecutive [id]s represent repeated polls of the same cached reading and never count twice.
 */
internal data class Obs(val id: Long, val held: Boolean)

/**
 * 2.0.0-beta5 (5.2 & 5.8 #3): pure consecutive-observation persistence check shared by
 * 3.6 ([wordFactsFromReadings]) and 5.2 ([RuleLive] debounce).
 */
internal object Persistence {
    /** Longest consecutive run of `held == true` across distinct observation IDs. */
    fun maxRun(seq: List<Obs>): Int {
        var lastId = Long.MIN_VALUE
        var cur = 0
        var best = 0
        for (o in seq) {
            if (o.id == lastId) continue
            lastId = o.id
            if (o.held) {
                cur++
                if (cur > best) best = cur
            } else {
                cur = 0
            }
        }
        return best
    }

    /** Trailing consecutive run of `held == true` across distinct observation IDs. */
    fun trailingRun(seq: List<Obs>): Int {
        var lastId = Long.MIN_VALUE
        var cur = 0
        for (o in seq) {
            if (o.id == lastId) continue
            lastId = o.id
            cur = if (o.held) cur + 1 else 0
        }
        return cur
    }

    fun persisted(seq: List<Obs>, minConsecutive: Int = Report.MIN_CONSECUTIVE_READINGS): Boolean =
        maxRun(seq) >= minConsecutive

    fun trailingPersisted(seq: List<Obs>, minConsecutive: Int = Report.MIN_CONSECUTIVE_READINGS): Boolean =
        trailingRun(seq) >= minConsecutive
}

/** 2.0.0-beta5 (5.2): parsed short-range `--on "<cond> => <step>[ x<N>]"` rule specification. */
internal class RuleSpec(
    val cond: Cond,
    val step: Step,
    val times: Int,
)

/**
 * 2.0.0-beta5 (5.2, 5.6 & 5.8 #2/#3): live state of one short-range `--on` rule within a single `act`.
 * Never persisted across turns and never touches `VScreenRules`.
 */
internal class RuleLive(
    val spec: RuleSpec,
    var fired: Int = 0,
    var off: String? = null,
    val index: Int = 1,
    initialLastFalseEpochMs: Long = 0L,
) {
    /** Elapsed-realtime of the most recent firing (enforces [ActRules.COOLDOWN_MS]). */
    var lastFiredElapsedMs: Long = Long.MIN_VALUE / 4

    /** Wall-clock epoch ms of the most recent observation where [RuleSpec.cond] evaluated false (5.8 #2). */
    var lastFalseObsEpochMs: Long = initialLastFalseEpochMs

    /** Independent observation history for `text:` / `gone:` debounce via [Persistence.trailingPersisted]. */
    private val observations = ArrayDeque<Obs>()

    val active: Boolean get() = off == null

    val needsDebounce: Boolean
        get() = spec.cond.kind == CondKind.TEXT || spec.cond.kind == CondKind.GONE

    fun cooldownReady(nowElapsedMs: Long): Boolean = nowElapsedMs - lastFiredElapsedMs >= ActRules.COOLDOWN_MS

    /**
     * Record one condition evaluation and return true iff the rule should fire now.
     * For `text:` / `gone:`, requires [Report.MIN_CONSECUTIVE_READINGS] (2) consecutive independent observations
     * (distinct [obsId]s) via [Persistence.trailingPersisted]. Same cached [obsId] never counts twice.
     */
    fun recordObservation(obsId: Long?, held: Boolean, nowEpochMs: Long): Boolean {
        if (!active) return false
        if (!held) {
            if (obsId == null) {
                if (!needsDebounce) lastFalseObsEpochMs = nowEpochMs
                return false
            }
            if (observations.lastOrNull()?.id != obsId) {
                lastFalseObsEpochMs = nowEpochMs
                pushObs(Obs(obsId, false))
            }
            return false
        }
        if (needsDebounce) {
            if (obsId == null) return false
            pushObs(Obs(obsId, true))
            return Persistence.trailingPersisted(observations.toList(), Report.MIN_CONSECUTIVE_READINGS)
        }
        return true
    }

    /** Current trailing distinct-observation count of `held == true` (1 = candidate awaiting 2nd reading). */
    fun trailingTrueCount(): Int = Persistence.trailingRun(observations.toList())

    fun markFired(nowElapsedMs: Long) {
        fired++
        lastFiredElapsedMs = nowElapsedMs
        observations.clear()
        if (fired >= spec.times && off == null) {
            off = tr("触发次数已用完（${spec.times} 次）", "trigger limit reached (${spec.times}x)")
        }
    }

    fun disable(reason: String) {
        if (off == null) off = reason
        observations.clear()
    }

    private fun pushObs(o: Obs) {
        if (observations.lastOrNull()?.id == o.id) return
        observations.addLast(o)
        while (observations.size > MAX_OBS_HISTORY) observations.removeFirst()
    }

    companion object {
        private const val MAX_OBS_HISTORY = 8
    }
}

/** 2.0.0-beta5 (5.2): record of one rule firing during an `act`, rendered in text and `rules_fired` JSON. */
internal data class RuleFired(
    val rule: Int,
    val atEpochMs: Long,
    val duringStep: Int,
    val condRaw: String,
    val stepRaw: String,
    val contextKind: ContextKind,
) {
    enum class ContextKind { BEFORE_STEP, DURING_WAIT, WATCHING }

    fun line(nowMs: Long): String {
        val ago = Report.ago(atEpochMs, nowMs)
        val where = when (contextKind) {
            ContextKind.BEFORE_STEP -> tr("第 $duringStep 步开始前", "before step $duringStep")
            ContextKind.DURING_WAIT -> tr("第 $duringStep 步等待中", "during step $duringStep wait")
            ContextKind.WATCHING -> tr("观察中", "while watching")
        }
        return tr(
            "规则 $rule：看到 $condRaw → $stepRaw（$where，$ago）",
            "Rule $rule: saw $condRaw → $stepRaw ($where, $ago)",
        )
    }

    fun json(): JSONObject = JSONObject()
        .put("rule", rule)
        .put("at", atEpochMs)
        .put("during_step", duringStep)
}

internal object ActRules {
    const val MAX_RULES = 3
    const val MAX_TIMES = 3
    const val COOLDOWN_MS = 500L
    const val SETTLE_MAX_MS = 1_500L
    const val CLAIMED_AFTER_MS = 1_500L
    const val PKG_CHECK_INTERVAL_MS = 300L
    const val PRE_STEP_DEBOUNCE_MS = 50L

    fun parseAll(raws: List<String>): List<RuleSpec> {
        if (raws.size > MAX_RULES) {
            throw ActionError(
                "BAD_RULE",
                tr(
                    "一条 act 最多写 $MAX_RULES 条 --on 规则（收到 ${raws.size} 条）",
                    "An act takes at most $MAX_RULES --on rules (got ${raws.size})",
                ),
            )
        }
        return raws.map { parseRule(it) }
    }
}

/**
 * 2.0.0-beta5 (5.2 & 5.6): parse one `--on "<cond> => <step>[ x<N>]"` rule, reusing [Steps.cond] and [Steps.parse].
 * Any syntax or restriction violation throws `ActionError("BAD_RULE", ...)`.
 */
internal fun parseRule(raw: String): RuleSpec {
    fun bad(why: String): Nothing = throw ActionError(
        "BAD_RULE",
        tr(
            "规则“$raw”写法不对：$why。整条 act 没有执行。写法：--on \"<条件> => <tap|key back|scroll|wait 时长>[ x1..x3]\"",
            "Bad rule \"$raw\": $why. Nothing in this act ran. Syntax: --on \"<cond> => <tap|key back|scroll|wait duration>[ x1..x3]\"",
        ),
    )

    val trimmed = raw.trim()
    if (trimmed.isEmpty()) bad(tr("规则为空", "rule is empty"))

    // Find `=>` outside quotes.
    val arrowPositions = ArrayList<Int>()
    var q: Char? = null
    var i = 0
    while (i < trimmed.length - 1) {
        val c = trimmed[i]
        when {
            q != null -> if (c == q) q = null
            c == '"' || c == '\'' || c == '“' -> q = if (c == '“') '”' else c
            c == '=' && trimmed[i + 1] == '>' -> {
                arrowPositions += i
                i++
            }
        }
        i++
    }
    if (arrowPositions.isEmpty()) bad(tr("缺少 =>（写成 条件 => 一步）", "missing => (write <cond> => <step>)"))
    if (arrowPositions.size > 1) bad(tr("一条规则只能有一个 =>", "a rule may contain only one =>"))

    val arrow = arrowPositions[0]
    val condRaw = trimmed.substring(0, arrow).trim()
    val rhsRaw = trimmed.substring(arrow + 2).trim()
    if (condRaw.isEmpty()) bad(tr("=> 左边缺少条件", "missing condition before =>"))
    if (rhsRaw.isEmpty()) bad(tr("=> 右边缺少步骤", "missing step after =>"))

    // Reuse Steps condition parsing (single condition, no `or` / `|`).
    val conds = try {
        Steps.conds(condRaw)
    } catch (e: ActionError) {
        bad(e.message ?: e.code)
    }
    if (conds.size != 1) {
        bad(tr("规则条件只能写单个条件，不能用 or 连接", "a rule takes a single condition without or"))
    }
    val cond = conds[0]
    if (cond.kind == CondKind.STABLE || cond.kind == CondKind.STILL) {
        bad(tr("条件不能是 ${cond.kind.key}（太容易成立）", "condition cannot be ${cond.kind.key} (matches too easily)"))
    }

    // Disallow multiple steps separated by `;` / newline.
    val splitSteps = Steps.split(rhsRaw)
    if (splitSteps.size != 1) {
        bad(tr("规则右边只能写一步，不能用 ; 写多步", "rule right-hand side must be a single step (no ;)"))
    }

    // Extract optional trailing `x<N>` count (outside quotes).
    var stepRaw = rhsRaw
    var times = 1
    val countMatch = Regex("^(.*\\S)\\s+[xX]([+-]?\\d+)$").find(rhsRaw)
    if (countMatch != null) {
        // Ensure the trailing xN is not inside an open quote.
        val beforeCount = countMatch.groupValues[1]
        var openQ: Char? = null
        for (ch in beforeCount) {
            if (openQ != null) {
                if (ch == openQ) openQ = null
            } else if (ch == '"' || ch == '\'' || ch == '“') {
                openQ = if (ch == '“') '”' else ch
            }
        }
        if (openQ == null) {
            val nStr = countMatch.groupValues[2]
            val n = nStr.toIntOrNull()
            if (n == null || n !in 1..ActRules.MAX_TIMES) {
                bad(tr("触发次数只能是 x1 到 x${ActRules.MAX_TIMES}（收到 x$nStr）", "repeat count must be x1 to x${ActRules.MAX_TIMES} (got x$nStr)"))
            }
            times = n
            stepRaw = beforeCount.trim()
        }
    }

    // Reuse Steps.parse for the action step.
    val step = try {
        Steps.parse(stepRaw)
    } catch (e: ActionError) {
        bad(e.message ?: e.code)
    }
    if (step.loop != Loop.NONE) {
        bad(tr("规则里不能用闭环手势（hold / seek）", "closed-loop gestures (hold / seek) are not allowed in rules"))
    }
    if (step.until != null) {
        bad(tr("规则里的一步不能带 until", "a rule step cannot include until"))
    }
    val stepAllowed = when (step.kind) {
        StepKind.TAP -> true
        StepKind.KEY -> step.text == "back"
        StepKind.SCROLL -> true
        StepKind.SLEEP -> true
        else -> false
    }
    if (!stepAllowed) {
        bad(
            tr(
                "规则只允许 tap、key back、scroll、wait <时长>，不允许 ${step.kind.key}${if (step.kind == StepKind.KEY) " ${step.text.orEmpty()}" else ""}",
                "rules only allow tap, key back, scroll, and wait <duration>; ${step.kind.key}${if (step.kind == StepKind.KEY) " ${step.text.orEmpty()}" else ""} is not allowed",
            )
        )
    }
    return RuleSpec(cond = cond, step = step, times = times)
}
