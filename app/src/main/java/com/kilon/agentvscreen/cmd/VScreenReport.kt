package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.tr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenActivity
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenPerception
import com.kilon.agentvscreen.core.VScreenRecorder
import com.kilon.agentvscreen.core.VScreenShell
import kotlin.math.roundToInt

// [vscreen] v49: the fixed four-block return of `act` and `launch`.
//
//   【做了什么】   the steps that ran, each wait met or timed out, which stop fired — facts, no verdicts;
//   【这段时间】   since the previous return (the model's thinking time included): words that appeared / went / came
//                 and went, where the picture moved and for how long, and a contact sheet from the recording track
//                 (only when something changed);
//   【现在】       the foreground app, numbered elements (accessibility + OCR, with their points) and a screenshot
//                 with screen coordinates on its edges;
//   【注意】       safety (main screen, pay guard, takeover, app rules) and failure facts only.
// The screenshot and the sheet are one picture handed to the model with the result.

/** What an element is when it has no words of its own to show. */
internal enum class ElKind { TEXT, EMPTY_INPUT, NO_TEXT, LINK }

/**
 * One element of the 【现在】 list. [n] is what `tap #N` uses. 1.2: what can be done with it is typed (not a localized
 * flags string), and an element without words says what it is through [kind] / [hint] (not through a label that starts
 * with a bracket): the text printed for a model and the JSON for a program are both rendered from these fields.
 */
internal data class El(val n: Int, val text: String, val box: Rect, val fromA11y: Boolean,
                       val clickable: Boolean = false, val editable: Boolean = false, val scrollable: Boolean = false,
                       val checked: Boolean? = null, val focused: Boolean = false, val password: Boolean = false,
                       val kind: ElKind = ElKind.TEXT,
                       /** EMPTY_INPUT / NO_TEXT: the view id or class; LINK: the length of the hidden text. */
                       val hint: String? = null,
                       /** 1.4.0-beta13: a search box the app draws itself (its view id or class says search): a control, not content. */
                       val search: Boolean = false,
                       /** 2.0: found by the optional detector model (a textless tappable thing neither the tree nor OCR names). */
                       val detected: Boolean = false) {
    val placeholder: Boolean get() = kind != ElKind.TEXT
    /** Nothing can be done with it: plain words. */
    val plain: Boolean get() = !clickable && !editable && !scrollable && checked == null && !password
    /** Same element across two looks, whatever the reply language. */
    val key: String get() = "${kind.name}|$text|${hint.orEmpty()}"
    /** The words shown for it. */
    val label: String get() = when (kind) {
        ElKind.TEXT -> text
        ElKind.EMPTY_INPUT -> tr("（空输入框", "(empty input box") + (hint?.let { " $it" } ?: "") + tr("）", ")")
        ElKind.NO_TEXT -> if (detected) tr("（无文字 图标）", "(no text icon)") else tr("（无文字", "(no text") + (hint?.let { " $it" } ?: "") + tr("）", ")")
        ElKind.LINK -> tr("（链接/标识 ${hint ?: "?"} 字）", "(link / id, ${hint ?: "?"} chars)")
    }
    val flags: String get() = buildList {
        if (editable) add(tr("可输入", "input")) else if (clickable) add(tr("可点", "tappable"))
        if (scrollable) add(tr("可滚动", "scrollable"))
        checked?.let { add(if (it) tr("已选", "checked") else tr("未选", "unchecked")) }
        if (focused && editable) add(tr("有焦点", "focused"))
        if (password) add(tr("密码", "password"))
    }.joinToString(" ")
    fun json(): org.json.JSONObject = org.json.JSONObject().put("n", n).put("text", if (placeholder) "" else text)
        .put("x", box.centerX()).put("y", box.centerY())
        .put("box", org.json.JSONArray(listOf(box.left, box.top, box.right, box.bottom)))
        .put("source", if (fromA11y) "accessibility" else if (detected) "detector" else "ocr")
        .also { o ->
            if (placeholder) o.put("kind", kind.name.lowercase()).put("hint", hint ?: org.json.JSONObject.NULL)
            if (clickable) o.put("tappable", true); if (editable) o.put("input", true); if (scrollable) o.put("scrollable", true)
            checked?.let { o.put("checked", it) }; if (focused && editable) o.put("focused", true); if (password) o.put("password", true)
        }
}
/** The elements last shown to a chat, for `#N` (bound to the screen generation they were read on). */
internal class ElSet(val generation: Long, val atMs: Long, val list: List<El>,
                     /** 1.3: the page the list was read on (front activity, and elapsedRealtime of the read). */
                     val activity: String? = null, val atElapsed: Long = 0L)

/**
 * 1.3, pure: the common shift between two lists of the same page (a scroll moves everything by about the same amount):
 * the (dx, dy) most pairs of words that appear once in each list agree on, with how many agree. null = no such pairs.
 */
internal fun commonShift(prev: List<El>, now: List<El>): Triple<Int, Int, Int>? {
    val a = prev.filter { !it.placeholder }.groupBy { it.key }.filterValues { it.size == 1 }
    val b = now.filter { !it.placeholder }.groupBy { it.key }.filterValues { it.size == 1 }
    val shifts = a.keys.intersect(b.keys).map { k -> val x = a[k]!![0]; val y = b[k]!![0]; (y.box.centerX() - x.box.centerX()) to (y.box.centerY() - x.box.centerY()) }
    if (shifts.isEmpty()) return null
    return shifts.map { s -> Triple(s.first, s.second, shifts.count { Math.abs(it.first - s.first) <= 24 && Math.abs(it.second - s.second) <= 24 }) }.maxByOrNull { it.third }
}

/**
 * 1.3, pure: whether [now] is the same page as [prev] (maybe scrolled): enough of its elements are still there, in
 * place or all moved by one common shift. Before, any element whose words were unique on both lists kept its number,
 * so a page title that repeated the tapped row's words (设置 › 显示和亮度) took over the row's number on the new page.
 */
internal fun samePage(prev: List<El>, now: List<El>): Boolean {
    if (prev.isEmpty() || now.isEmpty()) return false
    val inPlace = now.count { e -> prev.any { sameEl(it, e) } }
    val shifted = commonShift(prev, now)?.takeIf { it.first != 0 || it.second != 0 }?.third ?: 0
    val need = maxOf(2, (minOf(prev.size, now.size) * 0.3).toInt())
    return maxOf(inPlace, shifted) >= need
}

/** What 【这段时间】 says, plus the frames for its sheet. */
internal class Since(val lines: List<String>, val changed: Boolean, val from: Long, val to: Long)

internal object Report {
    /** Lines of the 【现在】 list printed at most (all are kept for #N and zoom). */
    const val MAX_ELEMENTS = 160
    /** Elements given in the structured result at most. */
    const val MAX_JSON_ELEMENTS = 400
    const val NOW_SIDE = 1280
    const val CELL_SIDE = 300
    const val MAX_CELLS = 8

    /** "-3.2s" relative to [now]. */
    fun ago(t: Long, now: Long): String = "-${((now - t).coerceAtLeast(0) / 100) / 10.0}s"

    fun box(r: Rect) = "${r.left},${r.top},${r.right},${r.bottom}"
}

// ── elements ─────────────────────────────────────────────────────────

