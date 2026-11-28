package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.trPair
import com.kilon.agentvscreen.core.Change
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.tr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.InputReach
import com.kilon.agentvscreen.core.InputRoute
import com.kilon.agentvscreen.core.Mask
import com.kilon.agentvscreen.core.MotionTrack
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

/**
 * Watches [conds] on [d]; the baseline (for change:) is the picture when it was made.
 *
 * 1.3 [afterAction]: the probe of an action's `until` (and its settle wait) is made BEFORE the action and judges only
 * what came AFTER it. In 1.2 `tap 蓝牙 until text:WLAN` was met at 0.0 s by the page being left (WLAN was on it), `tap
 * 显示和亮度 until text:亮度` by the tapped words themselves, `type 地图导航 until text:导航` by the typed words. Now:
 *  - nothing is judged until the app reacted: the picture changed (outside areas that were already playing) or the
 *    accessibility tree reported a change after [acted]; and words are judged once the change has calmed down (or
 *    1.5 s after it began), not mid-animation, when the old page is still half there;
 *  - `text:` counts words that are new since the action: not at the same place before it, unless the page changed
 *    (a new window / activity / most of the picture), in which case whatever the new page shows counts;
 *  - words inside the field that was just typed into never count;
 *  - OCR readings count only when taken after the change.
 */
internal class Probe(
    private val h: VScreenOffloadHandler,
    private val d: VScreenDisplay,
    val conds: List<Cond>,
    private val afterAction: Boolean = false,
    private val look: Boolean = false,
    /** 2.0.0-beta4 (4.7): closed-loop gestures evaluate `text:`/`gone:` via `VScreenOcr.readCrop` on `c.area` only, never full-screen perception OCR. */
    private val cropOnly: Boolean = false,
) {
    companion object {
        /** A settle wait after an action that changed nothing gives up waiting for a change after this long. */
        const val GRACE_MS = 1_000L
        const val CALM_MS = 1_500L
        /** After a settle wait that saw no reaction, how much longer to look for a late one before saying "no change". */
        const val LATE_MS = 1_500L
    }
    private val since = SystemClock.elapsedRealtime()
    /** Areas already playing before (video, carousel): not waited for, not read. */
    private var maskNow: Mask? = VScreenPerception.mask(d.displayId, if (look) MotionTrack.LOOK_SPAN_MS else MotionTrack.MIN_SPAN_MS)
    private var maskAt = SystemClock.elapsedRealtime()
    /**
     * 1.3.0: a look (no action of ours before it) asks again while it waits: a page whose video started just before
     * (or a watcher that has only just begun) gets its moving area after about a second, not never.
     */
    private val mask: Mask? get() {
        if (look && maskNow == null && SystemClock.elapsedRealtime() - maskAt >= 300L) {
            maskAt = SystemClock.elapsedRealtime(); maskNow = VScreenPerception.mask(d.displayId, MotionTrack.LOOK_SPAN_MS)
        }
        return maskNow
    }
    private val needsSmall = afterAction || conds.any {
        it.kind == CondKind.CHANGE || it.kind == CondKind.STILL || it.kind == CondKind.STABLE
    }
    private val colorWatches: Map<Int, VScreenDisplay.ColorWatch> = conds.withIndex()
        .filter { it.value.kind == CondKind.COLOR }
        .associate { (i, c) -> i to d.watchColor(c.x, c.y) }
    private var base: Bitmap? = if (needsSmall) d.latestFrame()?.let { VScreenPerception.small(it) } else null
    private var prev: Bitmap? = null
    private val stillMs = HashMap<Int, Long>()
    /** stable: (time, share of sampled pixels that differ from the previous look) of the last looks, per condition. */
    private val motion = HashMap<Int, ArrayDeque<Pair<Long, Double>>>()
    private val cropCache = HashMap<Rect, Pair<Bitmap, List<VScreenOcr.Item>>>()
    private var lastPoll = 0L
    private var appAt = 0L
    private var appNow: List<String> = emptyList()
    private val wantsText = conds.any { it.kind == CondKind.TEXT || it.kind == CondKind.GONE }
    private var actionAt = 0L
    private var changedAt = 0L
    private var calmAt = 0L
    private var pageAt = 0L
    private var exclude: Rect? = null
    private val top0: String? = if (afterAction) runCatching { VScreenShell.topActivity(d.displayId) }.getOrNull() else null
    private var topAt = 0L
    /** 2.0.0-beta8 (C3): whether at least one post-action OCR/crop reading or readable A11y text snapshot ran. */
    private var hadPostActionOcr = false
    private var hadPostActionA11y = false
    /** 2.0.0-beta16 (Item 1): last rejection reason per text condition key seen after the action (IN_TYPED_FIELD / PRE_EXISTING). */
    private val lastRejection = HashMap<String, CountDecision>()
    /** 2.0.0-beta16 (Item 1): text read back from the input box on `type`, and whether the step verified the typed text landed. */
    private var typedFieldText: String? = null
    private var typedConfirmed: Boolean = false
    /** Where each text condition's words were before the action. */
    private val prior: Map<String, List<Rect>> = if (!afterAction || !wantsText) emptyMap() else conds.filter { it.kind == CondKind.TEXT }.associate { c ->
        val pat = TextPat(c.text!!)
        val a = runCatching { com.kilon.agentvscreen.core.A11yTree.snapshot(d.displayId).find(pat::hit) }.getOrNull().orEmpty().map { d.toFrame(it.second) }
        val o = VScreenPerception.reading(d.displayId, 0L, 0)?.items.orEmpty().filter { pat.hit(it.text) }.map { Rect(it.box) }
        pat.key to (a + o)
    }

    /** The action was just done: from now on only what it caused counts ([kind] "type": not the field typed into). */
    fun acted(kind: StepKind, typedField: String? = null, typedBounds: Rect? = null, typeVerified: Boolean = false) {
        actionAt = SystemClock.elapsedRealtime()
        if (kind == StepKind.TYPE) {
            val rawBox = runCatching { com.kilon.agentvscreen.core.A11yTree.snapshot(d.displayId).focusedInput() }.getOrNull() ?: typedBounds
            exclude = rawBox?.let { d.toFrame(it) }
            typedFieldText = typedField
            typedConfirmed = typeVerified
        }
    }

    private val waitingForChange: Boolean get() = afterAction && actionAt > 0 && changedAt == 0L
    /** Words may be judged: the app reacted and the change calmed down (or has gone on long enough). */
    private fun wordsReady(now: Long) = !afterAction || actionAt == 0L || (changedAt > 0 && (calmAt > 0 || now - changedAt >= CALM_MS))

    fun close() {
        base?.recycle(); base = null
        prev?.recycle(); prev = null
        cropCache.values.forEach { it.first.recycle() }
        cropCache.clear()
        colorWatches.values.forEach { d.unwatchColor(it) }
    }

    /**
     * 1.4.0-beta13 (test: `repeat … until` ran one round more than needed): a short window could end before any reading
     * of the screen AFTER the round existed, and "not there" was concluded from no evidence. When words are asked for,
     * one reading newer than this probe is awaited (up to [waitMs]) and judged before saying no.
     */
    fun judgeFresh(waitMs: Long): String? {
        if (wantsText && !cropOnly) VScreenPerception.reading(d.displayId, since, waitMs)
        // 1.4.0-beta15 (test: repeat … until still ran one or two rounds more in 3 of 4 runs): the final look also
        // re-reads every accessibility node (the cache kept the display's old number) and does not hide words inside
        // "moving" areas (a display changing every round reads as a carousel). The words asked for are judged as shown.
        finalLook = true
        return try { poll() } finally { finalLook = false }
    }
    private var finalLook = false

    private fun scaled(c: Cond, b: Bitmap): Rect? = c.area?.let { r ->
        val k = b.width.toDouble() / maxOf(1, d.width)
        Rect((r.left * k).toInt(), (r.top * k).toInt(), maxOf((r.left * k).toInt() + 1, (r.right * k).toInt()), maxOf((r.top * k).toInt() + 1, (r.bottom * k).toInt()))
    }

    private fun diff(a: Bitmap, b: Bitmap, c: Cond?): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        // 1.4.0-beta5 (test: a game's title page → its loading page, 75% different, reported as "no change"): when
        // nearly the whole picture moves by itself (games, animated backgrounds), leaving the moving areas out leaves
        // nothing to compare. Then the whole picture is compared and only a change far beyond the animation counts.
        val m = mask
        if (c?.area == null && m != null && m.share > Mask.MOSTLY) {
            val r = Change.ratio(a, b, null, emptyList()).coerceAtLeast(0.0)
            return if (r >= Change.PAGE_OVER_MOTION) r else 0.0
        }
        val skip = if (c?.area != null) emptyList() else m?.rectsIn(a.width, a.height).orEmpty()
        return Change.ratio(a, b, c?.let { scaled(it, a) }, skip).coerceAtLeast(0.0)
    }

    /** 2.0.0-beta12: a small but clear change outside the areas already moving, in frame pixels (whole-screen `change` only). */
    private fun smallChange(a: Bitmap, b: Bitmap, c: Cond): Rect? {
        if (c.area != null) return null
        val m = mask
        if (m != null && m.share > Mask.MOSTLY) return null
        val r = Change.localChange(a, b, m?.rectsIn(a.width, a.height).orEmpty()) ?: return null
        val k = d.width.toDouble() / a.width
        return Rect((r.left * k).toInt(), (r.top * k).toInt(), (r.right * k).toInt(), (r.bottom * k).toInt())
    }

    private fun where(c: Cond) = c.area?.let { tr(S.vscreen_act_region, Report.box(it)) } ?: tr(S.vscreen_act_the_screen)

    /** Readings count from: this probe's start, or (after an action) the moment the change calmed down. */
    private fun readFrom(): Long = if (afterAction && changedAt > 0) maxOf(calmAt, changedAt) else since

    /** True when every pixel in [bmp] has the exact same ARGB color (provably zero text / contrast). */
    private fun isUniformSolid(bmp: Bitmap): Boolean {
        val n = bmp.width * bmp.height
        if (n <= 0) return true
        val px = IntArray(n)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val first = px[0]
        for (i in 1 until n) if (px[i] != first) return false
        return true
    }

    /** Crop-only OCR of [area] in [full], reusing cached items when pixels in [area] are unchanged since last poll. */
    private fun readCropCached(full: Bitmap?, area: Rect): List<VScreenOcr.Item>? {
        if (full == null || full.isRecycled) return null
        val r = Rect(area)
        if (!r.intersect(0, 0, full.width, full.height) || r.width() < 4 || r.height() < 4) return emptyList()
        val snap = runCatching { Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height()) }.getOrNull() ?: return null
        val prevEntry = cropCache[area]
        if (prevEntry != null && prevEntry.first.width == snap.width && prevEntry.first.height == snap.height && prevEntry.first.sameAs(snap)) {
            snap.recycle()
            return prevEntry.second
        }
        val items = if (isUniformSolid(snap)) emptyList() else runCatching { VScreenOcr.readCrop(full, area) }.getOrDefault(emptyList())
        prevEntry?.first?.recycle()
        cropCache[Rect(area)] = snap to items
        return items
    }

    /**
     * 2.0.0-beta12 (test: on Bilibili `gone:排行榜` stayed false and `if text:排行榜` was true on the 推荐 page): the
     * accessibility tree also lists words the app keeps but does not show (a page left under the one on top, a pager
     * page off to the side), and "visible to user" does not see that. An accessibility hit counts only when its box is
     * on the screen and the picture does not show other words in that place ([pictureAgrees]).
     */
    private fun shownA11y(snap: com.kilon.agentvscreen.core.A11yTree.Snapshot, pat: TextPat, recheck: Boolean): List<Pair<String, Rect>>? {
        val raw = snap.find(pat::hit, recheck = recheck) ?: return null
        val onScreen = raw.map { it.first to d.toFrame(it.second) }
            .filter { (_, b) -> b.width() > 0 && b.height() > 0 && b.centerX() in 0 until d.width && b.centerY() in 0 until d.height }
        if (onScreen.isEmpty() || cropOnly) return onScreen
        val ocr = VScreenPerception.readingWithVersion(d.displayId, readFrom(), 0)?.result?.items ?: return onScreen
        return onScreen.filter { (_, b) -> pictureAgrees(b, pat, ocr) }
    }

    /** The words of [q] seen now (accessibility first, then an OCR reading taken late enough or crop OCR), as [counts] allows. */
    private fun seen(q: String, area: Rect? = null, full: Bitmap? = null): String? {
        val pat = TextPat(q)
        val before = prior[pat.key].orEmpty()
        val recheck = finalLook || cropOnly
        fun inArea(r: Rect) = area == null || area.contains(r.centerX(), r.centerY())
        fun checkBox(r: Rect): CountDecision = counts(r, before, exclude, pageAt > 0)
        fun recordRejection(d0: CountDecision) {
            if (d0 != CountDecision.COUNTED) {
                // IN_TYPED_FIELD is more specific than PRE_EXISTING if both occur on the same screen.
                val prev = lastRejection[pat.key]
                if (prev != CountDecision.IN_TYPED_FIELD || d0 == CountDecision.IN_TYPED_FIELD) {
                    lastRejection[pat.key] = d0
                }
            }
        }
        val a11ySnap = runCatching { com.kilon.agentvscreen.core.A11yTree.snapshot(d.displayId) }.getOrNull()
        if (a11ySnap != null && a11ySnap.readable) {
            if (a11ySnap.texts(1)?.isNotEmpty() == true) hadPostActionA11y = true
            val a11yMatches = shownA11y(a11ySnap, pat, recheck)
            if (a11yMatches != null) {
                for ((text, box) in a11yMatches) {
                    if (!inArea(box)) continue
                    if (!afterAction || actionAt == 0L) {
                        lastObsId = ++a11yObsSeq
                        return text
                    }
                    val dec = checkBox(box)
                    if (dec.ok) {
                        lastObsId = ++a11yObsSeq
                        return text
                    }
                    recordRejection(dec)
                }
            }
        }
        if (cropOnly && area != null) {
            val items = readCropCached(full ?: d.latestFrame(), area) ?: return null
            hadPostActionOcr = true
            items.firstOrNull { pat.hit(it.text) }?.let { return ScreenText.clean(it.text, maxLen = 40) }
            return VScreenPerception.joinSplit(items).firstOrNull { pat.hit(it) }?.let { ScreenText.clean(it, maxLen = 40) }
        }
        val ocr = VScreenPerception.readingWithVersion(d.displayId, readFrom(), 0)
        if (ocr != null) {
            hadPostActionOcr = true
            val r = ocr.result
            val items = if (finalLook) r.items else VScreenPerception.still(r.items, mask)
            for (it in items) {
                val box = Rect(it.box)
                if (!pat.hit(it.text) || !inArea(box)) continue
                if (!afterAction || actionAt == 0L) {
                    lastObsId = -(ocr.version + 1L)
                    lastObsEpochMs = ocr.epochMs.takeIf { e -> e > 0L }
                    return ScreenText.clean(it.text, maxLen = 40)
                }
                val dec = checkBox(box)
                if (dec.ok) {
                    lastObsId = -(ocr.version + 1L)
                    lastObsEpochMs = ocr.epochMs.takeIf { e -> e > 0L }
                    return ScreenText.clean(it.text, maxLen = 40)
                }
                recordRejection(dec)
            }
        }
        when {
            a11ySnap != null && a11ySnap.readable && a11ySnap.texts(1)?.isNotEmpty() == true -> {
                lastObsId = ++a11yObsSeq
                lastObsEpochMs = null
            }
            ocr != null -> {
                lastObsId = -(ocr.version + 1L)
                lastObsEpochMs = ocr.epochMs.takeIf { it > 0L }
            }
            a11ySnap != null && a11ySnap.readable -> {
                lastObsId = ++a11yObsSeq
                lastObsEpochMs = null
            }
            else -> {
                lastObsId = null
                lastObsEpochMs = null
            }
        }
        return null
    }

    /** True only when a reading taken late enough does not hold [q] (and the accessibility tree does not either). */
    private fun gone(q: String, area: Rect? = null, full: Bitmap? = null): Boolean {
        val pat = TextPat(q)
        val recheck = finalLook || cropOnly
        fun inArea(r: Rect) = area == null || area.contains(r.centerX(), r.centerY())
        val a11ySnap = runCatching { com.kilon.agentvscreen.core.A11yTree.snapshot(d.displayId) }.getOrNull()
        if (a11ySnap != null && a11ySnap.readable) {
            if (a11ySnap.texts(1)?.isNotEmpty() == true) hadPostActionA11y = true
            val present = shownA11y(a11ySnap, pat, recheck)?.any { inArea(it.second) } == true
            if (present) {
                lastObsId = ++a11yObsSeq
                lastObsEpochMs = null
                return false
            }
        }
        if (cropOnly && area != null) {
            val items = readCropCached(full ?: d.latestFrame(), area) ?: return false
            hadPostActionOcr = true
            return items.none { pat.hit(it.text) } && VScreenPerception.joinSplit(items).none { pat.hit(it) }
        }
        val ocr = VScreenPerception.readingWithVersion(d.displayId, readFrom(), 0)
        if (ocr == null) {
            lastObsId = null
            lastObsEpochMs = null
            return false
        }
        hadPostActionOcr = true
        lastObsId = -(ocr.version + 1L)
        lastObsEpochMs = ocr.epochMs.takeIf { it > 0L }
        return VScreenPerception.still(ocr.result.items, mask).none { pat.hit(it.text) && inArea(Rect(it.box)) }
    }

    /** 2.0.0-beta16 (Item 1): whether [c] appeared only inside the input box just typed into. */
    private fun isOnlyInFieldNow(c: Cond): Boolean {
        if (!afterAction || actionAt == 0L || c.kind != CondKind.TEXT) return false
        val q = c.text ?: return false
        val pat = TextPat(q)
        if (lastRejection[pat.key] == CountDecision.IN_TYPED_FIELD) return true
        val box = exclude
        if (typedFieldText != null && pat.hit(typedFieldText!!)) {
            if (c.area == null || box == null || c.area.contains(box.centerX(), box.centerY())) return true
        }
        if (box == null) return false
        fun inArea(r: Rect) = c.area == null || c.area.contains(r.centerX(), r.centerY())
        val a11y = runCatching { com.kilon.agentvscreen.core.A11yTree.snapshot(d.displayId) }.getOrNull()
        if (a11y != null && a11y.readable) {
            val hit = a11y.find(pat::hit, recheck = true)?.any {
                val r = d.toFrame(it.second)
                inArea(r) && box.contains(r.centerX(), r.centerY())
            } == true
            if (hit) return true
        }
        val latest = VScreenPerception.reading(d.displayId, 0L, 0)?.items.orEmpty()
        return latest.any {
            val r = Rect(it.box)
            pat.hit(it.text) && inArea(r) && box.contains(r.centerX(), r.centerY())
        }
    }

    /** 2.0.0-beta16 (Item 1): true when `type` already read back the box and its text equals [c]'s target. */
    private fun typedFieldEqualsTarget(c: Cond): Boolean {
        val field = typedFieldText ?: return false
        val q = c.text ?: return false
        return typedConfirmed && TextPat(q).exact(field)
    }

    /** 2.0.0-beta8 (C3): whether [q] currently appears on screen (ignoring [counts] pre-existing exclusion) AND existed before the action. */
    private fun isPreExistingNow(c: Cond): Boolean {
        if (!afterAction || actionAt == 0L || c.kind != CondKind.TEXT) return false
        val q = c.text ?: return false
        val pat = TextPat(q)
        val before = prior[pat.key].orEmpty()
        if (before.isEmpty()) return false
        if (lastRejection[pat.key] == CountDecision.PRE_EXISTING) return true
        fun inArea(r: Rect) = c.area == null || c.area.contains(r.centerX(), r.centerY())
        val a11y = runCatching { com.kilon.agentvscreen.core.A11yTree.snapshot(d.displayId) }.getOrNull()
        if (a11y != null && a11y.readable) {
            val hit = a11y.find(pat::hit, recheck = true)?.any { inArea(d.toFrame(it.second)) } == true
            if (hit) return true
        }
        val latest = VScreenPerception.reading(d.displayId, 0L, 0)?.items.orEmpty()
        return latest.any { pat.hit(it.text) && inArea(Rect(it.box)) }
    }

    /** 2.0.0-beta8 (C3) + 2.0.0-beta16 (Item 1): classify why a TEXT/GONE condition timed out. */
    private fun textTimeoutKindFor(c: Cond): TextTimeoutKind {
        if (!cropOnly && !hadPostActionOcr && VScreenPerception.readingWithVersion(d.displayId, readFrom(), 0) != null) {
            hadPostActionOcr = true
        }
        val onlyInField = c.kind == CondKind.TEXT && isOnlyInFieldNow(c)
        val preExisting = !onlyInField && c.kind == CondKind.TEXT && isPreExistingNow(c)
        val ocrBusy = com.kilon.agentvscreen.core.VScreenPaddleOcr.busy()
        return classifyTextTimeout(
            preExisting = preExisting,
            hadPostActionOcr = hadPostActionOcr,
            hadPostActionA11y = hadPostActionA11y,
            ocrBusy = ocrBusy,
            waitingForChange = waitingForChange,
            onlyInField = onlyInField,
        )
    }

    /** 2.0.0-beta8 (C3): `NOT_READ` when every text/gone condition failed due to missing post-action reading, else `STEP_MISSED`. */
    fun timeoutCode(): String {
        val textConds = conds.filter { it.kind == CondKind.TEXT || it.kind == CondKind.GONE }
        if (textConds.isNotEmpty() && textConds.all { textTimeoutKindFor(it) == TextTimeoutKind.NOT_READ }) {
            return "NOT_READ"
        }
        return "STEP_MISSED"
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

    private var colorWatchHits = 0
    private var colorFallbackHits = 0

    /** Read live color at condition [i] from [VScreenDisplay.ColorWatch] when fresh, falling back to [full]. */
    private fun liveColor(i: Int, c: Cond, full: Bitmap?): Int? {
        val w = colorWatches[i]
        if (w != null && w.sampledAtMs >= 0L && w.sampledAtMs >= d.latestCaptureAtMs) {
            colorWatchHits++
            return w.color
        }
        colorFallbackHits++
        return full?.takeIf { c.x in 0 until it.width && c.y in 0 until it.height }?.let { VPixels.at(it, c.x, c.y) }
    }

    internal fun colorDiag(hitElapsed: Long, upElapsed: Long, startElapsed: Long): String {
        val w = colorWatches.values.firstOrNull()
        val sampleLag = if (w != null && hitElapsed > 0L && w.sampledAtMs > 0L) hitElapsed - w.sampledAtMs else -1L
        val hitToUp = if (hitElapsed > 0L) upElapsed - hitElapsed else -1L
        return "totalMs=${upElapsed - startElapsed} hitToUp=$hitToUp watchHits=$colorWatchHits fbHits=$colorFallbackHits " +
            "samples=${w?.samples ?: -1} lastImgAge=${w?.lastImgAgeMs ?: -1} maxImgAge=${w?.maxImgAgeMs ?: -1} " +
            "sampleLag=$sampleLag previewMs=${d.previewIntervalMs} ocrBusy=${com.kilon.agentvscreen.core.VScreenPaddleOcr.busy()}"
    }

    /** For a wait that timed out: what each condition sees now (a fact the caller can compare with what it asked). */
    fun now(): String {
        val kinds = conds.map { c -> if (c.kind == CondKind.TEXT || c.kind == CondKind.GONE) textTimeoutKindFor(c) else null }
        val hideNoReaction = kinds.any { it == TextTimeoutKind.ONLY_IN_FIELD }
        val prefix = if (waitingForChange && !hideNoReaction) tr(S.vscreen_act_nothing_on_the_screen_changed) else ""
        return prefix + conds.withIndex().joinToString(tr(S.agent_guide_sep)) { (i, c) ->
            when (c.kind) {
                // 1.4.0-beta5 + 2.0.0-beta8 (C3) + 2.0.0-beta16 (Item 1): distinguish absent / pre-existing / only-in-field / not-read.
                CondKind.TEXT -> {
                    val kind = kinds[i] ?: TextTimeoutKind.ABSENT
                    if (kind != TextTimeoutKind.ABSENT) {
                        formatTextTimeout(
                            c.text!!,
                            kind,
                            com.kilon.agentvscreen.core.VScreenPaddleOcr.busy(),
                            typedMatchesTarget = kind == TextTimeoutKind.ONLY_IN_FIELD && typedFieldEqualsTarget(c),
                        )
                    } else if (c.area != null) {
                        if (cropOnly) tr(S.vscreen_act_is_not_in, where(c), c.text)
                        else {
                            val elsewhere = runCatching { seen(c.text!!) }.getOrNull() != null
                            if (elsewhere) tr(S.vscreen_act_is_not_in_it_is, where(c), c.text)
                            else tr(S.vscreen_act_is_not_in_nor_anywhere, where(c), c.text)
                        }
                    } else formatTextTimeout(c.text!!, TextTimeoutKind.ABSENT, false)
                }
                CondKind.GONE -> {
                    val kind = kinds[i] ?: TextTimeoutKind.ABSENT
                    if (kind == TextTimeoutKind.NOT_READ) {
                        formatTextTimeout(c.text!!, TextTimeoutKind.NOT_READ, com.kilon.agentvscreen.core.VScreenPaddleOcr.busy())
                    } else tr(S.vscreen_act_is_still_there, c.text)
                }
                CondKind.CHANGE -> tr(S.vscreen_act_no_clear_change_in, where(c))
                CondKind.STILL -> tr(S.vscreen_act_keeps_changing, where(c))
                CondKind.STABLE -> tr(S.vscreen_act_is_still_changing_not_a, where(c))
                CondKind.COLOR -> liveColor(i, c, d.latestFrame())
                    ?.let { tr(S.vscreen_act_is_now, c.x, c.y, VPixels.hex(it)) }
                    ?: tr(S.vscreen_act_cannot_read_the_color_at, c.x, c.y)
                CondKind.APP -> tr(S.vscreen_act_front_app_is) + (appFacts().takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: tr(S.vscreen_act_unreadable))
                else -> c.raw
            }
        }
    }

    /** null = nothing met yet; otherwise "<condition>：<what was seen>". */
    fun poll(): String? {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastPoll == 0L) 0L else now - lastPoll
        lastPoll = now
        lastObsId = null
        lastObsEpochMs = null
        if (wantsText && !cropOnly) VScreenPerception.hurry(d.displayId)
        val needFull = needsSmall || wantsText || conds.withIndex().any { (i, c) ->
            c.kind == CondKind.COLOR && colorWatches[i]?.let { it.sampledAtMs < 0L || it.sampledAtMs < d.latestCaptureAtMs } != false
        }
        val full = if (needFull) d.latestFrame() else null
        val cur = if (needsSmall) full?.let { VScreenPerception.small(it) } else null
        try {
            if (afterAction && actionAt > 0) {
                // Did the app react, has the change calmed down, is it a new page?
                val b = base
                val whole = if (b != null && cur != null) diff(b, cur, null) else 0.0
                val evAt = com.kilon.agentvscreen.host.VScreenAccessibilityService.contentChangedAt(d.displayId)
                if (changedAt == 0L && (whole >= Change.Level.STILL.max || evAt > actionAt)) changedAt = now
                if (changedAt > 0 && calmAt == 0L && now > changedAt) { val p = prev; if (p != null && cur != null && diff(p, cur, null) <= Change.Level.STILL.max) calmAt = now }
                if (pageAt == 0L) {
                    if (com.kilon.agentvscreen.host.VScreenAccessibilityService.pageChangedAt(d.displayId) > actionAt || whole >= 0.3) pageAt = now
                    else if (now - topAt >= 500L) { topAt = now; runCatching { VScreenShell.topActivity(d.displayId) }.getOrNull()?.let { if (top0 != null && it != top0) pageAt = now } }
                }
            }
            val wordsOk = wordsReady(now)
            for ((i, c) in conds.withIndex()) {
                val hit: String? = when (c.kind) {
                    CondKind.TEXT -> if (!wordsOk) null else seen(c.text!!, c.area, full)?.let { tr(S.vscreen_act_saw, it) }
                    CondKind.GONE -> if (!wordsOk) null else if (gone(c.text!!, c.area, full)) tr(S.vscreen_act_is_gone_from_the_screen, c.text) else null
                    CondKind.CHANGE -> { val b = base; if (b != null && cur != null) diff(b, cur, c).takeIf { it >= Change.CHANGED }?.let { tr(S.vscreen_act_changed_of_sample_points_differ, where(c), (it * 100).roundToInt()) }
                        ?: smallChange(b, cur, c)?.let { r -> tr(S.vscreen_act_a_small_part_of_the, Report.box(r)) } else null }
                    CondKind.STILL -> {
                        val p = prev
                        if (p != null && cur != null && diff(p, cur, c) <= Change.Level.STILL.max) stillMs[i] = (stillMs[i] ?: 0L) + dt else stillMs[i] = 0L
                        val s = stillMs[i] ?: 0L
                        if (!(waitingForChange && now - actionAt < GRACE_MS) && s >= 500L && now - since >= 300L) tr(S.vscreen_act_unchanged_for_ms, where(c), s) else null
                    }
                    CondKind.STABLE -> {
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
                            if (df <= Change.Level.STILL.max) stillMs[i] = (stillMs[i] ?: 0L) + dt else stillMs[i] = 0L
                        }
                        val s = stillMs[i] ?: 0L
                        when {
                            // After an action: not settled before the app reacted (unless it never does: GRACE_MS).
                            waitingForChange && now - actionAt < GRACE_MS -> null
                            s >= 500L && now - since >= 300L -> tr(S.vscreen_act_unchanged_for_ms, where(c), s)
                            now - since >= 1_200L && q.size >= 5 && steady(q.map { it.second }) -> tr(S.vscreen_act_is_left_with_only_a, where(c), (q.maxOf { it.second } * 100).roundToInt().coerceAtLeast(1))
                            else -> null
                        }
                    }
                    CondKind.COLOR -> liveColor(i, c, full)?.let { px ->
                        if (VPixels.dist(px, c.color) <= c.tol) tr(S.vscreen_act_is, c.x, c.y, VPixels.hex(px)) else null
                    }
                    CondKind.APP -> {
                        if (now - appAt >= 500L) { appNow = appFacts(); appAt = now }
                        appNow.firstOrNull { it.contains(c.text!!, ignoreCase = true) }?.let { tr(S.vscreen_act_front_app_is_2, appNow.first()) }
                    }
                    else -> null
                }
                if (hit != null) {
                    lastMetKind = c.kind
                    lastHitImgAgeMs = if (c.kind == CondKind.COLOR) {
                        colorWatches[i]?.lastImgAgeMs?.takeIf { it >= 0L }
                    } else null
                    return tr(S.connect_name_value, c.raw, hit)
                }
            }
            return null
        } finally { prev?.recycle(); prev = cur }
    }

    /** 2.0.0-beta3 (3.13): the kind of the condition that most recently matched in [poll]. */
    var lastMetKind: CondKind? = null
        private set

    /** 2.0.0-beta5 (5.7 #2): frame age (ms) from [VScreenDisplay.ColorWatch.lastImgAgeMs] on the most recent COLOR hit. */
    var lastHitImgAgeMs: Long? = null
        private set

    /** 2.0.0-beta5 (5.8 #3): independent observation ID of the latest poll (positive = A11y snapshot, negative = OCR readingVersion). */
    var lastObsId: Long? = null
        private set

    /** 2.0.0-beta5 (5.8 #2): frame capture epoch ms when the latest observation came from OCR (null = live A11y poll time). */
    var lastObsEpochMs: Long? = null
        private set

    private var a11yObsSeq = 0L
}

