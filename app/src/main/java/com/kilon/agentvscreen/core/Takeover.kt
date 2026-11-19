package com.kilon.agentvscreen.core

/**
 * 1.4.2-beta4: the rules of a user takeover against the agent's turn, the idle timers and other sessions (pure).
 *
 * A takeover is the user's: only the user ends it (Hand back, Stop, closing the screen or hiding the window). Before,
 * the agent's turn ending dropped it: an agent that met USER_TAKEOVER and went quiet ended its turn after
 * Turns.quietMs, the takeover vanished under the user's finger without a hand-back (no handback event, no re-read), the
 * idle screen was paused and released, and the agent coming back had to rebuild the screen. Every place that decides
 * what a takeover means asks here, so the answers cannot drift apart again.
 */
internal object TakeoverRules {
    /** [session]'s turn ends: the user's takeover of its screen stays. */
    fun survivesTurnEnd(takeover: String?, session: String): Boolean = takeover == session

    /** The idle screen may be paused / released: no turn runs on it, no watch holds it, the user does not hold it. */
    fun mayIdle(ownerInTurn: Boolean, holds: Int, takeover: String?): Boolean = !ownerInTurn && holds == 0 && takeover == null

    /** Work on the screen keeps it awake: a running turn, a watch, or the user holding it. */
    fun needsAwake(ownerInTurn: Boolean, holds: Int, takeover: String?): Boolean = ownerInTurn || holds > 0 || takeover != null

    /** [sid] may pick up a screen another session left idle, but not while the user holds it. */
    fun mayAdoptIdle(takeover: String?, sid: String): Boolean = takeover == null || takeover == sid

    /**
     * A command waited out a takeover: changing commands are refused once (REREAD_REQUIRED); a look, or a launch (a
     * new page), just runs, since it is the re-read itself.
     */
    fun refuseAfterWait(waited: Boolean, isRead: Boolean, freshPage: Boolean): Boolean = waited && !isRead && !freshPage
}

/**
 * 1.4.2-beta4: "look first" owed to sessions between their turns: session -> reason (null = the user had the screen).
 * A turn's bookkeeping is dropped when the turn ends and the next turn starts empty, so a hand-back that came after the
 * turn was forgotten and the agent acted on a page the user had changed. Not thread-safe: the caller locks.
 */
internal class RereadDebts {
    private val owed = HashMap<String, String?>()

    /** [sid] must look before its next change; a reason already owed is kept when none is given. */
    fun owe(sid: String, reason: String? = null) { owed[sid] = reason ?: owed[sid] }

    fun owes(sid: String): Boolean = owed.containsKey(sid)

    /** Settle [sid]'s debt and return its reason (null when none or no reason); [owes] is false afterwards. */
    fun settle(sid: String): String? = owed.remove(sid)

    fun clear() = owed.clear()
}
