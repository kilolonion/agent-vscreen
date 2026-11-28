package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.tr

import com.kilon.agentvscreen.api.EventHub
import com.kilon.agentvscreen.core.VScreenRules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** `events [--wait T]`: take the events posted for this session (a keep with --keep wake saw its condition). */
internal fun VScreenOffloadHandler.eventsCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: VScreenOffloadHandler.DEFAULT_SESSION
    val want = boundedMs("wait", args.getLong("wait") ?: 0L, 0L, 120_000L)
    // 1.1: events come only from this session's running --keep wake watches. With none running, waiting cannot bring
    // anything, so the reply says that at once instead of silently returning early or uselessly blocking.
    // 1.3.0-beta16: a question waiting for the user and the user holding the screen bring events too (answer, hand back).
    val watching = VScreenRules.running().count { it.spec.wake && it.spec.sid == s } + com.kilon.agentvscreen.host.UserAsk.openFor(s) +
        (if (com.kilon.agentvscreen.core.BackgroundOperation.state.value.let { it.ownerSessionId == s && (it.takeoverSessionId != null || it.userStopped) }) 1 else 0)
    val t0 = android.os.SystemClock.elapsedRealtime()
    val again = args.hasFlag("again")
    val evs = EventHub.take(s, if (watching > 0) want else 0L, again)
    if (again && evs.isEmpty()) return text(tr(S.vscreen_events_no_previous_batch_the_last))
    val waited = android.os.SystemClock.elapsedRealtime() - t0
    if (evs.isEmpty()) return text(when {
        watching == 0 && want > 0 -> tr(S.vscreen_events_no_new_events_and_no, s)
        watching == 0 -> tr(S.vscreen_events_no_new_events_session_has, s)
        want > 0 -> tr(S.vscreen_events_waited_s_no_new_events, waited / 1000, s, watching)
        else -> tr(S.vscreen_events_no_new_events_session_has_2, s, watching)
    })
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    val body = evs.joinToString("\n\n") { e -> "#${e.optLong("seq")} [${fmt.format(Date(e.optLong("at")))}${ago(e.optLong("at"))}] ${e.optString("kind")} \"${e.optString("name")}\"" + (if (e.optBoolean("repeat")) tr(S.vscreen_events_repeat_handed_out_before_the) else "") + "\n${e.optString("text")}" } +
        "\n\n" + tr(S.vscreen_events_this_batch_is_kept_until)
    return NativeOffloadResult(0, body + "\n", "OK", org.json.JSONObject().put("events", org.json.JSONArray(evs)))
}

/** 1.3.0: an event from long ago says so; a clock time alone made an unconfirmed one from yesterday look new. */
private fun ago(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60_000
    return when {
        m < 30 -> ""
        m < 120 -> tr(S.vscreen_events_min_ago, m)
        m < 48 * 60 -> tr(S.vscreen_events_h_ago, m / 60)
        else -> tr(S.vscreen_events_days_ago, m / 1440)
    }
}
