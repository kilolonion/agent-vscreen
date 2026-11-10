package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.core.VScreenGame
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.Consent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 1.2.0-beta2: hands-off autonomy, per-call scopes, redaction, the MCP args description. */
@RunWith(RobolectricTestRunner::class)
class Beta2Test {
    private fun args(cmd: String, vararg rest: String) = VScreenOptions.args(cmd, VScreenOptions.normalize(cmd, rest.toList()))

    @Test fun handsOffAsksOnlyForMoneyAndTheUsersOwnRule() {
        assertTrue(Consent.asks("pay", "auto"))
        assertTrue(Consent.asks("app", "auto"))
        assertFalse(Consent.asks("spend", "auto"))
        assertFalse(Consent.asks("irreversible", "auto"))
        assertFalse(Consent.asks("system", "auto"))
        listOf("pay", "app", "spend", "irreversible", "system").forEach { assertTrue(it, Consent.asks(it, "careful")) }
    }

    @Test fun buttonWordsAreSortedByWhatTheyDo() {
        assertEquals("pay", VScreenGame.commitKind("立即支付"))
        assertEquals("pay", VScreenGame.commitKind("提交订单"))
        assertEquals("spend", VScreenGame.commitKind("加入购物车并购买"))
        assertEquals("irreversible", VScreenGame.commitKind("删除"))
        // A "clear" × on an input box's row only empties the box.
        assertNull(VScreenGame.commitKind("清空", besideInput = true))
        assertEquals("irreversible", VScreenGame.commitKind("清空", besideInput = false))
        assertEquals("irreversible", VScreenGame.commitKind("清空聊天记录", besideInput = true))
        assertNull(VScreenGame.commitKind("搜索"))
    }

    @Test fun aLookNeedsOnlyLookAndReachingBeyondTheScreenNeedsSystem() {
        val act = CommandSpec.of("act")!!
        assertEquals(CommandSpec.Scope.LOOK, act.scopeFor(args("act")))
        assertEquals(CommandSpec.Scope.LOOK, act.scopeFor(args("act", "wait text:好 3s")))
        assertEquals(CommandSpec.Scope.OPERATE, act.scopeFor(args("act", "tap 搜索")))
        assertEquals(CommandSpec.Scope.OPERATE, act.scopeFor(args("act", "--stop", "text:到货", "--keep", "notify")))
        val stop = CommandSpec.of("stop")!!
        assertEquals(CommandSpec.Scope.OPERATE, stop.scopeFor(args("stop")))
        assertEquals(CommandSpec.Scope.SYSTEM, stop.scopeFor(args("stop", "--force")))
        val handoff = CommandSpec.of("handoff")!!
        assertEquals(CommandSpec.Scope.SYSTEM, handoff.scopeFor(args("handoff", "in")))
        assertEquals(CommandSpec.Scope.OPERATE, handoff.scopeFor(args("handoff", "out")))
    }

    @Test fun typedTextIsHiddenEverywhereInTheCommand() {
        val line = Audit.redact(listOf("act", "type 笔记testA until text:笔记testA; wait gone:笔记testA"))
        assertFalse(line, line.contains("笔记testA"))
        assertTrue(line, line.contains("<7 chars>"))
    }

    @Test fun mcpArgsSayItIsOneString() {
        I18n.choice = I18n.Lang.EN
        try {
            val d = CommandSpec.inputSchema(CommandSpec.of("act")!!).getJSONObject("properties").getJSONObject("args").getString("description")
            assertTrue(d, d.contains("ONE string"))
            assertFalse(d, d.startsWith("["))
        } finally { I18n.choice = I18n.Lang.AUTO }
    }

    @Test fun conditionWordsAreHiddenToo() {
        val line = Audit.redact(listOf("act", "tap 清空 until gone:蛋糕cake测试 2026 3s; wait text:订单已提交 or gone:加载中"))
        assertFalse(line, line.contains("蛋糕") || line.contains("订单已提交") || line.contains("加载中"))
        assertTrue(line, line.contains("gone:<"))
        assertTrue(line, line.contains("tap 清空"))
    }

    @Test fun untilAloneSaysWhereItGoes() {
        val e = runCatching { Steps.parseAll("tap 搜索; until text:结果") }.exceptionOrNull()
        assertTrue(e?.message ?: "no error", e?.message?.contains("wait text:") == true)
    }

    @Test fun busyCarriesItsOwner() {
        val r = VScreenOffloadHandler(org.robolectric.RuntimeEnvironment.getApplication()).err("BUSY", "x", data = org.json.JSONObject().put("owner", "A").put("frees_in_s", 120))
        assertEquals("A", r.data!!.getString("owner")); assertEquals(120, r.data!!.getInt("frees_in_s"))
    }
}
