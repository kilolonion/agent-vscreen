package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
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
        tr(S.vscreen_open_commands_could_not_read_the_screen, it.javaClass.simpleName)
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
    BackgroundOperation.agentAction(sid, null, isRead = false, describe = tr(S.vscreen_open_commands_start, pkg), freshPage = true) { d ->
        if (!hold) synchronized(wmFitted) { wmFitted[d.displayId]?.takeIf { it != pkg }?.let { wmFitted.remove(d.displayId) } }?.let {
            VScreenShell.run(arrayOf("wm", "size", "reset", "-d", d.displayId.toString())); syncRotation(d)
            facts.done += tr(S.vscreen_open_commands_screen_logical_size_restored_changed, it)
        }
        val r = VScreenShell.launch(d.displayId, pkg, null, mode)
        if (!r.ok) throw ActionError(r.code, r.message)
        facts.done += when (mode) {
            VScreenShell.LaunchMode.RESTART -> tr(S.vscreen_open_commands_cold_start, pkg)
            VScreenShell.LaunchMode.KEEP -> tr(S.vscreen_open_commands_open_the_screen_is_left, pkg)
            else -> tr(S.vscreen_open_commands_open, pkg)
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
        facts.done += if (w.met != null) tr(S.vscreen_open_commands_screen_settled_at_s, (ms / 100) / 10.0) else tr(S.vscreen_open_commands_waited_s_still_changing_loading, (ms / 100) / 10.0)
        syncRotation(d)?.let { facts.notes += it }
        val act = VScreenShell.topActivity(d.displayId)
        landed = act?.substringBefore('/')
        if (act != null) synchronized(VScreenOffloadHandler.lastActivity) { VScreenOffloadHandler.lastActivity[d.displayId] = act }
        if (act != null && VScreenShell.isResolver(act)) throw ActionError("LAUNCH_RESOLVER",
            tr(S.vscreen_open_commands_after_starting_the_screen_is, pkg, act))
        if (landed != null && landed != pkg) facts.notes += tr(S.vscreen_open_commands_the_front_app_is_not, landed, pkg)
        if (landed == pkg) {
            if (declared == null && orient == null) VScreenMap.setProfile(pkg, mapOf("orientation" to
                if (BackgroundOperation.nativeLandscape(d) != (d.rotation % 2 == 1)) "landscape" else "portrait"))
            if (!hold && depth == 0 && d.rotation % 2 == 1) needRefit = !BackgroundOperation.nativeLandscape(d)
            val gs = VScreenShell.windowScale(d.displayId, pkg)
            val crop = VScreenDownscale.surfaceCrop(pkg)
            val late = restart && VScreenDownscale.consumeLateScaled(pkg)
            if (!hold && depth == 0 && needRefit == null && ((gs != null && gs > 1.02) || crop?.cropped == true || late)) needUnscale = gs ?: 1.0
            if (depth > 0 && ((gs != null && gs > 1.02) || crop?.cropped == true))
                facts.notes += run { val x = gs?.let { "%.2f".format(it) } ?: "?"; tr(S.vscreen_open_commands_after_the_fix_is_still, pkg, x) } + Vendor.current.downscaleHint()
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
            facts.done += run { val x = "%.2f".format(gs); tr(S.vscreen_open_commands_the_system_renders_scaled_down, pkg, x, fix.text) }
            return doLaunch(sid, pkg, orient, true, depth + 1, keep, facts)
        }
        wmFit(sid, pkg)?.let { note -> facts.done += note; return doLaunch(sid, pkg, orient, true, depth + 1, keep, facts) }
        facts.notes += run { val x = "%.2f".format(gs); tr(S.vscreen_open_commands_is_scaled_by_the_system, pkg, x, fix.text) } + Vendor.current.downscaleHint()
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
    return tr(S.vscreen_open_commands_the_system_renders_at_can, pkg, x, w, h, pw, ph)
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
            tr(S.vscreen_open_commands_agent_vscreen_s_own_pages),
        )
    }
}

internal fun VScreenOffloadHandler.handoffSkip(): Set<String> =
    setOf(context.packageName, "com.android.systemui") +
        com.kilon.agentvscreen.host.ShizukuManager.discoveredManagerPackages() +
        runCatching { VScreenHandoff.homePackages() }.getOrDefault(emptySet())

