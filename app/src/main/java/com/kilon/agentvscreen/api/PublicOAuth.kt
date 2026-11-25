package com.kilon.agentvscreen.api

import android.app.KeyguardManager
import android.content.Context
import android.util.Base64
import androidx.core.util.AtomicFile
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.StrongConfirm
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 2.0.0-beta7 (7.1–7.3, 7.7, 7.8): OAuth 2.1 authorization server & token store for public MCP.
 *
 * Security invariants:
 * - Only `authorization_code` (PKCE S256 mandatory) and rotating `refresh_token` are supported.
 * - Raw authorization codes, access tokens, and refresh tokens are NEVER stored on disk or logged;
 *   only their SHA-256 hex digests are stored (persisted atomically to `files/vscreen/public_oauth.json`).
 * - Public clients receive a stable `Tokens.Token(publicOnly = true, key = "")` identity so per-agent
 *   records, risk dimensions, and audit work seamlessly while local/tailnet entrypoints reject them.
 * - Scope `"system"` is NEVER selectable and NEVER granted.
 * - Reusing any retired refresh token immediately revokes ALL access and refresh tokens of that client.
 */
object PublicOAuth {
    private const val TAG = "PublicOAuth"
    private const val STORE_REL = "vscreen/public_oauth.json"

    // Named limits & lifetimes per spec 7.2–7.3 & beta8 Section I.
    const val REGISTER_MAX_PER_HOUR = 5
    const val REGISTER_GLOBAL_MAX_PER_HOUR = 20
    const val REGISTER_WINDOW_MS = 60 * 60_000L
    const val MAX_PENDING_PAIRINGS = 1
    const val MAX_PENDING_PAIRINGS_GLOBAL = 3
    const val AUTHORIZE_DENY_LIMIT = 3
    const val AUTHORIZE_DENY_COOLDOWN_MS = 30 * 60_000L
    const val PAIRING_TTL_MS = 5 * 60_000L
    const val PAIRING_POLL_MS = 2_000L
    /** 2.0.0-beta9 (rainbow P10): clients that poll slowly exchanged the code after 70–95 s and got invalid_grant; RFC 6749 allows up to 10 minutes. Single use still holds. */
    const val AUTH_CODE_TTL_MS = 10 * 60_000L
    const val ACCESS_TOKEN_TTL_MS = 60 * 60_000L
    const val REFRESH_TOKEN_TTL_MS = 30L * 24 * 60 * 60_000L
    const val TOKEN_BYTES = 32

    /** 8-char pairing code alphabet without 0 / O / 1 / I (7.2). */
    const val PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val ALLOWED_SCOPES = listOf("look", "operate")

    const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    private val SUPPORTED_GRANTS = setOf("authorization_code", "refresh_token", DEVICE_GRANT)
    /** RFC 8628 polling interval (s); polling faster answers slow_down. */
    const val DEVICE_INTERVAL_S = 3L
    private val PKCE_VERIFIER_RE = Regex("^[A-Za-z0-9._~-]{43,128}$")
    private val PKCE_CHALLENGE_RE = Regex("^[A-Za-z0-9_-]{43,128}$")

    data class RegisteredClient(
        val clientId: String,
        val clientName: String,
        val redirectUris: List<String>,
        val requestedScope: String?,
        var tokenId: String?,
        val createdAt: Long,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("client_id", clientId)
            .put("client_name", clientName)
            .put("redirect_uris", JSONArray(redirectUris))
            .put("requested_scope", requestedScope ?: JSONObject.NULL)
            .put("token_id", tokenId ?: JSONObject.NULL)
            .put("created_at", createdAt)

        companion object {
            fun fromJson(o: JSONObject): RegisteredClient? {
                val id = o.optString("client_id").takeIf { it.isNotBlank() } ?: return null
                val name = o.optString("client_name").ifBlank { "MCP Client" }
                val urisArr = o.optJSONArray("redirect_uris") ?: return null
                val uris = (0 until urisArr.length()).mapNotNull { urisArr.optString(it).takeIf { u -> u.isNotBlank() } }
                if (uris.isEmpty()) return null
                val reqScope = o.optString("requested_scope").takeIf { it.isNotBlank() && !o.isNull("requested_scope") }
                val tid = o.optString("token_id").takeIf { it.isNotBlank() && !o.isNull("token_id") }
                return RegisteredClient(id, name, uris, reqScope, tid, o.optLong("created_at", 0L))
            }
        }
    }

    data class AccessTokenRecord(
        val tokenId: String,
        val clientId: String,
        val expiresAt: Long,
        val resource: String,
        val scope: String,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("token_id", tokenId)
            .put("client_id", clientId)
            .put("expires_at", expiresAt)
            .put("resource", resource)
            .put("scope", scope)

        companion object {
            fun fromJson(o: JSONObject): AccessTokenRecord? {
                val tid = o.optString("token_id").takeIf { it.isNotBlank() } ?: return null
                val cid = o.optString("client_id").takeIf { it.isNotBlank() } ?: return null
                val res = o.optString("resource").takeIf { it.isNotBlank() } ?: return null
                val sc = o.optString("scope").takeIf { it in ALLOWED_SCOPES } ?: "look"
                return AccessTokenRecord(tid, cid, o.optLong("expires_at", 0L), res, sc)
            }
        }
    }

    data class RefreshTokenRecord(
        val tokenId: String,
        val clientId: String,
        val expiresAt: Long,
        val resource: String,
        val scope: String,
        var retired: Boolean = false,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("token_id", tokenId)
            .put("client_id", clientId)
            .put("expires_at", expiresAt)
            .put("resource", resource)
            .put("scope", scope)
            .put("retired", retired)

        companion object {
            fun fromJson(o: JSONObject): RefreshTokenRecord? {
                val tid = o.optString("token_id").takeIf { it.isNotBlank() } ?: return null
                val cid = o.optString("client_id").takeIf { it.isNotBlank() } ?: return null
                val res = o.optString("resource").takeIf { it.isNotBlank() } ?: return null
                val sc = o.optString("scope").takeIf { it in ALLOWED_SCOPES } ?: "look"
                return RefreshTokenRecord(tid, cid, o.optLong("expires_at", 0L), res, sc, o.optBoolean("retired", false))
            }
        }
    }

    private data class AuthCodeRecord(
        val clientId: String,
        val tokenId: String,
        val redirectUri: String,
        val codeChallenge: String,
        val resource: String,
        val grantedScope: String,
        val expiresAt: Long,
    )

    internal class Pairing(
        val id: String,
        val code: String,
        val clientId: String,
        val clientName: String,
        val redirectUri: String,
        val redirectHost: String,
        val codeChallenge: String,
        val resource: String,
        val state: String?,
        val selectableScopes: List<String>,
        val createdAt: Long,
        val sourceBucket: String = "unknown",
    ) {
        @Volatile var status: String = "pending" // pending | approved | denied | expired | completed
        @Volatile var redirectLocation: String? = null
        @Volatile var confirmId: String? = null
        /** 2.0.0-beta11: RFC 8628 device pairing (no browser, no redirect): sha256 of the device_code. */
        @Volatile var deviceCodeHash: String? = null
        /** Raw one-time auth code kept for the device poll that collects it (device pairings only). */
        @Volatile var deviceAuthCode: String? = null
        @Volatile var lastPollAt: Long = 0L
        val isDevice: Boolean get() = deviceCodeHash != null
    }

    data class AccessAuth(val token: Tokens.Token, val clientId: String, val scope: String)

