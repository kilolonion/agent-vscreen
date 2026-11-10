package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.Vendor
import com.kilon.agentvscreen.core.VScreenDisplayService
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.Swallowed
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1.2 `diag`: one text a user or an agent can hand to the developer. Versions, device, connection state, the last
 * commands (codes and timings only: the audit log already redacts typed text), exceptions that were swallowed, and the
 * end of the app log. No screenshots, no typed text, no tokens.
 */
internal fun VScreenOffloadHandler.diagCmd(@Suppress("UNUSED_PARAMETER") sid: String?): NativeOffloadResult = NativeOffloadResult(0, diagText(context) + "\n", "OK")

internal fun diagText(context: android.content.Context): String = buildString {
    val st = BackgroundOperation.state.value
    val dev = Vendor.device
    val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
    append("Agent-VScreen ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), service protocol ${VScreenDisplayService.PROTOCOL}\n")
    append("device: ${dev.vendor} ${dev.brand} ${dev.model}, sdk ${dev.sdk}, vendor layer ${Vendor.current.javaClass.simpleName}, os ${dev.os}\n")
    append("connection: ${st.connection.name}" + (st.connectionError?.let { " ($it)" } ?: "") + ", screen: " + (st.displayId?.let { "display $it" } ?: "none") + ", tasks running: ${Tasks.running()}\n")
    append("queue: " + (VScreenOffloadHandler.queueHolder ?: "idle") + "\n")
    val sw = Swallowed.snapshot()
    append("\n" + tr("被吞掉的异常（按位置计数）", "swallowed exceptions (count per site)") + ": " + (if (sw.isEmpty()) tr("无", "none") else "") + "\n")
    sw.sortedByDescending { it.second.count }.take(30).forEach { (tag, e) -> append("  $tag ×${e.count}  ${fmt.format(Date(e.at))}  ${e.last.take(160)}\n") }
    append("\n" + tr("最近的命令（不含输入的文字）", "recent commands (typed text not included)") + ":\n")
    Audit.tail(40).forEach { o ->
        append("  ${fmt.format(Date(o.optLong("at")))} ${o.optString("kind")} ${o.optString("token")} ${o.optString("cmd", o.optString("what"))} → ${o.optString("code", o.optString("answer"))} ${o.optLong("ms").takeIf { it > 0 }?.let { "${it}ms" } ?: ""}\n")
    }
    append("\n" + tr("应用日志（最后 150 行）", "app log (last 150 lines)") + ":\n")
    append(AppLogger.tail(150))
}

/** The same as JSON (the app's "export diagnostics" button shares the text). */
internal fun diagJson(context: android.content.Context): JSONObject = JSONObject().put("text", diagText(context))
    .put("swallowed", JSONArray(Swallowed.snapshot().map { JSONObject().put("site", it.first).put("count", it.second.count).put("last", it.second.last) }))

/** For the app's own "copy diagnostics" button. */
object Diag { fun text(context: android.content.Context): String = diagText(context) }
