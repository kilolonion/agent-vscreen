package com.kilon.agentvscreen.core

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

    private val hint get() = tr("这是用户在 Agent-VScreen › 设置 › 应用规则 里定的，不要绕过（也不要从别的入口进同一个应用）",
        "The user set this in Agent-VScreen › Settings › App rules; don't work around it (and don't reach the same app through another entry)")

    /** May [session] do [act] to [pkg] (the app it targets; for READ / SCROLL / BACK / INPUT the app on the screen)? */
    fun check(act: Act, pkg: String?, session: String? = null, asked: Boolean = false): Verdict {
        // The app this session was working on is with the user on the main screen: whatever is under it now is not
        // what the agent planned for. Reading is fine; changing anything is not.
        if (act == Act.INPUT || act == Act.SCROLL || act == Act.BACK) VScreenOwnership.blocking(session)?.let { g ->
            val app = BackgroundOperation.appContext?.let { VScreenOwnership.label(it, g.pkg) } ?: g.pkg
            return Verdict.Deny("APP_WITH_USER", tr("$app 现在在用户手上（${when (g.why) { "user" -> "用户在主屏打开了它"; "ours" -> "你的上一步让它跑到了主屏，handoff in 可以拿回"; else -> "你把它交给了用户" }}），副屏上已经没有它，动作没有执行。等用户交回（vscreen events --wait 收 handback），或 launch 别的应用去做别的事",
                "$app is with the user now (${when (g.why) { "user" -> "the user opened it on the main screen"; "ours" -> "your last step moved it to the main screen; handoff in takes it back"; else -> "you handed it to the user" }}); it is no longer on the background screen and the action was not done. Wait for the hand-back (vscreen events --wait for handback), or launch another app to do something else"))
        }
        // 1.4.0-beta6 (test: `launch 计算器 --asked` refused): once the user agreed (--asked), a separate copy may open.
        if (act == Act.OPEN && pkg != null && !asked) VScreenOwnership.givenFor(pkg)?.let {
            return Verdict.Deny("APP_WITH_USER", tr("$pkg 现在在用户手上（主屏），还没交回。等 handback 事件（vscreen events --wait）；或先问用户（vscreen ask），同意后 launch $pkg --asked 另开一份，或 handoff in 接着他的页面",
                "$pkg is with the user now (main screen) and not handed back yet. Wait for the handback event (vscreen events --wait); or ask the user first (vscreen ask) and, once they agree, launch $pkg --asked for a separate copy or handoff in to carry on from their page"))
        }
        val rule = VScreenStore.ruleFor(pkg ?: return Verdict.Allow) ?: return Verdict.Allow
        return when (rule) {
            VScreenStore.Rule.BLOCK -> when (act) {
                Act.BACK -> Verdict.Allow
                Act.OPEN -> Verdict.Deny("APP_BLOCKED", tr("用户禁止后台操作 $pkg（Agent-VScreen › 设置 › 应用规则），不要绕过", "The user blocked background operation of $pkg (Agent-VScreen › Settings › App rules); don't work around it"))
                Act.TAKE -> Verdict.Deny("APP_BLOCKED", tr("用户禁止后台操作 $pkg（Agent-VScreen › 设置 › 应用规则），不能接手它", "The user blocked background operation of $pkg (Agent-VScreen › Settings › App rules); it can't be taken over"))
                else -> Verdict.Deny("APP_BLOCKED", tr("副屏上是 $pkg，用户禁止后台操作它（不能读屏也不能操作）：只能 key back 离开或 stop。$hint",
                    "The screen shows $pkg, which the user blocked from background operation (no reading, no actions): only key back to leave, or stop. $hint"))
            }
            VScreenStore.Rule.READONLY -> if (act == Act.INPUT) Verdict.Deny("APP_READ_ONLY", tr("副屏上是 $pkg，用户设为只读：能看、滚动、返回，不能点击/输入/滑动。$hint",
                "The screen shows $pkg, which the user set to read-only: you can look, scroll and go back, but not tap / type / swipe. $hint")) else Verdict.Allow
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
