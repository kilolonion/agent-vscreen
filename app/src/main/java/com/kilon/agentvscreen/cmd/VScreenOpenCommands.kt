package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.tr
import com.kilon.agentvscreen.I18n

import android.os.SystemClock
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenDownscale
import com.kilon.agentvscreen.core.VScreenHandoff
import com.kilon.agentvscreen.core.VScreenKnowledge
import com.kilon.agentvscreen.core.VScreenMap
import com.kilon.agentvscreen.core.VScreenPerception
import com.kilon.agentvscreen.core.VScreenRemoteDisplay
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.Vendor
import kotlin.math.roundToInt
import com.kilon.agentvscreen.cmd.VScreenOffloadHandler.Companion.TAG

// Open an app on the background screen (launch), move apps between the screens (handoff), close
// (stop) and give the screen back (release). launch fits the screen to the app (orientation, vendor downscaling)
// and reports what it did as facts; it does not judge whether the page is "ready" — the four blocks show it.

/** What a launch did ([done]) and what the caller must know ([notes]). */
internal class LaunchFacts(val done: MutableList<String>, val notes: Notes)

/** Display id -> package whose real render size the screen's logical size was set to (wmsize fit). */
internal val wmFitted = HashMap<Int, String>()

/** Rotation re-read; a failure is a fact for 【注意】. */
internal fun VScreenOffloadHandler.syncRotation(d: VScreenDisplay): String? =
    runCatching { BackgroundOperation.syncRotation(d) }.exceptionOrNull()?.let {
        AppLogger.warning(TAG, "rotation sync on ${d.displayId}: ${it.message}")
        tr("没读到副屏当前方向（${it.javaClass.simpleName}），坐标按上次的方向算", "Could not read the screen's current orientation (${it.javaClass.simpleName}); coordinates use the last one")
    }

/**
 * Open [pkg]. The screen is built in the orientation the app declares (else the way it ran last time, else the
 * device's natural way); if the app turns it at runtime, or the vendor downscales it, the screen is rebuilt / the
 * scaling undone / the logical size fitted, and the app started again — each said in [LaunchFacts.done].
 */
