package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.trPair
import android.content.Context
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.host.UserAsk
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenMap
import com.kilon.agentvscreen.steps
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

// [vscreen] 1.3 示教: the user shows it once on the background screen; it is written down as the same steps an agent
// writes (`act`), so an agent can run it again with `use NAME`.
//
// What the user did is recorded as facts: where the finger landed, how it moved, how long it stayed; what was typed;
// which key. Each event also takes a reading of the screen (the accessibility words and the front app) at the moment
// it starts. A step's target and its `until` come from those readings, never from guesses:
//  - a tap on words that are on the screen once becomes `tap 词` (survives layout changes); otherwise `tap x y`;
//  - what the next reading shows that this one did not (new words, another app, words gone) becomes `until …`, so the
//    replay waits for the same result instead of a fixed time, and stops when it does not come;
//  - typed text becomes a parameter `{1}` by default (the same steps then search for anything); a password is never kept.

/** One reading of the screen: the front app and the elements. */
/** One reading of the screen while recording; [act] = the activity in front (a new one is a new page, as in replay). */
internal class TeachSnap(val pkg: String?, val els: List<El>, val act: String? = null)

/** One thing the user did. */
internal sealed class TeachEv {
    abstract val t: Long
    /** The screen when it started (read on a worker; null when it could not be read). */
    @Volatile var before: TeachSnap? = null
    /** 1.3.0-beta19 (rainbow): a small picture of the screen at the same moment, for the lesson's contact sheet. */
    @Volatile var pic: TeachPic? = null
    @Volatile internal var pending: Future<*>? = null

    class Touch(override val t: Long, val x: Int, val y: Int) : TeachEv() {
        val path = ArrayList<IntArray>().apply { add(intArrayOf(x, y, 0)) }
        @Volatile var end = -1L
        @Volatile var cancelled = false
    }
    /** [whole]: the field now reads [text] (the full screen box mirrors the field); otherwise [text] was added. */
    class Typed(override val t: Long, val text: String, val whole: Boolean = false) : TeachEv()
    class Key(override val t: Long, val name: String) : TeachEv()
    /** 1.4.0-beta11: "让 agent 看一下" pressed while recording: the agent checks here before the lesson goes on. */
    class Check(override val t: Long, val what: String) : TeachEv()
}

/** One step written from what the user did, before the user reviews it. */
internal data class TeachLine(
    /** The step without its until; for typing, the text is in [typed]. */
    val head: String,
    val until: String?,
    /** In words, for the review page: what it was and why the step is written so. */
    val why: String,
    val kind: String,
    val typed: String? = null,
    val secret: Boolean = false,
    /** Problems the user may want to fix (nothing changed after it, a target by coordinates). */
    val warn: String? = null,
    /** 1.3.0-beta18: where a tap landed (screen pixels), for a tap that has to follow a parameter (see [Teach.build]). */
    val at: Pair<Int, Int>? = null,
    /** The words the tap was named by (null: by point). */
    val words: String? = null,
    /** 1.3.0-beta18: the screen when the tap began (which of several matches it was). */
    val snap: TeachSnap? = null,
    /** 1.3.0-beta19: a small picture of the screen before the step (the contact sheet). */
    val pic: TeachPic? = null,
    /** 1.4.0-beta5: [at] as the steps write it (shares of the screen when its size was known). */
    val pos: String? = null,
    /** 1.4.0-beta11: after this step the agent checks ([Teach.build] writes `check "<it>"`); null = no check. */
    val check: String? = null,
)

/** 1.3.0 (rainbow): a small picture of the background screen while a lesson was recorded; [scale] maps screen pixels onto it. */
internal class TeachPic(val bmp: android.graphics.Bitmap, val scale: Float) {
    companion object { const val W = 240 }
}

/**
 * 1.4.0-beta11 (DESIGN-SCREEN-LESSON-1.4): where the lesson is, in ONE value owned by [Teach]. Before, five fields
 * (rec, count, paused, prep, result) plus the pages each held a piece, and "stop recording" also opened the save page,
 * so 缩小, hand-back, release and closing the window all threw the user onto it. A lesson never opens a page: only the
 * user's own tap does (ui/Surface).
 */
internal sealed interface Lesson {
    /** The request it answers (null: a lesson of the user's own). */
    val askId: Int?
    data object None : Lesson { override val askId: Int? get() = null }
    /** 准备: the user holds the screen and gets to the start; nothing is written down. */
    data class Ready(override val askId: Int) : Lesson
    /** 示范: being written down. [askEnded]: the request ended meanwhile (said on the strip; saved as the user's own). */
    data class Recording(override val askId: Int?, val steps: Int, val paused: Boolean, val askEnded: String? = null) : Lesson
    /** Recording stopped; the steps are being written (a moment). */
    data class Ending(override val askId: Int?, val steps: Int) : Lesson
    /** 等保存: needs no screen. [screenGone]: the screen closed (cannot record again). */
    data class Review(val result: Teach.Result, val screenGone: Boolean) : Lesson { override val askId: Int? get() = result.askId }
}

internal object Teach {
    const val MAX = Steps.MAX_STEPS

    class Rec(val session: String, val displayId: Int, val purpose: String?, val askId: Int?, val name: String?) {
        val events = ArrayList<TeachEv>()
        val at = System.currentTimeMillis()
        /** 1.4.0-beta4: the page the lesson starts from (read when 开始示范 is tapped); `use` checks it first. */
        @Volatile var start: TeachSnap? = null
        @Volatile var startRead: Future<*>? = null
        /** 1.4.0-beta4: 暂停记录: the user's touches still reach the screen but are not written down. */
        @Volatile var paused = false
        /** 1.4.0-beta10: the question itself, kept so its answer reaches the agent whatever happened to the list. */
        val ask: UserAsk.Ask? = askId?.let { UserAsk.byId(it) }
        /** 1.4.0-beta11: the request ended while recording (why); the lesson is then saved as the user's own. */
        @Volatile var askEnded: String? = null
    }

    /** What the review page shows: the steps and where they came from. */
    class Result(val session: String, val purpose: String?, val askId: Int?, val name: String?, val app: String?, val lines: List<TeachLine>,
                 /** 1.3.0-beta19: the screen when recording stopped (last cell of the contact sheet). */
                 val endPic: TeachPic? = null,
                 /** 1.4.0-beta4: the starting page, as stored with the segment ([startJson]). */
                 val start: org.json.JSONObject? = null,
                 /** 1.4.0-beta10: the agent question it answers (key, token, session), for [deliver]. */
                 val ask: UserAsk.Ask? = null)

    @Volatile private var rec: Rec? = null
    private val _lesson = MutableStateFlow<Lesson>(Lesson.None)
    /** Where the lesson is (the only state the pages, the floating window and the home page read). */
    val lesson: StateFlow<Lesson> = _lesson.asStateFlow()
    /**
     * 1.4.0-beta10: a lesson recorded and not yet saved or discarded. It stays until the user decides (leaving the
     * review page no longer drops it); the floating window and the home page show "录好了，等你保存" for it.
     */
    val result: Result? get() = (_lesson.value as? Lesson.Review)?.result
    /** Steps recorded so far while recording; null = not recording. */
    val steps: Int? get() = (_lesson.value as? Lesson.Recording)?.steps
    private val prepId: Int? get() = (_lesson.value as? Lesson.Ready)?.askId
    private fun recState(r: Rec) { _lesson.value = Lesson.Recording(r.askId, r.events.size, r.paused, r.askEnded) }

    /**
     * 1.4.0-beta10 (rainbow: "彻底理顺"): ONE place says which agent question the lesson answers, from the lesson's own
     * state: the one being prepared for, else the one being recorded, else the one waiting to be saved. Called after
     * every change of that state. Before, three places held and released one global slot by hand, and any cancel let
     * go of a question still being answered.
     */
    private fun sync() { UserAsk.bind(_lesson.value.askId) }
    private var current: TeachEv.Touch? = null
    private val reader = Executors.newSingleThreadExecutor { r -> Thread(r, "vscreen-teach").apply { isDaemon = true } }

    /** For tests: a reading without the phone. */
    internal var snapSource: ((Int) -> TeachSnap?)? = null

    val recording: Boolean get() = rec != null
    /** The request the lesson being recorded answers (null: none, or a lesson of the user's own). */
    fun recAskId(): Int? = rec?.askId
    fun recordingFor(session: String) = rec?.session == session

