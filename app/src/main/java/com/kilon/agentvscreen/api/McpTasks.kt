package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.cmd.Tasks
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.StrongConfirm
import com.kilon.agentvscreen.host.UserAsk
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `io.modelcontextprotocol/tasks` bridge for MCP `2026-07-28`.
 *
 * Bridges four internal long-running / user-wait mechanisms under strict `(tokenId, taskId)` isolation:
 *  - `bg_<id>`: `--async` background tasks ([Tasks])
 *  - `ask_<id>`: phone user questions ([UserAsk]) — ALWAYS reported as `working` (never `input_required`)
 *  - `cfm_<id>`: strong confirmation ([StrongConfirm]) — ALWAYS reported as `working`; NEVER approves over MCP;
 *    after on-phone approval, replays the stored command at most once on a detached request
 *  - `keep_<name>`: background watch rules ([VScreenRules], `pollIntervalMs = 5000`)
 */
internal object McpTasks {
    const val PREFIX_BG = "bg_"
    const val PREFIX_ASK = "ask_"
    const val PREFIX_CFM = "cfm_"
    const val PREFIX_KEEP = "keep_"

    private const val MAX_BRIDGED = 64
    private const val TTL_MS = 30 * 60_000L
    private const val POLL_FAST_MS = 2_000L
    private const val POLL_KEEP_MS = 5_000L

    private class BridgedTask(
        val taskId: String,
        val kind: String, // "ask" | "cfm"
        val askId: Int,
        val confirmId: String?,
        val tokenId: String,
        val session: String,
        val argv: List<String>,
        val createdAt: Long,
        val statusMessage: String,
    ) {
        val lock = Object()
        val replaying = AtomicBoolean(false)
        @Volatile var cancelled: Boolean = false
        @Volatile var lastUpdatedAt: Long = createdAt
        @Volatile var finalResult: NativeOffloadResult? = null
        @Volatile var completedAt: Long = 0L
    }

    private val lock = Object()
    private val bridged = LinkedHashMap<String, BridgedTask>()

    internal fun resetForTest() = synchronized(lock) {
        bridged.clear()
    }

    private fun pruneLocked(now: Long) {
        val it = bridged.entries.iterator()
        while (it.hasNext()) {
            val t = it.next().value
            val refTime = if (t.completedAt > 0L) t.completedAt else t.createdAt
            if (now - refTime > TTL_MS) it.remove()
        }
        while (bridged.size > MAX_BRIDGED) {
            val firstKey = bridged.keys.firstOrNull() ?: break
            bridged.remove(firstKey)
        }
    }

    private fun isoTime(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }

    private fun taskDescriptor(
        taskId: String,
        status: String,
        statusMessage: String?,
        createdAt: Long,
        lastUpdatedAt: Long,
        pollIntervalMs: Long = POLL_FAST_MS,
    ): JSONObject = JSONObject()
        .put("taskId", taskId)
        .put("status", status)
        .put("createdAt", isoTime(createdAt))
        .put("lastUpdatedAt", isoTime(lastUpdatedAt))
        .put("ttl", TTL_MS)
        .put("pollIntervalMs", pollIntervalMs)
        .also { o -> if (!statusMessage.isNullOrBlank()) o.put("statusMessage", statusMessage) }

