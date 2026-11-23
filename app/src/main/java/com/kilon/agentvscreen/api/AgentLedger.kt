package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.tr

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 1.1.6: does the agent still know about us in its next conversation?
 *
 * 1.1.4/1.1.5 printed a skill in the install output and asked the agent to save it. Most agents did not: a request
 * inside a tool's output is data to them (often treated as a prompt injection), not something their user asked. So the
 * request now comes from the user (the app copies a message the user sends to the agent, see [AgentGuide.onboard]), and
 * this ledger shows the user whether it worked, from facts only:
 *  - installed: the install script fetched the client;
 *  - remembered: the agent ran `vscreen remembered "<where>"` after saving the note (its own claim, shown as such);
 *  - came back: a call after ≥ [GAP_MS] of silence since install — a later conversation found vscreen on its own.
 * Names and times only; no command contents.
 */
object AgentLedger {
    const val GAP_MS = 30 * 60_000L
    private lateinit var prefs: SharedPreferences

    data class Snapshot(
        val installAt: Long = 0L,
        val rememberedAt: Long = 0L,
        val rememberedWhere: String? = null,
        val lastCallAt: Long = 0L,
        val comebacks: Int = 0,
        val lastComebackAt: Long = 0L,
        /** 1.3 beta5: the last call from an agent on this phone / from another device (the Connect tile says which is in use). */
        val lastLocalAt: Long = 0L,
        val lastRemoteAt: Long = 0L,
    ) {
        val installed get() = installAt > 0
        val remembered get() = rememberedAt >= installAt && rememberedAt > 0
        val cameBack get() = comebacks > 0 && lastComebackAt > installAt
        val used get() = installAt > 0 || lastCallAt > 0 || rememberedAt > 0
    }

    /**
     * 1.4.0-beta7: one record per agent (token id). Before, one record for the whole phone: a second agent's install
     * reset it and its `remembered` overwrote the first one's (the connect page showed one agent's path with another's
     * note version).
     */
    private val _agents = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val agents: StateFlow<Map<String, Snapshot>> = _agents.asStateFlow()

    /** Any agent at all (the home page's "connected before", the service's last use): the latest of each. */
    private val _snap = MutableStateFlow(Snapshot())
    val snap: StateFlow<Snapshot> = _snap.asStateFlow()

    private val FIELDS = listOf("install_at", "remembered_at", "remembered_where", "last_call_at", "comebacks", "last_comeback_at", "last_local_at", "last_remote_at")

