package com.kilon.agentvscreen.cmd

import android.app.Application
import android.content.Context
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.Turns
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.InputReach
import com.kilon.agentvscreen.core.InputRoute
import com.kilon.agentvscreen.core.VScreenAwake
import com.kilon.agentvscreen.core.VScreenStore
import com.kilon.agentvscreen.host.HeatPolicy
import com.kilon.agentvscreen.host.ThermalGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for issues #60 (screen-off input reachability & routing), #48 (reactive awake & passive read-only turn
 * lifecycle), and #61 (dynamic bilingual thermal reason & structured JSON).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class V201IssuesTest {

    // ── Issue #60: InputReach.parseNonInteractive ────────────────────────────

    @Test
    fun parseNonInteractiveCoversIssue60SampleEmptyAlternateAndUnknownFormats() {
        // Real dumpsys input excerpt matching issue #60.
        val sample60 = """
            INPUT MANAGER (dumpsys input)
            Input Dispatcher State:
              DispatchEnabled: true
              DispatchFrozen: false
              Display not interactive: {0, 37}
              FocusedDisplayId: 0
        """.trimIndent()
        assertEquals(setOf(0, 37), InputReach.parseNonInteractive(sample60))

        // Empty set variants -> emptySet() (every display interactive).
        assertEquals(emptySet<Int>(), InputReach.parseNonInteractive("Display not interactive: {}"))
        assertEquals(emptySet<Int>(), InputReach.parseNonInteractive("Display not interactive: none"))
        assertEquals(emptySet<Int>(), InputReach.parseNonInteractive("Non-interactive displays: []"))

        // Alternate valid punctuation / casing.
        assertEquals(setOf(0, 5, 12), InputReach.parseNonInteractive("non-interactive displays: [0, 5, 12]"))
        assertEquals(setOf(3), InputReach.parseNonInteractive("  non interactive display = 3 "))

        // Missing line or unknown / malformed value -> null (maps to UNKNOWN).
        assertNull(InputReach.parseNonInteractive("Input Dispatcher State:\n  DispatchEnabled: true\n"))
        assertNull(InputReach.parseNonInteractive("Display not interactive:"))
        assertNull(InputReach.parseNonInteractive("Display not interactive: {0, bogus}"))
        assertNull(InputReach.parseNonInteractive("Display not interactive: {0"))
    }

    // ── Issue #60: InputRoute.choose full truth table ────────────────────────

    @Test
    fun inputRouteTruthTableCoversAllReachKindNodeWakeCombinations() {
        val elementKinds = setOf(
            InputRoute.Kind.ELEMENT_TAP,
            InputRoute.Kind.ELEMENT_PRESS,
            InputRoute.Kind.SCROLL,
            InputRoute.Kind.TYPE,
        )
        for (reach in InputReach.Reach.entries) {
            for (kind in InputRoute.Kind.entries) {
                for (hasNode in listOf(false, true)) {
                    for (wakeEnabled in listOf(false, true)) {
                        val expected = when {
                            reach != InputReach.Reach.BLOCKED -> InputRoute.Route.TOUCH
                            kind in elementKinds && hasNode -> InputRoute.Route.A11Y
                            wakeEnabled -> InputRoute.Route.WAKE_THEN_TOUCH
                            else -> InputRoute.Route.REFUSE
                        }
                        assertEquals(
                            "reach=$reach kind=$kind hasNode=$hasNode wake=$wakeEnabled",
                            expected,
                            InputRoute.choose(reach, kind, hasNode, wakeEnabled),
                        )
                    }
                }
            }
        }
    }

    // ── Issue #60: Three-mode phoneScreenMode migration & wantsScreenLock ────

    @Test
    fun phoneScreenModeMigrationFromLegacyKeepScreenOn() {
        // Pure migration matrix
        assertEquals(VScreenStore.PhoneScreenMode.KEEP_ON, VScreenStore.PhoneScreenMode.migrate(null, true))
        assertEquals(VScreenStore.PhoneScreenMode.ALLOW_SLEEP, VScreenStore.PhoneScreenMode.migrate(null, false))
        assertEquals(VScreenStore.PhoneScreenMode.ALLOW_SLEEP, VScreenStore.PhoneScreenMode.migrate(null, null))
        assertEquals(VScreenStore.PhoneScreenMode.WAKE_WHEN_NEEDED, VScreenStore.PhoneScreenMode.migrate("wake_when_needed", false))
        assertEquals(VScreenStore.PhoneScreenMode.ALLOW_SLEEP, VScreenStore.PhoneScreenMode.migrate("allow_sleep", true))

        // SharedPreferences one-time rewrite & removal of legacy key
        val ctx = RuntimeEnvironment.getApplication()
        val spTrue = ctx.getSharedPreferences("test_mode_migrate_true", Context.MODE_PRIVATE)
        spTrue.edit().clear().putBoolean("keep_screen_on", true).commit()
        assertEquals(VScreenStore.PhoneScreenMode.KEEP_ON, VScreenStore.loadAndMigratePhoneScreenMode(spTrue))
        assertEquals("keep_on", spTrue.getString("phone_screen_mode", null))
        assertFalse(spTrue.contains("keep_screen_on"))

        val spFalse = ctx.getSharedPreferences("test_mode_migrate_false", Context.MODE_PRIVATE)
        spFalse.edit().clear().putBoolean("keep_screen_on", false).commit()
        assertEquals(VScreenStore.PhoneScreenMode.ALLOW_SLEEP, VScreenStore.loadAndMigratePhoneScreenMode(spFalse))
        assertEquals("allow_sleep", spFalse.getString("phone_screen_mode", null))
        assertFalse(spFalse.contains("keep_screen_on"))

        // Clean up global store mode back to default
        VScreenStore.setPhoneScreenMode(VScreenStore.PhoneScreenMode.ALLOW_SLEEP)
    }

    @Test
    fun wantsScreenLockAcrossAllThreeModes() {
        for (mode in VScreenStore.PhoneScreenMode.entries) {
            for (turnActive in listOf(false, true)) {
                for (screenOff in listOf(false, true)) {
                    for (heatLimited in listOf(false, true)) {
                        val expected = mode != VScreenStore.PhoneScreenMode.ALLOW_SLEEP && turnActive && !screenOff && !heatLimited
                        assertEquals(
                            "mode=$mode turn=$turnActive off=$screenOff heat=$heatLimited",
                            expected,
                            VScreenAwake.wantsScreenLock(mode, turnActive, screenOff, heatLimited),
                        )
                    }
                }
            }
        }
    }

    // ── Issue #48: Command classification, cold-start awake, done -> status ──

    @Test
    fun commandSpecPassiveReadOnlyClassificationIsExact() {
        // Passive read-only queries that never touch or wake the background display
        val passiveCases = listOf(
            listOf("status"),
            listOf("diag"),
            listOf("apps"),
            listOf("apps", "wechat"),
            listOf("permissions"),
            listOf("events"),
            listOf("task"),
            listOf("task", "1"),
            listOf("task", "recap"),
            listOf("sound"),
            listOf("sound", "list"),
            listOf("keep"),
            listOf("schedule"),
            listOf("guide"),
            listOf("remembered"),
            listOf("help"),
            listOf("help", "codes"),
            listOf("--help"),
            listOf("-h"),
        )
        for (argv in passiveCases) {
            assertTrue("Expected passive read-only: $argv", CommandSpec.isPassiveReadOnly(argv))
        }

        // Work or mutating commands that must start/extend a turn
        val workCases = listOf(
            listOf("launch", "com.android.settings"),
            listOf("act"),
            listOf("act", "tap 设置"),
            listOf("look"),
            listOf("zoom", "0,0,100,100"),
            listOf("frames"),
            listOf("spots"),
            listOf("collect", "--pages", "2"),
            listOf("tasks"),
            listOf("handoff", "out"),
            listOf("stop"),
            listOf("release"),
            listOf("done"),
            listOf("task", "cancel", "1"),
            listOf("sound", "mute", "com.example.app"),
            listOf("keep", "off", "watch1"),
            listOf("schedule", "08:00"),
            listOf("fix"),
            listOf("elevate", "caution", "--why", "test"),
        )
        for (argv in workCases) {
            assertFalse("Expected work/mutating command: $argv", CommandSpec.isPassiveReadOnly(argv))
        }
    }

    @Test
    fun passiveReadOnlyTurnLifecycleAndDoneThenStatus() {
        val sid = "test_issue48_${System.nanoTime()}"
        try {
            // 1. No active turn: passive `status` does NOT start a turn and does NOT activate agentTurnActive / lock.
            val joinedBefore = Turns.beginCommand(sid, passive = CommandSpec.isPassiveReadOnly(listOf("status")))
            assertFalse(joinedBefore)
            assertFalse(Turns.isActive(sid))
            assertFalse(sid in BackgroundOperation.activeTurns.value)
            Turns.endCommand(sid, joinedBefore)
            assertFalse(Turns.isActive(sid))

            // 2. Work command (`launch`) starts a turn even before display exists (cold-start scenario).
            val joinedWork = Turns.beginCommand(sid, passive = CommandSpec.isPassiveReadOnly(listOf("launch", "settings")))
            assertTrue(joinedWork)
            assertTrue(Turns.isActive(sid))
            assertTrue(sid in BackgroundOperation.activeTurns.value)
            // Once display exists for this active turn, wantsScreenLock is true under KEEP_ON & WAKE_WHEN_NEEDED.
            assertTrue(VScreenAwake.wantsScreenLock(VScreenStore.PhoneScreenMode.KEEP_ON, turnActive = sid in BackgroundOperation.activeTurns.value, phoneScreenOff = false, heatLimited = false))
            assertTrue(VScreenAwake.wantsScreenLock(VScreenStore.PhoneScreenMode.WAKE_WHEN_NEEDED, turnActive = sid in BackgroundOperation.activeTurns.value, phoneScreenOff = false, heatLimited = false))
            Turns.endCommand(sid, joinedWork)
            assertTrue("Turn remains active during quiet window after launch completes", Turns.isActive(sid))

            // 3. Passive `status` DURING an active turn joins and extends the turn.
            val joinedDuring = Turns.beginCommand(sid, passive = CommandSpec.isPassiveReadOnly(listOf("status")))
            assertTrue(joinedDuring)
            assertTrue(Turns.isActive(sid))
            Turns.endCommand(sid, joinedDuring)
            assertTrue(Turns.isActive(sid))

            // 4. `done` (`Turns.finish`) ends the turn; subsequent `status` does NOT reopen it or take screen lock.
            Turns.finish(sid)
            assertFalse(Turns.isActive(sid))
            assertFalse(sid in BackgroundOperation.activeTurns.value)

            val joinedAfterDone = Turns.beginCommand(sid, passive = CommandSpec.isPassiveReadOnly(listOf("status")))
            assertFalse(joinedAfterDone)
            assertFalse(Turns.isActive(sid))
            assertFalse(sid in BackgroundOperation.activeTurns.value)
            Turns.endCommand(sid, joinedAfterDone)
            assertFalse(Turns.isActive(sid))
            assertFalse(VScreenAwake.wantsScreenLock(VScreenStore.PhoneScreenMode.KEEP_ON, turnActive = sid in BackgroundOperation.activeTurns.value, phoneScreenOff = false, heatLimited = false))
        } finally {
            Turns.finish(sid)
        }
    }

    // ── Issue #61: Dynamic bilingual ThermalGuard reason & JSON ──────────────

    @Test
    fun thermalReasonIsDynamicBilingualAndFreeOfEnglishFullwidthPunctuation() {
        val fullwidth = Regex("[，。；：（）“”‘’？！、【】]")
        val snapshots = listOf(
            HeatPolicy.Snapshot(locked = true, limited = true, ocrPaused = true, hardStopEnabled = true, phase = HeatPolicy.Phase.LOCKED_HOT, strictUnlock = false, tempTenths = 455, status = 3, unlockTempTenths = 430, unlockMaxStatusExclusive = 3, holdMs = 60_000L, retryAfterS = null),
            HeatPolicy.Snapshot(locked = true, limited = true, ocrPaused = true, hardStopEnabled = true, phase = HeatPolicy.Phase.COOLING, strictUnlock = false, tempTenths = 440, status = 1, unlockTempTenths = 430, unlockMaxStatusExclusive = 3, holdMs = 60_000L, retryAfterS = null),
            HeatPolicy.Snapshot(locked = true, limited = true, ocrPaused = true, hardStopEnabled = true, phase = HeatPolicy.Phase.COOLING, strictUnlock = false, tempTenths = 420, status = 1, unlockTempTenths = 430, unlockMaxStatusExclusive = 3, holdMs = 60_000L, retryAfterS = 42),
            HeatPolicy.Snapshot(locked = true, limited = true, ocrPaused = true, hardStopEnabled = true, phase = HeatPolicy.Phase.READY, strictUnlock = false, tempTenths = 415, status = 1, unlockTempTenths = 430, unlockMaxStatusExclusive = 3, holdMs = 60_000L, retryAfterS = 0),
            HeatPolicy.Snapshot(locked = false, limited = false, ocrPaused = false, hardStopEnabled = true, phase = HeatPolicy.Phase.NORMAL, strictUnlock = false, tempTenths = 360, status = 0, unlockTempTenths = 430, unlockMaxStatusExclusive = 3, holdMs = 60_000L, retryAfterS = null),
        )
        for (s in snapshots) {
            val zh = I18n.with("zh") { ThermalGuard.formatReason(s) }
            val en = I18n.with("en") { ThermalGuard.formatReason(s) }
            assertTrue("ZH should include temperature: $zh", zh.contains("°C"))
            assertTrue("EN should include temperature: $en", en.contains("°C"))
            assertFalse("EN thermal reason must not contain fullwidth punctuation: $en", fullwidth.containsMatchIn(en))
        }
        // Cooling with countdown explicitly reports remaining seconds.
        val zhCount = I18n.with("zh") { ThermalGuard.formatReason(snapshots[2]) }
        val enCount = I18n.with("en") { ThermalGuard.formatReason(snapshots[2]) }
        assertTrue(zhCount.contains("42"))
        assertTrue(enCount.contains("42"))

        // Cooling waiting on temperature says target temperature, not a fake countdown.
        val enHotCool = I18n.with("en") { ThermalGuard.formatReason(snapshots[1]) }
        assertTrue(enHotCool.contains("43.0°C"))

        // Structured JSON always exposes all 5 contract keys.
        val json = ThermalGuard.thermalJson()
        for (k in listOf("battery_c", "status", "phase", "hard_stop_enabled", "retry_after_s")) {
            assertTrue("thermalJson missing key $k", json.has(k))
        }
        assertNotNull(json.optString("phase"))

        // INPUT_BLOCKED_SCREEN_OFF English message also has zero fullwidth punctuation.
        val enBlocked = I18n.with("en") { inputBlockedMessage() }
        assertFalse("EN INPUT_BLOCKED_SCREEN_OFF must not contain fullwidth punctuation: $enBlocked", fullwidth.containsMatchIn(enBlocked))
    }
}