    fun start(session: String, purpose0: String?, askId0: Int?, name0: String? = null): Boolean {
        val id = BackgroundOperation.state.value.displayId ?: return false
        // 1.4.0-beta8 (test beta6 P0-2: the agent kept getting ASK_PENDING after the user showed it and tapped Done):
        // a recording started without a request (the page's own record button, used because the request card was in
        // the way) still answers the agent's open teach request for this screen. A lesson exists to answer the
        // request in front of the user; one of the user's own only when nobody asked.
        val bound = if (askId0 != null) null else openRequestFor(session)
        val askId = askId0 ?: bound?.id
        val purpose = purpose0 ?: bound?.title
        val name = name0 ?: bound?.options?.firstOrNull()
        val r = Rec(session, id, purpose, askId, name)
        r.startRead = reader.submit { r.start = read(id) }
        synchronized(this) { rec = r; current = null; recState(r) }
        sync(); Draft.clear()
        return true
    }

    // 1.4.0-beta4 (rainbow): a lesson has two parts. 准备: the user has the screen and goes to where the lesson starts;
    // nothing is written down. 示范: from 开始示范 on, what the user does is recorded, from that page.
    /** Begin 示范 for the request being prepared (or a lesson of the user's own when none). */
    fun startPrepared(session: String): Boolean {
        val a = prepId?.let { UserAsk.byId(it) }?.takeIf { it.live }
        return start(session, a?.title, a?.id, a?.options?.firstOrNull())
    }

    /** The live teach request this screen's lesson answers: the one being prepared for, else the newest for [session]. */
    internal fun openRequestFor(session: String): UserAsk.Ask? =
        prepId?.let { UserAsk.byId(it) }?.takeIf { it.live && it.kind == UserAsk.Kind.TEACH }
            ?: UserAsk.liveOf(UserAsk.Kind.TEACH).filter { it.session == session }.maxByOrNull { it.at }

    fun cancelPrep() { if (_lesson.value is Lesson.Ready) { _lesson.value = Lesson.None; sync() } }

    /**
     * 1.4.0-beta6 (test: after the teach request was cancelled the screen page still said 示教准备中 with 开始示范):
     * getting ready belongs to one request; when that request is answered, withdrawn or gone, so is getting ready.
     * 1.4.0-beta11: while recording, the lesson goes on and the strip says why the request ended (saved as yours).
     */
    fun askEnded(id: Int, why: String? = null) {
        if (prepId == id) { cancelPrep(); return }
        synchronized(this) { rec?.takeIf { it.askId == id }?.let { it.askEnded = why ?: tr(S.vscreen_teach_the_request_ended); recState(it) } }
    }

    /**
     * 1.4.0-beta5: the user handed the screen back (from the floating window, the notification, the full screen page,
     * Stop): the lesson cannot go on without them holding the screen. One answer, wherever the hand-back came from
     * (beta4 only handled it on the full screen page, so a hand-back from the floating window left the agent waiting):
     *  - recording: it ends as if Done was tapped (the review page opens with what was shown);
     *  - getting ready: the request ends as not shown, and the agent hears it at once.
     */
    fun onTakeoverEnded(ctx: Context?) {
        if (rec != null) { endRecording(ctx, byDone = false); return }
        val p = prepId ?: return
        cancelPrep()
        ctx?.let { UserAsk.answer(it, p, false, org.json.JSONObject().put("kind", "handed_back")) }
    }

    /** What a check marked while recording asks the agent by default (the review page lets the user say it better). */
    val CHECK_DEFAULT: String get() = tr(S.vscreen_teach_check_that_this_step_went)

    /** 1.4.0-beta11: "让 agent 看一下": after the step just recorded, the agent will check before the lesson goes on. */
    fun markCheck() = synchronized(this) {
        val r = rec?.takeIf { !it.paused } ?: return
        flushTyping(r)
        if (r.events.isEmpty() || r.events.last() is TeachEv.Check) return
        r.events += TeachEv.Check(System.currentTimeMillis(), CHECK_DEFAULT)
    }

    /** 撤掉上一步: the last thing recorded (or the typing not yet written) is dropped. */
    fun undo() = synchronized(this) {
        val r = rec ?: return
        if (typing != null || typingStart != null) { typing = null; typingStart = null; return }
        if (r.events.isNotEmpty()) r.events.removeAt(r.events.lastIndex)
        current = null
        recState(r)
    }

    fun setPaused(p: Boolean) = synchronized(this) {
        val r = rec ?: return
        if (p) flushTyping(r)
        // A finger still on the picture when pausing is not a step (DESIGN §5.4).
        current?.cancelled = true
        r.paused = p; current = null; recState(r)
    }

    /**
     * 1.3.0-beta14: the user took an agent's "show me how" (from any card, the notification or the full screen page):
     * take the screen over and record at once. The question's card goes away while the lesson is under way.
     */
    fun beginFor(a: UserAsk.Ask): Boolean {
        if (!a.live || a.kind != UserAsk.Kind.TEACH) return false
        if (rec?.askId == a.id || prepId == a.id) return true
        if (_lesson.value !is Lesson.None) return false // one lesson at a time: finish the one under way first
        val st = BackgroundOperation.state.value
        val session = st.ownerSessionId ?: return false
        if (!BackgroundOperation.beginTakeover(session) && BackgroundOperation.state.value.takeoverSessionId != session) return false
        // 1.4.0-beta4: not recording yet: the user first gets to where the lesson starts.
        _lesson.value = Lesson.Ready(a.id)
        sync()
        return true
    }