/** The accessibility side of the elements (no OCR): what nodes say, where, and what can be done with them (n = 0). */
internal fun a11yRaw(d: VScreenDisplay): List<El> {
    val raw = ArrayList<El>()
    val screenArea = d.width.toLong() * d.height
    // 1.1: a clickable container with no words of its own (a list row, a card, a search box drawn by the app) takes the
    // words of the plain text nodes inside it, and those nodes are not listed again: one line per thing a person
    // would tap, with the words they would read on it. A wordless node is listed only when it is small (an icon
    // button); big wordless wrappers are layout, not targets. Its view id (stable across rotating placeholder
    // words) is shown when the app gives one.
    fun walk(node: com.kilon.agentvscreen.core.A11yTree.Node) {
        if (raw.size > 400) return
        val n = node.info
        val visible = node.visible
        val text = if (visible) node.words?.takeIf { !isJunkLabel(it) } else null
        val actionable = n.isClickable || n.isEditable || n.isCheckable || n.isScrollable
        // 1.4.0-beta13 (test L18a): the system gives bounds in INPUT space; everything we read, show and tap is in the
        // upright frame space. They differ when the logical display is letterboxed in the picture (a rotated app on a
        // screen built the other way); unmapped, a tap on "9" was named "beside 9" and element taps were mapped twice.
        val b = d.toFrame(node.box)
        val inside = visible && b.width() > 0 && b.height() > 0 && b.centerX() in 0 until d.width && b.centerY() in 0 until d.height
        val area = b.width().toLong() * b.height()
        val big = area > screenArea / 4
        val start = raw.size
        node.children.forEach { walk(it) }
        if (!inside || !(text != null || (actionable && !big) || n.isEditable)) return
        var words: Pair<ElKind, String>? = text?.let { tidy(it) }
        if (words == null && !n.isEditable && (n.isClickable || n.isCheckable)) {
            // Plain words drawn inside this tappable node (not inside a tappable child of it).
            val inner = (start until raw.size).filter { i -> raw[i].let { it.plain && !it.placeholder && b.contains(it.box.centerX(), it.box.centerY()) } }
            if (inner.isNotEmpty()) {
                words = ElKind.TEXT to inner.map { raw[it].text }.distinct().joinToString(" · ").let { if (it.length > 60) it.take(58) + "…" else it }
                inner.asReversed().forEach { raw.removeAt(it) }
            }
        }
        val id = n.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.length in 2..32 }
        var kind = words?.first ?: ElKind.TEXT
        var hint: String? = if (kind == ElKind.LINK) words!!.second else null
        if (words == null) {
            if (!n.isEditable && area > screenArea / 40) return
            kind = if (n.isEditable) ElKind.EMPTY_INPUT else ElKind.NO_TEXT
            hint = if (n.isEditable) id else (id ?: n.className?.toString()?.substringAfterLast('.')?.takeIf { it.length in 3..24 })
        }
        raw.add(start.coerceAtMost(raw.size), El(0, if (kind == ElKind.TEXT) words!!.second else "", b, true,
            clickable = n.isClickable, editable = n.isEditable, scrollable = n.isScrollable,
            checked = if (n.isCheckable) n.isChecked else null, focused = n.isFocused, password = n.isPassword, kind = kind, hint = hint,
            search = n.isClickable && ((n.viewIdResourceName?.substringAfterLast('/') ?: "") + " " + (n.className ?: "")).contains("search", ignoreCase = true)))
    }
    com.kilon.agentvscreen.core.A11yTree.snapshot(d.displayId).roots.forEach { walk(it) }
    // Same element at (nearly) the same place: once.
    val out = ArrayList<El>()
    for (r in raw) if (out.none { it.key == r.key && Math.abs(it.box.centerX() - r.box.centerX()) <= 20 && Math.abs(it.box.centerY() - r.box.centerY()) <= 20 }) out += r
    return out
}
/** Internal names that are not words on the screen: hex hashes, code identifiers (virtualNode). */
private val HEXISH = Regex("^[0-9a-fA-F]{5,}$")
private val CODE_ID = Regex("^[a-z]{3,}[A-Z][a-z]+[A-Za-z0-9]*$")
internal fun isJunkLabel(t: String): Boolean = (HEXISH.matches(t) && t.any { it.isDigit() } && t.any { it.isLetter() }) || (t.length >= 8 && CODE_ID.matches(t))

private val DIGIT = Regex("[0-9]")

/** Element words [l] say the OCR word [w]: one holds the other, or most of [w]'s characters (a misread glyph). */
internal fun saysWord(l: String, w: String): Boolean {
    if (l.isEmpty() || w.isEmpty()) return false
    if (l.contains(w) || w.contains(l)) return true
    val pool = l.groupingBy { it }.eachCount().toMutableMap()
    val hit = w.count { c -> (pool[c] ?: 0).let { n -> if (n > 0) { pool[c] = n - 1; true } else false } }
    return w.length >= 3 && hit * 10 >= w.length * 7
}

/**
 * The elements of one picture: accessibility nodes, then the OCR words no node already says, in reading order.
 * v0.5: when a node's text has digits and the picture shows different digits in its place (an accessibility tree
 * that lags behind the picture: a timer, a counter), the picture's text wins — the list says what is drawn.
 * Nothing is cut here; the printer decides how many lines to show.
 */
