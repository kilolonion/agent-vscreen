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

    @Test
    fun inputReachTrustsInputDispatcherOverPowerGroupState() {
        try {
            InputReach.resetForTest()
            VScreenAwake.setScreenOffForTest(true)
            // Issue #60: the display's power group is awake, but InputDispatcher marks it non-interactive.
            InputReach.interactiveProbe = { true }
            InputReach.dumpsysRunner = {
                com.kilon.agentvscreen.host.ShizukuManager.ProcessResult(0, "INPUT MANAGER (dumpsys input)\n  Display not interactive: {0, 37}\n", "")
            }
            val blocked = InputReach.probeDirect(37)
            assertEquals(InputReach.Reach.BLOCKED, blocked.reach)
            assertEquals("dumpsys", blocked.via)
            // A dump without the line falls back to the power-group answer.
            InputReach.dumpsysRunner = { com.kilon.agentvscreen.host.ShizukuManager.ProcessResult(0, "INPUT MANAGER (dumpsys input)\n", "") }
            val fallback = InputReach.probeDirect(37)
            assertEquals(InputReach.Reach.OK, fallback.reach)
            assertEquals("isInteractive", fallback.via)
            // Neither source readable: unknown.
            InputReach.dumpsysRunner = { null }
            InputReach.interactiveProbe = { null }
            assertEquals(InputReach.Reach.UNKNOWN, InputReach.probeDirect(37).reach)
        } finally {
            VScreenAwake.setScreenOffForTest(false)
            InputReach.resetForTest()
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
            listOf("look"),
            listOf("look", "0,0,100,100"),
            listOf("zoom", "0,0,100,100"),
            listOf("frames"),
            listOf("spots"),
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

    // ── Fix 14 (#60): Direct target-display wake readiness probe ─────────────

    @Test
    fun wakePhoneForInputWaitsForDirectVirtualDisplayReadinessAcrossDelayedBlockedAndTimeoutCases() {
        val ctx = RuntimeEnvironment.getApplication()
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)!!
        try {
            // Case 1: Main screen reports interactive on poll 1 (screenOff -> false), while virtual display 37
            // stays non-interactive for 2 polls and recovers on poll 3. Must NOT return early on poll 1!
            VScreenAwake.resetWakeForTest(ctx)
            InputReach.resetForTest()
            InputReach.dumpsysRunner = { null }
            VScreenStore.setPhoneScreenMode(VScreenStore.PhoneScreenMode.WAKE_WHEN_NEEDED)
            VScreenAwake.setScreenOffForTest(true)
            var now = 100_000L
            VScreenAwake.monoClockMs = { now }
            VScreenAwake.sleepFn = { step -> now += step }
            VScreenAwake.wakeTimeoutMs = 2_000L
            VScreenAwake.wakePollStepMs = 100L
            VScreenAwake.isInteractiveFn = { true } // main screen turns interactive immediately!
            val createdLocks = ArrayList<Triple<Int, String, android.os.PowerManager.WakeLock>>()
            VScreenAwake.wakeLockFactory = { _, flags, tag ->
                pm.newWakeLock(flags, tag).also { createdLocks += Triple(flags, tag, it) }
            }
            var probes = 0
            InputReach.interactiveProbe = { displayId ->
                assertEquals(37, displayId)
                probes++
                probes >= 3
            }

            val errDelayed = VScreenAwake.wakePhoneForInputDisplay(37)
            assertNull("Delayed display recovery must succeed once display 37 probes interactive: $errDelayed", errDelayed)
            assertEquals("Must bypass !screenOff shortcut and probe display 37 until poll 3", 3, probes)
            val wakeLockEntry = createdLocks.first { it.second == "agentvscreen:wake-for-input" }
            @Suppress("DEPRECATION")
            assertTrue((wakeLockEntry.first and android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0)
            assertFalse("Active wakeInputLock must be released immediately after wake completes", wakeLockEntry.third.isHeld)

            // Case 2: Main screen wakes, but virtual display 37 remains persistently BLOCKED -> must timeout with error!
            VScreenAwake.resetWakeForTest(ctx)
            InputReach.resetForTest()
            InputReach.dumpsysRunner = { null }
            VScreenStore.setPhoneScreenMode(VScreenStore.PhoneScreenMode.WAKE_WHEN_NEEDED)
            VScreenAwake.setScreenOffForTest(true)
            now = 200_000L
            VScreenAwake.monoClockMs = { now }
            VScreenAwake.sleepFn = { step -> now += step }
            VScreenAwake.wakeTimeoutMs = 500L
            VScreenAwake.wakePollStepMs = 100L
            VScreenAwake.isInteractiveFn = { true }
            var persistentProbes = 0
            InputReach.interactiveProbe = {
                persistentProbes++
                false
            }
            val errBlocked = VScreenAwake.wakePhoneForInputDisplay(37)
            assertNotNull("Persistently blocked virtual display MUST fail rather than letting input proceed", errBlocked)
            assertTrue("Should probe multiple times before timing out", persistentProbes >= 4)

            // Case 3: Main screen itself never becomes interactive -> times out cleanly with error.
            VScreenAwake.resetWakeForTest(ctx)
            InputReach.resetForTest()
            InputReach.dumpsysRunner = { null }
            VScreenStore.setPhoneScreenMode(VScreenStore.PhoneScreenMode.WAKE_WHEN_NEEDED)
            VScreenAwake.setScreenOffForTest(true)
            now = 300_000L
            VScreenAwake.monoClockMs = { now }
            VScreenAwake.sleepFn = { step -> now += step }
            VScreenAwake.wakeTimeoutMs = 300L
            VScreenAwake.wakePollStepMs = 100L
            VScreenAwake.isInteractiveFn = { false }
            InputReach.interactiveProbe = { false }
            val errMainTimeout = VScreenAwake.wakePhoneForInputDisplay(37)
            assertNotNull("Timeout when main screen never wakes must return error", errMainTimeout)
        } finally {
            VScreenAwake.resetWakeForTest()
            InputReach.resetForTest()
            InputReach.dumpsysRunner = { null }
            VScreenStore.setPhoneScreenMode(VScreenStore.PhoneScreenMode.ALLOW_SLEEP)
        }
    }

    // ── Fix 15 (#48/#60): Separate keepOnLock never reuses ACQUIRE_CAUSES_WAKEUP ──

    @Test
    @Suppress("DEPRECATION")
    fun keepOnLockNeverReusesAcquireCausesWakeupAfterModeSwitchTurnEndOrThermalRelease() {
        val ctx = RuntimeEnvironment.getApplication()
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)!!
        try {
            VScreenAwake.resetWakeForTest(ctx)
            InputReach.resetForTest()
            InputReach.dumpsysRunner = { null }
            VScreenStore.setPhoneScreenMode(VScreenStore.PhoneScreenMode.WAKE_WHEN_NEEDED)
            VScreenAwake.setScreenOffForTest(true)
            var now = 400_000L
            VScreenAwake.monoClockMs = { now }
            VScreenAwake.sleepFn = { step -> now += step }
            VScreenAwake.isInteractiveFn = { true }
            InputReach.interactiveProbe = { true }

            val created = ArrayList<Triple<Int, String, android.os.PowerManager.WakeLock>>()
            VScreenAwake.wakeLockFactory = { _, flags, tag ->
                pm.newWakeLock(flags, tag).also { created += Triple(flags, tag, it) }
            }

            // 1. Active wake under WAKE_WHEN_NEEDED uses dedicated wakeInputLock with ACQUIRE_CAUSES_WAKEUP.
            assertNull(VScreenAwake.wakePhoneForInputDisplay(37))
            val wakeEntry = created.single { it.second == "agentvscreen:wake-for-input" }
            assertTrue((wakeEntry.first and android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0)
            assertFalse("wakeInputLock must be released on handoff", wakeEntry.third.isHeld)

            // 2. Switch mode to KEEP_ON, end turn (releaseScreenLock), simulate thermal release (suspendForHeat).
            VScreenStore.setPhoneScreenMode(VScreenStore.PhoneScreenMode.KEEP_ON)
            VScreenAwake.releaseScreenLock()
            VScreenAwake.suspendForHeat()

            // 3. Next ordinary holdScreenLock MUST create/reuse plain keepOnLock WITHOUT ACQUIRE_CAUSES_WAKEUP!
            VScreenAwake.holdScreenLock(ctx)
            val keepEntry = created.last { it.second == "agentvscreen:keep-screen-on" }
            assertEquals(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK, keepEntry.first)
            assertEquals(0, keepEntry.first and android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP)
            assertEquals(0, VScreenAwake.lastKeepOnLockFlags and android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP)
            assertTrue("keepOnLock must be held", keepEntry.third.isHeld)
            assertFalse("wakeInputLock must NEVER be re-acquired by ordinary keep-on", wakeEntry.third.isHeld)
            assertTrue("keepOnLock and wakeInputLock must be distinct WakeLock instances", keepEntry.third !== wakeEntry.third)
        } finally {
            VScreenAwake.resetWakeForTest()
            InputReach.resetForTest()
            InputReach.dumpsysRunner = { null }
            VScreenStore.setPhoneScreenMode(VScreenStore.PhoneScreenMode.ALLOW_SLEEP)
        }
    }

    @Test
    fun noticesGoIntoJsonRepliesAndBeforeTextReplies() {
        val json = "{\"version\":\"2.0.1-beta1\",\"ready\":true}\n"
        val out = com.kilon.agentvscreen.api.ApiServer.withNotices(json, listOf("NEWS"), "HINT")
        val o = org.json.JSONObject(out.trim())
        assertEquals("2.0.1-beta1", o.getString("version"))
        assertTrue(o.getBoolean("ready"))
        assertEquals("NEWS", o.getJSONArray("notices").getString(0))
        assertEquals("HINT", o.getJSONArray("notices").getString(1))

        val text = com.kilon.agentvscreen.api.ApiServer.withNotices("OK\n", listOf("NEWS", "PERM"), null)
        assertEquals("NEWS\nPERM\nOK\n", text)
        assertEquals("OK\n\nHINT\n", com.kilon.agentvscreen.api.ApiServer.withNotices("OK\n", emptyList(), "HINT"))
        assertEquals("{not json", com.kilon.agentvscreen.api.ApiServer.withNotices("{not json", emptyList(), null))
    }
}