/** 2.0.0-beta8 (C3) + 2.0.0-beta16 (Item 1): mutually exclusive explanations when a text wait times out. */
internal enum class TextTimeoutKind { ABSENT, PRE_EXISTING, ONLY_IN_FIELD, NOT_READ }

/** 2.0.0-beta8 (C3) + 2.0.0-beta16 (Item 1), pure: classify why a `text:` / `gone:` wait timed out. */
internal fun classifyTextTimeout(
    preExisting: Boolean,
    hadPostActionOcr: Boolean,
    hadPostActionA11y: Boolean,
    ocrBusy: Boolean,
    waitingForChange: Boolean,
    onlyInField: Boolean = false,
): TextTimeoutKind = when {
    onlyInField -> TextTimeoutKind.ONLY_IN_FIELD
    preExisting -> TextTimeoutKind.PRE_EXISTING
    !waitingForChange && !hadPostActionOcr && (ocrBusy || !hadPostActionA11y) -> TextTimeoutKind.NOT_READ
    else -> TextTimeoutKind.ABSENT
}

/** 2.0.0-beta8 (C3) + 2.0.0-beta16 (Item 1), pure: user/agent-facing explanation for each [TextTimeoutKind]. */
internal fun formatTextTimeout(
    text: String,
    kind: TextTimeoutKind,
    ocrBusy: Boolean,
    typedMatchesTarget: Boolean = false,
): String = when (kind) {
    TextTimeoutKind.ABSENT -> tr(S.vscreen_act_is_not_on_the_screen, text)
    TextTimeoutKind.PRE_EXISTING -> tr(S.vscreen_act_was_already_on_the_screen, text)
    TextTimeoutKind.ONLY_IN_FIELD -> if (typedMatchesTarget) tr(S.vscreen_act_typing_already_succeeded_wrong_condition, text) else tr(S.vscreen_act_appeared_only_inside_the_box, text)
    TextTimeoutKind.NOT_READ -> if (com.kilon.agentvscreen.host.ThermalGuard.ocrPausedOrFalse()) {
        tr(S.vscreen_act_the_phone_is_hot_and)
    } else if (ocrBusy) {
        tr(S.vscreen_act_screen_reading_did_not_keep)
    } else {
        tr(S.vscreen_act_screen_reading_did_not_keep_2)
    }
}

/**
 * 2.0.0-beta12, pure: whether the picture's reading [ocr] agrees that words matching [pat] are drawn at [box]. It
 * disagrees only when it read other words there (the place is covered by something else) and none of them is
 * these words or close to them; nothing read there (an icon, a picture, words the reading missed) is no evidence.
 */
internal fun pictureAgrees(box: Rect, pat: TextPat, ocr: List<com.kilon.agentvscreen.core.VScreenOcr.Item>): Boolean {
    val there = ocr.filter { val c = Rect(it.box); Rect.intersects(c, box) && (box.contains(c.centerX(), c.centerY()) || c.contains(box.centerX(), box.centerY())) }
    if (there.isEmpty()) return true
    if (there.any { pat.hit(it.text) }) return true
    if (pat.hit(there.sortedBy { it.box.left }.joinToString("") { it.text })) return true
    val key = pat.key
    if (key.isEmpty() || pat.wild) return true
    // A misread of the same words (one or two characters wrong) still agrees.
    return there.any { similar(com.kilon.agentvscreen.core.VScreenOcr.norm(it.text), key) }
}

private fun similar(a: String, key: String): Boolean {
    if (a.isEmpty()) return false
    val common = key.count { it in a }
    return common * 2 >= key.length && common >= 1 && (key.length <= 2 || common >= key.length - 2)
}

/** 2.0.0-beta16 (Item 1): why a matched text box after an action does or does not count for `until text:`. */
internal enum class CountDecision {
    COUNTED,
    IN_TYPED_FIELD,
    PRE_EXISTING;
    val ok: Boolean get() = this == COUNTED
}

/**
 * 1.3 + 2.0.0-beta16 (Item 1), pure: whether words found at [r] after an action count for `until text:`, and why not.
 * Never inside [exclude] (the field typed into); on a new page always; on the same page only when the words were not
 * at that place before the action.
 */
internal fun counts(r: Rect, before: List<Rect>, exclude: Rect?, newPage: Boolean): CountDecision {
    if (exclude != null && exclude.contains(r.centerX(), r.centerY())) return CountDecision.IN_TYPED_FIELD
    if (newPage) return CountDecision.COUNTED
    return if (before.none { Math.abs(it.centerX() - r.centerX()) <= 24 && Math.abs(it.centerY() - r.centerY()) <= 24 }) {
        CountDecision.COUNTED
    } else {
        CountDecision.PRE_EXISTING
    }
}

/** Small and even motion: every look differs from the one before by a similar, small share (a loop, not an arrival). Pure. */
internal fun steady(diffs: List<Double>): Boolean {
    if (diffs.size < 5) return false
    val mx = diffs.max(); val mn = diffs.min()
    return mx <= 0.08 && mx <= mn * 1.6 + 0.003
}

/** How one wait ended: [met] = the condition's fact, [stopped] = a stop condition's fact, else it timed out. */
internal class WaitOut(val met: String?, val stopped: String?, val ms: Long, val metKind: CondKind? = null)

/** 2.0.0-beta5 (5.2): result of one short-range rule check pass inside [waitOn] or before a step. */
internal data class RuleTick(val elapsedMs: Long, val stopped: String? = null)

/**
 * Wait on [probe] (null = just time) up to [maxMs], checking [stop] and short-range [rules] too. 1.2: frame-driven:
 * it looks when a new frame arrives (at most every 33 ms) and at least every 100 ms, instead of on a fixed 100 ms clock;
 * it ends at once when the caller went away (CANCELLED) or the screen is gone (CONNECTION_LOST).
 * 2.0.0-beta5 (5.2): when a rule fires via [runRules], [deadline] is extended by the time spent running/settling the rule.
 */
internal fun waitOn(
    d: VScreenDisplay,
    probe: Probe?,
    maxMs: Long,
    stop: Probe?,
    rules: List<RuleLive> = emptyList(),
    runRules: (() -> RuleTick)? = null,
): WaitOut {
    val t0 = SystemClock.elapsedRealtime()
    var deadline = t0 + maxMs
    val call = Call.current()
    var seen = d.version
    while (true) {
        val tick = SystemClock.elapsedRealtime()
        d.awaitNewFrame(seen, 100, urgent = true)
        seen = d.version
        (33 - (SystemClock.elapsedRealtime() - tick)).let { if (it > 0) Thread.sleep(it) }
        val el = SystemClock.elapsedRealtime() - t0
        checkInterrupts(d, call)
        stop?.poll()?.let { return WaitOut(null, it, el) }
        if (rules.isNotEmpty() && runRules != null) {
            val rt = runRules()
            if (rt.stopped != null) return WaitOut(null, rt.stopped, SystemClock.elapsedRealtime() - t0)
            if (rt.elapsedMs > 0L) {
                deadline += rt.elapsedMs
                seen = d.version
                checkInterrupts(d, call)
                stop?.poll()?.let { return WaitOut(null, it, SystemClock.elapsedRealtime() - t0) }
            }
        }
        probe?.poll()?.let { return WaitOut(it, null, SystemClock.elapsedRealtime() - t0, probe.lastMetKind) }
        if (SystemClock.elapsedRealtime() >= deadline) return WaitOut(null, null, SystemClock.elapsedRealtime() - t0)
    }
}

/** What ends every wait at once: the caller went away, the screen is gone, the user took over or stopped. */
internal fun checkInterrupts(d: VScreenDisplay, call: Call?) {
    if (call?.cancelled == true) throw ActionError("CANCELLED", tr(S.vscreen_act_the_caller_disconnected_or_cancelled))
    connectionLost(d)?.let { throw ActionError("CONNECTION_LOST", it) }
    if (BackgroundOperation.state.value.takeoverSessionId != null) throw ActionError("USER_TAKEOVER", tr(S.vscreen_act_the_user_took_over_the))
    if (BackgroundOperation.state.value.userStopped) throw ActionError("USER_STOPPED", BackgroundOperation.userStoppedMessage())
}

/**
 * How the look for a late reaction ended; times are ms after the action finished, on the one clock lookLate was given.
 * [same]: the last picture still equals the one before the action (null = no picture). [changedMs]: when the first
 * different picture was seen. [stopped]: a stop condition's fact, seen while looking or while the late change settled.
 * [settle]: the wait for the late change to settle. [endMs]: when the look ended (the whole wait).
 */
internal class LateOut(val same: Boolean?, val changedMs: Long?, val stopped: String?, val settle: WaitOut?, val endMs: Long) {
    /** The wait's words when a stop condition fired or the screen changed late (else null). */
    fun waitText(): String? = endMs.let { waitedMs -> when {
        stopped != null -> tr(S.vscreen_act_stop_condition_at, s1(waitedMs), stopped)
        changedMs == null -> null
        settle?.met != null -> tr(S.vscreen_act_the_screen_changed_late_after, s1(changedMs), s1(waitedMs))
        else -> tr(S.vscreen_act_the_screen_changed_late_after_2, s1(changedMs), s1(waitedMs))
    } }
}

/**
 * 1.4.2 (E2E on a slow emulator: `tap Battery` opened Battery about 2 s later, but the reply said "the screen did not
 * change"): after a settle wait that saw no reaction, look up to [windowMs] longer. [nextSame] waits for the next
 * picture and says whether it still equals the one before the action; [stop] is checked on every picture, as in any
 * wait; a late change is timed when first seen and then waited out with [settle]. [sinceDone]: ms since the action
 * finished, the same clock the step's first wait is timed on, so every time in the reply counts from one moment.
 */