internal fun mergeElements(d: VScreenDisplay, a11y: List<El>, ocr: List<VScreenOcr.Item>?, icons: List<Rect>? = null): List<El> {
    val items = ocr.orEmpty().filter { !VScreenOcr.isJunk(it) && !isJunkLabel(it.text.trim()) && it.text.isNotBlank() }
    val out = ArrayList<El>()
    val usedOcr = HashSet<VScreenOcr.Item>()
    // 1.4.0-beta6 (test: 计时器 listed as "计时器" and "O · 计时器"; every 哔哩哔哩 tab as "直播,7之1,标签" and "直播"):
    // a tappable wrapper around a label is one place, not two. When an element holds a smaller element with words
    // (its label) and the wrapper either says nothing or says the label plus extras (position, role), the label is
    // the entry: it takes the wrapper's tap, and glyphs OCR read off the wrapper's icon go with the wrapper.
    fun area(r: Rect) = r.width().toLong() * r.height()
    val dropped = HashSet<El>(); val tappable = HashSet<El>()
    for (w in a11y) {
        if (w.scrollable || w.editable || w.kind == ElKind.LINK || w.kind == ElKind.EMPTY_INPUT) continue
        val wn = VScreenOcr.norm(w.text)
        val label = a11y.filter { c -> c !== w && c.kind == ElKind.TEXT && !c.editable && w.box.contains(c.box.centerX(), c.box.centerY()) && area(c.box) < area(w.box) }
            .filter { c -> val cn = VScreenOcr.norm(c.text); cn.isNotEmpty() && (w.kind == ElKind.NO_TEXT || (wn != cn && wn.startsWith(cn))) }
        // A wrapper with several labels (a whole list row) stays out of this: only a single label is its name.
        // 1.4.0-beta8 (test: 计时器 still twice, "计时器" and "O · 计时器" 37 px apart): the label need not sit inside
        // the wrapper's box. A wordless wrapper whose drawn words, glyphs aside, are exactly what one overlapping
        // element with words says is that element's place too.
        val one = label.singleOrNull() ?: run {
            if (w.kind != ElKind.NO_TEXT || label.isNotEmpty()) return@run null
            val drawn = items.filter { it !in usedOcr && w.box.contains(it.box.centerX(), it.box.centerY()) }
            val words = drawn.map { VScreenOcr.norm(it.text) }.filter { it.length > 1 }
            if (words.size != 1) return@run null
            a11y.filter { c -> c !== w && c.kind == ElKind.TEXT && !c.editable && VScreenOcr.norm(c.text) == words[0] && Rect.intersects(w.box, c.box) }.singleOrNull()
        } ?: continue
        dropped += w
        if (w.clickable) tappable += one
        items.filter { it !in usedOcr && w.box.contains(it.box.centerX(), it.box.centerY()) && VScreenOcr.norm(it.text).length <= 1 }.forEach { usedOcr += it }
    }
    for (a0 in a11y) {
        if (a0 in dropped) continue
        val a = if (a0 in tappable && !a0.clickable) a0.copy(clickable = true) else a0
        if (!a.placeholder && DIGIT.containsMatchIn(a.text) && a.text.length <= 24) {
            val drawn = items.filter { Rect(a.box).apply { inset(-8, -8) }.contains(it.box.centerX(), it.box.centerY()) && DIGIT.containsMatchIn(it.text) }
            val drawnText = drawn.joinToString(" ") { it.text.trim() }
            if (drawn.isNotEmpty() && VScreenOcr.norm(drawnText) != VScreenOcr.norm(a.text) && !VScreenOcr.norm(drawnText).contains(VScreenOcr.norm(a.text))) {
                usedOcr += drawn
                val w = tidy(drawnText)
                out += a.copy(text = if (w.first == ElKind.TEXT) w.second else "", kind = w.first, hint = if (w.first == ElKind.LINK) w.second else null)
                continue
            }
        }
        // 1.3: a wordless tappable element (an icon button, a tile) with words drawn inside it is named by them: one
        // entry, not "(no text ViewGroup)" plus the words beside it.
        if (a.kind == ElKind.NO_TEXT) {
            val inner = items.filter { it !in usedOcr && a.box.contains(it.box.centerX(), it.box.centerY()) }
            if (inner.isNotEmpty()) {
                usedOcr += inner
                val row = maxOf(16, d.height / 120)
                val words = inner.sortedWith(compareBy({ it.box.centerY() / row }, { it.box.centerX() })).joinToString(" · ") { it.text.replace('\n', ' ').trim() }
                val w = tidy(words)
                out += a.copy(text = if (w.first == ElKind.TEXT) w.second else "", kind = w.first, hint = if (w.first == ElKind.LINK) w.second else null)
                continue
            }
        }
        out += a
    }
    for (it in items) {
        if (it in usedOcr) continue
        val t = it.text.replace('\n', ' ').trim()
        val nt = VScreenOcr.norm(t)
        // 1.3: one source per place. Where the app's own element has words (its text, its description, or a hidden
        // link), those are the element: the OCR reading of the same place is not listed again. Before, both were listed
        // ("推荐,7之2,标签" and "推荐"), icons were named by what OCR made of them ("Q", "O") next to their real names,
        // and an address bar hidden as a link was read out in clear by OCR.
        // 1.4.0-beta3: an element with words covers only the words it SAYS (or a lone glyph inside it: an icon OCR
        // misnamed). beta2 let any element up to a quarter of the screen swallow every word inside its box, so a card
        // whose node says "荣耀字体" hid the "HONOR Sans" drawn under it: the word was on the picture and in no list.
        // A hidden link still covers everything inside it.
        // 1.4.0-beta5 (test: a tab bar's 计时器 listed twice, 37 px apart): the element's box and the drawn words need not
        // share a centre (a tab's node is its label, OCR's box takes the glyph spacing differently). The same words in
        // boxes that overlap are one place.
        val said = out.any { a ->
            a.fromA11y && (a.kind == ElKind.TEXT || a.kind == ElKind.LINK) && (
                Rect(a.box).apply { inset(-6, -6) }.contains(it.box.centerX(), it.box.centerY()) &&
                    (a.kind == ElKind.LINK || nt.length <= 1 || saysWord(VScreenOcr.norm(a.text), nt)) ||
                nt.isNotEmpty() && VScreenOcr.norm(a.text) == nt && Rect.intersects(Rect(a.box).apply { inset(-12, -12) }, it.box))
        }
        if (!said) { val w = tidy(t); out += El(0, if (w.first == ElKind.TEXT) w.second else "", Rect(it.box), false, kind = w.first, hint = if (w.first == ElKind.LINK) w.second else null) }
    }
    out += detectedOnly(out, icons.orEmpty())
    val row = maxOf(16, d.height / 120)
    return out.sortedWith(compareBy({ it.box.centerY() / row }, { it.box.centerX() })).mapIndexed { i, r -> r.copy(n = i + 1) }
}

/** 2.0: at most this many detector-only places join one list (the list is for tapping; the rest are in the picture). */
internal const val MAX_DETECTED = 20

/**
 * 2.0, pure: the detector's boxes ([icons], best first) that name a place no listed element already names: one source
 * per place, as for OCR words. A box is the same place as an element when they overlap most (IoU >= 0.3), when the
 * element's middle is inside the box (words drawn on a button: OCR already lists it), or when the box's middle is
 * inside a tappable element not much bigger than it (an app's own icon button). What is left is what the tree and
 * OCR both miss: icons in web pages, games and canvases, and buttons apps never labelled.
 */
internal fun detectedOnly(els: List<El>, icons: List<Rect>): List<El> {
    if (icons.isEmpty()) return emptyList()
    fun area(r: Rect) = r.width().toLong() * r.height()
    fun iou(a: Rect, b: Rect): Double {
        val i = Rect(a); if (!i.intersect(b)) return 0.0
        val inter = area(i).toDouble(); return inter / (area(a) + area(b) - inter)
    }
    val out = ArrayList<El>()
    for (b in icons) {
        if (out.size >= MAX_DETECTED) break
        val taken = (els + out).any { e ->
            iou(e.box, b) >= 0.3 || b.contains(e.box.centerX(), e.box.centerY()) ||
                (e.clickable || e.editable || e.checked != null) && e.box.contains(b.centerX(), b.centerY()) && area(e.box) <= 6 * area(b)
        }
        if (!taken) out += El(0, "", Rect(b), false, clickable = true, kind = ElKind.NO_TEXT, hint = "icon", detected = true)
    }
    return out
}
/** One look at the screen: a frame, the OCR words of exactly that frame, the elements, the foreground activity. */
internal class Snap(val frame: Bitmap?, val version: Long, val items: List<VScreenOcr.Item>?, val els: List<El>,
                    val activity: String?, val ocrReused: Boolean, val mask: com.kilon.agentvscreen.core.Mask? = null,
                    /** Set when the words could not be read (both engines failed) or the accurate one fell back: said in 【注意】. */
                    val ocrProblem: String? = null, val ocrFallback: String? = null,
                    /** 1.4.2: set when the picture is an old one the screen could not refresh (asleep / parked): said in 【注意】. */
                    val staleNote: String? = null)

/**
 * 1.4.2 (pure): whether the picture must be called old. No new frame since the look asked for one is normal for a still
 * screen that is ON (nothing changed, the last frame is the screen as it is), so that alone says nothing. It matters
 * when the screen could not draw: asleep, dozing or parked. Then the picture may no longer be what the app shows.
 */
