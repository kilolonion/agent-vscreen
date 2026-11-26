package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.core.VScreenAwake
import com.kilon.agentvscreen.core.VScreenDisplayService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.0.0 (issue #48): keep the phone screen on during agent turns; keep the own display group as long as possible. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V200ReleaseTest {

    @Test fun screenLockOnlyDuringAgentTurnsWithTheScreenOn() {
        assertTrue(VScreenAwake.wantsScreenLock(enabled = true, turnActive = true, phoneScreenOff = false, heatLimited = false))
        // Off by default / switched off: never.
        assertFalse(VScreenAwake.wantsScreenLock(enabled = false, turnActive = true, phoneScreenOff = false, heatLimited = false))
        // Between turns (watches can run for hours): never.
        assertFalse(VScreenAwake.wantsScreenLock(enabled = true, turnActive = false, phoneScreenOff = false, heatLimited = false))
        // The user pressed power: the lock is let go and never turns the screen back on.
        assertFalse(VScreenAwake.wantsScreenLock(enabled = true, turnActive = true, phoneScreenOff = true, heatLimited = false))
        // A hot phone: let go.
        assertFalse(VScreenAwake.wantsScreenLock(enabled = true, turnActive = true, phoneScreenOff = false, heatLimited = true))
    }

    @Test fun ownDisplayGroupOutlivesAlwaysUnlocked() {
        val ladder = VScreenDisplayService.defaultFlagLadder().map { VScreenDisplayService.flagNames(it).toSet() }
        val firstWithoutUnlocked = ladder.indexOfFirst { "ALWAYS_UNLOCKED" !in it }
        // The first rung that drops ALWAYS_UNLOCKED still has its own display group.
        assertTrue(ladder[firstWithoutUnlocked].contains("OWN_DISPLAY_GROUP"))
        // Every rung with the group before the last one with it keeps TRUSTED (the group needs the trusted permission).
        ladder.filter { "OWN_DISPLAY_GROUP" in it }.forEach { assertTrue(it.contains("TRUSTED")) }
        // Some rung keeps only TRUSTED + own group (no other privileged flag), before any rung without the group.
        val bare = ladder.indexOfFirst { "OWN_DISPLAY_GROUP" in it && "ALWAYS_UNLOCKED" !in it && "TOUCH_FEEDBACK_DISABLED" !in it }
        val firstWithoutGroup = ladder.indexOfFirst { "OWN_DISPLAY_GROUP" !in it }
        assertTrue(bare in 0 until firstWithoutGroup)
        assertEquals(ladder.size, ladder.distinct().size)
    }
}
