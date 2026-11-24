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

    private fun monoMs(): Long = System.nanoTime() / 1_000_000L

    fun readRequest(inp: InputStream): ApiServer.Req? {
        val t0 = monoMs()
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = inp.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > MAX_HEAD) throw HttpError(431, "HEADERS_TOO_LARGE", "request headers over $MAX_HEAD bytes")
            if (head.size() % 512 == 0 && monoMs() - t0 > HEAD_MS) {
                throw HttpError(408, "REQUEST_TIMEOUT", "request headers took too long")
            }
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) throw HttpError(400, "BAD_REQUEST", "bad request line")
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val body: ByteArray = if (headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true) {
            readChunked(inp, t0)
        } else {
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len < 0 || len > MAX_BODY) throw HttpError(413, "BODY_TOO_LARGE", "request body over $MAX_BODY bytes")
            val b = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = inp.read(b, off, len - off)
                if (n < 0) throw HttpError(400, "BAD_REQUEST", "body shorter than Content-Length")
                off += n
                if (monoMs() - t0 > BODY_MS) throw HttpError(408, "REQUEST_TIMEOUT", "request body took too long")
            }
            b
        }
        val target = parts[1]
        return ApiServer.Req(parts[0].uppercase(), target, parseForm(target.substringAfter('?', "")), headers, body)
    }

    /** Transfer-Encoding: chunked request bodies with [MAX_BODY] and [BODY_MS] limits. */
    fun readChunked(inp: InputStream, t0: Long): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val c = inp.read()
                if (c < 0) throw HttpError(400, "BAD_REQUEST", "chunked body cut off")
                if (c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
                if (sb.length > 256) throw HttpError(400, "BAD_REQUEST", "bad chunk header")
            }
            return sb.toString()
        }
        while (true) {
            val size = line().substringBefore(';').trim().toIntOrNull(16)
                ?: throw HttpError(400, "BAD_REQUEST", "bad chunk size")
            if (size == 0) {
                while (line().isNotEmpty()) { /* trailers */ }
                break
            }
            if (size < 0 || out.size() + size > MAX_BODY) {
                throw HttpError(413, "BODY_TOO_LARGE", "request body over $MAX_BODY bytes")
            }
            val b = ByteArray(size)
            var off = 0
            while (off < size) {
                val n = inp.read(b, off, size - off)
                if (n < 0) throw HttpError(400, "BAD_REQUEST", "chunked body cut off")
                off += n
            }
            out.write(b)
            line()
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
            r.stream.invoke(ApiServer.Emitter(out))
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
    private val fails = HashMap<String, ArrayDeque<Long>>()
    private val lockedUntil = HashMap<String, Long>()

    fun checkLocked(who: String, remote: Boolean): ApiServer.Resp? {
        if (!remote) return null
        val now = clock()
        val until = synchronized(fails) { lockedUntil[who] } ?: return null
        if (until <= now) return null
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
            if (fails.size > 500) fails.keys.firstOrNull()?.let { fails.remove(it) }
            q.size
        }
        Audit.security("auth_failed", "$who $code $path")
        if (remote && n >= HttpCore.MAX_FAILS) {
            val first = synchronized(fails) {
                val was = lockedUntil[who] ?: 0L
                lockedUntil[who] = now + HttpCore.LOCK_MS
                was < now
            }
            if (first) {
                Audit.security("locked_out", who)
                quiet("lockout-notify") {
                    com.kilon.agentvscreen.core.VScreenHandoff.alert(
                        ctx,
                        7390,
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
        // Slow every wrong guess down a little (bounded: it holds one worker).
        sleepFn(if (remote) 400L else 100L)
        return ApiServer.Resp.err(status, code, msg).plus(headers)
    }

    internal fun resetForTests() {
        synchronized(fails) {
            fails.clear()
            lockedUntil.clear()
        }
    }
}
