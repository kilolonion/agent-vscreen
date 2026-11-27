package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.cmd.ScreenText
import com.kilon.agentvscreen.host.Audit
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * OAuth Client ID Metadata Document (CIMD) resolver with strict SSRF defense and bounded caching.
 *
 * When a public MCP OAuth client presents an `https://` URL as its `client_id`, this resolver:
 * 1. Validates strict HTTPS URL syntax (non-empty path, no userinfo/query/fragment/IP literal/non-443 port).
 * 2. Resolves all DNS records and blocks loopback, RFC1918 private, link-local, CGNAT/Tailscale (`100.64.0.0/10`),
 *    IPv6 ULA (`fc00::/7`), multicast, and unspecified addresses.
 * 3. Fetches the document with redirects disabled, 5 s timeouts, 64 KB cap, JSON Content-Type check, and
 *    per-source fetch rate limiting (10/hour).
 * 4. Verifies `client_id` matches the URL verbatim, `redirect_uris` are valid, and `token_endpoint_auth_method`
 *    is `"none"` (or absent), caching up to 64 entries capped at 24 h.
 */
internal object CimdResolver {
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 5_000
    const val MAX_BODY_BYTES = 64 * 1024
    const val MAX_CACHE_ENTRIES = 64
    const val DEFAULT_CACHE_TTL_MS = 60 * 60_000L
    const val MAX_CACHE_TTL_MS = 24 * 60 * 60_000L
    const val FETCH_WINDOW_MS = 60 * 60_000L
    const val MAX_FETCH_PER_HOUR = 10
    private const val MAX_URL_LEN = 2048

    private val MAX_AGE_RE = Regex("(?i)(?:^|,)\\s*max-age\\s*=\\s*(\\d+)")
    private val SUPPORTED_GRANTS = setOf(
        "authorization_code",
        "refresh_token",
        PublicOAuth.DEVICE_GRANT,
    )

    data class Document(
        val clientId: String,
        val clientName: String,
        val verifiedDomain: String,
        val redirectUris: List<String>,
        val requestedScope: String?,
        val deviceOnly: Boolean,
    )

    sealed class Result {
        data class Ok(val doc: Document) : Result()
        data class Error(val httpStatus: Int, val oauthError: String, val description: String) : Result()
    }

    data class FetchResponse(
        val status: Int,
        val contentType: String?,
        val cacheControl: String?,
        val body: ByteArray,
    )

    private data class CacheEntry(
        val doc: Document,
        val expiresAt: Long,
    )

    private val lock = Object()
    private val cache = object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES + 4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private val fetchTimesBySource = HashMap<String, ArrayDeque<Long>>()

    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile internal var dnsResolver: (String) -> Array<InetAddress> = { host -> InetAddress.getAllByName(host) }
    @Volatile internal var httpFetcher: (URI) -> FetchResponse = { uri -> defaultFetch(uri) }

    internal fun resetForTest() = synchronized(lock) {
        cache.clear()
        fetchTimesBySource.clear()
        clock = { System.currentTimeMillis() }
        dnsResolver = { host -> InetAddress.getAllByName(host) }
        httpFetcher = { uri -> defaultFetch(uri) }
    }

    /** True when [clientId] looks like a CIMD URL (`https://...`). */
    fun isCimdClientId(clientId: String?): Boolean =
        clientId != null && clientId.startsWith("https://")

    /**
     * Validate CIMD URL syntax:
     * - `https://` scheme only, max 2048 chars, no control chars or whitespace
     * - non-empty path (not empty and not `/`), no `.` or `..` path segments
     * - no userinfo, query, fragment, IP literal host, or non-443 port
     */
    fun validateClientIdUrl(raw: String): URI? {
        if (raw.length > MAX_URL_LEN || !raw.startsWith("https://")) return null
        if (raw.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        if (uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true) return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        if (!isValidPublicDomainSyntax(host)) return null
        val rawPath = uri.rawPath ?: return null
        if (rawPath.isEmpty() || rawPath == "/") return null
        val segs = rawPath.split('/')
        if (segs.any { it == "." || it == ".." }) return null
        return uri
    }

    private fun isValidPublicDomainSyntax(host: String): Boolean {
        if (host.length > 253) return false
        if (host.startsWith("[") || host.endsWith("]") || ':' in host) return false
        if (PublicOAuth.isValidIpLiteral(host)) return false
        if ('.' !in host) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return false
        }
        val labels = host.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return false
        if (labels.any { lbl -> lbl.any { ch -> ch !in 'a'..'z' && ch !in '0'..'9' && ch != '-' } }) return false
        // Reject all-numeric TLD (catches decimal/octal IP tricks).
        if (labels.last().all { it in '0'..'9' }) return false
        return true
    }

