package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.tr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VPixels
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenGame
import com.kilon.agentvscreen.core.VScreenHandoff
import com.kilon.agentvscreen.core.VScreenKnowledge
import com.kilon.agentvscreen.core.VScreenMap
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenAwake
import com.kilon.agentvscreen.core.VScreenPerception
import com.kilon.agentvscreen.core.VScreenRecorder
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenStore
import kotlin.math.hypot
import kotlin.math.roundToInt

// [vscreen] v49: `act` — one segment: 0..N steps, each with a fact-only wait, stop conditions checked every 0.1 s,
// optional watching afterwards, and (for apps whose pause / resume steps were written down) resume → steps → watch →
// pause, so a realtime game becomes turn-based. It returns the four blocks (VScreenReport). With --keep the stop
// conditions go on being watched after the turn (VScreenRules) and post an event to the caller or notify the user.

/** Watches [conds] on [d]; the baseline (for change:) is the picture when it was made. */
internal class Probe(private val h: VScreenOffloadHandler, private val d: VScreenDisplay, val conds: List<Cond>) {
    private val since = SystemClock.elapsedRealtime()
    private var base: Bitmap? = d.latestFrame()?.let { VScreenPerception.small(it) }
    private var prev: Bitmap? = null
    private val stillMs = HashMap<Int, Long>()
    private var lastPoll = 0L
    private var appAt = 0L
    private var appNow: List<String> = emptyList()
    private val wantsText = conds.any { it.kind == "text" || it.kind == "gone" }

    fun close() { base?.recycle(); base = null; prev?.recycle(); prev = null }

    /** Re-take the baseline (a kept watch starts from the moment the turn ended). */
    fun rebase() { base?.recycle(); base = d.latestFrame()?.let { VScreenPerception.small(it) }; stillMs.clear() }

    private fun scaled(c: Cond, b: Bitmap): Rect? = c.area?.let { r ->
        val k = b.width.toDouble() / maxOf(1, d.width)
        Rect((r.left * k).toInt(), (r.top * k).toInt(), maxOf((r.left * k).toInt() + 1, (r.right * k).toInt()), maxOf((r.top * k).toInt() + 1, (r.bottom * k).toInt()))
    }

    private fun diff(a: Bitmap, b: Bitmap, c: Cond): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        return VPixels.regionDiff(a, b, scaled(c, a) ?: Rect(0, 0, a.width, a.height)).coerceAtLeast(0.0)
    }

    private fun where(c: Cond) = c.area?.let { tr("区域 [${Report.box(it)}]", "region [${Report.box(it)}]") } ?: tr("画面", "the screen")

    /** The words of [q] seen now (accessibility first, then an OCR reading taken after this probe began). */
    private fun seen(q: String): String? {
        val n = VScreenOcr.norm(q)
        runCatching { BackgroundOperation.a11yTexts(d.displayId, 400) }.getOrNull()?.firstOrNull { VScreenOcr.norm(it).contains(n) }?.let { return it.take(40) }
        val r = VScreenPerception.reading(d.displayId, since, 0) ?: return null
        return r.items.firstOrNull { VScreenOcr.norm(it.text).contains(n) }?.text?.take(40)
    }

    /** True only when a reading taken after this probe began does not hold [q] (and the accessibility tree does not either). */
    private fun gone(q: String): Boolean {
        val n = VScreenOcr.norm(q)
        if (runCatching { BackgroundOperation.a11yTexts(d.displayId, 400) }.getOrNull()?.any { VScreenOcr.norm(it).contains(n) } == true) return false
        val r = VScreenPerception.reading(d.displayId, since, 0) ?: return false
        return r.items.none { VScreenOcr.norm(it.text).contains(n) }
    }

    /**
     * What the foreground of the screen is, every way it can be named: the top activity, the packages of the
     * visible tasks on this display, and their app names. `app:` matches any of them.
     */
    private fun appFacts(): List<String> {
        val out = ArrayList<String>()
        runCatching { VScreenShell.topActivity(d.displayId) }.getOrNull()?.let { out += it; out += it.substringBefore('/') }
        runCatching { VScreenHandoff.tasksOn(d.displayId).filter { it.visible }.map { it.pkg } }.getOrNull()?.let { out += it }
        out.toList().map { it.substringBefore('/') }.distinct().forEach { p -> VScreenKnowledge.label(p)?.let { out += it } }
        return out.distinct()
    }

    /** For a wait that timed out: what each condition sees now (a fact the caller can compare with what it asked). */
    fun now(): String = conds.joinToString(tr("；", "; ")) { c ->
        when (c.kind) {
            "text" -> tr("画面上没有“${c.text}”", "\"${c.text}\" is not on the screen")
            "gone" -> tr("“${c.text}”还在", "\"${c.text}\" is still there")
            "change" -> tr("${where(c)}没有明显变化", "no clear change in ${where(c)}")
            "still" -> tr("${where(c)}一直在变", "${where(c)} keeps changing")
            "color" -> d.latestFrame()?.takeIf { c.x in 0 until it.width && c.y in 0 until it.height }
                ?.let { tr("(${c.x},${c.y}) 现在是 ${VPixels.hex(VPixels.at(it, c.x, c.y))}", "(${c.x},${c.y}) is now ${VPixels.hex(VPixels.at(it, c.x, c.y))}") } ?: tr("读不到 (${c.x},${c.y}) 的颜色", "cannot read the color at (${c.x},${c.y})")
            "app" -> tr("前台是 ", "front app is ") + (appFacts().takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: tr("（读不到）", "(unreadable)"))
            else -> c.raw
        }
    }

    /** null = nothing met yet; otherwise "<condition>：<what was seen>". */
    fun poll(): String? {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastPoll == 0L) 0L else now - lastPoll
        lastPoll = now
        if (wantsText) VScreenPerception.hurry(d.displayId)
        val full = d.latestFrame()
        val cur = full?.let { VScreenPerception.small(it) }
        try {
            for ((i, c) in conds.withIndex()) {
                val hit: String? = when (c.kind) {
                    "text" -> seen(c.text!!)?.let { tr("看到“$it”", "saw \"$it\"") }
                    "gone" -> if (gone(c.text!!)) tr("画面上没有“${c.text}”了", "\"${c.text}\" is gone from the screen") else null
                    "change" -> { val b = base; if (b != null && cur != null) diff(b, cur, c).takeIf { it >= 0.02 }?.let { tr("${where(c)}变了（${(it * 100).roundToInt()}% 的取样点不同）", "${where(c)} changed (${(it * 100).roundToInt()}% of sample points differ)") } else null }
                    "still" -> {
                        val p = prev
                        if (p != null && cur != null && diff(p, cur, c) <= 0.004) stillMs[i] = (stillMs[i] ?: 0L) + dt else stillMs[i] = 0L
                        val s = stillMs[i] ?: 0L
                        if (s >= 500L && now - since >= 300L) tr("${where(c)}连续 ${s}ms 没变", "${where(c)} unchanged for ${s}ms") else null
                    }
                    "color" -> full?.takeIf { c.x in 0 until it.width && c.y in 0 until it.height }?.let { f ->
                        val px = VPixels.at(f, c.x, c.y)
                        if (VPixels.dist(px, c.color) <= c.tol) tr("(${c.x},${c.y}) 是 ${VPixels.hex(px)}", "(${c.x},${c.y}) is ${VPixels.hex(px)}") else null
                    }
                    "app" -> {
                        if (now - appAt >= 500L) { appNow = appFacts(); appAt = now }
                        appNow.firstOrNull { it.contains(c.text!!, ignoreCase = true) }?.let { tr("前台是 ${appNow.first()}", "front app is ${appNow.first()}") }
                    }
                    else -> null
                }
                if (hit != null) return tr("${c.raw}：$hit", "${c.raw}: $hit")
            }
            return null
        } finally { prev?.recycle(); prev = cur }
    }
}

