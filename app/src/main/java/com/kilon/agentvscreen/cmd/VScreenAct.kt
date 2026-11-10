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

// [vscreen] `act` — one stretch: 0..N steps, each with a fact-only wait, stop conditions checked every 0.1 s,
// optional watching afterwards, and (for apps whose pause / resume steps were written down) resume → steps → watch →
// pause, so a realtime game becomes turn-based. It returns the four blocks (VScreenReport). With --keep the stop
// conditions go on being watched after the turn (VScreenRules) and post an event to the caller or notify the user.

/** Watches [conds] on [d]; the baseline (for change:) is the picture when it was made. */
internal class Probe(private val h: VScreenOffloadHandler, private val d: VScreenDisplay, val conds: List<Cond>) {
    private val since = SystemClock.elapsedRealtime()
    private var base: Bitmap? = d.latestFrame()?.let { VScreenPerception.small(it) }
    private var prev: Bitmap? = null
    private val stillMs = HashMap<Int, Long>()
    /** stable: (time, share of sampled pixels that differ from the previous look) of the last looks, per condition. */
    private val motion = HashMap<Int, ArrayDeque<Pair<Long, Double>>>()
    private var lastPoll = 0L
    private var appAt = 0L
    private var appNow: List<String> = emptyList()
    private val wantsText = conds.any { it.kind == "text" || it.kind == "gone" }

    fun close() { base?.recycle(); base = null; prev?.recycle(); prev = null }

    /** Re-take the baseline (a kept watch starts from the moment the turn ended). */
    fun rebase() { base?.recycle(); base = d.latestFrame()?.let { VScreenPerception.small(it) }; stillMs.clear(); motion.clear() }

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
        runCatching { BackgroundOperation.a11yShows(d.displayId, VScreenOcr::norm, n) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val r = VScreenPerception.reading(d.displayId, since, 0) ?: return null
        return r.items.firstOrNull { VScreenOcr.norm(it.text).contains(n) }?.text?.take(40)
    }

