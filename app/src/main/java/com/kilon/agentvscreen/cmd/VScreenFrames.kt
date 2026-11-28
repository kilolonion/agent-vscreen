package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.tr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenRecorder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// [vscreen] 1.3 beta5: frames from the recording track, anchored on the agent's own actions.
//
// Before, the sheet in 【这段时间】 was up to 8 frames spread over the stretch, each action's red dot drawn on the first
// frame AFTER it (on the page the tap produced, not on what was under the finger), and only when the picture changed —
// so a tap that hit nothing, the case where seeing the dot matters most, came without any picture. Agents could not
// ask for more frames, another stretch, or a denser look either.
//
// Now each action gets the frame showing just before it, with where it touched drawn on it (a numbered ring / a line)
// and its step written under it; the next cell (or 【现在】) is what it led to. `vscreen frames` returns the same kind
// of sheet for any stretch the track still holds, as many frames as asked, over several pictures when needed.

/** One cell of a sheet: when, the frame, what to write under it, the actions drawn on it (numbered [nums]). */
internal class Cell(val t: Long, val bmp: Bitmap, val tag: String, val marks: List<VScreenRecorder.Mark>, val nums: List<Int>)

/** What `frames` with no range shows: the stretch the last four-block reply covered, per session. */
internal val lastWindows: HashMap<String, LongArray> = VScreenOffloadHandler.recent()

private class Pick(val frame: VScreenRecorder.Frame, val tag: StringBuilder = StringBuilder(), val marks: ArrayList<VScreenRecorder.Mark> = ArrayList(), val nums: ArrayList<Int> = ArrayList())

/**
 * Frames for a sheet of [from]..[to]: the one showing at [from]; for every action the one showing just before it
 * (the action drawn on it); then frames spread over what is left. At most [max]; when there are more actions than
 * room, the latest are kept and [Picked.leftOut] says how many earlier ones are not shown. [every] (ms) samples at
 * fixed ticks instead.
 */
internal class Picked(
    val cells: List<Cell>,
    val leftOut: Int,
    val distinct: Int,
    val actions: Int,
    /** 2.0.0-beta16 (Item 6): number of frames inside (from, to] with a clear visual change (same filter as Meanwhile). */
    val clearChanges: Int = (distinct - 1).coerceAtLeast(0),
)

internal data class SelectedFrame(
    val index: Int,
    val frame: VScreenRecorder.Frame,
    val tag: String,
    val marks: List<VScreenRecorder.Mark>,
    val nums: List<Int>,
)

internal data class SelectedFrames(
    val picks: List<SelectedFrame>,
    val leftOut: Int,
    val distinct: Int,
    val actions: Int,
    val clearChanges: Int,
)

/**
 * 2.0.0-beta16 (Item 6), pure: choose frames for a replay sheet of [from]..[to].
 * Only frames tagged [VScreenRecorder.Frame.clearChange] count toward [SelectedFrames.distinct] /
 * [SelectedFrames.clearChanges] and toward filling remaining slots (blinking cursors and tiny animations do not).
 * When a stretch has no clear changes and no actions, only start and end (at most 2 frames) are returned.
 */