/**
 * `handoff park <应用>`: the app moves to the hidden parking display, its page untouched, and is out of the agent's
 * hands until `handoff unpark <应用>`, which brings it back to the background screen. The app is always named: a wrong
 * guess would park the wrong page. Neither direction touches the main screen: nothing is moved to it and nothing is
 * read from it, and the app-rule check ([Guard]) is made on that one package.
 */
private fun VScreenOffloadHandler.handoffPark(sid: String, pkgArg: String?, park: Boolean): NativeOffloadResult {
    val what = if (park) tr(S.vscreen_open_commands_park) else tr(S.vscreen_open_commands_unpark)
    val use = if (park) "handoff park <应用>" else "handoff unpark <应用>"
    val pkg = pkgArg?.takeIf { it.isNotBlank() }
        ?: throw ActionError("INVALID_ARGS", tr(S.vscreen_open_commands_name_the_app_park_and, use))
    requireNotOwnPackage(pkg)
    if (pkg in handoffSkip()) throw ActionError("HANDOFF_DENIED", tr(S.vscreen_open_commands_is_the_launcher_or_system, pkg))
    // Only this session's own screen: parking is not a way to take a screen another session is using (BUSY says whose).
    BackgroundOperation.previewDisplayFor(sid) ?: run {
        val owner = BackgroundOperation.state.value.ownerSessionId
        throw if (owner != null && owner != sid)
            ActionError("BUSY", tr(S.vscreen_open_commands_the_screen_belongs_to_now, owner))
        else ActionError("NO_SCREEN", tr(S.vscreen_open_commands_there_is_no_screen_now))
    }
    com.kilon.agentvscreen.core.Guard.require(if (park) com.kilon.agentvscreen.core.Guard.Act.OPEN else com.kilon.agentvscreen.core.Guard.Act.TAKE, pkg, sid)
    val taskId = BackgroundOperation.agentAction(sid, null, isRead = false, describe = tr(S.vscreen_open_commands_text, what, pkg), allowParked = true) { d ->
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
        tr(S.vscreen_open_commands_is_parked_on_the_parking, pkg, taskId)
    else
        tr(S.vscreen_open_commands_is_back_on_the_screen, pkg, taskId)
    return NativeOffloadResult(0, msg + "\n", "OK",
        org.json.JSONObject().put("package", pkg).put("task_id", taskId).put("parked", park))
}

/**
 * `tasks`: the app tasks on the background screen right now (front first) — package, task id and whether it is in
 * front, straight from the system's own per-display list. A read: it neither takes a screen another session is using
 * nor creates one, and "cannot read" is said instead of guessed.
 */
internal fun VScreenOffloadHandler.tasksCmd(sid: String?): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr(S.vscreen_act_no_session_header_x_vscreen))
    val d = BackgroundOperation.previewDisplayFor(s) ?: run {
        val owner = BackgroundOperation.state.value.ownerSessionId
        throw if (owner != null && owner != s)
            ActionError("BUSY", tr(S.vscreen_open_commands_the_screen_belongs_to_now_2, owner))
        else ActionError("NO_SCREEN", tr(S.vscreen_open_commands_there_is_no_screen_now))
    }
    val list = VScreenHandoff.tasksOrNull(d.displayId)
        ?: throw ActionError("NOT_SUPPORTED", tr(S.vscreen_open_commands_the_system_s_task_list))
    val rows = list.joinToString("\n") { t ->
        (if (t.foreground) "* " else "  ") + t.pkg + "  " + tr(S.vscreen_open_commands_task, t.taskId) + (if (t.foreground) tr(S.vscreen_open_commands_in_front) else "")
    }
    val head = if (list.isEmpty()) tr(S.vscreen_open_commands_no_app_tasks_on_the)
        else tr(S.vscreen_open_commands_app_tasks_on_the_screen)
    val data = org.json.JSONObject().put("display_id", d.displayId).put("tasks", org.json.JSONArray(list.map {
        org.json.JSONObject().put("package", it.pkg).put("task_id", it.taskId).put("foreground", it.foreground)
    }))
    return NativeOffloadResult(0, head + (if (list.isEmpty()) "" else "\n" + rows) + "\n", "OK", data)
}

