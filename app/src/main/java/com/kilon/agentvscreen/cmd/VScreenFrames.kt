package com.kilon.agentvscreen.cmd

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
internal class Picked(val cells: List<Cell>, val leftOut: Int, val distinct: Int, val actions: Int)

internal fun pickCells(displayId: Int, from: Long, to: Long, max: Int, maxSide: Int, every: Long? = null): Picked {
    val all = VScreenRecorder.frames(displayId, from - 120_000L, to)
    if (all.isEmpty() || max <= 0) return Picked(emptyList(), 0, 0, 0)
    val acts = VScreenRecorder.marks(displayId, from, to).filter { it.kind == "action" }.sortedBy { it.atMs }
    fun idxAt(t: Long): Int = all.indexOfLast { it.atMs <= t }.coerceAtLeast(0)
    val start = idxAt(from)
    val inside = all.indices.filter { all[it].atMs in (from + 1)..to }
    val picks = sortedMapOf<Int, Pick>()
    fun pick(i: Int) = picks.getOrPut(i) { Pick(all[i]) }
    var leftOut = 0
    if (every != null && every > 0) {
        var t = from; var last = -1
        while (t <= to && picks.size < max) { val i = idxAt(t); if (i != last) { pick(i); last = i }; t += every }
    } else {
        val room = (max - 1).coerceAtLeast(1)
        val kept = if (acts.size > room) acts.takeLast(room) else acts
        leftOut = acts.size - kept.size
        pick(start).tag.append(tr("开头", "start"))
        kept.forEach { a ->
            val p = pick(idxAt(a.atMs - 1))
            val n = acts.indexOf(a) + 1
            p.marks += a; p.nums += n
            if (p.tag.isNotEmpty()) p.tag.append(" · ")
            p.tag.append(tr("动作$n 前 ", "before action $n ")).append(caption(a.label))
        }
        // 1.3 beta8: the rest of the room goes first to what each action led to (the last frame before the next
        // action, or the end), not to frames spread evenly (a blinking cursor took those places).
        kept.forEachIndexed { k, a ->
            if (picks.size >= max) return@forEachIndexed
            val until = kept.getOrNull(k + 1)?.atMs?.minus(1) ?: to
            val r = idxAt(until)
            if (all[r].atMs > a.atMs) pick(r)
        }
        val rest = inside.filter { it !in picks }
        val free = max - picks.size
        if (free > 0) (if (rest.size <= free) rest else (0 until free).map { k -> rest[(k * (rest.size - 1)) / maxOf(1, free - 1)] }).forEach { pick(it) }
    }
    // A frame after an action that is not drawn on any cell: say which action it follows.
    val cells = picks.values.take(max).mapNotNull { p ->
        if (p.tag.isEmpty()) acts.lastOrNull { it.atMs <= p.frame.atMs }?.let { a -> p.tag.append(tr("动作${acts.indexOf(a) + 1} 后 +${s1(p.frame.atMs - a.atMs)}", "action ${acts.indexOf(a) + 1} +${s1(p.frame.atMs - a.atMs)}")) }
        VScreenRecorder.decode(p.frame, maxSide)?.let { Cell(p.frame.atMs, it, p.tag.toString(), p.marks, p.nums) }
    }
    return Picked(cells, leftOut, inside.size + 1, acts.size)
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

/** `frames [--last 30s | --from T [--to T]] [--count N] [--every D] [--big]`. */
internal fun VScreenOffloadHandler.framesCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
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
        return now - parseDuration(v.removePrefix("-"), 0L).also { if (it <= 0L) throw ActionError("INVALID_ARGS", tr("--$name 写成多久以前（30s、2m）或钟点（14:03:10）", "write --$name as how long ago (30s, 2m) or a clock time (14:03:10)")) }
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
        lastArg != null && lastArg != "window" -> parseDuration(lastArg, 30_000L).let { ms -> Triple(now - ms, now, tr("最近 ${spoken(ms)}", "the last ${spokenEn(ms)}")) }
        args.get("from") != null -> Triple(at(args.get("from")!!, "from"), args.get("to")?.let { at(it, "to") } ?: now, tr("指定的时间段", "the stretch asked for"))
        win != null -> Triple(win[0], win[1], tr("上一次返回的【这段时间】", "the [Meanwhile] of the last reply"))
        else -> Triple(now - 30_000L, now, tr("最近 30 秒（还没有上一次返回）", "the last 30 s (no earlier reply yet)"))
    }
    if (to <= from) throw ActionError("INVALID_ARGS", tr("--to 要晚于 --from", "--to must be later than --from"))
    return BackgroundOperation.agentAction(s, null, isRead = true, describe = tr("回看录像", "look back at the recording")) { d ->
        val kept = VScreenRecorder.frames(d.displayId, 0L, Long.MAX_VALUE)
        val sb = StringBuilder()
        if (kept.isEmpty()) return@agentAction text(tr("这块副屏还没有录像（画面变了才录；设置里的“后台录制”关着时只留最近 15 秒）", "This screen has no recording yet (frames are kept when the picture changes; with \"Background recording\" off only the last 15 s are kept)"))
        val oldest = kept.first().atMs
        val side = detail.cellSide
        val picked = pickCells(d.displayId, maxOf(from, oldest), to, count, side, every)
        if (picked.cells.isEmpty()) return@agentAction text(tr("这段时间里没有录像", "No recording in that stretch"))
        sb.append(tr("回看 $said：${clock(from)} → ${clock(to)}（${s1(to - from)}，最后一刻是 ${Report.ago(to, now)}）\n", "Looking back at $said: ${clock(from)} → ${clock(to)} (${s1(to - from)}, ending ${Report.ago(to, now)})\n"))
        if (from < oldest) sb.append(tr("开头早于最早的录像（${clock(oldest)}），从那里开始\n", "The start is before the oldest frame kept (${clock(oldest)}); starting there\n"))
        sb.append(tr("这段录到 ${picked.distinct} 张不同的画面、${picked.actions} 个动作（画面变了才录一张，最快约每 0.15 秒一张；更短的闪动可能没录到），给出 ${picked.cells.size} 张",
            "${picked.distinct} different pictures and ${picked.actions} actions in it (a frame is kept when the picture changes, at most about one per 0.15 s; shorter flashes may be missing); showing ${picked.cells.size}"))
        if (every != null) sb.append(tr("，每 ${s1(every)} 取一张（和上一张一样的不重复）", ", one every ${s1(every)} (repeats left out)"))
        if (picked.leftOut > 0) sb.append(tr("；前 ${picked.leftOut} 个动作没放进来，用 --from 往前看或加大 --count", "; the first ${picked.leftOut} actions are not shown: look earlier with --from or raise --count"))
        sb.append(tr("。圈和编号是动作的位置，画在动作之前的那张上；它带来的变化看下一张\n", ". Rings and numbers are where actions touched, drawn on the frame just before each; what it led to is the next frame\n"))
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
        if (first >= pages.size) { picked.cells.forEach { it.bmp.recycle() }; throw ActionError("INVALID_ARGS", tr("这段只有 ${(pages.size + LookBudget.MAX_PICS - 1) / LookBudget.MAX_PICS} 页（--page）", "This stretch has only ${(pages.size + LookBudget.MAX_PICS - 1) / LookBudget.MAX_PICS} page(s) (--page)")) }
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
            sb.append(tr("第 ${pi + 1} 张图 ", "picture ${pi + 1} ")).append(ref ?: tr("（没存下来）", "(not saved)")).append('\n')
            page.forEachIndexed { k, cell ->
                val n = pi * per + k + 1
                sb.append("  $n  ${clock(cell.t)}  ${Report.ago(cell.t, now)}").append(if (cell.tag.isNotEmpty()) "  ${cell.tag}" else "").append('\n')
                data.put(org.json.JSONObject().put("n", n).put("at_ms", cell.t).put("ago_s", (now - cell.t) / 1000.0).put("tag", cell.tag).put("image", ref ?: org.json.JSONObject.NULL)
                    .put("actions", org.json.JSONArray(cell.marks.map { it.json() })))
            }
        }
        picked.cells.forEach { it.bmp.recycle() }
        val rest = pages.size - first - shown
        if (rest > 0) sb.append(tr("还有 $rest 张图没给（每次最多 ${LookBudget.MAX_PICS} 张、约 ${LookBudget.MAX_BYTES / 1_000_000} MB）：同样的参数加 --page ${page + 1} 取下一批，或 --detail low 让每张更小\n",
            "$rest more pictures not sent (at most ${LookBudget.MAX_PICS} pictures, about ${LookBudget.MAX_BYTES / 1_000_000} MB, at a time): the same options with --page ${page + 1} for the next ones, or --detail low for smaller ones\n"))
        sb.append(tr("录像保留：最近 3 分钟每张都留，3–10 分钟前每秒一张；现在最早一张是 ${Report.ago(oldest, now)}。更密：--every 0.3s；更长：--last 2m；某一段：--from 14:03:10 --to 14:03:30；看得更清楚：--detail high；排法：--layout single|strip|3x4",
            "Kept: every frame of the last 3 minutes, one a second for 3–10 minutes ago; the oldest now is ${Report.ago(oldest, now)}. Denser: --every 0.3s; longer: --last 2m; a given stretch: --from 14:03:10 --to 14:03:30; clearer: --detail high; layout: --layout single|strip|3x4"))
        NativeOffloadResult(0, sb.toString() + "\n", null, org.json.JSONObject().put("from_ms", from).put("to_ms", to).put("frames", data))
    }
}