internal fun lookLate(windowMs: Long, sinceDone: () -> Long, nextSame: () -> Boolean?, stop: () -> String?, settle: () -> WaitOut): LateOut {
    val t0 = sinceDone()
    var same: Boolean? = true
    while (same == true && sinceDone() - t0 < windowMs) {
        same = nextSame()
        val seenAt = sinceDone()
        stop()?.let { return LateOut(same, seenAt.takeIf { same == false }, it, null, seenAt) }
        if (same == false) {
            val s = settle()
            return LateOut(false, seenAt, s.stopped, s, sinceDone())
        }
    }
    return LateOut(same, null, null, null, sinceDone())
}

/**
 * Options of one stretch. [fast]: no settling wait between actions (the last step still waits). [bare]: the app's
 * before / after steps are skipped. [skipLookSettle]: skip the 3s look settle when replaying `act --full`.
 */
internal class SegmentOpts(
    val stop: List<Cond>,
    val watchMs: Long?,
    val bare: Boolean,
    val fast: Boolean,
    val human: Boolean = false,
    val skipLookSettle: Boolean = false,
    val rules: List<RuleSpec> = emptyList(),
)

/** A step that was not done, and why (it ends the stretch; the reason goes to the notes). */
internal class StepRefused(
    val code: String,
    message: String,
    val data: org.json.JSONObject? = null,
    val systemMessage: String = message,
    val screenExcerpts: List<String> = emptyList(),
) : Exception(message)

/**
 * Separated display suffix, strictly system-authored suffix, and third-party screen excerpts
 * for wait/expectation timeout notes so `Note.system_text` never mixes untrusted page text.
 */
internal data class ScreenNowParts(
    val displaySuffix: String,
    val systemSuffix: String,
    val excerpts: List<String>,
)

internal fun buildScreenNowParts(activity: String?, els: List<El>): ScreenNowParts {
    val excerpts = els.filter { !it.placeholder }
        .map { ScreenText.clean(it.text, maxLen = 40) }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(10)
    val quoted = excerpts.map { ScreenText.quote(it, maxLen = 16) }
    val actName = activity?.substringAfterLast('/')?.let { ScreenText.clean(it, maxLen = 40) }
    val display = if (I18n.en) {
        "Now: ${actName ?: "front app unreadable"}" +
            if (quoted.isEmpty()) ", no text read on the screen" else ", visible: ${quoted.joinToString(", ")}"
    } else {
        "现在：${actName ?: "前台读不到"}" +
            if (quoted.isEmpty()) "，画面上没有读到字" else "，能看到 ${quoted.joinToString("、")}"
    }
    val sys = if (I18n.en) {
        "Now: ${actName ?: "front app unreadable"}" +
            if (excerpts.isEmpty()) ", no text read on the screen" else " (visible text listed in screen_excerpts)"
    } else {
        "现在：${actName ?: "前台读不到"}" +
            if (excerpts.isEmpty()) "，画面上没有读到字" else "（可见文字见 screen_excerpts）"
    }
    return ScreenNowParts(display, sys, excerpts)
}

internal fun buildMissedNote(
    code: String,
    displayDetail: String,
    systemDetail: String,
    excerpts: List<String>,
    skipped: Int,
): Note {
    val skipSuffix = if (skipped > 0) {
        tr(S.vscreen_act_the_remaining_steps_were_not, skipped)
    } else ""
    val tail = tr(S.vscreen_act_look_at_the_screenshot_to)
    val display = tr(S.vscreen_act_expectation_missed_the_stretch_stops, displayDetail) + skipSuffix + tail
    val sys = tr(S.vscreen_act_expectation_missed_the_stretch_stops, systemDetail) + skipSuffix + tail
    return Note(code = code, text = display, screenExcerpts = excerpts, systemText = sys)
}

/**
 * What a stretch did. [outcome]: done | stopped (a stop condition fired) | missed (a step was done but what it expected
 * never came) | refused (a step was not done) | interrupted (takeover, stop, lost connection, cancelled).
 */
internal class SegmentOut(
    val lines: List<String>,
    val steps: List<StepOut>,
    val notes: Notes,
    val actionMs: Long,
    val start: Long,
    val outcome: String,
    val stoppedAt: String?,
    val startPkg: String? = null,
    val firstActionMask: Mask? = null,
    val beforeChanged: Boolean = false,
    val afterStartMs: Long = 0L,
    val excludeWindows: List<LongRange> = emptyList(),
    val rulesFired: List<RuleFired> = emptyList(),
    val errorData: org.json.JSONObject? = null,
)

private fun s1(ms: Long) = "${(ms / 100) / 10.0}s"

/** The app a session works on: the one it launched (pages of other packages included), else the one on top. */
internal fun VScreenOffloadHandler.workApp(sid: String, d: VScreenDisplay): String? =
    VScreenKnowledge.journey(sid) ?: runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()?.takeIf { it != context.packageName }

/** 1.3.0-beta18: the most steps one stretch does, repeats counted (each step of each round). */
private const val MAX_RUN = 300

private val INTERRUPTS = setOf("APP_WITH_USER", "USER_TAKEOVER", "USER_STOPPED", "CONTROL_CHANGED", "DISABLED", "CONNECTION_LOST", "CANCELLED", "NOT_CONNECTED", "BUSY", "NO_SCREEN")

