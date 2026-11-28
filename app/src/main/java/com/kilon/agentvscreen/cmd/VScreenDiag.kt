package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
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
    // 2.0.0 (issue #48): which display flags the screen got, which were refused and why, and whether it stays awake alone.
    BackgroundOperation.currentDisplay()?.let { d ->
        appendLine("display flags: ${VScreenDisplayService.flagNames(d.flags).joinToString("|")}, shell_owned=${d.shellOwned}, own_power_group=${com.kilon.agentvscreen.core.VScreenAwake.canWakeAlone(d)}")
    }
    com.kilon.agentvscreen.core.VScreenDisplay.lastRemoteError?.let { appendLine("screen service error: $it") }
    com.kilon.agentvscreen.core.VScreenRemoteDisplay.rejectedFlags.forEach { appendLine("display flags refused: $it") }
    val screenMode = com.kilon.agentvscreen.core.VScreenStore.phoneScreenMode.value
    appendLine("keep screen on: mode=${screenMode.key}, enabled=${com.kilon.agentvscreen.core.VScreenStore.keepScreenOnEnabled}, held=${com.kilon.agentvscreen.core.VScreenAwake.screenLockHeld}, woke=${com.kilon.agentvscreen.core.VScreenAwake.screenWokeCount}")
    appendLine("input reach: ${com.kilon.agentvscreen.core.InputReach.diagSummary(st.displayId)}")
    appendLine("thermal: ${com.kilon.agentvscreen.host.ThermalGuard.thermalJson()}")
    appendLine("app heap: managed_used_kib=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024}, " +
        "native_allocated_kib=${android.os.Debug.getNativeHeapAllocatedSize() / 1024}")
    appendLine(com.kilon.agentvscreen.host.Privileged.diagnostics(context))
    appendLine("gate: ${com.kilon.agentvscreen.host.Liveness.gateJson()?.toString() ?: "off"}")
    appendLine("watchdogs: ${com.kilon.agentvscreen.core.LoopWatchdog.process.summary()}")
    appendLine("hold: ${ActHold.diagSummary()}")
    appendLine("detector: ${com.kilon.agentvscreen.core.VScreenDetector.status(context)}")
    appendLine("onnx: ${com.kilon.agentvscreen.core.OnnxRuntime.summary(context)}")
    // 1.4.0-beta2: a real reading on this phone, not the state of a flag.
    append("\n" + tr(S.vscreen_diag_text_recognition_self_test_a) + ":\n")
    append(runCatching { com.kilon.agentvscreen.core.VScreenOcr.selfTest(context) }.getOrElse { "self-test crashed: ${it.javaClass.simpleName}: ${it.message}" }.prependIndent("  ") + "\n")
    append("\n" + tr(S.vscreen_diag_recent_onnx_inferences_up_to) + ":\n")
    append(com.kilon.agentvscreen.core.OnnxRuntime.formatRecent(com.kilon.agentvscreen.core.OnnxRuntime.MAX_RECORDS) + "\n")
    val sw = Swallowed.snapshot()
    append("\n" + tr(S.vscreen_diag_swallowed_exceptions_count_per_site) + ": " + (if (sw.isEmpty()) tr(S.vscreen_diag_none) else "") + "\n")
    sw.sortedByDescending { it.second.count }.take(30).forEach { (tag, e) -> append("  $tag ×${e.count}  ${fmt.format(Date(e.at))}  ${e.last.take(160)}\n") }
    append("\n" + tr(S.vscreen_diag_recent_commands_typed_text_not) + ":\n")
    Audit.tail(40).forEach { o ->
        // 2.0.0-beta9: where the call came from (tailnet peer, or the real public address of a public client).
        val from = listOfNotNull(o.optString("source").takeIf { it.isNotEmpty() && it != "local" }, o.optString("src").takeIf { it.isNotEmpty() }?.let { "src=$it" },
            o.optString("peer").takeIf { it.isNotEmpty() && it != "null" && o.optString("source") == "tailnet" }).joinToString(" ").let { if (it.isEmpty()) "" else " [$it]" }
        append("  ${fmt.format(Date(o.optLong("at")))} ${o.optString("kind")} ${o.optString("token")}$from ${o.optString("cmd", o.optString("what"))} → ${o.optString("code", o.optString("answer"))} ${o.optLong("ms").takeIf { it > 0 }?.let { "${it}ms" } ?: ""}\n")
    }
    append("\n" + tr(S.vscreen_diag_app_log_last_150_lines) + ":\n")
    append(AppLogger.tail(150))
}

/** For the app's own "copy diagnostics" button. */
object Diag { fun text(context: android.content.Context): String = diagText(context) }
