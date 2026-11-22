package com.kilon.agentvscreen.host

import android.content.Context
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.3: can an agent use the phone right now, and if not, why — in one place.
 *
 * Before 1.3 the answer was spread out: `status` had its `degraded` list, the CLI guessed from a 5 s silence, the app's
 * banner read [Liveness], and a command found out by failing somewhere deep down. An agent that runs on a timer (at
 * 07:00, with nobody looking at the phone) needs a plain yes or no it can read before it starts, and the same reasons
 * the user sees. [now] is that answer; `status` (ready / blocking), `/v2/ping` and the scheduled wake-up check all read
 * it, so they cannot disagree.
 *
 * Items are facts the app can see without running anything slow (no shell call): it is cheap enough for every ping.
 * [Item.blocking] = commands that touch the screen will fail until it is fixed; the others make failures likely
 * (the system may freeze the app, remote callers cannot reach it) but do not stop a command by themselves.
 */
object Readiness {
    class Item(val code: String, val blocking: Boolean, val text: String) {
        fun json(): JSONObject = JSONObject().put("code", code).put("blocking", blocking).put("text", text)
    }

    /**
     * 1.4.0-beta2: the one answer to "is it healthy". `status` (ready, degraded, fixes), ping, the scheduled wake-up
     * check, `fix` and the app's banner all read it. Before, `status` had its own degraded() list with its own texts
     * for the same facts, and the remote item only looked at "logged in" while Tailscale could be stuck.
     */
    sealed class Health(val items: List<Item>) {
        class Ready : Health(emptyList())
        class Degraded(items: List<Item>) : Health(items)
        class Blocked(items: List<Item>) : Health(items)
        companion object {
            fun of(items: List<Item>): Health = when { items.any { it.blocking } -> Blocked(items); items.isEmpty() -> Ready(); else -> Degraded(items) }
        }
    }

    /** What [evaluate] looks at; a plain value so the rule table is testable without a phone. */
    data class Inputs(
        val serviceOn: Boolean,
        val serviceAlive: Boolean,
        val foreground: Boolean,
        val privilege: String,
        val connection: String,
        val needsReconnect: Boolean,
        val userStopped: Boolean,
        val frozen: Boolean,
        /** off / builtin / system */
        val remoteMode: String,
        val remoteUp: Boolean,
        /** Why remote is not up, in the user's words (Tailnet's phase line); null = no detail. */
        val remoteWhy: String? = null,
        /** Words for the foreground / frozen problem (Liveness), null = the short default. */
        val liveWhy: String? = null,
        // The deeper facts (null = not looked at: ping and the wake-up check stay cheap; status looks).
        val a11yOn: Boolean? = null,
        val a11ySeesScreen: Boolean? = null,
        val powerProblems: List<String>? = null,
        val ownFocus: Boolean? = null,
        val shellOwned: Boolean? = null,
        /** 2.0.0-beta2: OCR engines that crashed during self-test and are disabled for this app versionCode. */
        val ocrCrashedEngines: Set<String> = emptySet(),
    )