/** Run a stretch of [steps] on the session's screen. Never throws for a step: a failed step ends the stretch and is said. */
internal fun VScreenOffloadHandler.runSegment(
    sid: String,
    d: VScreenDisplay,
    steps: List<Step>,
    o: SegmentOpts,
    prevReturnAt: Long? = null,
): SegmentOut {
    val start = System.currentTimeMillis()
    val t0 = SystemClock.elapsedRealtime()
    val lines = ArrayList<String>()
    val outs = ArrayList<StepOut>()
    val notes = Notes()
    val main0 = mainWatch(sid)
    VScreenAwake.ensure(d)?.let { notes += it }
    BackgroundOperation.takeWakeNote(d.displayId)?.let { notes.add("SCREEN_RESUMED", it) }
    VScreenAwake.kick("act")
    VScreenPerception.ensure(d)
    val startPkg = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()
    val claimedWindows = ArrayList<LongRange>()
    val rulesFired = ArrayList<RuleFired>()
    var beforeCaptured = false
    var firstActionMask: Mask? = null
    var beforeChanged = false
    fun captureBeforeIfNeeded() {
        if (beforeCaptured) return
        beforeCaptured = true
        firstActionMask = VScreenPerception.mask(d.displayId)
        d.latestWithVersion()?.let { (bmp, ver) ->
            VScreenRecorder.offerFresh(d, bmp, ver, startPkg)
        }
        beforeChanged = hasPictureEvent(d, prevReturnAt, System.currentTimeMillis(), firstActionMask, claimedWindows.toList())
    }
    val app = workApp(sid, d)
    val prof = if (o.bare) emptyMap() else app?.let { VScreenMap.profile(it) }.orEmpty()
    muteNote(d)?.let { notes += it }
    // 1.3: time that is not any step's (waking the screen, reading the app's profile) is said, never left unexplained.
    val setupMs = SystemClock.elapsedRealtime() - t0
    fun kept(key: String): List<Step>? = prof[key]?.let { body ->
        runCatching { Steps.parseAll(body) }.getOrElse {
            notes.add("INVALID_STEP", tr(S.vscreen_act_the_steps_kept_for_this, key, it.message?.take(80))); null
        }
    }
    val beforeSteps = kept("before"); val afterSteps = kept("after")
    val stop = if (o.stop.isNotEmpty()) Probe(this, d, o.stop) else null
    val ruleLives = o.rules.mapIndexed { idx, spec ->
        RuleLive(spec, index = idx + 1, initialLastFalseEpochMs = prevReturnAt ?: start)
    }
    val ruleProbes = o.rules.map { spec -> Probe(this, d, listOf(spec.cond)) }
    if (ruleLives.isNotEmpty()) d.boost((o.watchMs ?: 10_000L) + 5_000L)
    var lastRulePkgCheckEl = 0L
    var beforeRan = false
    var stoppedBy: String? = null
    var stoppedAt: String? = null
    // A step whose expected result did not happen (an until that timed out, typing that did not land or could not be
    // checked) ends the stretch like a refused step: the later steps were written for a screen that is not there.
    var missed: String? = null
    var missedSystem: String? = null
    var missedExcerpts: List<String> = emptyList()
    var outcome = "done"
    var skipped = 0
    // 1.4.0-beta11 (rainbow): a `check` step stops the stretch and hands the screen to the agent; [resumeWith] is how
    // to go on from the step after it (`use NAME … from N` inside a segment, the remaining steps in a plain act).
    class Checkpoint(val label: String, val what: String, val resumeWith: String?)
    var checkpoint: Checkpoint? = null
    var errorData: org.json.JSONObject? = null
    // Where each running `use` is (its name, values and which of its own steps), innermost last; and the top-level step.
    class UseAt(val name: String, val params: List<String>, val size: Int) { var at = 0 }
    val useStack = ArrayList<UseAt>()
    var topAt = 0
    var topSteps: List<Step> = emptyList()
    var activeCleanTopBoundary = false
    var runningRule = false
    fun screenNowParts(): ScreenNowParts = runCatching {
        val sn = snapshot(d)
        buildScreenNowParts(sn.activity, sn.els)
    }.getOrDefault(ScreenNowParts("", "", emptyList()))
    fun recordMissed(displayPrefix: String, systemPrefix: String = displayPrefix, withScreen: Boolean = false, extraExcerpts: List<String> = emptyList()) {
        val sp = if (withScreen) screenNowParts() else null
        missed = displayPrefix + (sp?.displaySuffix.orEmpty())
        missedSystem = systemPrefix + (sp?.systemSuffix.orEmpty())
        missedExcerpts = (extraExcerpts + (sp?.excerpts.orEmpty())).filter { it.isNotEmpty() }
    }
    // Where the agent last tapped in this stretch: typing must go to a field there (BackgroundOperation.typeFact).
    var lastTap: Pair<Int, Int>? = null
    val ctxAct = ActCtx(sid)
    // 1.3.0-beta19: human pacing, asked for this stretch or kept for the app (note human on).
    if (o.human || prof["human"] == "true") { ctxAct.human = Human(); notes += tr(S.vscreen_act_human_pacing_is_on_a) }
    fun put(so: StepOut): StepOut { outs += so; lines += so.text; Call.current()?.report(so.text); return so }
    fun stepNumOf(label: String): Int = label.substringBefore('.').toIntOrNull() ?: 0
    fun disableRulesForAppSwitch(curPkg: String) {
        for (r in ruleLives) {
            if (!r.active) continue
            r.disable(tr(S.vscreen_act_front_app_changed_from_to, startPkg, curPkg))
            notes.add("RULE_OFF", tr(S.vscreen_act_rule_disabled, r.index, r.off))
        }
    }
    /** 2.0.0-beta5 (5.2, 5.6, 5.8 #2/#3): poll short-range `--on` rules and execute at most one firing per pass. */
    fun checkRules(duringStep: Int, contextKind: RuleFired.ContextKind, allowPreStepDebounce: Boolean = false): RuleTick {
        if (ruleLives.none { it.active }) return RuleTick(0L)
        val nowEl0 = SystemClock.elapsedRealtime()
        if (startPkg != null && nowEl0 - lastRulePkgCheckEl >= ActRules.PKG_CHECK_INTERVAL_MS) {
            lastRulePkgCheckEl = nowEl0
            val curPkg = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()
            if (curPkg != null && curPkg != startPkg) {
                disableRulesForAppSwitch(curPkg)
                return RuleTick(0L)
            }
        }
        for ((i, r) in ruleLives.withIndex()) {
            if (!r.active) continue
            val nowEl = SystemClock.elapsedRealtime()
            if (!r.cooldownReady(nowEl)) continue
            val probe = ruleProbes[i]
            val pollStartEpoch = System.currentTimeMillis()
            val hit = probe.poll()
            val obsId = if (r.needsDebounce) probe.lastObsId else null
            var shouldFire = r.recordObservation(obsId, hit != null, probe.lastObsEpochMs ?: pollStartEpoch)
            if (!shouldFire && allowPreStepDebounce && hit != null && r.needsDebounce && obsId != null && obsId > 0L && r.trailingTrueCount() == 1) {
                Thread.sleep(ActRules.PRE_STEP_DEBOUNCE_MS)
                checkInterrupts(d, Call.current())
                val poll2Epoch = System.currentTimeMillis()
                val hit2 = probe.poll()
                shouldFire = r.recordObservation(probe.lastObsId, hit2 != null, probe.lastObsEpochMs ?: poll2Epoch)
            }
            if (!shouldFire) continue

            // Unthrottled foreground check right before firing so we never act on a switched app.
            if (startPkg != null) {
                val pkgCheck = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()
                lastRulePkgCheckEl = SystemClock.elapsedRealtime()
                if (pkgCheck != null && pkgCheck != startPkg) {
                    disableRulesForAppSwitch(pkgCheck)
                    return RuleTick(0L)
                }
            }

            val ruleStartEl = SystemClock.elapsedRealtime()
            val fireEpochMs = System.currentTimeMillis()
            if (r.spec.step.kind == StepKind.SLEEP) {
                val sw = waitOn(d, null, r.spec.step.ms ?: 0L, stop)
                val endEpochMs = System.currentTimeMillis()
                claimedWindows += (r.lastFalseObsEpochMs + 1L).coerceAtMost(endEpochMs)..(endEpochMs + ActRules.CLAIMED_AFTER_MS)
                r.markFired(SystemClock.elapsedRealtime())
                r.lastFalseObsEpochMs = endEpochMs
                rulesFired += RuleFired(r.index, fireEpochMs, duringStep, r.spec.cond.raw, r.spec.step.raw, contextKind)
                r.off?.let { notes.add("RULE_OFF", tr(S.vscreen_act_rule_disabled, r.index, it)) }
                return RuleTick(SystemClock.elapsedRealtime() - ruleStartEl, stopped = sw.stopped)
            }

            val beforeSmall = d.latestFrame()?.let { VScreenPerception.small(it) }
            val settleProbe = Probe(this, d, listOf(Cond("stable", CondKind.STABLE)), afterAction = true)
            try {
                val ruleActEl = SystemClock.elapsedRealtime()
                runningRule = true
                try {
                    doAction(sid, d, r.spec.step, ctxAct, fromRule = true)
                } catch (e: StepRefused) {
                    if (e.code == "NOT_FOUND" || e.code == "AMBIGUOUS") {
                        r.disable(e.message ?: e.code)
                        notes.add(
                            "RULE_OFF",
                            tr(S.vscreen_act_rule_disabled, r.index, r.off),
                            screenExcerpts = e.screenExcerpts,
                            systemText = tr(S.vscreen_act_rule_disabled, r.index, e.systemMessage),
                        )
                        continue
                    }
                    throw e
                } finally {
                    runningRule = false
                }
                settleProbe.acted(r.spec.step.kind)
                val ruleActionEndEpochMs = System.currentTimeMillis()
                claimedWindows += (r.lastFalseObsEpochMs + 1L).coerceAtMost(ruleActionEndEpochMs)..(ruleActionEndEpochMs + ActRules.CLAIMED_AFTER_MS)
                val settleOut = waitOn(d, settleProbe, ActRules.SETTLE_MAX_MS, stop)
                d.awaitFrameAfter(SystemClock.elapsedRealtime() - 50L, 100L)
                d.latestWithVersion()?.let { (bmp, ver) ->
                    VScreenRecorder.offerFresh(d, bmp, ver, startPkg)
                }
                if (beforeSmall != null && settleOut.stopped == null) {
                    val maskNow = VScreenPerception.mask(d.displayId)
                    val visualSame = d.latestFrame()?.let { VScreenPerception.small(it) }?.let { afterSmall ->
                        try {
                            Change.same(beforeSmall, afterSmall, Change.Level.TINY, skip = maskNow?.rectsIn(afterSmall.width, afterSmall.height).orEmpty())
                        } finally {
                            afterSmall.recycle()
                        }
                    }
                    val a11yChanged = com.kilon.agentvscreen.host.VScreenAccessibilityService.contentChangedAt(d.displayId) > ruleActEl ||
                        com.kilon.agentvscreen.host.VScreenAccessibilityService.pageChangedAt(d.displayId) > ruleActEl
                    if (visualSame == true && !a11yChanged) {
                        r.disable(tr(S.vscreen_act_screen_did_not_change_after))
                    }
                }
                r.markFired(SystemClock.elapsedRealtime())
                r.lastFalseObsEpochMs = System.currentTimeMillis()
                rulesFired += RuleFired(r.index, fireEpochMs, duringStep, r.spec.cond.raw, r.spec.step.raw, contextKind)
                r.off?.let { notes.add("RULE_OFF", tr(S.vscreen_act_rule_disabled, r.index, it)) }
                return RuleTick(SystemClock.elapsedRealtime() - ruleStartEl, stopped = settleOut.stopped)
            } finally {
                settleProbe.close()
                beforeSmall?.recycle()
            }
        }
        return RuleTick(0L)
    }
    // 1.3.0-beta18: steps really done in this stretch (a repeat runs its steps again): a bound against endless loops.
    var ran = 0
    /** An if / repeat check: [conds] within [ms] on the screen as it is (not "since an action"). null = stopped. */
    fun check(conds: List<Cond>, ms: Long, label: String, raw: String): WaitOut? {
        val p = Probe(this, d, conds)
        val w = try {
            waitOn(d, p, ms, stop, ruleLives) { checkRules(stepNumOf(label), RuleFired.ContextKind.DURING_WAIT) }
                .let { w0 -> if (w0.met == null && w0.stopped == null) p.judgeFresh(3_000L)?.let { WaitOut(it, null, w0.ms, p.lastMetKind) } ?: w0 else w0 }
        } finally { p.close() }
        if (w.stopped != null) { stoppedBy = w.stopped; stoppedAt = label; put(StepOut(label, raw, "stopped", tr(S.vscreen_act_stop_condition_at_2, label, s1(w.ms), w.stopped))); return null }
        return w
    }
    fun runOne(step: Step, label: String, depth: Int, quick: Boolean, own: Boolean, nestedBlock: Boolean = false) {
        if (stoppedBy != null || missed != null || outcome != "done") { if (own) { skipped++; outs += StepOut(label, step.raw, "skipped", "") }; return }
        activeCleanTopBoundary = own && depth == 0 && !nestedBlock && useStack.isEmpty() && !runningRule
        if (own && ruleLives.isNotEmpty() && step.kind !in setOf(StepKind.USE, StepKind.CHECK, StepKind.TRY, StepKind.IF, StepKind.REPEAT)) {
            val rt = checkRules(stepNumOf(label), RuleFired.ContextKind.BEFORE_STEP, allowPreStepDebounce = true)
            if (rt.stopped != null) {
                stoppedBy = rt.stopped
                stoppedAt = label
                put(StepOut(label, step.raw, "stopped", tr(S.vscreen_act_not_done_stop_condition_already, label, step.raw, rt.stopped)))
                return
            }
        }
        if (step.kind == StepKind.USE) {
            // 1.3 示教: `use NAME a b` fills the segment's blanks {1} {2} … (a taught lesson's typed words) in order.
            var name = step.text!!
            var params = emptyList<String>()
            var seg0 = app?.let { VScreenMap.segment(it, name) }
            var toks = Steps.tokens(step.raw).drop(1)
            // 1.4.0-beta11: `use NAME values… from N` goes on from its N-th step (after a check stopped it).
            var fromN = 1
            if (toks.size >= 3 && toks[toks.size - 2].lowercase() in setOf("from", "从") && toks.last().toIntOrNull() != null) {
                fromN = toks.last().toInt().coerceAtLeast(1); toks = toks.dropLast(2)
                if (seg0 == null) { name = toks.joinToString(" "); seg0 = app?.let { VScreenMap.segment(it, name) } }
            }
            if (seg0 == null && toks.size > 1) app?.let { VScreenMap.segment(it, toks[0]) }?.let { seg0 = it; name = toks[0]; params = toks.drop(1) }
            val seg = seg0 ?: throw StepRefused("NOT_FOUND", tr(S.vscreen_act_no_segment_named_note_lists, name, app ?: "当前应用", app ?: "current app"))
            if (depth >= 3) throw StepRefused("INVALID_STEP", tr(S.vscreen_act_use_is_nested_too_deep))
            // 1.4.0-beta4: a lesson starts from the page it was shown on. Not there: do nothing and say what it looks like.
            // Going on after a check (from N > 1) is in the middle, not at the start.
            seg.start?.takeIf { fromN == 1 }?.let { st ->
                val shot = runCatching { com.kilon.agentvscreen.core.A11yTree.snapshot(d.displayId) }.getOrNull()
                val read = VScreenPerception.reading(d.displayId, 0L, 0)?.items.orEmpty()
                val top = runCatching { VScreenShell.topActivity(d.displayId) }.getOrNull()
                Teach.atStart(st, top?.substringBefore('/'), top, { w -> val pat = TextPat(w); shot?.shows(pat::hit)?.isNotEmpty() == true || read.any { pat.hit(it.text) } })
                    ?.let { why -> throw StepRefused("NOT_AT_START", tr(S.vscreen_act_starts_from_its_own_page, name, why)) }
            } ?: app?.let { a -> if (VScreenMap.oldLesson(a, seg)) notes += tr(S.vscreen_act_is_an_old_format_lesson, name) }
            val filled = Steps.split(seg.steps).map { Teach.fill(it, params) }
            val missing = filled.flatMap { it.second }.distinct().sorted()
            if (missing.isNotEmpty()) throw StepRefused("INVALID_STEP", tr(S.vscreen_act_segment_takes_values_missing_use, name, missing.max(), missing.joinToString(" ") { "{$it}" }, (1..missing.max()).joinToString(" ") { "<$it>" }))
            val inner = runCatching { filled.map { Steps.parse(it.first) } }.getOrElse { throw StepRefused("INVALID_STEP", tr(S.vscreen_act_the_steps_of_segment_can, name, it.message)) }
            put(StepOut(label, step.raw, "done", tr(S.vscreen_act_use, label, name, inner.size, com.kilon.agentvscreen.steps(inner.size))))
            if (fromN > inner.size) throw StepRefused("INVALID_STEP", tr(S.vscreen_act_segment_has_only_there_is, name, inner.size, fromN, com.kilon.agentvscreen.steps(inner.size)))
            app?.let { VScreenMap.markUsed(it, name) }
            val at = UseAt(name, params, inner.size)
            useStack += at
            // 1.4.0-beta11: where it really started from, for a segment that does not know its start yet (learned once it succeeds).
            val startSeen = if (fromN == 1 && seg.start == null && app != null && !VScreenMap.oldLesson(app, seg)) runCatching { Teach.startHere(d.displayId) }.getOrNull() else null
            try {
                inner.forEachIndexed { i, st -> if (i + 1 >= fromN) { at.at = i + 1; runOne(st, "$label.${i + 1}", depth + 1, quick, own, nestedBlock = true) } }
            } finally { useStack.remove(at) }
            // 1.4.0-beta13 (test B8: a segment run to its check and then on `from N` never learned its start): stopping at
            // its own check is the flow working, so the page it started from is its start as well.
            if (startSeen != null && missed == null && stoppedBy == null && (checkpoint != null || outcome == "done")) app?.let { VScreenMap.learnStart(it, name, startSeen) }
            return
        }
        if (step.kind == StepKind.CHECK) {
            step.until?.let { conds ->
                val w = check(conds, step.untilMs ?: Steps.CHECK_DEFAULT_MS, label, step.raw) ?: return
                if (w.met != null) { put(StepOut(label, step.raw, "done", tr(S.vscreen_act_check_no_need_to_stop, label, step.text, w.met))); return }
            }
            fun q(v: String) = if (v.any { it.isWhitespace() }) "'$v'" else v
            val u = useStack.lastOrNull()
            val resume = when {
                u != null && u.at < u.size -> "act \"use ${u.name}" + u.params.joinToString("") { " " + q(it) } + " from ${u.at + 1}\""
                u != null -> null
                topAt < topSteps.size -> "act \"" + topSteps.drop(topAt).joinToString("; ") { it.raw } + "\""
                else -> null
            }
            put(StepOut(label, step.raw, "check", tr(S.vscreen_act_checkpoint, label, step.text)).also { it.code = "CHECKPOINT" })
            checkpoint = Checkpoint(label, step.text!!, resume)
            outcome = "checkpoint"
            return
        }
        if (++ran > MAX_RUN) throw StepRefused("INVALID_STEP", tr(S.vscreen_act_this_stretch_has_already_done, MAX_RUN))
        if (step.kind == StepKind.TRY) {
            // Not found, not as expected, more than one place: skip what is inside and go on. Being interrupted is not skipped.
            val missed0 = missed; val missedSys0 = missedSystem; val missedEx0 = missedExcerpts; val skipped0 = skipped; val from = outs.size
            val openTry = put(StepOut(label, step.raw, "done", tr(S.vscreen_act_try_skipped_if_they_don, label, step.body.size, com.kilon.agentvscreen.steps(step.body.size))).also {
                it.recapLabel = "try"
            })
            var why: String? = null
            try { step.body.forEachIndexed { i, st -> runOne(st, "$label.${i + 1}", depth, quick, own, nestedBlock = true) } }
            catch (e: StepRefused) { if (e.code in INTERRUPTS) throw e; why = "${e.code}${tr(S.agent_guide_colon)}${e.message}" }
            catch (e: ActionError) { if (e.code in INTERRUPTS) throw e; why = "${e.code}${tr(S.agent_guide_colon)}${e.message}" }
            if (why == null && missed != null && missed0 == null) why = missed
            if (why != null) {
                missed = missed0; missedSystem = missedSys0; missedExcerpts = missedEx0; skipped = skipped0
                for (k in from until outs.size) if (outs[k].status == "missed" || outs[k].status == "skipped") outs[k].status = "tried"
                openTry.recapLabel = tr(S.vscreen_act_try_skipped)
                put(StepOut(label, step.raw, "done", tr(S.vscreen_act_try_did_not_work_skipped, label, why.take(160))).also {
                    it.recapLabel = ""
                })
            }
            return
        }
        if (step.kind == StepKind.IF) {
            val want = step.until!!.joinToString(" or ") { it.raw }
            val w = check(step.until, step.untilMs ?: Steps.CHECK_DEFAULT_MS, label, step.raw) ?: return
            val branch = if (w.met != null) step.body else step.alt
            val branchTag = when {
                w.met != null -> tr(S.vscreen_act_yes)
                step.alt.isEmpty() -> tr(S.vscreen_act_no)
                else -> "else"
            }
            put(StepOut(label, step.raw, "done", if (w.met != null) tr(S.vscreen_act_if_yes, label, want, w.met)
                else tr(S.vscreen_act_if_not_within, label, want, s1(w.ms)) + if (step.alt.isEmpty()) tr(S.vscreen_act_skipped) else tr(S.vscreen_act_doing_else)).also {
                it.recapLabel = "if $want → $branchTag"
            })
            branch.forEachIndexed { i, st -> runOne(st, "$label.${i + 1}", depth, quick, own, nestedBlock = true) }
            return
        }
        if (step.kind == StepKind.REPEAT) {
            val want = step.until?.joinToString(" or ") { it.raw }
            val openRepeat = put(StepOut(label, step.raw, "done", if (want == null) tr(S.vscreen_act_repeat_times, label, step.times) else tr(S.vscreen_act_repeat_until_at_most_times, label, want, step.times)).also {
                it.recapLabel = if (want == null) "repeat ×${step.times}" else "repeat until $want"
            })
            for (k in 1..step.times) {
                step.body.forEachIndexed { i, st -> runOne(st, "$label.$k.${i + 1}", depth, quick, own, nestedBlock = true) }
                if (stoppedBy != null || missed != null || outcome != "done") return
                val conds = step.until ?: continue
                val w = check(conds, step.untilMs ?: Steps.CHECK_DEFAULT_MS, label, step.raw) ?: return
                if (w.met != null) {
                    openRepeat.recapLabel = "repeat ×$k until $want"
                    put(StepOut(label, step.raw, "done", tr(S.vscreen_act_got_after_round, label, k, w.met)).also {
                        it.waitedFor = want; it.waitMs = w.ms; it.waitMet = true
                        it.metCondKind = w.metKind
                        it.isActionOrRepeatUntil = own
                        it.recapLabel = ""
                    })
                    return
                }
            }
            if (want != null) {
                val so = put(StepOut(label, step.raw, "missed", tr(S.vscreen_act_repeat_ran_times_never_came, label, step.times, want)).also {
                    it.code = "STEP_MISSED"; it.waitedFor = want; it.waitMet = false
                    it.recapLabel = "repeat ×${step.times} until $want"
                })
                recordMissed(
                    tr(S.vscreen_act_step_repeat_ran_times_and, label, step.times, want),
                    withScreen = true,
                )
                so.waitedFor = want
            }
            return
        }
        if (step.kind == StepKind.SLEEP) {
            val w = waitOn(d, null, step.ms ?: 0L, stop, ruleLives) { checkRules(stepNumOf(label), RuleFired.ContextKind.DURING_WAIT) }
            val so = StepOut(label, step.raw, "done", "").also { it.waitMs = w.ms }
            if (w.stopped != null) { stoppedBy = w.stopped; stoppedAt = label; so.status = "stopped"; so.text = tr(S.vscreen_act_wait_stop_condition_at, label, s1(step.ms ?: 0), s1(w.ms), w.stopped) }
            else so.text = tr(S.vscreen_act_waited, label, s1(w.ms))
            put(so); return
        }
        if (step.kind == StepKind.WAIT) {
            val p = Probe(this, d, step.until!!)
            val want = step.until.joinToString(" or ") { it.raw }
            try {
                val w = waitOn(d, p, step.untilMs ?: Steps.WAIT_DEFAULT_MS, stop, ruleLives) { checkRules(stepNumOf(label), RuleFired.ContextKind.DURING_WAIT) }
                val so = StepOut(label, step.raw, "done", "").also { it.waitedFor = want; it.waitMs = w.ms; it.waitMet = w.met != null }
                when {
                    w.stopped != null -> { stoppedBy = w.stopped; stoppedAt = label; so.status = "stopped"; so.text = tr(S.vscreen_act_wait_stop_condition_at, label, want, s1(w.ms), w.stopped) }
                    w.met != null -> so.text = tr(S.vscreen_act_got, label, w.met, s1(w.ms))
                    else -> {
                        val seenNow = p.now()
                        so.status = "missed"; so.code = p.timeoutCode()
                        so.text = tr(S.vscreen_act_wait_time_up_at_never, label, want, s1(w.ms), seenNow)
                        recordMissed(
                            tr(S.vscreen_act_step_never_came, label, step.raw, seenNow),
                            withScreen = true,
                        )
                    }
                }
                put(so)
            } finally { p.close() }
            return
        }
        // An action.
        captureBeforeIfNeeded()
        stop?.poll()?.let { stoppedBy = it; stoppedAt = label; put(StepOut(label, step.raw, "stopped", tr(S.vscreen_act_not_done_stop_condition_already, label, step.raw, it))); return }
        // 2.0.0-beta4 (4.3 & 4.7): closed-loop press/drag hold/seek checks its own condition while holding; no post-action until wait.
        if (step.loop != Loop.NONE) {
            appRule(d, step)
            com.kilon.agentvscreen.host.Risk.recordInputAction(Call.current()?.tokenId)
            val reachBefore = InputReach.probe(d.displayId)
            val out = BackgroundOperation.agentAction(sid, null, isRead = false, describe = com.kilon.agentvscreen.host.Audit.redact(listOf(step.raw)).take(40)) { dd ->
                closedLoop(sid, dd, step, ctxAct)
            }
            ctxAct.acted = true
            if (out.points.isNotEmpty()) BackgroundOperation.actionLog(sid, 1).lastOrNull()?.trackN?.let { VScreenRecorder.setPoints(d.displayId, it, out.points) }
            VScreenPerception.noteAction(d.displayId, out.ms)
            lastTap = if (step.kind == StepKind.PRESS) ctxAct.point else null
            noteViaChannel(notes, out.via)
            val want = step.until!!.joinToString(" or ") { it.raw }
            val so = StepOut(label, step.raw, if (out.met) "done" else "missed", "").also {
                it.fact = out.actionFact
                it.via = out.via
                it.alreadyAtStart = out.alreadyAtStart
                it.point = ctxAct.point.takeIf { step.kind == StepKind.PRESS }
                it.waitedFor = want
                it.waitMet = out.met
                it.waitMs = out.ms
                it.metCondKind = if (out.met) out.metKind else null
                it.isActionOrRepeatUntil = own
                it.gesture = out.record
                it.pollMsP50 = out.pollMsP50
                it.pollMsMax = out.pollMsMax
                it.imgAgeMs = out.imgAgeMs
            }
            if (out.met) {
                so.text = tr(S.vscreen_act_got_2, label, step.raw, out.actionFact, out.metFact, s1(out.ms))
                if (so.gesture?.afterRelease == Gesture.AFTER_SNAPPED_BACK) {
                    notes.add("SNAP_BACK", tr(S.vscreen_act_step_snapped_back_to_its, label, step.raw))
                }
            } else {
                so.code = if (out.notRead) "NOT_READ" else "STEP_MISSED"
                if (so.code == "STEP_MISSED" && VScreenAwake.screenOff) {
                    InputReach.invalidate(d.displayId)
                    val reprobed = InputReach.probe(d.displayId, force = true)
                    if (reachBefore != InputReach.Reach.BLOCKED && reprobed == InputReach.Reach.BLOCKED) {
                        val msg = inputBlockedMessage()
                        so.code = "INPUT_BLOCKED_SCREEN_OFF"
                        so.text = tr(S.vscreen_act_text, label, step.raw, out.actionFact, msg)
                        recordMissed(msg)
                        put(so)
                        return
                    }
                }
                val reasonSuffix = out.missReason?.let { tr(S.public_guide_text, it) } ?: ""
                so.text = tr(S.vscreen_act_wait_not_met_after, label, step.raw, out.actionFact, want, s1(out.ms), reasonSuffix)
                recordMissed(
                    tr(S.vscreen_act_step_was_done_but_never, label, step.raw, want, reasonSuffix),
                    withScreen = true,
                )
            }
            put(so)
            return
        }
        val conds = step.until ?: if (quick) null else listOf(Cond("stable", CondKind.STABLE))
        val probe = conds?.let { Probe(this, d, it, afterAction = true) }
        // The picture before the action, to say afterwards when the action changed nothing at all.
        // 1.4.0-beta14 (test K1/K2: "nothing changed" after ctrl+a and typed keys that did change the words): an
        // editing key changes a few letters, too small to read as a change of the picture; it gets no such note.
        val editKey = step.kind == StepKind.KEY && step.text.orEmpty().let { k -> '+' in k || k.length == 1 || k == "del" || k == "forward_del" || k == "space" }
        val before = if (step.until == null && !quick && step.kind != StepKind.TYPE && !editKey) d.latestFrame()?.let { VScreenPerception.small(it) } else null
        try {
            val reachBefore = InputReach.probe(d.displayId)
            val tAct = SystemClock.elapsedRealtime()
            val done1 = doAction(sid, d, step, ctxAct, lastTap)
            probe?.acted(step.kind, done1.typedField, done1.typedBounds, done1.typeVerified)
            val actMs = SystemClock.elapsedRealtime() - tAct
            val fact = done1.text + if (actMs >= 1_500L) tr(S.vscreen_act_the_step_itself_took, s1(actMs)) else ""
            // 1.4.0-beta14 (test K6: tap a web box; key ctrl+a; type → the box was not found and the words were lost):
            // an editing key keeps focus where the tap put it, so the tapped point still names the box typed into.
            lastTap = if (step.kind == StepKind.TAP || step.kind == StepKind.PRESS) ctxAct.point else if (step.kind == StepKind.TYPE || editKey) lastTap else null
            noteViaChannel(notes, done1.via)
            val so = StepOut(label, step.raw, "done", "").also {
                it.fact = fact
                it.via = done1.via
                it.readback = done1.typedField
                it.point = ctxAct.point.takeIf { step.kind == StepKind.TAP || step.kind == StepKind.PRESS }
                it.typeVerified = done1.typeVerified
                it.isActionOrRepeatUntil = own
                it.gesture = done1.gesture
            }
            val maxMs = step.untilMs ?: if (step.until != null) Steps.UNTIL_DEFAULT_MS else 3_000L
            if (done1.miss != null) {
                so.status = "missed"; so.code = done1.code ?: "STEP_MISSED"; so.text = "$label ${step.raw} → $fact"
                recordMissed(
                    displayPrefix = tr(S.vscreen_act_step, label, step.raw, done1.miss),
                    systemPrefix = tr(S.vscreen_act_step, label, step.raw, done1.missSystem ?: done1.miss),
                    withScreen = false,
                    extraExcerpts = done1.missExcerpts,
                )
                put(so); return
            }
            if (probe == null) { so.text = "$label ${step.raw} → $fact"; put(so); Thread.sleep(80); return }
            val tWait = SystemClock.elapsedRealtime()
            val w = waitOn(d, probe, maxMs, stop, ruleLives) { checkRules(stepNumOf(label), RuleFired.ContextKind.DURING_WAIT) }
            so.waitedFor = conds.joinToString(" or ") { it.raw }; so.waitMs = w.ms; so.waitMet = w.met != null; so.metCondKind = w.metKind
            var waitTxt = when {
                w.stopped != null -> { stoppedBy = w.stopped; stoppedAt = label; so.status = "stopped"; tr(S.vscreen_act_stop_condition_at, s1(w.ms), w.stopped) }
                w.met != null -> tr(S.vscreen_act_got_3, w.met, s1(w.ms))
                step.until == null -> tr(S.vscreen_act_waiting_for_the_screen_to, s1(w.ms))
                else -> {
                    val seenNow = probe.now()
                    so.status = "missed"; so.code = probe.timeoutCode()
                    if (so.code == "STEP_MISSED" && VScreenAwake.screenOff) {
                        InputReach.invalidate(d.displayId)
                        val reprobed = InputReach.probe(d.displayId, force = true)
                        if (reachBefore != InputReach.Reach.BLOCKED && reprobed == InputReach.Reach.BLOCKED) {
                            val msg = inputBlockedMessage()
                            so.code = "INPUT_BLOCKED_SCREEN_OFF"
                            recordMissed(msg)
                            msg
                        } else {
                            recordMissed(
                                tr(S.vscreen_act_step_was_done_but_never_2, label, step.raw, so.waitedFor, seenNow),
                                withScreen = true,
                            )
                            tr(S.vscreen_act_wait_time_up_at_never_2, so.waitedFor, s1(w.ms), seenNow)
                        }
                    } else {
                        recordMissed(
                            tr(S.vscreen_act_step_was_done_but_never_2, label, step.raw, so.waitedFor, seenNow),
                            withScreen = true,
                        )
                        tr(S.vscreen_act_wait_time_up_at_never_2, so.waitedFor, s1(w.ms), seenNow)
                    }
                }
            }
            // 1.2: an action after which not one sampled pixel differs is said: the usual cause is a tap that hit nothing.
            if (before != null && so.status == "done") {
                val mask = VScreenPerception.mask(d.displayId)
                fun sameAsBefore(): Boolean? = d.latestFrame()?.let { VScreenPerception.small(it) }?.let { after ->
                    try { Change.same(before, after, Change.Level.TINY, skip = mask?.rectsIn(after.width, after.height).orEmpty()) } finally { after.recycle() }
                }
                var same = sameAsBefore()
                // The settle wait stops waiting for a reaction after GRACE_MS, and a slow app reacts later: look a little
                // longer before saying nothing changed. A stop condition met meanwhile stops the stretch as in any wait.
                if (same == true) {
                    val call = Call.current()
                    var seen = d.version
                    val late = lookLate(Probe.LATE_MS, { SystemClock.elapsedRealtime() - tWait },
                        nextSame = { d.awaitNewFrame(seen, 100, urgent = true); seen = d.version; checkInterrupts(d, call); sameAsBefore() },
                        stop = { stop?.poll() },
                        settle = {
                            val p = Probe(this, d, listOf(Cond("stable", CondKind.STABLE)))
                            try { waitOn(d, p, 3_000L, stop, ruleLives) { checkRules(stepNumOf(label), RuleFired.ContextKind.DURING_WAIT) } } finally { p.close() }
                        })
                    same = late.same
                    late.waitText()?.let {
                        waitTxt = it; so.waitMs = late.endMs; so.waitMet = late.stopped == null && late.settle?.met != null
                        if (so.waitMet == true) so.metCondKind = late.settle?.metKind ?: CondKind.STABLE
                    }
                    late.stopped?.let { stoppedBy = it; stoppedAt = label; so.status = "stopped" }
                }
                if (same != null) so.changed = !same
                // 1.4.2-beta6 (test: a menu with moving lines behind it swallowed a tap during its entry animation, and the
                // reply said the tap was done and the screen settled): where the screen keeps moving by itself, "the picture
                // changed" proves nothing about a tap. When the tap point is inside an area that moves on its own (or most
                // of the screen does), the change is not counted as the tap's effect, and the reply says it is unproven.
                // A drag or swipe on a screen that mostly moves by itself is the same case (test: a drag that never
                // took was reported as done because the game kept animating).
                val tapped = so.point?.takeIf { step.kind == StepKind.TAP || step.kind == StepKind.PRESS }
                val dragged = step.kind == StepKind.DRAG || step.kind == StepKind.SWIPE
                if (same == false && so.status == "done" && mask != null &&
                    ((tapped != null && (mask.mostly || mask.covers(tapped.first, tapped.second))) || (dragged && mask.mostly))) {
                    so.changed = null
                    notes.add("UNPROVEN", tr(S.vscreen_act_step_acted_where_the_screen, label, step.raw))
                }
                // 2.0.0-beta8 (C6): SNAP_BACK is more specific than NO_CHANGE and takes precedence when a drag snapped back.
                val snappedBack = so.status == "done" && so.gesture?.afterRelease == Gesture.AFTER_SNAPPED_BACK
                if (!snappedBack && same == true && so.status == "done") {
                    notes.add("NO_CHANGE", tr(S.vscreen_act_after_step_nothing_on_the, label, step.raw))
                }
            }
            val snappedBackNow = so.gesture?.afterRelease == Gesture.AFTER_SNAPPED_BACK
            if (so.status == "done" && snappedBackNow) {
                notes.add("SNAP_BACK", tr(S.vscreen_act_step_snapped_back_to_its, label, step.raw))
            }
            so.text = tr(S.vscreen_act_text, label, step.raw, fact, waitTxt) +
                if (so.changed == false && !snappedBackNow) tr(S.vscreen_act_the_screen_did_not_change) else ""
            put(so)
        } finally { probe?.close(); before?.recycle() }
    }
    fun runList(list: List<Step>, prefix: String, quickAll: Boolean, lastWaits: Boolean, own: Boolean) {
        if (own) topSteps = list
        list.forEachIndexed { i, st ->
            if (own) topAt = i + 1
            val last = i == list.lastIndex
            runOne(st, "$prefix${i + 1}", 0, quick = quickAll && !(last && lastWaits), own = own)
        }
    }
    fun attachContinuationIfConfirm(code: String, baseData: org.json.JSONObject?): org.json.JSONObject? {
        if (code != "CONFIRM_PENDING" && code != "NEEDS_CONFIRM") return baseData
        val copy = baseData?.let { org.json.JSONObject(it.toString()) } ?: org.json.JSONObject()
        val priorDone = outs.count { it.status == "done" }
        val stepIndex = (topAt - 1).coerceAtLeast(0)
        val remainingTop = if (activeCleanTopBoundary && stepIndex in topSteps.indices) topSteps.drop(stepIndex) else emptyList()
        val safeResume = when {
            priorDone == 0 && !beforeRan && rulesFired.isEmpty() -> true
            activeCleanTopBoundary && !runningRule && remainingTop.isNotEmpty() -> true
            else -> false
        }
        val cont = org.json.JSONObject()
            .put("kind", "act")
            .put("session", sid)
            .put("prior_steps_done", priorDone)
            .put("safe_resume", safeResume)
            .put("add_bare", beforeRan)
            .put("display_generation", d.generation)
        if (safeResume) {
            val resumeList = if (priorDone == 0 && !beforeRan && rulesFired.isEmpty()) topSteps else remainingTop
            if (resumeList.isNotEmpty()) {
                cont.put("resume_steps", resumeList.joinToString("; ") { it.raw })
            }
        }
        runCatching { VScreenShell.topActivity(d.displayId) }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
            cont.put("activity", it)
        }
        copy.put("continuation", cont)
        return copy
    }
    fun fail(code: String, message: String, at: String, systemMessage: String = message, screenExcerpts: List<String> = emptyList()) {
        notes.add(code, message, screenExcerpts = screenExcerpts, systemText = systemMessage)
        outcome = if (code in INTERRUPTS) "interrupted" else "refused"
        // This reply shows the screen as it is now: that is the look the code asks for (CONTROL_CHANGED too, 1.4.2-beta6).
        if (code == "REREAD_REQUIRED" || code == "HOST_RESTARTED" || code == "CONTROL_CHANGED") BackgroundOperation.acknowledgeReread(sid)
        lines += tr(S.vscreen_act_not_done_from_here, code)
        outs.lastOrNull { it.status == "skipped" && it.n == at } ?: outs.add(StepOut(at, "", "refused", "").also { it.code = code })
    }
    val acting = steps.isNotEmpty() || o.watchMs != null || ruleLives.isNotEmpty()
    try {
        try {
            if (beforeSteps != null && acting) {
                runList(beforeSteps, tr(S.vscreen_act_before), quickAll = true, lastWaits = true, own = false)
                beforeRan = true
            }
            runList(steps, "", quickAll = o.fast, lastWaits = true, own = true)
            if (steps.isEmpty() && !o.skipLookSettle && !o.fast && stoppedBy == null && outcome == "done") {
                val p = Probe(this, d, listOf(Cond("stable", CondKind.STABLE)), look = true)
                try {
                    val w = waitOn(d, p, 3_000L, stop, ruleLives) { checkRules(0, RuleFired.ContextKind.WATCHING) }
                    lines += when {
                        w.stopped != null -> { stoppedBy = w.stopped; tr(S.vscreen_act_looked_stop_condition_at, s1(w.ms), w.stopped) }
                        w.met != null -> tr(S.vscreen_act_looked_screen_settled, s1(w.ms))
                        else -> tr(S.vscreen_act_looked_the_picture_kept_changing)
                    }
                } finally { p.close() }
            }
            val watch = o.watchMs ?: 0L
            if (watch > 0 && stoppedBy == null && missed == null && outcome == "done") {
                d.boost(watch + 1_000)
                val w = waitOn(d, null, watch, stop, ruleLives) { checkRules(0, RuleFired.ContextKind.WATCHING) }
                lines += if (w.stopped != null) { stoppedBy = w.stopped; tr(S.vscreen_act_watch_stop_condition_at, s1(watch), s1(w.ms), w.stopped) } else tr(S.vscreen_act_watched, s1(w.ms))
            }
        } catch (e: StepRefused) {
            errorData = attachContinuationIfConfirm(e.code, e.data) ?: errorData
            fail(
                e.code,
                e.message ?: e.code,
                (outs.count { it.status != "skipped" && !it.n.contains(' ') } + 1).toString(),
                systemMessage = e.systemMessage,
                screenExcerpts = e.screenExcerpts,
            )
        }
    } catch (e: ActionError) {
        errorData = attachContinuationIfConfirm(e.code, e.data) ?: errorData
        fail(e.code, e.message ?: e.code, (outs.count { it.status != "skipped" } + 1).toString())
        connectionLost(d)?.let { notes.add("CONNECTION_LOST", it) }
    } finally {
        stop?.close()
        ruleProbes.forEach { it.close() }
        if (afterSteps != null && acting && (beforeRan || beforeSteps == null) && connectionLost(d) == null && outcome != "interrupted") {
            // The after steps run even when the stretch stopped early (a game must not keep running while the caller thinks).
            val m = missed; val mSys = missedSystem; val mEx = missedExcerpts; val oc = outcome; val sb = stoppedBy
            missed = null; missedSystem = null; missedExcerpts = emptyList(); outcome = "done"; stoppedBy = null
            runCatching { runList(afterSteps, tr(S.vscreen_act_after), quickAll = true, lastWaits = true, own = false) }
                .onFailure { err ->
                    val sr = err as? StepRefused
                    notes.add(
                        (err as? ActionError)?.code ?: sr?.code ?: "OPERATION_FAILED",
                        tr(S.vscreen_act_the_after_steps_did_not, err.message),
                        screenExcerpts = sr?.screenExcerpts.orEmpty(),
                        systemText = tr(S.vscreen_act_the_after_steps_did_not, sr?.systemMessage ?: err.message),
                    )
                }
            missed?.let { am ->
                notes.add(
                    "STEP_MISSED",
                    tr(S.vscreen_act_the_after_steps_missed_their, am),
                    screenExcerpts = missedExcerpts,
                    systemText = tr(S.vscreen_act_the_after_steps_missed_their, missedSystem ?: am),
                )
            }
            missed = m; missedSystem = mSys; missedExcerpts = mEx; outcome = oc; stoppedBy = sb
        }
    }
    missed?.let { m ->
        outcome = "missed"
        val missCode = outs.lastOrNull { it.status == "missed" }?.code ?: "STEP_MISSED"
        notes.add(buildMissedNote(missCode, m, missedSystem ?: m, missedExcerpts, skipped))
        if (skipped > 0) lines += tr(S.vscreen_act_the_remaining_steps_were_not_2, skipped)
    }
    checkpoint?.let { c ->
        notes.add("CHECKPOINT", trPair("停在检查点 ${c.label}：${c.what}。先看【现在】的画面：没问题就接着跑" + (c.resumeWith?.let { "：$it" } ?: "（后面没有步骤了）") +
                "；不对就先用 act 改好，再接着跑。改的事每次都一样的话，把它写回段里（note save），下次就不用停。",
            "Stopped at checkpoint ${c.label}: ${c.what}. Look at [Now] first: if it is right, go on" + (c.resumeWith?.let { ": $it" } ?: " (no steps were left)") +
                "; if not, put it right with act first, then go on. If the fix is the same every time, write it into the segment (note save) so it need not stop next time."))
        if (skipped > 0) lines += tr(S.vscreen_act_the_steps_after_the_checkpoint, skipped)
        com.kilon.agentvscreen.host.Needs.checkpoint(context, sid, c.what, app)
    }
    if (outcome == "done" && stoppedBy != null) outcome = "stopped"
    if (setupMs >= 1_000L) lines += tr(S.vscreen_act_getting_ready_took_waking_the, s1(setupMs))
    if (o.stop.isNotEmpty()) lines += if (stoppedBy != null) tr(S.vscreen_act_stop_condition_fired, stoppedBy) else tr(S.vscreen_act_stop_condition_did_not_occur, o.stop.joinToString(" or ") { it.raw })
    if ((beforeSteps != null || afterSteps != null) && !acting) lines += tr(S.vscreen_act_this_app_has_before_after)
    mainCheckLines(main0, sid)?.let { notes.add("MAIN_SCREEN_CHANGED", it) }
    muteNote(d)?.let { notes += it }
    val top = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()
    if (app != null && top != null && top != app && top != context.packageName) notes += tr(S.vscreen_act_the_front_app_on_the, top, app)
    if (!beforeCaptured) captureBeforeIfNeeded()
    d.awaitFrameAfter(SystemClock.elapsedRealtime() - 50L, 100L)
    d.latestWithVersion()?.let { (bmp, ver) ->
        VScreenRecorder.offerFresh(d, bmp, ver, top ?: startPkg)
    }
    val afterStartMs = System.currentTimeMillis()
    return SegmentOut(
        lines = lines,
        steps = outs.filter { it.status != "refused" || it.code != null },
        notes = notes,
        actionMs = SystemClock.elapsedRealtime() - t0,
        start = start,
        outcome = outcome,
        stoppedAt = stoppedAt,
        startPkg = startPkg,
        firstActionMask = firstActionMask,
        beforeChanged = beforeChanged,
        afterStartMs = afterStartMs,
        excludeWindows = claimedWindows.toList(),
        rulesFired = rulesFired.toList(),
        errorData = errorData,
    )
}

