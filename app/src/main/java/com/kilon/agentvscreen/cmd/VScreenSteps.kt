package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.trPair
import com.kilon.agentvscreen.tr

import android.graphics.Rect
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError

// [vscreen] v49: the step and condition language of `act`, parsed in one place (pure Kotlin, unit-tested).
//
// A segment is steps separated by ';' or new lines. A step is an action (tap / press / swipe / drag / scroll /
// type / key), a wait, or `use NAME` (a saved segment). Every action may end with `until <cond> [or <cond>…] [max]`.
// Conditions are facts the phone can check by itself: text appears / is gone, an area changed / stayed still,
// a point has a colour, the foreground app or activity. There is no "progress" and no meaning here.
//
// 1.3.0-beta18: one language for everything that runs steps (act, saved segments and taught lessons, before/after):
//  - words may hold `*` (any characters; the element's whole text must then match) and end in `@N` (the N-th match,
//    top to bottom, left to right; @-1 = the last). `tap *{1}*@1` = the first item that holds the first value.
//  - blocks in braces: `try <step>` / `try { … }` (not found or not as expected: skip it and go on);
//    `if <cond> [or …] [wait] { … } [else { … } | else <step>]`; `repeat N { … }` / `repeat [N] { … } until <cond> [wait]`.

/** Where a tap / press / scroll goes: a point, an element of the last list (#N), or words on the screen. */
internal sealed class StepTarget {
    data class At(val x: Int, val y: Int) : StepTarget()
    data class Ref(val n: Int) : StepTarget()
    /** [nth]: 0 = the one match; N > 0 the N-th in reading order; -1 the last. */
    /** [dx]/[dy]: 1.4.0-beta5, a point beside the words (an icon next to its label), in pixels from their centre. */
    data class Words(val text: String, val nth: Int = 0, val dx: Int = 0, val dy: Int = 0) : StepTarget()

    fun describe(): String = when (this) {
        is At -> "$x,$y"
        is Ref -> "#$n"
        is Words -> "“$text”" + (if (nth != 0) "@$nth" else "") + (if (dx != 0 || dy != 0) " %+d,%+d".format(dx, dy) else "")
    }
}

/** One fact to wait for or stop at. [area] null = the whole screen. */
/** 1.4.0-beta2: the kinds of condition and step are types; the parser is the only place that reads them from words. */
internal enum class CondKind(val key: String, private val zh: String, private val en: String) {
    TEXT("text", "text:字[ in x1,y1,x2,y2]", "text:word[ in x1,y1,x2,y2]"), GONE("gone", "gone:字", "gone:word"),
    CHANGE("change", "change[:x1,y1,x2,y2]", "change[:x1,y1,x2,y2]"), STILL("still", "still[:x1,y1,x2,y2]", "still[:x1,y1,x2,y2]"),
    STABLE("stable", "stable", "stable"), COLOR("color", "color:x,y=#RRGGBB[~容差]", "color:x,y=#RRGGBB[~tolerance]"), APP("app", "app:名字", "app:name");
    /** How it is written (help, error messages): generated, so the help cannot miss a kind. */
    val form: String get() = trPair(zh, en)
    override fun toString() = key
}

internal enum class StepKind(val key: String, private val zh: String, private val en: String) {
    TAP("tap", "tap 目标", "tap <target>"), PRESS("press", "press 目标", "press <target>"),
    SWIPE("swipe", "swipe x1 y1 x2 y2", "swipe x1 y1 x2 y2"), DRAG("drag", "drag x1 y1 x2 y2 …", "drag x1 y1 x2 y2 …"),
    SCROLL("scroll", "scroll down", "scroll down"), TYPE("type", "type 文字", "type <text>"), KEY("key", "key back", "key back"),
    WAIT("wait", "wait 条件", "wait <condition>"), SLEEP("sleep", "wait 2s", "wait 2s"), USE("use", "use 段名", "use <segment>"),
    TRY("try", "try 一步", "try <step>"), IF("if", "if 条件 { … } else { … }", "if <cond> { … } else { … }"),
    REPEAT("repeat", "repeat 3 { … } | repeat { … } until 条件", "repeat 3 { … } | repeat { … } until <cond>"),
    /** 1.4.0-beta11 (rainbow): stop here and hand the screen to the agent to check (or put right) before going on. */
    CHECK("check", "check \"要看什么\" [unless 条件]", "check \"what to look at\" [unless <cond>]");
    /** How it is written (help): generated, so the help cannot miss a kind. */
    val form: String get() = trPair(zh, en)
    override fun toString() = key
}

internal data class Cond(val raw: String, val kind: CondKind, val text: String? = null, val area: Rect? = null,
                         val x: Int = 0, val y: Int = 0, val color: Int = 0, val tol: Int = 40)

/** 2.0.0-beta4 (4.1-4.2): closed-loop gesture mode on `press` / `drag`. */
internal enum class Loop { NONE, HOLD, SEEK }