    /** True only when a reading taken after this probe began does not hold [q] (and the accessibility tree does not either). */
    private fun gone(q: String): Boolean {
        val n = VScreenOcr.norm(q)
        if (!runCatching { BackgroundOperation.a11yShows(d.displayId, VScreenOcr::norm, n) }.getOrNull().isNullOrEmpty()) return false
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
            "stable" -> tr("${where(c)}还在变（不是匀速的小动画）", "${where(c)} is still changing (not a steady small animation)")
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
                    "stable" -> {
                        // 1.2: "settled" is not "not one pixel moves". A page with a spinner, a blinking cursor, a
                        // banner that keeps sliding never became still, so every default wait ran to its 3 s limit.
                        // Settled = fully still for 0.5 s (as before), OR for at least 1.2 s the motion has been small
                        // and even (each look differs from the last by about the same small share: a loop, not a
                        // page arriving). A page that is loading moves in bursts and is not even.
                        val p = prev
                        val q = motion.getOrPut(i) { ArrayDeque() }
                        if (p != null && cur != null) {
                            val df = diff(p, cur, c)
                            q.addLast(now to df); while (q.isNotEmpty() && now - q.first().first > 1_000L) q.removeFirst()
                            if (df <= 0.004) stillMs[i] = (stillMs[i] ?: 0L) + dt else stillMs[i] = 0L
                        }
                        val s = stillMs[i] ?: 0L
                        when {
                            s >= 500L && now - since >= 300L -> tr("${where(c)}连续 ${s}ms 没变", "${where(c)} unchanged for ${s}ms")
                            now - since >= 1_200L && q.size >= 5 && steady(q.map { it.second }) -> tr("${where(c)}只剩匀速的小变动（约 ${(q.maxOf { it.second } * 100).roundToInt().coerceAtLeast(1)}% 的取样点在循环变化）", "${where(c)} is left with only a small steady motion (about ${(q.maxOf { it.second } * 100).roundToInt().coerceAtLeast(1)}% of sample points loop)")
                            else -> null
                        }
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

/** Small and even motion: every look differs from the one before by a similar, small share (a loop, not an arrival). Pure. */
internal fun steady(diffs: List<Double>): Boolean {
    if (diffs.size < 5) return false
    val mx = diffs.max(); val mn = diffs.min()
    return mx <= 0.08 && mx <= mn * 1.6 + 0.003
}

/** How one wait ended: [met] = the condition's fact, [stopped] = a stop condition's fact, else it timed out. */
internal class WaitOut(val met: String?, val stopped: String?, val ms: Long)

/**
 * Wait on [probe] (null = just time) up to [maxMs], checking [stop] too. 1.2: frame-driven: it looks when a new frame
 * arrives (at most every 33 ms) and at least every 100 ms, instead of on a fixed 100 ms clock; it ends at once when the
 * caller went away (CANCELLED) or the screen is gone (CONNECTION_LOST), instead of waiting out its time on a dead screen.
 */
internal fun waitOn(d: VScreenDisplay, probe: Probe?, maxMs: Long, stop: Probe?): WaitOut {
    val t0 = SystemClock.elapsedRealtime()
    val call = Call.current()
    var seen = d.version
    while (true) {
        val tick = SystemClock.elapsedRealtime()
        d.awaitNewFrame(seen, 100)
        seen = d.version
        (33 - (SystemClock.elapsedRealtime() - tick)).let { if (it > 0) Thread.sleep(it) }
        val el = SystemClock.elapsedRealtime() - t0
        if (call?.cancelled == true) throw ActionError("CANCELLED", tr("调用方断开或取消了，这段停在这里", "The caller disconnected or cancelled; the stretch stops here"))
        connectionLost(d)?.let { throw ActionError("CONNECTION_LOST", it) }
        if (BackgroundOperation.state.value.takeoverSessionId != null) throw ActionError("USER_TAKEOVER", tr("用户接管了副屏，这段停在这里", "The user took over the screen; the stretch stops here"))
        if (BackgroundOperation.state.value.userStopped) throw ActionError("USER_STOPPED", BackgroundOperation.userStoppedMessage())
        stop?.poll()?.let { return WaitOut(null, it, el) }
        probe?.poll()?.let { return WaitOut(it, null, el) }
        if (el >= maxMs) return WaitOut(null, null, el)
    }
}

/**
 * Options of one stretch. [fast]: no settling wait between actions (the last step still waits). [bare]: the app's
 * before / after steps are skipped.
 */
internal class SegmentOpts(val stop: List<Cond>, val watchMs: Long?, val bare: Boolean, val fast: Boolean)

/** A step that was not done, and why (it ends the stretch; the reason goes to the notes). */
internal class StepRefused(val code: String, message: String) : Exception(message)

/**
 * What a stretch did. [outcome]: done | stopped (a stop condition fired) | missed (a step was done but what it expected
 * never came) | refused (a step was not done) | interrupted (takeover, stop, lost connection, cancelled).
 */
internal class SegmentOut(val lines: List<String>, val steps: List<StepOut>, val notes: Notes, val actionMs: Long, val start: Long,
                          val outcome: String, val stoppedAt: String?)

private fun s1(ms: Long) = "${(ms / 100) / 10.0}s"

/** The app a session works on: the one it launched (pages of other packages included), else the one on top. */
internal fun VScreenOffloadHandler.workApp(sid: String, d: VScreenDisplay): String? =
    VScreenKnowledge.journey(sid) ?: runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()?.takeIf { it != context.packageName }

private val INTERRUPTS = setOf("USER_TAKEOVER", "USER_STOPPED", "CONNECTION_LOST", "CANCELLED", "NOT_CONNECTED", "BUSY", "NO_SCREEN")

/** Run a stretch of [steps] on the session's screen. Never throws for a step: a failed step ends the stretch and is said. */
internal fun VScreenOffloadHandler.runSegment(sid: String, d: VScreenDisplay, steps: List<Step>, o: SegmentOpts): SegmentOut {
    val start = System.currentTimeMillis()
    val t0 = SystemClock.elapsedRealtime()
    val lines = ArrayList<String>()
    val outs = ArrayList<StepOut>()
    val notes = Notes()
    val main0 = mainTop(); val tasks0 = mainTaskIds()
    VScreenAwake.ensure(d)?.let { notes += it }
    VScreenAwake.kick("act")
    VScreenPerception.ensure(d)
    val app = workApp(sid, d)
    val prof = if (o.bare) emptyMap() else app?.let { VScreenMap.profile(it) }.orEmpty()
    fun kept(key: String): List<Step>? = prof[key]?.let { body ->
        runCatching { Steps.parseAll(body) }.getOrElse {
            notes.add("INVALID_STEP", tr("这个应用记的 $key 步骤读不懂（${it.message?.take(80)}），这次没做：note $key 重写", "The $key steps kept for this app can't be parsed (${it.message?.take(80)}); skipped this time: rewrite them with note $key")); null
        }
    }
    val beforeSteps = kept("before"); val afterSteps = kept("after")
    val stop = if (o.stop.isNotEmpty()) Probe(this, d, o.stop) else null
    var beforeRan = false
    var stoppedBy: String? = null
    var stoppedAt: String? = null
    // A step whose expected result did not happen (an until that timed out, typing that did not land or could not be
    // checked) ends the stretch like a refused step: the later steps were written for a screen that is not there.
    var missed: String? = null
    var outcome = "done"
    var skipped = 0
    fun screenNow(): String = runCatching {
        val sn = snapshot(d)
        val words = sn.els.filter { !it.placeholder }.map { it.text.take(16) }.distinct().take(10)
        if (I18n.en) "Now: ${sn.activity?.substringAfterLast('/') ?: "front app unreadable"}" + if (words.isEmpty()) ", no text read on the screen" else ", visible: ${words.joinToString(", ") { "\"$it\"" }}"
        else "现在：${sn.activity?.substringAfterLast('/') ?: "前台读不到"}" + if (words.isEmpty()) "，画面上没有读到字" else "，能看到 ${words.joinToString("、") { "“$it”" }}"
    }.getOrDefault("")
    // Where the agent last tapped in this stretch: typing must go to a field there (BackgroundOperation.typeFact).
    var lastTap: Pair<Int, Int>? = null
    val ctxAct = ActCtx(sid)
    fun put(so: StepOut): StepOut { outs += so; lines += so.text; Call.current()?.report(so.text); return so }
    fun runOne(step: Step, label: String, depth: Int, quick: Boolean, own: Boolean) {
        if (stoppedBy != null || missed != null || outcome != "done") { if (own) { skipped++; outs += StepOut(label, step.raw, "skipped", "") }; return }
        if (step.kind == "use") {
            val name = step.text!!
            val seg = app?.let { VScreenMap.segment(it, name) } ?: throw StepRefused("NOT_FOUND", tr("没有叫“$name”的段（${app ?: "当前应用"}）：note 能看到保存过的段", "No segment named \"$name\" (${app ?: "current app"}): note lists the saved segments"))
            if (depth >= 3) throw StepRefused("INVALID_STEP", tr("段里 use 套用太深（最多 3 层）", "use is nested too deep in segments (at most 3 levels)"))
            val inner = runCatching { Steps.parseAll(seg.steps) }.getOrElse { throw StepRefused("INVALID_STEP", tr("段“$name”的步骤读不懂：${it.message}", "The steps of segment \"$name\" can't be parsed: ${it.message}")) }
            put(StepOut(label, step.raw, "done", tr("$label use $name（${inner.size} 步）", "$label use $name (${inner.size} steps)")))
            VScreenMap.markUsed(app, name)
            inner.forEachIndexed { i, st -> runOne(st, "$label.${i + 1}", depth + 1, quick, own) }
            return
        }
        if (step.kind == "sleep") {
            val w = waitOn(d, null, step.ms ?: 0L, stop)
            val so = StepOut(label, step.raw, "done", "").also { it.waitMs = w.ms }
            if (w.stopped != null) { stoppedBy = w.stopped; stoppedAt = label; so.status = "stopped"; so.text = tr("$label 等 ${s1(step.ms ?: 0)}：${s1(w.ms)} 时停止条件 ${w.stopped}", "$label wait ${s1(step.ms ?: 0)}: stop condition ${w.stopped} at ${s1(w.ms)}") }
            else so.text = tr("$label 等了 ${s1(w.ms)}", "$label waited ${s1(w.ms)}")
            put(so); return
        }
        if (step.kind == "wait") {
            val p = Probe(this, d, step.until!!)
            val want = step.until.joinToString(" or ") { it.raw }
            try {
                val w = waitOn(d, p, step.untilMs ?: Steps.WAIT_DEFAULT_MS, stop)
                val so = StepOut(label, step.raw, "done", "").also { it.waitedFor = want; it.waitMs = w.ms; it.waitMet = w.met != null }
                when {
                    w.stopped != null -> { stoppedBy = w.stopped; stoppedAt = label; so.status = "stopped"; so.text = tr("$label 等 $want：${s1(w.ms)} 时停止条件 ${w.stopped}", "$label wait $want: stop condition ${w.stopped} at ${s1(w.ms)}") }
                    w.met != null -> so.text = tr("$label 等到了 ${w.met}（${s1(w.ms)}）", "$label got ${w.met} (${s1(w.ms)})")
                    else -> {
                        val seenNow = p.now()
                        so.status = "missed"; so.code = "STEP_MISSED"
                        so.text = tr("$label 等 $want：${s1(w.ms)} 到时间，没等到（$seenNow）", "$label wait $want: time up at ${s1(w.ms)}, never came ($seenNow)")
                        missed = tr("第 $label 步 ${step.raw} 没等到（$seenNow）。", "Step $label ${step.raw} never came ($seenNow). ") + screenNow()
                    }
                }
                put(so)
            } finally { p.close() }
            return
        }
        // An action.
        stop?.poll()?.let { stoppedBy = it; stoppedAt = label; put(StepOut(label, step.raw, "stopped", tr("$label 没做（${step.raw}）：停止条件 $it 已经满足", "$label not done (${step.raw}): stop condition $it already met"))); return }
        val conds = step.until ?: if (quick) null else listOf(Cond("stable", "stable"))
        val probe = conds?.let { Probe(this, d, it) }
        // The picture before the action, to say afterwards when the action changed nothing at all.
        val before = if (step.until == null && !quick && step.kind != "type") d.latestFrame()?.let { VScreenPerception.small(it) } else null
        try {
            val done1 = doAction(sid, d, step, ctxAct, lastTap)
            val fact = done1.text
            lastTap = if (step.kind == "tap" || step.kind == "press") ctxAct.point else if (step.kind == "type") lastTap else null
            val so = StepOut(label, step.raw, "done", "").also { it.fact = fact; it.point = ctxAct.point.takeIf { step.kind == "tap" || step.kind == "press" } }
            val maxMs = step.untilMs ?: if (step.until != null) Steps.UNTIL_DEFAULT_MS else 3_000L
            if (done1.miss != null) {
                so.status = "missed"; so.code = done1.code ?: "STEP_MISSED"; so.text = "$label ${step.raw} → $fact"
                missed = tr("第 $label 步 ${step.raw}：${done1.miss}", "Step $label ${step.raw}: ${done1.miss}")
                put(so); return
            }
            if (probe == null) { so.text = "$label ${step.raw} → $fact"; put(so); Thread.sleep(80); return }
            val w = waitOn(d, probe, maxMs, stop)
            so.waitedFor = conds.joinToString(" or ") { it.raw }; so.waitMs = w.ms; so.waitMet = w.met != null
            val waitTxt = when {
                w.stopped != null -> { stoppedBy = w.stopped; stoppedAt = label; so.status = "stopped"; tr("${s1(w.ms)} 时停止条件 ${w.stopped}", "stop condition ${w.stopped} at ${s1(w.ms)}") }
                w.met != null -> tr("等到 ${w.met}（${s1(w.ms)}）", "got ${w.met} (${s1(w.ms)})")
                step.until == null -> tr("等画面稳定：${s1(w.ms)} 到时间，画面还在变", "waiting for the screen to settle: time up at ${s1(w.ms)}, still changing")
                else -> {
                    val seenNow = probe.now()
                    so.status = "missed"; so.code = "STEP_MISSED"
                    missed = tr("第 $label 步 ${step.raw} 做了，但没等到 ${so.waitedFor}（$seenNow）。", "Step $label ${step.raw} was done, but ${so.waitedFor} never came ($seenNow). ") + screenNow()
                    tr("等 ${so.waitedFor}：${s1(w.ms)} 到时间，没等到（$seenNow）", "wait ${so.waitedFor}: time up at ${s1(w.ms)}, never came ($seenNow)")
                }
            }
            // 1.2: an action after which not one sampled pixel differs is said: the usual cause is a tap that hit nothing.
            if (before != null && so.status == "done") d.latestFrame()?.let { VScreenPerception.small(it) }?.let { after ->
                val same = before.width == after.width && before.height == after.height && VPixels.regionDiff(before, after, Rect(0, 0, after.width, after.height)) in 0.0..0.0005
                after.recycle()
                so.changed = !same
                if (same) notes.add("NO_CHANGE", tr("第 $label 步（${step.raw}）之后画面没有任何变化：可能没点中、控件没响应，或变化不在画面上。看图核对这一步有没有生效", "After step $label (${step.raw}) nothing on the screen changed: it may have missed, the control may not respond, or the effect is not visible. Check the picture to see whether the step took effect"))
            }
            so.text = tr("$label ${step.raw} → $fact；$waitTxt", "$label ${step.raw} → $fact; $waitTxt") + if (so.changed == false) tr("；画面没有变化", "; the screen did not change") else ""
            put(so)
        } finally { probe?.close(); before?.recycle() }
    }
    fun runList(list: List<Step>, prefix: String, quickAll: Boolean, lastWaits: Boolean, own: Boolean) {
        list.forEachIndexed { i, st ->
            val last = i == list.lastIndex
            runOne(st, "$prefix${i + 1}", 0, quick = quickAll && !(last && lastWaits), own = own)
        }
    }
    fun fail(code: String, message: String, at: String) {
        notes.add(code, message)
        outcome = if (code in INTERRUPTS) "interrupted" else "refused"
        if (code == "REREAD_REQUIRED" || code == "HOST_RESTARTED") BackgroundOperation.acknowledgeReread(sid)
        lines += tr("（从这里起没做：$code）", "(not done from here: $code)")
        outs.lastOrNull { it.status == "skipped" && it.n == at } ?: outs.add(StepOut(at, "", "refused", "").also { it.code = code })
    }
    val acting = steps.isNotEmpty() || o.watchMs != null
    try {
        try {
            if (beforeSteps != null && acting) {
                runList(beforeSteps, tr("前置 ", "before "), quickAll = true, lastWaits = true, own = false)
                beforeRan = true
            }
            runList(steps, "", quickAll = o.fast, lastWaits = true, own = true)
        } catch (e: StepRefused) {
            fail(e.code, e.message ?: e.code, (outs.count { it.status != "skipped" && !it.n.contains(' ') } + 1).toString())
        }
        if (steps.isEmpty() && !o.fast && stoppedBy == null && outcome == "done") {
            val p = Probe(this, d, listOf(Cond("stable", "stable")))
            try {
                val w = waitOn(d, p, 3_000L, stop)
                lines += when {
                    w.stopped != null -> { stoppedBy = w.stopped; tr("看了一眼：${s1(w.ms)} 时停止条件 ${w.stopped}", "looked: stop condition ${w.stopped} at ${s1(w.ms)}") }
                    w.met != null -> tr("看了一眼（画面稳定，${s1(w.ms)}）", "looked (screen settled, ${s1(w.ms)})")
                    else -> tr("看了一眼（3 秒内画面一直在变，没等到稳定；有没有明显变化看【这段时间】）", "looked (the picture kept changing for 3 s, never settled; see [Meanwhile] for clear changes)")
                }
            } finally { p.close() }
        }
        val watch = o.watchMs ?: 0L
        if (watch > 0 && stoppedBy == null && missed == null && outcome == "done") {
            d.boost(watch + 1_000)
            val w = waitOn(d, null, watch, stop)
            lines += if (w.stopped != null) { stoppedBy = w.stopped; tr("观察 ${s1(watch)}：${s1(w.ms)} 时停止条件 ${w.stopped}", "watch ${s1(watch)}: stop condition ${w.stopped} at ${s1(w.ms)}") } else tr("观察了 ${s1(w.ms)}", "watched ${s1(w.ms)}")
        }
    } catch (e: ActionError) {
        fail(e.code, e.message ?: e.code, (outs.count { it.status != "skipped" } + 1).toString())
        connectionLost(d)?.let { notes.add("CONNECTION_LOST", it) }
    } finally {
        stop?.close()
        if (afterSteps != null && acting && (beforeRan || beforeSteps == null) && connectionLost(d) == null && outcome != "interrupted") {
            // The after steps run even when the stretch stopped early (a game must not keep running while the caller thinks).
            val m = missed; val oc = outcome; val sb = stoppedBy; missed = null; outcome = "done"; stoppedBy = null
            runCatching { runList(afterSteps, tr("收尾 ", "after "), quickAll = true, lastWaits = true, own = false) }
                .onFailure { notes.add((it as? ActionError)?.code ?: (it as? StepRefused)?.code ?: "OPERATION_FAILED", tr("收尾步骤没做完：${it.message}", "The after steps did not finish: ${it.message}")) }
            missed?.let { notes.add("STEP_MISSED", tr("收尾步骤没达到预期：$it。看图核对；不对就 note after 改步骤", "The after steps missed their expectation: $it. Check the picture; if wrong, fix them with note after")) }
            missed = m; outcome = oc; stoppedBy = sb
        }
    }
    missed?.let { m ->
        outcome = "missed"
        notes.add(outs.lastOrNull { it.status == "missed" }?.code ?: "STEP_MISSED", tr("没达到预期，这段停在这里：$m", "Expectation missed, the stretch stops here: $m") + (if (skipped > 0) tr("。后面 $skipped 步没做", ". The remaining $skipped steps were not done") else "") + tr("。看截图决定下一步", ". Look at the screenshot to decide what next"))
        if (skipped > 0) lines += tr("（后面 $skipped 步没做：上一步没达到预期）", "(the remaining $skipped steps were not done: the previous step missed its expectation)")
    }
    if (outcome == "done" && stoppedBy != null) outcome = "stopped"
    if (o.stop.isNotEmpty()) lines += if (stoppedBy != null) tr("停止条件触发：$stoppedBy", "stop condition fired: $stoppedBy") else tr("停止条件 ${o.stop.joinToString(" or ") { it.raw }}：这段里没出现", "stop condition ${o.stop.joinToString(" or ") { it.raw }}: did not occur in this stretch")
    if ((beforeSteps != null || afterSteps != null) && !acting) lines += tr("（这个应用记了前置/收尾步骤；这次没带步骤也没 --watch，所以没有做它们，画面保持原状态）", "(this app has before/after steps kept; this time there were no steps and no --watch, so they were not run and the screen stays as it was)")
    mainCheckLines(main0, tasks0)?.let { notes.add("MAIN_SCREEN_CHANGED", it) }
    val top = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()
    if (app != null && top != null && top != app && top != context.packageName) notes += tr("副屏前台现在是 $top，不是你打开的 $app", "The front app on the screen is now $top, not $app that you opened")
    return SegmentOut(lines, outs.filter { it.status != "refused" || it.code != null }, notes, SystemClock.elapsedRealtime() - t0, start, outcome, stoppedAt)
}

/** Per stretch: where the last tap went, the focus before it, and when the session last acted (for the #N freshness check). */
internal class ActCtx(val sid: String) {
    var point: Pair<Int, Int>? = null
    var focusBefore: String? = null
    var acted = false
}

/** A resolved target: the point, the words there, and (for #N) whether the element had moved. */
private class Target(val x: Int, val y: Int, val label: String?, val moved: String? = null)

private fun near(a: El, b: El): Boolean = Rect(a.box).apply { inset(-12, -12) }.contains(b.box.centerX(), b.box.centerY()) || Rect(b.box).apply { inset(-12, -12) }.contains(a.box.centerX(), a.box.centerY())

/**
 * Where a step goes. 1.2:
 *  - `#N` is checked against the screen as it is NOW when the list is not fresh (older than 3 s, or this stretch already
 *    acted): the element must still be there (same words, same place). Moved on the same page → the new place is used
 *    and said. Gone → STALE_ELEMENT, nothing is tapped. Before 1.2 `tap #5` tapped wherever #5 used to be.
 *  - words that match several different places are not guessed (before: "tapped the first"): AMBIGUOUS, with every
 *    place and its point. One tappable match among plain-text matches is not ambiguous: that one is meant.
 */
private fun VScreenOffloadHandler.target(sid: String, d: VScreenDisplay, t: StepTarget, ctx: ActCtx): Target = when (t) {
    is StepTarget.At -> {
        if (t.x !in 0 until d.width || t.y !in 0 until d.height)
            throw StepRefused("OUT_OF_BOUNDS", tr("(${t.x},${t.y}) 在副屏 ${d.width}x${d.height} 外面", "(${t.x},${t.y}) is outside the ${d.width}x${d.height} screen"))
        val label = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }?.list
            ?.filter { it.box.contains(t.x, t.y) }?.minByOrNull { it.box.width().toLong() * it.box.height() }?.label
        Target(t.x, t.y, label)
    }
    is StepTarget.Ref -> {
        val set = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }
            ?: throw StepRefused("NOT_FOUND", tr("还没有元素列表：先 act 看一眼再用 #编号", "No element list yet: look with act first, then use #numbers"))
        if (set.generation != d.generation) throw StepRefused("STALE_ELEMENT", tr("#${t.n} 是上一块副屏的列表（副屏重建过）：先 act 看一眼", "#${t.n} is from the previous screen's list (the screen was rebuilt): look with act first"))
        val e = set.list.firstOrNull { it.n == t.n } ?: run {
            // A number from an earlier list: its element left the screen (numbers are never reused, see numbered()).
            val was = Numbers.label("$sid@${d.generation}", t.n)
            if (was != null) throw StepRefused("STALE_ELEMENT", tr("#${t.n}（“${was.take(24)}”）是之前列表里的编号，它已经不在画面上了，没点。用最新【现在】里的编号，或用文字、坐标",
                "#${t.n} (\"${was.take(24)}\") is a number from an earlier list and that element is no longer on the screen; nothing was tapped. Use the numbers of the latest [Now], or text / coordinates"))
            throw StepRefused("NOT_FOUND", tr("没有 #${t.n} 这个编号（最新列表共 ${set.list.size} 个）", "There is no #${t.n} (the latest list has ${set.list.size})"))
        }
        val fresh = !ctx.acted && System.currentTimeMillis() - set.atMs <= 3_000L
        if (fresh) Target(e.box.centerX(), e.box.centerY(), e.label)
        else {
            val now = snapshot(d).els
            val same = now.filter { it.key == e.key }
            val here = same.firstOrNull { near(it, e) }
            when {
                here != null -> Target(here.box.centerX(), here.box.centerY(), here.label)
                // Wordless elements (icons) cannot be told apart by what they say: only the same place counts.
                same.size == 1 && !e.placeholder -> same[0].let { Target(it.box.centerX(), it.box.centerY(), it.label, tr("它从 (${e.box.centerX()},${e.box.centerY()}) 移到了这里", "it moved here from (${e.box.centerX()},${e.box.centerY()})")) }
                else -> throw StepRefused("STALE_ELEMENT", tr("#${t.n}“${e.label.take(24)}”已经不在 (${e.box.centerX()},${e.box.centerY()}) 了（页面变了" + (if (same.size > 1) "，现在有 ${same.size} 处一样的字" else "") + "），没点。看【现在】的新列表再用编号",
                    "#${t.n} \"${e.label.take(24)}\" is no longer at (${e.box.centerX()},${e.box.centerY()}) (the page changed" + (if (same.size > 1) "; the same text is now in ${same.size} places" else "") + "); nothing was tapped. Use the numbers of the new list in [Now]"))
            }
        }
    }
    is StepTarget.Words -> {
        val els = snapshot(d).els.filter { !it.placeholder }
        val q = VScreenOcr.norm(t.text)
        val exact = els.filter { VScreenOcr.norm(it.text) == q }
        val all = exact.ifEmpty { els.filter { VScreenOcr.norm(it.text).contains(q) } }
        // The same thing read twice (a node and the OCR words on it) is one place.
        val hits = ArrayList<El>().also { out -> all.forEach { h -> if (out.none { near(it, h) }) out += h } }
        if (hits.isEmpty()) throw StepRefused("NOT_FOUND", tr("画面上没找到“${t.text}”（控件文字和 OCR 都没有）", "\"${t.text}\" was not found on the screen (neither in widget text nor OCR)") + nearMiss(els, t.text))
        val pick = hits.singleOrNull() ?: hits.filter { it.clickable || it.editable }.singleOrNull()
            ?: throw StepRefused("AMBIGUOUS", tr("“${t.text}”在画面上有 ${hits.size} 处，没点：", "\"${t.text}\" is on the screen in ${hits.size} places; nothing was tapped: ") +
                hits.take(8).joinToString(tr("；", "; ")) { "“${it.text.take(24)}” (${it.box.centerX()},${it.box.centerY()})" + it.flags.let { f -> if (f.isNotEmpty()) " $f" else "" } } +
                tr("。改用坐标（tap x y）点你要的那一处", ". Tap the one you mean by its coordinates (tap x y)"))
        Target(pick.box.centerX(), pick.box.centerY(), pick.label)
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
    val close = els.filter { !it.placeholder }.map { it to score(it.text) }.filter { it.second >= 0.5 }
        .sortedByDescending { it.second }.take(3).map { "“${it.first.text.take(24)}”(${it.first.box.centerX()},${it.first.box.centerY()})" }
    val fields = els.filter { it.editable }.take(3).map { "${it.label.take(20)}(${it.box.centerX()},${it.box.centerY()})" }
    val parts = buildList {
        if (close.isNotEmpty()) add(tr("相近的字：", "similar text: ") + close.joinToString(tr("、", ", ")))
        if (fields.isNotEmpty()) add(tr("输入框：", "input boxes: ") + fields.joinToString(tr("、", ", ")))
    }
    return if (parts.isEmpty()) tr("。画面上也没有相近的字：看截图，用坐标点", ". No similar text on the screen either: look at the screenshot and tap by coordinates") else tr("。", ". ") + parts.joinToString(tr("；", "; ")) + tr("。文字会变的地方（轮播的提示词）用坐标点", ". Where the text changes (rotating hints), tap by coordinates")
}