/** `handoff in [包名]` (take the user's app, return the four blocks) | `handoff out [包名] [--note 说明]` (give it back). */
internal fun VScreenOffloadHandler.handoffCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr(S.vscreen_act_no_session_header_x_vscreen))
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
            text((if (op != null) tr(S.vscreen_open_commands_handoff_in_package_handoff_out, op) else "") +
                tr(S.vscreen_open_commands_tasks_on_the_main_screen) + (main.joinToString(tr(S.agent_guide_sep)) { t -> t.pkg + (if (t.visible) tr(S.vscreen_open_commands_visible) else "") + if (t.pkg in skip) tr(S.vscreen_open_commands_cannot_be_taken_over) else "" }.ifEmpty { tr(S.vscreen_diag_none) }))
        }
    }
}

private fun VScreenOffloadHandler.handoffIn(sid: String, pkgArg: String?): NativeOffloadResult {
    val start = System.currentTimeMillis(); val t0 = SystemClock.elapsedRealtime()
    requireNotOwnPackage(pkgArg)
    if (pkgArg != null && pkgArg in handoffSkip()) {
        throw ActionError("PROTECTED_APP", tr(S.vscreen_open_commands_is_the_launcher_or_system_2, pkgArg))
    }
    val main = VScreenHandoff.tasksOn(0)
    val pick = if (pkgArg != null) main.firstOrNull { it.pkg == pkgArg }
        ?: throw ActionError("HANDOFF_NO_TASK", tr(S.vscreen_open_commands_no_task_of_on_the, pkgArg))
    else main.firstOrNull { it.pkg !in handoffSkip() }
        ?: throw ActionError("HANDOFF_NO_TASK", tr(S.vscreen_open_commands_no_app_on_the_main))
    requireNotOwnPackage(pick.pkg)
    BackgroundOperation.requireNotParked(sid, pick.pkg)
    com.kilon.agentvscreen.core.Guard.require(com.kilon.agentvscreen.core.Guard.Act.TAKE, pick.pkg)
    // 1.2: taking the app the user is using off their screen is theirs to allow, on the phone.
    run { val app = VScreenKnowledge.label(pick.pkg) ?: pick.pkg
        com.kilon.agentvscreen.host.Consent.require(context, "handoff:${pick.pkg}", tr(S.vscreen_open_commands_move_which_you_are_using, app), tr(S.vscreen_open_commands_it_leaves_the_main_screen), kind = com.kilon.agentvscreen.host.Consent.CAUTION, pkg = pick.pkg) }
    val want = VScreenShell.appOrientation(context, pick.pkg, null) ?: VScreenMap.profile(pick.pkg)["orientation"]?.let { it == "landscape" }
    // 1.4.2-beta6: building / rebuilding the screen and removing a task come before the action itself: not while the
    // user holds the screen or has stopped the AI.
    com.kilon.agentvscreen.core.Control.admitInput(checkPlan = false)
    var d0 = BackgroundOperation.acquire(sid, create = true, landscape = want)
    if (!BackgroundOperation.hasParkedApps(sid) && want != null && BackgroundOperation.nativeLandscape(d0) != want) d0 = BackgroundOperation.rebuild(sid, want)
    VScreenShell.taskIdOn(d0.displayId, pick.pkg)?.takeIf { it != pick.taskId }?.let { com.kilon.agentvscreen.core.Control.admitInput(checkPlan = false); VScreenRemoteDisplay.removeTask(it, d0.displayId) }
    val done = ArrayList<String>()
    BackgroundOperation.agentAction(sid, null, isRead = false, describe = tr(S.vscreen_open_commands_relay_move_from_the_main, pick.pkg), allowParked = true) { d ->
        val m = VScreenHandoff.move(pick.taskId, pick.pkg, d.displayId)
        if (!m.ok) throw ActionError("HANDOFF_DENIED", tr(S.vscreen_open_commands_the_system_did_not_move, pick.pkg, m.detail) +
            tr(S.vscreen_open_commands_instead_launch_opens_a_separate, pick.pkg))
        syncRotation(d)
        done += tr(S.vscreen_open_commands_moved_task_from_the_user, pick.pkg, pick.taskId, m.via)
        done += run { val top = VScreenShell.topPackage(0) ?: "?"; tr(S.vscreen_open_commands_the_main_screen_now_shows, top) }
    }
    BackgroundOperation.appSelected(sid, pick.pkg)
    VScreenKnowledge.startJourney(sid, pick.pkg)
    val d = BackgroundOperation.acquire(sid, create = false)
    VScreenPerception.ensure(d)
    val attention = Notes()
    attention += tr(S.vscreen_open_commands_this_is_the_page_the)
    takeAdjusted().forEach { attention += tr(S.vscreen_act_options_adjusted, it) }
    return fourBlocks(sid, d, done, attention, start, SystemClock.elapsedRealtime() - t0, "handoff", fullList = true)
}

