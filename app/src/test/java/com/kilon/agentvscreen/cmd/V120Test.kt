package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.AgentGuide
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.ApiServer
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.host.Audit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** 1.2 contracts: the command table, the code table, typed results, tokens and signatures, the audit log, the guide. */
@RunWith(RobolectricTestRunner::class)
class V120Test {
    private val ctx get() = RuntimeEnvironment.getApplication()

    // ── the command table ───────────────────────────────────────────────

    @Test fun mcpSchemasAndArgvComeFromTheTable() {
        val act = CommandSpec.of("act")!!
        val schema = CommandSpec.inputSchema(act).getJSONObject("properties")
        assertEquals("boolean", schema.getJSONObject("fast").getString("type"))
        assertEquals("string", schema.getJSONObject("stop").getString("type"))
        assertFalse("deprecated options are not offered", schema.has("confirmed"))
        val argv = CommandSpec.argvOf(act, "tap 搜索; type \"a b\"", JSONObject().put("fast", true).put("stop", "text:完成").put("bare", false))
        assertEquals(listOf("act", "tap 搜索; type \"a b\"", "--stop", "text:完成", "--fast"), argv)
        assertEquals(listOf("note", "before", "tap 1 1"), CommandSpec.argvOf(CommandSpec.of("note")!!, "before \"tap 1 1\"", null))
    }

    @Test fun everyCommandDeclaresScopeAndTheGateEnforcesIt() {
        assertEquals(CommandSpec.Scope.LOOK, CommandSpec.of("status")!!.scope)
        assertEquals(CommandSpec.Scope.SYSTEM, CommandSpec.of("fix")!!.scope)
        assertFalse(CommandSpec.of("apps")!!.screen)
        val h = VScreenOffloadHandler(ctx)
        val lookOnly = Call("s", "t1", "reader", setOf("look"))
        val r = Call.with(lookOnly) { h.handle(NativeOffloadRequest(listOf("vscreen", "act", "tap 1 1"), "s")) }
        assertEquals("SCOPE_DENIED", r.code); assertEquals(1, r.exitCode)
        assertEquals("ask_user", r.data!!.getString("next"))
    }

    @Test fun theReferenceDocIsGeneratedFromTheTables() {
        // docs/REFERENCE.md is the command table and the code table, written out. When it is missing this writes it;
        // otherwise it must match (delete the file and run the tests to regenerate after changing a table).
        val f = java.io.File("../docs/REFERENCE.md")
        if (f.parentFile?.isDirectory != true) return
        fun part(): String = "## " + com.kilon.agentvscreen.tr("命令", "Commands") + "\n\n" + CommandSpec.markdown() + "## " + com.kilon.agentvscreen.tr("结果代码", "Result codes") + "\n\n" + Codes.markdown() + "\n"
        I18n.choice = I18n.Lang.ZH; val zh = part()
        I18n.choice = I18n.Lang.EN; val en = part()
        I18n.choice = I18n.Lang.ZH
        val want = "# Agent-VScreen 命令与代码参考 / Command and code reference\n\n> 由代码里的命令表和代码表生成，不要手改。Generated from the command and code tables in the source; do not edit by hand.\n\n# 中文\n\n" + zh + "\n# English\n\n" + en
        if (!f.isFile) f.writeText(want)
        assertEquals("docs/REFERENCE.md is out of date: delete it and run the tests", want, f.readText())
    }

    // ── codes and typed results ─────────────────────────────────────────

    @Test fun everyCodeSaysWhatToDoNext() {
        assertEquals(Codes.all.size, Codes.all.map { it.name }.toSet().size)
        for (c in Codes.all) { assertTrue(c.name, c.text.isNotBlank()); assertNotNull(Codes.of(c.name)) }
        assertEquals("ask_user", Codes.next("USER_STOPPED")); assertEquals("look", Codes.next("STALE_ELEMENT"))
        assertEquals("fix_command", Codes.next("INVALID_STEP")); assertEquals("retry_later", Codes.next("QUEUE_TIMEOUT"))
        assertTrue(Codes.help().contains("AMBIGUOUS"))
    }

