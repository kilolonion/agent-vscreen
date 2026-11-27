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
                       val detected: Boolean = false,
                       /** 2.0.0-beta8 (L.3): clickable icon button (ImageButton or clickable contentDescription-only node), excluded from page titles. */
                       val iconButton: Boolean = false,
                       /** 2.0.0-beta8 (L.3): AccessibilityNodeInfo.isHeading, preferred for page titles. */
                       val heading: Boolean = false,
                       /** 2.0.0-beta8 (L.3): non-clickable text inside a Toolbar/ActionBar container, preferred for page titles. */
                       val toolbarTitle: Boolean = false,
                       /** 2.0.0-beta16 (Item 5): bounds of plain child accessibility text nodes absorbed into this container. */
                       val textBoxes: List<Rect> = emptyList()) {
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

/** 2.0.0-beta3 (3.1–3.13) & 2.0.0-beta5 (5.1–5.3, 5.8): `act`-only facts passed into [fourBlocks]. */
internal class ActLoudCtx(
    val stepsWritten: List<Step>,
    val firstNoExpectStep: Int?,
    val hasAppUntil: Boolean,
    val lastExpectOnlyVisual: Boolean,
    val startPkg: String?,
    val firstActionMask: com.kilon.agentvscreen.core.Mask?,
    val beforeChanged: Boolean,
    val afterStartMs: Long,
    val forced: Boolean,
    val replayWindow: LongArray? = null,
    val prevReturnAt: Long? = null,
    val excludeWindows: List<LongRange> = emptyList(),
    val rulesFired: List<RuleFired> = emptyList(),
    val hasRules: Boolean = false,
    val usedHold: Boolean = false,
)

internal object Report {
    /** Lines of the 【现在】 list printed at most for look/launch/handoff (all are kept for #N and zoom). */
    const val MAX_ELEMENTS = 160
    /** 3.7: max elements printed for action-type `act` replies. */
    const val ACT_ELEMENTS = 60
    /** Elements given in the structured result at most. */
    const val MAX_JSON_ELEMENTS = 400
    /** 3.7: max characters of a single element text before truncation with `…`. */
    const val MAX_ELEMENT_TEXT = 40
    /** 3.7: text reply budget (estimated tokens via Meter, excluding the element list). */
    const val BUDGET_TOKENS = 1200

    /** 3.5: image sizing constants. */
    const val NOW_SIDE = 1280
    const val FULL_SIDE = 960
    const val THUMB_SIDE = 360
    const val CROP_SIDE = 640
    const val CROP_PAD_PX = 48
    const val PAGE_CHANGE_SHARE = 0.60
    const val MAX_CHANGED_REGIONS = 2
    const val CELL_SIDE = 240
    const val MAX_CELLS = 4

    /** 3.3: short reply limits. */
    const val MAX_QUIET_STEPS_FULL = 10
    const val QUIET_TAIL_STEPS = 3
    const val MAX_QUIET_DIFF = 12

    /** 3.6: event-based 【这段时间】 limits & detection thresholds. */
    const val MAX_APPEARED = 10
    const val MAX_GONE = 10
    const val MAX_BETWEEN = 5
    const val MAX_PIC_SPANS = 3
    const val MIN_SPAN_SHARE = VScreenActivity.CLEAR_CHANGE_SHARE
    const val TOAST_BOTTOM_SHARE = 0.35
    const val TOAST_MAX_MS = 4_000L
    const val MAX_TOASTS = 5
    const val TOAST_MAX_NEW_WORDS = 3
    const val POPUP_MIN_AREA = 0.15
    const val POPUP_MAX_AREA = 0.90
    const val POPUP_DIM_DROP = 0.15
    const val MIN_CONSECUTIVE_READINGS = 2

    /** 5.3: max gap (10 min) for showing "距上次回复 X.Xs" on the first line. */
    const val TIME_HINT_MAX_MS = 10 * 60_000L

    /** "-3.2s" relative to [now]. */
    fun ago(t: Long, now: Long): String = "-${((now - t).coerceAtLeast(0) / 100) / 10.0}s"

    fun box(r: Rect) = "${r.left},${r.top},${r.right},${r.bottom}"
}

/** 2.0.0-beta5 (5.3), pure: " · 距上次回复 X.Xs" when `0 <= sinceLastMs <= TIME_HINT_MAX_MS`, else "". */
internal fun timeHintPart(sinceLastMs: Long?): String {
    if (sinceLastMs == null || sinceLastMs < 0L || sinceLastMs > Report.TIME_HINT_MAX_MS) return ""
    val sec = "${(sinceLastMs / 100) / 10.0}s"
    return tr(" · 距上次回复 $sec", " · $sec since last reply")
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
    fun walk(node: com.kilon.agentvscreen.core.A11yTree.Node, inToolbar: Boolean = false) {
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
        val nextToolbar = inToolbar || (node.className?.let { it.contains("Toolbar", ignoreCase = true) || it.contains("ActionBar", ignoreCase = true) } == true)
        node.children.forEach { walk(it, nextToolbar) }
        if (!inside || !(text != null || (actionable && !big) || n.isEditable)) return
        var words: Pair<ElKind, String>? = text?.let { tidy(it) }
        var absorbedInner = false
        var absorbedBoxes: List<Rect> = emptyList()
        if (words == null && !n.isEditable && (n.isClickable || n.isCheckable)) {
            // Plain words drawn inside this tappable node (not inside a tappable child of it).
            val inner = (start until raw.size).filter { i -> raw[i].let { it.plain && !it.placeholder && b.contains(it.box.centerX(), it.box.centerY()) } }
            if (inner.isNotEmpty()) {
                words = ElKind.TEXT to inner.map { raw[it].text }.distinct().joinToString(" · ").let { if (it.length > 60) it.take(58) + "…" else it }
                absorbedBoxes = inner.map { Rect(raw[it].box) }
                inner.asReversed().forEach { raw.removeAt(it) }
                absorbedInner = true
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
            search = n.isClickable && ((n.viewIdResourceName?.substringAfterLast('/') ?: "") + " " + (n.className ?: "")).contains("search", ignoreCase = true),
            iconButton = node.isIconButton && !absorbedInner,
            heading = node.heading,
            toolbarTitle = inToolbar && !n.isClickable && node.hasText,
            textBoxes = absorbedBoxes))
    }
    com.kilon.agentvscreen.core.A11yTree.snapshot(d.displayId).roots.forEach { walk(it, false) }
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
internal fun mergeElements(d: VScreenDisplay, a11y: List<El>, ocr: List<VScreenOcr.Item>?, icons: List<Rect>? = null): List<El> =
    mergeElements(d.height, a11y, ocr, icons)

internal fun mergeElements(screenHeight: Int, a11y: List<El>, ocr: List<VScreenOcr.Item>?, icons: List<Rect>? = null): List<El> {
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
    // 2.0.0-beta16 (Item 5): icon glyphs (<= 2 normalized chars) misread by OCR inside a clickable accessibility row
    // (single-label, title+summary, with right-side switch, etc.) belong to the row when they do not overlap any
    // accessibility text box of that row and sit on a detected icon box or in the row's leading icon zone.
    // Absorbing them into usedOcr keeps them out of both [Now] and collect (which consumes this fused list).
    val iconList = icons.orEmpty()
    fun onDetectedIcon(b: Rect): Boolean = iconList.any { ic ->
        ic.contains(b.centerX(), b.centerY()) || run {
            val inter = Rect(b)
            inter.intersect(ic) && area(inter) * 2L >= area(b)
        }
    }
    for (row in a11y) {
        if (!row.fromA11y || !row.clickable || row.scrollable || row.editable ||
            row.kind == ElKind.LINK || row.kind == ElKind.EMPTY_INPUT) continue
        val childTextBoxes = a11y.filter { c ->
            c !== row && c.kind == ElKind.TEXT && !c.editable && c.text.isNotBlank() &&
                row.box.contains(c.box.centerX(), c.box.centerY()) && area(c.box) < area(row.box)
        }.map { it.box }
        val hasRowText = (row.kind == ElKind.TEXT && row.text.isNotBlank()) || childTextBoxes.isNotEmpty()
        if (!hasRowText) continue
        val rowTextBoxes = row.textBoxes + childTextBoxes
        val rowNorm = VScreenOcr.norm(row.text)
        val textLeft = rowTextBoxes.minOfOrNull { it.left }
        val isHorizontalRow = row.box.width() >= row.box.height() * 3 / 2
        val leadingRight = row.box.left + minOf(row.box.width() / 4, row.box.height() * 3 / 2)
        for (it in items) {
            if (it in usedOcr) continue
            if (!row.box.contains(it.box.centerX(), it.box.centerY())) continue
            if (rowTextBoxes.any { tb -> Rect.intersects(tb, it.box) }) continue
            val nt = VScreenOcr.norm(it.text)
            if (nt.length > 2) continue
            // When the row has no distinct child text boxes (self-labelled node), avoid swallowing real text of
            // short buttons/counters or digit updates on a digit-bearing widget.
            if (rowTextBoxes.isEmpty()) {
                if (rowNorm.length <= 2 || saysWord(rowNorm, nt)) continue
                if (DIGIT.containsMatchIn(row.text) && DIGIT.containsMatchIn(it.text)) continue
            }
            val inDetected = onDetectedIcon(it.box)
            val inLeadingIconZone = isHorizontalRow &&
                it.box.centerX() <= leadingRight &&
                it.box.width() <= row.box.height() &&
                it.box.height() <= row.box.height() &&
                (textLeft == null || it.box.centerX() < textLeft)
            if (inDetected || inLeadingIconZone) {
                usedOcr += it
            }
        }
    }
    for (a0 in a11y) {
        if (a0 in dropped) continue
        val a = if (a0 in tappable && !a0.clickable) a0.copy(clickable = true) else a0
        if (!a.placeholder && DIGIT.containsMatchIn(a.text) && a.text.length <= 24) {
            val drawn = items.filter { it !in usedOcr && Rect(a.box).apply { inset(-8, -8) }.contains(it.box.centerX(), it.box.centerY()) && DIGIT.containsMatchIn(it.text) }
            val drawnText = ScreenText.clean(drawn.joinToString(" ") { it.text.trim() }, maxLen = 0)
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
                val row = maxOf(16, screenHeight / 120)
                val words = inner.sortedWith(compareBy({ it.box.centerY() / row }, { it.box.centerX() })).joinToString(" · ") { ScreenText.clean(it.text, maxLen = 0) }
                val w = tidy(words)
                out += a.copy(text = if (w.first == ElKind.TEXT) w.second else "", kind = w.first, hint = if (w.first == ElKind.LINK) w.second else null)
                continue
            }
        }
        out += a
    }
    for (it in items) {
        if (it in usedOcr) continue
        val t = ScreenText.clean(it.text, maxLen = 0)
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
    val row = maxOf(16, screenHeight / 120)
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
        val frameEpochMs = System.currentTimeMillis()
        val a11yF = snapPool.submit<List<El>> { runCatching { a11yRaw(d) }.getOrDefault(emptyList()) }
        val actF = snapPool.submit<String?> { runCatching { VScreenShell.topActivity(d.displayId) }.getOrNull() }
        // 2.0.0-beta8 (D): the optional detector runs concurrently with OCR on the shared non-spinning ORT thread pool.
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
                        val a11yList = runCatching { a11yF.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
                        val a11yBoxes = HashMap<String, Rect>()
                        for (el in a11yList) if (!el.placeholder) a11yBoxes.putIfAbsent(VScreenOcr.norm(el.text), el.box)
                        VScreenPerception.offerReading(d.displayId, fv.first, fv.second, r, a11yList.map { it.label }, a11yBoxes, frameEpochMs)
                        r.items
                    }
        }
        val raw = runCatching { a11yF.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
        val act = runCatching { actF.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
        val after = d.latestWithVersion()
        val mask = VScreenPerception.mask(d.displayId)
        if (attempt == 0 && fv != null && after != null && after.second != fv.second && !samePicture(fv.first, after.first, mask)) { attempt++; continue }
        if (fv != null && items != null && (after == null || after.second == fv.second || samePicture(fv.first, after.first, mask))) {
            VScreenPerception.confirmLatest(d.displayId)
        }
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
    val t = ScreenText.clean(raw, maxLen = 0)
    if (PCT.findAll(t).count() >= 3 || (t.length > Report.MAX_ELEMENT_TEXT && !t.contains(' ') && URLISH.containsMatchIn(t))) return ElKind.LINK to t.length.toString()
    return ElKind.TEXT to (if (t.length > Report.MAX_ELEMENT_TEXT) t.take(Report.MAX_ELEMENT_TEXT) + "…" else t)
}

internal fun El.line(): String {
    val c = "(${box.centerX()},${box.centerY()})"
    val lbl = ScreenText.quote(label, Report.MAX_ELEMENT_TEXT)
    return "#$n $lbl $c" + flags.let { if (it.isNotEmpty()) " $it" else "" }
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

/** 2.0.0-beta3 (3.6 & 3.7): separated static/toast word lines vs trimmable between-word lines. */
internal data class WordEvents(
    val staticLines: List<String>,
    val betweenLines: List<String>,
) {
    val allLines: List<String> get() = staticLines + betweenLines
}

/**
 * 2.0.0-beta3 (3.6) + 2.0.0-beta8 (C5), pure: event-based word facts from perception readings.
 * - Appeared / disappeared / between words must persist in at least [Report.MIN_CONSECUTIVE_READINGS] (2)
 *   consecutive readings; single-flash words are suppressed.
 * - OCR-based toast guessing was removed in beta8 (C5): system Toasts come exclusively from accessibility
 *   `TYPE_NOTIFICATION_STATE_CHANGED` events via [formatSystemToasts].
 * - Caps: appeared <= [Report.MAX_APPEARED] (10), gone <= [Report.MAX_GONE] (10), between <= [Report.MAX_BETWEEN] (5).
 */
internal fun wordFactsFromReadings(rs: List<VScreenPerception.Reading>, @Suppress("UNUSED_PARAMETER") screenH: Int, @Suppress("UNUSED_PARAMETER") toMs: Long): WordEvents {
    if (rs.size < 2) return WordEvents(emptyList(), emptyList())
    data class Slot(val atMs: Long, val lastAtMs: Long, val words: List<String>, val normSet: Set<String>, val boxes: Map<String, Rect>)
    val slots = ArrayList<Slot>(rs.size * 2)
    for (r in rs) {
        val normSet = r.words.map { VScreenOcr.norm(it) }.toSet()
        val copies = r.hits.coerceIn(1, Report.MIN_CONSECUTIVE_READINGS)
        for (c in 0 until copies) {
            val t = if (c == 0) r.atMs else r.lastAtMs
            slots += Slot(t, r.lastAtMs, r.words, normSet, r.boxes)
        }
    }
    if (slots.size < 2) return WordEvents(emptyList(), emptyList())

    val startSlot = slots.first()
    val endSlot = slots.last()
    val thenN = startSlot.normSet
    val nowN = endSlot.normSet

    fun partOf(k: String, of: Collection<String>) = k.isNotEmpty() && of.any { it.length > k.length && it.contains(k) }

    // Appeared: absent at start, persisted in the trailing MIN_CONSECUTIVE_READINGS (2) readings (shared with 5.2 rules).
    val appeared = endSlot.words.filter { w ->
        val k = VScreenOcr.norm(w)
        k.isNotEmpty() && k !in thenN && !partOf(k, nowN) &&
            Persistence.trailingPersisted(slots.mapIndexed { idx, s -> Obs(idx.toLong(), k in s.normSet) })
    }.distinctBy { VScreenOcr.norm(it) }

    // Disappeared: present at start, absent across the trailing MIN_CONSECUTIVE_READINGS (2) readings.
    val gone = startSlot.words.filter { w ->
        val k = VScreenOcr.norm(w)
        k.isNotEmpty() && !partOf(k, nowN) &&
            Persistence.trailingPersisted(slots.mapIndexed { idx, s -> Obs(idx.toLong(), k !in s.normSet) })
    }.distinctBy { VScreenOcr.norm(it) }

    // Transient words (appeared after start and disappeared before end): require >=2 consecutive readings.
    val midCandidates = LinkedHashMap<String, String>()
    for (s in slots.drop(1).dropLast(1)) {
        for (w in s.words) {
            val k = VScreenOcr.norm(w)
            if (k.isNotEmpty() && k !in thenN && k !in nowN && !partOf(k, nowN) && !partOf(k, thenN)) {
                midCandidates.putIfAbsent(k, w)
            }
        }
    }
    val between = ArrayList<String>()
    for ((k, raw) in midCandidates) {
        val obsSeq = slots.mapIndexed { idx, s -> Obs(idx.toLong(), k in s.normSet) }
        if (Persistence.maxRun(obsSeq) >= Report.MIN_CONSECUTIVE_READINGS) {
            between += raw
        }
    }

    val staticLines = ArrayList<String>()
    if (appeared.isNotEmpty()) staticLines += tr("出现了：", "appeared: ") + list(appeared, Report.MAX_APPEARED)
    if (gone.isNotEmpty()) staticLines += tr("不见了：", "disappeared: ") + list(gone, Report.MAX_GONE)
    val betweenLines = if (between.isNotEmpty()) listOf(tr("中途出现过又消失：", "appeared and disappeared meanwhile: ") + list(between, Report.MAX_BETWEEN)) else emptyList()
    return WordEvents(staticLines, betweenLines)
}

/**
 * 2.0.0-beta8 (C5), pure: format real system Toast accessibility events matching the virtual display's
 * foreground package [vdPkg] within [[fromMs], [toMs]]. When [mainTopPkg] equals [vdPkg], still includes
 * the toast and notes that it may have originated from the same app on the main screen.
 */
internal fun formatSystemToasts(
    events: List<com.kilon.agentvscreen.host.VScreenAccessibilityService.ToastEvent>,
    vdPkg: String?,
    mainTopPkg: String?,
    fromMs: Long,
    toMs: Long,
): List<String> {
    if (vdPkg.isNullOrBlank() || events.isEmpty()) return emptyList()
    val sameOnMain = !mainTopPkg.isNullOrBlank() && mainTopPkg == vdPkg
    val seen = LinkedHashSet<String>()
    val out = ArrayList<String>()
    for (ev in events) {
        if (ev.packageName != vdPkg) continue
        if (ev.epochMs < fromMs || ev.epochMs > toMs) continue
        val text = ScreenText.clean(ev.text, maxLen = 80)
        if (text.isEmpty() || !seen.add(text)) continue
        val ago = Report.ago(ev.epochMs, toMs)
        val suffix = if (sameOnMain) tr("（可能来自主屏上的同一应用）", " (may be from the same app on the main screen)") else ""
        val quoted = ScreenText.quote(text, maxLen = 80)
        out += tr("toast：$quoted（$ago）$suffix", "toast: $quoted ($ago)$suffix")
        if (out.size >= Report.MAX_TOASTS) break
    }
    return out
}

/** Words that appeared, went, or came and went between [from] and [to], from the perception readings. */
internal fun wordFacts(d: VScreenDisplay, from: Long, to: Long): List<String> =
    wordFactsFromReadings(VScreenPerception.readings(d.displayId, from, to), d.height, to).allLines

internal fun wordEvents(d: VScreenDisplay, from: Long, to: Long): WordEvents =
    wordFactsFromReadings(VScreenPerception.readings(d.displayId, from, to), d.height, to)

/**
 * 2.0.0-beta3 (3.6), pure: whether [region] (in [before]/[after] pixel coords) qualifies as a popup ("弹出").
 * All three conditions must hold simultaneously:
 *   1. Area is 15%–90% of the screen (`POPUP_MIN_AREA..POPUP_MAX_AREA`).
 *   2. Does not touch top edge (`top > 0`) nor bottom edge (`bottom < h`).
 *   3. Mean luminance outside [region] dropped by > 15% (`POPUP_DIM_DROP`, dimmed scrim).
 */
internal fun isPopup(before: Bitmap, after: Bitmap, region: Rect): Boolean {
    if (before.isRecycled || after.isRecycled) return false
    val w = before.width; val h = before.height
    if (w <= 0 || h <= 0 || after.width != w || after.height != h) return false
    val r = Rect(region.left.coerceIn(0, w), region.top.coerceIn(0, h), region.right.coerceIn(0, w), region.bottom.coerceIn(0, h))
    if (r.width() <= 0 || r.height() <= 0) return false
    val areaShare = (r.width().toDouble() * r.height().toDouble()) / (w.toDouble() * h.toDouble())
    if (areaShare < Report.POPUP_MIN_AREA || areaShare > Report.POPUP_MAX_AREA) return false
    if (r.top <= 0 || r.bottom >= h) return false
    val stepX = maxOf(1, w / 48)
    val stepY = maxOf(1, h / 64)
    var sumBefore = 0L
    var sumAfter = 0L
    var count = 0L
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            if (!r.contains(x, y)) {
                sumBefore += VScreenActivity.lum(before.getPixel(x, y))
                sumAfter += VScreenActivity.lum(after.getPixel(x, y))
                count++
            }
            x += stepX
        }
        y += stepY
    }
    if (count == 0L) return false
    val meanBefore = sumBefore.toDouble() / count
    if (meanBefore <= 1.0) return false
    val meanAfter = sumAfter.toDouble() / count
    val drop = (meanBefore - meanAfter) / meanBefore
    return drop > Report.POPUP_DIM_DROP
}

/**
 * Locate the popup region between [before] and [after] (in bitmap pixel coords).
 * Tests [changedBox] first; if a full-screen dim scrim caused the whole frame to mark as changed,
 * extracts the largest non-dimmed foreground box and checks it with the exact same 3-condition [isPopup].
 */
internal fun findPopupBox(before: Bitmap, after: Bitmap, changedBox: Rect): Rect? {
    if (isPopup(before, after, changedBox)) return changedBox
    val w = before.width; val h = before.height
    if (w <= 0 || h <= 0 || after.width != w || after.height != h) return null
    val cols = VScreenActivity.COLS
    val rows = VScreenActivity.rowsFor(w, h)
    val bright = BooleanArray(cols * rows)
    val stepX = maxOf(1, w / (cols * 2))
    val stepY = maxOf(1, h / (rows * 2))
    for (cy in 0 until rows) {
        val y0 = cy * h / rows; val y1 = (cy + 1) * h / rows
        for (cx in 0 until cols) {
            val x0 = cx * w / cols; val x1 = (cx + 1) * w / cols
            var sb = 0L; var sa = 0L; var n = 0L
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    sb += VScreenActivity.lum(before.getPixel(x, y))
                    sa += VScreenActivity.lum(after.getPixel(x, y))
                    n++
                    x += stepX
                }
                y += stepY
            }
            if (n > 0 && sb > 0 && sa.toDouble() >= sb.toDouble() * (1.0 - Report.POPUP_DIM_DROP)) {
                bright[cy * cols + cx] = true
            }
        }
    }
    val candidates = VScreenActivity.boxes(bright, cols, rows, w, h)
    return candidates.firstOrNull { isPopup(before, after, it) }
}