    fun evaluate(i: Inputs): List<Item> = buildList {
        if (!i.serviceOn) add(Item("service_off", true, tr("用户关闭了 Agent-VScreen（用户的选择，不是故障）：请用户在 App 右上角的电源键里打开", "The user turned Agent-VScreen off (their choice, not a fault): ask them to turn it on with the power button at the top of the app")))
        when (i.privilege) {
            // 1.3: with Shizuku there, the next command reconnects by itself: a warning, not a block.
            // 1.4.0-beta4: only a channel that failed is reported. A connection that is merely not open (the service
            // was hidden or stopped, nothing has run yet) is the normal resting state: the next command opens it. It
            // was reported as "down … fix not_connected", and agents asked users to repair a service that was only off.
            "READY" -> if (i.connection == "ERROR")
                add(Item("not_connected", false, tr("副屏服务刚才没有响应，下一条命令会自动重连，不用用户做什么", "The screen service did not answer just now; the next command reconnects by itself, nothing for the user to do")))
            // 1.4.2-beta3: Shizuku or root, whichever the user chose, says why in its own words.
            else -> add(Item("no_privilege", true, Privileged.notReadyHint()))
        }
        if (i.userStopped) add(Item("user_stopped", true, tr("用户按了“停止 AI”，只有用户能解除", "The user pressed \"Stop AI\"; only the user can lift it")))
        if (i.serviceOn && i.serviceAlive && !i.foreground)
            add(Item("not_foreground_service", false, i.liveWhy ?: tr("服务不是前台服务，后台可能被冻结；用户打开一次 App 就会补上", "The service is not a foreground service and may be frozen in the background; opening the app once fixes it")))
        else if (i.frozen)
            add(Item("frozen_in_background", false, i.liveWhy ?: tr("系统最近在后台冻结过 Agent-VScreen：需要用户在系统的后台管理里允许它后台活动", "The system recently froze Agent-VScreen in the background: the user must allow background activity in the system's background management")))
        if (i.remoteMode != "off" && !i.remoteUp)
            add(Item("remote_down", false, tr("其它设备访问打开了，但 Tailscale 现在没连上：其它设备上的 agent 连不进来", "Access from other devices is on but Tailscale is not connected now: agents on other devices cannot get in") +
                (i.remoteWhy?.let { tr("（$it）", " ($it)") } ?: "")))
        if (i.a11yOn == false)
            add(Item("no_accessibility", false, tr("无障碍服务当前未连接（刚启动时系统可能还在绑定，几秒后会更新）：元素列表只有 OCR 文字（图标按钮没有文字、中文输入只能走剪贴板粘贴）。需要更稳时，请用户在 系统设置 › 无障碍 › 已安装的应用 › Agent-VScreen 打开。",
                "The accessibility service is not connected (right after start the system may still be binding it; it updates in a few seconds): element lists have OCR text only (icon buttons have no text, non-ASCII typing goes through clipboard paste). For more reliability, ask the user to turn it on in Settings › Accessibility › Installed apps › Agent-VScreen.")))
        else if (i.a11ySeesScreen == false)
            add(Item("accessibility_cannot_see_display", false, tr("无障碍已开启，但系统没有把副屏窗口交给它：控件树为空，元素列表只有 OCR 文字和截图。", "Accessibility is on, but the system does not give it the screen's windows: the widget tree is empty and element lists have only OCR text and screenshots.")))
        if (!i.powerProblems.isNullOrEmpty()) {
            val p = i.powerProblems.joinToString(tr("、", ", "))
            add(Item("background_network", false, tr("Agent-VScreen 切到后台后系统可能切断它的网络（$p），模型请求会报 connection abort。", "The system may cut Agent-VScreen's network in the background ($p); model requests may fail with connection abort. ") +
                (if (i.privilege == "READY") tr("fix background_network 可用 Shizuku 自动加白名单（改系统设置，先征得用户同意）。", "fix background_network can add the exemptions via Shizuku (changes system settings; ask the user first).")
                 else tr("请用户手动设置：${com.kilon.agentvscreen.core.VScreenPower.manualSteps()}", "Ask the user to set by hand: ${com.kilon.agentvscreen.core.VScreenPower.manualSteps()}"))))
        }
        if (i.ownFocus == false)
            add(Item("no_display_focus", false, tr("副屏没有独立输入焦点（需要 Android 14+）：key back/enter 可能送不到；无效时直接 tap 页面上的返回/搜索按钮。", "The screen has no input focus of its own (needs Android 14+): key back/enter may not arrive; if they do nothing, tap the page's back / search buttons.")))
        if (i.shellOwned == false)
            add(Item("app_owned_display", false, tr("Shizuku 副屏服务不可用，只能用 App 自建副屏：系统可能把应用开到主屏。请用户在设置里重新连接后台操作。", "The Shizuku screen service is unavailable, so only the app's own screen can be used: the system may open apps on the main screen. Ask the user to reconnect background operation in settings.")))
        val mlCrashed = com.kilon.agentvscreen.core.VScreenOcr.ENGINE_MLKIT in i.ocrCrashedEngines
        val pdCrashed = com.kilon.agentvscreen.core.VScreenOcr.ENGINE_PADDLE in i.ocrCrashedEngines
        if (mlCrashed && pdCrashed) {
            add(Item("ocr_unavailable", false, tr("OCR 不可用：PP-OCRv6 与 ML Kit 在自检时均崩溃，本版本已禁用；画面上的文字仅能靠无障碍和截图识别（运行 diag 或升级应用后重试）",
                "OCR is unavailable: both PP-OCRv6 and ML Kit crashed during self-test and are disabled for this version; text on screen relies on accessibility and screenshots only (run diag or update the app to retry)")))
        } else if (mlCrashed) {
            add(Item("ocr_degraded", false, tr("ML Kit 在自检时崩溃，本版本已禁用，改由 PP-OCRv6 接手（运行 diag 或升级应用后重试）",
                "ML Kit crashed during self-test and is disabled for this version; PP-OCRv6 took over (run diag or update the app to retry)")))
        } else if (pdCrashed) {
            add(Item("ocr_degraded", false, tr("PP-OCRv6 在自检时崩溃，本版本已禁用，改由 ML Kit 接手（运行 diag 或升级应用后重试）",
                "PP-OCRv6 crashed during self-test and is disabled for this version; ML Kit took over (run diag or update the app to retry)")))
        }
    }

