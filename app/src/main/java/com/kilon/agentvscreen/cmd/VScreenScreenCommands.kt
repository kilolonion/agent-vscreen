package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.tr

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.kilon.agentvscreen.host.VScreenAccessibilityService
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.ScreenSig
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenDisplayService
import com.kilon.agentvscreen.core.VScreenRemoteDisplay
import com.kilon.agentvscreen.core.VScreenDownscale
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenStore
import com.kilon.agentvscreen.core.Vendor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt
import com.kilon.agentvscreen.cmd.VScreenOffloadHandler.Companion.lastActivity

// [vscreen] vscreen — 副屏与诊断：status / fix / apps.
// Part of VScreenOffloadHandler (see that file for the command table and shared state).

private val AUTO_FIXABLE = setOf("background_network", "not_connected", "no_accessibility", "app_owned_display", "a11y_not_ready", "accessibility_cannot_see_display")

private val FIX_WHAT = mapOf(
    "not_connected" to tr("重新连接特权通道", "reconnect the privileged channel"),
    "not_foreground_service" to tr("说明：请用户打开一次 Agent-VScreen，服务会补成前台服务", "explain: ask the user to open Agent-VScreen once; the service becomes a foreground service again"),
    "frozen_in_background" to tr("用 Shizuku 加电池白名单和后台运行许可，并说明厂商后台管理里要用户手动打开的项", "grant the battery exemption and background running via Shizuku, and explain what the user must allow by hand in the vendor's background management"),
    "no_privilege" to tr("Shizuku 未启动/未授权：给出用户步骤，--open 在主屏打开 Shizuku", "Shizuku not running / not granted: give the user the steps; --open opens Shizuku on the main screen"),
    "no_accessibility" to tr("通过 Shizuku 打开 Agent-VScreen 的无障碍服务（改系统设置）", "turn on Agent-VScreen's accessibility service via Shizuku (changes system settings)"),
    "app_owned_display" to tr("重新连接并释放旧副屏，下次 launch 用 Shizuku 副屏", "reconnect and release the old screen; the next launch uses a Shizuku screen"),
    "no_display_focus" to tr("系统版本限制（需 Android 14+），无法修复", "system version limit (needs Android 14+), cannot be fixed"),
    "accessibility_cannot_see_display" to tr("重启无障碍服务后重新检查", "restart the accessibility service and check again"),
    "a11y_not_ready" to tr("等待并重新检查控件树", "wait and check the widget tree again"),
    "background_network" to tr("通过 Shizuku 把 Agent-VScreen 加入电池优化/后台运行/后台数据白名单（改系统设置）；没有 Shizuku 时给出手动步骤", "exempt Agent-VScreen from battery optimization / background limits / background data limits via Shizuku (changes system settings); without Shizuku, give manual steps"),
)

private val A11Y_SERVICE = "com.kilon.agentvscreen.host.VScreenAccessibilityService"


// ── status / state ────────────────────────────────────────────────────