/** 2.0.0-beta3 (3.6 & 3.13): one contiguous visual change span detected on the recording track. */
internal class PicSpan(
    val start: Long,
    var end: Long,
    var area: Rect,
    var share: Double,
    var popupArea: Rect? = null,
)

/** 2.0.0-beta3 (3.13): pure output of frame-sequence analysis shared by 【这段时间】 and BEFORE/AFTER. */
internal data class FrameAnalysis(
    val spans: List<PicSpan>,
    val firstLast: Pair<Double, Rect?>?,
) {
    val maxShare: Double get() = spans.maxOfOrNull { it.share } ?: 0.0
    val hasPopup: Boolean get() = spans.any { it.popupArea != null }
    val hasSignificantChange: Boolean get() = maxShare >= Loud.CHANGE_RATIO || hasPopup
}

/**
 * 2.0.0-beta3 (3.6 & 3.13), pure: analyze an ordered list of `(timestampMs, bitmap)` frames for visual
 * change spans and popups, excluding cells in [mask]. Detects intermediate changes such as A → B → A
 * even when the first and last frames are identical.
 */
internal fun analyzeFrameSequence(
    pics: List<Pair<Long, Bitmap>>,
    fw: Int,
    fh: Int,
    mask: com.kilon.agentvscreen.core.Mask?,
    excludeWindows: List<LongRange> = emptyList(),
): FrameAnalysis {
    val spans = ArrayList<PicSpan>()
    for ((a, b) in pics.zipWithNext()) {
        if (excludeWindows.isNotEmpty() && excludeWindows.any { b.first in it }) continue
        val cols = mask?.cols ?: VScreenActivity.COLS
        val rows = mask?.rows ?: VScreenActivity.rowsFor(a.second.width, a.second.height)
        val c = VScreenActivity.cells(a.second, b.second, cols, rows)?.let { cc -> mask?.minus(cc) ?: cc } ?: continue
        val share = VScreenActivity.share(c)
        if (share < Report.MIN_SPAN_SHARE) continue
        val bbSmall = VScreenActivity.bbox(c, cols, rows, a.second.width, a.second.height) ?: continue
        val bb = VScreenActivity.bbox(c, cols, rows, fw, fh) ?: continue
        val popSmall = findPopupBox(a.second, b.second, bbSmall)
        val popFull = popSmall?.let { p ->
            Rect(
                (p.left.toLong() * fw / maxOf(1, a.second.width)).toInt(),
                (p.top.toLong() * fh / maxOf(1, a.second.height)).toInt(),
                (p.right.toLong() * fw / maxOf(1, a.second.width)).toInt(),
                (p.bottom.toLong() * fh / maxOf(1, a.second.height)).toInt(),
            )
        }
        val last = spans.lastOrNull()
        if (last != null && a.first - last.end <= 600L && (popFull == null) == (last.popupArea == null)) {
            last.end = b.first
            last.area.union(bb)
            last.share = maxOf(last.share, share)
            if (popFull != null) last.popupArea = popFull
        } else {
            spans += PicSpan(a.first, b.first, Rect(bb), share, popFull)
        }
    }
    val firstLast = if (spans.isNotEmpty() && pics.size >= 2) {
        val a = pics.first().second; val b = pics.last().second
        val cols = mask?.cols ?: VScreenActivity.COLS
        val rows = mask?.rows ?: VScreenActivity.rowsFor(a.width, a.height)
        VScreenActivity.cells(a, b, cols, rows)?.let { c0 -> mask?.minus(c0) ?: c0 }?.let { c ->
            VScreenActivity.share(c) to VScreenActivity.bbox(c, cols, rows, fw, fh)
        }
    } else null
    return FrameAnalysis(spans, firstLast)
}