/** One step of a segment. */
internal data class Step(
    val raw: String,
    val kind: StepKind,
    val target: StepTarget? = null,
    val points: List<Pair<Int, Int>> = emptyList(),
    val text: String? = null,
    val ms: Long? = null,
    val append: Boolean = false,
    /** until … (or, for wait, what it waits for); null = the default wait. */
    val until: List<Cond>? = null,
    val untilMs: Long? = null,
    /** try / if / repeat: the steps inside the braces; [alt] = if's else; [times] = repeat's count (the most). */
    val body: List<Step> = emptyList(),
    val alt: List<Step> = emptyList(),
    val times: Int = 0,
    /** 2.0.0-beta4 (4.2): closed-loop modifier (`hold` / `seek`) on `press` / `drag`. */
    val loop: Loop = Loop.NONE,
) {
    val isAction: Boolean get() = kind !in BLOCKS && kind !in setOf(StepKind.WAIT, StepKind.SLEEP, StepKind.USE, StepKind.CHECK)
    companion object { val BLOCKS = setOf(StepKind.TRY, StepKind.IF, StepKind.REPEAT) }
}

/**
 * 1.3.0-beta18: words to find on the screen. Without `*` as before (the same words, else words that hold them); with
 * `*` a pattern over the element's whole text (spaces and case ignored), so `*蓝牙*` holds 蓝牙, `共*条` starts and ends so.
 */
internal class TextPat(raw: String) {
    val key: String = com.kilon.agentvscreen.core.VScreenOcr.norm(raw)
    private val glob: Regex? = if ('*' in key) Regex(key.split('*').joinToString(".*") { if (it.isEmpty()) "" else Regex.escape(it) }) else null
    val wild: Boolean get() = glob != null
    /** Counts as these words (for conditions, and a target when nothing is exactly so). */
    fun hit(text: String): Boolean { val n = com.kilon.agentvscreen.core.VScreenOcr.norm(text); return glob?.matches(n) ?: containsNumberSafe(n, key) }
    /** Exactly these words (a pattern: matches the whole text). */
    fun exact(text: String): Boolean { val n = com.kilon.agentvscreen.core.VScreenOcr.norm(text); return glob?.matches(n) ?: (n == key) }
}

/**
 * 2.0.0-beta11 (test G2: `seek until text:75%` stopped at 275%): words inside longer text, except that a number is not
 * found inside a longer number: where [key] begins or ends with a digit, the text next to it must not be a digit there
 * (75% is not in 275%, 1 is not in 10; 第1页 is still in 第1页共5页).
 */
internal fun containsNumberSafe(text: String, key: String): Boolean {
    if (key.isEmpty()) return true
    var i = text.indexOf(key)
    while (i >= 0) {
        val beforeOk = !key.first().isDigit() || i == 0 || !text[i - 1].isDigit()
        val end = i + key.length
        val afterOk = !key.last().isDigit() || end >= text.length || !text[end].isDigit()
        if (beforeOk && afterOk) return true
        i = text.indexOf(key, i + 1)
    }
    return false
}

internal object Steps {
    const val UNTIL_DEFAULT_MS = 5_000L
    const val WAIT_DEFAULT_MS = 10_000L
    const val MAX_WAIT_MS = 120_000L
    const val MAX_STEPS = 40
    /** 2.0.0-beta4 (4.1): closed-loop gesture wait defaults and caps. */
    const val HOLD_DEFAULT_MS = 5_000L
    const val HOLD_MAX_MS = 10_000L
    const val SEEK_DEFAULT_MS = 15_000L
    const val SEEK_MAX_MS = 30_000L

    /** 1.2: key names come from the one key table (VScreenShell.KEYS); a number is a keycode. */
    /** 1.4.0-beta13: `type "…" slow`: the gap between characters by default, and the most characters. */
    const val SLOW_TYPE_MS = 120L
    const val SLOW_TYPE_MAX = 200
    private fun isKey(k: String): Boolean = com.kilon.agentvscreen.core.VScreenShell.keyProblem(k) == null
    private val KEY_NAMES: String get() = com.kilon.agentvscreen.core.VScreenShell.KEYS.keys.joinToString(" ")

    /**
     * 1.4.0-beta11: whether `tap <s>` reads back as exactly the words [s] (not coordinates, an element number, an
     * offset, a duration or an @N). Recorders (teach) name places only this way, so what they write is what replays.
     */
    fun readsAsWords(s: String): Boolean = runCatching {
        val t = s.trim()
        val q = if (t.any { it.isWhitespace() }) "\"$t\"" else t
        parse("tap $q").target == StepTarget.Words(t)
    }.getOrDefault(false)

