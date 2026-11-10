package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

import android.content.Context
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
        { argv, t -> ShizukuManager.runProcess(argv, null, null, t) }

    fun run(argv: Array<String>, timeoutMs: Long = 8_000L) = runner(argv, timeoutMs)

    // ── launch ─────────────────────────────────────────────────────────────

    /** ROOT: an app already on the background screen goes back to its first page (default). KEEP: just bring it forward. RESTART: force-stop first. */
    enum class LaunchMode { ROOT, KEEP, RESTART }

    fun launch(displayId: Int, pkg: String, activity: String?, mode0: LaunchMode = LaunchMode.ROOT, intent: List<String>? = null): Outcome {
        val clean = pkg.trim()
        if (clean.isEmpty()) return fail("INVALID_ARGS", tr("缺少包名", "Missing package name"))
        // v22 传送门: with an explicit intent (deep link / shortcut / learned page) the app is
        // always opened in a fresh task on the background screen (never "keep").
        val mode = if (intent != null && mode0 == LaunchMode.KEEP) LaunchMode.ROOT else mode0
        val explicit = activity?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.contains('/')) it else "$clean/$it" }
        val component: String = explicit ?: (if (intent != null) "" else resolveLauncher(clean))
            ?: return fail("APP_NOT_FOUND", tr("找不到 $clean 的启动入口（未安装，或没有桌面图标）", "No launch entry for $clean (not installed, or no launcher icon)"))
        val targetPkg = if (component.isEmpty()) clean else component.substringBefore('/')
        val what = if (intent != null) intent.joinToString(" ") else component
        var prefix = ""
        if (mode == LaunchMode.RESTART) {
            val r = run(arrayOf("am", "force-stop", targetPkg), 8_000L)
            prefix = if (r.exitCode == 0) tr("已结束 $targetPkg 并冷启动；", "Stopped $targetPkg and cold-started it; ") else tr("结束 $targetPkg 失败（${r.combined.take(80)}），仍尝试启动；", "Stopping $targetPkg failed (${r.combined.take(80)}), starting anyway; ")
            Thread.sleep(300)
        } else {
            val taskId = taskIdOn(displayId, targetPkg)
            if (taskId != null) {
                if (mode == LaunchMode.KEEP) {
                    run(arrayOf("am", "start", "--display", displayId.toString(), "-n", component), 15_000L)
                    return ok(tr("$targetPkg 已在副屏 $displayId 上（保持当前页面）", "$targetPkg is already on screen $displayId (current page kept)"))
                }
                // Back to the first page: drop the background screen's own task
                // (the app keeps running, the user's copy on the main screen is
                // untouched), then start a fresh task below.
                val removed = VScreenRemoteDisplay.removeTask(taskId, displayId)
                prefix = if (removed.getOrNull() == true) { Thread.sleep(250); tr("已关闭副屏上的旧页面栈，", "Closed the old page stack on the screen, ") }
                else tr("无法关闭旧页面栈（", "Could not close the old page stack (") + (removed.exceptionOrNull()?.message ?: tr("系统拒绝", "refused by the system")) + tr("），新开一个任务，", "), opening a new task, ")
            }
        }
        // --windowingMode 1 = fullscreen on the target display. -W waits for the launch.
        // -f 0x18000000 = NEW_TASK | MULTIPLE_TASK: start a fresh task on the target
        // display instead of pulling the user's existing task (on the main screen)
        // to the front where it already lives.
        val target = intent ?: listOf("-n", component)
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
                return fail("PORTAL_DENIED", tr("系统不允许从外部直接打开这个页面（$what）：${out.take(200)}。它是应用内部页面，只能从应用里点进去", "The system does not allow opening this page from outside ($what): ${out.take(200)}. It is an internal page; reach it by tapping inside the app"))
            if (intent != null && (out.contains("unable to resolve") || out.contains("Unable to resolve")))
                return fail("PORTAL_UNRESOLVED", tr("$targetPkg 不接受这个入口（$what）：没有页面能处理它。换一个入口，或从首页点进去", "$targetPkg does not accept this entry ($what): no page handles it. Use another entry, or tap your way from its home page"))
            return fail("LAUNCH_FAILED", tr("在副屏 $displayId 启动 $what 失败：", "Starting $what on screen $displayId failed: ") + out.ifBlank { "exit=${r.exitCode}" })
        }
        // Verify where it actually landed: on an untrusted display the system
        // quietly redirects the launch to the main screen and still says OK.
        var top: String? = null
        for (i in 0 until 6) {
            Thread.sleep(300)
            top = topPackage(displayId)
            if (top == targetPkg) return ok(if (intent != null) tr("${prefix}已在副屏 $displayId 直接打开 $what", "${prefix}opened $what directly on screen $displayId") else tr("${prefix}已在副屏 $displayId 启动 $component（首页）", "${prefix}started $component (home page) on screen $displayId"))
        }
        val onMain = topPackage(0) == targetPkg
        return if (onMain) {
            val why = VScreenDisplay.lastRemoteError?.let { tr("（Shizuku 副屏服务不可用：$it，只能用 App 自建副屏，系统不允许 shell 往上启动应用）", " (Shizuku screen service unavailable: $it; only the app's own screen can be used, and the system does not let the shell start apps on it)") } ?: ""
            fail("LAUNCHED_ON_MAIN_SCREEN", tr("系统把 $targetPkg 开到了主屏，而不是副屏 $displayId$why。不要在主屏上继续操作，请告诉用户。", "The system opened $targetPkg on the main screen instead of screen $displayId$why. Do not go on operating the main screen; tell the user."))
        } else if (top == null) {
            ok(tr("已请求在副屏 $displayId 启动 $what（无法确认副屏顶部应用，请 read 核实）", "Asked to start $what on screen $displayId (cannot confirm the top app on the screen; check with read)"))
        } else {
            fail("LAUNCH_NOT_ON_TOP", tr("副屏 $displayId 顶部是 $top，不是 $targetPkg；请 read 查看副屏状态", "The top of screen $displayId is $top, not $targetPkg; check the screen with read"))
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
        fun step(label: String, argv: Array<String>): String {
            val r = run(argv, 8_000L)
            val msg = r.combined.trim().lines().firstOrNull { it.isNotBlank() }?.take(120)
            return "$label: " + if (r.exitCode == 0) (msg ?: "ok") else tr("失败", "failed") + " (${msg ?: "exit ${r.exitCode}"})"
        }
        return listOf(
            step("am compat disable DOWNSCALED", arrayOf("am", "compat", "disable", "DOWNSCALED", pkg)),
            step("cmd game set --downscale disable", arrayOf("cmd", "game", "set", "--downscale", "disable", pkg)),
        ).joinToString(tr("；", "; "))
    }

    /** Undo [disableDownscale]. */
    fun resetDownscale(pkg: String) {
        runCatching { run(arrayOf("am", "compat", "reset", "DOWNSCALED", pkg), 8_000L) }
        runCatching { run(arrayOf("cmd", "game", "reset", pkg), 8_000L) }
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
        val target = pkg?.trim()?.takeIf { it.isNotEmpty() } ?: topPackage(displayId) ?: return ok(tr("副屏 $displayId 上没有应用", "No app on screen $displayId"))
        val task = taskIdOn(displayId, target) ?: return null
        // v27: never a task the main screen lists (the v26 field test closed Minis' own main task this way).
        if (displayId == 0 || VScreenHandoff.tasksOn(0).any { it.taskId == task }) return fail("MAIN_SCREEN_TASK", tr("$target 的这个任务在主屏上，不在副屏，没有关闭", "This task of $target is on the main screen, not the background screen; not closed"))
        val r = VScreenRemoteDisplay.removeTask(task, displayId)
        return if (r.getOrNull() == true) ok(tr("已关闭副屏上的 $target（只移除副屏里的这个任务，应用进程和主屏上的页面不受影响）", "Closed $target on the screen (only this task on the screen was removed; the app process and its pages on the main screen are untouched)")) else null
    }

    /**
     * Packages the background screen must never force-stop: Minis itself (the agent runs in it),
     * the launcher and the system UI. Set from BackgroundOperation.init.
     */
    @Volatile var protectedPackages: Set<String> = setOf("com.android.systemui", "android")

    fun isProtected(pkg: String?): Boolean = pkg != null && pkg in protectedPackages

    /** Force-stop [pkg] (default: the app on top of [displayId]). Note: this stops the app everywhere. */
    fun stop(displayId: Int, pkg: String?): Outcome {
        val target = pkg?.trim()?.takeIf { it.isNotEmpty() } ?: topPackage(displayId)
            ?: return ok(tr("副屏 $displayId 上没有应用", "No app on screen $displayId"))
        if (isProtected(target)) return fail("PROTECTED_APP", tr("$target 是 Agent-VScreen 自己、桌面或系统界面，不能 force-stop（会中断任务或打断用户）", "$target is Agent-VScreen itself, the launcher or system UI and cannot be force-stopped (it would break the task or disturb the user)"))
        val r = run(arrayOf("am", "force-stop", target), 8_000L)
        return if (r.exitCode == 0) ok(tr("已结束 $target（force-stop 会结束该应用的所有页面，包括主屏上的）", "Stopped $target (force-stop ends all of the app's pages, including those on the main screen)"))
        else fail("OPERATION_FAILED", r.combined)
    }

    /**
     * Before a screen is released: close every app task that is really on [displayId] (by task id,
     * so the app process and its main-screen pages stay). Force-stop is only a last resort for a task
     * that would not close, and never for a protected package or one also shown on the main screen.
     */
    fun closeAllOnDisplay(displayId: Int): Outcome {
        if (displayId == 0) return fail("MAIN_SCREEN", tr("不会关闭主屏上的任务", "Tasks on the main screen are never closed"))
        val tasks = VScreenHandoff.tasksOn(displayId)
        if (tasks.isEmpty()) return ok(tr("副屏 $displayId 上没有应用", "No app on screen $displayId"))
        val mainIds = VScreenHandoff.tasksOn(0).map { it.taskId }.toSet()
        val notes = ArrayList<String>()
        for (t in tasks) {
            // Belt and braces: a task the main screen lists is never closed from here.
            if (t.taskId in mainIds) { notes += tr("${t.pkg} 的任务在主屏上，没有关闭", "${t.pkg}'s task is on the main screen; not closed"); continue }
            if (VScreenRemoteDisplay.removeTask(t.taskId, displayId).getOrNull() == true) { notes += tr("已关闭 ${t.pkg}", "Closed ${t.pkg}"); continue }
            if (isProtected(t.pkg)) { notes += tr("${t.pkg} 受保护，没有结束", "${t.pkg} is protected; not stopped"); continue }
            if (taskIdOn(0, t.pkg) != null) { notes += tr("${t.pkg} 在主屏也有页面，没有结束", "${t.pkg} also has pages on the main screen; not stopped"); continue }
            val r = run(arrayOf("am", "force-stop", t.pkg), 8_000L)
            notes += if (r.exitCode == 0) tr("已结束 ${t.pkg}", "Stopped ${t.pkg}") else tr("结束 ${t.pkg} 失败", "Stopping ${t.pkg} failed")
        }
        return ok(notes.joinToString(tr("；", "; ")))
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
    /** Where a frame point lands in input space (for diagnostics). */
    fun inputPoint(displayId: Int, x: Int, y: Int): Pair<Int, Int> = mx(displayId, x, y)

    /**
     * 1.2: the one way input reaches a display. The default goes to the Shizuku display service; tests replace it.
     * Each call answers true (injected), false (the system refused the event) or fails (service not connected).
     */
    interface Injector {
        fun gesture(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int): Result<Boolean>
        fun key(displayId: Int, code: Int): Result<Boolean>
        fun text(displayId: Int, text: String): Result<Boolean>
        fun pointer(displayId: Int, op: Int, pointerId: Int, x: Float, y: Float): Result<Pair<Boolean, Int>>
    }

    @Volatile internal var injector: Injector = object : Injector {
        override fun gesture(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int) =
            VScreenRemoteDisplay.injectDrag(displayId, x1, y1, x2, y2, durationMs, holdMs)
        override fun key(displayId: Int, code: Int) = VScreenRemoteDisplay.injectKey(displayId, code)
        override fun text(displayId: Int, text: String) = VScreenRemoteDisplay.injectText(displayId, text)
        override fun pointer(displayId: Int, op: Int, pointerId: Int, x: Float, y: Float) = VScreenRemoteDisplay.pointer(displayId, op, pointerId, x, y)
    }

    /** How long a tap's finger stays down. */
    private const val TAP_MS = 60

    private fun injected(what: String, displayId: Int, r: Result<Boolean>): Outcome = when {
        r.getOrNull() == true -> ok(tr("副屏 $displayId：$what 已注入", "screen $displayId: $what injected"))
        r.isFailure -> fail("NOT_CONNECTED", tr("副屏服务（Shizuku）不可用，$what 没有做：${r.exceptionOrNull()?.message}。status 看原因，fix not_connected 重连", "The screen service (Shizuku) is unavailable, $what was not done: ${r.exceptionOrNull()?.message}. See status for the cause; fix not_connected reconnects"))
        else -> fail("INPUT_FAILED", tr("系统拒绝了副屏 $displayId 上的 $what 注入", "The system refused injecting $what on screen $displayId"))
    }

    fun tap(displayId: Int, x: Int, y: Int): Outcome {
        val (ix, iy) = mx(displayId, x, y)
        return injected("tap", displayId, injector.gesture(displayId, ix, iy, ix, iy, 0, TAP_MS))
    }

    fun longPress(displayId: Int, x: Int, y: Int, ms: Int): Outcome {
        val (ix, iy) = mx(displayId, x, y)
        return injected("press", displayId, injector.gesture(displayId, ix, iy, ix, iy, 0, ms.coerceIn(300, 10_000)))
    }

    /** A swipe: the finger moves at an even speed and lifts while moving, so lists fling (hold = -1). */
    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Outcome {
        val (a, b) = mx(displayId, x1, y1); val (c, e) = mx(displayId, x2, y2)
        return injected("swipe", displayId, injector.gesture(displayId, a, b, c, e, ms.coerceIn(50, 5000), -1))
    }

    /** A precise drag: eased, with a still hold at the end (no fling). */
    fun drag(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int): Outcome {
        val (a, b) = mx(displayId, x1, y1); val (c, e) = mx(displayId, x2, y2)
        return injected("drag", displayId, injector.gesture(displayId, a, b, c, e, durationMs.coerceIn(50, 5000), holdMs.coerceIn(0, 3000)))
    }

    /** Held finger [pointerId] (multi-touch across calls): op "down" | "move" | "up" | "cancel". */
    fun finger(displayId: Int, op: String, pointerId: Int, x: Int, y: Int): Pair<Outcome, Int> {
        val code = when (op) { "down" -> 0; "move" -> 1; "up" -> 2; else -> 3 }
        val (ix, iy) = if (x < 0 || y < 0) (-1 to -1) else mx(displayId, x, y)
        val r = injector.pointer(displayId, code, pointerId, ix.toFloat(), iy.toFloat())
        val v = r.getOrNull() ?: return fail("NOT_CONNECTED", tr("副屏服务（Shizuku）不可用：${r.exceptionOrNull()?.message}", "The screen service (Shizuku) is unavailable: ${r.exceptionOrNull()?.message}")) to 0
        return (if (v.first) ok(tr("手指 $pointerId $op ($x,$y)", "finger $pointerId $op ($x,$y)")) else fail("INPUT_FAILED", tr("手指 $pointerId $op 注入失败（这根手指可能没按下）", "finger $pointerId $op injection failed (this finger may not be down)"))) to v.second
    }

    data class KeyResult(val outcome: Outcome, val method: String)

    /** The keycode of a key name ([KEYS]) or of a number; null when it is neither. */
    fun keyCode(key: String): Int? = key.trim().lowercase().let { KEYS[it] ?: it.toIntOrNull()?.takeIf { c -> c in 1..400 && c !in FORBIDDEN_KEYS } }

    /** Key event on [displayId], injected with the display id set. */
    fun keyWithMethod(displayId: Int, key: String): KeyResult {
        val code = keyCode(key)
            ?: return KeyResult(fail("INVALID_ARGS", tr("未知按键 $key（可用：${KEYS.keys.joinToString(" ")} 或数字 keycode；没有 home / 多任务键）", "Unknown key $key (available: ${KEYS.keys.joinToString(" ")} or a numeric keycode; there is no home / recents key)")), "none")
        return KeyResult(injected(tr("按键 $key", "key $key"), displayId, injector.key(displayId, code)), "inject")
    }

    fun key(displayId: Int, key: String): Outcome = keyWithMethod(displayId, key).outcome

    /** Characters the virtual keyboard can produce (ASCII), typed as key events. Other text goes through accessibility or the clipboard. */
    fun typeKeys(displayId: Int, text: String): Outcome {
        if (text.any { it.code > 126 || it.code < 32 }) return fail("NON_ASCII", tr("按键方式只能输入 ASCII 字符", "Key events can only type ASCII characters"))
        return injected(tr("输入", "typing"), displayId, injector.text(displayId, text))
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
        val apk = context.applicationInfo.sourceDir
        val r = run(arrayOf(
            "sh", "-c",
            "CLASSPATH='$apk' app_process /system/bin ${VScreenShellMain::class.java.name} ime-hide $displayId",
        ), 10_000L)
        return if (r.exitCode == 0 && r.stdout.contains("OK")) ok(tr("键盘策略：副屏不弹出键盘", "Keyboard policy: no keyboard on the screen"))
        else fail("IME_POLICY_FAILED", tr("设置副屏键盘策略失败：${r.combined.take(300)}", "Setting the screen's keyboard policy failed: ${r.combined.take(300)}"))
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