internal fun VScreenOffloadHandler.doLaunch(sid: String, pkg: String, orient: Boolean?, restart: Boolean, depth: Int = 0,
                                            keep: Boolean = false,
                                            facts: LaunchFacts = LaunchFacts(ArrayList(), Notes())): LaunchFacts {
    // `launch --keep` — and any launch while an app sits parked on the parking display — leaves the screen exactly as
    // it is: no rebuild for the app's declared orientation, no un-scaling, no re-fit. The parked app keeps the geometry
    // its page was laid out in, and the page is not thrown away. The app is then opened normally (mode ROOT, its first
    // page) unless --keep asks for its existing page back (mode KEEP).
    val hold = keep || BackgroundOperation.hasParkedApps(sid)
    val declared = VScreenShell.appOrientation(context, pkg, null)
    val ranAs = VScreenMap.profile(pkg)["orientation"]?.let { it == "landscape" }
    val want = orient ?: declared ?: ranAs ?: VScreenShell.naturalLandscape(context)
    VScreenKnowledge.startJourney(sid, pkg)
    VScreenKnowledge.touch(sid, pkg)
    val d0 = BackgroundOperation.acquire(sid, create = true, landscape = want)
    if (!hold && BackgroundOperation.nativeLandscape(d0) != want) {
        BackgroundOperation.rebuild(sid, want)
        facts.done += if (I18n.en) "Screen rebuilt in ${if (want) "landscape" else "portrait"} (${when { orient != null -> "as you asked"; declared != null -> "as the app declares"; ranAs != null -> "as it last actually ran"; else -> "the device's natural orientation" }})"
            else "副屏按${if (want) "横屏" else "竖屏"}重建（${when { orient != null -> "你指定的"; declared != null -> "应用声明的"; ranAs != null -> "它上次实际运行的"; else -> "设备自然方向" }}）"
    }
    val mode = if (restart || depth > 0) VScreenShell.LaunchMode.RESTART
        else if (keep) VScreenShell.LaunchMode.KEEP else VScreenShell.LaunchMode.ROOT
    var needRefit: Boolean? = null
    var needUnscale: Double? = null
    var landed: String? = null
    BackgroundOperation.agentAction(sid, null, isRead = false, describe = tr("启动 $pkg", "start $pkg"), freshPage = true) { d ->
        if (!hold) synchronized(wmFitted) { wmFitted[d.displayId]?.takeIf { it != pkg }?.let { wmFitted.remove(d.displayId) } }?.let {
            VScreenShell.run(arrayOf("wm", "size", "reset", "-d", d.displayId.toString())); syncRotation(d)
            facts.done += tr("副屏逻辑尺寸还原（上一个应用 $it 改过）", "screen logical size restored (changed by the previous app $it)")
        }
        val r = VScreenShell.launch(d.displayId, pkg, null, mode)
        if (!r.ok) throw ActionError(r.code, r.message)
        facts.done += when (mode) {
            VScreenShell.LaunchMode.RESTART -> tr("冷启动 $pkg", "cold-start $pkg")
            VScreenShell.LaunchMode.KEEP -> tr("打开 $pkg（副屏不动：方向和尺寸保持原样）", "open $pkg (the screen is left as it is: same orientation and size)")
            else -> tr("打开 $pkg", "open $pkg")
        }
        VScreenPerception.ensure(d)
        // Wait for a still, non-blank picture (at most 10 s): facts, not a readiness verdict.
        val t0 = SystemClock.elapsedRealtime()
        val p = Probe(this, d, listOf(Cond("stable", CondKind.STABLE)))
        var w: WaitOut
        try {
            while (true) {
                w = waitOn(d, p, 10_000L - (SystemClock.elapsedRealtime() - t0), null)
                if (w.met == null || VScreenPerception.blankFor(d.displayId) == null || SystemClock.elapsedRealtime() - t0 >= 10_000L) break
                Thread.sleep(300)
            }
        } finally { p.close() }
        val ms = SystemClock.elapsedRealtime() - t0
        facts.done += if (w.met != null) tr("画面在 ${(ms / 100) / 10.0}s 时稳定", "screen settled at ${(ms / 100) / 10.0}s") else tr("等了 ${(ms / 100) / 10.0}s，画面还在变（加载、动画或视频）", "waited ${(ms / 100) / 10.0}s, still changing (loading, animation or video)")
        syncRotation(d)?.let { facts.notes += it }
        val act = VScreenShell.topActivity(d.displayId)
        landed = act?.substringBefore('/')
        if (act != null) synchronized(VScreenOffloadHandler.lastActivity) { VScreenOffloadHandler.lastActivity[d.displayId] = act }
        if (act != null && VScreenShell.isResolver(act)) throw ActionError("LAUNCH_RESOLVER",
            tr("启动 $pkg 后副屏停在系统的“使用以下方式打开”选择器（$act）：这个包没有桌面入口，apps 关键词 找带图标的包名", "After starting $pkg the screen is on the system's \"Open with\" chooser ($act): this package has no launcher entry; find the package with an icon via apps <keyword>"))
        if (landed != null && landed != pkg) facts.notes += tr("前台是 $landed，不是 $pkg（被跳转到别的应用或系统页）", "The front app is $landed, not $pkg (redirected to another app or a system page)")
        if (landed == pkg) {
            if (declared == null && orient == null) VScreenMap.setProfile(pkg, mapOf("orientation" to
                if (BackgroundOperation.nativeLandscape(d) != (d.rotation % 2 == 1)) "landscape" else "portrait"))
            if (!hold && depth == 0 && d.rotation % 2 == 1) needRefit = !BackgroundOperation.nativeLandscape(d)
            val gs = VScreenShell.windowScale(d.displayId, pkg)
            val crop = VScreenDownscale.surfaceCrop(pkg)
            val late = restart && VScreenDownscale.consumeLateScaled(pkg)
            if (!hold && depth == 0 && needRefit == null && ((gs != null && gs > 1.02) || crop?.cropped == true || late)) needUnscale = gs ?: 1.0
            if (depth > 0 && ((gs != null && gs > 1.02) || crop?.cropped == true))
                facts.notes += run { val x = gs?.let { "%.2f".format(it) } ?: "?"; tr("处理后 $pkg 仍被系统缩放（$x 倍），画面右侧/下方可能被裁。用户可手动：", "After the fix $pkg is still scaled by the system (×$x); the right/bottom of the picture may be cut off. The user can do it by hand: ") } + Vendor.current.downscaleHint()
        }
    }
    needRefit?.let { land ->
        BackgroundOperation.rebuild(sid, land)
        facts.done += if (I18n.en) "$pkg turned the picture to ${if (land) "landscape" else "portrait"} after starting: the screen was rebuilt in ${if (land) "landscape" else "portrait"} and the app reopened"
            else "$pkg 启动后把画面转成${if (land) "横屏" else "竖屏"}：副屏按${if (land) "横屏" else "竖屏"}重建后重开"
        return doLaunch(sid, pkg, land, true, depth + 1, keep, facts)
    }
    needUnscale?.let { gs ->
        val fix = BackgroundOperation.disableDownscale(pkg)
        if (fix.changed) {
            facts.done += run { val x = "%.2f".format(gs); tr("系统把 $pkg 缩小 $x 倍渲染（画面会被裁）：${fix.text}；冷启动一次（副屏释放时恢复原设置）", "The system renders $pkg scaled down ×$x (the picture gets cut off): ${fix.text}; cold-started once (the original setting returns when the screen is released)") }
            return doLaunch(sid, pkg, orient, true, depth + 1, keep, facts)
        }
        wmFit(sid, pkg)?.let { note -> facts.done += note; return doLaunch(sid, pkg, orient, true, depth + 1, keep, facts) }
        facts.notes += run { val x = "%.2f".format(gs); tr("$pkg 被系统缩放（$x 倍），画面右侧/下方会被裁，这里改不了（${fix.text}）。用户可手动：", "$pkg is scaled by the system (×$x); the right/bottom of the picture gets cut off and this can't be changed here (${fix.text}). The user can do it by hand: ") } + Vendor.current.downscaleHint()
    }
    if (landed == pkg && depth == 0) runCatching { VScreenDownscale.watchAfterLaunch(BackgroundOperation.acquire(sid, create = false), pkg) }
    return facts
}