/** Per stretch: where the last tap went, the focus before it, and when the session last acted (for the #N freshness check). */
internal class ActCtx(val sid: String) {
    /** 1.3.0-beta19: set = act like a person's hand ([Human]). */
    var human: Human? = null
    var point: Pair<Int, Int>? = null
    var focusBefore: String? = null
    var acted = false
}

/** A resolved target: the point, the words there, (for #N) whether the element had moved, and same-page elements. */
internal class Target(
    val x: Int,
    val y: Int,
    val label: String?,
    val moved: String? = null,
    val box: Rect? = null,
    /** What is at the point itself, for the payment / irreversible check (differs from [label] for a point beside words). */
    val atPoint: String? = label,
    /** True when resolved from an element (#N or text without coordinate offset), false for raw/offset points. */
    val isElement: Boolean = false,
    /** Elements from the exact live/valid page observation that resolved this target, for same-page payment checks. */
    val contextEls: List<El>? = null,
)

/**
 * 2.0.0-beta11 (test R14: `tap 1012 2688` on a checkout page pressed 立即支付 with no confirmation, `tap 自己付款` asked):
 * what is at a point AND the full same-page element list from that observation, for the reply and payment check.
 * When [acted] is true (a prior step already ran in this stretch) or the page changed since `lastElements`,
 * `lastElements` is skipped and the current screen is read live so button and payment context always come from
 * the exact same page.
 */
internal fun VScreenOffloadHandler.wordsAtWithContext(
    sid: String,
    d: VScreenDisplay,
    x: Int,
    y: Int,
    acted: Boolean = false,
): Pair<String?, List<El>?> {
    if (!acted) {
        val set = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }
        val current = set?.takeIf {
            it.generation == d.generation &&
                !pageChangedSince(d, it, runCatching { com.kilon.agentvscreen.core.VScreenShell.topActivity(d.displayId) }.getOrNull())
        }
        if (current != null) return labelAt(current.list, x, y) to current.list
    }
    val quick = runCatching { mergeElements(d, a11yRaw(d), null).filter { !it.placeholder } }.getOrNull()
    if (quick != null) {
        labelAt(quick, x, y)?.let { return it to quick }
    }
    val full = runCatching { snapshot(d).els.filter { !it.placeholder } }.getOrNull()
    if (full != null) return labelAt(full, x, y) to full
    return null to quick
}

internal fun VScreenOffloadHandler.wordsAt(sid: String, d: VScreenDisplay, x: Int, y: Int): String? =
    wordsAtWithContext(sid, d, x, y, acted = false).first

/** Read a fresh same-page element list when a target did not already carry one. */
internal fun VScreenOffloadHandler.liveContextElements(sid: String, d: VScreenDisplay, acted: Boolean): List<El> {
    if (!acted) {
        val set = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }
        val current = set?.takeIf {
            it.generation == d.generation &&
                !pageChangedSince(d, it, runCatching { com.kilon.agentvscreen.core.VScreenShell.topActivity(d.displayId) }.getOrNull())
        }
        if (current != null) return current.list
    }
    val quick = runCatching { mergeElements(d, a11yRaw(d), null).filter { !it.placeholder } }.getOrDefault(emptyList())
    if (quick.isNotEmpty()) return quick
    return runCatching { snapshot(d).els.filter { !it.placeholder } }.getOrDefault(emptyList())
}

/** The words of the smallest element with words under (x, y), or of one within a finger's width of it. */
internal fun labelAt(list: List<El>, x: Int, y: Int): String? {
    val worded = list.filter { it.label.isNotBlank() }
    worded.filter { it.box.contains(x, y) }.minByOrNull { it.box.width().toLong() * it.box.height() }?.let { return it.label }
    return worded.filter { Rect(it.box).apply { inset(-24, -24) }.contains(x, y) }
        .minByOrNull { Math.hypot((it.box.centerX() - x).toDouble(), (it.box.centerY() - y).toDouble()) }?.label
}

/**
 * 1.3.0-beta18: the places on the screen that [text] names, in reading order (top to bottom, then left to right), the
 * same element read twice (a node and the OCR words on it) once. Without `*`: the same words, else words that hold
 * them. With `*` (a pattern over the whole text): input boxes only when nothing else matches, since a pattern names
 * something shown (the box typed into holds the same words). A taught lesson picks its @N by this same rule.
 */
internal fun wordHits(els: List<El>, text: String, hints: Boolean = false): List<El> {
    val pat = TextPat(text)
    val exact = els.filter { (hints || !it.placeholder) && pat.exact(it.text) }
    val all0 = if (pat.wild) exact else exact.ifEmpty { els.filter { (hints || !it.placeholder) && pat.hit(it.text) } }
    val all = if (pat.wild) all0.filter { !it.editable }.ifEmpty { all0 } else all0
    // 1.4.0-beta6 (test: two listed 搜索, the word and the search box under it, @2 NOT_FOUND): @N counts the places as
    // the list shows them; the list already merged a node with the OCR words on it, so nothing more is folded here.
    val one = if (hints) all else ArrayList<El>().also { out -> all.forEach { h -> if (out.none { near(it, h) }) out += h } }
    return readingOrder(one) { it.box }
}

/** Top to bottom; things whose middles are within half the smaller height of each other are one row, left to right. */
internal fun <T> readingOrder(list: List<T>, box: (T) -> Rect): List<T> {
    val byY = list.sortedBy { box(it).centerY() }
    val rows = ArrayList<MutableList<T>>()
    for (e in byY) {
        val r = rows.lastOrNull()
        val first = r?.firstOrNull()
        if (first != null && Math.abs(box(e).centerY() - box(first).centerY()) <= minOf(box(e).height(), box(first).height()) / 2) r.add(e) else rows += mutableListOf(e)
    }
    // 1.4.0-beta8 (test: 搜索@1 was the wide search box whose left edge is further left): left to right by the middle, as the list is.
    return rows.flatMap { row -> row.sortedBy { box(it).centerX() } }
}

internal fun near(a: El, b: El): Boolean = Rect(a.box).apply { inset(-12, -12) }.contains(b.box.centerX(), b.box.centerY()) || Rect(b.box).apply { inset(-12, -12) }.contains(a.box.centerX(), a.box.centerY())

/**
 * Where a step goes. 1.2:
 *  - `#N` is checked against the screen as it is NOW when the list is not fresh (older than 3 s, or this stretch already
 *    acted): the element must still be there (same words, same place). Moved on the same page → the new place is used
 *    and said. Gone → STALE_ELEMENT, nothing is tapped. Before 1.2 `tap #5` tapped wherever #5 used to be.
 *  - words that match several different places are not guessed (before: "tapped the first"): AMBIGUOUS, with every
 *    place and its point. One tappable match among plain-text matches is not ambiguous: that one is meant.
 */
