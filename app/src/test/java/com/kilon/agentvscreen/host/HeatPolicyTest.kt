package com.kilon.agentvscreen.host

import org.junit.Assert.*
import org.junit.Test

class HeatPolicyTest {
    @Test fun replayIncident() {
        val p = HeatPolicy(false)
        assertFalse(p.observe(290, 0, 0)); assertFalse(p.limited)
        assertFalse(p.observe(420, 0, 1)); assertTrue(p.limited)
        assertFalse(p.observe(415, 0, 2)); assertTrue(p.limited)
        assertTrue(p.observe(450, 0, 3)); assertTrue(p.locked)
        assertFalse(p.observe(580, 0, 4)); assertTrue(p.locked)
        p.observe(340, 0, 5); assertFalse(p.resume(180_004)); assertTrue(p.locked)
        p.observe(340, 0, 200_005); assertTrue(p.locked); assertTrue(p.resume(200_005)); assertFalse(p.limited)
    }
    @Test fun independentThermalSignals() {
        val p = HeatPolicy(false)
        p.observe(null, 2, 10); assertTrue(p.limited); assertFalse(p.locked)
        assertTrue(p.observe(null, 3, 20)); assertFalse(p.resume(999_999))
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
