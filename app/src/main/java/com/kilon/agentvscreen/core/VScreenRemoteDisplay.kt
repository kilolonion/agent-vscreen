package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.view.Surface
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.host.ShizukuManager
import com.kilon.agentvscreen.host.Privileged
import com.kilon.agentvscreen.host.RootBackend
import com.kilon.agentvscreen.host.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import rikka.shizuku.Shizuku

/**
 * [vscreen] App-side client of [VScreenDisplayService]: binds the Shizuku
 * UserService once and asks it to create / release shell-owned virtual
 * displays that render into the app's own Surface.
 *
 * 1.4.2-beta3: the same service also runs in a root-started process ([RootMain]); which one answers depends only on
 * [Privileged.via]. Every call below is the same for both ways in.
 */
internal object VScreenRemoteDisplay {
    private const val TAG = "VScreenRemoteDisplay"
    private const val BIND_TIMEOUT_S = 10L
    /** 1.4.2: the longest one call to the screen service may take (a normal one is well under 100 ms). */
    internal const val CALL_TIMEOUT_MS = 10_000L

    /**
     * 1.4.2: every call to the service runs here with a deadline. A binder call into a service the system froze (or one
     * stuck inside a system call) never returns by itself; before, the caller, and everything queued behind it on the
     * same lock, waited forever and the whole app looked hung. Now the caller gets "not responding" after
     * [CALL_TIMEOUT_MS]. The stuck call keeps its pool thread until it returns; while one is stuck past its deadline,
     * new calls fail at once instead of piling more threads behind a frozen service.
     *
     * A timeout leaves one thing open: the service may still carry the call out later. The calls that move things
     * (release, removeTask, moveTask) are safe to repeat; a create that timed out leaves at most an orphan display,
     * which the service's own reaper releases.
     */
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "vscreen-call").apply { isDaemon = true } }
    @Volatile private var stuckSince = 0L

    internal fun notResponding(s: Long) = IllegalStateException(tr("副屏服务 $s 秒没有响应（可能被系统冻结），稍后重试；一直这样就在 App 里重连 Shizuku", "The screen service has not responded for $s s (the system may have frozen it); retry shortly, and reconnect Shizuku in the app if it keeps happening"))

    private fun <T> timed(what: String, extraMs: Long = 0L, block: () -> T): T {
        val since = stuckSince
        if (since != 0L) {
            val s = (android.os.SystemClock.elapsedRealtime() - since) / 1000
            if (s * 1000 >= CALL_TIMEOUT_MS) throw notResponding(s)
        }
        val f = pool.submit<T> {
            val t0 = android.os.SystemClock.elapsedRealtime()
            try { block() } finally {
                val took = android.os.SystemClock.elapsedRealtime() - t0
                if (took > CALL_TIMEOUT_MS + extraMs) AppLogger.warning(TAG, "$what returned after ${took}ms")
                synchronized(pool) { if (stuckSince != 0L && stuckSince >= t0) stuckSince = 0L }
            }
        }
        val start = android.os.SystemClock.elapsedRealtime()
        return try {
            f.get(CALL_TIMEOUT_MS + extraMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            synchronized(pool) { if (stuckSince == 0L) stuckSince = start }
            AppLogger.warning(TAG, "$what: no answer in ${CALL_TIMEOUT_MS + extraMs}ms")
            throw notResponding((android.os.SystemClock.elapsedRealtime() - start) / 1000)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    data class Created(val displayId: Int, val flags: Int) {
        val trusted: Boolean get() = VScreenDisplayService.isTrusted(flags)
    }

    @Volatile private var shizukuBinder: IBinder? = null
    /** The live service of the chosen way in (Shizuku's UserService or the root process). */
    private val binder: IBinder? get() = if (Privileged.via == Privileged.Via.ROOT) RootBackend.binder else shizukuBinder
    private val lock = Any()

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, VScreenDisplayService::class.java.name))
            .daemon(true) // survive an app restart so the next process can adopt the screen
            .processNameSuffix("vscreen")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE * 100 + VScreenDisplayService.PROTOCOL)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuBinder = service?.takeIf { it.pingBinder() }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { shizukuBinder = null }
    }
    @Volatile private var latch: CountDownLatch? = null

    /** A live service binder, binding (and waiting) if needed. Never on the main thread. */
    private fun service(): Result<IBinder> {
        if (Privileged.via == Privileged.Via.ROOT) return RootBackend.awaitBinder()
        shizukuBinder?.takeIf { it.isBinderAlive }?.let { return Result.success(it) }
        if (!ShizukuManager.isReady()) return Result.failure(IllegalStateException(tr("Shizuku 未就绪", "Shizuku not ready")))
        synchronized(lock) {
            shizukuBinder?.takeIf { it.isBinderAlive }?.let { return Result.success(it) }
            val l = CountDownLatch(1).also { latch = it }
            try {
                Shizuku.bindUserService(args, conn)
            } catch (t: Throwable) {
                return Result.failure(IllegalStateException(tr("Shizuku 不支持 UserService：${t.message}", "Shizuku does not support UserService: ${t.message}")))
            }
            if (!l.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) {
                return Result.failure(IllegalStateException(tr("等待 Shizuku 启动副屏服务超时", "Timed out waiting for Shizuku to start the screen service")))
            }
            return shizukuBinder?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException(tr("副屏服务连接失败", "Connecting to the screen service failed")))
        }
    }

    fun create(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Result<Created> = runCatching {
        val b = service().getOrThrow()
        timed("create") {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                data.writeString(name); data.writeInt(width); data.writeInt(height); data.writeInt(dpi)
                data.writeInt(0) // 0 = let the service walk its flag ladder
                surface.writeToParcel(data, 0)
                b.transact(VScreenDisplayService.CREATE, data, reply, 0)
                if (reply.readInt() != 0) error(reply.readString() ?: "unknown error")
                Created(reply.readInt(), reply.readInt())
            } finally { data.recycle(); reply.recycle() }
        }
    }.onFailure { AppLogger.warning(TAG, "remote create failed: ${it.message}") }

    fun createParking(active: Int, width: Int, height: Int, dpi: Int): Result<Int> =
        call(VScreenDisplayService.CREATE_PARKING, {
            it.writeInt(active); it.writeInt(width); it.writeInt(height); it.writeInt(dpi)
        }) { it.readInt() }

    fun releaseParking(displayId: Int): Result<Unit> = call(VScreenDisplayService.RELEASE, { it.writeInt(displayId) }) { }

    fun release(displayId: Int) {
        val b = binder?.takeIf { it.isBinderAlive } ?: return
        try {
            timed("release") {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                    data.writeInt(displayId)
                    b.transact(VScreenDisplayService.RELEASE, data, reply, 0)
                } finally { data.recycle(); reply.recycle() }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "remote release($displayId) failed: ${t.message}")
        }
    }

    /** Run one call on the service; null when the service is not available. */
    private fun <T> call(code: Int, write: (Parcel) -> Unit, extraMs: Long = 0L, read: (Parcel) -> T): Result<T> = runCatching {
        val b = service().getOrThrow()
        timed("call $code", extraMs) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                write(data)
                if (!b.transact(code, data, reply, 0)) error(tr("副屏服务不认识这个调用（旧版本服务仍在运行，重连后会更新）", "The screen service does not know this call (an old service version is still running; it updates after a reconnect)"))
                if (reply.readInt() != 0) error(reply.readString() ?: "unknown error")
                read(reply)
            } finally { data.recycle(); reply.recycle() }
        }
    }

    /**
     * 1.4.2-beta3: a command run by the service's user (the root way in has no Shizuku to spawn it). The output streams
     * back through pipes, read here while the call runs, so a long `dumpsys` is not cut by the binder size limit.
     */
    fun exec(argv: Array<String>, env: Array<String>?, cwd: String?, timeoutMs: Long): ShizukuManager.ProcessResult {
        val b = service().getOrElse { return ShizukuManager.ProcessResult(126, "", it.message ?: "service unavailable") }
        val outPipe = android.os.ParcelFileDescriptor.createPipe()
        val errPipe = android.os.ParcelFileDescriptor.createPipe()
        fun reader(fd: android.os.ParcelFileDescriptor, into: StringBuilder, name: String) = Thread {
            runCatching { android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { r -> synchronized(into) { into.append(r.readText()) } } }
        }.apply { isDaemon = true; this.name = name; start() }
        val out = StringBuilder(); val err = StringBuilder()
        val t1 = reader(outPipe[0], out, "vscreen-exec-out")
        val t2 = reader(errPipe[0], err, "vscreen-exec-err")
        val code = try {
            timed("exec ${argv.firstOrNull()}", timeoutMs + 2_000L) {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                    data.writeStringArray(argv); data.writeStringArray(env); data.writeString(cwd); data.writeLong(timeoutMs)
                    outPipe[1].writeToParcel(data, 0); errPipe[1].writeToParcel(data, 0)
                    if (!b.transact(VScreenDisplayService.EXEC, data, reply, 0)) error(tr("副屏服务太旧，不支持运行命令", "The screen service is too old to run commands"))
                    if (reply.readInt() != 0) error(reply.readString() ?: "unknown error")
                    reply.readInt()
                } finally { data.recycle(); reply.recycle() }
            }
        } catch (t: Throwable) {
            synchronized(err) { err.append(t.message ?: t.javaClass.simpleName) }
            1
        } finally {
            // Our copies of the write ends: closed, so the readers see the end once the service closes its own.
            runCatching { outPipe[1].close() }; runCatching { errPipe[1].close() }
        }
        t1.join(3_000); t2.join(3_000)
        return ShizukuManager.ProcessResult(code, synchronized(out) { out.toString() }.trimEnd('\n'), synchronized(err) { err.toString() }.trimEnd('\n'))
    }

    /** Key event with the display id set (reaches a display with its own focus). */
    fun injectKey(displayId: Int, keyCode: Int, longPress: Boolean = false): Result<Boolean> =
        call(VScreenDisplayService.INJECT_KEY, { it.writeInt(displayId); it.writeInt(keyCode); it.writeInt(if (longPress) 1 else 0) }) { it.readInt() != 0 }

    /** 1.4.0-beta13: a key with modifier keys held (see VScreenDisplayService.injectCombo). */
    fun injectCombo(displayId: Int, keyCode: Int, mods: List<Int>): Result<Boolean> =
        call(VScreenDisplayService.INJECT_COMBO, { it.writeInt(displayId); it.writeInt(keyCode); it.writeInt(mods.size); mods.forEach { m -> it.writeInt(m) } }) { it.readInt() != 0 }

    /** 1.2: characters typed as key events on [displayId] (ASCII; see VScreenDisplayService.injectText). */
    fun injectText(displayId: Int, text: String): Result<Boolean> =
        call(VScreenDisplayService.INJECT_TEXT, { it.writeInt(displayId); it.writeString(text) }) { it.readInt() != 0 }

    /** Finger drag (or tap when start == end) on [displayId]; see VScreenDisplayService.injectDrag. */
    fun injectDrag(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int): Result<Boolean> =
        call(VScreenDisplayService.INJECT_DRAG, {
            it.writeInt(displayId); it.writeInt(x1); it.writeInt(y1); it.writeInt(x2); it.writeInt(y2)
            it.writeInt(durationMs); it.writeInt(holdMs)
        }, extraMs = (durationMs.coerceAtLeast(0) + holdMs.coerceAtLeast(0)).toLong()) { it.readInt() != 0 }

    /** (rotation, logicalWidth, logicalHeight) as the system sees [displayId]. */
    fun rotation(displayId: Int): Result<Triple<Int, Int, Int>> =
        call(VScreenDisplayService.ROTATION, { it.writeInt(displayId) }) { Triple(it.readInt(), it.readInt(), it.readInt()) }

    /** Held finger: op 0 down, 1 move, 2 up (x,y < 0 keeps the last point), 3 lift all. Returns (injected, fingers still down). */
    fun pointer(displayId: Int, op: Int, pointerId: Int, x: Float, y: Float): Result<Pair<Boolean, Int>> =
        call(VScreenDisplayService.POINTER, {
            it.writeInt(displayId); it.writeInt(op); it.writeInt(pointerId); it.writeFloat(x); it.writeFloat(y)
        }) { (it.readInt() != 0) to it.readInt() }

    /** Remove [taskId] only if the system says it is on [displayId] (the service checks; refuses otherwise). */
    fun removeTask(taskId: Int, displayId: Int): Result<Boolean> =
        call(VScreenDisplayService.REMOVE_TASK, { it.writeInt(taskId); it.writeInt(displayId) }) { it.readInt() != 0 }

    /** A task on a display, as ActivityTaskManager reports it (display-scoped by the system, not by parsing dumpsys). */
    data class DisplayTask(val taskId: Int, val component: String?, val visible: Boolean, val activityType: Int, val rootTop: String?) {
        val pkg: String? get() = (rootTop ?: component)?.substringBefore('/')
        /** HOME = 2, RECENTS = 3, ASSISTANT = 4, DREAM = 5. */
        val isAppTask: Boolean get() = activityType !in setOf(2, 3, 4, 5)
    }

    fun isConnected(): Boolean = binder?.isBinderAlive == true

    /** [listTasks] only when the service is already connected (never binds or waits); null otherwise. */
    fun listTasksIfConnected(displayId: Int): List<DisplayTask>? {
        if (binder?.isBinderAlive != true) return null
        return listTasks(displayId).getOrNull()
    }

    fun listTasks(displayId: Int): Result<List<DisplayTask>> = call(VScreenDisplayService.LIST_TASKS, { it.writeInt(displayId) }) { r ->
        val n = r.readInt()
        (0 until n).map { DisplayTask(r.readInt(), r.readString(), r.readInt() != 0, r.readInt(), r.readString()) }
    }

    /** (displayId, taskId) that has the system's input focus. */
    fun focused(): Result<Pair<Int, Int>> = call(VScreenDisplayService.FOCUSED, {}) { it.readInt() to it.readInt() }

    fun focusTask(taskId: Int, expectedDisplay: Int? = null): Result<Boolean> =
        call(VScreenDisplayService.FOCUS_TASK, { it.writeInt(taskId); expectedDisplay?.let(it::writeInt) }) { it.readInt() != 0 }

    /** v22 接力: (moved, system's message when not). */
    fun moveTask(taskId: Int, displayId: Int, source: Int? = null): Result<Pair<Boolean, String>> =
        call(VScreenDisplayService.MOVE_TASK, { it.writeInt(taskId); it.writeInt(displayId); source?.let(it::writeInt) }) { (it.readInt() != 0) to (it.readString() ?: "") }

    data class Existing(val displayId: Int, val width: Int, val height: Int, val dpi: Int, val flags: Int)

    /** Displays the service still holds (e.g. left by a previous app process). */
    fun list(): Result<List<Existing>> = call(VScreenDisplayService.LIST, {}) { r ->
        val n = r.readInt()
        (0 until n).map { Existing(r.readInt(), r.readInt(), r.readInt(), r.readInt(), r.readInt()) }
    }

    fun setSurface(displayId: Int, surface: Surface): Result<Unit> =
        call(VScreenDisplayService.SET_SURFACE, { it.writeInt(displayId); surface.writeToParcel(it, 0) }) { }

    /** v21: take the surface away (display off) to save power; setSurface brings it back. */
    fun park(displayId: Int): Result<Unit> =
        call(VScreenDisplayService.PARK, { it.writeInt(displayId) }) { }

    /** Honor/Huawei APS state of [pkg] (available=false on other phones). */
    fun apsGet(pkg: String): Result<VScreenDownscale.Aps> =
        call(VScreenDisplayService.APS, { it.writeInt(0); it.writeString(pkg) }) { r ->
            if (r.readInt() == 0) VScreenDownscale.Aps(false, 1f, 1f, false, null, r.readString())
            else VScreenDownscale.Aps(true, r.readFloat(), r.readFloat(), r.readInt() != 0, r.createByteArray())
        }

    /** Set the APS ratio; (return code, ratio read back). */
    fun apsSet(pkg: String, ratio: Float): Result<Pair<Int, Float>> =
        call(VScreenDisplayService.APS, { it.writeInt(1); it.writeString(pkg); it.writeFloat(ratio) }) { it.readInt() to it.readFloat() }

    /** Put the saved APS entry back (or delete ours when there was none); returns the ratio read back. */
    data class ApsRestored(val readBack: Float, val hasEntry: Boolean, val detail: String)

    fun apsRestore(pkg: String, hadEntry: Boolean, entry: ByteArray?, ratio: Float): Result<ApsRestored> =
        call(VScreenDisplayService.APS, {
            it.writeInt(2); it.writeString(pkg); it.writeInt(if (hadEntry) 1 else 0); it.writeByteArray(entry); it.writeFloat(ratio)
        }) { ApsRestored(it.readFloat(), it.readInt() != 0, it.readString() ?: "") }

    /** Keep-alive for the orphan reaper; cheap, fire-and-forget. */
    /** 1.4.0-beta11: the service owns the sound of apps on our screens (see VScreenDisplayService.Sound). */
    fun soundMute(pkg: String): Result<Int> = call(VScreenDisplayService.SOUND, { it.writeInt(0); it.writeString(pkg) }) { it.readInt() }
    fun soundGive(pkg: String): Result<Unit> = call(VScreenDisplayService.SOUND, { it.writeInt(1); it.writeString(pkg) }) { }
    fun soundGiveAll(): Result<Unit> = call(VScreenDisplayService.SOUND, { it.writeInt(3) }) { }
    fun soundList(): Result<List<String>> = call(VScreenDisplayService.SOUND, { it.writeInt(2) }) { r -> List(r.readInt()) { r.readString().orEmpty() } }

    fun touch(displayId: Int) {
        if (binder?.isBinderAlive != true) return
        call(VScreenDisplayService.TOUCH, { it.writeInt(displayId) }) { }
    }
}
