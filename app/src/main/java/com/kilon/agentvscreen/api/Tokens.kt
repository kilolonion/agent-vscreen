package com.kilon.agentvscreen.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 1.2: one token per agent instead of one token for everything.
 *
 * Each token has a name (shown in the audit log, in confirmations and next to "last caller"), a scope
 * (look ⊂ operate ⊂ system: what its commands may do), can be revoked alone, and can be given a period without
 * confirmations by the user. A token also owns its sessions: two agents with different tokens cannot take each other's
 * screen by using the same session name.
 *
 * The first token ("main") is the one 1.1 had; it keeps working unchanged. New tokens look like
 * `avs_<id>_<secret>`. Only sha256(secret) is stored for them (the token is shown once, when it is made); it is also
 * the key that signs requests (see ApiServer: X-VScreen-Sign), so a signed request never carries the token itself.
 */
object Tokens {
    const val MAIN = "main"
    val SCOPES = listOf("look", "operate", "system")
    /** 2.0.0-beta7 (7.2, 7.5, 7.8 #5): public OAuth clients can NEVER hold "system". */
    val PUBLIC_SCOPES = listOf("look", "operate")

    /**
     * 2.0.0-beta8 (feedback 2): one ladder per agent answers both "what may it do" and "when does it ask".
     * look: read only. careful: operate apps, asks before money, irreversible and careful steps. free (default): operate
     * apps, asks only before money and steps that cannot be undone. free_system: free + system commands.
     * The scope (look ⊂ operate ⊂ system) is derived from the level and never set on its own.
     */
    const val LOOK = "look"
    const val CAREFUL = "careful"
    const val FREE = "free"
    const val FREE_SYSTEM = "free_system"
    val LEVELS = listOf(LOOK, CAREFUL, FREE, FREE_SYSTEM, Policy.CUSTOM)
    /** Public OAuth clients never get system commands. */
    val PUBLIC_LEVELS = listOf(LOOK, CAREFUL, FREE, Policy.CUSTOM)
    fun scopeOfLevel(level: String): String = Policy.scopeOf(Policy.table(level, emptyMap()))
    /** The level an old scope maps to when nothing else is known (an agent created by scope, e.g. by a test hook). */
    fun defaultLevelFor(scope: String): String = when (scope) { "look" -> LOOK; "system" -> FREE_SYSTEM; else -> FREE }
    /**
     * A level as given by any caller: a level key as is, or an older scope name (look / operate / system) mapped to its
     * default level. Callers written before 2.0.0-beta9 (pairing approval, test hooks, scripts) still pass scopes.
     */
    fun levelOf(raw: String?): String? = when {
        raw == null -> null
        raw in LEVELS -> raw
        raw in SCOPES -> defaultLevelFor(raw)
        else -> null
    }
    /**
     * Upgrade migration (feedback 2): the old per-token scope plus the old global "放手跑 / 谨慎" become one level.
     * look -> look; operate + careful -> careful; operate + auto -> free; system + auto -> free_system;
     * system + careful -> careful (conservative: the user can raise it).
     */
    fun migrateLevel(scope: String, autonomy: String, publicOnly: Boolean): String {
        val l = when {
            scope == "look" -> LOOK
            autonomy == "careful" -> CAREFUL
            scope == "system" -> FREE_SYSTEM
            else -> FREE
        }
        return if (publicOnly && l == FREE_SYSTEM) FREE else l
    }