    /**
     * 1.3.0 (rainbow: "示范返回几张缩略图方便agent用视觉理解"): one small contact sheet of a lesson for an agent to look
     * at. At most [SHEET_MAX] cells, 4 to a row: the screen before each of up to 7 kept steps (picked evenly when
     * there are more), tap points circled, and the screen at the end. Each cell is labelled with the step's number and
     * text. One picture, not one per step: it stays small (about 960 px wide) and fits any agent's image limit.
     */
    internal fun sheet(r: Result, keep: Set<Int>): android.graphics.Bitmap? = runCatching {
        val steps = r.lines.withIndex().filter { it.index in keep }.mapIndexed { n, l -> Triple(n + 1, l.value, l.value.pic) }.filter { it.third != null }
        val picked = spread(steps, SHEET_MAX - (if (r.endPic != null) 1 else 0))
        val cells = picked.map { (n, l, p) -> Triple("$n " + (if (l.kind == "type") "type " + (if (l.secret) "***" else l.typed.orEmpty().take(10)) else l.head).take(24), p!!, l.at) } +
            listOfNotNull(r.endPic?.let { Triple(tr(S.vscreen_teach_at_the_end), it, null as Pair<Int, Int>?) })
        if (cells.isEmpty()) return null
        val cw = TeachPic.W; val ch = cells.maxOf { it.second.bmp.height }; val label = 26; val gap = 6
        val cols = minOf(4, cells.size); val rows = (cells.size + cols - 1) / cols
        val out = android.graphics.Bitmap.createBitmap(cols * cw + (cols + 1) * gap, rows * (ch + label) + (rows + 1) * gap, android.graphics.Bitmap.Config.RGB_565)
        val c = android.graphics.Canvas(out)
        c.drawColor(0xFF15131F.toInt())
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 17f }
        val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFFF3B6B.toInt() }
        cells.forEachIndexed { i, (name, p, at) ->
            val x = gap + (i % cols) * (cw + gap); val y = gap + (i / cols) * (ch + label + gap)
            c.drawText(name, x + 2f, y + 19f, text)
            c.drawBitmap(p.bmp, x.toFloat(), (y + label).toFloat(), null)
            at?.let { (ax, ay) -> c.drawCircle(x + ax * p.scale, y + label + ay * p.scale, 11f, ring) }
        }
        out
    }.getOrNull()

    const val SHEET_MAX = 8

    /** Up to [n] of [xs], spread evenly, the first and the last always in. */
    internal fun <T> spread(xs: List<T>, n: Int): List<T> = when {
        n <= 0 -> emptyList()
        xs.size <= n -> xs
        n == 1 -> listOf(xs.last())
        else -> (0 until n).map { xs[(it * (xs.size - 1).toDouble() / (n - 1)).roundToInt()] }.distinct()
    }

    fun cancel() { synchronized(this) { rec = null; current = null; typing = null; typingStart = null; _lesson.value = Lesson.None }; sync(); Draft.clear() }

    /**
     * 1.4.0-beta10: the background screen closed. What was recorded is not lost with it (saving needs no screen):
     * recording ends as if Done was tapped; getting ready ends as not shown; a lesson waiting to be saved stays.
     */
    fun screenClosed(ctx: Context?) {
        if (rec != null) { endRecording(ctx, byDone = false, screenGone = true); return }
        (_lesson.value as? Lesson.Review)?.let { v -> if (!v.screenGone) _lesson.value = v.copy(screenGone = true); return }
        val p = prepId ?: return
        cancelPrep()
        ctx?.let { UserAsk.answer(it, p, false, org.json.JSONObject().put("ended", tr(S.vscreen_teach_the_screen_closed))) }
    }

    /** While recording an agent's request: who asked and what (the full screen page shows it in full). */
    fun brief(): Pair<String?, String>? = rec?.let { r -> r.purpose?.takeIf { it.isNotBlank() }?.let { p -> (r.askId?.let { UserAsk.byId(it)?.tokenName }) to p } }
        ?: prepId?.let { UserAsk.byId(it) }?.let { a -> a.tokenName to a.title }

    /** 1.4.0-beta13: a teach request's key without who sent it ("what:name"): the same request from any session. */
    fun askedOf(key: String): String = key.removePrefix("teach:").substringAfter(':')

    /** 1.4.0-beta11: the start of a segment as it would be kept, read from [displayId] now (null when unreadable). */
    fun startHere(displayId: Int): org.json.JSONObject? = startJson(read(displayId))

    private fun read(id: Int): TeachSnap? {
        snapSource?.let { return it(id) }
        val d = BackgroundOperation.state.value.ownerSessionId?.let { BackgroundOperation.previewDisplayFor(it) } ?: return null
        // 1.3.0-beta14: a reading that failed (or came back empty) is "unknown", never "a blank screen": a blank before
        // made every word on the page look new, and a lesson got `until text:<title bar words>` it could never meet.
        val els = runCatching { a11yRaw(d) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return TeachSnap(runCatching { VScreenShell.topPackage(id) }.getOrNull(), els, runCatching { VScreenShell.topActivity(id) }.getOrNull())
    }

    /** 1.3.0: the screen now as a small picture ([TeachPic.W] px wide), or null. */
    private fun grab(): TeachPic? = runCatching {
        if (snapSource != null) return null
        val d = BackgroundOperation.state.value.ownerSessionId?.let { BackgroundOperation.previewDisplayFor(it) } ?: return null
        val f = d.latestFrame() ?: return null
        val w = TeachPic.W; val h = (f.height.toLong() * w / maxOf(1, f.width)).toInt().coerceAtLeast(1)
        val small = android.graphics.Bitmap.createScaledBitmap(f, w, h, true)
        val pic = small.copy(android.graphics.Bitmap.Config.RGB_565, false)
        if (small !== f && small !== pic) small.recycle()
        TeachPic(pic, w.toFloat() / maxOf(1, d.width))
    }.getOrNull()

    private fun add(r: Rec, e: TeachEv) {
        if (r.events.size >= MAX) return
        r.events += e
        e.pending = reader.submit { e.before = read(r.displayId); e.pic = grab() }
        recState(r)
    }

    // ── what the user does (UserTouch, the typing field, the keys) ──

    fun touchDown(displayId: Int, x: Int, y: Int) = synchronized(this) {
        val r = rec?.takeIf { it.displayId == displayId && !it.paused } ?: return
        flushTyping(r)
        current = TeachEv.Touch(System.currentTimeMillis(), x, y).also { add(r, it) }
    }

    fun touchMove(x: Int, y: Int) = synchronized(this) {
        val c = current ?: return
        if (c.path.size < 400) c.path += intArrayOf(x, y, (System.currentTimeMillis() - c.t).toInt())
    }

    /** The reading of the touch in progress (the finger's lift waits for it while recording). */
    fun lastReading(): Future<*>? = synchronized(this) { if (rec == null) null else current?.pending }

    fun touchUp(x: Int, y: Int, cancelled: Boolean) = synchronized(this) {
        val c = current ?: return
        current = null
        if (x >= 0 && y >= 0) c.path += intArrayOf(x, y, (System.currentTimeMillis() - c.t).toInt())
        c.end = System.currentTimeMillis()
        if (cancelled) c.cancelled = true
    }

    /** Typed text / a key, called on the thread that sends it: waits (briefly) until the screen before it was read. */
    fun typed(text: String) = record(TeachEv.Typed(System.currentTimeMillis(), text))
    fun key(name: String) = record(TeachEv.Key(System.currentTimeMillis(), name))

    /** 1.3.0-beta14: what the mirrored field reads now (null: unchanged); it becomes one step at the next action. */
    @Volatile private var typing: String? = null
    /**
     * 1.3.0-beta19: the screen when the typing began, read at the first keystroke. The typing becomes a step only at
     * the next action, and a reading taken then already shows the typed words: the tap before the typing got
     * `until text:<the typed words>` (test M: a lesson that could never replay).
     */
    @Volatile private var typingStart: TeachEv.Typed? = null
    fun typingNow(value: String?) = synchronized(this) {
        val r = rec?.takeIf { !it.paused } ?: return
        val v = value?.takeIf { it.isNotEmpty() }
        if (v != null && typing == null && typingStart == null) startTyping(r)
        if (v == null) typingStart = null
        typing = v
    }
    private fun startTyping(r: Rec): TeachEv.Typed = TeachEv.Typed(System.currentTimeMillis(), "", whole = true).also { st ->
        typingStart = st; st.pending = reader.submit { st.before = read(r.displayId); st.pic = grab() }
    }
    /**
     * 1.3.0-beta19 (test M again): called before the first character of a typing run is sent; the caller waits for
     * the returned reading. Read at the first keystroke was already too late: the first character had landed and the
     * page had reacted (a voice-search icon gone), so the tap before it got `until gone:<icon>`.
     */
    fun beforeTyping(): Future<*>? = synchronized(this) {
        val r = rec ?: return null
        if (typing != null) return null
        (typingStart ?: startTyping(r)).pending
    }
    fun commitTyping() = synchronized(this) { rec?.let { flushTyping(it) } }
    private fun flushTyping(r: Rec) {
        val t = typing ?: return
        val st = typingStart
        typing = null; typingStart = null
        val e = TeachEv.Typed(st?.t ?: System.currentTimeMillis(), t, whole = true)
        if (st == null) { add(r, e); return }
        if (r.events.size >= MAX) return
        r.events += e
        e.pending = reader.submit { runCatching { st.pending?.get(4, TimeUnit.SECONDS) }; e.before = st.before; e.pic = st.pic }
        recState(r)
    }

    private fun record(e: TeachEv) {
        synchronized(this) { val r = rec?.takeIf { !it.paused } ?: return; flushTyping(r); add(r, e) }
        runCatching { e.pending?.get(800, TimeUnit.MILLISECONDS) }
    }

    /**
     * Stop recording and write the steps (reads the screen once more). Never opens a page (1.4.0-beta11): the user's
     * own Done tap opens the save pane itself; every other way here (hand-back, the screen closing, the window closed)
     * only changes the cards and posts one notification. [endWaitMs]: how long the end screen may be read on THIS
     * thread (the screen is about to be released; 0 = read on the worker).
     */
    fun endRecording(ctx: Context?, byDone: Boolean, screenGone: Boolean = false, endWaitMs: Long = 0L) {
        val r = synchronized(this) {
            rec?.let { flushTyping(it) }
            // A finger still on the picture when Done is tapped is not a step (DESIGN §5.4).
            current?.cancelled = true
            rec.also { rec = null; current = null; typing = null; typingStart = null }
        } ?: return
        _lesson.value = Lesson.Ending(r.askId, r.events.size)
        val ctx = ctx?.applicationContext
        // The end screen, read before anything else happens to it (the screen may be released right after).
        val endPic0 = if (endWaitMs > 0) grab() else null
        val end0 = if (endWaitMs > 0) runCatching { reader.submit<TeachSnap?> { read(r.displayId) }.get(endWaitMs, TimeUnit.MILLISECONDS) }.getOrNull() else null
        Thread({
            val end = end0 ?: if (endWaitMs > 0) null else runCatching { reader.submit<TeachSnap?> { read(r.displayId) }.get(4, TimeUnit.SECONDS) }.getOrNull()
            val endPic = endPic0 ?: if (endWaitMs > 0) null else runCatching { reader.submit<TeachPic?> { grab() }.get(2, TimeUnit.SECONDS) }.getOrNull()
            r.events.forEach { e -> runCatching { e.pending?.get(4, TimeUnit.SECONDS) } }
            val evs = r.events.filter { it !is TeachEv.Touch || (!it.cancelled && it.end > 0) }
            val app = evs.firstNotNullOfOrNull { it.before?.pkg } ?: end?.pkg
            runCatching { r.startRead?.get(4, TimeUnit.SECONDS) }
            val size = runCatching { BackgroundOperation.previewDisplayFor(r.session)?.let { it.width to it.height } }.getOrNull()
            // A request that ended meanwhile is not answered: the lesson is saved as the user's own.
            val askId = r.askId?.takeIf { r.askEnded == null }
            val res = Result(r.session, r.purpose, askId, r.name, app, translate(evs, end, size), endPic, startJson(r.start), r.ask?.takeIf { askId != null })
            val gone = screenGone || BackgroundOperation.state.value.displayId == null
            if (_lesson.value is Lesson.Ending) _lesson.value = Lesson.Review(res, gone)
            sync()
            ctx?.let { c ->
                Draft.save(c, res)
                if (!byDone) waitingNote(c, res)
            }
        }, "vscreen-teach-stop").apply { isDaemon = true }.start()
    }

    /** 1.4.0-beta11: the one notification a lesson posts, when it was stopped by something other than Done. */
    private fun waitingNote(c: Context, res: Result) {
        val tap = android.app.PendingIntent.getActivity(c, 7790, com.kilon.agentvscreen.ui.Surface.reviewIntent(c),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT, com.kilon.agentvscreen.ui.MainDisplay.options())
        com.kilon.agentvscreen.core.VScreenHandoff.alert(c, NOTE_ID, tr(S.asks_recorded_waiting_to_be_saved, res.lines.size, steps(res.lines.size)),
            tr(S.vscreen_teach_the_lesson_stopped_here_the), tap)
    }
    private const val NOTE_ID = 7791

    /** 1.3.0-beta16: "Done": stop recording AND hand the screen back (one tap, not Done then Hand back). The caller opens the save pane. */
    fun finish(ctx: Context) {
        endRecording(ctx, byDone = true)
        if (BackgroundOperation.state.value.takeoverSessionId != null) BackgroundOperation.userResume()
    }

    /** 1.4.0-beta11: a lesson kept on disk ([Draft]) comes back waiting to be saved after the app restarted. */
    internal fun restore(r: Result) {
        if (_lesson.value !is Lesson.None) return
        _lesson.value = Lesson.Review(r, BackgroundOperation.state.value.displayId == null)
        sync()
    }

    /**
     * 1.4.0-beta11: 重录 from the save pane: the same request, the steps cleared, the screen taken over again. False
     * when the screen is gone (the button says why).
     */
    fun again(): Boolean {
        val r = result ?: return false
        val s = BackgroundOperation.state.value
        if (s.ownerSessionId != r.session || s.displayId == null) return false
        BackgroundOperation.beginTakeover(r.session)
        return start(r.session, r.purpose, r.askId, r.name)
    }

    /** The review page is done with the steps (saved, discarded or left): an unanswered question shows again. */
    /** Saved: the agent that asked gets the steps, wherever its question went meanwhile. */
    fun deliver(ctx: Context, r: Result, reply: org.json.JSONObject) {
        val a = r.ask
        UserAsk.deliver(ctx, r.askId, a?.key, a?.token, a?.tokenName, a?.session, a?.title ?: r.purpose.orEmpty(), reply)
    }

    /** The user saved, discarded or re-recorded the lesson waiting to be saved. Only those end it. */
    fun clearResult(ctx: Context? = null) {
        if (_lesson.value is Lesson.Review) _lesson.value = Lesson.None
        sync(); Draft.clear()
        ctx?.let { c -> runCatching { c.getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTE_ID) } }
    }

    // ── the starting page (1.4.0-beta4) ──

    /** The start as kept with the segment: the app, the screen (activity) and up to 5 words seen once there. */
    internal fun startJson(sn: TeachSnap?): org.json.JSONObject? {
        if (sn == null) return null
        val words = texts(sn).map { it.text.trim() }.filter { sign(it) && unique(sn, it) }.distinct().take(5)
        return org.json.JSONObject().put("app", sn.pkg ?: org.json.JSONObject.NULL).put("activity", sn.act ?: org.json.JSONObject.NULL).put("words", org.json.JSONArray(words))
    }

    /**
     * Whether the screen now is the lesson's start: the same app, and the same screen (activity) or most of the start's
     * words on it. Null = yes; otherwise what the start looks like, for NOT_AT_START.
     */
    internal fun atStart(start: org.json.JSONObject, pkg: String?, act: String?, shown: (String) -> Boolean): String? {
        val app = start.optString("app").takeIf { it.isNotEmpty() && it != "null" }
        val sact = start.optString("activity").takeIf { it.isNotEmpty() && it != "null" }
        val words = start.optJSONArray("words")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val sameApp = app == null || pkg == null || app == pkg
        val hits = words.count(shown)
        val ok = sameApp && (words.isEmpty() || hits * 2 >= words.size || (sact != null && act == sact && hits > 0))
        if (ok) return null
        return trPair("起点是 ${app ?: "某个应用"}" + (sact?.let { " 的 ${it.substringAfterLast('.')}" } ?: "") + (if (words.isNotEmpty()) "，页面上有「${words.joinToString("」「")}」" else ""),
            "The start is ${app ?: "an app"}" + (sact?.let { " at ${it.substringAfterLast('.')}" } ?: "") + (if (words.isNotEmpty()) ", a page showing \"${words.joinToString("\", \"")}\"" else ""))
    }

    // ── from events to steps (pure) ──

    private const val SLOP = 24
    private const val PRESS_MS = 500L

    /** The words someone could read and a step could name. */
    /**
     * Words a step can name a place by. 1.4.0-beta11 (rainbow: tapping 1, 2, 8 on a calculator was written as "beside
     * 百分比符号"): the recorder used its own stricter rule (letters required, numbers refused) while the step language
     * reads `tap 8` as the word 8. Now the step parser decides: words are nameable when `tap <them>` reads back as
     * exactly those words.
     */
    private fun nameable(s: String) = s.length in 1..20 && s.none { it == ';' || it == '；' || it == '"' || it == '“' || it == '”' || it == '\'' || it == '\n' } &&
        !Regex("\\s+(or|或)\\s+").containsMatchIn(s) && Steps.readsAsWords(s)

    /** Words that make a good "it worked" sign: real words, not a clock, a counter or a value being typed. */
    private fun sign(s: String) = s.length in 2..16 && nameable(s) && s.any { it.isLetter() } && !s.contains(" until ")

    private fun texts(sn: TeachSnap?) = sn?.els.orEmpty().filter { !it.placeholder }

    /** The words of [sn] exactly once on the screen. */
    private fun unique(sn: TeachSnap?, text: String): Boolean = texts(sn).count { VScreenOcr.norm(it.text) == VScreenOcr.norm(text) } == 1

    /** A check's words as one quoted argument (quotes and step separators inside become plain). */
    private fun quoteCheck(s: String) = "\"" + s.replace(Regex("[\"“”;；\n]"), " ").trim() + "\""

    private fun quote(s: String) = if (s.any { it.isWhitespace() }) "\"$s\"" else s

    /** The words under (x, y) that name it once, or null. */
    internal fun wordsAt(sn: TeachSnap?, x: Int, y: Int): String? {
        val under = texts(sn).filter { it.box.contains(x, y) }.sortedBy { it.box.width().toLong() * it.box.height() }
        val e = under.firstOrNull() ?: return null
        val t = e.text.trim()
        if (!nameable(t) || !unique(sn, t)) return null
        return t
    }

    /** What changed from [a] to [b], as a condition; null when the readings show no difference. */
    /** Nothing a person could read moved, appeared or went away between [a] and [b] (the step changed nothing). */
    internal fun sameScreen(a: TeachSnap, b: TeachSnap): Boolean {
        fun key(sn: TeachSnap) = texts(sn).map { Triple(VScreenOcr.norm(it.text), it.box.centerX() / 24, it.box.centerY() / 24) }.toSet()
        return a.pkg == b.pkg && a.act == b.act && key(a) == key(b)
    }

    /**
     * The `until` for a step: what it caused, judged by the SAME rule replay uses ([counts], 1.3.0-beta14). Words count
     * when they were not at that place before the step, unless the step opened a new page (another app or activity).
     * A step after which nothing changed gets no `until` (replay would wait for a change that never comes).
     */
    internal fun changeCond(a: TeachSnap?, b: TeachSnap?, skip: Set<String> = emptySet()): Pair<String, String>? {
        if (a == null || b == null || sameScreen(a, b)) return null
        val before = texts(a).map { VScreenOcr.norm(it.text) }.toSet()
        val after = texts(b).map { VScreenOcr.norm(it.text) }.toSet()
        val newPage = (a.pkg != null && b.pkg != null && a.pkg != b.pkg) || (a.act != null && b.act != null && a.act != b.act)
        fun priorAt(t: String) = texts(a).filter { VScreenOcr.norm(it.text) == VScreenOcr.norm(t) }.map { it.box }
        val skipN = skip.map { VScreenOcr.norm(it) }
        fun ok(t: String) = sign(t) && skipN.none { s -> s.isNotEmpty() && (VScreenOcr.norm(t).contains(s) || s.contains(VScreenOcr.norm(t))) }
        val fresh = texts(b).filter { counts(it.box, priorAt(it.text), null, newPage).ok && (newPage || VScreenOcr.norm(it.text) !in before) && ok(it.text.trim()) && unique(b, it.text.trim()) }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
        fresh.firstOrNull()?.let { return "text:${it.text.trim()}" to tr(S.vscreen_teach_then_appeared, it.text.trim()) }
        if (a.pkg != null && b.pkg != null && a.pkg != b.pkg) return "app:${b.pkg}" to tr(S.vscreen_teach_then_came_to_the_front, b.pkg)
        val gone = texts(a).filter { VScreenOcr.norm(it.text) !in after && ok(it.text.trim()) && unique(a, it.text.trim()) }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
        gone.firstOrNull()?.let { return "gone:${it.text.trim()}" to tr(S.vscreen_teach_then_went_away, it.text.trim()) }
        return null
    }

    /** The steps for [evs]; [end] is the screen when recording stopped. */
    /**
     * 1.4.0-beta5 (rainbow: 坐标按屏幕比例保存): the screen the lesson was shown on. Points in the steps are written as
     * shares of it (`50% 80%`), so replay on another size or orientation lands on the same place. Null (tests, a
     * screen that could not be read) keeps pixels.
     */
    @Volatile private var size: Pair<Int, Int>? = null

    /** A point as the steps write it: shares of [size], or pixels without one. */
    private fun pt(x: Int, y: Int): String = size?.let { (w, h) -> "${Steps.pct(x, w)} ${Steps.pct(y, h)}" } ?: "$x $y"
    private fun area(r: android.graphics.Rect): String = size?.let { (w, h) -> "${Steps.pct(r.left, w)},${Steps.pct(r.top, h)},${Steps.pct(r.right, w)},${Steps.pct(r.bottom, h)}" } ?: "${r.left},${r.top},${r.right},${r.bottom}"
    private fun off(d: Int, of: Int?): String = (if (d >= 0) "+" else "-") + (of?.let { Steps.pct(abs(d), it) } ?: abs(d).toString())

    /**
     * 1.4.0-beta5 (rainbow: 点击按附近的锚点文字定位): a tap where nothing can be read (an icon, a switch) is written
     * relative to the nearest words that name one place, `tap 蓝牙 +62%,+0%`, so it follows the row when the list
     * moves. Words on the same row come first (a switch beside its label); nothing nearby keeps a plain point.
     */
    internal fun anchorAt(sn: TeachSnap?, x: Int, y: Int): Pair<String, Pair<Int, Int>>? {
        val w = size?.first ?: (sn?.els?.maxOfOrNull { it.box.right } ?: 1080)
        // The same row reaches across the screen (a switch at the right edge, its label at the left); elsewhere only
        // words close by (an icon under its caption).
        val reach = maxOf(240, w / 4)
        val cands = texts(sn).filter { e -> val t = e.text.trim(); nameable(t) && unique(sn, t) }
            .map { e -> e to hypot((e.box.centerX() - x).toDouble(), (e.box.centerY() - y).toDouble()) }
        val row = cands.filter { (e, _) -> y in (e.box.top - 24)..(e.box.bottom + 24) }
        val best = (row.ifEmpty { cands.filter { it.second <= reach } }).minByOrNull { it.second }?.first ?: return null
        return best.text.trim() to ((x - best.box.centerX()) to (y - best.box.centerY()))
    }

    internal fun translate(evs: List<TeachEv>, end: TeachSnap?, size: Pair<Int, Int>? = null): List<TeachLine> {
        this.size = size?.takeIf { it.first > 0 && it.second > 0 }
        try { return translateIn(evs, end) } finally { this.size = null }
    }

    private fun translateIn(evs: List<TeachEv>, end: TeachSnap?): List<TeachLine> {
        val out = ArrayList<TeachLine>()
        var i = 0
        while (i < evs.size) {
            val e = evs[i]
            // Typing sent in several pieces into the same box is one step.
            if (e is TeachEv.Typed) {
                val sb = StringBuilder(e.text)
                while (i + 1 < evs.size && evs[i + 1] is TeachEv.Typed) { i++; val n = evs[i] as TeachEv.Typed; if (n.whole) sb.setLength(0); sb.append(n.text) }
                val secret = e.before?.els.orEmpty().any { it.focused && it.password }
                out += TeachLine("type", null, pic = e.pic, why = if (secret) tr(S.vscreen_teach_typed_into_a_password_box) else tr(S.vscreen_teach_typed, sb.toString().take(30)),
                    kind = "type", typed = sb.toString(), secret = secret)
                i++; continue
            }
            if (e is TeachEv.Check) { out.lastIndex.takeIf { it >= 0 }?.let { k -> out[k] = out[k].copy(check = e.what) }; i++; continue }
            // The screen after this step: the next event's "before" (a check mark is not a screen).
            val next = evs.drop(i + 1).firstOrNull { it !is TeachEv.Check }?.before ?: end
            when (e) {
                is TeachEv.Key -> {
                    val c = changeCond(e.before, next)
                    out += TeachLine("key ${e.name}", c?.first, tr(S.vscreen_act_pressed, e.name) + (c?.let { "，" + it.second } ?: ""), "key", pic = e.pic,
                        warn = if (next != null && e.before != null && sameScreen(e.before!!, next)) tr(S.vscreen_teach_no_change_was_read_afterwards) else null)
                }
                is TeachEv.Touch -> {
                    // 1.4.0-beta4: swipes are written down by what they were for, not by how far the finger went.
                    var k = i
                    while (k + 1 < evs.size && moved(evs[k + 1]) && moved(e) && sameAxis(e as TeachEv.Touch, evs[k + 1] as TeachEv.Touch)) k++
                    val intent = if (moved(e)) intentLine(evs.subList(i, k + 1).map { it as TeachEv.Touch }, evs.getOrNull(k + 1), evs.getOrNull(k + 1)?.before ?: end) else null
                    if (intent != null) { out += intent.copy(pic = e.pic); i = k + 1; continue }
                    out += touchLine(e, next).copy(pic = e.pic)
                }
                else -> {}
            }
            i++
        }
        return out
    }

    private fun moved(e: TeachEv) = e is TeachEv.Touch && e.path.maxOf { hypot((it[0] - e.x).toDouble(), (it[1] - e.y).toDouble()) } >= SLOP

    /** Mostly vertical (or mostly horizontal) both, starting near each other: the same list or the same control. */
    private fun sameAxis(a: TeachEv.Touch, b: TeachEv.Touch): Boolean {
        fun vert(t: TeachEv.Touch) = t.path.last().let { abs(it[1] - t.y) >= abs(it[0] - t.x) }
        return vert(a) == vert(b) && (if (vert(a)) abs(a.x - b.x) < 160 else abs(a.y - b.y) < 160)
    }

    /**
     * 1.4.0-beta4 (rainbow: "坐标记录不能简单机械播放"). A run of swipes on one list or control becomes one step that
     * replays toward the result the user reached, however far that is this time:
     *  - the swipes brought words into view that the next tap then hit: `repeat 20 { swipe … } until text:<them>`;
     *  - only the words around the finger changed (a wheel, a slider, a stepper) while the rest of the page stayed:
     *    the value left in the middle of that area is the target, `repeat 40 { swipe one row } until text:<v> in <row>`,
     *    and the value becomes a blank like typed words (another time, 08:15 instead of 07:30).
     * Anything else (a canvas, a game, a page that changed as a whole) stays a plain swipe, marked on the review page.
     */
    internal fun intentLine(run: List<TeachEv.Touch>, after: TeachEv?, end: TeachSnap?): TeachLine? {
        val first = run.first(); val before = first.before ?: return null
        val vertical = first.path.last().let { abs(it[1] - first.y) >= abs(it[0] - first.x) }
        // The swipe to repeat: the user's own direction, at most 40% of the screen, slow enough not to fling.
        val dir = run.sumOf { (if (vertical) it.path.last()[1] - it.y else it.path.last()[0] - it.x).toLong() }.let { if (it >= 0) 1 else -1 }
        val xs = run.flatMap { t -> t.path.map { it[0] } }; val ys = run.flatMap { t -> t.path.map { it[1] } }
        val cx = xs.average().toInt(); val cy = ys.average().toInt()
        // 1) Scrolled to find something, then tapped it.
        if (after is TeachEv.Touch && !moved(after) && end != null) {
            val w = wordsAt(end, after.x, after.y)
            if (w != null && texts(before).none { VScreenOcr.norm(it.text) == VScreenOcr.norm(w) }) {
                val len = 0.4 * (if (vertical) (end.els.maxOfOrNull { it.box.bottom } ?: 1600) else (end.els.maxOfOrNull { it.box.right } ?: 1000))
                val sw = swipe(cx, cy, vertical, dir, len.toInt())
                return TeachLine("repeat 20 { $sw }", "text:${quote(w)}", tr(S.vscreen_teach_swiped_until_showed_swipes_this, dirWord(vertical, dir), w, run.size), "seek")
            }
        }
        // 2) Set a value: only the words near the finger changed.
        if (end == null) return null
        // The control: a strip along the finger's path, a little wider than the path and not far beyond its ends.
        val band = if (vertical) android.graphics.Rect(xs.min() - 90, ys.min() - 300, xs.max() + 90, ys.max() + 300) else android.graphics.Rect(xs.min() - 300, ys.min() - 90, xs.max() + 300, ys.max() + 90)
        fun inside(e: El) = band.contains(e.box.centerX(), e.box.centerY())
        fun outside(sn: TeachSnap) = texts(sn).filter { !inside(it) }.map { Triple(VScreenOcr.norm(it.text), it.box.centerX() / 24, it.box.centerY() / 24) }.toSet()
        if (before.pkg != end.pkg || before.act != end.act || outside(before) != outside(end)) return null
        val col = texts(end).filter { inside(it) && it.text.trim().length in 1..12 }
        val was = texts(before).filter { inside(it) }.map { VScreenOcr.norm(it.text) to (it.box.centerY() / 24) }.toSet()
        if (col.size < 2 || col.all { (VScreenOcr.norm(it.text) to (it.box.centerY() / 24)) in was }) return null
        // The value is the item nearest the middle of the column (where a wheel or a picker shows the chosen one).
        val mid = if (vertical) (col.minOf { it.box.top } + col.maxOf { it.box.bottom }) / 2 else (col.minOf { it.box.left } + col.maxOf { it.box.right }) / 2
        val v = col.minByOrNull { abs((if (vertical) it.box.centerY() else it.box.centerX()) - mid) } ?: return null
        val step = (if (vertical) v.box.height() else v.box.width()).coerceAtLeast(24)
        val row = if (vertical) android.graphics.Rect(band.left.coerceAtLeast(0), v.box.top - step / 3, band.right.coerceAtMost(v.box.right + 400), v.box.bottom + step / 3)
            else android.graphics.Rect(v.box.left - step / 3, band.top.coerceAtLeast(0), v.box.right + step / 3, band.bottom.coerceAtMost(v.box.bottom + 400))
        val value = v.text.trim()
        val sw = swipe(v.box.centerX(), v.box.centerY(), vertical, dir, (step * 1.2).toInt())
        return TeachLine("repeat 40 { $sw }", "text:$value in ${area(row)}",
            tr(S.vscreen_teach_set_this_column_to_replayed, value), "value", typed = value)
    }

    private fun dirWord(vertical: Boolean, dir: Int) = if (vertical) (if (dir > 0) tr(S.vscreen_teach_down) else tr(S.vscreen_teach_up)) else (if (dir > 0) tr(S.vscreen_teach_right) else tr(S.vscreen_teach_left))

    /** A slow swipe of [len] px from (x, y) along the user's direction (no fling). */
    private fun swipe(x: Int, y: Int, vertical: Boolean, dir: Int, len: Int): String {
        val h = len / 2
        return if (vertical) "swipe ${pt(x, (y - dir * h).coerceAtLeast(1))} ${pt(x, (y + dir * h).coerceAtLeast(1))} 450ms"
            else "swipe ${pt((x - dir * h).coerceAtLeast(1), y)} ${pt((x + dir * h).coerceAtLeast(1), y)} 450ms"
    }

    private fun touchLine(e: TeachEv.Touch, next: TeachSnap?): TeachLine {
        val last = e.path.last()
        val far = e.path.maxOf { hypot((it[0] - e.x).toDouble(), (it[1] - e.y).toDouble()) }
        val ms = (e.end - e.t).coerceAtLeast(0)
        if (far < SLOP) {
            val press = ms >= PRESS_MS
            val words = wordsAt(e.before, e.x, e.y)
            val anchor = if (words == null) anchorAt(e.before, e.x, e.y) else null
            val target = words?.let { quote(it) } ?: anchor?.let { (t, d) -> "${quote(t)} ${off(d.first, size?.first)},${off(d.second, size?.second)}" } ?: pt(e.x, e.y)
            val verb = if (press) "press" else "tap"
            val head = if (press) "$verb $target ${((ms + 50) / 100 * 100).coerceIn(100, 10_000)}ms" else "$verb $target"
            val c = changeCond(e.before, next, listOfNotNull(words, anchor?.first).toSet())
            val what = (if (press) tr(S.vscreen_teach_held) else tr(S.vscreen_teach_tapped)) + (words?.let { tr(S.vscreen_teach_text, it) }
                ?: anchor?.let { tr(S.vscreen_teach_beside, it.first) } ?: " (${e.x}, ${e.y})")
            val warn = when {
                // 1.4.0-beta11: only when the screen really read the same. A change with no word fit to wait for (a
                // number growing, a symbol added) is a change: the step simply has no `until`.
                next != null && e.before != null && sameScreen(e.before!!, next) -> tr(S.vscreen_teach_no_change_was_read_afterwards_2)
                words == null && anchor != null -> null
                words == null -> tr(S.vscreen_teach_no_words_name_that_place)
                else -> null
            }
            return TeachLine(head, c?.first, what + (c?.let { "，" + it.second } ?: ""), verb, warn = warn, at = e.x to e.y, words = words, snap = e.before, pos = pt(e.x, e.y))
        }
        // Moved: a straight line is a swipe; a bent path is a drag through its turns.
        val dur = ms.coerceIn(50, 10_000)
        val pts = e.path
        val dx = (last[0] - e.x).toDouble(); val dy = (last[1] - e.y).toDouble()
        val len = max(1.0, hypot(dx, dy))
        val bend = pts.maxOf { abs((it[0] - e.x) * dy - (it[1] - e.y) * dx) / len }
        if (bend < 48 || pts.size < 4) {
            return TeachLine("swipe ${pt(e.x, e.y)} ${pt(last[0], last[1])} ${dur}ms", null, tr(S.vscreen_teach_swiped_from_to, e.x, e.y, last[0], last[1]), "swipe",
                warn = tr(S.vscreen_teach_replayed_as_is_what_this))
        }
        val n = 6
        val picked = (0 until n).map { k -> pts[(k * (pts.size - 1)) / (n - 1)] }.distinctBy { it[0] to it[1] }
        return TeachLine("drag " + picked.joinToString(" ") { pt(it[0], it[1]) } + " ${dur}ms", null,
            tr(S.vscreen_teach_dragged_through_points, picked.size), "drag",
            warn = tr(S.vscreen_teach_replayed_as_is_what_this_2))
    }

    /**
     * The segment text from the reviewed lines: [keep] the lines kept, [literal] the typing lines whose words are kept
     * as they were (others become {1}, {2} …). Returns the text and how many parameters it takes.
     */
    internal fun build(lines: List<TeachLine>, keep: Set<Int>, literal: Set<Int>, checks: Map<Int, String> = lines.withIndex().mapNotNull { (i, l) -> l.check?.let { i to it } }.toMap()): Pair<String, Int> {
        var p = 0
        // 1.3.0-beta18: the last typed text that became a parameter. A later tap named by words that contain it (the
        // search result the user picked, "声音 · 声音和触感" after typing "声音") depends on the parameter: with another
        // value those words are not on the screen (NOT_FOUND). Such a tap goes to the same place instead (the first
        // result stays the first result), and an until that names the typed words waits for a change instead.
        var param: String? = null
        var paramN = 0
        // 1.3.0-beta19: every typed value that becomes a parameter. A step's `until` that names one of them before it is
        // typed can't be what that step caused (the reading came after the typing): it is dropped. After it, the
        // words depend on the value: it becomes `change`.
        fun typedValue(i: Int, l: TeachLine) = l.typed?.takeIf { l.kind == "type" && i !in literal && !l.secret }?.let { VScreenOcr.norm(it) }?.takeIf { it.isNotEmpty() }
        fun checkAfter(i: Int) = checks[i]?.trim()?.takeIf { it.isNotEmpty() }?.let { "; check " + quoteCheck(it) } ?: ""
        val values = lines.withIndex().filter { it.index in keep }.mapNotNull { (i, l) -> typedValue(i, l)?.let { i to it } }
        val steps = lines.withIndex().filter { it.index in keep }.map { (i, l) ->
            var until = l.until
            val named = if (l.kind != "type" && l.until != null) values.firstOrNull { (_, v) -> VScreenOcr.norm(l.until).contains(v) } else null
            if (named != null) until = if (named.first > i) null else "change"
            if (l.kind == "value" && l.typed != null && until != null) {
                // 1.4.0-beta4: the value a control was set to is a blank like typed words, unless kept as it was.
                param = null
                if (i !in literal) { paramN = ++p; until = until.replaceFirst("text:${l.typed} in ", "text:{$p} in ") }
                return@map "${l.head} until $until" + checkAfter(i)
            }
            val head = if (l.kind == "type") {
                if (i in literal && !l.secret && l.typed != null && l.typed.none { it == ';' || it == '；' || it == '\n' }) { param = null; "type ${l.typed}" }
                else { param = l.typed?.takeIf { !l.secret }?.let { VScreenOcr.norm(it) }?.takeIf { it.isNotEmpty() }; paramN = ++p; "type {$p}" }
            } else {
                val q = param
                if (q != null && until != null && VScreenOcr.norm(until).contains(q)) until = "change"
                if (q != null && l.kind == "tap" && l.at != null && l.words != null && VScreenOcr.norm(l.words).contains(q)) {
                    // What this tap opens depends on the value too: wait for a change, not for the demo's words.
                    if (until != null) until = "change"
                    rankOf(l.snap, q, l.at)?.let { k -> "tap *{$paramN}*@$k" } ?: "tap ${l.pos ?: "${l.at.first} ${l.at.second}"}"
                } else l.head
            }
            (if (until != null) "$head until $until" else head) + checkAfter(i)
        }
        return steps.joinToString("; ") to p
    }

    /**
     * 1.3.0-beta18: which match of "holds the typed words" the tap at [at] was, counted by the rule replay uses
     * ([wordHits]: reading order, the box typed into left out). With another value the N-th result is tapped, by what
     * it says, not by where it happened to be. Null when the screen was not read or the tap was on none of them.
     */
    internal fun rankOf(sn: TeachSnap?, typed: String, at: Pair<Int, Int>): Int? {
        val els = sn?.els ?: return null
        val under = texts(sn).filter { it.box.contains(at.first, at.second) }.minByOrNull { it.box.width().toLong() * it.box.height() } ?: return null
        val hits = wordHits(els, "*$typed*")
        val i = hits.indexOfFirst { it === under || near(it, under) }
        return if (i < 0) null else i + 1
    }

    /** Replace {1}, {2} … in [body] by [params]; the names of the ones not given. */
    internal fun fill(body: String, params: List<String>): Pair<String, List<Int>> {
        val missing = ArrayList<Int>()
        val s = Regex("\\{([1-9]\\d*)\\}").replace(body) { m ->
            val k = m.groupValues[1].toInt()
            params.getOrNull(k - 1) ?: run { missing += k; m.value }
        }
        return s to missing.distinct()
    }
}