internal fun selectFrames(
    all: List<VScreenRecorder.Frame>,
    acts: List<VScreenRecorder.Mark>,
    from: Long,
    to: Long,
    max: Int,
    every: Long? = null,
): SelectedFrames {
    if (all.isEmpty() || max <= 0) return SelectedFrames(emptyList(), 0, 0, 0, 0)
    val sortedActs = acts.sortedBy { it.atMs }
    fun idxAt(t: Long): Int = all.indexOfLast { it.atMs <= t }.coerceAtLeast(0)
    val start = idxAt(from)
    val inside = all.indices.filter { all[it].atMs in (from + 1)..to }
    val clearInside = inside.filter { all[it].clearChange }
    val picks = sortedMapOf<Int, Pick>()
    fun pick(i: Int) = picks.getOrPut(i) { Pick(all[i]) }
    var leftOut = 0
    if (every != null && every > 0) {
        var t = from; var last = -1
        while (t <= to && picks.size < max) { val i = idxAt(t); if (i != last) { pick(i); last = i }; t += every }
    } else {
        val room = (max - 1).coerceAtLeast(1)
        val kept = if (sortedActs.size > room) sortedActs.takeLast(room) else sortedActs
        leftOut = sortedActs.size - kept.size
        pick(start).tag.append(tr(S.vscreen_frames_start))
        kept.forEach { a ->
            val p = pick(idxAt(a.atMs - 1))
            val n = sortedActs.indexOf(a) + 1
            p.marks += a; p.nums += n
            if (p.tag.isNotEmpty()) p.tag.append(" · ")
            p.tag.append(tr(S.vscreen_frames_before_action, n)).append(caption(a.label))
        }
        // 1.3 beta8: the rest of the room goes first to what each action led to (the last frame before the next
        // action, or the end), not to frames spread evenly (a blinking cursor took those places).
        kept.forEachIndexed { k, a ->
            if (picks.size >= max) return@forEachIndexed
            val until = kept.getOrNull(k + 1)?.atMs?.minus(1) ?: to
            val r = idxAt(until)
            if (all[r].atMs > a.atMs) pick(r)
        }
        if (clearInside.isEmpty()) {
            // 2.0.0-beta16 (Item 6): no clear visual change in this stretch; keep only start + end when there are no actions.
            if (kept.isEmpty() && picks.size < max) {
                val end = idxAt(to)
                if (end != start) pick(end).tag.append(tr(S.vscreen_frames_end))
            }
        } else {
            val rest = clearInside.filter { it !in picks }
            val free = max - picks.size
            if (free > 0 && rest.isNotEmpty()) {
                (if (rest.size <= free) rest else (0 until free).map { k -> rest[(k * (rest.size - 1)) / maxOf(1, free - 1)] }).forEach { pick(it) }
            }
        }
    }
    val selected = picks.entries.take(max).map { (idx, p) ->
        if (p.tag.isEmpty()) sortedActs.lastOrNull { it.atMs <= p.frame.atMs }?.let { a ->
            p.tag.append(tr(S.vscreen_frames_action, sortedActs.indexOf(a) + 1, s1(p.frame.atMs - a.atMs)))
        }
        SelectedFrame(idx, p.frame, p.tag.toString(), p.marks.toList(), p.nums.toList())
    }
    val clearCount = clearInside.size
    return SelectedFrames(selected, leftOut, clearCount + 1, sortedActs.size, clearCount)
}

internal fun pickCells(displayId: Int, from: Long, to: Long, max: Int, maxSide: Int, every: Long? = null): Picked {
    val all = VScreenRecorder.frames(displayId, from - 120_000L, to)
    if (all.isEmpty() || max <= 0) return Picked(emptyList(), 0, 0, 0, 0)
    val acts = VScreenRecorder.marks(displayId, from, to).filter { it.kind == "action" }
    val sel = selectFrames(all, acts, from, to, max, every)
    val cells = sel.picks.mapNotNull { p ->
        VScreenRecorder.decode(p.frame, maxSide)?.let { Cell(p.frame.atMs, it, p.tag, p.marks, p.nums) }
    }
    return Picked(cells, sel.leftOut, sel.distinct, sel.actions, sel.clearChanges)
}

private fun spoken(ms: Long) = if (ms >= 60_000 && ms % 60_000 == 0L) "${ms / 60_000} 分钟" else "${(ms + 500) / 1000} 秒"
private fun spokenEn(ms: Long) = if (ms >= 60_000 && ms % 60_000 == 0L) "${ms / 60_000} min" else "${(ms + 500) / 1000} s"
/** An action as a caption: the step without its until (what it waits for is not where it touched). */
private fun caption(label: String) = label.substringBefore(" until ").take(28)
private fun s1(ms: Long) = "${(maxOf(0L, ms) / 100) / 10.0}s"
private fun clock(t: Long) = SimpleDateFormat("HH:mm:ss.S", Locale.ROOT).format(Date(t))

/**
 * Draw [cells] in a grid of [cols] from ([x0],[y0]); every cell [cw]×[ch] plus two caption lines. Marks are in track
 * frame pixels ([fw]×[fh]). Times are said relative to [to].
 */