/** How one wait ended: [met] = the condition's fact, [stopped] = a stop condition's fact, else it timed out. */
internal class WaitOut(val met: String?, val stopped: String?, val ms: Long)

/** Wait on [probe] (null = just time) up to [maxMs], checking [stop] too, every 0.1 s. */
internal fun waitOn(d: VScreenDisplay, probe: Probe?, maxMs: Long, stop: Probe?): WaitOut {
    val t0 = SystemClock.elapsedRealtime()
    while (true) {
        d.requestFreshFrame()
        Thread.sleep(100)
        val el = SystemClock.elapsedRealtime() - t0
        if (BackgroundOperation.state.value.takeoverSessionId != null) throw ActionError("USER_TAKEOVER", tr("用户接管了副屏，这段停在这里", "The user took over the screen; the segment stops here"))
        if (BackgroundOperation.state.value.userStopped) throw ActionError("USER_STOPPED", BackgroundOperation.userStoppedMessage())
        stop?.poll()?.let { return WaitOut(null, it, el) }
        probe?.poll()?.let { return WaitOut(it, null, el) }
        if (el >= maxMs) return WaitOut(null, null, el)
    }
}

/** Options of one segment. */
internal class SegmentOpts(val stop: List<Cond>, val watchMs: Long?, val paused: Boolean, val confirmed: Boolean)

/** A step that was not done, and why (it ends the segment; the reason goes to 【注意】). */
internal class StepRefused(val code: String, message: String) : Exception(message)

/** What a segment did. */
internal class SegmentOut(val done: List<String>, val attention: MutableList<String>, val actionMs: Long, val start: Long)

private fun s1(ms: Long) = "${(ms / 100) / 10.0}s"

/** The app a chat works on: the one it launched (pages of other packages included), else the one on top. */
internal fun VScreenOffloadHandler.workApp(sid: String, d: VScreenDisplay): String? =
    VScreenKnowledge.journey(sid) ?: runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()?.takeIf { it != context.packageName }

