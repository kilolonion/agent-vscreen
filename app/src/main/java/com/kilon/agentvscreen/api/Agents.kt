package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.tr

/**
 * 1.4.0-beta7 (rainbow: "区分一下我接入的各个agent，不要把他们的历史、remember等等混在一起"). An agent is its token:
 * the connect page makes one per agent, and everything an agent leaves behind (its connection record, tasks, asks,
 * events, schedules, watches) is kept under it. Sessions of a token other than the first one are named
 * "<token id>.<session>" (ApiServer.sessionOf), so the agent of any session can be told from the session itself.
 * Lessons and app notes stay shared: they are about the app, and the user taught them for any agent.
 * Only identity: no agent is allowed or refused anything here.
 */
object Agents {
    /** The token id behind [session] (the first token, "main", for sessions without a prefix). */
    fun ofSession(session: String?): String {
        val p = session?.substringBefore('.', "").orEmpty()
        return if (p.isNotEmpty() && Tokens.list.value.any { it.id == p }) p else Tokens.MAIN
    }

    /** How the app names agent [id]. The first token keeps "main" until the user names it. */
    fun name(id: String?): String {
        val t = Tokens.list.value.firstOrNull { it.id == (id ?: Tokens.MAIN) }
        return when {
            t == null -> if (id == null || id == Tokens.MAIN) firstName() else id
            t.id == Tokens.MAIN && t.name == Tokens.MAIN -> firstName()
            else -> t.name
        }
    }

    fun nameOfSession(session: String?): String = name(ofSession(session))

    /**
     * 1.4.1-beta1: the user forgets agent [id] (connect page) or revokes its token (settings): one place for both. Its
     * token goes, its connection record goes, and so does what it left running: its schedules would still start the
     * service and its watches would still run, with no agent left that can list or cancel them. Tasks and the audit
     * stay (they are the record of what it did). The first token cannot be forgotten.
     */
    fun forget(ctx: android.content.Context, id: String) {
        if (id == Tokens.MAIN) return
        Tokens.revoke(id)
        AgentLedger.forget(id)
        com.kilon.agentvscreen.host.quiet("forget-schedules") { com.kilon.agentvscreen.host.Wake.remove(ctx, null, id) }
        com.kilon.agentvscreen.host.quiet("forget-keeps") {
            com.kilon.agentvscreen.core.VScreenRules.running().filter { it.spec.agentId == id }
                .forEach { com.kilon.agentvscreen.core.VScreenRules.stop(id, it.spec.name, tr("这个 agent 已被忘掉", "this agent was forgotten")) }
            com.kilon.agentvscreen.core.VScreenRules.clearEnded(id)
        }
    }

    private fun firstName() = tr("最早接入的 agent", "First connected agent")
}
