package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.host.UserAsk
import com.kilon.agentvscreen.ui.AskWords
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.0.0-beta15: fixes from the beta13 phone test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V200Beta15Test {

    @Test fun swipeTakesATrailingBareNumberAsMilliseconds() {
        val s = Steps.parse("swipe 1100 1830 1100 1660 450")
        assertEquals(StepKind.SWIPE, s.kind)
        assertEquals(2, s.points.size)
        assertEquals(450L, s.ms)
        val d = Steps.parse("drag 1 2 3 4 5 6 700")
        assertEquals(3, d.points.size)
        assertEquals(700L, d.ms)
        // Six numbers are still three points for drag, and too many for swipe.
        assertEquals(3, Steps.parse("drag 1 2 3 4 5 6").points.size)
    }

    @Test fun countdownFollowsTheClockItIsGiven() {
        val a = UserAsk.Ask(1, UserAsk.Kind.CHOOSE, "k", "t", "t", "s", "q", "", at = 1_000_000L, reusable = false, validMs = 600_000L)
        assertEquals("10:00", AskWords.left(a, 1_000_000L))
        assertEquals("9:29", AskWords.left(a, 1_031_000L))
        assertEquals("0:00", AskWords.left(a, 9_000_000L))
    }
}
