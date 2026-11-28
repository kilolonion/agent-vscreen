package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenKnowledge
import com.kilon.agentvscreen.core.VScreenMute
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

/** 1.4.0-beta13: `sound …`, the same actions as the app's 应用静音 page, through [VScreenMute]. */
internal fun VScreenOffloadHandler.soundCmd(args: OffloadArgs): NativeOffloadResult {
    val p = args.positional
    fun name(pkg: String) = VScreenKnowledge.label(pkg) ?: pkg
    fun app(i: Int): String {
        val q = p.getOrNull(i) ?: throw ActionError("INVALID_ARGS", tr(S.vscreen_sound_cmd_name_the_app_or_package))
        return VScreenKnowledge.resolveApp(q) ?: throw ActionError("APP_NOT_FOUND", tr(S.vscreen_sound_cmd_not_found_apps_lists_installed, q))
    }
    fun onOff(i: Int): Boolean = when (p.getOrNull(i)?.lowercase()) {
        "on", "true", "1", "开" -> true; "off", "false", "0", "关" -> false
        else -> throw ActionError("INVALID_ARGS", tr(S.vscreen_sound_cmd_write_on_or_off))
    }
    fun done(c: VScreenMute.Change, what: String): NativeOffloadResult {
        if (c.ok) return ok(args, JSONObject().put("message", what).put("package_mode", c.modes?.pkg ?: JSONObject.NULL).put("uid_mode", c.modes?.uid ?: JSONObject.NULL))
        when (c.why) {
            "no-shizuku" -> throw ActionError("NOT_CONNECTED", tr(S.vscreen_sound_cmd_must_be_connected, com.kilon.agentvscreen.host.Privileged.name()))
            "denied" -> throw ActionError("NOT_SUPPORTED", tr(S.vscreen_sound_cmd_the_system_does_not_let))
            else -> throw ActionError("NOT_SUPPORTED", tr(S.vscreen_sound_cmd_it_did_not_change_the, c.modes?.pkg, c.modes?.uid))
        }
    }
    return when (val op = p.getOrNull(0)?.lowercase() ?: "list") {
        "list" -> {
            val silent = VScreenMute.silenced() ?: throw ActionError("NOT_CONNECTED", tr(S.vscreen_sound_cmd_must_be_connected, com.kilon.agentvscreen.host.Privileged.name()))
            val arr = JSONArray()
            silent.forEach { h ->
                arr.put(JSONObject().put("package", h.pkg).put("name", name(h.pkg)).put("user", h.user).put("package_mode", h.modes.pkg ?: JSONObject.NULL).put("uid_mode", h.modes.uid ?: JSONObject.NULL)
                    .put("why", when {
                        h.user != 0 -> "clone"
                        h.pkg in VScreenMute.manual() -> "muted_by_hand"
                        h.pkg in VScreenMute.mutedNow() -> "on_screen"
                        else -> "left_silent"
                    }))
            }
            ok(args, JSONObject().put("screen_mute", VScreenMute.enabled).put("silent", arr)
                .put("muted_by_hand", JSONArray(VScreenMute.manual().sorted())).put("plays_on_screen", JSONArray(VScreenMute.plays().sorted())))
        }
        "mute" -> { val a = app(1); done(VScreenMute.mute(a), tr(S.vscreen_sound_cmd_is_muted_on_every_screen, name(a))) }
        "unmute" -> { val a = app(1); done(VScreenMute.unmute(a), tr(S.vscreen_sound_cmd_has_its_sound_back_what, name(a))) }
        "plays" -> { val a = app(1); val on = onOff(2); VScreenMute.setPlays(a, on)
            ok(args, JSONObject().put("message", if (on) tr(S.vscreen_sound_cmd_keeps_its_sound_on_the, name(a)) else tr(S.vscreen_sound_cmd_is_silent_on_the_screen, name(a)))) }
        "screen" -> { val on = onOff(1); VScreenMute.enabled = on
            ok(args, JSONObject().put("message", if (on) tr(S.vscreen_sound_cmd_screen_muting_is_on) else tr(S.vscreen_sound_cmd_screen_muting_is_off_apps))) }
        else -> throw ActionError("INVALID_ARGS", tr(S.vscreen_sound_cmd_after_sound_write_list_mute, op))
    }
}
