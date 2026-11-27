package com.kilon.agentvscreen.cmd

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
        val q = p.getOrNull(i) ?: throw ActionError("INVALID_ARGS", tr("写上应用名或包名", "name the app or package"))
        return VScreenKnowledge.resolveApp(q) ?: throw ActionError("APP_NOT_FOUND", tr("没找到“$q”：apps $q 看看已安装的应用", "\"$q\" not found: apps $q lists installed apps"))
    }
    fun onOff(i: Int): Boolean = when (p.getOrNull(i)?.lowercase()) {
        "on", "true", "1", "开" -> true; "off", "false", "0", "关" -> false
        else -> throw ActionError("INVALID_ARGS", tr("写 on 或 off", "write on or off"))
    }
    fun done(c: VScreenMute.Change, what: String): NativeOffloadResult {
        if (c.ok) return ok(args, JSONObject().put("message", what).put("package_mode", c.modes?.pkg ?: JSONObject.NULL).put("uid_mode", c.modes?.uid ?: JSONObject.NULL))
        when (c.why) {
            "no-shizuku" -> throw ActionError("NOT_CONNECTED", tr("需要 ${com.kilon.agentvscreen.host.Privileged.name()} 已连接", "${com.kilon.agentvscreen.host.Privileged.name()} must be connected"))
            "denied" -> throw ActionError("NOT_SUPPORTED", tr("系统不允许 shell 修改应用权限（小米 / 红米要在开发者选项打开“USB 调试（安全设置）”）：告诉用户", "The system does not let the shell change app permissions (Xiaomi / Redmi: \"USB debugging (Security settings)\" in developer options): tell the user"))
            else -> throw ActionError("NOT_SUPPORTED", tr("没有改成：系统读回来是 包=${c.modes?.pkg}、UID=${c.modes?.uid}", "It did not change: the system reads package=${c.modes?.pkg}, UID=${c.modes?.uid}"))
        }
    }
    return when (val op = p.getOrNull(0)?.lowercase() ?: "list") {
        "list" -> {
            val silent = VScreenMute.silenced() ?: throw ActionError("NOT_CONNECTED", tr("需要 ${com.kilon.agentvscreen.host.Privileged.name()} 已连接", "${com.kilon.agentvscreen.host.Privileged.name()} must be connected"))
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
        "mute" -> { val a = app(1); done(VScreenMute.mute(a), tr("${name(a)} 已静音（所有屏幕），直到 sound unmute", "${name(a)} is muted on every screen until sound unmute")) }
        "unmute" -> { val a = app(1); done(VScreenMute.unmute(a), tr("${name(a)} 的声音恢复了；已经在播放的要重新播放才出声", "${name(a)} has its sound back; what is already playing sounds once played again")) }
        "plays" -> { val a = app(1); val on = onOff(2); VScreenMute.setPlays(a, on)
            ok(args, JSONObject().put("message", if (on) tr("${name(a)} 在副屏里也出声", "${name(a)} keeps its sound on the screen") else tr("${name(a)} 在副屏里静音", "${name(a)} is silent on the screen"))) }
        "screen" -> { val on = onOff(1); VScreenMute.enabled = on
            ok(args, JSONObject().put("message", if (on) tr("副屏静音已打开", "Screen muting is on") else tr("副屏静音已关闭，副屏静的应用都恢复了", "Screen muting is off; apps the screen muted have their sound back"))) }
        else -> throw ActionError("INVALID_ARGS", tr("sound 后面写 list、mute、unmute、plays 或 screen（不认识 $op）", "after sound write list, mute, unmute, plays or screen (not $op)"))
    }
}
