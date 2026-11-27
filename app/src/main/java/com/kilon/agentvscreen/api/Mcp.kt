package com.kilon.agentvscreen.api

import android.util.Base64
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.cmd.CommandSpec
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.cmd.ShotStore
import com.kilon.agentvscreen.host.quiet
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dual-stack Model Context Protocol over Streamable HTTP:
 *  - Modern stateless `2026-07-28` (`server/discover`, SEP-2243 headers, `io.modelcontextprotocol/tasks`, chunked SSE)
 *    handled by [McpModern] / [McpProtocol] / [McpTasks].
 *  - Legacy `initialize`-based revisions (`2025-06-18`, `2025-03-26`, `2024-11-05`) kept compatible in full here.
 */
internal object Mcp {
    private val VERSIONS = McpProtocol.LEGACY_VERSIONS
    private const val PREFIX = "vscreen_"

    private fun lineTool() = JSONObject()
        .put("name", "vscreen")
        .put("description", tr("在看不见的副屏里操作手机上的应用（不占用户主屏）。参数 command 是一条 vscreen 命令行，例如 " +
            "`launch 设置`、`act \"tap 搜索; type 耳机; key enter until text:综合\"`、`act`（看一眼）、`help act`、`help codes`。" +
            "也可以用按命令分开的工具（vscreen_act、vscreen_launch …），参数是字段，不用拼命令行。\n\n",
            "Operates apps on the phone on an invisible second screen (the user's screen is not used). The command parameter is one vscreen command line, e.g. " +
            "`launch Settings`, `act \"tap Search; type headphones; key enter until text:Results\"`, `act` (look), `help act`, `help codes`. " +
            "The per-command tools (vscreen_act, vscreen_launch …) take fields instead of a command line.\n\n") + AgentGuide.loop())
        .put("inputSchema", JSONObject()
            .put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            .put("type", "object")
            .put("properties", JSONObject()
                .put("command", JSONObject().put("type", "string").put("description", tr("vscreen 后面的部分，如 act \"tap 搜索; type 耳机\"", "What follows vscreen, e.g. act \"tap Search; type headphones\"")))
                .put("session", JSONObject().put("type", "string").put("description", tr("可选：会话名（副屏同一时间属于一个会话）", "Optional: session name (the screen belongs to one session at a time)"))))
            .put("required", JSONArray().put("command")))
        .put("annotations", McpProtocol.lineToolAnnotations())

    internal fun toolsList(): JSONArray = JSONArray(listOf(lineTool()) + typedTools())

    private fun typedTools(): List<JSONObject> = CommandSpec.commands.filterNot { it.hidden }.map { c ->
        JSONObject().put("name", PREFIX + c.name)
            .put("description", c.description)
            .put("inputSchema", CommandSpec.inputSchema(c))
            .put("annotations", McpProtocol.toolAnnotations(c))
    }

    /** Shared pre-auth check for /mcp used by both ApiServer and PublicServer. */
    fun preAuthCheck(r: ApiServer.Req): ApiServer.Resp? =
        if (r.path == "/mcp" && r.method != "POST") {
            ApiServer.Resp(405, "text/plain; charset=utf-8", "MCP: POST JSON-RPC".toByteArray(), mapOf("Allow" to "POST"))
        } else {
            null
        }

    /** Expose modern request detection for protocol tests. */
    internal fun isModern2026Request(r: ApiServer.Req, msg: JSONObject?): Boolean =
        McpProtocol.isModernRequest(r, msg)

    internal fun resolveToolArgv(p: JSONObject): List<String>? {
        val name = p.optString("name")
        val a = p.optJSONObject("arguments") ?: JSONObject()
        return when {
            name == "vscreen" -> ApiServer.splitCommand(a.optString("command").trim())
            name.startsWith(PREFIX) -> {
                val c = CommandSpec.of(name.removePrefix(PREFIX)) ?: return null
                CommandSpec.argvOf(c, a.opt("args")?.takeIf { it != JSONObject.NULL }?.toString(), a)
            }
            else -> null
        }
    }

