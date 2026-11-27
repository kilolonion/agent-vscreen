package com.kilon.agentvscreen.cmd

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/** One command call: the words after `vscreen`, and the caller's session (one session owns the screen at a time). */
data class NativeOffloadRequest(val argv: List<String>, val sessionId: String?)

/**
 * What a command returns. 1.2: the outcome is typed, not read back out of the text.
 *  - [exitCode] 0 = done as expected; 1 = refused, failed, or a stretch that stopped at a step; 2 = usage.
 *  - [code] one of [Codes] ("OK" when fine).
 *  - [data] the structured result (steps, screen, notes for act / launch; the JSON of status, apps, fix …).
 *  - [output] the same result as text for a model to read. Pictures are referenced in it as [ShotStore.REF]name.
 *  - [softFail] a four-block reply whose stretch did not go as expected: before 1.2 (and still on /v1) these exit 0.
 */
data class NativeOffloadResult(val exitCode: Int, val output: String, val code: String? = null,
                               val data: org.json.JSONObject? = null, val softFail: Boolean = false,
                               /** 2.0.0-beta5 (5.6): callback run right after the HTTP/MCP reply is flushed (e.g. --hold freeze). */
                               val afterSend: (() -> Unit)? = null) {
    val codeOrDefault: String get() = code ?: when (exitCode) { 0 -> "OK"; 2 -> "INVALID_ARGS"; else -> "OPERATION_FAILED" }
}

/**
 * 1.2: who is calling, for the thread that runs the command. Deep code (the wait loop, the consent check, the audit
 * log) reads it from here instead of every function carrying it along.
 */
class Call(val session: String, val tokenId: String = "main", val tokenName: String = "main",
           val scopes: Set<String> = setOf("look", "operate", "system"), val peer: String? = null,
           val requestId: String? = null, val lang: String? = null,
           /** The user gave this token a period without confirmations (epoch ms; 0 = none). */
           val trustUntil: Long = 0L,
           /** 1.4: how the caller reaches us (shell CLI, MCP, raw HTTP): texts for the agent name commands its way. */
           val via: com.kilon.agentvscreen.api.Abilities.Via = com.kilon.agentvscreen.api.Abilities.Via.CLI,
           /** 2.0.0-beta7 (7.7): network origin (local / tailnet / public); separate from [via] reply format. */
           val source: Source = Source.LOCAL,
           /** 2.0.0-beta9: the real public address of a public MCP caller (from tsbridge's X-AVS-Public-Src), for the audit log only. */
           val srcAddr: String? = null) {
    enum class Source(val key: String) {
        LOCAL("local"),
        TAILNET("tailnet"),
        PUBLIC("public"),
    }
    /** Set when the caller went away or cancelled the task: the command stops before its next step. */
    @Volatile var cancelled = false
    /**
     * 1.4.2-beta6: the control epoch the call arrived in ([com.kilon.agentvscreen.core.Control]). Once the user takes
     * over, stops, or hands back after it arrived, nothing it planned may change the screen any more.
     */
    @Volatile var epoch: Long = com.kilon.agentvscreen.core.Control.epoch
    /** 1.3: when the phone received the call (wall clock), to tell phone-side time from time outside the phone. */
    val receivedAt: Long = System.currentTimeMillis()
    /** 1.3: set when this call runs as a task (--async): nobody is holding a connection open for it. */
    @Volatile var taskId: String? = null
    /** Told each finished step while a long command runs (MCP progress, task status). */
    @Volatile var progress: ((String) -> Unit)? = null
    /** Steps reported so far (task status shows the last one). */
    @Volatile var lastProgress: String? = null
    fun report(line: String) { lastProgress = line; runCatching { progress?.invoke(line) } }
    val trusted: Boolean get() = trustUntil > System.currentTimeMillis()

    companion object {
        private val tl = ThreadLocal<Call?>()
        fun current(): Call? = tl.get()
        fun <T> with(c: Call, block: () -> T): T {
            val before = tl.get(); tl.set(c)
            try { return block() } finally { if (before == null) tl.remove() else tl.set(before) }
        }
    }
}