/**
 * Vendor scaling that cannot be turned off (Honor APS): set the screen's logical size to what the app really renders,
 * so the system scales the whole picture onto the screen instead of cutting it. Null when it does not apply.
 */
private fun VScreenOffloadHandler.wmFit(sid: String, pkg: String): String? {
    val aps = runCatching { VScreenDownscale.apsRead(pkg) }.getOrNull() ?: return null
    val r = aps.resolution
    if (!aps.available || r < 0.3f || r > 0.995f) return null
    val d = BackgroundOperation.acquire(sid, create = false)
    if (synchronized(wmFitted) { wmFitted[d.displayId] } == pkg) return null
    val id = d.displayId.toString()
    val m = Regex("Physical size: (\\d+)x(\\d+)").find(VScreenShell.run(arrayOf("wm", "size", "-d", id)).combined) ?: return null
    val pw = m.groupValues[1].toInt(); val ph = m.groupValues[2].toInt()
    val w = ((pw * r).roundToInt() / 2) * 2; val h = ((ph * r).roundToInt() / 2) * 2
    if (VScreenShell.run(arrayOf("wm", "size", "${w}x$h", "-d", id)).exitCode != 0) return null
    synchronized(wmFitted) { wmFitted[d.displayId] = pkg }
    syncRotation(d)
    val x = "%.3f".format(r)
    return tr("系统把 $pkg 降到 $x 倍渲染（改不了）：副屏逻辑尺寸设成它的实际渲染尺寸 ${w}x$h（物理 ${pw}x$ph），冷启动一次", "The system renders $pkg at ×$x (can't be changed): the screen's logical size was set to its real render size ${w}x$h (physical ${pw}x$ph), cold-started once")
}

// ── handoff ──────────────────────────────────────────────────────────

internal fun VScreenOffloadHandler.isOwnPackage(pkg: String?): Boolean =
    !pkg.isNullOrBlank() && pkg == context.packageName

/**
 * 2.0.0-beta6 (6.1 L3 / 6.10 #3): single App-side gate refusing to put our own package onto a background screen
 * (called by `launch`, `handoff in`, `handoff park`, and `handoff unpark`).
 */
internal fun VScreenOffloadHandler.requireNotOwnPackage(pkg: String?) {
    if (isOwnPackage(pkg)) {
        throw ActionError(
            "PROTECTED_APP",
            tr(
                "Agent-VScreen 自己的页面只能在手机主屏上运行，不能在副屏打开、移屏或停放。要看它的状态用 vscreen status；要用户在 App 里做什么请直接告诉用户",
                "Agent-VScreen's own pages run only on the phone's main screen and cannot be opened, moved, or parked on the background screen. Use vscreen status for its state, or ask the user directly",
            ),
        )
    }
}

internal fun VScreenOffloadHandler.handoffSkip(): Set<String> =
    setOf(context.packageName, "com.android.systemui", "moe.shizuku.privileged.api") + runCatching { VScreenHandoff.homePackages() }.getOrDefault(emptySet())

