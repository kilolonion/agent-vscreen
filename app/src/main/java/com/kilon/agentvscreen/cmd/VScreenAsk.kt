package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
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
    if (question.isEmpty()) throw ActionError("INVALID_ARGS", tr(S.vscreen_ask_ask_question_point_area_choose))
    if (question.length > 200) throw ActionError("INVALID_ARGS", tr(S.vscreen_ask_a_question_has_at_most))
    val choose = args.get("choose")?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }
    val picked = listOfNotNull(
        UserAsk.Kind.POINT.takeIf { args.hasFlag("point") }, UserAsk.Kind.AREA.takeIf { args.hasFlag("area") },
        UserAsk.Kind.TEXT.takeIf { args.hasFlag("text") }, UserAsk.Kind.CHOOSE.takeIf { choose != null })
    if (picked.size > 1) throw ActionError("INVALID_ARGS", tr(S.vscreen_ask_use_only_one_of_point))
    val kind = picked.firstOrNull() ?: UserAsk.Kind.POINT
    if (kind == UserAsk.Kind.CHOOSE && (choose!!.size < 2 || choose.size > 8)) throw ActionError("INVALID_ARGS", tr(S.vscreen_ask_choose_takes_2_to_8))
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
            val disp = d ?: throw ActionError("NO_SCREEN", tr(S.vscreen_ask_point_area_are_answered_on))
            disp.requestFreshFrame()
            val frame = disp.awaitFrameAfter(System.currentTimeMillis() - 500, 1_500L) ?: disp.latestFrame()
                ?: throw ActionError("NO_FRAME", tr(S.vscreen_ask_the_screen_has_no_picture))
            w = disp.width; h = disp.height
            pic = savePicture(frame)
        }
        UserAsk.create(context, kind, key, question, tr(S.vscreen_ask_from_session, session), options = choose.orEmpty(), picture = pic, screenW = w, screenH = h)
    }
    if (ask.answer == null) {
        call?.report(tr(S.vscreen_ask_the_question_is_on_the))
        UserAsk.await(ask, wait)
    }
    if (call?.cancelled == true && ask.answer == null) { UserAsk.withdraw(context, ask); throw ActionError("CANCELLED", tr(S.vscreen_ask_the_caller_went_away_the)) }
    when (ask.answer) {
        null -> {
            val leftSec = ((ask.at + ask.validMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L)
            val askData = JSONObject().put(
                "ask",
                JSONObject().put("id", ask.id).put("kind", kind.key).put("expires_in_s", leftSec),
            )
            throw ActionError(
                "ASK_PENDING",
                tr(S.vscreen_ask_the_question_is_on_the_2, wait / 1000, ask.validMs / 60_000),
                data = askData,
            )
        }
        false -> { UserAsk.markUsed(ask); UserAsk.endedWhy(ask)?.let { why -> throw ActionError("ASK_DECLINED", tr(S.vscreen_ask_the_background_screen_was_closed, why, question)) }
            throw ActionError("ASK_DECLINED", tr(S.vscreen_ask_the_user_chose_not_to, question)) }
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
            tr(S.vscreen_ask_the_user_tapped, x, y) + (e?.let { tr(S.vscreen_ask_on, it.n, it.text.take(40)) }
                ?: if (els.isEmpty()) tr(S.vscreen_ask_no_element_list_yet_look) else "")
        }
        UserAsk.Kind.AREA -> {
            val r = Rect(reply.optInt("x1"), reply.optInt("y1"), reply.optInt("x2"), reply.optInt("y2"))
            val inside = elementsIn(els, r)
            data.put("elements", JSONArray(inside.map { el(it) }))
            tr(S.vscreen_ask_the_user_boxed, r.left, r.top, r.right, r.bottom) +
                if (inside.isNotEmpty()) tr(S.vscreen_ask_holding) + inside.take(12).joinToString(" ") { "#${it.n}「${it.text.take(20)}」" } else ""
        }
        UserAsk.Kind.CHOOSE -> tr(S.vscreen_ask_the_user_chose, reply.optInt("index"), reply.optString("choice"))
        else -> tr(S.vscreen_ask_the_user_answered, reply.optString("text"))
    }
    // 1.4.0-beta4: the answer is marked on the live screen; the user may have scrolled to the target first.
    val note = if (reply.optBoolean("moved")) tr(S.vscreen_ask_the_user_scrolled_the_screen) else ""
    data.put("message", said + note)
    return ok(args, data)
}

/** 1.4.0-beta4 (rainbow): the user answered "说一句" instead (the question, the page or the lesson was wrong). */
internal fun feedbackOf(reply: JSONObject): ActionError? = reply.takeIf { it.optString("kind") == "feedback" }?.let { r ->
    val t = r.optString("text").trim()
    ActionError("USER_FEEDBACK", tr(S.vscreen_ask_the_user_did_not_answer, t), JSONObject().put("feedback", t))
}

private fun VScreenOffloadHandler.savePicture(src: Bitmap): File {
    val dir = File(context.cacheDir, "ask").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 30 * 60_000L }?.forEach { it.delete() }
    return com.kilon.agentvscreen.core.Pictures.write(src, File(dir, "ask_${System.currentTimeMillis()}.jpg"), com.kilon.agentvscreen.core.Pictures.Use.ASK)
}