/** Run a segment of [steps] on the chat's screen. Never throws for a step: a failed step ends the segment and is said. */
internal fun VScreenOffloadHandler.runSegment(sid: String, d: VScreenDisplay, steps: List<Step>, o: SegmentOpts): SegmentOut {
    val start = System.currentTimeMillis()
    val t0 = SystemClock.elapsedRealtime()
    val done = ArrayList<String>()
    val attention = ArrayList<String>()
    val main0 = mainTop(); val tasks0 = mainTaskIds()
    VScreenAwake.ensure(d)?.let { attention += it }
    VScreenAwake.kick("act")
    VScreenPerception.ensure(d)
    val app = workApp(sid, d)
    val prof = app?.let { VScreenMap.profile(it) }.orEmpty()
    val pauseParsed = prof["pause"]?.let { runCatching { Steps.parseAll(it) } }
    val resumeParsed = prof["resume"]?.let { runCatching { Steps.parseAll(it) } }
    val pauseSteps = pauseParsed?.getOrNull()
    val resumeSteps = resumeParsed?.getOrNull()
    val turnBased = pauseSteps != null && resumeSteps != null
    // v0.5: a half-written or unreadable turn setup is said, not silently ignored.
    pauseParsed?.exceptionOrNull()?.let { attention += tr("记的暂停步骤读不懂（${it.message?.take(80)}）：回合制没启用，note pause 重写", "The saved pause steps can't be parsed (${it.message?.take(80)}): turn-based mode is off, rewrite them with note pause") }
    resumeParsed?.exceptionOrNull()?.let { attention += tr("记的继续步骤读不懂（${it.message?.take(80)}）：回合制没启用，note resume 重写", "The saved resume steps can't be parsed (${it.message?.take(80)}): turn-based mode is off, rewrite them with note resume") }
    if (prof["pause"] != null && prof["resume"] == null) attention += tr("只记了暂停、没记继续：回合制没启用（note resume \"<继续的步骤>\"）", "Only pause steps are saved, no resume steps: turn-based mode is off (note resume \"<resume steps>\")")
    if (prof["resume"] != null && prof["pause"] == null) attention += tr("只记了继续、没记暂停：回合制没启用（note pause \"<暂停的步骤>\"）", "Only resume steps are saved, no pause steps: turn-based mode is off (note pause \"<pause steps>\")")
    val realtime = turnBased || prof["realtime"] == "true"
    val stop = if (o.stop.isNotEmpty()) Probe(this, d, o.stop) else null
    var resumed = false
    var stoppedBy: String? = null
    // 1.1: a step whose expected result did not happen (an until that timed out, typing that did not land or could not
    // be checked) ends the segment like a refused step: the later steps were written for a screen that is not there.
    var missed: String? = null
    var skipped = 0
    fun screenNow(): String = runCatching {
        val sn = snapshot(d)
        val words = sn.els.filter { !it.label.let { it.startsWith("（") || it.startsWith("(") } }.map { it.label.take(16) }.distinct().take(10)
        if (I18n.en) "Now: ${sn.activity?.substringAfterLast('/') ?: "front app unreadable"}" + if (words.isEmpty()) ", no text read on the screen" else ", visible: ${words.joinToString(", ") { "\"$it\"" }}"
        else "现在：${sn.activity?.substringAfterLast('/') ?: "前台读不到"}" + if (words.isEmpty()) "，画面上没有读到字" else "，能看到 ${words.joinToString("、") { "“$it”" }}"
    }.getOrDefault("")
    var n = 0
    // Where the agent last tapped in this segment: typing must go to a field there (BackgroundOperation.typeFact).
    var lastTap: Pair<Int, Int>? = null
    fun line(s: String) { done += s }
    fun runOne(step: Step, label: String, depth: Int, quick: Boolean) {
        if (stoppedBy != null) return
        if (missed != null) { skipped++; return }
        if (step.kind == "use") {
            val name = step.text!!
            val seg = app?.let { VScreenMap.segment(it, name) } ?: throw StepRefused("NOT_FOUND", tr("没有叫“$name”的段（${app ?: "当前应用"}）：note 能看到保存过的段", "No segment named \"$name\" (${app ?: "current app"}): note lists the saved segments"))
            if (depth >= 3) throw StepRefused("INVALID_STEP", tr("段里 use 套用太深（最多 3 层）", "use is nested too deep in segments (at most 3 levels)"))
            val inner = runCatching { Steps.parseAll(seg.steps) }.getOrElse { throw StepRefused("INVALID_STEP", tr("段“$name”的步骤读不懂：${it.message}", "The steps of segment \"$name\" can't be parsed: ${it.message}")) }
            line(tr("$label use $name（${inner.size} 步）", "$label use $name (${inner.size} steps)"))
            VScreenMap.markUsed(app, name)
            inner.forEachIndexed { i, st -> runOne(st, "$label.${i + 1}", depth + 1, quick) }
            return
        }
        if (step.kind == "sleep") {
            val w = waitOn(d, null, step.ms ?: 0L, stop)
            if (w.stopped != null) { stoppedBy = w.stopped; line(tr("$label 等 ${s1(step.ms ?: 0)}：${s1(w.ms)} 时停止条件 ${w.stopped}", "$label wait ${s1(step.ms ?: 0)}: stop condition ${w.stopped} at ${s1(w.ms)}")) }
            else line(tr("$label 等了 ${s1(w.ms)}", "$label waited ${s1(w.ms)}"))
            return
        }
        if (step.kind == "wait") {
            val p = Probe(this, d, step.until!!)
            try {
                val w = waitOn(d, p, step.untilMs ?: Steps.WAIT_DEFAULT_MS, stop)
                when {
                    w.stopped != null -> { stoppedBy = w.stopped; line(tr("$label 等 ${step.until.joinToString(" or ") { it.raw }}：${s1(w.ms)} 时停止条件 ${w.stopped}", "$label wait ${step.until.joinToString(" or ") { it.raw }}: stop condition ${w.stopped} at ${s1(w.ms)}")) }
                    w.met != null -> line(tr("$label 等到了 ${w.met}（${s1(w.ms)}）", "$label got ${w.met} (${s1(w.ms)})"))
                    else -> {
                        val now = screenNow()
                        line(tr("$label 等 ${step.until.joinToString(" or ") { it.raw }}：${s1(w.ms)} 到时间，没等到（${p.now()}）", "$label wait ${step.until.joinToString(" or ") { it.raw }}: time up at ${s1(w.ms)}, never came (${p.now()})"))
                        missed = tr("第 $label 步 ${step.raw} 没等到（${p.now()}）。$now", "Step $label ${step.raw} never came (${p.now()}). $now")
                    }
                }
            } finally { p.close() }
            return
        }
        // An action.
        stop?.poll()?.let { stoppedBy = it; line(tr("$label 没做（${step.raw}）：停止条件 $it 已经满足", "$label not done (${step.raw}): stop condition $it already met")); return }
        val conds = step.until ?: if (quick) null else listOf(Cond("stable", "still"))
        val probe = conds?.let { Probe(this, d, it) }
        try {
            val done1 = doAction(sid, d, step, o.confirmed, lastTap)
            val fact = done1.text
            lastTap = if (step.kind == "tap" || step.kind == "press") lastPoint(sid) else if (step.kind == "type") lastTap else null
            val maxMs = step.untilMs ?: if (step.until != null) Steps.UNTIL_DEFAULT_MS else 3_000L
            if (done1.miss != null) {
                line("$label ${step.raw} → $fact")
                missed = tr("第 $label 步 ${step.raw}：${done1.miss}", "Step $label ${step.raw}: ${done1.miss}")
                return
            }
            if (probe == null) { line("$label ${step.raw} → $fact"); Thread.sleep(80); return }
            val w = waitOn(d, probe, maxMs, stop)
            val waitTxt = when {
                w.stopped != null -> { stoppedBy = w.stopped; tr("${s1(w.ms)} 时停止条件 ${w.stopped}", "stop condition ${w.stopped} at ${s1(w.ms)}") }
                w.met != null -> tr("等到 ${w.met}（${s1(w.ms)}）", "got ${w.met} (${s1(w.ms)})")
                step.until == null -> tr("等画面稳定：${s1(w.ms)} 到时间，画面还在变", "waiting for the screen to settle: time up at ${s1(w.ms)}, still changing")
                else -> {
                    missed = tr("第 $label 步 ${step.raw} 做了，但没等到 ${step.until.joinToString(" or ") { it.raw }}（${probe.now()}）。", "Step $label ${step.raw} was done, but ${step.until.joinToString(" or ") { it.raw }} never came (${probe.now()}). ") + screenNow()
                    tr("等 ${step.until.joinToString(" or ") { it.raw }}：${s1(w.ms)} 到时间，没等到（${probe.now()}）", "wait ${step.until.joinToString(" or ") { it.raw }}: time up at ${s1(w.ms)}, never came (${probe.now()})")
                }
            }
            line(tr("$label ${step.raw} → $fact；$waitTxt", "$label ${step.raw} → $fact; $waitTxt"))
        } finally { probe?.close() }
    }
    fun runList(list: List<Step>, prefix: String, quickAll: Boolean, lastWaits: Boolean) {
        list.forEachIndexed { i, st ->
            val last = i == list.lastIndex
            runOne(st, "$prefix${i + 1}", 0, quick = quickAll && !(last && lastWaits && !realtime))
        }
    }
    try {
        val watch = o.watchMs ?: if (realtime && (steps.isNotEmpty() || !turnBased)) 1_000L else 0L
        if (turnBased && !o.paused && (steps.isNotEmpty() || o.watchMs != null)) {
            val moving = Probe(this, d, listOf(Cond("change", "change")))
            try {
                runList(resumeSteps!!, tr("继续 ", "resume "), quickAll = true, lastWaits = false)
                resumed = true
                // v0.5: checked against the picture: a resumed app moves.
                val w = waitOn(d, moving, 1_000L, null)
                if (w.met == null) attention += tr("继续步骤做了，但 1 秒内画面没动：可能没继续上（坐标不对、被弹窗挡住），这段的动作是在暂停状态下做的", "The resume steps ran, but the screen did not move within 1 s: it may not have resumed (wrong coordinates, a popup in the way), so this segment's actions were done while paused")
            } finally { moving.close() }
        }
        // Normal apps wait for a still picture after each action (at most 3 s); realtime apps go straight on.
        runList(steps, "", quickAll = realtime, lastWaits = true)
        n = steps.size
        if (steps.isEmpty() && !realtime && stoppedBy == null) {
            val p = Probe(this, d, listOf(Cond("stable", "still")))
            try {
                val w = waitOn(d, p, 3_000L, stop)
                line(when {
                    w.stopped != null -> { stoppedBy = w.stopped; tr("看了一眼：${s1(w.ms)} 时停止条件 ${w.stopped}", "looked: stop condition ${w.stopped} at ${s1(w.ms)}") }
                    w.met != null -> tr("看了一眼（画面稳定，${s1(w.ms)}）", "looked (screen still, ${s1(w.ms)})")
                    else -> tr("看了一眼（3 秒内一直有像素在动，没等到完全静止；有没有明显变化看【这段时间】）", "looked (pixels kept moving for 3 s, never fully still; see [Meanwhile] for clear changes)")
                })
            } finally { p.close() }
        }
        if (watch > 0 && stoppedBy == null) {
            val w = waitOn(d, null, watch, stop)
            line(if (w.stopped != null) { stoppedBy = w.stopped; tr("观察 ${s1(watch)}：${s1(w.ms)} 时停止条件 ${w.stopped}", "watch ${s1(watch)}: stop condition ${w.stopped} at ${s1(w.ms)}") } else tr("观察了 ${s1(w.ms)}", "watched ${s1(w.ms)}"))
        }
    } catch (e: StepRefused) {
        attention += tr("${e.code}：${e.message}", "${e.code}: ${e.message}")
        line(tr("（从这里起没做：${e.code}：${e.message?.take(60)}）", "(not done from here: ${e.code}: ${e.message?.take(60)})"))
    } catch (e: ActionError) {
        when (e.code) {
            "REREAD_REQUIRED", "HOST_RESTARTED" -> { BackgroundOperation.acknowledgeReread(sid); attention += tr("${e.code}：${e.message}", "${e.code}: ${e.message}") }
            else -> attention += tr("${e.code}：${e.message}", "${e.code}: ${e.message}")
        }
        connectionLost(d)?.let { attention += it }
        line(tr("（停在这里：${e.code}）", "(stopped here: ${e.code})"))
    } finally {
        stop?.close()
        if (resumed && connectionLost(d) == null) {
            // The pause runs even when the segment stopped early (a game must not keep running while the caller thinks).
            val m = missed; missed = null
            runCatching { runList(pauseSteps!!, tr("暂停 ", "pause "), quickAll = true, lastWaits = false) }
                .also { if (missed != null) attention += tr("暂停步骤没达到预期：$missed", "The pause steps missed their expectation: $missed") ; missed = m }
                .onFailure { attention += tr("暂停步骤没做完：${it.message}", "The pause steps did not finish: ${it.message}") }
            // v0.5: the pause is checked against the picture, not assumed: a paused app stops moving.
            val p = Probe(this, d, listOf(Cond("stable", "still")))
            try {
                val w = waitOn(d, p, 2_500L, null)
                if (w.met != null) line(tr("暂停后画面静止（${s1(w.ms)} 内确认）", "screen still after pausing (confirmed within ${s1(w.ms)})"))
                else {
                    line(tr("暂停后 2.5s 画面一直在变", "screen kept changing for 2.5 s after pausing"))
                    attention += tr("暂停步骤做了，但画面 2.5 秒内一直在变：可能没暂停住（坐标不对、被弹窗挡住），也可能暂停页本身有动画。看图核对；没停住就 note pause 改步骤", "The pause steps ran, but the screen kept changing for 2.5 s: it may not have paused (wrong coordinates, a popup in the way), or the pause page itself is animated. Check the picture; if it did not pause, fix the steps with note pause")
                }
            } catch (e: ActionError) { attention += tr("暂停核对没做完：${e.code}", "The pause check did not finish: ${e.code}") } finally { p.close() }
        }
    }
    missed?.let { m ->
        attention += tr("没达到预期，这段停在这里：$m", "Expectation missed, the segment stops here: $m") + (if (skipped > 0) tr("。后面 $skipped 步没做", ". The remaining $skipped steps were not done") else "") + tr("。看截图决定下一步", ". Look at the screenshot to decide what next")
        if (skipped > 0) line(tr("（后面 $skipped 步没做：上一步没达到预期）", "(the remaining $skipped steps were not done: the previous step missed its expectation)"))
    }
    if (o.stop.isNotEmpty()) line(if (stoppedBy != null) tr("停止条件触发：$stoppedBy", "stop condition fired: $stoppedBy") else tr("停止条件 ${o.stop.joinToString(" or ") { it.raw }}：这段里没出现", "stop condition ${o.stop.joinToString(" or ") { it.raw }}: did not occur in this segment"))
    if (turnBased && !resumed && steps.isEmpty()) line(tr("（这个应用记了暂停/继续步骤；这次没带步骤也没 --watch，所以没有继续，画面停在原状态）", "(this app has saved pause/resume steps; this time there were no steps and no --watch, so it was not resumed and the screen stays as it was)"))
    mainCheckLines(main0, tasks0)?.let { attention += it }
    val top = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()
    if (app != null && top != null && top != app && top != context.packageName) attention += tr("副屏前台现在是 $top，不是你打开的 $app", "The front app on the screen is now $top, not $app that you opened")
    return SegmentOut(done, attention, SystemClock.elapsedRealtime() - t0, start)
}