    const val REPLY_QUIET = "quiet"
    const val REPLY_FULL = "full"
    private val replyModes = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val holdDefaults = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("agent_vscreen_ledger", Context.MODE_PRIVATE)
        // Before beta7 the one record had no agent: it belongs to the first token, the only one onboarding gave out.
        if (prefs.contains("install_at") || prefs.contains("last_call_at")) {
            val e = prefs.edit()
            FIELDS.forEach { f -> prefs.all[f]?.let { v -> when (v) { is Long -> e.putLong("${Tokens.MAIN}.$f", v); is Int -> e.putInt("${Tokens.MAIN}.$f", v); is String -> e.putString("${Tokens.MAIN}.$f", v) }; e.remove(f) } }
            e.apply()
        }
        val ids = prefs.all.keys.filter { it.contains('.') && !it.startsWith("ack") }.map { it.substringBefore('.') }.toSet()
        replyModes.clear()
        holdDefaults.clear()
        ids.forEach { id ->
            prefs.getString("$id.reply_mode", null)?.let { replyModes[id] = it }
            if (prefs.contains("$id.hold_default")) holdDefaults[id] = prefs.getBoolean("$id.hold_default", false)
        }
        _agents.value = ids.associateWith { id ->
            Snapshot(prefs.getLong("$id.install_at", 0L), prefs.getLong("$id.remembered_at", 0L), prefs.getString("$id.remembered_where", null),
                prefs.getLong("$id.last_call_at", 0L), prefs.getInt("$id.comebacks", 0), prefs.getLong("$id.last_comeback_at", 0L),
                prefs.getLong("$id.last_local_at", 0L), prefs.getLong("$id.last_remote_at", 0L))
        }
        _snap.value = combined(_agents.value.values)
    }

    /** 2.0.0-beta3 (3.1): per-agent default reply mode ("quiet" or "full"), default "quiet". */
    fun replyMode(agentId: String): String {
        val raw = replyModes[agentId] ?: if (::prefs.isInitialized) prefs.getString("$agentId.reply_mode", null) else null
        return if (raw.equals(REPLY_FULL, ignoreCase = true)) REPLY_FULL else REPLY_QUIET
    }

    fun setReplyMode(agentId: String, mode: String) {
        val norm = if (mode.equals(REPLY_FULL, ignoreCase = true)) REPLY_FULL else REPLY_QUIET
        replyModes[agentId] = norm
        if (::prefs.isInitialized) prefs.edit().putString("$agentId.reply_mode", norm).apply()
    }

    internal fun resetReplyModesForTest() { replyModes.clear() }

    /** 2.0.0-beta5 (5.1): per-agent default `--hold` switch (`note hold on|off`), default false. */
    fun holdDefault(agentId: String): Boolean =
        holdDefaults[agentId] ?: if (::prefs.isInitialized) prefs.getBoolean("$agentId.hold_default", false) else false

    fun setHoldDefault(agentId: String, on: Boolean) {
        holdDefaults[agentId] = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("$agentId.hold_default", on).apply()
    }

    internal fun resetHoldDefaultsForTest() { holdDefaults.clear() }

    private fun combined(all: Collection<Snapshot>): Snapshot {
        fun mx(f: (Snapshot) -> Long) = all.maxOfOrNull(f) ?: 0L
        val r = all.maxByOrNull { it.rememberedAt }
        return Snapshot(all.filter { it.installAt > 0 }.minOfOrNull { it.installAt } ?: 0L, r?.rememberedAt ?: 0L, r?.rememberedWhere, mx { it.lastCallAt },
            all.sumOf { it.comebacks }, mx { it.lastComebackAt }, mx { it.lastLocalAt }, mx { it.lastRemoteAt })
    }

    fun of(id: String): Snapshot = _agents.value[id] ?: Snapshot()

    private fun save(id: String, s: Snapshot) {
        _agents.value = _agents.value + (id to s)
        _snap.value = combined(_agents.value.values)
        if (!::prefs.isInitialized) return
        prefs.edit().putLong("$id.install_at", s.installAt).putLong("$id.remembered_at", s.rememberedAt)
            .putString("$id.remembered_where", s.rememberedWhere).putLong("$id.last_call_at", s.lastCallAt)
            .putInt("$id.comebacks", s.comebacks).putLong("$id.last_comeback_at", s.lastComebackAt)
            .putLong("$id.last_local_at", s.lastLocalAt).putLong("$id.last_remote_at", s.lastRemoteAt).apply()
    }

    /** The client was installed with token [id] (the install script names it; the first token when it can't). */
    fun installed(id: String = Tokens.MAIN) {
        // A fresh install starts a fresh record for that agent: an old "remembered" was about an earlier install.
        val now = System.currentTimeMillis()
        save(id, Snapshot(installAt = now, lastCallAt = now))
    }

    /** Forget agent [id]'s record (its token was revoked). */
    fun forget(id: String) {
        _agents.value = _agents.value - id
        replyModes.remove(id)
        holdDefaults.remove(id)
        _snap.value = combined(_agents.value.values)
        if (::prefs.isInitialized) prefs.edit().apply { prefs.all.keys.filter { it.startsWith("$id.") || it == "ack_$id" || it == "ack_at_$id" }.forEach { remove(it) } }.apply()
    }

    // ── 1.3 beta4: which app version each agent's note is for (per token) ──
    private val nudged = java.util.concurrent.ConcurrentHashMap<String, Long>()
    const val NUDGE_EVERY_MS = 20 * 60_000L

    /** The version code [tokenId]'s agent last confirmed its note for; 0 = never. */
    fun ackVersion(tokenId: String): Int = if (::prefs.isInitialized) prefs.getInt("ack_$tokenId", 0) else 0

    fun ack(tokenId: String) {
        if (::prefs.isInitialized) prefs.edit().putInt("ack_$tokenId", com.kilon.agentvscreen.BuildConfig.VERSION_CODE).putLong("ack_at_$tokenId", System.currentTimeMillis()).apply()
        nudged.remove(tokenId)
        _snap.value = _snap.value.copy()
    }

    /** The update notice goes out on the first command after an update, then at most every 20 minutes or at a new stretch of use. */
    fun nudgeDue(tokenId: String, freshStretch: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val last = nudged[tokenId] ?: 0L
        if (!freshStretch && now - last < NUDGE_EVERY_MS) return false
        nudged[tokenId] = now
        return true
    }

    fun remembered(where: String) {
        val id = com.kilon.agentvscreen.cmd.Call.current()?.tokenId ?: Tokens.MAIN
        ack(id)
        save(id, of(id).copy(rememberedAt = System.currentTimeMillis(), rememberedWhere = where.trim().take(80).ifEmpty { tr("（没说）", "(not said)") }))
    }

    /**
     * Every command. Returns true when this call opens a new stretch of use after a long silence (likely a new
     * conversation): the reply then carries a one-line pointer to `vscreen guide`. Per agent: another agent's calls
     * do not make this one's silence shorter.
     */
    fun call(): Boolean {
        val now = System.currentTimeMillis()
        // The app's own commands (no caller) are no agent's.
        val c = com.kilon.agentvscreen.cmd.Call.current() ?: return false
        val id = c.tokenId
        val s0 = of(id)
        // An agent seen for the first time without the install script (MCP, HTTP) is "installed" by its first call.
        val s = if (s0.installAt == 0L) s0.copy(installAt = now) else s0
        val fresh = s0.lastCallAt == 0L || now - s0.lastCallAt >= GAP_MS
        val comeback = fresh && s0.installed && s0.lastCallAt > 0
        val remote = c.peer != null
        save(id, s.copy(lastCallAt = now, lastLocalAt = if (remote) s.lastLocalAt else now, lastRemoteAt = if (remote) now else s.lastRemoteAt, comebacks = s.comebacks + if (comeback) 1 else 0, lastComebackAt = if (comeback) now else s.lastComebackAt))
        return fresh
    }
}
