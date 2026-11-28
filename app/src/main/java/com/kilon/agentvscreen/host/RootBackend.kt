package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.core.RootMain
import com.kilon.agentvscreen.core.VScreenDisplayService
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1.4.2-beta3 [root way in]: starts the screen service with `su` instead of Shizuku and keeps its binder.
 *
 * What root changes is only how the service process starts and how its binder reaches the app; the service itself is
 * the one Shizuku hosts ([VScreenDisplayService] in [RootMain]), and commands run through it (EXEC), so nothing after
 * the start knows which way in is used ([Privileged]).
 *
 * One `su` per app process start: the launch script adopts a service of this build that is already running (its
 * screens survive an app restart, as with Shizuku's daemon), or replaces an older one. The root manager (Magisk,
 * KernelSU, APatch …) asks the user once and remembers.
 */
object RootBackend {
    private const val TAG = "RootBackend"
    private const val SU_TIMEOUT_S = 40L
    private const val BINDER_WAIT_S = 10L

    enum class Phase { IDLE, STARTING, READY, NO_SU, DENIED, FAILED }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phaseFlow: StateFlow<Phase> = _phase.asStateFlow()
    val phase: Phase get() = _phase.value
    @Volatile var binder: IBinder? = null; private set
    /** The service's uid: 2000 (shell, as under Shizuku in ADB mode) or 0 (root manager without `su <uid>`). */
    @Volatile var uid = -1; private set
    /** What went wrong last, in the user's words; null when nothing did. */
    @Volatile var lastError: String? = null; private set
    @Volatile private var onChange: () -> Unit = {}
    @Volatile private var latch = CountDownLatch(1)
    private val starting = AtomicBoolean(false)
    /** Restarts after an unexpected death, so a crash loop does not ask su forever. */
    private val restarts = ArrayDeque<Long>()

    fun init(onChange: () -> Unit) { this.onChange = onChange }

    private fun changed(p: Phase, error: String? = lastError) {
        _phase.value = p; lastError = error
        runCatching { onChange() }
    }

    /** Start (or adopt) the root service in the background. Safe to call often: one start runs at a time. */
    fun start(ctx: Context, reason: String) {
        if (binder?.isBinderAlive == true) return
        if (!starting.compareAndSet(false, true)) return
        latch = CountDownLatch(1)
        val app = ctx.applicationContext
        appCtx = app
        Thread({
            try { launch(app, reason) } catch (t: Throwable) {
                AppLogger.warning(TAG, "start failed: ${t.message}")
                changed(Phase.FAILED, t.message)
            } finally { starting.set(false) }
        }, "vscreen-root-start").apply { isDaemon = true }.start()
    }