/** The elements to resolve #N / words against: the list last shown (for #N) or the screen now (for words). */
private fun VScreenOffloadHandler.target(sid: String, d: VScreenDisplay, t: StepTarget): Triple<Int, Int, String?> = when (t) {
    is StepTarget.At -> {
        if (t.x !in 0 until d.width || t.y !in 0 until d.height)
            throw StepRefused("OUT_OF_BOUNDS", tr("(${t.x},${t.y}) 在副屏 ${d.width}x${d.height} 外面", "(${t.x},${t.y}) is outside the ${d.width}x${d.height} screen"))
        val label = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }?.list
            ?.filter { it.box.contains(t.x, t.y) }?.minByOrNull { it.box.width().toLong() * it.box.height() }?.label
        Triple(t.x, t.y, label)
    }
    is StepTarget.Ref -> {
        val set = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }
            ?: throw StepRefused("NOT_FOUND", tr("还没有元素列表：先 act 看一眼再用 #编号", "No element list yet: look with act first, then use #numbers"))
        if (set.generation != d.generation) throw StepRefused("NOT_FOUND", tr("#${t.n} 是上一块副屏的列表（副屏重建过）：先 act 看一眼", "#${t.n} is from the previous screen's list (the screen was rebuilt): look with act first"))
        val e = set.list.firstOrNull { it.n == t.n } ?: throw StepRefused("NOT_FOUND", tr("上次的列表里没有 #${t.n}（共 ${set.list.size} 个）", "There is no #${t.n} in the last list (${set.list.size} in all)"))
        Triple(e.box.centerX(), e.box.centerY(), e.label)
    }
    is StepTarget.Words -> {
        val els = snapshot(d).els
        val q = VScreenOcr.norm(t.text)
        val exact = els.filter { VScreenOcr.norm(it.label) == q }
        val hits = exact.ifEmpty { els.filter { VScreenOcr.norm(it.label).contains(q) } }
        val e = hits.firstOrNull() ?: throw StepRefused("NOT_FOUND", tr("画面上没找到“${t.text}”（控件文字和 OCR 都没有）", "\"${t.text}\" was not found on the screen (neither in widget text nor OCR)") + nearMiss(els, t.text))
        Triple(e.box.centerX(), e.box.centerY(), e.label + if (hits.size > 1) tr("，共 ${hits.size} 处，点了第一处", "; ${hits.size} matches, tapped the first") else "")
    }
}

