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
        /** Parent node in the captured tree snapshot, or null for window roots. */
        var parent: Node? = null
            internal set
        val box: Rect = Rect().also { info.getBoundsInScreen(it) }
        val visible: Boolean = info.isVisibleToUser
        /** Its words: text, else content description, on one line; null when it has none. */
        val words: String? = wordsOf(info)
        val clickable get() = info.isClickable
        val longClickable get() = info.isLongClickable
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

        /** Nearest clickable (or ACTION_CLICK-capable) node starting from this node and walking up to ancestors. */
        fun clickableAncestor(): Node? = generateSequence(this) { it.parent }.firstOrNull { n ->
            n.clickable || n.editable || n.checkable ||
                runCatching { n.info.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_CLICK } }.getOrDefault(false)
        }

        /** Nearest long-clickable (or ACTION_LONG_CLICK-capable) node starting from this node and walking up to ancestors. */
        fun longClickableAncestor(): Node? = generateSequence(this) { it.parent }.firstOrNull { n ->
            n.longClickable ||
                runCatching { n.info.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_LONG_CLICK } }.getOrDefault(false)
        }
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

        /**
         * Match a resolved screen element ([targetBox] in frame coordinates, optional [label]) back to its backing
         * accessibility [Node] so screen-off routing can perform a verified node action. Returns null for OCR-only or
         * canvas elements with no matching node (never falls back to an unrelated full-screen container).
         */
        fun findNodeFor(targetBox: Rect, label: String?, toFrame: (Rect) -> Rect): Node? {
            if (!readable || targetBox.isEmpty) return null
            val normLabel = label?.trim()?.takeIf { it.isNotEmpty() }?.let { VScreenOcr.norm(it) }?.takeIf { it.isNotEmpty() }
            val cx = targetBox.centerX()
            val cy = targetBox.centerY()
            val targetArea = targetBox.width().toLong() * targetBox.height().toLong()

            fun iou(a: Rect, b: Rect): Double {
                val l = maxOf(a.left, b.left)
                val t = maxOf(a.top, b.top)
                val r = minOf(a.right, b.right)
                val bt = minOf(a.bottom, b.bottom)
                if (r <= l || bt <= t) return 0.0
                val inter = (r - l).toLong() * (bt - t).toLong()
                val union = a.width().toLong() * a.height().toLong() + b.width().toLong() * b.height().toLong() - inter
                return if (union <= 0L) 0.0 else inter.toDouble() / union.toDouble()
            }

            fun closeBox(a: Rect, b: Rect): Boolean =
                kotlin.math.abs(a.left - b.left) <= 24 && kotlin.math.abs(a.top - b.top) <= 24 &&
                    kotlin.math.abs(a.right - b.right) <= 24 && kotlin.math.abs(a.bottom - b.bottom) <= 24

            data class Cand(val node: Node, val score: Int, val area: Long)
            val cands = ArrayList<Cand>()
            for (n in nodes) {
                if (!n.visible) continue
                val fb = toFrame(n.box)
                if (fb.isEmpty) continue
                val area = fb.width().toLong() * fb.height().toLong()
                val ov = iou(fb, targetBox)
                val containsCenter = fb.contains(cx, cy)
                val intersects = Rect.intersects(fb, targetBox)
                if (!intersects && !containsCenter && !closeBox(fb, targetBox)) continue
                val normWords = n.words?.let { VScreenOcr.norm(it) }?.takeIf { it.isNotEmpty() }
                val textExact = normLabel != null && normWords != null && normWords == normLabel
                val textHit = textExact || (normLabel != null && normWords != null && (normWords.contains(normLabel) || (normLabel.length >= 2 && normLabel.contains(normWords))))
                val boxMatch = ov >= 0.35 || closeBox(fb, targetBox)
                if (!textHit && !boxMatch) continue
                // Guard against matching a giant container layout for a tiny OCR-only item unless text matches directly.
                if (!textHit && area > targetArea * 6L) continue
                val score = (if (textExact) 40 else if (textHit) 25 else 0) +
                    (ov * 30.0).toInt() +
                    (if (containsCenter) 10 else 0) +
                    (if (n.clickableAncestor() != null) 5 else 0)
                cands += Cand(n, score, area)
            }
            return cands.sortedWith(compareByDescending<Cand> { it.score }.thenBy { it.area }).firstOrNull()?.node
        }

        /** Perform a verified `ACTION_CLICK` on [node] (or its nearest clickable ancestor). */
        fun performClick(node: Node): Boolean {
            Control.admitInput()
            val target = node.clickableAncestor() ?: return false
            if (!com.kilon.agentvscreen.host.VScreenAccessibilityService.verifyNodeForAction(target.info, "ACTION_CLICK")) return false
            return runCatching { target.info.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        }

        /** Perform a verified `ACTION_LONG_CLICK` on [node] (or its nearest long-clickable ancestor). */
        fun performLongClick(node: Node): Boolean {
            Control.admitInput()
            val target = node.longClickableAncestor() ?: return false
            if (!com.kilon.agentvscreen.host.VScreenAccessibilityService.verifyNodeForAction(target.info, "ACTION_LONG_CLICK")) return false
            return runCatching { target.info.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)
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

        private fun scrollActionsFor(direction: String): Pair<Pair<Int, String>, Pair<Int, String>> = when (direction.lowercase()) {
            "up" -> (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id to "ACTION_SCROLL_UP") to
                (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD to "ACTION_SCROLL_BACKWARD")
            "left" -> (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id to "ACTION_SCROLL_LEFT") to
                (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD to "ACTION_SCROLL_BACKWARD")
            "right" -> (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id to "ACTION_SCROLL_RIGHT") to
                (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD to "ACTION_SCROLL_FORWARD")
            else -> (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id to "ACTION_SCROLL_DOWN") to
                (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD to "ACTION_SCROLL_FORWARD")
        }

        /** Locate a visible scrollable container, preferring one supporting [direction] and covering [atPoint] if given. */
        fun findScrollable(direction: String = "down", atPoint: Pair<Int, Int>? = null, toFrame: ((Rect) -> Rect)? = null): Node? {
            val scrollables = nodes.filter { it.scrollable && it.visible }
            if (scrollables.isEmpty()) return null
            val (dirAct, fbAct) = scrollActionsFor(direction)
            val supporting = scrollables.filter { n ->
                val ids = runCatching { n.info.actionList.map { it.id } }.getOrDefault(emptyList())
                dirAct.first in ids || fbAct.first in ids
            }.ifEmpty { scrollables }
            if (atPoint != null) {
                val atMatch = supporting.filter { n ->
                    val fb = toFrame?.invoke(n.box) ?: n.box
                    fb.contains(atPoint.first, atPoint.second)
                }.minByOrNull { it.box.width().toLong() * it.box.height() }
                if (atMatch != null) return atMatch
            }
            return supporting.maxByOrNull { it.box.width().toLong() * it.box.height() }
        }

        /**
         * Perform a verified directional accessibility scroll (`down`/`up`/`left`/`right`), preferring the matching
         * directional `ACTION_SCROLL_*` action and falling back to `ACTION_SCROLL_FORWARD`/`ACTION_SCROLL_BACKWARD`.
         */
        fun scroll(direction: String, atPoint: Pair<Int, Int>? = null, toFrame: ((Rect) -> Rect)? = null): Boolean {
            Control.admitInput()
            val best = findScrollable(direction, atPoint, toFrame) ?: return false
            val ids = runCatching { best.info.actionList.map { it.id } }.getOrDefault(emptyList())
            val (dirAct, fbAct) = scrollActionsFor(direction)
            if (dirAct.first in ids) {
                if (!com.kilon.agentvscreen.host.VScreenAccessibilityService.verifyNodeForAction(best.info, dirAct.second)) return false
                if (runCatching { best.info.performAction(dirAct.first) }.getOrDefault(false)) return true
            }
            if (!com.kilon.agentvscreen.host.VScreenAccessibilityService.verifyNodeForAction(best.info, fbAct.second)) return false
            return runCatching { best.info.performAction(fbAct.first) }.getOrDefault(false)
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
            val node = Node(n, depth, kids)
            kids.forEach { it.parent = node }
            return node
        }
        return Snapshot(roots.mapNotNull { read(it, 0) })
    }
}