/** A line of the notes block: [code] from [Codes] (null = plain information) and its text. */
class Note(val code: String?, val text: String, val screenExcerpts: List<String> = emptyList()) {
    fun line(): String = if (code == null) text else com.kilon.agentvscreen.tr("$code：$text", "$code: $text")
    fun json(): org.json.JSONObject = org.json.JSONObject()
        .put("code", code ?: org.json.JSONObject.NULL)
        .put("text", text)
        .put("system_text", text)
        .also {
            if (code != null) it.put("next", Codes.next(code))
            if (screenExcerpts.isNotEmpty()) {
                it.put("screen_excerpts", org.json.JSONArray(screenExcerpts.map { ex -> ScreenText.clean(ex, maxLen = 120) }))
            }
        }
}

/** The notes of one reply, in order, without repeats. */
class Notes {
    val list = ArrayList<Note>()
    operator fun plusAssign(text: String) { if (list.none { it.code == null && it.text == text }) list += Note(null, text) }
    fun add(code: String, text: String, screenExcerpts: List<String> = emptyList()) {
        if (list.none { it.code == code && it.text == text }) list += Note(code, text, screenExcerpts)
    }
    fun addAll(o: Notes) { o.list.forEach { n -> if (list.none { it.code == n.code && it.text == n.text }) list += n } }
    val isEmpty: Boolean get() = list.isEmpty()
    /** The code that decides the reply's outcome: the first coded note that is not mere information. */
    fun firstBlocking(): String? = list.firstOrNull { it.code != null && Codes.of(it.code)?.next != Codes.Next.NONE && it.code !in INFO }?.code
    companion object { val INFO = setOf("NO_CHANGE", "SNAP_BACK", "MAIN_SCREEN_CHANGED", "KEEP_LIMIT", "OCR_DOWN", "HOLD_UNSUPPORTED", "HOLD_REFUSED", "RULE_OFF", "FINANCE_APP_RISK") }
}

/**
 * One step of a stretch as it ended. [status]: done | missed (done, but its expectation never came) | refused (not done)
 * | stopped (a stop condition fired here) | skipped (an earlier step ended the stretch). [text] is its line in the
 * done block; the other fields are the same facts for programs.
 */
class StepOut(val n: String, val step: String, var status: String, var text: String) {
    var fact: String? = null
    var code: String? = null
    var waitedFor: String? = null
    var waitMet: Boolean? = null
    var waitMs: Long? = null
    var point: Pair<Int, Int>? = null
    /** false = the picture did not visibly change after this action. */
    var changed: Boolean? = null
    /** 2.0.0-beta3 (3.2): true when a `type` step confirmed its text landed in the input box. */
    var typeVerified: Boolean = false
    /** 2.0.0-beta3 (3.13): the condition kind that actually satisfied this step's `until` / `repeat … until`. */
    internal var metCondKind: CondKind? = null
    /** 2.0.0-beta3 (3.13): true for agent-owned action steps and enclosing `repeat … until` completion entries. */
    var isActionOrRepeatUntil: Boolean = false
    /** 2.0.0-beta8 (C4): true when a `seek` step satisfied its `until` at start without touching the screen. */
    var alreadyAtStart: Boolean = false
    /** 2.0.0-beta4 (4.4): drag pickup/follow/after-release record. */
    internal var gesture: GestureRecord? = null
    /** 2.0.0-beta4 (4.7): condition poll duration p50 / max (ms) for closed-loop gestures. */
    var pollMsP50: Long? = null
    var pollMsMax: Long? = null
    /** 2.0.0-beta5 (5.7 #2): color condition hit frame age in ms (`ColorWatch.lastImgAgeMs`). */
    var imgAgeMs: Long? = null
    /** 2.0.0-beta16 (Item 8): concise label for `task recap` on control-flow containers; empty string omits duplicate closing entries. */
    var recapLabel: String? = null
    /** Input delivery channel when non-default (e.g. `a11y` or `wake`). */
    var via: String? = null
    /** Sanitized non-password input-box readback for `screen_data.readback` isolation. */
    var readback: String? = null
    fun json(): org.json.JSONObject = org.json.JSONObject().put("n", n).put("step", step).put("status", status)
        .also { o ->
            fact?.let { o.put("fact", it) }; code?.let { o.put("code", it) }
            via?.let { o.put("via", it) }
            waitedFor?.let { o.put("waited_for", it).put("met", waitMet == true).put("waited_ms", waitMs ?: 0) }
            point?.let { o.put("x", it.first).put("y", it.second) }
            changed?.let { o.put("changed", it) }
            recapLabel?.let { o.put("recap", it) }
            val g = gesture?.json() ?: if (pollMsP50 != null || pollMsMax != null || imgAgeMs != null) {
                org.json.JSONObject().also { go ->
                    pollMsP50?.let { go.put("poll_ms_p50", it) }
                    pollMsMax?.let { go.put("poll_ms_max", it) }
                    imgAgeMs?.let { go.put("img_age_ms", it) }
                }
            } else null
            g?.let { o.put("gesture", it) }
        }
}
fun interface NativeOffloadHandler {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult
}

