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
import com.kilon.agentvscreen.core.VScreenStore
import kotlin.math.roundToInt
import com.kilon.agentvscreen.cmd.VScreenOffloadHandler.Companion.TAG

// [vscreen] v49: open an app on the background screen (launch), move apps between the screens (handoff), close
// (stop) and give the screen back (release). launch fits the screen to the app (orientation, vendor downscaling)
// and reports what it did as facts; it does not judge whether the page is "ready" — the four blocks show it.

/** What a launch did ([done]) and what the caller must know ([attention]). */
internal class LaunchFacts(val done: MutableList<String>, val attention: MutableList<String>)

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
                                            facts: LaunchFacts = LaunchFacts(ArrayList(), ArrayList())): LaunchFacts {
    val declared = VScreenShell.appOrientation(context, pkg, null)
    val ranAs = VScreenMap.profile(pkg)["orientation"]?.let { it == "landscape" }
    val want = orient ?: declared ?: ranAs ?: VScreenShell.naturalLandscape(context)
    VScreenKnowledge.startJourney(sid, pkg)
    VScreenKnowledge.touch(sid, pkg)
    val d0 = BackgroundOperation.acquire(sid, create = true, landscape = want)
    if (BackgroundOperation.nativeLandscape(d0) != want) {
        BackgroundOperation.rebuild(sid, want)
        facts.done += if (I18n.en) "Screen rebuilt in ${if (want) "landscape" else "portrait"} (${when { orient != null -> "as you asked"; declared != null -> "as the app declares"; ranAs != null -> "as it last actually ran"; else -> "the device's natural orientation" }})"
            else "副屏按${if (want) "横屏" else "竖屏"}重建（${when { orient != null -> "你指定的"; declared != null -> "应用声明的"; ranAs != null -> "它上次实际运行的"; else -> "设备自然方向" }}）"
    }
    val mode = if (restart || depth > 0) VScreenShell.LaunchMode.RESTART else VScreenShell.LaunchMode.ROOT
    var needRefit: Boolean? = null
    var needUnscale: Double? = null
    var landed: String? = null
    BackgroundOperation.agentAction(sid, null, isRead = false, describe = tr("启动 $pkg", "start $pkg"), freshPage = true) { d ->
        synchronized(wmFitted) { wmFitted[d.displayId]?.takeIf { it != pkg }?.let { wmFitted.remove(d.displayId) } }?.let {
            VScreenShell.run(arrayOf("wm", "size", "reset", "-d", d.displayId.toString())); syncRotation(d)
            facts.done += tr("副屏逻辑尺寸还原（上一个应用 $it 改过）", "screen logical size restored (changed by the previous app $it)")
        }
        val r = VScreenShell.launch(d.displayId, pkg, null, mode)
        if (!r.ok) throw ActionError(r.code, r.message)
        facts.done += if (mode == VScreenShell.LaunchMode.RESTART) tr("冷启动 $pkg", "cold-start $pkg") else tr("打开 $pkg", "open $pkg")
        VScreenPerception.ensure(d)
        // Wait for a still, non-blank picture (at most 10 s): facts, not a readiness verdict.
        val t0 = SystemClock.elapsedRealtime()
        val p = Probe(this, d, listOf(Cond("stable", "still")))
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
        syncRotation(d)?.let { facts.attention += it }
        val act = VScreenShell.topActivity(d.displayId)
        landed = act?.substringBefore('/')
        if (act != null) synchronized(VScreenOffloadHandler.lastActivity) { VScreenOffloadHandler.lastActivity[d.displayId] = act }
        if (act != null && VScreenShell.isResolver(act)) throw ActionError("LAUNCH_RESOLVER",
            tr("启动 $pkg 后副屏停在系统的“使用以下方式打开”选择器（$act）：这个包没有桌面入口，apps 关键词 找带图标的包名", "After starting $pkg the screen is on the system's \"Open with\" chooser ($act): this package has no launcher entry; find the package with an icon via apps <keyword>"))
        if (landed != null && landed != pkg) facts.attention += tr("前台是 $landed，不是 $pkg（被跳转到别的应用或系统页）", "The front app is $landed, not $pkg (redirected to another app or a system page)")
        if (landed == pkg) {
            if (declared == null && orient == null) VScreenMap.setProfile(pkg, mapOf("orientation" to
                if (BackgroundOperation.nativeLandscape(d) != (d.rotation % 2 == 1)) "landscape" else "portrait"))
            if (depth == 0 && d.rotation % 2 == 1) needRefit = !BackgroundOperation.nativeLandscape(d)
            val gs = VScreenShell.windowScale(d.displayId, pkg)
            val crop = VScreenDownscale.surfaceCrop(pkg)
            if (depth == 0 && needRefit == null && ((gs != null && gs > 1.02) || crop?.cropped == true)) needUnscale = gs ?: 1.0
            if (depth > 0 && ((gs != null && gs > 1.02) || crop?.cropped == true))
                facts.attention += run { val x = gs?.let { "%.2f".format(it) } ?: "?"; tr("处理后 $pkg 仍被系统缩放（$x 倍），画面右侧/下方可能被裁。用户可手动：", "After the fix $pkg is still scaled by the system (×$x); the right/bottom of the picture may be cut off. The user can do it by hand: ") } + VScreenShell.downscaleSettingHint()
        }
    }
    needRefit?.let { land ->
        BackgroundOperation.rebuild(sid, land)
        facts.done += if (I18n.en) "$pkg turned the picture to ${if (land) "landscape" else "portrait"} after starting: the screen was rebuilt in ${if (land) "landscape" else "portrait"} and the app reopened"
            else "$pkg 启动后把画面转成${if (land) "横屏" else "竖屏"}：副屏按${if (land) "横屏" else "竖屏"}重建后重开"
        return doLaunch(sid, pkg, land, true, depth + 1, facts)
    }
    needUnscale?.let { gs ->
        val fix = BackgroundOperation.disableDownscale(pkg)
        if (fix.changed) {
            facts.done += run { val x = "%.2f".format(gs); tr("系统把 $pkg 缩小 $x 倍渲染（画面会被裁）：${fix.text}；冷启动一次（副屏释放时恢复原设置）", "The system renders $pkg scaled down ×$x (the picture gets cut off): ${fix.text}; cold-started once (the original setting returns when the screen is released)") }
            return doLaunch(sid, pkg, orient, true, depth + 1, facts)
        }
        wmFit(sid, pkg)?.let { note -> facts.done += note; return doLaunch(sid, pkg, orient, true, depth + 1, facts) }
        facts.attention += run { val x = "%.2f".format(gs); tr("$pkg 被系统缩放（$x 倍），画面右侧/下方会被裁，这里改不了（${fix.text}）。用户可手动：", "$pkg is scaled by the system (×$x); the right/bottom of the picture gets cut off and this can't be changed here (${fix.text}). The user can do it by hand: ") } + VScreenShell.downscaleSettingHint()
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

internal fun VScreenOffloadHandler.handoffSkip(): Set<String> =
    setOf(context.packageName, "com.android.systemui", "moe.shizuku.privileged.api") + runCatching { VScreenHandoff.homePackages() }.getOrDefault(emptySet())

/** `handoff in [包名]` (take the user's app, return the four blocks) | `handoff out [包名] [--note 说明]` (give it back). */
internal fun VScreenOffloadHandler.handoffCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    return when (val op = args.positional.getOrNull(0)) {
        "in" -> handoffIn(s, args.positional.getOrNull(1))
        "out" -> handoffOut(s, args.positional.getOrNull(1), args.get("note"))
        else -> {
            val skip = handoffSkip()
            val main = VScreenHandoff.tasksOn(0)
            text((if (op != null) tr("handoff in [包名] | handoff out [包名] [--note 说明]（不认识 $op）\n\n", "handoff in [package] | handoff out [package] [--note text] (unknown $op)\n\n") else "") +
                tr("主屏上的任务：", "tasks on the main screen: ") + (main.joinToString(tr("；", "; ")) { t -> t.pkg + (if (t.visible) tr("（可见）", " (visible)") else "") + if (t.pkg in skip) tr("（不能接手）", " (cannot be taken over)") else "" }.ifEmpty { tr("无", "none") }))
        }
    }
}

private fun VScreenOffloadHandler.handoffIn(sid: String, pkgArg: String?): NativeOffloadResult {
    val start = System.currentTimeMillis(); val t0 = SystemClock.elapsedRealtime()
    val main = VScreenHandoff.tasksOn(0)
    val pick = if (pkgArg != null) main.firstOrNull { it.pkg == pkgArg }
        ?: throw ActionError("HANDOFF_NO_TASK", tr("主屏上没有 $pkgArg 的任务。可以 launch $pkgArg 在副屏另开一份（不带用户的页面）", "No task of $pkgArg on the main screen. launch $pkgArg opens a separate copy on the screen (without the user's page)"))
    else main.firstOrNull { it.pkg !in handoffSkip() }
        ?: throw ActionError("HANDOFF_NO_TASK", tr("主屏最近没有可接手的应用（只有桌面和 Agent-VScreen 自己）：请用户先打开那个应用，或说是哪个应用", "No app on the main screen to take over (only the launcher and Agent-VScreen): ask the user to open that app first, or to say which one"))
    if (VScreenStore.ruleFor(pick.pkg) == VScreenStore.Rule.BLOCK)
        throw ActionError("APP_BLOCKED", tr("用户禁止后台操作 ${pick.pkg}（Agent-VScreen › 应用限制），不能接手它", "The user blocked background operation of ${pick.pkg} (Agent-VScreen › App rules); it can't be taken over"))
    val want = VScreenShell.appOrientation(context, pick.pkg, null) ?: VScreenMap.profile(pick.pkg)["orientation"]?.let { it == "landscape" }
    var d0 = BackgroundOperation.acquire(sid, create = true, landscape = want)
    if (want != null && BackgroundOperation.nativeLandscape(d0) != want) d0 = BackgroundOperation.rebuild(sid, want)
    VScreenShell.taskIdOn(d0.displayId, pick.pkg)?.takeIf { it != pick.taskId }?.let { VScreenRemoteDisplay.removeTask(it, d0.displayId) }
    val done = ArrayList<String>()
    BackgroundOperation.agentAction(sid, null, isRead = false, describe = tr("接力：把主屏的 ${pick.pkg} 移到副屏", "Relay: move ${pick.pkg} from the main screen to the background screen")) { d ->
        val m = VScreenHandoff.move(pick.taskId, pick.pkg, d.displayId)
        if (!m.ok) throw ActionError("HANDOFF_DENIED", tr("系统没有让 ${pick.pkg} 移到副屏（${m.detail}），用户的应用仍在主屏没被改动。", "The system did not move ${pick.pkg} to the screen (${m.detail}); the user's app is still on the main screen, untouched. ") +
            tr("替代：launch ${pick.pkg} 在副屏另开一份，或请用户自己操作", "Instead: launch ${pick.pkg} opens a separate copy on the screen, or let the user do it"))
        syncRotation(d)
        done += tr("把用户主屏上的 ${pick.pkg}（任务 ${pick.taskId}）移到副屏（${m.via}），停在用户刚才的页面", "Moved ${pick.pkg} (task ${pick.taskId}) from the user's main screen to the background screen (${m.via}), on the page the user was on")
        done += run { val top = VScreenShell.topPackage(0) ?: "?"; tr("主屏现在是 $top", "The main screen now shows $top") }
    }
    VScreenKnowledge.startJourney(sid, pick.pkg)
    val d = BackgroundOperation.acquire(sid, create = false)
    VScreenPerception.ensure(d)
    val attention = ArrayList<String>()
    attention += tr("这是用户正在用的页面（登录、填了一半的内容都在）：不要 launch 重开；需要用户本人的一步（付款、密码、验证码）用 handoff out 交还", "This is the page the user was using (logins and half-filled content included): don't launch it again; for a step only the user can do (payment, password, code), hand it back with handoff out")
    takeAdjusted().forEach { attention += tr("参数被调整：$it", "options adjusted: $it") }
    return fourBlocks(sid, d, done, attention, start, SystemClock.elapsedRealtime() - t0, "handoff", fullList = true)
}

private fun VScreenOffloadHandler.handoffOut(sid: String, pkgArg: String?, msg: String?): NativeOffloadResult {
    val d0 = BackgroundOperation.acquire(sid, create = false)
    val pkg = pkgArg ?: VScreenShell.topPackage(d0.displayId) ?: throw ActionError("NO_APP", tr("副屏上没有应用可以交还", "No app on the screen to hand back"))
    val task = VScreenShell.taskIdOn(d0.displayId, pkg) ?: throw ActionError("HANDOFF_NO_TASK", tr("副屏上找不到 $pkg 的任务", "No task of $pkg on the screen"))
    return BackgroundOperation.agentAction(sid, null, isRead = false, describe = tr("交还：把副屏的 $pkg 移到主屏", "Hand back: move $pkg from the background screen to the main screen")) { _ ->
        val m = VScreenHandoff.move(task, pkg, 0)
        if (!m.ok) throw ActionError("HANDOFF_DENIED", tr("系统没有让 $pkg 移回主屏（${m.detail}）。替代：请用户点副屏悬浮窗 › 全屏 › 接管，自己完成这一步", "The system did not move $pkg back to the main screen (${m.detail}). Instead: ask the user to tap the floating window › Full screen › Take over and do the step themselves"))
        val label = runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }.getOrDefault(pkg)
        val notified = VScreenHandoff.alert(context, 7290, tr("$label 已交给你", "$label is yours now"), msg ?: tr("Agent 把 $label 停在当前页面交给你，请在主屏继续", "The agent left $label on the current page for you; carry on on the main screen"))
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

/** Main-screen tasks listed in [before] that are gone now (empty when unknown). */
internal fun VScreenOffloadHandler.mainTasksLost(before: Set<Int>?): Set<Int> {
    if (before.isNullOrEmpty()) return emptySet()
    val now = mainTaskIds() ?: return emptySet()
    return before - now
}

// ── stop / release ───────────────────────────────────────────────────

/** `stop [包名] [--force]`: close the app's task on the background screen (--force: kill the app, main screen too). */
internal fun VScreenOffloadHandler.stopCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val force = args.hasFlag("force")
    val main0 = mainTop(); val tasks0 = mainTaskIds()
    val pkgArg = args.positional.getOrNull(0)
    if (pkgArg != null && force && VScreenShell.isProtected(pkgArg)) throw ActionError("PROTECTED_APP", tr("$pkgArg 是 Agent-VScreen 自己、桌面或系统界面，不能 force-stop", "$pkgArg is Agent-VScreen itself, the launcher or system UI, and cannot be force-stopped"))
    val stopped = VScreenKnowledge.journey(s)
    VScreenKnowledge.endJourney(s)
    VScreenKnowledge.touch(s, stopped)
    val msg = BackgroundOperation.agentAction(s, null, isRead = false, describe = if (force) tr("结束应用（force-stop）", "stop the app (force-stop)") else tr("关闭副屏上的应用", "close the app on the screen")) { d ->
        if (!force) {
            VScreenShell.closeOnDisplay(d.displayId, pkgArg)?.let { o -> if (o.ok) return@agentAction o.message }
            throw ActionError("CLOSE_FAILED", tr("没能只关闭副屏里的任务（找不到它的 task）。要结束整个应用加 --force（主屏上的这个应用也会被结束），先征得用户同意", "Could not close just the task on the screen (its task was not found). To stop the whole app add --force (the app on the main screen is stopped too); ask the user first"))
        }
        val o = VScreenShell.stop(d.displayId, pkgArg)
        if (!o.ok) throw ActionError(o.code, o.message)
        o.message + tr("（force-stop：主屏上的同一个应用也被结束了）", " (force-stop: the same app on the main screen was stopped too)")
    }
    return text(msg + (mainCheckLines(main0, tasks0)?.let { "\n${Blocks.notes}$it" } ?: ""))
}

/** `release`: give the background screen back now (the connection stays; the next launch builds a new one). */
internal fun VScreenOffloadHandler.releaseCmd(sid: String?): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val main0 = mainTop(); val tasks0 = mainTaskIds()
    BackgroundOperation.releaseNow(s)
    return text(tr("副屏已释放（连接保留，下次 launch 会新建副屏）", "Screen released (connection kept; the next launch creates a new screen)") + (mainCheckLines(main0, tasks0)?.let { "\n${Blocks.notes}$it" } ?: ""))
}

/** 1.1 `done`: this session's turn ends now (screen idles, page kept, another session may take it over). */
internal fun VScreenOffloadHandler.doneCmd(sid: String?): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    com.kilon.agentvscreen.api.Turns.doneAfterThis(s)
    BackgroundOperation.setTitle(s, null)
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
