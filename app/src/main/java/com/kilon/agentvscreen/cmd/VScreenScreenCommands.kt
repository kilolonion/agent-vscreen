package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
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
    "not_connected" -> tr(S.vscreen_screen_commands_reconnect_the_privileged_channel)
    "not_foreground_service" -> tr(S.vscreen_screen_commands_grant_the_battery_exemption_then)
    "remote_down" -> tr(S.vscreen_screen_commands_restart_the_built_in_tailscale)
    "silent_apps" -> tr(S.vscreen_screen_commands_give_sound_back_to_apps)
    "frozen_in_background" -> tr(S.vscreen_screen_commands_grant_the_battery_exemption_and)
    "no_privilege" -> tr(S.vscreen_screen_commands_shizuku_not_running_not_granted)
    "no_accessibility" -> tr(S.vscreen_screen_commands_turn_on_agent_vscreen_s)
    "app_owned_display" -> tr(S.vscreen_screen_commands_reconnect_and_release_the_old)
    "no_display_focus" -> tr(S.vscreen_screen_commands_system_version_limit_needs_android)
    "accessibility_cannot_see_display" -> tr(S.vscreen_screen_commands_restart_the_accessibility_service_and)
    "a11y_not_ready" -> tr(S.vscreen_screen_commands_wait_and_check_the_widget)
    "background_network" -> tr(S.vscreen_screen_commands_exempt_agent_vscreen_from_battery)
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
            h.userOff(context) -> tr(S.vscreen_screen_commands_turned_off_by_the_user)
            h.blocked(context) -> tr(S.vscreen_screen_commands_the_system_blocks_the_write)
            h.wanted(context) -> tr(S.vscreen_screen_commands_turned_on_again_automatically_when)
            else -> tr(S.vscreen_screen_commands_never_turned_on_set_up)
        } })
        .put("user_stopped", BackgroundOperation.state.value.userStopped)
        // 1.4.0-beta6 (test: an app with the user was read as "the user pressed Stop AI"): three different states,
        // each with its own way back. with_user = apps the user has on the main screen now (handback event ends it).
        .put("with_user", org.json.JSONArray(com.kilon.agentvscreen.core.VScreenOwnership.given.value.values.map { g ->
            JSONObject().put("app", g.pkg).put("label", com.kilon.agentvscreen.core.VScreenOwnership.label(context, g.pkg)).put("since", g.at)
                .put("note", tr(S.vscreen_screen_commands_this_app_is_with_the))
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
        // 2.0.0-beta11: what this agent may do, from the same source the checks use (host/Permissions).
        .put("permissions", com.kilon.agentvscreen.host.Permissions.json(Call.current()))
        .put("app_rules", JSONObject().also { o -> VScreenStore.rules.value.forEach { (k, v) -> o.put(k, v.key) } })
        .put("keeps", JSONArray(VScreenRules.list().filter { it.spec.agentId == me }.map { it.json() }))
        .put("idle_release_in_s", if (idleAt > 0) ((idleAt - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0) else JSONObject.NULL)
        .also { o -> BackgroundOperation.hostRestartNote?.let { o.put("host_restarted", it) } }
        .put("version", JSONObject().put("app", com.kilon.agentvscreen.BuildConfig.VERSION_NAME).put("code", com.kilon.agentvscreen.BuildConfig.VERSION_CODE)
            .put("build", com.kilon.agentvscreen.BuildConfig.VERSION_NAME).put("protocol", VScreenDisplayService.PROTOCOL)
            .put("service_connected", VScreenRemoteDisplay.isConnected())
            .put("service_tasks_api", runCatching { VScreenRemoteDisplay.listTasksIfConnected(0) != null }.getOrDefault(false)))
    data.put("screen_off", com.kilon.agentvscreen.core.VScreenAwake.json(d))
    data.put("thermal", com.kilon.agentvscreen.host.ThermalGuard.thermalJson())
    if (d != null) {
        data.put("recording", com.kilon.agentvscreen.core.VScreenRecorder.status(d.displayId))
        data.put("display_flags", JSONArray(VScreenDisplayService.flagNames(d.flags)))
        data.put("ime_guard", JSONObject()
            .put("keeps_main_focus", VScreenDisplayService.keepsMainFocus(d.flags))
            .put("focus_restored", BackgroundOperation.focusRestores)
            .put("restore_failed", BackgroundOperation.focusRestoreFailures)
            .put("note", if (VScreenDisplayService.keepsMainFocus(d.flags)) tr(S.vscreen_screen_commands_the_screen_does_not_steal)
                else tr(S.vscreen_screen_commands_this_phone_ignores_the_no)))
            .put("display_has_own_focus", d.ownFocus)
            .put("shell_owned", d.shellOwned)
        val act = VScreenShell.topActivity(d.displayId)
        act?.let { lastActivity[d.displayId] = it }
        data.put("current_app", act?.substringBefore('/') ?: JSONObject.NULL)
            .put("current_activity", act ?: JSONObject.NULL)
            .put("resume_hint", if (act != null)
                tr(S.vscreen_screen_commands_the_screen_still_holds_s, act.substringBefore('/'))
            else tr(S.vscreen_screen_commands_the_screen_exists_but_has))
    } else if (s.displayId == null) {
        data.put("resume_hint", tr(S.vscreen_screen_commands_there_is_no_screen_right))
    } else if (s.ownerSessionId != sid) {
        data.put("resume_hint", if (s.idle || s.ownerSessionId == null || !BackgroundOperation.isTaskActive(s.ownerSessionId))
            tr(S.vscreen_screen_commands_screen_is_left_from_the, s.displayId)
        else tr(S.vscreen_screen_commands_screen_is_in_use_by, s.displayId))
    }
    data.put("capabilities", capabilities(d))
    data.put("perception", com.kilon.agentvscreen.core.OnnxRuntime.perceptionJson(context))
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
                .put("auto", code in AUTO_FIXABLE).put("what", fixWhat(code) ?: tr(S.vscreen_screen_commands_explain_the_cause_and_the)))
        }
        data.put("fixes", fixes).put("fix_hint", tr(S.vscreen_screen_commands_vscreen_fix_no_arguments_handles))
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
    val tried = codes.map { code -> code to runCatching { fixOne(sid, code, args) }.getOrElse { false to tr(S.vscreen_screen_commands_error_while_fixing, it.message) } }
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
            quiet -> tr(S.vscreen_screen_commands_no_problems_found)
            left == 0 && deg.length() == 0 -> tr(S.vscreen_screen_commands_all_fine_now)
            else -> tr(S.vscreen_screen_commands_items_remain_ask_the_user, maxOf(left, deg.length()))
        }))
}

