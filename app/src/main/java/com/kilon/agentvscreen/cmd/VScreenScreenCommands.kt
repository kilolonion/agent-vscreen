package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.tr

import android.content.Intent
import android.os.SystemClock
import com.kilon.agentvscreen.host.VScreenAccessibilityService
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenDisplayService
import com.kilon.agentvscreen.core.VScreenRemoteDisplay
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenStore
import com.kilon.agentvscreen.core.Vendor
import org.json.JSONArray
import org.json.JSONObject
import com.kilon.agentvscreen.cmd.VScreenOffloadHandler.Companion.lastActivity

// [vscreen] vscreen — 副屏与诊断：status / fix / apps.
// Part of VScreenOffloadHandler (see that file for the command table and shared state).

private val AUTO_FIXABLE = setOf("background_network", "not_connected", "no_accessibility", "app_owned_display", "a11y_not_ready", "accessibility_cannot_see_display",
    "not_foreground_service", "remote_down", "silent_apps")

private val FIX_KEYS = listOf(
    "not_connected", "not_foreground_service", "remote_down", "silent_apps", "frozen_in_background",
    "no_privilege", "no_accessibility", "app_owned_display", "no_display_focus",
    "accessibility_cannot_see_display", "a11y_not_ready", "background_network",
)

private fun fixWhat(code: String): String? = when (code) {
    "not_connected" -> tr("重新连接特权通道", "reconnect the privileged channel")
    "not_foreground_service" -> tr("先加电池白名单，再把服务重新启动成前台服务；系统仍不允许时请用户打开一次 App", "grant the battery exemption, then start the service again as a foreground service; if the system still refuses, ask the user to open the app once")
    "remote_down" -> tr("重启内置 Tailscale 并等它连上；要登录或批准时说明用户要做什么", "restart the built-in Tailscale and wait for it to connect; say what the user must do when it needs a login or approval")
    "silent_apps" -> tr("把被留下静音的应用（不含手动静音的和正在副屏上的）恢复声音并逐个核实；fix 不带参数时总会检查", "give sound back to apps left silent (not the ones muted by hand or on the screen now), each read back; fix without arguments always checks this")
    "frozen_in_background" -> tr("用 Shizuku 加电池白名单和后台运行许可，并说明厂商后台管理里要用户手动打开的项", "grant the battery exemption and background running via Shizuku, and explain what the user must allow by hand in the vendor's background management")
    "no_privilege" -> tr("Shizuku 未启动/未授权：给出用户步骤，--open 在主屏打开 Shizuku", "Shizuku not running / not granted: give the user the steps; --open opens Shizuku on the main screen")
    "no_accessibility" -> tr("通过 Shizuku 打开 Agent-VScreen 的无障碍服务（改系统设置）", "turn on Agent-VScreen's accessibility service via Shizuku (changes system settings)")
    "app_owned_display" -> tr("重新连接并释放旧副屏，下次 launch 用 Shizuku 副屏", "reconnect and release the old screen; the next launch uses a Shizuku screen")
    "no_display_focus" -> tr("系统版本限制（需 Android 14+），无法修复", "system version limit (needs Android 14+), cannot be fixed")
    "accessibility_cannot_see_display" -> tr("重启无障碍服务后重新检查", "restart the accessibility service and check again")
    "a11y_not_ready" -> tr("等待并重新检查控件树", "wait and check the widget tree again")
    "background_network" -> tr("通过 Shizuku 把 Agent-VScreen 加入电池优化/后台运行/后台数据白名单（改系统设置）；没有 Shizuku 时给出手动步骤", "exempt Agent-VScreen from battery optimization / background limits / background data limits via Shizuku (changes system settings); without Shizuku, give manual steps")
    else -> null
}



// ── status / state ────────────────────────────────────────────────────