/** 2.0.0-beta3 (3.6, 3.7 & 3.13): structured picture events shared by 【这段时间】 and BEFORE/AFTER. */
internal data class PicEvents(
    val maskLine: String?,
    val detailLines: List<String>,
    val spanCount: Int,
    val changed: Boolean,
    val maxShare: Double = 0.0,
    val hasPopup: Boolean = false,
) {
    val allLines: List<String> get() = listOfNotNull(maskLine) + detailLines
    val hasSignificantChange: Boolean get() = maxShare >= Loud.CHANGE_RATIO || hasPopup
}

/** Where and how long the picture changed between [from] and [to], from the recording track. */
internal fun pictureEvents(
    d: VScreenDisplay,
    from: Long,
    to: Long,
    maskOverride: com.kilon.agentvscreen.core.Mask? = null,
    excludeWindows: List<LongRange> = emptyList(),
): PicEvents {
    // 1.3: an area that keeps playing (video, carousel) is said once and left out of the changes below.
    val mask = maskOverride ?: VScreenPerception.mask(d.displayId)
    val maskLine = mask?.let { tr("一直在动的区域（视频、轮播或动画，里面的字不列出，稳定判断不算它）：${it.line(true)}", "areas that keep moving (video, carousel or animation; their words are not listed, settling ignores them): ${it.line(false)}") }
    val frames = VScreenRecorder.frames(d.displayId, from, to)
    if (frames.isEmpty()) return PicEvents(maskLine, emptyList(), 0, false, maxShare = 0.0, hasPopup = false)
    val base = VScreenRecorder.frameAt(d.displayId, from)
    val sampledFrames = frames.let { fs ->
        if (fs.size <= 24) {
            fs
        } else {
            val basePick = (0 until 24).map { fs[it * (fs.size - 1) / 23] }
            if (excludeWindows.isEmpty()) {
                basePick.distinct()
            } else {
                // Preserve the last frame inside each rule-claimed window so subsampling never drops the post-rule settled anchor.
                val anchors = excludeWindows.mapNotNull { w -> fs.lastOrNull { it.atMs in w } }
                (basePick + anchors).distinct().sortedBy { it.atMs }
            }
        }
    }
    val sample = listOfNotNull(base?.takeIf { it.atMs < (sampledFrames.firstOrNull()?.atMs ?: 0L) }) + sampledFrames
    val pics = sample.mapNotNull { f -> VScreenRecorder.decode(f, 160)?.let { f.atMs to it } }
    val (fw, fh) = VScreenRecorder.frameSize(d.displayId) ?: (d.width to d.height)
    try {
        val analysis = analyzeFrameSequence(pics, fw, fh, mask, excludeWindows)
        val spans = analysis.spans
        if (spans.isEmpty()) return PicEvents(maskLine, emptyList(), 0, false, maxShare = 0.0, hasPopup = false)
        val firstLast = analysis.firstLast
        val readings = VScreenPerception.readings(d.displayId, from, to)
        val priorWords = readings.firstOrNull()?.words?.map { VScreenOcr.norm(it) }?.toSet().orEmpty()
        fun popupWords(sp: PicSpan): String {
            val box = sp.popupArea ?: return ""
            val found = LinkedHashSet<String>()
            for (r in readings) {
                if (r.atMs < sp.start - 500L || r.atMs > sp.end + 1_500L) continue
                for (w in r.words) {
                    val k = VScreenOcr.norm(w)
                    if (k in priorWords) continue
                    val wb = r.boxes[k] ?: continue
                    if (box.contains(wb.centerX(), wb.centerY())) {
                        found += ScreenText.quote(w, maxLen = 20)
                        if (found.size >= 3) break
                    }
                }
                if (found.size >= 3) break
            }
            return if (found.isEmpty()) "" else tr("：", ": ") + found.joinToString(" / ")
        }

        // 3.6: keep at most MAX_PIC_SPANS (3) spans (prioritizing popups, then size*duration), chronological order.
        val shown = spans.sortedWith(
            compareByDescending<PicSpan> { it.popupArea != null }
                .thenByDescending { (it.end - it.start).coerceAtLeast(100L) * (it.share + 0.01) }
        ).take(Report.MAX_PIC_SPANS).sortedBy { it.start }

        val detailLines = ArrayList<String>()
        for (sp in shown) {
            if (sp.popupArea != null) {
                detailLines += "${Report.ago(sp.start, to)} ${tr("弹出", "popup")} [${Report.box(sp.popupArea!!)}]${popupWords(sp)}"
            } else {
                val dur = ((sp.end - sp.start) / 100) / 10.0
                detailLines += "${Report.ago(sp.start, to)} ${tr("变化", "changed")} [${Report.box(sp.area)}] ${(sp.share * 100).roundToInt()}% (${dur}s)"
            }
        }
        if (spans.size > shown.size) {
            detailLines += tr("另有 ${spans.size - shown.size} 段较小的变化", "plus ${spans.size - shown.size} smaller changes")
        }
        firstLast?.let { (share, bb) ->
            detailLines += if (share < Report.MIN_SPAN_SHARE) tr("开头和现在的画面一样（中间动过又回去了）", "the start and now look the same (it moved and came back)")
            else tr("开头和现在相比：${(share * 100).roundToInt()}% 的格子不同", "start vs now: ${(share * 100).roundToInt()}% of cells differ") + (bb?.let { " [${Report.box(it)}]" } ?: "")
        }
        return PicEvents(
            maskLine = maskLine,
            detailLines = detailLines,
            spanCount = spans.size,
            changed = firstLast?.let { it.first >= Report.MIN_SPAN_SHARE } ?: true,
            maxShare = analysis.maxShare,
            hasPopup = analysis.hasPopup,
        )
    } finally { pics.forEach { it.second.recycle() } }
}

