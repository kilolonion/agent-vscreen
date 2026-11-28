package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.tr

import android.content.Context
import com.kilon.agentvscreen.host.Privileged
import com.kilon.agentvscreen.host.ShizukuManager

/**
 * [vscreen] Privileged shell operations aimed at ONE display, run through
 * Shizuku (uid 2000 in ADB mode, no root).
 *
 * Every method takes the display id explicitly; nothing here ever falls
 * back to display 0.
 *
 * 1.2: one way in for each thing. Touches, keys and typed characters are all injected by the Shizuku display service
 * with the display id set ([Injector]); the `input` command and its form probing are gone. Which tasks are on which
 * display comes from the system's own per-display list ([taskSource]); the dumpsys parsers are gone. When the service
 * is not there the answer is "not connected", never a second, differently behaving path.
 */
internal object VScreenShell {
    private const val TAG = "VScreenShell"

    data class Outcome(val ok: Boolean, val code: String, val message: String)

    private fun ok(msg: String) = Outcome(true, "OK", msg)
    private fun fail(code: String, msg: String) = Outcome(false, code, msg)

    /** Test seam. */
    @Volatile internal var runner: (Array<String>, Long) -> ShizukuManager.ProcessResult =
        { argv, t -> com.kilon.agentvscreen.host.Privileged.runProcess(argv, null, null, t) }

    fun run(argv: Array<String>, timeoutMs: Long = 8_000L) = runner(argv, timeoutMs)

    // ── launch ─────────────────────────────────────────────────────────────

    /** ROOT: an app already on the background screen goes back to its first page (default). KEEP: just bring it forward. RESTART: force-stop first. */
    enum class LaunchMode { ROOT, KEEP, RESTART }

    @Volatile internal var taskFocus: (Int, Int) -> Result<Boolean> = { taskId, display -> VScreenRemoteDisplay.focusTask(taskId, display) }

    /** Focus this exact task without sending a launcher intent (which can recreate activities). */
    fun bringToFront(displayId: Int, taskId: Int): Boolean {
        Control.admitInput()
        if (VScreenHandoff.tasksOrNull(displayId)?.any { it.taskId == taskId && it.foreground } == true) return true
        if (taskFocus(taskId, displayId).getOrNull() != true) return false
        repeat(8) {
            if (VScreenHandoff.tasksOrNull(displayId)?.any { it.taskId == taskId && it.foreground } == true) return true
            Thread.sleep(200)
        }
        return false
    }

