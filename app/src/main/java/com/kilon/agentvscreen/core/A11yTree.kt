package com.kilon.agentvscreen.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 1.4.0-beta2: the one walk of a screen's accessibility tree. Before, seven functions each walked it with their own
 * depth limit, node limit and idea of "visible" and "its words" (a11yRaw, a11yShows, a11yFind, canScroll,
 * focusedInput, a11yTexts, focusedEditable). Now [snapshot] reads the tree once into plain [Node]s, and every
 * question is a function of a [Snapshot]. A caller that asks several questions about one moment takes one snapshot.
 */
object A11yTree {
    private const val MAX_DEPTH = 50
    private const val MAX_NODES = 3_000
    private const val RECHECK_MAX = 200

    class Node(
        /** The node itself, for actions and for a refresh of a hit. */
        val info: AccessibilityNodeInfo,
        val depth: Int,
        val children: List<Node>,
    ) {
        val box: Rect = Rect().also { info.getBoundsInScreen(it) }
        val visible: Boolean = info.isVisibleToUser
        /** Its words: text, else content description, on one line; null when it has none. */
        val words: String? = wordsOf(info)
        val clickable get() = info.isClickable
        val editable get() = info.isEditable
        val checkable get() = info.isCheckable
        val checked get() = info.isChecked
        val scrollable get() = info.isScrollable
        val focused get() = info.isFocused
        val password get() = info.isPassword
        val viewId: String? get() = info.viewIdResourceName
        val className: String? get() = info.className?.toString()
        val heading: Boolean get() = info.isHeading
        val hasText: Boolean = !info.text.isNullOrBlank()
        val hasContentDesc: Boolean = !info.contentDescription.isNullOrBlank()
        /** 2.0.0-beta8 (L.3): clickable icon button (ImageButton, or clickable node with contentDescription but no text). */
        val isIconButton: Boolean get() = clickable && ((className?.endsWith("ImageButton") == true) || (!hasText && hasContentDesc))
        fun all(): Sequence<Node> = sequence { yield(this@Node); children.forEach { yieldAll(it.all()) } }
    }

    class Snapshot(val roots: List<Node>) {
        val readable: Boolean get() = roots.isNotEmpty()
        val nodes: List<Node> by lazy { roots.flatMap { it.all().toList() } }

        /**
         * The first visible node whose words pass [match], re-read before it counts (a background screen's cache can
         * keep a cleared box's old text). "" when none; null when the tree could not be read.
         */
        fun shows(match: (String) -> Boolean, recheck: Boolean = false): String? = if (!readable) null else find(match, 1, recheck)!!.firstOrNull()?.first ?: ""

        /**
         * Visible nodes whose words pass [match] (re-read first), with their boxes; null when the tree could not be read.
         *
         * 1.4.0-beta15 (test: `repeat { tap 1 } until text:11` stopped at "111" or "1,111"): only nodes whose CACHED
         * words matched were re-read, so a display whose cache still said "1" was never re-read and "11" was never
         * seen. [recheck] re-reads every visible node with words before matching: for a final "no" that must rest on
         * what is on the screen now, not on the cache (at most [RECHECK_MAX] nodes).
         */
        fun find(match: (String) -> Boolean, max: Int = 20, recheck: Boolean = false): List<Pair<String, Rect>>? {
            if (!readable) return null
            val out = ArrayList<Pair<String, Rect>>()
            var rechecked = 0
            for (n in nodes) {
                if (out.size >= max) break
                val t = n.words ?: continue
                if (!n.visible) continue
                if (!match(t)) {
                    if (!recheck || rechecked++ >= RECHECK_MAX) continue
                    if (!runCatching { n.info.refresh() }.getOrDefault(false)) continue
                    val t2 = wordsOf(n.info) ?: continue
                    if (n.info.isVisibleToUser && match(t2)) out += t2.take(40) to Rect().also { n.info.getBoundsInScreen(it) }
                    continue
                }
                val fresh = runCatching { n.info.refresh() }.getOrDefault(false)
                val t2 = if (fresh) wordsOf(n.info) else t
                if (t2 != null && (!fresh || n.info.isVisibleToUser) && match(t2)) out += t2.take(40) to Rect().also { n.info.getBoundsInScreen(it) }
            }
            return out
        }

        /** Whether the largest visible scrollable area can go further ([forward]) or back; null when there is none. */
        fun canScroll(forward: Boolean): Boolean? {
            val best = nodes.filter { it.scrollable && it.visible }.maxByOrNull { it.box.width().toLong() * it.box.height() } ?: return null
            val ids = best.info.actionList.map { it.id }
            return if (forward) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id in ids || AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in ids
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id in ids || AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id in ids
        }

        /** 1.4.0-beta14: ask the largest visible scrollable area to go one page further (the list's own scroll). */
        fun scrollForward(): Boolean {
            val best = nodes.filter { it.scrollable && it.visible }.maxByOrNull { it.box.width().toLong() * it.box.height() } ?: return false
            if (!com.kilon.agentvscreen.host.VScreenAccessibilityService.verifyNodeForAction(best.info, "ACTION_SCROLL_FORWARD")) return false
            return runCatching { best.info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }.getOrDefault(false)
        }

        /** Visible editable fields, in tree order. */
        fun editables(): List<Node> = nodes.filter { it.visible && it.editable }

        /** The focused visible input field's box (where typing went), or null. */
        fun focusedInput(): Rect? = editables().firstOrNull { it.focused }?.box

        /** Visible words, in tree order (at most [max]); null when the tree could not be read. */
        fun texts(max: Int = 400): List<String>? = if (!readable) null else nodes.asSequence().filter { it.visible }.mapNotNull { it.words?.take(120) }.take(max).toList()
    }

    fun wordsOf(n: AccessibilityNodeInfo): String? = (n.text ?: n.contentDescription)?.toString()?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }

    /** Read [displayId]'s tree now (empty snapshot when the service sees nothing there). */
    fun snapshot(displayId: Int): Snapshot {
        val roots = BackgroundOperation.nodeRoots(displayId)
        var seen = 0
        fun read(n: AccessibilityNodeInfo?, depth: Int): Node? {
            if (n == null || depth > MAX_DEPTH || seen++ > MAX_NODES) return null
            val kids = ArrayList<Node>()
            for (i in 0 until n.childCount) read(runCatching { n.getChild(i) }.getOrNull(), depth + 1)?.let { kids += it }
            return Node(n, depth, kids)
        }
        return Snapshot(roots.mapNotNull { read(it, 0) })
    }
}
