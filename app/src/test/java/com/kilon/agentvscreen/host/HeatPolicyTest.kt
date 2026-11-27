package com.kilon.agentvscreen.host

import org.junit.Assert.*
import org.junit.Test

class HeatPolicyTest {
    @Test fun replayIncident() {
        val p = HeatPolicy(false)
        assertFalse(p.observe(290, 0, 0)); assertFalse(p.limited); assertFalse(p.ocrPaused)
        // 42.0°C with low system thermal status stays full-speed (relaxed from the old 42.0°C cutoff).
        assertFalse(p.observe(420, 0, 1)); assertFalse(p.limited); assertFalse(p.ocrPaused)
        // 43.0°C throttles background/heavy work (`limited`) while keeping fast on-demand OCR available.
        assertFalse(p.observe(430, 0, 2)); assertTrue(p.limited); assertFalse(p.ocrPaused)
        assertFalse(p.observe(425, 0, 3)); assertTrue(p.limited); assertFalse(p.ocrPaused)
        // 44.0°C pauses all OCR (`ocrPaused`) before the hard 45.0°C shutdown latch.
        assertFalse(p.observe(440, 0, 4)); assertTrue(p.limited); assertTrue(p.ocrPaused); assertFalse(p.locked)
        assertTrue(p.observe(450, 0, 5)); assertTrue(p.locked); assertTrue(p.ocrPaused)
        assertEquals(HeatPolicy.Phase.LOCKED_HOT, p.snapshot(5).phase)
        assertFalse(p.observe(580, 0, 6)); assertTrue(p.locked)
        // First recovery needs <43.0°C and status <3 held for 60 s.
        p.observe(340, 0, 7); assertFalse(p.resume(60_006)); assertTrue(p.locked)
        assertEquals(HeatPolicy.Phase.COOLING, p.snapshot(60_006).phase)
        p.observe(340, 0, 60_007); assertTrue(p.locked); assertEquals(HeatPolicy.Phase.READY, p.snapshot(60_007).phase)
        assertTrue(p.resume(60_007))
        // Resuming enters limited mode first; next cool observation clears limited automatically.
        assertFalse(p.locked); assertTrue(p.limited); assertFalse(p.ocrPaused)
        p.observe(340, 0, 60_008)
        assertFalse(p.limited); assertFalse(p.ocrPaused)
    }

    @Test fun relaxedChargingModerateAndTwoStepRecovery() {
        val p = HeatPolicy(false)
        // Routine charging warmth (38.5°C + MODERATE) does not throttle or pause OCR.
        assertFalse(p.observe(385, 2, 0)); assertFalse(p.limited); assertFalse(p.ocrPaused); assertFalse(p.locked)
        // Warmer charging (40.5°C + MODERATE) throttles heavy/background work, but fast OCR keeps working.
        assertFalse(p.observe(405, 2, 1)); assertTrue(p.limited); assertFalse(p.ocrPaused); assertFalse(p.locked)
        // Hysteresis while MODERATE stays active: 39.5°C holds limited, <39.0°C clears it.
        assertFalse(p.observe(395, 2, 2)); assertTrue(p.limited)
        assertFalse(p.observe(389, 2, 3)); assertFalse(p.limited)

        // Two-step recovery from 44.0°C (`ocrPaused`): <42.0°C resumes fast OCR first, <41.0°C resumes full OCR/background.
        assertFalse(p.observe(440, 1, 10)); assertTrue(p.limited); assertTrue(p.ocrPaused); assertFalse(p.locked)
        assertFalse(p.observe(425, 1, 11)); assertTrue(p.limited); assertTrue(p.ocrPaused)
        assertFalse(p.observe(415, 1, 12)); assertTrue(p.limited); assertFalse(p.ocrPaused)
        assertFalse(p.observe(405, 1, 13)); assertFalse(p.limited); assertFalse(p.ocrPaused)
    }

    @Test fun independentThermalSignals() {
        val p = HeatPolicy(false)
        // Without battery temperature, MODERATE still triggers `limited` (heavy/background), not hard OCR pause or lock.
        p.observe(null, 2, 10); assertTrue(p.limited); assertFalse(p.ocrPaused); assertFalse(p.locked)
        // Dropping back to LIGHT clears `limited` even when battery temperature is absent.
        p.observe(null, 1, 15); assertFalse(p.limited); assertFalse(p.ocrPaused)
        // SEVERE still immediately trips hard shutdown latch and pauses OCR.
        assertTrue(p.observe(null, 3, 20)); assertTrue(p.locked); assertTrue(p.ocrPaused); assertFalse(p.resume(999_999))
        assertTrue(HeatPolicy(false).observe(451, null, 0))
    }

    @Test fun coldBootAndBadSensorsCannotUnlock() {
        val p = HeatPolicy(true)
        p.observe(350, null, 0); assertFalse(p.resume(900_000))
        // 43.0°C breaks the normal cooling hold; missing temp also breaks it.
        p.observe(350, 0, 900_000); p.observe(430, 0, 950_000)
        p.observe(380, 0, 960_000); p.observe(null, 0, 970_000); assertFalse(p.resume(1_150_000))
        p.observe(380, 0, 1_150_000); assertFalse(p.resume(1_209_999)); assertTrue(p.resume(1_210_000))
    }