internal fun staleFrameNote(lastFrameAtMs: Long, askedAtMs: Long, nowMs: Long, on: Boolean?, parked: Boolean): String? {
    if (lastFrameAtMs <= 0L || lastFrameAtMs >= askedAtMs) return null
    if (on != false && !parked) return null
    val s = ((nowMs - lastFrameAtMs) / 1000).coerceAtLeast(0)
    val why = if (parked) tr("副屏处于省电停放", "the screen is parked to save power") else tr("副屏在休眠", "the screen is asleep")
    return tr("画面是 $s 秒前的旧画面（$why，没有产生新画面）：可能不是应用现在的样子，先 look 确认再动作",
        "The picture is $s s old ($why and drew no new frame): it may not be what the app shows now; look again before acting")
}

private val snapPool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "vscreen-snap").apply { isDaemon = true } }

/**
 * v0.5 — the one way the tool looks at the screen now (four blocks, words targets, look).
 *  - The words are read from the SAME frame the screenshot shows (the list and the picture cannot disagree).
 *  - When the perception thread already read this picture (same frame, or no sampled pixel differs), its reading is
 *    used as is: no second OCR of an unchanged screen. Otherwise OCR runs once, on this frame, and the result is handed
 *    to the perception thread (it will not read the same picture again).
 *  - The accessibility walk and the foreground lookup run in parallel with the OCR.
 *  - If the picture changed while it was being read (a page still fading in), it is read once more.
 */
