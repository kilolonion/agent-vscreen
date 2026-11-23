package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.tr

/**
 * 1.3 beta4: an agent keeps what it learned about vscreen in its own memory or skill, and after an app update that
 * note can be out of date. Every agent (token) confirms the version its note is for with `remembered`; while that is
 * older than the app, the agent's next command carries a short notice: what changed for agents since its version,
 * and how to refresh its note. The same for the shell, MCP and HTTP (it is in the command output), so no client needs
 * anything of its own. The app shows the user which version each agent's note is for.
 *
 * Only what changes how an agent should work is news, not every fix: it is written beside the ability it changes.
 */
object AgentNews {
    /** Version code → name, for "your note is from …" (a table of names, not of news). */
    private val NAMES: Map<Int, String> = mapOf(
        15 to "1.3.0-beta2",
        17 to "1.3.0-beta4",
        18 to "1.3.0-beta5",
        19 to "1.3.0-beta6",
        20 to "1.3.0-beta7",
        21 to "1.3.0-beta8",
        22 to "1.3.0-beta9",
        23 to "1.3.0-beta10",
        25 to "1.3.0-beta12",
        26 to "1.3.0-beta13",
        27 to "1.3.0-beta14",
        28 to "1.3.0-beta15",
        29 to "1.3.0-beta16",
        30 to "1.3.0-beta17",
        31 to "1.3.0-beta18",
        32 to "1.3.0-beta19",
        33 to "1.3.0",
        34 to "1.4.0-beta1",
        35 to "1.4.0-beta2",
        36 to "1.4.0-beta3",
        37 to "1.4.0-beta4",
        38 to "1.4.0-beta5",
        39 to "1.4.0-beta6",
        40 to "1.4.0-beta7",
        41 to "1.4.0-beta8",
        42 to "1.4.0-beta9",
        43 to "1.4.0-beta10",
        44 to "1.4.0-beta11",
        45 to "1.4.0-beta12",
        46 to "1.4.0-beta13",
        47 to "1.4.0-beta14",
        48 to "1.4.0-beta15",
        49 to "1.4.0",
        50 to "1.4.1-beta1",
        51 to "1.4.1",
        52 to "1.4.2-beta1",
        53 to "1.4.2-beta2",
        54 to "1.4.2-beta3",
        55 to "1.4.2-beta4",
        56 to "1.4.2-beta5",
        57 to "1.4.2-beta6",
        58 to "2.0.0-beta1",
        59 to "2.0.0-beta2",
        60 to "2.0.0-beta3",
        61 to "2.0.0-beta4",
        62 to "2.0.0-beta5",
    )

    /**
     * 1.4.0-beta2: what changed for agents since [since] comes from the ability table: each ability that changed after
     * that version brings its own news line (Abilities.Ability.news). No list to keep by hand beside the table.
     */
    fun since(since: Int): List<Abilities.Ability> = Abilities.changedSince(since).filter { it.changed <= BuildConfig.VERSION_CODE }

    fun nameOf(code: Int): String = NAMES.filterKeys { it <= code }.maxByOrNull { it.key }?.value ?: tr("更早的版本", "an earlier version")

    /**
     * The notice, or null when this agent's note is current or it saw the notice a moment ago.
     *
     * 1.4: abilities added or reworded since the agent's version come from [Abilities] (nobody has to remember to
     * write them here), and when any did, the notice carries the whole new note for the agent to save in place of the
     * old one: agents that were asked to "re-read the guide and update your note" mostly kept the old note.
     */
    fun notice(tokenId: String, freshStretch: Boolean, via: Abilities.Via = Abilities.Via.CLI): String? {
        val ack = AgentLedger.ackVersion(tokenId)
        if (ack >= BuildConfig.VERSION_CODE) return null
        val abilities = since(ack)
        if (abilities.isEmpty()) return null
        if (!AgentLedger.nudgeDue(tokenId, freshStretch)) return null
        val lines = abilities.mapNotNull { a -> a.news.takeIf { it.isNotEmpty() }?.let { "- $it" } }
        val was = if (ack <= 0) tr("你确认过的版本未知", "the version your note is for is unknown") else tr("你的说明是 ${nameOf(ack)} 时记下的", "your note is from ${nameOf(ack)}")
        val remembered = if (via == Abilities.Via.CLI) "vscreen remembered \"<存在哪里>\"" else "remembered <存在哪里>"
        val rememberedEn = if (via == Abilities.Via.CLI) "vscreen remembered \"<where>\"" else "remembered <where>"
        return tr(
            "【Agent-VScreen 已更新到 ${BuildConfig.VERSION_NAME}，$was】\n" +
                (if (lines.isNotEmpty()) "用法上的变化：\n${lines.joinToString("\n")}\n" else "") +
                (if (abilities.isNotEmpty()) "能力有新增或变化（${abilities.joinToString("、") { it.id }}）。请把你长期记忆或 skill 里关于 Agent-VScreen 的说明整段换成下面这段（原样保存，不要改写成摘要）：\n「${AgentGuide.memoryFor(via)}」\n"
                    else "请执行 guide 重读一遍用法，按需更新你记下的说明。\n") +
                "存好后执行 $remembered，之后不再提醒。\n",
            "[Agent-VScreen was updated to ${BuildConfig.VERSION_NAME}; $was]\n" +
                (if (lines.isNotEmpty()) "Changes in use:\n${lines.joinToString("\n")}\n" else "") +
                (if (abilities.isNotEmpty()) "Abilities were added or changed (${abilities.joinToString(", ") { it.id }}). Replace what your long-term memory or skill says about Agent-VScreen with the note below, whole (save it as it is, not as a summary):\n\"${AgentGuide.memoryFor(via)}\"\n"
                    else "Run guide to read the usage again and update your note as needed.\n") +
                "When saved, run $rememberedEn; the notice then stops.\n",
        )
    }
}
