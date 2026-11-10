package com.kilon.agentvscreen.cmd

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

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
                               val data: org.json.JSONObject? = null, val softFail: Boolean = false) {
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
           val trustUntil: Long = 0L) {
    /** Set when the caller went away or cancelled the task: the command stops before its next step. */
    @Volatile var cancelled = false
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
class Note(val code: String?, val text: String) {
    fun line(): String = if (code == null) text else com.kilon.agentvscreen.tr("$code：$text", "$code: $text")
    fun json(): org.json.JSONObject = org.json.JSONObject().put("code", code ?: org.json.JSONObject.NULL).put("text", text)
        .also { if (code != null) it.put("next", Codes.next(code)) }
}

/** The notes of one reply, in order, without repeats. */
class Notes {
    val list = ArrayList<Note>()
    operator fun plusAssign(text: String) { if (list.none { it.code == null && it.text == text }) list += Note(null, text) }
    fun add(code: String, text: String) { if (list.none { it.code == code && it.text == text }) list += Note(code, text) }
    fun addAll(o: Notes) { o.list.forEach { n -> if (list.none { it.code == n.code && it.text == n.text }) list += n } }
    val isEmpty: Boolean get() = list.isEmpty()
    /** The code that decides the reply's outcome: the first coded note that is not mere information. */
    fun firstBlocking(): String? = list.firstOrNull { it.code != null && Codes.of(it.code)?.next != Codes.Next.NONE && it.code !in INFO }?.code
    companion object { val INFO = setOf("NO_CHANGE", "MAIN_SCREEN_CHANGED", "KEEP_LIMIT") }
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
    fun json(): org.json.JSONObject = org.json.JSONObject().put("n", n).put("step", step).put("status", status)
        .also { o ->
            fact?.let { o.put("fact", it) }; code?.let { o.put("code", it) }
            waitedFor?.let { o.put("waited_for", it).put("met", waitMet == true).put("waited_ms", waitMs ?: 0) }
            point?.let { o.put("x", it.first).put("y", it.second) }
            changed?.let { o.put("changed", it) }
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

    fun save(ctx: Context, bmp: Bitmap, tag: String): String? = runCatching {
        val d = dir(ctx)
        val safeTag = tag.replace(Regex("[^A-Za-z0-9_-]"), "").take(24).ifEmpty { "shot" }
        val name = "vscreen_${safeTag}_${System.currentTimeMillis()}_${(Math.random() * 1e6).toInt()}.jpg"
        FileOutputStream(File(d, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        d.listFiles()?.filter { it.name.startsWith("vscreen_") }?.sortedBy { it.lastModified() }
            ?.let { fs -> if (fs.size > KEEP) fs.take(fs.size - KEEP).forEach { it.delete() } }
        REF + name
    }.getOrNull()

    fun file(ctx: Context, name: String): File? = if (NAME.matches(name)) File(dir(ctx), name).takeIf { it.isFile } else null

    /** Names referenced in [text], in order of first appearance. */
    fun refs(text: String): List<String> = IN_TEXT.findAll(text).map { it.groupValues[1] }.distinct().toList()
}