/**
 * 1.2: a press on a money / irreversible button is confirmed by the user on the phone (Consent), not by a flag the
 * agent adds itself. The button's words and the app are shown to the user.
 */
private fun VScreenOffloadHandler.payGuard(sid: String, d: VScreenDisplay, label: String?, x: Int, y: Int) {
    val l = label ?: return
    // A button on the row of an input box (its "clear" ×) acts on the box, not on the user's data.
    val besideInput = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }?.list
        ?.any { it.editable && y in it.box.top..it.box.bottom } == true
    val kind = VScreenGame.commitKind(l, besideInput) ?: return
    val pkg = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull() ?: "?"
    val app = VScreenKnowledge.label(pkg) ?: pkg
    try {
        com.kilon.agentvscreen.host.Consent.require(context, "commit:$pkg:${VScreenOcr.norm(l).take(40)}",
            if (kind != "irreversible") tr("点付费/购买按钮“${l.take(20)}”", "tap the payment / purchase button \"${l.take(20)}\"") else tr("点不可撤销的按钮“${l.take(20)}”", "tap the irreversible button \"${l.take(20)}\""),
            tr("在 $app 里点 ($x,$y) 的“${l.take(30)}”", "tap \"${l.take(30)}\" at ($x,$y) in $app"), kind = kind)
    } catch (e: ActionError) { throw StepRefused(e.code, e.message ?: e.code) }
}

