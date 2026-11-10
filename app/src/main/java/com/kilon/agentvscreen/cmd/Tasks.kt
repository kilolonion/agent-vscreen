package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 1.2: long commands as tasks. `launch|act|collect --async` returns a task id at once; the command runs on its own
 * thread (in the screen queue like any other); `task <id> [--wait T]` returns its state, its last finished step, and
 * the full result once done; `task cancel <id>` stops it before its next step. Before 1.2 a 2-minute stretch needed a
 * 2-minute HTTP request, which proxies and agent tool timeouts cut, leaving the phone working for nobody.
 */
internal object Tasks {
    class Task(val id: String, val session: String, val owner: String, val line: String, val call: Call) {
        val startedAt = System.currentTimeMillis()
        @Volatile var result: NativeOffloadResult? = null
        @Volatile var endedAt = 0L
        val lock = Object()
        val state: String get() = when { result == null -> if (call.cancelled) "cancelling" else "running"; call.cancelled && result?.code == "CANCELLED" -> "cancelled"; else -> "done" }
    }

    private const val KEEP_MS = 30 * 60_000L
    private const val MAX_RUNNING = 4
    private val tasks = LinkedHashMap<String, Task>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "vscreen-task").apply { isDaemon = true } }
    private var seq = 0

    private fun prune() {
        val now = System.currentTimeMillis()
        tasks.values.removeAll { it.result != null && now - it.endedAt > KEEP_MS }
    }

    fun start(h: VScreenOffloadHandler, sid: String, sub: String, argv: List<String>): NativeOffloadResult {
        val parent = Call.current()
        val t = synchronized(tasks) {
            prune()
            if (tasks.values.count { it.result == null } >= MAX_RUNNING) throw ActionError("BUSY", tr("已经有 $MAX_RUNNING 个任务在运行：task 看列表，等一个做完或 task cancel", "$MAX_RUNNING tasks are already running: task lists them; wait for one or task cancel"))
            val id = "t" + (++seq) + "-" + java.lang.Long.toString((Math.random() * 46656).toLong(), 36)
            val c = Call(sid, parent?.tokenId ?: "main", parent?.tokenName ?: "main", parent?.scopes ?: setOf("look", "operate", "system"), parent?.peer, parent?.requestId, parent?.lang, parent?.trustUntil ?: 0L)
            Task(id, sid, c.tokenId, com.kilon.agentvscreen.host.Audit.redact(argv).take(120), c).also { tasks[id] = it }
        }
        val run = I18n.carry {
            Call.with(t.call) { h.handle(NativeOffloadRequest(listOf("vscreen") + argv, sid)) }
        }
        pool.execute {
            val r = try { run() } catch (e: Throwable) { h.err("INTERNAL_ERROR", "${e.javaClass.simpleName}: ${e.message}") }
            t.result = r; t.endedAt = System.currentTimeMillis()
            synchronized(t.lock) { t.lock.notifyAll() }
        }
        val data = JSONObject().put("task", t.id).put("state", "running")
        return NativeOffloadResult(0, tr("任务 ${t.id} 已开始（$sub）。取结果：vscreen task ${t.id} --wait 60s；取消：vscreen task cancel ${t.id}", "Task ${t.id} started ($sub). Result: vscreen task ${t.id} --wait 60s; cancel: vscreen task cancel ${t.id}") + "\n", "TASK_STARTED", data)
    }

    private fun mine(t: Task, sid: String): Boolean = t.owner == (Call.current()?.tokenId ?: "main") && (t.session == sid || Call.current() == null)

    fun command(sid: String, args: OffloadArgs): NativeOffloadResult {
        val p = args.positional
        val owner = Call.current()?.tokenId ?: "main"
        if (p.isEmpty()) {
            val list = synchronized(tasks) { prune(); tasks.values.filter { it.owner == owner }.toList() }
            if (list.isEmpty()) return NativeOffloadResult(0, tr("没有任务。launch / act / collect 加 --async 会成为任务", "No tasks. launch / act / collect with --async become tasks") + "\n", "OK", JSONObject().put("tasks", JSONArray()))
            val now = System.currentTimeMillis()
            return NativeOffloadResult(0, list.joinToString("\n") { "${it.id}  ${it.state}  ${(((if (it.result == null) now else it.endedAt) - it.startedAt) / 1000)}s  [${it.session}] ${it.line}" } + "\n", "OK",
                JSONObject().put("tasks", JSONArray(list.map { JSONObject().put("task", it.id).put("state", it.state).put("session", it.session).put("command", it.line) })))
        }
        val cancel = p[0] == "cancel"
        val id = (if (cancel) p.getOrNull(1) else p[0]) ?: throw ActionError("INVALID_ARGS", tr("task cancel <任务号>", "task cancel <task id>"))
        val t = synchronized(tasks) { tasks[id] }?.takeIf { it.owner == owner } ?: throw ActionError("NOT_FOUND", tr("没有任务 $id（结果只保留 30 分钟）：task 看列表", "No task $id (results are kept for 30 minutes): task lists them"))
        if (cancel) {
            if (t.result == null) t.call.cancelled = true
            return NativeOffloadResult(0, (if (t.result == null) tr("已要求任务 $id 停下：它会在当前这一步之后停。task $id 看它停在哪", "Task $id was asked to stop: it stops after its current step. task $id shows where it stopped") else tr("任务 $id 已经做完了，没有可取消的：task $id 取结果", "Task $id has already finished, nothing to cancel: task $id returns its result")) + "\n", "OK", JSONObject().put("task", id).put("state", t.state))
        }
        val wait = (args.getLong("wait") ?: 0L).coerceIn(0L, 120_000L)
        val until = System.currentTimeMillis() + wait
        synchronized(t.lock) {
            while (t.result == null && System.currentTimeMillis() < until && Call.current()?.cancelled != true) t.lock.wait(250)
        }
        val r = t.result ?: return NativeOffloadResult(0, tr("任务 $id 还在运行（${(System.currentTimeMillis() - t.startedAt) / 1000}s）", "Task $id is still running (${(System.currentTimeMillis() - t.startedAt) / 1000}s)") +
            (t.call.lastProgress?.let { tr("。最近完成的一步：$it", ". Last finished step: $it") } ?: "") + tr("。task $id --wait 60s 等它", ". task $id --wait 60s waits for it") + "\n", "TASK_RUNNING",
            JSONObject().put("task", id).put("state", t.state).put("last_step", t.call.lastProgress ?: JSONObject.NULL))
        // The task's own result, as if the command had been called directly.
        return r.copy(data = (r.data ?: JSONObject()).put("task", id).put("state", t.state))
    }

    /** For status / diag: running tasks, without their arguments. */
    fun running(): Int = synchronized(tasks) { tasks.values.count { it.result == null } }
}