/** `teach "<what to show>"`: the user shows it once on the background screen; the answer is the saved segment. */
internal fun VScreenOffloadHandler.teachCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val session = sid ?: VScreenOffloadHandler.DEFAULT_SESSION
    val what = args.positional.joinToString(" ").trim()
    if (what.isEmpty()) throw ActionError("INVALID_ARGS", tr(S.vscreen_teach_teach_what_to_show_name))
    if (what.length > 200) throw ActionError("INVALID_ARGS", tr(S.vscreen_teach_at_most_200_characters))
    val name = args.get("name")?.trim()?.replace(Regex("\\s+"), "-")?.take(32)?.takeIf { it.isNotEmpty() }
    val call = Call.current()
    val inTask = call?.taskId != null
    val valid = 15 * 60_000L
    val wait = if (inTask && args.get("wait") == null) valid else bounded("wait", parseDuration(args.get("wait"), ASK_DEFAULT_WAIT_MS), 0L, if (inTask) valid else ASK_MAX_WAIT_MS)
    val key = "teach:$session:$what:${name.orEmpty()}"
    // 1.4.0-beta11 (field test: the agent sent the same teach again to check, and the user was asked to show it a
    // second time). The same teach is the same request: once saved, sending it again returns that segment again;
    // only --again asks the user for a new showing. A declined request already told stays told (a new one is asked).
    val again = args.hasFlag("again")
    val old = UserAsk.find(key, call?.tokenId ?: "main")?.takeIf { it.kind == UserAsk.Kind.TEACH }
        ?.takeIf { o -> !(again && o.answer != null) && !(o.answer == false && o.used) }
        // 1.4.0-beta12: words instead of a lesson were told once; the same teach sent again (adjusted) asks again.
        ?.takeIf { o -> !(o.used && o.reply?.optString("kind") == "feedback") }
    // 1.4.0-beta13 (test B6: the same teach sent again an hour later, or after the app restarted, asked the user
    // again): the request in memory is short-lived; what lasts is the segment it produced. A saved lesson remembers the
    // request it answered, so the same teach finds it whoever sends it and whenever.
    // 1.4.0-beta14: a named teach also finds the taught lesson of that name (lessons kept before beta13 have no request).
    val kept = if (old == null && !again) VScreenMap.forTeach(Teach.askedOf(key)) ?: name?.let { VScreenMap.taughtNamed(it) } else null
    val repeat = (old?.answer == true && old.used) || kept != null
    var heardLine = ""
    var said: List<String> = emptyList()
    val r = if (kept != null) {
        val (app, sg) = kept
        org.json.JSONObject().put("kind", "teach").put("name", sg.name).put("app", app).put("steps", sg.steps)
            .put("params", Regex("\\{([1-9]\\d*)\\}").findAll(sg.steps).maxOfOrNull { it.groupValues[1].toInt() } ?: 0)
    } else {
    val ask = if (old != null && (old.answer != null || old.live)) old else {
        BackgroundOperation.previewDisplayFor(session) ?: throw ActionError("NO_SCREEN", tr(S.vscreen_teach_the_lesson_is_done_on))
        UserAsk.create(context, UserAsk.Kind.TEACH, key, what, tr(S.vscreen_teach_do_it_once_on_the),
            options = listOfNotNull(name), validMs = valid, reusable = true)
    }
    if (ask.answer == null) {
        call?.report(tr(S.vscreen_teach_the_request_is_on_the))
        UserAsk.await(ask, wait)
    }
    if (call?.cancelled == true && ask.answer == null) {
        if (UserAsk.taken(ask)) throw ActionError("CANCELLED", tr(S.vscreen_teach_the_caller_went_away_the))
        UserAsk.withdraw(context, ask); throw ActionError("CANCELLED", tr(S.vscreen_teach_the_caller_went_away_the_2))
    }
    // 1.4.0-beta12: what the user said while showing it, not yet told to this agent.
    val heard = ask.said.drop(ask.saidSeen).also { ask.saidSeen = ask.said.size }
    said = ask.said.toList()
    heardLine = if (heard.isEmpty()) "" else tr(S.vscreen_teach_while_showing_it_the_user, heard.joinToString("」「"), heard.joinToString("\", \""))
    when (ask.answer) {
        null -> if (UserAsk.taken(ask)) throw ActionError("ASK_PENDING", heardLine + when {
            Teach.result?.askId == ask.id -> tr(S.vscreen_teach_the_user_has_recorded_it)
            Teach.recAskId() == ask.id -> tr(S.vscreen_teach_the_user_is_showing_it, Teach.steps ?: 0)
            else -> tr(S.vscreen_teach_the_user_is_getting_to)
        }) else throw ActionError("ASK_PENDING", tr(S.vscreen_teach_the_request_is_on_the_2, wait / 1000, ask.validMs / 60_000))
        false -> { UserAsk.markUsed(ask); UserAsk.endedWhy(ask)?.let { why -> throw ActionError("ASK_DECLINED", tr(S.vscreen_teach_the_screen_closed_so_nothing, what, why)) }
            if (ask.reply?.optString("kind") == "handed_back") throw ActionError("ASK_DECLINED", tr(S.vscreen_teach_the_user_handed_the_screen, what))
            throw ActionError("ASK_DECLINED", tr(S.vscreen_teach_the_user_did_not_show, what)) }
        true -> UserAsk.markUsed(ask)
    }
    ask.reply ?: org.json.JSONObject()
    }
    feedbackOf(r)?.let { throw it }
    val seg = r.optString("name"); val n = r.optInt("params")
    val use = "use $seg" + (1..n).joinToString("") { " <$it>" }
    val data = org.json.JSONObject().put("what", what).put("name", seg).put("app", r.optString("app")).put("steps", r.optString("steps"))
        .put("params", n).put("described", r.optJSONArray("described") ?: org.json.JSONArray()).put("use", use)
    // 1.3.0: the lesson's contact sheet (key steps as thumbnails), attached as a picture.
    val pic = VScreenMap.picture(r.optString("app"), seg)?.let { f -> runCatching { android.graphics.BitmapFactory.decodeFile(f.path) }.getOrNull() }
        ?.let { bmp -> try { ShotStore.save(context, bmp, "lesson") } finally { bmp.recycle() } }
    pic?.let { data.put("picture", it) }
    // 1.4.0-beta11 (rainbow: the agent should try the recording once, then make it general by hand). What the user
    // showed is one run with this run's values; the agent turns it into a segment that holds next time.
    val startSeen = VScreenMap.segment(r.optString("app"), seg)?.start?.let { st ->
        val words = st.optJSONArray("words")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        (st.optString("activity").takeIf { it.isNotEmpty() && it != "null" }?.substringAfterLast('.') ?: r.optString("app")) +
            (if (words.isNotEmpty()) tr(S.vscreen_notes_a_page_showing, words.joinToString("」「"), words.joinToString("\", \"")) else "")
    }
    val next = trPair("先试一遍再交差：回到起点" + (startSeen?.let { "（$it）" } ?: "") + "，act \"$use\" 跑一次，看结果对不对。" +
            "然后把只对这一次成立的地方改通用：这次的具体值换成参数（tap {1}）或通配（tap *关键字*、text:结果*）；要等的画面加 until；有时才弹的窗用 try 或 if 包住。" +
            "改好用 note save $seg \"整段步骤\" 存回（起点核对保留），再跑一遍确认。note show $seg 看现在的步骤。",
        "Try it once before you report back: go to the start" + (startSeen?.let { " ($it)" } ?: "") + ", run act \"$use\" and check the result. " +
            "Then make what only held this time general: this run's values become blanks (tap {1}) or wildcards (tap *keyword*, text:Result*); add until where a screen must come; wrap popups that only sometimes show in try or if. " +
            "Save it back with note save $seg \"<all steps>\" (the start check is kept) and run it again. note show $seg shows the steps now.")
    data.put("next", next)
    if (repeat) data.put("again", false).put("note", tr(S.vscreen_teach_this_segment_was_already_given))
    if (said.isNotEmpty()) data.put("said", org.json.JSONArray(said))
    data.put("message", heardLine + (if (repeat) tr(S.vscreen_teach_this_teach_already_has_its) else "") + trPair("用户演示完并存成了段「$seg」（${r.optString("app")}）。照做：act \"$use\"" + (if (n > 0) "（按顺序填 $n 个参数，是用户当时输入的字的位置）" else "") + "。步骤：${r.optString("steps")}" + (pic?.let { "。示教关键步骤缩略图（数字是步骤序号，红圈是点的位置）：$it" } ?: ""),
        "The user showed it and saved it as segment \"$seg\" (${r.optString("app")}). Run it: act \"$use\"" + (if (n > 0) " (fill $n values in order: where the user typed)" else "") + ". Steps: ${r.optString("steps")}" + (pic?.let { ". Thumbnails of the key steps (numbers are step numbers, red rings are taps): $it" } ?: "")) + "\n" + next)
    return ok(args, data)
}

