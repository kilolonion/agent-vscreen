package com.kilon.agentvscreen.api

import android.app.Application
import android.content.Context
import com.kilon.agentvscreen.cmd.Call
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for P2-1 dual-stack MCP (`2026-07-28` stateless + `io.modelcontextprotocol/tasks` + legacy `initialize`)
 * and PR #66 review closing conditions 01, 04, 05, 09, and 10:
 * - Strict `2026-07-28` `DiscoverResult` wire validation (`supportedVersions`, `_meta.serverInfo`, no `protocolVersions`)
 * - `tools/list` deterministic ordering, JSON Schema 2020-12 (`$schema`, `required`, repeatable `--on` `oneOf`), full annotations
 * - SEP-2243 `Mcp-Method` & `Mcp-Name` mismatch (`-32020`), missing tasks capability (`-32021`), unsupported revision (`-32022`)
 * - Official flat `CreateTaskResult` (`ttlMs`, top-level `taskId`) & `tasks/get` inline `result` for both success and `isError`
 * - Multi-step `act` zero-duplicate continuation after phone confirmation (concurrent pollers, retries, denial, expiry, drift, nested block)
 * - Unified atomic task cancellation state machine (WAITING_PHONE, REPLAYING race, immutable terminal state)
 * - `ApiServer` idempotency lookup-before-eviction under exact-full, cache-hit, all-in-flight, and TTL-expiry cases
 * - Chunked SSE framing, legacy `initialize`, 64-char requestId cap, and `THERMAL_STOP`
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
        server.execOverride = null
        server.idemMax = 200
        server.idemClockMs = { System.currentTimeMillis() }
        McpTasks.resetForTest()
        UserAsk.reset()
        ctx.getSharedPreferences("thermal_safety_v1", Context.MODE_PRIVATE).edit().putBoolean("locked", false).commit()
        ThermalGuard.init(ctx)
        StrongConfirm.resetForTests()
        tempDir.deleteRecursively()
    }

    private fun mcpReq(
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
        tok: Tokens.Token = Tokens.byId(Tokens.MAIN)!!,
        grantId: String? = null,
    ): ApiServer.Req {
        val headers = LinkedHashMap<String, String>()
        headers["host"] = "127.0.0.1"
        headers["content-type"] = "application/json"
        extraHeaders.forEach { (k, v) -> headers[k.lowercase()] = v }
        val r = ApiServer.Req("POST", "/mcp", emptyMap(), headers, body.toByteArray(Charsets.UTF_8))
        r.auth = ApiServer.Auth(tok, signed = false, nonce = null)
        r.publicGrantId = grantId
        return r
    }

    private fun tasksMeta(): JSONObject = JSONObject().put(
        McpProtocol.META_CLIENT_CAPABILITIES,
        JSONObject().put("extensions", JSONObject().put(McpProtocol.TASKS_EXTENSION, JSONObject())),
    )

    /**
     * Independent strict validator for the official MCP `2026-07-28` `DiscoverResult` schema.
     * Verifies required fields, types, and forbids deprecated/non-standard fields (`protocolVersions`, `Mcp-Session-Id`).
     */
    private fun validateOfficial2026DiscoverWire(resp: ApiServer.Resp): JSONObject {
        assertEquals(200, resp.status)
        assertNull("2026-07-28 stateless responses must not emit Mcp-Session-Id", resp.headers["Mcp-Session-Id"])
        val wire = JSONObject(String(resp.body, Charsets.UTF_8))
        assertEquals("2.0", wire.getString("jsonrpc"))
        assertFalse("Discover response must not be an error", wire.has("error"))
        val res = wire.getJSONObject("result")
        assertFalse("Deprecated/non-standard protocolVersions must NOT appear", res.has("protocolVersions"))
        val supported = res.getJSONArray("supportedVersions")
        assertTrue("supportedVersions must be a non-empty array", supported.length() >= 1)
        assertEquals("2026-07-28", supported.getString(0))
        val meta = res.getJSONObject("_meta")
        val serverInfo = meta.getJSONObject("io.modelcontextprotocol/serverInfo")
        assertTrue(serverInfo.getString("name").isNotBlank())
        assertTrue(serverInfo.getString("version").isNotBlank())
        val caps = res.getJSONObject("capabilities")
        assertTrue(caps.has("tools"))
        assertTrue(caps.getJSONObject("extensions").has("io.modelcontextprotocol/tasks"))
        assertEquals("complete", res.getString("resultType"))
        assertTrue(res.getLong("ttlMs") > 0L)
        assertEquals("public", res.getString("cacheScope"))
        return res
    }

    /** Independent strict validator for `ext-tasks` (`2026-07-28`) flat `CreateTaskResult`. */
    private fun validateOfficialCreateTaskResult(res: JSONObject): String {
        assertEquals("task", res.getString("resultType"))
        assertFalse("CreateTaskResult must be flat (no nested task object)", res.has("task"))
        assertFalse("Task descriptor must use ttlMs, not ttl", res.has("ttl"))
        val taskId = res.getString("taskId")
        assertTrue(taskId.isNotBlank())
        assertTrue(res.getString("status") in setOf("working", "completed", "failed", "cancelled"))
        assertTrue(res.getString("createdAt").endsWith("Z"))
        assertTrue(res.getString("lastUpdatedAt").endsWith("Z"))
        assertTrue(res.getLong("ttlMs") > 0L)
        assertTrue(res.getLong("pollIntervalMs") > 0L)
        return taskId
    }

    private fun pollTaskUntilDone(
        taskId: String,
        tok: Tokens.Token = Tokens.byId(Tokens.MAIN)!!,
        grantId: String? = null,
        maxWaitMs: Long = 5_000L,
    ): JSONObject {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var last = JSONObject()
        while (System.currentTimeMillis() < deadline) {
            val getBody = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 900)
                .put("method", "tasks/get")
                .put("params", JSONObject().put("taskId", taskId).put("_meta", tasksMeta()))
                .toString()
            val resp = Mcp.handle(
                server,
                mcpReq(
                    getBody,
                    mapOf(
                        "MCP-Protocol-Version" to "2026-07-28",
                        "Mcp-Method" to "tasks/get",
                        "Mcp-Name" to taskId,
                    ),
                    tok = tok,
                    grantId = grantId,
                ),
            )
            assertEquals(200, resp.status)
            last = JSONObject(String(resp.body, Charsets.UTF_8)).getJSONObject("result")
            if (last.getString("status") != "working") return last
            Thread.sleep(20L)
        }
        return last
    }

    // ── Fix 04: Official 2026-07-28 DiscoverResult -> tools/list -> side-effect-free tools/call ──

    @Test
    fun modern2026DiscoverToolsListAndSideEffectFreeCallFollowOfficialSchema() {
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
        validateOfficial2026DiscoverWire(discResp)

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

        // Complete the full client handshake flow with a side-effect-free `tools/call` (`help codes`).
        val callBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 3)
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", "vscreen")
                    .put("arguments", JSONObject().put("command", "help codes")),
            )
            .toString()
        val callResp = Mcp.handle(
            server,
            mcpReq(
                callBody,
                mapOf(
                    "MCP-Protocol-Version" to "2026-07-28",
                    "Mcp-Method" to "tools/call",
                    "Mcp-Name" to "vscreen",
                ),
            ),
        )
        assertEquals(200, callResp.status)
        assertNull(callResp.headers["Mcp-Session-Id"])
        val callRes = JSONObject(String(callResp.body, Charsets.UTF_8)).getJSONObject("result")
        assertEquals("complete", callRes.getString("resultType"))
        assertFalse(callRes.getBoolean("isError"))
        assertTrue(callRes.getJSONArray("content").length() >= 1)
    }

    // ── Fix 04 & 05: SEP-2243 header validation, -32021 missing capability, -32022 version ──

    @Test
    fun modernHeaderMismatchReturns32020MissingTasksCapReturns32021AndBadVersionReturns32022() {
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
        assertEquals(McpProtocol.ERR_HEADER_MISMATCH, JSONObject(String(mismatchMethod.body, Charsets.UTF_8)).getJSONObject("error").getInt("code"))

        // Mcp-Name mismatch on tools/call -> HTTP 400 + JSON-RPC -32020
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
        assertEquals(McpProtocol.ERR_HEADER_MISMATCH, JSONObject(String(mismatchName.body, Charsets.UTF_8)).getJSONObject("error").getInt("code"))

        // Mcp-Name mismatch on tasks/get (must match params.taskId) -> HTTP 400 + JSON-RPC -32020
        val badTaskNameBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 12)
            .put("method", "tasks/get")
            .put("params", JSONObject().put("taskId", "cfm_real").put("_meta", tasksMeta()))
            .toString()
        val mismatchTaskName = Mcp.handle(
            server,
            mcpReq(
                badTaskNameBody,
                mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "tasks/get", "Mcp-Name" to "cfm_other"),
            ),
        )
        assertEquals(400, mismatchTaskName.status)
        assertEquals(McpProtocol.ERR_HEADER_MISMATCH, JSONObject(String(mismatchTaskName.body, Charsets.UTF_8)).getJSONObject("error").getInt("code"))

        // Missing io.modelcontextprotocol/tasks client capability on tasks/get|cancel|update -> HTTP 400 + -32021
        for (method in listOf("tasks/get", "tasks/cancel", "tasks/update", "tasks/list")) {
            val params = if (method == "tasks/list") JSONObject() else JSONObject().put("taskId", "cfm_abc")
            val body = JSONObject().put("jsonrpc", "2.0").put("id", 13).put("method", method).put("params", params).toString()
            val hdrs = buildMap {
                put("MCP-Protocol-Version", "2026-07-28")
                put("Mcp-Method", method)
                if (method != "tasks/list") put("Mcp-Name", "cfm_abc")
            }
            val resp = Mcp.handle(server, mcpReq(body, hdrs))
            assertEquals("Expected 400 for missing tasks capability on $method", 400, resp.status)
            val err = JSONObject(String(resp.body, Charsets.UTF_8)).getJSONObject("error")
            assertEquals(McpProtocol.ERR_MISSING_CAPABILITY, err.getInt("code"))
            assertTrue(
                err.getJSONObject("data")
                    .getJSONObject("requiredCapabilities")
                    .getJSONObject("extensions")
                    .has(McpProtocol.TASKS_EXTENSION),
            )
        }

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

    // ── Fix 05: Full standard ext-tasks client lifecycle (flat CreateTaskResult + tasks/get inline result) ──

    @Test
    fun standardExtTasksClientCompletesCreatePollInlineResultIsolationAndRejectsTasksUpdate() {
        val mainTok = Tokens.byId(Tokens.MAIN)!!
        val otherTok = Tokens.create("other-agent", Tokens.FREE).first
        val now = System.currentTimeMillis()
        val (cfmReq, _) = StrongConfirm.getOrCreateRequest(
            risk = Risk.HIGH_RISK,
            key = "HIGH_RISK:${mainTok.id}:pay-ext-tasks",
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

        var stepRuns = 0
        server.execOverride = { argv, _ ->
            stepRuns++
            if (stepRuns == 1) {
                NativeOffloadResult(
                    exitCode = 1,
                    output = "pending\n",
                    code = "CONFIRM_PENDING",
                    data = JSONObject()
                        .put("confirm", JSONObject().put("id", cfmReq.id))
                        .put("user_action", JSONObject().put("tell_user", "Confirm on phone")),
                )
            } else {
                NativeOffloadResult(0, "done: ${argv.joinToString(" ")}\n", "OK")
            }
        }

        // Step 1: tools/call with tasks capability -> flat CreateTaskResult
        val callBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 20)
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", "vscreen_act")
                    .put("arguments", JSONObject().put("args", "tap Pay"))
                    .put("_meta", tasksMeta()),
            )
            .toString()
        val callResp = Mcp.handle(
            server,
            mcpReq(
                callBody,
                mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "tools/call", "Mcp-Name" to "vscreen_act"),
                tok = mainTok,
            ),
        )
        assertEquals(200, callResp.status)
        val createRes = JSONObject(String(callResp.body, Charsets.UTF_8)).getJSONObject("result")
        val taskId = validateOfficialCreateTaskResult(createRes)
        assertEquals("cfm_${cfmReq.id}", taskId)
        assertEquals("working", createRes.getString("status"))
        assertEquals("Confirm on phone", createRes.getString("statusMessage"))

        // Step 2: tasks/get with Mcp-Name = taskId returns working (never input_required)
        val getBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 21)
            .put("method", "tasks/get")
            .put("params", JSONObject().put("taskId", taskId).put("_meta", tasksMeta()))
            .toString()
        val getHeaders = mapOf(
            "MCP-Protocol-Version" to "2026-07-28",
            "Mcp-Method" to "tasks/get",
            "Mcp-Name" to taskId,
        )
        val getWorking = Mcp.handle(server, mcpReq(getBody, getHeaders, tok = mainTok))
        assertEquals(200, getWorking.status)
        val workingRes = JSONObject(String(getWorking.body, Charsets.UTF_8)).getJSONObject("result")
        assertEquals("complete", workingRes.getString("resultType"))
        assertEquals("working", workingRes.getString("status"))
        assertFalse("Working task must not inline result yet", workingRes.has("result"))

        // Cross-token tasks/get MUST fail (strict (ownerKey, tokenId, taskId) isolation)
        val otherGet = Mcp.handle(server, mcpReq(getBody, getHeaders, tok = otherTok))
        assertTrue(JSONObject(String(otherGet.body, Charsets.UTF_8)).has("error"))

        // tasks/update MUST be rejected (-32601) even with tasks capability, and NEVER approve StrongConfirm
        val updateBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 22)
            .put("method", "tasks/update")
            .put("params", JSONObject().put("taskId", taskId).put("approved", true).put("_meta", tasksMeta()))
            .toString()
        val updResp = Mcp.handle(
            server,
            mcpReq(
                updateBody,
                mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "tasks/update", "Mcp-Name" to taskId),
                tok = mainTok,
            ),
        )
        assertEquals(-32601, JSONObject(String(updResp.body, Charsets.UTF_8)).getJSONObject("error").getInt("code"))
        assertNull("StrongConfirm must remain unapproved", StrongConfirm.byId(cfmReq.id)?.answer)

        // Step 3: User approves on phone -> standard tasks/get ALONE delivers inline result!
        assertTrue(StrongConfirm.approve(ctx, cfmReq.id, by = "biometric"))
        val doneRes = pollTaskUntilDone(taskId, tok = mainTok)
        assertEquals("complete", doneRes.getString("resultType"))
        assertEquals("completed", doneRes.getString("status"))
        assertTrue("tasks/get on completed task MUST inline result", doneRes.has("result"))
        val inlineToolResult = doneRes.getJSONObject("result")
        assertFalse(inlineToolResult.getBoolean("isError"))
        assertTrue(inlineToolResult.getJSONObject("structuredContent").getBoolean("ok"))

        // Step 4: Verify UserAsk ask_ bridging also returns flat CreateTaskResult & inline isError result on decline
        val ask = UserAsk.create(ctx, UserAsk.Kind.TEXT, "ask-key-flat", "Pick", "Detail")
        val askRes = NativeOffloadResult(
            exitCode = 1,
            output = "pending\n",
            code = "ASK_PENDING",
            data = JSONObject().put("ask", JSONObject().put("id", ask.id)),
        )
        val askWrapped = McpTasks.maybeWrap(mcpReq("{}", tok = mainTok), listOf("help", "codes"), "s1", askRes)!!
        val askTaskId = validateOfficialCreateTaskResult(askWrapped)
        assertEquals("ask_${ask.id}", askTaskId)
        assertEquals("working", askWrapped.getString("status"))
        UserAsk.answer(ctx, ask.id, allow = false)
        val askDone = pollTaskUntilDone(askTaskId, tok = mainTok)
        assertEquals("completed", askDone.getString("status"))
        val askInline = askDone.getJSONObject("result")
        assertTrue("Declined ask must complete with inline CallToolResult isError=true", askInline.getBoolean("isError"))
        assertEquals("ASK_DECLINED", askInline.getJSONObject("structuredContent").getString("code"))
    }

    // ── Fix 01: Multi-step act zero-duplicate continuation after StrongConfirm ──

    @Test
    fun multiStepActContinuationExecutesEachSideEffectAtMostOnceAcrossPollersRetriesDenialExpiryDriftAndNestedBlocks() {
        val mainTok = Tokens.byId(Tokens.MAIN)!!

        // Verify argv rewriter drops --save, injects --bare when beforeRan, and replaces positional steps.
        val rewritten = McpTasks.rewriteActArgvForContinuation(
            originalArgv = listOf("act", "--fast", "--save", "my_seg", "tap Plus; tap Pay"),
            resumeSteps = "tap Pay",
            addBare = true,
            priorDoneCount = 1,
        )
        assertEquals(listOf("act", "--fast", "--bare", "tap Pay"), rewritten)

        // Scenario A: Multi-step act ("tap Plus; tap Pay") with prior side effect + retry + 2 concurrent pollers.
        val prefixCount = AtomicInteger(0)
        val payCount = AtomicInteger(0)
        val executedArgvs = java.util.Collections.synchronizedList(ArrayList<List<String>>())
        var activeCfmId: String? = null

        val (cfmA, _) = StrongConfirm.getOrCreateRequest(
            risk = Risk.HIGH_RISK,
            key = "HIGH_RISK:${mainTok.id}:multi-step-a",
            tokenId = mainTok.id,
            tokenName = mainTok.name,
            tokenScope = "system",
            client = Risk.CLIENT_LOCAL,
            commandName = "pay",
            detail = "tap Pay",
            validMs = 60_000L,
            reusable = false,
            allowHold = true,
            nowMs = System.currentTimeMillis(),
        )
        activeCfmId = cfmA.id

        McpTasks.continuationPageVerifier = { _, gen, act ->
            assertEquals(7L, gen)
            assertEquals("com.shop/.Checkout", act)
            null
        }
        server.execOverride = { argv, _ ->
            executedArgvs += argv
            val stepsArg = argv.lastOrNull().orEmpty()
            if (stepsArg.contains("tap Plus")) {
                prefixCount.incrementAndGet()
                // Step 2 requires phone confirmation; attach continuation point starting at "tap Pay".
                NativeOffloadResult(
                    exitCode = 1,
                    output = "confirm pay\n",
                    code = "CONFIRM_PENDING",
                    data = JSONObject()
                        .put("confirm", JSONObject().put("id", activeCfmId))
                        .put(
                            "continuation",
                            JSONObject()
                                .put("kind", "act")
                                .put("session", "s1")
                                .put("prior_steps_done", 1)
                                .put("safe_resume", true)
                                .put("resume_steps", "tap Pay")
                                .put("add_bare", true)
                                .put("display_generation", 7L)
                                .put("activity", "com.shop/.Checkout"),
                        ),
                )
            } else if (stepsArg == "tap Pay") {
                payCount.incrementAndGet()
                NativeOffloadResult(0, "paid once\n", "OK")
            } else {
                error("Unexpected argv: $argv")
            }
        }

        val multiCallBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 30)
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", "vscreen_act")
                    .put("arguments", JSONObject().put("args", "tap Plus; tap Pay").put("session", "s1"))
                    .put("_meta", tasksMeta().put("requestId", "req-multi-a")),
            )
            .toString()
        val callHeaders = mapOf(
            "MCP-Protocol-Version" to "2026-07-28",
            "Mcp-Method" to "tools/call",
            "Mcp-Name" to "vscreen_act",
        )
        val firstResp = Mcp.handle(server, mcpReq(multiCallBody, callHeaders, tok = mainTok))
        val taskIdA = validateOfficialCreateTaskResult(JSONObject(String(firstResp.body, Charsets.UTF_8)).getJSONObject("result"))
        assertEquals(1, prefixCount.get())
        assertEquals(0, payCount.get())

        // Original request retry while pending must hit idempotency AND must NOT overwrite/reset the bridged task entry.
        val retryResp = Mcp.handle(server, mcpReq(multiCallBody, callHeaders, tok = mainTok))
        val retryTaskId = validateOfficialCreateTaskResult(JSONObject(String(retryResp.body, Charsets.UTF_8)).getJSONObject("result"))
        assertEquals(taskIdA, retryTaskId)
        assertEquals("Retrying original request must not re-run prefix step", 1, prefixCount.get())

        // Approve on phone, then launch TWO concurrent tasks/get pollers simultaneously.
        assertTrue(StrongConfirm.approve(ctx, cfmA.id, by = "biometric"))
        val startGate = CountDownLatch(1)
        val p1Res = AtomicReference<JSONObject>()
        val p2Res = AtomicReference<JSONObject>()
        val t1 = Thread {
            startGate.await(2, TimeUnit.SECONDS)
            p1Res.set(pollTaskUntilDone(taskIdA, tok = mainTok))
        }
        val t2 = Thread {
            startGate.await(2, TimeUnit.SECONDS)
            p2Res.set(pollTaskUntilDone(taskIdA, tok = mainTok))
        }
        t1.start(); t2.start(); startGate.countDown()
        t1.join(5_000L); t2.join(5_000L)

        assertEquals("completed", p1Res.get().getString("status"))
        assertEquals("completed", p2Res.get().getString("status"))
        assertFalse(p1Res.get().getJSONObject("result").getBoolean("isError"))
        assertEquals("Prefix step must run strictly once", 1, prefixCount.get())
        assertEquals("Confirmed tail step must run strictly once across 2 concurrent pollers", 1, payCount.get())
        assertTrue("Continuation argv must pass --bare and only resume_steps", executedArgvs.last().contains("--bare"))
        assertEquals("tap Pay", executedArgvs.last().last())

        // Scenario B: Page drift before continuation -> STALE_ELEMENT, tail step count = 0.
        val (cfmDrift, _) = StrongConfirm.getOrCreateRequest(
            risk = Risk.HIGH_RISK,
            key = "HIGH_RISK:${mainTok.id}:multi-drift",
            tokenId = mainTok.id,
            tokenName = mainTok.name,
            tokenScope = "system",
            client = Risk.CLIENT_LOCAL,
            commandName = "pay",
            detail = "tap Pay",
            validMs = 60_000L,
            reusable = false,
            allowHold = true,
            nowMs = System.currentTimeMillis(),
        )
        val driftRes = NativeOffloadResult(
            1, "confirm\n", "CONFIRM_PENDING",
            data = JSONObject()
                .put("confirm", JSONObject().put("id", cfmDrift.id))
                .put(
                    "continuation",
                    JSONObject()
                        .put("kind", "act")
                        .put("session", "s1")
                        .put("prior_steps_done", 1)
                        .put("safe_resume", true)
                        .put("resume_steps", "tap Pay")
                        .put("display_generation", 7L)
                        .put("activity", "com.shop/.Checkout"),
                ),
        )
        val driftTaskId = validateOfficialCreateTaskResult(
            McpTasks.maybeWrap(mcpReq("{}", tok = mainTok), listOf("act", "tap Plus; tap Pay"), "s1", driftRes)!!,
        )
        McpTasks.continuationPageVerifier = { _, _, _ -> "foreground page changed" }
        assertTrue(StrongConfirm.approve(ctx, cfmDrift.id, by = "biometric"))
        val driftDone = pollTaskUntilDone(driftTaskId, tok = mainTok)
        assertEquals("completed", driftDone.getString("status"))
        assertEquals("STALE_ELEMENT", driftDone.getJSONObject("result").getJSONObject("structuredContent").getString("code"))
        assertEquals("Drifted screen must NOT execute tail step", 1, payCount.get())

        // Scenario C: Non-resumable nested block (`safe_resume = false`) -> stops cleanly with completed steps.
        val (cfmNested, _) = StrongConfirm.getOrCreateRequest(
            risk = Risk.HIGH_RISK,
            key = "HIGH_RISK:${mainTok.id}:multi-nested",
            tokenId = mainTok.id,
            tokenName = mainTok.name,
            tokenScope = "system",
            client = Risk.CLIENT_LOCAL,
            commandName = "pay",
            detail = "inside repeat",
            validMs = 60_000L,
            reusable = false,
            allowHold = true,
            nowMs = System.currentTimeMillis(),
        )
        val nestedRes = NativeOffloadResult(
            1, "confirm\n", "CONFIRM_PENDING",
            data = JSONObject()
                .put("confirm", JSONObject().put("id", cfmNested.id))
                .put(
                    "continuation",
                    JSONObject()
                        .put("kind", "act")
                        .put("session", "s1")
                        .put("prior_steps_done", 2)
                        .put("safe_resume", false),
                ),
        )
        val nestedTaskId = validateOfficialCreateTaskResult(
            McpTasks.maybeWrap(mcpReq("{}", tok = mainTok), listOf("act", "repeat 2 { tap Plus; tap Pay }"), "s1", nestedRes)!!,
        )
        assertTrue(StrongConfirm.approve(ctx, cfmNested.id, by = "biometric"))
        val nestedDone = pollTaskUntilDone(nestedTaskId, tok = mainTok)
        assertEquals("completed", nestedDone.getString("status"))
        val nestedData = nestedDone.getJSONObject("result").getJSONObject("structuredContent")
        assertTrue(nestedData.getBoolean("confirmed"))
        assertTrue(nestedData.getBoolean("continuation_skipped"))
        assertEquals(2, nestedData.getInt("prior_steps_done"))
        assertEquals("Unsafe nested block must NOT replay", 1, payCount.get())

        // Scenario D: Phone denial & expiry -> zero side effects, inline isError=true result.
        val (cfmDeny, _) = StrongConfirm.getOrCreateRequest(
            risk = Risk.HIGH_RISK,
            key = "HIGH_RISK:${mainTok.id}:multi-deny",
            tokenId = mainTok.id,
            tokenName = mainTok.name,
            tokenScope = "system",
            client = Risk.CLIENT_LOCAL,
            commandName = "pay",
            detail = "deny me",
            validMs = 60_000L,
            reusable = false,
            allowHold = true,
            nowMs = System.currentTimeMillis(),
        )
        val denyTaskId = validateOfficialCreateTaskResult(
            McpTasks.maybeWrap(
                mcpReq("{}", tok = mainTok),
                listOf("act", "tap Pay"),
                "s1",
                NativeOffloadResult(1, "confirm\n", "CONFIRM_PENDING", data = JSONObject().put("confirm", JSONObject().put("id", cfmDeny.id))),
            )!!,
        )
        assertTrue(StrongConfirm.deny(ctx, cfmDeny.id))
        val denyDone = pollTaskUntilDone(denyTaskId, tok = mainTok)
        assertEquals("completed", denyDone.getString("status"))
        assertTrue(denyDone.getJSONObject("result").getBoolean("isError"))
        assertEquals("USER_DENIED", denyDone.getJSONObject("result").getJSONObject("structuredContent").getString("code"))
        assertEquals(1, payCount.get())

        var fakeNow = 2_000_000L
        StrongConfirm.clock = { fakeNow }
        val (cfmExp, _) = StrongConfirm.getOrCreateRequest(
            risk = Risk.HIGH_RISK,
            key = "HIGH_RISK:${mainTok.id}:multi-expire",
            tokenId = mainTok.id,
            tokenName = mainTok.name,
            tokenScope = "system",
            client = Risk.CLIENT_LOCAL,
            commandName = "pay",
            detail = "expire me",
            validMs = 1_000L,
            reusable = false,
            allowHold = true,
            nowMs = fakeNow,
        )
        val expTaskId = validateOfficialCreateTaskResult(
            McpTasks.maybeWrap(
                mcpReq("{}", tok = mainTok),
                listOf("act", "tap Pay"),
                "s1",
                NativeOffloadResult(1, "confirm\n", "CONFIRM_PENDING", data = JSONObject().put("confirm", JSONObject().put("id", cfmExp.id))),
            )!!,
        )
        fakeNow += 10_000L
        StrongConfirm.expireDue()
        val expDone = pollTaskUntilDone(expTaskId, tok = mainTok)
        assertEquals("completed", expDone.getString("status"))
        assertTrue(expDone.getJSONObject("result").getBoolean("isError"))
        assertEquals("CONFIRM_PENDING", expDone.getJSONObject("result").getJSONObject("structuredContent").getString("code"))
        assertEquals(1, payCount.get())
    }

    // ── Fix 10: Unified atomic cancellation state machine ──

    @Test
    fun taskCancellationIsAtomicAndNeverContradictsOrMutatesTerminalState() {
        val mainTok = Tokens.byId(Tokens.MAIN)!!

        fun cancelTask(taskId: String): JSONObject {
            val body = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 40)
                .put("method", "tasks/cancel")
                .put("params", JSONObject().put("taskId", taskId).put("_meta", tasksMeta()))
                .toString()
            val resp = Mcp.handle(
                server,
                mcpReq(
                    body,
                    mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "tasks/cancel", "Mcp-Name" to taskId),
                    tok = mainTok,
                ),
            )
            assertEquals(200, resp.status)
            return JSONObject(String(resp.body, Charsets.UTF_8)).getJSONObject("result")
        }

        // Case 1: Cancel while WAITING_PHONE -> immediately cancelled & immutable even if approved later.
        val runs1 = AtomicInteger(0)
        server.execOverride = { _, _ -> runs1.incrementAndGet(); NativeOffloadResult(0, "ok\n", "OK") }
        val (cfm1, _) = StrongConfirm.getOrCreateRequest(
            risk = Risk.HIGH_RISK,
            key = "HIGH_RISK:${mainTok.id}:cancel-wait",
            tokenId = mainTok.id,
            tokenName = mainTok.name,
            tokenScope = "system",
            client = Risk.CLIENT_LOCAL,
            commandName = "pay",
            detail = "wait",
            validMs = 60_000L,
            reusable = false,
            allowHold = true,
            nowMs = System.currentTimeMillis(),
        )
        val tid1 = validateOfficialCreateTaskResult(
            McpTasks.maybeWrap(
                mcpReq("{}", tok = mainTok),
                listOf("act", "tap Pay"),
                "s1",
                NativeOffloadResult(1, "confirm\n", "CONFIRM_PENDING", data = JSONObject().put("confirm", JSONObject().put("id", cfm1.id))),
            )!!,
        )
        assertEquals("cancelled", cancelTask(tid1).getString("status"))
        assertTrue(StrongConfirm.approve(ctx, cfm1.id, by = "biometric"))
        val afterApprove = pollTaskUntilDone(tid1, tok = mainTok)
        assertEquals("cancelled", afterApprove.getString("status"))
        assertEquals("Cancelled task must never start replay", 0, runs1.get())

        // Case 2: Cancel while REPLAYING -> reports working (honest), signals Call.cancelled, settles to ONE terminal state.
        val enteredReplay = CountDownLatch(1)
        val releaseReplay = CountDownLatch(1)
        val sawCancelled = AtomicReference(false)
        server.execOverride = { _, _ ->
            enteredReplay.countDown()
            releaseReplay.await(5, TimeUnit.SECONDS)
            val c = Call.current()?.cancelled == true
            sawCancelled.set(c)
            if (c) NativeOffloadResult(1, "cancelled\n", "CANCELLED")
            else NativeOffloadResult(0, "done\n", "OK")
        }
        val (cfm2, _) = StrongConfirm.getOrCreateRequest(
            risk = Risk.HIGH_RISK,
            key = "HIGH_RISK:${mainTok.id}:cancel-replaying",
            tokenId = mainTok.id,
            tokenName = mainTok.name,
            tokenScope = "system",
            client = Risk.CLIENT_LOCAL,
            commandName = "pay",
            detail = "replaying",
            validMs = 60_000L,
            reusable = false,
            allowHold = true,
            nowMs = System.currentTimeMillis(),
        )
        val tid2 = validateOfficialCreateTaskResult(
            McpTasks.maybeWrap(
                mcpReq("{}", tok = mainTok),
                listOf("act", "tap Pay"),
                "s1",
                NativeOffloadResult(1, "confirm\n", "CONFIRM_PENDING", data = JSONObject().put("confirm", JSONObject().put("id", cfm2.id))),
            )!!,
        )
        assertTrue(StrongConfirm.approve(ctx, cfm2.id, by = "biometric"))
        // Kick off replay via first tasks/get
        val kickBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 41)
            .put("method", "tasks/get")
            .put("params", JSONObject().put("taskId", tid2).put("_meta", tasksMeta()))
            .toString()
        Mcp.handle(
            server,
            mcpReq(kickBody, mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "tasks/get", "Mcp-Name" to tid2), tok = mainTok),
        )
        assertTrue("Replay thread must enter execOverride", enteredReplay.await(3, TimeUnit.SECONDS))

        // Cancel while actively replaying: MUST report "working" rather than falsely claiming "cancelled".
        val cancelDuring = cancelTask(tid2)
        assertEquals("working", cancelDuring.getString("status"))
        releaseReplay.countDown()

        val settled2 = pollTaskUntilDone(tid2, tok = mainTok)
        assertTrue("Cooperative Call.cancelled must be observed", sawCancelled.get())
        assertEquals("cancelled", settled2.getString("status"))
        assertFalse("Cancelled task must not attach a conflicting result", settled2.has("result"))

        // Case 3: Cancel AFTER COMPLETED -> strictly immutable, stays completed!
        server.execOverride = { _, _ -> NativeOffloadResult(0, "finished\n", "OK") }
        val (cfm3, _) = StrongConfirm.getOrCreateRequest(
            risk = Risk.HIGH_RISK,
            key = "HIGH_RISK:${mainTok.id}:cancel-after-done",
            tokenId = mainTok.id,
            tokenName = mainTok.name,
            tokenScope = "system",
            client = Risk.CLIENT_LOCAL,
            commandName = "pay",
            detail = "done",
            validMs = 60_000L,
            reusable = false,
            allowHold = true,
            nowMs = System.currentTimeMillis(),
        )
        val tid3 = validateOfficialCreateTaskResult(
            McpTasks.maybeWrap(
                mcpReq("{}", tok = mainTok),
                listOf("act", "tap Pay"),
                "s1",
                NativeOffloadResult(1, "confirm\n", "CONFIRM_PENDING", data = JSONObject().put("confirm", JSONObject().put("id", cfm3.id))),
            )!!,
        )
        assertTrue(StrongConfirm.approve(ctx, cfm3.id, by = "biometric"))
        val done3 = pollTaskUntilDone(tid3, tok = mainTok)
        assertEquals("completed", done3.getString("status"))
        assertEquals("Completed task must remain completed after cancel", "completed", cancelTask(tid3).getString("status"))
        val getAfterCancel = pollTaskUntilDone(tid3, tok = mainTok)
        assertEquals("completed", getAfterCancel.getString("status"))
        assertTrue(getAfterCancel.has("result"))
    }

    // ── Fix 09: Idempotency cache lookup-before-eviction under full/hit/in-flight/TTL ──

    @Test
    fun idempotencyCacheLooksUpExistingOldestKeyBeforeEvictingAndHandlesFullInFlightAndTtl() {
        var now = 10_000_000L
        server.idemClockMs = { now }
        server.idemMax = 3
        val counts = LinkedHashMap<String, AtomicInteger>()
        server.execOverride = { argv, _ ->
            val tag = argv.last()
            val c = synchronized(counts) { counts.getOrPut(tag) { AtomicInteger(0) } }.incrementAndGet()
            NativeOffloadResult(0, "$tag#$c\n", "OK")
        }
        val req = mcpReq("{}")

        // 1. Fill cache to exact capacity (idemMax = 3)
        val (r1, rep1) = server.runFor(req, listOf("act", "op1"), "s1", "k1")
        val (_, rep2) = server.runFor(req, listOf("act", "op2"), "s1", "k2")
        val (_, rep3) = server.runFor(req, listOf("act", "op3"), "s1", "k3")
        assertFalse(rep1); assertFalse(rep2); assertFalse(rep3)
        assertEquals(1, counts["op1"]!!.get())

        // 2. Retry the OLDEST in-TTL key ("k1") when cache is at exact capacity -> MUST hit cache, NOT evict & re-run!
        val (r1Retry, rep1Retry) = server.runFor(req, listOf("act", "op1"), "s1", "k1")
        assertTrue("Oldest in-TTL key at full capacity must replay from cache", rep1Retry)
        assertEquals(r1.output, r1Retry.output)
        assertEquals("Side-effect count for k1 must stay 1", 1, counts["op1"]!!.get())

        // 3. Inserting a brand-new key ("k4") evicts oldest finished ("k1") while keeping k2 and k3.
        val (_, rep4) = server.runFor(req, listOf("act", "op4"), "s1", "k4")
        assertFalse(rep4)
        val (_, rep2Hit) = server.runFor(req, listOf("act", "op2"), "s1", "k2")
        assertTrue("k2 should still be cached", rep2Hit)
        assertEquals(1, counts["op2"]!!.get())

        // 4. TTL expiry allows k2 to execute freshly.
        now += ApiServer.IDEM_MS + 1_000L
        val (_, rep2AfterTtl) = server.runFor(req, listOf("act", "op2"), "s1", "k2")
        assertFalse("Expired key must execute freshly", rep2AfterTtl)
        assertEquals(2, counts["op2"]!!.get())

        // 5. All-in-flight case: 3 concurrent running calls at capacity do not evict each other, and retrying an in-flight key joins it.
        now += ApiServer.IDEM_MS + 1_000L // prune prior completed entries
        val allEntered = CountDownLatch(3)
        val releaseAll = CountDownLatch(1)
        val inflightCounts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        server.execOverride = { argv, _ ->
            val tag = argv.last()
            inflightCounts.computeIfAbsent(tag) { AtomicInteger(0) }.incrementAndGet()
            allEntered.countDown()
            releaseAll.await(5, TimeUnit.SECONDS)
            NativeOffloadResult(0, "done-$tag\n", "OK")
        }
        val threads = (1..3).map { idx ->
            Thread { server.runFor(mcpReq("{}"), listOf("act", "live$idx"), "s1", "live$idx") }.apply { isDaemon = true; start() }
        }
        assertTrue(allEntered.await(3, TimeUnit.SECONDS))

        // Retrying live1 while all 3 slots are in-flight MUST wait and join live1 (never re-execute).
        val retryReplay = AtomicReference<Boolean>()
        val retryThread = Thread {
            val (_, rep) = server.runFor(mcpReq("{}"), listOf("act", "live1"), "s1", "live1")
            retryReplay.set(rep)
        }.apply { isDaemon = true; start() }
        Thread.sleep(60L)
        releaseAll.countDown()
        threads.forEach { it.join(5_000L) }
        retryThread.join(5_000L)
        assertEquals(true, retryReplay.get())
        assertEquals("In-flight retry must not duplicate execution", 1, inflightCounts["live1"]!!.get())
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