    /** Split a segment into step strings: ';' / '；' / new lines, not inside quotes. */
    fun split(body: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var q: Char? = null
        var depth = 0
        for (c in body) {
            when {
                q != null -> { cur.append(c); if (c == q) q = null }
                c == '"' || c == '\'' || c == '“' -> { cur.append(c); q = if (c == '“') '”' else c }
                c == '{' -> { depth++; cur.append(c) }
                c == '}' -> { depth = maxOf(0, depth - 1); cur.append(c) }
                depth == 0 && (c == ';' || c == '；' || c == '\n') -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 1.3.0-beta18: the steps as lines to show a person, each with how deep in braces it is ("if text:X {", its steps
     * one level in, "} else {", …, "}").
     */
    fun outline(body: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val cur = StringBuilder()
        var depth = 0
        var q: Char? = null
        fun flush() { val t = cur.toString().trim(); if (t.isNotEmpty()) out += depth to t; cur.setLength(0) }
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                q != null -> { cur.append(c); if (c == q) q = null }
                c == '"' || c == '\'' || c == '“' -> { cur.append(c); q = if (c == '“') '”' else c }
                c == '{' && Regex("^\\{\\d+\\}").containsMatchIn(body.substring(i)) -> { val e = body.indexOf('}', i); cur.append(body, i, e + 1); i = e }
                c == '{' -> { val t = cur.toString().trimEnd(); cur.setLength(0); cur.append(t).append(" {"); flush(); depth++ }
                c == '}' -> { flush(); depth = maxOf(0, depth - 1); cur.append('}') }
                c == ';' || c == '；' || c == '\n' -> flush()
                else -> cur.append(c)
            }
            i++
        }
        flush()
        return out
    }

    /** Tokens of one step: whitespace separated, quotes keep spaces (and are removed). */
    fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var q: Char? = null
        var had = false
        for (c in s) {
            when {
                q != null -> if (c == q) q = null else cur.append(c)
                c == '"' || c == '\'' || c == '“' -> { q = if (c == '“') '”' else c; had = true }
                c.isWhitespace() -> { if (cur.isNotEmpty() || had) out += cur.toString(); cur.setLength(0); had = false }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty() || had) out += cur.toString()
        return out
    }

    fun duration(t: String?): Long? = t?.let { VScreenOptions.durationMs(it.trim()) }

    /** "540,1200" / "540" "1200" / "540，1200" → numbers. */
    private fun numbers(toks: List<String>): List<Int>? {
        val out = ArrayList<Int>()
        for (t in toks) for (p in t.replace('，', ',').split(',').filter { it.isNotEmpty() }) out += p.toDoubleOrNull()?.toInt() ?: return null
        return out
    }

    fun parseAll(body: String): List<Step> {
        val parts = split(body)
        if (parts.size > MAX_STEPS) throw ActionError("INVALID_STEP", tr(S.vscreen_steps_a_stretch_has_at_most, MAX_STEPS, parts.size))
        val steps = parts.map { parse(it) }
        val all = count(steps)
        if (all > MAX_STEPS * 2) throw ActionError("INVALID_STEP", tr(S.vscreen_steps_a_stretch_has_at_most_2, MAX_STEPS * 2, all))
        return steps
    }

    /** Steps written, counting those inside braces. */
    fun count(list: List<Step>): Int = list.sumOf { 1 + count(it.body) + count(it.alt) }

    const val CHECK_DEFAULT_MS = 1_500L
    const val REPEAT_DEFAULT = 10
    const val REPEAT_MAX = 50

    /** Where the block's '{' is in [s] (outside quotes; a value blank {1} is not a brace), or -1. */
    private fun openBrace(s: String): Int {
        var q: Char? = null
        for ((i, c) in s.withIndex()) when {
            q != null -> if (c == q) q = null
            c == '"' || c == '\'' || c == '“' -> q = if (c == '“') '”' else c
            c == '{' && !Regex("^\\{\\d+\\}").containsMatchIn(s.substring(i)) -> return i
        }
        return -1
    }

    /** The '}' that closes the '{' at [open]. */
    private fun closeBrace(raw: String, s: String, open: Int): Int {
        var q: Char? = null
        var depth = 0
        for (i in open until s.length) { val c = s[i]; when {
            q != null -> if (c == q) q = null
            c == '"' || c == '\'' || c == '“' -> q = if (c == '“') '”' else c
            c == '{' -> depth++
            c == '}' -> { depth--; if (depth == 0) return i }
        } }
        bad(raw, tr(S.vscreen_steps_a_has_no_matching))
    }

    /** The steps in the braces at [open], and where the block ends. */
    private fun block(raw: String, s: String, open: Int): Pair<List<Step>, Int> {
        val close = closeBrace(raw, s, open)
        val inner = split(s.substring(open + 1, close)).map { parse(it) }
        if (inner.isEmpty()) bad(raw, tr(S.vscreen_steps_nothing_inside))
        return inner to close
    }

    /** try / if / repeat (1.3.0-beta18). */
    private val REPEAT_ONE = Regex("^(\\d+)\\s*(?:次|x|times)?\\s*[:：]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)

    private fun blockStep(raw: String, verb: String, verbTok: String): Step {
        val rest = rawAfterVerb(raw, verbTok)
        when (verb) {
            "try", "试" -> {
                if (rest.isEmpty()) bad(raw, tr(S.vscreen_steps_write_a_step_or_steps))
                if (!rest.startsWith("{")) return Step(raw, StepKind.TRY, body = listOf(parse(rest)))
                val (body, close) = block(raw, rest, 0)
                if (rest.substring(close + 1).isNotBlank()) bad(raw, tr(S.vscreen_steps_nothing_may_follow_try_separate))
                return Step(raw, StepKind.TRY, body = body)
            }
            "if", "如果" -> {
                val open = openBrace(rest)
                if (open < 0) bad(raw, tr(S.vscreen_steps_write_if_condition_steps_else))
                val (conds, max) = condsWithMax(tokens(rest.substring(0, open)))
                val (body, close) = block(raw, rest, open)
                val tail = rest.substring(close + 1).trim()
                val alt = when {
                    tail.isEmpty() -> emptyList()
                    tail.lowercase().startsWith("else") || tail.startsWith("否则") -> {
                        val after = tail.drop(if (tail.startsWith("否则")) 2 else 4).trim()
                        if (after.isEmpty()) bad(raw, tr(S.vscreen_steps_write_steps_or_a_step))
                        if (after.startsWith("{")) block(raw, after, 0).also { (_, c) -> if (after.substring(c + 1).isNotBlank()) bad(raw, tr(S.vscreen_steps_nothing_may_follow_else_to)) }.first
                        else listOf(parse(after))
                    }
                    else -> bad(raw, tr(S.vscreen_steps_only_else_may_follow_to))
                }
                return Step(raw, StepKind.IF, until = conds, untilMs = max ?: CHECK_DEFAULT_MS, body = body, alt = alt)
            }
            else -> {
                val open = openBrace(rest)
                // 2.0.0-beta12 (test: the documented `repeat 40: swipe …` was refused): one step after a colon.
                if (open < 0) REPEAT_ONE.matchEntire(rest)?.let { m ->
                    val n = m.groupValues[1].toInt()
                    if (n !in 1..REPEAT_MAX) bad(raw, tr(S.vscreen_steps_repeat_takes_1_to_times, REPEAT_MAX))
                    return Step(raw, StepKind.REPEAT, untilMs = CHECK_DEFAULT_MS, body = listOf(parse(m.groupValues[2].trim())), times = n)
                }
                if (open < 0) bad(raw, tr(S.vscreen_steps_write_repeat_times_steps_repeat))
                val head = rest.substring(0, open).trim()
                val n = if (head.isEmpty()) null else (head.removeSuffix("次").removeSuffix("x").removeSuffix("times").trim().toIntOrNull() ?: bad(raw, tr(S.vscreen_steps_write_a_number_of_times)))
                val (body, close) = block(raw, rest, open)
                val tail = tokens(rest.substring(close + 1))
                var until: List<Cond>? = null; var max: Long? = null
                if (tail.isNotEmpty()) {
                    if (tail[0].lowercase() != "until" && tail[0] != "直到") bad(raw, tr(S.vscreen_steps_only_until_condition_may_follow))
                    condsWithMax(tail.drop(1)).let { until = it.first; max = it.second }
                }
                if (n == null && until == null) bad(raw, tr(S.vscreen_steps_repeat_needs_a_number_of, REPEAT_DEFAULT))
                if (n != null && n !in 1..REPEAT_MAX) bad(raw, tr(S.vscreen_steps_repeat_takes_1_to_times, REPEAT_MAX))
                return Step(raw, StepKind.REPEAT, until = until, untilMs = max ?: CHECK_DEFAULT_MS, body = body, times = n ?: REPEAT_DEFAULT)
            }
        }
    }

    /** One step. Throws INVALID_STEP with what was wrong and how to write it. */
    /**
     * 1.4.0-beta5 (rainbow: 坐标按屏幕比例保存): coordinates may be written as shares of the screen, `50% 80%`, so a
     * lesson recorded on one screen size lands on the same place on another. They become pixels of the screen the
     * step runs on ([frame]; without a screen a 1000×1000 stand-in only checks the writing). A share stands for a
     * coordinate only in a pair (x then y), so one `50%` alone stays words (a battery level); typing is left as is.
     */
    @Volatile var frame: () -> Pair<Int, Int>? = { null }

    private val PCT_RUN = Regex("(?<![\\w.%])[+-]?\\d+(?:\\.\\d+)?%(?:[\\s,，]+[+-]?\\d+(?:\\.\\d+)?%)+(?![\\w%])")

    fun resolvePct(raw: String): String {
        if ('%' !in raw) return raw
        val verb = raw.trimStart().substringBefore(' ').lowercase()
        if (verb == "type" || verb == "type+" || verb == "输入") return raw
        val (w, h) = frame() ?: (1000 to 1000)
        val out = StringBuilder(); var q: Char? = null; var seg = StringBuilder()
        fun flush() {
            out.append(PCT_RUN.replace(seg) { m ->
                val vals = Regex("[+-]?\\d+(?:\\.\\d+)?%").findAll(m.value).toList()
                if (vals.size % 2 != 0) return@replace m.value
                var k = 0
                Regex("([+-]?)(\\d+(?:\\.\\d+)?)%").replace(m.value) { v ->
                    val share = v.groupValues[2].toDouble() / 100.0
                    val px = Math.round(share * (if (k++ % 2 == 0) w else h)).toInt()
                    v.groupValues[1] + (if (v.groupValues[1].isEmpty()) px.coerceAtLeast(0) else px)
                }
            }); seg = StringBuilder()
        }
        for (c in raw) when {
            q != null -> { out.append(c); if (c == q) q = null }
            c == '"' || c == '\'' || c == '“' -> { flush(); out.append(c); q = if (c == '“') '”' else c }
            else -> seg.append(c)
        }
        flush()
        return out.toString()
    }

    /** Pixels as a share of the screen, as [resolvePct] reads it back ("50%", "12.5%"). */
    fun pct(px: Int, of: Int): String {
        val v = Math.round(px * 1000.0 / maxOf(1, of)) / 10.0
        return (if (v == Math.floor(v)) v.toLong().toString() else v.toString()) + "%"
    }

    fun parse(raw0: String): Step {
        val raw = resolvePct(raw0)
        val toks0 = tokens(raw)
        if (toks0.isEmpty()) bad(raw, tr(S.vscreen_steps_empty_step))
        val verb = toks0[0].lowercase()
        if (verb in setOf("try", "试", "if", "如果", "repeat", "重复")) return blockStep(raw.trim(), verb, toks0[0])
        if (verb == "check" || verb == "检查") {
            // `check "what to look at" [unless <cond>]`: the words are what the agent should look at; unless = pass by
            // itself when that is on the screen (checked for 1.5 s like if), so a run that went well is not stopped.
            val ui = toks0.indexOfFirst { it.lowercase() == "unless" || it == "除非" }
            val what = (if (ui > 0) toks0.subList(1, ui) else toks0.drop(1)).joinToString(" ").trim()
            if (what.isEmpty()) bad(raw, tr(S.vscreen_steps_write_what_the_agent_should))
            if (ui > 0) {
                if (ui == toks0.lastIndex) bad(raw, tr(S.vscreen_steps_unless_needs_a_condition) + CONDS_HELP)
                val (c, max) = condsWithMax(toks0.drop(ui + 1))
                return Step(raw, StepKind.CHECK, text = what, until = c, untilMs = max ?: CHECK_DEFAULT_MS)
            }
            return Step(raw, StepKind.CHECK, text = what)
        }
        if (verb == "else" || verb == "否则") bad(raw, tr(S.vscreen_steps_else_goes_right_after_if))
        // until … at the end (for type only when what follows really is a condition).
        val ui = toks0.indexOfLast { it.lowercase() == "until" }
        var until: List<Cond>? = null
        var untilMs: Long? = null
        var toks = toks0
        if (ui > 0 && verb != "wait") {
            val parsed = runCatching { condsWithMax(toks0.drop(ui + 1)) }
            if (parsed.isSuccess) { until = parsed.getOrThrow().first; untilMs = parsed.getOrThrow().second; toks = toks0.take(ui) }
            else if (verb != "type" && verb != "type+") throw parsed.exceptionOrNull()!!
        }
        val args = toks.drop(1)
        if (verb == "until") bad(raw, tr(S.vscreen_steps_until_goes_after_an_action))
        return when (verb) {
            "tap", "click", "点" -> Step(raw, StepKind.TAP, target = target(raw, args, allowDuration = false).first, until = until, untilMs = untilMs)
            "press", "longpress", "hold", "长按" -> {
                val isHoldLoop = args.size >= 2 && args.last().lowercase() == "hold"
                if (isHoldLoop) {
                    if (until == null) throw ActionError("GESTURE_NEEDS_UNTIL", tr(S.vscreen_steps_press_hold_requires_until_condition))
                    validateLoopConds(until)
                    val (t, _) = target(raw, args.dropLast(1), allowDuration = false)
                    val maxWait = capMs(raw, untilMs ?: HOLD_DEFAULT_MS, HOLD_MAX_MS, withValue = false).coerceAtLeast(100L)
                    Step(raw, StepKind.PRESS, target = t, until = until, untilMs = maxWait, loop = Loop.HOLD)
                } else {
                    val (t, ms) = target(raw, args, allowDuration = true)
                    Step(raw, StepKind.PRESS, target = t, ms = (ms ?: 800L).coerceIn(100L, 10_000L), until = until, untilMs = untilMs)
                }
            }
            "swipe", "drag", "滑", "拖" -> {
                val isDrag = verb == "drag" || verb == "拖"
                val tail = args.lastOrNull()?.lowercase()
                val loop = if (isDrag && tail == "hold") Loop.HOLD else if (isDrag && tail == "seek") Loop.SEEK else Loop.NONE
                if (loop != Loop.NONE) {
                    if (until == null) throw ActionError("GESTURE_NEEDS_UNTIL", tr(S.vscreen_steps_drag_requires_until_condition, tail))
                    val rest = args.dropLast(1)
                    val ms = if (loop == Loop.HOLD) {
                        rest.lastOrNull()?.takeIf { it.any { c -> c.isLetter() } }?.let { duration(it) ?: bad(raw, tr(S.vscreen_steps_write_the_duration_as_300ms)) }
                    } else null
                    val numArgs = if (ms != null) rest.dropLast(1) else rest
                    val nums = numbers(numArgs) ?: bad(raw, run { val more = if (loop == Loop.HOLD) " [x3 y3 …] [时长]" else ""; tr(S.vscreen_steps_coordinates_are_numbers_x1_y1, verb, more, tail) })
                    if (nums.size < 4 || nums.size % 2 != 0) bad(raw, run { val more = if (loop == Loop.HOLD) " [x3 y3 …]" else ""; tr(S.vscreen_steps_needs_coordinate_pairs_x1_y1, verb, more, nums.size) })
                    val pts = nums.chunked(2).map { it[0] to it[1] }
                    if (loop == Loop.SEEK && pts.size != 2) throw ActionError("INVALID_ARGS", tr(S.vscreen_steps_drag_seek_supports_only_2, pts.size))
                    validateLoopConds(until)
                    val defaultWait = if (loop == Loop.SEEK) SEEK_DEFAULT_MS else HOLD_DEFAULT_MS
                    val maxCap = if (loop == Loop.SEEK) SEEK_MAX_MS else HOLD_MAX_MS
                    val maxWait = capMs(raw, untilMs ?: defaultWait, maxCap, withValue = false).coerceAtLeast(100L)
                    Step(raw, StepKind.DRAG, points = pts, ms = if (loop == Loop.HOLD) (ms ?: 600L).coerceIn(50L, 10_000L) else null, until = until, untilMs = maxWait, loop = loop)
                } else {
                    var ms = args.lastOrNull()?.takeIf { it.any { c -> c.isLetter() } }?.let { duration(it) ?: bad(raw, tr(S.vscreen_steps_write_the_duration_as_300ms)) }
                    var nums = numbers(if (ms != null) args.dropLast(1) else args) ?: bad(raw, run { val more = if (isDrag) " [x3 y3 …]" else ""; tr(S.vscreen_steps_coordinates_are_numbers_x1_y1_2, verb, more) })
                    // 2.0.0-beta15 (test: `swipe 1100 1830 1100 1660 450` was refused, the 450 read as a fifth coordinate): an odd
                    // last bare number after at least two points is the duration in ms, as in `adb shell input swipe`.
                    if (ms == null && nums.size >= 5 && nums.size % 2 == 1) { ms = nums.last().toLong(); nums = nums.dropLast(1) }
                    if (nums.size < 4 || nums.size % 2 != 0) bad(raw, run { val more = if (isDrag) " [x3 y3 …]" else ""; tr(S.vscreen_steps_needs_coordinate_pairs_x1_y1, verb, more, nums.size) })
                    if (!isDrag && nums.size != 4) bad(raw, tr(S.vscreen_steps_swipe_has_only_a_start))
                    val pts = nums.chunked(2).map { it[0] to it[1] }
                    Step(raw, if (isDrag) StepKind.DRAG else StepKind.SWIPE, points = pts, ms = (ms ?: if (isDrag) 600L else 300L).coerceIn(50L, 10_000L), until = until, untilMs = untilMs)
                }
            }
            "scroll", "滚" -> {
                val dir = args.firstOrNull()?.lowercase()?.let { d -> mapOf("上" to "up", "下" to "down", "左" to "left", "右" to "right")[d] ?: d }
                if (dir !in setOf("up", "down", "left", "right")) bad(raw, "scroll up|down|left|right [#N | x y]")
                val t = if (args.size > 1) target(raw, args.drop(1), allowDuration = false).first.also {
                    if (it is StepTarget.Words) bad(raw, tr(S.vscreen_steps_write_scroll_s_position_as))
                } else null
                Step(raw, StepKind.SCROLL, target = t, text = dir, until = until, untilMs = untilMs)
            }
            "type", "type+", "输入" -> {
                var bodyRaw = rawAfterVerb(raw, toks0[0]).let { r ->
                    if (until != null) r.substring(0, r.lastIndexOf("until", ignoreCase = true).coerceAtLeast(0)).trimEnd() else r
                }
                // 1.4.0-beta13 (rainbow): `type "文字" slow [200ms]` types it one character at a time, so input
                // listeners (suggestions, search as you type) react as to a person typing. Only after quoted text, so
                // words ending in "slow" are never taken for it. Without slow: all at once, as before.
                var slowMs: Long? = null
                Regex("""^(["'“「].*["'”」])\s+slow(?:\s+(\d+)\s*ms)?$""").find(bodyRaw)?.let { m ->
                    bodyRaw = m.groupValues[1]; slowMs = (m.groupValues[2].toLongOrNull() ?: SLOW_TYPE_MS).coerceIn(30L, 1500L)
                }
                val body = unquote(bodyRaw)
                if (body.isEmpty()) bad(raw, tr(S.vscreen_steps_write_the_text_to_type))
                if (slowMs != null && body.length > SLOW_TYPE_MAX) bad(raw, tr(S.vscreen_steps_typing_slowly_takes_at_most, SLOW_TYPE_MAX))
                Step(raw, StepKind.TYPE, text = body, append = verb == "type+", until = until, untilMs = untilMs, ms = slowMs)
            }
            "key", "press-key", "按键" -> {
                val k = args.firstOrNull()?.lowercase() ?: bad(raw, tr(S.vscreen_steps_write_a_key_name_after, KEY_NAMES))
                if (k == "home" || k == "app_switch" || k == "recent" || k == "recents") bad(raw, tr(S.vscreen_steps_there_is_no_key_the, k))
                // 1.4.0-beta13: shortcuts too: ctrl / shift / alt + a key (`key ctrl+a`, `key shift+tab`).
                if (!isKey(k)) bad(raw, com.kilon.agentvscreen.core.VScreenShell.keyProblem(k)?.takeIf { it.isNotEmpty() }
                    ?: tr(S.vscreen_steps_unknown_key_available_letters_digits, k, KEY_NAMES))
                Step(raw, StepKind.KEY, text = k, until = until, untilMs = untilMs)
            }
            "back" -> Step(raw, StepKind.KEY, text = "back", until = until, untilMs = untilMs)
            "enter" -> Step(raw, StepKind.KEY, text = "enter", until = until, untilMs = untilMs)
            "wait", "等" -> {
                if (args.isEmpty()) bad(raw, tr(S.vscreen_steps_wait_condition_max_duration_or))
                val only = duration(args[0])
                if (args.size == 1 && only != null) Step(raw, StepKind.SLEEP, ms = capMs(raw, only, MAX_WAIT_MS, withValue = false))
                else {
                    val (c, max) = condsWithMax(args)
                    Step(raw, StepKind.WAIT, until = c, untilMs = (max ?: WAIT_DEFAULT_MS).coerceAtLeast(100L))
                }
            }
            "use", "用" -> Step(raw, StepKind.USE, text = args.joinToString(" ").trim().ifEmpty { bad(raw, tr(S.vscreen_steps_write_a_saved_segment_name)) })
            else -> bad(raw, tr(S.vscreen_steps_unknown_step, verb))
        }
    }

    private fun rawAfterVerb(raw: String, verbTok: String): String = raw.trim().let { r ->
        val i = r.indexOf(verbTok)
        if (i < 0) r else r.substring(i + verbTok.length).trim()
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\'') || (t.first() == '“' && t.last() == '”'))) return t.substring(1, t.length - 1)
        return t
    }

    /** A target: x y | x,y | #N | words; optionally a duration last. */
    private fun target(raw: String, args: List<String>, allowDuration: Boolean): Pair<StepTarget, Long?> {
        if (args.isEmpty()) bad(raw, tr(S.vscreen_steps_say_where_x_y_number))
        var a = args
        var ms: Long? = null
        if (allowDuration && a.size >= 2) a.last().takeIf { t -> t.any { it.isLetter() } }?.let { t -> duration(t)?.let { ms = it; a = a.dropLast(1) } }
        // v0.5: a bare number after the target is the duration in ms too (press 600 2400 1200 / press #3 1200), as the help says.
        if (allowDuration && ms == null && a.size >= 2 && a.last().all { it.isDigit() } &&
            (a[0].startsWith("#") && a.size == 2 || numbers(a)?.size == 3)) { ms = a.last().toLong(); a = a.dropLast(1) }
        // 1.4.0-beta5/6: words then a signed offset (read before numbers: `8 +50,+100` is the word 8 and an offset), `设置 +120,-8`: the point that far from the words (an icon or a switch
        // beside its label). Both signs are required, so a word that is a number is never read as an offset.
        var dx = 0; var dy = 0
        // 2.0.0-beta12 (test: the documented `tap 热门 +186,0` was NOT_FOUND): the first number's sign is enough.
        val off = Regex("^([+-]\\d+)[,，]([+-]?\\d+)$")
        if (a.size >= 2) {
            val one = off.find(a.last())
            val two = if (a.size >= 3 && Regex("^[+-]\\d+$").matches(a[a.size - 2]) && Regex("^[+-]\\d+$").matches(a.last())) a[a.size - 2] to a.last() else null
            when {
                one != null -> { dx = one.groupValues[1].toInt(); dy = one.groupValues[2].toInt(); a = a.dropLast(1) }
                two != null -> { dx = two.first.toInt(); dy = two.second.toInt(); a = a.dropLast(2) }
            }
        }
        val first = a[0]
        if (first.startsWith("#") && a.size == 1) {
            val n = first.drop(1).toIntOrNull() ?: bad(raw, tr(S.vscreen_steps_write_an_element_number_after))
            return StepTarget.Ref(n) to ms
        }
        val nums = numbers(a)
        if (nums != null) {
            // 1.1: one number alone is a word on the screen (a calculator's "1", a page number), not half a coordinate.
            if (nums.size == 1) return StepTarget.Words(a[0], 0, dx, dy) to ms
            if (nums.size != 2) bad(raw, tr(S.vscreen_steps_coordinates_are_two_numbers_x))
            return StepTarget.At(nums[0], nums[1]) to ms
        }
        // 1.3.0-beta18: words@N = the N-th place they are on the screen (top to bottom, left to right; @-1 the last).
        val w = a.joinToString(" ").trim()
        Regex("^(.*\\S)@(-?\\d{1,2})$").find(w)?.let { m -> val k = m.groupValues[2].toInt(); if (k != 0) return StepTarget.Words(m.groupValues[1], k, dx, dy) to ms }
        return StepTarget.Words(w, 0, dx, dy) to ms
    }

    /** Conditions joined by or, then an optional duration last (the longest wait). */
    fun condsWithMax(toks: List<String>): Pair<List<Cond>, Long?> {
        if (toks.isEmpty()) throw ActionError("INVALID_STEP", tr(S.vscreen_steps_until_wait_needs_a_condition) + CONDS_HELP)
        var t = toks
        var max: Long? = null
        val last = t.last()
        val hasUnit = last.any { it.isLetter() }
        val hasMaxKw = t.size >= 3 && t[t.size - 2].lowercase() in setOf("max", "最长", "within")
        if (t.size >= 2 && !last.contains(':') && last.any { it.isDigit() } && (hasUnit || hasMaxKw) && duration(last) != null) { max = duration(last); t = t.dropLast(1) }
        if (max != null && t.size >= 2 && t.last().lowercase() in setOf("max", "最长", "within")) t = t.dropLast(1)
        val conds = t.joinToString(" ").split(Regex("\\s+or\\s+|\\s*\\|\\s*|\\s+或\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.map { cond(it) }
        if (conds.isEmpty()) throw ActionError("INVALID_STEP", tr(S.vscreen_steps_until_wait_needs_a_condition) + CONDS_HELP)
        return conds to max?.let { capMs(toks.joinToString(" "), it, MAX_WAIT_MS, withValue = false).coerceAtLeast(100L) }
    }

    val CONDS_HELP: String get() = CondKind.entries.joinToString(" | ") { it.form } + tr(S.vscreen_steps_join_several_with_or)
    val STEPS_HELP: String get() = StepKind.entries.joinToString(" | ") { it.form }

    private val IN_AREA = Regex("^(.+?)\\s+in\\s+(-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+)$")

    /** One condition. */
    fun cond(s0: String): Cond {
        val s = s0.trim()
        val kind = s.substringBefore(':').lowercase().trim()
        val v = if (':' in s) s.substringAfter(':').trim() else ""
        fun area(): Rect? = if (v.isEmpty()) null else parseRect(v, tr(S.vscreen_helpers_region))
        return when (kind) {
            "text", "gone" -> {
                if (v.isEmpty()) throw ActionError("INVALID_STEP", tr(S.vscreen_steps_write_text_after, kind, s))
                // 1.4.0-beta4: `text:07 in x1,y1,x2,y2` only counts the words inside that area (a wheel's middle row, a
                // slider's label), so a lesson can wait for a value to be selected, not just to be somewhere on screen.
                val m = IN_AREA.find(v)
                val w = m?.groupValues?.get(1)?.trim() ?: v
                val a = m?.groupValues?.get(2)?.let { parseRect(it.replace(" ", ""), tr(S.vscreen_helpers_region)) }
                Cond(s, if (kind == "text") CondKind.TEXT else CondKind.GONE, text = w, area = a)
            }
            "change", "changed" -> Cond(s, CondKind.CHANGE, area = area())
            "still" -> Cond(s, CondKind.STILL, area = area())
            "stable" -> Cond(s, CondKind.STABLE, area = area())
            "app", "activity" -> { if (v.isEmpty()) throw ActionError("INVALID_STEP", tr(S.vscreen_steps_write_a_package_or_screen, s)); Cond(s, CondKind.APP, text = v) }
            "color", "colour" -> {
                val m = Regex("^(\\d+)\\s*[,，]\\s*(\\d+)\\s*=\\s*(#?[0-9a-fA-F]{6})\\s*(?:~\\s*(\\d+))?$").find(v)
                    ?: throw ActionError("INVALID_STEP", tr(S.vscreen_steps_write_a_color_condition_as, s))
                val c = com.kilon.agentvscreen.core.VPixels.parseColor(m.groupValues[3]) ?: throw ActionError("INVALID_STEP", tr(S.vscreen_steps_write_a_color_as_rrggbb, s))
                Cond(s, CondKind.COLOR, x = m.groupValues[1].toInt(), y = m.groupValues[2].toInt(), color = c, tol = m.groupValues[4].toIntOrNull()?.coerceIn(0, 255) ?: 40)
            }
            else -> throw ActionError("INVALID_STEP", tr(S.vscreen_steps_unknown_condition_the_conditions_are, s) + CONDS_HELP)
        }
    }

    /** Conditions of a --stop / keep value. */
    fun conds(spec: String): List<Cond> = condsWithMax(tokens(resolvePct(spec))).first

    /** 2.0.0-beta4 (4.1): text:/gone: conditions inside hold/seek must include `in x1,y1,x2,y2`. */
    private fun validateLoopConds(conds: List<Cond>) {
        conds.firstOrNull { (it.kind == CondKind.TEXT || it.kind == CondKind.GONE) && it.area == null }?.let { c ->
            throw ActionError(
                "GESTURE_NEEDS_AREA",
                tr(S.vscreen_steps_inside_a_closed_loop_gesture, c.kind.key, c.text),
            )
        }
    }

    private fun bad(raw: String, why: String): Nothing = throw ActionError("INVALID_STEP",
        tr(S.vscreen_steps_step_nothing_in_this_stretch, raw, why))
}
