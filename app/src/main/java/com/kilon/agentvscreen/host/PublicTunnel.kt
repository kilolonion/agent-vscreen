package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
import android.content.Context
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 2.0.0-beta11: what the tunnel channels (Cloudflare Tunnel [Cloudflared], ngrok [Ngrok]) share. Each runs one
 * client as a child process of the app that dials out to the provider, so no port is opened on the phone; requests
 * come back to [com.kilon.agentvscreen.api.PublicServer] marked public. What the user sees is measured, never
 * assumed: the client's own report for "connected", its log lines for why not, and a [selfTest] from this phone to
 * the public address that must come back with this phone's own OAuth metadata.
 */
enum class TunnelState { OFF, STARTING, CONNECTED, ERROR }

data class TunnelStatus(
    val state: TunnelState = TunnelState.OFF,
    /** Connections to the provider that are up (Cloudflare keeps up to 4; ngrok has one session). */
    val connections: Int = 0,
    /** Where it is connected (Cloudflare data centre, or the ngrok address). */
    val location: String? = null,
    val protocol: String? = null,
    /** Why it is not connected, in the user's words, and what to do. */
    val problem: String? = null,
    /** The client's own last error line (for 详情). */
    val raw: String? = null,
)

sealed class TunnelSelfTest {
    object Running : TunnelSelfTest()
    object Ok : TunnelSelfTest()
    data class Failed(val title: String, val fix: String, val raw: String? = null) : TunnelSelfTest()
}

/** One tunnel channel, as the public page and the connect page show it. */
interface PublicTunnel {
    /** The provider's name as the user knows it ("Cloudflare", "ngrok"). */
    val provider: String
    val status: StateFlow<TunnelStatus>
    val selfTest: StateFlow<TunnelSelfTest?>
    /** The public address (https://host), or null until it is set. */
    val baseUrl: String?
    fun start(ctx: Context)
    fun stop(why: String)
    /** Run the self-test now (blocking; call off the main thread). */
    fun runSelfTest()
}

/**
 * The client process of one tunnel: started with [start], restarted with backoff when it ends, stopped with [stop].
 * A [fatal] ending (a key the provider refuses) waits a minute before the next try: it will not start working by
 * retrying fast.
 */
internal class TunnelProcess(private val tag: String, private val thread: String) {
    @Volatile private var wanted = false
    @Volatile var gen = 0; private set
    @Volatile private var proc: Process? = null
    private var supervisor: Thread? = null

    val current: Boolean get() = wanted

    /**
     * [launch] builds the process for one run (or null to give up, having set the status itself), [onLine] gets each
     * output line, [onEnded] runs after each run and returns whether the ending was fatal.
     */
    @Synchronized fun start(launch: (Int) -> ProcessBuilder?, onLine: (String) -> Unit, onStarted: (Int) -> Unit = {}, onEnded: (Int) -> Boolean) {
        wanted = true
        if (supervisor?.isAlive == true) return
        val g = ++gen
        supervisor = Thread({ supervise(g, launch, onLine, onStarted, onEnded) }, thread).apply { isDaemon = true; start() }
    }

    @Synchronized fun stop(why: String): Boolean {
        val was = wanted || proc != null
        if (was) AppLogger.info(tag, "stop: $why")
        wanted = false
        gen++
        proc?.let { p ->
            Thread {
                runCatching { p.outputStream.close() }
                runCatching { p.destroy(); if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
            }.apply { isDaemon = true }.start()
        }
        proc = null
        supervisor?.interrupt(); supervisor = null
        return was
    }

    fun live(g: Int) = wanted && g == gen

    private fun supervise(g: Int, launch: (Int) -> ProcessBuilder?, onLine: (String) -> Unit, onStarted: (Int) -> Unit, onEnded: (Int) -> Boolean) {
        var restarts = 0
        while (live(g)) {
            val t0 = System.currentTimeMillis()
            var fatal = false
            runCatching {
                val pb = launch(g) ?: return
                val p = synchronized(this) { if (!live(g)) return; pb.redirectErrorStream(true).start().also { proc = it } }
                AppLogger.info(tag, "client started")
                onStarted(g)
                try {
                    p.inputStream.bufferedReader().forEachLine { line -> if (live(g) && line.isNotBlank()) onLine(line) }
                } finally {
                    runCatching { p.waitFor() }
                }
            }.onFailure { e -> AppLogger.warning(tag, "client: ${e.javaClass.simpleName}: ${e.message}") }
            if (!live(g)) break
            fatal = onEnded(g)
            if (System.currentTimeMillis() - t0 > 60_000) restarts = 0
            val wait = if (fatal) 60_000L else (2_000L shl restarts.coerceAtMost(4)).coerceAtMost(30_000L)
            restarts++
            AppLogger.info(tag, "client ended, restarting in ${wait / 1000}s")
            try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
        }
    }
}

/** Shared state holder of a tunnel: status and self-test flows. */
internal class TunnelFlows {
    val status = MutableStateFlow(TunnelStatus())
    val selfTest = MutableStateFlow<TunnelSelfTest?>(null)
    val statusFlow: StateFlow<TunnelStatus> = status.asStateFlow()
    val selfTestFlow: StateFlow<TunnelSelfTest?> = selfTest.asStateFlow()
}

internal object TunnelProbe {
    /**
     * GET base/.well-known/oauth-protected-resource from this phone, through the provider. [judge] turns the answer
     * into a result, [judgeError] a failure to connect.
     */
    fun run(base: String, judge: (Int, String) -> TunnelSelfTest, judgeError: (Throwable) -> TunnelSelfTest): TunnelSelfTest = runCatching {
        val c = URL("$base/.well-known/oauth-protected-resource").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.instanceFollowRedirects = false
        try {
            val code = c.responseCode
            val body = runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() }.getOrNull().orEmpty()
            judge(code, body)
        } finally { c.disconnect() }
    }.getOrElse { e -> judgeError(e) }

    /** The answer is this phone's own OAuth metadata for exactly this public address. */
    fun isMine(base: String, code: Int, body: String): Boolean =
        code == 200 && runCatching { JSONObject(body).optString("resource") }.getOrNull() == "$base/mcp"

    /** A failure to connect that is not specific to a provider. */
    fun commonError(e: Throwable): TunnelSelfTest.Failed = TunnelSelfTest.Failed(tr(S.public_tunnel_the_self_test_did_not),
        tr(S.public_tunnel_the_phone_could_not_reach),
        "${e.javaClass.simpleName}: ${e.message}")
}