internal fun VScreenOffloadHandler.target(sid: String, d: VScreenDisplay, t: StepTarget, ctx: ActCtx): Target = when (t) {
    is StepTarget.At -> {
        if (t.x !in 0 until d.width || t.y !in 0 until d.height)
            throw StepRefused("OUT_OF_BOUNDS", tr(S.vscreen_act_is_outside_the_x_screen, t.x, t.y, d.width, d.height))
        // 1.3 beta8: the words at a point come from the list of the page that is on the screen now; a list from an
        // earlier page named what used to be there ("which is …" from the previous page).
        val (lbl, ctxEls) = wordsAtWithContext(sid, d, t.x, t.y, acted = ctx.acted)
        Target(t.x, t.y, lbl, contextEls = ctxEls)
    }
    is StepTarget.Ref -> {
        val set = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }
            ?: throw StepRefused("NOT_FOUND", tr(S.vscreen_act_no_element_list_yet_look))
        if (set.generation != d.generation) throw StepRefused("STALE_ELEMENT", tr(S.vscreen_act_is_from_the_previous_screen, t.n))
        val e = set.list.firstOrNull { it.n == t.n } ?: run {
            // A number from an earlier list: its element left the screen (numbers are never reused, see numbered()).
            val was = Numbers.label("$sid@${d.generation}", t.n)
            if (was != null) {
                val qw = ScreenText.quote(was, maxLen = 24)
                throw StepRefused(
                    code = "STALE_ELEMENT",
                    message = tr(S.vscreen_act_is_a_number_from_an, t.n, qw),
                    systemMessage = tr(S.vscreen_act_is_a_number_from_an_2, t.n),
                    screenExcerpts = listOf(was),
                )
            }
            throw StepRefused("NOT_FOUND", tr(S.vscreen_act_there_is_no_the_latest, t.n, set.list.size))
        }
        val topAct = runCatching { com.kilon.agentvscreen.core.VScreenShell.topActivity(d.displayId) }.getOrNull()
        val fresh = !ctx.acted && System.currentTimeMillis() - set.atMs <= 3_000L && !pageChangedSince(d, set, topAct)
        if (fresh) Target(e.box.centerX(), e.box.centerY(), e.label, box = e.box, isElement = true, contextEls = set.list)
        else {
            val sn = snapshot(d)
            val now = sn.els
            val same = now.filter { it.key == e.key }
            // 1.3: another page (new window, other activity, or too little in common) → the number is stale, even if
            // the same words are on the new page (its title, say). A move counts only with the whole list (a scroll).
            val page = !pageChangedSince(d, set, sn.activity) && samePage(set.list, now)
            val shift = if (page) commonShift(set.list, now) else null
            // 1.3.0-beta18: on the same page that did not scroll, an element of the same kind with the same box in the
            // same place IS that element, whatever it says now: a search box (editable or only tappable) whose hint
            // rotates, a counter, a clock. beta17 allowed this for input boxes only, so a tappable search box on a
            // browser's home page whose words rotate still gave STALE_ELEMENT.
            val still = shift == null || (Math.abs(shift.first) <= 12 && Math.abs(shift.second) <= 12)
            fun sameBox(a: El) = a.kind == e.kind && a.clickable == e.clickable && a.editable == e.editable &&
                Math.abs(a.box.centerX() - e.box.centerX()) <= 12 && Math.abs(a.box.centerY() - e.box.centerY()) <= 12 &&
                Math.abs(a.box.width() - e.box.width()) <= maxOf(16, e.box.width() / 5) && Math.abs(a.box.height() - e.box.height()) <= maxOf(12, e.box.height() / 5)
            val here = same.firstOrNull { near(it, e) }
                ?: if (page && (still || e.editable)) now.filter { sameBox(it) }.singleOrNull() else null
            fun withList(m: El) = shift != null && shift.third >= 2 && Math.abs(m.box.centerX() - e.box.centerX() - shift.first) <= 24 && Math.abs(m.box.centerY() - e.box.centerY() - shift.second) <= 24
            val ql = ScreenText.quote(e.label, maxLen = 24)
            val exList = if (e.label.isNotBlank()) listOf(e.label) else emptyList()
            when {
                !page -> throw StepRefused(
                    code = "STALE_ELEMENT",
                    message = tr(S.vscreen_act_is_a_number_from_the, t.n, ql),
                    systemMessage = tr(S.vscreen_act_is_a_number_from_the_2, t.n),
                    screenExcerpts = exList,
                )
                here != null -> Target(here.box.centerX(), here.box.centerY(), here.label, box = here.box, isElement = true, contextEls = now)
                // Wordless elements (icons) cannot be told apart by what they say: only the same place counts.
                same.size == 1 && !e.placeholder && withList(same[0]) -> same[0].let { Target(it.box.centerX(), it.box.centerY(), it.label, tr(S.vscreen_act_it_moved_here_from, e.box.centerX(), e.box.centerY()), box = it.box, isElement = true, contextEls = now) }
                else -> throw StepRefused(
                    code = "STALE_ELEMENT",
                    message = trPair("#${t.n} $ql 已经不在 (${e.box.centerX()},${e.box.centerY()}) 了（页面变了" + (if (same.size > 1) "，现在有 ${same.size} 处一样的字" else "") + "），没点。看【现在】的新列表再用编号",
                        "#${t.n} $ql is no longer at (${e.box.centerX()},${e.box.centerY()}) (the page changed" + (if (same.size > 1) "; the same text is now in ${same.size} places" else "") + "); nothing was tapped. Use the numbers of the new list in [Now]"),
                    systemMessage = trPair("#${t.n} 已经不在 (${e.box.centerX()},${e.box.centerY()}) 了（页面变了" + (if (same.size > 1) "，现在有 ${same.size} 处一样的字" else "") + "），没点。看【现在】的新列表再用编号",
                        "#${t.n} is no longer at (${e.box.centerX()},${e.box.centerY()}) (the page changed" + (if (same.size > 1) "; the same text is now in ${same.size} places" else "") + "); nothing was tapped. Use the numbers of the new list in [Now]"),
                    screenExcerpts = exList,
                )
            }
        }
    }
    is StepTarget.Words -> run {
        // 1.4.0-beta5: with an offset the point is beside the words, not on them (no box: it is a point tap).
        fun hit(e: El, sourceEls: List<El>): Target = if (t.dx == 0 && t.dy == 0) {
            Target(e.box.centerX(), e.box.centerY(), e.label, box = e.box, isElement = true, contextEls = sourceEls)
        } else {
            // 1.4.0-beta8 (test: `tap 蓝牙 +60%,+0%` tapped off the screen and only said "nothing changed"): a point
            // beside words that falls off the screen is refused, as a point off the screen is.
            val x = e.box.centerX() + t.dx; val y = e.box.centerY() + t.dy
            val ql = ScreenText.quote(e.label.orEmpty(), maxLen = 24)
            if (x !in 0 until d.width || y !in 0 until d.height) {
                throw StepRefused(
                    code = "OUT_OF_BOUNDS",
                    message = tr(S.vscreen_act_is_at_with_the_offset, ql, e.box.centerX(), e.box.centerY(), x, y, d.width, d.height),
                    systemMessage = tr(S.vscreen_act_target_element_is_at_with, e.box.centerX(), e.box.centerY(), x, y, d.width, d.height),
                    screenExcerpts = if (e.label.isNotBlank()) listOf(e.label) else emptyList(),
                )
            }
            val (atLbl, atEls) = wordsAtWithContext(sid, d, x, y, acted = ctx.acted)
            Target(x, y, tr(S.vscreen_act_beside, ql), atPoint = atLbl, isElement = false, contextEls = atEls ?: sourceEls)
        }
        fun choose(hits: List<El>): El? = if (t.nth != 0) hits.getOrNull(if (t.nth > 0) t.nth - 1 else hits.size + t.nth)
            else hits.singleOrNull() ?: hits.filter { it.clickable || it.editable }.singleOrNull()
        // 1.3.0-beta19 (test N, a pattern tap took 10.9 s): the app's own elements answer first. A full look waits for
        // a fresh frame and OCRs it, and reads it again while the page is still changing (a result list updating as
        // you type): seconds. OCR is only needed when the tree does not settle it (no match there, or not one place).
        // 1.4.0-beta5 (test: 搜索@2 NOT_FOUND, @-1 the first place, *搜索*@2 the third): @N counts in the list the agent
        // was shown. It lists a search box's hint words ("搜索", tagged as a hint) but matching skipped hints, so the
        // places the agent counted were not the places counted here. With @N every listed place counts, hints too,
        // and only the full list is used (the tree alone may lack an OCR-only place).
        if (t.nth == 0) {
            val quick = runCatching { mergeElements(d, a11yRaw(d), null) }.getOrDefault(emptyList()).filter { !it.placeholder }
            val fast = wordHits(quick, t.text).takeIf { it.isNotEmpty() }?.let { choose(it) }
            if (fast != null) return@run hit(fast, quick)
        }
        val els = snapshot(d).els.let { all -> if (t.nth != 0) all else all.filter { !it.placeholder } }
        val hits = wordHits(els, t.text, hints = t.nth != 0)
        if (hits.isEmpty()) throw buildNotFoundRefusal(t.text, els)
        val pick = if (t.nth != 0) choose(hits)
            ?: throw StepRefused("NOT_FOUND", tr(S.vscreen_act_is_on_the_screen_in, t.text, hits.size, t.nth))
        else hits.singleOrNull() ?: hits.filter { it.clickable || it.editable }.singleOrNull()
            ?: throw buildAmbiguousRefusal(t.text, hits)
        hit(pick, els)
    }
}

internal data class NearMissParts(
    val displaySuffix: String,
    val systemSuffix: String,
    val excerpts: List<String>,
)

/**
 * 1.1: what IS on the screen when a word target is missing: the closest words (shared characters) and the input
 * fields, each with its point, separated into display text, strictly platform-authored system text, and untrusted excerpts.
 */
internal fun nearMissParts(els: List<El>, want: String): NearMissParts {
    val q = VScreenOcr.norm(want)
    if (q.isEmpty()) return NearMissParts("", "", emptyList())
    // Shared characters, relative to the shorter text; at least two shared (one shared character is chance).
    fun score(l: String): Double {
        val n = VScreenOcr.norm(l); if (n.isEmpty()) return 0.0
        val common = q.toSet().intersect(n.toSet()).size
        if (common < minOf(2, q.toSet().size)) return 0.0
        return common.toDouble() / minOf(q.toSet().size, n.toSet().size)
    }
    val closeEls = els.filter { !it.placeholder }.map { it to score(it.text) }.filter { it.second >= 0.5 }
        .sortedByDescending { it.second }.take(3).map { it.first }
    val fieldEls = els.filter { it.editable }.take(3)
    val close = closeEls.map { "${ScreenText.quote(it.text, maxLen = 24)}(${it.box.centerX()},${it.box.centerY()})" }
    val fields = fieldEls.map { "${ScreenText.quote(it.label, maxLen = 20)}(${it.box.centerX()},${it.box.centerY()})" }
    val excerpts = buildList {
        closeEls.forEach { e -> ScreenText.clean(e.text, maxLen = 40).takeIf { it.isNotEmpty() }?.let { add(it) } }
        fieldEls.forEach { e -> ScreenText.clean(e.label, maxLen = 40).takeIf { it.isNotEmpty() }?.let { add(it) } }
    }.distinct()
    val parts = buildList {
        if (close.isNotEmpty()) add(tr(S.vscreen_act_similar_text) + close.joinToString(tr(S.automation_list_sep)))
        if (fields.isNotEmpty()) add(tr(S.vscreen_act_input_boxes) + fields.joinToString(tr(S.automation_list_sep)))
    }
    if (parts.isEmpty()) {
        val none = tr(S.vscreen_act_no_similar_text_on_the)
        return NearMissParts(none, none, emptyList())
    }
    val display = tr(S.command_spec_text) + parts.joinToString(tr(S.agent_guide_sep)) + tr(S.vscreen_act_where_the_text_changes_rotating)
    val sys = tr(S.vscreen_act_found_similar_screen_text_or)
    return NearMissParts(display, sys, excerpts)
}

internal fun nearMiss(els: List<El>, want: String): String = nearMissParts(els, want).displaySuffix

internal fun buildNotFoundRefusal(want: String, els: List<El>): StepRefused {
    val nm = nearMissParts(els, want.replace("*", ""))
    val base = tr(S.vscreen_act_was_not_found_on_the, want)
    return StepRefused(
        code = "NOT_FOUND",
        message = base + nm.displaySuffix,
        systemMessage = base + nm.systemSuffix,
        screenExcerpts = nm.excerpts,
    )
}

internal fun buildAmbiguousRefusal(want: String, hits: List<El>): StepRefused {
    val shown = hits.take(8)
    val excerpts = shown.map { ScreenText.clean(it.text, maxLen = 60) }.filter { it.isNotEmpty() }
    val display = tr(S.vscreen_act_is_on_the_screen_in_2, want, hits.size) +
        shown.withIndex().joinToString(tr(S.agent_guide_sep)) { (i, h) ->
            "@${i + 1} ${ScreenText.quote(h.text, maxLen = 24)} (${h.box.centerX()},${h.box.centerY()})" +
                h.flags.let { f -> if (f.isNotEmpty()) " $f" else "" }
        } +
        tr(S.vscreen_act_write_n_for_the_n, want)
    val sys = tr(S.vscreen_act_is_on_the_screen_in_3, want, hits.size)
    return StepRefused(
        code = "AMBIGUOUS",
        message = display,
        systemMessage = sys,
        screenExcerpts = excerpts,
    )
}

/**
 * Pure same-page payment/irreversible classifier: evaluates BOTH `besideInput` and `screenText`
 * from the exact [currentPageEls] observation that resolved the target.
 */
internal fun evaluatePayGuardContext(
    ctx: android.content.Context,
    pkg: String,
    targetLabel: String,
    targetY: Int,
    currentPageEls: List<El>,
): String? {
    // A button on the row of an input box (its "clear" ×) acts on the box, not on the user's data.
    val besideInput = currentPageEls.any { it.editable && targetY in it.box.top..it.box.bottom }
    val screenText = currentPageEls.joinToString(" ") { it.text }
    return consentClass(ctx, pkg, targetLabel, besideInput, screenText)
}

/**
 * 1.2: a press on a money / irreversible button is confirmed by the user on the phone (Consent), not by a flag the
 * agent adds itself. The button's words and the app are shown to the user, using same-page context elements.
 */
internal fun VScreenOffloadHandler.payGuard(
    sid: String,
    d: VScreenDisplay,
    label: String?,
    x: Int,
    y: Int,
    fromRule: Boolean = false,
    contextEls: List<El>? = null,
    acted: Boolean = false,
) {
    val els = contextEls ?: liveContextElements(sid, d, acted)
    payGuardCheck(sid, d.displayId, label, x, y, fromRule, contextEls = els)
}

internal fun VScreenOffloadHandler.payGuardCheck(
    sid: String,
    displayId: Int,
    label: String?,
    x: Int,
    y: Int,
    fromRule: Boolean = false,
    contextEls: List<El>? = null,
    pkgOverride: String? = null,
) {
    val l = label ?: return
    val safeL = ScreenText.clean(l, maxLen = 30)
    val els = contextEls ?: synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid]?.list }.orEmpty()
    val pkg = pkgOverride ?: (runCatching { VScreenShell.topPackage(displayId) }.getOrNull() ?: "?")
    val kind = evaluatePayGuardContext(context, pkg, l, y, els) ?: return
    val app = ScreenText.clean(VScreenKnowledge.label(pkg) ?: pkg, maxLen = 40)
    if (fromRule) {
        throw StepRefused(
            code = "CONFIRM_PENDING",
            message = tr(S.vscreen_act_rules_cannot_auto_tap_payment, ScreenText.quote(safeL, maxLen = 20), app),
            systemMessage = tr(S.vscreen_act_rules_cannot_auto_tap_payment_2, app),
            screenExcerpts = if (safeL.isNotEmpty()) listOf(safeL) else emptyList(),
        )
    }
    try {
        val what = when (kind) {
            com.kilon.agentvscreen.host.Consent.MONEY -> tr(S.vscreen_act_tap_the_payment_button, ScreenText.quote(safeL, maxLen = 20))
            com.kilon.agentvscreen.host.Consent.DESTROY -> tr(S.vscreen_act_tap_irreversible, ScreenText.quote(safeL, maxLen = 20))
            else -> tr(S.vscreen_act_tap_sensitive, ScreenText.quote(safeL, maxLen = 20))
        }
        com.kilon.agentvscreen.host.Consent.require(
            context,
            "commit:$pkg:${VScreenOcr.norm(l).take(40)}",
            what,
            tr(S.vscreen_act_tap_at_in, app, x, y, ScreenText.quote(safeL, maxLen = 30)),
            kind = kind,
            pkg = pkg,
            screenLabel = safeL,
        )
    } catch (e: ActionError) { throw StepRefused(e.code, e.message ?: e.code, e.data) }
}

/**
 * 2.0.0-beta8 (feedback 4): the class of a tap. The button's own words first ([VScreenGame.consentKind]); then one
 * generic signal for system dialogs whose button only says "OK": the confirm button of the phone's own uninstaller
 * (whatever its package on this ROM: the app that handles ACTION_DELETE) or of the system settings asking to clear an
 * app's data is a step that cannot be undone. No app or brand is named.
 */
internal fun consentClass(ctx: android.content.Context, pkg: String, label: String, besideInput: Boolean, screenText: String): String? {
    VScreenGame.consentKind(label, besideInput)?.let { return it }
    if (!CONFIRM_WORD.matches(label.trim())) return null
    if (VScreenGame.REAL_MONEY.containsMatchIn(screenText) && PAYMENT_CONTEXT.containsMatchIn(screenText)) {
        return com.kilon.agentvscreen.host.Consent.MONEY
    }
    val sys = SystemDialogs.of(ctx)
    return when {
        pkg in sys.uninstallers && UNINSTALL_TEXT.containsMatchIn(screenText) -> com.kilon.agentvscreen.host.Consent.DESTROY
        pkg in sys.settings && CLEAR_DATA_TEXT.containsMatchIn(screenText) -> com.kilon.agentvscreen.host.Consent.DESTROY
        else -> null
    }
}

private val CONFIRM_WORD = Regex("^(确定|确认|好|好的|是|继续|卸载|删除|清除|ok|yes|continue|uninstall|delete|clear)$", RegexOption.IGNORE_CASE)
private val PAYMENT_CONTEXT = Regex("付款方式|支付方式|账户余额|零钱|银行卡|信用卡|储蓄卡|花呗|白条|免密|刷脸支付|指纹支付|实付款|应付金额|payment method|pay with|wallet balance|bank card|credit card|debit card|autopay|checkout|order total", RegexOption.IGNORE_CASE)
private val UNINSTALL_TEXT = Regex("卸载|uninstall", RegexOption.IGNORE_CASE)
private val CLEAR_DATA_TEXT = Regex("清除数据|清除存储|清除应用数据|删除应用数据|清空数据|删除所有数据|clear data|clear storage|delete app data|all.*data.*deleted", RegexOption.IGNORE_CASE)

