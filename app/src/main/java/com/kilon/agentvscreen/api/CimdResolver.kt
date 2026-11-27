package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.cmd.ScreenText
import com.kilon.agentvscreen.host.Audit
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * OAuth Client ID Metadata Document (CIMD) resolver with strict SSRF defense and bounded caching.
 *
 * When a public MCP OAuth client presents an `https://` URL as its `client_id`, this resolver:
 * 1. Validates strict HTTPS URL syntax (non-empty path, no userinfo/query/fragment/IP literal/non-443 port).
 * 2. Resolves all DNS records and blocks loopback, RFC1918 private, link-local, CGNAT/Tailscale (`100.64.0.0/10`),
 *    IPv6 ULA (`fc00::/7`), multicast, and unspecified addresses.
 * 3. Connects directly to the validated [InetAddress] list over TLS with SNI and HTTPS hostname verification
 *    for the original hostname (eliminating DNS rebinding TOCTOU), redirects disabled, 5 s timeouts, 64 KB cap,
 *    JSON Content-Type check, and per-source fetch rate limiting (10/hour).
 * 4. Verifies `client_id` matches the URL verbatim, `redirect_uris` are valid, and `token_endpoint_auth_method`
 *    is `"none"` (or absent), caching up to 64 entries capped at 24 h.
 */
internal object CimdResolver {
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 5_000
    const val MAX_BODY_BYTES = 64 * 1024
    const val MAX_HEADER_BYTES = 16 * 1024
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
    @Volatile internal var tlsSocketOpener: (InetAddress, String, Int, Int) -> Socket = { addr, host, port, timeoutMs ->
        openPinnedTlsSocket(addr, host, port, timeoutMs)
    }
    @Volatile internal var httpFetcher: (URI, List<InetAddress>) -> FetchResponse = { uri, addrs ->
        defaultFetch(uri, addrs)
    }