/** The user's per-app rule for the app on the screen (read-only: no taps / typing / gestures; blocked: only key back; ask: confirmed on the phone). */
private fun VScreenOffloadHandler.appRule(d: VScreenDisplay, step: Step) {
    if (VScreenStore.rules.value.isEmpty()) return
    val pkg = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull() ?: return
    val rule = VScreenStore.ruleFor(pkg) ?: return
    val hint = tr("这是用户在 Agent-VScreen › 应用限制 里定的，不要绕过（也不要从别的入口进同一个应用）", "The user set this in Agent-VScreen › App rules; don't work around it (and don't reach the same app through another entry)")
    val back = step.kind == "key" && step.text == "back"
    if (rule == VScreenStore.Rule.BLOCK && !back) throw StepRefused("APP_BLOCKED", tr("副屏上是 $pkg，用户禁止后台操作它：只能 key back 离开或 stop。$hint", "The screen shows $pkg, which the user blocked from background operation: only key back to leave, or stop. $hint"))
    if (rule == VScreenStore.Rule.READONLY && step.kind !in setOf("scroll", "key")) throw StepRefused("APP_READ_ONLY", tr("副屏上是 $pkg，用户设为只读：能看、滚动、返回，不能点击/输入/滑动。$hint", "The screen shows $pkg, which the user set to read-only: you can look, scroll and go back, but not tap / type / swipe. $hint"))
    if (rule == VScreenStore.Rule.ASK && step.kind !in setOf("scroll", "key")) {
        val app = VScreenKnowledge.label(pkg) ?: pkg
        try { com.kilon.agentvscreen.host.Consent.require(context, "app:$pkg", tr("在 $app 里操作", "act in $app"), tr("这一段从“${step.raw.take(40)}”开始；允许后 5 分钟内不再问", "this stretch starts with \"${step.raw.take(40)}\"; once allowed you are not asked again for 5 minutes"), reusable = true, kind = "app") }
        catch (e: ActionError) { throw StepRefused(e.code, e.message ?: e.code) }
    }
}