/**
 * 1.1: what IS on the screen when a word target is missing: the closest words (shared characters) and the input
 * fields, each with its point, so the next try can aim at a point instead of guessing another word. Rotating
 * placeholder words (a browser's hot-search box) are the usual cause.
 */
internal fun nearMiss(els: List<El>, want: String): String {
    val q = VScreenOcr.norm(want)
    if (q.isEmpty()) return ""
    // Shared characters, relative to the shorter text; at least two shared (one shared character is chance).
    fun score(l: String): Double {
        val n = VScreenOcr.norm(l); if (n.isEmpty()) return 0.0
        val common = q.toSet().intersect(n.toSet()).size
        if (common < minOf(2, q.toSet().size)) return 0.0
        return common.toDouble() / minOf(q.toSet().size, n.toSet().size)
    }
    val close = els.filter { !it.label.let { l -> l.startsWith("（") || l.startsWith("(") } }.map { it to score(it.label) }.filter { it.second >= 0.5 }
        .sortedByDescending { it.second }.take(3).map { "“${it.first.label.take(24)}”(${it.first.box.centerX()},${it.first.box.centerY()})" }
    val fields = els.filter { it.editable }.take(3).map { "${it.label.take(20)}(${it.box.centerX()},${it.box.centerY()})" }
    val parts = buildList {
        if (close.isNotEmpty()) add(tr("相近的字：", "similar text: ") + close.joinToString(tr("、", ", ")))
        if (fields.isNotEmpty()) add(tr("输入框：", "input boxes: ") + fields.joinToString(tr("、", ", ")))
    }
    return if (parts.isEmpty()) tr("。画面上也没有相近的字：看截图，用坐标点", ". No similar text on the screen either: look at the screenshot and tap by coordinates") else tr("。", ". ") + parts.joinToString(tr("；", "; ")) + tr("。文字会变的地方（轮播的提示词）用坐标点", ". Where the text changes (rotating hints), tap by coordinates")
}

/** Refuse a press on a money / irreversible button without --confirmed. */
private fun payGuard(label: String?, confirmed: Boolean) {
    val l = label ?: return
    val kind = VScreenGame.commitKind(l) ?: return
    if (!confirmed) throw StepRefused("CONFIRM_REQUIRED", tr("“${l.take(20)}”是${if (kind == "spend") "付费/购买" else "不可撤销"}按钮，没点。", "\"${l.take(20)}\" is ${if (kind == "spend") "a payment / purchase" else "an irreversible"} button; not tapped. ") +
        tr("先把要做的事和金额告诉用户，用户同意后在这一段加 --confirmed 重发", "First tell the user what you are about to do and the amount; once the user agrees, send this segment again with --confirmed"))
}

/** The user's per-app rule for the app on the screen (read-only: no taps / typing / gestures; blocked: only key back). */
private fun appRule(d: VScreenDisplay, step: Step) {
    if (VScreenStore.rules.value.isEmpty()) return
    val pkg = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull() ?: return
    val rule = VScreenStore.ruleFor(pkg) ?: return
    val hint = tr("这是用户在 Agent-VScreen › 应用限制 里定的，不要绕过（也不要从别的入口进同一个应用）", "The user set this in Agent-VScreen › App rules; don't work around it (and don't reach the same app through another entry)")
    val back = step.kind == "key" && step.text == "back"
    if (rule == VScreenStore.Rule.BLOCK && !back) throw StepRefused("APP_BLOCKED", tr("副屏上是 $pkg，用户禁止后台操作它：只能 key back 离开或 stop。$hint", "The screen shows $pkg, which the user blocked from background operation: only key back to leave, or stop. $hint"))
    if (rule == VScreenStore.Rule.READONLY && step.kind !in setOf("scroll", "key")) throw StepRefused("APP_READ_ONLY", tr("副屏上是 $pkg，用户设为只读：能看、滚动、返回，不能点击/输入/滑动。$hint", "The screen shows $pkg, which the user set to read-only: you can look, scroll and go back, but not tap / type / swipe. $hint"))
}

/** Do one action and say what was done (facts: where, with which method). */
/** The point the last tap / press of this thread went to (for the typing check). */
private val actPoint = ThreadLocal<Pair<Int, Int>?>()
/** The focused field before the last tap (see BackgroundOperation.typeFact). */
private val focusBeforeTap = ThreadLocal<String?>()
private fun lastPoint(@Suppress("UNUSED_PARAMETER") sid: String): Pair<Int, Int>? = actPoint.get()

/** Null while the privileged connection and the screen are alive; otherwise what the agent must know and do. */
internal fun connectionLost(d: VScreenDisplay): String? {
    val st = BackgroundOperation.state.value
    if (!d.released && st.connection == BackgroundOperation.Connection.READY) return null
    return tr("后台连接断了（${st.connectionError ?: st.connection.name}），副屏和上面的应用已经没了，不是你的操作造成的。", "The background connection broke (${st.connectionError ?: st.connection.name}); the screen and the app on it are gone, and your actions did not cause it. ") +
        tr("告诉用户；连接恢复（Shizuku 在运行，vscreen fix not_connected）后重新 launch", "Tell the user; once the connection is back (Shizuku running, vscreen fix not_connected), launch again")
}

