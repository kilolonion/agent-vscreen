package com.kilon.agentvscreen.api

import android.content.Context
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.quiet
import com.kilon.agentvscreen.tr
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * 2.0.0-beta7 (7.3, 7.7): shared HTTP request parser, size/time limits, response writer, and
 * auth failure lockout used by both [ApiServer] and [PublicServer] without duplicating code.
 */
internal class HttpError(val status: Int, val code: String, message: String) : Exception(message)

internal object HttpCore {
    const val MAX_CONN = 24
    const val MAX_HEAD = 16_384
    const val MAX_BODY = 1_048_576
    const val HEAD_MS = 10_000L
    const val BODY_MS = 20_000L
    const val MAX_FAILS = 8
    const val LOCK_MS = 5 * 60_000L
    private const val MAX_TRAILER_LINES = 16
    private const val MAX_TRAILER_BYTES = 4_096
    private const val READ_CHUNK_SIZE = 16_384

    @Volatile internal var monoClockMs: () -> Long = { System.nanoTime() / 1_000_000L }

    private fun monoMs(): Long = monoClockMs()

    private fun applyDeadline(t0: Long, limitMs: Long, sock: java.net.Socket?, timeoutMsg: String) {
        val rem = limitMs - (monoMs() - t0)
        if (rem <= 0L) throw HttpError(408, "REQUEST_TIMEOUT", timeoutMsg)
        if (sock != null) {
            runCatching { sock.soTimeout = rem.coerceIn(1L, 5_000L).toInt() }
        }
    }

