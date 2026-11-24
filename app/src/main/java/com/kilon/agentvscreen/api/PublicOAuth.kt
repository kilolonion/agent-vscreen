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

    // Named limits & lifetimes per spec 7.2–7.3.
    const val REGISTER_MAX_PER_HOUR = 5
    const val REGISTER_WINDOW_MS = 60 * 60_000L
    const val MAX_PENDING_PAIRINGS = 1
    const val AUTHORIZE_DENY_LIMIT = 3
    const val AUTHORIZE_DENY_COOLDOWN_MS = 30 * 60_000L
    const val PAIRING_TTL_MS = 5 * 60_000L
    const val PAIRING_POLL_MS = 2_000L
    const val AUTH_CODE_TTL_MS = 60_000L
    const val ACCESS_TOKEN_TTL_MS = 60 * 60_000L
    const val REFRESH_TOKEN_TTL_MS = 30L * 24 * 60 * 60_000L
    const val TOKEN_BYTES = 32

    /** 8-char pairing code alphabet without 0 / O / 1 / I (7.2). */
    const val PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val ALLOWED_SCOPES = listOf("look", "operate")

    private val SUPPORTED_GRANTS = setOf("authorization_code", "refresh_token")
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
    ) {
        @Volatile var status: String = "pending" // pending | approved | denied | expired | completed
        @Volatile var redirectLocation: String? = null
        @Volatile var confirmId: String? = null
    }

    data class AccessAuth(val token: Tokens.Token, val clientId: String, val scope: String)

    private val lock = Any()
    private val rng = SecureRandom()
    private val clients = LinkedHashMap<String, RegisteredClient>()
    private val accessTokens = HashMap<String, AccessTokenRecord>()
    private val refreshTokens = HashMap<String, RefreshTokenRecord>()
    private val authCodes = HashMap<String, AuthCodeRecord>()
    private val pairings = LinkedHashMap<String, Pairing>()
    private val registerTimes = ArrayDeque<Long>()
    private var recentDenies = 0
    private var denyCooldownUntil = 0L

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
                .put("grant_types_supported", JSONArray(listOf("authorization_code", "refresh_token")))
                .put("code_challenge_methods_supported", JSONArray().put("S256"))
                .put("token_endpoint_auth_methods_supported", JSONArray().put("none"))
                .put("scopes_supported", JSONArray(ALLOWED_SCOPES)),
        )
    }

    // ── Dynamic client registration (RFC 7591) ─────────────────────────────

    fun handleRegister(r: ApiServer.Req): ApiServer.Resp {
        val now = nowMs()
        val rateLimited = synchronized(lock) {
            while (registerTimes.isNotEmpty() && now - registerTimes.first() >= REGISTER_WINDOW_MS) {
                registerTimes.removeFirst()
            }
            if (registerTimes.size >= REGISTER_MAX_PER_HOUR) {
                true
            } else {
                registerTimes.addLast(now)
                false
            }
        }
        if (rateLimited) {
            Audit.security("public_register_rate_limited", "limit=$REGISTER_MAX_PER_HOUR/h")
            return oauthError(429, "too_many_requests", "Registration rate limit exceeded (max $REGISTER_MAX_PER_HOUR per hour)")
        }

        val body = runCatching { JSONObject(r.bodyText) }.getOrNull()
            ?: return oauthError(400, "invalid_client_metadata", "Request body must be a JSON object")

        val urisJson = body.optJSONArray("redirect_uris")
            ?: return oauthError(400, "invalid_redirect_uri", "redirect_uris is required")
        if (urisJson.length() == 0 || urisJson.length() > 16) {
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

        body.optJSONArray("grant_types")?.let { arr ->
            val reqGrants = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { g -> g.isNotBlank() } }
            if (reqGrants.any { it !in SUPPORTED_GRANTS } || (reqGrants.isNotEmpty() && "authorization_code" !in reqGrants)) {
                return oauthError(400, "invalid_client_metadata", "Only authorization_code and refresh_token grant_types are supported")
            }
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
            .put("grant_types", JSONArray(listOf("authorization_code", "refresh_token")))
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

        val pairing: Pairing = synchronized(lock) {
            pruneExpiredLocked(now)
            if (now < denyCooldownUntil) {
                val leftMin = ((denyCooldownUntil - now) / 60_000L).coerceAtLeast(1L)
                return oauthError(429, "access_denied", "Authorization temporarily locked after repeated denials; retry in $leftMin min")
            }
            val activePending = pairings.values.count { it.status == "pending" && now - it.createdAt < PAIRING_TTL_MS }
            if (activePending >= MAX_PENDING_PAIRINGS) {
                return oauthError(429, "temporarily_unavailable", "Another pairing confirmation is already pending on the phone")
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
            )
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

        val html = pairingPageHtml(pairing)
        val csp = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'"
        return ApiServer.Resp.html(html, 200, mapOf("Content-Security-Policy" to csp))
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

    private fun onPairingApproved(pairing: Pairing, rawScope: String, by: String) {
        val now = nowMs()
        val safeScope = if (rawScope == "operate" && "operate" in pairing.selectableScopes) "operate" else "look"
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
                Tokens.setScope(existingTok.id, safeScope)
                Tokens.byId(existingTok.id) ?: existingTok
            } else {
                Tokens.createPublic(client.clientName, safeScope).also { created ->
                    client.tokenId = created.id
                }
            }
            tokenId = tok.id
            recentDenies = 0

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
            pairing.redirectLocation = buildRedirectUrl(pairing.redirectUri, rawCode, pairing.state)
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
            recentDenies++
            if (recentDenies >= AUTHORIZE_DENY_LIMIT) {
                denyCooldownUntil = now + AUTHORIZE_DENY_COOLDOWN_MS
                recentDenies = 0
                enteredCooldown = true
            }
        }
        if (enteredCooldown) {
            Audit.security("public_authorize_cooldown", "client=${pairing.clientId} cooldown_min=${AUTHORIZE_DENY_COOLDOWN_MS / 60_000L}")
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
            null -> oauthError(400, "invalid_request", "grant_type is required")
            else -> oauthError(400, "unsupported_grant_type", "Only authorization_code and refresh_token are supported")
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
        if (rec.redirectUri != redirectUri) {
            return oauthError(400, "invalid_grant", "redirect_uri mismatch")
        }
        val expectedRes = rec.resource.ifBlank { mcpResource(base) }
        if (resource != null && !matchesResource(resource, expectedRes)) {
            return oauthError(400, "invalid_target", "resource mismatch")
        }
        if (!verifyPkceS256(codeVerifier, rec.codeChallenge)) {
            return oauthError(400, "invalid_grant", "PKCE code_verifier verification failed")
        }
        val tok = Tokens.byId(rec.tokenId)?.takeIf { it.publicOnly }
            ?: return oauthError(400, "invalid_grant", "Client token was revoked")

        val grantedScope = if (rec.grantedScope == "operate" && tok.scope == "operate") "operate" else "look"
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
                scope = grantedScope,
            )
            refreshTokens[refreshHash] = RefreshTokenRecord(
                tokenId = tok.id,
                clientId = rec.clientId,
                expiresAt = now + REFRESH_TOKEN_TTL_MS,
                resource = expectedRes,
                scope = grantedScope,
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
            val currentScope = if (tok.scope == "operate") "operate" else "look"
            val rawAccess = randomToken()
            val rawRefresh = randomToken()
            accessTokens[Tokens.sha256(rawAccess)] = AccessTokenRecord(
                tokenId = tok.id,
                clientId = rec.clientId,
                expiresAt = now + ACCESS_TOKEN_TTL_MS,
                resource = rec.resource,
                scope = currentScope,
            )
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
                .put("scope", currentScope)
        }

        if (reuseClientId != null) {
            Audit.security("public_refresh_reuse", "client=$reuseClientId token=$reuseTokenId")
        }
        errorResp?.let { return it }
        issuedLog?.let { Audit.security("public_token_refreshed", it) }
        return ApiServer.Resp.json(issued!!)
    }

    // ── Access token verification & revocation ─────────────────────────────

    /** Verify a Bearer access token presented to `POST /mcp` on [PublicServer]. */
    fun validateAccessToken(rawToken: String?, expectedResource: String): AccessAuth? {
        if (rawToken.isNullOrBlank()) return null
        val now = nowMs()
        val hash = Tokens.sha256(rawToken)
        val rec = synchronized(lock) { accessTokens[hash] } ?: return null
        if (now >= rec.expiresAt) return null
        if (!matchesResource(rec.resource, expectedResource)) return null
        val tok = Tokens.byId(rec.tokenId)?.takeIf { it.publicOnly } ?: return null
        val safeScope = if (tok.scope == "operate") "operate" else "look"
        return AccessAuth(tok, rec.clientId, safeScope)
    }

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
</div>
<script>
(function(){
  var id="$safeId";
  var st=document.getElementById("st");
  function poll(){
    fetch("/oauth/authorize/status?id="+encodeURIComponent(id),{cache:"no-store"})
      .then(function(r){return r.json();})
      .then(function(d){
        if(d.status==="approved"&&d.redirect_uri){
          st.textContent="已确认，正在跳转… / Confirmed, redirecting…";
          window.location.replace(d.redirect_uri);
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
            registerTimes.clear()
            recentDenies = 0
            denyCooldownUntil = 0L
            storageFile = file
            clock = { System.currentTimeMillis() }
            clockOffsetMs = 0L
            deviceSecureCheck = { true }
            pairingPresenterOverride = null
        }
    }
}