    fun inputs(ctx: Context): Inputs {
        val s = BackgroundOperation.state.value
        val mode = runCatching { com.kilon.agentvscreen.api.ApiConfig.tailnetMode }.getOrDefault(Tailnet.Mode.OFF)
        // 1.4.0-beta2: built-in Tailscale is up only in its CONNECTED phase (logged in but stuck behind a proxy VPN is not up).
        val phase = if (mode == Tailnet.Mode.BUILTIN) runCatching { Tailnet.phase(ctx) }.getOrNull() else null
        val remoteUp = when (mode) {
            Tailnet.Mode.BUILTIN -> phase == Tailnet.Phase.CONNECTED
            Tailnet.Mode.SYSTEM -> HostService.server?.tailAddress != null
            else -> true
        }
        return Inputs(
            serviceOn = !Prefs.blocked(ctx), serviceAlive = HostService.isAlive, foreground = Liveness.foreground,
            privilege = s.privilege.name, connection = s.connection.name, needsReconnect = s.needsExplicitReconnect,
            userStopped = s.userStopped, frozen = Liveness.frozenUnresolved(), remoteMode = mode.key, remoteUp = remoteUp,
            remoteWhy = if (!remoteUp && phase != null) runCatching { Tailnet.line(ctx) }.getOrNull() else null,
            liveWhy = Liveness.problem(),
            ocrCrashedEngines = com.kilon.agentvscreen.core.VScreenOcr.crashedEngines(ctx),
        )
    }

    /** The cheap answer (no shell call): ping, the wake-up check. */
    fun now(ctx: Context): List<Item> = evaluate(inputs(ctx))

    /** The full answer, with the facts that need the screen and the system (status, fix). */
    fun full(ctx: Context, d: com.kilon.agentvscreen.core.VScreenDisplay?): List<Item> {
        val a11y = VScreenAccessibilityService.getInstance() != null
        return evaluate(inputs(ctx).copy(
            a11yOn = a11y,
            a11ySeesScreen = if (a11y && d != null) BackgroundOperation.nodeRoots(d.displayId).isNotEmpty() || runCatching { com.kilon.agentvscreen.core.VScreenShell.topActivity(d.displayId) }.getOrNull() == null else null,
            powerProblems = runCatching { com.kilon.agentvscreen.core.VScreenPower.check(ctx).let { if (it.ok) emptyList() else it.problems() } }.getOrNull(),
            ownFocus = d?.ownFocus, shellOwned = d?.shellOwned,
        ))
    }

    fun health(ctx: Context): Health = Health.of(now(ctx))

    fun ready(items: List<Item>): Boolean = items.none { it.blocking }

    /** `ready`, `blocking` (codes) and `warnings` (codes), as status and ping give them. */
    fun json(items: List<Item>, withText: Boolean = true): JSONObject = JSONObject()
        .put("ready", ready(items))
        .put("blocking", JSONArray(items.filter { it.blocking }.map { if (withText) it.json() else it.code }))
        .put("warnings", JSONArray(items.filter { !it.blocking }.map { if (withText) it.json() else it.code }))
}
