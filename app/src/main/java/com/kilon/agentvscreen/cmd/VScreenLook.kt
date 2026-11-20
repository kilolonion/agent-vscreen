package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.tr

// [vscreen] 1.4.0-beta2: one image request.
//
// Pictures used to come from seven places, each with its own size, quality, count and layout, and options that meant
// the same thing under different names (frames --big, zoom --scale, nothing at all for the reply picture). Every fix
// in one place drifted from the others. Now a picture is always a request with the same few parameters:
//   what     nothing = the screen now; x1,y1,x2,y2 = a region; --last / --from / --to = a stretch of the recording
//   how many --count, --every (a stretch)
//   detail   --detail low | normal | high (size and JPEG quality together; frames --big = high)
//   layout   --layout auto | single | strip | grid CxR (a stretch: how cells are put on pictures)
//   marks    --marks on | off (coordinate ruler, action rings)
//   page     --page N (a stretch that needs more pictures than one reply carries)
// and one budget: a reply that comes by itself carries one compact picture, only when something changed (api/
// ImagePolicy); a `look` carries what it asked for, up to [LookBudget.MAX_PICS] pictures / [LookBudget.MAX_BYTES],
// and says what is left and how to get it. `zoom` and `frames` are the same request written the old way.

/** Size and quality together: what "clearer" means for every kind of picture. */
internal enum class Detail(val key: String, val use: com.kilon.agentvscreen.core.Pictures.Use, val cellSide: Int, val zoomSide: Int) {
    LOW("low", com.kilon.agentvscreen.core.Pictures.Use.LOOK_LOW, 240, 768),
    NORMAL("normal", com.kilon.agentvscreen.core.Pictures.Use.LOOK, 360, 1280),
    HIGH("high", com.kilon.agentvscreen.core.Pictures.Use.LOOK_HIGH, 560, 2048);
    val quality: Int get() = use.quality
    val nowSide: Int get() = use.side
    companion object {
        fun of(args: OffloadArgs): Detail = args.get("detail")?.let { v -> entries.firstOrNull { it.key == v.lowercase() }
            ?: throw ActionError("INVALID_ARGS", tr("--detail 只能是 low、normal 或 high", "--detail is low, normal or high")) }
            ?: if (args.hasFlag("big")) HIGH else NORMAL
    }
}

/** How a stretch's cells are put on pictures: [per] cells a picture, [cols] columns (null = by orientation). */
internal class Layout(val per: Int?, val cols: Int?) {
    companion object {
        fun of(args: OffloadArgs): Layout {
            // 1.4.0-beta3: one token (`3x4`). beta2 documented `grid 3x4`, which a shell splits in two: `3x4` then
            // landed among the positionals and was refused as a region.
            val v = args.get("layout")?.lowercase()?.replace(" ", "")?.trim() ?: return Layout(null, null)
            Regex("^(?:grid)?(\\d{1,2})[x×*](\\d{1,2})$").matchEntire(v)?.let { m ->
                val c = m.groupValues[1].toInt().coerceIn(1, 8); val r = m.groupValues[2].toInt().coerceIn(1, 8)
                return Layout(c * r, c)
            }
            return when (v) {
                "auto" -> Layout(null, null)
                "single" -> Layout(1, 1)
                "strip" -> Layout(6, 6)
                else -> throw ActionError("INVALID_ARGS", tr("--layout 只能是 auto、single、strip 或 3x4", "--layout is auto, single, strip or 3x4"))
            }
        }
    }
}

private val REGION = Regex("^-?\\d+(\\.\\d+)?(,-?\\d+(\\.\\d+)?){3}$")

internal object LookBudget {
    const val MAX_PICS = 8
    const val MAX_BYTES = 3_000_000L
}

internal fun OffloadArgs.marks(): Boolean = when (get("marks")?.lowercase()) { null, "on", "true", "1" -> true; "off", "false", "0" -> false
    else -> throw ActionError("INVALID_ARGS", tr("--marks 只能是 on 或 off", "--marks is on or off")) }

/** `look [x1,y1,x2,y2] [--last|--from|--to|--count|--every|--page] [--detail] [--layout] [--marks]`. [as]: zoom or frames when called by their old names. */
internal fun VScreenOffloadHandler.lookCmd(sid: String?, args: OffloadArgs, `as`: String = "look"): NativeOffloadResult {
    val region = args.positional.firstOrNull()
    // A positional that is not a region is named as such, not mistaken for one (beta2: `--layout grid 3x4`).
    if (region != null && !REGION.matches(region.replace(" ", "")))
        throw ActionError("INVALID_ARGS", tr("看不懂“$region”：区域写成 x1,y1,x2,y2；排法写成 --layout 3x4", "Cannot read \"$region\": a region is x1,y1,x2,y2; a layout is --layout 3x4"))
    BackgroundOperation.requireNotParked(sid)
    val stretch = `as` == "frames" || listOf("last", "from", "to", "count", "every", "page").any { args.get(it) != null }
    return when {
        region != null && stretch -> throw ActionError("INVALID_ARGS", tr("一次只看一样：区域（x1,y1,x2,y2）或一段时间（--last 等），不能同时", "One thing at a time: a region (x1,y1,x2,y2) or a stretch (--last etc.), not both"))
        // 1.4.0-beta5: a scale alone means the whole screen at that scale (beta4 refused `zoom --scale 1.5`).
        region != null || args.get("scale") != null -> zoomCmd(sid, args)
        `as` == "zoom" -> throw ActionError("INVALID_ARGS", "zoom x1,y1,x2,y2 [--detail high]")
        stretch -> framesCmd(sid, args)
        else -> lookNow(sid, args)
    }
}

/** The screen now, as asked (detail, ruler on or off), with no stretch strip. */
private fun VScreenOffloadHandler.lookNow(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val detail = Detail.of(args); val marks = args.marks()
    return BackgroundOperation.agentAction(s, null, isRead = true, describe = tr("看现在的画面", "look at the screen")) { d ->
        val frame = freshFrameOf(d)
        val ref = picture(s, d, frame, null, "look", emptyList(), detail, marks)
        text(tr("现在的画面（${detail.key}）：", "The screen now (${detail.key}): ") + (ref ?: tr("（图没存下来）", "(not saved)")) +
            tr("\n更清楚：--detail high；某一块：look x1,y1,x2,y2；刚才的过程：look --last 30s", "\nClearer: --detail high; a region: look x1,y1,x2,y2; what just happened: look --last 30s"))
    }
}