internal fun drawCells(c: Canvas, x0: Int, y0: Int, cells: List<Cell>, cols: Int, cw: Int, ch: Int, fw: Int, fh: Int, to: Long, first: Int = 1, marks: Boolean = true) {
    val pad = CELL_PAD
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; color = Color.BLACK; isFakeBoldText = true }
    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; color = Color.rgb(70, 70, 90) }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 30, 60); style = Paint.Style.STROKE; strokeWidth = 3f }
    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 30, 60) }
    val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; color = Color.WHITE; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 30, 60) }
    val frameLine = Paint().apply { color = Color.rgb(200, 200, 210); style = Paint.Style.STROKE; strokeWidth = 1f }
    cells.forEachIndexed { i, cell ->
        val b = cell.bmp
        val x = x0 + pad + (i % cols) * (cw + pad); val y = y0 + pad + (i / cols) * (ch + CAPTION + pad)
        c.drawBitmap(b, null, Rect(x, y, x + b.width, y + b.height), Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawRect(x.toFloat(), y.toFloat(), (x + b.width).toFloat(), (y + b.height).toFloat(), frameLine)
        val kx = b.width.toFloat() / maxOf(1, fw); val ky = b.height.toFloat() / maxOf(1, fh)
        if (marks) cell.marks.forEachIndexed { mi, m ->
            val pts = m.pts.map { (x + it[0] * kx) to (y + it[1] * ky) }
            if (pts.isEmpty()) return@forEachIndexed
            if (pts.size == 1) {
                val (px, py) = pts[0]
                c.drawCircle(px, py, 13f, halo); c.drawCircle(px, py, 13f, ring); c.drawCircle(px, py, 5f, fill)
            } else {
                for ((a, bb) in pts.zipWithNext()) { c.drawLine(a.first, a.second, bb.first, bb.second, halo); c.drawLine(a.first, a.second, bb.first, bb.second, ring) }
                c.drawCircle(pts.last().first, pts.last().second, 6f, badge)
            }
            // The action's number in a small badge beside its first point.
            val (bx, by) = pts[0]
            val cx = (bx + 18f).coerceAtMost((x + b.width - 10).toFloat()); val cy = (by - 18f).coerceAtLeast((y + 10).toFloat())
            c.drawCircle(cx, cy, 10f, badge); c.drawText("${cell.nums.getOrNull(mi) ?: ""}", cx, cy + 5f, num)
        }
        c.drawText("${first + i}  ${Report.ago(cell.t, to)}", x.toFloat(), (y + ch + 16).toFloat(), tp)
        if (cell.tag.isNotEmpty()) {
            val n = sp.breakText(cell.tag, true, cw.toFloat(), null)
            c.drawText(cell.tag.take(n), x.toFloat(), (y + ch + 33).toFloat(), sp)
        }
    }
}

internal const val CELL_PAD = 6
internal const val CAPTION = 38

/**
 * 2.0.0-beta16 (Item 6), pure: summary line for `look --last` / `frames`.
 * When [clearChanges] == 0 (no clear visual change, matching [Meanwhile]), uses the same wording and notes start+end.
 */
internal fun formatFramesSummary(
    distinct: Int,
    clearChanges: Int,
    actions: Int,
    shownCount: Int,
    leftOut: Int,
    every: Long?,
): String = buildString {
    if (every == null && clearChanges == 0) {
        val shownPart = if (actions == 0 && shownCount == 2) tr(S.vscreen_frames_start_and_end_2) else tr(S.vscreen_frames_text, shownCount)
        append(tr(S.vscreen_frames_no_clear_change_in_the, actions, shownPart))
        if (leftOut > 0) append(tr(S.vscreen_frames_the_first_actions_are_not, leftOut))
        if (actions > 0) append(tr(S.vscreen_frames_rings_and_numbers_are_where))
        append('\n')
        return@buildString
    }
    append(tr(S.vscreen_frames_different_pictures_and_actions_in, distinct, actions, shownCount))
    if (every != null) append(tr(S.vscreen_frames_one_every_repeats_left_out, s1(every)))
    if (leftOut > 0) append(tr(S.vscreen_frames_the_first_actions_are_not, leftOut))
    append(tr(S.vscreen_frames_rings_and_numbers_are_where_2))
}