internal fun VScreenOffloadHandler.status(sid: String?, args: OffloadArgs): NativeOffloadResult {
    // After an app restart the screen may still live in the Shizuku service: adopt it now.
    if (sid != null && BackgroundOperation.state.value.let { it.enabled && it.displayId == null && !it.needsExplicitReconnect }) {
        com.kilon.agentvscreen.host.quiet("VScreenScreenCommands:55") { BackgroundOperation.acquire(sid, create = false) }
    }
    val t0 = SystemClock.elapsedRealtime()
    // 2.0.0-beta2: do not block `status` for 1.5 s waiting for accessibility to bind; Readiness.no_accessibility
    // already notes that binding may still be in progress right after start, and waiting penalised every cold wake
    // and every phone with accessibility off.
    val a11y = a11yLive(0)
    if (a11y && !BackgroundOperation.state.value.accessibilityConnected) BackgroundOperation.refreshStatus()
    val s = BackgroundOperation.state.value
    val d = BackgroundOperation.previewDisplayFor(sid ?: "")
    val idleAt = BackgroundOperation.idleReleaseAt
    // 1.3: the one yes/no an agent (above all a timed one) reads first; the same rules as ping and the schedule check.
    val me = Call.current()?.tokenId ?: com.kilon.agentvscreen.api.Agents.ofSession(sid ?: VScreenOffloadHandler.DEFAULT_SESSION)
    val readiness = com.kilon.agentvscreen.host.Readiness.now(context)
    val data = JSONObject()
        .put("ready", com.kilon.agentvscreen.host.Readiness.ready(readiness))
        .put("readiness", com.kilon.agentvscreen.host.Readiness.json(readiness))
        .put("app_version", com.kilon.agentvscreen.BuildConfig.VERSION_NAME)
        .put("your_note_version", com.kilon.agentvscreen.api.AgentLedger.ackVersion(me).let { if (it <= 0) JSONObject.NULL else com.kilon.agentvscreen.api.AgentNews.nameOf(it) })
        .put("schedules", JSONArray(com.kilon.agentvscreen.host.Wake.list(context).filter { it.agentId == me }.map { it.json() }))
        .put("enabled", s.enabled)
        .put("connection", s.connection.name)
        .put("connection_error", s.connectionError ?: JSONObject.NULL)
        .put("needs_explicit_reconnect", s.needsExplicitReconnect)
        .put("display_id", s.displayId ?: JSONObject.NULL)
        .put("generation", s.generation)
        .put("owned_by_this_session", s.ownerSessionId != null && s.ownerSessionId == sid)
        .put("privilege", s.privilege.name)
        .put("needs_adb", false)
        .put("accessibility_connected", a11y)
        .put("host", com.kilon.agentvscreen.host.Liveness.json()
            .put("http_error", com.kilon.agentvscreen.host.HostService.server?.lastError ?: JSONObject.NULL)
            .put("port", com.kilon.agentvscreen.host.HostService.server?.boundPort ?: 0)
            .put("port_note", com.kilon.agentvscreen.host.HostService.server?.portNote ?: JSONObject.NULL))
        .put("accessibility_auto", com.kilon.agentvscreen.host.A11yHeal.let { h -> when {
            h.userOff(context) -> tr("用户关掉了，不再自动打开", "turned off by the user, not turned on again automatically")
            h.blocked(context) -> tr("系统拦截写入：要在 系统设置 › 无障碍 里手动打开", "the system blocks the write: turn it on by hand in Settings › Accessibility")
            h.wanted(context) -> tr("断开时自动重新打开（每分钟最多一次）", "turned on again automatically when it drops (at most once a minute)")
            else -> tr("没开过（App 首页一键配置）", "never turned on (Set up the rest on the app's home page)")
        } })
        .put("user_stopped", BackgroundOperation.state.value.userStopped)
        // 1.4.0-beta6 (test: an app with the user was read as "the user pressed Stop AI"): three different states,
        // each with its own way back. with_user = apps the user has on the main screen now (handback event ends it).
        .put("with_user", org.json.JSONArray(com.kilon.agentvscreen.core.VScreenOwnership.given.value.values.map { g ->
            JSONObject().put("app", g.pkg).put("label", com.kilon.agentvscreen.core.VScreenOwnership.label(context, g.pkg)).put("since", g.at)
                .put("note", tr("这个应用在用户手上（主屏），不是“停止 AI”：等 handback 事件（vscreen events --wait），或 launch 别的应用", "This app is with the user (main screen); it is not Stop AI: wait for the handback event (vscreen events --wait), or launch another app"))
        }))
        .also { o -> if (BackgroundOperation.state.value.userStopped) o.put("user_stopped_note", BackgroundOperation.userStoppedMessage()) }
        .put("window_hidden_by_user", s.windowHidden)
        .put("remote", when (com.kilon.agentvscreen.api.ApiConfig.tailnetMode) {
            com.kilon.agentvscreen.host.Tailnet.Mode.BUILTIN -> com.kilon.agentvscreen.host.Tailnet.json(context)
            com.kilon.agentvscreen.host.Tailnet.Mode.SYSTEM -> JSONObject().put("mode", "system").put("ip", com.kilon.agentvscreen.host.HostService.server?.tailAddress ?: JSONObject.NULL)
            else -> JSONObject().put("mode", "off")
        })
        .also { o -> d?.let { o.put("accessibility_sees_display", BackgroundOperation.nodeRoots(it.displayId).isNotEmpty()) } }
        .put("user_takeover", s.takeoverSessionId != null)
        .put("screen", "${s.screenWidth}x${s.screenHeight}@${s.screenDpi}dpi")
        .put("ocr_engine", VScreenOcr.engineInfo())
        .put("idle", s.idle)
        .put("power_saving", s.powerSaving)
        .put("background_network", com.kilon.agentvscreen.core.VScreenPower.check(context).json())
        .put("app_rules", JSONObject().also { o -> VScreenStore.rules.value.forEach { (k, v) -> o.put(k, v.key) } })
        .put("keeps", JSONArray(VScreenRules.list().filter { it.spec.agentId == me }.map { it.json() }))
        .put("idle_release_in_s", if (idleAt > 0) ((idleAt - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0) else JSONObject.NULL)
        .also { o -> BackgroundOperation.hostRestartNote?.let { o.put("host_restarted", it) } }
        .put("version", JSONObject().put("app", com.kilon.agentvscreen.BuildConfig.VERSION_NAME).put("code", com.kilon.agentvscreen.BuildConfig.VERSION_CODE)
            .put("build", com.kilon.agentvscreen.BuildConfig.VERSION_NAME).put("protocol", VScreenDisplayService.PROTOCOL)
            .put("service_connected", VScreenRemoteDisplay.isConnected())
            .put("service_tasks_api", runCatching { VScreenRemoteDisplay.listTasksIfConnected(0) != null }.getOrDefault(false)))
    data.put("screen_off", com.kilon.agentvscreen.core.VScreenAwake.json(d))
    if (d != null) {
        data.put("recording", com.kilon.agentvscreen.core.VScreenRecorder.status(d.displayId))
        data.put("display_flags", JSONArray(VScreenDisplayService.flagNames(d.flags)))
        data.put("ime_guard", JSONObject()
            .put("keeps_main_focus", VScreenDisplayService.keepsMainFocus(d.flags))
            .put("focus_restored", BackgroundOperation.focusRestores)
            .put("restore_failed", BackgroundOperation.focusRestoreFailures)
            .put("note", if (VScreenDisplayService.keepsMainFocus(d.flags)) tr("副屏不抢主屏焦点（STEAL_TOP_FOCUS_DISABLED），键盘只跟着用户的主屏", "the screen does not steal the main screen's focus (STEAL_TOP_FOCUS_DISABLED); the keyboard stays with the user's screen")
                else tr("本机没接受“不抢焦点”标志：每个副屏动作后若焦点被抢走，会立刻还给主屏（focus_restored 计数）", "this phone ignores the no-focus-steal flag: after each screen action, stolen focus is given straight back to the main screen (counted in focus_restored)")))
            .put("display_has_own_focus", d.ownFocus)
            .put("shell_owned", d.shellOwned)
        val act = VScreenShell.topActivity(d.displayId)
        act?.let { lastActivity[d.displayId] = it }
        data.put("current_app", act?.substringBefore('/') ?: JSONObject.NULL)
            .put("current_activity", act ?: JSONObject.NULL)
            .put("resume_hint", if (act != null)
                tr("副屏仍保留着 ${act.substringBefore('/')} 的页面：act 看一眼就能接着做，不必重新 launch", "The screen still holds ${act.substringBefore('/')}'s page: look with act and carry on, no need to launch again")
            else tr("副屏还在但上面没有应用：用 launch 打开", "The screen exists but has no app: open one with launch"))
    } else if (s.displayId == null) {
        data.put("resume_hint", tr("当前没有副屏，这是正常的：直接 launch <应用名或包名> 就会自动建屏。不需要 adb 或无线调试，不要去配置它们", "There is no screen right now, which is normal: launch <app name or package> creates one. No adb or wireless debugging needed; don't set them up"))
    } else if (s.ownerSessionId != sid) {
        data.put("resume_hint", if (s.idle || s.ownerSessionId == null || !BackgroundOperation.isTaskActive(s.ownerSessionId))
            tr("副屏 ${s.displayId} 是上一轮留下的（空闲中）：act / launch 会直接接手它，页面保留", "Screen ${s.displayId} is left from the last turn (idle): act / launch take it over with the page kept")
        else tr("副屏 ${s.displayId} 正被另一个会话的任务使用，等它结束后再操作", "Screen ${s.displayId} is in use by another session's task; wait until it ends"))
    }
    data.put("capabilities", capabilities(d))
    runCatching {
        val dv = Vendor.device
        data.put("device", JSONObject().put("vendor", dv.vendor).put("brand", dv.brand).put("model", dv.model)
            .put("os", dv.os).put("sdk", dv.sdk).put("tips", JSONArray(Vendor.current.hints())))
    }
    val (deg, sug) = degraded(d)
    data.put("degraded", deg).put("suggestions", sug)
    if (deg.length() > 0) {
        val fixes = JSONArray()
        for (i in 0 until deg.length()) {
            val code = deg.getString(i)
            fixes.put(JSONObject().put("code", code).put("command", "vscreen fix $code")
                .put("auto", code in AUTO_FIXABLE).put("what", fixWhat(code) ?: tr("说明原因和用户要做的步骤", "explain the cause and the user's steps")))
        }
        data.put("fixes", fixes).put("fix_hint", tr("vscreen fix（不带参数）会依次处理全部问题；会改系统设置的步骤先征得用户同意", "vscreen fix (no arguments) handles every problem in turn; ask the user before steps that change system settings"))
    }
    data.put("took_ms", SystemClock.elapsedRealtime() - t0)
    return ok(args, data)
}

/** Accessibility as it is right now (not the cached status), waiting up to [waitMs] for it to bind. */
internal fun VScreenOffloadHandler.a11yLive(waitMs: Long = 0): Boolean {
    val deadline = System.nanoTime() + waitMs * 1_000_000L
    while (true) {
        if (VScreenAccessibilityService.getInstance() != null) return true
        if (System.nanoTime() >= deadline) return false
        Thread.sleep(150)
    }
}

/** Codes and texts of everything not right now (Readiness.full: the one health model), as status and fix give them. */
internal fun VScreenOffloadHandler.degraded(d: VScreenDisplay?): Pair<JSONArray, JSONArray> {
    a11yLive(0)
    val items = com.kilon.agentvscreen.host.Readiness.full(context, d)
    return JSONArray(items.map { it.code }) to JSONArray(items.map { it.text })
}

internal fun VScreenOffloadHandler.fix(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val what = args.positional.getOrNull(0) ?: "auto"
    val codes: List<String> = if (what == "auto") {
        val (deg, _) = degraded(BackgroundOperation.previewDisplayFor(sid ?: ""))
        // 1.4.0-beta13 (rainbow: fix was too weak): also the leftovers status can't see cheaply (apps left silent).
        (0 until deg.length()).map { deg.getString(it) } + "silent_apps"
    } else listOf(when (what) { "accessibility", "a11y" -> "no_accessibility"; "shizuku", "privilege" -> "no_privilege"
        "connect", "reconnect", "connection" -> "not_connected"; "sound", "mute", "audio" -> "silent_apps"
        "tailscale", "remote" -> "remote_down"; "foreground" -> "not_foreground_service"; else -> what })
    val tried = codes.map { code -> code to runCatching { fixOne(sid, code, args) }.getOrElse { false to tr("修复时出错：${it.message}", "Error while fixing: ${it.message}") } }
    BackgroundOperation.refreshStatus()
    val (deg, sug) = degraded(BackgroundOperation.previewDisplayFor(sid ?: ""))
    // 1.4.0-beta13: "fixed" is what the health check says afterwards, not what the attempt claimed.
    val still = (0 until deg.length()).map { deg.getString(it) }.toSet()
    val results = JSONArray()
    var left = 0
    for ((code, r) in tried) {
        val fixed = r.first && code !in still
        if (!fixed) left++
        results.put(JSONObject().put("code", code).put("fixed", fixed).put("message", r.second))
    }
    val quiet = tried.singleOrNull()?.let { it.first == "silent_apps" && it.second.first && what == "auto" } == true
    return ok(args, JSONObject()
        .put("checked", JSONArray(codes))
        .put("results", results)
        .put("degraded_now", deg).put("suggestions", sug)
        .put("message", when {
            quiet -> tr("没有发现问题", "no problems found")
            left == 0 && deg.length() == 0 -> tr("全部正常了", "all fine now")
            else -> tr("还有 ${maxOf(left, deg.length())} 项没解决：按每条 message 和 suggestions 请用户处理", "${maxOf(left, deg.length())} items remain: ask the user to handle them per each message and suggestions")
        }))
}

/** One repair attempt: (fixed, what happened / what the user must do). */
internal fun VScreenOffloadHandler.fixOne(sid: String?, code: String, args: OffloadArgs): Pair<Boolean, String> {
    val s = BackgroundOperation.state.value
    return when (code) {
        "not_connected" -> {
            val r = BackgroundOperation.connect()
            if (r.connection.name == "READY") true to tr("已重新连接", "reconnected") else false to tr("重连失败：${r.connectionError ?: r.connection.name}", "reconnect failed: ${r.connectionError ?: r.connection.name}")
        }
        "no_privilege" -> {
            val steps = when (s.privilege.name) {
                "NOT_INSTALLED" -> tr("手机上没有安装 Shizuku：请用户安装 Shizuku，用无线调试启动后，打开 Agent-VScreen 点“授权 Shizuku”。", "Shizuku is not installed: ask the user to install Shizuku, start it with wireless debugging, then open Agent-VScreen and tap \"Grant Shizuku\".")
                "NOT_RUNNING" -> tr("Shizuku 服务没在运行（重启手机后会停）：请用户打开 Shizuku，用无线调试点“启动”，再回来执行 fix not_connected。", "The Shizuku service is not running (it stops after a reboot): ask the user to open Shizuku and tap Start (wireless debugging), then run fix not_connected.")
                "NOT_AUTHORIZED" -> tr("Agent-VScreen 还没获得 Shizuku 授权：请用户打开 Agent-VScreen 点“授权 Shizuku”并允许。", "Agent-VScreen has no Shizuku permission yet: ask the user to open Agent-VScreen, tap \"Grant Shizuku\" and allow.")
                else -> tr("Shizuku 已可用", "Shizuku is available")
            }
            if (s.privilege.name == "READY") return true to steps
            var opened = ""
            if (args.hasFlag("open")) {
                val pm = context.packageManager
                val intent = listOf("moe.shizuku.privileged.api", "rikka.shizuku").firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
                opened = if (intent != null) {
                    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .fold({ tr("（已在主屏打开 Shizuku）", " (opened Shizuku on the main screen)") }, { tr("（打开 Shizuku 失败：${it.message}）", " (opening Shizuku failed: ${it.message})") })
                } else tr("（没找到 Shizuku 应用）", " (Shizuku app not found)")
            }
            false to steps + opened
        }
        "no_accessibility" -> {
            if (a11yLive()) return true to tr("无障碍服务已连接", "the accessibility service is connected")
            com.kilon.agentvscreen.host.Heal.accessibility(context)
        }
        "accessibility_cannot_see_display", "a11y_not_ready" -> {
            val d = BackgroundOperation.previewDisplayFor(sid ?: "") ?: return false to tr("当前没有副屏", "there is no screen right now")
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < 3_000) {
                if (BackgroundOperation.nodeRoots(d.displayId).isNotEmpty()) return true to tr("无障碍已能看到副屏控件", "accessibility can see the screen's widgets")
                Thread.sleep(300)
            }
            if (code == "accessibility_cannot_see_display" && s.privilege.name == "READY") {
                com.kilon.agentvscreen.host.Heal.accessibility(context, restart = true)
                if (VScreenAccessibilityService.getInstance() != null) {
                    val t1 = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - t1 < 3_000) {
                        if (BackgroundOperation.nodeRoots(d.displayId).isNotEmpty()) return true to tr("已重启无障碍服务，现在能看到副屏控件", "Restarted the accessibility service; the screen's widgets are visible now")
                        Thread.sleep(300)
                    }
                }
            }
            false to tr("无障碍仍看不到副屏控件：这个页面可能不提供控件。可继续用 OCR，或 release 后重新 launch", "Accessibility still cannot see the screen's widgets: this page may not expose any. Carry on with OCR, or release and launch again")
        }
        "app_owned_display" -> {
            val r = BackgroundOperation.connect()
            if (r.connection.name != "READY") return false to tr("重连失败：${r.connectionError}", "reconnect failed: ${r.connectionError}")
            if (sid != null) BackgroundOperation.releaseNow(sid)
            true to tr("已重新连接并释放旧副屏；下一次 launch 会用 Shizuku 副屏重建（页面不保留）", "Reconnected and released the old screen; the next launch rebuilds it as a Shizuku screen (page not kept)")
        }
        "background_network" -> {
            val pw = com.kilon.agentvscreen.core.VScreenPower
            if (pw.check(context).ok) return true to tr("后台网络不受限制", "background network is not restricted")
            if (s.privilege.name != "READY") return false to tr("没有 Shizuku，无法自动设置。请用户手动：${pw.manualSteps()}", "No Shizuku, so this can't be set automatically. Ask the user to do it by hand: ${pw.manualSteps()}")
            val (ok, msg) = com.kilon.agentvscreen.host.Heal.power(context)
            if (ok) true to msg else false to tr("$msg。其余请用户手动：${pw.manualSteps()}", "$msg. The rest the user does by hand: ${pw.manualSteps()}")
        }
        "no_display_focus" -> false to tr("副屏没有独立输入焦点是系统限制（需要 Android 14+），无法修复；back 无效时直接点页面上的返回按钮", "The screen lacking its own input focus is a system limit (needs Android 14+) and can't be fixed; if back does nothing, tap the page's back button")
        "not_foreground_service" -> com.kilon.agentvscreen.host.Heal.foreground(context)
        "remote_down" -> com.kilon.agentvscreen.host.Heal.remote(context)
        "silent_apps" -> com.kilon.agentvscreen.host.Heal.sound()
        "service_off" -> false to tr("用户关掉了服务（用户的选择）：请用户在 App 右上角的电源键里打开", "The user turned the service off (their choice): ask them to turn it on with the power button at the top of the app")
        "user_stopped" -> false to tr("用户按了“停止 AI”，只有用户能解除", "The user pressed \"Stop AI\"; only the user can lift it")
        "frozen_in_background" -> {
            val (ok, msg) = com.kilon.agentvscreen.host.Heal.power(context)
            (ok && !com.kilon.agentvscreen.host.Liveness.frozenUnresolved()) to tr("系统白名单：$msg${if (ok) "（已全部生效）" else ""}。厂商自己的后台管理只能用户手动设：请用户打开 Agent-VScreen，点“后台运行受限”的“去设置”，允许自启动、关联启动和后台活动", "System exemptions: $msg${if (ok) " (all in effect)" else ""}. The vendor's own background management can only be set by the user: ask the user to open Agent-VScreen, tap Settings on \"Background running restricted\", and allow auto-start, start by other apps and background activity")
        }
        else -> false to run { val keys = FIX_KEYS.joinToString(tr("、", ", ")); tr("不认识的问题代码：$code（可用：$keys）", "Unknown problem code: $code (available: $keys)") }
    }
}