    /**
     * When a modern `tools/call` opted into `io.modelcontextprotocol/tasks` produces an async/pending/watch result,
     * wrap it into a `CreateTaskResult` (`resultType: "task"`). Returns `null` for immediate synchronous results.
     */
    fun maybeWrap(
        r: ApiServer.Req,
        argv: List<String>,
        session: String,
        res: NativeOffloadResult,
    ): JSONObject? {
        val tokenId = r.auth?.token?.id ?: return null
        val now = System.currentTimeMillis()
        val code = res.codeOrDefault
        val data = res.data
        val tellUser = data?.optJSONObject("user_action")?.optString("tell_user")?.takeIf { it.isNotBlank() }

        if (code == "TASK_STARTED") {
            val bgId = data?.optString("task")?.takeIf { it.isNotBlank() } ?: return null
            val tid = PREFIX_BG + bgId
            val msg = tr("后台任务已启动", "Background task started")
            return JSONObject()
                .put("resultType", "task")
                .put("task", taskDescriptor(tid, "working", msg, now, now, POLL_FAST_MS))
        }

        if (code == "ASK_PENDING") {
            val askId = data?.optJSONObject("ask")?.optInt("id", -1)?.takeIf { it > 0 } ?: return null
            val tid = PREFIX_ASK + askId
            val msg = tellUser ?: tr("正在等待用户在手机上回答问题", "Waiting for the user to answer on the phone")
            synchronized(lock) {
                pruneLocked(now)
                bridged[tid] = BridgedTask(tid, "ask", askId, null, tokenId, session, argv.toList(), now, msg)
                pruneLocked(now)
            }
            return JSONObject()
                .put("resultType", "task")
                .put("task", taskDescriptor(tid, "working", msg, now, now, POLL_FAST_MS))
        }

        if (code == "CONFIRM_PENDING" || code == "NEEDS_CONFIRM") {
            val cfmId = data?.optJSONObject("confirm")?.optString("id")?.takeIf { it.isNotBlank() } ?: return null
            val tid = PREFIX_CFM + cfmId
            val msg = tellUser ?: tr("正在等待用户在手机上完成确认", "Waiting for the user to confirm on the phone")
            synchronized(lock) {
                pruneLocked(now)
                bridged[tid] = BridgedTask(tid, "cfm", -1, cfmId, tokenId, session, argv.toList(), now, msg)
                pruneLocked(now)
            }
            return JSONObject()
                .put("resultType", "task")
                .put("task", taskDescriptor(tid, "working", msg, now, now, POLL_FAST_MS))
        }

        if (res.exitCode == 0) {
            val keepName = data?.optString("keep")?.takeIf { it.isNotBlank() }
            if (keepName != null) {
                val tid = PREFIX_KEEP + keepName
                val msg = tr("后台盯守「$keepName」已启动", "Background watch \"$keepName\" started")
                return JSONObject()
                    .put("resultType", "task")
                    .put("task", taskDescriptor(tid, "working", msg, now, now, POLL_KEEP_MS))
            }
        }
        return null
    }

    fun handleGet(server: ApiServer, r: ApiServer.Req, id: Any, params: JSONObject): JSONObject {
        val tokenId = r.auth?.token?.id ?: return McpProtocol.jsonRpcError(id, -32602, "missing auth")
        val taskId = params.optString("taskId").trim()
        if (taskId.isEmpty()) return McpProtocol.jsonRpcError(id, -32602, "missing taskId")
        val desc = describeTask(server, r, tokenId, taskId)
            ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
        return McpProtocol.jsonRpcResult(id, desc.put("resultType", "complete"))
    }

    fun handleResult(
        server: ApiServer,
        r: ApiServer.Req,
        id: Any,
        params: JSONObject,
        toToolResult: (ApiServer, NativeOffloadResult, Boolean, String?) -> JSONObject,
    ): JSONObject {
        val tokenId = r.auth?.token?.id ?: return McpProtocol.jsonRpcError(id, -32602, "missing auth")
        val taskId = params.optString("taskId").trim()
        if (taskId.isEmpty()) return McpProtocol.jsonRpcError(id, -32602, "missing taskId")

        return when {
            taskId.startsWith(PREFIX_BG) -> {
                val t = Tasks.findForToken(tokenId, taskId.removePrefix(PREFIX_BG))
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                val res = t.result
                    ?: return McpProtocol.jsonRpcError(id, -32602, "task $taskId is still working")
                val out = toToolResult(server, res, false, t.session).put("resultType", "complete")
                McpProtocol.jsonRpcResult(id, out)
            }
            taskId.startsWith(PREFIX_ASK) || taskId.startsWith(PREFIX_CFM) -> {
                val entry = findBridged(tokenId, taskId)
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                // Advance state / trigger continuation if approved and not started yet.
                advanceBridged(server, r, entry)
                if (entry.finalResult == null && entry.replaying.get()) {
                    synchronized(entry.lock) {
                        val deadline = System.currentTimeMillis() + 5_000L
                        while (entry.finalResult == null && System.currentTimeMillis() < deadline) {
                            entry.lock.wait(200L)
                        }
                    }
                }
                val res = entry.finalResult
                    ?: return McpProtocol.jsonRpcError(id, -32602, "task $taskId is still working")
                val out = toToolResult(server, res, false, entry.session).put("resultType", "complete")
                McpProtocol.jsonRpcResult(id, out)
            }
            taskId.startsWith(PREFIX_KEEP) -> {
                val name = taskId.removePrefix(PREFIX_KEEP)
                val live = VScreenRules.list().firstOrNull { it.spec.agentId == tokenId && it.spec.name == name }
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                if (!live.stopped) return McpProtocol.jsonRpcError(id, -32602, "task $taskId is still working")
                val summary = live.lastFire ?: live.endReason ?: live.status
                val keepRes = NativeOffloadResult(
                    exitCode = 0,
                    output = summary + "\n",
                    code = "OK",
                    data = live.json(),
                )
                val out = toToolResult(server, keepRes, false, live.spec.sid).put("resultType", "complete")
                McpProtocol.jsonRpcResult(id, out)
            }
            else -> McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
        }
    }

