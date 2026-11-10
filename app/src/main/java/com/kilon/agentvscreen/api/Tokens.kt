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

    class Token(val id: String, val name: String, val key: String, val scope: String, val createdAt: Long,
                val lastUsedAt: Long = 0L, val revoked: Boolean = false, val trustUntil: Long = 0L) {
        /** look ⊂ operate ⊂ system. */
        val scopes: Set<String> get() = SCOPES.take(SCOPES.indexOf(scope).coerceAtLeast(0) + 1).toSet()
        val trusted: Boolean get() = trustUntil > System.currentTimeMillis()
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("key", key).put("scope", scope)
            .put("created", createdAt).put("used", lastUsedAt).put("revoked", revoked).put("trust_until", trustUntil)
        fun with(lastUsedAt: Long = this.lastUsedAt, revoked: Boolean = this.revoked, trustUntil: Long = this.trustUntil, scope: String = this.scope, name: String = this.name) =
            Token(id, name, key, scope, createdAt, lastUsedAt, revoked, trustUntil)
    }

    private var prefs: SharedPreferences? = null
    private val _list = MutableStateFlow<List<Token>>(emptyList())
    val list: StateFlow<List<Token>> = _list.asStateFlow()

    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("agent_vscreen_tokens", Context.MODE_PRIVATE)
        _list.value = runCatching {
            val a = JSONArray(prefs!!.getString("tokens", "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map { o -> Token(o.getString("id"), o.getString("name"), o.getString("key"), o.optString("scope", "system"),
                o.optLong("created"), o.optLong("used"), o.optBoolean("revoked"), o.optLong("trust_until")) }
        }.getOrDefault(emptyList())
        syncMain()
    }

    /** The main token follows ApiConfig.token (the user can reset it there). */
    fun syncMain() {
        val t = ApiConfig.token
        if (t.isEmpty()) return
        val key = sha256(t)
        val cur = _list.value.firstOrNull { it.id == MAIN }
        if (cur?.key == key) return
        save(listOf(Token(MAIN, cur?.name ?: "main", key, "system", cur?.createdAt ?: System.currentTimeMillis())) + _list.value.filter { it.id != MAIN })
    }

    private fun save(l: List<Token>) {
        _list.value = l
        prefs?.edit()?.putString("tokens", JSONArray(l.map { it.json() }).toString())?.apply()
    }

    /** For tests: use [l] without storage. */
    internal fun setForTest(l: List<Token>) { _list.value = l }

    private fun random(n: Int): String {
        val abc = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..n).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    /** A new token; the second value is the token text, shown to the user once and never stored. */
    @Synchronized fun create(name: String, scope: String): Pair<Token, String> {
        var id: String
        do { id = random(6).lowercase() } while (_list.value.any { it.id == id })
        val secret = random(28)
        val t = Token(id, name.trim().take(24).ifEmpty { "agent-$id" }, sha256(secret), if (scope in SCOPES) scope else "operate", System.currentTimeMillis())
        save(_list.value + t)
        return t to "avs_${id}_$secret"
    }

    /** (id, secret) of a token text; the old single token is ("main", text). */
    fun parse(text: String): Pair<String, String> {
        val m = Regex("^avs_([a-z0-9]{4,12})_([A-Za-z0-9]{16,64})$").find(text.trim())
        return if (m != null) m.groupValues[1] to m.groupValues[2] else MAIN to text.trim()
    }

    fun byId(id: String?): Token? = _list.value.firstOrNull { it.id == id && !it.revoked }

    /** The live token a bearer text belongs to. */
    fun byBearer(text: String?): Token? {
        if (text.isNullOrBlank()) return null
        val (id, secret) = parse(text)
        val t = byId(id) ?: return null
        return if (ApiServer.constTimeEq(sha256(secret), t.key)) t else null
    }

    @Synchronized fun used(id: String) {
        val now = System.currentTimeMillis()
        val l = _list.value
        val t = l.firstOrNull { it.id == id } ?: return
        if (now - t.lastUsedAt < 60_000L) return
        save(l.map { if (it.id == id) it.with(lastUsedAt = now) else it })
    }

    @Synchronized fun revoke(id: String) { if (id != MAIN) save(_list.value.filter { it.id != id }) }
    @Synchronized fun setScope(id: String, scope: String) { if (scope in SCOPES && id != MAIN) save(_list.value.map { if (it.id == id) it.with(scope = scope) else it }) }
    /** [minutes] 0 ends the period now. */
    @Synchronized fun trust(id: String, minutes: Int) =
        save(_list.value.map { if (it.id == id) it.with(trustUntil = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes.coerceAtMost(24 * 60) * 60_000L) else it })
}
