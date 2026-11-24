package com.kilon.agentvscreen.host

import android.content.Context
import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.StateFlow

/**
 * 1.2: the user's yes, given on the phone, for the steps an agent must not decide alone: a payment or irreversible
 * button, force-stopping an app, changing system settings, taking the app off the user's main screen, and anything in
 * an app the user set to "confirm every time".
 *
 * Before 1.2 the agent added `--confirmed` itself after "asking the user": the tool could not tell a real yes from a
 * model that simply added the flag. Now the question appears on the phone (a heads-up notification with Allow / Deny,
 * and a card in the app), the command waits up to [WAIT_MS], and only the user's tap lets it through. No answer in
 * time is CONFIRM_PENDING: the request stays open for [VALID_MS], so the user can still allow it and the agent's
 * resend goes through. Since 2.0.0-beta8 what asks depends on the agent's level ([asks]), and an allow can cover the
 * rest of a run or the day ([StrongConfirm.Range]); every pass is written to the audit log like every answer.
 */
object Consent {
    const val WAIT_MS = 60_000L
    const val VALID_MS = 5 * 60_000L
    const val ACTION = UserAsk.ACTION

    /** Questions waiting for the user (1.3: every kind of question, see [UserAsk]). */
    val open: StateFlow<List<UserAsk.Ask>> get() = UserAsk.open

    /**
     * 2.0.0-beta8 (feedback 4): three classes by "can it be undone afterwards" ([com.kilon.agentvscreen.api.Policy]
     * rows), plus the user's own per-app rule. money: pay, place an order, transfer, top up. destroy: uninstall, clear
     * an app's data, delete for good, close an account, factory reset, format. caution: an ordinary delete, a step
     * toward buying, force-stop, taking the app the user is using to the background. app: an app set to "confirm every time".
     */
    const val MONEY = com.kilon.agentvscreen.api.Policy.MONEY
    const val DESTROY = com.kilon.agentvscreen.api.Policy.DESTROY
    const val CAUTION = com.kilon.agentvscreen.api.Policy.CAUTION
    const val APP = "app"

    /**
     * Returns when the step may go on; throws USER_DENIED / CONFIRM_PENDING / SCOPE_DENIED otherwise. How a [kind] is
     * handled is this agent's own table ([com.kilon.agentvscreen.api.Tokens.Token.policy]); an app the user set to
     * "confirm every time" always asks. [pkg]: the app the step is in; an allow for "this run" or "today" covers the same
     * [kind] in the same app for the same agent.
     */
    fun require(ctx: Context, key: String, title: String, detail: String, kind: String = MONEY, pkg: String = "*") {
        val call = Call.current()
        val name = call?.tokenName ?: "main"; val session = call?.session ?: "default"
        val act = actFor(kind, call)
        if (act == com.kilon.agentvscreen.api.Policy.Act.DENY) {
            Audit.consent(name, session, "$title — $detail", "not allowed")
            val row = com.kilon.agentvscreen.api.Policy.row(kind)?.title ?: kind
            throw ActionError("SCOPE_DENIED", tr("用户给这个 agent 定的规则里，“$row”是不允许：$title 没有做。不要换别的办法绕过；需要的话请用户在 Agent-VScreen › 接入 › 这个 agent 里改",
                "The user's rules for this agent say \"$row\" is not allowed: $title was not done. Don't work around it; if needed, ask the user to change it in Agent-VScreen › Connect › this agent"))
        }
        // "Payments allowed for a while" (the agent page) passes every class; the user's own app rule still asks.
        if (call?.trusted == true && kind != APP) { Audit.consent(name, session, "$title — $detail", "pre-authorized"); return }
        if (act == com.kilon.agentvscreen.api.Policy.Act.ALLOW) {
            Audit.consent(name, session, "$title — $detail", "automatic"); return
        }
        StrongConfirm.requireForConsent(ctx, call, key, title, detail, kind, pkg, WAIT_MS, VALID_MS)
    }

    /** How [kind] is handled for the agent behind [call] (an app rule always asks). */
    fun actFor(kind: String, call: Call?): com.kilon.agentvscreen.api.Policy.Act {
        if (kind == APP) return com.kilon.agentvscreen.api.Policy.Act.ASK
        val t = com.kilon.agentvscreen.api.Tokens.byId(call?.tokenId ?: com.kilon.agentvscreen.api.Tokens.MAIN)
        return t?.act(kind) ?: com.kilon.agentvscreen.api.Policy.table(com.kilon.agentvscreen.api.Tokens.FREE, emptyMap())[kind] ?: com.kilon.agentvscreen.api.Policy.Act.ASK
    }

    /** Feedback 3: money is allowed one step at a time; the other classes can be allowed for this run (and the milder ones, today). */
    fun rangesFor(kind: String): List<StrongConfirm.Range> = when (kind) {
        MONEY -> listOf(StrongConfirm.Range.ONCE)
        DESTROY -> listOf(StrongConfirm.Range.ONCE, StrongConfirm.Range.ROUND)
        else -> listOf(StrongConfirm.Range.ONCE, StrongConfirm.Range.ROUND, StrongConfirm.Range.TODAY)
    }

    /** The user's tap (notification action or the card in the app). */
    fun answer(ctx: Context, id: Int, allow: Boolean) = UserAsk.answer(ctx, id, allow)

    /** For tests. */
    internal fun reset() = UserAsk.reset()
    internal fun pendingId(key: String): Int? = UserAsk.pendingId(key)
}