private fun VScreenOffloadHandler.handoffOut(sid: String, pkgArg: String?, msg: String?): NativeOffloadResult {
    val d0 = BackgroundOperation.acquire(sid, create = false)
    val pkg = pkgArg ?: VScreenShell.topPackage(d0.displayId) ?: throw ActionError("NO_APP", tr(S.vscreen_open_commands_no_app_on_the_screen))
    BackgroundOperation.requireNotParked(sid, pkg)
    val task = VScreenShell.taskIdOn(d0.displayId, pkg) ?: throw ActionError("HANDOFF_NO_TASK", tr(S.vscreen_open_commands_no_task_of_on_the_2, pkg))
    return BackgroundOperation.agentAction(sid, null, isRead = false, describe = tr(S.vscreen_open_commands_hand_back_move_from_the, pkg), allowParked = true) { _ ->
        val m = VScreenHandoff.move(task, pkg, 0)
        if (m.ok) com.kilon.agentvscreen.core.VScreenMute.restore(pkg)
        if (m.ok) com.kilon.agentvscreen.core.VScreenOwnership.handedOut(context, sid, pkg, task,
            synchronized(VScreenOffloadHandler.lastActivity) { VScreenOffloadHandler.lastActivity[d0.displayId] })
        if (!m.ok) throw ActionError("HANDOFF_DENIED", tr(S.vscreen_open_commands_the_system_did_not_move_2, pkg, m.detail))
        val label = runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }.getOrDefault(pkg)
        val who = com.kilon.agentvscreen.api.Agents.nameOfSession(sid)
        val notified = VScreenHandoff.alert(context, com.kilon.agentvscreen.core.VScreenOwnership.nudgeId(pkg), tr(S.vscreen_open_commands_is_yours_now, label),
            (msg ?: tr(S.vscreen_open_commands_left_on_the_current_page, who, label)) +
                tr(S.vscreen_open_commands_when_done_tap_hand_back),
            com.kilon.agentvscreen.host.Needs.open(context, com.kilon.agentvscreen.host.Needs.Page.HOME, 1),
            listOf(tr(S.vscreen_open_commands_hand_back) to com.kilon.agentvscreen.core.VScreenOwnership.handBackIntent(context, pkg)))
        text(run { val top = VScreenShell.topPackage(0) ?: "?"; tr(S.vscreen_open_commands_is_now_in_front_on, pkg, m.via, top) } +
            (if (notified) tr(S.vscreen_open_commands_the_user_was_notified) else run { val why = VScreenHandoff.notifyBlocked(context) ?: tr(S.vscreen_open_commands_the_notification_was_not_sent); tr(S.vscreen_open_commands_the_user_was_not_notified, why) }) +
            tr(S.vscreen_open_commands_tell_the_user_what_to))
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
    val s = sid ?: throw ActionError("NO_SESSION", tr(S.vscreen_act_no_session_header_x_vscreen))
    val force = args.hasFlag("force")
    val main0 = mainWatch(s)
    // 1.4.0-beta13 (test: `stop 计算器` -> CLOSE_FAILED): an app name is resolved like launch does, not taken for a package.
    val pkgArg = args.positional.getOrNull(0)?.let { VScreenKnowledge.resolveApp(it) ?: it }
    if (pkgArg != null && !com.kilon.agentvscreen.core.PackageNames.isValid(pkgArg)) {
        throw ActionError("INVALID_ARGS", tr(S.vscreen_open_commands_invalid_package_name, pkgArg))
    }
    if (pkgArg != null && force && VScreenShell.isProtected(pkgArg)) throw ActionError("PROTECTED_APP", tr(S.vscreen_open_commands_is_agent_vscreen_itself_the, pkgArg))
    if (force) {
        val target = pkgArg ?: runCatching { BackgroundOperation.previewDisplayFor(s)?.let { VScreenShell.topPackage(it.displayId) } }.getOrNull() ?: "?"
        val app = VScreenKnowledge.label(target) ?: target
        com.kilon.agentvscreen.host.Consent.require(context, "force-stop:$target", tr(S.vscreen_open_commands_stop_the_whole_of_force, app), tr(S.vscreen_open_commands_on_the_main_screen_is, app), kind = com.kilon.agentvscreen.host.Consent.CAUTION, pkg = target)
    }
    val stopped = VScreenKnowledge.journey(s)
    VScreenKnowledge.endJourney(s)
    VScreenKnowledge.touch(s, stopped)
    // allowParked: stop is how the screen (and anything parked on it) is cleared, so the parked gate must not refuse
    // it. clearParkedApps runs inside this authorised block, after the phone's confirmation for --force above.
    val msg = BackgroundOperation.agentAction(s, null, isRead = false, describe = if (force) tr(S.vscreen_open_commands_stop_the_app_force_stop) else tr(S.vscreen_open_commands_close_the_app_on_the), allowParked = true) { d ->
        BackgroundOperation.clearParkedApps(s)
        if (!force) {
            VScreenShell.closeOnDisplay(d.displayId, pkgArg)?.let { o -> if (o.ok) return@agentAction o.message }
            throw ActionError("CLOSE_FAILED", tr(S.vscreen_open_commands_could_not_close_just_the))
        }
        val o = VScreenShell.stop(d.displayId, pkgArg)
        if (!o.ok) throw ActionError(o.code, o.message)
        o.message + tr(S.vscreen_open_commands_force_stop_the_same_app)
    }
    return text(msg + (mainCheckLines(main0, s)?.let { "\n${Blocks.notes}$it" } ?: ""))
}

