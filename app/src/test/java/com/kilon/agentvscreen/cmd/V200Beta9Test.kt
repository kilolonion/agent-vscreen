package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.Policy
import com.kilon.agentvscreen.api.Policy.Act
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenGame
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.Consent
import com.kilon.agentvscreen.host.Risk
import com.kilon.agentvscreen.host.StrongConfirm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** 2.0.0-beta9: one table per agent (levels + custom rows), two confirmation strengths, ranged allows, loop-token cleanup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V200Beta9Test {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val now = AtomicLong(1_760_000_000_000L)

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
    }

    @After fun tearDown() { StrongConfirm.resetForTests(); Risk.resetForTests() }

    @Test fun presetsAreOrderedAndMoneyIsNeverAllowed() {
        val order = listOf(Tokens.LOOK, Tokens.CAREFUL, Tokens.FREE, Tokens.FREE_SYSTEM)
        // Each preset allows at least as much as the one before it, row by row.
        order.zipWithNext().forEach { (a, b) ->
            Policy.ROWS.forEach { r -> assertTrue("${r.key} $a→$b", Policy.PRESETS.getValue(b).getValue(r.key).rank >= Policy.PRESETS.getValue(a).getValue(r.key).rank) }
        }
        order.forEach { assertTrue(Policy.PRESETS.getValue(it).getValue(Policy.MONEY) != Act.ALLOW) }
        assertEquals(Act.ASK, Policy.clamp(Policy.MONEY, Act.ALLOW))
        assertEquals(Act.ASK, Policy.clamp(Policy.RATE, Act.DENY))
        assertEquals(Act.ASK, Policy.table(Tokens.FREE, mapOf(Policy.MONEY to Act.ALLOW))[Policy.MONEY])
        // A public client never gets system actions, whatever the table says.
        assertEquals(Act.DENY, Policy.table(Tokens.FREE_SYSTEM, emptyMap(), publicOnly = true)[Policy.SYSTEM])
        assertEquals("look", Policy.scopeOf(Policy.PRESETS.getValue(Tokens.LOOK)))
        assertEquals("operate", Policy.scopeOf(Policy.PRESETS.getValue(Tokens.FREE)))
        assertEquals("system", Policy.scopeOf(Policy.PRESETS.getValue(Tokens.FREE_SYSTEM)))
        // Display names (rainbow 00:52).
        assertEquals(listOf("只看", "稳妥", "省心", "全权", "自定义"), Tokens.LEVELS.map { Policy.levelTitle(it) })
    }

    @Test fun widenedListsOnlyRowsThatAllowMore() {
        val free = Policy.PRESETS.getValue(Tokens.FREE)
        assertEquals(emptyList<String>(), Policy.widened(free, Policy.PRESETS.getValue(Tokens.CAREFUL)))
        assertEquals(listOf(Policy.SYSTEM), Policy.widened(free, Policy.PRESETS.getValue(Tokens.FREE_SYSTEM)))
        assertEquals(listOf(Policy.DESTROY), Policy.widened(free, Policy.table(Tokens.FREE, mapOf(Policy.DESTROY to Act.ALLOW, Policy.CAUTION to Act.DENY))))
        val json = Policy.toJson(mapOf(Policy.DESTROY to Act.ALLOW, Policy.MONEY to Act.ALLOW))
        json.put("bogus", "allow")
        assertEquals(mapOf(Policy.DESTROY to Act.ALLOW, Policy.MONEY to Act.ASK), Policy.fromJson(json))
    }

    @Test fun setActKeepsOnlyRowsThatDifferFromThePreset() {
        val (t, _) = Tokens.createLevel("agent-x", Tokens.FREE)
        Tokens.setAct(t.id, Policy.CAUTION, Act.ASK)
        assertEquals(mapOf(Policy.CAUTION to Act.ASK), Tokens.byId(t.id)!!.custom)
        Tokens.setAct(t.id, Policy.CAUTION, Act.ALLOW) // back to the preset value: the mark goes away
        assertEquals(emptyMap<String, Act>(), Tokens.byId(t.id)!!.custom)
        Tokens.setAct(t.id, Policy.SYSTEM, Act.ALLOW)
        assertEquals("system", Tokens.byId(t.id)!!.policy.let { Policy.scopeOf(it) })
        Tokens.setLevel(t.id, Tokens.LOOK)
        assertEquals(emptyMap<String, Act>(), Tokens.byId(t.id)!!.custom)
        assertEquals(Act.DENY, Tokens.byId(t.id)!!.act(Policy.OPERATE))
        Tokens.revoke(t.id)
    }

    @Test fun migrationMapsOldScopeAndAutonomyToALevel() {
        assertEquals(Tokens.LOOK, Tokens.migrateLevel("look", "auto", false))
        assertEquals(Tokens.CAREFUL, Tokens.migrateLevel("operate", "careful", false))
        assertEquals(Tokens.CAREFUL, Tokens.migrateLevel("system", "careful", false))
        assertEquals(Tokens.FREE, Tokens.migrateLevel("operate", "auto", false))
        assertEquals(Tokens.FREE_SYSTEM, Tokens.migrateLevel("system", "auto", false))
        assertEquals(Tokens.FREE, Tokens.migrateLevel("system", "auto", true))
        // Older callers (pairing hook, scripts) pass scope names; they map to the scope's default level.
        assertEquals(Tokens.FREE, Tokens.levelOf("operate"))
        assertEquals(Tokens.LOOK, Tokens.levelOf("look"))
        assertEquals(Tokens.CAREFUL, Tokens.levelOf(Tokens.CAREFUL))
        assertEquals(null, Tokens.levelOf("bogus"))
    }

    @Test fun loopTokensPicksOnlyUnusedSuggestedBurstTokens() {
        fun tk(id: String, name: String, at: Long, used: Long = 0L, pub: Boolean = false) = Tokens.Token(id, name, "k", "operate", at, lastUsedAt = used, publicOnly = pub)
        val t0 = 1_000_000L
        val list = listOf(
            tk("a", "默认 Agent 2", t0), tk("b", "默认 Agent 3", t0 + 200), tk("c", "默认 Agent 4", t0 + 400),
            tk("d", "默认 Agent 5", t0 + 600, used = t0 + 9_000), // used: kept
            tk("e", "我的 Claude", t0 + 800), // renamed: kept
            tk("f", "默认 Agent 9", t0 + 10 * 60_000L), // alone: kept
            tk("g", "Default Agent 2", t0 + 900, pub = true), // public: kept
            tk(Tokens.MAIN, "默认 Agent 1", t0 + 100),
        )
        assertEquals(setOf("a", "b", "c"), Tokens.loopTokens(list))
    }

    @Test fun consentFollowsTheAgentsTable() {
        val (t, _) = Tokens.createLevel("agent-y", Tokens.FREE)
        val call = Call("s1", tokenId = t.id, tokenName = t.name)
        assertEquals(Act.ALLOW, Consent.actFor(Consent.CAUTION, call))
        assertEquals(Act.ASK, Consent.actFor(Consent.DESTROY, call))
        assertEquals(Act.ASK, Consent.actFor(Consent.APP, call)) // the user's app rule always asks
        // caution passes without a question.
        Call.with(call) { Consent.require(ctx, "c1", "强制结束", "x", kind = Consent.CAUTION, pkg = "com.example.a") }
        // 2.0.0-beta11: a row set to "not allowed" asks for an elevation (fingerprint), it is not run silently.
        Tokens.setAct(t.id, Policy.DESTROY, Act.DENY)
        val code = Call.with(call) { try { Consent.require(ctx, "d1", "卸载", "x", kind = Consent.DESTROY, pkg = "com.example.a"); null } catch (e: ActionError) { e.code } }
        assertEquals("NEEDS_CONFIRM", code)
        assertEquals(listOf(StrongConfirm.Range.ONCE), Consent.rangesFor(Consent.MONEY))
        assertFalse(StrongConfirm.Range.TODAY in Consent.rangesFor(Consent.DESTROY))
        Tokens.revoke(t.id)
    }

    @Test fun aTapAllowForThisRunCoversTheSameKindInTheSameApp() {
        val (t, _) = Tokens.createLevel("agent-z", Tokens.CAREFUL)
        val call = Call("s1", tokenId = t.id, tokenName = t.name)
        fun ask(key: String, pkg: String) = Call.with(call) {
            try { Consent.require(ctx, key, "删除", "x", kind = Consent.CAUTION, pkg = pkg); null } catch (e: ActionError) { e }
        }
        val e = ask("k1", "com.example.a")!!
        assertEquals("CONFIRM_PENDING", e.code)
        val id = e.data!!.getJSONObject("confirm").getString("id")
        assertTrue(StrongConfirm.approve(ctx, id, StrongConfirm.BY_TAP, range = StrongConfirm.Range.ROUND))
        // Another delete in the same app: covered; in another app: asks.
        assertEquals(null, ask("k2", "com.example.a"))
        assertEquals("CONFIRM_PENDING", ask("k3", "com.example.b")?.code)
        assertEquals(1, StrongConfirm.activeGrants(now.get()).count { it.token == t.id })
        // Quiet for a whole turn length: the run is over.
        now.addAndGet(11 * 60_000L)
        assertEquals("CONFIRM_PENDING", ask("k4", "com.example.a")?.code)
        Tokens.revoke(t.id)
    }

    @Test fun systemDialogWordsMakeDestroyGenerically() {
        assertEquals(Consent.DESTROY, VScreenGame.consentKind("清除数据"))
        assertEquals(Consent.DESTROY, VScreenGame.consentKind("卸载"))
        assertEquals(Consent.MONEY, VScreenGame.consentKind("立即支付"))
    }
}