/**
 * 2.0.0-beta3 (3.13) & 2.0.0-beta5 (5.8 #2): unified BEFORE / AFTER detector built on [pictureEvents].
 * Recording only stores frames on picture change:
 * - Track does not cover [fromMs] (missing, older than retention, or pruned) -> true (uncertain = changed).
 * - Track covers [fromMs] and window has no new frames -> false (unchanged).
 * - Otherwise -> true iff any non-excluded span has `share >= Loud.CHANGE_RATIO` or is marked as popup.
 */
internal fun hasPictureEvent(
    d: VScreenDisplay,
    fromMs: Long?,
    toMs: Long,
    maskOverride: com.kilon.agentvscreen.core.Mask? = null,
    excludeWindows: List<LongRange> = emptyList(),
): Boolean {
    if (fromMs == null || !VScreenRecorder.coversFrom(d.displayId, fromMs, toMs)) return true
    return pictureEvents(d, fromMs, toMs, maskOverride, excludeWindows).hasSignificantChange
}

internal fun pictureFacts(d: VScreenDisplay, from: Long, to: Long): Pair<List<String>, Boolean> =
    pictureEvents(d, from, to).let { it.allLines to it.changed }

private fun list(ws: List<String>, max: Int): String =
    if (I18n.en) ws.take(max).joinToString(", ") { ScreenText.quote(it, maxLen = 30) } + (if (ws.size > max) " and more, ${ws.size} in all" else "")
    else ws.take(max).joinToString("、") { ScreenText.quote(it, maxLen = 30) } + if (ws.size > max) " 等 ${ws.size} 处" else ""

// ── the picture ──────────────────────────────────────────────────────

/**
 * One image for the model: the screen now (long side [sideOverride] or [detail.nowSide]) with screen coordinates on its
 * top and left edges, and — when [cells0] is non-empty — a sheet of frames from the track next to it.
 * Note: `look` calls this with `sideOverride = null` so 3.5's 960px cap does not affect explicit `look` requests.
 */
internal fun VScreenOffloadHandler.picture(
    sid: String,
    d: VScreenDisplay,
    frame: Bitmap,
    since: Since?,
    tag: String,
    cells0: List<Cell>? = null,
    detail: Detail = Detail.NORMAL,
    marks: Boolean = true,
    sideOverride: Int? = null,
): String? = runCatching {
    val maxSide = sideOverride ?: detail.nowSide
    val k = minOf(1.0, maxSide.toDouble() / maxOf(frame.width, frame.height))
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
    if (cells.isNotEmpty() && since != null) {
        val sx = if (landscape) 0 else nowW; val sy = if (landscape) nowH else 0
        c.drawText(tr("这段时间（${Report.ago(since.from, since.to)} 到现在；圈=动作的位置，画在动作前那张上）", "Meanwhile (${Report.ago(since.from, since.to)} to now; ring = where an action touched, on the frame before it)"), (sx + pad).toFloat(), (sy + 20).toFloat(), head)
        drawCells(c, sx, sy + title, cells, cols, cw, ch, fw, fh, since.to)
    }
    cells.forEach { it.bmp.recycle() }
    savePic(sid, out, tag, detail.use, sent = true).also { out.recycle() }
}.getOrNull()

/**
 * 2.0.0-beta3 (3.5): composite image for 1–2 changed regions (total <= 60% of screen):
 * Left = full thumbnail (long side [Report.THUMB_SIDE] = 360) with changed regions boxed;
 * Right = expanded crops (+[Report.CROP_PAD_PX] = 48px, long side <= [Report.CROP_SIDE] = 640) with top-left &
 * bottom-right screen coordinates printed on each crop. Optional [cells] sheet appended in the same single image.
 */
internal fun VScreenOffloadHandler.pictureRegions(
    sid: String,
    d: VScreenDisplay,
    frame: Bitmap,
    regions: List<Rect>,
    since: Since?,
    tag: String,
    cells: List<Cell> = emptyList(),
    detail: Detail = Detail.NORMAL,
): String? = runCatching {
    val pad = 10
    val titleH = 24
    val tk = minOf(1.0, Report.THUMB_SIDE.toDouble() / maxOf(frame.width, frame.height))
    val tw = (frame.width * tk).roundToInt().coerceAtLeast(1)
    val th = (frame.height * tk).roundToInt().coerceAtLeast(1)

    data class CropItem(val src: Rect, val dw: Int, val dh: Int)
    val crops = regions.take(Report.MAX_CHANGED_REGIONS).map { r ->
        val exp = Rect(
            (r.left - Report.CROP_PAD_PX).coerceIn(0, frame.width),
            (r.top - Report.CROP_PAD_PX).coerceIn(0, frame.height),
            (r.right + Report.CROP_PAD_PX).coerceIn(1, frame.width),
            (r.bottom + Report.CROP_PAD_PX).coerceIn(1, frame.height),
        )
        val ck = minOf(1.0, Report.CROP_SIDE.toDouble() / maxOf(exp.width(), exp.height()).coerceAtLeast(1))
        CropItem(exp, (exp.width() * ck).roundToInt().coerceAtLeast(1), (exp.height() * ck).roundToInt().coerceAtLeast(1))
    }

    val leftW = pad + tw + pad
    val leftH = titleH + pad + th + pad
    val rightW = if (crops.isEmpty()) 0 else pad + crops.maxOf { it.dw } + pad
    val rightH = if (crops.isEmpty()) 0 else titleH + crops.sumOf { it.dh + pad } + pad
    val mainW = leftW + rightW
    val mainH = maxOf(leftH, rightH)

    val (fw, fh) = VScreenRecorder.frameSize(d.displayId) ?: (d.width to d.height)
    val cw = cells.maxOfOrNull { it.bmp.width } ?: 0
    val ch = cells.maxOfOrNull { it.bmp.height } ?: 0
    val cols = if (cells.isEmpty()) 0 else minOf(4, cells.size)
    val rows = if (cols == 0) 0 else (cells.size + cols - 1) / cols
    val sheetW = if (cols == 0) 0 else cols * (cw + CELL_PAD) + CELL_PAD
    val sheetH = if (cols == 0) 0 else rows * (ch + CAPTION + CELL_PAD) + CELL_PAD + titleH

    val totalW = maxOf(mainW, sheetW)
    val totalH = mainH + sheetH
    val out = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
    val c = Canvas(out); c.drawColor(Color.WHITE)

    val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; color = Color.BLACK; isFakeBoldText = true }
    val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 38, 38); style = Paint.Style.STROKE; strokeWidth = 2.5f }
    val border = Paint().apply { color = Color.rgb(180, 180, 195); style = Paint.Style.STROKE; strokeWidth = 1f }
    val coordBg = Paint().apply { color = Color.argb(215, 255, 255, 255); style = Paint.Style.FILL }
    val coordTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; color = Color.rgb(20, 20, 140); isFakeBoldText = true }

    c.drawText(tr("全屏缩略图", "Full thumb"), pad.toFloat(), 18f, head)
    val tx = pad; val ty = titleH + pad
    c.drawBitmap(frame, null, Rect(tx, ty, tx + tw, ty + th), Paint(Paint.FILTER_BITMAP_FLAG))
    c.drawRect(tx.toFloat(), ty.toFloat(), (tx + tw).toFloat(), (ty + th).toFloat(), border)
    for (r in regions.take(Report.MAX_CHANGED_REGIONS)) {
        val bx0 = tx + (r.left * tk).toFloat()
        val by0 = ty + (r.top * tk).toFloat()
        val bx1 = tx + (r.right * tk).toFloat()
        val by1 = ty + (r.bottom * tk).toFloat()
        c.drawRect(bx0, by0, bx1, by1, boxPaint)
    }

    if (crops.isNotEmpty()) {
        val rx = leftW + pad
        c.drawText(tr("变化区域（副屏坐标）", "Changed regions (screen coords)"), rx.toFloat(), 18f, head)
        var cy = titleH + pad
        for (item in crops) {
            val dst = Rect(rx, cy, rx + item.dw, cy + item.dh)
            c.drawBitmap(frame, item.src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawRect(dst, border)
            val tl = "(${item.src.left},${item.src.top})"
            val br = "(${item.src.right},${item.src.bottom})"
            val tlW = coordTxt.measureText(tl)
            c.drawRect(dst.left + 2f, dst.top + 2f, dst.left + tlW + 10f, dst.top + 21f, coordBg)
            c.drawText(tl, dst.left + 6f, dst.top + 16f, coordTxt)
            val brW = coordTxt.measureText(br)
            c.drawRect((dst.right - brW - 10f).coerceAtLeast(dst.left + 2f), (dst.bottom - 21f).coerceAtLeast(dst.top + 2f), dst.right - 2f, dst.bottom - 2f, coordBg)
            c.drawText(br, (dst.right - brW - 6f).coerceAtLeast(dst.left + 6f), dst.bottom - 6f, coordTxt)
            cy += item.dh + pad
        }
    }

    if (cells.isNotEmpty() && since != null) {
        c.drawText(tr("这段时间（${Report.ago(since.from, since.to)} 到现在）", "Meanwhile (${Report.ago(since.from, since.to)} to now)"), pad.toFloat(), (mainH + 18).toFloat(), head)
        drawCells(c, 0, mainH + titleH, cells, cols, cw, ch, fw, fh, since.to)
    }
    cells.forEach { it.bmp.recycle() }
    savePic(sid, out, tag, detail.use, sent = true).also { out.recycle() }
}.getOrNull()

