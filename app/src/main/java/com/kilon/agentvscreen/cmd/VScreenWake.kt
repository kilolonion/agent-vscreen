package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.trPair
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.host.Readiness
import com.kilon.agentvscreen.host.Wake
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

// [vscreen] 1.3: `schedule` — tell the phone when you will come, so it is up and checked at those times.

internal fun VScreenOffloadHandler.scheduleCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val p = args.positional
    val session = sid ?: VScreenOffloadHandler.DEFAULT_SESSION
    val me = Call.current()?.tokenId ?: com.kilon.agentvscreen.api.Agents.ofSession(session)
    if (p.firstOrNull()?.lowercase() == "off") {
        val name = p.getOrNull(1) ?: throw ActionError("INVALID_ARGS", tr(S.vscreen_wake_schedule_off_name_all))
        // 1.4.0-beta7 / 1.4.1-beta1: only this agent's bookings, keyed by (agentId, name).
        val n = Wake.remove(context, name.takeUnless { it == "all" }, me)
        if (n == 0) throw ActionError("NOT_FOUND", tr(S.vscreen_wake_no_schedule_called_schedule_lists, name))
        return ok(args, JSONObject().put("removed", n).put("schedules", JSONArray(Wake.list(context).filter { it.agentId == me }.map { it.json() })))
    }
    if (p.isEmpty()) {
        val list = Wake.list(context).filter { it.agentId == me }
        return ok(args, JSONObject()
            .put("schedules", JSONArray(list.map { it.json() }))
            .put("exact_alarm", Wake.exactAllowed(context))
            .put("now", Readiness.json(Readiness.now(context)))
            .put("hint", if (list.isEmpty()) tr(S.vscreen_wake_no_schedules_if_a_timed)
                else tr(S.vscreen_wake_each_window_is_checked_minutes, Wake.LEAD_MS / 60_000)))
    }
    val text = p.joinToString(" ")
    val forMin = args.get("for")?.let { (parseDuration(it, 0L) / 60_000L).toInt() }?.let { bounded("for", it, 1, Wake.MAX_FOR_MIN) }
    val added = try {
        Wake.add(context, args.get("name"), text, forMin, session, Call.current()?.tokenName ?: "main", me)
    } catch (x: Wake.BadSpec) {
        throw ActionError("INVALID_ARGS", tr(S.vscreen_wake_cannot_read_the_time_forms, text, x.message, Wake.MIN_EVERY_MIN))
    } catch (x: IllegalStateException) {
        throw ActionError("SCHEDULE_LIMIT", x.message ?: "")
    }
    val e = added.entry
    val now = Readiness.now(context)
    if (added.same) return ok(args, JSONObject().put("added", JSONObject.NULL).put("existing", e.json())
        .put("message", tr(S.vscreen_wake_there_is_already_the_same, e.name, e.spec.describe())))
    return ok(args, JSONObject()
        .put("added", e.json())
        .put("exact_alarm", Wake.exactAllowed(context))
        .put("now", Readiness.json(now))
        .put("message", trPair("已预约「${e.name}」：${e.spec.describe()}" + (if (e.spec.date == null && e.spec.days == 0x7F && e.spec.everyMin == 0) "（每天都会来；只约一次写 today / once ${e.spec.times.joinToString(",") { "%02d:%02d".format(it / 60, it % 60) }}）" else "") + "，每次保持 ${e.forMin} 分钟。窗口开始前 ${Wake.LEAD_MS / 60_000} 分钟会拉起服务并检查，没准备好会通知用户，检查结果作为事件发给本会话。",
            "Booked \"${e.name}\": ${e.spec.describe()}" + (if (e.spec.date == null && e.spec.days == 0x7F && e.spec.everyMin == 0) " (it comes every day; for once only write today / once ${e.spec.times.joinToString(",") { "%02d:%02d".format(it / 60, it % 60) }})" else "") + ", kept up for ${e.forMin} minutes each time. ${Wake.LEAD_MS / 60_000} minutes before each window the service is started and checked; the user is notified if it is not ready, and the result is sent to this session as an event.") +
            (if (!Readiness.ready(now)) tr(S.vscreen_wake_note_it_is_not_ready) else "") +
            (if (!Wake.exactAllowed(context)) tr(S.vscreen_wake_this_phone_does_not_allow) else "")))
}