/**
 * `handoff park <应用>`: the app moves to the hidden parking display, its page untouched, and is out of the agent's
 * hands until `handoff unpark <应用>`, which brings it back to the background screen. The app is always named: a wrong
 * guess would park the wrong page. Neither direction touches the main screen: nothing is moved to it and nothing is
 * read from it, and the app-rule check ([Guard]) is made on that one package.
 */
private fun VScreenOffloadHandler.handoffPark(sid: String, pkgArg: String?, park: Boolean): NativeOffloadResult {
    val what = if (park) tr("停下", "park") else tr("取回", "unpark")
    val use = if (park) "handoff park <应用>" else "handoff unpark <应用>"
    val pkg = pkgArg?.takeIf { it.isNotBlank() }
        ?: throw ActionError("INVALID_ARGS", tr("$use：要写明是哪个应用（停放和取回都按名字，不猜）", "$use: name the app (park and unpark both go by name, never a guess)"))
    requireNotOwnPackage(pkg)
    if (pkg in handoffSkip()) throw ActionError("HANDOFF_DENIED", tr("$pkg 是桌面或系统界面，不能停放", "$pkg is the launcher or system UI and cannot be parked"))
    // Only this session's own screen: parking is not a way to take a screen another session is using (BUSY says whose).
    BackgroundOperation.previewDisplayFor(sid) ?: run {
        val owner = BackgroundOperation.state.value.ownerSessionId
        throw if (owner != null && owner != sid)
            ActionError("BUSY", tr("副屏现在归 $owner，等它让出再做（status 里有 owner 和多久后让出）", "The screen belongs to $owner now; wait until it frees up (status has owner and frees_in_s)"))
        else ActionError("NO_SCREEN", tr("现在没有副屏：先 launch 一个应用", "There is no screen now: launch an app first"))
    }
    com.kilon.agentvscreen.core.Guard.require(if (park) com.kilon.agentvscreen.core.Guard.Act.OPEN else com.kilon.agentvscreen.core.Guard.Act.TAKE, pkg, sid)
    val taskId = BackgroundOperation.agentAction(sid, null, isRead = false, describe = tr("$what $pkg", "$what $pkg"), allowParked = true) { d ->
        if (park) BackgroundOperation.parkApp(sid, d, pkg) else BackgroundOperation.unparkApp(sid, d, pkg)
    }
    if (park) {
        if (VScreenKnowledge.journey(sid) == pkg) VScreenKnowledge.endJourney(sid)
    } else {
        // The session works on this app again: its segments / before-after steps and the "with the user" check follow
        // the app it is actually working on, not the one from before the park.
        VScreenKnowledge.startJourney(sid, pkg)
        VScreenKnowledge.touch(sid, pkg)
        com.kilon.agentvscreen.core.VScreenOwnership.agentLaunched(sid, pkg)
    }
    val msg = if (park)
        tr("$pkg 已停到停放屏（隐藏，不在副屏上；任务 $taskId）：任务没有重启。若它仍是当前目标，look/act 返回 APP_PARKED；可以 launch 别的应用。要接着做：vscreen handoff unpark $pkg",
            "$pkg is parked on the parking display (hidden, not on the screen; task $taskId): the task was not restarted. Look and act return APP_PARKED if it is still the current target; you can launch another app. To carry on: vscreen handoff unpark $pkg")
    else
        tr("$pkg 已回到副屏（任务 $taskId），停在停下时的那一页：先看清现在的画面再继续",
            "$pkg is back on the screen (task $taskId) on the page it was parked at: look at it before going on")
    return NativeOffloadResult(0, msg + "\n", "OK",
        org.json.JSONObject().put("package", pkg).put("task_id", taskId).put("parked", park))
}

/**
 * `tasks`: the app tasks on the background screen right now (front first) — package, task id and whether it is in
 * front, straight from the system's own per-display list. A read: it neither takes a screen another session is using
 * nor creates one, and "cannot read" is said instead of guessed.
 */