/** `frames [--last 30s | --from T [--to T]] [--count N] [--every D] [--big]`. */
internal fun VScreenOffloadHandler.framesCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr(S.vscreen_act_no_session_header_x_vscreen))
    val now = System.currentTimeMillis()
    fun at(v: String, name: String): Long {
        Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?$").matchEntire(v.trim())?.let { m ->
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); set(Calendar.MINUTE, m.groupValues[2].toInt())
                set(Calendar.SECOND, m.groupValues[3].ifEmpty { "0" }.toInt())
                set(Calendar.MILLISECOND, (m.groupValues[4].ifEmpty { "0" } + "00").take(3).toInt())
            }
            return cal.timeInMillis.let { if (it > now + 60_000L) it - 86_400_000L else it }
        }
        return now - parseDuration(v.removePrefix("-"), 0L).also { if (it <= 0L) throw ActionError("INVALID_ARGS", tr(S.vscreen_frames_write_as_how_long_ago, name)) }
    }
    val detail = Detail.of(args)
    val layout = Layout.of(args)
    val marks = args.marks()
    val page = bounded("page", args.getInt("page") ?: 1, 1, 20)
    val count = bounded("count", args.getInt("count") ?: 12, 1, 96)
    val every = args.get("every")?.let { parseDuration(it, 0L).coerceAtLeast(100L) }
    val win = synchronized(lastWindows) { lastWindows[s] }
    val lastArg = args.get("last")
    val (from, to, said) = when {
        lastArg != null && lastArg != "window" -> parseDuration(lastArg, 30_000L).let { ms -> Triple(now - ms, now, tr(S.vscreen_frames_the_last, spoken(ms), spokenEn(ms))) }
        args.get("from") != null -> Triple(at(args.get("from")!!, "from"), args.get("to")?.let { at(it, "to") } ?: now, tr(S.vscreen_frames_the_stretch_asked_for))
        win != null -> Triple(win[0], win[1], tr(S.vscreen_frames_the_meanwhile_of_the_last))
        else -> Triple(now - 30_000L, now, tr(S.vscreen_frames_the_last_30_s_no))
    }
    if (to <= from) throw ActionError("INVALID_ARGS", tr(S.vscreen_frames_to_must_be_later_than))
    return BackgroundOperation.agentAction(s, null, isRead = true, describe = tr(S.vscreen_frames_look_back_at_the_recording)) { d ->
        val kept = VScreenRecorder.frames(d.displayId, 0L, Long.MAX_VALUE)
        val sb = StringBuilder()
        if (kept.isEmpty()) return@agentAction text(tr(S.vscreen_frames_this_screen_has_no_recording))
        val oldest = kept.first().atMs
        val side = detail.cellSide
        val picked = pickCells(d.displayId, maxOf(from, oldest), to, count, side, every)
        if (picked.cells.isEmpty()) return@agentAction text(tr(S.vscreen_frames_no_recording_in_that_stretch))
        sb.append(tr(S.vscreen_frames_looking_back_at_ending, said, clock(from), clock(to), s1(to - from), Report.ago(to, now)))
        if (from < oldest) sb.append(tr(S.vscreen_frames_the_start_is_before_the, clock(oldest)))
        sb.append(formatFramesSummary(picked.distinct, picked.clearChanges, picked.actions, picked.cells.size, picked.leftOut, every))
        val (fw, fh) = VScreenRecorder.frameSize(d.displayId) ?: (d.width to d.height)
        val landscape = fw > fh
        val big = detail == Detail.HIGH
        val per = layout.per ?: if (big) 6 else 12
        val cols0 = layout.cols ?: if (landscape) (if (big) 2 else 3) else (if (big) 3 else 4)
        val data = org.json.JSONArray()
        // 1.4.0-beta2: one look carries at most LookBudget.MAX_PICS pictures / MAX_BYTES; --page N picks up after that.
        val pages = picked.cells.chunked(per)
        val first = (page - 1) * LookBudget.MAX_PICS
        var bytes = 0L; var shown = 0
        if (first >= pages.size) { picked.cells.forEach { it.bmp.recycle() }; throw ActionError("INVALID_ARGS", tr(S.vscreen_frames_this_stretch_has_only_page, (pages.size + LookBudget.MAX_PICS - 1) / LookBudget.MAX_PICS)) }
        pages.forEachIndexed { pi, page ->
            if (pi < first || shown >= LookBudget.MAX_PICS || bytes >= LookBudget.MAX_BYTES) return@forEachIndexed
            val cols = minOf(cols0, page.size)
            val cw = page.maxOf { it.bmp.width }; val ch = page.maxOf { it.bmp.height }
            val rows = (page.size + cols - 1) / cols
            val usedCols = cols
            val out = Bitmap.createBitmap(usedCols * (cw + CELL_PAD) + CELL_PAD, rows * (ch + CAPTION + CELL_PAD) + CELL_PAD, Bitmap.Config.ARGB_8888)
            val c = Canvas(out); c.drawColor(Color.WHITE)
            drawCells(c, 0, 0, page, usedCols, cw, ch, fw, fh, now, first = pi * per + 1, marks = marks)
            val ref = savePic(s, out, "frames", detail.use); out.recycle()
            shown++; bytes += ref?.let { r -> ShotStore.refs(r).firstOrNull()?.let { ShotStore.file(context, it)?.length() } } ?: 0L
            sb.append(tr(S.vscreen_frames_picture, pi + 1)).append(ref ?: tr(S.vscreen_frames_not_saved)).append('\n')
            page.forEachIndexed { k, cell ->
                val n = pi * per + k + 1
                sb.append("  $n  ${clock(cell.t)}  ${Report.ago(cell.t, now)}").append(if (cell.tag.isNotEmpty()) "  ${cell.tag}" else "").append('\n')
                data.put(org.json.JSONObject().put("n", n).put("at_ms", cell.t).put("ago_s", (now - cell.t) / 1000.0).put("tag", cell.tag).put("image", ref ?: org.json.JSONObject.NULL)
                    .put("actions", org.json.JSONArray(cell.marks.map { it.json() })))
            }
        }
        picked.cells.forEach { it.bmp.recycle() }
        val rest = pages.size - first - shown
        if (rest > 0) sb.append(tr(S.vscreen_frames_more_pictures_not_sent_at, rest, LookBudget.MAX_PICS, LookBudget.MAX_BYTES / 1_000_000, page + 1))
        sb.append(tr(S.vscreen_frames_kept_every_frame_of_the, Report.ago(oldest, now)))
        NativeOffloadResult(0, sb.toString() + "\n", null, org.json.JSONObject().put("from_ms", from).put("to_ms", to).put("frames", data))
    }
}