internal fun VScreenOffloadHandler.status(sid: String?, args: OffloadArgs): NativeOffloadResult {
    // After a Minis restart the screen may still live in the Shizuku service: adopt it now.
    if (sid != null && BackgroundOperation.state.value.let { it.enabled && it.displayId == null && !it.needsExplicitReconnect }) {
        com.kilon.agentvscreen.host.quiet("VScreenScreenCommands:55") { BackgroundOperation.acquire(sid, create = false) }
    }
    val t0 = SystemClock.elapsedRealtime()
    // Right after the app starts the system may still be binding the accessibility service: wait for it once
    // (not once per field: with accessibility off that cost seconds on every status).
    val a11y = a11yLive(if (BackgroundOperation.state.value.accessibilityConnected) 0 else 1_500)
    if (a11y && !BackgroundOperation.state.value.accessibilityConnected) BackgroundOperation.refreshStatus()
    val s = BackgroundOperation.state.value
    val d = BackgroundOperation.previewDisplayFor(sid ?: "")
    val idleAt = BackgroundOperation.idleReleaseAt
    val data = JSONObject()
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
            .put("http_error", com.kilon.agentvscreen.host.HostService.server?.lastError ?: JSONObject.NULL))
        .put("accessibility_auto", com.kilon.agentvscreen.host.A11yHeal.let { h -> when {
            h.userOff(context) -> tr("用户关掉了，不再自动打开", "turned off by the user, not turned on again automatically")
            h.blocked(context) -> tr("系统拦截写入：要在 系统设置 › 无障碍 里手动打开", "the system blocks the write: turn it on by hand in Settings › Accessibility")
            h.wanted(context) -> tr("断开时自动重新打开（每分钟最多一次）", "turned on again automatically when it drops (at most once a minute)")
            else -> tr("没开过（App 首页一键配置）", "never turned on (Set up the rest on the app's home page)")
        } })
        .put("user_stopped", BackgroundOperation.state.value.userStopped)
        .also { o -> if (BackgroundOperation.state.value.userStopped) o.put("user_stopped_note", BackgroundOperation.userStoppedMessage()) }
        .put("window_hidden_by_user", s.windowHidden)
        .put("remote", when (com.kilon.agentvscreen.api.ApiConfig.tailnetMode) {
            com.kilon.agentvscreen.host.Tailnet.Mode.BUILTIN -> com.kilon.agentvscreen.host.Tailnet.status.value.json()
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
        .put("keeps", JSONArray(VScreenRules.list().map { it.json() }))
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
                .put("auto", code in AUTO_FIXABLE).put("what", FIX_WHAT[code] ?: tr("说明原因和用户要做的步骤", "explain the cause and the user's steps")))
        }
        data.put("fixes", fixes).put("fix_hint", tr("vscreen fix（不带参数）会依次处理全部问题；会改系统设置的步骤先征得用户同意", "vscreen fix (no arguments) handles every problem in turn; ask the user before steps that change system settings"))
    }
    data.put("took_ms", SystemClock.elapsedRealtime() - t0)
    return ok(args, data)
}

/** Accessibility as it is right now (not the cached status), waiting up to [waitMs] for it to bind. */
internal fun VScreenOffloadHandler.a11yLive(waitMs: Long = 0): Boolean {
    val t0 = SystemClock.elapsedRealtime()
    while (true) {
        if (VScreenAccessibilityService.getInstance() != null) return true
        if (SystemClock.elapsedRealtime() - t0 >= waitMs) return false
        Thread.sleep(150)
    }
}

