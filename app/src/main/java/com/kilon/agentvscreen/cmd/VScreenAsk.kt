package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Rect
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.host.UserAsk
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// [vscreen] 1.3: `ask` — ask the user on the phone. Above all "show me": the user taps the place on a picture of the
// background screen, and the agent gets the point (and the element there) instead of a description.

internal const val ASK_MAX_WAIT_MS = 300_000L
/** 1.4.0-beta4 (rainbow): a question waits 50 s by default, under the 60-120 s most agents give one tool call, so the
 *  call returns ASK_PENDING before the agent gives up on it; sending the same ask again picks the answer up. */
internal const val ASK_DEFAULT_WAIT_MS = 50_000L

/** The element of [list] under (x, y): the smallest box that holds the point. */
internal fun elementAt(list: List<El>, x: Int, y: Int): El? =
    list.filter { it.box.contains(x, y) }.minByOrNull { it.box.width().toLong() * it.box.height() }

/** The elements whose centre lies in [r]. */
internal fun elementsIn(list: List<El>, r: Rect): List<El> = list.filter { r.contains(it.box.centerX(), it.box.centerY()) }

internal fun VScreenOffloadHandler.askCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val session = sid ?: VScreenOffloadHandler.DEFAULT_SESSION
    val question = args.positional.joinToString(" ").trim()
    if (question.isEmpty()) throw ActionError("INVALID_ARGS", tr("ask \"问题\" [--point|--area|--choose \"甲|乙\"|--text]", "ask \"question\" [--point|--area|--choose \"A|B\"|--text]"))
    if (question.length > 200) throw ActionError("INVALID_ARGS", tr("问题最多 200 字", "a question has at most 200 characters"))
    val choose = args.get("choose")?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }
    val picked = listOfNotNull(
        UserAsk.Kind.POINT.takeIf { args.hasFlag("point") }, UserAsk.Kind.AREA.takeIf { args.hasFlag("area") },
        UserAsk.Kind.TEXT.takeIf { args.hasFlag("text") }, UserAsk.Kind.CHOOSE.takeIf { choose != null })
    if (picked.size > 1) throw ActionError("INVALID_ARGS", tr("--point / --area / --choose / --text 只能选一个", "use only one of --point / --area / --choose / --text"))
    val kind = picked.firstOrNull() ?: UserAsk.Kind.POINT
    if (kind == UserAsk.Kind.CHOOSE && (choose!!.size < 2 || choose.size > 8)) throw ActionError("INVALID_ARGS", tr("--choose 给 2 到 8 个选项，用 | 隔开", "--choose takes 2 to 8 options separated by |"))
    // 1.3 beta2: as a task (--async) nobody holds a connection, so it waits as long as the question is valid (the
    // answer then lands in the task's result). Before, it gave up after the default 60 s and the task kept ASK_PENDING.
    val inTask = Call.current()?.taskId != null
    val wait = if (inTask && args.get("wait") == null) com.kilon.agentvscreen.host.UserAsk.VALID_MS
        else bounded("wait", parseDuration(args.get("wait"), ASK_DEFAULT_WAIT_MS), 0L, if (inTask) com.kilon.agentvscreen.host.UserAsk.VALID_MS else ASK_MAX_WAIT_MS)
    val call = Call.current()
    val key = "ask:$session:${kind.key}:$question:${choose?.joinToString("|").orEmpty()}"

    // The same question again: the answer given meanwhile, or keep waiting on the one already on the phone.
    val old = UserAsk.find(key, call?.tokenId ?: "main")?.takeIf { it.kind == kind }
    val d = BackgroundOperation.previewDisplayFor(session)
    val ask = if (old != null && (old.answer != null || old.live)) old else {
        var pic: File? = null; var w = 0; var h = 0
        if (kind == UserAsk.Kind.POINT || kind == UserAsk.Kind.AREA) {
            val disp = d ?: throw ActionError("NO_SCREEN", tr("--point / --area 要在副屏的画面上回答，现在没有副屏：先 launch，或改用 --choose / --text", "--point / --area are answered on the screen's picture and there is no screen: launch first, or use --choose / --text"))
            disp.requestFreshFrame()
            val frame = disp.awaitFrameAfter(System.currentTimeMillis() - 500, 1_500L) ?: disp.latestFrame()
                ?: throw ActionError("NO_FRAME", tr("副屏还没有画面", "the screen has no picture yet"))
            w = disp.width; h = disp.height
            pic = savePicture(frame)
        }
        UserAsk.create(context, kind, key, question, tr("来自会话 $session", "from session $session"), options = choose.orEmpty(), picture = pic, screenW = w, screenH = h)
    }
    if (ask.answer == null) {
        call?.report(tr("问题已显示在手机上，等用户回答", "The question is on the phone; waiting for the user"))
        UserAsk.await(ask, wait)
    }
    if (call?.cancelled == true && ask.answer == null) { UserAsk.withdraw(context, ask); throw ActionError("CANCELLED", tr("调用方断开了，问题已从手机上撤下", "The caller went away; the question was taken off the phone")) }
    when (ask.answer) {
        null -> {
            val leftSec = ((ask.at + ask.validMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L)
            val askData = JSONObject().put(
                "ask",
                JSONObject().put("id", ask.id).put("kind", kind.key).put("expires_in_s", leftSec),
            )
            throw ActionError(
                "ASK_PENDING",
                tr("问题已显示在手机上（通知和 Agent-VScreen 首页），用户 ${wait / 1000} 秒内没回答。告诉用户去手机上回答；之后把同一条 ask 原样再发一次就能拿到答案（${ask.validMs / 60_000} 分钟内有效）",
                    "The question is on the phone (notification and the Agent-VScreen home page); the user did not answer within ${wait / 1000} s. Tell the user to answer it on the phone; then send the same ask again to get the answer (valid for ${ask.validMs / 60_000} minutes)"),
                data = askData,
            )
        }
        false -> { UserAsk.markUsed(ask); UserAsk.endedWhy(ask)?.let { why -> throw ActionError("ASK_DECLINED", tr("副屏已经关闭（$why），这个要在副屏上回答的问题结束了：$question", "The background screen was closed ($why), so this question about it ended: $question")) }
            throw ActionError("ASK_DECLINED", tr("用户选择不回答：$question", "The user chose not to answer: $question")) }
        true -> UserAsk.markUsed(ask)
    }
    val reply = ask.reply ?: JSONObject()
    feedbackOf(reply)?.let { throw it }
    val els = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[session] }
        ?.takeIf { d != null && it.generation == d.generation }?.list.orEmpty()
    fun el(e: El) = JSONObject().put("n", e.n).put("text", e.text).put("x", e.box.centerX()).put("y", e.box.centerY())
    val data = JSONObject().put("question", question).put("kind", kind.key).put("answer", reply)
    val said: String = when (kind) {
        UserAsk.Kind.POINT -> {
            val x = reply.optInt("x"); val y = reply.optInt("y")
            val e = elementAt(els, x, y)
            e?.let { data.put("element", el(it)) }
            tr("用户点了 ($x, $y)", "The user tapped ($x, $y)") + (e?.let { tr("，在 #${it.n}「${it.text.take(40)}」上", ", on #${it.n} \"${it.text.take(40)}\"") }
                ?: if (els.isEmpty()) tr("（还没有元素列表：act 看一眼就能对上编号）", " (no element list yet: look with act to match numbers)") else "")
        }
        UserAsk.Kind.AREA -> {
            val r = Rect(reply.optInt("x1"), reply.optInt("y1"), reply.optInt("x2"), reply.optInt("y2"))
            val inside = elementsIn(els, r)
            data.put("elements", JSONArray(inside.map { el(it) }))
            tr("用户框了 ${r.left},${r.top},${r.right},${r.bottom}", "The user boxed ${r.left},${r.top},${r.right},${r.bottom}") +
                if (inside.isNotEmpty()) tr("，框里有 ", ", holding ") + inside.take(12).joinToString(" ") { "#${it.n}「${it.text.take(20)}」" } else ""
        }
        UserAsk.Kind.CHOOSE -> tr("用户选了 ${reply.optInt("index")}：${reply.optString("choice")}", "The user chose ${reply.optInt("index")}: ${reply.optString("choice")}")
        else -> tr("用户回答：${reply.optString("text")}", "The user answered: ${reply.optString("text")}")
    }
    // 1.4.0-beta4: the answer is marked on the live screen; the user may have scrolled to the target first.
    val note = if (reply.optBoolean("moved")) tr("。用户先滑动了画面才标出来，坐标对的是现在的画面，不是你提问时那一屏", ". The user scrolled the screen before marking it: the point is on the screen now, not the one you asked about") else ""
    data.put("message", said + note)
    return ok(args, data)
}

/** 1.4.0-beta4 (rainbow): the user answered "说一句" instead (the question, the page or the lesson was wrong). */
internal fun feedbackOf(reply: JSONObject): ActionError? = reply.takeIf { it.optString("kind") == "feedback" }?.let { r ->
    val t = r.optString("text").trim()
    ActionError("USER_FEEDBACK", tr("用户没有照问的方式回答，说：「$t」", "The user did not answer as asked and said: \"$t\""), JSONObject().put("feedback", t))
}

private fun VScreenOffloadHandler.savePicture(src: Bitmap): File {
    val dir = File(context.cacheDir, "ask").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 30 * 60_000L }?.forEach { it.delete() }
    return com.kilon.agentvscreen.core.Pictures.write(src, File(dir, "ask_${System.currentTimeMillis()}.jpg"), com.kilon.agentvscreen.core.Pictures.Use.ASK)
}
