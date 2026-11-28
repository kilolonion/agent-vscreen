package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.cmd.CommandSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 1.4: what agents are told they can do comes from one table ([Abilities]). These tests keep every text and file that
 * discloses it in step with the table, and fail when a command ships without being disclosed.
 *
 * Generated files (API.md ability block, the Operit advice) are rewritten by [generatedFilesMatch] when they differ;
 * the test then fails once so the change shows up in the diff. Run the tests again and they pass.
 */
@RunWith(RobolectricTestRunner::class)
class AbilitiesTest {
    @Before fun init() { ApiConfig.init(RuntimeEnvironment.getApplication()) }
    @After fun lang() { I18n.choice = I18n.Lang.ZH }

    @Test fun everyCommandIsDisclosedExactlyOnce() {
        assertEquals("commands no ability tells agents about", emptyList<String>(), Abilities.undisclosed())
        val names = CommandSpec.commands.map { it.name }.toSet()
        val owned = Abilities.all.flatMap { it.commands }
        assertEquals("a command in two abilities", owned.size, owned.toSet().size)
        for (c in owned) assertTrue("ability names a command that does not exist: $c", c in names)
        assertEquals(Abilities.all.size, Abilities.all.map { it.id }.toSet().size)
        for (a in Abilities.all) {
            assertTrue(a.id, a.since <= a.changed && a.changed <= BuildConfig.VERSION_CODE)
            for (l in listOf(I18n.Lang.ZH, I18n.Lang.EN)) {
                I18n.choice = l
                assertTrue(a.id, a.whenText.isNotBlank() && a.can.isNotBlank() && a.how.isNotBlank())
                // The note line must name a command, so the agent can act on the line alone.
                assertTrue("${a.id}: can must name a command", a.commands.any { c -> a.can.contains(c) } || a.can.contains("act"))
            }
        }
    }

    @Test fun everyTextAnAgentReadsCarriesEveryAbility() {
        for (l in listOf(I18n.Lang.ZH, I18n.Lang.EN)) {
            I18n.choice = l
            val texts = mapOf(
                "memory" to AgentGuide.memory(), "memoryMcp" to AgentGuide.memoryMcp(), "memoryHttp" to AgentGuide.memoryHttp(),
                "guide" to AgentGuide.guide(), "skill" to AgentGuide.skill(), "advice" to AgentGuide.advice(),
            )
            for ((name, text) in texts) for (a in Abilities.all) assertTrue("$name ($l) lacks ${a.id}", text.contains(a.whenText))
            // Timed jobs were the ability agents missed in 1.3: the note must say it in the user's words.
            assertTrue(AgentGuide.memory().contains(if (l == I18n.Lang.ZH) "每天" else "daily"))
            assertTrue(AgentGuide.memory().contains("schedule"))
            // MCP / HTTP agents are not told to type `vscreen …`.
            assertTrue(!AgentGuide.memoryMcp().contains("`vscreen schedule"))
        }
    }

    @Test fun theUpdateNoticeCarriesTheNewNote() {
        // An agent whose note is from before an ability changed gets the whole new note to save in its place.
        val old = Abilities.changedSince(13)
        assertTrue(old.any { it.id == "schedule" })
        assertTrue(Abilities.changedSince(BuildConfig.VERSION_CODE).isEmpty())
    }

    // ── generated files ──────────────────────────────────────────────

    private fun block(): String {
        fun list(): String = Abilities.all.joinToString("\n") { "- **" + it.whenText + "**" + com.kilon.agentvscreen.trPair("：", ": ") + it.can }
        I18n.choice = I18n.Lang.ZH; val zh = list()
        I18n.choice = I18n.Lang.EN; val en = list()
        I18n.choice = I18n.Lang.ZH
        return "$zh\n\u0000$en"
    }

    private fun replaceBetween(text: String, start: String, end: String, body: String): String {
        val a = text.indexOf(start); val b = text.indexOf(end)
        require(a >= 0 && b > a) { "markers $start / $end missing" }
        return text.substring(0, a + start.length) + "\n" + body + "\n" + text.substring(b)
    }

    @Test fun generatedFilesMatch() {
        val root = File("..")
        if (!File(root, "API.md").isFile) return
        val (zh, _) = block().split("\n\u0000")
        // 1.4.0-beta2: a test only checks; the files are rewritten only when asked (AVS_REGEN=1 ./build.sh regen).
        val regen = System.getenv("AVS_REGEN") == "1"
        val changed = ArrayList<String>()
        fun sync(f: File, start: String, end: String, body: String) {
            val now = f.readText()
            val want = replaceBetween(now, start, end, body)
            if (want != now) { if (regen) f.writeText(want); changed += f.path }
        }
        sync(File(root, "API.md"), "<!-- abilities:start -->", "<!-- abilities:end -->", zh)
        // Operit: the usage_advice tool's description (JSON inside the METADATA comment; newlines as literal \n).
        val js = File(root, "integrations/operit/dist/packages/agent_vscreen.js")
        if (js.isFile) {
            // JSON string by hand (Robolectric's org.json would write "\/" for every slash).
            fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
            I18n.choice = I18n.Lang.ZH; val az = AgentGuide.advice()
            I18n.choice = I18n.Lang.EN; val ae = AgentGuide.advice()
            I18n.choice = I18n.Lang.ZH
            val lines = js.readLines().toMutableList()
            val i = lines.indexOfFirst { it.trimStart().startsWith("\"zh\": \"Agent-VScreen 使用建议") }
            require(i >= 0 && lines[i + 1].trimStart().startsWith("\"en\": \"Agent-VScreen advice")) { "Operit advice lines not found" }
            val pad = lines[i].takeWhile { it == ' ' }
            val want = listOf("$pad\"zh\": ${q(az)},", "$pad\"en\": ${q(ae)}")
            if (lines.subList(i, i + 2) != want) { lines[i] = want[0]; lines[i + 1] = want[1]; if (regen) js.writeText(lines.joinToString("\n") + "\n"); changed += js.path }
        }
        assertTrue("out of date, generated from Abilities (./build.sh regen rewrites them): $changed", regen || changed.isEmpty())
    }
}
