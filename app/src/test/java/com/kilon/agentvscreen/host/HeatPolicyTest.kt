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
        assertFalse(p.observe(580, 0, 6)); assertTrue(p.locked)
        p.observe(340, 0, 7); assertFalse(p.resume(180_006)); assertTrue(p.locked)
        p.observe(340, 0, 200_007); assertTrue(p.locked); assertTrue(p.resume(200_007))
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
        p.observe(350, 0, 900_000); p.observe(390, 0, 950_000)
        p.observe(380, 0, 960_000); p.observe(null, 0, 970_000); assertFalse(p.resume(1_150_000))
        p.observe(380, 0, 1_150_000); assertFalse(p.resume(1_329_999)); assertTrue(p.resume(1_330_000))
    }

    @Test fun wakeStormBounded() {
        val b = WakeBudget(); assertTrue(b.allow(0)); assertFalse(b.allow(200)); assertFalse(b.allow(14_999)); assertTrue(b.allow(15_000))
        for (s in 16..119) assertFalse(b.allow(s*1000L))
        assertTrue(b.allow(120_000)); assertFalse(b.allow(120_001))
    }
}
