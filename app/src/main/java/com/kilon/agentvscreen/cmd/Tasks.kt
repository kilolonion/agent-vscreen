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
            if (com.kilon.agentvscreen.host.ThermalGuard.locked) com.kilon.agentvscreen.host.ThermalGuard.throwStopped()
            prune()
            if (tasks.values.count { it.result == null } >= MAX_RUNNING) throw ActionError("BUSY", tr("已经有 $MAX_RUNNING 个任务在运行：task 看列表，等一个做完或 task cancel", "$MAX_RUNNING tasks are already running: task lists them; wait for one or task cancel"))
            val id = "t" + (++seq) + "-" + java.lang.Long.toString((Math.random() * 46656).toLong(), 36)
            val c = Call(sid, parent?.tokenId ?: "main", parent?.tokenName ?: "main", parent?.scopes ?: setOf("look", "operate", "system"), parent?.peer, parent?.requestId, parent?.lang, parent?.trustUntil ?: 0L, parent?.via ?: com.kilon.agentvscreen.api.Abilities.Via.CLI, parent?.source ?: Call.Source.LOCAL)
            c.taskId = id
            // 1.4.2-beta6: a task plans with what its caller saw, so it keeps the epoch the request arrived in.
            parent?.let { c.epoch = it.epoch }
            Task(id, sid, c.tokenId, com.kilon.agentvscreen.host.Audit.redact(argv).take(120), c).also { tasks[id] = it }
        }
        // 1.3: a task is part of its session's turn for as long as it runs. Before, only the request that started it
        // counted, so after the quiet minutes the turn ended (screen paused, takeover dropped) under a task still working.
        com.kilon.agentvscreen.api.Turns.begin(sid)
        val run = I18n.carry {
            try { Call.with(t.call) { h.handle(NativeOffloadRequest(listOf("vscreen") + argv, sid)) } }
            finally { com.kilon.agentvscreen.api.Turns.end(sid) }
        }
        pool.execute {
            val r = try { run() } catch (e: Throwable) { h.err("INTERNAL_ERROR", "${e.javaClass.simpleName}: ${e.message}") }
            // prune() treats a published result as finished and reads its timestamp.
            t.endedAt = System.currentTimeMillis()
            t.result = r
            synchronized(t.lock) { t.lock.notifyAll() }
        }
        val data = JSONObject().put("task", t.id).put("state", "running")
        return NativeOffloadResult(0, tr("任务 ${t.id} 已开始（$sub）。取结果：vscreen task ${t.id} --wait 60s；取消：vscreen task cancel ${t.id}", "Task ${t.id} started ($sub). Result: vscreen task ${t.id} --wait 60s; cancel: vscreen task cancel ${t.id}") + "\n", "TASK_STARTED", data)
    }

    private fun mine(t: Task, sid: String): Boolean = t.owner == (Call.current()?.tokenId ?: "main") && (t.session == sid || Call.current() == null)

    fun command(sid: String, args: OffloadArgs): NativeOffloadResult {
        val p = args.positional
        if (p.isEmpty()) {
            val list = synchronized(tasks) { prune(); tasks.values.filter { mine(it, sid) }.toList() }
            if (list.isEmpty()) return NativeOffloadResult(0, tr("没有任务。launch / act / collect 加 --async 会成为任务", "No tasks. launch / act / collect with --async become tasks") + "\n", "OK", JSONObject().put("tasks", JSONArray()))
            val now = System.currentTimeMillis()
            return NativeOffloadResult(0, list.joinToString("\n") { "${it.id}  ${it.state}  ${(((if (it.result == null) now else it.endedAt) - it.startedAt) / 1000)}s  [${it.session}] ${it.line}" } + "\n", "OK",
                JSONObject().put("tasks", JSONArray(list.map { JSONObject().put("task", it.id).put("state", it.state).put("session", it.session).put("command", it.line) })))
        }
        if (p[0].equals("recap", ignoreCase = true)) return recap(sid)
        val cancel = p[0] == "cancel"
        val id = (if (cancel) p.getOrNull(1) else p[0]) ?: throw ActionError("INVALID_ARGS", tr("task cancel <任务号>", "task cancel <task id>"))
        val t = synchronized(tasks) { tasks[id] }?.takeIf { mine(it, sid) } ?: throw ActionError("NOT_FOUND", tr("没有任务 $id（结果只保留 30 分钟）：task 看列表", "No task $id (results are kept for 30 minutes): task lists them"))
        if (cancel) {
            if (t.result == null) t.call.cancelled = true
            return NativeOffloadResult(0, (if (t.result == null) tr("已要求任务 $id 停下：它会在当前这一步之后停。task $id 看它停在哪", "Task $id was asked to stop: it stops after its current step. task $id shows where it stopped") else tr("任务 $id 已经结束了（不一定成功），没有可取消的：task $id 看它的结果", "Task $id has already ended (not necessarily successfully), nothing to cancel: task $id shows its result")) + "\n", "OK", JSONObject().put("task", id).put("state", t.state))
        }
        val wait = boundedMs("wait", args.getLong("wait") ?: 0L, 0L, WAIT_MAX_MS)
        val until = System.currentTimeMillis() + wait
        synchronized(t.lock) {
            while (t.result == null && System.currentTimeMillis() < until && Call.current()?.cancelled != true) t.lock.wait(250)
        }
        val r = t.result ?: return NativeOffloadResult(0, tr("任务 $id 还在运行（${(System.currentTimeMillis() - t.startedAt) / 1000}s）", "Task $id is still running (${(System.currentTimeMillis() - t.startedAt) / 1000}s)") +
            (t.call.lastProgress?.let { tr("。最近完成的一步：$it", ". Last finished step: $it") } ?: "") + tr("。task $id --wait 2m 再等（一次最长 2m）", ". task $id --wait 2m waits again (at most 2m per call)") + "\n", "TASK_RUNNING",
            JSONObject().put("task", id).put("state", t.state).put("last_step", t.call.lastProgress ?: JSONObject.NULL))
        // The task's own result, as if the command had been called directly.
        return r.copy(data = (r.data ?: JSONObject()).put("task", id).put("state", t.state))
    }

    /** 2.0.0-beta3 (3.8): summarize this session's path from TaskLog + PageBook only (no screen read, <= 600 chars). */
    fun recap(sid: String): NativeOffloadResult {
        val turns = com.kilon.agentvscreen.api.Turns.turnOf(sid)
        val cards = PageBook.cards(sid)
        val current = PageBook.current(sid)
        val entries = com.kilon.agentvscreen.core.TaskLog.recapSteps(sid)
        val text = formatRecap(turns, cards, current, entries)
        val pageCount = maxOf(cards.size, entries.mapNotNull { it.pageId }.distinct().size)
        val data = JSONObject()
            .put("turns", turns)
            .put("pages", pageCount)
            .put("current_page", current?.pageJson() ?: JSONObject.NULL)
        return NativeOffloadResult(0, text + "\n", "OK", data)
    }

    /**
     * 2.0.0-beta16 (Item 8), pure: shorten a recap step label to at most [maxLen] characters without
     * cutting in the middle of paired brackets `()[]{}（）「」【】` or quotes `""“”`.
     */
    internal fun truncateStepLabel(s: String, maxLen: Int = 28): String {
        if (s.length <= maxLen) return s
        if (maxLen <= 1) return s.take(maxLen)
        val bracketPairs = mapOf(
            '(' to ')', '[' to ']', '{' to '}',
            '（' to '）', '「' to '」', '【' to '】',
        )
        val quotePairs = mapOf('"' to '"', '“' to '”')
        val allOpen = bracketPairs + quotePairs
        val closers = bracketPairs.values.toSet() + setOf('”')
        val stack = ArrayDeque<Triple<Int, Char, Boolean>>() // (openIdx, closeChar, isQuote)
        val limit = maxLen - 1
        for (i in 0 until limit) {
            val ch = s[i]
            when {
                ch == '"' -> {
                    if (stack.isNotEmpty() && stack.last().second == '"') stack.removeLast()
                    else stack.addLast(Triple(i, '"', true))
                }
                ch in allOpen -> stack.addLast(Triple(i, allOpen.getValue(ch), ch in quotePairs))
                ch in closers -> {
                    if (stack.isNotEmpty() && stack.last().second == ch) stack.removeLast()
                }
            }
        }
        if (stack.isEmpty()) {
            return s.substring(0, limit).trimEnd() + "…"
        }
        val firstBracket = stack.firstOrNull { !it.third }
        if (firstBracket != null && firstBracket.first > 0) {
            val cutAt = firstBracket.first
            val openQuotesBefore = stack.takeWhile { it.first < cutAt }.reversed().map { it.second }.joinToString("")
            val prefix = s.substring(0, cutAt).trimEnd()
            if (prefix.isNotEmpty()) {
                val room = (maxLen - 1 - openQuotesBefore.length).coerceAtLeast(1)
                return prefix.take(room).trimEnd() + "…" + openQuotesBefore
            }
        }
        // Only quotes are open (or a bracket opened at index 0): keep balanced by closing open delimiters.
        val closing = stack.reversed().map { it.second }.joinToString("")
        val bodyLen = (maxLen - 1 - closing.length).coerceAtLeast(1)
        return s.substring(0, minOf(bodyLen, s.length)).trimEnd() + "…" + closing
    }

    internal fun formatRecap(
        turns: Int,
        cards: List<PageBook.Card>,
        current: PageBook.Card?,
        entries: List<com.kilon.agentvscreen.core.TaskLog.RecapStep>,
    ): String {
        val pageCount = maxOf(cards.size, entries.mapNotNull { it.pageId }.distinct().size)
        val header = tr("本会话 $turns 轮，经过 $pageCount 个页面", "Session: $turns turns, $pageCount pages visited")

        // Build path tokens: alternating page nodes and step tokens.
        data class Tok(val text: String, val isStep: Boolean)
        val toks = ArrayList<Tok>()
        var lastPageId: String? = null
        for (e in entries) {
            for ((step, fail) in e.steps) {
                val label = truncateStepLabel(step, 28) + (if (fail != null) " ✗ $fail" else "")
                toks += Tok(label, isStep = true)
            }
            if (e.pageId != null && e.pageId != lastPageId) {
                val p = listOfNotNull(e.pageId, e.pageTitle?.take(16)?.takeIf { it.isNotBlank() }).joinToString(" ")
                toks += Tok(p, isStep = false)
                lastPageId = e.pageId
            }
        }
        if (toks.isEmpty() && current != null) {
            toks += Tok("P${current.id} ${current.title}".trim(), isStep = false)
        }

        val nowLine = if (current != null) {
            val clickables = current.els
                .filter { (it.clickable || it.editable) && !it.placeholder && it.text.isNotBlank() }
                .map { it.text.take(16) }
                .distinct()
                .ifEmpty { current.els.filter { !it.placeholder && it.text.isNotBlank() }.map { it.text.take(16) }.distinct() }
                .take(RECAP_MAX_CLICKABLES)
            if (clickables.isEmpty()) tr("现在 P${current.id}。", "Now P${current.id}.")
            else tr(
                "现在 P${current.id}。P${current.id} 上可点：${clickables.joinToString("、")}",
                "Now P${current.id}. Tappable on P${current.id}: ${clickables.joinToString(", ")}",
            )
        } else {
            tr("还没有页面记录。", "No pages visited yet.")
        }

        val totalSteps = toks.count { it.isStep }
        fun buildPath(keepSteps: Int): String {
            if (toks.isEmpty()) return ""
            if (totalSteps <= keepSteps) return toks.joinToString(" → ") { it.text }
            // Keep the tail containing the last `keepSteps` step tokens (and any page nodes after the first kept step).
            var seen = 0
            var cutIdx = toks.size
            for (i in toks.indices.reversed()) {
                if (toks[i].isStep) {
                    seen++
                    if (seen == keepSteps) {
                        cutIdx = i
                        break
                    }
                }
            }
            val dropped = totalSteps - keepSteps
            val tail = toks.subList(cutIdx, toks.size).joinToString(" → ") { it.text }
            val prefix = tr("…前 $dropped 步", "...$dropped earlier steps")
            return if (tail.isEmpty()) prefix else "$prefix → $tail"
        }

        fun assemble(pathLine: String, footer: String): String =
            listOf(header, pathLine, footer).filter { it.isNotEmpty() }.joinToString("\n")

        var keep = minOf(totalSteps, RECAP_MAX_STEPS)
        var pathLine = buildPath(keep)
        var out = assemble(pathLine, nowLine)
        while (out.length > RECAP_MAX_CHARS && keep > 1) {
            keep--
            pathLine = buildPath(keep)
            out = assemble(pathLine, nowLine)
        }
        if (out.length > RECAP_MAX_CHARS) {
            val availPath = (RECAP_MAX_CHARS - header.length - nowLine.length - 4).coerceAtLeast(0)
            val shortPath = if (availPath > 4 && pathLine.length > availPath) "…" + pathLine.takeLast(availPath - 1) else pathLine.take(availPath)
            out = assemble(shortPath, nowLine)
        }
        return if (out.length <= RECAP_MAX_CHARS) out else out.take(RECAP_MAX_CHARS - 1) + "…"
    }

    /** One `task <id> --wait` holds the connection at most this long (a client's HTTP timeout must not cut it). */
    const val WAIT_MAX_MS = 120_000L
    const val RECAP_MAX_CHARS = 600
    const val RECAP_MAX_STEPS = 12
    private const val RECAP_MAX_CLICKABLES = 8

    /** For status / diag: running tasks, without their arguments. */
    internal fun cancelForHeat() = synchronized(tasks) { tasks.values.filter { it.result == null }.forEach { it.call.cancelled = true } }

    fun running(): Int = synchronized(tasks) { tasks.values.count { it.result == null } }
}