    @Test fun deadZoneRecovery42C60sAnd44CBlocked() {
        val p = HeatPolicy(false)
        assertTrue(p.observe(450, 3, 1_000))
        assertEquals(HeatPolicy.Phase.LOCKED_HOT, p.snapshot(1_000).phase)
        assertNull(p.snapshot(1_000).retryAfterS)

        // 44.0°C (below danger zone, above 43.0°C): COOLING, no countdown, cannot resume even after 5 min.
        p.observe(440, 1, 2_000)
        val s44 = p.snapshot(302_000)
        assertEquals(HeatPolicy.Phase.COOLING, s44.phase)
        assertNull(s44.retryAfterS)
        assertFalse(p.resume(302_000))

        // 42.0°C with status 1 (LIGHT): starts 60 s countdown, unlocks at 60 s and enters `limited`.
        p.observe(420, 1, 400_000)
        assertEquals(HeatPolicy.Phase.COOLING, p.snapshot(430_000).phase)
        assertEquals(30, p.snapshot(430_000).retryAfterS)
        assertFalse(p.resume(459_999))
        assertEquals(HeatPolicy.Phase.READY, p.snapshot(460_000).phase)
        assertEquals(0, p.snapshot(460_000).retryAfterS)
        assertTrue(p.resume(460_000))
        assertFalse(p.locked)
        assertTrue(p.limited)
        assertFalse(p.ocrPaused)
    }

    @Test fun recurrenceWithinOneHourRequiresStrictUnlock() {
        val p = HeatPolicy(false)
        assertTrue(p.observe(452, 2, 10_000))
        p.observe(420, 1, 20_000)
        assertTrue(p.resume(80_000))
        assertFalse(p.strictUnlock)

        // Re-trip 10 minutes later (< 1 hour): strict unlock (<39.0°C, status <2, 180 s) activates.
        assertTrue(p.observe(455, 2, 680_000))
        assertTrue(p.strictUnlock)
        // 42.0°C for 60 s no longer suffices.
        p.observe(420, 1, 690_000)
        assertFalse(p.resume(800_000))
        assertNull(p.snapshot(800_000).retryAfterS)
        // 38.5°C with status 2 (MODERATE) still blocked under strict rules.
        p.observe(385, 2, 810_000)
        assertFalse(p.resume(1_100_000))
        // 38.5°C with status 1 (LIGHT) must hold full 180 s.
        p.observe(385, 1, 1_200_000)
        assertFalse(p.resume(1_379_999))
        assertEquals(1, p.snapshot(1_379_999).retryAfterS)
        assertTrue(p.resume(1_380_000))
        assertFalse(p.locked)
        assertTrue(p.limited)
    }

    @Test fun hardStopDisabledNeverLocksKeepsLimitedAndAlertsOncePerEntry() {
        val p = HeatPolicy(initiallyLocked = false, hardStopEnabled = false)
        assertFalse(p.observe(455, 4, 1_000))
        assertFalse(p.locked)
        assertTrue(p.limited)
        assertTrue(p.ocrPaused)
        assertTrue(p.consumeDangerAlert())
        // Staying in danger zone does not re-trigger the alert.
        assertFalse(p.observe(460, 4, 2_000))
        assertFalse(p.consumeDangerAlert())
        // Leaving danger zone and re-entering triggers a new one-shot alert.
        assertFalse(p.observe(435, 2, 3_000))
        assertFalse(p.consumeDangerAlert())
        assertFalse(p.observe(451, 2, 4_000))
        assertFalse(p.locked)
        assertTrue(p.consumeDangerAlert())
    }

    @Test fun togglingHardStopOffUnlocksAndReEnablingInDangerLocks() {
        val p = HeatPolicy(false)
        assertTrue(p.observe(455, 3, 1_000))
        assertTrue(p.locked)

        // Disabling while locked unlocks immediately while retaining load throttling.
        val offRes = p.setHardStopEnabled(false, 2_000)
        assertTrue(offRes.unlocked)
        assertFalse(offRes.tripped)
        assertFalse(p.locked)
        assertTrue(p.limited)
        assertTrue(p.ocrPaused)

        // Re-enabling while still in danger zone locks immediately.
        val onRes = p.setHardStopEnabled(true, 3_000)
        assertFalse(onRes.unlocked)
        assertTrue(onRes.tripped)
        assertTrue(p.locked)
        assertEquals(HeatPolicy.Phase.LOCKED_HOT, p.snapshot(3_000).phase)
    }

    @Test fun wakeStormBounded() {
        val b = WakeBudget(); assertTrue(b.allow(0)); assertFalse(b.allow(200)); assertFalse(b.allow(14_999)); assertTrue(b.allow(15_000))
        for (s in 16..119) assertFalse(b.allow(s*1000L))
        assertTrue(b.allow(120_000)); assertFalse(b.allow(120_001))
    }
}