    class Token(val id: String, val name: String, val key: String, val scope: String, val createdAt: Long,
                val lastUsedAt: Long = 0L, val revoked: Boolean = false, val trustUntil: Long = 0L,
                /** 2.0.0-beta7 (7.7): stable identity for a public OAuth client; has no local secret and is never accepted on local/tailnet endpoints. */
                val publicOnly: Boolean = false,
                /** 2.0.0-beta8 (feedback 2): this agent's level (a preset of [Policy] or custom), see [LEVELS]. */
                val level: String = defaultLevelFor(scope),
                /** Rows the user changed on top of the preset (for custom: the whole table). */
                val custom: Map<String, Policy.Act> = emptyMap()) {
        /** How each class of action is handled for this agent. */
        val policy: Map<String, Policy.Act> get() = Policy.table(level, custom, publicOnly)
        fun act(key: String): Policy.Act = policy[key] ?: Policy.Act.ASK
        /** look ⊂ operate ⊂ system (public tokens capped at look ⊂ operate). */
        val scopes: Set<String> get() {
            val allowed = if (publicOnly) PUBLIC_SCOPES else SCOPES
            val effective = if (publicOnly && scope == "system") "operate" else scope
            return allowed.take(allowed.indexOf(effective).coerceAtLeast(0) + 1).toSet()
        }
        val trusted: Boolean get() = trustUntil > System.currentTimeMillis()
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("key", key).put("scope", scope)
            .put("created", createdAt).put("used", lastUsedAt).put("revoked", revoked).put("trust_until", trustUntil)
            .put("public", publicOnly).put("level", level).put("custom", Policy.toJson(custom))
        fun with(lastUsedAt: Long = this.lastUsedAt, revoked: Boolean = this.revoked, trustUntil: Long = this.trustUntil, name: String = this.name,
                 level: String = this.level, custom: Map<String, Policy.Act> = this.custom) =
            Token(id, name, key, Policy.scopeOf(Policy.table(level, custom, publicOnly)), createdAt, lastUsedAt, revoked, trustUntil, publicOnly, level, custom)
    }

    private var prefs: SharedPreferences? = null
    private val _list = MutableStateFlow<List<Token>>(emptyList())
    val list: StateFlow<List<Token>> = _list.asStateFlow()

    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("agent_vscreen_tokens", Context.MODE_PRIVATE)
        val autonomy = runCatching { ApiConfig.legacyAutonomy }.getOrDefault("auto")
        var migrated = false
        _list.value = runCatching {
            val a = JSONArray(prefs!!.getString("tokens", "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val pub = o.optBoolean("public", false)
                val rawScope = o.optString("scope", if (pub) "look" else "system")
                val scope = if (pub) (if (rawScope == "operate") "operate" else "look") else rawScope
                val stored = o.optString("level").takeIf { it in LEVELS && (!pub || it in PUBLIC_LEVELS) }
                val level = stored ?: migrateLevel(scope, autonomy, pub).also { migrated = true }
                val custom = Policy.fromJson(o.optJSONObject("custom"))
                Token(o.getString("id"), o.getString("name"), o.getString("key"), Policy.scopeOf(Policy.table(level, custom, pub)),
                    o.optLong("created"), o.optLong("used"), o.optBoolean("revoked"), o.optLong("trust_until"), pub, level, custom)
            }
        }.getOrDefault(emptyList())
        if (migrated) save(_list.value)
        syncMain()
        cleanupLoopTokensOnce()
    }

    /**
     * 2.0.0-beta8 (feedback 5): the "connect a new agent" page made a new token every second while its last step was
     * open with the name left empty ("默认 Agent 1", "默认 Agent 2", … hundreds). Once per install, drop those: named
     * like the suggested name, never used by any request, not public, and created in a burst (another token of the
     * same kind within a minute). A token the user really handed to an agent has been used and stays.
     */
    private fun cleanupLoopTokensOnce() {
        val p = prefs ?: return
        if (p.getBoolean(CLEANUP_FLAG, false)) return
        val drop = loopTokens(_list.value)
        if (drop.isNotEmpty()) {
            save(_list.value.filter { it.id !in drop })
            pendingForget = drop
        }
        p.edit().putBoolean(CLEANUP_FLAG, true).apply()
    }
    private const val CLEANUP_FLAG = "cleanup_loop_tokens_beta8"
    /** Ids removed by [cleanupLoopTokensOnce]; their ledger / approvals are dropped once those stores are up ([Agents.finishCleanup]). */
    @Volatile var pendingForget: Set<String> = emptySet()