/** Null while the privileged connection and the screen are alive; otherwise what the agent must know and do. */
internal fun connectionLost(d: VScreenDisplay): String? {
    val st = BackgroundOperation.state.value
    if (!d.released && st.connection == BackgroundOperation.Connection.READY) return null
    return tr("后台连接断了（${st.connectionError ?: st.connection.name}），副屏和上面的应用已经没了，不是你的操作造成的。", "The background connection broke (${st.connectionError ?: st.connection.name}); the screen and the app on it are gone, and your actions did not cause it. ") +
        tr("告诉用户；连接恢复（Shizuku 在运行，vscreen fix not_connected）后重新 launch", "Tell the user; once the connection is back (Shizuku running, vscreen fix not_connected), launch again")
}

/** What one action did ([text]), and [miss] when its own effect could not be confirmed (it ends the stretch; [code] says which kind). */
internal class Done(val text: String, val miss: String? = null, val code: String? = null)

/** Do one action and say what was done (facts: where, with which method). */
private fun VScreenOffloadHandler.doAction(sid: String, d: VScreenDisplay, step: Step, ctx: ActCtx, tappedAt: Pair<Int, Int>? = null): Done {
    appRule(d, step)
    ctx.point = null
    var miss: String? = null
    var missCode: String? = null
    val pts = ArrayList<IntArray>()
    val fact = BackgroundOperation.agentAction(sid, null, isRead = false, describe = com.kilon.agentvscreen.host.Audit.redact(listOf(step.raw)).take(40)) { dd ->
        fun okOr(o: VScreenShell.Outcome) { if (!o.ok) throw ActionError(o.code, o.message) }
        fun there(t: Target) = (t.label?.let { tr("，那里是“${it.take(30)}”", ", which is \"${it.take(30)}\"") } ?: "") + (t.moved?.let { tr("（$it）", " ($it)") } ?: "")
        when (step.kind) {
            "tap" -> {
                val t = target(sid, dd, step.target!!, ctx)
                payGuard(sid, dd, t.label, t.x, t.y)
                ctx.focusBefore = com.kilon.agentvscreen.core.VScreenTyping.focusedFieldKey(dd.displayId)
                okOr(VScreenShell.tap(dd.displayId, t.x, t.y)); pts += intArrayOf(t.x, t.y); ctx.point = t.x to t.y
                tr("点了 (${t.x},${t.y})", "tapped (${t.x},${t.y})") + there(t)
            }
            "press" -> {
                val t = target(sid, dd, step.target!!, ctx)
                payGuard(sid, dd, t.label, t.x, t.y)
                okOr(VScreenShell.longPress(dd.displayId, t.x, t.y, (step.ms ?: 800L).toInt())); pts += intArrayOf(t.x, t.y); ctx.point = t.x to t.y
                tr("按住 (${t.x},${t.y}) ${step.ms}ms", "held (${t.x},${t.y}) ${step.ms}ms") + there(t)
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
                drag(dd, step.points, (step.ms ?: 600L).toInt())
                step.points.forEach { pts += intArrayOf(it.first, it.second) }
                tr("按住拖过 ", "held and dragged through ") + step.points.joinToString(" → ") { "(${it.first},${it.second})" } + ", ${step.ms}ms"
            }
            "scroll" -> {
                val c = step.target?.let { val t = target(sid, dd, it, ctx); t.x to t.y } ?: (dd.width / 2 to dd.height / 2)
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
                // A drag (eased, still at the end), not a fling: the list moves by what was dragged, every time.
                okOr(VScreenShell.drag(dd.displayId, x1, y1, x2, y2, 350, 80))
                pts += intArrayOf(x1, y1); pts += intArrayOf(x2, y2)
                tr("手指从 ($x1,$y1) 拖到 ($x2,$y2)", "finger dragged ($x1,$y1) → ($x2,$y2)")
            }
            "type" -> {
                val f = com.kilon.agentvscreen.core.VScreenTyping.typeFact(dd.displayId, step.text!!, step.append, tappedAt, if (tappedAt != null) ctx.focusBefore else null)
                val where = f.bounds?.let { b -> " [${b.left},${b.top},${b.right},${b.bottom}]" + (f.fieldId?.let { tr("（$it）", " ($it)") } ?: "") } ?: ""
                when {
                    f.field == null -> {
                        missCode = "TYPE_FAILED"
                        miss = if (f.checked) tr("没有获得焦点的输入框，字很可能没进去。先点输入框（【现在】里标“可输入”的），等它标“有焦点”再 type", "No input box has focus, so the text most likely did not go in. Tap the input box first (marked \"input\" in [Now]) and type once it is marked \"focused\"")
                            else tr("无障碍没开，确认不了字落在哪。看截图核对，字进去了再发后面的步骤", "Accessibility is off, so where the text landed can't be confirmed. Check the screenshot, and send the next steps once the text is in")
                        tr("按 ${f.method} 发了 ${step.text.length} 个字；", "sent ${step.text.length} characters by ${f.method}; ") + if (f.checked) tr("没有获得焦点的输入框", "no focused input box") else tr("读不到输入框", "input box unreadable")
                    }
                    f.password -> tr("输入 ${step.text.length} 个字（${f.method}）；密码框$where 现在 ${f.field.length} 个字符", "typed ${step.text.length} characters (${f.method}); password box$where now holds ${f.field.length} characters")
                    !f.field.contains(step.text) -> {
                        missCode = "TYPE_FAILED"
                        miss = tr("输入框$where 现在是“${f.field.take(60)}”，不含你输入的字（这个框可能不收这种输入，或焦点在别处）", "The input box$where now reads \"${f.field.take(60)}\" without your text (this box may not accept this kind of input, or focus is elsewhere)")
                        tr("按 ${f.method} 发了 ${step.text.length} 个字；输入框$where 现在是“${f.field.take(60)}”，不含你输入的字", "sent ${step.text.length} characters by ${f.method}; the input box$where now reads \"${f.field.take(60)}\" without your text")
                    }
                    else -> tr("输入 ${step.text.length} 个字（${f.method}）；输入框$where 现在是“${f.field.take(60)}”", "typed ${step.text.length} characters (${f.method}); the input box$where now reads \"${f.field.take(60)}\"")
                }
            }
            "key" -> {
                okOr(VScreenShell.key(dd.displayId, step.text!!))
                tr("按了 ${step.text}", "pressed ${step.text}")
            }
            else -> throw StepRefused("INVALID_STEP", tr("不是动作：${step.raw}", "not an action: ${step.raw}"))
        }
    }
    ctx.acted = true
    if (pts.isNotEmpty()) BackgroundOperation.actionLog(sid, 1).lastOrNull()?.trackN?.let { VScreenRecorder.setPoints(d.displayId, it, pts) }
    return Done(fact, miss, missCode)
}

