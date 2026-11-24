package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.tr
import org.json.JSONObject

/**
 * 2.0.0-beta3 (3.4): per-session in-memory page card book.
 *
 * Each session holds up to [MAX_CARDS] cards with LRU eviction. Cards match by same `activity` and
 * [samePage], taking the most recently seen matching card. An existing card may be referenced (diff-only)
 * only when `turn - card.seenTurn <= MAX_REF_GAP` AND the element change ratio is strictly below
 * [MAX_DIFF_RATIO]; otherwise the card bumps its `version` and is printed in full.
 */
internal object PageBook {
    const val MAX_CARDS = 32
    const val MAX_REF_GAP = 8
    const val MAX_DIFF_RATIO = 0.4
    const val TITLE_MIN_LEN = 2
    const val TITLE_MAX_LEN = 16

    class Card(
        val id: Int,
        val activity: String?,
        var els: List<El>,
        var title: String,
        var seenTurn: Int,
        var version: Int,
    ) {
        /** Previous turn this card was seen on before the latest [match] update. */
        var prevSeenTurn: Int = seenTurn
            internal set
        /** Elements stored on this card before the latest [match] update. */
        var prevEls: List<El> = emptyList()
            internal set
        /** Whether the latest [match] qualifies to reference the old card (diff-only). */
        var canRef: Boolean = false
            internal set
        /** Whether the latest [match] newly created this card. */
        var isNew: Boolean = true
            internal set

        fun pageJson(): JSONObject = JSONObject()
            .put("id", "P$id")
            .put("title", title)
            .put("new", isNew)
            .put("seen_turn", prevSeenTurn)
    }

    data class ElementDiff(
        val added: List<El>,
        val removed: List<El>,
        val modified: List<Pair<El, El>>,
        val unchanged: List<El>,
    ) {
        val totalChanges: Int get() = added.size + removed.size + modified.size
    }

