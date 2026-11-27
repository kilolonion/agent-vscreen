package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.cmd.CommandSpec
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.cmd.OffloadOutput
import com.kilon.agentvscreen.cmd.Tasks
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.core.VScreenShell
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

/**
 * `io.modelcontextprotocol/tasks` (`2026-07-28`) bridge.
 *
 * Bridges four internal long-running / user-wait mechanisms under strict `(ownerKey, tokenId, taskId)` isolation
 * (where `ownerKey = publicGrantId ?: tokenId`, so independent CIMD pairings never share task visibility):
 *  - `bg_<id>`: `--async` background tasks ([Tasks])
 *  - `ask_<id>`: phone user questions ([UserAsk]) — ALWAYS reported as `working` (never `input_required`)
 *  - `cfm_<id>`: strong confirmation ([StrongConfirm]) — ALWAYS reported as `working`; NEVER approves over MCP;
 *    after on-phone approval, continues ONLY from the recorded safe continuation point (or stops cleanly with
 *    completed steps if safe continuation is unavailable), at most once, under a unified atomic state machine
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

    internal enum class Phase {
        WAITING_PHONE,
        REPLAYING,
        COMPLETED,
        FAILED,
        CANCELLED,
    }

    internal data class ContinuationPlan(
        val priorDoneCount: Int,
        val safeResume: Boolean,
        val resumeArgv: List<String>?,
        val expectedGeneration: Long?,
        val expectedActivity: String?,
    )

    private class BridgedTask(
        val taskId: String,
        val kind: String, // "ask" | "cfm"
        val askId: Int,
        val confirmId: String?,
        val ownerKey: String,
        val tokenId: String,
        val session: String,
        val initialResult: NativeOffloadResult,
        val continuation: ContinuationPlan,
        val createdAt: Long,
        val waitStatusMessage: String,
    ) {
        val lock = Object()
        var phase: Phase = Phase.WAITING_PHONE
        var cancelRequested: Boolean = false
        var activeCall: Call? = null
        var statusMessage: String = waitStatusMessage
        var lastUpdatedAt: Long = createdAt
        var finalResult: NativeOffloadResult? = null
        var protocolError: JSONObject? = null
        var completedAt: Long = 0L

        fun isTerminalLocked(): Boolean =
            phase == Phase.COMPLETED || phase == Phase.FAILED || phase == Phase.CANCELLED

        fun wireStatusLocked(): String = when (phase) {
            Phase.WAITING_PHONE, Phase.REPLAYING -> "working"
            Phase.COMPLETED -> "completed"
            Phase.FAILED -> "failed"
            Phase.CANCELLED -> "cancelled"
        }
    }

    private data class Snapshot(
        val phase: Phase,
        val wireStatus: String,
        val statusMessage: String?,
        val createdAt: Long,
        val lastUpdatedAt: Long,
        val finalResult: NativeOffloadResult?,
        val protocolError: JSONObject?,
    )

    private val lock = Object()
    private val bridged = LinkedHashMap<String, BridgedTask>()
    private val cancelledKeeps = HashSet<String>()

    /**
     * Testable page/session binding verifier run right before continuing a multi-step `act` after confirmation.
     * Returns `null` when the session display & foreground activity still match, or a short mismatch description.
     */
    @Volatile
    internal var continuationPageVerifier: (session: String, expectedGen: Long?, expectedAct: String?) -> String? =
        ::defaultVerifyContinuationPage

    internal fun resetForTest() = synchronized(lock) {
        bridged.clear()
        cancelledKeeps.clear()
        continuationPageVerifier = ::defaultVerifyContinuationPage
    }

    private fun defaultVerifyContinuationPage(session: String, expectedGen: Long?, expectedAct: String?): String? {
        if (expectedGen == null && expectedAct == null) return null
        val st = BackgroundOperation.state.value
        val d = BackgroundOperation.currentDisplay()
        if (d == null || st.ownerSessionId != session) {
            return tr("原会话副屏已关闭或已易主", "session display is no longer open or changed owner")
        }
        if (expectedGen != null && d.generation != expectedGen) {
            return tr("副屏已重建（页态已变）", "virtual display was rebuilt")
        }
        if (expectedAct != null) {
            val actNow = runCatching { VScreenShell.topActivity(d.displayId) }.getOrNull()
            if (actNow != null && actNow != expectedAct) {
                return tr("当前页面已从 $expectedAct 切换为 $actNow", "foreground page changed from $expectedAct to $actNow")
            }
        }
        return null
    }

    private fun ownerKeyOf(r: ApiServer.Req): String? =
        r.publicGrantId?.takeIf { it.isNotBlank() } ?: r.auth?.token?.id

    private fun pruneLocked(now: Long) {
        val it = bridged.entries.iterator()
        while (it.hasNext()) {
            val t = it.next().value
            val refTime = synchronized(t.lock) { if (t.completedAt > 0L) t.completedAt else t.createdAt }
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
        .put("ttlMs", TTL_MS)
        .put("pollIntervalMs", pollIntervalMs)
        .also { o -> if (!statusMessage.isNullOrBlank()) o.put("statusMessage", statusMessage) }

    /**
     * Rewrite an `act` argv so continuation executes ONLY [resumeSteps] instead of replaying earlier completed steps.
     * Drops `--save` when [priorDoneCount] > 0 so a partial tail is never saved as the full segment, and injects
     * `--bare` when [addBare] is true so app-level `before` steps do not run a second time.
     */
    internal fun rewriteActArgvForContinuation(
        originalArgv: List<String>,
        resumeSteps: String,
        addBare: Boolean,
        priorDoneCount: Int,
    ): List<String> {
        if (originalArgv.firstOrNull() != "act") return originalArgv.toList()
        val actSpec = CommandSpec.of("act")
        val switchNames = buildSet<String> {
            addAll(OffloadOutput.OUTPUT_FLAGS)
            actSpec?.allOpts?.filter { it.kind == CommandSpec.Kind.SWITCH }?.forEach { opt ->
                add(opt.name)
            }
        }
        val valueNames = buildSet<String> {
            actSpec?.allOpts?.filter { it.kind != CommandSpec.Kind.SWITCH }?.forEach { opt ->
                add(opt.name)
            }
        }
        val out = ArrayList<String>(originalArgv.size + 2)
        out += "act"
        var hasBare = false
        var i = 1
        while (i < originalArgv.size) {
            val tok = originalArgv[i]
            if (tok == "--") break
            if (tok.startsWith("--") && tok.length > 2) {
                val eq = tok.indexOf('=')
                val name = if (eq > 0) tok.substring(2, eq) else tok.substring(2)
                if (name == "bare" || name == "paused") hasBare = true
                if (name == "save" && priorDoneCount > 0) {
                    if (eq < 0 && i + 1 < originalArgv.size) i++
                    i++
                    continue
                }
                if (name in switchNames) {
                    out += tok
                    i++
                    continue
                }
                if (name in valueNames) {
                    out += tok
                    if (eq < 0 && i + 1 < originalArgv.size) {
                        i++
                        out += originalArgv[i]
                    }
                    i++
                    continue
                }
            }
            // Positional step arguments are replaced by resumeSteps.
            i++
        }
        if (addBare && !hasBare) out += "--bare"
        out += resumeSteps
        return out
    }

    private fun parseContinuationPlan(argv: List<String>, res: NativeOffloadResult): ContinuationPlan {
        val cont = res.data?.optJSONObject("continuation")
            ?: return ContinuationPlan(
                priorDoneCount = 0,
                safeResume = true,
                resumeArgv = argv.toList(),
                expectedGeneration = null,
                expectedActivity = null,
            )
        val priorDone = cont.optInt("prior_steps_done", 0).coerceAtLeast(0)
        val safeFlag = cont.optBoolean("safe_resume", false)
        val resumeSteps = cont.optString("resume_steps").takeIf { it.isNotBlank() && !cont.isNull("resume_steps") }
        val addBare = cont.optBoolean("add_bare", false)
        val expectedGen = if (cont.has("display_generation") && !cont.isNull("display_generation")) {
            cont.optLong("display_generation", -1L).takeIf { it >= 0L }
        } else null
        val expectedAct = cont.optString("activity").takeIf { it.isNotBlank() && !cont.isNull("activity") }

        if (!safeFlag) {
            return ContinuationPlan(priorDone, safeResume = false, resumeArgv = null, expectedGen, expectedAct)
        }
        if (argv.firstOrNull() == "act") {
            if (resumeSteps != null) {
                val rewritten = rewriteActArgvForContinuation(argv, resumeSteps, addBare, priorDone)
                return ContinuationPlan(priorDone, safeResume = true, resumeArgv = rewritten, expectedGen, expectedAct)
            }
            if (priorDone > 0) {
                // Never fall back to full original argv once prior steps already produced side effects.
                return ContinuationPlan(priorDone, safeResume = false, resumeArgv = null, expectedGen, expectedAct)
            }
        }
        return ContinuationPlan(priorDone, safeResume = true, resumeArgv = argv.toList(), expectedGen, expectedAct)
    }

    /**
     * When a modern `tools/call` opted into `io.modelcontextprotocol/tasks` produces an async/pending/watch result,
     * wrap it into a flat `CreateTaskResult` (`resultType: "task"`). Returns `null` for immediate synchronous results.
     *
     * Never overwrites an already-registered bridged task for the same `taskId` (prevents retries from resetting
     * the one-shot continuation state machine).
     */
    fun maybeWrap(
        r: ApiServer.Req,
        argv: List<String>,
        session: String,
        res: NativeOffloadResult,
    ): JSONObject? {
        val tokenId = r.auth?.token?.id ?: return null
        val ownerKey = ownerKeyOf(r) ?: return null
        val now = System.currentTimeMillis()
        val code = res.codeOrDefault
        val data = res.data
        val tellUser = data?.optJSONObject("user_action")?.optString("tell_user")?.takeIf { it.isNotBlank() }

        if (code == "TASK_STARTED") {
            val bgId = data?.optString("task")?.takeIf { it.isNotBlank() } ?: return null
            val tid = PREFIX_BG + bgId
            val msg = tr("后台任务已启动", "Background task started")
            return taskDescriptor(tid, "working", msg, now, now, POLL_FAST_MS).put("resultType", "task")
        }

        if (code == "ASK_PENDING") {
            val askId = data?.optJSONObject("ask")?.optInt("id", -1)?.takeIf { it > 0 } ?: return null
            val tid = PREFIX_ASK + askId
            val msg = tellUser ?: tr("正在等待用户在手机上回答问题", "Waiting for the user to answer on the phone")
            val entry = synchronized(lock) {
                pruneLocked(now)
                val existing = bridged[tid]?.takeIf { it.ownerKey == ownerKey && it.tokenId == tokenId }
                if (existing != null) {
                    existing
                } else {
                    BridgedTask(
                        taskId = tid,
                        kind = "ask",
                        askId = askId,
                        confirmId = null,
                        ownerKey = ownerKey,
                        tokenId = tokenId,
                        session = session,
                        initialResult = res,
                        continuation = ContinuationPlan(0, safeResume = true, resumeArgv = argv.toList(), null, null),
                        createdAt = now,
                        waitStatusMessage = msg,
                    ).also {
                        bridged[tid] = it
                        pruneLocked(now)
                    }
                }
            }
            val snap = snapshotBridged(entry)
            return taskDescriptor(tid, snap.wireStatus, snap.statusMessage, snap.createdAt, snap.lastUpdatedAt, POLL_FAST_MS)
                .put("resultType", "task")
        }

        if (code == "CONFIRM_PENDING" || code == "NEEDS_CONFIRM") {
            val cfmId = data?.optJSONObject("confirm")?.optString("id")?.takeIf { it.isNotBlank() } ?: return null
            val tid = PREFIX_CFM + cfmId
            val msg = tellUser ?: tr("正在等待用户在手机上完成确认", "Waiting for the user to confirm on the phone")
            val entry = synchronized(lock) {
                pruneLocked(now)
                val existing = bridged[tid]?.takeIf { it.ownerKey == ownerKey && it.tokenId == tokenId }
                if (existing != null) {
                    existing
                } else {
                    val plan = parseContinuationPlan(argv, res)
                    BridgedTask(
                        taskId = tid,
                        kind = "cfm",
                        askId = -1,
                        confirmId = cfmId,
                        ownerKey = ownerKey,
                        tokenId = tokenId,
                        session = session,
                        initialResult = res,
                        continuation = plan,
                        createdAt = now,
                        waitStatusMessage = msg,
                    ).also {
                        bridged[tid] = it
                        pruneLocked(now)
                    }
                }
            }
            val snap = snapshotBridged(entry)
            return taskDescriptor(tid, snap.wireStatus, snap.statusMessage, snap.createdAt, snap.lastUpdatedAt, POLL_FAST_MS)
                .put("resultType", "task")
        }

        if (res.exitCode == 0) {
            val keepName = data?.optString("keep")?.takeIf { it.isNotBlank() }
            if (keepName != null) {
                val tid = PREFIX_KEEP + keepName
                synchronized(lock) { cancelledKeeps.remove("$ownerKey:$keepName") }
                val msg = tr("后台盯守「$keepName」已启动", "Background watch \"$keepName\" started")
                return taskDescriptor(tid, "working", msg, now, now, POLL_KEEP_MS).put("resultType", "task")
            }
        }
        return null
    }

    /**
     * Standard `tasks/get` (`GetTaskResult`): returns flat `DetailedTask` with `resultType: "complete"`,
     * inlining `"result"` (`CallToolResult`, including `isError: true`) when `status == "completed"`,
     * and inlining `"error"` when `status == "failed"`.
     */
    fun handleGet(
        server: ApiServer,
        r: ApiServer.Req,
        id: Any,
        params: JSONObject,
        toToolResult: (ApiServer, NativeOffloadResult, Boolean, String?) -> JSONObject,
    ): JSONObject {
        val tokenId = r.auth?.token?.id ?: return McpProtocol.jsonRpcError(id, -32602, "missing auth")
        val ownerKey = ownerKeyOf(r) ?: return McpProtocol.jsonRpcError(id, -32602, "missing auth")
        val taskId = params.optString("taskId").trim()
        if (taskId.isEmpty()) return McpProtocol.jsonRpcError(id, -32602, "missing taskId")
        val detailed = describeTask(server, r, ownerKey, tokenId, taskId, includePayload = true, toToolResult = toToolResult)
            ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
        return McpProtocol.jsonRpcResult(id, detailed.put("resultType", "complete"))
    }

    fun handleResult(
        server: ApiServer,
        r: ApiServer.Req,
        id: Any,
        params: JSONObject,
        toToolResult: (ApiServer, NativeOffloadResult, Boolean, String?) -> JSONObject,
    ): JSONObject {
        val tokenId = r.auth?.token?.id ?: return McpProtocol.jsonRpcError(id, -32602, "missing auth")
        val ownerKey = ownerKeyOf(r) ?: return McpProtocol.jsonRpcError(id, -32602, "missing auth")
        val taskId = params.optString("taskId").trim()
        if (taskId.isEmpty()) return McpProtocol.jsonRpcError(id, -32602, "missing taskId")

        return when {
            taskId.startsWith(PREFIX_BG) -> {
                val t = Tasks.findForToken(tokenId, taskId.removePrefix(PREFIX_BG))
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                if (t.state == "cancelled") {
                    return McpProtocol.jsonRpcError(id, -32602, "task $taskId was cancelled")
                }
                val res = t.result
                    ?: return McpProtocol.jsonRpcError(id, -32602, "task $taskId is still working")
                val out = toToolResult(server, res, false, t.session).put("resultType", "complete")
                McpProtocol.jsonRpcResult(id, out)
            }
            taskId.startsWith(PREFIX_ASK) || taskId.startsWith(PREFIX_CFM) -> {
                val entry = findBridged(ownerKey, tokenId, taskId)
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                advanceBridged(server, r, entry)
                synchronized(entry.lock) {
                    val deadline = System.currentTimeMillis() + 5_000L
                    while (entry.phase == Phase.REPLAYING && System.currentTimeMillis() < deadline) {
                        val rem = (deadline - System.currentTimeMillis()).coerceAtMost(200L)
                        if (rem > 0L) entry.lock.wait(rem)
                    }
                }
                val snap = snapshotBridged(entry)
                when (snap.phase) {
                    Phase.COMPLETED -> {
                        val res = snap.finalResult
                            ?: return McpProtocol.jsonRpcError(id, -32603, "missing task result")
                        McpProtocol.jsonRpcResult(id, toToolResult(server, res, false, entry.session).put("resultType", "complete"))
                    }
                    Phase.FAILED -> {
                        val err = snap.protocolError
                        McpProtocol.jsonRpcError(
                            id,
                            err?.optInt("code", -32603) ?: -32603,
                            err?.optString("message")?.takeIf { it.isNotBlank() } ?: "task execution failed",
                        )
                    }
                    Phase.CANCELLED -> McpProtocol.jsonRpcError(id, -32602, "task $taskId was cancelled")
                    Phase.WAITING_PHONE, Phase.REPLAYING -> McpProtocol.jsonRpcError(id, -32602, "task $taskId is still working")
                }
            }
            taskId.startsWith(PREFIX_KEEP) -> {
                val name = taskId.removePrefix(PREFIX_KEEP)
                val live = VScreenRules.list().firstOrNull { it.spec.agentId == tokenId && it.spec.name == name }
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                if (!live.stopped) return McpProtocol.jsonRpcError(id, -32602, "task $taskId is still working")
                if (synchronized(lock) { "$ownerKey:$name" in cancelledKeeps }) {
                    return McpProtocol.jsonRpcError(id, -32602, "task $taskId was cancelled")
                }
                val keepRes = keepResultOf(live)
                val out = toToolResult(server, keepRes, false, live.spec.sid).put("resultType", "complete")
                McpProtocol.jsonRpcResult(id, out)
            }
            else -> McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
        }
    }

    /**
     * Cooperative atomic `tasks/cancel`:
     * - Never overwrites an already-terminal task (`completed` / `failed` / `cancelled`).
     * - When `WAITING_PHONE`, atomically transitions to `CANCELLED` (and withdraws `UserAsk` if applicable).
     * - When `REPLAYING`, marks cooperative cancellation (`cancelRequested = true`, `activeCall.cancelled = true`)
     *   and accurately reports current status (`working`) until the replay thread settles into a single terminal state.
     */
    fun handleCancel(server: ApiServer, r: ApiServer.Req, id: Any, params: JSONObject): JSONObject {
        val tokenId = r.auth?.token?.id ?: return McpProtocol.jsonRpcError(id, -32602, "missing auth")
        val ownerKey = ownerKeyOf(r) ?: return McpProtocol.jsonRpcError(id, -32602, "missing auth")
        val taskId = params.optString("taskId").trim()
        if (taskId.isEmpty()) return McpProtocol.jsonRpcError(id, -32602, "missing taskId")
        val now = System.currentTimeMillis()

        return when {
            taskId.startsWith(PREFIX_BG) -> {
                val t = Tasks.cancelForToken(tokenId, taskId.removePrefix(PREFIX_BG))
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                val status = when {
                    t.state == "cancelled" -> "cancelled"
                    t.result != null -> "completed"
                    else -> "working"
                }
                val msg = when (status) {
                    "cancelled" -> tr("任务已取消", "Task cancelled")
                    "completed" -> tr("任务已完成，无法取消", "Task already completed")
                    else -> tr("已请求取消任务", "Cancellation requested")
                }
                val desc = taskDescriptor(taskId, status, msg, t.startedAt, if (t.endedAt > 0L) t.endedAt else now, POLL_FAST_MS)
                McpProtocol.jsonRpcResult(id, desc.put("resultType", "complete"))
            }
            taskId.startsWith(PREFIX_ASK) || taskId.startsWith(PREFIX_CFM) -> {
                val entry = findBridged(ownerKey, tokenId, taskId)
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                var withdrawAsk = false
                val snap = synchronized(entry.lock) {
                    when (entry.phase) {
                        Phase.WAITING_PHONE -> {
                            entry.cancelRequested = true
                            entry.phase = Phase.CANCELLED
                            entry.statusMessage = if (entry.kind == "ask") {
                                tr("已取消提问任务", "Ask task cancelled")
                            } else {
                                tr("已取消确认等待句柄", "Confirmation wait handle cancelled")
                            }
                            entry.completedAt = now
                            entry.lastUpdatedAt = now
                            entry.lock.notifyAll()
                            withdrawAsk = entry.kind == "ask"
                        }
                        Phase.REPLAYING -> {
                            // Do NOT lie and mark cancelled while replay is still executing; signal cooperative cancel
                            // so the single replay completion transition picks either CANCELLED or COMPLETED.
                            entry.cancelRequested = true
                            entry.activeCall?.cancelled = true
                            entry.statusMessage = tr("已请求取消，等待执行停止", "Cancellation requested; waiting for execution to stop")
                            entry.lastUpdatedAt = now
                        }
                        Phase.COMPLETED, Phase.FAILED, Phase.CANCELLED -> {
                            // Terminal state is strictly immutable.
                        }
                    }
                    snapshotBridgedLocked(entry)
                }
                if (withdrawAsk) {
                    val ask = UserAsk.byId(entry.askId)
                    if (ask != null && ask.token == tokenId && ask.answer == null && !UserAsk.taken(ask)) {
                        UserAsk.withdraw(server.ctxFiles(), ask)
                    }
                }
                val desc = taskDescriptor(taskId, snap.wireStatus, snap.statusMessage, snap.createdAt, snap.lastUpdatedAt, POLL_FAST_MS)
                McpProtocol.jsonRpcResult(id, desc.put("resultType", "complete"))
            }
            taskId.startsWith(PREFIX_KEEP) -> {
                val name = taskId.removePrefix(PREFIX_KEEP)
                val live = VScreenRules.list().firstOrNull { it.spec.agentId == tokenId && it.spec.name == name }
                    ?: return McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
                val status: String
                val msg: String
                if (!live.stopped) {
                    synchronized(lock) { cancelledKeeps += "$ownerKey:$name" }
                    VScreenRules.stop(tokenId, name)
                    status = "cancelled"
                    msg = tr("已停止盯守", "Watch stopped")
                } else {
                    val wasCancelled = synchronized(lock) { "$ownerKey:$name" in cancelledKeeps }
                    status = if (wasCancelled) "cancelled" else "completed"
                    msg = if (wasCancelled) tr("已停止盯守", "Watch stopped") else tr("盯守已结束", "Watch already completed")
                }
                val desc = taskDescriptor(taskId, status, msg, live.startedAt, if (live.endedAt > 0L) live.endedAt else now, POLL_KEEP_MS)
                McpProtocol.jsonRpcResult(id, desc.put("resultType", "complete"))
            }
            else -> McpProtocol.jsonRpcError(id, -32602, "TASK_NOT_FOUND: $taskId")
        }
    }

    fun handleList(server: ApiServer, r: ApiServer.Req, id: Any): JSONObject {
        val tokenId = r.auth?.token?.id ?: return McpProtocol.jsonRpcError(id, -32602, "missing auth")
        val ownerKey = ownerKeyOf(r) ?: return McpProtocol.jsonRpcError(id, -32602, "missing auth")
        val arr = JSONArray()
        for (t in Tasks.listForToken(tokenId)) {
            arr.put(describeBg(server, t, includePayload = false, toToolResult = null))
        }
        val entries = synchronized(lock) {
            pruneLocked(System.currentTimeMillis())
            bridged.values.filter { it.ownerKey == ownerKey && it.tokenId == tokenId }.toList()
        }
        for (e in entries) {
            val snap = advanceBridged(server, r, e)
            arr.put(taskDescriptor(e.taskId, snap.wireStatus, snap.statusMessage, snap.createdAt, snap.lastUpdatedAt, POLL_FAST_MS))
        }
        for (live in VScreenRules.list().filter { it.spec.agentId == tokenId }) {
            arr.put(describeKeep(server, ownerKey, live, includePayload = false, toToolResult = null))
        }
        return McpProtocol.jsonRpcResult(
            id,
            JSONObject()
                .put("tasks", arr)
                .put("resultType", "complete"),
        )
    }

    private fun findBridged(ownerKey: String, tokenId: String, taskId: String): BridgedTask? = synchronized(lock) {
        pruneLocked(System.currentTimeMillis())
        bridged[taskId]?.takeIf { it.ownerKey == ownerKey && it.tokenId == tokenId }
    }

    private fun describeTask(
        server: ApiServer,
        r: ApiServer.Req,
        ownerKey: String,
        tokenId: String,
        taskId: String,
        includePayload: Boolean,
        toToolResult: ((ApiServer, NativeOffloadResult, Boolean, String?) -> JSONObject)?,
    ): JSONObject? = when {
        taskId.startsWith(PREFIX_BG) ->
            Tasks.findForToken(tokenId, taskId.removePrefix(PREFIX_BG))?.let {
                describeBg(server, it, includePayload, toToolResult)
            }
        taskId.startsWith(PREFIX_ASK) || taskId.startsWith(PREFIX_CFM) ->
            findBridged(ownerKey, tokenId, taskId)?.let { entry ->
                val snap = advanceBridged(server, r, entry)
                val out = taskDescriptor(entry.taskId, snap.wireStatus, snap.statusMessage, snap.createdAt, snap.lastUpdatedAt, POLL_FAST_MS)
                if (includePayload) {
                    when (snap.phase) {
                        Phase.COMPLETED -> snap.finalResult?.let { res ->
                            toToolResult?.let { render -> out.put("result", render(server, res, false, entry.session)) }
                        }
                        Phase.FAILED -> snap.protocolError?.let { err -> out.put("error", err) }
                        else -> Unit
                    }
                }
                out
            }
        taskId.startsWith(PREFIX_KEEP) ->
            VScreenRules.list().firstOrNull { it.spec.agentId == tokenId && it.spec.name == taskId.removePrefix(PREFIX_KEEP) }
                ?.let { describeKeep(server, ownerKey, it, includePayload, toToolResult) }
        else -> null
    }

    private fun describeBg(
        server: ApiServer,
        t: Tasks.Task,
        includePayload: Boolean,
        toToolResult: ((ApiServer, NativeOffloadResult, Boolean, String?) -> JSONObject)?,
    ): JSONObject {
        val tid = PREFIX_BG + t.id
        val res = t.result
        val updated = if (t.endedAt > 0L) t.endedAt else System.currentTimeMillis()
        val (status, msg) = when {
            t.state == "cancelled" -> "cancelled" to tr("任务已取消", "Task cancelled")
            res == null -> "working" to (t.call.lastProgress ?: tr("后台任务执行中", "Background task running"))
            res.exitCode == 0 -> "completed" to tr("任务已完成", "Task completed")
            // Per ext-tasks 2026-07-28: tool results with isError=true still complete with status="completed" + inline result.
            else -> "completed" to res.codeOrDefault
        }
        val out = taskDescriptor(tid, status, msg, t.startedAt, updated, POLL_FAST_MS)
        if (includePayload && status == "completed" && res != null && toToolResult != null) {
            out.put("result", toToolResult(server, res, false, t.session))
        }
        return out
    }

    private fun keepResultOf(live: VScreenRules.Live): NativeOffloadResult {
        val summary = live.lastFire ?: live.endReason ?: live.status
        return NativeOffloadResult(
            exitCode = 0,
            output = summary + "\n",
            code = "OK",
            data = live.json(),
        )
    }

    private fun describeKeep(
        server: ApiServer,
        ownerKey: String,
        live: VScreenRules.Live,
        includePayload: Boolean,
        toToolResult: ((ApiServer, NativeOffloadResult, Boolean, String?) -> JSONObject)?,
    ): JSONObject {
        val tid = PREFIX_KEEP + live.spec.name
        val updated = if (live.endedAt > 0L) live.endedAt else System.currentTimeMillis()
        val wasCancelled = synchronized(lock) { "$ownerKey:${live.spec.name}" in cancelledKeeps }
        val status = when {
            !live.stopped -> "working"
            wasCancelled -> "cancelled"
            else -> "completed"
        }
        val msg = if (!live.stopped) live.status else (live.lastFire ?: live.endReason ?: live.status)
        val out = taskDescriptor(tid, status, msg, live.startedAt, updated, POLL_KEEP_MS)
        if (includePayload && status == "completed" && toToolResult != null) {
            out.put("result", toToolResult(server, keepResultOf(live), false, live.spec.sid))
        }
        return out
    }

    private fun snapshotBridged(entry: BridgedTask): Snapshot = synchronized(entry.lock) {
        snapshotBridgedLocked(entry)
    }

    private fun snapshotBridgedLocked(entry: BridgedTask): Snapshot = Snapshot(
        phase = entry.phase,
        wireStatus = entry.wireStatusLocked(),
        statusMessage = entry.statusMessage,
        createdAt = entry.createdAt,
        lastUpdatedAt = entry.lastUpdatedAt,
        finalResult = entry.finalResult,
        protocolError = entry.protocolError,
    )

    private fun buildSafeStopAfterConfirmResult(entry: BridgedTask): NativeOffloadResult {
        val priorData = entry.initialResult.data?.let { JSONObject(it.toString()) } ?: JSONObject()
        priorData.put("confirmed", true)
        priorData.put("continuation_skipped", true)
        priorData.put("prior_steps_done", entry.continuation.priorDoneCount)
        val msg = tr(
            "用户已在手机上批准确认。由于前面已有 ${entry.continuation.priorDoneCount} 步已执行且当前处于嵌套块/规则内，为避免重复执行已生效操作，本次已在此停住；请查看【现在】状态后决定后续步骤。\n",
            "User approved confirmation on the phone. Because ${entry.continuation.priorDoneCount} prior step(s) already ran inside a nested block or rule, automatic replay was skipped to avoid duplicating side effects; inspect [Now] and continue explicitly.\n",
        )
        return NativeOffloadResult(
            exitCode = 0,
            output = msg,
            code = "OK",
            data = priorData,
        )
    }

    private fun buildDriftStopResult(entry: BridgedTask, driftReason: String): NativeOffloadResult {
        val priorData = entry.initialResult.data?.let { JSONObject(it.toString()) } ?: JSONObject()
        priorData.put("confirmed", true)
        priorData.put("continuation_skipped", true)
        priorData.put("drift_reason", driftReason)
        val msg = tr(
            "用户已批准确认，但等待期间画面状态已变化（$driftReason），为避免误操作已停住且未重复执行；请重新查看【现在】。\n",
            "User approved confirmation, but the screen state changed while waiting ($driftReason); stopped without replaying to avoid unintended actions. Inspect [Now] first.\n",
        )
        return NativeOffloadResult(
            exitCode = 1,
            output = msg,
            code = "STALE_ELEMENT",
            data = priorData,
        )
    }

    private fun advanceBridged(server: ApiServer, r: ApiServer.Req, entry: BridgedTask): Snapshot {
        val now = System.currentTimeMillis()
        var launchReplay: List<String>? = null
        val snap = synchronized(entry.lock) {
            if (entry.isTerminalLocked() || entry.phase == Phase.REPLAYING) {
                return@synchronized snapshotBridgedLocked(entry)
            }
            if (entry.kind == "ask") {
                val ask = UserAsk.byId(entry.askId)?.takeIf { it.token == entry.tokenId }
                when {
                    ask == null -> {
                        entry.phase = Phase.COMPLETED
                        entry.finalResult = NativeOffloadResult(1, tr("提问已过期\n", "Question expired\n"), "ASK_PENDING")
                        entry.statusMessage = tr("提问已过期", "Question expired")
                        entry.completedAt = now
                        entry.lastUpdatedAt = now
                        entry.lock.notifyAll()
                    }
                    ask.answer == null -> {
                        entry.lastUpdatedAt = now
                    }
                    ask.answer == false -> {
                        entry.phase = Phase.COMPLETED
                        entry.finalResult = NativeOffloadResult(1, tr("用户未回答该问题\n", "The user declined to answer\n"), "ASK_DECLINED")
                        entry.statusMessage = tr("用户未回答", "User declined")
                        entry.completedAt = now
                        entry.lastUpdatedAt = now
                        entry.lock.notifyAll()
                    }
                    ask.answer == true -> {
                        entry.phase = Phase.REPLAYING
                        entry.statusMessage = tr("用户已回答，正在汇总结果", "User answered; collecting result")
                        entry.lastUpdatedAt = now
                        launchReplay = entry.continuation.resumeArgv
                    }
                }
                return@synchronized snapshotBridgedLocked(entry)
            }

            // cfm_<id>
            val req = StrongConfirm.byId(entry.confirmId)?.takeIf { it.tokenId == entry.tokenId }
            when {
                req == null -> {
                    entry.phase = Phase.COMPLETED
                    entry.finalResult = NativeOffloadResult(1, tr("强确认已过期\n", "Confirmation expired\n"), "CONFIRM_PENDING")
                    entry.statusMessage = tr("强确认已过期", "Confirmation expired")
                    entry.completedAt = now
                    entry.lastUpdatedAt = now
                    entry.lock.notifyAll()
                }
                req.answer == null -> {
                    entry.lastUpdatedAt = now
                }
                req.answer == false -> {
                    entry.phase = Phase.COMPLETED
                    entry.finalResult = NativeOffloadResult(1, tr("用户在手机上拒绝了确认\n", "The user denied confirmation on the phone\n"), "USER_DENIED")
                    entry.statusMessage = tr("用户已拒绝确认", "User denied confirmation")
                    entry.completedAt = now
                    entry.lastUpdatedAt = now
                    entry.lock.notifyAll()
                }
                req.answer == true -> {
                    if (!entry.continuation.safeResume || entry.continuation.resumeArgv == null) {
                        entry.phase = Phase.COMPLETED
                        entry.finalResult = buildSafeStopAfterConfirmResult(entry)
                        entry.statusMessage = tr("用户已批准；为防重复执行已安全停住", "Approved; stopped safely without replaying earlier steps")
                        entry.completedAt = now
                        entry.lastUpdatedAt = now
                        entry.lock.notifyAll()
                    } else {
                        entry.phase = Phase.REPLAYING
                        entry.statusMessage = tr("用户已批准，正在继续执行剩余步骤", "User approved; continuing remaining steps")
                        entry.lastUpdatedAt = now
                        launchReplay = entry.continuation.resumeArgv
                    }
                }
            }
            snapshotBridgedLocked(entry)
        }

        launchReplay?.let { resumeArgv ->
            launchContinuationThread(server, r, entry, resumeArgv)
        }
        return snap
    }

    private fun launchContinuationThread(
        server: ApiServer,
        pollReq: ApiServer.Req,
        entry: BridgedTask,
        resumeArgv: List<String>,
    ) {
        val detached = detachedReq(pollReq)
        Thread({
            // Abort before side effects if cancelled before thread even started.
            val cancelledEarly = synchronized(entry.lock) {
                if (entry.phase != Phase.REPLAYING) return@Thread
                if (entry.cancelRequested) {
                    val t = System.currentTimeMillis()
                    entry.phase = Phase.CANCELLED
                    entry.statusMessage = tr("任务已取消", "Task cancelled")
                    entry.completedAt = t
                    entry.lastUpdatedAt = t
                    entry.lock.notifyAll()
                    true
                } else false
            }
            if (cancelledEarly) return@Thread

            if (entry.kind == "cfm" && entry.continuation.priorDoneCount > 0) {
                val drift = continuationPageVerifier(
                    entry.session,
                    entry.continuation.expectedGeneration,
                    entry.continuation.expectedActivity,
                )
                if (drift != null) {
                    val driftRes = buildDriftStopResult(entry, drift)
                    synchronized(entry.lock) {
                        if (entry.phase == Phase.REPLAYING) {
                            val t = System.currentTimeMillis()
                            entry.phase = Phase.COMPLETED
                            entry.finalResult = driftRes
                            entry.statusMessage = tr("页面已变化，安全停住", "Screen changed; stopped safely")
                            entry.completedAt = t
                            entry.lastUpdatedAt = t
                            entry.activeCall = null
                            entry.lock.notifyAll()
                        }
                    }
                    return@Thread
                }
            }

            var runResult: NativeOffloadResult? = null
            var runException: Throwable? = null
            try {
                val (res, _) = server.runFor(
                    detached,
                    resumeArgv,
                    entry.session,
                    requestId = "mcptask:${entry.taskId}",
                    onStep = null,
                    onCallCreated = { call ->
                        synchronized(entry.lock) {
                            entry.activeCall = call
                            if (entry.cancelRequested) call.cancelled = true
                        }
                    },
                )
                runResult = res
            } catch (t: Throwable) {
                AppLogger.warning("McpTasks", "continuation failed for ${entry.taskId}: ${t.message}")
                runException = t
            }

            synchronized(entry.lock) {
                entry.activeCall = null
                if (entry.phase != Phase.REPLAYING) return@synchronized
                val doneAt = System.currentTimeMillis()
                when {
                    runException != null -> {
                        if (entry.cancelRequested) {
                            entry.phase = Phase.CANCELLED
                            entry.statusMessage = tr("任务已取消", "Task cancelled")
                        } else {
                            entry.phase = Phase.FAILED
                            entry.protocolError = JSONObject()
                                .put("code", -32603)
                                .put("message", tr("任务续跑发生内部错误", "Internal error during task continuation"))
                            entry.statusMessage = tr("继续执行失败", "Continuation failed")
                        }
                    }
                    entry.cancelRequested && runResult?.codeOrDefault == "CANCELLED" -> {
                        entry.phase = Phase.CANCELLED
                        entry.statusMessage = tr("任务已取消", "Task cancelled")
                    }
                    else -> {
                        val res = runResult!!
                        entry.phase = Phase.COMPLETED
                        entry.finalResult = res
                        entry.statusMessage = if (res.exitCode == 0) tr("已完成", "Completed") else res.codeOrDefault
                    }
                }
                entry.completedAt = doneAt
                entry.lastUpdatedAt = doneAt
                entry.lock.notifyAll()
            }
        }, "vscreen-mcp-task").apply { isDaemon = true; start() }
    }

    /** Copy authenticated caller context without the short-lived polling socket so background replay isn't cancelled. */
    private fun detachedReq(r: ApiServer.Req): ApiServer.Req =
        ApiServer.Req(r.method, r.target, r.query, r.headers, ByteArray(0)).apply {
            peer = r.peer
            auth = r.auth
            viaPublic = r.viaPublic
            publicClientId = r.publicClientId
            publicGrantId = r.publicGrantId
            publicScope = r.publicScope
            sock = null
        }
}
