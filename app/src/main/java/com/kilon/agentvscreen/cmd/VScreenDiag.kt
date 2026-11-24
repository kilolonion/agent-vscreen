package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.Vendor
import com.kilon.agentvscreen.core.VScreenDisplayService
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.Swallowed
import com.kilon.agentvscreen.tr
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
    BackgroundOperation.currentDisplay()?.let { appendLine("frame pump: ${it.frameWork()}") }
    appendLine("app heap: managed_used_kib=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024}, " +
        "native_allocated_kib=${android.os.Debug.getNativeHeapAllocatedSize() / 1024}")
    appendLine(com.kilon.agentvscreen.host.Privileged.diagnostics(context))
    appendLine("gate: ${com.kilon.agentvscreen.host.Liveness.gateJson()?.toString() ?: "off"}")
    appendLine("hold: ${ActHold.diagSummary()}")
    appendLine("detector: ${com.kilon.agentvscreen.core.VScreenDetector.status(context)}")
    appendLine("onnx: ${com.kilon.agentvscreen.core.OnnxRuntime.summary(context)}")
    // 1.4.0-beta2: a real reading on this phone, not the state of a flag.
    append("\n" + tr("文字识别自检（本机真实识别一张已知图片）", "text recognition self-test (a real reading of a known picture on this phone)") + ":\n")
    append(runCatching { com.kilon.agentvscreen.core.VScreenOcr.selfTest(context) }.getOrElse { "self-test crashed: ${it.javaClass.simpleName}: ${it.message}" }.prependIndent("  ") + "\n")
    append("\n" + tr("最近 ONNX 推理（最多 50 条）", "recent ONNX inferences (up to 50)") + ":\n")
    append(com.kilon.agentvscreen.core.OnnxRuntime.formatRecent(com.kilon.agentvscreen.core.OnnxRuntime.MAX_RECORDS) + "\n")
    val sw = Swallowed.snapshot()
    append("\n" + tr("被吞掉的异常（按位置计数）", "swallowed exceptions (count per site)") + ": " + (if (sw.isEmpty()) tr("无", "none") else "") + "\n")
    sw.sortedByDescending { it.second.count }.take(30).forEach { (tag, e) -> append("  $tag ×${e.count}  ${fmt.format(Date(e.at))}  ${e.last.take(160)}\n") }
    append("\n" + tr("最近的命令（不含输入的文字）", "recent commands (typed text not included)") + ":\n")
    Audit.tail(40).forEach { o ->
        // 2.0.0-beta9: where the call came from (tailnet peer, or the real public address of a public client).
        val from = listOfNotNull(o.optString("source").takeIf { it.isNotEmpty() && it != "local" }, o.optString("src").takeIf { it.isNotEmpty() }?.let { "src=$it" },
            o.optString("peer").takeIf { it.isNotEmpty() && it != "null" && o.optString("source") == "tailnet" }).joinToString(" ").let { if (it.isEmpty()) "" else " [$it]" }
        append("  ${fmt.format(Date(o.optLong("at")))} ${o.optString("kind")} ${o.optString("token")}$from ${o.optString("cmd", o.optString("what"))} → ${o.optString("code", o.optString("answer"))} ${o.optLong("ms").takeIf { it > 0 }?.let { "${it}ms" } ?: ""}\n")
    }
    append("\n" + tr("应用日志（最后 150 行）", "app log (last 150 lines)") + ":\n")
    append(AppLogger.tail(150))
}

/** For the app's own "copy diagnostics" button. */
object Diag { fun text(context: android.content.Context): String = diagText(context) }