    fun handle(server: ApiServer, r: ApiServer.Req): ApiServer.Resp {
        val body = r.bodyText.trim()
        if (body.startsWith("[")) {
            val arr = runCatching { JSONArray(body) }.getOrElse { return ApiServer.Resp.json(error(null, -32700, "parse error"), 400) }
            val anyModern = McpProtocol.isModernRequest(r, null) || (0 until arr.length()).any { McpProtocol.isModernRequest(r, arr.optJSONObject(it)) }
            if (anyModern) return McpModern.handleBatch(server, r, arr)
            val out = JSONArray()
            val afterSends = ArrayList<() -> Unit>()
            for (i in 0 until arr.length()) one(server, r, arr.optJSONObject(i), null) { fn -> fn?.let { afterSends += it } }?.let { out.put(it) }
            val after: (() -> Unit)? = if (afterSends.isEmpty()) null else ({ afterSends.forEach { quiet("mcp-after-send") { it() } } })
            return (if (out.length() == 0) ApiServer.Resp(202, "text/plain", ByteArray(0)) else ApiServer.Resp.json(out)).withAfterSend(after)
        }
        val msg = runCatching { JSONObject(body) }.getOrElse { return ApiServer.Resp.json(error(null, -32700, "parse error"), 400) }
        if (McpProtocol.isModernRequest(r, msg)) return McpModern.handleSingle(server, r, msg)

        // Legacy initialize-based MCP flow.
        val token = msg.optJSONObject("params")?.optJSONObject("_meta")?.opt("progressToken")
        if (msg.optString("method") == "tools/call" && token != null && token != JSONObject.NULL && r.accepts("text/event-stream")) return ApiServer.Resp.sse { em ->
            var n = 0
            var after: (() -> Unit)? = null
            val res = one(server, r, msg, { line ->
                quiet("mcp-progress") { em.send("message", JSONObject().put("jsonrpc", "2.0").put("method", "notifications/progress")
                    .put("params", JSONObject().put("progressToken", token).put("progress", ++n).put("message", line)).toString()) }
            }) { fn -> if (fn != null) after = fn }
            if (res != null) quiet("mcp-result") { em.send("message", res.toString()) }
            after?.let { quiet("mcp-sse-after-send") { it() } }
        }
        var after: (() -> Unit)? = null
        val res = one(server, r, msg, null) { fn -> if (fn != null) after = fn } ?: return ApiServer.Resp(202, "text/plain", ByteArray(0))
        val headers = if (msg.optString("method") == "initialize") mapOf("Mcp-Session-Id" to java.util.UUID.randomUUID().toString()) else emptyMap()
        return ApiServer.Resp.json(res, 200, headers).withAfterSend(after)
    }

    /** null for notifications (no response). */
    private fun one(server: ApiServer, r: ApiServer.Req, m: JSONObject?, onStep: ((String) -> Unit)?, onAfterSend: (((() -> Unit)?) -> Unit)? = null): JSONObject? {
        if (m == null) return error(null, -32600, "invalid request")
        val id = m.opt("id")
        val method = m.optString("method")
        if (id == null || id == JSONObject.NULL) return null
        val p = m.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> {
                val want = p.optString("protocolVersion")
                result(id, JSONObject()
                    .put("protocolVersion", if (want in VERSIONS) want else VERSIONS.first())
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "agent-vscreen").put("version", BuildConfig.VERSION_NAME))
                    .put("instructions", McpProtocol.serverInstructions()))
            }
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", toolsList()))
            "tools/call" -> {
                val argv = resolveToolArgv(p) ?: return error(id, -32602, "unknown tool ${p.optString("name")}")
                val a = p.optJSONObject("arguments") ?: JSONObject()
                val session = server.sessionOf(r, a)
                val reqId = p.optJSONObject("_meta")?.optString("requestId")?.trim()?.takeIf { it.isNotEmpty() }?.take(64)
                val (res, replayed) = server.runFor(r, argv, session, reqId, onStep)
                onAfterSend?.invoke(res.afterSend)
                result(id, toolResult(server, res, replayed, session))
            }
            "resources/list" -> result(id, JSONObject().put("resources", JSONArray()))
            "prompts/list" -> result(id, JSONObject().put("prompts", JSONArray()))
            else -> error(id, -32601, "method not found: $method")
        }
    }

    internal fun toolResult(server: ApiServer, res: NativeOffloadResult, replayed: Boolean, session: String?): JSONObject {
        val shots = ShotStore.refs(res.output)
        val sent = ImagePolicy.pick(res, shots)
        var text = res.output
        shots.forEachIndexed { _, n -> text = text.replace(ShotStore.REF + n, if (n in sent) tr("[图${sent.indexOf(n) + 1}，见附图]", "[picture ${sent.indexOf(n) + 1}, attached]") else ImagePolicy.leftOut()) }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        for (n in sent) {
            val f = ShotStore.file(server.ctxFiles(), ImagePolicy.sendName(server.ctxFiles(), res, n)) ?: continue
            content.put(JSONObject().put("type", "image").put("mimeType", "image/jpeg").put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)))
        }
        val structured = JSONObject()
        res.data?.let { d -> for (k in d.keys()) structured.put(k, d.get(k)) }
        structured.put("ok", res.exitCode == 0).put("code", res.codeOrDefault).put("exit", res.exitCode)
            .put("next", if (res.exitCode == 0) "none" else com.kilon.agentvscreen.cmd.Codes.next(res.codeOrDefault))
            .put("returned_at", res.data?.optLong("returned_at")?.takeIf { it > 0L } ?: System.currentTimeMillis())
        if (replayed) structured.put("replayed", true)
        val meter = com.kilon.agentvscreen.core.Meter.of(
            text,
            com.kilon.agentvscreen.core.Meter.sentDimensions(server.ctxFiles(), res, sent),
            structured = structured.toString(),
        )
        structured.put("meter", meter.json())
        return JSONObject().put("content", content).put("structuredContent", structured).put("isError", res.exitCode != 0)
    }

    private fun result(id: Any, r: JSONObject) = McpProtocol.jsonRpcResult(id, r)
    private fun error(id: Any?, code: Int, msg: String) = McpProtocol.jsonRpcError(id, code, msg)
}