/** One repair attempt: (fixed, what happened / what the user must do). */
internal fun VScreenOffloadHandler.fixOne(sid: String?, code: String, args: OffloadArgs): Pair<Boolean, String> {
    val s = BackgroundOperation.state.value
    return when (code) {
        "not_connected" -> {
            val r = BackgroundOperation.connect()
            if (r.connection.name == "READY") true to tr(S.vscreen_screen_commands_reconnected) else false to tr(S.vscreen_screen_commands_reconnect_failed, r.connectionError ?: r.connection.name)
        }
        "no_privilege" -> {
            val viaRoot = com.kilon.agentvscreen.host.Privileged.via == com.kilon.agentvscreen.host.Privileged.Via.ROOT
            val srcName = com.kilon.agentvscreen.host.Privileged.name()
            val steps = if (viaRoot) {
                if (s.privilege.name == "READY") tr(S.vscreen_screen_commands_root_is_available)
                else com.kilon.agentvscreen.host.Privileged.notReadyHint()
            } else when (s.privilege.name) {
                "NOT_INSTALLED" -> tr(S.vscreen_screen_commands_shizuku_or_a_compatible_fork)
                "NOT_RUNNING" -> tr(S.vscreen_screen_commands_is_not_running_it_stops, srcName)
                "NOT_AUTHORIZED" -> tr(S.vscreen_screen_commands_agent_vscreen_has_no_permission, srcName)
                else -> tr(S.vscreen_screen_commands_is_available, srcName)
            }
            if (s.privilege.name == "READY") return true to steps
            var opened = ""
            if (args.hasFlag("open") && !viaRoot) {
                val pm = context.packageManager
                val candidates = com.kilon.agentvscreen.host.ShizukuManager.discoveredManagerPackages().toList() + "rikka.shizuku"
                val intent = candidates.distinct().firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
                opened = if (intent != null) {
                    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .fold({ tr(S.vscreen_screen_commands_opened_on_the_main_screen, srcName) }, { tr(S.vscreen_screen_commands_opening_failed, srcName, it.message) })
                } else tr(S.vscreen_screen_commands_app_not_found, srcName)
            }
            false to steps + opened
        }
        "no_accessibility" -> {
            if (a11yLive()) return true to tr(S.vscreen_screen_commands_the_accessibility_service_is_connected)
            com.kilon.agentvscreen.host.Heal.accessibility(context)
        }
        "accessibility_cannot_see_display", "a11y_not_ready" -> {
            val d = BackgroundOperation.previewDisplayFor(sid ?: "") ?: return false to tr(S.vscreen_screen_commands_there_is_no_screen_right_2)
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < 3_000) {
                if (BackgroundOperation.nodeRoots(d.displayId).isNotEmpty()) return true to tr(S.vscreen_screen_commands_accessibility_can_see_the_screen)
                Thread.sleep(300)
            }
            if (code == "accessibility_cannot_see_display" && s.privilege.name == "READY") {
                com.kilon.agentvscreen.host.Heal.accessibility(context, restart = true)
                if (VScreenAccessibilityService.getInstance() != null) {
                    val t1 = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - t1 < 3_000) {
                        if (BackgroundOperation.nodeRoots(d.displayId).isNotEmpty()) return true to tr(S.vscreen_screen_commands_restarted_the_accessibility_service_the)
                        Thread.sleep(300)
                    }
                }
            }
            false to tr(S.vscreen_screen_commands_accessibility_still_cannot_see_the)
        }
        "app_owned_display" -> {
            val r = BackgroundOperation.connect()
            if (r.connection.name != "READY") return false to tr(S.vscreen_screen_commands_reconnect_failed, r.connectionError)
            if (sid != null) BackgroundOperation.releaseNow(sid)
            val srcName = com.kilon.agentvscreen.host.Privileged.name()
            true to tr(S.vscreen_screen_commands_reconnected_and_released_the_old, srcName)
        }
        "background_network" -> {
            val pw = com.kilon.agentvscreen.core.VScreenPower
            if (pw.check(context).ok) return true to tr(S.vscreen_screen_commands_background_network_is_not_restricted)
            if (s.privilege.name != "READY") return false to tr(S.vscreen_screen_commands_no_shizuku_so_this_can, pw.manualSteps())
            val (ok, msg) = com.kilon.agentvscreen.host.Heal.power(context)
            if (ok) true to msg else false to tr(S.vscreen_screen_commands_the_rest_the_user_does, msg, pw.manualSteps())
        }
        "no_display_focus" -> false to tr(S.vscreen_screen_commands_the_screen_lacking_its_own)
        "not_foreground_service" -> com.kilon.agentvscreen.host.Heal.foreground(context)
        "remote_down" -> com.kilon.agentvscreen.host.Heal.remote(context)
        "silent_apps" -> com.kilon.agentvscreen.host.Heal.sound()
        "service_off" -> false to tr(S.vscreen_screen_commands_the_user_turned_the_service)
        "user_stopped" -> false to tr(S.vscreen_screen_commands_the_user_pressed_stop_ai)
        "frozen_in_background" -> {
            val (ok, msg) = com.kilon.agentvscreen.host.Heal.power(context)
            (ok && !com.kilon.agentvscreen.host.Liveness.frozenUnresolved()) to tr(S.vscreen_screen_commands_system_exemptions_the_vendor_s, msg, if (ok) "（已全部生效）" else "", if (ok) " (all in effect)" else "")
        }
        else -> false to run { val keys = FIX_KEYS.joinToString(tr(S.automation_list_sep)); tr(S.vscreen_screen_commands_unknown_problem_code_available, code, keys) }
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

