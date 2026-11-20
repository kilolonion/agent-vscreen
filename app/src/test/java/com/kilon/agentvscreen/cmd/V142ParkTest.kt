package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.Abilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * 1.4.2: an app can be parked on a hidden parking display and brought back later (handoff park / unpark), the screen
 * can be left exactly as it is (launch --keep), and the screen's own task list is readable (tasks). These check the
 * contract an agent sees: the command table, the code table, the ability table and the help text.
 */
class V142ParkTest {

    private val language = I18n.choice
    @After fun restoreLanguage() { I18n.choice = language }

    private fun <T> zh(block: () -> T): T { I18n.choice = I18n.Lang.ZH; return block() }
    private fun <T> en(block: () -> T): T { I18n.choice = I18n.Lang.EN; return block() }

    @Test fun launchTakesKeepAndItIsNotACompatibilityShell() {
        val c = CommandSpec.of("launch") ?: error("no launch")
        val keep = c.allOpts.firstOrNull { it.name == "keep" } ?: error("launch --keep missing")
        assertEquals(CommandSpec.Kind.SWITCH, keep.kind)
        assertTrue(zh { keep.text }.contains("重建"))
        // --keep holds the geometry, so the two that change it are refused together (checked in launchCmd).
        assertNotNull(c.allOpts.firstOrNull { it.name == "landscape" })
        assertNotNull(c.allOpts.firstOrNull { it.name == "portrait" })
        assertTrue(zh { c.body.joinToString(" ") }.contains("--keep"))
    }

    @Test fun handoffNamesParkAndUnpark() {
        val c = CommandSpec.of("handoff") ?: error("no handoff")
        for (w in listOf("park", "unpark", "in", "out")) assertTrue("handoff syntax lacks $w", zh { c.positional }.contains(w))
        // park / unpark work on the background screen only: they run in the screen queue like every screen command.
        assertTrue(c.screen)
        // Taking the user's app (in) needs system; parking does not.
        assertEquals(CommandSpec.Scope.SYSTEM, c.scopeFor(VScreenOptions.args("handoff", listOf("in", "com.x"))))
        assertEquals(CommandSpec.Scope.OPERATE, c.scopeFor(VScreenOptions.args("handoff", listOf("park", "com.x"))))
        assertEquals(CommandSpec.Scope.OPERATE, c.scopeFor(VScreenOptions.args("handoff", listOf("unpark", "com.x"))))
        assertTrue(zh { c.body.joinToString(" ") }.contains("unpark"))
    }

    @Test fun tasksIsAReadOfTheScreensOwnList() {
        val c = CommandSpec.of("tasks") ?: error("no tasks command")
        assertEquals(CommandSpec.Scope.LOOK, c.scope)
        assertEquals("", c.positional)
        assertTrue(zh { c.body.joinToString(" ") }.contains("前台"))
        assertTrue(en { c.summary }.contains("task"))
    }

    @Test fun appParkedIsAFixTheAgentItselfUndoes() {
        val code = Codes.of("APP_PARKED") ?: error("APP_PARKED missing from the code table")
        // The agent unparks it itself, so the next move is fix_command, not "ask the user".
        assertEquals(Codes.Next.FIX, code.next)
        assertTrue(code.text.contains("unpark"))
        // handoff keeps its own two codes; parking does not invent new ones.
        assertNotNull(Codes.of("HANDOFF_DENIED")); assertNotNull(Codes.of("HANDOFF_NO_TASK"))
        assertNull(Codes.of("PARK_DENIED"))
        assertNull(Codes.of("UNPARK_FAILED"))
    }

    @Test fun parkingIsDisclosedAndTheScreenListToo() {
        val a = Abilities.of("tasks") ?: error("tasks told to nobody")
        assertEquals("park", a.id)
        assertNotNull(Abilities.of("launch"))
        assertTrue(a.can.contains("handoff park/unpark"))
        assertEquals("commands in two abilities", Abilities.all.size, Abilities.all.map { it.id }.toSet().size)
        assertEquals("commands no ability tells agents about", emptyList<String>(), Abilities.undisclosed())
        // The parked app is not on the screen: nothing tells an agent that `tasks` shows it.
        assertTrue(zh { CommandSpec.of("tasks")!!.body.joinToString(" ") }.contains("停放屏（handoff park）的应用不在这里"))
        assertTrue(en { CommandSpec.of("tasks")!!.body.joinToString(" ") }.contains("parked with handoff park"))
    }

    @Test fun helpShowsTheNewWords() {
        assertTrue(zh { CommandSpec.quickHelp() }.contains("tasks"))
        assertTrue(zh { CommandSpec.helpFor("handoff") }.orEmpty().contains("park"))
        assertTrue(zh { CommandSpec.fullHelp() }.contains("handoff park <应用>"))
        assertTrue(zh { CommandSpec.fullHelp() }.contains("--keep"))
        assertTrue(zh { Codes.help() }.contains("APP_PARKED (fix_command)"))
        assertTrue(en { CommandSpec.fullHelp() }.contains("handoff park <app>"))
        assertTrue(en { Codes.help() }.contains("APP_PARKED (fix_command)"))
    }
}
