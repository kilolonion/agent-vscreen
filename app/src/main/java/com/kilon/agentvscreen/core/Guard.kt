package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.tr

/**
 * 1.4.0-beta2: the one place that says whether an agent may do something to an app. Before, the user's app rules were
 * checked in four places (the command gate, each act step, the end of a stretch, launch and handoff in), each with its
 * own reading of "blocked" and "read-only", and "the user has this app now" (VScreenOwnership) was a fifth. Callers
 * name what they are about to do; [check] answers Allow, Deny (code + words) or Ask (confirm on the phone first).
 */
object Guard {
    enum class Act {
        /** Read or show the screen (elements, pictures). */
        READ,
        /** Open an app on the background screen (launch). */
        OPEN,
        /** Take an app from the main screen (handoff in). */
        TAKE,
        /** Scroll, or a key other than back. */
        SCROLL,
        /** key back: the way out of any app. */
        BACK,
        /** Tap, type, swipe, drag: a change the app sees as input. */
        INPUT,
    }

    sealed class Verdict {
        object Allow : Verdict()
        class Deny(val code: String, val text: String) : Verdict()
        /** The user confirms on the phone first ([key] for Consent). */
        class Ask(val key: String, val pkg: String) : Verdict()
    }

    private val hint get() = tr(S.guard_the_user_set_this_in)

    /** May [session] do [act] to [pkg] (the app it targets; for READ / SCROLL / BACK / INPUT the app on the screen)? */
    fun check(act: Act, pkg: String?, session: String? = null, asked: Boolean = false): Verdict {
        // The app this session was working on is with the user on the main screen: whatever is under it now is not
        // what the agent planned for. Reading is fine; changing anything is not.
        if (act == Act.INPUT || act == Act.SCROLL || act == Act.BACK) VScreenOwnership.blocking(session)?.let { g ->
            val app = BackgroundOperation.appContext?.let { VScreenOwnership.label(it, g.pkg) } ?: g.pkg
            return Verdict.Deny("APP_WITH_USER", tr(S.guard_is_with_the_user_now, app, when (g.why) { "user" -> "用户在主屏打开了它"; "ours" -> "你的上一步让它跑到了主屏，handoff in 可以拿回"; else -> "你把它交给了用户" }, when (g.why) { "user" -> "the user opened it on the main screen"; "ours" -> "your last step moved it to the main screen; handoff in takes it back"; else -> "you handed it to the user" }))
        }
        // 1.4.0-beta6 (test: `launch 计算器 --asked` refused): once the user agreed (--asked), a separate copy may open.
        if (act == Act.OPEN && pkg != null && !asked) VScreenOwnership.givenFor(pkg)?.let {
            return Verdict.Deny("APP_WITH_USER", tr(S.guard_is_with_the_user_now_2, pkg))
        }
        val rule = VScreenStore.ruleFor(pkg ?: return Verdict.Allow) ?: return Verdict.Allow
        return when (rule) {
            VScreenStore.Rule.BLOCK -> when (act) {
                Act.BACK -> Verdict.Allow
                Act.OPEN -> Verdict.Deny("APP_BLOCKED", tr(S.guard_the_user_blocked_background_operation, pkg))
                Act.TAKE -> Verdict.Deny("APP_BLOCKED", tr(S.guard_the_user_blocked_background_operation_2, pkg))
                else -> Verdict.Deny("APP_BLOCKED", tr(S.guard_the_screen_shows_which_the, pkg, hint))
            }
            VScreenStore.Rule.READONLY -> if (act == Act.INPUT) Verdict.Deny("APP_READ_ONLY", tr(S.guard_the_screen_shows_which_the_2, pkg, hint)) else Verdict.Allow
            VScreenStore.Rule.ASK -> if (act == Act.INPUT) Verdict.Ask("app:$pkg", pkg) else Verdict.Allow
        }
    }

    /** [check] for callers that refuse by throwing (commands). Ask is left to the caller (it knows what to show). */
    fun require(act: Act, pkg: String?, session: String? = null, asked: Boolean = false): Verdict {
        val v = check(act, pkg, session, asked)
        if (v is Verdict.Deny) throw BackgroundOperation.ActionError(v.code, v.text)
        return v
    }
}
