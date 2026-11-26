package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.ApiServer
import com.kilon.agentvscreen.api.PublicServer
import com.kilon.agentvscreen.host.Cloudflared
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.host.TunnelSelfTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

/** 2.0.0-beta11: Cloudflare Tunnel as a second public channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V200Beta11CloudflareTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val token = Base64.getEncoder().encodeToString("""{"a":"acct0123456789","t":"5e3c1f2a-1111-2222-3333-444455556666","s":"c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0"}""".toByteArray())

    @Before fun setUp() { I18n.choice = I18n.Lang.ZH; PublicAccess.init(ctx); Cloudflared.init(ctx); Cloudflared.clear() }
    @After fun tearDown() { PublicAccess.setChannel(ctx, PublicAccess.Channel.TAILSCALE); Cloudflared.clear() }

    @Test fun tokenComesOutOfWhateverIsPastedAndHostnamesAreCleaned() {
        assertEquals(token, Cloudflared.extractToken("sudo cloudflared service install $token"))
        assertEquals(token, Cloudflared.extractToken("cloudflared.exe tunnel run --token $token"))
        assertEquals(token, Cloudflared.extractToken(token))
        assertNull(Cloudflared.extractToken("eyJ" + "x".repeat(60)))
        assertEquals("5e3c1f2a-1111-2222-3333-444455556666", Cloudflared.parseToken(token)!!.tunnelId)
        assertEquals("phone.example.com", Cloudflared.normalizeHost("https://Phone.Example.com/mcp"))
        assertNull(Cloudflared.normalizeHost("not a host"))
        assertNull(Cloudflared.normalizeHost("phone.tailnet.ts.net"))
        assertTrue(Cloudflared.save("garbage", null)!!.contains("令牌"))
        assertNull(Cloudflared.save("cloudflared service install $token", "phone.example.com"))
        assertTrue(Cloudflared.config.value.complete)
        assertEquals("https://phone.example.com", Cloudflared.config.value.baseUrl)
    }

    @Test fun theChannelDecidesThePublicAddressAndTheChecklist() {
        Cloudflared.save(token, "phone.example.com")
        PublicAccess.setChannel(ctx, PublicAccess.Channel.CLOUDFLARE)
        assertEquals("https://phone.example.com/mcp", PublicAccess.mcpUrl())
        val c = PublicAccess.cloudflareChecklist(Cloudflared.config.value, deviceSecure = true)
        assertTrue(c.allOk)
        assertFalse(PublicAccess.cloudflareChecklist(Cloudflared.Config(), deviceSecure = true).allOk)
    }

    @Test fun theCloudflareOriginMarksEveryRequestPublicAndTakesTheSourceFromCloudflare() {
        val api = ApiServer(ctx)
        val srv = PublicServer(ctx, api, secretProvider = { "s3cret" }, baseProvider = { "https://phone.example.com" }, wakeService = {}, cloudflareOrigin = true)
        val raw = ApiServer.Req("GET", "/.well-known/oauth-protected-resource", emptyMap(),
            mapOf("host" to "phone.example.com", "x-avs-public" to "forged", "x-avs-public-src" to "10.0.0.1", "x-vscreen-tailnet" to "1", "cf-connecting-ip" to "203.0.113.7"), ByteArray(0))
        val r = srv.fromCloudflare(raw)
        assertEquals("s3cret", r.headers["x-avs-public"])
        assertEquals("203.0.113.7", r.headers["x-avs-public-src"])
        assertNull(r.headers["x-vscreen-tailnet"])
        val resp = srv.route(r)
        assertEquals(200, resp.status)
        assertEquals("https://phone.example.com/mcp", JSONObject(String(resp.body)).getString("resource"))
        // A name in Cf-Connecting-IP is never used (nor resolved).
        assertNull(srv.fromCloudflare(ApiServer.Req("GET", "/", emptyMap(), mapOf("cf-connecting-ip" to "evil.example"), ByteArray(0))).headers["x-avs-public-src"])
        // The Funnel listener still requires the bridge header.
        val funnel = PublicServer(ctx, api, secretProvider = { "s3cret" }, baseProvider = { "https://phone.example.com" }, wakeService = {})
        assertEquals(403, funnel.route(raw.let { ApiServer.Req(it.method, it.target, it.query, it.headers - "x-avs-public", it.body) }).status)
    }

    @Test fun clientErrorsAndSelfTestResultsAreExplained() {
        assertTrue(Cloudflared.explain("2026-11-25T02:00:00Z ERR Provided Tunnel token is not valid")!!.contains("令牌"))
        assertTrue(Cloudflared.explain("ERR Unable to reach the origin service. dial tcp 127.0.0.1:18790: connect: connection refused")!!.contains("127.0.0.1:18790"))
        assertNull(Cloudflared.explain("INF Starting metrics server"))
        val base = "https://phone.example.com"
        assertEquals(TunnelSelfTest.Ok, Cloudflared.judge(base, 200, """{"resource":"$base/mcp"}"""))
        assertTrue((Cloudflared.judge(base, 530, "error code: 1033") as TunnelSelfTest.Failed).title.contains("未连接"))
        assertTrue((Cloudflared.judge(base, 502, "Bad gateway") as TunnelSelfTest.Failed).fix.contains("18790"))
        assertTrue(Cloudflared.judge(base, 200, """{"resource":"https://other/mcp"}""") is TunnelSelfTest.Failed)
    }
}