internal fun VScreenOffloadHandler.snapshot(d: VScreenDisplay, freshMs: Long = 100L): Snap {
    VScreenPerception.ensure(d)
    var attempt = 0
    while (true) {
        val t0 = SystemClock.elapsedRealtime()
        d.requestFreshFrame()
        d.awaitFrameAfter(t0, freshMs)
        val fv = d.latestWithVersion()
        val a11yF = snapPool.submit<List<El>> { runCatching { a11yRaw(d) }.getOrDefault(emptyList()) }
        val actF = snapPool.submit<String?> { runCatching { VScreenShell.topActivity(d.displayId) }.getOrNull() }
        // 2.0: the optional detector looks at the same frame while OCR reads it (null when no model is installed).
        val iconsF = if (fv != null && com.kilon.agentvscreen.core.VScreenDetector.active())
            snapPool.submit<List<Rect>?> { runCatching { com.kilon.agentvscreen.core.VScreenDetector.detectFrame(d.displayId, fv.first, fv.second)?.map { it.box } }.getOrNull() } else null
        var reused = false
        var problem: String? = null
        var fellBack: String? = null
        val items: List<VScreenOcr.Item>? = if (fv == null) null else {
            VScreenPerception.readingFor(d.displayId, fv.first, fv.second)?.let { reused = true; it.items }
                ?: runCatching { VScreenOcr.recognize(d.displayId, fv.first, fv.second, accurate = VScreenOcr.accurateEnabled()) }
                    .getOrElse { t -> VScreenOcr.Result(emptyList(), d.displayId, fv.second, 0L, "${t.javaClass.simpleName}: ${t.message}") }
                    .also { r -> problem = r.error?.let { e -> VScreenOcr.downLine(e) }; fellBack = r.fallback }
                    .takeIf { it.error == null }?.let { r ->
                        val a11yTexts = runCatching { a11yF.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList()).map { it.label }
                        VScreenPerception.offerReading(d.displayId, fv.first, fv.second, r, a11yTexts)
                        r.items
                    }
        }
        val raw = runCatching { a11yF.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
        val act = runCatching { actF.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
        val after = d.latestWithVersion()
        val mask = VScreenPerception.mask(d.displayId)
        if (attempt == 0 && fv != null && after != null && after.second != fv.second && !samePicture(fv.first, after.first, mask)) { attempt++; continue }
        val stale = staleFrameNote(d.lastFrameAtMs, t0, SystemClock.elapsedRealtime(),
            com.kilon.agentvscreen.core.VScreenAwake.stateOf(d)?.let { it == android.view.Display.STATE_ON }, d.parked)
        val icons = iconsF?.let { f -> runCatching { f.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() }
        return Snap(fv?.first, fv?.second ?: -1L, items, mergeElements(d, raw, items?.let { VScreenPerception.still(it, mask) }, icons), act, reused, mask,
            problem ?: VScreenOcr.downLine(null), fellBack, stale)
    }
}

private fun samePicture(a: Bitmap, b: Bitmap, mask: com.kilon.agentvscreen.core.Mask? = null): Boolean {
    if (a === b) return true
    val x = VScreenPerception.small(a); val y = VScreenPerception.small(b)
    // 1.3: a playing area (video, carousel) does not make the page "still changing": it is not read again for it.
    return try { com.kilon.agentvscreen.core.Change.same(x, y, com.kilon.agentvscreen.core.Change.Level.SETTLED, skip = mask?.rectsIn(x.width, x.height).orEmpty()) }
    finally { x.recycle(); y.recycle() }
}

private val PCT = Regex("%[0-9A-Fa-f]{2}")
private val URLISH = Regex("^(https?://|[a-z][a-z0-9+.-]*://)|[?&][\\w%]+=")

/** Words of an element: URL-encoded tracking strings become LINK (with their length), long ones are cut. */
internal fun tidy(raw: String): Pair<ElKind, String> {
    val t = raw.trim()
    if (PCT.findAll(t).count() >= 3 || (t.length > 40 && !t.contains(' ') && URLISH.containsMatchIn(t))) return ElKind.LINK to t.length.toString()
    return ElKind.TEXT to (if (t.length > 40) t.take(40) + "…" else t)
}

internal fun El.line(): String {
    val c = "(${box.centerX()},${box.centerY()})"
    return "#$n “$label” $c" + flags.let { if (it.isNotEmpty()) " $it" else "" }
}
// ── 这段时间 ──────────────────────────────────────────────────────────

/**
 * What happened on [d] between [from] and [to] (epoch ms), from the perception readings and the recording track:
 * words that appeared, went, or came and went; spans when the picture changed, where and how much.
 */
internal fun sinceHeader(from: Long, to: Long, actionMs: Long, receivedAt: Long = 0L): String {
    fun s(ms: Long) = "${(maxOf(0L, ms) / 100) / 10.0}s"
    val span = s(to - from)
    // 1.3: time is split by where it was spent. Before, everything that was not this segment's actions was called
    // "you thinking", including the phone building the last reply and the transfer of the reply and its picture.
    if (receivedAt <= from || receivedAt > to) return if (actionMs > 0 && to - from > actionMs + 300) tr("上次返回到现在 $span（其中这段动作 ${s(actionMs)}）", "$span since the last reply (this segment's actions ${s(actionMs)})") else tr("这段 $span", "this segment $span")
    val outside = receivedAt - from
    val here = to - receivedAt
    return tr("上次返回到现在 $span：手机之外 ${s(outside)}（回复和图片在路上、你在看和思考），这条命令到手机后 ${s(here)}（其中动作和等待 ${s(actionMs)}）",
        "$span since the last reply: ${s(outside)} outside the phone (the reply and picture in transit, you reading and thinking), ${s(here)} since this command reached the phone (actions and waits ${s(actionMs)})")
}

/** 1.3: what the phone spent on this reply, by part (ms), said at the end of 【做了什么】 and in data.timing. */
internal class Timing(val received: Long, val actionMs: Long) {
    var lookMs = 0L; var trackMs = 0L; var pictureMs = 0L
    fun line(total: Long): String = tr("手机端这条用了 ${sec(total)}：动作和等待 ${sec(actionMs)}，读屏 ${sec(lookMs)}，这段时间的录像 ${sec(trackMs)}，出图 ${sec(pictureMs)}",
        "on the phone this took ${sec(total)}: actions and waits ${sec(actionMs)}, reading the screen ${sec(lookMs)}, the meanwhile track ${sec(trackMs)}, the picture ${sec(pictureMs)}")
    fun json(total: Long) = org.json.JSONObject().put("phone_ms", total).put("action_ms", actionMs).put("look_ms", lookMs).put("track_ms", trackMs).put("picture_ms", pictureMs)
    private fun sec(ms: Long) = "${(maxOf(0L, ms) / 100) / 10.0}s"
}

/** Words that appeared, went, or came and went between [from] and [to], from the perception readings. */
internal fun wordFacts(d: VScreenDisplay, from: Long, to: Long): List<String> {
    val lines = ArrayList<String>()
    val rs = VScreenPerception.readings(d.displayId, from, to)
    if (rs.size >= 2) {
        val then = rs.first().words; val now = rs.last().words
        val thenN = then.map { VScreenOcr.norm(it) }.toSet(); val nowN = now.map { VScreenOcr.norm(it) }.toSet()
        // 1.3.0: a reading taken while text was still drawing or sliding in gives pieces of it ("Auracast 音频广",
        // "频广", "o"). A piece of a word that is on the screen now is not a word of its own: it did not appear
        // next to the whole word, and it did not go away.
        fun partOf(k: String, of: Collection<String>) = k.isNotEmpty() && of.any { it.length > k.length && it.contains(k) }
        val appeared = now.filter { val k = VScreenOcr.norm(it); k !in thenN && !partOf(k, nowN) }
        val gone = then.filter { val k = VScreenOcr.norm(it); k !in nowN && !partOf(k, nowN) }
        val between = LinkedHashMap<String, String>()
        // 1.4.2-beta6 (test: an animated screen listed "周-, 0', K, 6, R, 个" here): a short piece read once, in one
        // reading only, is the OCR catching a moving picture mid-frame, not a word that came and went. A word counts
        // when it was read in two readings, or is long enough to be a real word read once (a toast seen briefly).
        val seen = HashMap<String, Int>()
        for (r in rs.drop(1).dropLast(1)) for (k in r.words.map { VScreenOcr.norm(it) }.toSet()) seen[k] = (seen[k] ?: 0) + 1
        fun real(k: String) = (seen[k] ?: 0) >= 2 || k.length >= 4
        for (r in rs.drop(1).dropLast(1)) for (w in r.words) { val k = VScreenOcr.norm(w); if (real(k) && k !in thenN && k !in nowN && !partOf(k, nowN) && !partOf(k, thenN)) between.putIfAbsent(k, w) }
        if (appeared.isNotEmpty()) lines += tr("出现了：", "appeared: ") + list(appeared, 20)
        if (gone.isNotEmpty()) lines += tr("不见了：", "disappeared: ") + list(gone, 20)
        if (between.isNotEmpty()) lines += tr("中途出现过又消失：", "appeared and disappeared meanwhile: ") + list(between.values.toList(), 12)
    }
    return lines
}

/** Where and how long the picture changed between [from] and [to], from the recording track. (lines, changed) */
internal fun pictureFacts(d: VScreenDisplay, from: Long, to: Long): Pair<List<String>, Boolean> {
    val lines = ArrayList<String>()
    // 1.3: an area that keeps playing (video, carousel) is said once and left out of the changes below.
    val mask = VScreenPerception.mask(d.displayId)
    mask?.let { lines += tr("一直在动的区域（视频、轮播或动画，里面的字不列出，稳定判断不算它）：${it.line(true)}", "areas that keep moving (video, carousel or animation; their words are not listed, settling ignores them): ${it.line(false)}") }
    val frames = VScreenRecorder.frames(d.displayId, from, to)
    if (frames.isEmpty()) return lines to false
    val base = VScreenRecorder.frameAt(d.displayId, from)
    val sample = (listOfNotNull(base?.takeIf { it.atMs < (frames.firstOrNull()?.atMs ?: 0L) }) + frames.let { fs ->
        if (fs.size <= 24) fs else (0 until 24).map { fs[it * (fs.size - 1) / 23] }.distinct()
    })
    val pics = sample.mapNotNull { f -> VScreenRecorder.decode(f, 160)?.let { f.atMs to it } }
    val (fw, fh) = VScreenRecorder.frameSize(d.displayId) ?: (d.width to d.height)
    class Span(val start: Long, var end: Long, var area: Rect, var share: Double)
    val spans = ArrayList<Span>()
    try {
        for ((a, b) in pics.zipWithNext()) {
            val rows = VScreenActivity.rowsFor(a.second.width, a.second.height)
            val c = VScreenActivity.cells(a.second, b.second, VScreenActivity.COLS, rows)?.let { cc -> mask?.takeIf { it.cols == VScreenActivity.COLS && it.rows == rows }?.minus(cc) ?: cc } ?: continue
            val share = VScreenActivity.share(c)
            if (share <= 0.0) continue
            val bb = VScreenActivity.bbox(c, VScreenActivity.COLS, rows, fw, fh) ?: continue
            val last = spans.lastOrNull()
            if (last != null && a.first - last.end <= 600L) { last.end = b.first; last.area.union(bb); last.share = maxOf(last.share, share) }
            else spans += Span(a.first, b.first, Rect(bb), share)
        }
        if (spans.isEmpty()) return lines to false
        val firstLast = if (pics.size >= 2) {
            val a = pics.first().second; val b = pics.last().second
            VScreenActivity.cells(a, b, VScreenActivity.COLS, VScreenActivity.rowsFor(a.width, a.height))?.let { c0 -> mask?.takeIf { it.cols == VScreenActivity.COLS && it.rows == VScreenActivity.rowsFor(a.width, a.height) }?.minus(c0) ?: c0 }?.let { c ->
                VScreenActivity.share(c) to VScreenActivity.bbox(c, VScreenActivity.COLS, VScreenActivity.rowsFor(a.width, a.height), fw, fh)
            }
        } else null
        val shown = spans.sortedByDescending { (it.end - it.start) * (it.share + 0.01) }.take(6).sortedBy { it.start }
        lines += tr("画面变化（时间相对现在，区域是副屏坐标，百分比是变了的格子占全屏）：", "picture changes (times relative to now, regions in screen coordinates, percent = changed cells of the whole screen): ") + shown.joinToString(tr("；", "; ")) { sp ->
            val dur = ((sp.end - sp.start) / 100) / 10.0
            "${Report.ago(sp.start, to)}～${if (to - sp.end < 400) tr("现在", "now") else Report.ago(sp.end, to)} (${dur}s) [${Report.box(sp.area)}] ${(sp.share * 100).roundToInt()}%"
        } + if (spans.size > shown.size) tr("；另有 ${spans.size - shown.size} 段较小的变化", "; plus ${spans.size - shown.size} smaller changes") else ""
        firstLast?.let { (share, bb) ->
            lines += if (share <= 0.0) tr("开头和现在的画面一样（中间动过又回去了）", "the start and now look the same (it moved and came back)")
                else tr("开头和现在相比：${(share * 100).roundToInt()}% 的格子不同", "start vs now: ${(share * 100).roundToInt()}% of cells differ") + (bb?.let { " [${Report.box(it)}]" } ?: "")
        }
        // 1.4.0-beta6 (test: "start and now look the same" yet picture_changed and a picture attached): the picture the
        // agent had is the start; when now looks the same, it has nothing new to see.
        return lines to (firstLast?.let { it.first > 0.0 } ?: true)
    } finally { pics.forEach { it.second.recycle() } }
}

private fun list(ws: List<String>, max: Int): String =
    if (I18n.en) ws.take(max).joinToString(", ") { "\"${it.take(30)}\"" } + (if (ws.size > max) " and more, ${ws.size} in all" else "")
    else ws.take(max).joinToString("、") { "“${it.take(30)}”" } + if (ws.size > max) " 等 ${ws.size} 处" else ""

// ── the picture ──────────────────────────────────────────────────────

/**
 * One image for the model: the screen now (long side [Report.NOW_SIDE]) with screen coordinates on its top and left
 * edges, and — when [since] says something changed — a sheet of frames from the track next to it (each cell: how long
 * ago, the actions done before it drawn as a red dot / line). Saved in the chat's attachments and offered inline.
 */
internal fun VScreenOffloadHandler.picture(sid: String, d: VScreenDisplay, frame: Bitmap, since: Since?, tag: String,
                                           cells0: List<Cell>? = null, detail: Detail = Detail.NORMAL, marks: Boolean = true): String? = runCatching {
    val k = minOf(1.0, detail.nowSide.toDouble() / maxOf(frame.width, frame.height))
    val nw = (frame.width * k).roundToInt().coerceAtLeast(1); val nh = (frame.height * k).roundToInt().coerceAtLeast(1)
    val margin = 30; val title = 26
    // The sheet.
    val cells: List<Cell> = cells0 ?: if (since?.changed == true) pickCells(d.displayId, since.from, since.to, Report.MAX_CELLS, Report.CELL_SIDE).cells else emptyList()
    val (fw, fh) = VScreenRecorder.frameSize(d.displayId) ?: (d.width to d.height)
    val landscape = frame.width > frame.height
    val cw = cells.maxOfOrNull { it.bmp.width } ?: 0; val ch = cells.maxOfOrNull { it.bmp.height } ?: 0
    val cap = CAPTION; val pad = CELL_PAD
    val cols = when { cells.isEmpty() -> 0; landscape -> minOf(4, cells.size); cells.size <= 4 -> 1; else -> 2 }
    val rows = if (cols == 0) 0 else (cells.size + cols - 1) / cols
    val sheetW = if (cols == 0) 0 else cols * (cw + pad) + pad
    val sheetH = if (cols == 0) 0 else rows * (ch + cap + pad) + pad + title
    val nowW = margin + nw + pad; val nowH = title + margin + nh + pad
    val totalW = if (landscape) maxOf(nowW, sheetW) else nowW + sheetW
    val totalH = if (landscape) nowH + sheetH else maxOf(nowH, sheetH)
    val out = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
    val c = Canvas(out); c.drawColor(Color.WHITE)
    val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; color = Color.BLACK; isFakeBoldText = true }
    val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; color = Color.rgb(30, 30, 160) }
    val line = Paint().apply { color = Color.rgb(30, 30, 160); strokeWidth = 1f }
    val faint = Paint().apply { color = Color.argb(70, 30, 30, 160); strokeWidth = 1f }
    c.drawText(if (marks) tr("现在（边上是副屏坐标）", "Now (screen coordinates on the edges)") else tr("现在", "Now"), margin.toFloat(), 20f, head)
    val ox = margin; val oy = title + margin
    c.drawBitmap(frame, null, Rect(ox, oy, ox + nw, oy + nh), Paint(Paint.FILTER_BITMAP_FLAG))
    if (marks) drawRuler(c, d, ox, oy, nw, nh, k, tick, line, faint)
    if (cells.isNotEmpty()) {
        val sx = if (landscape) 0 else nowW; val sy = if (landscape) nowH else 0

        c.drawText(tr("这段时间（${Report.ago(since!!.from, since.to)} 到现在；圈=动作的位置，画在动作前那张上）", "Meanwhile (${Report.ago(since!!.from, since.to)} to now; ring = where an action touched, on the frame before it)"), (sx + pad).toFloat(), (sy + 20).toFloat(), head)
        drawCells(c, sx, sy + title, cells, cols, cw, ch, fw, fh, since.to)
    }
    cells.forEach { it.bmp.recycle() }
    savePic(sid, out, tag, detail.use, sent = true).also { out.recycle() }
}.getOrNull()

/** The coordinate ruler on a picture of the whole screen: ticks every 100 screen px, numbers and faint lines every 200 (500 on big screens). */
private fun drawRuler(c: Canvas, d: VScreenDisplay, ox: Int, oy: Int, nw: Int, nh: Int, k: Double, tick: Paint, line: Paint, faint: Paint) {
    // Ticks every 100 screen px, numbers every 200 (every 500 on big screens), faint lines across every 200/500.
    val stepLabel = if (maxOf(d.width, d.height) > 2600) 500 else 200
    var v = 0
    while (v <= d.width) {
        val x = ox + (v * k).toFloat()
        c.drawLine(x, (oy - 6).toFloat(), x, oy.toFloat(), line)
        if (v % stepLabel == 0) { c.drawText(v.toString(), x + 2f, (oy - 8).toFloat(), tick); if (v in 1 until d.width) c.drawLine(x, oy.toFloat(), x, (oy + nh).toFloat(), faint) }
        v += 100
    }
    v = 0
    while (v <= d.height) {
        val y = oy + (v * k).toFloat()
        c.drawLine((ox - 6).toFloat(), y, ox.toFloat(), y, line)
        if (v % stepLabel == 0) {
            c.save(); c.rotate(-90f, (ox - 9).toFloat(), y - 2f); c.drawText(v.toString(), (ox - 9).toFloat(), y - 2f, tick); c.restore()
            if (v in 1 until d.height) c.drawLine(ox.toFloat(), y, (ox + nw).toFloat(), y, faint)
        }
        v += 100
    }
}

/** Save [bmp] as a JPEG in the shot store and return its reference (the API layer turns it into a URL, a local file or an inline image). */
internal fun VScreenOffloadHandler.savePic(sid: String, bmp: Bitmap, tag: String, use: com.kilon.agentvscreen.core.Pictures.Use = Detail.NORMAL.use, sent: Boolean = false): String? = ShotStore.save(context, bmp, tag, use, sent)

// ── the four blocks ──────────────────────────────────────────────────

/**
 * The four-block return, as text for a model and as data for a program, both from the same values: [lines] / [steps]
 * (done), the readings and the track (meanwhile), one snapshot (now), [notes]. [outcome] says how the stretch ended:
 * done | stopped (a stop condition fired: also fine) | missed | refused | interrupted.
 */
internal fun VScreenOffloadHandler.fourBlocks(sid: String, d: VScreenDisplay, lines: List<String>, notes: Notes,
                                              segmentStart: Long, actionMs: Long, tag: String, fullList: Boolean = false,
                                              steps: List<StepOut> = emptyList(), outcome: String = "done", stoppedAt: String? = null): NativeOffloadResult {
    // Whoever is handed 【现在】 has seen the current page: that is the re-read a takeover or restart asks for.
    BackgroundOperation.acknowledgeReread(sid)
    val now = System.currentTimeMillis()
    val last = synchronized(VScreenOffloadHandler.lastReturn) { VScreenOffloadHandler.lastReturn[sid] }
    val from = if (last != null && now - last <= 600_000L && last <= segmentStart) last else segmentStart
    val received = Call.current()?.receivedAt ?: segmentStart
    val timing = Timing(received, actionMs)
    fun finish(sb: StringBuilder, data: org.json.JSONObject, outcome0: String): NativeOffloadResult {
        val total = System.currentTimeMillis() - received
        val tl = timing.line(total)
        val at = sb.indexOf(TIMING_MARK)
        if (at >= 0) sb.replace(at, at + TIMING_MARK.length, tl + "\n")
        data.put("timing", timing.json(total))
        sb.append("\n" + Blocks.notes + "\n")
        if (notes.isEmpty) sb.append(tr("无\n", "none\n")) else notes.list.forEach { sb.append("- ").append(it.line()).append('\n') }
        val ok = outcome0 == "done" || outcome0 == "stopped"
        val code = if (ok) "OK" else notes.firstBlocking() ?: "STEP_MISSED"
        data.put("outcome", outcome0).put("steps", org.json.JSONArray(steps.map { it.json() }))
            .put("stopped_at", stoppedAt ?: org.json.JSONObject.NULL)
            .put("done", org.json.JSONArray(lines)).put("notes", org.json.JSONArray(notes.list.map { it.json() }))
        synchronized(VScreenOffloadHandler.lastReturn) { VScreenOffloadHandler.lastReturn[sid] = System.currentTimeMillis() }
        return NativeOffloadResult(if (ok) 0 else 1, sb.toString(), code, data, softFail = !ok)
    }
    // The screen is gone (privileged connection lost): say so instead of describing an empty screen.
    connectionLost(d)?.let { lost ->
        val sb = StringBuilder(Blocks.done + "\n"); lines.forEach { sb.append(it).append('\n') }
        sb.append("\n" + Blocks.meanwhile + "\n" + tr("（副屏已不存在，没有录屏）", "(the screen no longer exists, no recording)") + "\n\n" + Blocks.now + "\n" + tr("（没有副屏）", "(no screen)") + "\n")
        notes.add("CONNECTION_LOST", lost)
        return finish(sb, org.json.JSONObject().put("screen", org.json.JSONObject.NULL), "interrupted")
    }
    // v0.5: the recording-track work (decoding frames for 【这段时间】 and the sheet) runs while the screen is read.
    val tTrack = SystemClock.elapsedRealtime()
    val picF = REPORT_POOL.submit<Pair<List<String>, Boolean>>(com.kilon.agentvscreen.I18n.carry { runCatching { pictureFacts(d, from, now) }.getOrElse { listOf(tr("（读不到录屏：${it.message}）", "(recording unreadable: ${it.message})")) to false } })
    val cellsF = REPORT_POOL.submit<Picked> { runCatching { pickCells(d.displayId, from, now, Report.MAX_CELLS, Report.CELL_SIDE) }.getOrDefault(Picked(emptyList(), 0, 0, 0)) }
    val snapAt = SystemClock.elapsedRealtime()
    val snap = snapshot(d)
    timing.lookMs = SystemClock.elapsedRealtime() - snapAt
    val (picLines, changed) = runCatching { picF.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList<String>() to false)
    val picked = runCatching { cellsF.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(Picked(emptyList(), 0, 0, 0))
    val cells = picked.cells
    // 1.3 beta5: the sheet also comes when the picture did not change but the stretch touched the screen: a tap that
    // hit nothing is exactly when the agent needs to see where it landed.
    val touched = cells.any { c -> c.marks.any { it.pts.isNotEmpty() } }
    timing.trackMs = maxOf(0L, SystemClock.elapsedRealtime() - tTrack - timing.lookMs)
    val words = wordFacts(d, from, System.currentTimeMillis())
    val sheet = changed || touched
    val sinceLines = ArrayList<String>().apply { add(sinceHeader(from, now, actionMs, received)); addAll(words); addAll(picLines); if (size == 1) add(tr("文字和画面都没有明显变化（光标、细小动画这类小变动不算）", "no clear change in text or picture (cursors and tiny animations don't count)"))
        if (sheet && cells.isNotEmpty()) add(run { val before = cells.count { it.marks.isNotEmpty() }
            tr("拼图 ${cells.size} 张：${before} 张是动作之前（圈和编号是点的位置，拖动是线），其余是开头和动作之后的结果，每张下面写着是哪一种", "Sheet of ${cells.size}: $before before actions (ring and number where it touched, a line for a drag), the others the start and what actions led to; each says which under it") } +
            (if (picked.leftOut > 0) tr("；前 ${picked.leftOut} 个动作没放进来", "; the first ${picked.leftOut} actions are not in it") else "") +
            tr("。要更密、更长或别的时间段：vscreen frames（默认就是这段，--every 0.3s / --last 2m / --from 14:03:10）", ". Denser, longer or another stretch: vscreen frames (this stretch by default; --every 0.3s / --last 2m / --from 14:03:10)")) }
    val since = Since(sinceLines, sheet, from, now)
    synchronized(lastWindows) { lastWindows[sid] = longArrayOf(from, now) }
    val els = numbered(sid, d, snap.els, snap.activity)
    val prev = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }
    synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] = ElSet(d.generation, now, els, snap.activity, snapAt) }
    val shownCells = if (sheet) cells else { cells.forEach { it.bmp.recycle() }; emptyList() }
    val tPic = SystemClock.elapsedRealtime()
    val pic = snap.frame?.let { picture(sid, d, it, since, tag, shownCells) } ?: run { shownCells.forEach { it.bmp.recycle() }; null }
    timing.pictureMs = SystemClock.elapsedRealtime() - tPic
    if (snap.activity == null) notes.add("NO_APP", tr("副屏上没有应用（副屏没有桌面，back 回不到桌面）：launch <应用名> 打开一个", "No app on the screen (the screen has no launcher; back can't reach one): open one with launch <app name>"))
    // 1.4.0-beta2: a reading that failed is said, never left as a short element list that looks complete.
    com.kilon.agentvscreen.core.VScreenOwnership.takeNotes(sid).forEach { notes += it }
    com.kilon.agentvscreen.core.VScreenDownscale.takeNote(d.displayId)?.let { notes += it }
    snap.ocrProblem?.let { notes.add("OCR_DOWN", it) } ?: snap.ocrFallback?.let { notes += it }
    snap.staleNote?.let { notes += it }
    VScreenPerception.blankFor(d.displayId)?.let { ms -> notes += tr("画面已连续 ${(ms / 100) / 10.0} 秒是一整片纯色（黑屏/白屏）", "The picture has been one solid color for ${(ms / 100) / 10.0} s (black / white screen)") }
    val sb = StringBuilder()
    sb.append(Blocks.done + "\n")
    lines.forEach { sb.append(it).append('\n') }
    sb.append(TIMING_MARK)
    sb.append("\n" + Blocks.meanwhile + "\n")
    since.lines.forEach { sb.append(it).append('\n') }
    sb.append("\n" + Blocks.now + " ").append(snap.activity ?: tr("（副屏上没有应用）", "(no app on the screen)")).append(tr("  副屏 ", "  screen ") + "${d.width}x${d.height}")
    if (pic != null) sb.append(tr("  图 ", "  picture ")).append(pic).append(if (since.changed) tr("（左/上是现在，旁边是这段时间的拼图）", " (left / top is now, next to it the strip of meanwhile)") else "")
    sb.append('\n')
    val prevSame = prev?.takeIf { it.generation == d.generation }
    val shown = appendElements(sb, els, prevSame, fullList, snap.items == null)
    val screen = org.json.JSONObject().put("app", snap.activity?.substringBefore('/') ?: org.json.JSONObject.NULL)
        .put("activity", snap.activity ?: org.json.JSONObject.NULL).put("width", d.width).put("height", d.height)
        .put("generation", d.generation).put("image", pic ?: org.json.JSONObject.NULL).put("image_has_strip", since.changed && pic != null)
        .put("elements", org.json.JSONArray(els.take(Report.MAX_JSON_ELEMENTS).map { e -> e.json().also { o -> if (e !in shown) o.put("unchanged", true) } }))
        .put("elements_total", els.size)
    return finish(sb, org.json.JSONObject().put("meanwhile", org.json.JSONArray(since.lines)).put("picture_changed", changed || from != last).put("screen", screen), outcome)
}
private const val TIMING_MARK = "\u0000timing\u0000"
private val REPORT_POOL = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "vscreen-report").apply { isDaemon = true } }

