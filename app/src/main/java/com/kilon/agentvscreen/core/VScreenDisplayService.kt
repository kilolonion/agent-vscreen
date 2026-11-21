package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface

/**
 * [vscreen] Shizuku UserService: runs as the Shizuku shell user (uid 2000),
 * started by `Shizuku.bindUserService` from [VScreenRemoteDisplay].
 *
 * Why: a virtual display created by the app itself is an untrusted display
 * owned by the app's uid. Android only lets the display owner (or system)
 * put activities on such a display, so `am start --display N` run as the
 * shell user is refused and the system quietly opens the app on the main
 * screen. A display created here is owned by the shell uid (the same uid
 * that runs `am start` / `input`) and, on Android 13+, can be TRUSTED,
 * which is what scrcpy's `--new-display` does.
 *
 * The app keeps its ImageReader: its Surface travels here through binder,
 * so the frames still land in the app for the preview and screenshots.
 *
 * Raw binder protocol (no AIDL), interface [DESCRIPTOR]:
 *   CREATE   in: name, width, height, dpi, flags, Surface  out: status, displayId | message
 *   RELEASE  in: displayId                                 out: status
 *   PING     out: status, uid
 *   INJECT_KEY   in: displayId, keyCode, longPress(0/1)            out: status, injected(0/1)
 *   INJECT_DRAG  in: displayId, x1, y1, x2, y2, durationMs, holdMs  out: status, injected(0/1)
 *                (x1,y1)==(x2,y2) is a tap / long press of holdMs)
 *   REMOVE_TASK  in: taskId, expectedDisplayId                    out: status, removed(0/1)
 *                (v27: refused unless the system says the task is on expectedDisplayId)
 *   LIST_TASKS   in: displayId                                    out: status, n, n × (taskId, component, visible, activityType, rootTop)
 *   FOCUSED      in: -                                            out: status, displayId, taskId (-1 = unknown)
 *   FOCUS_TASK   in: taskId, [expected display]                                       out: status, ok(0/1)
 *   MOVE_TASK    in: taskId, displayId, [expected source display]                   out: status, moved(0/1), detail
 *   CREATE_PARKING in: activeDisplayId, width, height, dpi           out: status, displayId
 *   LIST         out: status, n, n x (displayId, w, h, dpi, flags)
 *   SET_SURFACE  in: displayId, Surface                            out: status
 *   PARK         in: displayId (surface -> null: display off, v21)  out: status
 *   TOUCH        in: displayId                                     out: status
 *   ROTATION     in: displayId                         out: status, rotation, logicalW, logicalH
 *   POINTER      in: displayId, op(0 down,1 move,2 up,3 cancel own side), pointerId, x, y, epoch
 *                out: status, injected(0/1), activePointers
 *                (fingers stay down between calls: joystick, hold-and-look, two-finger play)
 *   FENCE        in: displayId, epoch, agentAllowed                out: status, agent fingers cancelled
 *                (1.4.2-beta6: every INJECT_* / POINTER ends with its epoch; see InputFence)
 *   APS          in: op, pkg, ...   Honor/Huawei per-app resolution (aps_service, IHwApsManager)
 *                op 0 GET     out: status, available, [resolution, dynamicRatio, hasEntry, entryBytes] | reason
 *                op 1 SET     in: ratio                      out: status, returnCode, readBack
 *                op 2 RESTORE in: hadEntry, entryBytes, ratio out: status, readBack, hasEntryNow, detail
 *
 * The service runs as a Shizuku daemon: when the app process dies (killed
 * in the background, or restarted) its displays survive with the apps on
 * them, and the next app process adopts them (LIST + SET_SURFACE with a
 * fresh ImageReader). Displays nobody touched for [ORPHAN_MS] are released.
 * Input is injected with the target display id set on every event, from
 * the shell uid (INJECT_EVENTS), the way scrcpy does it.
 * status 0 = ok, 1 = error (followed by a message string).
 * Kept by proguard (class is instantiated by name in another process).
 */
class VScreenDisplayService : Binder {
    private val base: Context?
    private val displays = HashMap<Int, VirtualDisplay>()
    private val meta = HashMap<Int, IntArray>()
    private val touched = HashMap<Int, Long>()
    // A parking display is never advertised for adoption. Its contents must finish on removal, never hop to the main screen.
    private var parking: Pair<Int, Int>? = null // active display -> hidden display; protected by displays

    private fun releaseLocked(id: Int) {
        parking?.let { (active, hidden) ->
            if (id == active || id == hidden) {
                parking = null
                if (id == active) releaseLocked(hidden)
            }
        }
        meta.remove(id)
        touched.remove(id)
        fence.forget(id)
        displays.remove(id)?.release()
    }
    @Volatile private var reaperStarted = false

    private fun startReaper() {
        if (reaperStarted) return
        reaperStarted = true
        Thread {
            while (true) {
                Thread.sleep(60_000)
                val now = SystemClock.elapsedRealtime()
                val stale = synchronized(displays) { touched.filter { now - it.value > ORPHAN_MS }.keys.toList() }
                for (id in stale) {
                    synchronized(displays) { if (now - (touched[id] ?: now) > ORPHAN_MS) runCatching { releaseLocked(id) } }
                }
                if (stale.isNotEmpty()) sound.check()
            }
        }.apply { isDaemon = true; name = "vscreen-reaper" }.start()
    }

    constructor() : super() { base = null; guardThreads() }
    constructor(context: Context) : super() { base = context; guardThreads() }

    /** 1.4.2: in the Shizuku process too, a helper thread's exception must not take the screens down with it. */
    private fun guardThreads() = ThreadGuard.install({ System.err.println("vscreen-service: $it") })

    /**
     * 1.4.0-beta11: the sound of apps on the background screen is owned HERE, not by the app (rainbow 2026-11-17: after
     * an uninstall or an abnormal exit, apps used on the background screen stayed silent and needed a command).
     *
     * Muting is a system setting (PLAY_AUDIO app-op) that outlives whoever set it. When the app owned it, giving the
     * sound back needed the app to run again: an uninstall, a kill, or a reboot left apps silent for good. This service
     * runs as the shell beside the screens themselves, survives the app, and gives an app its sound back the moment it
     * is no longer on one of our screens or comes to the front of the main screen: screen released or reaped, the app
     * moved away, the app process gone, the app uninstalled ([DESTROY_CODE] from Shizuku, and a package check), and on
     * the next service start for anything a killed service left behind ([Sound.FILE]).
     */
    private val sound = Sound()