private fun bounds(d: VScreenDisplay, pts: List<Pair<Int, Int>>) {
    pts.firstOrNull { it.first !in 0 until d.width || it.second !in 0 until d.height }?.let {
        throw StepRefused("OUT_OF_BOUNDS", tr("(${it.first},${it.second}) 在副屏 ${d.width}x${d.height} 外面", "(${it.first},${it.second}) is outside the ${d.width}x${d.height} screen"))
    }
}

/**
 * One finger down at the first point, still 150 ms (apps pick a thing up only after a hold), glide through the
 * points over [ms], still 120 ms, lift.
 */
private fun drag(d: VScreenDisplay, pts: List<Pair<Int, Int>>, ms: Int) {
    val lens = pts.zipWithNext { a, b -> hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) }
    val total = lens.sum().coerceAtLeast(1.0)
    d.boost(ms.toLong() + 3_000)
    val down = VScreenShell.finger(d.displayId, "down", 0, pts[0].first, pts[0].second).first
    if (!down.ok) throw ActionError(down.code, down.message)
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
    } finally { com.kilon.agentvscreen.host.quiet("drag-cancel") { VScreenShell.finger(d.displayId, "cancel", 0, -1, -1) } }
}

/** Main-screen facts for the notes: its top activity or its task list changed during the stretch. */
internal fun VScreenOffloadHandler.mainCheckLines(before: String?, tasksBefore: Set<Int>?): String? {
    val after = mainTop()
    val lost = mainTasksLost(tasksBefore)
    if (before == after && lost.isEmpty()) return null
    return tr("主屏在这段前后变了（前台 $before → $after", "The main screen changed during this stretch (front app $before → $after") + (if (lost.isNotEmpty()) tr("；主屏任务 ${lost.joinToString(",")} 不见了", "; main-screen tasks ${lost.joinToString(",")} disappeared") else "") +
        tr("）。可能是用户自己切的，也可能被打扰了：停下，先告诉用户", "). The user may have switched it, or something interrupted: stop and tell the user first")
}