internal fun VScreenOffloadHandler.degraded(d: VScreenDisplay?): Pair<JSONArray, JSONArray> {
    val s = BackgroundOperation.state.value
    val deg = JSONArray(); val sug = JSONArray()
    val live = com.kilon.agentvscreen.host.Liveness
    if (com.kilon.agentvscreen.host.HostService.isAlive && !live.foreground) {
        deg.put("not_foreground_service")
        sug.put(run { val why = live.foregroundError ?: tr("原因未知", "unknown reason"); tr("Agent-VScreen 的服务没能成为前台服务（$why）：它在后台会被冻结，命令会卡住直到用户打开它。请用户打开一次 Agent-VScreen（会自动补上）。", "Agent-VScreen's service could not become a foreground service ($why): it gets frozen in the background and commands hang until the user opens it. Ask the user to open Agent-VScreen once (that fixes it).") })
    } else if (live.frozenUnresolved()) {
        deg.put("frozen_in_background")
        sug.put(tr("亮屏时系统在后台冻结过 Agent-VScreen ${live.screenOnFreezesSinceVisit} 次（最近一次 ${live.lastScreenOnFreezeMs / 1000} 秒），那段时间的命令会一直等。", "With the screen on, the system froze Agent-VScreen in the background ${live.screenOnFreezesSinceVisit} times (last time ${live.lastScreenOnFreezeMs / 1000} s); commands wait during that time.") +
            tr("请用户打开 Agent-VScreen，点首页“后台运行受限”的“去设置”，把它设为允许自启动、关联启动和后台活动（荣耀/华为：应用启动管理 › 手动管理）。", "Ask the user to open Agent-VScreen, tap Settings on \"Background running restricted\" on the home page, and allow auto-start, start by other apps and background activity (Honor/Huawei: App launch › Manage manually)."))
    }
    if (s.privilege.name != "READY") {
        deg.put("no_privilege")
        sug.put(tr("Shizuku 不可用（${s.privilege.name}）：后台操作无法建屏和注入输入。fix no_privilege 会告诉用户怎么启动/授权。", "Shizuku unavailable (${s.privilege.name}): background operation cannot create a screen or inject input. fix no_privilege tells the user how to start / grant it."))
    } else if (s.enabled && (s.connection.name == "ERROR" || s.needsExplicitReconnect)) {
        deg.put("not_connected")
        sug.put(run { val why = s.connectionError ?: tr("需要重新连接", "needs reconnecting"); tr("后台连接已断开（$why）：fix not_connected 会重新连接。", "The background connection dropped ($why): fix not_connected reconnects.") })
    }
    if (!a11yLive()) {
        deg.put("no_accessibility")
        sug.put(tr("无障碍服务当前未连接（Agent-VScreen 刚启动时系统可能还在绑定，几秒后 status 会更新）：元素列表只有 OCR 文字（图标按钮没有文字、中文输入只能走剪贴板粘贴）。需要更稳时，请用户在 系统设置 › 无障碍 › 已安装的应用 › Agent-VScreen 打开。", "The accessibility service is not connected (right after Agent-VScreen starts the system may still be binding it; status updates in a few seconds): element lists have OCR text only (icon buttons have no text, non-ASCII typing goes through clipboard paste). For more reliability, ask the user to turn it on in Settings › Accessibility › Installed apps › Agent-VScreen."))
    } else if (d != null && BackgroundOperation.nodeRoots(d.displayId).isEmpty() && VScreenShell.topActivity(d.displayId) != null) {
        deg.put("accessibility_cannot_see_display")
        sug.put(tr("无障碍已开启，但系统没有把副屏窗口交给它：控件树为空，元素列表只有 OCR 文字和截图。", "Accessibility is on, but the system does not give it the screen's windows: the widget tree is empty and element lists have only OCR text and screenshots."))
    }
    val pw = com.kilon.agentvscreen.core.VScreenPower.check(context)
    if (!pw.ok) {
        deg.put("background_network")
        sug.put(run { val p = pw.problems().joinToString(tr("、", ", ")); tr("Agent-VScreen 切到后台后系统可能切断它的网络（$p），模型请求会报 connection abort。", "The system may cut Agent-VScreen's network in the background ($p); model requests may fail with connection abort. ") } +
            (if (s.privilege.name == "READY") tr("fix background_network 可用 Shizuku 自动加白名单（改系统设置，先征得用户同意）。", "fix background_network can add the exemptions via Shizuku (changes system settings; ask the user first).")
             else tr("请用户手动设置：${com.kilon.agentvscreen.core.VScreenPower.manualSteps()}", "Ask the user to set by hand: ${com.kilon.agentvscreen.core.VScreenPower.manualSteps()}")))
    }
    if (d != null && !d.ownFocus) {
        deg.put("no_display_focus")
        sug.put(tr("副屏没有独立输入焦点（需要 Android 14+）：key back/enter 可能送不到；无效时直接 tap 页面上的返回/搜索按钮。", "The screen has no input focus of its own (needs Android 14+): key back/enter may not arrive; if they do nothing, tap the page's back / search buttons."))
    }
    if (d != null && !d.shellOwned) {
        deg.put("app_owned_display")
        sug.put(tr("Shizuku 副屏服务不可用，只能用 App 自建副屏：系统可能把应用开到主屏。请用户在设置里重新连接后台操作。", "The Shizuku screen service is unavailable, so only the app's own screen can be used: the system may open apps on the main screen. Ask the user to reconnect background operation in settings."))
    }
    return deg to sug
}