internal fun VScreenOffloadHandler.tasksCmd(sid: String?): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val d = BackgroundOperation.previewDisplayFor(s) ?: run {
        val owner = BackgroundOperation.state.value.ownerSessionId
        throw if (owner != null && owner != s)
            ActionError("BUSY", tr("副屏现在归 $owner，这之前看不到它的任务列表（status 里有 owner 和多久后让出）", "The screen belongs to $owner now, so its task list says nothing about your work (status has owner and frees_in_s)"))
        else ActionError("NO_SCREEN", tr("现在没有副屏：先 launch 一个应用", "There is no screen now: launch an app first"))
    }
    val list = VScreenHandoff.tasksOrNull(d.displayId)
        ?: throw ActionError("NOT_SUPPORTED", tr("读不到系统给出的副屏任务列表（不猜）：status 看副屏状态", "The system's task list for the screen could not be read (no guessing): status shows the screen"))
    val rows = list.joinToString("\n") { t ->
        (if (t.foreground) "* " else "  ") + t.pkg + "  " + tr("任务 ${t.taskId}", "task ${t.taskId}") + (if (t.foreground) tr("  前台", "  in front") else "")
    }
    val head = if (list.isEmpty()) tr("副屏上没有应用任务（桌面不算）", "No app tasks on the screen (the launcher does not count)")
        else tr("副屏上的应用任务（最前面的排第一，* 是前台）：", "App tasks on the screen (front first, * = in front):")
    val data = org.json.JSONObject().put("display_id", d.displayId).put("tasks", org.json.JSONArray(list.map {
        org.json.JSONObject().put("package", it.pkg).put("task_id", it.taskId).put("foreground", it.foreground)
    }))
    return NativeOffloadResult(0, head + (if (list.isEmpty()) "" else "\n" + rows) + "\n", "OK", data)
}

/** `handoff in [包名]` (take the user's app, return the four blocks) | `handoff out [包名] [--note 说明]` (give it back). */
internal fun VScreenOffloadHandler.handoffCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    return when (val op = args.positional.getOrNull(0)) {
        // 1.4.0-beta8 (test: `handoff out 时钟` said no task, the package name worked; launch takes names): an app is
        // named the same way in every command, by its name or its package.
        "in" -> handoffIn(s, args.positional.getOrNull(1)?.let { VScreenKnowledge.resolveApp(it) ?: it })
        "out" -> handoffOut(s, args.positional.getOrNull(1)?.let { VScreenKnowledge.resolveApp(it) ?: it }, args.get("note"))
        // park / unpark work on the app parked on the parking display: the guard is checked on that package alone and
        // the main screen is neither moved nor read.
        "park" -> handoffPark(s, args.positional.getOrNull(1)?.let { VScreenKnowledge.resolveApp(it) ?: it }, park = true)
        "unpark" -> handoffPark(s, args.positional.getOrNull(1)?.let { VScreenKnowledge.resolveApp(it) ?: it }, park = false)
        else -> {
            val skip = handoffSkip()
            val main = VScreenHandoff.tasksOn(0)
            text((if (op != null) tr("handoff in [包名] | handoff out [包名] [--note 说明] | handoff park <应用> | handoff unpark <应用>（不认识 $op）\n\n", "handoff in [package] | handoff out [package] [--note text] | handoff park <app> | handoff unpark <app> (unknown $op)\n\n") else "") +
                tr("主屏上的任务：", "tasks on the main screen: ") + (main.joinToString(tr("；", "; ")) { t -> t.pkg + (if (t.visible) tr("（可见）", " (visible)") else "") + if (t.pkg in skip) tr("（不能接手）", " (cannot be taken over)") else "" }.ifEmpty { tr("无", "none") }))
        }
    }
}