// ── the command ──────────────────────────────────────────────────────

/** `act ["<steps>"] [--stop C] [--watch T] [--fast] [--bare] [--keep notify|wake] [--for T] [--save NAME]` */
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
    val out = runSegment(s, d, steps, SegmentOpts(stop, watch, bare = args.hasFlag("bare", "paused"), fast = args.hasFlag("fast")))
    val lines = ArrayList(out.lines)
    if (args.hasFlag("confirmed")) out.notes += tr("--confirmed 已不起作用：付费和不可撤销的步骤由用户在手机上确认", "--confirmed no longer has any effect: payment and irreversible steps are confirmed by the user on the phone")
    if (steps.isNotEmpty() && body.isNotEmpty() && out.outcome == "done") args.get("save")?.let { name ->
        val app = workApp(s, d) ?: throw ActionError("NO_APP", tr("副屏上没有应用，段存不到哪里", "No app on the screen, so there's nowhere to save the segment"))
        VScreenMap.saveSegment(app, name.trim(), body)
        lines += tr("已存为 $app 的段“${name.trim()}”（以后 use ${name.trim()}）", "Saved as segment \"${name.trim()}\" of $app (use ${name.trim()} from now on)")
    } else if (args.get("save") != null && steps.isNotEmpty()) out.notes += tr("这段没按预期做完，没有存成段", "The stretch did not go as expected, so it was not saved as a segment")
    keep?.let { k -> startKeep(s, d, args.get("stop")!!, k, args.getLong("for") ?: 3_600_000L, out.notes) }
    takeAdjusted().forEach { out.notes += tr("参数被调整：$it", "options adjusted: $it") }
    // The user's "blocked" apps are never read or shown, whichever way the stretch got there.
    val pkgNow = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()
    if (pkgNow != null && VScreenStore.ruleFor(pkgNow) == VScreenStore.Rule.BLOCK) return blockedBlocks(lines, out.notes, pkgNow)
    return fourBlocks(s, d, lines, out.notes, out.start, out.actionMs, "act", fullList = steps.isEmpty(), steps = out.steps, outcome = out.outcome, stoppedAt = out.stoppedAt)
}

/** The now block of an app the user blocked: nothing of it is read or shown. */
internal fun blockedBlocks(done: List<String>, notes: Notes, pkg: String): NativeOffloadResult {
    val sb = StringBuilder(Blocks.done + "\n")
    done.forEach { sb.append(it).append('\n') }
    notes.add("APP_BLOCKED", tr("副屏上是 $pkg，用户禁止后台操作它", "the screen shows $pkg, which the user blocked from background operation"))
    sb.append("\n" + Blocks.meanwhile + "\n" + tr("（不读取：用户禁止后台操作 $pkg）", "(not read: the user blocked background operation of $pkg)") + "\n\n" + Blocks.now + " $pkg\n" +
        tr("（不读取：用户禁止后台操作这个应用。key back 离开或 stop）", "(not read: the user blocked background operation of this app. key back to leave, or stop)") + "\n\n" + Blocks.notes + "\n")
    notes.list.forEach { sb.append("- ").append(it.line()).append('\n') }
    val data = org.json.JSONObject().put("outcome", "refused").put("done", org.json.JSONArray(done)).put("screen", org.json.JSONObject().put("app", pkg).put("blocked", true))
        .put("notes", org.json.JSONArray(notes.list.map { it.json() }))
    return NativeOffloadResult(1, sb.toString(), "APP_BLOCKED", data, softFail = true)
}