/** The packages of this phone's uninstaller and settings, resolved from the system (cached). */
internal class SystemDialogs(val uninstallers: Set<String>, val settings: Set<String>) {
    companion object {
        @Volatile private var cached: SystemDialogs? = null
        fun of(ctx: android.content.Context): SystemDialogs = cached ?: runCatching {
            val pm = ctx.packageManager
            fun handlers(i: android.content.Intent) = pm.queryIntentActivities(i, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
            val del = handlers(android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:${ctx.packageName}"))) +
                handlers(android.content.Intent(android.content.Intent.ACTION_UNINSTALL_PACKAGE, android.net.Uri.parse("package:${ctx.packageName}")))
            val set = handlers(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) +
                handlers(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")))
            SystemDialogs(del, set)
        }.getOrDefault(SystemDialogs(emptySet(), emptySet())).also { cached = it }
    }
}

/** The user's per-app rule for the app on the screen. */
internal fun VScreenOffloadHandler.appRule(d: VScreenDisplay, step: Step, fromRule: Boolean = false) {
    val pkg = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull() ?: return
    appRuleForPkg(pkg, step, fromRule)
}

internal fun VScreenOffloadHandler.appRuleForPkg(pkg: String, step: Step, fromRule: Boolean = false) {
    val act = when {
        step.kind == StepKind.KEY && step.text == "back" -> com.kilon.agentvscreen.core.Guard.Act.BACK
        step.kind == StepKind.SCROLL || step.kind == StepKind.KEY -> com.kilon.agentvscreen.core.Guard.Act.SCROLL
        else -> com.kilon.agentvscreen.core.Guard.Act.INPUT
    }
    when (val v = com.kilon.agentvscreen.core.Guard.check(act, pkg)) {
        is com.kilon.agentvscreen.core.Guard.Verdict.Deny -> throw StepRefused(v.code, v.text)
        is com.kilon.agentvscreen.core.Guard.Verdict.Ask -> {
            val app = ScreenText.clean(VScreenKnowledge.label(pkg) ?: pkg, maxLen = 40)
            if (fromRule) {
                throw StepRefused(
                    "CONFIRM_PENDING",
                    tr(S.vscreen_act_rules_cannot_auto_run_in, app, step.raw.take(40)),
                )
            }
            try { com.kilon.agentvscreen.host.Consent.require(context, v.key, tr(S.vscreen_act_act_in, app), tr(S.vscreen_act_this_stretch_starts_with, step.raw.take(40)), kind = com.kilon.agentvscreen.host.Consent.APP, pkg = pkg) }
            catch (e: ActionError) { throw StepRefused(e.code, e.message ?: e.code, e.data) }
        }
        else -> {}
    }
}

/** Null while the privileged connection and the screen are alive; otherwise what the agent must know and do. */
internal fun connectionLost(d: VScreenDisplay): String? {
    val st = BackgroundOperation.state.value
    if (!d.released && st.connection == BackgroundOperation.Connection.READY) return null
    return tr(S.vscreen_act_the_background_connection_broke_the, st.connectionError ?: st.connection.name) +
        tr(S.vscreen_act_tell_the_user_once_shizuku)
}

/** Bilingual message for `INPUT_BLOCKED_SCREEN_OFF`. */
internal fun inputBlockedMessage(extra: String? = null): String {
    val base = tr(S.vscreen_act_while_the_phone_screen_is)
    return if (extra.isNullOrBlank()) base else "$base${tr(S.public_guide_text, extra)}"
}

/** Execute `WAKE_THEN_TOUCH` or throw `StepRefused("INPUT_BLOCKED_SCREEN_OFF")` on `REFUSE` or wake failure. */
internal fun executeWakeOrRefuse(d: VScreenDisplay, route: InputRoute.Route): String? = when (route) {
    InputRoute.Route.TOUCH -> null
    InputRoute.Route.WAKE_THEN_TOUCH -> {
        val err = VScreenAwake.wakePhoneForInput(d)
        if (err != null) throw StepRefused("INPUT_BLOCKED_SCREEN_OFF", inputBlockedMessage(err))
        "wake"
    }
    InputRoute.Route.REFUSE -> throw StepRefused("INPUT_BLOCKED_SCREEN_OFF", inputBlockedMessage())
    InputRoute.Route.A11Y -> "a11y"
}

/** Append an informational routing note (`via=a11y` / `via=wake`) when a non-default channel was used. */
internal fun noteViaChannel(notes: Notes, via: String?) {
    when (via) {
        "a11y" -> notes += tr(S.vscreen_act_via_a11y_action_performed_via)
        "wake" -> notes += tr(S.vscreen_act_via_wake_main_screen_temporarily)
    }
}

/** What one action did ([text]), and [miss] when its own effect could not be confirmed (it ends the stretch; [code] says which kind). */
internal class Done(
    val text: String,
    val miss: String? = null,
    val code: String? = null,
    val typeVerified: Boolean = false,
    val gesture: GestureRecord? = null,
    val typedField: String? = null,
    val typedBounds: Rect? = null,
    val via: String? = null,
    val missSystem: String? = null,
    val missExcerpts: List<String> = emptyList(),
)

/** Do one action and say what was done (facts: where, with which method). */
private fun VScreenOffloadHandler.doAction(
    sid: String,
    d: VScreenDisplay,
    step: Step,
    ctx: ActCtx,
    tappedAt: Pair<Int, Int>? = null,
    fromRule: Boolean = false,
): Done {
    appRule(d, step, fromRule = fromRule)
    if (fromRule || step.kind in setOf(StepKind.TAP, StepKind.PRESS, StepKind.SWIPE, StepKind.DRAG, StepKind.SCROLL, StepKind.TYPE, StepKind.KEY)) {
        com.kilon.agentvscreen.host.Risk.recordInputAction(Call.current()?.tokenId)
        try { com.kilon.agentvscreen.host.Risk.checkRate(context, Call.current()) } catch (e: ActionError) { throw StepRefused(e.code, e.message ?: e.code, e.data) }
    }
    ctx.point = null
    var miss: String? = null
    var missSystem: String? = null
    var missExcerpts: List<String> = emptyList()
    var missCode: String? = null
    var typeVerified = false
    var typedField: String? = null
    var typedBounds: Rect? = null
    var dragRecord: GestureRecord? = null
    var actionVia: String? = null
    val pts = ArrayList<IntArray>()
    val hu = ctx.human
    // 1.3.0-beta19: a person does not act the instant they decide: a short, uneven pause first.
    val pause = hu?.pauseMs()?.also { Thread.sleep(it) }
    val fact = BackgroundOperation.agentAction(sid, null, isRead = false, describe = com.kilon.agentvscreen.host.Audit.redact(listOf(step.raw)).take(40)) { dd ->
        fun okOr(o: VScreenShell.Outcome) { if (!o.ok) throw ActionError(o.code, o.message) }
        fun there(t: Target) = (t.label?.let { tr(S.gesture_which_is, it.take(30)) } ?: "") + (t.moved?.let { tr(S.public_guide_text, it) } ?: "")
        fun viaTag(v: String?) = v?.let { tr(S.gesture_via, it) }.orEmpty()
        val reach = InputReach.probe(dd.displayId)
        val wakeEnabled = VScreenStore.phoneScreenMode.value == VScreenStore.PhoneScreenMode.WAKE_WHEN_NEEDED
        when (step.kind) {
            StepKind.TAP -> {
                val t = target(sid, dd, step.target!!, ctx)
                payGuard(sid, dd, t.atPoint, t.x, t.y, fromRule = fromRule, contextEls = t.contextEls, acted = ctx.acted)
                ctx.focusBefore = com.kilon.agentvscreen.core.VScreenTyping.focusedFieldKey(dd.displayId)
                val kind = if (t.isElement) InputRoute.Kind.ELEMENT_TAP else InputRoute.Kind.POINT_OR_GESTURE
                val snap = if (reach == InputReach.Reach.BLOCKED && kind == InputRoute.Kind.ELEMENT_TAP) com.kilon.agentvscreen.core.A11yTree.snapshot(dd.displayId) else null
                val a11yNode = if (snap != null && t.box != null) snap.findNodeFor(t.box, t.label, dd::toFrame) else null
                val route = InputRoute.choose(reach, kind, hasNode = a11yNode?.clickableAncestor() != null, wakeEnabled = wakeEnabled)
                if (route == InputRoute.Route.A11Y && snap != null && a11yNode != null && snap.performClick(a11yNode)) {
                    actionVia = "a11y"
                    pts += intArrayOf(t.x, t.y); ctx.point = t.x to t.y
                    tr(S.vscreen_act_tapped, t.x, t.y) + there(t) + viaTag(actionVia)
                } else {
                    val touchRoute = if (route == InputRoute.Route.A11Y) {
                        InputRoute.choose(reach, InputRoute.Kind.POINT_OR_GESTURE, hasNode = false, wakeEnabled = wakeEnabled)
                    } else route
                    actionVia = executeWakeOrRefuse(dd, touchRoute)
                    val (x, y) = hu?.near(t.x, t.y, t.box, dd.width, dd.height) ?: (t.x to t.y)
                    okOr(if (hu != null) VScreenShell.tap(dd.displayId, x, y, hu.holdMs()) else VScreenShell.tap(dd.displayId, x, y)); pts += intArrayOf(x, y); ctx.point = x to y
                    tr(S.vscreen_act_tapped, x, y) + there(t) + (if (hu != null) Human.said(pause ?: 0, t.x to t.y, x to y) else "") + viaTag(actionVia)
                }
            }
            StepKind.PRESS -> {
                val t = target(sid, dd, step.target!!, ctx)
                payGuard(sid, dd, t.atPoint, t.x, t.y, fromRule = fromRule, contextEls = t.contextEls, acted = ctx.acted)
                val kind = if (t.isElement) InputRoute.Kind.ELEMENT_PRESS else InputRoute.Kind.POINT_OR_GESTURE
                val snap = if (reach == InputReach.Reach.BLOCKED && kind == InputRoute.Kind.ELEMENT_PRESS) com.kilon.agentvscreen.core.A11yTree.snapshot(dd.displayId) else null
                val a11yNode = if (snap != null && t.box != null) snap.findNodeFor(t.box, t.label, dd::toFrame) else null
                val route = InputRoute.choose(reach, kind, hasNode = a11yNode?.longClickableAncestor() != null, wakeEnabled = wakeEnabled)
                if (route == InputRoute.Route.A11Y && snap != null && a11yNode != null && snap.performLongClick(a11yNode)) {
                    actionVia = "a11y"
                    pts += intArrayOf(t.x, t.y); ctx.point = t.x to t.y
                    tr(S.vscreen_act_long_pressed, t.x, t.y) + there(t) + viaTag(actionVia)
                } else {
                    val touchRoute = if (route == InputRoute.Route.A11Y) {
                        InputRoute.choose(reach, InputRoute.Kind.POINT_OR_GESTURE, hasNode = false, wakeEnabled = wakeEnabled)
                    } else route
                    actionVia = executeWakeOrRefuse(dd, touchRoute)
                    val (x, y) = hu?.near(t.x, t.y, t.box, dd.width, dd.height) ?: (t.x to t.y)
                    val ms = hu?.vary(step.ms ?: 800L, 0.15) ?: (step.ms ?: 800L)
                    okOr(VScreenShell.longPress(dd.displayId, x, y, ms.toInt())); pts += intArrayOf(x, y); ctx.point = x to y
                    tr(S.gesture_held_ms, x, y, ms) + there(t) + (if (hu != null) Human.said(pause ?: 0, t.x to t.y, x to y) else "") + viaTag(actionVia)
                }
            }
            StepKind.SWIPE -> {
                bounds(dd, step.points)
                val route = InputRoute.choose(reach, InputRoute.Kind.POINT_OR_GESTURE, hasNode = false, wakeEnabled = wakeEnabled)
                actionVia = executeWakeOrRefuse(dd, route)
                val px = (minOf(dd.width, dd.height) * 0.015).toInt().coerceAtLeast(2)
                val a = step.points[0].let { hu?.drift(it, px, dd.width, dd.height) ?: it }
                val b = step.points[1].let { hu?.drift(it, px * 2, dd.width, dd.height) ?: it }
                val ms = hu?.vary(step.ms ?: 300L) ?: (step.ms ?: 300L)
                okOr(VScreenShell.swipe(dd.displayId, a.first, a.second, b.first, b.second, ms.toInt()))
                pts += intArrayOf(a.first, a.second); pts += intArrayOf(b.first, b.second)
                tr(S.vscreen_act_swiped_ms, a.first, a.second, b.first, b.second, ms) + (if (hu != null) Human.said(pause ?: 0) else "") + viaTag(actionVia)
            }
            StepKind.DRAG -> {
                bounds(dd, step.points)
                val route = InputRoute.choose(reach, InputRoute.Kind.POINT_OR_GESTURE, hasNode = false, wakeEnabled = wakeEnabled)
                actionVia = executeWakeOrRefuse(dd, route)
                // Human pacing: the hand is a little off at each turn (3 px: a drag usually has to land somewhere).
                val path = if (hu != null) step.points.map { hu.drift(it, 3, dd.width, dd.height) } else step.points
                val ms = hu?.vary(step.ms ?: 600L, 0.15) ?: (step.ms ?: 600L)
                val pkg = runCatching { VScreenShell.topPackage(dd.displayId) }.getOrNull()
                dd.boost(ms + Gesture.BOOST_EXTRA_MS)
                dd.latestWithVersion()?.let { (bmp, ver) -> VScreenRecorder.offerFresh(dd, bmp, ver, pkg) }
                val beforeEls = runCatching { a11yRaw(dd).filter { !it.placeholder } }.getOrNull()
                val preDownAt = System.currentTimeMillis()
                val downAt = preDownAt
                // 2.0.0-beta1 & 2.0.0-beta4 (4.7): a straight drag stays one server-timed gesture via VScreenShell.drag
                // (eased, still at the end). GestureRecord timings come from call start + duration + DRAG_END_HOLD_MS.
                if (path.size == 2) okOr(VScreenShell.drag(dd.displayId, path[0].first, path[0].second, path[1].first, path[1].second, ms.toInt(), DRAG_END_HOLD_MS))
                else drag(dd, path, ms.toInt())
                val upAt = System.currentTimeMillis()
                val upElapsed = SystemClock.elapsedRealtime()
                val midAt = if (path.size == 2) downAt + ms / 2 else downAt + Gesture.DRAG_DOWN_HOLD_MS + ms / 2
                val preUpAt = (upAt - DRAG_END_HOLD_MS).coerceAtLeast(midAt)
                val rec = Gesture.evaluateDragRecord(
                    before = beforeEls,
                    d = dd,
                    startPt = path.first(),
                    midPt = Gesture.pointAtHalfPath(path),
                    endPt = path.last(),
                    preDownAt = preDownAt,
                    downAt = downAt,
                    midAt = midAt,
                    preUpAt = preUpAt,
                    upAt = upAt,
                    upElapsed = upElapsed,
                    pkg = pkg,
                )
                dragRecord = rec
                path.forEach { pts += intArrayOf(it.first, it.second) }
                tr(S.gesture_held_and_dragged_through) + path.joinToString(" → ") { "(${it.first},${it.second})" } +
                    tr(S.gesture_ms, ms) + rec.describeSuffix(omitUnknownWhenNotPicked = path.size == 2) + (if (hu != null) Human.said(pause ?: 0) else "") + viaTag(actionVia)
            }
            StepKind.SCROLL -> {
                val c = step.target?.let { val t = target(sid, dd, it, ctx); t.x to t.y } ?: (dd.width / 2 to dd.height / 2)
                val dir = step.text!!
                val atPt = if (step.target != null) c else null
                val snap = if (reach == InputReach.Reach.BLOCKED) com.kilon.agentvscreen.core.A11yTree.snapshot(dd.displayId) else null
                val hasScrollNode = snap?.findScrollable(dir, atPt, dd::toFrame) != null
                val route = InputRoute.choose(reach, InputRoute.Kind.SCROLL, hasNode = hasScrollNode, wakeEnabled = wakeEnabled)
                if (route == InputRoute.Route.A11Y && snap != null && snap.scroll(dir, atPt, dd::toFrame)) {
                    actionVia = "a11y"
                    tr(S.vscreen_act_scrolled, dir) + viaTag(actionVia)
                } else {
                    val touchRoute = if (route == InputRoute.Route.A11Y) {
                        InputRoute.choose(reach, InputRoute.Kind.POINT_OR_GESTURE, hasNode = false, wakeEnabled = wakeEnabled)
                    } else route
                    actionVia = executeWakeOrRefuse(dd, touchRoute)
                    val vertical = dir == "up" || dir == "down"
                    val span = ((if (vertical) dd.height else dd.width) * (if (hu != null) 0.3 * (0.85 + Math.random() * 0.3) else 0.3)).toInt()
                    // "down" = see what is below: the finger moves up.
                    val (x1, y1, x2, y2) = when (dir) {
                        "down" -> listOf(c.first, (c.second + span).coerceAtMost(dd.height - 2), c.first, (c.second - span).coerceAtLeast(1))
                        "up" -> listOf(c.first, (c.second - span).coerceAtLeast(1), c.first, (c.second + span).coerceAtMost(dd.height - 2))
                        "right" -> listOf((c.first + span).coerceAtMost(dd.width - 2), c.second, (c.first - span).coerceAtLeast(1), c.second)
                        else -> listOf((c.first - span).coerceAtLeast(1), c.second, (c.first + span).coerceAtMost(dd.width - 2), c.second)
                    }
                    // A drag (eased, still at the end), not a fling: the list moves by what was dragged, every time.
                    val px = (minOf(dd.width, dd.height) * 0.02).toInt().coerceAtLeast(2)
                    val (sx, sy) = if (hu != null) hu.drift(x1 to y1, px, dd.width, dd.height) else (x1 to y1)
                    val (ex, ey) = if (hu != null) hu.drift(x2 to y2, px, dd.width, dd.height) else (x2 to y2)
                    okOr(VScreenShell.drag(dd.displayId, sx, sy, ex, ey, hu?.vary(350L)?.toInt() ?: 350, hu?.vary(80L, 0.4)?.toInt() ?: 80))
                    pts += intArrayOf(sx, sy); pts += intArrayOf(ex, ey)
                    tr(S.vscreen_act_finger_dragged, sx, sy, ex, ey) + (if (hu != null) Human.said(pause ?: 0) else "") + viaTag(actionVia)
                }
            }
            StepKind.TYPE -> {
                val text = step.text!!
                val slow = step.ms
                val hasWritable = if (reach == InputReach.Reach.BLOCKED) {
                    com.kilon.agentvscreen.core.VScreenTyping.hasWritableNode(dd.displayId, tappedAt)
                } else false
                val route = InputRoute.choose(reach, InputRoute.Kind.TYPE, hasNode = hasWritable, wakeEnabled = wakeEnabled)
                var a11yOnly = route == InputRoute.Route.A11Y
                if (!a11yOnly) actionVia = executeWakeOrRefuse(dd, route)

                fun runType(onlyA11y: Boolean): com.kilon.agentvscreen.core.VScreenTyping.TypeFact = when {
                    slow != null -> {
                        // 1.4.0-beta13: one character at a time with a gap (code points, so an emoji is one), first as asked
                        // (replace or add), then added; each character is its own input event for the app's listeners.
                        val chars = text.codePoints().toArray().map { String(Character.toChars(it)) }
                        var tf = com.kilon.agentvscreen.core.VScreenTyping.typeFact(dd.displayId, chars[0], step.append, tappedAt, if (tappedAt != null) ctx.focusBefore else null, a11yOnly = onlyA11y)
                        var i = 1
                        while (i < chars.size && tf.field != null && !tf.a11yFailed) {
                            if (tf.password && i == 1) { /* a password box shows nothing as you type: still one at a time */ }
                            Thread.sleep(hu?.let { (slow * (0.6 + Math.random() * 0.8)).toLong() } ?: slow)
                            tf = com.kilon.agentvscreen.core.VScreenTyping.typeFact(dd.displayId, chars[i], true, a11yOnly = onlyA11y)
                            i++
                        }
                        if (i < chars.size && !tf.a11yFailed) tf = com.kilon.agentvscreen.core.VScreenTyping.typeFact(dd.displayId, chars.drop(i).joinToString(""), true, a11yOnly = onlyA11y)
                        tf
                    }
                    hu == null || text.length > 60 ->
                        com.kilon.agentvscreen.core.VScreenTyping.typeFact(dd.displayId, text, step.append, tappedAt, if (tappedAt != null) ctx.focusBefore else null, a11yOnly = onlyA11y)
                    else -> {
                        // Human pacing: a few characters at a time; the first piece as asked (replace or add), then added. No
                        // box found, or a password box: the rest goes in at once (nothing to pace, or nothing to show).
                        val parts = hu.pieces(text)
                        var tf = com.kilon.agentvscreen.core.VScreenTyping.typeFact(dd.displayId, parts[0], step.append, tappedAt, if (tappedAt != null) ctx.focusBefore else null, a11yOnly = onlyA11y)
                        var i = 1
                        while (i < parts.size && tf.field != null && !tf.a11yFailed) {
                            if (tf.password) { tf = com.kilon.agentvscreen.core.VScreenTyping.typeFact(dd.displayId, parts.drop(i).joinToString(""), true, a11yOnly = onlyA11y); break }
                            Thread.sleep(hu.keyGapMs())
                            tf = com.kilon.agentvscreen.core.VScreenTyping.typeFact(dd.displayId, parts[i], true, a11yOnly = onlyA11y)
                            i++
                        }
                        tf
                    }
                }

                val typeBefore = dd.latestFrame()?.let { VScreenPerception.small(it) }
                val f = try {
                    val first = runType(a11yOnly)
                    if (a11yOnly && first.a11yFailed) {
                        val fb = InputRoute.choose(reach, InputRoute.Kind.POINT_OR_GESTURE, hasNode = false, wakeEnabled = wakeEnabled)
                        actionVia = executeWakeOrRefuse(dd, fb)
                        a11yOnly = false
                        runType(false)
                    } else {
                        if (a11yOnly && first.viaA11y) actionVia = "a11y"
                        first
                    }
                } catch (e: Throwable) {
                    typeBefore?.recycle()
                    throw e
                }
                val where = f.bounds?.let { b -> " [${b.left},${b.top},${b.right},${b.bottom}]" + (f.fieldId?.let { tr(S.public_guide_text, it) } ?: "") } ?: ""
                // 2.0.0-beta15 (test B6: a browser address box took the URL and loaded it while the reply said TYPE_FAILED /
                // no focused input box): the accessibility tree of some apps shows no focused box even while one has the
                // keys. "No box" is a failure only when the picture also stayed as it was; a picture that changed while
                // typing is "unconfirmed", as for a box that cannot be read.
                val pictureMoved = if (f.field == null && f.checked) typeBefore?.let { b -> pictureStill(dd, b).not() } ?: false
                    else { typeBefore?.recycle(); false }
                (when {
                    // 1.4.0-beta3: only a reading that saw no focused box is a failure. When the box could not be read at
                    // all (a web page, a self-drawn box) the text often did go in (beta2 test: the words reached the
                    // suggestions while the reply said TYPE_FAILED); that is "unconfirmed", said in the step, not a miss.
                    f.field == null && f.checked && !pictureMoved -> {
                        missCode = "TYPE_FAILED"
                        miss = tr(S.vscreen_act_no_input_box_has_focus)
                        tr(S.vscreen_act_sent_characters_by_no_focused, f.method, text.length)
                    }
                    f.field == null -> {
                        tr(S.vscreen_act_sent_characters_by_no_input, f.method, text.length)
                    }
                    f.unreadable -> {
                        typedField = f.field
                        val qf = ScreenText.quote(f.field, maxLen = 40)
                        tr(S.vscreen_act_sent_characters_by_the_input, f.method, text.length, where, qf)
                    }
                    f.password -> tr(S.vscreen_act_typed_characters_password_box_now, text.length, f.method, where, f.field.length)
                    !f.field.contains(ScreenText.clean(text, maxLen = 0)) -> {
                        typedField = f.field
                        val qf = ScreenText.quote(f.field, maxLen = 60)
                        missCode = "TYPE_FAILED"
                        miss = tr(S.vscreen_act_the_input_box_now_reads, where, qf)
                        missSystem = tr(S.vscreen_act_the_input_box_readback_does, where)
                        missExcerpts = if (f.field.isNotBlank()) listOf(f.field) else emptyList()
                        tr(S.vscreen_act_sent_characters_by_the_input_2, f.method, text.length, where, qf)
                    }
                    else -> {
                        typeVerified = true
                        typedField = f.field
                        typedBounds = f.bounds
                        val qf = ScreenText.quote(f.field, maxLen = 60)
                        tr(S.vscreen_act_typed_characters_the_input_box, text.length, f.method, where, qf)
                    }
                }) + viaTag(actionVia)
            }
            StepKind.KEY -> {
                val route = InputRoute.choose(reach, InputRoute.Kind.POINT_OR_GESTURE, hasNode = false, wakeEnabled = wakeEnabled)
                actionVia = executeWakeOrRefuse(dd, route)
                val k = step.text!!.lowercase()
                // 2.0.0-beta15 (test B1 on HONOR: `key esc` on a settings sub-page was sent and nothing happened, while
                // `key back` went back): many apps ignore Esc, and Android only turns an unhandled Esc into Back on some
                // builds. Where no text box has focus (Esc there may clear or close the box), an Esc that leaves the
                // picture untouched is followed by Back, and the reply says so.
                val escToBack = (k == "esc" || k == "escape") && com.kilon.agentvscreen.core.VScreenTyping.focusedFieldKey(dd.displayId) == null
                val before = if (escToBack) dd.latestFrame()?.let { VScreenPerception.small(it) } else null
                okOr(VScreenShell.key(dd.displayId, step.text))
                (if (before != null && pictureStill(dd, before)) {
                    okOr(VScreenShell.key(dd.displayId, "back"))
                    tr(S.vscreen_act_pressed_this_page_ignored_it, step.text)
                } else tr(S.vscreen_act_pressed, step.text)) + viaTag(actionVia)
            }
            else -> throw StepRefused("INVALID_STEP", tr(S.vscreen_act_not_an_action, step.raw))
        }
    }
    ctx.acted = true
    if (pts.isNotEmpty()) BackgroundOperation.actionLog(sid, 1).lastOrNull()?.trackN?.let { VScreenRecorder.setPoints(d.displayId, it, pts) }
    return Done(fact, miss, missCode, typeVerified, dragRecord, typedField, typedBounds, via = actionVia, missSystem = missSystem, missExcerpts = missExcerpts)
}

/** Whether the picture stayed as [before] for [ESC_WAIT_MS] (an Esc that did nothing, keys that showed nothing); [before] is recycled. */
private fun pictureStill(d: VScreenDisplay, before: android.graphics.Bitmap): Boolean = try {
    val end = SystemClock.elapsedRealtime() + ESC_WAIT_MS
    var seen = d.version
    var same = true
    val mask = VScreenPerception.mask(d.displayId)
    while (same && SystemClock.elapsedRealtime() < end) {
        d.awaitNewFrame(seen, 120, urgent = true); seen = d.version
        same = d.latestFrame()?.let { VScreenPerception.small(it) }?.let { a -> try { Change.same(before, a, Change.Level.TINY, skip = mask?.rectsIn(a.width, a.height).orEmpty()) } finally { a.recycle() } } ?: false
    }
    same
} finally { before.recycle() }
private const val ESC_WAIT_MS = 450L

internal fun bounds(d: VScreenDisplay, pts: List<Pair<Int, Int>>) {
    pts.firstOrNull { it.first !in 0 until d.width || it.second !in 0 until d.height }?.let {
        throw StepRefused("OUT_OF_BOUNDS", tr(S.vscreen_act_is_outside_the_x_screen, it.first, it.second, d.width, d.height))
    }
}

/**
 * One finger down at the first point, still 150 ms (apps pick a thing up only after a hold), glide through the
 * points over [ms], still 120 ms, lift.
 */
/** How long a drag's finger rests at the end before lifting, so the page takes it as a drag and not a fling. */
private const val DRAG_END_HOLD_MS = 120

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
            if (BackgroundOperation.state.value.takeoverSessionId != null) throw ActionError("USER_TAKEOVER", tr(S.gesture_the_user_took_over_the))
            val f = ((SystemClock.elapsedRealtime() - t0).toDouble() / ms).coerceIn(0.0, 1.0)
            var dist = f * total; var i = 0
            while (i < lens.size - 1 && dist > lens[i]) { dist -= lens[i]; i++ }
            val a = pts[i]; val b = pts[i + 1]; val k = if (lens[i] <= 0) 1.0 else (dist / lens[i]).coerceIn(0.0, 1.0)
            VScreenShell.finger(d.displayId, "move", 0, (a.first + (b.first - a.first) * k).roundToInt(), (a.second + (b.second - a.second) * k).roundToInt())
            if (f >= 1.0) break
            Thread.sleep(16)
        }
        Thread.sleep(DRAG_END_HOLD_MS.toLong())
        VScreenShell.finger(d.displayId, "up", 0, pts.last().first, pts.last().second)
    } finally { com.kilon.agentvscreen.host.quiet("drag-cancel") { VScreenShell.finger(d.displayId, "cancel", 0, -1, -1) } }
}