private fun VScreenOffloadHandler.handoffIn(sid: String, pkgArg: String?): NativeOffloadResult {
    val start = System.currentTimeMillis(); val t0 = SystemClock.elapsedRealtime()
    requireNotOwnPackage(pkgArg)
    if (pkgArg != null && pkgArg in handoffSkip()) {
        throw ActionError("PROTECTED_APP", tr("$pkgArg 是桌面或系统界面，不能接到副屏", "$pkgArg is the launcher or system UI and cannot be moved to the background screen"))
    }
    val main = VScreenHandoff.tasksOn(0)
    val pick = if (pkgArg != null) main.firstOrNull { it.pkg == pkgArg }
        ?: throw ActionError("HANDOFF_NO_TASK", tr("主屏上没有 $pkgArg 的任务。可以 launch $pkgArg 在副屏另开一份（不带用户的页面）", "No task of $pkgArg on the main screen. launch $pkgArg opens a separate copy on the screen (without the user's page)"))
    else main.firstOrNull { it.pkg !in handoffSkip() }
        ?: throw ActionError("HANDOFF_NO_TASK", tr("主屏最近没有可接手的应用（只有桌面和 Agent-VScreen 自己）：请用户先打开那个应用，或说是哪个应用", "No app on the main screen to take over (only the launcher and Agent-VScreen): ask the user to open that app first, or to say which one"))
    requireNotOwnPackage(pick.pkg)
    BackgroundOperation.requireNotParked(sid, pick.pkg)
    com.kilon.agentvscreen.core.Guard.require(com.kilon.agentvscreen.core.Guard.Act.TAKE, pick.pkg)
    // 1.2: taking the app the user is using off their screen is theirs to allow, on the phone.
    run { val app = VScreenKnowledge.label(pick.pkg) ?: pick.pkg
        com.kilon.agentvscreen.host.Consent.require(context, "handoff:${pick.pkg}", tr("把你正在用的 $app 接到后台副屏", "move $app, which you are using, to the background screen"), tr("它会从主屏消失，停在当前页面，由 Agent 在后台继续操作", "it leaves the main screen, stays on its current page, and the agent carries on in the background"), kind = "system") }
    val want = VScreenShell.appOrientation(context, pick.pkg, null) ?: VScreenMap.profile(pick.pkg)["orientation"]?.let { it == "landscape" }
    // 1.4.2-beta6: building / rebuilding the screen and removing a task come before the action itself: not while the
    // user holds the screen or has stopped the AI.
    com.kilon.agentvscreen.core.Control.admitInput(checkPlan = false)
    var d0 = BackgroundOperation.acquire(sid, create = true, landscape = want)
    if (!BackgroundOperation.hasParkedApps(sid) && want != null && BackgroundOperation.nativeLandscape(d0) != want) d0 = BackgroundOperation.rebuild(sid, want)
    VScreenShell.taskIdOn(d0.displayId, pick.pkg)?.takeIf { it != pick.taskId }?.let { com.kilon.agentvscreen.core.Control.admitInput(checkPlan = false); VScreenRemoteDisplay.removeTask(it, d0.displayId) }
    val done = ArrayList<String>()
    BackgroundOperation.agentAction(sid, null, isRead = false, describe = tr("接力：把主屏的 ${pick.pkg} 移到副屏", "Relay: move ${pick.pkg} from the main screen to the background screen"), allowParked = true) { d ->
        val m = VScreenHandoff.move(pick.taskId, pick.pkg, d.displayId)
        if (!m.ok) throw ActionError("HANDOFF_DENIED", tr("系统没有让 ${pick.pkg} 移到副屏（${m.detail}），用户的应用仍在主屏没被改动。", "The system did not move ${pick.pkg} to the screen (${m.detail}); the user's app is still on the main screen, untouched. ") +
            tr("替代：launch ${pick.pkg} 在副屏另开一份，或请用户自己操作", "Instead: launch ${pick.pkg} opens a separate copy on the screen, or let the user do it"))
        syncRotation(d)
        done += tr("把用户主屏上的 ${pick.pkg}（任务 ${pick.taskId}）移到副屏（${m.via}），停在用户刚才的页面", "Moved ${pick.pkg} (task ${pick.taskId}) from the user's main screen to the background screen (${m.via}), on the page the user was on")
        done += run { val top = VScreenShell.topPackage(0) ?: "?"; tr("主屏现在是 $top", "The main screen now shows $top") }
    }
    BackgroundOperation.appSelected(sid, pick.pkg)
    VScreenKnowledge.startJourney(sid, pick.pkg)
    val d = BackgroundOperation.acquire(sid, create = false)
    VScreenPerception.ensure(d)
    val attention = Notes()
    attention += tr("这是用户正在用的页面（登录、填了一半的内容都在）：不要 launch 重开；需要用户本人的一步（付款、密码、验证码）用 handoff out 交还", "This is the page the user was using (logins and half-filled content included): don't launch it again; for a step only the user can do (payment, password, code), hand it back with handoff out")
    takeAdjusted().forEach { attention += tr("参数被调整：$it", "options adjusted: $it") }
    return fourBlocks(sid, d, done, attention, start, SystemClock.elapsedRealtime() - t0, "handoff", fullList = true)
}

