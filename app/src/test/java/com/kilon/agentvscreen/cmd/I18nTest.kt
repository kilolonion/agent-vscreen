package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.AgentGuide
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** 1.1.7: one language switch for the agent-facing texts. */
@RunWith(RobolectricTestRunner::class)
class I18nTest {
    @After fun reset() { I18n.choice = I18n.Lang.AUTO }

    private fun opts(help: String, cmd: String): Set<String> {
        val out = ArrayList<String>(); var take = false
        for (l in help.lines()) {
            if (l.startsWith("  $cmd ") || l == "  $cmd") { take = true; out += l; continue }
            if (take) { if (l.startsWith("        ")) out += l else take = false }
        }
        return Regex("--([a-z][a-z0-9-]*)").findAll(out.joinToString("\n")).map { it.groupValues[1] }.toSet()
    }

    @Test fun englishHelpListsTheSameOptionsAsChinese() {
        for (c in listOf("launch", "act", "note", "events", "title", "done", "keep", "stop", "release", "handoff", "status", "apps", "spots", "zoom", "collect", "fix"))
            assertEquals(c, opts(VScreenOffloadHandler.HELP_ZH, c), opts(VScreenOffloadHandler.HELP_EN, c))
    }

    @Test fun englishModeSpeaksEnglish() {
        I18n.choice = I18n.Lang.EN
        assertTrue(VScreenOffloadHandler.HELP.startsWith("vscreen — Agent-VScreen background operation"))
        assertEquals("[Error]", Blocks.error)
        val e = runCatching { Steps.parse("tap 1 2 3") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(e, e.contains("a single number"))
        com.kilon.agentvscreen.api.ApiConfig.init(RuntimeEnvironment.getApplication())
        val m = AgentGuide.memory("http://100.64.0.1:18765")
        assertTrue(m, m.contains("100.64.0.1"))
        assertFalse(m, Regex("[\\u4e00-\\u9fa5]").containsMatchIn(m))
    }

    @Test fun chineseModeSpeaksChinese() {
        I18n.choice = I18n.Lang.ZH
        assertEquals("【错误】", Blocks.error)
        assertTrue(VScreenOffloadHandler.QUICK_HELP.contains("看一眼"))
    }
}