    fun handleCancel(server: ApiServer, r: ApiServer.Req, id: Any, params: JSONObject): JSONObject {
        val tokenId = r.auth?.token?.id ?: return McpProtocol.jsonRpcError(id, -32602, "missing auth")
        val taskId = params.optString("taskId").trim()
        if (taskId.isEmpty()) return McpProtocol.jsonRpcError(id, -32602, "missing taskId")
        val now = System.currentTimeMillis()

        return when {
            taskId.startsWith(PREFIX_BG) -> {
                val t = Tasks.cancelForToken(tokenId, taskId.removePrefix(PREFIX_BG))
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                val status = if (t.result == null) "cancelled" else if (t.state == "cancelled") "cancelled" else if (t.result?.exitCode == 0) "completed" else "failed"
                val desc = taskDescriptor(taskId, status, tr("已请求取消任务", "Task cancellation requested"), t.startedAt, now, POLL_FAST_MS)
                McpProtocol.jsonRpcResult(id, desc.put("resultType", "complete"))
            }
            taskId.startsWith(PREFIX_ASK) -> {
                val entry = findBridged(tokenId, taskId)
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                entry.cancelled = true
                entry.lastUpdatedAt = now
                if (entry.finalResult == null) {
                    entry.finalResult = NativeOffloadResult(1, tr("任务已取消\n", "Task cancelled\n"), "CANCELLED")
                    entry.completedAt = now
                }
                val ask = UserAsk.byId(entry.askId)
                if (ask != null && ask.token == tokenId && ask.answer == null && !UserAsk.taken(ask)) {
                    UserAsk.withdraw(server.ctxFiles(), ask)
                }
                val desc = taskDescriptor(taskId, "cancelled", tr("已取消提问任务", "Ask task cancelled"), entry.createdAt, now, POLL_FAST_MS)
                McpProtocol.jsonRpcResult(id, desc.put("resultType", "complete"))
            }
            taskId.startsWith(PREFIX_CFM) -> {
                val entry = findBridged(tokenId, taskId)
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                // Cancel only the MCP wait handle; never mutate or bypass strong-confirm policy.
                entry.cancelled = true
                entry.lastUpdatedAt = now
                if (entry.finalResult == null) {
                    entry.finalResult = NativeOffloadResult(1, tr("已取消等待确认\n", "Cancelled waiting for confirmation\n"), "CANCELLED")
                    entry.completedAt = now
                }
                val desc = taskDescriptor(taskId, "cancelled", tr("已取消确认等待句柄", "Confirmation wait handle cancelled"), entry.createdAt, now, POLL_FAST_MS)
                McpProtocol.jsonRpcResult(id, desc.put("resultType", "complete"))
            }
            taskId.startsWith(PREFIX_KEEP) -> {
                val name = taskId.removePrefix(PREFIX_KEEP)
                val live = VScreenRules.list().firstOrNull { it.spec.agentId == tokenId && it.spec.name == name }
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                if (!live.stopped) VScreenRules.stop(tokenId, name)
                val desc = taskDescriptor(taskId, "cancelled", tr("已停止盯守", "Watch stopped"), live.startedAt, now, POLL_KEEP_MS)
                McpProtocol.jsonRpcResult(id, desc.put("resultType", "complete"))
            }
            else -> McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
        }
    }

