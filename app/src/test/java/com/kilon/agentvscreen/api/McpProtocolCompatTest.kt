package com.kilon.agentvscreen.api

import android.app.Application
import android.content.Context
import com.kilon.agentvscreen.cmd.Codes
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.StrongConfirm
import com.kilon.agentvscreen.host.ThermalGuard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for P1-5:
 * - MCP 2026-07-28 HTTP 400 fallback shim (no JSON-RPC -32022) across all 4 modern triggers (single & batch)
 * - Legacy MCP revisions (2024-11-05, 2025-03-26, 2025-06-18) remain unaffected
 * - MCP requestId capped to 64 characters
 * - ApiServer.runFor returns proper code == "THERMAL_STOP" and non-empty output when ThermalGuard is locked
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class McpProtocolCompatTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var tempDir: File
    private lateinit var server: ApiServer

    @Before
    fun setUp() {
        tempDir = File(ctx.filesDir, "p15-test-${System.nanoTime()}").apply { mkdirs() }
        ApiConfig.init(ctx)
        Tokens.init(ctx)
        Audit.init(ctx)
        StrongConfirm.resetForTests(File(tempDir, "approvals.json"))
        StrongConfirm.postNotification = false
        ctx.getSharedPreferences("thermal_safety_v1", Context.MODE_PRIVATE).edit().putBoolean("locked", false).commit()
        ThermalGuard.init(ctx)
        server = ApiServer(ctx)
    }

    @After
    fun tearDown() {
        ctx.getSharedPreferences("thermal_safety_v1", Context.MODE_PRIVATE).edit().putBoolean("locked", false).commit()
        ThermalGuard.init(ctx)
        StrongConfirm.resetForTests()
        tempDir.deleteRecursively()
    }

    private fun mcpReq(body: String, extraHeaders: Map<String, String> = emptyMap()): ApiServer.Req {
        val headers = LinkedHashMap<String, String>()
        headers["host"] = "127.0.0.1"
        headers["content-type"] = "application/json"
        extraHeaders.forEach { (k, v) -> headers[k.lowercase()] = v }
        val r = ApiServer.Req("POST", "/mcp", emptyMap(), headers, body.toByteArray(Charsets.UTF_8))
        val mainTok = Tokens.byId(Tokens.MAIN)!!
        r.auth = ApiServer.Auth(mainTok, signed = false, nonce = null)
        return r
    }

    private fun assertModern400Fallback(resp: ApiServer.Resp) {
        assertEquals(400, resp.status)
        val text = String(resp.body, Charsets.UTF_8)
        assertFalse("Fallback 400 must never include JSON-RPC -32022: $text", text.contains("-32022"))
        val obj = JSONObject(text)
        assertFalse("Fallback 400 must not be a JSON-RPC envelope: $text", obj.has("jsonrpc"))
        assertEquals("UNSUPPORTED_MCP_PROTOCOL", obj.optString("code"))
        assertTrue(obj.optString("error").contains("initialize"))
    }

    @Test
    fun modern2026SingleRequestsReturnHttp400Without32022() {
        val toolsList = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "tools/list")
            .put("params", JSONObject())
            .toString()

        // 1) MCP-Protocol-Version: 2026-07-28 header
        assertModern400Fallback(
            Mcp.handle(server, mcpReq(toolsList, mapOf("MCP-Protocol-Version" to "2026-07-28"))),
        )

        // 2) Mcp-Method header
        assertModern400Fallback(
            Mcp.handle(server, mcpReq(toolsList, mapOf("Mcp-Method" to "tools/list"))),
        )

        // 3) method == server/discover
        val discover = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 2)
            .put("method", "server/discover")
            .put("params", JSONObject())
            .toString()
        assertModern400Fallback(Mcp.handle(server, mcpReq(discover)))

        // 4) params._meta["io.modelcontextprotocol/protocolVersion"] >= 2026-01-01
        val metaModern = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 3)
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", "vscreen")
                    .put("arguments", JSONObject().put("command", "help codes"))
                    .put(
                        "_meta",
                        JSONObject()
                            .put("io.modelcontextprotocol/protocolVersion", "2026-07-28")
                            .put("progressToken", "p1"),
                    ),
            )
            .toString()
        // Even with Accept: text/event-stream, must reject before SSE streaming
        assertModern400Fallback(
            Mcp.handle(server, mcpReq(metaModern, mapOf("Accept" to "text/event-stream"))),
        )
    }

    @Test
    fun modern2026BatchRequestsReturnHttp400Without32022() {
        val batchWithDiscover = JSONArray()
            .put(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"))
            .put(JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "server/discover"))
            .toString()
        assertModern400Fallback(Mcp.handle(server, mcpReq(batchWithDiscover)))

        val batchWithHeader = JSONArray()
            .put(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"))
            .toString()
        assertModern400Fallback(
            Mcp.handle(server, mcpReq(batchWithHeader, mapOf("MCP-Protocol-Version" to "2026-07-28"))),
        )
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

        // tools/call with oversized requestId (> 64 chars) executes cleanly and caps to 64 chars for idempotency replay
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

        // Calling again with the exact 64-char prefix hits the idempotent flight replay
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
