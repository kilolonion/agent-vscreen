package com.kilon.agentvscreen.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 1.4.0-beta3: what the user sees as "a task" — one turn of one session (Turns: from its first command until it says
 * `done` or goes quiet), with what it was called (`vscreen title`), which app it was in, how many commands, how it
 * ended, and four small frames. The 记录 timeline and the home page's 刚才 read this; the commands themselves stay in
 * the audit log (a task's detail page reads them from there, between its start and end).
 *
 * Kept in files/tasks: log.jsonl (newest last, the last [KEEP]) and the frames beside it. A frame is taken after a
 * command at most every [FRAME_GAP_MS]; a task keeps its first frame and its last three.
 */
object TaskLog {
    const val KEEP = 200
    private const val COMPACT_AT = KEEP * 2
    private const val FRAME_GAP_MS = 2_500L
    private const val FRAMES = 4
    private const val QUIET_MS = 10 * 60_000L

    /** 2.0.0-beta3 (3.8): compact step/page record used by `task recap` without re-reading the screen. */
    data class RecapStep(
        val cmd: String,
        val steps: List<Pair<String, String?>>,
        val pageId: String?,
        val pageTitle: String?,
        val code: String,
        val exit: Int,
    )

    class Task(
        val id: Long, val session: String, val token: String, val start: Long,
        var end: Long = 0L, var title: String? = null, var pkg: String? = null,
        var commands: Int = 0, var lastCode: String = "OK", var failed: Int = 0, var stoppedByUser: Boolean = false,
        val frames: MutableList<String> = ArrayList(), @Volatile var lastFrameAt: Long = 0L,
        val recap: MutableList<RecapStep> = ArrayList(),
    ) {
        /** When its last command came (only while open). */
        @Volatile var lastAt: Long = start
        val running get() = end == 0L
        fun json(): JSONObject = JSONObject().put("id", id).put("session", session).put("token", token).put("start", start).put("end", end)
            .put("title", title ?: JSONObject.NULL).put("pkg", pkg ?: JSONObject.NULL).put("commands", commands).put("last_code", lastCode)
            .put("failed", failed).put("stopped_by_user", stoppedByUser).put("frames", JSONArray(frames))
        companion object {
            fun of(o: JSONObject) = Task(o.optLong("id"), o.optString("session"), o.optString("token"), o.optLong("start"), o.optLong("end"),
                o.optString("title").takeIf { it.isNotEmpty() && it != "null" }, o.optString("pkg").takeIf { it.isNotEmpty() && it != "null" },
                o.optInt("commands"), o.optString("last_code", "OK"), o.optInt("failed"), o.optBoolean("stopped_by_user"),
                o.optJSONArray("frames")?.let { a -> MutableList(a.length()) { a.optString(it) } } ?: ArrayList())
        }
    }

    @Volatile private var dir: File? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "vscreen-tasklog").apply { isDaemon = true } }
    /**
     * 1.4.2-beta6: pictures waiting on the worker. The queue itself has no bound and a picture job holds a full-size
     * frame; on a slow disk (or a worker stuck in one write) they piled up. Past [MAX_PICS_QUEUED] a new picture is
     * skipped (the task keeps its text, and the next picture comes with the next command).
     */
    private val picsQueued = java.util.concurrent.atomic.AtomicInteger()
    private const val MAX_PICS_QUEUED = 3

    private fun queuePicture(job: () -> Unit, skipped: () -> Unit = {}) {
        if (picsQueued.incrementAndGet() > MAX_PICS_QUEUED) { picsQueued.decrementAndGet(); skipped(); return }
        try {
            worker.execute { try { runCatching(job) } finally { picsQueued.decrementAndGet() } }
        } catch (e: java.util.concurrent.RejectedExecutionException) { picsQueued.decrementAndGet(); skipped() }
    }
    internal fun picturesQueuedForTest(): Int = picsQueued.get()
    private val open = HashMap<String, Task>()
    private val done = ArrayList<Task>()
    @Volatile private var loaded = false
    private var diskLines = 0
    private var lastId = 0L

    @Volatile private var app: Context? = null
    fun init(ctx: Context) = synchronized(this) { app = ctx.applicationContext; dir = File(ctx.filesDir, "tasks").apply { mkdirs() }; loaded = false; diskLines = 0; lastId = 0L; open.clear(); done.clear() }
    internal fun flushForTest() { worker.submit {}.get() }
    fun frameFile(name: String): File? = dir?.let { File(it, name) }?.takeIf { it.isFile && name.matches(Regex("^t[0-9_]+(s[0-9]+)?\\.jpg$")) }

    // ── 1.4.0-beta5: every step of a task, to open one by one on the task's page ──
    private const val STEP_TEXT = 4_000
    private const val STEP_FRAMES = 60
    private const val STEP_PICS_TASKS = 20

    private fun stepsFile(id: Long) = dir?.let { File(it, "t$id.steps.jsonl") }

    /**
     * One command of a task: the command as logged (typed text as a length), how it ended, how long it took, what the
     * agent was told (pictures left out), and a small copy of the picture it got (the reply's own pictures are cleared
     * with the screen, so a copy is kept here, at most [STEP_FRAMES] per task).
     */
    private fun saveStep(t: Task, n: Int, argv: List<String>, code: String, exit: Int, ms: Long, output: String,
                         res: com.kilon.agentvscreen.cmd.NativeOffloadResult? = null) {
        val f = stepsFile(t.id) ?: return
        val allShots = com.kilon.agentvscreen.cmd.ShotStore.refs(output)
        val shot = allShots.firstOrNull()
        var frame: String? = null
        if (shot != null && n <= STEP_FRAMES) runCatching {
            val c = app ?: return@runCatching
            val src = com.kilon.agentvscreen.cmd.ShotStore.file(c, shot) ?: return@runCatching
            val b = android.graphics.BitmapFactory.decodeFile(src.path) ?: return@runCatching
            val name = "t${t.id}_s$n.jpg"
            Pictures.write(b, File(f.parentFile, name), Pictures.Use.STEP); b.recycle(); frame = name
        }
        val sentShots = if (res != null) com.kilon.agentvscreen.api.ImagePolicy.pick(res, allShots) else allShots.take(com.kilon.agentvscreen.api.ImagePolicy.MAX)
        val meteredText = com.kilon.agentvscreen.cmd.ShotStore.IN_TEXT.replace(output) { m ->
            val sn = m.groupValues[1]
            if (sn in sentShots) com.kilon.agentvscreen.api.ApiConfig.baseUrl + "/v2/shot/" + sn else com.kilon.agentvscreen.api.ImagePolicy.leftOut()
        }
        val cost = Meter.of(meteredText, Meter.sentDimensions(app, res, sentShots))
        val text = com.kilon.agentvscreen.cmd.ShotStore.IN_TEXT.replace(output, "").trim().take(STEP_TEXT)
        val o = JSONObject().put("n", n).put("at", System.currentTimeMillis()).put("cmd", com.kilon.agentvscreen.host.Audit.redact(argv))
            .put("code", code).put("exit", exit).put("ms", ms).put("text", text).put("frame", frame ?: JSONObject.NULL)
            .put("chars", cost.chars).put("est_tokens", cost.estTokens).put("images", cost.imagesJson()).put("image_tokens", cost.imageTokens)
        res?.data?.optJSONObject("screen")?.optJSONObject("page")?.let { o.put("page", it) }
        f.appendText(o.toString() + "\n")
    }

    /** The steps of task [id], oldest first (empty for tasks from before 1.4.0-beta5). */
    fun steps(id: Long): List<JSONObject> = runCatching {
        stepsFile(id)?.takeIf { it.isFile }?.readLines()?.mapNotNull { l -> runCatching { JSONObject(l) }.getOrNull() }
    }.getOrNull() ?: emptyList()

    /** 2.0.0-beta3 (3.8): recorded step/page trail for [session]'s open task (in-memory, no screen read). */
    fun recapSteps(session: String): List<RecapStep> = synchronized(this) {
        open[session]?.recap?.toList().orEmpty()
    }

    internal fun resetOpenForTest() = synchronized(this) { open.clear() }

    private fun extractRecap(argv: List<String>, code: String, exit: Int, res: com.kilon.agentvscreen.cmd.NativeOffloadResult?): RecapStep? {
        val sub = argv.firstOrNull() ?: return null
        if (sub !in setOf("launch", "act", "handoff")) return null
        val data = res?.data
        val page = data?.optJSONObject("screen")?.optJSONObject("page")
        val pageId = page?.optString("id")?.takeIf { it.isNotBlank() && it != "null" }
        val pageTitle = page?.optString("title")?.takeIf { it.isNotBlank() && it != "null" }
        val stepList = ArrayList<Pair<String, String?>>()
        val arr = data?.optJSONArray("steps")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val status = s.optString("status")
                if (status == "skipped" || status == "tried") continue
                val raw = s.optString("step").trim()
                if (raw.isEmpty()) continue
                val clean = raw.substringBefore(" until ").trim()
                val fail = when {
                    status != "done" -> s.optString("code").takeIf { it.isNotBlank() && it != "null" } ?: status.uppercase()
                    s.has("changed") && !s.optBoolean("changed", true) -> "NO_CHANGE"
                    else -> null
                }
                stepList += clean to fail
            }
        }
        return RecapStep(sub, stepList, pageId, pageTitle, code, exit)
    }

    private fun dropFiles(t: Task) {
        t.frames.forEach { n -> dir?.let { File(it, n).delete() } }
        dir?.listFiles { f -> f.name.startsWith("t${t.id}_s") || f.name == "t${t.id}.steps.jsonl" }?.forEach { it.delete() }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val f = dir?.let { File(it, "log.jsonl") } ?: return
        if (!f.isFile) { diskLines = 0; return }
        val lines = runCatching { f.readLines() }.getOrNull() ?: run { diskLines = 0; return }
        diskLines = lines.size
        val byId = LinkedHashMap<Long, Task>()
        for (l in lines) {
            if (l.isBlank()) continue
            val t = runCatching { Task.of(JSONObject(l)) }.getOrNull() ?: continue
            byId.remove(t.id)
            byId[t.id] = t
            if (t.id > lastId) lastId = t.id
        }
        val all = byId.values.toList()
        if (all.size > KEEP) {
            all.subList(0, all.size - KEEP).forEach { dropFiles(it) }
            done.addAll(all.takeLast(KEEP))
        } else {
            done.addAll(all)
        }
    }

    private fun compactLocked(d: File, f: File) {
        val tmp = File(d, "log.jsonl.tmp")
        runCatching {
            val content = if (done.isEmpty()) "" else done.joinToString("\n") { it.json().toString() } + "\n"
            tmp.writeText(content)
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
            diskLines = done.size
        }
    }

    /** A command of [session] finished with [code] (called for every audited command). */
    fun onCommand(session: String, token: String, argv: List<String>, code: String, exit: Int, ms: Long = 0L, output: String = "",
                  res: com.kilon.agentvscreen.cmd.NativeOffloadResult? = null) {
        val sub = argv.firstOrNull() ?: return
        if (sub in setOf("status", "guide", "help", "remembered", "events", "diag", "version")) return
        val now = System.currentTimeMillis()
        // 1.4.0-beta5 (test: home said "running" after release / stop): the log hears of a command after it ran, so
        // `release` ended the turn and then opened a new one for itself. A command that ends the turn is the last step
        // of the open task (none open: nothing to log), and the task ends with it.
        val ends = sub in setOf("done", "release", "stop")
        if (ends && synchronized(this) { open[session] } == null) return
        val recapEntry = extractRecap(argv, code, exit, res)
        val t = synchronized(this) {
            load()
            open.getOrPut(session) {
                val id = maxOf(now, lastId + 1)
                lastId = id
                Task(id, session, token, now)
            }.also { t ->
                t.commands++; t.lastCode = code; t.lastAt = now; if (exit != 0) t.failed++
                BackgroundOperation.state.value.agentTitle?.let { t.title = it }
                if (recapEntry != null) t.recap += recapEntry
            }
        }
        val n = t.commands
        worker.execute { runCatching { saveStep(t, n, argv, code, exit, ms, output, res) } }
        if (ends) { onTurnEnd(session); return }
        if (now - t.lastFrameAt < FRAME_GAP_MS) return
        t.lastFrameAt = now
        queuePicture({ capture(t) })
    }

    /**
     * 1.4.2: the picture as the turn ends, taken now on the caller's thread (the screen is still there). Before, a task
     * kept only the pictures taken after its commands, so the home page's resting picture was the screen as it was at
     * the last command (or a task before it), not as it was left.
     */
    fun captureNow(session: String) {
        val t = synchronized(this) { open[session] } ?: return
        val d = BackgroundOperation.previewDisplayFor(t.session) ?: return
        // Only the copy is taken here (callers may be on the main thread); the file is written on the log's worker,
        // which writes the task's record after it (same single thread), so the record lists this picture.
        val copy = d.latestFrame()?.takeIf { !it.isRecycled && it.width > 0 }
            ?.let { runCatching { it.copy(it.config ?: android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull() } ?: return
        queuePicture({ try { writeFrame(t, copy) } finally { copy.recycle() } }, skipped = { copy.recycle() })
    }

    private fun capture(t: Task) {
        val d = BackgroundOperation.previewDisplayFor(t.session) ?: return
        VScreenShell.topPackage(d.displayId)?.let { p -> if (p !in VScreenShell.protectedPackages) t.pkg = p }
        val bmp = d.latestFrame()?.takeIf { !it.isRecycled && it.width > 0 } ?: return
        writeFrame(t, bmp)
    }

    private fun writeFrame(t: Task, bmp: android.graphics.Bitmap) {
        val dir = dir ?: return
        val name = "t${t.id}_${System.currentTimeMillis() % 1_000_000}.jpg"
        Pictures.write(bmp, File(dir, name), Pictures.Use.TASK)
        synchronized(this) {
            t.frames += name
            if (t.frames.size > FRAMES) t.frames.removeAt(1).let { File(dir, it).delete() }
        }
    }

    /** The turn of [session] is over (done, quiet, released); [byUser]: the user pressed stop. */
    fun onTurnEnd(session: String, byUser: Boolean = false) {
        val t = synchronized(this) { open.remove(session) } ?: return
        t.end = maxOf(t.lastAt, if (System.currentTimeMillis() - t.lastAt > QUIET_MS) t.lastAt else System.currentTimeMillis()); t.stoppedByUser = t.stoppedByUser || byUser
        synchronized(this) { load(); done += t }
        worker.execute {
            synchronized(this) {
                val d = dir ?: return@execute
                val drop = if (done.size > KEEP) done.subList(0, done.size - KEEP).toList() else emptyList()
                drop.forEach { old -> dropFiles(old) }
                // 1.4.0-beta6: step pictures are full size now; only the newest tasks keep theirs (their text stays).
                if (done.size > STEP_PICS_TASKS) done.subList(0, done.size - STEP_PICS_TASKS).forEach { old ->
                    d.listFiles { f -> f.name.startsWith("t${old.id}_s") && f.name.endsWith(".jpg") }?.forEach { it.delete() }
                }
                if (drop.isNotEmpty()) done.removeAll(drop.toSet())
                // 1.4.1-beta1: append-only per turn, compacting only when log.jsonl grows past COMPACT_AT lines.
                val f = File(d, "log.jsonl")
                if (diskLines + 1 >= COMPACT_AT) {
                    compactLocked(d, f)
                } else {
                    runCatching { f.appendText(t.json().toString() + "\n"); diskLines++ }
                }
            }
        }
    }

    /** The user pressed stop: the running task of [session] ends as stopped by the user. */
    fun stoppedByUser(session: String) { synchronized(this) { open[session]?.stoppedByUser = true } }

    /** Newest first: running tasks, then finished ones. */
    fun recent(n: Int = KEEP): List<Task> {
        // 1.4.0-beta4: an agent that went away without `done` (or a screen released under it) left its task "running"
        // for ever; the home page then said "last: … running" over a resting screen. Quiet for QUIET_MS = over.
        val now = System.currentTimeMillis()
        synchronized(this) { open.values.filter { now - it.lastAt > QUIET_MS }.map { it.session } }.forEach { onTurnEnd(it) }
        return recentNow(n)
    }

    private fun recentNow(n: Int): List<Task> = synchronized(this) {
        load()
        (open.values.sortedByDescending { it.start } + done.asReversed()).take(n)
    }

    /** Whether any agent has ever run a task here (the home page's first-use guide). */
    fun everUsed(): Boolean = synchronized(this) { load(); open.isNotEmpty() || done.isNotEmpty() }
}