/**
 * 1.4.0-beta11 (DESIGN-SCREEN-LESSON-1.4 §4.6): a lesson waiting to be saved is kept on disk (files/lesson-draft), so
 * a killed or crashed app still has it: the home page shows "录好了 N 步" again and saving still answers the agent
 * (by the request's key, as a late answer). The screen readings behind each step are not kept (only used to tell which
 * of several matches a tap was); everything the save pane shows is.
 */
internal object Draft {
    private const val TAG = "TeachDraft"
    @Volatile private var dir: java.io.File? = null

    fun init(ctx: Context) {
        if (dir != null) return
        dir = java.io.File(ctx.applicationContext.filesDir, "lesson-draft")
        runCatching { load() }.onFailure { com.kilon.agentvscreen.host.AppLogger.warning(TAG, "load: ${it.message}") }.getOrNull()?.let { Teach.restore(it) }
    }

    fun clear() { runCatching { dir?.deleteRecursively() } }

    private fun pic(name: String, p: TeachPic?, o: org.json.JSONObject) {
        val d = dir ?: return
        if (p == null) return
        runCatching { com.kilon.agentvscreen.core.Pictures.write(p.bmp, java.io.File(d, "$name.jpg"), com.kilon.agentvscreen.core.Pictures.Use.LESSON, fit = false); o.put("${name}Scale", p.scale.toDouble()) }
    }