    private val SUGGESTED = Regex("^(默认 Agent|Default Agent) \\d+$")
    /** Pure selection for [cleanupLoopTokensOnce] (unit-tested). */
    internal fun loopTokens(list: List<Token>): Set<String> {
        val cand = list.filter { it.id != MAIN && !it.publicOnly && it.lastUsedAt == 0L && SUGGESTED.matches(it.name) }
            .sortedBy { it.createdAt }
        return cand.filterIndexed { i, t ->
            (i > 0 && t.createdAt - cand[i - 1].createdAt < 60_000L) || (i + 1 < cand.size && cand[i + 1].createdAt - t.createdAt < 60_000L)
        }.map { it.id }.toSet()
    }

    /** Tokens no request ever used (for the "remove never-used" action); the main token and public clients are kept. */
    fun neverUsed(isUsed: (String) -> Boolean): List<Token> =
        _list.value.filter { it.id != MAIN && !it.publicOnly && it.lastUsedAt == 0L && !isUsed(it.id) }

    /** Remove many tokens with one write. */
    @Synchronized fun revokeMany(ids: Set<String>) {
        val drop = ids - MAIN
        if (drop.isEmpty()) return
        save(_list.value.filter { it.id !in drop })
        drop.forEach { id ->
            com.kilon.agentvscreen.host.StrongConfirm.revokeAllForToken(id)
            PublicOAuth.revokeAllForToken(id)
        }
    }

    /** The main token follows ApiConfig.token (the user can reset it there). */
    fun syncMain() {
        val t = ApiConfig.token
        if (t.isEmpty()) return
        val key = sha256(t)
        val cur = _list.value.firstOrNull { it.id == MAIN }
        if (cur?.key == key) return
        val level = cur?.level ?: migrateLevel("system", runCatching { ApiConfig.legacyAutonomy }.getOrDefault("auto"), false)
        val custom = cur?.custom ?: emptyMap()
        save(listOf(Token(MAIN, cur?.name ?: "main", key, Policy.scopeOf(Policy.table(level, custom)), cur?.createdAt ?: System.currentTimeMillis(), level = level, custom = custom)) + _list.value.filter { it.id != MAIN })
    }

    private fun save(l: List<Token>) {
        _list.value = l
        prefs?.edit()?.putString("tokens", JSONArray(l.map { it.json() }).toString())?.apply()
    }

    private fun random(n: Int): String {
        val abc = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..n).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    /** A new token; the second value is the token text, shown to the user once and never stored. */
    @Synchronized fun create(name: String, scope: String): Pair<Token, String> =
        createLevel(name, defaultLevelFor(if (scope in SCOPES) scope else "operate"))

    /** 2.0.0-beta8 (feedback 2): a new agent at [level]. */
    @Synchronized fun createLevel(name: String, level: String, custom: Map<String, Policy.Act> = emptyMap()): Pair<Token, String> {
        var id: String
        do { id = random(6).lowercase() } while (_list.value.any { it.id == id })
        val secret = random(28)
        val lv = if (level in LEVELS) level else FREE
        val t = Token(id, name.trim().take(24).ifEmpty { "agent-$id" }, sha256(secret), Policy.scopeOf(Policy.table(lv, custom)), System.currentTimeMillis(), level = lv, custom = custom)
        save(_list.value + t)
        return t to "avs_${id}_$secret"
    }

    /**
     * 2.0.0-beta7 (7.2, 7.7): stable identity entry for a public OAuth client.
     * Has NO usable local secret (`key = ""`, `publicOnly = true`) so local and tailnet endpoints reject it;
     * scope is strictly "look" or "operate" (never "system").
     */
    @Synchronized fun createPublic(clientName: String, scope: String): Token {
        var id: String
        do { id = random(6).lowercase() } while (_list.value.any { it.id == id })
        val label = "公网·" + clientName.trim().ifEmpty { "MCP" }.take(16)
        val safeScope = if (scope == "operate") "operate" else "look"
        val t = Token(id, label, "", safeScope, System.currentTimeMillis(), publicOnly = true, level = defaultLevelFor(safeScope))
        save(_list.value + t)
        return t
    }

