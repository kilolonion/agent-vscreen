package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.host.quiet
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stateless MCP `2026-07-28` handler (`server/discover`, `tools/list`, `tools/call`, `tasks/get`, `tasks/result`, `tasks/cancel`, `tasks/list`).
 *
 * - Never emits `Mcp-Session-Id`.
 * - Enforces SEP-2243 `Mcp-Method` / `Mcp-Name` matching (`-32020`) and modern protocol revision checks (`-32022`).
 * - Attaches `resultType: "complete"` (or `resultType: "task"` when opted into `io.modelcontextprotocol/tasks`) and
 *   cache hints (`ttlMs`, `cacheScope`) on discovery & tool-list responses.
 */
internal object McpModern {
    fun handleSingle(server: ApiServer, r: ApiServer.Req, msg: JSONObject): ApiServer.Resp {
        val id = msg.opt("id")
        McpProtocol.validateModern(r, msg, id)?.let { (status, errObj) ->
            return ApiServer.Resp.json(errObj, status)
        }
        val token = msg.optJSONObject("params")?.optJSONObject("_meta")?.opt("progressToken")
        if (msg.optString("method") == "tools/call" && token != null && token != JSONObject.NULL && r.accepts("text/event-stream")) {
            return ApiServer.Resp.sse { em ->
                var n = 0
                var after: (() -> Unit)? = null
                val res = dispatchOne(server, r, msg, { line ->
                    quiet("mcp-progress") {
                        em.send(
                            "message",
                            JSONObject()
                                .put("jsonrpc", "2.0")
                                .put("method", "notifications/progress")
                                .put("params", JSONObject().put("progressToken", token).put("progress", ++n).put("message", line))
                                .toString(),
                        )
                    }
                }) { fn -> if (fn != null) after = fn }
                if (res != null) quiet("mcp-result") { em.send("message", res.toString()) }
                after?.let { quiet("mcp-sse-after-send") { it() } }
            }
        }
        var after: (() -> Unit)? = null
        val res = dispatchOne(server, r, msg, null) { fn -> if (fn != null) after = fn }
            ?: return ApiServer.Resp(202, "text/plain", ByteArray(0))
        return ApiServer.Resp.json(res, 200).withAfterSend(after)
    }

    fun handleBatch(server: ApiServer, r: ApiServer.Req, arr: JSONArray): ApiServer.Resp {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            McpProtocol.validateModern(r, item, item.opt("id"))?.let { (status, errObj) ->
                return ApiServer.Resp.json(errObj, status)
            }
        }
        val out = JSONArray()
        val afterSends = ArrayList<() -> Unit>()
        for (i in 0 until arr.length()) {
            dispatchOne(server, r, arr.optJSONObject(i), null) { fn -> fn?.let { afterSends += it } }?.let { out.put(it) }
        }
        val after: (() -> Unit)? = if (afterSends.isEmpty()) null else ({ afterSends.forEach { quiet("mcp-after-send") { it() } } })
        return (if (out.length() == 0) ApiServer.Resp(202, "text/plain", ByteArray(0)) else ApiServer.Resp.json(out)).withAfterSend(after)
    }

    private fun dispatchOne(
        server: ApiServer,
        r: ApiServer.Req,
        m: JSONObject?,
        onStep: ((String) -> Unit)?,
        onAfterSend: (((() -> Unit)?) -> Unit)? = null,
    ): JSONObject? {
        if (m == null) return McpProtocol.jsonRpcError(null, -32600, "invalid request")
        val id = m.opt("id")
        val method = m.optString("method")
        if (id == null || id == JSONObject.NULL) return null
        val p = m.optJSONObject("params") ?: JSONObject()

        return when (method) {
            "server/discover" -> McpProtocol.jsonRpcResult(
                id,
                JSONObject()
                    .put("protocolVersions", JSONArray(McpProtocol.SUPPORTED_VERSIONS))
                    .put("serverInfo", JSONObject().put("name", "agent-vscreen").put("version", BuildConfig.VERSION_NAME))
                    .put("instructions", McpProtocol.serverInstructions())
                    .put(
                        "capabilities",
                        JSONObject()
                            .put("tools", JSONObject().put("listChanged", false))
                            .put("extensions", JSONObject().put(McpProtocol.TASKS_EXTENSION, JSONObject())),
                    )
                    .put("resultType", "complete")
                    .put("ttlMs", McpProtocol.CACHE_TTL_MS)
                    .put("cacheScope", McpProtocol.CACHE_SCOPE_PUBLIC),
            )
            "ping" -> McpProtocol.jsonRpcResult(id, JSONObject().put("resultType", "complete"))
            "tools/list" -> McpProtocol.jsonRpcResult(
                id,
                JSONObject()
                    .put("tools", Mcp.toolsList())
                    .put("resultType", "complete")
                    .put("ttlMs", McpProtocol.CACHE_TTL_MS)
                    .put("cacheScope", McpProtocol.CACHE_SCOPE_PUBLIC),
            )
            "tools/call" -> {
                val argv = Mcp.resolveToolArgv(p)
                    ?: return McpProtocol.jsonRpcError(id, -32602, "unknown tool ${p.optString("name")}")
                val a = p.optJSONObject("arguments") ?: JSONObject()
                val session = server.sessionOf(r, a)
                val reqId = p.optJSONObject("_meta")?.optString("requestId")?.trim()?.takeIf { it.isNotEmpty() }?.take(64)
                val (res, replayed) = server.runFor(r, argv, session, reqId, onStep)
                onAfterSend?.invoke(res.afterSend)
                if (McpProtocol.clientSupportsTasks(m)) {
                    McpTasks.maybeWrap(r, argv, session, res)?.let { taskWrap ->
                        return McpProtocol.jsonRpcResult(id, taskWrap)
                    }
                }
                val tr = Mcp.toolResult(server, res, replayed, session).put("resultType", "complete")
                McpProtocol.jsonRpcResult(id, tr)
            }
            "tasks/get" -> McpTasks.handleGet(server, r, id, p)
            "tasks/result" -> McpTasks.handleResult(server, r, id, p) { srv, res, rep, sess -> Mcp.toolResult(srv, res, rep, sess) }
            "tasks/cancel" -> McpTasks.handleCancel(server, r, id, p)
            "tasks/list" -> McpTasks.handleList(server, r, id)
            "tasks/update" -> McpProtocol.jsonRpcError(
                id,
                -32601,
                "method not found: tasks/update (on-phone confirmations cannot be updated over MCP)",
            )
            "resources/list" -> McpProtocol.jsonRpcResult(id, JSONObject().put("resources", JSONArray()).put("resultType", "complete"))
            "prompts/list" -> McpProtocol.jsonRpcResult(id, JSONObject().put("prompts", JSONArray()).put("resultType", "complete"))
            else -> McpProtocol.jsonRpcError(id, -32601, "method not found: $method")
        }
    }
}
