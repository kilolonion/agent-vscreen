package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.host.ShizukuManager.ProcessResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionTasksCoreTest {
    private val source = VScreenShell.taskSource
    private val focus = VScreenShell.taskFocus
    private val runner = VScreenShell.runner

    @After fun restore() {
        VScreenShell.taskSource = source
        VScreenShell.taskFocus = focus
        VScreenShell.runner = runner
    }

    private fun task(id: Int, pkg: String, visible: Boolean, type: Int = 1, top: String? = null) =
        VScreenRemoteDisplay.DisplayTask(id, "$pkg/.Home", visible, type, top)

    @Test fun keepFocusesExactTaskRatherThanSendingALauncherIntent() {
        val a = task(4, "example.a", true)
        val b = task(5, "example.b", false)
        var rows = listOf(a, b)
        VScreenShell.taskSource = { if (it == 7) rows else error("unexpected display $it") }
        VScreenShell.runner = { _, _ -> error("KEEP must not issue a shell command for an existing task") }
        val calls = mutableListOf<Pair<Int, Int>>()
        VScreenShell.taskFocus = { id, d ->
            calls += id to d
            rows = listOf(b.copy(visible = true), a.copy(visible = false))
            Result.success(true)
        }
        assertTrue(VScreenShell.launch(7, "example.b", null, VScreenShell.LaunchMode.KEEP).ok)
        assertTrue(VScreenShell.launch(7, "example.b", null, VScreenShell.LaunchMode.KEEP).ok)
        assertEquals(listOf(5 to 7), calls)
    }

    @Test fun keepStartsMissingTaskOnTheSameDisplay() {
        var rows = listOf(task(4, "example.a", true))
        val calls = mutableListOf<List<String>>()
        VScreenShell.taskSource = { if (it == 7) rows else null }
        VScreenShell.taskFocus = { _, _ -> error("must not focus another display's task") }
        VScreenShell.runner = { argv, _ ->
            calls += argv.toList()
            if (argv.first() == "cmd") ProcessResult(0, "example.b/.Home", "")
            else {
                rows = listOf(task(5, "example.b", true), task(4, "example.a", false))
                ProcessResult(0, "Status: ok", "")
            }
        }
        assertTrue(VScreenShell.launch(7, "example.b", null, VScreenShell.LaunchMode.KEEP).ok)
        assertEquals(listOf("am", "start", "-W", "--display", "7", "--windowingMode", "1", "-f", "0x18000000", "-n", "example.b/.Home"), calls.last())
        assertEquals(listOf(5, 4), rows.map { it.taskId })
    }

    @Test fun keepDoesNotPretendItSwitchedWhenFocusWasRefused() {
        VScreenShell.taskSource = { listOf(task(4, "example.a", true), task(5, "example.b", false)) }
        VScreenShell.taskFocus = { _, _ -> Result.success(false) }
        VScreenShell.runner = { _, _ -> error("must not relaunch") }
        assertEquals("LAUNCH_NOT_ON_TOP", VScreenShell.launch(7, "example.b", null, VScreenShell.LaunchMode.KEEP).code)
    }

    @Test fun tasksUseBasePackageAndOnlyMarkOneActualForegroundTask() {
        VScreenShell.taskSource = {
            when (it) {
                7 -> listOf(task(1, "launcher", true, type = 2), task(4, "example.a", false),
                    task(5, "example.b", true, top = "other.package/.Picker"), task(6, "example.c", true))
                8 -> listOf(task(9, "parked.app", false))
                9 -> emptyList()
                else -> null
            }
        }
        val tasks = VScreenHandoff.tasksOrNull(7)!!
        assertEquals(listOf("example.b", "example.a", "example.c"), tasks.map { it.pkg })
        assertEquals(listOf(5), tasks.filter { it.foreground }.map { it.taskId })
        assertTrue(VScreenHandoff.tasksOn(8).none { it.foreground })
        assertEquals(emptyList<VScreenHandoff.TaskRef>(), VScreenHandoff.tasksOrNull(9))
        assertNull(VScreenHandoff.tasksOrNull(10))
    }
}