    /** SSRF IP filter: returns true for any non-public / loopback / LAN / link-local / CGNAT / Tailscale / ULA / multicast address. */
    fun isForbiddenAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) return true

        val bytes = addr.address
        if (addr is Inet4Address && bytes.size == 4) {
            return isForbiddenIpv4(bytes[0].toInt() and 0xff, bytes[1].toInt() and 0xff, bytes[2].toInt() and 0xff)
        }
        if (addr is Inet6Address && bytes.size == 16) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            // Unique Local Address fc00::/7
            if ((b0 and 0xfe) == 0xfc) return true
            // Link-local fe80::/10
            if (b0 == 0xfe && (b1 and 0xc0) == 0x80) return true
            // Site-local deprecated fec0::/10
            if (b0 == 0xfe && (b1 and 0xc0) == 0xc0) return true
            // Multicast ff00::/8
            if (b0 == 0xff) return true
            // Unspecified :: or loopback ::1
            val allZeroPrefix = (0..14).all { bytes[it] == 0.toByte() }
            if (allZeroPrefix && (bytes[15] == 0.toByte() || bytes[15] == 1.toByte())) return true
            // IPv4-mapped ::ffff:0:0/96
            val ipv4Mapped = (0..9).all { bytes[it] == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
            if (ipv4Mapped) {
                return isForbiddenIpv4(bytes[12].toInt() and 0xff, bytes[13].toInt() and 0xff, bytes[14].toInt() and 0xff)
            }
            // IPv4-compatible ::/96
            val ipv4Compat = (0..11).all { bytes[it] == 0.toByte() }
            if (ipv4Compat) {
                return isForbiddenIpv4(bytes[12].toInt() and 0xff, bytes[13].toInt() and 0xff, bytes[14].toInt() and 0xff)
            }
            // NAT64 well-known prefix 64:ff9b::/96 -> check embedded IPv4
            val nat64 = b0 == 0x00 && b1 == 0x64 && (bytes[2].toInt() and 0xff) == 0xff && (bytes[3].toInt() and 0xff) == 0x9b &&
                (4..11).all { bytes[it] == 0.toByte() }
            if (nat64) {
                return isForbiddenIpv4(bytes[12].toInt() and 0xff, bytes[13].toInt() and 0xff, bytes[14].toInt() and 0xff)
            }
        }
        return false
    }

    private fun isForbiddenIpv4(b0: Int, b1: Int, b2: Int): Boolean = when {
        b0 == 0 -> true                                     // 0.0.0.0/8 ("this network")
        b0 == 10 -> true                                    // 10.0.0.0/8 RFC1918
        b0 == 100 && (b1 and 0xc0) == 64 -> true            // 100.64.0.0/10 CGNAT / Tailscale
        b0 == 127 -> true                                   // 127.0.0.0/8 loopback
        b0 == 169 && b1 == 254 -> true                      // 169.254.0.0/16 link-local / cloud metadata
        b0 == 172 && (b1 and 0xf0) == 16 -> true            // 172.16.0.0/12 RFC1918
        b0 == 192 && b1 == 0 && b2 == 0 -> true             // 192.0.0.0/24 IETF protocol assignments
        b0 == 192 && b1 == 168 -> true                      // 192.168.0.0/16 RFC1918
        b0 == 198 && (b1 == 18 || b1 == 19) -> true         // 198.18.0.0/15 benchmarking
        b0 >= 224 -> true                                   // 224.0.0.0/4 multicast + 240.0.0.0/4 reserved/broadcast
        else -> false
    }

    /** Resolve and validate a CIMD `client_id`, serving from bounded LRU cache when fresh. */
    fun resolve(clientId: String, sourceBucket: String): Result {
        val uri = validateClientIdUrl(clientId)
            ?: return Result.Error(
                400,
                "invalid_client",
                "Invalid CIMD client_id URL: must be https:// with a non-empty path, public hostname, default port 443, and no userinfo/query/fragment",
            )
        val now = clock()
        synchronized(lock) {
            val cached = cache[clientId]
            if (cached != null && now < cached.expiresAt) {
                return Result.Ok(cached.doc)
            }
            if (cached != null) cache.remove(clientId)
        }

        // Per-source fetch rate limit (cache misses only) to prevent fetch amplification.
        val limited = synchronized(lock) {
            val q = fetchTimesBySource.getOrPut(sourceBucket) { ArrayDeque() }
            while (q.isNotEmpty() && now - q.first() >= FETCH_WINDOW_MS) q.removeFirst()
            if (fetchTimesBySource.size > 500) {
                fetchTimesBySource.entries.removeAll { (k, dq) ->
                    k != sourceBucket && (dq.isEmpty() || now - dq.last() >= FETCH_WINDOW_MS)
                }
            }
            if (q.size >= MAX_FETCH_PER_HOUR) {
                true
            } else {
                q.addLast(now)
                false
            }
        }
        if (limited) {
            Audit.security("public_cimd_rate_limited", "src=$sourceBucket host=${uri.host}")
            return Result.Error(429, "too_many_requests", "CIMD metadata fetch rate limit exceeded (max $MAX_FETCH_PER_HOUR per hour)")
        }

        val host = uri.host.lowercase(Locale.ROOT)
        val addrs = runCatching { dnsResolver(host) }.getOrNull()
        if (addrs == null || addrs.isEmpty()) {
            Audit.security("public_cimd_dns_failed", "src=$sourceBucket host=$host")
            return Result.Error(400, "invalid_client", "Could not resolve CIMD host: $host")
        }
        if (addrs.any { isForbiddenAddress(it) }) {
            Audit.security("public_cimd_ssrf_blocked", "src=$sourceBucket host=$host")
            return Result.Error(400, "invalid_client", "CIMD host resolves to a restricted or non-public network address")
        }

        val resp = try {
            httpFetcher(uri)
        } catch (e: BodyTooLargeException) {
            Audit.security("public_cimd_too_large", "src=$sourceBucket host=$host")
            return Result.Error(400, "invalid_client_metadata", "CIMD metadata document exceeds ${MAX_BODY_BYTES / 1024} KB limit")
        } catch (e: Exception) {
            Audit.security("public_cimd_fetch_failed", "src=$sourceBucket host=$host")
            return Result.Error(400, "invalid_client", "Failed to fetch CIMD metadata document")
        }

        if (resp.status in 300..399) {
            Audit.security("public_cimd_redirect_blocked", "src=$sourceBucket host=$host status=${resp.status}")
            return Result.Error(400, "invalid_client", "CIMD metadata URL must not redirect")
        }
        if (resp.status != 200) {
            return Result.Error(400, "invalid_client", "CIMD metadata fetch returned HTTP ${resp.status}")
        }
        if (!isJsonContentType(resp.contentType)) {
            return Result.Error(400, "invalid_client_metadata", "CIMD metadata Content-Type must be application/json")
        }
        if (resp.body.size > MAX_BODY_BYTES) {
            return Result.Error(400, "invalid_client_metadata", "CIMD metadata document exceeds ${MAX_BODY_BYTES / 1024} KB limit")
        }

        val json = runCatching { JSONObject(String(resp.body, Charsets.UTF_8)) }.getOrNull()
            ?: return Result.Error(400, "invalid_client_metadata", "CIMD metadata document is not valid JSON")

        val doc = when (val parsed = validateMetadataDocument(clientId, host, json)) {
            is Result.Ok -> parsed.doc
            is Result.Error -> return parsed
        }

        val ttlMs = parseCacheTtlMs(resp.cacheControl)
        if (ttlMs > 0L) {
            synchronized(lock) {
                cache[clientId] = CacheEntry(doc, now + ttlMs)
            }
        }
        Audit.security("public_cimd_resolved", "host=$host src=$sourceBucket")
        return Result.Ok(doc)
    }

    internal fun isJsonContentType(raw: String?): Boolean {
        val mediaType = raw?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        return mediaType == "application/json" || mediaType.endsWith("+json")
    }

    internal fun parseCacheTtlMs(cacheControl: String?): Long {
        if (cacheControl.isNullOrBlank()) return DEFAULT_CACHE_TTL_MS
        val lower = cacheControl.lowercase(Locale.ROOT)
        if ("no-store" in lower || "no-cache" in lower) return 0L
        val m = MAX_AGE_RE.find(lower) ?: return DEFAULT_CACHE_TTL_MS
        val sec = m.groupValues[1].toLongOrNull() ?: return DEFAULT_CACHE_TTL_MS
        if (sec <= 0L) return 0L
        return (sec * 1000L).coerceAtMost(MAX_CACHE_TTL_MS)
    }

    internal fun validateMetadataDocument(expectedClientId: String, verifiedDomain: String, body: JSONObject): Result {
        // Verbatim client_id equality required by OAuth CIMD draft.
        val docClientId = body.optString("client_id", "")
        if (docClientId != expectedClientId) {
            return Result.Error(400, "invalid_client_metadata", "CIMD document client_id must exactly match the requested client_id URL")
        }

        val authMethod = body.optString("token_endpoint_auth_method").takeIf { it.isNotBlank() && !body.isNull("token_endpoint_auth_method") } ?: "none"
        if (authMethod != "none") {
            return Result.Error(400, "invalid_client_metadata", "Only public CIMD clients (token_endpoint_auth_method=none) are supported")
        }

        val reqGrants: List<String> = body.optJSONArray("grant_types")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { g -> g.isNotBlank() } }
        } ?: emptyList()
        if (reqGrants.any { it !in SUPPORTED_GRANTS } ||
            (reqGrants.isNotEmpty() && "authorization_code" !in reqGrants && PublicOAuth.DEVICE_GRANT !in reqGrants)
        ) {
            return Result.Error(400, "invalid_client_metadata", "Unsupported grant_types in CIMD metadata")
        }
        val deviceOnly = PublicOAuth.DEVICE_GRANT in reqGrants && "authorization_code" !in reqGrants

        val urisJson = body.optJSONArray("redirect_uris") ?: if (deviceOnly) JSONArray() else
            return Result.Error(400, "invalid_redirect_uri", "CIMD metadata requires redirect_uris")
        if ((urisJson.length() == 0 && !deviceOnly) || urisJson.length() > 16) {
            return Result.Error(400, "invalid_redirect_uri", "CIMD redirect_uris must have 1..16 entries")
        }
        val uris = ArrayList<String>(urisJson.length())
        for (i in 0 until urisJson.length()) {
            val u = urisJson.optString(i, "")
            if (!PublicOAuth.isValidRedirectUri(u)) {
                return Result.Error(400, "invalid_redirect_uri", "Invalid redirect_uri in CIMD metadata")
            }
            if (u !in uris) uris += u
        }

        val rawName = ScreenText.clean(body.optString("client_name"), maxLen = 64)
        val clientName = rawName.ifEmpty { verifiedDomain }
        val reqScope = body.optString("scope").takeIf { it.isNotBlank() && !body.isNull("scope") }?.let { ScreenText.clean(it, maxLen = 128) }?.takeIf { it.isNotEmpty() }

        return Result.Ok(
            Document(
                clientId = expectedClientId,
                clientName = clientName,
                verifiedDomain = verifiedDomain,
                redirectUris = uris,
                requestedScope = reqScope,
                deviceOnly = deviceOnly,
            ),
        )
    }

    private class BodyTooLargeException : Exception()

    private fun defaultFetch(uri: URI): FetchResponse {
        val conn = (URL(uri.toString()).openConnection() as? HttpsURLConnection)
            ?: throw IllegalStateException("Expected HTTPS connection")
        conn.instanceFollowRedirects = false
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.useCaches = false
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        try {
            val status = conn.responseCode
            val ct = conn.contentType
            val cc = conn.getHeaderField("Cache-Control")
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val out = ByteArrayOutputStream()
            if (stream != null) {
                stream.use { ins ->
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        if (out.size() + n > MAX_BODY_BYTES) throw BodyTooLargeException()
                        out.write(buf, 0, n)
                    }
                }
            }
            return FetchResponse(status, ct, cc, out.toByteArray())
        } finally {
            conn.disconnect()
        }
    }
}