private fun VScreenOffloadHandler.handoffOut(sid: String, pkgArg: String?, msg: String?): NativeOffloadResult {
    val d0 = BackgroundOperation.acquire(sid, create = false)
    val pkg = pkgArg ?: VScreenShell.topPackage(d0.displayId) ?: throw ActionError("NO_APP", tr("副屏上没有应用可以交还", "No app on the screen to hand back"))
    BackgroundOperation.requireNotParked(sid, pkg)
    val task = VScreenShell.taskIdOn(d0.displayId, pkg) ?: throw ActionError("HANDOFF_NO_TASK", tr("副屏上找不到 $pkg 的任务", "No task of $pkg on the screen"))
    return BackgroundOperation.agentAction(sid, null, isRead = false, describe = tr("交还：把副屏的 $pkg 移到主屏", "Hand back: move $pkg from the background screen to the main screen"), allowParked = true) { _ ->
        val m = VScreenHandoff.move(task, pkg, 0)
        if (m.ok) com.kilon.agentvscreen.core.VScreenMute.restore(pkg)
        if (m.ok) com.kilon.agentvscreen.core.VScreenOwnership.handedOut(context, sid, pkg, task,
            synchronized(VScreenOffloadHandler.lastActivity) { VScreenOffloadHandler.lastActivity[d0.displayId] })
        if (!m.ok) throw ActionError("HANDOFF_DENIED", tr("系统没有让 $pkg 移回主屏（${m.detail}）。替代：请用户点副屏悬浮窗 › 全屏 › 接管，自己完成这一步", "The system did not move $pkg back to the main screen (${m.detail}). Instead: ask the user to tap the floating window › Full screen › Take over and do the step themselves"))
        val label = runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }.getOrDefault(pkg)
        val who = com.kilon.agentvscreen.api.Agents.nameOfSession(sid)
        val notified = VScreenHandoff.alert(context, com.kilon.agentvscreen.core.VScreenOwnership.nudgeId(pkg), tr("$label 已交给你", "$label is yours now"),
            (msg ?: tr("「$who」把 $label 停在当前页面交给你，请在主屏继续。", "\"$who\" left $label on the current page for you; carry on on the main screen.")) +
                tr("\n用完点“交回”，它会从你离开的页面接着做。", "\nWhen done, tap Hand back and it carries on from where you left it."),
            com.kilon.agentvscreen.host.Needs.open(context, com.kilon.agentvscreen.host.Needs.Page.HOME, 1),
            listOf(tr("交回", "Hand back") to com.kilon.agentvscreen.core.VScreenOwnership.handBackIntent(context, pkg)))
        text(run { val top = VScreenShell.topPackage(0) ?: "?"; tr("$pkg 已移到主屏最前面（${m.via}），停在交还时的页面；主屏现在是 $top。", "$pkg is now in front on the main screen (${m.via}), on the page it was handed back on; the main screen shows $top. ") } +
            (if (notified) tr("已通知用户。", "The user was notified.") else run { val why = VScreenHandoff.notifyBlocked(context) ?: tr("通知没发出", "the notification was not sent"); tr("没通知到用户（$why）。", "The user was not notified ($why). ") }) +
            tr("告诉用户接下来要做什么；之后不要再操作它，除非用户再让你接手（handoff in）", "Tell the user what to do next; don't operate it afterwards unless the user asks you to take it over again (handoff in)"))
    }
}

// ── main-screen facts ────────────────────────────────────────────────

/** The main screen's resumed activity (to prove a background-screen command left it alone). */
internal fun VScreenOffloadHandler.mainTop(): String? = runCatching { VScreenShell.topActivity(0) }.getOrNull()

/** Ids of the app tasks on the main screen; null when they cannot be read (never "none"). */
internal fun VScreenOffloadHandler.mainTaskIds(): Set<Int>? =
    runCatching { VScreenHandoff.tasksOn(0).map { it.taskId }.toSet() }.getOrNull()?.takeIf { it.isNotEmpty() }

// ── stop / release ───────────────────────────────────────────────────

