package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.host.Ngrok
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.host.TunnelSelfTest
import com.kilon.agentvscreen.host.TunnelState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 2.0.0-beta11: ngrok as the third public channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V200Beta11NgrokTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    /** Shaped like an authtoken (letters, digits, one underscore); built so secret scanners see no literal. */
    private val token = "2test" + "0123456789".repeat(3) + "_" + "abcdefghij".repeat(2)

    @Before fun setUp() { I18n.choice = I18n.Lang.ZH; PublicAccess.init(ctx); Ngrok.clear(); Ngrok.stop("test") }
    @After fun tearDown() { PublicAccess.setChannel(ctx, PublicAccess.Channel.TAILSCALE); Ngrok.clear() }

    @Test fun tokenAndDomainComeOutOfWhateverIsPasted() {
        assertEquals(token, Ngrok.extractToken("ngrok config add-authtoken $token"))
        assertEquals(token, Ngrok.extractToken(" $token\n"))
        assertNull(Ngrok.extractToken("ngrok config add-authtoken"))
        assertEquals("name.ngrok-free.app", Ngrok.normalizeDomain("https://Name.ngrok-free.app/"))
        assertNull(Ngrok.normalizeDomain("phone.tailnet.ts.net"))
        assertTrue(Ngrok.save("short", null)!!.contains("authtoken"))
        assertNull(Ngrok.save("ngrok config add-authtoken $token", "name.ngrok-free.app"))
        assertTrue(Ngrok.config.value.complete)
    }

    @Test fun theChannelUsesTheNgrokDomainAndChecklist() {
        assertFalse(PublicAccess.ngrokChecklist(Ngrok.config.value, deviceSecure = true).allOk)
        Ngrok.save(token, "name.ngrok-free.app")
        PublicAccess.setChannel(ctx, PublicAccess.Channel.NGROK)
        assertSame(Ngrok, PublicAccess.channel.tunnel)
        assertEquals("https://name.ngrok-free.app/mcp", PublicAccess.mcpUrl())
        assertTrue(PublicAccess.ngrokChecklist(Ngrok.config.value, deviceSecure = true).allOk)
        assertFalse(PublicAccess.ngrokChecklist(Ngrok.config.value, deviceSecure = false).allOk)
    }

    @Test fun agentLinesDriveTheStatusAndErrorsAreExplained() {
        Ngrok.save(token, "name.ngrok-free.app")
        Ngrok.onLine("""{"ngrok":"error","reason":"failed to connect: authentication failed: The authtoken you specified is invalid. ERR_NGROK_105"}""")
        assertEquals(TunnelState.ERROR, Ngrok.status.value.state)
        assertTrue(Ngrok.status.value.problem!!.contains("authtoken"))
        Ngrok.onLine("""{"ngrok":"online","url":"https://other.ngrok-free.app"}""")
        assertEquals(TunnelState.CONNECTED, Ngrok.status.value.state)
        assertNotNull(Ngrok.status.value.problem) // not the domain entered
        assertTrue(Ngrok.explain("ERR_NGROK_108 session limit").contains("一个"))
        // The authtoken never reaches the log or the status.
        Ngrok.onLine("""{"ngrok":"error","reason":"bad token $token"}""")
        assertFalse(Ngrok.status.value.raw!!.contains(token))
    }

    @Test fun selfTestResultsAreExplained() {
        val base = "https://name.ngrok-free.app"
        assertEquals(TunnelSelfTest.Ok, Ngrok.judge(base, 200, """{"resource":"$base/mcp"}"""))
        assertTrue((Ngrok.judge(base, 404, "ERR_NGROK_3200") as TunnelSelfTest.Failed).title.contains("离线"))
        assertTrue((Ngrok.judge(base, 502, "ERR_NGROK_8012") as TunnelSelfTest.Failed).title.contains("转不到"))
    }
}
