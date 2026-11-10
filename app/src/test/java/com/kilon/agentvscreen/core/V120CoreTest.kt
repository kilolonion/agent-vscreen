package com.kilon.agentvscreen.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 1.2: what is on a display comes only from the system's task list; injection goes through one seam; phases; vendors. */
@RunWith(RobolectricTestRunner::class)
class V120CoreTest {
    private val savedSource = VScreenShell.taskSource
    private val savedInjector = VScreenShell.injector
    private fun t(id: Int, comp: String, visible: Boolean = true, type: Int = 1) = VScreenRemoteDisplay.DisplayTask(id, comp, visible, type, null)
    private val tasks = mapOf(
        0 to listOf(t(36165, "com.tencent.mm/.ui.LauncherUI"), t(36170, "com.example.notes/.Main", visible = false), t(1, "com.android.launcher3/.Home", type = 2)),
        4 to listOf(t(36200, "com.taobao.taobao/.Welcome")),
        7 to emptyList())

    @Before fun setUp() { VScreenShell.taskSource = { id -> tasks[id] } }
    @After fun tearDown() { VScreenShell.taskSource = savedSource; VScreenShell.injector = savedInjector }

    @Test fun eachDisplayHasItsOwnTasks() {
        assertEquals("com.taobao.taobao/.Welcome", VScreenShell.topActivity(4))
        assertEquals("com.tencent.mm/.ui.LauncherUI", VScreenShell.topActivity(0))
        assertEquals(36200, VScreenShell.taskIdOn(4, "com.taobao.taobao"))
        assertNull(VScreenShell.taskIdOn(4, "com.tencent.mm"))
    }

    @Test fun anEmptyOrUnknownDisplayHasNoAppAndIsNeverGuessed() {
        assertNull(VScreenShell.topActivity(7))
        assertNull(VScreenShell.topPackage(7))
        // The list cannot be read (service gone): unknown, not "the main screen's app".
        assertNull(VScreenShell.topActivity(99))
        assertTrue(VScreenHandoff.tasksOn(99).isEmpty())
    }

    @Test fun handoffTasksSkipHomeAndKeepOrder() {
        val l = VScreenHandoff.tasksOn(0)
        assertEquals(listOf(36165, 36170), l.map { it.taskId })
        assertTrue(l[0].visible); assertFalse(l[1].visible)
    }

    @Test fun protectedPackagesAreNeverForceStopped() {
        VScreenShell.protectedPackages = setOf("com.kilon.agentvscreen", "com.android.systemui")
        val o = VScreenShell.stop(7, "com.kilon.agentvscreen")
        assertFalse(o.ok); assertEquals("PROTECTED_APP", o.code)
    }

    @Test fun everyGestureGoesThroughTheOneInjector() {
        val seen = ArrayList<String>()
        VScreenShell.injector = object : VScreenShell.Injector {
            override fun gesture(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int): Result<Boolean> { seen += "g $displayId $x1,$y1>$x2,$y2 $durationMs $holdMs"; return Result.success(true) }
            override fun key(displayId: Int, code: Int): Result<Boolean> { seen += "k $displayId $code"; return Result.success(true) }
            override fun text(displayId: Int, text: String): Result<Boolean> { seen += "t $displayId $text"; return Result.success(true) }
            override fun pointer(displayId: Int, op: Int, pointerId: Int, x: Float, y: Float): Result<Pair<Boolean, Int>> { seen += "p $op"; return Result.success(true to 1) }
        }
        assertTrue(VScreenShell.tap(4, 10, 20).ok)
        assertTrue(VScreenShell.swipe(4, 1, 2, 3, 4, 300).ok)
        assertTrue(VScreenShell.key(4, "back").ok)
        assertTrue(VScreenShell.typeKeys(4, "abc").ok)
        assertEquals(4, seen.size)
        assertTrue(seen[0], seen[0].startsWith("g 4 10,20>10,20"))
        assertTrue(seen[1], seen[1].endsWith(" -1"))          // a swipe is a fling: no hold at the end
        assertEquals("k 4 4", seen[2])
        assertEquals("t 4 abc", seen[3])
    }

