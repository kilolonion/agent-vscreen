package com.kilon.agentvscreen.host

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenRules
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HeatIntegrationTest {
    @Test fun existingLatchBlocksServiceWakeAndDisplayAndWatchRestore() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("thermal_safety_v1", Context.MODE_PRIVATE).edit().putBoolean("locked", true).commit()
        ThermalGuard.init(app)
        assertTrue(ThermalGuard.locked); assertFalse(ThermalGuard.resumeFromPowerSheet())
        assertEquals(ServiceControl.State.BLOCKED, ServiceControl.now(app))
        assertFalse(ServiceControl.wake(app, "booked window"))
        HostService.start(app); assertNull(shadowOf(app).nextStartedService)
        try {
            BackgroundOperation.acquire("agent", true)
            fail("display created while hot")
        } catch (e: BackgroundOperation.ActionError) {
            assertEquals("THERMAL_STOP", e.code)
            val th = e.data?.optJSONObject("thermal")
            assertNotNull("THERMAL_STOP must carry structured thermal payload", th)
            assertTrue(th!!.getBoolean("hard_stop_enabled"))
        }
        assertEquals(0, VScreenRules.restore()); assertFalse(HostService.isAlive)

        // Turning off hard-stop while locked unlocks immediately and clears persisted locked state.
        ThermalGuard.setHardStopEnabled(false)
        assertFalse(ThermalGuard.locked)
        assertFalse(app.getSharedPreferences("thermal_safety_v1", Context.MODE_PRIVATE).getBoolean("locked", true))
        ThermalGuard.setHardStopEnabled(true)
    }
    @Test fun systemBatteryBroadcastTripsLatchWithoutWritingUserServicePreference() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.setServiceOn(app, true); ThermalGuard.init(app)
        app.sendBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_TEMPERATURE, 451)); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ThermalGuard.locked); assertTrue(app.getSharedPreferences("thermal_safety_v1", Context.MODE_PRIVATE).getBoolean("locked", false))
        assertTrue(Prefs.serviceOn(app)); assertEquals(ServiceControl.State.BLOCKED, ServiceControl.now(app))
    }
}