    internal fun resetForTest() = synchronized(lock) {
        cache.clear()
        fetchTimesBySource.clear()
        clock = { System.currentTimeMillis() }
        dnsResolver = { host -> InetAddress.getAllByName(host) }
        tlsSocketOpener = { addr, host, port, timeoutMs -> openPinnedTlsSocket(addr, host, port, timeoutMs) }
        httpFetcher = { uri, addrs -> defaultFetch(uri, addrs) }
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
            httpFetcher(uri, addrs.toList())
        } catch (e: BodyTooLargeException) {
            Audit.security("public_cimd_too_large", "src=$sourceBucket host=$host")
            return Result.Error(400, "invalid_client_metadata", "CIMD metadata document exceeds ${MAX_BODY_BYTES / 1024} KB limit")
        } catch (e: SSLException) {
            Audit.security("public_cimd_tls_failed", "src=$sourceBucket host=$host")
            return Result.Error(400, "invalid_client", "TLS verification failed for CIMD metadata host")
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

    private fun openPinnedTlsSocket(addr: InetAddress, host: String, port: Int, timeoutMs: Int): Socket {
        val raw = Socket()
        var ssl: SSLSocket? = null
        try {
            raw.connect(InetSocketAddress(addr, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            ssl = sslFactory.createSocket(raw, host, port, true) as SSLSocket
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                throw SSLPeerUnverifiedException("TLS hostname verification failed for $host")
            }
            return ssl
        } catch (t: Throwable) {
            runCatching { ssl?.close() }
            runCatching { raw.close() }
            throw t
        }
    }

    internal fun defaultFetch(uri: URI, validatedAddrs: List<InetAddress>): FetchResponse {
        if (validatedAddrs.isEmpty()) throw IOException("No validated addresses for CIMD fetch")
        if (validatedAddrs.any { isForbiddenAddress(it) }) throw IOException("Forbidden address in CIMD fetch")

        val host = uri.host.lowercase(Locale.ROOT)
        val port = if (uri.port == -1) 443 else uri.port
        val rawPath = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"

        var sock: Socket? = null
        var lastErr: IOException? = null
        for (addr in validatedAddrs) {
            try {
                sock = tlsSocketOpener(addr, host, port, CONNECT_TIMEOUT_MS)
                break
            } catch (e: SSLException) {
                throw e
            } catch (e: IOException) {
                lastErr = e
            }
        }
        val activeSock = sock ?: throw (lastErr ?: IOException("Failed to connect to validated CIMD addresses"))

        return activeSock.use { s ->
            val deadlineNs = System.nanoTime() + READ_TIMEOUT_MS * 1_000_000L
            fun applyRemainingTimeout() {
                val remMs = ((deadlineNs - System.nanoTime()) / 1_000_000L).toInt()
                if (remMs <= 0) throw IOException("CIMD read timed out")
                s.soTimeout = remMs
            }

            applyRemainingTimeout()
            val req = buildString {
                append("GET ").append(rawPath).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("Accept: application/json\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val outStream = s.getOutputStream()
            outStream.write(req.toByteArray(Charsets.US_ASCII))
            outStream.flush()

            val ins = BufferedInputStream(s.getInputStream(), 4096)
            val headerBytes = readHeaderBlock(ins, ::applyRemainingTimeout)
            val headerText = String(headerBytes, Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val statusLine = lines.firstOrNull()?.takeIf { it.startsWith("HTTP/1.") }
                ?: throw IOException("Invalid HTTP status line from CIMD host")
            val statusParts = statusLine.split(' ', limit = 3)
            val status = statusParts.getOrNull(1)?.takeIf { it.length == 3 }?.toIntOrNull()
                ?: throw IOException("Invalid HTTP status code from CIMD host")

            var contentType: String? = null
            var cacheControl: String? = null
            var contentLength: Long? = null
            var chunked = false
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                val value = line.substring(colon + 1).trim()
                when (name) {
                    "content-type" -> if (contentType == null) contentType = value
                    "cache-control" -> cacheControl = if (cacheControl == null) value else "$cacheControl, $value"
                    "content-length" -> {
                        val parsed = value.toLongOrNull()
                        if (parsed == null || parsed < 0L) throw IOException("Invalid Content-Length")
                        if (contentLength != null && contentLength != parsed) throw IOException("Conflicting Content-Length")
                        contentLength = parsed
                    }
                    "transfer-encoding" -> {
                        if (value.lowercase(Locale.ROOT).split(',').any { it.trim() == "chunked" }) {
                            chunked = true
                        }
                    }
                }
            }

            if (status in 300..399) {
                // Never follow redirects.
                return@use FetchResponse(status, contentType, cacheControl, ByteArray(0))
            }
            if (chunked && contentLength != null) throw IOException("Both Transfer-Encoding and Content-Length present")

            val body = when {
                chunked -> readChunkedBody(ins, ::applyRemainingTimeout)
                contentLength != null -> readFixedBody(ins, contentLength, ::applyRemainingTimeout)
                else -> readBoundedUntilEof(ins, ::applyRemainingTimeout)
            }
            FetchResponse(status, contentType, cacheControl, body)
        }
    }

    private fun readHeaderBlock(ins: InputStream, updateTimeout: () -> Unit): ByteArray {
        val out = ByteArrayOutputStream(1024)
        var state = 0
        while (true) {
            updateTimeout()
            val b = ins.read()
            if (b < 0) throw IOException("Unexpected EOF while reading CIMD response headers")
            if (out.size() >= MAX_HEADER_BYTES) throw IOException("CIMD response headers too large")
            out.write(b)
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) return out.toByteArray() else if (b == '\r'.code) 1 else 0
                else -> 0
            }
        }
    }

    private fun readLineCrLf(ins: InputStream, maxLen: Int, updateTimeout: () -> Unit): String {
        val out = ByteArrayOutputStream(64)
        while (true) {
            updateTimeout()
            val b = ins.read()
            if (b < 0) throw IOException("Unexpected EOF in chunked CIMD body")
            if (b == '\n'.code) {
                val arr = out.toByteArray()
                val end = if (arr.isNotEmpty() && arr[arr.size - 1] == '\r'.code.toByte()) arr.size - 1 else arr.size
                return String(arr, 0, end, Charsets.ISO_8859_1)
            }
            if (out.size() >= maxLen) throw IOException("Chunk line too long")
            out.write(b)
        }
    }

    private fun readFixedBody(ins: InputStream, length: Long, updateTimeout: () -> Unit): ByteArray {
        if (length > MAX_BODY_BYTES.toLong()) throw BodyTooLargeException()
        val target = length.toInt()
        val out = ByteArray(target)
        var offset = 0
        while (offset < target) {
            updateTimeout()
            val n = ins.read(out, offset, target - offset)
            if (n <= 0) throw IOException("Truncated CIMD response body")
            offset += n
        }
        return out
    }

    private fun readChunkedBody(ins: InputStream, updateTimeout: () -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLineCrLf(ins, 256, updateTimeout)
            val hex = line.substringBefore(';').trim()
            if (hex.isEmpty() || hex.length > 8 || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                throw IOException("Invalid chunk size in CIMD response")
            }
            val size = hex.toLongOrNull(16) ?: throw IOException("Invalid chunk size in CIMD response")
            if (size == 0L) {
                // Consume optional trailers up to empty line (bounded).
                var trailerBytes = 0
                while (true) {
                    val tl = readLineCrLf(ins, 1024, updateTimeout)
                    if (tl.isEmpty()) break
                    trailerBytes += tl.length + 2
                    if (trailerBytes > 4096) throw IOException("CIMD chunk trailers too large")
                }
                return out.toByteArray()
            }
            if (size > MAX_BODY_BYTES.toLong() || out.size().toLong() + size > MAX_BODY_BYTES.toLong()) {
                throw BodyTooLargeException()
            }
            var rem = size.toInt()
            val buf = ByteArray(minOf(rem, 4096))
            while (rem > 0) {
                updateTimeout()
                val n = ins.read(buf, 0, minOf(rem, buf.size))
                if (n <= 0) throw IOException("Truncated chunk in CIMD response")
                out.write(buf, 0, n)
                rem -= n
            }
            val cr = ins.read()
            val lf = ins.read()
            if (cr != '\r'.code || lf != '\n'.code) throw IOException("Missing CRLF after chunk in CIMD response")
        }
    }

    private fun readBoundedUntilEof(ins: InputStream, updateTimeout: () -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            updateTimeout()
            val n = ins.read(buf)
            if (n <= 0) break
            if (out.size() + n > MAX_BODY_BYTES) throw BodyTooLargeException()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
