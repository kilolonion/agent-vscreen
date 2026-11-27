package com.kilon.agentvscreen.api

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Security unit tests for [HttpCore], [AuthFailLimiter], and [PublicServer.fromCloudflare] (P1-4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class HttpCoreSecurityTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        HttpCore.monoClockMs = { System.nanoTime() / 1_000_000L }
    }

    private fun readHttp(raw: String): ApiServer.Req? =
        HttpCore.readRequest(ByteArrayInputStream(raw.toByteArray(StandardCharsets.UTF_8)))

    private fun expectHttpError(expectedStatus: Int, expectedCode: String, raw: String) {
        try {
            readHttp(raw)
            fail("Expected HttpError($expectedStatus, $expectedCode) for: ${raw.take(80)}")
        } catch (e: HttpError) {
            assertEquals("status for ${raw.take(60)}", expectedStatus, e.status)
            assertEquals("code for ${raw.take(60)}", expectedCode, e.code)
        }
    }

    @Test
    fun chunkedRejectsOverflowSignedGarbageAndOversizedTrailers() {
        // Integer overflow in out.size() + size (7fffffff + small chunk wraps negative in 32-bit int)
        expectHttpError(
            413,
            "BODY_TOO_LARGE",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nabcd\r\n7fffffff\r\n",
        )
        // > 8 hex digits overflow
        expectHttpError(
            413,
            "BODY_TOO_LARGE",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n100000000\r\n",
        )
        // Signed hex chunk sizes rejected
        expectHttpError(
            400,
            "BAD_REQUEST",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n+4\r\nabcd\r\n0\r\n\r\n",
        )
        expectHttpError(
            400,
            "BAD_REQUEST",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n-4\r\n",
        )
        // Trailing garbage on chunk terminator line
        expectHttpError(
            400,
            "BAD_REQUEST",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nabcdJUNK\r\n0\r\n\r\n",
        )
        // Unbounded trailers rejected (> 16 lines)
        val manyTrailers = (1..18).joinToString("") { "X-T$it: v\r\n" }
        expectHttpError(
            431,
            "HEADERS_TOO_LARGE",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n$manyTrailers\r\n",
        )
        // Valid chunked body with chunk extension and small trailer succeeds
        val ok = readHttp(
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n5;ext=1\r\nhello\r\n0\r\nX-Check: 1\r\n\r\n",
        )
        assertNotNull(ok)
        assertEquals("hello", ok!!.bodyText)
    }

    @Test
    fun requestParserRejectsSmugglingAndMalformedHeadersAndTruncatedLargeBody() {
        // Conflicting Content-Length headers
        expectHttpError(
            400,
            "BAD_REQUEST",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 4\r\nContent-Length: 8\r\n\r\nabcd",
        )
        // Malformed Content-Length (non-digits / negative)
        expectHttpError(
            400,
            "BAD_REQUEST",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: abc\r\n\r\n",
        )
        expectHttpError(
            400,
            "BAD_REQUEST",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: -5\r\n\r\n",
        )
        // Simultaneous Transfer-Encoding + Content-Length (request smuggling)
        expectHttpError(
            400,
            "BAD_REQUEST",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 5\r\n\r\n0\r\n\r\n",
        )
        // Header name containing space before ':'
        expectHttpError(
            400,
            "BAD_REQUEST",
            "GET /v2/ping HTTP/1.1\r\nHost : 127.0.0.1\r\n\r\n",
        )
        // Unsupported Transfer-Encoding
        expectHttpError(
            400,
            "BAD_REQUEST",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: gzip\r\n\r\n",
        )
        // Fake large Content-Length (MAX_BODY) followed immediately by EOF fails cleanly without upfront 1 MiB alloc
        expectHttpError(
            400,
            "BAD_REQUEST",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${HttpCore.MAX_BODY}\r\n\r\nab",
        )
        // Content-Length > MAX_BODY returns 413
        expectHttpError(
            413,
            "BODY_TOO_LARGE",
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${HttpCore.MAX_BODY + 1}\r\n\r\n",
        )
    }

    @Test
    fun headerDeadlineEnforcedBefore512Bytes() {
        var now = 1_000L
        HttpCore.monoClockMs = { now }
        val raw = "GET /v2/ping HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
        var idx = 0
        val slowStream = object : InputStream() {
            override fun read(): Int {
                if (idx >= raw.size) return -1
                if (idx == 10) now += HttpCore.HEAD_MS + 50L
                return raw[idx++].toInt() and 0xff
            }
        }
        try {
            HttpCore.readRequest(slowStream)
            fail("Slow header (<512 bytes) must time out with 408")
        } catch (e: HttpError) {
            assertEquals(408, e.status)
            assertEquals("REQUEST_TIMEOUT", e.code)
        }
    }

    @Test
    fun authFailLimiterNoRemoteSleepPrunesMapsAndSeparatesChannelAlerts() {
        var now = 1_000_000L
        val sleeps = ArrayList<Long>()
        val limiter = AuthFailLimiter(
            ctx = ctx,
            clock = { now },
            sleepFn = { ms -> sleeps += ms },
        )

        // Remote failures must NOT sleep on worker threads; local failures keep 100ms backoff.
        limiter.recordFailure("public-src:198.51.100.1", remote = true, path = "/mcp", code = "BAD_TOKEN", msg = "bad")
        assertTrue("remote failure must not sleep", sleeps.isEmpty())
        limiter.recordFailure("local", remote = false, path = "/v2/cmd", code = "BAD_TOKEN", msg = "bad")
        assertEquals(listOf(100L), sleeps)

        // Public lockout alerts on "public" channel, leaving "tailnet" alert channel completely independent.
        for (i in 0 until HttpCore.MAX_FAILS) {
            limiter.recordFailure("public-src:203.0.113.10", remote = true, path = "/mcp", code = "BAD_TOKEN", msg = "bad")
        }
        assertTrue(limiter.lastAlertAtForChannelForTests("public") > 0L)
        assertEquals(0L, limiter.lastAlertAtForChannelForTests("tailnet"))

        // Immediately after, a Tailnet lockout fires on its own "tailnet" alert channel.
        for (i in 0 until HttpCore.MAX_FAILS) {
            limiter.recordFailure("100.64.0.9", remote = true, path = "/v2/cmd", code = "BAD_TOKEN", msg = "bad")
        }
        assertTrue(limiter.lastAlertAtForChannelForTests("tailnet") > 0L)

        // Bound both fails and lockedUntil at <= 500 entries and prune expired entries.
        for (i in 0 until 540) {
            val key = "public-src:198.51.${i / 256}.${i % 256}"
            repeat(HttpCore.MAX_FAILS) {
                limiter.recordFailure(key, remote = true, path = "/mcp", code = "BAD_TOKEN", msg = "bad")
            }
            now += 10L
        }
        assertTrue("fails map bounded <= 500", limiter.trackedFailCountForTests() <= 500)
        assertTrue("lockedUntil map bounded <= 500", limiter.trackedLockCountForTests() <= 500)
    }

    @Test
    fun cloudflareIpValidationRejectsMalformedLiterals() {
        assertTrue(PublicOAuth.isValidIpLiteral("203.0.113.7"))
        assertTrue(PublicOAuth.isValidIpLiteral("2001:db8::1"))
        assertTrue(PublicOAuth.isValidIpLiteral("::ffff:192.0.2.1"))
        assertFalse(PublicOAuth.isValidIpLiteral("999.999.999.999"))
        assertFalse(PublicOAuth.isValidIpLiteral("1.2.3"))
        assertFalse(PublicOAuth.isValidIpLiteral(":::1"))
        assertFalse(PublicOAuth.isValidIpLiteral("2001:db8::1::2"))
        assertFalse(PublicOAuth.isValidIpLiteral("evil.example"))
        assertFalse(PublicOAuth.isValidIpLiteral(".."))

        val api = ApiServer(ctx)
        val srv = PublicServer(ctx, api, secretProvider = { "s3cret" }, baseProvider = { "https://phone.example.com" }, wakeService = {}, cloudflareOrigin = true)
        assertNull(srv.fromCloudflare(ApiServer.Req("GET", "/", emptyMap(), mapOf("cf-connecting-ip" to "999.999.999.999"), ByteArray(0))).headers["x-avs-public-src"])
        assertNull(srv.fromCloudflare(ApiServer.Req("GET", "/", emptyMap(), mapOf("cf-connecting-ip" to ":::1"), ByteArray(0))).headers["x-avs-public-src"])
        assertEquals("2001:db8::1", srv.fromCloudflare(ApiServer.Req("GET", "/", emptyMap(), mapOf("cf-connecting-ip" to "2001:db8::1"), ByteArray(0))).headers["x-avs-public-src"])
    }
}