    private fun launch(app: Context, reason: String) {
        changed(Phase.STARTING, null)
        val apk = app.applicationInfo.sourceDir
        val user = android.os.Process.myUid() / 100_000
        val script = script(app.packageName, BuildConfig.VERSION_CODE, apk, user)
        val p = try {
            ProcessBuilder(suPath()).redirectErrorStream(true).start()
        } catch (t: java.io.IOException) {
            AppLogger.warning(TAG, "no su: ${t.message}")
            return changed(Phase.NO_SU, tr(S.root_backend_this_phone_has_no_root))
        }
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } } }
            .apply { isDaemon = true; start() }
        runCatching { p.outputStream.bufferedWriter().use { it.write(script) } }
        val exited = p.waitFor(SU_TIMEOUT_S, TimeUnit.SECONDS)
        if (!exited) runCatching { p.destroyForcibly() }
        reader.join(2_000)
        val text = synchronized(out) { out.toString() }.trim()
        AppLogger.info(TAG, "su ($reason) exit=${if (exited) p.exitValue() else "timeout"}: ${text.take(600)}")
        val verdict = verdict(exited, if (exited) p.exitValue() else -1, text)
        if (verdict != null) return changed(verdict.first, verdict.second)
        if (!latch.await(BINDER_WAIT_S, TimeUnit.SECONDS) || binder == null) {
            return changed(Phase.FAILED, tr(S.root_backend_the_root_service_started_but) + text.lines().takeLast(3).joinToString(" / ").take(300))
        }
    }

    /**
     * `su` from PATH. Debug builds only: the CI emulator's stand-in root manager (tools/android-smoke/avsu.c) is named by
     * the `debug.avs.su` property, since the emulator's own su refuses apps. Release builds always use PATH.
     */
    private fun suPath(): String = if (!BuildConfig.DEBUG) "su" else runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.avs.su") as String
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "su"

    /** null = the service is (being) started; otherwise why not. Pure, for tests. */
    internal fun verdict(exited: Boolean, exit: Int, out: String): Pair<Phase, String>? = when {
        !exited -> Phase.DENIED to tr(S.root_backend_the_root_manager_did_not)
        out.contains("AVS started") || out.contains("AVS reused") -> null
        out.contains("AVS failed") -> Phase.FAILED to tr(S.root_backend_the_root_service_did_not) + out.substringAfter("AVS failed").trim().lines().takeLast(4).joinToString(" / ").take(300)
        else -> Phase.DENIED to tr(S.root_backend_no_root_the_root_manager, exit) +
            (out.lines().lastOrNull { it.isNotBlank() }?.let { tr(S.agent_guide_colon) + it.take(160) } ?: "")
    }

    /**
     * The script `su` runs. Adopts a running service of this build ([RootMain.REQ] makes it send its binder again),
     * otherwise stops an older one and starts this build's, as the shell user when `su 2000` works. The started service
     * is moved out of this app's process group, so killing the app (which kills its whole group) leaves it running.
     */
    internal fun script(pkg: String, version: Int, apk: String, user: Int): String {
        val p = RootMain.PID.removeSuffix(".pid")
        val name = RootMain.NICE_NAME
        val main = RootMain::class.java.name
        return """
            P=$p
            V=$version
            A='$apk'
            if [ -f ${'$'}P.pid ]; then
              read OP OV OU < ${'$'}P.pid
              if [ -n "${'$'}OP" ] && grep -q "$name" /proc/${'$'}OP/cmdline 2>/dev/null; then
                if [ "${'$'}OV" = "${'$'}V" ]; then echo x > ${'$'}P.req; echo "AVS reused ${'$'}OP ${'$'}OU"; exit 0; fi
                kill ${'$'}OP 2>/dev/null; sleep 0.3
              fi
            fi
            rm -f ${'$'}P.pid ${'$'}P.req
            C="CLASSPATH=${'$'}A exec /system/bin/app_process /system/bin --nice-name=$name $main $pkg ${'$'}V ${'$'}A $user"
            if [ "${'$'}(su 2000 -c 'id -u' </dev/null 2>/dev/null)" = 2000 ]; then
              nohup su 2000 -c "${'$'}C" </dev/null >${'$'}P.log 2>&1 &
            else
              nohup sh -c "${'$'}C" </dev/null >${'$'}P.log 2>&1 &
            fi
            i=0
            while [ ${'$'}i -lt 50 ] && [ ! -s ${'$'}P.pid ]; do sleep 0.2; i=${'$'}((i+1)); done
            if [ -s ${'$'}P.pid ]; then
              read NP NV NU < ${'$'}P.pid
              for g in /sys/fs/cgroup/cgroup.procs /acct/cgroup.procs; do [ -w ${'$'}g ] && echo ${'$'}NP > ${'$'}g 2>/dev/null; done
              echo "AVS started ${'$'}NP ${'$'}NU"
            else
              echo "AVS failed"; tail -n 20 ${'$'}P.log 2>/dev/null
            fi
            exit 0
        """.trimIndent() + "\n"
    }

    /** From [RootLink]: the service handing its binder over (at its start, and whenever a new app process asks). */
    fun onBinder(b: IBinder, serviceUid: Int, protocol: Int) {
        if (protocol != VScreenDisplayService.PROTOCOL) {
            AppLogger.warning(TAG, "binder from protocol $protocol (want ${VScreenDisplayService.PROTOCOL}): ignored")
            return
        }
        if (!b.isBinderAlive) return
        runCatching {
            b.linkToDeath({
                if (binder === b) {
                    binder = null
                    AppLogger.warning(TAG, "root service died")
                    changed(Phase.IDLE, tr(S.root_backend_the_root_service_exited))
                    restartAfterDeath()
                }
            }, 0)
        }
        binder = b; uid = serviceUid
        AppLogger.info(TAG, "root service connected (uid $serviceUid)")
        changed(Phase.READY, null)
        latch.countDown()
    }

    @Volatile private var appCtx: Context? = null

    fun remember(ctx: Context) { appCtx = ctx.applicationContext }

    private fun restartAfterDeath() {
        val ctx = appCtx ?: return
        if (Privileged.via != Privileged.Via.ROOT) return
        val now = SystemClock.elapsedRealtime()
        synchronized(restarts) {
            while (restarts.isNotEmpty() && now - restarts.first() > 5 * 60_000L) restarts.removeFirst()
            if (restarts.size >= 3) { AppLogger.warning(TAG, "root service died 3 times in 5 min: not restarting"); return }
            restarts.addLast(now)
        }
        start(ctx, "after death")
    }

    /** A live binder, waiting for a start in progress (never on the main thread). */
    fun awaitBinder(): Result<IBinder> {
        binder?.takeIf { it.isBinderAlive }?.let { return Result.success(it) }
        appCtx?.let { start(it, "service call") }
        latch.await(if (phase == Phase.STARTING || starting.get()) SU_TIMEOUT_S else 1L, TimeUnit.SECONDS)
        return binder?.takeIf { it.isBinderAlive }?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException(lastError ?: tr(S.root_backend_the_root_service_is_not)))
    }

    /** Stops the root service: screens released, sound given back, process gone (switching to Shizuku). */
    fun stop() {
        val b = binder ?: return
        binder = null
        Thread({
            val d = Parcel.obtain(); val r = Parcel.obtain()
            runCatching { b.transact(VScreenDisplayService.DESTROY_CODE, d, r, 0) } // the service exits inside: DeadObject is expected
            d.recycle(); r.recycle()
        }, "vscreen-root-stop").apply { isDaemon = true }.start()
        changed(Phase.IDLE, null)
    }

    /** su in one of the usual places, readable by apps (Magisk keeps it visible; KernelSU only for granted apps). */
    fun suVisible(): Boolean = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su", "/system_ext/bin/su")
        .any { runCatching { File(it).exists() }.getOrDefault(false) }

    fun diagnostics(): String = "Root: phase=$phase uid=$uid binder=${binder?.isBinderAlive == true} su=${suVisible()}" + (lastError?.let { " error=$it" } ?: "")
}