/** The coordinate ruler on a picture of the whole screen: ticks every 100 screen px, numbers and faint lines every 200 (500 on big screens). */
private fun drawRuler(c: Canvas, d: VScreenDisplay, ox: Int, oy: Int, nw: Int, nh: Int, k: Double, tick: Paint, line: Paint, faint: Paint) {
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

// ── 3.3 short reply & 3.7 reply budget (pure, unit-tested) ───────────

/** 2.0.0-beta3 (3.11): fixed single-line escape hatch footer appended to every short reply (<= 30 tokens). */
internal fun quietEscapeLine(): String = tr(
    "要完整内容：act --full；要看图：look；要回看这段：look --last",
    "Full reply: act --full; picture: look; replay: look --last",
)

/** 2.0.0-beta16 (Item 3): short reply text together with the exact current elements shown in it. */
internal data class ShortReply(val text: String, val shown: List<El>)

/**
 * 2.0.0-beta16 (Item 3), pure: populate `screen.elements`, `elements_total`, and `elements_omitted`
 * respecting the reply's visibility decision: short/diff replies carry only [shown] elements; `--full` /
 * `launch` / `handoff` / brand-new pages carry all elements (up to [Report.MAX_JSON_ELEMENTS]).
 */
internal fun putScreenElements(
    screen: org.json.JSONObject,
    all: List<El>,
    shown: Collection<El>,
    fullElements: Boolean,
): org.json.JSONObject {
    val list = (if (fullElements) all else shown.toList()).take(Report.MAX_JSON_ELEMENTS)
    return screen
        .put("elements", org.json.JSONArray(list.map { it.json() }))
        .put("elements_total", all.size)
        .put("elements_omitted", (all.size - list.size).coerceAtLeast(0))
}

/**
 * P2-3, pure: isolated top-level `screen_data` block carrying all untrusted third-party text
 * separately from trusted system instructions/notes (`untrusted = true`).
 */
internal fun buildScreenData(
    appLabel: String?,
    pageTitle: String,
    allElements: List<El>,
    shownElements: Collection<El>,
    fullElements: Boolean,
    meanwhileTexts: List<String>,
    toasts: List<String>,
    readbacks: List<String>,
): org.json.JSONObject {
    val els = (if (fullElements) allElements else shownElements.toList()).take(Report.MAX_JSON_ELEMENTS)
    return org.json.JSONObject()
        .put("untrusted", true)
        .put("app_label", appLabel?.let { ScreenText.clean(it, maxLen = 64) } ?: org.json.JSONObject.NULL)
        .put("page_title", ScreenText.clean(pageTitle, maxLen = 32))
        .put("elements", org.json.JSONArray(els.map { e ->
            e.json().put("text", ScreenText.clean(e.text, maxLen = 80)).also { o ->
                e.hint?.let { o.put("hint", ScreenText.clean(it, maxLen = 64)) }
            }
        }))
        .put("meanwhile_texts", org.json.JSONArray(meanwhileTexts.map { ScreenText.clean(it, maxLen = 120) }))
        .put("toasts", org.json.JSONArray(toasts.map { ScreenText.clean(it, maxLen = 120) }))
        .put("readback", org.json.JSONArray(readbacks.map { ScreenText.clean(it, maxLen = 120) }))
}

/**
 * 2.0.0-beta3 (3.3 & 3.11) + 2.0.0-beta16 (Item 3), pure: format the short reply for a quiet `act`
 * (when `loudReasons` is empty) and return both the rendered text and the exact current [El]s shown.
 */
internal fun formatShortReplyWithShown(
    turn: Int,
    card: PageBook.Card,
    steps: List<StepOut>,
    totalMs: Long,
    els: List<El>,
    notes: Notes = Notes(),
    sinceLastMs: Long? = null,
    rulesFired: List<RuleFired> = emptyList(),
    nowMs: Long = System.currentTimeMillis(),
): ShortReply {
    val sb = StringBuilder()
    val execRaw = steps.filter { it.status != "skipped" }
    // Drop container-open wrapper entries when a matching completion entry with waitedFor exists for the same label.
    val exec = execRaw.filter { s ->
        !(s.waitedFor == null && !s.typeVerified && execRaw.any { it !== s && it.n == s.n && it.waitedFor != null })
    }
    val stepCount = exec.size
    val totalSec = "${(maxOf(0L, totalMs) / 100) / 10.0}s"
    val safeTitle = ScreenText.clean(card.title, maxLen = 24)
    val pageTag = "P${card.id} $safeTitle".trim()
    val timeHint = timeHintPart(sinceLastMs)
    sb.append(
        tr(
            "✓ 第 $turn 轮$timeHint · $pageTag · $stepCount 步都符合预期 · $totalSec",
            "✓ Turn $turn$timeHint · $pageTag · ${com.kilon.agentvscreen.steps(stepCount)} as expected · $totalSec",
        )
    ).append('\n')

    val shownSteps = if (exec.size > Report.MAX_QUIET_STEPS_FULL) {
        val hidden = exec.size - Report.QUIET_TAIL_STEPS
        sb.append(tr("前 $hidden 步均符合预期", "First $hidden steps all as expected")).append('\n')
        exec.takeLast(Report.QUIET_TAIL_STEPS)
    } else exec

    for (s in shownSteps) {
        val clean = s.step.substringBefore(" until ").trim()
        val cond = s.waitedFor ?: if (s.typeVerified) tr("已输入", "typed") else "ok"
        val waitSec = "${(maxOf(0L, s.waitMs ?: 0L) / 100) / 10.0}s"
        val startNote = if (s.alreadyAtStart && !s.fact.isNullOrBlank()) tr("（${s.fact}）", " (${s.fact})") else ""
        sb.append("${s.n} $clean → $cond ✓ $waitSec$startNote").append('\n')
    }
    for (rf in rulesFired) {
        sb.append(rf.line(nowMs)).append('\n')
    }

    val diff = PageBook.diffElements(card.prevEls, els)
    if (!card.isNew) {
        val modPart = if (diff.modified.isNotEmpty()) " ~${diff.modified.size}" else ""
        sb.append(
            tr(
                "现在 P${card.id}：和第 ${card.prevSeenTurn} 轮相比 +${diff.added.size} −${diff.removed.size}$modPart，其余 ${diff.unchanged.size} 个不变",
                "Now P${card.id}: vs turn ${card.prevSeenTurn} +${diff.added.size} −${diff.removed.size}$modPart, ${diff.unchanged.size} unchanged",
            )
        ).append('\n')
    } else {
        sb.append(
            tr(
                "现在 P${card.id} $safeTitle（新页面，共 ${els.size} 个元素）",
                "Now P${card.id} $safeTitle (new page, ${els.size} elements)",
            )
        ).append('\n')
    }

    val diffEntries = ArrayList<Pair<String, El?>>()
    for (e in diff.added) {
        val lbl = ScreenText.quote(e.label, Report.MAX_ELEMENT_TEXT)
        diffEntries += ("+ #${e.n} $lbl [${Report.box(e.box)}]") to e
    }
    for (e in diff.removed) {
        val lbl = ScreenText.quote(e.label, Report.MAX_ELEMENT_TEXT)
        diffEntries += ("- #${e.n} $lbl [${Report.box(e.box)}]") to null
    }
    for ((old, now) in diff.modified) {
        val oLbl = ScreenText.quote(old.label, maxLen = 20)
        val nLbl = ScreenText.quote(now.label, Report.MAX_ELEMENT_TEXT)
        diffEntries += ("~ #${now.n} $oLbl → $nLbl [${Report.box(now.box)}]") to now
    }
    val keptEntries = diffEntries.take(Report.MAX_QUIET_DIFF)
    keptEntries.forEach { sb.append(it.first).append('\n') }
    val shown = keptEntries.mapNotNull { it.second }
    if (diffEntries.size > Report.MAX_QUIET_DIFF) {
        val extra = diffEntries.size - Report.MAX_QUIET_DIFF
        sb.append(tr("另有 $extra 处变化，act 不带步骤可看全部", "$extra more changes; act with no steps lists all")).append('\n')
    }

    if (!notes.isEmpty) {
        sb.append(Blocks.notes).append('\n')
        notes.list.forEach { sb.append("- ").append(it.line()).append('\n') }
    }
    sb.append(quietEscapeLine()).append('\n')
    return ShortReply(sb.toString(), shown)
}

internal fun formatShortReply(
    turn: Int,
    card: PageBook.Card,
    steps: List<StepOut>,
    totalMs: Long,
    els: List<El>,
    notes: Notes = Notes(),
    sinceLastMs: Long? = null,
    rulesFired: List<RuleFired> = emptyList(),
    nowMs: Long = System.currentTimeMillis(),
): String = formatShortReplyWithShown(turn, card, steps, totalMs, els, notes, sinceLastMs, rulesFired, nowMs).text

/** 2.0.0-beta3 (3.7): structured sections of a full reply for deterministic budget trimming. */
internal data class FullReplyParts(
    val header: String,
    val doneLines: List<String>,
    val timingLine: String?,
    val meanwhileHeader: String,
    val maskLine: String?,
    val wordStaticLines: List<String>,
    val betweenLines: List<String>,
    val picDetailLines: List<String>,
    val picChangeCount: Int,
    val sheetNoteLine: String?,
    val nowHeader: String,
    val elementLines: List<String>,
    val notesLines: List<String>,
)

/**
 * 2.0.0-beta3 (3.7), pure: assemble a full reply and trim to [budgetTokens] (excluding [FullReplyParts.elementLines])
 * in strict priority order:
 *   1. "中途出现过又消失" ([FullReplyParts.betweenLines]);
 *   2. picture change detail lines ([FullReplyParts.picDetailLines] -> single line "画面变过 N 次");
 *   3. timing breakdown line ([FullReplyParts.timingLine]).
 * Never trims 【注意】 or 【做了什么】 failure facts. Appends "省略了 N 行，look --last 可回看" when trimmed.
 */
internal fun applyReplyBudget(parts: FullReplyParts, budgetTokens: Int = Report.BUDGET_TOKENS): String {
    fun render(bet: List<String>, pics: List<String>, timing: String?, includeElements: Boolean, omitted: Int): String {
        val sb = StringBuilder()
        if (parts.header.isNotBlank()) sb.append(parts.header).append('\n')
        sb.append(Blocks.done).append('\n')
        parts.doneLines.forEach { sb.append(it).append('\n') }
        timing?.let { sb.append(it).append('\n') }
        sb.append('\n').append(Blocks.meanwhile).append('\n')
        sb.append(parts.meanwhileHeader).append('\n')
        parts.maskLine?.let { sb.append(it).append('\n') }
        val screenMeanwhile = parts.wordStaticLines + bet + pics
        if (screenMeanwhile.isNotEmpty()) {
            sb.append(ScreenText.FENCE_OPEN).append('\n')
            screenMeanwhile.forEach { sb.append(it).append('\n') }
            sb.append(ScreenText.FENCE_CLOSE).append('\n')
        }
        if (screenMeanwhile.isEmpty() && parts.maskLine == null) {
            sb.append(tr("文字和画面都没有明显变化（光标、细小动画这类小变动不算）", "no clear change in text or picture (cursors and tiny animations don't count)")).append('\n')
        }
        parts.sheetNoteLine?.let { sb.append(it).append('\n') }
        sb.append('\n').append(parts.nowHeader).append('\n')
        if (includeElements && parts.elementLines.isNotEmpty()) {
            sb.append(ScreenText.FENCE_OPEN).append('\n')
            parts.elementLines.forEach { sb.append(it).append('\n') }
            sb.append(ScreenText.FENCE_CLOSE).append('\n')
        }
        sb.append('\n').append(Blocks.notes).append('\n')
        if (parts.notesLines.isEmpty()) sb.append(tr("无\n", "none\n"))
        else parts.notesLines.forEach { sb.append("- ").append(it).append('\n') }
        if (omitted > 0) {
            sb.append(tr("省略了 $omitted 行，look --last 可回看", "$omitted lines omitted; look --last to replay")).append('\n')
        }
        return sb.toString()
    }

    fun over(bet: List<String>, pics: List<String>, timing: String?, omitted: Int): Boolean =
        com.kilon.agentvscreen.core.Meter.of(render(bet, pics, timing, includeElements = false, omitted = omitted), emptyList()).estTokens > budgetTokens

    var curBetween = parts.betweenLines
    var curPics = parts.picDetailLines
    var curTiming = parts.timingLine
    var omitted = 0

    // ① Drop between-words first.
    if (over(curBetween, curPics, curTiming, omitted) && curBetween.isNotEmpty()) {
        omitted += curBetween.size
        curBetween = emptyList()
    }
    // ② Collapse picture change details to one summary line.
    if (over(curBetween, curPics, curTiming, omitted) && curPics.isNotEmpty()) {
        val count = maxOf(1, parts.picChangeCount)
        omitted += maxOf(1, curPics.size)
        curPics = listOf(tr("画面变过 $count 次", "screen changed $count times"))
    }
    // ③ Drop timing breakdown line last.
    if (over(curBetween, curPics, curTiming, omitted) && curTiming != null) {
        omitted += 1
        curTiming = null
    }
    return render(curBetween, curPics, curTiming, includeElements = true, omitted = omitted)
}

// ── the four blocks ──────────────────────────────────────────────────

/**
 * The four-block return, as text for a model and as data for a program, both from the same values: [lines] / [steps]
 * (done), the readings and the track (meanwhile), one snapshot (now), [notes]. [outcome] says how the stretch ended:
 * done | stopped (a stop condition fired: also fine) | missed | refused | interrupted.
 *
 * 2.0.0-beta3 (3.1–3.12): when [actLoud] is non-null (`act`), computes [loudReasons]. When empty, returns the short
 * quiet reply (no image, no 【这段时间】, full JSON with `loud: []`). When [actLoud] is null (`launch`, `handoff`),
 * always returns the full four blocks and never enters short reply.
 */
internal fun VScreenOffloadHandler.fourBlocks(
    sid: String,
    d: VScreenDisplay,
    lines: List<String>,
    notes: Notes,
    segmentStart: Long,
    actionMs: Long,
    tag: String,
    fullList: Boolean = false,
    steps: List<StepOut> = emptyList(),
    outcome: String = "done",
    stoppedAt: String? = null,
    actLoud: ActLoudCtx? = null,
): NativeOffloadResult {
    // Whoever is handed 【现在】 has seen the current page: that is the re-read a takeover or restart asks for.
    BackgroundOperation.acknowledgeReread(sid)
    val now0 = System.currentTimeMillis()
    val turn = com.kilon.agentvscreen.api.Turns.turnOf(sid) + 1
    val last = actLoud?.prevReturnAt ?: synchronized(VScreenOffloadHandler.lastReturn) { VScreenOffloadHandler.lastReturn[sid] }
    val from = actLoud?.replayWindow?.get(0)
        ?: if (last != null && now0 - last <= VScreenRecorder.KEEP_MS && last <= segmentStart) last else segmentStart
    val received = Call.current()?.receivedAt ?: segmentStart
    val sinceLastMs = last?.let { received - it }?.takeIf { it in 0L..Report.TIME_HINT_MAX_MS }
    val timeHint = timeHintPart(sinceLastMs)
    var totalActionMs = actionMs

    fun recordReturned(frame: Bitmap?, version: Long, pkg: String?, quiet: Boolean?, windowFrom: Long): Long {
        if (frame != null && !frame.isRecycled) VScreenRecorder.offerFresh(d, frame, version, pkg)
        val returnedAt = System.currentTimeMillis()
        synchronized(VScreenOffloadHandler.lastReturn) { VScreenOffloadHandler.lastReturn[sid] = returnedAt }
        synchronized(lastWindows) { lastWindows[sid] = longArrayOf(windowFrom, returnedAt) }
        if (quiet != null) Loud.recordActReply(sid, quiet, windowFrom, returnedAt)
        return returnedAt
    }

    // The screen is gone (privileged connection lost): say so instead of describing an empty screen.
    connectionLost(d)?.let { lost ->
        notes.add("CONNECTION_LOST", lost)
        val total = System.currentTimeMillis() - received
        val timing = Timing(received, totalActionMs)
        val sb = StringBuilder()
        sb.append(tr("第 $turn 轮$timeHint", "Turn $turn$timeHint")).append('\n')
        sb.append(Blocks.done + "\n"); lines.forEach { sb.append(it).append('\n') }
        sb.append(timing.line(total)).append('\n')
        sb.append("\n" + Blocks.meanwhile + "\n" + tr("（副屏已不存在，没有录屏）", "(the screen no longer exists, no recording)") + "\n\n" + Blocks.now + "\n" + tr("（没有副屏）", "(no screen)") + "\n")
        sb.append("\n" + Blocks.notes + "\n")
        notes.list.forEach { sb.append("- ").append(it.line()).append('\n') }
        val returnedAt = recordReturned(null, -1L, null, if (actLoud != null) false else null, from)
        val data = org.json.JSONObject()
            .put("screen", org.json.JSONObject.NULL)
            .put("timing", timing.json(total))
            .put("turn", turn)
            .put("returned_at", returnedAt)
            .put("outcome", "interrupted")
            .put("steps", org.json.JSONArray(steps.map { it.json() }))
            .put("stopped_at", stoppedAt ?: org.json.JSONObject.NULL)
            .put("done", org.json.JSONArray(lines))
            .put("notes", org.json.JSONArray(notes.list.map { it.json() }))
        if (actLoud != null) {
            data.put("loud", org.json.JSONArray(listOf(Loud.FAILED)))
            if (actLoud.hasRules || actLoud.rulesFired.isNotEmpty()) {
                data.put("rules_fired", org.json.JSONArray(actLoud.rulesFired.map { it.json() }))
            }
        }
        return NativeOffloadResult(1, sb.toString(), notes.firstBlocking() ?: "CONNECTION_LOST", data, softFail = true)
    }

    // Read screen first so we know activity, elementCount, and notes before deciding Loud / AFTER wait.
    val snapAt = SystemClock.elapsedRealtime()
    var snap = snapshot(d)
    val lookMs0 = SystemClock.elapsedRealtime() - snapAt

    fun attachSnapNotes(s: Snap) {
        if (s.activity == null) notes.add("NO_APP", tr("副屏上没有应用（副屏没有桌面，back 回不到桌面）：launch <应用名> 打开一个", "No app on the screen (the screen has no launcher; back can't reach one): open one with launch <app name>"))
        com.kilon.agentvscreen.core.VScreenOwnership.takeNotes(sid).forEach { notes += it }
        com.kilon.agentvscreen.core.VScreenDownscale.takeNote(d.displayId)?.let { notes += it }
        s.ocrProblem?.let { notes.add("OCR_DOWN", it) } ?: s.ocrFallback?.let { notes += it }
        s.staleNote?.let { notes += it }
        VScreenPerception.blankFor(d.displayId)?.let { ms -> notes += tr("画面已连续 ${(ms / 100) / 10.0} 秒是一整片纯色（黑屏/白屏）", "The picture has been one solid color for ${(ms / 100) / 10.0} s (black / white screen)") }
    }
    attachSnapNotes(snap)

    var extraLookMs = 0L
    val mainPkgNow = mainTop()?.substringBefore('/')
    fun collectSystemToasts(endMs: Long): List<String> {
        val vdPkg = snap.activity?.substringBefore('/') ?: actLoud?.startPkg
        if (vdPkg.isNullOrBlank()) return emptyList()
        val evs = com.kilon.agentvscreen.host.VScreenAccessibilityService.recentToasts(vdPkg, received, endMs)
        return formatSystemToasts(evs, vdPkg, mainPkgNow, received, endMs)
    }
    var toastLines: List<String> = collectSystemToasts(System.currentTimeMillis())

    val reasons: List<String> = if (actLoud == null) {
        emptyList()
    } else {
        val hasPageTextDiff = PageBook.hasTextDiff(sid, snap.activity, snap.els)
        val baseInput = LoudInput(
            outcome = outcome,
            hasBlockingNote = notes.firstBlocking() != null,
            hasNoExpect = actLoud.firstNoExpectStep != null,
            hasNotMet = steps.any { it.waitMet == false },
            beforeChanged = actLoud.beforeChanged,
            afterChanged = false,
            startPkg = actLoud.startPkg,
            endPkg = snap.activity?.substringBefore('/'),
            hasAppUntil = actLoud.hasAppUntil,
            snappedBack = steps.any { it.gesture?.afterRelease == Gesture.AFTER_SNAPPED_BACK },
            elementCount = Loud.textElements(snap.els).size,
            lastExpectOnlyVisual = actLoud.lastExpectOnlyVisual,
            hasPageTextDiff = hasPageTextDiff,
            hasToast = toastLines.isNotEmpty(),
            forced = actLoud.forced,
        )
        val pre = loudReasons(baseInput)
        if (pre.isEmpty()) {
            // 3.2 & 3.13: observe until AFTER_MS = 800ms after last expectation met, only when all other reasons are false.
            val tAfter = SystemClock.elapsedRealtime()
            val remainMs = (actLoud.afterStartMs + Loud.AFTER_MS - System.currentTimeMillis()).coerceAtLeast(0L)
            val deadline = tAfter + remainMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (Call.current()?.cancelled == true || connectionLost(d) != null) break
                Thread.sleep(minOf(100L, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
            }
            totalActionMs += SystemClock.elapsedRealtime() - tAfter
            d.awaitFrameAfter(SystemClock.elapsedRealtime() - 50L, 100L)
            d.latestWithVersion()?.let { (bmp, ver) ->
                VScreenRecorder.offerFresh(d, bmp, ver, snap.activity?.substringBefore('/') ?: actLoud.startPkg)
            }
            val afterChanged = hasPictureEvent(d, actLoud.afterStartMs, System.currentTimeMillis(), actLoud.firstActionMask, actLoud.excludeWindows)
            if (afterChanged) {
                val tRe = SystemClock.elapsedRealtime()
                snap = snapshot(d)
                extraLookMs = SystemClock.elapsedRealtime() - tRe
                attachSnapNotes(snap)
            }
            toastLines = collectSystemToasts(System.currentTimeMillis())
            loudReasons(
                baseInput.copy(
                    afterChanged = afterChanged,
                    endPkg = snap.activity?.substringBefore('/'),
                    elementCount = Loud.textElements(snap.els).size,
                    hasPageTextDiff = if (afterChanged) PageBook.hasTextDiff(sid, snap.activity, snap.els) else hasPageTextDiff,
                    hasBlockingNote = notes.firstBlocking() != null,
                    hasToast = toastLines.isNotEmpty(),
                )
            )
        } else {
            pre
        }
    }

    val now = System.currentTimeMillis()
    if (toastLines.isEmpty()) toastLines = collectSystemToasts(now)
    val els = numbered(sid, d, snap.els, snap.activity)
    synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] = ElSet(d.generation, now, els, snap.activity, snapAt) }
    val (card, _) = PageBook.match(sid, snap.activity, els, turn)

    // 2.0.0-beta8 (C2): execute & verify --hold synchronously BEFORE sending the reply so we never
    // claim "已暂停" without kernel confirmation (cgroup.events frozen 1 / wchan fallback).
    var holdFooter: String? = null
    var holdJson: org.json.JSONObject? = null
    if (actLoud?.usedHold == true && outcome != "interrupted") {
        val holdPkg = snap.activity?.substringBefore('/')
        val holdAppName = holdPkg?.let { com.kilon.agentvscreen.core.VScreenKnowledge.label(it) ?: it }.orEmpty()
        val res = ActHold.holdBeforeReply(
            context = context,
            sid = sid,
            displayId = d.displayId,
            pkg = holdPkg,
            appName = holdAppName,
            skipPkgs = handoffSkip(),
            mainTopPkg = mainPkgNow,
        )
        when (res) {
            is ActHold.HoldOutcome.Unsupported -> notes.add("HOLD_UNSUPPORTED", res.text)
            is ActHold.HoldOutcome.Refused -> notes.add("HOLD_REFUSED", res.text)
            is ActHold.HoldOutcome.Skipped -> {}
            is ActHold.HoldOutcome.Held -> {
                holdFooter = res.footerLine
                holdJson = res.json()
            }
        }
    }

    // 5.3: track consecutive BEFORE full replies across BOTH quiet (resets streak) and full paths.
    val showBeforeHint = if (actLoud != null) {
        Loud.recordBeforeAndShouldHint(sid, reasons, ActHold.sdkIntFn(), actLoud.usedHold)
    } else false

    val ruleLines = actLoud?.rulesFired?.map { it.line(now) }.orEmpty()
    val doneWithRules = if (ruleLines.isEmpty()) lines else lines + ruleLines

    val timing = Timing(received, totalActionMs).also { it.lookMs = lookMs0 + extraLookMs }
    val ok = outcome == "done" || outcome == "stopped"
    val code = if (ok) "OK" else notes.firstBlocking() ?: "STEP_MISSED"
    val appPkg = snap.activity?.substringBefore('/')
    val appLabel = appPkg?.let { com.kilon.agentvscreen.core.VScreenKnowledge.label(it) }
    val readbacks = steps.mapNotNull { it.readback?.takeIf { r -> r.isNotEmpty() } }

    // ── Quiet short reply path (act only, loudReasons empty) ──
    if (actLoud != null && reasons.isEmpty()) {
        val total = System.currentTimeMillis() - received
        val shortReply = formatShortReplyWithShown(
            turn, card, steps, total, els, notes,
            sinceLastMs = sinceLastMs,
            rulesFired = actLoud.rulesFired,
            nowMs = now,
        )
        val shortText = if (holdFooter != null) shortReply.text + holdFooter + "\n" else shortReply.text
        val returnedAt = recordReturned(snap.frame, snap.version, appPkg, quiet = true, windowFrom = from)
        val screen = putScreenElements(
            org.json.JSONObject()
                .put("app", appPkg ?: org.json.JSONObject.NULL)
                .put("activity", snap.activity ?: org.json.JSONObject.NULL)
                .put("width", d.width).put("height", d.height)
                .put("generation", d.generation)
                .put("image", org.json.JSONObject.NULL)
                .put("image_has_strip", false)
                .put("page", card.pageJson()),
            all = els,
            shown = shortReply.shown,
            fullElements = false,
        )
        val screenData = buildScreenData(
            appLabel = appLabel,
            pageTitle = card.title,
            allElements = els,
            shownElements = shortReply.shown,
            fullElements = false,
            meanwhileTexts = emptyList(),
            toasts = emptyList(),
            readbacks = readbacks,
        )
        val data = org.json.JSONObject()
            .put("timing", timing.json(total))
            .put("turn", turn)
            .put("returned_at", returnedAt)
            .put("loud", org.json.JSONArray())
            .put("outcome", outcome)
            .put("steps", org.json.JSONArray(steps.map { it.json() }))
            .put("stopped_at", stoppedAt ?: org.json.JSONObject.NULL)
            .put("done", org.json.JSONArray(doneWithRules))
            .put("notes", org.json.JSONArray(notes.list.map { it.json() }))
            .put("meanwhile", org.json.JSONArray())
            .put("picture_changed", false)
            .put("screen", screen)
            .put("screen_data", screenData)
        if (actLoud.hasRules || actLoud.rulesFired.isNotEmpty()) {
            data.put("rules_fired", org.json.JSONArray(actLoud.rulesFired.map { it.json() }))
        }
        holdJson?.let { data.put("hold", it) }
        return NativeOffloadResult(0, shortText, "OK", data, softFail = false)
    }

    // ── Full reply path (loud act, launch, handoff) ──
    if (showBeforeHint) {
        notes += tr(
            "你思考时画面一直在变：可以加 --hold 让应用在两轮之间暂停，或用 --on 让手机当场处理",
            "The screen kept changing while you thought: add --hold to pause the app between turns, or --on to handle it on the spot",
        )
    }
    // 3.8: NO_EXPECT coaching hint (only when NO_EXPECT is the sole reason, at most once every 10 turns per sid).
    if (actLoud != null && reasons == listOf(Loud.NO_EXPECT) && actLoud.firstNoExpectStep != null && Loud.allowNoExpectHint(sid, turn)) {
        val stN = actLoud.firstNoExpectStep
        notes += tr(
            "第 $stN 步没写 until，所以给了完整回复。写上你期待看到什么，符合时只回一小段",
            "Step $stN has no until, so a full reply was returned. Add what you expect to see for a short reply when it matches",
        )
    }

    val tTrack = SystemClock.elapsedRealtime()
    val picF = REPORT_POOL.submit<PicEvents>(com.kilon.agentvscreen.I18n.carry {
        runCatching { pictureEvents(d, from, now, actLoud?.firstActionMask) }.getOrElse {
            PicEvents(null, listOf(tr("（读不到录屏：${it.message}）", "(recording unreadable: ${it.message})")), 0, false)
        }
    })
    // 4.4: full reply for any drag uses a fixed 4-cell strip (before down, drag midpoint, before release, 350ms after release).
    val dragKeyTimes = steps.lastOrNull { it.gesture?.afterRelease == Gesture.AFTER_SNAPPED_BACK }?.gesture?.keyTimesMs
        ?: steps.lastOrNull { it.gesture?.keyTimesMs != null }?.gesture?.keyTimesMs
    val hasNoChange = notes.list.any { it.code == "NO_CHANGE" || it.code == "SNAP_BACK" }
    val cellsF = when {
        dragKeyTimes != null -> REPORT_POOL.submit<Picked>(com.kilon.agentvscreen.I18n.carry {
            val dc = runCatching { Gesture.pickDragCells(d.displayId, dragKeyTimes, Report.CELL_SIDE) }.getOrDefault(emptyList())
            Picked(dc, 0, dc.size, if (dc.isNotEmpty()) 1 else 0)
        })
        hasNoChange -> REPORT_POOL.submit<Picked>(com.kilon.agentvscreen.I18n.carry {
            runCatching { pickCells(d.displayId, from, now, Report.MAX_CELLS, Report.CELL_SIDE) }.getOrDefault(Picked(emptyList(), 0, 0, 0))
        })
        else -> null
    }

    val picEv = runCatching { picF.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(PicEvents(null, emptyList(), 0, false))
    val picked = cellsF?.let { runCatching { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(Picked(emptyList(), 0, 0, 0)) }
        ?: Picked(emptyList(), 0, 0, 0)
    val cells = picked.cells
    // 3.5 & 4.4: contact sheet when drag 4-cell strip is available, or when NO_CHANGE and the stretch touched the screen.
    val isDragStrip = dragKeyTimes != null && cells.isNotEmpty()
    val touched = cells.any { c -> c.marks.any { it.pts.isNotEmpty() } }
    val sheet = isDragStrip || (hasNoChange && touched && cells.isNotEmpty())
    val shownCells = if (sheet) cells else { cells.forEach { it.bmp.recycle() }; emptyList() }
    timing.trackMs = SystemClock.elapsedRealtime() - tTrack

    val wordEv = wordEvents(d, from, System.currentTimeMillis())
    val wordStaticWithToasts = wordEv.staticLines + toastLines
    val sinceHead = sinceHeader(from, now, totalActionMs, received)
    val sheetNote = when {
        isDragStrip -> tr(
            "拖动拼图 ${shownCells.size} 格：按下前、滑动中点、松手前、松手后 350ms",
            "Drag strip (${shownCells.size} frames): before down, drag midpoint, before release, 350ms after release",
        )
        sheet -> {
            val before = shownCells.count { it.marks.isNotEmpty() }
            tr("拼图 ${shownCells.size} 张：${before} 张是动作之前（圈和编号是点的位置，拖动是线），其余是开头和动作之后的结果，每张下面写着是哪一种", "Sheet of ${shownCells.size}: $before before actions (ring and number where it touched, a line for a drag), the others the start and what actions led to; each says which under it") +
                (if (picked.leftOut > 0) tr("；前 ${picked.leftOut} 个动作没放进来", "; the first ${picked.leftOut} actions are not in it") else "") +
                tr("。要更密、更长或别的时间段：vscreen look --last", ". Denser, longer or another stretch: vscreen look --last")
        }
        else -> null
    }
    val allSinceLines = buildList {
        add(sinceHead)
        picEv.maskLine?.let { add(it) }
        addAll(wordStaticWithToasts)
        addAll(wordEv.betweenLines)
        addAll(picEv.detailLines)
        if (size == 1) add(tr("文字和画面都没有明显变化（光标、细小动画这类小变动不算）", "no clear change in text or picture (cursors and tiny animations don't count)"))
        sheetNote?.let { add(it) }
    }
    val since = Since(allSinceLines, sheet, from, now)

    // 3.5: decide image (960 full / 360 thumb + 640 crops / none).
    val tPic = SystemClock.elapsedRealtime()
    val isLookOrFullCmd = actLoud == null || actLoud.stepsWritten.isEmpty() || actLoud.replayWindow != null
    val isFailedOrNoText = reasons.contains(Loud.FAILED) || reasons.contains(Loud.NO_TEXT)
    var changedShare = 0.0
    var changedRegions: List<Rect> = emptyList()
    if (!isLookOrFullCmd && snap.frame != null) {
        val prevFrame = last?.let { VScreenRecorder.frameAtStrict(d.displayId, it) }
        if (prevFrame == null) {
            changedShare = 1.0
        } else {
            val prevBmp = VScreenRecorder.decode(prevFrame, 160)
            val nowBmp = VScreenPerception.small(snap.frame!!, 160)
            try {
                if (prevBmp != null && prevBmp.width == nowBmp.width && prevBmp.height == nowBmp.height) {
                    val rows = VScreenActivity.rowsFor(prevBmp.width, prevBmp.height)
                    val mask = actLoud?.firstActionMask ?: snap.mask
                    val c0 = VScreenActivity.cells(prevBmp, nowBmp, VScreenActivity.COLS, rows)
                    val c = if (c0 != null) mask?.takeIf { it.cols == VScreenActivity.COLS && it.rows == rows }?.minus(c0) ?: c0 else null
                    if (c != null) {
                        changedShare = VScreenActivity.share(c)
                        changedRegions = VScreenActivity.boxes(c, VScreenActivity.COLS, rows, d.width, d.height).take(Report.MAX_CHANGED_REGIONS)
                    } else {
                        changedShare = 1.0
                    }
                } else {
                    changedShare = 1.0
                }
            } finally {
                prevBmp?.recycle()
                nowBmp?.recycle()
            }
        }
    }

    var usedRegionPic = false
    val pic: String? = snap.frame?.let { frame ->
        when {
            isLookOrFullCmd || changedShare > Report.PAGE_CHANGE_SHARE ->
                picture(sid, d, frame, since, tag, shownCells, sideOverride = Report.FULL_SIDE)
            changedRegions.isNotEmpty() -> {
                usedRegionPic = true
                pictureRegions(sid, d, frame, changedRegions, since, tag, shownCells)
            }
            isFailedOrNoText || sheet ->
                picture(sid, d, frame, since, tag, shownCells, sideOverride = Report.FULL_SIDE)
            else -> {
                shownCells.forEach { it.bmp.recycle() }
                null
            }
        }
    } ?: run {
        shownCells.forEach { it.bmp.recycle() }
        null
    }
    timing.pictureMs = SystemClock.elapsedRealtime() - tPic

    // 3.4 & 3.7: PageBook-driven 【现在】 element list (replaces old 60% logic in appendElements).
    val elemLimit = if (fullList) Report.MAX_ELEMENTS else Report.ACT_ELEMENTS
    val (nowHeader, elemLines, shown) = buildNowSection(d, snap, card, els, fullList, elemLimit, pic, sheet, usedRegionPic)

    val total = System.currentTimeMillis() - received
    val safePageTitle = ScreenText.clean(card.title, maxLen = 24)
    val pageTag = "P${card.id} $safePageTitle".trim()
    val baseFull = applyReplyBudget(
        FullReplyParts(
            header = tr("第 $turn 轮$timeHint · $pageTag", "Turn $turn$timeHint · $pageTag"),
            doneLines = doneWithRules,
            timingLine = timing.line(total),
            meanwhileHeader = sinceHead,
            maskLine = picEv.maskLine,
            wordStaticLines = wordStaticWithToasts,
            betweenLines = wordEv.betweenLines,
            picDetailLines = picEv.detailLines,
            picChangeCount = picEv.spanCount,
            sheetNoteLine = sheetNote,
            nowHeader = nowHeader,
            elementLines = elemLines,
            notesLines = notes.list.map { it.line() },
        )
    )
    val fullText = if (holdFooter != null) baseFull + holdFooter + "\n" else baseFull

    val returnedAt = recordReturned(snap.frame, snap.version, appPkg, quiet = if (actLoud != null) false else null, windowFrom = from)
    val screen = putScreenElements(
        org.json.JSONObject()
            .put("app", appPkg ?: org.json.JSONObject.NULL)
            .put("activity", snap.activity ?: org.json.JSONObject.NULL)
            .put("width", d.width).put("height", d.height)
            .put("generation", d.generation)
            .put("image", pic ?: org.json.JSONObject.NULL)
            .put("image_has_strip", sheet && pic != null)
            .put("page", card.pageJson()),
        all = els,
        shown = shown,
        fullElements = fullList || !card.canRef,
    )
    val screenData = buildScreenData(
        appLabel = appLabel,
        pageTitle = card.title,
        allElements = els,
        shownElements = shown,
        fullElements = fullList || !card.canRef,
        meanwhileTexts = wordEv.allLines + picEv.detailLines,
        toasts = toastLines,
        readbacks = readbacks,
    )
    val data = org.json.JSONObject()
        .put("timing", timing.json(total))
        .put("turn", turn)
        .put("returned_at", returnedAt)
        .put("outcome", outcome)
        .put("steps", org.json.JSONArray(steps.map { it.json() }))
        .put("stopped_at", stoppedAt ?: org.json.JSONObject.NULL)
        .put("done", org.json.JSONArray(doneWithRules))
        .put("notes", org.json.JSONArray(notes.list.map { it.json() }))
        .put("meanwhile", org.json.JSONArray(allSinceLines))
        .put("picture_changed", pic != null && (picEv.changed || changedShare > 0.0 || changedRegions.isNotEmpty() || from != last || isLookOrFullCmd))
        .put("screen", screen)
        .put("screen_data", screenData)
    if (actLoud != null) {
        data.put("loud", org.json.JSONArray(reasons))
        if (actLoud.hasRules || actLoud.rulesFired.isNotEmpty()) {
            data.put("rules_fired", org.json.JSONArray(actLoud.rulesFired.map { it.json() }))
        }
    }
    holdJson?.let { data.put("hold", it) }
    return NativeOffloadResult(if (ok) 0 else 1, fullText, code, data, softFail = !ok)
}

private val REPORT_POOL = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "vscreen-report").apply { isDaemon = true } }