    /** (id, secret) of a token text; the old single token is ("main", text). */
    fun parse(text: String): Pair<String, String> {
        val m = Regex("^avs_([a-z0-9]{4,12})_([A-Za-z0-9]{16,64})$").find(text.trim())
        return if (m != null) m.groupValues[1] to m.groupValues[2] else MAIN to text.trim()
    }

    fun byId(id: String?): Token? = _list.value.firstOrNull { it.id == id && !it.revoked }

    /** The live local/tailnet token a bearer text belongs to. Public OAuth identities are never accepted here (7.7). */
    fun byBearer(text: String?): Token? {
        if (text.isNullOrBlank()) return null
        val (id, secret) = parse(text)
        val t = byId(id) ?: return null
        if (t.publicOnly || t.key.isEmpty()) return null
        return if (ApiServer.constTimeEq(sha256(secret), t.key)) t else null
    }

    @Synchronized fun used(id: String) {
        val now = System.currentTimeMillis()
        val l = _list.value
        val t = l.firstOrNull { it.id == id } ?: return
        if (now - t.lastUsedAt < 60_000L) return
        save(l.map { if (it.id == id) it.with(lastUsedAt = now) else it })
    }

    /** 1.4.0-beta7: each connected agent has its own token; its name is how the app shows that agent. */
    @Synchronized fun rename(id: String, name: String) { val n = name.trim().take(24); if (n.isNotEmpty()) save(_list.value.map { if (it.id == id) it.with(name = n) else it }) }
    @Synchronized fun revoke(id: String) {
        if (id != MAIN) {
            save(_list.value.filter { it.id != id })
            com.kilon.agentvscreen.host.StrongConfirm.revokeAllForToken(id)
            PublicOAuth.revokeAllForToken(id)
        }
    }
    @Synchronized fun revokeAllPublic(): List<String> {
        val ids = _list.value.filter { it.publicOnly && it.id != MAIN }.map { it.id }
        if (ids.isNotEmpty()) {
            save(_list.value.filterNot { it.publicOnly && it.id != MAIN })
            ids.forEach { id ->
                com.kilon.agentvscreen.host.StrongConfirm.revokeAllForToken(id)
                PublicOAuth.revokeAllForToken(id)
            }
        }
        return ids
    }
    /** Kept for callers that think in scopes (public OAuth grant): the level that scope maps to by default. */
    @Synchronized fun setScope(id: String, scope: String) {
        val cur = _list.value.firstOrNull { it.id == id } ?: return
        val allowed = if (cur.publicOnly) PUBLIC_SCOPES else SCOPES
        if (scope in allowed && cur.scope != scope) setLevel(id, defaultLevelFor(scope))
    }

    /** 2.0.0-beta8 (feedback 2): the one setting per agent. The main token has a level too (it is an agent). */
    @Synchronized fun setLevel(id: String, level: String, custom: Map<String, Policy.Act> = emptyMap()) {
        val cur = _list.value.firstOrNull { it.id == id } ?: return
        val allowed = if (cur.publicOnly) PUBLIC_LEVELS else LEVELS
        if (level !in allowed) return
        save(_list.value.map { if (it.id == id) it.with(level = level, custom = custom) else it })
    }

    /** Change one row of this agent's table (kept on top of its preset, so "restore preset" can drop it). */
    @Synchronized fun setAct(id: String, key: String, act: Policy.Act) {
        val cur = _list.value.firstOrNull { it.id == id } ?: return
        val base = Policy.table(cur.level, emptyMap(), cur.publicOnly)
        val a = Policy.clamp(key, act)
        val next = if (cur.level != Policy.CUSTOM && base[key] == a) cur.custom - key else cur.custom + (key to a)
        save(_list.value.map { if (it.id == id) it.with(custom = next) else it })
    }
    /** [minutes] 0 ends the period now. */
    @Synchronized fun trust(id: String, minutes: Int) =
        save(_list.value.map { if (it.id == id) it.with(trustUntil = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes.coerceAtMost(24 * 60) * 60_000L) else it })
}
