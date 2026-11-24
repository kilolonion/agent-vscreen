package com.kilon.agentvscreen.host

import android.content.Context
import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.StateFlow

/**
 * 1.2: the user's yes, given on the phone, for the steps an agent must not decide alone: a payment or irreversible
 * button, force-stopping an app, changing system settings, taking the app off the user's main screen, and anything in
 * an app the user set to "ask every time".
 *
 * Before 1.2 the agent added `--confirmed` itself after "asking the user": the tool could not tell a real yes from a
 * model that simply added the flag. Now the question appears on the phone (a heads-up notification with Allow / Deny,
 * and a card in the app), the command waits up to [WAIT_MS], and only the user's tap lets it through. No answer in
 * time is CONFIRM_PENDING: the request stays open for [VALID_MS], so the user can still allow it and the agent's
 * resend goes through. A user who trusts a token can give it a period without questions in the app (per token, with
 * an end time); those passes are written to the audit log like every answer.
 */
object Consent {
    const val WAIT_MS = 60_000L
    const val VALID_MS = 5 * 60_000L
    const val ACTION = UserAsk.ACTION

    /** Questions waiting for the user (1.3: every kind of question, see [UserAsk]). */
    val open: StateFlow<List<UserAsk.Ask>> get() = UserAsk.open

    /**
     * Returns when the user allowed [key]; throws USER_DENIED / CONFIRM_PENDING otherwise. [reusable]: an allow covers
     * the same key of the same token for [VALID_MS] (an app set to "ask every time" is asked once per few minutes, not
     * once per tap); otherwise an allow is used up by one pass.
     */
    fun require(ctx: Context, key: String, title: String, detail: String, reusable: Boolean = false, kind: String = "pay") {
        val call = Call.current()
        val name = call?.tokenName ?: "main"; val session = call?.session ?: "default"
        if (call?.trusted == true) { Audit.consent(name, session, "$title — $detail", "pre-authorized"); return }
        if (!asks(kind, runCatching { com.kilon.agentvscreen.api.ApiConfig.autonomy }.getOrDefault("auto"))) {
            Audit.consent(name, session, "$title — $detail", "automatic"); return
        }
        // 2.0.0-beta6 (6.3): HIGH_RISK goes through StrongConfirm (no notification Allow button, no overlay/ask card).
        StrongConfirm.requireForConsent(ctx, call, key, title, detail, reusable, WAIT_MS, VALID_MS)
    }

    /**
     * 1.2.0-beta2: which kinds of step ask the user at each autonomy level. One table, shown to the user as it is.
     * Kinds: "pay" (a button that moves money), "app" (an app the user set to ask every time), "spend" (a step toward
     * buying), "irreversible" (delete, uninstall …), "system" (force-stop, taking an app off the main screen).
     * The default level lets the agent run hands-off: only money and the user's own per-app rule stop it.
     */
    fun asks(kind: String, level: String): Boolean = when (kind) {
        "pay", "app" -> true
        else -> level == "careful"
    }

    /** The user's tap (notification action or the card in the app). */
    fun answer(ctx: Context, id: Int, allow: Boolean) = UserAsk.answer(ctx, id, allow)

    /** For tests. */
    internal fun reset() = UserAsk.reset()
    internal fun pendingId(key: String): Int? = UserAsk.pendingId(key)
}