    private class Book {
        var nextId: Int = 0
        var lastId: Int = 0
        val cards = object : LinkedHashMap<Int, Card>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Card>?): Boolean = size > MAX_CARDS
        }
    }

    private val books: HashMap<String, Book> = VScreenOffloadHandler.recent()

    private fun matchesCard(card: Card, activity: String?, now: List<El>): Boolean {
        if (card.activity != activity) return false
        // 2.0 (detector): which page this is rests on what the app and OCR say, not on detected icon boxes.
        val els = Loud.textElements(now).ifEmpty { now }
        val prev = Loud.textElements(card.els).ifEmpty { card.els }
        if (samePage(prev, els)) return true
        // 1-element page (e.g. Canvas HUD with a single button): exact unchanged element stays on the same card.
        return els.size == 1 && prev.size == 1 && sameEl(prev[0], els[0])
    }

    /**
     * Match [els] on [activity] at [turn] against [sid]'s book.
     * Returns `(card, isNew)` per spec 3.4.
     */
    fun match(sid: String, activity: String?, els: List<El>, turn: Int): Pair<Card, Boolean> = synchronized(books) {
        val book = books.getOrPut(sid) { Book() }
        val existing = book.cards.values
            .filter { matchesCard(it, activity, els) }
            .maxByOrNull { it.seenTurn }

        val title = pickTitle(els, activity)
        if (existing != null) {
            // Touch LRU order.
            book.cards[existing.id]
            val prevTurn = existing.seenTurn
            val prevEls = existing.els
            val ref = shouldRef(prevEls, els, prevTurn, turn)
            existing.prevSeenTurn = prevTurn
            existing.prevEls = prevEls
            existing.canRef = ref
            existing.isNew = false
            if (!ref) existing.version++
            existing.els = els
            existing.title = title
            existing.seenTurn = turn
            book.lastId = existing.id
            return existing to false
        }

        val card = Card(
            id = ++book.nextId,
            activity = activity,
            els = els,
            title = title,
            seenTurn = turn,
            version = 1,
        ).apply {
            prevSeenTurn = turn
            prevEls = emptyList()
            canRef = false
            isNew = true
        }
        book.cards[card.id] = card
        book.lastId = card.id
        return card to true
    }

    /**
     * 2.0.0-beta3 (3.13): read-only check of whether [els] on [activity] has any `+`, `-`, or `~` diff
     * against the matching PageBook card (without mutating [books]).
     */
    fun hasTextDiff(sid: String, activity: String?, els: List<El>): Boolean = synchronized(books) {
        // 2.0 (detector): places the optional detector found carry no text; an icon box that moves on a canvas is
        // not a text difference, so they take no part in the comparison (Loud.textElements).
        val now = Loud.textElements(els)
        if (now.isEmpty()) return false
        val book = books[sid] ?: return true
        val existing = book.cards.values
            .filter { matchesCard(it, activity, now) }
            .maxByOrNull { it.seenTurn }
            ?: return true
        diffElements(Loud.textElements(existing.els), now).totalChanges > 0
    }

    /** Pure check of 3.4 reference rule: `turn - prevTurn <= 8` AND changed fraction < 40%. */
    fun shouldRef(prevEls: List<El>, nowEls: List<El>, prevTurn: Int, turn: Int): Boolean {
        if (turn - prevTurn > MAX_REF_GAP) return false
        return changeRatio(prevEls, nowEls) < MAX_DIFF_RATIO
    }

    /** Fraction of elements changed between [prevEls] and [nowEls] in `[0.0, 1.0]`. */
    fun changeRatio(prevEls: List<El>, nowEls: List<El>): Double {
        val denom = maxOf(prevEls.size, nowEls.size)
        if (denom == 0) return 0.0
        val used = BooleanArray(prevEls.size)
        var unchanged = 0
        for (e in nowEls) {
            val idx = prevEls.indices.firstOrNull { !used[it] && sameEl(prevEls[it], e) }
            if (idx != null) {
                used[idx] = true
                unchanged++
            }
        }
        return 1.0 - unchanged.toDouble() / denom.toDouble()
    }

    /** Compute `+` added, `-` removed, `~` text-changed at same place, and unchanged elements. */
    fun diffElements(prevEls: List<El>, nowEls: List<El>): ElementDiff {
        val prevUsed = BooleanArray(prevEls.size)
        val nowUsed = BooleanArray(nowEls.size)
        val unchanged = ArrayList<El>()
        val modified = ArrayList<Pair<El, El>>()

        // 1. Exact unchanged matches (sameEl).
        for ((ni, n) in nowEls.withIndex()) {
            val pi = prevEls.indices.firstOrNull { !prevUsed[it] && sameEl(prevEls[it], n) }
            if (pi != null) {
                prevUsed[pi] = true
                nowUsed[ni] = true
                unchanged += n
            }
        }
        // 2. Same position & kind with different text (~).
        for ((ni, n) in nowEls.withIndex()) {
            if (nowUsed[ni]) continue
            val pi = prevEls.indices.firstOrNull {
                !prevUsed[it] && prevEls[it].kind == n.kind &&
                    kotlin.math.abs(prevEls[it].box.centerX() - n.box.centerX()) <= 24 &&
                    kotlin.math.abs(prevEls[it].box.centerY() - n.box.centerY()) <= 24
            }
            if (pi != null) {
                prevUsed[pi] = true
                nowUsed[ni] = true
                modified += prevEls[pi] to n
            }
        }
        val added = nowEls.indices.filter { !nowUsed[it] }.map { nowEls[it] }
        val removed = prevEls.indices.filter { !prevUsed[it] }.map { prevEls[it] }
        return ElementDiff(added, removed, modified, unchanged)
    }

    /**
     * 3.4 + 2.0.0-beta8 (L.3) title rule: exclude clickable icon buttons (ImageButton / contentDescription-only
     * buttons such as "Navigate up"), prefer Toolbar title nodes then `heading=true` nodes, then topmost text
     * element (2..16 chars, not all digits); else short activity name.
     */
    fun pickTitle(els: List<El>, activity: String?): String {
        val candidates = els.asSequence()
            .filter { !it.placeholder && !it.iconButton }
            .map { it to it.text.trim() }
            .filter { (_, t) -> t.length in TITLE_MIN_LEN..TITLE_MAX_LEN && !t.all { c -> c.isDigit() } }
            .toList()
        val prioritized = candidates.filter { it.first.toolbarTitle }
            .ifEmpty { candidates.filter { it.first.heading } }
            .ifEmpty { candidates }
        val chosen = prioritized
            .sortedWith(compareBy({ it.first.box.top }, { it.first.box.left }))
            .firstOrNull()?.second
        if (chosen != null) return chosen
        return activity?.substringAfterLast('.')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: tr("页面", "Page")
    }

    fun cards(sid: String): List<Card> = synchronized(books) { books[sid]?.cards?.values?.toList().orEmpty() }

    fun current(sid: String): Card? = synchronized(books) {
        val book = books[sid] ?: return null
        book.cards[book.lastId] ?: book.cards.values.maxByOrNull { it.seenTurn }
    }

    fun size(sid: String): Int = synchronized(books) { books[sid]?.cards?.size ?: 0 }

    fun clear(sid: String) { synchronized(books) { books.remove(sid) } }

    internal fun resetForTest() { synchronized(books) { books.clear() } }
}