internal fun VScreenOffloadHandler.fix(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val what = args.positional.getOrNull(0) ?: "auto"
    val codes: List<String> = if (what == "auto") {
        val (deg, _) = degraded(BackgroundOperation.previewDisplayFor(sid ?: ""))
        (0 until deg.length()).map { deg.getString(it) }
    } else listOf(when (what) { "accessibility", "a11y" -> "no_accessibility"; "shizuku", "privilege" -> "no_privilege"
        "connect", "reconnect", "connection" -> "not_connected"; else -> what })
    val results = JSONArray()
    for (code in codes) {
        val (fixed, msg) = runCatching { fixOne(sid, code, args) }.getOrElse { false to tr("修复时出错：${it.message}", "Error while fixing: ${it.message}") }
        results.put(JSONObject().put("code", code).put("fixed", fixed).put("message", msg))
    }
    BackgroundOperation.refreshStatus()
    val (deg, sug) = degraded(BackgroundOperation.previewDisplayFor(sid ?: ""))
    return ok(args, JSONObject()
        .put("checked", JSONArray(codes))
        .put("results", results)
        .put("degraded_now", deg).put("suggestions", sug)
        .put("message", if (codes.isEmpty()) tr("没有发现问题", "no problems found") else if (deg.length() == 0) tr("全部正常了", "all fine now") else tr("还有 ${deg.length()} 项没解决，按 suggestions 请用户处理", "${deg.length()} items remain; ask the user to handle them per suggestions")))
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
            if (s.privilege.name != "READY") return false to tr("需要先修好 Shizuku（fix no_privilege），或请用户在 系统设置 › 无障碍 › 已安装的应用 › Agent-VScreen 手动打开", "Fix Shizuku first (fix no_privilege), or ask the user to turn it on in Settings › Accessibility › Installed apps › Agent-VScreen")
            val comp = "${context.packageName}/$A11Y_SERVICE"
            val cur = VScreenShell.run(arrayOf("settings", "get", "secure", "enabled_accessibility_services")).stdout.trim()
                .takeIf { it.isNotEmpty() && it != "null" }
            val list = cur?.split(':')?.filter { it.isNotBlank() }.orEmpty()
            val next = (list.filter { it != comp } + comp).joinToString(":")
            // Re-writing the list (ours removed then added) also re-binds a crashed service.
            val w = VScreenShell.run(arrayOf("settings", "put", "secure", "enabled_accessibility_services", next))
            VScreenShell.run(arrayOf("settings", "put", "secure", "accessibility_enabled", "1"))
            if (w.exitCode != 0) return false to tr("写入系统设置失败（${w.combined.take(120)}）；请用户在 系统设置 › 无障碍 里手动打开 Agent-VScreen", "Writing system settings failed (${w.combined.take(120)}); ask the user to turn on Agent-VScreen in Settings › Accessibility")
            if (a11yLive(4_000)) true to tr("已通过 Shizuku 打开 Agent-VScreen 的无障碍服务", "Turned on Agent-VScreen's accessibility service via Shizuku")
            else false to tr("已写入设置，但服务 4 秒内没有连上：部分系统（荣耀/华为）会拦截，请用户在 系统设置 › 无障碍 里手动打开一次", "The setting was written but the service did not connect within 4 s: some systems (Honor/Huawei) block it; ask the user to turn it on once in Settings › Accessibility")
        }
        "accessibility_cannot_see_display", "a11y_not_ready" -> {
            val d = BackgroundOperation.previewDisplayFor(sid ?: "") ?: return false to tr("当前没有副屏", "there is no screen right now")
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < 3_000) {
                if (BackgroundOperation.nodeRoots(d.displayId).isNotEmpty()) return true to tr("无障碍已能看到副屏控件", "accessibility can see the screen's widgets")
                Thread.sleep(300)
            }
            if (code == "accessibility_cannot_see_display" && s.privilege.name == "READY") {
                val comp = "${context.packageName}/$A11Y_SERVICE"
                val cur = VScreenShell.run(arrayOf("settings", "get", "secure", "enabled_accessibility_services")).stdout.trim()
                val rest = cur.split(':').filter { it.isNotBlank() && it != "null" && it != comp }
                if (rest.isEmpty()) VScreenShell.run(arrayOf("settings", "delete", "secure", "enabled_accessibility_services"))
                else VScreenShell.run(arrayOf("settings", "put", "secure", "enabled_accessibility_services", rest.joinToString(":")))
                Thread.sleep(500)
                VScreenShell.run(arrayOf("settings", "put", "secure", "enabled_accessibility_services", (rest + comp).joinToString(":")))
                if (a11yLive(4_000)) {
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
            val (ok, msg) = pw.whitelist(context)
            if (ok) true to msg else false to tr("$msg。其余请用户手动：${pw.manualSteps()}", "$msg. The rest the user does by hand: ${pw.manualSteps()}")
        }
        "no_display_focus" -> false to tr("副屏没有独立输入焦点是系统限制（需要 Android 14+），无法修复；back 无效时直接点页面上的返回按钮", "The screen lacking its own input focus is a system limit (needs Android 14+) and can't be fixed; if back does nothing, tap the page's back button")
        "not_foreground_service" -> false to tr("要用户打开一次 Agent-VScreen：在前台时服务会自动补成前台服务。之后 status 的 host.foreground_service 应为 true", "The user needs to open Agent-VScreen once: in the foreground the service becomes a foreground service again. Afterwards host.foreground_service in status should be true")
        "frozen_in_background" -> {
            val (ok, msg) = com.kilon.agentvscreen.core.VScreenPower.whitelist(context)
            false to tr("系统白名单：$msg${if (ok) "（已全部生效）" else ""}。厂商自己的后台管理只能用户手动设：请用户打开 Agent-VScreen，点“后台运行受限”的“去设置”，允许自启动、关联启动和后台活动", "System exemptions: $msg${if (ok) " (all in effect)" else ""}. The vendor's own background management can only be set by the user: ask the user to open Agent-VScreen, tap Settings on \"Background running restricted\", and allow auto-start, start by other apps and background activity")
        }
        else -> false to run { val keys = FIX_WHAT.keys.joinToString(tr("、", ", ")); tr("不认识的问题代码：$code（可用：$keys）", "Unknown problem code: $code (available: $keys)") }
    }
}

