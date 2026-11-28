package com.kilon.agentvscreen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.DetectorModels
import com.kilon.agentvscreen.core.VScreenDetector
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 2.0 + 2.0.0-beta8 (K.1): textless-element detector settings dialog.
 *
 * - Single toggle switch row for on/off (no confusing "关闭" button next to "完成").
 * - Two radio rows for model selection (built-in small model vs downloadable large model).
 * - Non-breaking space inside "（无文字\u00A0图标）".
 * - Raw model debug summary lives in Diagnostics, not here.
 */
@Composable
fun DetectorPreference() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    val inst = remember(tick) { DetectorModels.installed(ctx) }
    val accepted = remember(tick) { VScreenDetector.licenseAccepted(ctx) }
    val on = remember(tick) { VScreenDetector.active(ctx) }
    ArrowPreference(
        title = tr("无字图标识别", "Icon without text detection"),
        summary = tr("识别没有文字标签的按钮和图标", "Finds buttons and icons without text labels"),
        endActions = {
            Value(
                when {
                    inst == null -> tr("不可用", "Unavailable")
                    !accepted -> tr("未开启", "Not on")
                    on -> tr("已开启", "On")
                    else -> tr("已关闭", "Off")
                },
            )
        },
        onClick = { open = true },
    )
    if (!open) return
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var pct by remember { mutableIntStateOf(-1) }
    val small = MiuixTheme.colorScheme.onSurfaceVariantSummary

    fun downloadLarge() {
        if (busy || inst == null || !inst.builtIn) return
        busy = true; note = null; pct = 0
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val (offers, errors) = DetectorModels.fetchOffers()
                    if (offers.isEmpty()) error(tr("所有下载源都连不上：", "No download source answered: ") + errors.joinToString("; "))
                    val pick = offers.filter { it.sha256 != inst.sha256 }.maxByOrNull { it.bytes }
                        ?: error(tr("还没有更大的模型", "No larger model yet"))
                    DetectorModels.install(ctx, pick) { got, total -> pct = (got * 100 / maxOf(1L, total)).toInt() }
                }.exceptionOrNull()
            }
            busy = false; pct = -1; tick++
            note = err?.let { tr("没装上：", "Not installed: ") + (it.message ?: it.javaClass.simpleName) }
        }
    }

    GlassDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { DialogTitle(tr("无字图标识别", "Icon without text detection")) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr(
                        "在本机找出图标、×、开关这类没有文字的可点目标，agent 的元素列表里会多出“（无文字\u00A0图标）”，可以直接 tap #编号。",
                        "Finds tappable items without words (icons, ×, switches) on this phone; the agent's element list gains \"(no\u00A0text\u00A0icon)\" entries it can tap by #number.",
                    ),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = small,
                )
                if (!accepted) {
                    Text(tr("许可说明：", "License: ") + VScreenDetector.licenseNote(), fontSize = 13.sp, lineHeight = 19.sp)
                } else if (inst != null) {
                    SwitchPreference(
                        checked = on,
                        onCheckedChange = { checked ->
                            if (!busy) {
                                VScreenDetector.setSwitchedOn(ctx, checked)
                                tick++
                            }
                        },
                        title = tr("开启无字图标识别", "Enable icon finder"),
                    )
                    RadioButtonPreference(
                        title = tr("内置小模型", "Built-in small model"),
                        selected = inst.builtIn,
                        onClick = {
                            if (!busy && !inst.builtIn) {
                                DetectorModels.remove(ctx)
                                note = null
                                tick++
                            }
                        },
                    )
                    RadioButtonPreference(
                        title = tr("大模型（约 40 MB，更慢，需下载）", "Large model (~40 MB, slower, download)"),
                        selected = !inst.builtIn,
                        onClick = { if (!busy && inst.builtIn) downloadLarge() },
                    )
                }
                if (pct >= 0) Text(tr("下载中 $pct%", "Downloading $pct%"), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                note?.let { Text(it, fontSize = 12.sp, color = Palette.stopped) }
            }
        },
        confirmButton = {
            if (!accepted && inst != null) {
                LinkButton(onClick = { VScreenDetector.acceptLicense(ctx); tick++ }) {
                    Text(tr("已了解，开启", "I understand, turn on"))
                }
            } else {
                LinkButton(enabled = !busy, onClick = { open = false }) {
                    Text(tr("完成", "Done"))
                }
            }
        },
        dismissButton = if (!accepted && inst != null) {
            { LinkButton(enabled = !busy, onClick = { open = false }) { Text(tr("暂不", "Not now")) } }
        } else null,
    )
}