    private fun picOf(name: String, o: org.json.JSONObject): TeachPic? {
        val d = dir ?: return null
        val f = java.io.File(d, "$name.jpg").takeIf { it.isFile } ?: return null
        val b = android.graphics.BitmapFactory.decodeFile(f.path) ?: return null
        return TeachPic(b, o.optDouble("${name}Scale", 1.0).toFloat())
    }

    fun save(ctx: Context, r: Teach.Result) = runCatching {
        if (dir == null) dir = java.io.File(ctx.applicationContext.filesDir, "lesson-draft")
        val d = dir!!
        d.deleteRecursively(); d.mkdirs()
        val o = org.json.JSONObject().put("v", 1).put("session", r.session).put("purpose", r.purpose).put("askId", r.askId ?: -1)
            .put("name", r.name).put("app", r.app).put("start", r.start)
        r.ask?.let { a -> o.put("ask", org.json.JSONObject().put("id", a.id).put("key", a.key).put("token", a.token).put("tokenName", a.tokenName)
            .put("session", a.session).put("title", a.title).put("detail", a.detail).put("at", a.at)) }
        pic("end", r.endPic, o)
        o.put("lines", org.json.JSONArray(r.lines.mapIndexed { i, l ->
            org.json.JSONObject().put("head", l.head).put("until", l.until).put("why", l.why).put("kind", l.kind)
                .put("typed", if (l.secret) null else l.typed).put("secret", l.secret).put("warn", l.warn)
                .put("ax", l.at?.first ?: -1).put("ay", l.at?.second ?: -1).put("words", l.words).put("pos", l.pos).put("check", l.check)
                .also { pic("p$i", l.pic, it) }
        }))
        java.io.File(d, "draft.json").writeText(o.toString())
    }.onFailure { com.kilon.agentvscreen.host.AppLogger.warning(TAG, "save: ${it.message}") }

