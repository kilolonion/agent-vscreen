package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.I18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 1.4.2-beta4 (field report: after taking over the screen and handing it back, the agent of an MCP host could only go
 * on after the screen was rebuilt): a takeover is the user's and outlives the agent's turn; the held screen is neither
 * paused nor released; "look first" survives between turns and is asked once.
 */
@RunWith(RobolectricTestRunner::class)
class V142Beta4Test {

    @Test fun takeoverOutlivesTheAgentsTurn() {
        // The agent met USER_TAKEOVER, went quiet, and its turn ended after Turns.quietMs: the user still holds it.
        assertTrue(TakeoverRules.survivesTurnEnd(takeover = "s1", session = "s1"))
        // Nobody held it, or someone else's turn ended: nothing to keep.
        assertFalse(TakeoverRules.survivesTurnEnd(takeover = null, session = "s1"))
        assertFalse(TakeoverRules.survivesTurnEnd(takeover = "s2", session = "s1"))
    }

    @Test fun theHeldScreenIsNeverPausedOrReleased() {
        assertFalse(TakeoverRules.mayIdle(ownerInTurn = false, holds = 0, takeover = "s1"))
        assertTrue(TakeoverRules.mayIdle(ownerInTurn = false, holds = 0, takeover = null))
        assertFalse(TakeoverRules.mayIdle(ownerInTurn = true, holds = 0, takeover = null))
        assertFalse(TakeoverRules.mayIdle(ownerInTurn = false, holds = 1, takeover = null))
    }

    @Test fun theHeldScreenIsKeptAwakeAfterTheTurn() {
        assertTrue(TakeoverRules.needsAwake(ownerInTurn = false, holds = 0, takeover = "s1"))
        assertTrue(TakeoverRules.needsAwake(ownerInTurn = true, holds = 0, takeover = null))
        assertTrue(TakeoverRules.needsAwake(ownerInTurn = false, holds = 2, takeover = null))
        assertFalse(TakeoverRules.needsAwake(ownerInTurn = false, holds = 0, takeover = null))
    }

    @Test fun anotherSessionCannotPickUpTheHeldScreen() {
        assertFalse(TakeoverRules.mayAdoptIdle(takeover = "s1", sid = "s2"))
        assertTrue(TakeoverRules.mayAdoptIdle(takeover = "s1", sid = "s1"))
        assertTrue(TakeoverRules.mayAdoptIdle(takeover = null, sid = "s2"))
    }

    @Test fun afterWaitingOutATakeoverOnlyChangesAreRefused() {
        // A tap that waited out the takeover is refused once (the page may have changed under it).
        assertTrue(TakeoverRules.refuseAfterWait(waited = true, isRead = false, freshPage = false))
        // The look the refusal asks for, and launch (a new page), just run: they are the re-read.
        assertFalse(TakeoverRules.refuseAfterWait(waited = true, isRead = true, freshPage = false))
        assertFalse(TakeoverRules.refuseAfterWait(waited = true, isRead = false, freshPage = true))
        assertFalse(TakeoverRules.refuseAfterWait(waited = false, isRead = false, freshPage = false))
    }

    @Test fun lookFirstOwedBetweenTurnsIsKeptUntilSettled() {
        val d = RereadDebts()
        assertFalse(d.owes("s1"))
        // The user hands back after the agent's turn ended: owed without a reason (a takeover).
        d.owe("s1")
        assertTrue(d.owes("s1"))
        assertFalse(d.owes("s2"))
        // A later debt without a reason keeps the reason already owed (a restart note is not lost).
        d.owe("s2", "restarted")
        d.owe("s2")
        assertEquals("restarted", d.settle("s2"))
        assertFalse(d.owes("s2"))
        // Settled once: the next turn starts with it, the one after does not.
        assertNull(d.settle("s1"))
        assertFalse(d.owes("s1"))
        d.owe("s3"); d.clear()
        assertFalse(d.owes("s3"))
    }

    @Test fun theCodesTellTheAgentToWaitNotRebuild() {
        fun both(code: String): Pair<String, String> {
            val c = com.kilon.agentvscreen.cmd.Codes.of(code)!!
            try {
                I18n.choice = I18n.Lang.ZH; val zh = c.text
                I18n.choice = I18n.Lang.EN; val en = c.text
                return zh to en
            } finally { I18n.choice = I18n.Lang.AUTO }
        }
        val (tz, te) = both("USER_TAKEOVER")
        assertTrue(tz, tz.contains("handback") && tz.contains("不要 release"))
        assertTrue(te, te.contains("handback") && te.contains("don't release or launch again"))
        val (rz, re) = both("REREAD_REQUIRED")
        assertTrue(rz, rz.contains("只拒这一次"))
        assertTrue(re, re.contains("Refused only this once"))
    }
}