/** Same element as last time: same words, centre within 24 px. */
internal fun sameEl(a: El, b: El) = a.key == b.key && Math.abs(a.box.centerX() - b.box.centerX()) <= 24 && Math.abs(a.box.centerY() - b.box.centerY()) <= 24

/** 1.3: the app reported a new window, or the front activity is another one, since [set] was read. */
internal fun pageChangedSince(d: VScreenDisplay, set: ElSet, activity: String?): Boolean =
    (set.activity != null && activity != null && set.activity != activity) ||
        (set.atElapsed > 0 && com.kilon.agentvscreen.host.VScreenAccessibilityService.pageChangedAt(d.displayId) > set.atElapsed)

internal fun numbered(sid: String, d: VScreenDisplay, els: List<El>, activity: String? = null): List<El> {
    val key = "$sid@${d.generation}"
    val prevSet = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }?.takeIf { it.generation == d.generation }
    val book = synchronized(Numbers) { Numbers.of(key) }
    if (els.isEmpty()) return els
    val newPage = prevSet == null || pageChangedSince(d, prevSet, activity) || !samePage(prevSet.list, els)
    val prev = if (newPage) emptyList() else prevSet!!.list
    val used = HashSet<Int>()
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
            book.labels[n] = e.label.take(Report.MAX_ELEMENT_TEXT)
            e.copy(n = n)
        }.also { book.trim() }
    }
}

