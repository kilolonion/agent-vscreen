package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.cmd.CommandSpec
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared protocol constants, version detection, SEP-2243 header validation, capability helpers,
 * and tool annotations for dual-stack MCP (`2026-07-28` stateless + legacy `initialize` revisions).
 */
internal object McpProtocol {
    const val MODERN_VERSION = "2026-07-28"
    val LEGACY_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
    val SUPPORTED_VERSIONS = listOf(MODERN_VERSION) + LEGACY_VERSIONS

    const val TASKS_EXTENSION = "io.modelcontextprotocol/tasks"
    const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
    const val META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
    const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"

    /** SEP-2243: HTTP routing header (`Mcp-Method` / `Mcp-Name`) does not match JSON-RPC body. */
    const val ERR_HEADER_MISMATCH = -32020
    /** MCP 2026-07-28: client did not declare a required extension capability (`data.requiredCapabilities`). */
    const val ERR_MISSING_CAPABILITY = -32021
    /** Stateless modern protocol version error with `data: {supported, requested}`. */
    const val ERR_UNSUPPORTED_PROTOCOL = -32022

    private val TASK_ID_METHODS = setOf("tasks/get", "tasks/update", "tasks/cancel", "tasks/result")

    const val CACHE_TTL_MS = 3_600_000L
    const val CACHE_SCOPE_PUBLIC = "public"

    private val DESTRUCTIVE_COMMANDS = setOf("act", "stop", "release", "handoff", "note")
    private val IDEMPOTENT_WRITE_COMMANDS = setOf("title", "stop", "release", "fix")
    private val OPEN_WORLD_COMMANDS = setOf("launch", "act", "collect", "teach", "handoff")

    /** Returns true for date-based protocol revisions from 2026 onward (`YYYY-MM-DD`). */
    fun isModernRevision(ver: String?): Boolean {
        val v = ver?.trim() ?: return false
        if (v.length < 10 || v[4] != '-' || v[7] != '-') return false
        val year = v.substring(0, 4).toIntOrNull() ?: return false
        return year >= 2026
    }

    /** True when the HTTP request or JSON-RPC message targets the stateless `2026-07-28` stack. */
    fun isModernRequest(r: ApiServer.Req, msg: JSONObject?): Boolean {
        if (isModernRevision(r.headers["mcp-protocol-version"])) return true
        if (r.headers.containsKey("mcp-method") || r.headers.containsKey("mcp-name")) return true
        val method = msg?.optString("method")
        if (method == "server/discover" || method?.startsWith("tasks/") == true) return true
        val metaVer = msg?.optJSONObject("params")?.optJSONObject("_meta")?.optString(META_PROTOCOL_VERSION)
        if (isModernRevision(metaVer)) return true
        return false
    }

