package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
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
        /** Critical background loops (app or privileged service) that tripped or exited unexpectedly. */
        val unhealthyWatchdogs: List<String> = emptyList(),
    )

    fun evaluate(i: Inputs): List<Item> = buildList {
        if (!i.serviceOn) add(Item("service_off", true, tr(S.readiness_the_user_turned_agent_vscreen)))
        when (i.privilege) {
            // 1.3: with Shizuku there, the next command reconnects by itself: a warning, not a block.
            // 1.4.0-beta4: only a channel that failed is reported. A connection that is merely not open (the service
            // was hidden or stopped, nothing has run yet) is the normal resting state: the next command opens it. It
            // was reported as "down … fix not_connected", and agents asked users to repair a service that was only off.
            "READY" -> if (i.connection == "ERROR")
                add(Item("not_connected", false, tr(S.readiness_the_screen_service_did_not)))
            // 1.4.2-beta3: Shizuku or root, whichever the user chose, says why in its own words.
            else -> add(Item("no_privilege", true, Privileged.notReadyHint()))
        }
        if (i.userStopped) add(Item("user_stopped", true, tr(S.vscreen_screen_commands_the_user_pressed_stop_ai)))
        if (i.serviceOn && i.serviceAlive && !i.foreground)
            add(Item("not_foreground_service", false, i.liveWhy ?: tr(S.readiness_the_service_is_not_a)))
        else if (i.frozen)
            add(Item("frozen_in_background", false, i.liveWhy ?: tr(S.readiness_the_system_recently_froze_agent)))
        if (i.remoteMode != "off" && !i.remoteUp)
            add(Item("remote_down", false, tr(S.readiness_access_from_other_devices_is) +
                (i.remoteWhy?.let { tr(S.public_guide_text, it) } ?: "")))
        if (i.a11yOn == false)
            add(Item("no_accessibility", false, tr(S.readiness_the_accessibility_service_is_not)))
        else if (i.a11ySeesScreen == false)
            add(Item("accessibility_cannot_see_display", false, tr(S.readiness_accessibility_is_on_but_the)))
        if (!i.powerProblems.isNullOrEmpty()) {
            val p = i.powerProblems.joinToString(tr(S.automation_list_sep))
            val srcName = Privileged.name()
            add(Item("background_network", false, tr(S.readiness_the_system_may_cut_agent, p) +
                (if (i.privilege == "READY") tr(S.readiness_fix_background_network_can_add, srcName)
                 else tr(S.readiness_ask_the_user_to_set, com.kilon.agentvscreen.core.VScreenPower.manualSteps()))))
        }
        if (i.ownFocus == false)
            add(Item("no_display_focus", false, tr(S.readiness_the_screen_has_no_input)))
        if (i.shellOwned == false) {
            val srcName = Privileged.name()
            add(Item("app_owned_display", false, tr(S.readiness_the_screen_service_is_unavailable, srcName)))
        }
        val mlCrashed = com.kilon.agentvscreen.core.VScreenOcr.ENGINE_MLKIT in i.ocrCrashedEngines
        val pdCrashed = com.kilon.agentvscreen.core.VScreenOcr.ENGINE_PADDLE in i.ocrCrashedEngines
        if (mlCrashed && pdCrashed) {
            add(Item("ocr_unavailable", false, tr(S.readiness_ocr_is_unavailable_both_pp)))
        } else if (mlCrashed) {
            add(Item("ocr_degraded", false, tr(S.readiness_ml_kit_crashed_during_self)))
        } else if (pdCrashed) {
            add(Item("ocr_degraded", false, tr(S.readiness_pp_ocrv6_crashed_during_self)))
        }
        if (i.unhealthyWatchdogs.isNotEmpty()) {
            val names = i.unhealthyWatchdogs.joinToString(", ")
            add(Item(
                "watchdog_unhealthy",
                false,
                tr(S.readiness_background_loop_stopped_or_tripped, names),
            ))
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
        val unhealthy = buildList {
            addAll(com.kilon.agentvscreen.core.LoopWatchdog.process.unhealthyCriticalNames())
            val remote = Liveness.gateJson()?.optJSONArray("watchdogs")
            addAll(com.kilon.agentvscreen.core.LoopWatchdog.unhealthyFromJson(remote, prefix = "service/"))
        }.distinct()
        return Inputs(
            serviceOn = !Prefs.blocked(ctx), serviceAlive = HostService.isAlive, foreground = Liveness.foreground,
            privilege = s.privilege.name, connection = s.connection.name, needsReconnect = s.needsExplicitReconnect,
            userStopped = s.userStopped, frozen = Liveness.frozenUnresolved(), remoteMode = mode.key, remoteUp = remoteUp,
            remoteWhy = if (!remoteUp && phase != null) runCatching { Tailnet.line(ctx) }.getOrNull() else null,
            liveWhy = Liveness.problem(),
            ocrCrashedEngines = com.kilon.agentvscreen.core.VScreenOcr.crashedEngines(ctx),
            unhealthyWatchdogs = unhealthy,
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
