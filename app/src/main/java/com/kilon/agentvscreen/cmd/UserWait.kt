package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.trPair
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2.0.0-beta13 (rainbow: many people keep notifications silent, so a request sits unseen on the phone while the agent
 * spends its time getting around it). Every reply whose code means "a request is now on the phone and waits for the
 * user" ([Codes.Next.PHONE]) leaves here with the same addition, whatever command produced it:
 *  - a [block] in the text saying the agent is waiting on the user, that the user may not have noticed, and the
 *    sentence to send the user in the agent's own chat right now, naming where to tap on the phone;
 *  - `user_action` in the data with the same facts for programs (kind, where it shows, tell_user, then, expiry).
 * One place, so a new kind of request gets it by having its code in the table, not by remembering to word it.
 */
internal object UserWait {
    enum class Kind(val key: String) { CONFIRM("strong_confirm"), QUESTION("question"), LESSON("lesson") }

    /** Where every waiting request shows on the phone (host.Needs, host.WaitingPill, the notification). */
    val SHOWN_AT = listOf("notification", "home_waiting_list", "top_pill")

    fun kindOf(code: String?, command: String?): Kind? {
        if (Codes.of(code)?.next != Codes.Next.PHONE) return null
        return when {
            code == "ASK_PENDING" && command == "teach" -> Kind.LESSON
            code == "ASK_PENDING" -> Kind.QUESTION
            else -> Kind.CONFIRM
        }
    }

    /** Where to tap, the same for every kind: the pill or the app's home page, then the item. */
    private val whereZh = "打开 Agent-VScreen（或点手机屏幕顶部紫色的“Agent 在等你处理”提示条），在首页“待处理”里点这一项"
    private val whereEn = "open Agent-VScreen (or tap the purple \"An agent is waiting for you\" bar at the top of the phone's screen) and tap this item under Waiting for you on its home page"

    fun tellUser(kind: Kind, what: String?): String = when (kind) {
        Kind.CONFIRM -> trPair("我需要你在手机上确认一步操作" + (what?.let { "：$it" } ?: "") + "。请$whereZh，按提示确认。好了告诉我一声。",
            "I need you to confirm a step on your phone" + (what?.let { ": $it" } ?: "") + ". Please $whereEn and confirm it. Tell me when it's done.")
        Kind.QUESTION -> tr(S.user_wait_i_asked_you_a_question, whereZh, whereEn)
        Kind.LESSON -> tr(S.user_wait_i_need_you_to_show, whereZh, whereEn)
    }

    /** The waiting strong confirmation of the calling token, if any (what it asks, seconds left). */
    private fun confirmOf(tokenId: String?): Pair<String, Long>? {
        val now = System.currentTimeMillis()
        val r = com.kilon.agentvscreen.host.StrongConfirm.pending.value.lastOrNull { tokenId == null || it.tokenId == tokenId } ?: return null
        return r.commandName to ((r.at + r.validMs - now) / 1000L).coerceAtLeast(1L)
    }

    /**
     * 2.0.0-beta15 (test W5: a teach reply said "valid for 15 minutes" and then "valid for 10 minutes"): the time left
     * of the caller's own newest open request on the phone, so the waiting block and expires_in_s say what the request
     * itself says (a demonstration waits 15 minutes, a question 10).
     */
    private fun askLeftOf(tokenId: String?, kind: Kind): Long? {
        val now = System.currentTimeMillis()
        val teach = kind == Kind.LESSON
        return com.kilon.agentvscreen.host.UserAsk.open.value
            .filter { it.live && (tokenId == null || it.token == tokenId) && (it.kind == com.kilon.agentvscreen.host.UserAsk.Kind.TEACH) == teach }
            .maxByOrNull { it.at }?.let { ((it.at + it.validMs - now) / 1000L).coerceAtLeast(1L) }
    }

    /** [r] with the waiting block and `user_action` added when its code waits on the user's phone; else [r] as it is. */
    fun attach(r: NativeOffloadResult, command: String?, tokenId: String?): NativeOffloadResult {
        if (r.exitCode == 0) return r
        val kind = kindOf(r.codeOrDefault, command) ?: return r
        // A refusal that never put a request on the phone (a rule may not tap a pay button) has nothing to wait for.
        val confirm = if (kind == Kind.CONFIRM) confirmOf(tokenId) ?: return r else null
        val leftS = confirm?.second ?: r.data?.optJSONObject("confirm")?.optLong("expires_in_s")?.takeIf { it > 0 }
            ?: askLeftOf(tokenId, kind) ?: (com.kilon.agentvscreen.host.UserAsk.VALID_MS / 1000L)
        val say = tellUser(kind, confirm?.first)
        val text = block(say, leftS)
        val data = (r.data ?: JSONObject()).put("user_action", JSONObject()
            .put("waiting", "user_on_phone").put("kind", kind.key)
            .put("shown_at", JSONArray(SHOWN_AT)).put("may_be_unseen", true)
            .put("tell_user", say).put("then", "resend_same_command_after_user_says_done")
            .put("expires_in_s", leftS))
        return r.copy(output = r.output.trimEnd() + "\n" + text, data = data)
    }

    fun block(say: String, leftS: Long): String {
        val min = (leftS + 59) / 60
        return tr(S.user_wait_waiting_for_the_user_the, say, min)
    }
}
