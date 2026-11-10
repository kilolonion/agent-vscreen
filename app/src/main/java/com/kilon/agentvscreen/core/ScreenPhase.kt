package com.kilon.agentvscreen.core

/**
 * 1.2: the one answer to "what is the background screen in right now". Before, callers combined six flags of
 * BackgroundOperation.State themselves (connection, displayId, owner, takeover, userStopped, idle/powerSaving) and
 * each combined them a little differently. The phase is derived from the state (never stored), so it cannot disagree
 * with it; [refusal] is the one table of what an agent action meets in each phase.
 */
enum class ScreenPhase {
    /** No privileged connection. */
    OFFLINE,
    /** Connected, no screen. */
    NO_SCREEN,
    /** The user pressed Stop: nothing runs until the user hands back. */
    STOPPED,
    /** The user is controlling the screen. */
    TAKEN_OVER,
    /** Another session owns the screen and is in its turn. */
    FOREIGN,
    /** The owner's turn is over, the page is kept; the next command wakes it (or another session may take it). */
    IDLE,
    /** The idle screen is paused to save power. */
    PARKED,
    /** The caller's own screen, in its turn. */
    ACTIVE;

    companion object {
        /** Pure: the phase [sid] meets, from the flags. */
        fun of(connected: Boolean, hasScreen: Boolean, owner: String?, takeover: String?, userStopped: Boolean, idle: Boolean, powerSaving: Boolean, sid: String?): ScreenPhase = when {
            userStopped -> STOPPED
            !connected -> OFFLINE
            !hasScreen -> NO_SCREEN
            takeover != null -> TAKEN_OVER
            powerSaving -> PARKED
            idle -> IDLE
            owner != null && sid != null && owner != sid -> FOREIGN
            else -> ACTIVE
        }

        fun of(st: BackgroundOperation.State, sid: String?): ScreenPhase =
            of(st.connection == BackgroundOperation.Connection.READY, st.displayId != null, st.ownerSessionId, st.takeoverSessionId, st.userStopped, st.idle, st.powerSaving, sid)

        /**
         * The code an action is refused with in [phase] (null = it may run). [creates]: the command may build a screen
         * (launch, handoff in). IDLE and PARKED are not refusals: any command wakes the screen.
         */
        fun refusal(phase: ScreenPhase, creates: Boolean): String? = when (phase) {
            STOPPED -> "USER_STOPPED"
            TAKEN_OVER -> "USER_TAKEOVER"
            FOREIGN -> "BUSY"
            OFFLINE -> if (creates) null else "NOT_CONNECTED"   // launch connects by itself when the user allowed it
            NO_SCREEN -> if (creates) null else "NO_SCREEN"
            IDLE, PARKED, ACTIVE -> null
        }
    }
}