/** What one action did ([text]), and [miss] when its own effect could not be confirmed (it ends the segment). */
internal class Done(val text: String, val miss: String? = null)

private fun VScreenOffloadHandler.doAction(sid: String, d: VScreenDisplay, step: Step, confirmed: Boolean, tappedAt: Pair<Int, Int>? = null): Done {
    appRule(d, step)
    actPoint.set(null)
    var miss: String? = null
    val pts = ArrayList<IntArray>()
    val fact = BackgroundOperation.agentAction(sid, null, isRead = false, describe = step.raw.take(40)) { dd ->
        fun okOr(o: VScreenShell.Outcome) { if (!o.ok) throw ActionError(o.code, o.message) }
        when (step.kind) {
            "tap" -> {
                val (x, y, label) = target(sid, dd, step.target!!)
                payGuard(label, confirmed)
                focusBeforeTap.set(BackgroundOperation.focusedFieldKey(dd.displayId))
                okOr(VScreenShell.tap(dd.displayId, x, y)); pts += intArrayOf(x, y); actPoint.set(x to y)
                tr("点了 ($x,$y)", "tapped ($x,$y)") + (label?.let { tr("，那里是“${it.take(30)}”", ", which is \"${it.take(30)}\"") } ?: "")
            }
            "press" -> {
                val (x, y, label) = target(sid, dd, step.target!!)
                payGuard(label, confirmed)
                okOr(VScreenShell.longPress(dd.displayId, x, y, (step.ms ?: 800L).toInt())); pts += intArrayOf(x, y); actPoint.set(x to y)
                tr("按住 ($x,$y) ${step.ms}ms", "held ($x,$y) ${step.ms}ms") + (label?.let { tr("，那里是“${it.take(30)}”", ", which is \"${it.take(30)}\"") } ?: "")
            }
            "swipe" -> {
                val (a, b) = step.points
                bounds(dd, step.points)
                okOr(VScreenShell.swipe(dd.displayId, a.first, a.second, b.first, b.second, (step.ms ?: 300L).toInt()))
                pts += intArrayOf(a.first, a.second); pts += intArrayOf(b.first, b.second)
                tr("从 (${a.first},${a.second}) 滑到 (${b.first},${b.second})，${step.ms}ms", "swiped (${a.first},${a.second}) → (${b.first},${b.second}), ${step.ms}ms")
            }
            "drag" -> {
                bounds(dd, step.points)
                val how = drag(dd, step.points, (step.ms ?: 600L).toInt())
                step.points.forEach { pts += intArrayOf(it.first, it.second) }
                tr("按住拖过 ", "held and dragged through ") + step.points.joinToString(" → ") { "(${it.first},${it.second})" } + ", ${step.ms}ms ($how)"
            }
            "scroll" -> {
                val c = step.target?.let { val (x, y, _) = target(sid, dd, it); x to y } ?: (dd.width / 2 to dd.height / 2)
                val dir = step.text!!
                val vertical = dir == "up" || dir == "down"
                val span = ((if (vertical) dd.height else dd.width) * 0.3).toInt()
                // "down" = see what is below: the finger moves up.
                val (x1, y1, x2, y2) = when (dir) {
                    "down" -> listOf(c.first, (c.second + span).coerceAtMost(dd.height - 2), c.first, (c.second - span).coerceAtLeast(1))
                    "up" -> listOf(c.first, (c.second - span).coerceAtLeast(1), c.first, (c.second + span).coerceAtMost(dd.height - 2))
                    "right" -> listOf((c.first + span).coerceAtMost(dd.width - 2), c.second, (c.first - span).coerceAtLeast(1), c.second)
                    else -> listOf((c.first - span).coerceAtLeast(1), c.second, (c.first + span).coerceAtMost(dd.width - 2), c.second)
                }
                okOr(VScreenShell.swipe(dd.displayId, x1, y1, x2, y2, 350))
                pts += intArrayOf(x1, y1); pts += intArrayOf(x2, y2)
                tr("手指从 ($x1,$y1) 滑到 ($x2,$y2)", "finger slid ($x1,$y1) → ($x2,$y2)")
            }
            "type" -> {
                val f = BackgroundOperation.typeFact(dd.displayId, step.text!!, step.append, tappedAt, if (tappedAt != null) focusBeforeTap.get() else null)
                val where = f.bounds?.let { b -> " [${b.left},${b.top},${b.right},${b.bottom}]" + (f.fieldId?.let { tr("（$it）", " ($it)") } ?: "") } ?: ""
                when {
                    f.field == null -> {
                        miss = if (f.checked) tr("没有获得焦点的输入框，字很可能没进去。先点输入框（【现在】里标“可输入”的），等它标“有焦点”再 type", "No input box has focus, so the text most likely did not go in. Tap the input box first (marked \"input\" in [Now]) and type once it is marked \"focused\"")
                            else tr("无障碍没开，确认不了字落在哪。看截图核对，字进去了再发后面的步骤", "Accessibility is off, so where the text landed can't be confirmed. Check the screenshot, and send the next steps once the text is in")
                        tr("按 ${f.method} 发了 ${step.text.length} 个字；", "sent ${step.text.length} characters by ${f.method}; ") + if (f.checked) tr("没有获得焦点的输入框", "no focused input box") else tr("读不到输入框", "input box unreadable")
                    }
                    f.field.startsWith(tr("（密码框", "(password box")) -> tr("输入 ${step.text.length} 个字（${f.method}）；密码框$where ${f.field}", "typed ${step.text.length} characters (${f.method}); password box$where ${f.field}")
                    !f.field.contains(step.text) -> {
                        miss = tr("输入框$where 现在是“${f.field.take(60)}”，不含你输入的字（这个框可能不收这种输入，或焦点在别处）", "The input box$where now reads \"${f.field.take(60)}\" without your text (this box may not accept this kind of input, or focus is elsewhere)")
                        tr("按 ${f.method} 发了 ${step.text.length} 个字；输入框$where 现在是“${f.field.take(60)}”，不含你输入的字", "sent ${step.text.length} characters by ${f.method}; the input box$where now reads \"${f.field.take(60)}\" without your text")
                    }
                    else -> tr("输入 ${step.text.length} 个字（${f.method}）；输入框$where 现在是“${f.field.take(60)}”", "typed ${step.text.length} characters (${f.method}); the input box$where now reads \"${f.field.take(60)}\"")
                }
            }
            "key" -> {
                val k = VScreenShell.keyWithMethod(dd.displayId, step.text!!)
                okOr(k.outcome)
                tr("按了 ${step.text}（${k.method}）", "pressed ${step.text} (${k.method})")
            }
            else -> throw StepRefused("INVALID_STEP", tr("不是动作：${step.raw}", "not an action: ${step.raw}"))
        }
    }
    if (pts.isNotEmpty()) BackgroundOperation.actionLog(sid, 1).lastOrNull()?.trackN?.let { VScreenRecorder.setPoints(d.displayId, it, pts) }
    return Done(fact, miss)
}

