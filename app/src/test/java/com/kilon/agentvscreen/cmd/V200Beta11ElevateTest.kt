package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.Policy
import com.kilon.agentvscreen.api.Policy.Act
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.Consent
import com.kilon.agentvscreen.host.Risk
import com.kilon.agentvscreen.host.StrongConfirm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

/** 2.0.0-beta11: agents ask for temporary or permanent elevation; one card, one effective-permission answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V200Beta11ElevateTest {
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
        com.kilon.agentvscreen.host.Permissions.resetForTests()
    }

    @After fun tearDown() { StrongConfirm.resetForTests(); Risk.resetForTests() }

    private fun elevate(call: Call, vararg a: String): Pair<String?, String?> = Call.with(call) {
        try { null to handler.elevateCmd(OffloadArgs(a.toList())).output } catch (e: ActionError) { e.code to e.data?.optJSONObject("confirm")?.optString("id") }
    }

    @Test fun temporaryRowElevationEndsWithTheTurnAndLeavesTheTableAlone() {
        val (t, _) = Tokens.createLevel("agent-e", Tokens.FREE)
        StrongConfirm.grantAgent(t.id)
        val call = Call("s1", tokenId = t.id, tokenName = t.name)
        val (code, id) = elevate(call, "destroy", "--why", "卸载你点名的 3 个应用")
        assertEquals("NEEDS_CONFIRM", code)
        val req = StrongConfirm.pending.value.single { it.id == id }
        assertEquals(listOf(StrongConfirm.Range.ROUND, StrongConfirm.Range.TODAY, StrongConfirm.Range.ALWAYS), req.ranges)
        assertEquals("卸载你点名的 3 个应用", req.detail)
        assertTrue(StrongConfirm.approve(ctx, id!!, StrongConfirm.BY_BIOMETRIC, range = StrongConfirm.Range.ROUND))
        val tok = Tokens.byId(t.id)!!
        assertEquals(Act.ASK, tok.act(Policy.DESTROY)) // the table itself is unchanged
        assertEquals(Act.ALLOW, StrongConfirm.effectiveAct(tok, Policy.DESTROY))
        assertEquals(Act.ALLOW, Consent.actFor(Consent.DESTROY, call))
        now.addAndGet(11 * 60_000L)
        assertEquals(Act.ASK, StrongConfirm.effectiveAct(Tokens.byId(t.id)!!, Policy.DESTROY))
        Tokens.revoke(t.id)
    }

    @Test fun permanentElevationWritesTheTable() {
        val (t, _) = Tokens.createLevel("agent-p", Tokens.CAREFUL)
        StrongConfirm.grantAgent(t.id)
        val call = Call("s1", tokenId = t.id, tokenName = t.name)
        val (_, id) = elevate(call, "caution", "--why", "清理缓存", "--for", "always")
        val req = StrongConfirm.pending.value.single { it.id == id }
        assertEquals(StrongConfirm.Range.ALWAYS, req.ranges.first())
        assertTrue(StrongConfirm.approve(ctx, id!!, StrongConfirm.BY_BIOMETRIC, range = StrongConfirm.Range.ALWAYS))
        assertEquals(Act.ALLOW, Tokens.byId(t.id)!!.act(Policy.CAUTION))
        Tokens.revoke(t.id)
    }

    @Test fun levelElevationLiftsEveryRowOfThatPresetForToday() {
        val (t, _) = Tokens.createLevel("agent-l", Tokens.LOOK)
        StrongConfirm.grantAgent(t.id)
        val call = Call("s1", tokenId = t.id, tokenName = t.name)
        val (_, id) = elevate(call, "free", "--why", "帮你整理相册")
        assertTrue(StrongConfirm.approve(ctx, id!!, StrongConfirm.BY_BIOMETRIC, range = StrongConfirm.Range.TODAY))
        val tok = Tokens.byId(t.id)!!
        assertEquals(Tokens.LOOK, tok.level)
        val free = Policy.table(Tokens.FREE, emptyMap())
        free.forEach { (k, a) -> assertEquals(k, a, StrongConfirm.effectiveAct(tok, k)) }
        Tokens.revoke(t.id)
    }

    @Test fun paymentsAndMissingReasonAreRefusedAndCoveredIsSaid() {
        val (t, _) = Tokens.createLevel("agent-m", Tokens.FREE)
        val call = Call("s1", tokenId = t.id, tokenName = t.name)
        assertEquals("SCOPE_DENIED", elevate(call, "money", "--why", "x").first)
        assertEquals("INVALID_ARGS", elevate(call, "destroy").first)
        assertEquals(null, elevate(call, "operate", "--why", "x").first) // already allowed: nothing asked
        assertTrue(StrongConfirm.pending.value.isEmpty())
        Tokens.revoke(t.id)
    }

    @Test fun permissionsAreReadFromWhatIsEnforcedAndChangesAreAnnounced() {
        val P = com.kilon.agentvscreen.host.Permissions
        val (t, _) = Tokens.createLevel("agent-q", Tokens.FREE)
        StrongConfirm.grantAgent(t.id)
        val call = Call("s1", tokenId = t.id, tokenName = t.name)
        val o = P.json(call)
        val rows = (0 until o.getJSONArray("classes").length()).map { o.getJSONArray("classes").getJSONObject(it) }.associateBy { it.getString("class") }
        val tok = Tokens.byId(t.id)!!
        Policy.ROWS.forEach { r -> assertEquals(StrongConfirm.effectiveAct(tok, r.key).key, rows.getValue(r.key).getString("handling")) }
        assertTrue(P.text(call).contains("不可撤销操作"))
        assertEquals(null, P.changeNotice(call)) // first reply only sets the baseline
        assertEquals(null, P.changeNotice(call))
        Tokens.setAct(t.id, Policy.CAUTION, Act.DENY)
        val note = P.changeNotice(call)!!
        assertTrue(note, note.contains("敏感操作") && note.contains("不允许"))
        assertEquals(null, P.changeNotice(call))
        // A temporary allow shows as raised, and reading does not extend a "this turn" allow.
        val (_, id) = elevate(call, "caution", "--why", "x")
        StrongConfirm.approve(ctx, id!!, StrongConfirm.BY_BIOMETRIC, range = StrongConfirm.Range.ROUND)
        val raised = P.json(call).getJSONArray("classes").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("class") == Policy.CAUTION }
        assertEquals("allow", raised.getString("handling")); assertTrue(raised.has("raised_by"))
        now.addAndGet(9 * 60_000L); P.json(call); now.addAndGet(2 * 60_000L)
        assertEquals(Act.DENY, StrongConfirm.effectiveAct(Tokens.byId(t.id)!!, Policy.CAUTION))
        Tokens.revoke(t.id)
    }
}