    @Test fun everyCodeTheSourceUsesIsInTheTable() {
        val root = java.io.File("src/main/java/com/kilon/agentvscreen").takeIf { it.isDirectory } ?: return
        val re = Regex("""(?:ActionError|StepRefused|err|failed\(r,|notes\.add|Resp\.err\(\d+,|HttpError\(\d+,)\(?\s*"([A-Z][A-Z_]{2,})"""")
        val used = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f -> re.findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        val missing = used.filter { Codes.of(it) == null }
        assertTrue("codes used but not in Codes: $missing", missing.isEmpty())
    }

    @Test fun notesDecideTheOutcomeCode() {
        val n = Notes()
        n += "只是一句话"; n.add("NO_CHANGE", "没变"); assertNull(n.firstBlocking())
        n.add("STALE_ELEMENT", "不在了"); n.add("NOT_FOUND", "x")
        assertEquals("STALE_ELEMENT", n.firstBlocking())
        n.add("STALE_ELEMENT", "不在了"); assertEquals(4, n.list.size)
        assertEquals("look", n.list[2].json().getString("next"))
    }

    @Test fun stepOutIsStructured() {
        val so = StepOut("2", "tap 登录 until text:首页", "missed", "…").also { it.code = "STEP_MISSED"; it.waitedFor = "text:首页"; it.waitMet = false; it.waitMs = 5000; it.point = 10 to 20; it.changed = false }
        val j = so.json()
        assertEquals("missed", j.getString("status")); assertEquals("STEP_MISSED", j.getString("code"))
        assertFalse(j.getBoolean("met")); assertEquals(10, j.getInt("x")); assertFalse(j.getBoolean("changed"))
    }

    @Test fun steadyMotionIsSettledButArrivalIsNot() {
        assertTrue(steady(listOf(0.012, 0.011, 0.013, 0.012, 0.012, 0.011)))      // a spinner
        assertFalse(steady(listOf(0.0, 0.0, 0.31, 0.02, 0.0, 0.0)))                // a page arriving
        assertFalse(steady(listOf(0.2, 0.21, 0.2, 0.19, 0.2)))                     // a video: even but large
        assertFalse(steady(listOf(0.01, 0.01)))                                    // too few looks
    }

    // ── tokens, signatures, audit ───────────────────────────────────────

    @Test fun tokensHaveScopesAndOnlyAFingerprintIsKept() {
        ApiConfig.init(ctx); Tokens.init(ctx)
        val (t, text) = Tokens.create("电脑助手", "look")
        assertTrue(text.startsWith("avs_${t.id}_"))
        assertEquals(setOf("look"), t.scopes)
        assertFalse("the secret is not stored", Tokens.list.value.any { text.substringAfterLast('_') in it.json().toString() })
        assertEquals(t.id, Tokens.byBearer(text)?.id)
        assertNull(Tokens.byBearer(text.dropLast(1) + "x"))
        assertEquals(Tokens.MAIN, Tokens.byBearer(ApiConfig.token)?.id)
        assertEquals(setOf("look", "operate", "system"), Tokens.byId(Tokens.MAIN)!!.scopes)
        Tokens.trust(t.id, 30); assertTrue(Tokens.byId(t.id)!!.trusted)
        Tokens.trust(t.id, 0); assertFalse(Tokens.byId(t.id)!!.trusted)
        Tokens.revoke(t.id); assertNull(Tokens.byBearer(text))
        Tokens.revoke(Tokens.MAIN); assertNotNull(Tokens.byId(Tokens.MAIN))
    }

    @Test fun signatureCoversEverythingThatMatters() {
        val k = Tokens.sha256("secret")
        val body = "616374\n".toByteArray()
        val s = ApiServer.signature(k, "1700000000", "abcdef123456", "POST", "/v2/run?session=a", body)
        assertEquals(64, s.length)
        assertEquals(s, ApiServer.signature(k, "1700000000", "abcdef123456", "post", "/v2/run?session=a", body))
        for (other in listOf(
            ApiServer.signature(Tokens.sha256("other"), "1700000000", "abcdef123456", "POST", "/v2/run?session=a", body),
            ApiServer.signature(k, "1700000001", "abcdef123456", "POST", "/v2/run?session=a", body),
            ApiServer.signature(k, "1700000000", "abcdef123457", "POST", "/v2/run?session=a", body),
            ApiServer.signature(k, "1700000000", "abcdef123456", "POST", "/v2/run?session=b", body),
            ApiServer.signature(k, "1700000000", "abcdef123456", "POST", "/v2/run?session=a", "616375\n".toByteArray()))) assertFalse(s == other)
        // What the shell client computes: sha256 of the lines joined by \n, K first and last.
        assertEquals(Tokens.sha256("$k\n1700000000\nabcdef123456\nPOST\n/v2/run?session=a\n${Tokens.sha256(body)}\n$k"), s)
        assertEquals(Tokens.sha256("$k\nabcdef123456\nresp\n${Tokens.sha256("ok".toByteArray())}\n$k"), ApiServer.proof(k, "abcdef123456", "ok".toByteArray()))
        assertEquals("act", ApiServer.unhex("616374")); assertEquals("你好", ApiServer.unhex("e4bda0e5a5bd")); assertNull(ApiServer.unhex("zz"))
    }

    @Test fun auditNeverKeepsTypedText() {
        Audit.init(ctx); Audit.clear()
        val line = Audit.redact(listOf("act", "tap 密码; type hunter2-secret; key enter"))
        assertFalse(line, line.contains("hunter2")); assertTrue(line, line.contains("type <14 chars>")); assertTrue(line.contains("key enter"))
        assertFalse(Audit.redact(listOf("act", "type+ 我的银行卡号 until text:完成")).contains("银行卡"))
        Audit.command("电脑助手", "laptop", "s", listOf("act", "type topsecret"), "OK", 0, 12, "rid1")
        val last = Audit.tail(5).last()
        assertEquals("电脑助手", last.getString("token")); assertEquals("OK", last.getString("code"))
        assertFalse(last.toString().contains("topsecret"))
    }

    // ── the scaffold ─────────────────────────────────────────────────────

    @Test fun theGuideTeachesTheLoopAndOnlyRealThings() {
        ApiConfig.init(ctx)
        for (lang in listOf(I18n.Lang.ZH, I18n.Lang.EN)) {
            I18n.choice = lang
            val g = AgentGuide.guide()
            assertFalse("no install line in the guide", g.contains("/cli | "))
            assertFalse("the removed flag is not taught", g.contains("--confirmed"))
            // Every code the guide names exists, and every option it names is a real option.
            Regex("\\b[A-Z][A-Z_]{4,}\\b").findAll(g).map { it.value }.filter { it != "HTTP" }.forEach { assertNotNull("guide names unknown code $it", Codes.of(it)) }
            val real = CommandSpec.commands.flatMap { c -> c.allOpts.map { it.name } }.toSet()
            Regex("--([a-z][a-z-]+)").findAll(g).map { it.groupValues[1] }.forEach { assertTrue("guide names unknown option --$it", it in real) }
            // Every command it shows as `vscreen <word>` exists.
            Regex("`vscreen ([a-z]+)").findAll(g).map { it.groupValues[1] }.forEach { assertTrue("guide names unknown command $it", CommandSpec.of(it) != null || it in CommandSpec.TRANSPORT_WORDS) }
            assertTrue(AgentGuide.skill().startsWith("---\nname: agent-vscreen"))
            assertTrue(AgentGuide.memory().length < 1500)
        }
        I18n.choice = I18n.Lang.ZH
        assertTrue(AgentGuide.guide().contains("看 → 做 → 核对"))
        val other = AgentGuide.onboard("http://100.64.0.9:18765", "avs_abc123_SECRETSECRETSECRET1234")
        assertTrue(other.contains("avs_abc123_SECRETSECRETSECRET1234") && other.contains("VSCREEN_URL=http://100.64.0.9:18765"))
        assertFalse("another token's message never carries the main token", other.contains(ApiConfig.token))
    }

    @Test fun perRequestLanguageDoesNotLeak() {
        I18n.choice = I18n.Lang.ZH
        val en = I18n.with("en") { Blocks.error }
        assertEquals("[Error]", en); assertEquals("【错误】", Blocks.error)
    }
}
