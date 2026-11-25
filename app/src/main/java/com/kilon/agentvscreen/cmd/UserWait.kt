package com.kilon.agentvscreen.cmd

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
        Kind.CONFIRM -> tr("我需要你在手机上确认一步操作" + (what?.let { "：$it" } ?: "") + "。请$whereZh，按提示确认。好了告诉我一声。",
            "I need you to confirm a step on your phone" + (what?.let { ": $it" } ?: "") + ". Please $whereEn and confirm it. Tell me when it's done.")
        Kind.QUESTION -> tr("我在手机上问了你一个问题。请$whereZh 回答。好了告诉我一声。",
            "I asked you a question on your phone. Please $whereEn to answer it. Tell me when it's done.")
        Kind.LESSON -> tr("我需要你在手机上给我演示一下怎么操作。请$whereZh 开始演示，做完点完成。好了告诉我一声。",
            "I need you to show me how to do this on your phone. Please $whereEn to start, and press done when finished. Tell me when it's done.")
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
        return tr("【等用户】请求已经发到手机上（通知、App 首页“待处理”和屏幕顶部的提示条），现在在等用户。很多人的通知是静音的，用户多半还不知道。" +
                "现在就在你和用户的对话里发这句话，然后停下等用户回话；不要换别的方式绕过，也不要反复重发命令去催：\n  「$say」\n" +
                "用户说好了以后，把同一条命令原样再发一次（$min 分钟内有效）。\n",
            "[Waiting for the user] The request is on the phone (a notification, Waiting for you on the app's home page, and a bar at the top of the screen) and waits for the user. Many people keep notifications silent, so the user most likely hasn't noticed. " +
                "Send this to the user in your conversation now, then stop and wait for their reply; don't try another way around it and don't keep resending the command to push:\n  \"$say\"\n" +
                "Once the user says it's done, send the same command again as it is (valid for $min minutes).\n")
    }
}