// ── apps / launch ─────────────────────────────────────────────────────

internal fun VScreenOffloadHandler.apps(args: OffloadArgs): NativeOffloadResult {
    val filter = args.positional.getOrNull(0)?.lowercase()
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val arr = JSONArray()
    pm.queryIntentActivities(intent, 0)
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        .distinctBy { it.second }
        .filter { filter == null || it.first.lowercase().contains(filter) || it.second.lowercase().contains(filter) }
        .sortedBy { it.first }
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
    o.put("accessibility", if (a11yLive()) tr("已连接（控件树可用）", "connected (widget tree available)") else tr("未连接（只用 OCR/视觉）", "not connected (OCR / vision only)"))
    o.put("vision", tr("由调用方的模型看截图（本应用不调用任何模型）", "the caller's model looks at screenshots (this app calls no model)"))
    o.put("orientation", if (d == null) "-" else run { val src = d.geometryJson().optString("turn_source"); tr("旋转 ${d.rotation * 90}°，画面转正 ${d.bufferTurn}°（$src）", "rotated ${d.rotation * 90}°, picture turned upright ${d.bufferTurn}° ($src)") })
    o.put("clipboard", tr("非 ASCII 文字在无障碍写不进时走剪贴板，粘贴后恢复原剪贴板；系统不让读取时清空", "non-ASCII text goes through the clipboard when accessibility can't write it; the clipboard is restored after pasting, or cleared if the system won't let it be read"))
    o.put("stop", tr("默认只关副屏里的任务；--force 才 force-stop 整个应用", "by default only the task on the screen is closed; --force force-stops the whole app"))
    return o
}