    /**
     * Validate stateless modern protocol version and SEP-2243 headers (`Mcp-Method`, `Mcp-Name`).
     * Returns `(httpStatus, jsonRpcError)` on mismatch/unsupported version, or `null` when valid.
     */
    fun validateModern(r: ApiServer.Req, msg: JSONObject, id: Any?): Pair<Int, JSONObject>? {
        val headerVer = r.headers["mcp-protocol-version"]?.trim()?.takeIf { it.isNotEmpty() }
        val metaVer = msg.optJSONObject("params")?.optJSONObject("_meta")
            ?.optString(META_PROTOCOL_VERSION)?.trim()?.takeIf { it.isNotEmpty() }
        if (headerVer != null && metaVer != null && headerVer != metaVer) {
            return 400 to jsonRpcError(
                id,
                ERR_HEADER_MISMATCH,
                "HeaderMismatch: MCP-Protocol-Version ($headerVer) conflicts with _meta protocolVersion ($metaVer)",
            )
        }
        val requestedVer = headerVer ?: metaVer
        if (requestedVer != null && requestedVer != MODERN_VERSION) {
            return 400 to jsonRpcError(
                id,
                ERR_UNSUPPORTED_PROTOCOL,
                "UnsupportedProtocolVersionError: $requestedVer",
                JSONObject()
                    .put("supported", JSONArray(SUPPORTED_VERSIONS))
                    .put("requested", requestedVer),
            )
        }
        val bodyMethod = msg.optString("method")
        if (r.headers.containsKey("mcp-method")) {
            val headerMethod = r.headers["mcp-method"]
            if (headerMethod != bodyMethod) {
                return 400 to jsonRpcError(
                    id,
                    ERR_HEADER_MISMATCH,
                    "HeaderMismatch: Mcp-Method does not match JSON-RPC method",
                )
            }
        }
        if (r.headers.containsKey("mcp-name")) {
            val headerName = r.headers["mcp-name"]
            val params = msg.optJSONObject("params")
            val expectedName = when {
                bodyMethod in TASK_ID_METHODS -> params?.optString("taskId")?.trim()?.takeIf { it.isNotEmpty() }
                bodyMethod == "tasks/list" -> null
                else -> params?.optString("name")?.takeIf { it.isNotEmpty() }
            }
            if (headerName != expectedName) {
                val targetField = if (bodyMethod in TASK_ID_METHODS) "params.taskId" else "params.name"
                return 400 to jsonRpcError(
                    id,
                    ERR_HEADER_MISMATCH,
                    "HeaderMismatch: Mcp-Name does not match $targetField",
                )
            }
        }
        if (bodyMethod.startsWith("tasks/") && !clientSupportsTasks(msg)) {
            return missingTasksCapabilityError(id)
        }
        return null
    }

    fun missingTasksCapabilityError(id: Any?): Pair<Int, JSONObject> =
        400 to jsonRpcError(
            id,
            ERR_MISSING_CAPABILITY,
            "Missing required client capability: $TASKS_EXTENSION",
            JSONObject().put(
                "requiredCapabilities",
                JSONObject().put("extensions", JSONObject().put(TASKS_EXTENSION, JSONObject())),
            ),
        )

    /**
     * Opt-in gate for `io.modelcontextprotocol/tasks`: only returns true when the request's
     * `_meta["io.modelcontextprotocol/clientCapabilities"].extensions` explicitly declares
     * `"io.modelcontextprotocol/tasks"`.
     */
    fun clientSupportsTasks(msg: JSONObject?): Boolean {
        val ext = msg?.optJSONObject("params")
            ?.optJSONObject("_meta")
            ?.optJSONObject(META_CLIENT_CAPABILITIES)
            ?.optJSONObject("extensions")
            ?: return false
        return ext.has(TASKS_EXTENSION) && ext.opt(TASKS_EXTENSION) != JSONObject.NULL
    }

    fun toolAnnotations(c: CommandSpec.Cmd): JSONObject = JSONObject()
        .put("readOnlyHint", c.readOnly)
        .put("destructiveHint", !c.readOnly && c.name in DESTRUCTIVE_COMMANDS)
        .put("idempotentHint", c.readOnly || c.name in IDEMPOTENT_WRITE_COMMANDS)
        .put("openWorldHint", c.name in OPEN_WORLD_COMMANDS)

    fun lineToolAnnotations(): JSONObject = JSONObject()
        .put("readOnlyHint", false)
        .put("destructiveHint", true)
        .put("idempotentHint", false)
        .put("openWorldHint", true)

    fun serverInstructions(): String = tr(
        "Agent-VScreen：这台手机上的后台副屏。要打开、查看或操作手机里的应用时用这里的工具，它在看不见的副屏上操作，不打扰用户主屏，不需要 adb。\n\n",
        "Agent-VScreen: a background screen on this phone. To open, view or operate apps on the phone use these tools; they work on an invisible second screen, never disturb the user's screen, and need no adb.\n\n",
    ) + AgentGuide.guide().replace("`vscreen ", "`")

    fun jsonRpcResult(id: Any, r: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", r)

    fun jsonRpcError(id: Any?, code: Int, msg: String, data: JSONObject? = null): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put(
                "error",
                JSONObject().put("code", code).put("message", msg).also { e ->
                    if (data != null) e.put("data", data)
                },
            )
}