    private inner class Sound {
        private val muted = LinkedHashMap<String, String>()
        @Volatile private var watching = false
        private var checks = 0

        init {
            // Whatever a killed service left silent: no screen exists in a fresh process, so all of it goes back.
            runCatching { java.io.File(FILE).takeIf { it.isFile }?.readText() }.getOrNull()?.let { t ->
                runCatching { org.json.JSONObject(t).let { o -> o.keys().forEach { k -> muted[k] = o.optString(k, "default") } } }
            }
            if (muted.isNotEmpty()) Thread { giveAll(); if (list().isNotEmpty()) watch() }.apply { isDaemon = true; name = "vscreen-sound-leftover" }.start()
        }

        private fun save() {
            runCatching {
                val f = java.io.File(FILE)
                if (muted.isEmpty()) f.delete()
                else {
                    f.writeText(org.json.JSONObject().apply { muted.forEach { (k, v) -> put(k, v) } }.toString())
                    // 1.4.2-beta3: written by a root service, it must stay the shell's, or a later Shizuku service
                    // (the shell) could not update it.
                    if (android.os.Process.myUid() == 0) runCatching { android.system.Os.chown(FILE, 2000, 2000) }
                }
            }
        }

        /**
         * 1 = silenced now, 2 = already ours, 3 = silenced but a player of it is still heard (the system re-checks only
         * when the app plays again), 0 = left alone (unreadable, refused, or silent by someone else's choice).
         *
         * 1.4.0-beta13 (rainbow: on vivo a package-level ignore did not silence): where an app has a UID record the
         * system checks THAT one and the package's ignore does nothing. So both records are set to ignore (the UID's
         * only when the uid is the app's alone, never a shared one), read back, and given back together as allow.
         */
        fun mute(pkg: String): Int = synchronized(muted) {
            if (pkg.isBlank()) return 0
            if (pkg in muted) return 2
            val now = runCatching { PlayAudio.parse(sh("cmd", "appops", "get", pkg, "PLAY_AUDIO")) }.getOrNull() ?: return 0
            if (PlayAudio.silent(now)) return 0
            val uid = uidOf(pkg)?.takeIf { u -> u >= 10_000 && runCatching { sh("cmd", "package", "list", "packages", "--uid", "$u").lines().count { it.startsWith("package:") } == 1 }.getOrDefault(false) }
            val out = runCatching { sh("sh", "-c", PlayAudio.silence(0, pkg, uid)) }.getOrDefault("")
            val after = PlayAudio.parseScan(out).firstOrNull()?.modes
            if (after == null || after.pkg != "ignore" || (uid != null && after.uid != null && after.uid != "ignore")) {
                // Refused or not taken: put back what we may have set, keep nothing.
                runCatching { sh("sh", "-c", PlayAudio.giveBack(0, pkg, uid)) }
                return 0
            }
            muted[pkg] = now.pkg ?: "default"; save(); watch()
            runCatching { Thread.sleep(300); PlayAudio.audible(sh("dumpsys", "audio"), uid ?: uidOf(pkg) ?: -1) }.getOrNull()?.let { if (it) return 3 }
            1
        }

        private fun uidOf(pkg: String): Int? = runCatching {
            sh("cmd", "package", "list", "packages", "-U", pkg).lines().firstOrNull { it.trim().startsWith("package:$pkg ") || it.trim() == "package:$pkg" }
                ?.let { Regex("uid:(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        }.getOrNull()

        /**
         * 1.4.0-beta13 (rainbow: the sound did not come back): back to `allow` on BOTH records, then read both. Some ROMs
         * treat an explicit `default` as muted, so `default` is never written; nothing but an app that played before
         * is muted, so `allow` is what it had in effect.
         */
        fun give(pkg: String) = synchronized(muted) {
            if (pkg !in muted) return
            val ok = runCatching {
                val uid = uidOf(pkg)
                val out = sh("sh", "-c", PlayAudio.giveBack(0, pkg, uid))
                PlayAudio.parseScan(out).firstOrNull()?.let { !PlayAudio.silent(it.modes) } ?: false
            }.getOrDefault(false)
            if (ok || !installed(pkg)) muted.remove(pkg)
            save()
        }

        fun giveAll() = synchronized(muted) { muted.keys.toList().forEach { give(it) } }

        fun list(): List<String> = synchronized(muted) { muted.keys.toList() }

        /** Give back every app that is no longer on one of our screens, or is in front on the main screen. */
        fun check() = synchronized(muted) {
            if (muted.isEmpty()) return
            if (++checks % 4 == 0 && !installed(com.kilon.agentvscreen.BuildConfig.APPLICATION_ID)) { giveAll(); return }
            val ours = synchronized(displays) { displays.keys.toSet() }
            val roots = runCatching { rootTasks() }.getOrNull() ?: return
            fun pkgs(r: Root) = (r.children.mapNotNull { it.second } + listOfNotNull(r.top)).map { it.substringBefore('/') }
            val here = roots.filter { it.displayId in ours }.flatMap { pkgs(it) }.toSet()
            val front = roots.firstOrNull { it.displayId == 0 && it.visible && it.type == 1 }?.top?.substringBefore('/')
            muted.keys.filter { it !in here || it == front }.forEach { give(it) }
        }

        private fun watch() {
            if (watching) return
            watching = true
            Thread {
                while (true) {
                    runCatching { Thread.sleep(3_000) }
                    runCatching { check() }
                }
            }.apply { isDaemon = true; name = "vscreen-sound" }.start()
        }

        private fun installed(pkg: String): Boolean =
            runCatching { sh("cmd", "package", "path", pkg).contains("package:") }.getOrDefault(true)

        private fun sh(vararg cmd: String): String {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread { runCatching { p.inputStream.bufferedReader().use { out.append(it.readText()) } } }.apply { isDaemon = true; start() }
            if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); error("timeout: ${cmd.joinToString(" ")}") }
            reader.join(1_000)
            return synchronized(out) { out.toString() }
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == DESTROY_CODE) {
            synchronized(displays) { displays.values.forEach { runCatching { it.release() } }; displays.clear() }
            sound.giveAll() // uninstall, update, Shizuku stopping the service: no app is left silent
            System.exit(0)
            return true
        }
        // Every call code up to LAST_CALL: a range that stopped short of a newer code made the service refuse it (FENCE did).
        if (code !in CREATE..LAST_CALL) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val out = reply ?: return true
        try {
            when (code) {
                CREATE -> {
                    val name = data.readString() ?: "agent-vscreen"
                    val w = data.readInt(); val h = data.readInt(); val dpi = data.readInt()
                    val requested = data.readInt()
                    val surface = Surface.CREATOR.createFromParcel(data)
                    val vd = create(name, w, h, dpi, requested, surface)
                    val id = vd.display.displayId
                    synchronized(displays) {
                        displays[id] = vd
                        meta[id] = intArrayOf(w, h, dpi, lastFlags)
                        touched[id] = SystemClock.elapsedRealtime()
                    }
                    startReaper()
                    out.writeInt(0); out.writeInt(id); out.writeInt(lastFlags)
                }
                CREATE_PARKING -> {
                    val active = data.readInt()
                    val w = data.readInt(); val h = data.readInt(); val dpi = data.readInt()
                    val id = synchronized(displays) {
                        check(active in meta) { "active display $active not found" }
                        check(parking == null) { "previous parking display is still being cleaned up" }
                        // Private, own content only, display OFF (no Surface). Don't fall back to flags that
                        // might transfer its tasks to display 0 when torn down.
                        val vd = create("agent-vscreen-parking", w, h, dpi,
                            OWN_CONTENT_ONLY or DESTROY_CONTENT_ON_REMOVAL or TRUSTED, null)
                        vd.display.displayId.also {
                            displays[it] = vd
                            touched[it] = SystemClock.elapsedRealtime()
                            parking = active to it
                        }
                    }
                    out.writeInt(0); out.writeInt(id)
                }
                RELEASE -> {
                    val id = data.readInt()
                    synchronized(displays) { releaseLocked(id) }
                    sound.check()
                    out.writeInt(0)
                }
                SOUND -> {
                    when (data.readInt()) {
                        0 -> { val p = data.readString().orEmpty(); out.writeInt(0); out.writeInt(sound.mute(p)) }
                        1 -> { sound.give(data.readString().orEmpty()); out.writeInt(0) }
                        3 -> { sound.giveAll(); out.writeInt(0) }
                        else -> { val l = sound.list(); out.writeInt(0); out.writeInt(l.size); l.forEach { out.writeString(it) } }
                    }
                }
                // 1.4.2-beta3: the protocol follows the uid, so a launcher that adopts a running service (root) can tell
                // whether it is this build's. Older readers read the uid only.
                PING -> { out.writeInt(0); out.writeInt(android.os.Process.myUid()); out.writeInt(PROTOCOL) }
                EXEC -> {
                    val argv = data.createStringArray() ?: error("missing argv")
                    val env = data.createStringArray()
                    val cwd = data.readString()
                    val timeoutMs = data.readLong()
                    val o = android.os.ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    val e = android.os.ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    out.writeInt(0); out.writeInt(exec(argv, env, cwd, timeoutMs, o, e))
                }
                INJECT_KEY -> {
                    val id = data.readInt(); val key = data.readInt(); val longPress = data.readInt() != 0
                    val epoch = epochOf(data)
                    out.writeInt(0); out.writeInt(if (injectKey(id, key, longPress, epoch)) 1 else 0)
                }
                INJECT_COMBO -> {
                    val id = data.readInt(); val key = data.readInt(); val n = data.readInt()
                    val mods = IntArray(n) { data.readInt() }
                    val epoch = epochOf(data)
                    out.writeInt(0); out.writeInt(if (injectCombo(id, key, mods, epoch)) 1 else 0)
                }
                INJECT_TEXT -> {
                    val id = data.readInt(); val text = data.readString().orEmpty()
                    val epoch = epochOf(data)
                    out.writeInt(0); out.writeInt(if (injectText(id, text, epoch)) 1 else 0)
                }
                INJECT_DRAG -> {
                    val id = data.readInt()
                    val x1 = data.readInt(); val y1 = data.readInt(); val x2 = data.readInt(); val y2 = data.readInt()
                    val dur = data.readInt(); val hold = data.readInt()
                    val epoch = epochOf(data)
                    out.writeInt(0); out.writeInt(if (injectDrag(id, x1, y1, x2, y2, dur, hold, epoch)) 1 else 0)
                }
                FENCE -> {
                    // 1.4.2-beta6 (protocol 15): a change of control. Returns once the agent's input in flight has ended.
                    val id = data.readInt(); val epoch = data.readLong(); val allowed = data.readInt() != 0
                    fence.set(id, epoch, allowed)
                    val cancelled = synchronized(fence.lock(id)) { synchronized(fingers) { cancelFingers(id, epoch.coerceAtLeast(0L)) } }
                    out.writeInt(0); out.writeInt(cancelled)
                }
                LIST -> {
                    val snap = synchronized(displays) {
                        // Host restart: keep the active screen, but never adopt an old session's parked apps.
                        parking?.second?.let { releaseLocked(it) }
                        meta.map { it.key to it.value.copyOf() }
                    }
                    out.writeInt(0); out.writeInt(snap.size)
                    for ((id, m) in snap) { out.writeInt(id); m.forEach { out.writeInt(it) } }
                }
                SET_SURFACE -> {
                    val id = data.readInt()
                    val surface = Surface.CREATOR.createFromParcel(data)
                    val vd = synchronized(displays) { touched[id] = SystemClock.elapsedRealtime(); displays[id] }
                        ?: throw IllegalStateException("display $id not found")
                    vd.surface = surface
                    out.writeInt(0)
                }
                TOUCH -> {
                    val id = data.readInt()
                    synchronized(displays) {
                        val now = SystemClock.elapsedRealtime()
                        if (displays.containsKey(id)) touched[id] = now
                        parking?.takeIf { it.first == id }?.let { touched[it.second] = now }
                    }
                    out.writeInt(0)
                }
                PARK -> {
                    // v21 idle power saving: no surface = display off, the apps on it stop (pages kept).
                    val id = data.readInt()
                    val vd = synchronized(displays) { touched[id] = SystemClock.elapsedRealtime(); displays[id] }
                        ?: throw IllegalStateException("display $id not found")
                    vd.surface = null
                    out.writeInt(0)
                }
                ROTATION -> {
                    val id = data.readInt()
                    val disp = displayManager().getDisplay(id) ?: throw IllegalStateException("display $id not found")
                    val pt = android.graphics.Point()
                    @Suppress("DEPRECATION") disp.getRealSize(pt)
                    out.writeInt(0); out.writeInt(disp.rotation); out.writeInt(pt.x); out.writeInt(pt.y)
                }
                POINTER -> {
                    val id = data.readInt(); val op = data.readInt(); val pid = data.readInt()
                    val x = data.readFloat(); val y = data.readFloat()
                    val epoch = epochOf(data)
                    synchronized(displays) { if (displays.containsKey(id)) touched[id] = SystemClock.elapsedRealtime() }
                    val okp = pointer(id, op, pid, x, y, epoch)
                    out.writeInt(0); out.writeInt(if (okp) 1 else 0); out.writeInt(synchronized(fingers) { fingers[id]?.list?.size ?: 0 })
                }
                REMOVE_TASK -> {
                    val taskId = data.readInt(); val expected = data.readInt()
                    // Never remove a task the system places on another display (the user's main screen).
                    val on = runCatching { rootTasks().firstOrNull { taskId in it.children.map { c -> c.first } }?.displayId }.getOrNull()
                    val okRemove = on != null && on == expected && removeTask(taskId)
                    out.writeInt(0); out.writeInt(if (okRemove) 1 else 0)
                }
                LIST_TASKS -> {
                    val id = data.readInt()
                    val all = rootTasks()
                    if (all.isNotEmpty() && all.all { it.displayId < 0 }) error(tr("系统任务列表里没有 displayId", "The system task list has no displayId"))
                    val roots = all.filter { it.displayId == id }
                    val rows = roots.flatMap { r ->
                        // AOSP reports leaf IDs bottom first within each root. Report only the top leaf visible.
                        r.children.asReversed().mapIndexed { i, c -> listOf(c.first, c.second, if (i == 0 && r.visible) 1 else 0, r.type, if (i == 0) r.top else null) }
                    }
                    out.writeInt(0); out.writeInt(rows.size)
                    rows.forEach { row ->
                        out.writeInt(row[0] as Int); out.writeString(row[1] as String?); out.writeInt(row[2] as Int)
                        out.writeInt(row[3] as Int); out.writeString(row[4] as String?)
                    }
                }
                FOCUSED -> {
                    val f = runCatching { focusedRoot() }.getOrNull()
                    out.writeInt(0); out.writeInt(f?.first ?: -1); out.writeInt(f?.second ?: -1)
                }
                FOCUS_TASK -> {
                    val taskId = data.readInt()
                    val expected = if (data.dataAvail() >= 4) data.readInt() else null
                    val r = runCatching {
                        if (expected != null) check(rootTasks().any { r -> r.displayId == expected && r.children.any { it.first == taskId } })
                        val a = atmService()
                        a.javaClass.getMethod("setFocusedTask", Int::class.javaPrimitiveType).invoke(a, taskId); true
                    }.getOrDefault(false)
                    out.writeInt(0); out.writeInt(if (r) 1 else 0)
                }
                MOVE_TASK -> {
                    val taskId = data.readInt(); val target = data.readInt()
                    val source = if (data.dataAvail() >= 4) data.readInt() else null
                    val r = runCatching {
                        if (source == null) moveTask(taskId, target)
                        else synchronized(displays) {
                            check(parking == (source to target) || parking == (target to source)) { "parking display pair no longer exists" }
                            val root = rootTasks().firstOrNull { r -> r.displayId == source && r.children.any { it.first == taskId } }
                            check(root != null && root.children.size == 1) { "task moved away or shares a root with other tasks" }
                            moveTask(taskId, target)
                        }
                    }
                    val e = r.exceptionOrNull()?.let { (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it }
                    out.writeInt(0); out.writeInt(if (r.isSuccess) 1 else 0)
                    out.writeString(e?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "")
                }
                APS -> {
                    val op = data.readInt()
                    val pkg = data.readString() ?: throw IllegalArgumentException(tr("缺少包名", "Missing package name"))
                    when (op) {
                        0 -> {
                            val s = runCatching { aps() }
                            if (s.isFailure) { out.writeInt(0); out.writeInt(0); out.writeString(s.exceptionOrNull()?.message ?: "no aps_service") }
                            else {
                                val res = apsResolution(pkg)
                                val dyn = runCatching { (apsCall("getDynamicResolutionRatio", pkg) as Number).toFloat() }.getOrDefault(1f)
                                val info = runCatching { apsCall("getPackageApsInfo", pkg) }.getOrNull()
                                out.writeInt(0); out.writeInt(1); out.writeFloat(res); out.writeFloat(dyn)
                                out.writeInt(if (info != null) 1 else 0)
                                out.writeByteArray((info as? android.os.Parcelable)?.let { marshal(it) })
                            }
                        }
                        1 -> {
                            val ratio = data.readFloat()
                            val rc = apsCall("setResolution", pkg, ratio, false)
                            val code = if (rc is Number) rc.toInt() else if (rc == true) 0 else -1
                            out.writeInt(0); out.writeInt(code)
                            out.writeFloat(apsResolution(pkg))
                        }
                        2 -> {
                            val had = data.readInt() != 0
                            val blob = data.createByteArray()
                            val orig = data.readFloat()
                            val entry = if (had && blob != null) runCatching { unmarshal(blob) }.getOrNull() else null
                            val steps = ArrayList<String>()
                            fun hasNow() = runCatching { apsCall("getPackageApsInfo", pkg) != null }.getOrDefault(false)
                            when {
                                entry != null -> steps += "setPackageApsInfo=" + runCatching { apsCall("setPackageApsInfo", pkg, entry) }.fold({ "$it" }, { it.javaClass.simpleName })
                                had -> steps += tr("原配置无法还原（快照不可读），未动它", "The original cannot be restored (snapshot unreadable); left alone")
                                hasNow() -> steps += "deletePackageApsInfo=" + runCatching { apsCall("deletePackageApsInfo", pkg) }.fold({ "$it" }, { it.javaClass.simpleName })
                                else -> steps += tr("无单独配置，无需删除", "No separate setting, nothing to delete")
                            }
                            var rb = apsResolution(pkg)
                            // Last resort only when it cannot create an entry that was not there before.
                            if (kotlin.math.abs(rb - orig) > 0.002f && (had || hasNow())) {
                                steps += "setResolution=" + runCatching { apsCall("setResolution", pkg, orig, true) }.fold({ "$it" }, { it.javaClass.simpleName })
                                rb = apsResolution(pkg)
                            }
                            out.writeInt(0); out.writeFloat(rb); out.writeInt(if (hasNow()) 1 else 0); out.writeString(steps.joinToString("，"))
                        }
                        else -> throw IllegalArgumentException("unknown aps op $op")
                    }
                }
            }
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            out.writeInt(1); out.writeString("${cause.javaClass.simpleName}: ${cause.message}")
        }
        return true
    }

    @Volatile private var lastFlags = 0

    // ── Honor/Huawei APS (per-app render resolution) ─────────────────────

    private var apsSvc: Any? = null

    /** android.aps.IHwApsManager proxy for the "aps_service" binder (not "aps"). */
    private fun aps(): Any = synchronized(this) {
        apsSvc?.let { return it }
        val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "aps_service") as? android.os.IBinder ?: throw IllegalStateException(tr("本机没有 aps_service", "This phone has no aps_service"))
        val s = Class.forName("android.aps.IHwApsManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, b)
            ?: throw IllegalStateException(tr("aps_service 接口不可用", "aps_service interface unavailable"))
        apsSvc = s
        s
    }

    private fun apsCall(name: String, vararg a: Any?): Any? {
        val s = aps()
        val m = s.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == a.size }
            ?: throw NoSuchMethodException("IHwApsManager.$name/${a.size}")
        return m.invoke(s, *a)
    }

    private fun apsResolution(pkg: String): Float = (apsCall("getResolution", pkg) as Number).toFloat()

    private fun marshal(p: android.os.Parcelable): ByteArray? = runCatching {
        val pc = Parcel.obtain()
        try { pc.writeParcelable(p, 0); pc.marshall() } finally { pc.recycle() }
    }.getOrNull()

    private fun unmarshal(b: ByteArray): Any? {
        val pc = Parcel.obtain()
        try {
            pc.unmarshall(b, 0, b.size); pc.setDataPosition(0)
            @Suppress("DEPRECATION")
            return pc.readParcelable<android.os.Parcelable>(Class.forName("android.aps.IHwApsManager").classLoader)
        } finally { pc.recycle() }
    }

    // ── commands (root way in) ────────────────────────────────────────────

    private fun exec(
        argv: Array<String>, env: Array<String>?, cwd: String?, timeoutMs: Long,
        stdout: android.os.ParcelFileDescriptor, stderr: android.os.ParcelFileDescriptor,
    ): Int {
        val o = android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdout)
        val e = android.os.ParcelFileDescriptor.AutoCloseOutputStream(stderr)
        val p = try {
            val pb = ProcessBuilder(*argv)
            if (cwd != null) pb.directory(java.io.File(cwd))
            if (env != null) pb.environment().apply { clear(); env.forEach { kv -> val i = kv.indexOf('='); if (i > 0) put(kv.substring(0, i), kv.substring(i + 1)) } }
            pb.start()
        } catch (t: Throwable) {
            runCatching { e.write("${t.javaClass.simpleName}: ${t.message}\n".toByteArray()) }
            runCatching { o.close() }; runCatching { e.close() }
            return 127
        }
        fun pump(from: java.io.InputStream, to: java.io.OutputStream, name: String) = Thread {
            runCatching { from.use { i -> to.use { i.copyTo(it) } } }
        }.apply { isDaemon = true; this.name = name; start() }
        val t1 = pump(p.inputStream, o, "vscreen-exec-out")
        val t2 = pump(p.errorStream, e, "vscreen-exec-err")
        runCatching { p.outputStream.close() }
        val done = runCatching { p.waitFor(timeoutMs.coerceAtLeast(1L), java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!done) runCatching { p.destroyForcibly() }
        t1.join(2_000); t2.join(2_000)
        runCatching { o.close() }; runCatching { e.close() }
        return if (done) p.exitValue() else 124
    }

    // ── input injection ───────────────────────────────────────────────────

    /** (InputManager[Global] instance, injectInputEvent(InputEvent, int)). */
    private val injector: Pair<Any, java.lang.reflect.Method> by lazy {
        val im = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: android.hardware.input.InputManager::class.java.getMethod("getInstance").invoke(null)
        val m = im!!.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        im to m
    }
    private val setDisplayId: java.lang.reflect.Method by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    /** 1.4.2-beta6: who may put input on each display (see [InputFence]). */
    private val fence = InputFence()

    /** The control epoch an injection call carries (protocol 15); a caller without one is from before the fence. */
    private fun epochOf(data: Parcel): Long = if (data.dataAvail() >= 8) data.readLong() else InputFence.UNSET

    /** The key events of [ups] go up as cancelled: the app drops them instead of acting on a key press. */
    private fun cancelKeys(displayId: Int, downs: Collection<KeyEvent>) {
        for (d in downs) runCatching {
            inject(KeyEvent(d.downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, d.keyCode, 0, d.metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, d.scanCode, d.flags or KeyEvent.FLAG_CANCELED, InputDevice.SOURCE_KEYBOARD), displayId)
        }
    }

    private fun inject(event: InputEvent, displayId: Int, mode: Int = 0): Boolean {
        setDisplayId.invoke(event, displayId)
        // 0 = INJECT_INPUT_EVENT_MODE_ASYNC: never block on a busy app.
        // 1 = WAIT_FOR_RESULT: used for the final UP so it is known to be queued.
        return injector.second.invoke(injector.first, event, mode) as Boolean
    }

    private fun injectKey(displayId: Int, code: Int, longPress: Boolean, epoch: Long): Boolean = synchronized(fence.lock(displayId)) {
        if (!fence.admits(displayId, epoch)) return@synchronized false
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            0, InputDevice.SOURCE_KEYBOARD)
        var ok = inject(down, displayId)
        if (longPress) {
            Thread.sleep(600)
            // Fenced out while held: the key goes up cancelled, never as a long press.
            if (!fence.admits(displayId, epoch)) { cancelKeys(displayId, listOf(down)); return@synchronized false }
            val t = SystemClock.uptimeMillis()
            ok = inject(KeyEvent(now, t, KeyEvent.ACTION_DOWN, code, 1, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_LONG_PRESS, InputDevice.SOURCE_KEYBOARD), displayId) && ok
        }
        val up = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            0, InputDevice.SOURCE_KEYBOARD)
        inject(up, displayId) && ok
    }

    /**
     * 1.4.0-beta13 (rainbow: agents use keyboard shortcuts): a key with modifiers, the way a hardware keyboard sends it:
     * each modifier goes down (the meta state growing with it), the key goes down and up with the full meta state,
     * then the modifiers come up in reverse. Apps that read the meta state of the key and apps that track the
     * modifier keys themselves both see Ctrl+A.
     */
    private fun injectCombo(displayId: Int, code: Int, mods: IntArray, epoch: Long): Boolean = synchronized(fence.lock(displayId)) {
        if (!fence.admits(displayId, epoch)) return@synchronized false
        fun metaOf(m: Int) = when (m) {
            KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            else -> 0
        }
        val now = SystemClock.uptimeMillis()
        fun ev(action: Int, k: Int, meta: Int) = KeyEvent(now, SystemClock.uptimeMillis(), action, k, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        var meta = 0
        var ok = true
        for (m in mods) { meta = meta or metaOf(m); ok = inject(ev(KeyEvent.ACTION_DOWN, m, meta), displayId) && ok }
        ok = inject(ev(KeyEvent.ACTION_DOWN, code, meta), displayId) && ok
        ok = inject(ev(KeyEvent.ACTION_UP, code, meta), displayId) && ok
        for (m in mods.reversed()) { meta = meta and metaOf(m).inv(); ok = inject(ev(KeyEvent.ACTION_UP, m, meta), displayId, if (m == mods.first()) 1 else 0) && ok }
        ok
    }

    /**
     * 1.2: characters as key events on [displayId], the way the system's own `input text` makes them (the virtual
     * keyboard's character map), but sent from here with the display id set. False when a character has no key.
     */
    private fun injectText(displayId: Int, text: String, epoch: Long): Boolean = synchronized(fence.lock(displayId)) {
        if (text.isEmpty()) return@synchronized true
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) ?: return@synchronized false
        var ok = true
        val down = LinkedHashMap<Int, KeyEvent>()
        for ((i, e) in events.withIndex()) {
            // 1.4.2-beta6: asked before every key: once fenced out, the keys still down go up cancelled and the rest is dropped.
            if (!fence.admits(displayId, epoch)) { cancelKeys(displayId, down.values); return@synchronized false }
            val ev = KeyEvent(e.downTime, SystemClock.uptimeMillis(), e.action, e.keyCode, e.repeatCount, e.metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, e.scanCode, e.flags, InputDevice.SOURCE_KEYBOARD)
            // The last event waits until it is queued, so the caller's read-back sees the whole text.
            ok = inject(ev, displayId, if (i == events.lastIndex) 1 else 0) && ok
            if (e.action == KeyEvent.ACTION_DOWN) down[e.keyCode] = ev else down.remove(e.keyCode)
        }
        ok
    }

    private fun motion(downTime: Long, action: Int, x: Float, y: Float, flags: Int = 0): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
    }

    /**
     * A finger drag from (x1,y1) to (x2,y2) over [durationMs], then held
     * still for [holdMs] before lifting. The hold brings the velocity to
     * zero, so lists move by exactly the dragged distance instead of
     * flinging on. Same start and end = tap (or long press with a long hold).
     * 1.2: [holdMs] < 0 = a swipe: even speed, no hold, the finger lifts while moving (lists fling).
     */
    private fun injectDrag(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int, epoch: Long): Boolean = synchronized(fence.lock(displayId)) {
        if (!fence.admits(displayId, epoch)) return@synchronized false
        val downTime = SystemClock.uptimeMillis()
        var ok = true
        // 1.4.2-beta6: fenced out mid-gesture (takeover, Stop): no more events, and the finger is cancelled, not lifted.
        var fenced = false
        fun live(): Boolean { if (!fenced && !fence.admits(displayId, epoch)) fenced = true; return !fenced }
        fun send(action: Int, x: Float, y: Float) {
            val e = motion(downTime, action, x, y)
            ok = inject(e, displayId) && ok
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat())
        var lastX = x1.toFloat(); var lastY = y1.toFloat()
        try {
        if (x1 != x2 || y1 != y2) {
            val steps = (durationMs / 12).coerceIn(4, 400)
            for (i in 1..steps) {
                Thread.sleep((durationMs / steps).toLong().coerceAtLeast(1))
                if (!live()) break
                // Ease in-out so the start does not look like a flick.
                val t = i.toFloat() / steps
                val e = if (holdMs < 0) t else if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2) * (-2 * t + 2) / 2
                lastX = x1 + (x2 - x1) * e; lastY = y1 + (y2 - y1) * e
                send(MotionEvent.ACTION_MOVE, lastX, lastY)
            }
        }
        var held = 0
        while (held < holdMs && live()) {
            val step = minOf(16, holdMs - held)
            Thread.sleep(step.toLong()); held += step
            if (!live()) break
            if (x1 != x2 || y1 != y2) send(MotionEvent.ACTION_MOVE, x2.toFloat(), y2.toFloat())
        }
        if (!fenced) { lastX = x2.toFloat(); lastY = y2.toFloat() }
        } catch (t: Throwable) {
            fenced = true
            throw t
        } finally {
            // The finger must ALWAYS end: a lost end leaves the app mid-gesture (a half-pulled drawer, a list stuck in
            // overscroll) until the next touch. A gesture that did not finish ends with CANCEL: an UP would be a tap
            // (or a long press, a drop) the agent is no longer allowed to make.
            if (!fenced) live()
            val end = motion(downTime, if (fenced) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, lastX, lastY,
                if (fenced) MOTION_FLAG_CANCELED else 0)
            val sent = runCatching { inject(end, displayId, 1) }.getOrDefault(false) ||
                runCatching { inject(end, displayId, 0) }.getOrDefault(false)
            end.recycle()
            ok = sent && ok && !fenced
        }
        ok
    }

    // ── held fingers (multi-touch across calls) ──────────────────────────────

    private class Finger(val id: Int, var x: Float, var y: Float)
    private class Gesture(val downTime: Long) { val list = ArrayList<Finger>(); var lastMs = SystemClock.uptimeMillis() }
    /** displayId -> fingers currently down, in down order. */
    private val fingers = HashMap<Int, Gesture>()
    @Volatile private var fingerWatch = false

    private fun multi(g: Gesture, action: Int, flags: Int = 0): MotionEvent {
        val n = g.list.size
        val props = Array(n) { i -> MotionEvent.PointerProperties().apply { id = g.list[i].id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(n) { i -> MotionEvent.PointerCoords().apply { x = g.list[i].x; y = g.list[i].y; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(g.downTime, SystemClock.uptimeMillis(), action, n, props, coords, 0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
    }

    /**
     * One finger change on [displayId]; every event carries all fingers that are down. 1.4.2-beta6: down / move / up
     * need [epoch] to be admitted by the fence; op 3 cancels the caller's own side ([InputFence.cancels]).
     */
    private fun pointer(displayId: Int, op: Int, pid: Int, x: Float, y: Float, epoch: Long): Boolean = synchronized(fence.lock(displayId)) {
        if (op in 0..2 && !fence.admits(displayId, epoch)) return@synchronized false
        synchronized(fingers) {
            fun send(g: Gesture, action: Int, mode: Int = 0): Boolean {
                val e = multi(g, action); g.lastMs = SystemClock.uptimeMillis()
                return try { inject(e, displayId, mode) } finally { e.recycle() }
            }
            when (op) {
                0 -> {
                    val g = fingers[displayId]
                    if (g == null) {
                        val ng = Gesture(SystemClock.uptimeMillis()).also { it.list += Finger(pid, x, y) }
                        fingers[displayId] = ng
                        startFingerWatch()
                        send(ng, MotionEvent.ACTION_DOWN)
                    } else {
                        g.list.firstOrNull { it.id == pid }?.let { it.x = x; it.y = y; return@synchronized send(g, MotionEvent.ACTION_MOVE) }
                        g.list += Finger(pid, x, y)
                        val idx = g.list.size - 1
                        send(g, MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                    }
                }
                1 -> {
                    val g = fingers[displayId] ?: return@synchronized false
                    val f = g.list.firstOrNull { it.id == pid } ?: return@synchronized false
                    f.x = x; f.y = y
                    send(g, MotionEvent.ACTION_MOVE)
                }
                2 -> {
                    val g = fingers[displayId] ?: return@synchronized false
                    val idx = g.list.indexOfFirst { it.id == pid }
                    if (idx < 0) return@synchronized false
                    if (x >= 0 && y >= 0) { g.list[idx].x = x; g.list[idx].y = y }
                    val r = if (g.list.size == 1) {
                        send(g, MotionEvent.ACTION_UP, 1).also { fingers.remove(displayId) }
                    } else {
                        send(g, MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)).also { g.list.removeAt(idx) }
                    }
                    r
                }
                else -> { cancelFingers(displayId, epoch); true }
            }
        }
    }

    /**
     * Cancel the held fingers on [displayId] that a cancel from [epoch] may take away ([InputFence.cancels]); returns
     * how many. 1.4.2-beta6: never a lift. Before, a cancel sent ACTION_UP at the finger's last point, which apps take
     * as a tap: hiding the window with a pinch tapped the background screen under the user's finger, and a Stop could
     * complete the agent's press. When every finger down is cancelled the whole gesture gets ACTION_CANCEL; otherwise
     * each cancelled finger leaves with a POINTER_UP marked canceled and the others stay down. Caller holds [fingers].
     */
    private fun cancelFingers(displayId: Int, epoch: Long): Int {
        val g = fingers[displayId] ?: return 0
        val gone = g.list.filter { InputFence.cancels(epoch, it.id) }
        if (gone.isEmpty()) return 0
        if (gone.size == g.list.size) {
            val e = multi(g, MotionEvent.ACTION_CANCEL, MOTION_FLAG_CANCELED)
            runCatching { inject(e, displayId, 1) }; e.recycle()
            fingers.remove(displayId)
            return gone.size
        }
        for (f in gone) {
            val idx = g.list.indexOf(f)
            val e = multi(g, MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), MOTION_FLAG_CANCELED)
            runCatching { inject(e, displayId) }; e.recycle()
            g.list.removeAt(idx)
        }
        g.lastMs = SystemClock.uptimeMillis()
        return gone.size
    }

    /** Cancel every finger on [displayId] (the safety timeout). Caller holds [fingers]. */
    private fun cancelAll(displayId: Int) {
        val g = fingers.remove(displayId) ?: return
        if (g.list.isEmpty()) return
        val e = multi(g, MotionEvent.ACTION_CANCEL, MOTION_FLAG_CANCELED)
        runCatching { inject(e, displayId, 1) }; e.recycle()
    }

    /** A finger nobody moved for [FINGER_MAX_IDLE_MS] is cancelled, so a dead app never leaves a finger down. */
    private fun startFingerWatch() {
        if (fingerWatch) return
        fingerWatch = true
        Thread {
            while (true) {
                com.kilon.agentvscreen.host.quiet("VScreenDisplayService:508") { Thread.sleep(1_000) }
                synchronized(fingers) {
                    val now = SystemClock.uptimeMillis()
                    fingers.entries.filter { now - it.value.lastMs > FINGER_MAX_IDLE_MS }.map { it.key }.forEach { cancelAll(it) }
                }
            }
        }.apply { isDaemon = true; name = "vscreen-fingers" }.start()
    }

    private fun atmService(): Any = runCatching {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    }.getOrNull() ?: Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!

    /** One root task as the system reports it: its display, child tasks (id, component), visibility, activity type, top activity. */
    private class Root(val displayId: Int, val children: List<Pair<Int, String?>>, val visible: Boolean, val type: Int, val top: String?)

    private fun pub(o: Any, name: String): Any? = runCatching { o.javaClass.getField(name).get(o) }.getOrNull()

    private fun rootOf(o: Any): Root {
        val disp = (pub(o, "displayId") as? Int) ?: -1
        val ids = (pub(o, "childTaskIds") ?: pub(o, "taskIds")) as? IntArray ?: IntArray(0)
        @Suppress("UNCHECKED_CAST")
        val names = (pub(o, "childTaskNames") ?: pub(o, "taskNames")) as? Array<String?> ?: emptyArray()
        val ownId = pub(o, "taskId") as? Int
        val kids = if (ids.isNotEmpty()) ids.mapIndexed { i, t -> t to names.getOrNull(i) }
            else listOfNotNull(ownId?.let { it to (pub(o, "baseActivity") as? android.content.ComponentName)?.flattenToShortString() })
        val type = runCatching { o.javaClass.getMethod("getActivityType").invoke(o) as Int }.getOrElse {
            runCatching {
                val cfg = pub(o, "configuration") as android.content.res.Configuration
                val wc = cfg.javaClass.getField("windowConfiguration").get(cfg)
                wc.javaClass.getMethod("getActivityType").invoke(wc) as Int
            }.getOrDefault(0)
        }
        val top = (pub(o, "topActivity") as? android.content.ComponentName)?.flattenToShortString()
        return Root(disp, kids, (pub(o, "visible") as? Boolean) ?: false, type, top)
    }

    /** Every root task on every display, top first per display (ActivityTaskManager, display-scoped by the system itself). */
    private fun rootTasks(): List<Root> {
        val a = atmService()
        val list = runCatching { a.javaClass.getMethod("getAllRootTaskInfos").invoke(a) }
            .getOrElse { a.javaClass.getMethod("getAllStackInfos").invoke(a) } as List<*>
        return list.filterNotNull().map { rootOf(it) }
    }

    /** (displayId, taskId) of the root task that has the system's input focus. */
    private fun focusedRoot(): Pair<Int, Int>? {
        val a = atmService()
        val o = runCatching { a.javaClass.getMethod("getFocusedRootTaskInfo").invoke(a) }
            .getOrElse { a.javaClass.getMethod("getFocusedStackInfo").invoke(a) } ?: return null
        val r = rootOf(o)
        return r.displayId to (r.children.lastOrNull()?.first ?: -1)
    }

    /** Remove one task (the background screen's own copy of an app) without killing the app. */
    private fun removeTask(taskId: Int): Boolean {
        val atm = atmService()
        val m = atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
        return m.invoke(atm, taskId) as Boolean
    }

    /** v22: move a whole task (its root task) to another display, as it is. */
    private fun moveTask(taskId: Int, displayId: Int) {
        val atm = atmService()
        val ip = Int::class.javaPrimitiveType
        val m = runCatching { atm.javaClass.getMethod("moveRootTaskToDisplay", ip, ip) }.getOrNull()
            ?: atm.javaClass.getMethod("moveStackToDisplay", ip, ip)
        // On some ROMs (vivo) an app task sits inside another root task: the system only knows the root's id.
        val root = runCatching { rootIdOf(taskId) }.getOrNull()
        val ids = listOfNotNull(root, taskId).distinct()
        var last: Throwable? = null
        for (id in ids) {
            try { m.invoke(atm, id, displayId); return } catch (t: Throwable) { last = t }
        }
        val e = (last as? java.lang.reflect.InvocationTargetException)?.targetException ?: last
        throw IllegalStateException("tried root ${ids.joinToString("/")}: ${e?.javaClass?.simpleName}: ${e?.message}")
    }

    /** The root task that holds [taskId] (its own id when it is a root). */
    private fun rootIdOf(taskId: Int): Int? {
        val a = atmService()
        val list = runCatching { a.javaClass.getMethod("getAllRootTaskInfos").invoke(a) }
            .getOrElse { a.javaClass.getMethod("getAllStackInfos").invoke(a) } as List<*>
        for (o in list.filterNotNull()) {
            val own = (pub(o, "taskId") ?: pub(o, "stackId")) as? Int
            val kids = (pub(o, "childTaskIds") ?: pub(o, "taskIds")) as? IntArray
            if (own == taskId || kids?.contains(taskId) == true) return own
        }
        return null
    }

    private fun create(name: String, w: Int, h: Int, dpi: Int, requested: Int, surface: Surface?): VirtualDisplay {
        val dm = displayManager()
        val candidates = if (requested != 0) listOf(requested) else defaultFlagLadder()
        var last: Throwable? = null
        for (f in candidates) {
            try {
                val vd = dm.createVirtualDisplay(name, w, h, dpi, surface, f)
                    ?: throw IllegalStateException("createVirtualDisplay returned null")
                lastFlags = f
                return vd
            } catch (t: Throwable) { last = t }
        }
        throw last ?: IllegalStateException("no flags tried")
    }

    private fun displayManager(): DisplayManager {
        val ctx = ShellContext(base ?: systemContext())
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(ctx)
    }

    private fun systemContext(): Context {
        if (Looper.getMainLooper() == null) runCatching { Looper.prepareMainLooper() }
        val at = Class.forName("android.app.ActivityThread")
        val current = runCatching { at.getMethod("currentActivityThread").invoke(null) }.getOrNull()
            ?: at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(current) as Context
    }

    /** Presents itself as com.android.shell so DisplayManagerService's package/uid check passes. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PKG
        override fun getOpPackageName(): String = SHELL_PKG
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            if (Build.VERSION.SDK_INT >= 31) AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL_PKG).build()
            else super.getAttributionSource()
    }

    companion object {
        const val DESCRIPTOR = "com.kilon.agentvscreen.core.VScreenDisplayService"
        const val CREATE = FIRST_CALL_TRANSACTION
        const val RELEASE = FIRST_CALL_TRANSACTION + 1
        const val PING = FIRST_CALL_TRANSACTION + 2
        const val INJECT_KEY = FIRST_CALL_TRANSACTION + 3
        const val INJECT_DRAG = FIRST_CALL_TRANSACTION + 4
        const val REMOVE_TASK = FIRST_CALL_TRANSACTION + 5
        const val LIST = FIRST_CALL_TRANSACTION + 6
        const val SET_SURFACE = FIRST_CALL_TRANSACTION + 7
        const val TOUCH = FIRST_CALL_TRANSACTION + 8
        const val ROTATION = FIRST_CALL_TRANSACTION + 9
        const val POINTER = FIRST_CALL_TRANSACTION + 10
        const val APS = FIRST_CALL_TRANSACTION + 11
        const val PARK = FIRST_CALL_TRANSACTION + 12
        const val MOVE_TASK = FIRST_CALL_TRANSACTION + 13
        const val LIST_TASKS = FIRST_CALL_TRANSACTION + 14
        const val FOCUSED = FIRST_CALL_TRANSACTION + 15
        const val FOCUS_TASK = FIRST_CALL_TRANSACTION + 16
        /** 1.2 (protocol 10). */
        const val INJECT_TEXT = FIRST_CALL_TRANSACTION + 17
        /** 1.4.0-beta11 (protocol 11): op 0 MUTE pkg -> 0/1/2, op 1 GIVE pkg, op 2 LIST -> n, n x pkg, op 3 GIVE_ALL. */
        const val SOUND = FIRST_CALL_TRANSACTION + 18
        /** 1.4.0-beta13 (protocol 12): in displayId, keyCode, n, n x modifier keyCode  out: status, injected(0/1). */
        const val INJECT_COMBO = FIRST_CALL_TRANSACTION + 19
        /**
         * 1.4.2-beta3 (protocol 13): run a command as this service's user (shell, or root's shell), for the root way
         * in, which has no Shizuku to spawn processes. in argv[], env[]|null, cwd|null, timeoutMs, stdout fd, stderr fd
         * (write ends of the caller's pipes; the output streams there, so its size has no binder limit)
         * out: status, exit code (124 = timed out, the coreutils convention).
         */
        const val EXEC = FIRST_CALL_TRANSACTION + 20
        const val CREATE_PARKING = FIRST_CALL_TRANSACTION + 21
        /**
         * 1.4.2-beta6 (protocol 15): a change of control. in displayId, epoch(long), agentAllowed(0/1)  out: status,
         * agent fingers cancelled. Every INJECT_* and POINTER call now ends with the epoch (long) it was planned under
         * ([InputFence.USER] for the user's own input); see [InputFence].
         */
        const val FENCE = FIRST_CALL_TRANSACTION + 22
        /** The newest call code; onTransact serves CREATE..LAST_CALL. A new code moves this. */
        const val LAST_CALL = FENCE
        /** Apps the service silenced, so a service that was killed gives them back on its next start. Shell-owned. */
        const val FILE = "/data/local/tmp/agent-vscreen-muted.json"
        /** "allow" / "ignore" / "default" … as `appops get` says it, or null when it cannot be read. */
        /** The package's own PLAY_AUDIO record ("default" when it has none); null when the output says nothing. */
        fun parsePlayAudio(out: String): String? =
            PlayAudio.parse(out).pkg ?: if (out.contains("No operations", true) || out.contains("PLAY_AUDIO")) "default" else null
        /** MotionEvent.FLAG_CANCELED (public from API 33; older input stacks ignore the bit, the CANCEL action carries it). */
        private const val MOTION_FLAG_CANCELED = 0x20
        /** A held finger with no update for this long is cancelled by the service. */
        const val FINGER_MAX_IDLE_MS = 30_000L
        /** A display nobody touched for this long (app gone) is released. */
        const val ORPHAN_MS = 20 * 60_000L
        /** Bump when the protocol changes, so Shizuku restarts an old service process. */
        const val PROTOCOL = 15
        /** Shizuku's reserved "destroy user service" transaction. */
        const val DESTROY_CODE = 16777115
        private const val SHELL_PKG = "com.android.shell"

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (some are @hide).
        private const val PUBLIC = 1 shl 0
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val ROTATES_WITH_CONTENT = 1 shl 7
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val OWN_FOCUS = 1 shl 14
        private const val DEVICE_DISPLAY_GROUP = 1 shl 15
        /** VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED (API 34): needs OWN_FOCUS + TRUSTED. */
        private const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        /** Richest first (scrcpy --new-display set), then progressively plainer for strict ROMs. */
        fun defaultFlagLadder(): List<Int> {
            val basic = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL
            val out = ArrayList<Int>()
            if (Build.VERSION.SDK_INT >= 34) {
                // v27: first try a screen that never takes the main screen's focus (keyboard stays with the user).
                out += basic or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS or DEVICE_DISPLAY_GROUP or STEAL_TOP_FOCUS_DISABLED
                out += basic or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS or DEVICE_DISPLAY_GROUP
            }
            if (Build.VERSION.SDK_INT >= 33) {
                out += basic or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
                out += basic or TRUSTED
            }
            out += basic
            out += PUBLIC or OWN_CONTENT_ONLY
            return out
        }

        fun isTrusted(flags: Int) = flags and TRUSTED != 0
        fun keepsMainFocus(flags: Int) = flags and STEAL_TOP_FOCUS_DISABLED != 0

        /** Human-readable flag names for status output. */
        fun flagNames(flags: Int): List<String> = listOf(
            PUBLIC to "PUBLIC", OWN_CONTENT_ONLY to "OWN_CONTENT_ONLY", SUPPORTS_TOUCH to "SUPPORTS_TOUCH",
            ROTATES_WITH_CONTENT to "ROTATES_WITH_CONTENT", DESTROY_CONTENT_ON_REMOVAL to "DESTROY_CONTENT_ON_REMOVAL",
            TRUSTED to "TRUSTED", OWN_DISPLAY_GROUP to "OWN_DISPLAY_GROUP", ALWAYS_UNLOCKED to "ALWAYS_UNLOCKED",
            TOUCH_FEEDBACK_DISABLED to "TOUCH_FEEDBACK_DISABLED", OWN_FOCUS to "OWN_FOCUS",
            DEVICE_DISPLAY_GROUP to "DEVICE_DISPLAY_GROUP", STEAL_TOP_FOCUS_DISABLED to "STEAL_TOP_FOCUS_DISABLED",
        ).filter { flags and it.first != 0 }.map { it.second }
    }
}
