package com.kilon.agentvscreen.cmd

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
        val name = p.getOrNull(1) ?: throw ActionError("INVALID_ARGS", tr("schedule off <名字|all>", "schedule off <name|all>"))
        // 1.4.0-beta7 / 1.4.1-beta1: only this agent's bookings, keyed by (agentId, name).
        val n = Wake.remove(context, name.takeUnless { it == "all" }, me)
        if (n == 0) throw ActionError("NOT_FOUND", tr("没有叫「$name」的预约：schedule 看列表", "no schedule called \"$name\": schedule lists them"))
        return ok(args, JSONObject().put("removed", n).put("schedules", JSONArray(Wake.list(context).filter { it.agentId == me }.map { it.json() })))
    }
    if (p.isEmpty()) {
        val list = Wake.list(context).filter { it.agentId == me }
        return ok(args, JSONObject()
            .put("schedules", JSONArray(list.map { it.json() }))
            .put("exact_alarm", Wake.exactAllowed(context))
            .put("now", Readiness.json(Readiness.now(context)))
            .put("hint", if (list.isEmpty()) tr("没有预约。定时任务要用手机时先预约：schedule \"07:00\"、schedule \"weekdays 08:30,18:00\"、schedule \"every 2h from 08:00 to 22:00\"",
                "No schedules. If a timed task of yours uses the phone, book it: schedule \"07:00\", schedule \"weekdays 08:30,18:00\", schedule \"every 2h from 08:00 to 22:00\"")
                else tr("每个窗口开始前 ${Wake.LEAD_MS / 60_000} 分钟检查一次，结果在 last_check，也会作为事件发给预约的会话（vscreen events）", "Each window is checked ${Wake.LEAD_MS / 60_000} minutes before it starts; the result is in last_check and is sent to the booking session as an event (vscreen events)")))
    }
    val text = p.joinToString(" ")
    val forMin = args.get("for")?.let { (parseDuration(it, 0L) / 60_000L).toInt() }?.let { bounded("for", it, 1, Wake.MAX_FOR_MIN) }
    val added = try {
        Wake.add(context, args.get("name"), text, forMin, session, Call.current()?.tokenName ?: "main", me)
    } catch (x: Wake.BadSpec) {
        throw ActionError("INVALID_ARGS", tr("看不懂时间「$text」：${x.message}。写法：\"07:00\"（每天）、\"07:00,19:30\"、\"weekdays 08:30\"、\"mon,wed,fri 21:00\"、\"every 2h from 08:00 to 22:00\"（间隔至少 ${Wake.MIN_EVERY_MIN} 分钟）；只约一次：\"today 10:30\"、\"tomorrow 07:00\"、\"once 10:30\"、\"11-15 10:30\"",
            "Cannot read the time \"$text\": ${x.message}. Forms: \"07:00\", \"07:00,19:30\", \"weekdays 08:30\", \"mon,wed,fri 21:00\", \"every 2h from 08:00 to 22:00\" (at least ${Wake.MIN_EVERY_MIN} minutes apart); once only: \"today 10:30\", \"tomorrow 07:00\", \"once 10:30\", \"11-15 10:30\""))
    } catch (x: IllegalStateException) {
        throw ActionError("SCHEDULE_LIMIT", x.message ?: "")
    }
    val e = added.entry
    val now = Readiness.now(context)
    if (added.same) return ok(args, JSONObject().put("added", JSONObject.NULL).put("existing", e.json())
        .put("message", tr("已经有一样的预约「${e.name}」（${e.spec.describe()}），没有重复添加。要改就 --name ${e.name} 覆盖，或 schedule off ${e.name}", "There is already the same schedule \"${e.name}\" (${e.spec.describe()}); nothing added. To change it, book again with --name ${e.name}, or schedule off ${e.name}")))
    return ok(args, JSONObject()
        .put("added", e.json())
        .put("exact_alarm", Wake.exactAllowed(context))
        .put("now", Readiness.json(now))
        .put("message", tr("已预约「${e.name}」：${e.spec.describe()}" + (if (e.spec.date == null && e.spec.days == 0x7F && e.spec.everyMin == 0) "（每天都会来；只约一次写 today / once ${e.spec.times.joinToString(",") { "%02d:%02d".format(it / 60, it % 60) }}）" else "") + "，每次保持 ${e.forMin} 分钟。窗口开始前 ${Wake.LEAD_MS / 60_000} 分钟会拉起服务并检查，没准备好会通知用户，检查结果作为事件发给本会话。",
            "Booked \"${e.name}\": ${e.spec.describe()}" + (if (e.spec.date == null && e.spec.days == 0x7F && e.spec.everyMin == 0) " (it comes every day; for once only write today / once ${e.spec.times.joinToString(",") { "%02d:%02d".format(it / 60, it % 60) }})" else "") + ", kept up for ${e.forMin} minutes each time. ${Wake.LEAD_MS / 60_000} minutes before each window the service is started and checked; the user is notified if it is not ready, and the result is sent to this session as an event.") +
            (if (!Readiness.ready(now)) tr(" 注意：现在就没准备好（见 now.blocking），请告诉用户。", " Note: it is not ready right now (see now.blocking); tell the user.") else "") +
            (if (!Wake.exactAllowed(context)) tr(" 这台手机不允许精确闹钟，时间可能晚几分钟。", " This phone does not allow exact alarms; windows may start a few minutes late.") else "")))
}
