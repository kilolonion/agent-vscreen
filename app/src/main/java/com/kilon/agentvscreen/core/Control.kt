package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.tr
import java.util.concurrent.atomic.AtomicLong

/**
 * 1.4.2-beta6: may the agent change the screen right now, with a plan made when?
 *
 * Before, the user's Stop and takeover were checked once, when a command began ([BackgroundOperation.agentAction]).
 * Everything after that check went ahead unchecked: the tap that followed a 10 s text search, the rest of a slow
 * `type`, the next page of `collect`, the screen rebuilt by `launch`. And a command that arrived before the user took
 * the screen ran after the hand-back on the page the user had changed (it was refused once, then the next one ran).
 *
 * Now control has an epoch that moves on every change the user makes (takeover, hand-back, Stop, allow, closing the
 * screen). A command remembers the epoch it arrived in ([Call.epoch]). Each injection asks [admitInput] at the moment
 * it is sent, and the screen service asks again with the same epoch ([InputFence]) before every event, so nothing
 * the agent planned before the user's last change reaches the screen, and nothing at all while the user holds it or
 * has stopped it. The user's own input is marked with [asUser] and is not fenced (its callers let it through only
 * while the user holds the screen).
 */
object Control {
    /** Starts at the wall clock so it keeps rising across app restarts (the screen service outlives the app). */
    private val epochRef = AtomicLong(System.currentTimeMillis())
    val epoch: Long get() = epochRef.get()

    private val userSide = ThreadLocal<Boolean?>()
    private val plan = ThreadLocal<Long?>()

    /** A change of control: commands planned before it may no longer change the screen. */
    internal fun bump(): Long = epochRef.incrementAndGet()

    /** Run [block] as the user's own input (the picture, the full screen page's keys and typing). */
    fun <T> asUser(block: () -> T): T {
        val before = userSide.get(); userSide.set(true)
        try { return block() } finally { if (before == null) userSide.remove() else userSide.set(before) }
    }

    fun isUser(): Boolean = userSide.get() == true

    /** Run [block] with the plan epoch [e] (a launch that waited out a takeover plans afresh). */
    fun <T> withPlan(e: Long, block: () -> T): T {
        val before = plan.get(); plan.set(e)
        try { return block() } finally { if (before == null) plan.remove() else plan.set(before) }
    }

    /** The epoch the agent's input on this thread was planned under: its command's arrival, else now. */
    fun planned(): Long = plan.get() ?: Call.current()?.epoch ?: epoch

    /** What an injection from this thread carries to the screen service. */
    fun wireEpoch(): Long = if (isUser()) InputFence.USER else planned()

    /** The rule (pure, tested): the refusal code for agent input, or null when it may go. */
    fun refusal(stopped: Boolean, takenOver: Boolean, planned: Long, current: Long): String? = when {
        stopped -> "USER_STOPPED"
        takenOver -> "USER_TAKEOVER"
        planned < current -> "CONTROL_CHANGED"
        else -> null
    }

    /**
     * Called right before anything the agent sends to the screen (a touch, a key, text, a launch, a rebuild). Throws
     * the refusal; the user's own input always passes. [checkPlan] = false for steps that change no page the agent
     * planned on (they still stop for Stop and takeover).
     */
    @Throws(ActionError::class)
    fun admitInput(checkPlan: Boolean = true) {
        if (com.kilon.agentvscreen.host.ThermalGuard.locked) com.kilon.agentvscreen.host.ThermalGuard.throwStopped()
        if (isUser()) return
        val s = BackgroundOperation.state.value
        val code = refusal(s.userStopped, s.takeoverSessionId != null, if (checkPlan) planned() else Long.MAX_VALUE, epoch) ?: return
        throw ActionError(code, message(code))
    }

    fun message(code: String): String = when (code) {
        "USER_STOPPED" -> BackgroundOperation.userStoppedMessage()
        "USER_TAKEOVER" -> tr(S.control_the_user_took_over_the)
        "CONTROL_CHANGED" -> tr(S.control_the_user_took_over_or)
        else -> code
    }

    /**
     * Tell the screen service about the change just made, in order with the user's touches (one serial thread), so
     * the user's first finger lands only after the agent's gesture in flight was cancelled.
     */
    internal fun fence(displayId: Int?, e: Long, agentAllowed: Boolean) {
        displayId ?: return
        UserTouch.inOrder {
            VScreenRemoteDisplay.fence(displayId, e, agentAllowed)
                .onFailure { com.kilon.agentvscreen.host.AppLogger.warning("Control", "fence $displayId failed: ${it.message}") }
        }
    }
}