    @Test fun injectionFailureIsSaidNotSwallowed() {
        VScreenShell.injector = object : VScreenShell.Injector {
            override fun gesture(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int): Result<Boolean> = Result.failure(IllegalStateException("service gone"))
            override fun key(displayId: Int, code: Int): Result<Boolean> = Result.success(false)
            override fun text(displayId: Int, text: String): Result<Boolean> = Result.success(false)
            override fun pointer(displayId: Int, op: Int, pointerId: Int, x: Float, y: Float): Result<Pair<Boolean, Int>> = Result.success(false to 0)
        }
        val o = VScreenShell.tap(4, 1, 1)
        assertFalse(o.ok); assertTrue(o.code, o.code in setOf("NOT_CONNECTED", "INPUT_FAILED"))
        assertFalse(VScreenShell.key(4, "enter").ok)
    }

    @Test fun keysThatLeaveTheScreenDoNotExist() {
        assertNull(VScreenShell.keyCode("home")); assertNull(VScreenShell.keyCode("3")); assertNull(VScreenShell.keyCode("26")); assertNull(VScreenShell.keyCode("187"))
        assertEquals(4, VScreenShell.keyCode("back")); assertEquals(66, VScreenShell.keyCode("ENTER")); assertEquals(61, VScreenShell.keyCode("61"))
    }

    @Test fun phaseIsDerivedAndTheGateIsOneTable() {
        fun ph(connected: Boolean = true, screen: Boolean = true, owner: String? = "a", takeover: String? = null, stopped: Boolean = false, idle: Boolean = false, parked: Boolean = false, sid: String? = "a") =
            ScreenPhase.of(connected, screen, owner, takeover, stopped, idle, parked, sid)
        assertEquals(ScreenPhase.ACTIVE, ph())
        assertEquals(ScreenPhase.STOPPED, ph(stopped = true, connected = false))   // the user's stop wins over everything
        assertEquals(ScreenPhase.OFFLINE, ph(connected = false))
        assertEquals(ScreenPhase.NO_SCREEN, ph(screen = false))
        assertEquals(ScreenPhase.TAKEN_OVER, ph(takeover = "a"))
        assertEquals(ScreenPhase.FOREIGN, ph(owner = "b"))
        assertEquals(ScreenPhase.IDLE, ph(owner = "b", idle = true))                 // an idle screen may be taken by another session
        assertEquals(ScreenPhase.PARKED, ph(idle = true, parked = true))
        assertEquals("USER_STOPPED", ScreenPhase.refusal(ScreenPhase.STOPPED, creates = true))
        assertEquals("BUSY", ScreenPhase.refusal(ScreenPhase.FOREIGN, creates = false))
        assertEquals("NO_SCREEN", ScreenPhase.refusal(ScreenPhase.NO_SCREEN, creates = false))
        assertNull(ScreenPhase.refusal(ScreenPhase.NO_SCREEN, creates = true))
        for (p in listOf(ScreenPhase.ACTIVE, ScreenPhase.IDLE, ScreenPhase.PARKED)) assertNull(ScreenPhase.refusal(p, creates = false))
    }

    @Test fun makersMapToOneLayer() {
        assertEquals("vivo", Vendor.keyOf("vivo", "iQOO")); assertEquals("honor", Vendor.keyOf("HONOR", "HONOR"))
        assertEquals("xiaomi", Vendor.keyOf("Xiaomi", "Redmi")); assertEquals("oppo", Vendor.keyOf("OnePlus", "OnePlus").let { Vendor.keyOf("x", "oneplus") })
        assertTrue(Vendor.of("vivo").hints().isNotEmpty())
        assertTrue(Vendor.of("nobody").hints().isEmpty())
        assertTrue(Vendor.of("nobody").downscaleHint().isNotEmpty())
    }
}
