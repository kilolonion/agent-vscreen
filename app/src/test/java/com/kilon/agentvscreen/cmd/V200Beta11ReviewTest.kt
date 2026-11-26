package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.ApiServer
import com.kilon.agentvscreen.api.Policy
import com.kilon.agentvscreen.api.Policy.Act
import com.kilon.agentvscreen.api.PublicOAuth
import com.kilon.agentvscreen.api.PublicServer
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenGame
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.Consent
import com.kilon.agentvscreen.host.Permissions
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.host.Risk
import com.kilon.agentvscreen.host.StrongConfirm
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

/** 2.0.0-beta11: beta10 real-phone report, public OAuth report and the code review (astra), fixed at their causes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V200Beta11ReviewTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val now = AtomicLong(1_760_000_000_000L)
    private val handler by lazy { VScreenOffloadHandler(ctx) }

    @Before fun setUp() {
        I18n.choice = I18n.Lang.ZH
        ApiConfig.init(ctx); Tokens.init(ctx); Audit.init(ctx); Audit.clear()
        StrongConfirm.resetForTests(tmp.newFile("approvals.json"))
        StrongConfirm.postNotification = false
        StrongConfirm.waitMsOverride = 0L
        StrongConfirm.clock = { now.get() }
        StrongConfirm.roundIdleMs = { 10 * 60_000L }
        Risk.resetForTests(); Risk.clock = { now.get() }
        Consent.reset()
        Permissions.resetForTests()
        PublicOAuth.resetForTests(tmp.newFile("public_oauth.json"))
        PublicOAuth.clock = { now.get() }
        PublicOAuth.deviceSecureCheck = { true }
        PublicAccess.resetForTests()
        PublicAccess.clock = { now.get() }
        PublicAccess.postNotifications = false
        PublicAccess.startServerOnEnable = false
    }

    @After fun tearDown() {
        PublicOAuth.pairingPresenterOverride = null
        PublicOAuth.resetForTests(); PublicAccess.resetForTests()
        StrongConfirm.resetForTests(); Risk.resetForTests()
    }

    private fun el(n: Int, text: String, l: Int, t: Int, r: Int, b: Int) = El(n, text, Rect(l, t, r, b), true)

    // R14: a coordinate tap names what is under it, so the payment check sees it.
    @Test fun coordinateTapTakesTheWordsUnderThePoint() {
        val list = listOf(el(1, "订单", 0, 0, 1000, 2000), el(2, "立即支付", 100, 1800, 900, 1900), el(3, "返回", 0, 0, 100, 100))
        assertEquals("立即支付", labelAt(list, 500, 1850)) // smallest box containing the point
        assertEquals("立即支付", labelAt(list.drop(0).filter { it.n != 1 }, 500, 1915)) // within a finger's width
        assertNull(labelAt(list.filter { it.n == 3 }, 500, 1000))
    }

    // R6: force stop is irreversible, clearing a search box is not.
    @Test fun forceStopAsksAndClearingASearchDoesNot() {
        for (w in listOf("强行停止", "强制停止", "Force stop", "结束运行")) assertEquals(w, "irreversible", VScreenGame.commitKind(w))
        for (w in listOf("删除查询", "清除搜索", "Clear query")) assertNull(w, VScreenGame.commitKind(w))
    }

    // G2: a number key matches whole numbers only.
    @Test fun numberKeysMatchWholeNumbers() {
        assertFalse(containsNumberSafe("剩余 12 项", "2"))
        assertTrue(containsNumberSafe("第 2 项", "2"))
        assertTrue(containsNumberSafe("进度 50%", "50%"))
        assertFalse(containsNumberSafe("1500", "50"))
        assertTrue(containsNumberSafe("打开设置", "设置"))
    }

    // R16: the rate limit is checked at each step, not only when a command starts.
    @Test fun rateIsCheckedMidStretch() {
        val (t, _) = Tokens.createLevel("agent-r", Tokens.CAREFUL)
        StrongConfirm.grantAgent(t.id)
        val call = Call("s1", tokenId = t.id, tokenName = t.name)
        assertEquals(Act.ASK, StrongConfirm.effectiveAct(Tokens.byId(t.id)!!, Policy.RATE))
        repeat(Risk.RATE_MAX_PER_MIN) { Risk.recordInputAction(t.id, now.get()) }
        Risk.checkRate(ctx, call) // exactly at the limit: fine
        Risk.recordInputAction(t.id, now.get())
        val code = try { Risk.checkRate(ctx, call); null } catch (e: ActionError) { e.code }
        assertEquals("NEEDS_CONFIRM", code)
        Tokens.revoke(t.id)
    }

    // Review: pre-authorised payments skip the payment card only.
    @Test fun trustedSkipsMoneyOnly() {
        val src = java.io.File("src/main/java/com/kilon/agentvscreen/host/Consent.kt").takeIf { it.exists() }
            ?: java.io.File("app/src/main/java/com/kilon/agentvscreen/host/Consent.kt")
        val line = src.readLines().single { "pre-authorized" in it }
        assertTrue(line, "kind == MONEY" in line)
    }

    // Review: the user's latest tightening ends temporary allows for those rows.
    @Test fun tighteningDropsTemporaryElevations() {
        val (t, _) = Tokens.createLevel("agent-t", Tokens.FREE)
        StrongConfirm.grantAgent(t.id)
        val call = Call("s1", tokenId = t.id, tokenName = t.name)
        val id = Call.with(call) {
            try { handler.elevateCmd(OffloadArgs(listOf("destroy", "--why", "卸载"))); null } catch (e: ActionError) { e.data?.optJSONObject("confirm")?.optString("id") }
        }
        assertTrue(StrongConfirm.approve(ctx, id!!, StrongConfirm.BY_BIOMETRIC, range = StrongConfirm.Range.TODAY))
        assertEquals(Act.ALLOW, StrongConfirm.effectiveAct(Tokens.byId(t.id)!!, Policy.DESTROY))
        Tokens.setAct(t.id, Policy.DESTROY, Act.DENY)
        assertEquals(Act.DENY, StrongConfirm.effectiveAct(Tokens.byId(t.id)!!, Policy.DESTROY))
        Tokens.revoke(t.id)
    }

    // Review: a public credential does the smaller of what it was issued and what the level allows now.
    @Test fun publicScopeIsIssuedIntersectCurrent() {
        val tok = Tokens.createPublic("c", "operate")
        Tokens.setLevel(tok.id, Tokens.CAREFUL)
        assertEquals("operate", PublicOAuth.effectiveScope("operate", Tokens.byId(tok.id)!!))
        assertEquals("look", PublicOAuth.effectiveScope("look", Tokens.byId(tok.id)!!)) // raising needs a new pairing
        Tokens.setLevel(tok.id, Tokens.LOOK)
        assertEquals("look", PublicOAuth.effectiveScope("operate", Tokens.byId(tok.id)!!)) // lowering applies at once
        Tokens.revoke(tok.id)
    }

    // Review: permissions say per row whether elevate can help and what to do next.
    @Test fun permissionsGiveCanRequestAndNextAction() {
        val (t, _) = Tokens.createLevel("agent-p", Tokens.CAREFUL)
        val call = Call("s1", tokenId = t.id, tokenName = t.name)
        val rows = Permissions.json(call).getJSONArray("classes")
        val byKey = (0 until rows.length()).associate { rows.getJSONObject(it).let { o -> o.getString("class") to o } }
        val operate = byKey.getValue(Policy.OPERATE)
        assertEquals("run", operate.getString("next_action"))
        assertFalse(operate.getBoolean("can_request"))
        val money = byKey.getValue(Policy.MONEY)
        assertFalse(money.getBoolean("can_request"))
        val system = byKey.getValue(Policy.SYSTEM)
        assertEquals(Act.DENY.key, system.getString("handling"))
        assertEquals("elevate", system.getString("next_action"))
        assertTrue(system.getBoolean("can_request"))
        Tokens.revoke(t.id)
    }

    // Review: elevate notifies the phone, so it is not a read-only tool.
    @Test fun elevateIsNotReadOnly() {
        val c = CommandSpec.commands.single { it.name == "elevate" }
        assertFalse(c.readOnly)
        assertTrue(CommandSpec.commands.single { it.name == "permissions" }.readOnly)
    }

    // OAuth report: RFC 8628 device flow (no browser) end to end.
    @Test fun deviceFlowPairsWithoutABrowser() {
        val secret = "beta11-review-secret"
        val base = "https://phone.tailnet.ts.net"
        val apiServer = ApiServer(ctx)
        val pub = PublicServer(ctx, apiServer, secretProvider = { secret }, baseProvider = { base }, wakeService = {})
        fun req(method: String, path: String, body: String = "", form: Boolean = false): ApiServer.Req {
            val h = LinkedHashMap<String, String>()
            h["host"] = "phone.tailnet.ts.net"; h["x-avs-public"] = secret; h["x-avs-public-src"] = "203.0.113.9"
            if (body.isNotEmpty()) h["content-type"] = if (form) "application/x-www-form-urlencoded" else "application/json"
            return ApiServer.Req(method, path, emptyMap(), h, body.toByteArray(Charsets.UTF_8)).apply { peer = InetAddress.getLoopbackAddress() }
        }
        val meta = JSONObject(String(pub.route(req("GET", "/.well-known/oauth-authorization-server")).body))
        assertEquals("$base/oauth/device_authorization", meta.getString("device_authorization_endpoint"))

        val reg = pub.route(req("POST", "/oauth/register", """{"client_name":"CloudVM","grant_types":["${PublicOAuth.DEVICE_GRANT}","refresh_token"],"token_endpoint_auth_method":"none"}"""))
        assertEquals(201, reg.status)
        val cid = JSONObject(String(reg.body)).getString("client_id")

        var approve: ((String) -> Unit)? = null
        PublicOAuth.pairingPresenterOverride = { _, _, ok, _ -> approve = ok; "c1" }
        val da = pub.route(req("POST", "/oauth/device_authorization", "client_id=$cid&scope=operate", form = true))
        assertEquals(200, da.status)
        val dj = JSONObject(String(da.body))
        val dc = dj.getString("device_code")
        val userCode = dj.getString("user_code")
        assertTrue(userCode.isNotBlank())
        assertEquals(PublicOAuth.DEVICE_INTERVAL_S, dj.getLong("interval"))
        assertNull("device_authorization must not auto-present confirmation card before user enters code", approve)

        fun poll(): JSONObject = JSONObject(String(pub.route(req("POST", "/oauth/token",
            "grant_type=${java.net.URLEncoder.encode(PublicOAuth.DEVICE_GRANT, "UTF-8")}&device_code=$dc&client_id=$cid", form = true)).body))
        assertEquals("authorization_pending", poll().getString("error"))
        assertEquals("slow_down", poll().getString("error"))
        now.addAndGet(PublicOAuth.DEVICE_INTERVAL_S * 1000L)
        val claim = PublicOAuth.claimPairingByCode(ctx, userCode, openActivity = false)
        assertTrue("expected ClaimResult.Opened, got $claim", claim is PublicOAuth.ClaimResult.Opened)
        assertNotNull(approve)
        approve!!.invoke(Tokens.CAREFUL)
        val tok = poll()
        assertTrue(tok.toString(), tok.optString("access_token").isNotBlank())
        assertEquals("operate", tok.getString("scope"))
        now.addAndGet(PublicOAuth.DEVICE_INTERVAL_S * 1000L)
        assertEquals("invalid_grant", poll().getString("error")) // one use only
    }

    // OAuth report: TLS 0-byte EOF means public access is off; the onboarding text says so.
    @Test fun onboardingNamesDeviceFlowAndTlsEof() {
        val zh = com.kilon.agentvscreen.api.AgentGuide.onboardPublic("https://p.ts.net")
        assertTrue("/oauth/device_authorization" in zh && "UNEXPECTED_EOF" in zh && "https://p.ts.net/oauth/token" in zh)
        I18n.choice = I18n.Lang.EN
        val en = com.kilon.agentvscreen.api.AgentGuide.onboardPublic("https://p.ts.net")
        assertTrue("RFC 8628" in en && "UNEXPECTED_EOF" in en)
    }

    // G5: a row of look-alike tabs with one closed has one copy fewer, which is evidence it went away.
    @Test fun lookAlikeItemsAreCountedBeforeAndAfter() {
        fun frame(cards: Int): Bitmap {
            val bmp = Bitmap.createBitmap(288, 640, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(Color.rgb(245, 246, 250))
            val p = Paint()
            for (i in 0 until cards) {
                val top = 40f + i * 150f
                p.color = Color.rgb(60, 64, 90); c.drawRect(40f, top, 248f, top + 110f, p)
                p.color = Color.WHITE
                for (k in 0 until 5) c.drawRect(60f + k * 36f, top + 20f, 80f + k * 36f, top + 90f, p)
            }
            return bmp
        }
        val pre = frame(4); val after = frame(3)
        try {
            val patch = Gesture.makePatch(pre, 144, 95 + 150, Gesture.PATCH, 0.5f)!!
            val before = Gesture.countPatch(patch, pre)
            assertTrue("look-alikes before: $before", before >= 2)
            assertTrue(Gesture.countPatch(patch, after) < before)
        } finally { pre.recycle(); after.recycle() }
    }
}