/** Every number given out on a screen (per session and screen generation), so none is ever reused. */
internal object Numbers {
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
 * 2.0.0-beta3 (3.4 & 3.7): build the 【现在】 header and element lines using [PageBook] alone.
 * The old 60% similarity diff logic in `appendElements` is removed so only [PageBook] decides diff vs full listing.
 */
private fun buildNowSection(
    d: VScreenDisplay,
    snap: Snap,
    card: PageBook.Card,
    els: List<El>,
    fullList: Boolean,
    limit: Int,
    pic: String?,
    hasSheet: Boolean,
    usedRegionPic: Boolean,
): Triple<String, List<String>, Set<El>> {
    val safeTitle = ScreenText.clean(card.title, maxLen = 24)
    val safeAct = snap.activity?.let { ScreenText.clean(it, maxLen = 80) }
    val picSuffix = if (pic != null) {
        val desc = when {
            usedRegionPic && hasSheet -> tr("（左缩略图、右变化区域，下方是拼图）", " (left thumb, right changed regions, sheet below)")
            usedRegionPic -> tr("（左全屏缩略图，右变化区域裁图）", " (left full thumb, right changed region crops)")
            hasSheet -> tr("（左/上是现在，旁边是这段时间的拼图）", " (left / top is now, next to it the strip of meanwhile)")
            else -> ""
        }
        tr("  图 ", "  picture ") + pic + desc
    } else ""

    if (els.isEmpty()) {
        val header = "${Blocks.now} P${card.id} $safeTitle  ${safeAct ?: tr("（副屏上没有应用）", "(no app on the screen)")}${tr("  副屏 ", "  screen ")}${d.width}x${d.height}$picSuffix"
        val emptyLine = if (snap.items == null) tr("（没读到文字：OCR 没有结果）", "(no text read: OCR returned nothing)")
        else tr("（画面上没有可读的文字或控件：看图）", "(no readable text or widgets on the screen: look at the picture)")
        return Triple(header, listOf(emptyLine), emptySet())
    }

    if (!fullList && card.canRef) {
        val diff = PageBook.diffElements(card.prevEls, els)
        val header = tr(
            "${Blocks.now} P${card.id} $safeTitle（第 ${card.prevSeenTurn} 轮看过，没变的 ${diff.unchanged.size} 个省略）  副屏 ${d.width}x${d.height}",
            "${Blocks.now} P${card.id} $safeTitle (seen turn ${card.prevSeenTurn}, ${diff.unchanged.size} unchanged omitted)  screen ${d.width}x${d.height}",
        ) + picSuffix
        val outLines = ArrayList<String>()
        val shown = LinkedHashSet<El>()
        val changed = (diff.added + diff.modified.map { it.second }).sortedWith(compareBy({ it.box.centerY() }, { it.box.centerX() }))
        for (e in changed.take(limit)) {
            val isMod = diff.modified.any { it.second === e }
            outLines += (if (isMod) "~ " else "+ ") + e.line()
            shown += e
        }
        if (changed.size > limit) {
            outLines += tr("（还有 ${changed.size - limit} 个变化未列出：act 不带步骤可看全部）", "(${changed.size - limit} more changed not listed: act with no steps lists all)")
        }
        if (diff.removed.isNotEmpty()) {
            val remShown = diff.removed.take(10).joinToString(tr("、", ", ")) { ScreenText.quote(it.label, maxLen = 20) }
            outLines += tr("− 不见了 ${diff.removed.size} 个：$remShown", "− ${diff.removed.size} gone: $remShown")
        }
        return Triple(header, outLines, shown)
    }

    val header = "${Blocks.now} P${card.id} $safeTitle  ${safeAct ?: tr("（副屏上没有应用）", "(no app on the screen)")}${tr("  副屏 ", "  screen ")}${d.width}x${d.height}$picSuffix"
    val ordered = els.sortedWith(compareBy({ it.box.centerY() }, { it.box.centerX() }))
    val taken = ordered.take(limit)
    val outLines = ArrayList<String>(taken.size + 1)
    taken.forEach { outLines += it.line() }
    if (ordered.size > limit) {
        outLines += tr("（还有 ${ordered.size - limit} 个没列出：look x1,y1,x2,y2 列出那块的全部元素）", "(${ordered.size - limit} more not listed: look x1,y1,x2,y2 lists every element in that area)")
    }
    return Triple(header, outLines, taken.toSet())
}

/** 1.1.7: the four block headers (and the error header) in the current language. */
object Blocks {
    val done: String get() = tr("【做了什么】", "[Done]")
    val meanwhile: String get() = tr("【这段时间】", "[Meanwhile]")
    val now: String get() = tr("【现在】", "[Now]")
    val notes: String get() = tr("【注意】", "[Notes]")
    val error: String get() = tr("【错误】", "[Error]")
}