    fun handleList(server: ApiServer, r: ApiServer.Req, id: Any): JSONObject {
        val tokenId = r.auth?.token?.id ?: return McpProtocol.jsonRpcError(id, -32602, "missing auth")
        val arr = JSONArray()
        for (t in Tasks.listForToken(tokenId)) {
            describeBg(t)?.let { arr.put(it) }
        }
        val entries = synchronized(lock) {
            pruneLocked(System.currentTimeMillis())
            bridged.values.filter { it.tokenId == tokenId }.toList()
        }
        for (e in entries) {
            arr.put(advanceBridged(server, r, e))
        }
        for (live in VScreenRules.list().filter { it.spec.agentId == tokenId }) {
            arr.put(describeKeep(live))
        }
        return McpProtocol.jsonRpcResult(
            id,
            JSONObject()
                .put("tasks", arr)
                .put("resultType", "complete"),
        )
    }

    private fun findBridged(tokenId: String, taskId: String): BridgedTask? = synchronized(lock) {
        pruneLocked(System.currentTimeMillis())
        bridged[taskId]?.takeIf { it.tokenId == tokenId }
    }

    private fun describeTask(server: ApiServer, r: ApiServer.Req, tokenId: String, taskId: String): JSONObject? = when {
        taskId.startsWith(PREFIX_BG) ->
            Tasks.findForToken(tokenId, taskId.removePrefix(PREFIX_BG))?.let { describeBg(it) }
        taskId.startsWith(PREFIX_ASK) || taskId.startsWith(PREFIX_CFM) ->
            findBridged(tokenId, taskId)?.let { advanceBridged(server, r, it) }
        taskId.startsWith(PREFIX_KEEP) ->
            VScreenRules.list().firstOrNull { it.spec.agentId == tokenId && it.spec.name == taskId.removePrefix(PREFIX_KEEP) }
                ?.let { describeKeep(it) }
        else -> null
    }

    private fun describeBg(t: Tasks.Task): JSONObject {
        val tid = PREFIX_BG + t.id
        val res = t.result
        val updated = if (t.endedAt > 0L) t.endedAt else System.currentTimeMillis()
        val (status, msg) = when {
            res == null -> "working" to (t.call.lastProgress ?: tr("后台任务执行中", "Background task running"))
            t.state == "cancelled" -> "cancelled" to tr("任务已取消", "Task cancelled")
            res.exitCode == 0 -> "completed" to tr("任务已完成", "Task completed")
            else -> "failed" to (res.codeOrDefault)
        }
        return taskDescriptor(tid, status, msg, t.startedAt, updated, POLL_FAST_MS)
    }

    private fun describeKeep(live: VScreenRules.Live): JSONObject {
        val tid = PREFIX_KEEP + live.spec.name
        val updated = if (live.endedAt > 0L) live.endedAt else System.currentTimeMillis()
        val status = if (!live.stopped) "working" else "completed"
        val msg = if (!live.stopped) live.status else (live.lastFire ?: live.endReason ?: live.status)
        return taskDescriptor(tid, status, msg, live.startedAt, updated, POLL_KEEP_MS)
    }

