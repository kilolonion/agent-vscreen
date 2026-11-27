package com.kilon.agentvscreen.core

import android.os.Looper

/**
 * 1.4.2: one rule for every process of ours (the app, and the screen service in the Shizuku process): an exception
 * that escapes a background thread ends that thread, never the process.
 *
 * Before, any helper thread with an unguarded read (a child process's output stream closed under it, a binder call to
 * a service that just died) threw to the top and Android killed the whole process: the foreground service, the HTTP
 * server, the tailnet node and the virtual screen all went with it, and from outside it looked like "the service froze".
 * Guarding every `Thread { }` by hand does not hold (there are dozens, and the next one is written tomorrow), so the
 * guard sits where all of them end up: the default uncaught-exception handler.
 *
 * Still fatal, as before: the main thread (the UI cannot go on after it), and VirtualMachineError (out of memory, stack
 * overflow: the process is not in a state to continue). Everything else is logged once per kind and counted, and the
 * count is shown in status (`background_thread_deaths`), so a dying loop is visible instead of silent.
 */
object ThreadGuard {
    @Volatile var deaths = 0; private set
    @Volatile var last: String? = null; private set
    @Volatile private var installed = false

    /** Pure: whether an exception on this thread ends the process. */
    internal fun fatal(isMainThread: Boolean, e: Throwable): Boolean = isMainThread || e is VirtualMachineError

    /**
     * Normalize a thread name for per-site counting without collapsing distinct `vscreen-*` threads into `"vscreen"`.
     * Strips trailing/embedded numeric pool/worker indices and any whitespace-delimited arguments/URLs.
     */
    internal fun normalizeThreadName(raw: String?): String {
        val base = raw?.trim().orEmpty()
        if (base.isEmpty()) return "unnamed"
        val token = base.substringBefore(' ').substringBefore('\t')
        val parts = token.split('-').filter { it.isNotEmpty() }
        val kept = parts.filterNot { seg ->
            seg.all { it.isDigit() } || (seg.startsWith("#") && seg.length > 1 && seg.drop(1).all { it.isDigit() })
        }
        val normalized = (if (kept.isNotEmpty()) kept.joinToString("-") else token)
            .trimEnd('#', '-', '_')
            .take(40)
        return normalized.ifEmpty { "unnamed" }
    }

    /**
     * Install in this process (idempotent). [log] writes one line (app log, or stderr in the shell process);
     * [note] gets every survived exception (for per-kind counting).
     */
    @Synchronized fun install(log: (String) -> Unit, note: (String, Throwable) -> Unit = { _, _ -> }) {
        if (installed) return
        installed = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val main = runCatching { t === Looper.getMainLooper()?.thread }.getOrDefault(false)
            if (fatal(main, e)) {
                runCatching { log("uncaught in ${t.name} (fatal): ${e.stackTraceToString().take(4000)}") }
                prev?.uncaughtException(t, e)
                return@setDefaultUncaughtExceptionHandler
            }
            deaths++
            last = "${t.name}: ${e.javaClass.simpleName}: ${e.message?.take(160)}"
            runCatching { log("background thread ${t.name} ended by ${e.stackTraceToString().take(2000)}") }
            runCatching { note("thread/${normalizeThreadName(t.name)}", e) }
        }
    }
}