// ── apps / launch ─────────────────────────────────────────────────────

internal fun VScreenOffloadHandler.apps(args: OffloadArgs): NativeOffloadResult {
    val filter = args.positional.getOrNull(0)?.lowercase()
    val arr = JSONArray()
    // 1.3 beta2: the same matching and order as launch uses (launch takes the first one listed here).
    com.kilon.agentvscreen.core.VScreenKnowledge.findApps(filter).let { l -> if (filter == null) l.sortedBy { it.first } else l }
        .take(200)
        .forEach { (label, pkg) -> arr.put(JSONObject().put("name", label).put("package", pkg)) }
    return ok(args, JSONObject().put("apps", arr))
}

/** Which mechanism each capability is using right now, for `status`. */
internal fun VScreenOffloadHandler.capabilities(d: VScreenDisplay?): JSONObject {
    val s = BackgroundOperation.state.value
    val o = JSONObject()
    o.put("screen", when { d == null -> tr("无副屏", "no screen"); d.shellOwned && d.trusted -> tr("Shizuku 服务创建的受信任副屏", "trusted screen created by the Shizuku service"); d.shellOwned -> tr("Shizuku 服务创建（未受信任）", "created by the Shizuku service (untrusted)"); else -> tr("应用自建副屏（受限）", "app's own screen (limited)") })
    o.put("tap_swipe", tr("副屏服务注入（触摸事件直接进副屏，不经过 shell 命令）", "injected by the display service (touch events go straight to the screen, no shell command)"))
    o.put("keys", if (d?.ownFocus == true) tr("带 displayId 注入（副屏有独立焦点）", "injected with displayId (the screen has its own focus)") else tr("带 displayId 注入，失败时 input -d", "injected with displayId, input -d as fallback"))
    o.put("drag", if (d?.shellOwned == true) tr("副屏服务 POINTER（drag 多点、按住）", "screen service POINTER (multi-point drag, hold)") else tr("不可用（需要 Shizuku 副屏服务）", "unavailable (needs the Shizuku screen service)"))
    o.put("ocr", VScreenOcr.engineInfo())
    o.put("with_user", org.json.JSONArray(com.kilon.agentvscreen.core.VScreenOwnership.given.value.values.map { g ->
        JSONObject().put("app", g.pkg).put("since", g.at).put("why", g.why) }))
    o.put("accessibility", if (a11yLive()) tr("已连接（控件树可用）", "connected (widget tree available)") else tr("未连接（只用 OCR/视觉）", "not connected (OCR / vision only)"))
    o.put("vision", tr("由调用方的模型看截图（本应用不调用任何模型）", "the caller's model looks at screenshots (this app calls no model)"))
    o.put("orientation", if (d == null) "-" else run { val src = d.geometryJson().optString("turn_source"); tr("旋转 ${d.rotation * 90}°，画面转正 ${d.bufferTurn}°（$src）", "rotated ${d.rotation * 90}°, picture turned upright ${d.bufferTurn}° ($src)") })
    o.put("clipboard", tr("非 ASCII 文字在无障碍写不进时走剪贴板，粘贴后恢复原剪贴板；系统不让读取时清空", "non-ASCII text goes through the clipboard when accessibility can't write it; the clipboard is restored after pasting, or cleared if the system won't let it be read"))
    o.put("stop", tr("默认只关副屏里的任务；--force 才 force-stop 整个应用", "by default only the task on the screen is closed; --force force-stops the whole app"))
    return o
}