    /**
     * 2.0.0-beta11 (review): the one rule for what a public credential may do: the scope it was issued with (the level
     * picked when pairing, which the client asked for) and the agent's level now, whichever is smaller. A lower level on
     * the phone applies to old credentials at once; a credential issued for looking never starts operating because the
     * level was raised later: that takes a new pairing. Issue, refresh and every request use this.
     */
    fun effectiveScope(issued: String, tok: Tokens.Token): String = if (issued == "operate" && tok.scope == "operate") "operate" else "look"

    sealed class AccessCheck {
        data class Valid(val auth: AccessAuth) : AccessCheck()
        data class Invalid(val knownClientId: String?) : AccessCheck()
    }

    private val lock = Any()
    private val rng = SecureRandom()
    private val clients = LinkedHashMap<String, RegisteredClient>()
    private val accessTokens = HashMap<String, AccessTokenRecord>()
    private val refreshTokens = HashMap<String, RefreshTokenRecord>()
    private val authCodes = HashMap<String, AuthCodeRecord>()
    private val pairings = LinkedHashMap<String, Pairing>()
    /** Bounded history of access-token hash -> clientId so expired/revoked tokens from known clients still bucket by client_id. */
    private val knownTokenClients = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 512
    }
    private val registerTimesGlobal = ArrayDeque<Long>()
    private val registerTimesBySource = HashMap<String, ArrayDeque<Long>>()
    private val deniesBySource = HashMap<String, Int>()
    private val denyCooldownBySource = HashMap<String, Long>()

    @Volatile private var storageFile: File? = null
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile internal var clockOffsetMs: Long = 0L
    @Volatile internal var deviceSecureCheck: (Context) -> Boolean = { ctx ->
        runCatching { ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)
    }
    /** Debug/test-only seam (ignored whenever `!BuildConfig.DEBUG`). */
    @Volatile internal var pairingPresenterOverride: ((Context, Pairing, (String) -> Unit, () -> Unit) -> String?)? = null

    fun nowMs(): Long = clock() + clockOffsetMs

    fun init(ctx: Context) {
        initFile(File(ctx.filesDir, STORE_REL))
    }

    internal fun initFile(file: File) {
        synchronized(lock) {
            storageFile = file
            clients.clear()
            accessTokens.clear()
            refreshTokens.clear()
            if (file.isFile) {
                runCatching {
                    val root = JSONObject(file.readText(Charsets.UTF_8))
                    root.optJSONArray("clients")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { RegisteredClient.fromJson(it) }?.let { c ->
                                clients[c.clientId] = c
                            }
                        }
                    }
                    root.optJSONObject("access_tokens")?.let { obj ->
                        for (k in obj.keys()) {
                            obj.optJSONObject(k)?.let { AccessTokenRecord.fromJson(it) }?.let { rec ->
                                accessTokens[k] = rec
                            }
                        }
                    }
                    root.optJSONObject("refresh_tokens")?.let { obj ->
                        for (k in obj.keys()) {
                            obj.optJSONObject(k)?.let { RefreshTokenRecord.fromJson(it) }?.let { rec ->
                                refreshTokens[k] = rec
                            }
                        }
                    }
                    pruneExpiredLocked(nowMs())
                }.onFailure { AppLogger.warning(TAG, "load store failed: ${it.message}") }
            }
        }
    }

    private fun saveLocked() {
        val f = storageFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val root = JSONObject()
            val cArr = JSONArray()
            clients.values.forEach { cArr.put(it.json()) }
            root.put("clients", cArr)
            val aObj = JSONObject()
            accessTokens.forEach { (h, r) -> aObj.put(h, r.json()) }
            root.put("access_tokens", aObj)
            val rObj = JSONObject()
            refreshTokens.forEach { (h, r) -> rObj.put(h, r.json()) }
            root.put("refresh_tokens", rObj)
            val af = AtomicFile(f)
            val os = af.startWrite()
            try {
                os.write(root.toString(2).toByteArray(Charsets.UTF_8))
                os.flush()
                af.finishWrite(os)
            } catch (t: Throwable) {
                af.failWrite(os)
                throw t
            }
        }.onFailure { AppLogger.warning(TAG, "save store failed: ${it.message}") }
    }

    private fun pruneExpiredLocked(now: Long) {
        accessTokens.entries.removeAll { it.value.expiresAt <= now }
        refreshTokens.entries.removeAll { it.value.expiresAt <= now }
        authCodes.entries.removeAll { it.value.expiresAt <= now }
        val it = pairings.entries.iterator()
        while (it.hasNext()) {
            val p = it.next().value
            if (p.status == "pending" && now - p.createdAt >= PAIRING_TTL_MS) {
                p.status = "expired"
                p.redirectLocation = null
            }
            if (now - p.createdAt >= PAIRING_TTL_MS * 2) {
                it.remove()
            }
        }
    }

    // ── Pure helpers (URI, scope, PKCE, resource) ──────────────────────────

    fun publicBase(req: ApiServer.Req, configuredBase: String? = null): String {
        val cfg = configuredBase?.trim()?.trimEnd('/')
        if (!cfg.isNullOrEmpty()) return cfg
        val host = req.headers["host"]?.trim()?.takeIf { it.isNotEmpty() } ?: "127.0.0.1:${PublicServer.DEFAULT_PORT}"
        val hostOnly = host.substringBeforeLast(':').lowercase()
        val scheme = if (hostOnly == "127.0.0.1" || hostOnly == "localhost" || hostOnly == "[::1]") "http" else "https"
        return "$scheme://$host"
    }

    fun mcpResource(base: String): String = "${base.trimEnd('/')}/mcp"

    /** Canonicalize an RFC 8707 resource URI (no userinfo, query, or fragment). */
    fun normalizeResourceUri(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (s.any { it.isWhitespace() || it.code < 0x20 } || '#' in s) return null
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != null) return null
        if (uri.rawAuthority?.contains('@') == true) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port
        val portPart = when {
            port < 0 -> ""
            scheme == "https" && port == 443 -> ""
            scheme == "http" && port == 80 -> ""
            else -> ":$port"
        }
        val path = (uri.rawPath ?: "").trimEnd('/')
        return "$scheme://$host$portPart$path"
    }

    fun matchesResource(given: String?, expected: String): Boolean {
        val a = normalizeResourceUri(given) ?: return false
        val b = normalizeResourceUri(expected) ?: return false
        return a == b
    }

    /**
     * 2.0.0-beta7 (7.8 #6): strict URI-parsed redirect_uri validation.
     * - `https`: any non-empty host.
     * - `http`: ONLY exact host `127.0.0.1`, `localhost`, or `[::1]` (any port/path).
     * - Rejects userinfo, fragments, whitespace, and custom schemes.
     */
    fun isValidRedirectUri(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        if (raw.any { it.isWhitespace() || it.code < 0x20 } || '#' in raw) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (uri.rawFragment != null || uri.rawUserInfo != null) return false
        if (uri.rawAuthority?.contains('@') == true) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        return when (scheme) {
            "https" -> true
            "http" -> host == "127.0.0.1" || host == "localhost" || host == "[::1]"
            else -> false
        }
    }

    /**
     * 2.0.0-beta7 (7.8 #5): resolve selectable scopes from client-supplied upper bounds.
     * Omitted = both `["look", "operate"]` selectable. Provided = capped by what was requested;
     * unrecognized values and `"system"` are ignored and NEVER granted.
     */
    fun resolveSelectableScopes(registeredScope: String?, authorizeScope: String?): List<String> {
        fun cap(raw: String?): Set<String> {
            if (raw.isNullOrBlank()) return setOf("look", "operate")
            val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
            return if ("operate" in words) setOf("look", "operate") else setOf("look")
        }
        val regCap = cap(registeredScope)
        val authCap = cap(authorizeScope)
        return ALLOWED_SCOPES.filter { it in regCap && it in authCap }.ifEmpty { listOf("look") }
    }

    /** Pure PKCE S256 verifier check (RFC 7636). */
    fun verifyPkceS256(verifier: String?, challenge: String?): Boolean {
        if (verifier.isNullOrEmpty() || challenge.isNullOrEmpty()) return false
        if (!PKCE_VERIFIER_RE.matches(verifier) || !PKCE_CHALLENGE_RE.matches(challenge)) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val computed = base64UrlNoPad(digest)
        return ApiServer.constTimeEq(computed, challenge)
    }

    internal fun pkceS256Challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64UrlNoPad(digest)
    }

    private fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun randomToken(): String {
        val b = ByteArray(TOKEN_BYTES)
        rng.nextBytes(b)
        return base64UrlNoPad(b)
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    internal fun generatePairingCode(): String {
        val chars = CharArray(8) { PAIRING_ALPHABET[rng.nextInt(PAIRING_ALPHABET.length)] }
        return "${String(chars, 0, 4)}-${String(chars, 4, 4)}"
    }

    private fun oauthError(status: Int, error: String, desc: String): ApiServer.Resp =
        ApiServer.Resp.json(
            JSONObject().put("error", error).put("error_description", desc),
            status,
        )

    // ── Discovery endpoints (RFC 9728 / RFC 8414) ──────────────────────────

    /** Served identically at `/.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp` (7.8 #4). */
    fun protectedResourceMetadata(base: String): ApiServer.Resp {
        val cleanBase = base.trimEnd('/')
        return ApiServer.Resp.json(
            JSONObject()
                .put("resource", mcpResource(cleanBase))
                .put("authorization_servers", JSONArray().put(cleanBase))
                .put("bearer_methods_supported", JSONArray().put("header"))
                .put("scopes_supported", JSONArray(ALLOWED_SCOPES)),
        )
    }

    /** Served ONLY at root `/.well-known/oauth-authorization-server` (7.8 #4). */
    fun authorizationServerMetadata(base: String): ApiServer.Resp {
        val cleanBase = base.trimEnd('/')
        return ApiServer.Resp.json(
            JSONObject()
                .put("issuer", cleanBase)
                .put("authorization_endpoint", "$cleanBase/oauth/authorize")
                .put("token_endpoint", "$cleanBase/oauth/token")
                .put("registration_endpoint", "$cleanBase/oauth/register")
                .put("response_types_supported", JSONArray().put("code"))
                .put("grant_types_supported", JSONArray(listOf("authorization_code", "refresh_token", DEVICE_GRANT)))
                // 2.0.0-beta11: RFC 8628 for clients without a browser (cloud VMs, CLIs): the user approves on the
                // phone itself after checking the user_code; no redirect, no copied URL.
                .put("device_authorization_endpoint", "$cleanBase/oauth/device_authorization")
                .put("code_challenge_methods_supported", JSONArray().put("S256"))
                .put("token_endpoint_auth_methods_supported", JSONArray().put("none"))
                .put("scopes_supported", JSONArray(ALLOWED_SCOPES)),
        )
    }

    // ── Source bucketing (beta8 Section I: IPv4 /32, IPv6 /64, tailnet:<bucket>) ──

    /**
     * Normalize the `X-AVS-Public-Src` header injected by `tsbridge` into a stable rate-limit bucket key:
     * - IPv4 (or IPv4-mapped IPv6 `::ffff:a.b.c.d`) -> `/32` canonical `a.b.c.d`
     * - IPv6 -> `/64` prefix `xxxx:xxxx:xxxx:xxxx::/64`
     * - `tailnet:<ip>` -> `tailnet:<normalized bucket>`
     * - missing/blank -> `"unknown"`
     * Pure numeric parsing (never performs DNS lookups).
     */
    fun sourceBucket(rawHeader: String?): String {
        val raw = rawHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return "unknown"
        if (raw.startsWith("tailnet:", ignoreCase = true)) {
            val rest = raw.substring(8).trim()
            return "tailnet:" + normalizeIpBucket(rest)
        }
        return normalizeIpBucket(raw)
    }

    private fun normalizeIpBucket(input: String): String {
        var s = input.trim()
        if (s.isEmpty() || s.equals("unknown", ignoreCase = true)) return "unknown"
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length - 1)
        val zoneIdx = s.indexOf('%')
        if (zoneIdx > 0) s = s.substring(0, zoneIdx)
        parseIpv4(s)?.let { b -> return "${b[0] and 0xff}.${b[1] and 0xff}.${b[2] and 0xff}.${b[3] and 0xff}" }
        parseIpv6(s)?.let { b ->
            // Unwrap IPv4-mapped IPv6 (::ffff:a.b.c.d) to its IPv4 /32 bucket.
            val mapped = (0..9).all { b[it] == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
            if (mapped) {
                return "${b[12].toInt() and 0xff}.${b[13].toInt() and 0xff}.${b[14].toInt() and 0xff}.${b[15].toInt() and 0xff}"
            }
            val g0 = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
            val g1 = ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
            val g2 = ((b[4].toInt() and 0xff) shl 8) or (b[5].toInt() and 0xff)
            val g3 = ((b[6].toInt() and 0xff) shl 8) or (b[7].toInt() and 0xff)
            return "%x:%x:%x:%x::/64".format(java.util.Locale.ROOT, g0, g1, g2, g3)
        }
        return "other:" + s.take(64)
    }

    private fun parseIpv4(s: String): IntArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v
        }
        return out
    }

    private fun parseIpv6(raw: String): ByteArray? {
        if (':' !in raw) return null
        var s = raw
        var ipv4Tail: IntArray? = null
        val lastColon = s.lastIndexOf(':')
        if (lastColon >= 0 && '.' in s.substring(lastColon + 1)) {
            ipv4Tail = parseIpv4(s.substring(lastColon + 1)) ?: return null
            s = s.substring(0, lastColon) + ":0:0"
        }
        val dbl = s.indexOf("::")
        if (dbl >= 0 && s.indexOf("::", dbl + 2) >= 0) return null
        val headGroups = if (dbl >= 0) {
            val h = s.substring(0, dbl)
            if (h.isEmpty()) emptyList() else h.split(':')
        } else {
            s.split(':')
        }
        val tailGroups = if (dbl >= 0) {
            val t = s.substring(dbl + 2)
            if (t.isEmpty()) emptyList() else t.split(':')
        } else {
            emptyList()
        }
        val total = headGroups.size + tailGroups.size
        if (dbl >= 0) {
            if (total > 7) return null
        } else {
            if (total != 8) return null
        }
        val groups = IntArray(8)
        var idx = 0
        for (g in headGroups) {
            if (g.isEmpty() || g.length > 4) return null
            groups[idx++] = g.toIntOrNull(16) ?: return null
        }
        val zeros = 8 - total
        idx += zeros
        for (g in tailGroups) {
            if (g.isEmpty() || g.length > 4) return null
            groups[idx++] = g.toIntOrNull(16) ?: return null
        }
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i * 2] = ((groups[i] ushr 8) and 0xff).toByte()
            out[i * 2 + 1] = (groups[i] and 0xff).toByte()
        }
        if (ipv4Tail != null) {
            for (i in 0 until 4) out[12 + i] = ipv4Tail[i].toByte()
        }
        return out
    }

    // ── Dynamic client registration (RFC 7591) ─────────────────────────────

    fun handleRegister(r: ApiServer.Req): ApiServer.Resp {
        val now = nowMs()
        val src = sourceBucket(r.headers["x-avs-public-src"])
        val limitReason: String? = synchronized(lock) {
            while (registerTimesGlobal.isNotEmpty() && now - registerTimesGlobal.first() >= REGISTER_WINDOW_MS) {
                registerTimesGlobal.removeFirst()
            }
            val sq = registerTimesBySource.getOrPut(src) { ArrayDeque() }
            while (sq.isNotEmpty() && now - sq.first() >= REGISTER_WINDOW_MS) {
                sq.removeFirst()
            }
            if (registerTimesBySource.size > 500) {
                registerTimesBySource.entries.removeAll { (k, q) ->
                    k != src && (q.isEmpty() || now - q.last() >= REGISTER_WINDOW_MS)
                }
            }
            when {
                sq.size >= REGISTER_MAX_PER_HOUR -> "src=$src limit=$REGISTER_MAX_PER_HOUR/h"
                registerTimesGlobal.size >= REGISTER_GLOBAL_MAX_PER_HOUR -> "global limit=$REGISTER_GLOBAL_MAX_PER_HOUR/h"
                else -> {
                    sq.addLast(now)
                    registerTimesGlobal.addLast(now)
                    null
                }
            }
        }
        if (limitReason != null) {
            Audit.security("public_register_rate_limited", limitReason)
            return oauthError(429, "too_many_requests", "Registration rate limit exceeded (max $REGISTER_MAX_PER_HOUR per hour)")
        }

        val body = runCatching { JSONObject(r.bodyText) }.getOrNull()
            ?: return oauthError(400, "invalid_client_metadata", "Request body must be a JSON object")

        val reqGrants: List<String> = body.optJSONArray("grant_types")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { g -> g.isNotBlank() } }
        } ?: emptyList()
        if (reqGrants.any { it !in SUPPORTED_GRANTS } ||
            (reqGrants.isNotEmpty() && "authorization_code" !in reqGrants && DEVICE_GRANT !in reqGrants)
        ) {
            return oauthError(400, "invalid_client_metadata", "Supported grant_types: authorization_code, refresh_token, $DEVICE_GRANT")
        }
        val deviceOnly = DEVICE_GRANT in reqGrants && "authorization_code" !in reqGrants
        val urisJson = body.optJSONArray("redirect_uris") ?: if (deviceOnly) JSONArray() else
            return oauthError(400, "invalid_redirect_uri", "redirect_uris is required (or register grant_types [\"$DEVICE_GRANT\"] for a browserless client)")
        if ((urisJson.length() == 0 && !deviceOnly) || urisJson.length() > 16) {
            return oauthError(400, "invalid_redirect_uri", "redirect_uris must have 1..16 entries")
        }
        val uris = ArrayList<String>(urisJson.length())
        for (i in 0 until urisJson.length()) {
            val u = urisJson.optString(i, "")
            if (!isValidRedirectUri(u)) {
                return oauthError(400, "invalid_redirect_uri", "Invalid redirect_uri: only https:// or http://127.0.0.1|localhost|[::1] without userinfo/fragment")
            }
            if (u !in uris) uris += u
        }

        val authMethod = body.optString("token_endpoint_auth_method").takeIf { it.isNotBlank() } ?: "none"
        if (authMethod != "none") {
            return oauthError(400, "invalid_client_metadata", "Only public clients (token_endpoint_auth_method=none) are supported")
        }

        body.optJSONArray("response_types")?.let { arr ->
            val reqTypes = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { t -> t.isNotBlank() } }
            if (reqTypes.any { it != "code" }) {
                return oauthError(400, "invalid_client_metadata", "Only response_type=code is supported")
            }
        }

        val rawName = body.optString("client_name").trim().replace(Regex("[\\r\\n\\t]+"), " ").take(64)
        val clientName = rawName.ifEmpty { "MCP Client" }
        val reqScope = body.optString("scope").takeIf { it.isNotBlank() && !body.isNull("scope") }?.take(128)
        val clientId = "pub_" + randomHex(12)
        val client = RegisteredClient(clientId, clientName, uris, reqScope, null, now)

        synchronized(lock) {
            clients[clientId] = client
            saveLocked()
        }
        Audit.security("public_client_registered", "client=$clientId name=$clientName")

        val resp = JSONObject()
            .put("client_id", clientId)
            .put("client_name", clientName)
            .put("redirect_uris", JSONArray(uris))
            .put("grant_types", JSONArray(if (deviceOnly) listOf(DEVICE_GRANT, "refresh_token") else listOf("authorization_code", "refresh_token", DEVICE_GRANT)))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
            .put("client_id_issued_at", now / 1000L)
        return ApiServer.Resp.json(resp, 201)
    }

    // ── Authorization + Pairing page (7.2, 7.3, 7.7, 7.8 #5–#6) ────────────

    fun handleAuthorize(ctx: Context, r: ApiServer.Req, base: String): ApiServer.Resp {
        val now = nowMs()
        val responseType = r.q("response_type")
        if (responseType != "code") {
            return oauthError(400, "unsupported_response_type", "Only response_type=code is supported")
        }
        val clientId = r.q("client_id")?.takeIf { it.isNotBlank() }
            ?: return oauthError(400, "invalid_request", "client_id is required")
        val client = synchronized(lock) { clients[clientId] }
            ?: return oauthError(400, "invalid_client", "Unknown client_id")

        val redirectUri = r.q("redirect_uri")?.takeIf { it.isNotBlank() }
            ?: return oauthError(400, "invalid_request", "redirect_uri is required")
        // Verbatim match against registered redirect_uris (7.2, 7.8 #6) — NEVER redirect to an unverified URI.
        if (redirectUri !in client.redirectUris || !isValidRedirectUri(redirectUri)) {
            return oauthError(400, "invalid_redirect_uri", "redirect_uri does not exactly match registered URI")
        }

        val challengeMethod = r.q("code_challenge_method")
        val challenge = r.q("code_challenge")
        if (challengeMethod != "S256" || challenge.isNullOrBlank() || !PKCE_CHALLENGE_RE.matches(challenge)) {
            return oauthError(400, "invalid_request", "PKCE code_challenge_method=S256 and valid code_challenge are required")
        }

        val expectedResource = mcpResource(base)
        val reqResource = r.q("resource")?.takeIf { it.isNotBlank() }
        if (reqResource != null && !matchesResource(reqResource, expectedResource)) {
            return oauthError(400, "invalid_target", "resource must match this server's MCP URL")
        }
        val boundResource = expectedResource

        if (!deviceSecureCheck(ctx)) {
            Audit.security("public_authorize_no_lockscreen", "client=$clientId")
            return ApiServer.Resp.html(
                errorPageHtml(
                    tr("无法授权：手机未设置锁屏密码", "Authorization refused: phone has no screen lock"),
                    tr("公网接入要求手机已设置锁屏密码或生物识别，不允许长按确认。", "Public MCP requires a screen lock PIN or biometric on the phone."),
                ),
                403,
            )
        }

        val state = r.q("state")
        val selectableScopes = resolveSelectableScopes(client.requestedScope, r.q("scope"))
        val redirectHost = runCatching { URI(redirectUri).host }.getOrNull().orEmpty()
        val src = sourceBucket(r.headers["x-avs-public-src"])

        val pairing = when (val started = startPairing(ctx, client, redirectUri, redirectHost, challenge, boundResource, state, selectableScopes, src, null)) {
            is Started.Ok -> started.pairing
            is Started.Refused -> return started.resp
        }
        val html = pairingPageHtml(pairing)
        val csp = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'"
        return ApiServer.Resp.html(html, 200, mapOf("Content-Security-Policy" to csp))
    }

    private sealed class Started {
        class Ok(val pairing: Pairing) : Started()
        class Refused(val resp: ApiServer.Resp) : Started()
    }

    /** The one way a pairing starts (browser authorize and RFC 8628 device authorization): limits, record, phone card. */
    private fun startPairing(
        ctx: Context,
        client: RegisteredClient,
        redirectUri: String,
        redirectHost: String,
        challenge: String,
        boundResource: String,
        state: String?,
        selectableScopes: List<String>,
        src: String,
        deviceCodeHash: String?,
    ): Started {
        val now = nowMs()
        val clientId = client.clientId
        val pairing: Pairing = synchronized(lock) {
            pruneExpiredLocked(now)
            val cooldownUntil = denyCooldownBySource[src] ?: 0L
            if (now < cooldownUntil) {
                val leftMin = ((cooldownUntil - now) / 60_000L).coerceAtLeast(1L)
                return Started.Refused(oauthError(429, "access_denied", "Authorization temporarily locked after repeated denials; retry in $leftMin min"))
            }
            val livePending = pairings.values.filter { it.status == "pending" && now - it.createdAt < PAIRING_TTL_MS }
            if (livePending.count { it.sourceBucket == src } >= MAX_PENDING_PAIRINGS) {
                return Started.Refused(oauthError(429, "temporarily_unavailable", "A pairing confirmation from this source is already pending on the phone"))
            }
            if (livePending.size >= MAX_PENDING_PAIRINGS_GLOBAL) {
                return Started.Refused(oauthError(429, "temporarily_unavailable", "Too many pairing confirmations are already pending on the phone"))
            }
            val pid = "pair_" + randomHex(12)
            val p = Pairing(
                id = pid,
                code = generatePairingCode(),
                clientId = clientId,
                clientName = client.clientName,
                redirectUri = redirectUri,
                redirectHost = redirectHost,
                codeChallenge = challenge,
                resource = boundResource,
                state = state,
                selectableScopes = selectableScopes,
                createdAt = now,
                sourceBucket = src,
            )
            p.deviceCodeHash = deviceCodeHash
            pairings[pid] = p
            p
        }

        val presenter = if (BuildConfig.DEBUG) pairingPresenterOverride else null
        pairing.confirmId = if (presenter != null) {
            presenter(
                ctx,
                pairing,
                { grantedScope -> onPairingApproved(pairing, grantedScope, StrongConfirm.BY_BIOMETRIC) },
                { onPairingDenied(pairing) },
            )
        } else {
            StrongConfirm.createPublicPairingRequest(
                ctx = ctx,
                pairingId = pairing.id,
                clientId = pairing.clientId,
                clientName = pairing.clientName,
                redirectHost = pairing.redirectHost,
                pairingCode = pairing.code,
                selectableScopes = pairing.selectableScopes,
                validMs = PAIRING_TTL_MS,
                onApproveWithScope = { grantedScope ->
                    onPairingApproved(pairing, grantedScope, StrongConfirm.BY_BIOMETRIC)
                },
                onDeny = {
                    onPairingDenied(pairing)
                },
            ).id
        }
        return Started.Ok(pairing)
    }

    /**
     * 2.0.0-beta11: RFC 8628 device authorization. The phone shows its pairing card at once (same card, same
     * limits as the browser flow); the client shows user_code to the user and polls the token endpoint.
     */
    fun handleDeviceAuthorization(ctx: Context, r: ApiServer.Req, base: String): ApiServer.Resp {
        val form = HttpCore.parseForm(r.bodyText)
        fun f(k: String): String? = form[k]?.firstOrNull()?.takeIf { it.isNotBlank() }
        val clientId = f("client_id") ?: return oauthError(400, "invalid_request", "client_id is required")
        val client = synchronized(lock) { clients[clientId] }
            ?: return oauthError(400, "invalid_client", "Unknown client_id (register at /oauth/register first)")
        val expectedResource = mcpResource(base)
        val reqResource = f("resource")
        if (reqResource != null && !matchesResource(reqResource, expectedResource)) {
            return oauthError(400, "invalid_target", "resource must match this server's MCP URL")
        }
        if (!deviceSecureCheck(ctx)) {
            Audit.security("public_authorize_no_lockscreen", "client=$clientId device=1")
            return oauthError(403, "access_denied", "Public MCP requires a screen lock PIN or biometric on the phone")
        }
        val rawDevice = randomToken()
        val src = sourceBucket(r.headers["x-avs-public-src"])
        val scopes = resolveSelectableScopes(client.requestedScope, f("scope"))
        val pairing = when (val started = startPairing(ctx, client, "", tr("无回调地址（设备码配对）", "no redirect (device code)"), "", expectedResource, null, scopes, src, Tokens.sha256(rawDevice))) {
            is Started.Ok -> started.pairing
            is Started.Refused -> return started.resp
        }
        val verify = "${base.trimEnd('/')}/oauth/device"
        return ApiServer.Resp.json(
            JSONObject()
                .put("device_code", rawDevice)
                .put("user_code", pairing.code)
                .put("verification_uri", verify)
                .put("verification_uri_complete", "$verify?user_code=" + URLEncoder.encode(pairing.code, "UTF-8"))
                .put("expires_in", PAIRING_TTL_MS / 1000L)
                .put("interval", DEVICE_INTERVAL_S)
                .put("message", "Approve on the phone: Agent-VScreen shows a pairing card now. Tell the user to check that its code is ${pairing.code}. No browser is needed."),
        )
    }

    /** verification_uri page: there is nothing to type, the approval happens on the phone. */
    fun devicePageHtml(): ApiServer.Resp = ApiServer.Resp.html(
        errorPageHtml(
            tr("请在手机上批准", "Approve on your phone"),
            tr(
                "Agent-VScreen 已在手机上弹出配对确认。核对卡片上的配对码和 agent 告诉你的一致后批准即可，这个页面不用操作。",
                "Agent-VScreen shows a pairing card on the phone. Check that its code matches the one the agent gave you and approve it there; nothing to do on this page.",
            ),
        ),
        200,
    )

    /** Token endpoint, RFC 8628 §3.4/3.5. */
    private fun handleDeviceGrant(deviceCode: String?, clientId: String?, resource: String?, base: String): ApiServer.Resp {
        if (deviceCode == null || clientId == null) return oauthError(400, "invalid_request", "device_code and client_id are required")
        val now = nowMs()
        val hash = Tokens.sha256(deviceCode)
        val rawCode: String = synchronized(lock) {
            pruneExpiredLocked(now)
            val p = pairings.values.firstOrNull { it.deviceCodeHash == hash }
                ?: return oauthError(400, "expired_token", "device_code is unknown or expired; start again at /oauth/device_authorization")
            if (p.clientId != clientId) return oauthError(400, "invalid_grant", "client_id mismatch")
            val tooFast = p.lastPollAt != 0L && now - p.lastPollAt < DEVICE_INTERVAL_S * 1000L
            p.lastPollAt = now
            when (p.status) {
                "pending" -> return if (tooFast) oauthError(400, "slow_down", "Poll every $DEVICE_INTERVAL_S s or slower")
                else oauthError(400, "authorization_pending", "Waiting for the user to approve on the phone")
                "denied" -> return oauthError(400, "access_denied", "The user denied the pairing on the phone")
                "approved" -> {
                    val c = p.deviceAuthCode ?: return oauthError(400, "expired_token", "device_code already used")
                    p.deviceAuthCode = null
                    p.status = "completed"
                    c
                }
                "completed" -> return oauthError(400, "invalid_grant", "device_code already used")
                else -> return oauthError(400, "expired_token", "The pairing expired; start again")
            }
        }
        return issueFromAuthCode(rawCode, clientId, resource, base) { null }
    }

    fun handleAuthorizeStatus(r: ApiServer.Req): ApiServer.Resp {
        val pid = r.q("id")?.takeIf { it.isNotBlank() }
            ?: return oauthError(400, "invalid_request", "id is required")
        val now = nowMs()
        val body = synchronized(lock) {
            pruneExpiredLocked(now)
            val p = pairings[pid] ?: return oauthError(404, "not_found", "unknown pairing id")
            when (p.status) {
                "pending" -> {
                    val left = ((p.createdAt + PAIRING_TTL_MS - now) / 1000L).coerceAtLeast(1L)
                    JSONObject().put("status", "pending").put("expires_in", left)
                }
                "approved" -> {
                    val loc = p.redirectLocation
                    p.redirectLocation = null
                    p.status = "completed"
                    if (loc != null) JSONObject().put("status", "approved").put("redirect_uri", loc)
                    else JSONObject().put("status", "expired")
                }
                "denied" -> JSONObject().put("status", "denied")
                "completed" -> JSONObject().put("status", "completed")
                else -> JSONObject().put("status", "expired")
            }
        }
        return ApiServer.Resp.json(body)
    }

    private fun onPairingApproved(pairing: Pairing, rawLevel: String, by: String) {
        val now = nowMs()
        // 2.0.0-beta8 (feedback 2): the pairing card picks a level; the OAuth scope follows from it, capped by what the client asked.
        val level = Tokens.levelOf(rawLevel)?.takeIf { "operate" in pairing.selectableScopes && it in Tokens.PUBLIC_LEVELS } ?: Tokens.LOOK
        val safeScope = Tokens.scopeOfLevel(level).let { if (it == "look") "look" else "operate" }
        var tokenId: String? = null
        val ok = synchronized(lock) {
            pruneExpiredLocked(now)
            if (pairing.status != "pending" || now - pairing.createdAt >= PAIRING_TTL_MS) {
                pairing.status = "expired"
                return@synchronized false
            }
            val client = clients[pairing.clientId] ?: return@synchronized false
            val existingTok = client.tokenId?.let { Tokens.byId(it) }?.takeIf { it.publicOnly }
            val tok = if (existingTok != null) {
                Tokens.setLevel(existingTok.id, level)
                Tokens.byId(existingTok.id) ?: existingTok
            } else {
                Tokens.createPublic(client.clientName, safeScope).also { created ->
                    client.tokenId = created.id
                    Tokens.setLevel(created.id, level)
                }
            }
            tokenId = tok.id
            deniesBySource.remove(pairing.sourceBucket)

            val rawCode = randomToken()
            val codeHash = Tokens.sha256(rawCode)
            authCodes[codeHash] = AuthCodeRecord(
                clientId = pairing.clientId,
                tokenId = tok.id,
                redirectUri = pairing.redirectUri,
                codeChallenge = pairing.codeChallenge,
                resource = pairing.resource,
                grantedScope = safeScope,
                expiresAt = now + AUTH_CODE_TTL_MS,
            )
            if (pairing.isDevice) pairing.deviceAuthCode = rawCode
            else pairing.redirectLocation = buildRedirectUrl(pairing.redirectUri, rawCode, pairing.state)
            pairing.status = "approved"
            saveLocked()
            true
        }
        val tid = tokenId
        if (ok && tid != null) {
            StrongConfirm.recordPublicPairingApproval(tid, "public:${pairing.clientId}", safeScope, by)
            Audit.security("public_pairing_approved", "client=${pairing.clientId} token=$tid scope=$safeScope")
        }
    }

    private fun onPairingDenied(pairing: Pairing) {
        val now = nowMs()
        var enteredCooldown = false
        synchronized(lock) {
            if (pairing.status != "pending") return
            pairing.status = "denied"
            pairing.redirectLocation = null
            val n = (deniesBySource[pairing.sourceBucket] ?: 0) + 1
            if (n >= AUTHORIZE_DENY_LIMIT) {
                denyCooldownBySource[pairing.sourceBucket] = now + AUTHORIZE_DENY_COOLDOWN_MS
                deniesBySource[pairing.sourceBucket] = 0
                enteredCooldown = true
            } else {
                deniesBySource[pairing.sourceBucket] = n
            }
        }
        if (enteredCooldown) {
            Audit.security("public_authorize_cooldown", "src=${pairing.sourceBucket} client=${pairing.clientId} cooldown_min=${AUTHORIZE_DENY_COOLDOWN_MS / 60_000L}")
        }
    }

    internal fun latestPendingPairingForTests(): Pairing? = synchronized(lock) {
        pruneExpiredLocked(nowMs())
        pairings.values.lastOrNull { it.status == "pending" }
    }

    private fun buildRedirectUrl(redirectUri: String, code: String, state: String?): String {
        val sep = if ('?' in redirectUri) '&' else '?'
        val sb = StringBuilder(redirectUri)
            .append(sep)
            .append("code=").append(URLEncoder.encode(code, "UTF-8"))
        if (!state.isNullOrEmpty()) {
            sb.append("&state=").append(URLEncoder.encode(state, "UTF-8"))
        }
        return sb.toString()
    }

    // ── Token endpoint (authorization_code & rotating refresh_token) ───────

    fun handleToken(r: ApiServer.Req, base: String): ApiServer.Resp {
        val form = HttpCore.parseForm(r.bodyText)
        fun f(k: String): String? = form[k]?.firstOrNull()?.takeIf { it.isNotBlank() }
        return when (f("grant_type")) {
            "authorization_code" -> handleAuthCodeGrant(
                code = f("code"),
                redirectUri = f("redirect_uri"),
                clientId = f("client_id"),
                codeVerifier = f("code_verifier"),
                resource = f("resource"),
                base = base,
            )
            "refresh_token" -> handleRefreshGrant(
                refreshToken = f("refresh_token"),
                clientId = f("client_id"),
                resource = f("resource"),
            )
            DEVICE_GRANT -> handleDeviceGrant(f("device_code"), f("client_id"), f("resource"), base)
            null -> oauthError(400, "invalid_request", "grant_type is required")
            else -> oauthError(400, "unsupported_grant_type", "Supported: authorization_code, refresh_token, $DEVICE_GRANT")
        }
    }

    private fun handleAuthCodeGrant(
        code: String?,
        redirectUri: String?,
        clientId: String?,
        codeVerifier: String?,
        resource: String?,
        base: String,
    ): ApiServer.Resp {
        if (code == null || redirectUri == null || clientId == null || codeVerifier == null) {
            return oauthError(400, "invalid_request", "code, redirect_uri, client_id, and code_verifier are required")
        }
        return issueFromAuthCode(code, clientId, resource, base) { rec ->
            when {
                rec.redirectUri != redirectUri -> oauthError(400, "invalid_grant", "redirect_uri mismatch")
                !verifyPkceS256(codeVerifier, rec.codeChallenge) -> oauthError(400, "invalid_grant", "PKCE code_verifier verification failed")
                else -> null
            }
        }
    }

    /** Consumes a one-time auth code and issues access + refresh (browser flow checks redirect/PKCE in [extraCheck]). */
    private fun issueFromAuthCode(
        code: String,
        clientId: String,
        resource: String?,
        base: String,
        extraCheck: (AuthCodeRecord) -> ApiServer.Resp?,
    ): ApiServer.Resp {
        val now = nowMs()
        val codeHash = Tokens.sha256(code)
        // Consume immediately (single-use even if validation fails).
        val rec = synchronized(lock) { authCodes.remove(codeHash) }
            ?: return oauthError(400, "invalid_grant", "Authorization code is invalid or already used")
        if (now >= rec.expiresAt) {
            return oauthError(400, "invalid_grant", "Authorization code expired")
        }
        if (rec.clientId != clientId) {
            return oauthError(400, "invalid_grant", "client_id mismatch")
        }
        extraCheck(rec)?.let { return it }
        val expectedRes = rec.resource.ifBlank { mcpResource(base) }
        if (resource != null && !matchesResource(resource, expectedRes)) {
            return oauthError(400, "invalid_target", "resource mismatch")
        }
        val tok = Tokens.byId(rec.tokenId)?.takeIf { it.publicOnly }
            ?: return oauthError(400, "invalid_grant", "Client token was revoked")

        // The credential remembers the pairing's scope as its upper bound; what it may do now is [effectiveScope].
        val issuedScope = if (rec.grantedScope == "operate") "operate" else "look"
        val grantedScope = effectiveScope(issuedScope, tok)
        val rawAccess = randomToken()
        val rawRefresh = randomToken()
        val accessHash = Tokens.sha256(rawAccess)
        val refreshHash = Tokens.sha256(rawRefresh)

        synchronized(lock) {
            pruneExpiredLocked(now)
            accessTokens[accessHash] = AccessTokenRecord(
                tokenId = tok.id,
                clientId = rec.clientId,
                expiresAt = now + ACCESS_TOKEN_TTL_MS,
                resource = expectedRes,
                scope = issuedScope,
            )
            knownTokenClients[accessHash] = rec.clientId
            refreshTokens[refreshHash] = RefreshTokenRecord(
                tokenId = tok.id,
                clientId = rec.clientId,
                expiresAt = now + REFRESH_TOKEN_TTL_MS,
                resource = expectedRes,
                scope = issuedScope,
                retired = false,
            )
            saveLocked()
        }
        Audit.security("public_token_issued", "client=${rec.clientId} token=${tok.id} scope=$grantedScope")

        return ApiServer.Resp.json(
            JSONObject()
                .put("access_token", rawAccess)
                .put("token_type", "Bearer")
                .put("expires_in", ACCESS_TOKEN_TTL_MS / 1000L)
                .put("refresh_token", rawRefresh)
                .put("scope", grantedScope),
        )
    }

    private fun handleRefreshGrant(
        refreshToken: String?,
        clientId: String?,
        resource: String?,
    ): ApiServer.Resp {
        if (refreshToken == null || clientId == null) {
            return oauthError(400, "invalid_request", "refresh_token and client_id are required")
        }
        val now = nowMs()
        val hash = Tokens.sha256(refreshToken)

        var reuseClientId: String? = null
        var reuseTokenId: String? = null
        var errorResp: ApiServer.Resp? = null
        var issued: JSONObject? = null
        var issuedLog: String? = null

        synchronized(lock) {
            val rec = refreshTokens[hash]
            if (rec == null) {
                errorResp = oauthError(400, "invalid_grant", "Invalid refresh token")
                return@synchronized
            }
            if (rec.retired) {
                // 7.2: reuse of a rotated/retired refresh token revokes ALL tokens of this client.
                revokeAllForClientLocked(rec.clientId)
                saveLocked()
                reuseClientId = rec.clientId
                reuseTokenId = rec.tokenId
                errorResp = oauthError(400, "invalid_grant", "Refresh token reuse detected; all client tokens revoked")
                return@synchronized
            }
            // 7.8 #7: form client_id MUST match refresh token owner.
            if (rec.clientId != clientId) {
                errorResp = oauthError(400, "invalid_grant", "client_id mismatch")
                return@synchronized
            }
            if (now >= rec.expiresAt) {
                refreshTokens.remove(hash)
                saveLocked()
                errorResp = oauthError(400, "invalid_grant", "Refresh token expired")
                return@synchronized
            }
            // 7.8 #7: omitted resource keeps original bound value; provided resource MUST match original.
            if (resource != null && !matchesResource(resource, rec.resource)) {
                errorResp = oauthError(400, "invalid_target", "resource mismatch")
                return@synchronized
            }
            val tok = Tokens.byId(rec.tokenId)?.takeIf { it.publicOnly }
            if (tok == null) {
                revokeAllForClientLocked(rec.clientId)
                saveLocked()
                errorResp = oauthError(400, "invalid_grant", "Client token was revoked")
                return@synchronized
            }

            // Rotate: mark old refresh token retired (keep hash until original expiry to detect reuse),
            // mint new access + refresh tokens WITHOUT modifying the Tokens entry (7.7).
            rec.retired = true
            val currentScope = if (rec.scope == "operate") "operate" else "look" // the bound stays the pairing's
            val rawAccess = randomToken()
            val rawRefresh = randomToken()
            val newAccessHash = Tokens.sha256(rawAccess)
            accessTokens[newAccessHash] = AccessTokenRecord(
                tokenId = tok.id,
                clientId = rec.clientId,
                expiresAt = now + ACCESS_TOKEN_TTL_MS,
                resource = rec.resource,
                scope = currentScope,
            )
            knownTokenClients[newAccessHash] = rec.clientId
            refreshTokens[Tokens.sha256(rawRefresh)] = RefreshTokenRecord(
                tokenId = tok.id,
                clientId = rec.clientId,
                expiresAt = now + REFRESH_TOKEN_TTL_MS,
                resource = rec.resource,
                scope = currentScope,
                retired = false,
            )
            pruneExpiredLocked(now)
            saveLocked()
            issuedLog = "client=${rec.clientId} token=${tok.id} scope=$currentScope"
            issued = JSONObject()
                .put("access_token", rawAccess)
                .put("token_type", "Bearer")
                .put("expires_in", ACCESS_TOKEN_TTL_MS / 1000L)
                .put("refresh_token", rawRefresh)
                .put("scope", effectiveScope(currentScope, tok))
        }

        if (reuseClientId != null) {
            Audit.security("public_refresh_reuse", "client=$reuseClientId token=$reuseTokenId")
        }
        errorResp?.let { return it }
        issuedLog?.let { Audit.security("public_token_refreshed", it) }
        return ApiServer.Resp.json(issued!!)
    }

    // ── Access token verification & revocation ─────────────────────────────

    /**
     * Verify a Bearer access token presented to `POST /mcp` on [PublicServer], returning
     * either [AccessCheck.Valid] or [AccessCheck.Invalid] with `knownClientId` populated
     * when the token belonged to a known client (expired, wrong resource, or revoked) so
     * [PublicServer] can bucket known-client failures by `client_id` while isolating unknown
     * Bearer tokens strictly to the source IP bucket (beta8 Section I).
     */
    fun inspectAccessToken(rawToken: String?, expectedResource: String): AccessCheck {
        if (rawToken.isNullOrBlank()) return AccessCheck.Invalid(null)
        val now = nowMs()
        val hash = Tokens.sha256(rawToken)
        val (rec, knownCid) = synchronized(lock) {
            val r = accessTokens[hash]
            r to (r?.clientId ?: knownTokenClients[hash])
        }
        if (rec == null) return AccessCheck.Invalid(knownCid)
        if (now >= rec.expiresAt) return AccessCheck.Invalid(rec.clientId)
        if (!matchesResource(rec.resource, expectedResource)) return AccessCheck.Invalid(rec.clientId)
        val tok = Tokens.byId(rec.tokenId)?.takeIf { it.publicOnly } ?: return AccessCheck.Invalid(rec.clientId)
        return AccessCheck.Valid(AccessAuth(tok, rec.clientId, effectiveScope(rec.scope, tok)))
    }

    /** Verify a Bearer access token presented to `POST /mcp` on [PublicServer]. */
    fun validateAccessToken(rawToken: String?, expectedResource: String): AccessAuth? =
        (inspectAccessToken(rawToken, expectedResource) as? AccessCheck.Valid)?.auth

    fun revokeAllForToken(tokenId: String) {
        synchronized(lock) {
            val cids = clients.values.filter { it.tokenId == tokenId }.map { it.clientId }
            var changed = accessTokens.entries.removeAll { it.value.tokenId == tokenId }
            if (refreshTokens.entries.removeAll { it.value.tokenId == tokenId }) changed = true
            if (authCodes.entries.removeAll { it.value.tokenId == tokenId }) changed = true
            cids.forEach { cid ->
                clients.remove(cid)
                changed = true
            }
            if (changed) saveLocked()
        }
    }

    private fun revokeAllForClientLocked(clientId: String) {
        accessTokens.entries.removeAll { it.value.clientId == clientId }
        refreshTokens.entries.removeAll { it.value.clientId == clientId }
        authCodes.entries.removeAll { it.value.clientId == clientId }
    }

    fun revokeAll() {
        synchronized(lock) {
            clients.clear()
            accessTokens.clear()
            refreshTokens.clear()
            authCodes.clear()
            pairings.clear()
            saveLocked()
        }
    }

    fun activeClientCount(): Int = synchronized(lock) { clients.values.count { it.tokenId != null } }

    // ── Pairing HTML page ──────────────────────────────────────────────────

    private fun escapeHtml(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private fun pairingPageHtml(p: Pairing): String {
        val safeName = escapeHtml(p.clientName)
        val safeHost = escapeHtml(p.redirectHost)
        val safeCode = escapeHtml(p.code)
        val safeId = escapeHtml(p.id)
        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Agent-VScreen · OAuth</title>
<style>
body{margin:0;padding:24px;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#0f1118;color:#eef1f8;display:flex;min-height:100vh;align-items:center;justify-content:center;box-sizing:border-box}
.card{max-width:440px;width:100%;background:#181c28;border:1px solid #2b3145;border-radius:20px;padding:28px;text-align:center;box-shadow:0 16px 40px rgba(0,0,0,.4)}
h1{font-size:20px;margin:0 0 8px}
.sub{font-size:14px;color:#9aa3b8;margin:0 0 20px;line-height:1.5}
.code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:32px;font-weight:700;letter-spacing:4px;padding:16px;border-radius:14px;background:#232842;color:#b8aeff;margin:16px 0}
.meta{font-size:13px;color:#c3cbe0;text-align:left;background:#121520;border-radius:12px;padding:12px 14px;margin:16px 0;line-height:1.6}
.status{font-size:14px;color:#9aa3b8;margin-top:14px}
.err{color:#ff7b7b}
#cb{display:none;text-align:left;margin-top:16px}
#cb textarea{width:100%;box-sizing:border-box;height:84px;background:#121520;color:#c3cbe0;border:1px solid #2b3145;border-radius:10px;padding:8px;font:12px ui-monospace,monospace}
#cb button,#cb a{display:inline-block;margin:8px 8px 0 0;padding:8px 14px;border-radius:10px;border:0;background:#6f5cf2;color:#fff;font-size:14px;text-decoration:none}
</style>
</head>
<body>
<div class="card">
  <h1>请在手机的 Agent-VScreen 里确认</h1>
  <p class="sub">Please confirm in Agent-VScreen on your phone and verify this pairing code matches.</p>
  <div class="code">$safeCode</div>
  <div class="meta">
    <div><strong>Client:</strong> $safeName</div>
    <div><strong>Redirect host:</strong> $safeHost</div>
  </div>
  <div id="st" class="status">等待手机确认… / Waiting for phone confirmation…</div>
  <div id="cb">
    <div class="sub">若跳转后打不开（比如在手机浏览器里打开了电脑上的回调地址），复制下面的完整地址发给 agent。<br>If the redirect does not open (e.g. a computer's callback opened on a phone), copy this full address to the agent.</div>
    <textarea id="cbu" readonly></textarea>
    <button id="cp" type="button">复制 / Copy</button><a id="go" href="#">继续跳转 / Continue</a>
  </div>
</div>
<script>
(function(){
  var id="$safeId";
  var st=document.getElementById("st");
  // 2.0.0-beta11: the callback is shown and kept (Back returns here) because a phone browser cannot reach a
  // 127.0.0.1 callback on another machine and its address bar hides the code.
  function show(u){
    document.getElementById("cb").style.display="block";
    var t=document.getElementById("cbu"); t.value=u;
    document.getElementById("go").href=u;
    document.getElementById("cp").onclick=function(){
      t.select();
      try{navigator.clipboard.writeText(u);}catch(e){try{document.execCommand("copy");}catch(e2){}}
      st.textContent="已复制 / Copied";
    };
  }
  try{var kept=sessionStorage.getItem("avs_cb_"+id);if(kept){st.textContent="已确认 / Confirmed";show(kept);return;}}catch(e){}
  function poll(){
    fetch("/oauth/authorize/status?id="+encodeURIComponent(id),{cache:"no-store"})
      .then(function(r){return r.json();})
      .then(function(d){
        if(d.status==="approved"&&d.redirect_uri){
          st.textContent="已确认，正在跳转… / Confirmed, redirecting…";
          try{sessionStorage.setItem("avs_cb_"+id,d.redirect_uri);}catch(e){}
          show(d.redirect_uri);
          setTimeout(function(){window.location.assign(d.redirect_uri);},1500);
          return;
        }
        if(d.status==="denied"){
          st.className="status err";
          st.textContent="已在手机上拒绝 / Denied on phone.";
          return;
        }
        if(d.status==="expired"||d.error){
          st.className="status err";
          st.textContent="配对请求已过期，请重新发起连接 / Pairing expired; please retry.";
          return;
        }
        setTimeout(poll,$PAIRING_POLL_MS);
      })
      .catch(function(){setTimeout(poll,$PAIRING_POLL_MS);});
  }
  setTimeout(poll,$PAIRING_POLL_MS);
})();
</script>
</body>
</html>"""
    }

    private fun errorPageHtml(title: String, msg: String): String = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Agent-VScreen</title>
<style>body{margin:0;padding:24px;font-family:sans-serif;background:#0f1118;color:#eef1f8;display:flex;min-height:100vh;align-items:center;justify-content:center}.card{max-width:420px;background:#181c28;border:1px solid #3a2329;border-radius:18px;padding:24px}h1{font-size:18px;margin:0 0 10px;color:#ff8a8a}p{font-size:14px;color:#c3cbe0;line-height:1.5;margin:0}</style>
</head><body><div class="card"><h1>${escapeHtml(title)}</h1><p>${escapeHtml(msg)}</p></div></body></html>"""

    internal fun resetForTests(file: File? = null) {
        synchronized(lock) {
            clients.clear()
            accessTokens.clear()
            refreshTokens.clear()
            authCodes.clear()
            pairings.clear()
            knownTokenClients.clear()
            registerTimesGlobal.clear()
            registerTimesBySource.clear()
            deniesBySource.clear()
            denyCooldownBySource.clear()
            storageFile = file
            clock = { System.currentTimeMillis() }
            clockOffsetMs = 0L
            deviceSecureCheck = { true }
            pairingPresenterOverride = null
        }
    }
}
