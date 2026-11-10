package com.kilon.agentvscreen.host

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logcat plus a small rolling file (files/logs/agent-vscreen.log) that `status` and the app can show. */
object AppLogger {
    @Volatile private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "logs").apply { mkdirs() }.let { File(it, "agent-vscreen.log") }
    }

    fun info(tag: String, msg: String) { Log.i(tag, msg); write("I", tag, msg) }
    fun warning(tag: String, msg: String) { Log.w(tag, msg); write("W", tag, msg) }
    fun error(tag: String, msg: String, t: Throwable? = null) { Log.e(tag, msg, t); write("E", tag, msg + (t?.let { " / ${it.javaClass.simpleName}: ${it.message}" } ?: "")) }

    fun tail(lines: Int = 200): String = runCatching { file?.readLines()?.takeLast(lines)?.joinToString("\n") }.getOrNull() ?: ""

    @Synchronized private fun write(level: String, tag: String, msg: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > 1_000_000) { f.renameTo(File(f.parentFile, "agent-vscreen.1.log")) }
            f.appendText("${fmt.format(Date())} $level/$tag: $msg\n")
        }
    }
}

/**
 * 1.2: run [block]; an exception is not thrown on, but it is no longer lost either: it is logged with [tag] and
 * counted, and `vscreen diag` lists the counts. Use it where a failure really may be ignored (best-effort cleanup,
 * an optional probe); before 1.2 these places were bare `runCatching { }` that swallowed everything silently.
 */
inline fun <T> quiet(tag: String, block: () -> T): T? = try { block() } catch (t: Throwable) {
    if (t is InterruptedException) Thread.currentThread().interrupt()
    Swallowed.note(tag, t); null
}

/** What [quiet] swallowed: per tag, how often and the last one. */
object Swallowed {
    class Entry(var count: Int, var last: String, var at: Long)
    private val map = LinkedHashMap<String, Entry>()

    fun note(tag: String, t: Throwable) {
        val msg = "${t.javaClass.simpleName}: ${t.message?.take(160)}"
        val first = synchronized(map) {
            val e = map[tag]
            if (e == null) { map[tag] = Entry(1, msg, System.currentTimeMillis()); true } else { e.count++; e.last = msg; e.at = System.currentTimeMillis(); false }
        }
        // The first of a kind goes to the log in full; repeats only count (a loop must not flood the file).
        if (first) AppLogger.warning("quiet/$tag", msg)
    }

    fun snapshot(): List<Pair<String, Entry>> = synchronized(map) { map.map { it.key to Entry(it.value.count, it.value.last, it.value.at) } }
}