    fun readRequest(inp: InputStream, sock: java.net.Socket? = null): ApiServer.Req? {
        val t0 = monoMs()
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            applyDeadline(t0, HEAD_MS, sock, "request headers took too long")
            val b = inp.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > MAX_HEAD) throw HttpError(431, "HEADERS_TOO_LARGE", "request headers over $MAX_HEAD bytes")
            if (monoMs() - t0 > HEAD_MS) {
                throw HttpError(408, "REQUEST_TIMEOUT", "request headers took too long")
            }
        }
        val rawHead = head.toString("UTF-8")
        val lines = rawHead.removeSuffix("\r\n\r\n").split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw HttpError(400, "BAD_REQUEST", "bad request line")
        }
        val headers = HashMap<String, String>()
        var contentLengthValue: String? = null
        var transferEncodings: MutableList<String>? = null
        for (l in lines.drop(1)) {
            if (l.isEmpty()) continue
            val i = l.indexOf(':')
            if (i <= 0) throw HttpError(400, "BAD_REQUEST", "malformed header line")
            val rawName = l.substring(0, i)
            if (rawName.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7e }) {
                throw HttpError(400, "BAD_REQUEST", "invalid header name")
            }
            val key = rawName.lowercase(java.util.Locale.ROOT)
            val value = l.substring(i + 1).trim()
            if (key == "content-length") {
                val parsed = parseContentLengthValue(value)
                if (contentLengthValue != null && contentLengthValue != parsed) {
                    throw HttpError(400, "BAD_REQUEST", "conflicting Content-Length headers")
                }
                contentLengthValue = parsed
                headers[key] = parsed
            } else if (key == "transfer-encoding") {
                val list = transferEncodings ?: ArrayList<String>().also { transferEncodings = it }
                for (tok in value.split(',')) {
                    val t = tok.trim().lowercase(java.util.Locale.ROOT)
                    if (t.isNotEmpty()) list += t
                }
                headers[key] = list.joinToString(", ")
            } else {
                headers[key] = value
            }
        }
        val teList = transferEncodings
        if (teList != null && contentLengthValue != null) {
            throw HttpError(400, "BAD_REQUEST", "both Transfer-Encoding and Content-Length present")
        }
        val body: ByteArray = if (teList != null) {
            if (teList.size != 1 || teList[0] != "chunked") {
                throw HttpError(400, "BAD_REQUEST", "unsupported Transfer-Encoding")
            }
            readChunked(inp, t0, sock)
        } else if (contentLengthValue != null) {
            val lenLong = contentLengthValue.toLongOrNull()
                ?: throw HttpError(413, "BODY_TOO_LARGE", "request body over $MAX_BODY bytes")
            if (lenLong < 0L || lenLong > MAX_BODY.toLong()) {
                throw HttpError(413, "BODY_TOO_LARGE", "request body over $MAX_BODY bytes")
            }
            val len = lenLong.toInt()
            readFixedBody(inp, len, t0, sock)
        } else {
            ByteArray(0)
        }
        val target = parts[1]
        return ApiServer.Req(parts[0].uppercase(java.util.Locale.ROOT), target, parseForm(target.substringAfter('?', "")), headers, body)
    }

    private fun parseContentLengthValue(raw: String): String {
        val parts = raw.split(',').map { it.trim() }
        if (parts.isEmpty() || parts.any { it.isEmpty() || !it.all { c -> c in '0'..'9' } }) {
            throw HttpError(400, "BAD_REQUEST", "invalid Content-Length")
        }
        val first = parts[0].trimStart('0').ifEmpty { "0" }
        for (p in parts.drop(1)) {
            if (p.trimStart('0').ifEmpty { "0" } != first) {
                throw HttpError(400, "BAD_REQUEST", "conflicting Content-Length values")
            }
        }
        return first
    }

    private fun readFixedBody(inp: InputStream, len: Int, t0: Long, sock: java.net.Socket?): ByteArray {
        if (len == 0) return ByteArray(0)
        val out = ByteArrayOutputStream(minOf(len, READ_CHUNK_SIZE))
        val buf = ByteArray(minOf(len, READ_CHUNK_SIZE))
        var remaining = len
        while (remaining > 0) {
            applyDeadline(t0, BODY_MS, sock, "request body took too long")
            val n = inp.read(buf, 0, minOf(remaining, buf.size))
            if (n < 0) throw HttpError(400, "BAD_REQUEST", "body shorter than Content-Length")
            out.write(buf, 0, n)
            remaining -= n
            if (monoMs() - t0 > BODY_MS) throw HttpError(408, "REQUEST_TIMEOUT", "request body took too long")
        }
        return out.toByteArray()
    }

    /** Transfer-Encoding: chunked request bodies with [MAX_BODY] and [BODY_MS] limits. */
    fun readChunked(inp: InputStream, t0: Long, sock: java.net.Socket? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(READ_CHUNK_SIZE)
        fun line(): String {
            val sb = StringBuilder()
            while (true) {
                applyDeadline(t0, BODY_MS, sock, "request body took too long")
                val c = inp.read()
                if (c < 0) throw HttpError(400, "BAD_REQUEST", "chunked body cut off")
                if (c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
                if (sb.length > 256) throw HttpError(400, "BAD_REQUEST", "bad chunk header")
            }
            return sb.toString()
        }
        while (true) {
            val rawSize = line().substringBefore(';').trim()
            if (rawSize.isEmpty() || !rawSize.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw HttpError(400, "BAD_REQUEST", "bad chunk size")
            }
            val size = if (rawSize.length > 8) {
                throw HttpError(413, "BODY_TOO_LARGE", "request body over $MAX_BODY bytes")
            } else {
                rawSize.toLongOrNull(16) ?: throw HttpError(400, "BAD_REQUEST", "bad chunk size")
            }
            if (size == 0L) {
                var trailerLines = 0
                var trailerBytes = 0
                while (true) {
                    val tline = line()
                    if (tline.isEmpty()) break
                    trailerLines++
                    trailerBytes += tline.length + 2
                    if (trailerLines > MAX_TRAILER_LINES || trailerBytes > MAX_TRAILER_BYTES) {
                        throw HttpError(431, "HEADERS_TOO_LARGE", "chunk trailers too large")
                    }
                }
                break
            }
            val remainingAllowed = (MAX_BODY - out.size()).toLong()
            if (size > remainingAllowed) {
                throw HttpError(413, "BODY_TOO_LARGE", "request body over $MAX_BODY bytes")
            }
            var remaining = size.toInt()
            while (remaining > 0) {
                applyDeadline(t0, BODY_MS, sock, "request body took too long")
                val n = inp.read(buf, 0, minOf(remaining, buf.size))
                if (n < 0) throw HttpError(400, "BAD_REQUEST", "chunked body cut off")
                out.write(buf, 0, n)
                remaining -= n
                if (monoMs() - t0 > BODY_MS) throw HttpError(408, "REQUEST_TIMEOUT", "request body took too long")
            }
            if (line().isNotEmpty()) {
                throw HttpError(400, "BAD_REQUEST", "bad chunk terminator")
            }
            if (monoMs() - t0 > BODY_MS) throw HttpError(408, "REQUEST_TIMEOUT", "request body took too long")
        }
        return out.toByteArray()
    }

    fun writeResponse(out: OutputStream, r: ApiServer.Resp, versionHeader: String) {
        val reason = when (r.status) {
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            302 -> "Found"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val sb = StringBuilder("HTTP/1.1 ${r.status} $reason\r\nContent-Type: ${r.type}\r\n")
        if (r.stream == null) sb.append("Content-Length: ${r.body.size}\r\n")
        else sb.append("Transfer-Encoding: chunked\r\n")
        sb.append("Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-VScreen-Version: $versionHeader\r\n")
        r.headers.forEach { (k, v) ->
            sb.append(k).append(": ").append(v.replace("\r", "").replace("\n", " ")).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        if (r.stream == null) {
            out.write(r.body)
            out.flush()
        } else {
            out.flush()
            val em = ApiServer.Emitter(out, chunked = true)
            try {
                r.stream.invoke(em)
            } finally {
                em.finish()
            }
        }
    }

    fun parseForm(s: String): Map<String, List<String>> {
        if (s.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (kv in s.split('&')) {
            if (kv.isEmpty()) continue
            val rawK = kv.substringBefore('=')
            val rawV = if ('=' in kv) kv.substringAfter('=') else ""
            val k = runCatching { URLDecoder.decode(rawK, "UTF-8") }.getOrDefault(rawK)
            val v = runCatching { URLDecoder.decode(rawV, "UTF-8") }.getOrDefault(rawV)
            out.getOrPut(k) { ArrayList() } += v
        }
        return out
    }
}

/**
 * Shared Bearer/auth failure sliding-window tracker and lockout (7.3, 7.7).
 * Used identically by [ApiServer] (local/tailnet) and [PublicServer] (public MCP).
 */
internal class AuthFailLimiter(
    private val ctx: Context,
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() },
    @Volatile internal var sleepFn: (Long) -> Unit = { ms -> if (ms > 0L) Thread.sleep(ms) },
) {
    private val fails = LinkedHashMap<String, ArrayDeque<Long>>()
    private val lockedUntil = LinkedHashMap<String, Long>()
    private val lastAlertByChannel = HashMap<String, Long>()

    private fun channelOf(who: String): String = if (who.startsWith("public-")) "public" else "tailnet"

    private fun pruneLocked(now: Long) {
        if (fails.size > 500) {
            fails.entries.removeAll { (_, q) -> q.isEmpty() || now - q.last() > 60_000L }
            while (fails.size > 500) {
                val oldest = fails.keys.firstOrNull() ?: break
                fails.remove(oldest)
            }
        }
        if (lockedUntil.size > 500) {
            lockedUntil.entries.removeAll { (_, until) -> until <= now }
            while (lockedUntil.size > 500) {
                val oldest = lockedUntil.keys.firstOrNull() ?: break
                lockedUntil.remove(oldest)
            }
        }
    }

    fun checkLocked(who: String, remote: Boolean): ApiServer.Resp? {
        if (!remote) return null
        val now = clock()
        val until = synchronized(fails) {
            val u = lockedUntil[who]
            if (u != null && u <= now) {
                lockedUntil.remove(who)
                null
            } else {
                u
            }
        } ?: return null
        val secs = (until - now) / 1000L + 1L
        return ApiServer.Resp.err(
            429,
            "RATE_LIMITED",
            tr("验证失败次数太多，$secs 秒后再试", "Too many failed verifications; try again in $secs s"),
        )
    }

    fun recordFailure(
        who: String,
        remote: Boolean,
        path: String,
        code: String,
        msg: String,
        status: Int = 401,
        headers: Map<String, String> = emptyMap(),
    ): ApiServer.Resp {
        val now = clock()
        val n = synchronized(fails) {
            val q = fails.getOrPut(who) { ArrayDeque() }
            q.addLast(now)
            while (q.isNotEmpty() && now - q.first() > 60_000L) q.removeFirst()
            pruneLocked(now)
            q.size
        }
        Audit.security("auth_failed", "$who $code $path")
        if (remote && n >= HttpCore.MAX_FAILS) {
            val channel = channelOf(who)
            val first = synchronized(fails) {
                val was = lockedUntil[who] ?: 0L
                lockedUntil[who] = now + HttpCore.LOCK_MS
                pruneLocked(now)
                was < now
            }
            // Coalesce alerts per channel ("public" vs "tailnet") at most once per lock period so public scans
            // cannot suppress a Tailnet lockout warning (or vice versa).
            val alert = first && synchronized(fails) {
                val prev = lastAlertByChannel[channel] ?: 0L
                (now - prev >= HttpCore.LOCK_MS).also { if (it) lastAlertByChannel[channel] = now }
            }
            if (first) Audit.security("locked_out", who)
            if (alert) {
                quiet("lockout-notify") {
                    com.kilon.agentvscreen.core.VScreenHandoff.alert(
                        ctx,
                        if (channel == "public") 7391 else 7390,
                        tr("有设备在反复尝试连接 Agent-VScreen", "A device keeps trying to connect to Agent-VScreen"),
                        tr(
                            "$who 一分钟内 $n 次验证失败，已暂停接受它的请求 ${HttpCore.LOCK_MS / 60_000} 分钟。不是你的设备的话，在 其它设备 里关掉远程访问或重置令牌",
                            "$who failed verification $n times within a minute; its requests are refused for ${HttpCore.LOCK_MS / 60_000} minutes. If it is not your device, turn off remote access or reset the token under Other devices",
                        ),
                        com.kilon.agentvscreen.host.Needs.open(ctx, com.kilon.agentvscreen.host.Needs.Page.CONNECT, 2),
                    )
                }
            }
        }
        // Never block remote HTTP worker threads with sleep (prevents thread-pool exhaustion DoS);
        // keep a brief local backoff only.
        if (!remote) sleepFn(100L)
        return ApiServer.Resp.err(status, code, msg).plus(headers)
    }

    internal fun trackedFailCountForTests(): Int = synchronized(fails) { fails.size }
    internal fun trackedLockCountForTests(): Int = synchronized(fails) { lockedUntil.size }
    internal fun lastAlertAtForChannelForTests(channel: String): Long = synchronized(fails) { lastAlertByChannel[channel] ?: 0L }

    internal fun resetForTests() {
        synchronized(fails) {
            lastAlertByChannel.clear()
            fails.clear()
            lockedUntil.clear()
        }
    }
}
