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
 * Model Context Protocol over streamable HTTP.
 *
 * 1.2:
 *  - Typed tools. Besides `vscreen` (one command line, as before) every command is its own tool
 *    (`vscreen_act`, `vscreen_launch`, …) whose input schema comes from the command table: the model fills fields
 *    instead of composing a shell line and escaping quotes.
 *  - Structured results. `structuredContent` carries the typed result (outcome, steps, screen with elements, notes);
 *    `isError` follows the result code, not a search of the text.
 *  - Progress. When the caller sends a progress token and accepts an event stream, each finished step is sent as a
 *    progress notification while a long stretch runs, so the caller's timeout does not fire on a working phone.
 *  - Cancellation: closing the request's connection stops the stretch before its next step.
 */
internal object Mcp {
    private val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
    private const val PREFIX = "vscreen_"

    private fun lineTool() = JSONObject()
        .put("name", "vscreen")
        .put("description", tr("在看不见的副屏里操作手机上的应用（不占用户主屏）。参数 command 是一条 vscreen 命令行，例如 " +
            "`launch 设置`、`act \"tap 搜索; type 耳机; key enter until text:综合\"`、`act`（看一眼）、`help act`、`help codes`。" +
            "也可以用按命令分开的工具（vscreen_act、vscreen_launch …），参数是字段，不用拼命令行。\n\n",
            "Operates apps on the phone on an invisible second screen (the user's screen is not used). The command parameter is one vscreen command line, e.g. " +
            "`launch Settings`, `act \"tap Search; type headphones; key enter until text:Results\"`, `act` (look), `help act`, `help codes`. " +
            "The per-command tools (vscreen_act, vscreen_launch …) take fields instead of a command line.\n\n") + AgentGuide.loop())
        .put("inputSchema", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("command", JSONObject().put("type", "string").put("description", tr("vscreen 后面的部分，如 act \"tap 搜索; type 耳机\"", "What follows vscreen, e.g. act \"tap Search; type headphones\"")))
                .put("session", JSONObject().put("type", "string").put("description", tr("可选：会话名（副屏同一时间属于一个会话）", "Optional: session name (the screen belongs to one session at a time)"))))
            .put("required", JSONArray().put("command")))

    private fun typedTools(): List<JSONObject> = CommandSpec.commands.map { c ->
        JSONObject().put("name", PREFIX + c.name)
            .put("description", c.summary + "\n" + c.body.joinToString("\n"))
            .put("inputSchema", CommandSpec.inputSchema(c))
            .put("annotations", JSONObject().put("readOnlyHint", c.scope == CommandSpec.Scope.LOOK))
    }

    fun handle(server: ApiServer, r: ApiServer.Req): ApiServer.Resp {
        val body = r.bodyText.trim()
        if (body.startsWith("[")) {
            val arr = runCatching { JSONArray(body) }.getOrElse { return ApiServer.Resp.json(error(null, -32700, "parse error"), 400) }
            val out = JSONArray()
            for (i in 0 until arr.length()) one(server, r, arr.optJSONObject(i), null)?.let { out.put(it) }
            return if (out.length() == 0) ApiServer.Resp(202, "text/plain", ByteArray(0)) else ApiServer.Resp.json(out)
        }
        val msg = runCatching { JSONObject(body) }.getOrElse { return ApiServer.Resp.json(error(null, -32700, "parse error"), 400) }
        // A tool call that asked for progress, from a caller that reads an event stream: stream it.
        val token = msg.optJSONObject("params")?.optJSONObject("_meta")?.opt("progressToken")
        if (msg.optString("method") == "tools/call" && token != null && token != JSONObject.NULL && r.accepts("text/event-stream")) return ApiServer.Resp.sse { em ->
            var n = 0
            val res = one(server, r, msg) { line ->
                quiet("mcp-progress") { em.send("message", JSONObject().put("jsonrpc", "2.0").put("method", "notifications/progress")
                    .put("params", JSONObject().put("progressToken", token).put("progress", ++n).put("message", line)).toString()) }
            }
            if (res != null) quiet("mcp-result") { em.send("message", res.toString()) }
        }
        val res = one(server, r, msg, null) ?: return ApiServer.Resp(202, "text/plain", ByteArray(0))
        val headers = if (msg.optString("method") == "initialize") mapOf("Mcp-Session-Id" to java.util.UUID.randomUUID().toString()) else emptyMap()
        return ApiServer.Resp.json(res, 200, headers)
    }

    /** null for notifications (no response). */
    private fun one(server: ApiServer, r: ApiServer.Req, m: JSONObject?, onStep: ((String) -> Unit)?): JSONObject? {
        if (m == null) return error(null, -32600, "invalid request")
        val id = m.opt("id")
        val method = m.optString("method")
        if (id == null || id == JSONObject.NULL) return null // notification / response
        val p = m.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> {
                val want = p.optString("protocolVersion")
                result(id, JSONObject()
                    .put("protocolVersion", if (want in VERSIONS) want else VERSIONS.first())
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "agent-vscreen").put("version", BuildConfig.VERSION_NAME))
                    // The short guide, so every new conversation of an MCP agent knows how to use us without a saved note.
                    .put("instructions", tr("Agent-VScreen：这台手机上的后台副屏。要打开、查看或操作手机里的应用时用这里的工具，它在看不见的副屏上操作，不打扰用户主屏，不需要 adb。\n\n",
                        "Agent-VScreen: a background screen on this phone. To open, view or operate apps on the phone use these tools; they work on an invisible second screen, never disturb the user's screen, and need no adb.\n\n") + AgentGuide.guide().replace("`vscreen ", "`")))
            }
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", JSONArray(listOf(lineTool()) + typedTools())))
            "tools/call" -> {
                val name = p.optString("name")
                val a = p.optJSONObject("arguments") ?: JSONObject()
                val argv: List<String> = when {
                    name == "vscreen" -> ApiServer.splitCommand(a.optString("command").trim())
                    name.startsWith(PREFIX) -> {
                        val c = CommandSpec.of(name.removePrefix(PREFIX)) ?: return error(id, -32602, "unknown tool $name")
                        CommandSpec.argvOf(c, a.opt("args")?.takeIf { it != JSONObject.NULL }?.toString(), a)
                    }
                    else -> return error(id, -32602, "unknown tool $name")
                }
                val session = server.sessionOf(r, a)
                val (res, replayed) = server.runFor(r, argv, session, p.optJSONObject("_meta")?.optString("requestId")?.takeIf { it.isNotBlank() }, onStep)
                result(id, toolResult(server, res, replayed))
            }
            "resources/list" -> result(id, JSONObject().put("resources", JSONArray()))
            "prompts/list" -> result(id, JSONObject().put("prompts", JSONArray()))
            else -> error(id, -32601, "method not found: $method")
        }
    }

    private fun toolResult(server: ApiServer, res: NativeOffloadResult, replayed: Boolean): JSONObject {
        val shots = ShotStore.refs(res.output)
        var text = res.output
        shots.forEachIndexed { i, n -> text = text.replace(ShotStore.REF + n, tr("[图${i + 1}，见附图]", "[picture ${i + 1}, attached]")) }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        for (n in shots.take(4)) {
            val f = ShotStore.file(server.ctxFiles(), n) ?: continue
            content.put(JSONObject().put("type", "image").put("mimeType", "image/jpeg").put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)))
        }
        val structured = JSONObject()
        res.data?.let { d -> for (k in d.keys()) structured.put(k, d.get(k)) }
        structured.put("ok", res.exitCode == 0).put("code", res.codeOrDefault).put("exit", res.exitCode)
            .put("next", if (res.exitCode == 0) "none" else com.kilon.agentvscreen.cmd.Codes.next(res.codeOrDefault))
        if (replayed) structured.put("replayed", true)
        return JSONObject().put("content", content).put("structuredContent", structured).put("isError", res.exitCode != 0)
    }

    private fun result(id: Any, r: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", r)
    private fun error(id: Any?, code: Int, msg: String) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", msg))
}