    private fun load(): Teach.Result? {
        val d = dir ?: return null
        val o = org.json.JSONObject(java.io.File(d, "draft.json").takeIf { it.isFile }?.readText() ?: return null)
        fun str(j: org.json.JSONObject, k: String) = j.optString(k).takeIf { j.has(k) && !j.isNull(k) && it.isNotEmpty() }
        val arr = o.getJSONArray("lines")
        val lines = (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            val ax = l.optInt("ax", -1); val ay = l.optInt("ay", -1)
            TeachLine(l.getString("head"), str(l, "until"), l.optString("why"), l.optString("kind"), str(l, "typed"), l.optBoolean("secret"),
                str(l, "warn"), if (ax >= 0 && ay >= 0) ax to ay else null, str(l, "words"), null, picOf("p$i", l), str(l, "pos"), str(l, "check"))
        }
        val ask = o.optJSONObject("ask")?.let { a ->
            UserAsk.Ask(a.getInt("id"), UserAsk.Kind.TEACH, a.getString("key"), a.getString("token"), a.getString("tokenName"), a.getString("session"),
                a.getString("title"), a.optString("detail"), a.optLong("at"), false)
        }
        // The request id is not kept: ids start again after a restart; the request is found again by its key (UserAsk.deliver).
        return Teach.Result(o.getString("session"), str(o, "purpose"), null, str(o, "name"), str(o, "app"), lines,
            picOf("end", o), o.optJSONObject("start"), ask)
    }
}