private fun bounds(d: VScreenDisplay, pts: List<Pair<Int, Int>>) {
    pts.firstOrNull { it.first !in 0 until d.width || it.second !in 0 until d.height }?.let {
        throw StepRefused("OUT_OF_BOUNDS", tr("(${it.first},${it.second}) 在副屏 ${d.width}x${d.height} 外面", "(${it.first},${it.second}) is outside the ${d.width}x${d.height} screen"))
    }
}

/**
 * One finger down at the first point, still 150 ms (games pick a piece up only after a hold), glide through the
 * points over [ms], still 120 ms, lift. Without the display service (app-owned screen), two points use a plain drag.
 */
private fun drag(d: VScreenDisplay, pts: List<Pair<Int, Int>>, ms: Int): String {
    val lens = pts.zipWithNext { a, b -> hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) }
    val total = lens.sum().coerceAtLeast(1.0)
    d.boost(ms.toLong() + 3_000)
    val down = VScreenShell.finger(d.displayId, "down", 0, pts[0].first, pts[0].second).first
    if (!down.ok) {
        if (pts.size != 2) throw ActionError(down.code, tr("多点拖动需要副屏服务：${down.message}", "Multi-point drags need the screen service: ${down.message}"))
        val o = VScreenShell.drag(d.displayId, pts[0].first, pts[0].second, pts[1].first, pts[1].second, ms, 120)
        if (!o.ok) throw ActionError(o.code, o.message)
        return tr("整段注入", "injected as one gesture")
    }
    try {
        Thread.sleep(150)
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            if (BackgroundOperation.state.value.takeoverSessionId != null) throw ActionError("USER_TAKEOVER", tr("用户接管了副屏，已抬起手指", "The user took over the screen; fingers lifted"))
            val f = ((SystemClock.elapsedRealtime() - t0).toDouble() / ms).coerceIn(0.0, 1.0)
            var dist = f * total; var i = 0
            while (i < lens.size - 1 && dist > lens[i]) { dist -= lens[i]; i++ }
            val a = pts[i]; val b = pts[i + 1]; val k = if (lens[i] <= 0) 1.0 else (dist / lens[i]).coerceIn(0.0, 1.0)
            VScreenShell.finger(d.displayId, "move", 0, (a.first + (b.first - a.first) * k).roundToInt(), (a.second + (b.second - a.second) * k).roundToInt())
            if (f >= 1.0) break
            Thread.sleep(16)
        }
        Thread.sleep(120)
        VScreenShell.finger(d.displayId, "up", 0, pts.last().first, pts.last().second)
        return tr("手指注入", "finger injection")
    } finally { runCatching { VScreenShell.finger(d.displayId, "cancel", 0, -1, -1) } }
}

/** Main-screen facts for 【注意】: its top activity or its task list changed during the segment. */
internal fun VScreenOffloadHandler.mainCheckLines(before: String?, tasksBefore: Set<Int>?): String? {
    val after = mainTop()
    val lost = mainTasksLost(tasksBefore)
    if (before == after && lost.isEmpty()) return null
    return tr("主屏在这段前后变了（前台 $before → $after", "The main screen changed during this segment (front app $before → $after") + (if (lost.isNotEmpty()) tr("；主屏任务 ${lost.joinToString(",")} 不见了", "; main-screen tasks ${lost.joinToString(",")} disappeared") else "") +
        tr("）。可能是用户自己切的，也可能被打扰了：停下，先告诉用户", "). The user may have switched it, or something interrupted: stop and tell the user first")
}

// ── the command ──────────────────────────────────────────────────────

/** `act ["<steps>"] [--stop C] [--watch T] [--paused] [--keep notify|wake] [--for T] [--save NAME] [--confirmed]` */
internal fun VScreenOffloadHandler.act(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val body = args.positional.joinToString(" ").trim()
    val steps = if (body.isEmpty()) emptyList() else Steps.parseAll(body)
    val stop = args.get("stop")?.let { Steps.conds(it) }.orEmpty()
    val keep = args.get("keep")?.lowercase()?.also {
        if (it !in setOf("notify", "wake")) throw ActionError("INVALID_ARGS", tr("--keep notify（通知用户）或 --keep wake（发事件给调用方，用 events 取）", "--keep notify (notifies the user) or --keep wake (sends an event to the caller, collect with events)"))
        if (stop.isEmpty()) throw ActionError("INVALID_ARGS", tr("--keep 盯的是 --stop 的条件：一起写，如 --stop \"text:到货\" --keep notify", "--keep watches the --stop condition: write them together, e.g. --stop \"text:In stock\" --keep notify"))
    }
    val watch = args.getLong("watch")?.let { bounded("watch", it, 0L, 120_000L) }
    val d = BackgroundOperation.acquire(s, create = false)
    VScreenPerception.ensure(d)
    val out = runSegment(s, d, steps, SegmentOpts(stop, watch, args.hasFlag("paused"), args.hasFlag("confirmed")))
    val done = ArrayList(out.done)
    if (steps.isNotEmpty() && body.isNotEmpty()) args.get("save")?.let { name ->
        val app = workApp(s, d) ?: throw ActionError("NO_APP", tr("副屏上没有应用，段存不到哪里", "No app on the screen, so there's nowhere to save the segment"))
        VScreenMap.saveSegment(app, name.trim(), body)
        done += tr("已存为 $app 的段“${name.trim()}”（以后 use ${name.trim()}）", "Saved as segment \"${name.trim()}\" of $app (use ${name.trim()} from now on)")
    }
    keep?.let { k -> startKeep(s, d, args.get("stop")!!, stop, k, args.getLong("for") ?: 3_600_000L)?.let { out.attention += it } }
    takeAdjusted().forEach { out.attention += tr("参数被调整：$it", "options adjusted: $it") }
    // The user's "blocked" apps are never read or shown, whichever way the segment got there.
    val pkgNow = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()
    if (pkgNow != null && VScreenStore.ruleFor(pkgNow) == VScreenStore.Rule.BLOCK) return blockedBlocks(done, out.attention, pkgNow)
    return fourBlocks(s, d, done, out.attention, out.start, out.actionMs, "act", fullList = steps.isEmpty())
}

