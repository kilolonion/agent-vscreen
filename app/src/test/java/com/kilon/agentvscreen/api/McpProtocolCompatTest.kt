package com.kilon.agentvscreen.api

import android.app.Application
import android.content.Context
import com.kilon.agentvscreen.cmd.Codes
import com.kilon.agentvscreen.cmd.CommandSpec
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.Risk
import com.kilon.agentvscreen.host.StrongConfirm
import com.kilon.agentvscreen.host.ThermalGuard
import com.kilon.agentvscreen.host.UserAsk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Tests for P2-1 dual-stack MCP (`2026-07-28` stateless + `io.modelcontextprotocol/tasks` + legacy `initialize`):
 * - `server/discover` returns protocolVersions, capabilities.extensions["io.modelcontextprotocol/tasks"], ttlMs, cacheScope, no Mcp-Session-Id
 * - `tools/list` deterministic ordering, JSON Schema 2020-12 (`$schema`, `required`, repeatable `--on` `oneOf`), full annotations
 * - SEP-2243 `Mcp-Method` & `Mcp-Name` mismatch returns HTTP 400 + JSON-RPC `-32020`
 * - Unsupported modern revision returns HTTP 400 + JSON-RPC `-32022` with `data: {supported, requested}`
 * - `io.modelcontextprotocol/tasks` opt-in gate, `cfm_` / `ask_` always `working` (never `input_required`),
 *   token isolation, `tasks/update` rejected `-32601` (never approves StrongConfirm)
 * - Chunked SSE framing via `HttpCore.writeResponse`
 * - Legacy `initialize` (2024-11-05, 2025-03-26, 2025-06-18), 64-char requestId cap, and `THERMAL_STOP`
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class McpProtocolCompatTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var tempDir: File
    private lateinit var server: ApiServer

    @Before
    fun setUp() {
        tempDir = File(ctx.filesDir, "p21-mcp-test-${System.nanoTime()}").apply { mkdirs() }
        ApiConfig.init(ctx)
        Tokens.init(ctx)
        Audit.init(ctx)
        McpTasks.resetForTest()
        StrongConfirm.resetForTests(File(tempDir, "approvals.json"))
        StrongConfirm.postNotification = false
        ctx.getSharedPreferences("thermal_safety_v1", Context.MODE_PRIVATE).edit().putBoolean("locked", false).commit()
        ThermalGuard.init(ctx)
        server = ApiServer(ctx)
    }

    @After
    fun tearDown() {
        McpTasks.resetForTest()
        ctx.getSharedPreferences("thermal_safety_v1", Context.MODE_PRIVATE).edit().putBoolean("locked", false).commit()
        ThermalGuard.init(ctx)
        StrongConfirm.resetForTests()
        tempDir.deleteRecursively()
    }

    private fun mcpReq(
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
        tok: Tokens.Token = Tokens.byId(Tokens.MAIN)!!,
    ): ApiServer.Req {
        val headers = LinkedHashMap<String, String>()
        headers["host"] = "127.0.0.1"
        headers["content-type"] = "application/json"
        extraHeaders.forEach { (k, v) -> headers[k.lowercase()] = v }
        val r = ApiServer.Req("POST", "/mcp", emptyMap(), headers, body.toByteArray(Charsets.UTF_8))
        r.auth = ApiServer.Auth(tok, signed = false, nonce = null)
        return r
    }

    @Test
    fun modern2026DiscoverAndToolsListAreStatelessAndCached() {
        val discoverBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "server/discover")
            .put("params", JSONObject())
            .toString()
        val discResp = Mcp.handle(
            server,
            mcpReq(discoverBody, mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "server/discover")),
        )
        assertEquals(200, discResp.status)
        assertNull("Modern 2026-07-28 must never emit Mcp-Session-Id", discResp.headers["Mcp-Session-Id"])
        val discRes = JSONObject(String(discResp.body, Charsets.UTF_8)).getJSONObject("result")
        assertEquals("complete", discRes.getString("resultType"))
        assertEquals(McpProtocol.CACHE_TTL_MS, discRes.getLong("ttlMs"))
        assertEquals("public", discRes.getString("cacheScope"))
        val versions = discRes.getJSONArray("protocolVersions")
        assertEquals("2026-07-28", versions.getString(0))
        assertTrue(discRes.getJSONObject("capabilities").getJSONObject("extensions").has(McpProtocol.TASKS_EXTENSION))

        val listBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 2)
            .put("method", "tools/list")
            .put("params", JSONObject())
            .toString()
        val listResp = Mcp.handle(
            server,
            mcpReq(listBody, mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "tools/list")),
        )
        assertEquals(200, listResp.status)
        assertNull(listResp.headers["Mcp-Session-Id"])
        val listRes = JSONObject(String(listResp.body, Charsets.UTF_8)).getJSONObject("result")
        assertEquals("complete", listRes.getString("resultType"))
        assertEquals(McpProtocol.CACHE_TTL_MS, listRes.getLong("ttlMs"))
        assertEquals("public", listRes.getString("cacheScope"))

        val tools = listRes.getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        val expectedNames = listOf("vscreen") + CommandSpec.commands.filterNot { it.hidden }.map { "vscreen_${it.name}" }
        assertEquals("tools/list order must deterministically follow CommandSpec.commands", expectedNames, names)

        // Verify JSON Schema 2020-12 + repeatable --on oneOf + full annotations on vscreen_act
        val actTool = (0 until tools.length()).map { tools.getJSONObject(it) }.first { it.getString("name") == "vscreen_act" }
        val schema = actTool.getJSONObject("inputSchema")
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.getString("\$schema"))
        val onProp = schema.getJSONObject("properties").getJSONObject("on")
        assertTrue("Repeatable --on must declare oneOf", onProp.has("oneOf"))
        assertEquals(2, onProp.getJSONArray("oneOf").length())
        val ann = actTool.getJSONObject("annotations")
        assertFalse(ann.getBoolean("readOnlyHint"))
        assertTrue(ann.getBoolean("destructiveHint"))
        assertTrue(ann.getBoolean("openWorldHint"))
    }

    @Test
    fun modernHeaderMismatchReturns32020AndUnsupportedModernVersionReturns32022() {
        val listBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 10)
            .put("method", "tools/list")
            .put("params", JSONObject())
            .toString()

        // Mcp-Method mismatch -> HTTP 400 + JSON-RPC -32020
        val mismatchMethod = Mcp.handle(
            server,
            mcpReq(listBody, mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "tools/call")),
        )
        assertEquals(400, mismatchMethod.status)
        val err1 = JSONObject(String(mismatchMethod.body, Charsets.UTF_8)).getJSONObject("error")
        assertEquals(McpProtocol.ERR_HEADER_MISMATCH, err1.getInt("code"))

        // Mcp-Name mismatch -> HTTP 400 + JSON-RPC -32020
        val callBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 11)
            .put("method", "tools/call")
            .put("params", JSONObject().put("name", "vscreen").put("arguments", JSONObject().put("command", "help codes")))
            .toString()
        val mismatchName = Mcp.handle(
            server,
            mcpReq(
                callBody,
                mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "tools/call", "Mcp-Name" to "vscreen_act"),
            ),
        )
        assertEquals(400, mismatchName.status)
        val err2 = JSONObject(String(mismatchName.body, Charsets.UTF_8)).getJSONObject("error")
        assertEquals(McpProtocol.ERR_HEADER_MISMATCH, err2.getInt("code"))

        // Unsupported modern revision -> HTTP 400 + JSON-RPC -32022 with data.supported
        val badVer = Mcp.handle(
            server,
            mcpReq(listBody, mapOf("MCP-Protocol-Version" to "2026-12-31")),
        )
        assertEquals(400, badVer.status)
        val err3 = JSONObject(String(badVer.body, Charsets.UTF_8)).getJSONObject("error")
        assertEquals(McpProtocol.ERR_UNSUPPORTED_PROTOCOL, err3.getInt("code"))
        assertEquals("2026-12-31", err3.getJSONObject("data").getString("requested"))
        assertEquals("2026-07-28", err3.getJSONObject("data").getJSONArray("supported").getString(0))
    }

    @Test
    fun tasksExtensionBridgesConfirmAndAskWithStrictTokenIsolationAndNoTasksUpdateBypass() {
        val mainTok = Tokens.byId(Tokens.MAIN)!!
        val otherTok = Tokens.create("other-agent", Tokens.FREE).first
        val now = System.currentTimeMillis()
        val (cfmReq, _) = StrongConfirm.getOrCreateRequest(
            risk = Risk.HIGH_RISK,
            key = "HIGH_RISK:${mainTok.id}:pay-test",
            tokenId = mainTok.id,
            tokenName = mainTok.name,
            tokenScope = "system",
            client = Risk.CLIENT_LOCAL,
            commandName = "pay",
            detail = "test",
            validMs = 60_000L,
            reusable = false,
            allowHold = true,
            nowMs = now,
        )
        val pendingRes = NativeOffloadResult(
            exitCode = 1,
            output = "pending\n",
            code = "CONFIRM_PENDING",
            data = JSONObject()
                .put("confirm", JSONObject().put("id", cfmReq.id))
                .put("user_action", JSONObject().put("tell_user", "Confirm on phone")),
        )
        val wrapped = McpTasks.maybeWrap(mcpReq("{}", tok = mainTok), listOf("help", "codes"), "s1", pendingRes)
        assertNotNull(wrapped)
        assertEquals("task", wrapped!!.getString("resultType"))
        val taskObj = wrapped.getJSONObject("task")
        val taskId = taskObj.getString("taskId")
        assertEquals("cfm_${cfmReq.id}", taskId)
        assertEquals("working", taskObj.getString("status"))
        assertEquals("Confirm on phone", taskObj.getString("statusMessage"))

        // tasks/get by owner returns working (never input_required)
        val getBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 20)
            .put("method", "tasks/get")
            .put("params", JSONObject().put("taskId", taskId))
            .toString()
        val getResp = Mcp.handle(server, mcpReq(getBody, mapOf("MCP-Protocol-Version" to "2026-07-28"), tok = mainTok))
        assertEquals(200, getResp.status)
        val getRes = JSONObject(String(getResp.body, Charsets.UTF_8)).getJSONObject("result")
        assertEquals("working", getRes.getString("status"))

        // Cross-token tasks/get MUST fail (strict (tokenId, taskId) isolation)
        val otherGet = Mcp.handle(server, mcpReq(getBody, mapOf("MCP-Protocol-Version" to "2026-07-28"), tok = otherTok))
        assertTrue(JSONObject(String(otherGet.body, Charsets.UTF_8)).has("error"))

        // tasks/update MUST be rejected (-32601) and NEVER approve StrongConfirm
        val updateBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 21)
            .put("method", "tasks/update")
            .put("params", JSONObject().put("taskId", taskId).put("approved", true))
            .toString()
        val updResp = Mcp.handle(server, mcpReq(updateBody, mapOf("MCP-Protocol-Version" to "2026-07-28"), tok = mainTok))
        val updErr = JSONObject(String(updResp.body, Charsets.UTF_8)).getJSONObject("error")
        assertEquals(-32601, updErr.getInt("code"))
        assertNull("StrongConfirm must remain unapproved", StrongConfirm.byId(cfmReq.id)?.answer)

        // UserAsk ask_ bridging also stays working (never input_required)
        val ask = UserAsk.create(ctx, UserAsk.Kind.TEXT, "ask-key-1", "Pick", "Detail")
        val askRes = NativeOffloadResult(
            exitCode = 1,
            output = "pending\n",
            code = "ASK_PENDING",
            data = JSONObject().put("ask", JSONObject().put("id", ask.id)),
        )
        val askWrapped = McpTasks.maybeWrap(mcpReq("{}", tok = mainTok), listOf("help", "codes"), "s1", askRes)
        assertEquals("working", askWrapped!!.getJSONObject("task").getString("status"))
        assertEquals("ask_${ask.id}", askWrapped.getJSONObject("task").getString("taskId"))
    }

    @Test
    fun chunkedSseResponseWritesValidChunkFramingAndTerminator() {
        val out = ByteArrayOutputStream()
        val resp = ApiServer.Resp.sse { em ->
            em.send("message", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}")
        }
        HttpCore.writeResponse(out, resp, "2.0.1-beta1-zh")
        val wire = out.toString("UTF-8")
        assertTrue("SSE must use Transfer-Encoding: chunked", wire.contains("Transfer-Encoding: chunked\r\n"))
        assertTrue("SSE must end with zero-length chunk terminator", wire.endsWith("0\r\n\r\n"))
        assertTrue("SSE payload present", wire.contains("event: message\ndata: "))
    }

    @Test
    fun legacyMcpRevisionsRemainUnaffected() {
        for (rev in listOf("2024-11-05", "2025-03-26", "2025-06-18")) {
            val initBody = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "initialize")
                .put("params", JSONObject().put("protocolVersion", rev))
                .toString()
            val initResp = Mcp.handle(server, mcpReq(initBody, mapOf("MCP-Protocol-Version" to rev)))
            assertEquals("initialize failed for $rev", 200, initResp.status)
            val initJson = JSONObject(String(initResp.body, Charsets.UTF_8))
            assertEquals(rev, initJson.getJSONObject("result").getString("protocolVersion"))
            assertNotNull(initResp.headers["Mcp-Session-Id"])
        }

        val listBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 2)
            .put("method", "tools/list")
            .put("params", JSONObject())
            .toString()
        val listResp = Mcp.handle(server, mcpReq(listBody, mapOf("MCP-Protocol-Version" to "2025-06-18")))
        assertEquals(200, listResp.status)
        val tools = JSONObject(String(listResp.body, Charsets.UTF_8)).getJSONObject("result").getJSONArray("tools")
        assertTrue(tools.length() > 1)

        val longId = "r".repeat(64) + "_ignored_tail_123456"
        val callBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 3)
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", "vscreen")
                    .put("arguments", JSONObject().put("command", "help codes"))
                    .put("_meta", JSONObject().put("requestId", longId)),
            )
            .toString()
        val callResp1 = Mcp.handle(server, mcpReq(callBody, mapOf("MCP-Protocol-Version" to "2025-06-18")))
        assertEquals(200, callResp1.status)
        val structured1 = JSONObject(String(callResp1.body, Charsets.UTF_8))
            .getJSONObject("result")
            .getJSONObject("structuredContent")
        assertTrue(structured1.getBoolean("ok"))
        assertFalse(structured1.optBoolean("replayed", false))

        val callBodyPrefix = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 4)
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", "vscreen")
                    .put("arguments", JSONObject().put("command", "help codes"))
                    .put("_meta", JSONObject().put("requestId", longId.take(64))),
            )
            .toString()
        val callResp2 = Mcp.handle(server, mcpReq(callBodyPrefix, mapOf("MCP-Protocol-Version" to "2025-06-18")))
        assertEquals(200, callResp2.status)
        val structured2 = JSONObject(String(callResp2.body, Charsets.UTF_8))
            .getJSONObject("result")
            .getJSONObject("structuredContent")
        assertTrue("Expected replay when 64-char prefix matches", structured2.optBoolean("replayed", false))
    }

    @Test
    fun runForWhenThermalLockedReturnsExactThermalStopCodeAndReasonOutput() {
        ctx.getSharedPreferences("thermal_safety_v1", Context.MODE_PRIVATE).edit().putBoolean("locked", true).commit()
        ThermalGuard.init(ctx)
        assertTrue(ThermalGuard.locked)

        val req = mcpReq("{}")
        val (res, replayed) = server.runFor(req, listOf("status"), "s1", null)
        assertFalse(replayed)
        assertEquals(1, res.exitCode)
        assertEquals("THERMAL_STOP", res.codeOrDefault)
        assertEquals("ask_user", Codes.next(res.codeOrDefault))
        assertTrue("Expected thermal reason in output, got: ${res.output}", res.output.contains(ThermalGuard.reason()))
    }
}