/** The condition evaluator of a kept watch (the same one act uses). */
internal fun VScreenOffloadHandler.keepHost(spec: String): VScreenRules.Host {
    val conds = Steps.conds(spec)
    return object : VScreenRules.Host {
        private var probe: Probe? = null
        private var on: VScreenDisplay? = null
        override fun check(l: VScreenRules.Live, d: VScreenDisplay): String? {
            if (on !== d) { probe?.close(); probe = Probe(this@keepHost, d, conds); on = d }
            VScreenAwake.ensure(d)
            VScreenPerception.ensure(d)
            d.requestFreshFrame()
            val hit = probe!!.poll()
            if (hit != null) { probe?.close(); probe = null; on = null }
            return hit
        }
    }
}

/** For the transport layer: the evaluator of a kept condition. */
object KeepHosts { internal fun VScreenOffloadHandler.host(cond: String): VScreenRules.Host = keepHost(cond) }

/** Start watching the stop condition after the turn; what was started (or why not) goes to [notes]. */
private fun VScreenOffloadHandler.startKeep(sid: String, d0: VScreenDisplay, spec: String, how: String, forMs0: Long, notes: Notes) {
    val forMs = bounded("for", forMs0, 10_000L, 12 * 3_600_000L)
    val app = runCatching { VScreenShell.topPackage(d0.displayId) }.getOrNull()
    val spec2 = VScreenRules.Spec(VScreenRules.nextName(), sid, spec, app, 1_000L, forMs, wake = how == "wake", notify = how == "notify")
    val l = try { VScreenRules.start(spec2, keepHost(spec)) } catch (e: IllegalStateException) { notes.add("KEEP_LIMIT", e.message ?: "KEEP_LIMIT"); return }
    val warn = if (how == "notify") VScreenHandoff.notifyBlocked(context)?.let { tr("；但通知发不出去（$it）：请用户打开 Agent-VScreen 的通知，或改用 --keep wake", "; but notifications can't be sent ($it): ask the user to allow Agent-VScreen notifications, or use --keep wake") } else null
    notes += tr("本轮之后继续盯「${l.spec.name}」：$spec，最长 ${forMs / 60_000} 分钟，看到就${if (how == "wake") "发一条事件（vscreen events --wait 取）" else "通知用户"}，然后结束",
        "After this turn, keep watching \"${l.spec.name}\": $spec, for up to ${forMs / 60_000} minutes; when seen it ${if (how == "wake") "sends an event (collect with vscreen events --wait)" else "notifies the user"}, then ends") +
        (warn ?: "") + tr("。副屏会保持在这一页；App 重启后盯守还在；keep 查看，keep off ${l.spec.name} 取消。告诉用户你在盯什么、多久", ". The screen stays on this page; the watch survives an app restart; keep lists watches, keep off ${l.spec.name} cancels. Tell the user what you are watching and for how long")
}

// ── launch ──────────────────────────────────────────────────────────

/** `launch <app> [--landscape|--portrait] [--restart]`: open it, look, return the four blocks. */
internal fun VScreenOffloadHandler.launchCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val q = args.positional.joinToString(" ").trim().ifEmpty { throw ActionError("INVALID_ARGS", tr("launch <应用名或包名>", "launch <app name or package>")) }
    val pkg = VScreenKnowledge.resolveApp(q) ?: throw ActionError("APP_NOT_FOUND", tr("没找到“$q”：apps $q 看看已安装的应用和包名", "\"$q\" not found: apps $q lists installed apps and packages"))
    if (VScreenStore.ruleFor(pkg) == VScreenStore.Rule.BLOCK) throw ActionError("APP_BLOCKED", tr("用户禁止后台操作 $pkg（Agent-VScreen › 应用限制），不要绕过", "The user blocked background operation of $pkg (Agent-VScreen › App rules); don't work around it"))
    val start = System.currentTimeMillis()
    val t0 = SystemClock.elapsedRealtime()
    val main0 = mainTop(); val tasks0 = mainTaskIds()
    val orient = when { args.hasFlag("landscape") -> true; args.hasFlag("portrait") -> false; else -> null }
    val facts = doLaunch(s, pkg, orient, args.hasFlag("restart"))
    val d = BackgroundOperation.acquire(s, create = false)
    VScreenPerception.ensure(d)
    val done = ArrayList(facts.done)
    // What was written down about this app before, with who wrote it and for which version of the app.
    val m = VScreenMap.load(pkg)
    val verNow = appVersion(pkg)
    if (m.notes.isNotEmpty()) done += tr("以前记下的（note）：", "Noted before (note): ") + m.notes.takeLast(12).joinToString(tr("；", "; ")) { n -> "${n.id}. ${n.text}" + noteOrigin(n, verNow) }
    if (m.segments.isNotEmpty()) done += tr("保存的段（use 名字）：", "Saved segments (use <name>): ") + m.segments.joinToString(tr("；", "; ")) { "${it.name} = ${it.steps.take(80)}" }
    val p = m.profile
    if (p["before"] != null || p["after"] != null) done += tr("每段 act 会自动做：", "Every act automatically runs: ") + listOfNotNull(p["before"]?.let { tr("前置“$it”", "before \"$it\"") }, p["after"]?.let { tr("收尾“$it”", "after \"$it\"") }).joinToString(tr("，", ", ")) + tr("（--bare 跳过）", " (--bare skips them)")
    val notes = facts.notes
    mainCheckLines(main0, tasks0)?.let { notes.add("MAIN_SCREEN_CHANGED", it) }
    takeAdjusted().forEach { notes += tr("参数被调整：$it", "options adjusted: $it") }
    return fourBlocks(s, d, done, notes, start, SystemClock.elapsedRealtime() - t0, "launch", fullList = true)
}

/** versionName of [pkg] (null when it cannot be read). */
internal fun VScreenOffloadHandler.appVersion(pkg: String): String? = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()

/** "(by X, for app 1.2; the app is now 1.3)" — only what differs from the reader's own situation is said. */
internal fun noteOrigin(n: VScreenMap.Note, verNow: String?): String {
    val me = Call.current()?.tokenName
    val parts = buildList {
        if (n.by != null && n.by != me) add(tr("${n.by} 记的", "by ${n.by}"))
        if (n.appVersion != null && verNow != null && n.appVersion != verNow) add(tr("记于应用 ${n.appVersion} 版，现在是 $verNow，可能已过时", "kept for app version ${n.appVersion}, now $verNow: may be outdated"))
    }
    return if (parts.isEmpty()) "" else tr("（${parts.joinToString("，")}）", " (${parts.joinToString(", ")})")
}
