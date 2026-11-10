package com.kilon.agentvscreen.host

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 1.2: the user's record of what agents did on this phone. One JSON line per command, kept in the app's private storage
 * (files/audit/audit.jsonl, rotated at 1 MB, one older file kept) and shown only inside the app: who (token name, and
 * the device when it came over the network), which session, the command with typed text replaced by its length, the
 * outcome code, and every confirmation the user gave or refused. It is written before the reply is sent, so a command
 * cannot run without leaving its line.
 */
object Audit {
    private const val MAX = 1_000_000L
    @Volatile private var dir: File? = null

    fun init(ctx: Context) { dir = File(ctx.filesDir, "audit").apply { mkdirs() } }

    private val COND = Regex("(?i)\\b((?:text|gone):)((?:(?!\\s+or\\s|\\s+\\d+(?:\\.\\d+)?(?:ms|s|m)?(?:[;；\\n\"]|$))[^;；\\n\"<])+)")
    private val TYPE = Regex("(?i)(^|[;；\\n])(\\s*)(type\\+?|输入)\\s+((?:(?!\\s+until\\s)[^;；\\n])*)")

    /** A command line with what was typed replaced by its length (passwords and messages never reach the log). */
    fun redact(argv: List<String>): String {
        // 1.2.0-beta2: what was typed is removed everywhere in the command, not only after `type`: the same words
        // usually come back in the step's own `until text:…` and in --stop.
        val typed = argv.flatMap { a -> TYPE.findAll(a).map { it.groupValues[4].trim() } }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        return argv.joinToString(" ") { a ->
            var r = TYPE.replace(a) { m -> "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]} <${m.groupValues[4].trim().length} chars>" }
            for (t in typed) r = r.replace(t, "<${t.length} chars>")
            // 1.2.0: the words a condition waits for are often what was typed in an earlier command (a search term,
            // a message): they are kept as a length too.
            r = COND.replace(r) { m -> "${m.groupValues[1]}<${m.groupValues[2].length} chars>" }
            if (r.any { it.isWhitespace() }) "\"$r\"" else r
        }.take(600)
    }

    fun command(token: String, peer: String?, session: String, argv: List<String>, code: String, exit: Int, ms: Long, requestId: String? = null) =
        write(JSONObject().put("kind", "command").put("token", token).put("peer", peer ?: JSONObject.NULL).put("session", session)
            .put("cmd", redact(argv)).put("code", code).put("exit", exit).put("ms", ms).also { o -> requestId?.let { o.put("request_id", it.take(64)) } })

    fun consent(token: String, session: String, what: String, answer: String) =
        write(JSONObject().put("kind", "consent").put("token", token).put("session", session).put("what", what.take(200)).put("answer", answer))

    fun security(what: String, detail: String) = write(JSONObject().put("kind", "security").put("what", what).put("detail", detail.take(300)))

    @Synchronized private fun write(o: JSONObject) {
        val d = dir ?: return
        quiet("audit") {
            val f = File(d, "audit.jsonl")
            if (f.length() > MAX) { File(d, "audit.1.jsonl").delete(); f.renameTo(File(d, "audit.1.jsonl")) }
            f.appendText(o.put("at", System.currentTimeMillis()).toString() + "\n")
        }
    }

    /** The newest [n] entries, newest first. */
    @Synchronized fun tail(n: Int = 200): List<JSONObject> {
        val d = dir ?: return emptyList()
        val lines = ArrayList<String>()
        for (name in listOf("audit.1.jsonl", "audit.jsonl")) quiet("audit-read") { File(d, name).takeIf { it.isFile }?.readLines()?.let { lines += it } }
        return lines.takeLast(n).reversed().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    }

    @Synchronized fun clear() { dir?.listFiles()?.forEach { it.delete() } }
}