/** Same element as last time: same words, centre within 24 px. */
internal fun sameEl(a: El, b: El) = a.key == b.key && Math.abs(a.box.centerX() - b.box.centerX()) <= 24 && Math.abs(a.box.centerY() - b.box.centerY()) <= 24

/**
 * 1.2.0: a number names one element for as long as the screen lives. The same element keeps its number from list to
 * list, also when it moved (a scroll moves everything); an element that was not in the last list gets a number never
 * given out before on this screen. So an old `#12` can never land on whatever happens to be twelfth now: it is either
 * the same element (wherever it went) or a number no longer on the screen (STALE_ELEMENT).
 *
 * Before, a list that matched less than 60% of the last one (any scroll) was numbered from 1 again, and an agent that
 * used a number from two looks ago tapped a different element without being told.
 */
/** 1.3: the app reported a new window, or the front activity is another one, since [set] was read. */
internal fun pageChangedSince(d: VScreenDisplay, set: ElSet, activity: String?): Boolean =
    (set.activity != null && activity != null && set.activity != activity) ||
        (set.atElapsed > 0 && com.kilon.agentvscreen.host.VScreenAccessibilityService.pageChangedAt(d.displayId) > set.atElapsed)

internal fun numbered(sid: String, d: VScreenDisplay, els: List<El>, activity: String? = null): List<El> {
    val key = "$sid@${d.generation}"
    val prevSet = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }?.takeIf { it.generation == d.generation }
    val book = synchronized(Numbers) { Numbers.of(key) }
    if (els.isEmpty()) return els
    // 1.3: numbers carry over only on the same page. A new window, another front activity, or a list that shares too
    // little with the last one is a new page: every element gets a new number, and an old #N is STALE there.
    val newPage = prevSet == null || pageChangedSince(d, prevSet, activity) || !samePage(prevSet.list, els)
    val prev = if (newPage) emptyList() else prevSet!!.list
    val used = HashSet<Int>()
    // Same words in the same place first; then same words that moved with the whole list (a scroll).
    val match = arrayOfNulls<El>(els.size)
    els.forEachIndexed { i, e -> prev.firstOrNull { sameEl(it, e) && it.n !in used }?.let { match[i] = it; used += it.n } }
    val shift = commonShift(prev, els)?.takeIf { it.third >= 2 }
    if (shift != null) els.forEachIndexed { i, e ->
        if (match[i] != null || e.placeholder) return@forEachIndexed
        val inPrev = prev.filter { it.key == e.key && it.n !in used }
        if (inPrev.size == 1 && els.count { it.key == e.key } == 1 && Math.abs(e.box.centerX() - inPrev[0].box.centerX() - shift.first) <= 24 &&
            Math.abs(e.box.centerY() - inPrev[0].box.centerY() - shift.second) <= 24) { match[i] = inPrev[0]; used += inPrev[0].n }
    }
    return synchronized(Numbers) {
        els.mapIndexed { i, e ->
            val n = match[i]?.n ?: ++book.max
            book.labels[n] = e.label.take(40)
            e.copy(n = n)
        }.also { book.trim() }
    }
}