    private fun advanceBridged(server: ApiServer, r: ApiServer.Req, entry: BridgedTask): JSONObject {
        val now = System.currentTimeMillis()
        if (entry.cancelled) {
            return taskDescriptor(entry.taskId, "cancelled", tr("任务已取消", "Task cancelled"), entry.createdAt, entry.lastUpdatedAt, POLL_FAST_MS)
        }
        entry.finalResult?.let { done ->
            val status = if (done.exitCode == 0) "completed" else "failed"
            val msg = if (done.exitCode == 0) tr("已完成", "Completed") else done.codeOrDefault
            return taskDescriptor(entry.taskId, status, msg, entry.createdAt, entry.lastUpdatedAt, POLL_FAST_MS)
        }
        if (entry.replaying.get()) {
            return taskDescriptor(entry.taskId, "working", tr("已批准，正在继续执行原命令", "Approved; resuming original command"), entry.createdAt, now, POLL_FAST_MS)
        }

        if (entry.kind == "ask") {
            val ask = UserAsk.byId(entry.askId)?.takeIf { it.token == entry.tokenId }
            if (ask == null) {
                entry.finalResult = NativeOffloadResult(1, tr("提问已过期\n", "Question expired\n"), "ASK_PENDING")
                entry.completedAt = now
                entry.lastUpdatedAt = now
                return taskDescriptor(entry.taskId, "failed", tr("提问已过期", "Question expired"), entry.createdAt, now, POLL_FAST_MS)
            }
            return when (ask.answer) {
                null -> taskDescriptor(entry.taskId, "working", entry.statusMessage, entry.createdAt, now, POLL_FAST_MS)
                false -> {
                    entry.finalResult = NativeOffloadResult(1, tr("用户未回答该问题\n", "The user declined to answer\n"), "ASK_DECLINED")
                    entry.completedAt = now
                    entry.lastUpdatedAt = now
                    taskDescriptor(entry.taskId, "failed", tr("用户未回答", "User declined"), entry.createdAt, now, POLL_FAST_MS)
                }
                true -> {
                    startReplayOnce(server, r, entry)
                    taskDescriptor(entry.taskId, "working", tr("用户已回答，正在汇总结果", "User answered; collecting result"), entry.createdAt, now, POLL_FAST_MS)
                }
            }
        }

        // cfm_<id>
        val req = StrongConfirm.byId(entry.confirmId)?.takeIf { it.tokenId == entry.tokenId }
        if (req == null) {
            entry.finalResult = NativeOffloadResult(1, tr("强确认已过期\n", "Confirmation expired\n"), "CONFIRM_PENDING")
            entry.completedAt = now
            entry.lastUpdatedAt = now
            return taskDescriptor(entry.taskId, "failed", tr("强确认已过期", "Confirmation expired"), entry.createdAt, now, POLL_FAST_MS)
        }
        return when (req.answer) {
            null -> taskDescriptor(entry.taskId, "working", entry.statusMessage, entry.createdAt, now, POLL_FAST_MS)
            false -> {
                entry.finalResult = NativeOffloadResult(1, tr("用户在手机上拒绝了确认\n", "The user denied confirmation on the phone\n"), "USER_DENIED")
                entry.completedAt = now
                entry.lastUpdatedAt = now
                taskDescriptor(entry.taskId, "failed", tr("用户已拒绝确认", "User denied confirmation"), entry.createdAt, now, POLL_FAST_MS)
            }
            true -> {
                startReplayOnce(server, r, entry)
                taskDescriptor(entry.taskId, "working", tr("用户已批准，正在继续执行原命令", "User approved; resuming original command"), entry.createdAt, now, POLL_FAST_MS)
            }
        }
    }

    private fun startReplayOnce(server: ApiServer, pollReq: ApiServer.Req, entry: BridgedTask) {
        if (!entry.replaying.compareAndSet(false, true)) return
        val detached = detachedReq(pollReq)
        Thread({
            val res = try {
                server.runFor(detached, entry.argv, entry.session, requestId = null, onStep = null).first
            } catch (t: Throwable) {
                AppLogger.warning("McpTasks", "continuation replay failed for ${entry.taskId}: ${t.message}")
                NativeOffloadResult(1, tr("继续执行失败\n", "Continuation failed\n"), "INTERNAL_ERROR")
            }
            val doneAt = System.currentTimeMillis()
            entry.finalResult = res
            entry.completedAt = doneAt
            entry.lastUpdatedAt = doneAt
            synchronized(entry.lock) { entry.lock.notifyAll() }
        }, "vscreen-mcp-task").apply { isDaemon = true; start() }
    }

    /** Copy authenticated caller context without the short-lived polling socket so background replay isn't cancelled. */
    private fun detachedReq(r: ApiServer.Req): ApiServer.Req =
        ApiServer.Req(r.method, r.target, r.query, r.headers, ByteArray(0)).apply {
            peer = r.peer
            auth = r.auth
            viaPublic = r.viaPublic
            publicClientId = r.publicClientId
            publicScope = r.publicScope
            sock = null
        }
}