/**
 * 1.3 → 1.4.0-beta2: a command that may move apps between the screens is a "stretch" of VScreenOwnership, which is
 * the one place that decides who moved what (our action → MAIN_SCREEN_CHANGED in this reply; the user → handover).
 * The user going on using the main screen is not news.
 */
internal class MainWatch(val token: Long)

internal fun VScreenOffloadHandler.mainWatch(sid: String?, vararg extra: String?): MainWatch {
    val s = sid ?: return MainWatch(0L)
    return MainWatch(com.kilon.agentvscreen.core.VScreenOwnership.stretchBegin(context, s, extra.toList() + listOfNotNull(VScreenKnowledge.journey(s))))
}

/** 1.3: silence what is on the background screen (VScreenMute); a line for the notes when an app was just muted. */
internal fun VScreenOffloadHandler.muteNote(d: VScreenDisplay): String? = runCatching {
    com.kilon.agentvscreen.core.VScreenMute.sync(d.displayId, mainTop()?.substringBefore('/')).takeIf { it.isNotEmpty() }?.let { m ->
        tr(S.vscreen_act_on_the_background_screen_is, m.joinToString("、"), m.joinToString(", ")) +
            com.kilon.agentvscreen.core.VScreenMute.heard.takeIf { it.isNotEmpty() }?.let { h ->
                tr(S.vscreen_act_but_what_is_already_playing, h.joinToString("、"), h.joinToString(", "))
            }.orEmpty()
    }
}.getOrNull()

/** Main-screen facts for the notes: only when the stretch's own apps reached the main screen (see [MainWatch]). */
@Suppress("UNUSED_PARAMETER")
internal fun VScreenOffloadHandler.mainCheckLines(w: MainWatch, sid: String? = null): String? =
    if (w.token == 0L) null else com.kilon.agentvscreen.core.VScreenOwnership.stretchEnd(context, w.token)

// ── the command ──────────────────────────────────────────────────────

/** `act ["<steps>"] [--stop C] [--on R] [--hold] [--watch T] [--fast] [--bare] [--full] [--keep notify|wake] [--for T] [--save NAME]` */
internal fun VScreenOffloadHandler.act(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr(S.vscreen_act_no_session_header_x_vscreen))
    val body = args.positional.joinToString(" ").trim()
    val steps = if (body.isEmpty()) emptyList() else Steps.parseAll(body)
    val fullFlag = args.hasFlag("full")
    val stop = args.get("stop")?.let { Steps.conds(it) }.orEmpty()
    val rules = ActRules.parseAll(args.getAll("on"))
    val keep = args.get("keep")?.lowercase()?.also {
        if (it !in setOf("notify", "wake")) throw ActionError("INVALID_ARGS", tr(S.vscreen_act_keep_notify_notifies_the_user))
        if (stop.isEmpty()) throw ActionError("INVALID_ARGS", tr(S.vscreen_act_keep_watches_the_stop_condition))
    }
    val watch = args.getLong("watch")?.let { bounded("watch", it, 0L, 120_000L) }
    val agentId = Call.current()?.tokenId ?: com.kilon.agentvscreen.api.Agents.ofSession(s)
    val wantHold = args.hasFlag("hold") || com.kilon.agentvscreen.api.AgentLedger.holdDefault(agentId)
    // handoff park: the app the session works on is on the parking display, so this stretch would act on the wrong
    // page (or on nothing). Refused here, before the screen is read, with the way out (handoff unpark).
    BackgroundOperation.requireNotParked(s)
    val d = BackgroundOperation.acquire(s, create = false)
    VScreenPerception.ensure(d)
    // 1.4.0-beta4: a lesson with a starting page checks that page itself before its first step, so the user having had
    // the screen (REREAD_REQUIRED) does not stop it: it either finds its start or refuses with NOT_AT_START.
    steps.singleOrNull()?.takeIf { it.kind == StepKind.USE }?.let { st ->
        val nm = Steps.tokens(st.raw).getOrNull(1)
        if (nm != null && workApp(s, d)?.let { VScreenMap.segment(it, nm) }?.start != null) BackgroundOperation.acknowledgeReread(s)
    }
    // 3.12: read lastReturn[sid] BEFORE fourBlocks overwrites it.
    val prevReturnAt = synchronized(VScreenOffloadHandler.lastReturn) { VScreenOffloadHandler.lastReturn[s] }
    // 3.11: `act --full` with no steps replays the last act window without re-running steps.
    val replayWindow = if (fullFlag && steps.isEmpty() && watch == null && rules.isEmpty()) Loud.lastActWindow(s) else null
    val out = runSegment(
        s, d, steps,
        SegmentOpts(
            stop = stop,
            watchMs = watch,
            bare = args.hasFlag("bare", "paused"),
            fast = args.hasFlag("fast"),
            human = args.hasFlag("human"),
            skipLookSettle = replayWindow != null,
            rules = rules,
        ),
        prevReturnAt = prevReturnAt,
    )
    val lines = ArrayList(out.lines)
    if (replayWindow != null) {
        lines += tr(S.vscreen_act_full_replay_of_the_last)
    }
    if (args.hasFlag("confirmed")) out.notes += tr(S.vscreen_act_confirmed_no_longer_has_any)
    if (steps.isNotEmpty() && body.isNotEmpty() && out.outcome == "done") args.get("save")?.let { name ->
        val app = workApp(s, d) ?: throw ActionError("NO_APP", tr(S.vscreen_act_no_app_on_the_screen))
        VScreenMap.saveSegment(app, name.trim(), body)
        lines += tr(S.vscreen_act_saved_as_segment_of_use, app, name.trim())
    } else if (args.get("save") != null && steps.isNotEmpty()) out.notes += tr(S.vscreen_act_the_stretch_did_not_go)
    val keepName = keep?.let { k -> startKeep(s, d, args.get("stop")!!, k, args.getLong("for") ?: 3_600_000L, out.notes) }
    takeAdjusted().forEach { out.notes += tr(S.vscreen_act_options_adjusted, it) }
    // The user's "blocked" apps are never read or shown, whichever way the stretch got there.
    val pkgNow = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()
    if (pkgNow != null && com.kilon.agentvscreen.core.Guard.check(com.kilon.agentvscreen.core.Guard.Act.READ, pkgNow) is com.kilon.agentvscreen.core.Guard.Verdict.Deny) return blockedBlocks(lines, out.notes, pkgNow)

    val workAppNow = workApp(s, d)
    val resolveUse: (Step) -> List<Step>? = { st ->
        val nm0 = st.text
        var seg = if (nm0 != null) workAppNow?.let { VScreenMap.segment(it, nm0) } else null
        if (seg == null) {
            val nm = Steps.tokens(st.raw).getOrNull(1)
            if (nm != null) seg = workAppNow?.let { VScreenMap.segment(it, nm) }
        }
        seg?.let { runCatching { Steps.parseAll(it.steps) }.getOrNull() }
    }
    val verifiedTypes = out.steps.filter { it.typeVerified }.map { it.n }.toSet()
    val firstNoExpect = Loud.firstUnexpectedStep(steps, verifiedTypes, resolveUse)
    val hasAppUntil = Loud.hasAppUntil(steps, resolveUse)
    val lastExpectOnlyVisual = Loud.lastExpectOnlyVisual(out.steps)
    val replyModeFull = com.kilon.agentvscreen.api.AgentLedger.replyMode(agentId) == com.kilon.agentvscreen.api.AgentLedger.REPLY_FULL
    val explicitForced = fullFlag || steps.isEmpty() || replyModeFull
    val (escalated, explainEsc) = if (!explicitForced) Loud.consumeEscalation(s) else (false to false)
    if (explainEsc) {
        out.notes += tr(S.vscreen_act_refetched_times_in_the_last, Loud.ESCALATE_WINDOW, Loud.ESCALATE_THRESHOLD, Loud.ESCALATE_TURNS)
    }
    val loudCtx = ActLoudCtx(
        stepsWritten = steps,
        firstNoExpectStep = firstNoExpect,
        hasAppUntil = hasAppUntil,
        lastExpectOnlyVisual = lastExpectOnlyVisual,
        startPkg = out.startPkg,
        firstActionMask = out.firstActionMask,
        beforeChanged = out.beforeChanged,
        afterStartMs = out.afterStartMs,
        forced = explicitForced || escalated,
        replayWindow = replayWindow,
        prevReturnAt = prevReturnAt,
        excludeWindows = out.excludeWindows,
        rulesFired = out.rulesFired,
        hasRules = rules.isNotEmpty(),
        usedHold = wantHold,
    )
    val res = fourBlocks(
        s, d, lines, out.notes, out.start, out.actionMs, "act",
        fullList = steps.isEmpty() || fullFlag,
        steps = out.steps,
        outcome = out.outcome,
        stoppedAt = out.stoppedAt,
        actLoud = loudCtx,
    )
    out.errorData?.let { extra ->
        val dJson = res.data
        if (dJson != null) {
            for (k in extra.keys()) {
                if (!dJson.has(k)) dJson.put(k, extra.get(k))
            }
        }
    }
    if (keepName != null) res.data?.put("keep", keepName)
    return res
}

/** The now block of an app the user blocked: nothing of it is read or shown. */
internal fun blockedBlocks(done: List<String>, notes: Notes, pkg: String): NativeOffloadResult {
    val sb = StringBuilder(Blocks.done + "\n")
    done.forEach { sb.append(it).append('\n') }
    notes.add("APP_BLOCKED", tr(S.vscreen_act_the_screen_shows_which_the, pkg))
    sb.append("\n" + Blocks.meanwhile + "\n" + tr(S.vscreen_act_not_read_the_user_blocked, pkg) + "\n\n" + Blocks.now + " $pkg\n" +
        tr(S.vscreen_act_not_read_the_user_blocked_2) + "\n\n" + Blocks.notes + "\n")
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
private fun VScreenOffloadHandler.startKeep(sid: String, d0: VScreenDisplay, spec: String, how: String, forMs0: Long, notes: Notes): String? {
    val forMs = bounded("for", forMs0, 10_000L, 12 * 3_600_000L)
    val app = runCatching { VScreenShell.topPackage(d0.displayId) }.getOrNull()
    val me = Call.current()?.tokenId ?: com.kilon.agentvscreen.api.Agents.ofSession(sid)
    val spec2 = VScreenRules.Spec(VScreenRules.nextName(me), sid, spec, app, 1_000L, forMs, wake = how == "wake", notify = how == "notify", agentId = me)
    val l = try { VScreenRules.start(spec2, keepHost(spec)) } catch (e: IllegalStateException) { notes.add("KEEP_LIMIT", e.message ?: "KEEP_LIMIT"); return null }
    val warn = if (how == "notify") VScreenHandoff.notifyBlocked(context)?.let { tr(S.vscreen_act_but_notifications_can_t_be, it) } else null
    notes += tr(S.vscreen_act_after_this_turn_keep_watching, l.spec.name, spec, forMs / 60_000, if (how == "wake") "发一条事件（vscreen events --wait 取）" else "通知用户", if (how == "wake") "sends an event (collect with vscreen events --wait)" else "notifies the user") +
        (warn ?: "") + tr(S.vscreen_act_the_screen_stays_on_this, l.spec.name)
    return l.spec.name
}

// ── launch ──────────────────────────────────────────────────────────

/** 2.0.0-beta16 (Item 4): unified caps for per-app lists echoed in `launch` [Done]. */
internal object LaunchLimits {
    const val MAX_NOTES = 12
    const val MAX_SEGMENTS = 5
}

/** 2.0.0-beta16 (Item 4), pure: format the notes / recent segment names / before-after lines shown on `launch`. */
internal fun formatLaunchMapLines(m: VScreenMap.AppMap, verNow: String?): List<String> {
    val lines = ArrayList<String>()
    if (m.notes.isNotEmpty()) {
        val shownNotes = m.notes.takeLast(LaunchLimits.MAX_NOTES)
        val extra = m.notes.size - shownNotes.size
        val tail = if (extra > 0) tr(S.vscreen_act_more_see_note, extra) else ""
        lines += tr(S.vscreen_act_noted_before_note) +
            shownNotes.joinToString(tr(S.agent_guide_sep)) { n -> "${n.id}. ${n.text}" + noteOrigin(n, verNow) } + tail
    }
    if (m.segments.isNotEmpty()) {
        val recent = m.segments.sortedWith(
            compareByDescending<VScreenMap.Segment> { it.lastUsedAt }
                .thenByDescending { it.uses }
                .thenByDescending { it.at },
        )
        val shownSegs = recent.take(LaunchLimits.MAX_SEGMENTS)
        val extra = m.segments.size - shownSegs.size
        val tail = if (extra > 0) tr(S.vscreen_act_more_see_note_2, extra) else ""
        lines += tr(S.vscreen_act_saved_segments_use_name) +
            shownSegs.joinToString(tr(S.automation_list_sep)) { it.name } + tail
    }
    val p = m.profile
    if (p["before"] != null || p["after"] != null) {
        lines += tr(S.vscreen_act_every_act_automatically_runs) +
            listOfNotNull(
                p["before"]?.let { tr(S.vscreen_act_before_2, it) },
                p["after"]?.let { tr(S.vscreen_act_after_2, it) },
            ).joinToString(tr(S.vscreen_act_text_2)) + tr(S.vscreen_act_bare_skips_them)
    }
    return lines
}

/** `launch <app> [--landscape|--portrait] [--restart]`: open it, look, return the four blocks. */
internal fun VScreenOffloadHandler.launchCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr(S.vscreen_act_no_session_header_x_vscreen))
    val q = args.positional.joinToString(" ").trim().ifEmpty { throw ActionError("INVALID_ARGS", tr(S.vscreen_act_launch_app_name_or_package)) }
    val pkg = VScreenKnowledge.resolveApp(q) ?: throw ActionError("APP_NOT_FOUND", tr(S.vscreen_act_not_found_apps_lists_installed, q))
    // 2.0.0-beta6 (6.1 L3 / 6.10 #3): single gate refusing our own package on the background screen -> PROTECTED_APP.
    requireNotOwnPackage(pkg)
    val keep = args.hasFlag("keep")
    if (keep && (args.hasFlag("landscape") || args.hasFlag("portrait") || args.hasFlag("restart"))) throw ActionError("INVALID_ARGS",
        tr(S.vscreen_act_keep_keeps_the_screen_s))
    // The app is parked (handoff park): it is on the hidden parking display, not on this screen. Saying so is better
    // than a launch that would open a second copy of a page the user put aside.
    BackgroundOperation.requireNotParked(s, pkg)
    com.kilon.agentvscreen.core.Guard.require(com.kilon.agentvscreen.core.Guard.Act.OPEN, pkg, s, asked = args.hasFlag("asked"))
    // The user is using this very app on the main screen right now: a second copy, a cold start or a redirect can
    // disturb what they are doing (log-ins, half-typed text, single-instance apps). Not the agent's call: ask first.
    if (!args.hasFlag("asked") && userUsing(pkg)) {
        val app = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() ?: pkg
        throw ActionError("APP_IN_USE", tr(S.vscreen_act_the_user_is_using_on, app, pkg))
    }
    val start = System.currentTimeMillis()
    val t0 = SystemClock.elapsedRealtime()
    val main0 = mainWatch(s, pkg)
    val orient = when { args.hasFlag("landscape") -> true; args.hasFlag("portrait") -> false; else -> null }
    val facts = doLaunch(s, pkg, orient, args.hasFlag("restart"), keep = keep)
    // The session works on this app now: whatever was parked is no longer what it is on.
    BackgroundOperation.appSelected(s, pkg)
    com.kilon.agentvscreen.core.VScreenOwnership.agentLaunched(s, pkg)
    val d = BackgroundOperation.acquire(s, create = false)
    VScreenPerception.ensure(d)
    val done = ArrayList(facts.done)
    // What was written down about this app before, with who wrote it and for which version of the app.
    val m = VScreenMap.load(pkg)
    val verNow = appVersion(pkg)
    done += formatLaunchMapLines(m, verNow)
    val notes = facts.notes
    mainCheckLines(main0, s)?.let { notes.add("MAIN_SCREEN_CHANGED", it) }
    muteNote(d)?.let { notes += it }
    takeAdjusted().forEach { notes += tr(S.vscreen_act_options_adjusted, it) }
    return fourBlocks(s, d, done, notes, start, SystemClock.elapsedRealtime() - t0, "launch", fullList = true)
}

/** The user has [pkg] in front on the main screen and is looking at it (screen on and unlocked). */
internal fun VScreenOffloadHandler.userUsing(pkg: String): Boolean {
    val pm = context.getSystemService(android.os.PowerManager::class.java)
    val kg = context.getSystemService(android.app.KeyguardManager::class.java)
    if (pm?.isInteractive != true || kg?.isKeyguardLocked == true) return false
    return runCatching { VScreenShell.topPackage(0) }.getOrNull() == pkg
}

/** versionName of [pkg] (null when it cannot be read). */
internal fun VScreenOffloadHandler.appVersion(pkg: String): String? = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()

/** "(by X, for app 1.2; the app is now 1.3)" — only what differs from the reader's own situation is said. */
internal fun noteOrigin(n: VScreenMap.Note, verNow: String?): String {
    val me = Call.current()?.tokenName
    val parts = buildList {
        if (n.by != null && n.by != me) add(tr(S.vscreen_act_by, n.by))
        if (n.appVersion != null && verNow != null && n.appVersion != verNow) add(tr(S.vscreen_act_kept_for_app_version_now, n.appVersion, verNow))
    }
    return if (parts.isEmpty()) "" else tr(S.vscreen_act_text_3, parts.joinToString("，"), parts.joinToString(", "))
}