    fun launch(displayId: Int, pkg: String, activity: String?, mode0: LaunchMode = LaunchMode.ROOT, intent: List<String>? = null): Outcome {
        Control.admitInput()
        val clean = pkg.trim()
        if (clean.isEmpty()) return fail("INVALID_ARGS", tr(S.vscreen_display_service_missing_package_name))
        if (!PackageNames.isValid(clean)) return fail("INVALID_ARGS", tr(S.vscreen_open_commands_invalid_package_name, clean))
        // v22 传送门: with an explicit intent (deep link / shortcut / learned page) the app is
        // always opened in a fresh task on the background screen (never "keep").
        val mode = if (intent != null && mode0 == LaunchMode.KEEP) LaunchMode.ROOT else mode0
        val explicit = activity?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.contains('/')) it else "$clean/$it" }
        if (mode == LaunchMode.KEEP) {
            val target = explicit?.substringBefore('/') ?: clean
            val tasks = VScreenHandoff.tasksOrNull(displayId) ?: return fail("LAUNCH_FAILED",
                tr(S.vscreen_shell_cannot_read_the_screen_s, target))
            tasks.firstOrNull { it.pkg == target }?.let { task ->
                return if (bringToFront(displayId, task.taskId))
                    ok(tr(S.vscreen_shell_switched_to_on_screen_current, displayId, target))
                else fail("LAUNCH_NOT_ON_TOP", tr(S.vscreen_shell_the_system_did_not_bring, target))
            }
        }
        val component: String = explicit ?: (if (intent != null) "" else resolveLauncher(clean))
            ?: return fail("APP_NOT_FOUND", tr(S.vscreen_shell_no_launch_entry_for_not, clean))
        val targetPkg = if (component.isEmpty()) clean else component.substringBefore('/')
        val what = if (intent != null) intent.joinToString(" ") else component
        var prefix = ""
        if (mode == LaunchMode.RESTART) {
            val r = run(arrayOf("am", "force-stop", targetPkg), 8_000L)
            prefix = if (r.exitCode == 0) tr(S.vscreen_shell_stopped_and_cold_started_it, targetPkg) else tr(S.vscreen_shell_stopping_failed_starting_anyway, targetPkg, r.combined.take(80))
            Thread.sleep(300)
        } else if (mode == LaunchMode.ROOT) {
            val taskId = taskIdOn(displayId, targetPkg)
            if (taskId != null) {
                // Back to the first page: drop the background screen's own task
                // (the app keeps running, the user's copy on the main screen is
                // untouched), then start a fresh task below.
                val removed = VScreenRemoteDisplay.removeTask(taskId, displayId)
                prefix = if (removed.getOrNull() == true) { Thread.sleep(250); tr(S.vscreen_shell_closed_the_old_page_stack) }
                else tr(S.vscreen_shell_could_not_close_the_old) + (removed.exceptionOrNull()?.message ?: tr(S.vscreen_shell_refused_by_the_system)) + tr(S.vscreen_shell_opening_a_new_task)
            }
        }
        // --windowingMode 1 = fullscreen on the target display. -W waits for the launch.
        // -f 0x18000000 = NEW_TASK | MULTIPLE_TASK: start a fresh task on the target
        // display instead of pulling the user's existing task (on the main screen)
        // to the front where it already lives.
        val target = intent ?: listOf("-n", component)
        // 1.4.2-beta6: the start is a task of its own (MULTIPLE_TASK). When the user has the app open in front on the
        // main screen, the reply says that copy was not taken over (a tester read "launched" as "got the user's app").
        val userHasIt = runCatching { topPackage(0) == targetPkg }.getOrDefault(false)
        val separate = if (userHasIt) tr(S.vscreen_shell_the_open_in_front_on, targetPkg) else ""
        val argv = arrayOf(
            "am", "start", "-W", "--display", displayId.toString(),
            "--windowingMode", "1", "-f", "0x18000000",
        ) + target.toTypedArray()
        var r = run(argv, 15_000L)
        if (r.combined.contains("Unknown option") || r.combined.contains("windowingMode")) {
            r = run(arrayOf("am", "start", "-W", "--display", displayId.toString(), "-f", "0x18000000") + target.toTypedArray(), 15_000L)
        }
        val out = r.combined.trim()
        if (r.exitCode != 0 || out.contains("Error") || out.contains("Status: error")) {
            if (intent != null && (out.contains("Permission Denial") || out.contains("not exported")))
                return fail("PORTAL_DENIED", tr(S.vscreen_shell_the_system_does_not_allow, what, out.take(200)))
            if (intent != null && (out.contains("unable to resolve") || out.contains("Unable to resolve")))
                return fail("PORTAL_UNRESOLVED", tr(S.vscreen_shell_does_not_accept_this_entry, targetPkg, what))
            return fail("LAUNCH_FAILED", tr(S.vscreen_shell_starting_on_screen_failed, displayId, what) + out.ifBlank { "exit=${r.exitCode}" })
        }
        // Verify where it actually landed: on an untrusted display the system
        // quietly redirects the launch to the main screen and still says OK.
        var top: String? = null
        for (i in 0 until 6) {
            Thread.sleep(300)
            top = topPackage(displayId)
            if (top == targetPkg) return ok((if (intent != null) tr(S.vscreen_shell_opened_directly_on_screen, prefix, displayId, what) else tr(S.vscreen_shell_started_home_page_on_screen, prefix, displayId, component)) + separate)
        }
        // Not when it was already there before this launch: then it says nothing about where this start went.
        val onMain = !userHasIt && topPackage(0) == targetPkg
        return if (onMain) {
            val why = VScreenDisplay.lastRemoteError?.let { tr(S.vscreen_shell_shizuku_screen_service_unavailable_only, it) } ?: ""
            fail("LAUNCHED_ON_MAIN_SCREEN", tr(S.vscreen_shell_the_system_opened_on_the, targetPkg, displayId, why))
        } else if (top == null) {
            ok(tr(S.vscreen_shell_asked_to_start_on_screen, displayId, what))
        } else {
            fail("LAUNCH_NOT_ON_TOP", tr(S.vscreen_shell_the_top_of_screen_is, displayId, top, targetPkg))
        }
    }

    internal fun resolveLauncher(pkg: String): String? {
        val r = run(arrayOf("cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg), 8_000L)
        if (r.exitCode != 0) return null
        val comp = r.combined.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('/') && !it.contains(' ') }
            .lastOrNull() ?: return null
        if (!isResolver(comp)) return comp
        // Several launcher entries in one package (联系人 + 拨号 …): resolve-activity answers
        // with the system chooser, which on a background screen is often empty. Use the
        // package's own first entry instead.
        val q = run(arrayOf("cmd", "package", "query-activities", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg), 8_000L)
        return q.combined.lineSequence().map { it.trim() }
            .filter { it.startsWith("$pkg/") && !it.contains(' ') && !isResolver(it) }
            .firstOrNull()
    }

    private val RESOLVER = Regex("ResolverActivity|ChooserActivity", RegexOption.IGNORE_CASE)

    /** The system "open with" chooser rather than an app's own activity. */
    fun isResolver(component: String): Boolean = RESOLVER.containsMatchIn(component) || component.startsWith("android/")

    /**
     * v27: the system's own per-display task list (ActivityTaskManager via the Shizuku service),
     * null when the service is not connected or too old. Tests replace it.
     */
    @Volatile internal var taskSource: (Int) -> List<VScreenRemoteDisplay.DisplayTask>? =
        { id -> VScreenRemoteDisplay.listTasksIfConnected(id) }

    /** "pkg/.Activity" in front on [displayId]; null when nothing is there or the display service is not connected. */
    fun topActivity(displayId: Int): String? = taskSource(displayId)?.let { list -> front(list)?.let { it.rootTop ?: it.component } }

    /**
     * v41: the task really in front. The system's list is not always in z-order (v40 field test: `stop` closed
     * Settings, still listed first behind a full-screen game): a visible app task wins over hidden ones.
     */
    internal fun front(list: List<VScreenRemoteDisplay.DisplayTask>): VScreenRemoteDisplay.DisplayTask? =
        list.firstOrNull { it.visible && it.isAppTask } ?: list.firstOrNull { it.visible } ?: list.firstOrNull()

    /** Package currently on top of [displayId]. */
    fun topPackage(displayId: Int): String? = topActivity(displayId)?.substringBefore('/')

    /**
     * The system's compatibility scale for [pkg]'s window on [displayId] (dumpsys mGlobalScale):
     * 1.0 = drawn at full size; >1 = the app renders smaller and the system scales it up
     * (vendor resolution downscaling). Null when not found.
     */
    fun windowScale(displayId: Int, pkg: String): Double? {
        var inWin = false
        var onDisp = false
        for (l in run(arrayOf("dumpsys", "window", "windows"), 8_000L).combined.lines()) {
            val t = l.trim()
            if (t.startsWith("Window #")) { inWin = t.contains(" $pkg/"); onDisp = false; continue }
            if (!inWin) continue
            if (t.startsWith("mDisplayId=")) onDisp = t.startsWith("mDisplayId=$displayId ")
            if (onDisp) Regex("mGlobalScale=([0-9.]+)").find(t)?.let { return it.groupValues[1].toDoubleOrNull() }
        }
        return null
    }

    /**
     * Ask the system not to render [pkg] at a reduced resolution (vendor/game downscaling). Takes
     * effect at the app's next start. Best effort; returns what each step said.
     */
    fun disableDownscale(pkg: String): String {
        if (!PackageNames.isValid(pkg)) return tr(S.vscreen_open_commands_invalid_package_name, pkg)
        fun step(label: String, argv: Array<String>): String {
            val r = run(argv, 8_000L)
            val msg = r.combined.trim().lines().firstOrNull { it.isNotBlank() }?.take(120)
            return "$label: " + if (r.exitCode == 0) (msg ?: "ok") else tr(S.fullscreen_activity_failed) + " (${msg ?: "exit ${r.exitCode}"})"
        }
        return listOf(
            step("am compat disable DOWNSCALED", arrayOf("am", "compat", "disable", "DOWNSCALED", pkg)),
            step("cmd game set --downscale disable", arrayOf("cmd", "game", "set", "--downscale", "disable", pkg)),
        ).joinToString(tr(S.agent_guide_sep))
    }

    /** Undo [disableDownscale]. */
    fun resetDownscale(pkg: String) {
        if (!PackageNames.isValid(pkg)) return
        com.kilon.agentvscreen.host.quiet("VScreenShell:189") { run(arrayOf("am", "compat", "reset", "DOWNSCALED", pkg), 8_000L) }
        com.kilon.agentvscreen.host.quiet("VScreenShell:190") { run(arrayOf("cmd", "game", "reset", pkg), 8_000L) }
    }

    /** Orientation the app's entry activity declares: true = landscape, false = portrait, null = free / unknown. */
    /** v48: the device's natural orientation (rotation 0) is landscape (a tablet) — not the screen's current state. */
    fun naturalLandscape(context: Context): Boolean = runCatching {
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val disp = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val p = android.graphics.Point(); @Suppress("DEPRECATION") disp.getRealSize(p)
        (p.x > p.y) != (disp.rotation % 2 == 1)
    }.getOrDefault(false)

    fun appOrientation(context: Context, pkg: String, activity: String?): Boolean? = runCatching {
        val pm = context.packageManager
        val comp = activity?.let { a ->
            if (a.contains('/')) android.content.ComponentName.unflattenFromString(a)
            else android.content.ComponentName(pkg, if (a.startsWith(".")) pkg + a else a)
        } ?: pm.getLaunchIntentForPackage(pkg)?.component ?: return@runCatching null
        when (pm.getActivityInfo(comp, 0).screenOrientation) {
            0, 6, 8, 11 -> true   // landscape, sensorLandscape, reverseLandscape, userLandscape
            1, 7, 9, 12 -> false  // portrait, sensorPortrait, reversePortrait, userPortrait
            else -> null
        }
    }.getOrNull()

    /** Id of [pkg]'s task on [displayId] (the background screen's own copy), or null. */
    fun taskIdOn(displayId: Int, pkg: String): Int? =
        taskSource(displayId)?.firstOrNull { it.pkg == pkg || it.component?.substringBefore('/') == pkg }?.taskId

    /**
     * Close [pkg]'s window on the background screen only (remove its task there). The app process
     * and any page of it on the main screen are left alone. Null when the task cannot be found.
     */
    fun closeOnDisplay(displayId: Int, pkg: String?): Outcome? {
        val target = pkg?.trim()?.takeIf { it.isNotEmpty() } ?: topPackage(displayId) ?: return ok(tr(S.vscreen_shell_no_app_on_screen, displayId))
        val task = taskIdOn(displayId, target) ?: return null
        // v27: never a task the main screen lists (the v26 field test closed Minis' own main task this way).
        if (displayId == 0 || VScreenHandoff.tasksOn(0).any { it.taskId == task }) return fail("MAIN_SCREEN_TASK", tr(S.vscreen_shell_this_task_of_is_on, target))
        val r = VScreenRemoteDisplay.removeTask(task, displayId)
        return if (r.getOrNull() == true) ok(tr(S.vscreen_shell_closed_on_the_screen_only, target)) else null
    }

    /**
     * Packages the background screen must never force-stop: this app itself,
     * the launcher and the system UI. Set from BackgroundOperation.init.
     */
    @Volatile var protectedPackages: Set<String> = setOf("com.android.systemui", "android")

    fun isProtected(pkg: String?): Boolean = pkg != null && pkg in protectedPackages

    /** Force-stop [pkg] (default: the app on top of [displayId]). Note: this stops the app everywhere. */
    fun stop(displayId: Int, pkg: String?): Outcome {
        val target = pkg?.trim()?.takeIf { it.isNotEmpty() } ?: topPackage(displayId)
            ?: return ok(tr(S.vscreen_shell_no_app_on_screen, displayId))
        if (!PackageNames.isValid(target)) return fail("INVALID_ARGS", tr(S.vscreen_open_commands_invalid_package_name, target))
        if (isProtected(target)) return fail("PROTECTED_APP", tr(S.vscreen_shell_is_agent_vscreen_itself_the, target))
        val r = run(arrayOf("am", "force-stop", target), 8_000L)
        return if (r.exitCode == 0) ok(tr(S.vscreen_shell_stopped_force_stop_ends_all, target))
        else fail("OPERATION_FAILED", r.combined)
    }

    /**
     * Before a screen is released: close every app task that is really on [displayId] (by task id,
     * so the app process and its main-screen pages stay). Force-stop is only a last resort for a task
     * that would not close, and never for a protected package or one also shown on the main screen.
     */
    fun closeAllOnDisplay(displayId: Int): Outcome {
        if (displayId == 0) return fail("MAIN_SCREEN", tr(S.vscreen_shell_tasks_on_the_main_screen))
        val tasks = VScreenHandoff.tasksOn(displayId)
        if (tasks.isEmpty()) return ok(tr(S.vscreen_shell_no_app_on_screen, displayId))
        val mainIds = VScreenHandoff.tasksOn(0).map { it.taskId }.toSet()
        val notes = ArrayList<String>()
        for (t in tasks) {
            // Belt and braces: a task the main screen lists is never closed from here.
            if (t.taskId in mainIds) { notes += tr(S.vscreen_shell_s_task_is_on_the, t.pkg); continue }
            if (VScreenRemoteDisplay.removeTask(t.taskId, displayId).getOrNull() == true) { notes += tr(S.vscreen_shell_closed, t.pkg); continue }
            if (isProtected(t.pkg)) { notes += tr(S.vscreen_shell_is_protected_not_stopped, t.pkg); continue }
            if (taskIdOn(0, t.pkg) != null) { notes += tr(S.vscreen_shell_also_has_pages_on_the, t.pkg); continue }
            val r = run(arrayOf("am", "force-stop", t.pkg), 8_000L)
            notes += if (r.exitCode == 0) tr(S.vscreen_shell_stopped, t.pkg) else tr(S.vscreen_shell_stopping_failed, t.pkg)
        }
        return ok(notes.joinToString(tr(S.agent_guide_sep)))
    }

    /** What the input method is attached to right now (best effort, from dumpsys input_method). */
    fun imeState(): org.json.JSONObject {
        val o = org.json.JSONObject()
        val text = run(arrayOf("dumpsys", "input_method"), 6_000L).stdout
        fun first(re: String) = Regex(re).find(text)?.groupValues?.get(1)
        first("(?:mCurTokenDisplayId|curTokenDisplayId|mImeDisplayId|imeDisplayId)=(-?\\d+)")?.toIntOrNull()?.let { o.put("ime_display", it) }
        first("(?:mCurClient|curClient)=[^\\n]*?displayId=(-?\\d+)")?.toIntOrNull()?.let { o.put("client_display", it) }
        first("(?:mInputShown|inputShown|mIsInputViewShown)=(true|false)")?.let { o.put("shown", it == "true") }
        first("(?:mCurFocusedWindow|curFocusedWindow)=Window\\{[0-9a-f]+ u\\d+ ([^ }]+)")?.let { o.put("focused_window", it) }
        return o
    }

    // ── input ──────────────────────────────────────────────────────────────

    /**
     * Frame pixel -> input pixel for a display (set by BackgroundOperation). Every coordinate the
     * agent or the preview gives is in the upright frame; this is the one place it is converted.
     */
    @Volatile internal var mapper: ((Int, Float, Float) -> Pair<Float, Float>)? = null
    private fun mx(d: Int, x: Int, y: Int): Pair<Int, Int> =
        mapper?.invoke(d, x.toFloat(), y.toFloat())?.let { Math.round(it.first) to Math.round(it.second) } ?: (x to y)

    /**
     * 1.2: the one way input reaches a display. The default goes to the Shizuku display service; tests replace it.
     * 1.4.2-beta6: every public input function above it asks [Control.admitInput] first, and the default carries the
     * control epoch ([Control.wireEpoch], read on the caller's thread) for the service's own fence ([InputFence]).
     * Each call answers true (injected), false (the system refused the event) or fails (service not connected).
     */
    interface Injector {
        fun gesture(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int): Result<Boolean>
        fun key(displayId: Int, code: Int): Result<Boolean>
        fun combo(displayId: Int, code: Int, mods: List<Int>): Result<Boolean> = Result.failure(UnsupportedOperationException("combo"))
        fun text(displayId: Int, text: String): Result<Boolean>
        fun pointer(displayId: Int, op: Int, pointerId: Int, x: Float, y: Float): Result<Pair<Boolean, Int>>
    }

    @Volatile internal var injector: Injector = object : Injector {
        override fun gesture(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int) =
            VScreenRemoteDisplay.injectDrag(displayId, x1, y1, x2, y2, durationMs, holdMs)
        override fun key(displayId: Int, code: Int) = VScreenRemoteDisplay.injectKey(displayId, code)
        override fun combo(displayId: Int, code: Int, mods: List<Int>) = VScreenRemoteDisplay.injectCombo(displayId, code, mods)
        override fun text(displayId: Int, text: String) = VScreenRemoteDisplay.injectText(displayId, text)
        override fun pointer(displayId: Int, op: Int, pointerId: Int, x: Float, y: Float) = VScreenRemoteDisplay.pointer(displayId, op, pointerId, x, y)
    }

    /** How long a tap's finger stays down. */
    private const val TAP_MS = 60

    private fun injected(what: String, displayId: Int, r: Result<Boolean>, spanMs: Long = 0L): Outcome = run {
        // 1.3.0: the motion our own input causes is not the page moving by itself (see MotionTrack.action). Called
        // when the input is done; [spanMs] is how long it lasted (a swipe, a press).
        if (r.getOrNull() == true) com.kilon.agentvscreen.host.quiet("motion-action") { VScreenPerception.noteAction(displayId, spanMs) }
        injectedOutcome(what, displayId, r)
    }

    private fun injectedOutcome(what: String, displayId: Int, r: Result<Boolean>): Outcome = when {
        // 1.4.2-beta6: an input the screen service cut short because the user took over or stopped meanwhile (it ended
        // with a cancel) says so (USER_STOPPED / USER_TAKEOVER / CONTROL_CHANGED), not "the system refused".
        r.getOrNull() == false && !Control.isUser() -> { Control.admitInput(); fail("INPUT_FAILED", tr(S.vscreen_shell_the_system_refused_injecting_on, displayId, what)) }
        r.getOrNull() == true -> ok(tr(S.vscreen_shell_screen_injected, displayId, what))
        r.isFailure -> {
            val srcName = Privileged.name()
            val fixHint = if (Privileged.via == Privileged.Via.ROOT) {
                tr(S.vscreen_shell_ask_the_user_to_restart)
            } else {
                tr(S.vscreen_shell_most_likely_stopped_ask_the, srcName)
            }
            fail("NOT_CONNECTED", tr(S.vscreen_shell_the_screen_service_is_unavailable, srcName, what, r.exceptionOrNull()?.message, fixHint))
        }
        else -> fail("INPUT_FAILED", tr(S.vscreen_shell_the_system_refused_injecting_on, displayId, what))
    }

    fun tap(displayId: Int, x: Int, y: Int, holdMs: Int = TAP_MS): Outcome {
        Control.admitInput()
        val (ix, iy) = mx(displayId, x, y)
        val hold = holdMs.coerceIn(30, 250)
        return injected("tap", displayId, injector.gesture(displayId, ix, iy, ix, iy, 0, hold), hold.toLong())
    }

    fun longPress(displayId: Int, x: Int, y: Int, ms: Int): Outcome {
        Control.admitInput()
        val (ix, iy) = mx(displayId, x, y)
        return injected("press", displayId, injector.gesture(displayId, ix, iy, ix, iy, 0, ms.coerceIn(300, 10_000)), ms.coerceIn(300, 10_000).toLong())
    }

    /** A swipe: the finger moves at an even speed and lifts while moving, so lists fling (hold = -1). */
    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Outcome {
        Control.admitInput()
        val (a, b) = mx(displayId, x1, y1); val (c, e) = mx(displayId, x2, y2)
        return injected("swipe", displayId, injector.gesture(displayId, a, b, c, e, ms.coerceIn(50, 5000), -1), ms.coerceIn(50, 5000).toLong())
    }

    /** A precise drag: eased, with a still hold at the end (no fling). */
    fun drag(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int): Outcome {
        Control.admitInput()
        val (a, b) = mx(displayId, x1, y1); val (c, e) = mx(displayId, x2, y2)
        return injected("drag", displayId, injector.gesture(displayId, a, b, c, e, durationMs.coerceIn(50, 5000), holdMs.coerceIn(0, 3000)), (durationMs.coerceIn(50, 5000) + holdMs.coerceIn(0, 3000)).toLong())
    }

    /** Held finger [pointerId] (multi-touch across calls): op "down" | "move" | "up" | "cancel". */
    fun finger(displayId: Int, op: String, pointerId: Int, x: Int, y: Int): Pair<Outcome, Int> {
        val code = when (op) { "down" -> 0; "move" -> 1; "up" -> 2; else -> 3 }
        // A cancel is always allowed: it only ever takes the caller's own fingers away (1.4.2-beta6).
        if (code != 3) Control.admitInput()
        val (ix, iy) = if (x < 0 || y < 0) (-1 to -1) else mx(displayId, x, y)
        val r = injector.pointer(displayId, code, pointerId, ix.toFloat(), iy.toFloat())
        val srcName = Privileged.name()
        val v = r.getOrNull() ?: return fail("NOT_CONNECTED", tr(S.vscreen_shell_the_screen_service_is_unavailable_2, srcName, r.exceptionOrNull()?.message)) to 0
        return (if (v.first) ok(tr(S.vscreen_shell_finger, pointerId, op, x, y)) else fail("INPUT_FAILED", tr(S.vscreen_shell_finger_injection_failed_this_finger, pointerId, op))) to v.second
    }

    data class KeyResult(val outcome: Outcome, val method: String)

    /**
     * The keycode of a key name ([KEYS]), a letter, a digit, f1–f12, or a number; null when it is none of them.
     * 1.4.0-beta13: letters and digits too, for shortcuts (`key ctrl+a`).
     */
    fun keyCode(key: String): Int? = key.trim().lowercase().let { k ->
        KEYS[k] ?: when {
            k.length == 1 && k[0] in 'a'..'z' -> 29 + (k[0] - 'a')
            Regex("f([1-9]|1[0-2])").matches(k) -> 130 + k.drop(1).toInt()
            else -> k.toIntOrNull()?.let { n -> if (k.length == 1) 7 + n else n.takeIf { c -> c in 1..400 && c !in FORBIDDEN_KEYS } }
        }
    }

    /** Modifier names a shortcut may use, and the key each one is. Meta / Win is left out: its shortcuts are the system's (home, recents) on the MAIN screen. */
    val MODIFIERS: Map<String, Int> = linkedMapOf("ctrl" to 113, "control" to 113, "shift" to 59, "alt" to 57)

    /** `ctrl+shift+z` → (key, modifiers); null when it is not a shortcut. Throws with the reason when it is one we refuse. */
    fun combo(key: String): Pair<Int, List<Int>>? {
        val parts = key.trim().lowercase().split('+').map { it.trim() }
        if (parts.size < 2 || parts.any { it.isEmpty() }) return null
        val bad = parts.dropLast(1).filter { it !in MODIFIERS }
        if (bad.isNotEmpty()) throw IllegalArgumentException(if (bad.any { it in setOf("meta", "win", "cmd", "super") })
            tr(S.vscreen_shell_shortcuts_are_not_supported_they, bad.first())
            else tr(S.vscreen_shell_unknown_modifier_available_ctrl_shift, bad.joinToString()))
        val code = keyCode(parts.last()) ?: throw IllegalArgumentException(tr(S.vscreen_shell_unknown_key_in_the_shortcut, parts.last()))
        if (parts.last() in setOf("tab", "esc", "escape") && "alt" in parts) throw IllegalArgumentException(tr(S.vscreen_shell_alt_is_not_supported_it, parts.last()))
        return code to parts.dropLast(1).map { MODIFIERS.getValue(it) }.distinct()
    }

    /** Whether [key] is a key or a shortcut we can press; the reason when not. */
    fun keyProblem(key: String): String? = runCatching { if (keyCode(key) != null || combo(key) != null) null else "" }.getOrElse { it.message ?: "" }

    /** Key event on [displayId], injected with the display id set. */
    fun keyWithMethod(displayId: Int, key: String): KeyResult {
        Control.admitInput()
        if (key.length > 1 && '+' in key) {
            val c = runCatching { combo(key) }.getOrElse { return KeyResult(fail("INVALID_ARGS", it.message ?: key), "none") }
            if (c != null) return KeyResult(injected(tr(S.vscreen_shell_shortcut, key), displayId, again { injector.combo(displayId, c.first, c.second) }), "inject")
        }
        val code = keyCode(key)
            ?: return KeyResult(fail("INVALID_ARGS", tr(S.vscreen_shell_unknown_key_available_or_a, key, KEYS.keys.joinToString(" "))), "none")
        return KeyResult(injected(tr(S.vscreen_shell_key, key), displayId, again { injector.key(displayId, code) }), "inject")
    }

    /**
     * 1.4.0-beta14 (test: `key ctrl+a` once came back "the system refused the injection" and worked when sent again):
     * a key the input system turns down once (it was busy dispatching the previous one) is sent once more.
     */
    private fun again(send: () -> Result<Boolean>): Result<Boolean> {
        val r = send()
        if (r.getOrNull() == true) return r
        Thread.sleep(150)
        return send()
    }

    fun key(displayId: Int, key: String): Outcome = keyWithMethod(displayId, key).outcome

    /** Characters the virtual keyboard can produce (ASCII), typed as key events. Other text goes through accessibility or the clipboard. */
    fun typeKeys(displayId: Int, text: String): Outcome {
        Control.admitInput()
        if (text.any { it.code > 126 || it.code < 32 }) return fail("NON_ASCII", tr(S.vscreen_shell_key_events_can_only_type))
        return injected(tr(S.vscreen_shell_typing), displayId, injector.text(displayId, text))
    }

    // ── IME policy (via app_process helper) ───────────────────────────────

    /**
     * Keep the soft keyboard from popping up for the background screen.
     * Untrusted virtual displays default to FALLBACK_DISPLAY, which would
     * open the keyboard on the user's MAIN screen whenever the background
     * app focuses a text field. We set DISPLAY_IME_POLICY_HIDE through a
     * tiny helper launched with app_process under the Shizuku shell uid
     * (the same technique scrcpy uses for its server), because the hidden
     * IWindowManager call is not reachable from the app process.
     */
    fun hideImeOn(context: Context, displayId: Int): Outcome {
        if (displayId <= android.view.Display.DEFAULT_DISPLAY) {
            return fail("IME_POLICY_FAILED", tr(S.vscreen_shell_refused_to_modify_main_display))
        }
        val direct = runCatching { VScreenRemoteDisplay.hideIme(displayId) }.getOrNull()
        if (direct?.getOrNull() == true) {
            return ok(tr(S.vscreen_shell_keyboard_policy_no_keyboard_on))
        }
        val apk = context.applicationInfo.sourceDir
        val r = com.kilon.agentvscreen.host.Privileged.runProcess(
            argv = arrayOf("/system/bin/app_process", "/system/bin", VScreenShellMain::class.java.name, "ime-hide", displayId.toString()),
            env = arrayOf("CLASSPATH=$apk"),
            cwd = null,
            timeoutMs = 10_000L,
        )
        return if (r.exitCode == 0 && r.stdout.contains("OK")) ok(tr(S.vscreen_shell_keyboard_policy_no_keyboard_on))
        else fail("IME_POLICY_FAILED", tr(S.vscreen_shell_setting_the_screen_s_keyboard, r.combined.take(300)))
    }

    /**
     * 1.2: the one key table (the step parser, the help text and the injector all read it).
     * HOME / APP_SWITCH are deliberately absent, by name and by number: on a display without system decorations they
     * can bring up the launcher or the task switcher on the MAIN screen.
     */
    val KEYS: Map<String, Int> = linkedMapOf(
        "back" to 4, "enter" to 66, "tab" to 61, "del" to 67, "delete" to 67, "backspace" to 67, "space" to 62,
        "esc" to 111, "escape" to 111, "search" to 84, "menu" to 82,
        "up" to 19, "down" to 20, "left" to 21, "right" to 22, "page_up" to 92, "page_down" to 93,
        "paste" to 279, "copy" to 278, "cut" to 277,
    )
    /** HOME 3, POWER 26, APP_SWITCH 187, SLEEP 223, WAKEUP 224, ASSIST 219, VOICE_ASSIST 231, ALL_APPS 284. */
    val FORBIDDEN_KEYS: Set<Int> = setOf(3, 26, 187, 223, 224, 219, 231, 284)
}