/** `stop [包名] [--force]`: close the app's task on the background screen (--force: kill the app, main screen too). */
internal fun VScreenOffloadHandler.stopCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val force = args.hasFlag("force")
    val main0 = mainWatch(s)
    // 1.4.0-beta13 (test: `stop 计算器` -> CLOSE_FAILED): an app name is resolved like launch does, not taken for a package.
    val pkgArg = args.positional.getOrNull(0)?.let { VScreenKnowledge.resolveApp(it) ?: it }
    if (pkgArg != null && force && VScreenShell.isProtected(pkgArg)) throw ActionError("PROTECTED_APP", tr("$pkgArg 是 Agent-VScreen 自己、桌面或系统界面，不能 force-stop", "$pkgArg is Agent-VScreen itself, the launcher or system UI, and cannot be force-stopped"))
    if (force) {
        val target = pkgArg ?: runCatching { BackgroundOperation.previewDisplayFor(s)?.let { VScreenShell.topPackage(it.displayId) } }.getOrNull() ?: "?"
        val app = VScreenKnowledge.label(target) ?: target
        com.kilon.agentvscreen.host.Consent.require(context, "force-stop:$target", tr("结束整个 $app（force-stop）", "stop the whole of $app (force-stop)"), tr("主屏上的 $app 也会被结束，没保存的内容会丢", "$app on the main screen is stopped too; unsaved content is lost"), kind = "system")
    }
    val stopped = VScreenKnowledge.journey(s)
    VScreenKnowledge.endJourney(s)
    VScreenKnowledge.touch(s, stopped)
    // allowParked: stop is how the screen (and anything parked on it) is cleared, so the parked gate must not refuse
    // it. clearParkedApps runs inside this authorised block, after the phone's confirmation for --force above.
    val msg = BackgroundOperation.agentAction(s, null, isRead = false, describe = if (force) tr("结束应用（force-stop）", "stop the app (force-stop)") else tr("关闭副屏上的应用", "close the app on the screen"), allowParked = true) { d ->
        BackgroundOperation.clearParkedApps(s)
        if (!force) {
            VScreenShell.closeOnDisplay(d.displayId, pkgArg)?.let { o -> if (o.ok) return@agentAction o.message }
            throw ActionError("CLOSE_FAILED", tr("没能只关闭副屏里的任务（找不到它的 task）。要结束整个应用加 --force（主屏上的这个应用也会被结束；手机上会请用户确认）", "Could not close just the task on the screen (its task was not found). To stop the whole app add --force (the app on the main screen is stopped too; the user is asked to confirm on the phone)"))
        }
        val o = VScreenShell.stop(d.displayId, pkgArg)
        if (!o.ok) throw ActionError(o.code, o.message)
        o.message + tr("（force-stop：主屏上的同一个应用也被结束了）", " (force-stop: the same app on the main screen was stopped too)")
    }
    return text(msg + (mainCheckLines(main0, s)?.let { "\n${Blocks.notes}$it" } ?: ""))
}

/** `release`: give the background screen back now (the connection stays; the next launch builds a new one). */
internal fun VScreenOffloadHandler.releaseCmd(sid: String?): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val main0 = mainWatch(s)
    BackgroundOperation.releaseNow(s)
    PageBook.clear(s)
    Loud.clear(s)
    com.kilon.agentvscreen.api.Turns.resetTurn(s)
    return text(tr("副屏已释放（连接保留，下次 launch 会新建副屏）", "Screen released (connection kept; the next launch creates a new screen)") + (mainCheckLines(main0)?.let { "\n${Blocks.notes}$it" } ?: ""))
}

/** 1.1 `done`: this session's turn ends now (screen idles, page kept, another session may take it over). */
internal fun VScreenOffloadHandler.doneCmd(sid: String?): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    BackgroundOperation.parkSoonFor = s
    com.kilon.agentvscreen.api.Turns.doneAfterThis(s)
    BackgroundOperation.setTitle(s, null)
    // 1.4.2-beta4: the user's takeover outlives the turn: say so instead of promising a pause that does not happen.
    if (BackgroundOperation.heldByUser(s)) return text(tr("这一轮结束了。用户还在接管副屏：副屏和页面都留着，不会暂停也不会释放；用户交还时会有 handback 事件（vscreen events --wait），之后先看一眼画面再继续。",
        "This turn is over. The user still holds the screen: it and its page stay, neither paused nor released; a handback event comes when the user hands it back (vscreen events --wait); look at the screen before going on."))
    return text(tr("这一轮结束了：副屏省电暂停，页面保留 10 分钟（下一条命令自动恢复）；别的会话现在可以接手。", "This turn is over: the screen pauses to save power and keeps the page for 10 minutes (the next command resumes it); other sessions can take over now."))
}

/** 1.1 `title <文字>`: what you are doing, shown on the floating window for the user (cleared when the turn ends). */
internal fun VScreenOffloadHandler.titleCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val t = args.positional.joinToString(" ").trim()
    if (t.length > 40) throw ActionError("INVALID_ARGS", tr("标题最多 40 个字（收到 ${t.length}）", "A title has at most 40 characters (got ${t.length})"))
    BackgroundOperation.setTitle(s, t.ifEmpty { null })
    return text(if (t.isEmpty()) tr("悬浮窗标题已清除", "floating window title cleared") else tr("悬浮窗标题：$t（这一轮结束时清除）", "floating window title: $t (cleared when this turn ends)"))
}