/** Every number given out on a screen (per session and screen generation), so none is ever reused. */
internal object Numbers {
    /**
     * 1.4.2-beta6: [labels] keeps the [MAX_LABELS] numbers seen most recently (access order: every look re-puts what is
     * on the screen). Before, one long session that scrolled through a long list grew it without end. A number older
     * than that is still never reused ([max] only rises); it is just reported as unknown instead of by its old words.
     */
    class Book {
        var max = 0
        val labels = LinkedHashMap<Int, String>(64, 0.75f, true)
        fun trim() { val it = labels.keys.iterator(); while (labels.size > MAX_LABELS && it.hasNext()) { it.next(); it.remove() } }
    }
    const val MAX_LABELS = 2_000
    private val books = LinkedHashMap<String, Book>()
    fun of(key: String): Book = books.getOrPut(key) { Book() }.also { while (books.size > 16) books.remove(books.keys.first()) }
    /** What [n] was when it was given out on [key]'s screen, or null when it never was. */
    fun label(key: String, n: Int): String? = synchronized(this) { books[key]?.labels?.get(n) }
    internal fun reset() = synchronized(this) { books.clear() }
}

/**
 * The 【现在】 list. On the same page as last time only what is new or changed is printed (the rest keeps its numbers,
 * said in one line); [full] (a look without steps) prints everything. At most [Report.MAX_ELEMENTS] lines; all are kept
 * for #N and zoom.
 */