/**
 * Pictures a command made. They are written once to files/shots and referenced in the text as `vscreen-shot://<name>`;
 * the transport decides what the caller sees: an http URL (plain HTTP), a downloaded local path (the CLI) or an inline
 * image (MCP). Only the newest [KEEP] are kept.
 */
object ShotStore {
    const val REF = "vscreen-shot://"
    private const val KEEP = 300
    val NAME = Regex("^vscreen_[A-Za-z0-9_-]+\\.jpg$")
    val IN_TEXT = Regex(Regex.escape(REF) + "(vscreen_[A-Za-z0-9_-]+\\.jpg)")

    fun dir(ctx: Context): File = File(ctx.filesDir, "shots").apply { mkdirs() }

    /**
     * Keep [bmp] (already sized by the caller) as a picture for [use]. [sent]: it may go into a model context by
     * itself, so its compact copy (Pictures.Use.SENT) is written now from the same pixels, not re-encoded later.
     */
    fun save(ctx: Context, bmp: Bitmap, tag: String, use: com.kilon.agentvscreen.core.Pictures.Use = com.kilon.agentvscreen.core.Pictures.Use.LOOK, sent: Boolean = false): String? = runCatching {
        val d = dir(ctx)
        val safeTag = tag.replace(Regex("[^A-Za-z0-9_-]"), "").take(24).ifEmpty { "shot" }
        val name = "vscreen_${safeTag}_${System.currentTimeMillis()}_${(Math.random() * 1e6).toInt()}.jpg"
        com.kilon.agentvscreen.core.Pictures.write(bmp, File(d, name), use, fit = false)
        if (sent) runCatching { com.kilon.agentvscreen.core.Pictures.write(bmp, File(d, "vscreen_c" + name.removePrefix("vscreen")), com.kilon.agentvscreen.core.Pictures.Use.SENT) }
        d.listFiles()?.filter { it.name.startsWith("vscreen_") }?.sortedBy { it.lastModified() }
            ?.let { fs -> if (fs.size > KEEP) fs.take(fs.size - KEEP).forEach { it.delete() } }
        REF + name
    }.getOrNull()

    /** 1.2: screenshots do not outlive the screen they were taken of: cleared (after a short grace, so the last reply's pictures can still be fetched) when the screen is released. */
    fun clearLater(ctx: Context, graceMs: Long = 120_000L) {
        val cut = System.currentTimeMillis()
        Thread({
            try { Thread.sleep(graceMs) } catch (e: InterruptedException) { return@Thread }
            runCatching { dir(ctx).listFiles()?.filter { it.name.startsWith("vscreen_") && it.lastModified() <= cut }?.forEach { it.delete() } }
        }, "vscreen-shots-clear").apply { isDaemon = true }.start()
    }

    fun file(ctx: Context, name: String): File? = if (NAME.matches(name)) File(dir(ctx), name).takeIf { it.isFile } else null

    /** Names referenced in [text], in order of first appearance. */
    fun refs(text: String): List<String> = IN_TEXT.findAll(text).map { it.groupValues[1] }.distinct().toList()
}