/** 【现在】 of an app the user blocked: nothing of it is read or shown. */
internal fun blockedBlocks(done: List<String>, attention: List<String>, pkg: String): NativeOffloadResult {
    val sb = StringBuilder(Blocks.done + "\n")
    done.forEach { sb.append(it).append('\n') }
    sb.append("\n" + Blocks.meanwhile + "\n" + tr("（不读取：用户禁止后台操作 $pkg）", "(not read: the user blocked background operation of $pkg)") + "\n\n" + Blocks.now + " $pkg\n" +
        tr("（不读取：用户禁止后台操作这个应用。key back 离开或 stop）", "(not read: the user blocked background operation of this app. key back to leave, or stop)") + "\n\n" + Blocks.notes + "\n")
    (attention + tr("APP_BLOCKED：副屏上是 $pkg，用户禁止后台操作它", "APP_BLOCKED: the screen shows $pkg, which the user blocked from background operation")).distinct().forEach { sb.append("- ").append(it).append('\n') }
    return NativeOffloadResult(0, sb.toString())
}

private var keepSeq = 0

/** Start watching [conds] after the turn. Returns a line for 【注意】. */
private fun VScreenOffloadHandler.startKeep(sid: String, d0: VScreenDisplay, spec: String, conds: List<Cond>, how: String, forMs0: Long): String? {
    val forMs = bounded("for", forMs0, 10_000L, 12 * 3_600_000L)
    val app = runCatching { VScreenShell.topPackage(d0.displayId) }.getOrNull()
    val name = tr("盯", "watch") + synchronized(VScreenRules) { ++keepSeq }
    val host = object : VScreenRules.Host {
        private var probe: Probe? = null
        private var on: VScreenDisplay? = null
        override fun check(l: VScreenRules.Live, d: VScreenDisplay): String? {
            if (on !== d) { probe?.close(); probe = Probe(this@startKeep, d, conds); on = d }
            VScreenAwake.ensure(d)
            VScreenPerception.ensure(d)
            d.requestFreshFrame()
            val hit = probe!!.poll()
            if (hit != null) { probe?.close(); probe = null; on = null }
            return hit
        }
    }
    val spec2 = VScreenRules.Spec(name, sid, spec, app, 1_000L, forMs, wake = how == "wake", notify = how == "notify")
    val l = try { VScreenRules.start(spec2, host) } catch (e: IllegalStateException) { return tr("KEEP_LIMIT：${e.message}", "KEEP_LIMIT: ${e.message}") }
    val warn = if (how == "notify") VScreenHandoff.notifyBlocked(context)?.let { tr("；但通知发不出去（$it）：请用户打开 Agent-VScreen 的通知，或改用 --keep wake", "; but notifications can't be sent ($it): ask the user to allow Agent-VScreen notifications, or use --keep wake") } else null
    return tr("本轮之后继续盯「${l.spec.name}」：$spec，最长 ${forMs / 60_000} 分钟，看到就${if (how == "wake") "发一条事件（vscreen events --wait 取）" else "通知用户"}，然后结束",
        "After this turn, keep watching \"${l.spec.name}\": $spec, for up to ${forMs / 60_000} minutes; when seen it ${if (how == "wake") "sends an event (collect with vscreen events --wait)" else "notifies the user"}, then ends") +
        (warn ?: "") + tr("。副屏会保持在这一页；keep 查看，keep off ${l.spec.name} 取消。告诉用户你在盯什么、多久", ". The screen stays on this page; keep lists watches, keep off ${l.spec.name} cancels. Tell the user what you are watching and for how long")
}

// ── launch ──────────────────────────────────────────────────────────

/** `launch <app> [--landscape|--portrait] [--restart]`: open it, look, return the four blocks. */
internal fun VScreenOffloadHandler.launchCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val q = args.positional.joinToString(" ").trim().ifEmpty { throw ActionError("INVALID_ARGS", tr("launch <应用名或包名>", "launch <app name or package>")) }
    val pkg = VScreenKnowledge.resolveApp(q) ?: throw ActionError("NO_APP", tr("没找到“$q”：apps $q 看看已安装的应用和包名", "\"$q\" not found: apps $q lists installed apps and packages"))
    if (VScreenStore.ruleFor(pkg) == VScreenStore.Rule.BLOCK) throw ActionError("APP_BLOCKED", tr("用户禁止后台操作 $pkg（Agent-VScreen › 应用限制），不要绕过", "The user blocked background operation of $pkg (Agent-VScreen › App rules); don't work around it"))
    val start = System.currentTimeMillis()
    val t0 = SystemClock.elapsedRealtime()
    val main0 = mainTop(); val tasks0 = mainTaskIds()
    val orient = when { args.hasFlag("landscape") -> true; args.hasFlag("portrait") -> false; else -> null }
    val facts = doLaunch(s, pkg, orient, args.hasFlag("restart"))
    val d = BackgroundOperation.acquire(s, create = false)
    VScreenPerception.ensure(d)
    val done = ArrayList(facts.done)
    // What the agent wrote down about this app before.
    val m = VScreenMap.load(pkg)
    if (m.notes.isNotEmpty()) done += tr("你以前记下的（note）：", "What you noted before (note):") + m.notes.takeLast(12).joinToString(tr("；", "; ")) { "${it.id}. ${it.text}" }
    if (m.segments.isNotEmpty()) done += tr("保存的段（use 名字）：", "Saved segments (use <name>):") + m.segments.joinToString(tr("；", "; ")) { "${it.name} = ${it.steps.take(80)}" }
    val p = m.profile
    if (p["pause"] != null && p["resume"] != null) done += tr("回合制：每段 act 先“${p["resume"]}”继续，末尾“${p["pause"]}”暂停", "turn-based: every act first resumes with \"${p["resume"]}\" and pauses at the end with \"${p["pause"]}\"")
    else if (p["realtime"] == "true") done += tr("记为实时应用（不带步骤的 act 看 1 秒）", "noted as a real-time app (act without steps watches 1 s)")
    val attention = facts.attention
    mainCheckLines(main0, tasks0)?.let { attention += it }
    takeAdjusted().forEach { attention += tr("参数被调整：$it", "options adjusted: $it") }
    return fourBlocks(s, d, done, attention, start, SystemClock.elapsedRealtime() - t0, "launch", fullList = true)
}