private fun appendElements(sb: StringBuilder, els: List<El>, prev: ElSet?, full: Boolean, noOcr: Boolean): Set<El> {
    if (els.isEmpty()) { sb.append(if (noOcr) tr("（没读到文字：OCR 没有结果）\n", "(no text read: OCR returned nothing)\n") else tr("（画面上没有可读的文字或控件：看图）\n", "(no readable text or widgets on the screen: look at the picture)\n")); return emptySet() }
    val same = if (full || prev == null) emptyList() else els.filter { e -> prev.list.any { it.n == e.n && sameEl(it, e) } }
    val show = if (same.size >= els.size * 0.6) els - same.toSet() else els
    val keptLine = if (show.size < els.size) tr("（另有 ${els.size - show.size} 个元素和上次【现在】一样，编号不变；act 不带步骤会列出全部）", "(${els.size - show.size} more elements are the same as in the last [Now], numbers unchanged; act without steps lists them all)") else null
    val gone = if (prev != null && show.size < els.size) prev.list.count { p -> els.none { sameEl(it, p) } } else 0
    show.sortedWith(compareBy({ it.box.centerY() }, { it.box.centerX() })).take(Report.MAX_ELEMENTS).forEach { sb.append(it.line()).append('\n') }
    if (show.size > Report.MAX_ELEMENTS) sb.append(tr("（还有 ${show.size - Report.MAX_ELEMENTS} 个没列出：zoom x1,y1,x2,y2 列出那块的全部元素）\n", "(${show.size - Report.MAX_ELEMENTS} more not listed: zoom x1,y1,x2,y2 lists every element in that area)\n"))
    keptLine?.let { sb.append(it).append(if (gone > 0) tr("；上次的 $gone 个不见了", "; $gone from last time are gone") else "").append('\n') }
    return show.toSet()
}

/** 1.1.7: the four block headers (and the error header) in the current language. */
object Blocks {
    val done: String get() = tr("【做了什么】", "[Done]")
    val meanwhile: String get() = tr("【这段时间】", "[Meanwhile]")
    val now: String get() = tr("【现在】", "[Now]")
    val notes: String get() = tr("【注意】", "[Notes]")
    val error: String get() = tr("【错误】", "[Error]")
}