/** `release`: give the background screen back now (the connection stays; the next launch builds a new one). */
internal fun VScreenOffloadHandler.releaseCmd(sid: String?): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr(S.vscreen_act_no_session_header_x_vscreen))
    val main0 = mainWatch(s)
    BackgroundOperation.releaseNow(s)
    PageBook.clear(s)
    Loud.clear(s)
    com.kilon.agentvscreen.api.Turns.resetTurn(s)
    return text(tr(S.vscreen_open_commands_screen_released_connection_kept_the) + (mainCheckLines(main0)?.let { "\n${Blocks.notes}$it" } ?: ""))
}

/** 1.1 `done`: this session's turn ends now (screen idles, page kept, another session may take it over). */
internal fun VScreenOffloadHandler.doneCmd(sid: String?): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr(S.vscreen_act_no_session_header_x_vscreen))
    BackgroundOperation.parkSoonFor = s
    com.kilon.agentvscreen.api.Turns.doneAfterThis(s)
    BackgroundOperation.setTitle(s, null)
    // 1.4.2-beta4: the user's takeover outlives the turn: say so instead of promising a pause that does not happen.
    if (BackgroundOperation.heldByUser(s)) return text(tr(S.vscreen_open_commands_this_turn_is_over_the))
    return text(tr(S.vscreen_open_commands_this_turn_is_over_the_2))
}

/** 1.1 `title <文字>`: what you are doing, shown on the floating window for the user (cleared when the turn ends). */
internal fun VScreenOffloadHandler.titleCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr(S.vscreen_act_no_session_header_x_vscreen))
    val t = args.positional.joinToString(" ").trim()
    if (t.length > 40) throw ActionError("INVALID_ARGS", tr(S.vscreen_open_commands_a_title_has_at_most, t.length))
    BackgroundOperation.setTitle(s, t.ifEmpty { null })
    return text(if (t.isEmpty()) tr(S.vscreen_open_commands_floating_window_title_cleared) else tr(S.vscreen_open_commands_floating_window_title_cleared_when, t))
}