/** 2.0.0-beta8 (C6), pure: truthful `capabilities.drag` status string based on display ownership and privilege readiness. */
internal fun dragCapabilityText(shellOwned: Boolean?, privilegeReady: Boolean): String = when {
    shellOwned == true -> tr(S.vscreen_screen_commands_screen_service_pointer_multi_point)
    shellOwned == null && privilegeReady -> tr(S.vscreen_screen_commands_available_once_the_screen_is)
    else -> tr(S.vscreen_screen_commands_unavailable_needs_the_shizuku_screen)
}

/** Which mechanism each capability is using right now, for `status`. */
internal fun VScreenOffloadHandler.capabilities(d: VScreenDisplay?): JSONObject {
    val s = BackgroundOperation.state.value
    val o = JSONObject()
    o.put("screen", when { d == null -> tr(S.vscreen_screen_commands_no_screen); d.shellOwned && d.trusted -> tr(S.vscreen_screen_commands_trusted_screen_created_by_the); d.shellOwned -> tr(S.vscreen_screen_commands_created_by_the_shizuku_service); else -> tr(S.vscreen_screen_commands_app_s_own_screen_limited) })
    o.put("tap_swipe", tr(S.vscreen_screen_commands_injected_by_the_display_service))
    o.put("keys", if (d?.ownFocus == true) tr(S.vscreen_screen_commands_injected_with_displayid_the_screen) else tr(S.vscreen_screen_commands_injected_with_displayid_input_d))
    o.put("drag", dragCapabilityText(d?.shellOwned, s.privilege == BackgroundOperation.Privilege.READY))
    o.put("ocr", VScreenOcr.engineInfo())
    o.put("with_user", org.json.JSONArray(com.kilon.agentvscreen.core.VScreenOwnership.given.value.values.map { g ->
        JSONObject().put("app", g.pkg).put("since", g.at).put("why", g.why) }))
    o.put("accessibility", if (a11yLive()) tr(S.vscreen_screen_commands_connected_widget_tree_available) else tr(S.vscreen_screen_commands_not_connected_ocr_vision_only))
    o.put("vision", tr(S.vscreen_screen_commands_the_caller_s_model_looks))
    o.put("orientation", if (d == null) "-" else run { val src = d.geometryJson().optString("turn_source"); tr(S.vscreen_screen_commands_rotated_picture_turned_upright, d.rotation * 90, d.bufferTurn, src) })
    o.put("clipboard", tr(S.vscreen_screen_commands_non_ascii_text_goes_through))
    o.put("stop", tr(S.vscreen_screen_commands_by_default_only_the_task))
    return o
}
