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
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 2.0: the textless-element detector, one row in Settings. The small model is built in but its weights are AGPL-3.0
 * (a separate file next to the MIT app), so nothing runs until the user has read that in this dialog and turned it
 * on. After that: switch on/off, download the larger model (same license) or go back to the built-in one.
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
    ArrowPreference(title = tr("无字图标识别", "Textless icon finder"),
        summary = tr("帮 agent 找到没有文字的按钮", "Helps agents find buttons without words"),
        endActions = { Value(when { inst == null -> tr("不可用", "Unavailable"); !accepted -> tr("未开启", "Not on"); on -> tr("已开启", "On"); else -> tr("已关闭", "Off") }) },
        onClick = { open = true })
    if (!open) return
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var pct by remember { mutableIntStateOf(-1) }
    val small = MiuixTheme.colorScheme.onSurfaceVariantSummary
    GlassDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { DialogTitle(tr("无字图标识别", "Textless icon finder")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("在本机找出图标、×、开关这类没有文字的可点目标，agent 的元素列表里会多出“（无文字 图标）”，可以直接 tap #编号。",
                    "Finds tappable things without words (icons, ×, switches) on this phone; the agent's element list gains \"(no text icon)\" entries it can tap by #number."),
                    fontSize = 13.sp, color = small)
                if (!accepted) Text(tr("许可说明：", "License: ") + VScreenDetector.licenseNote(), fontSize = 13.sp)
                else Text(VScreenDetector.status(ctx), fontSize = 12.sp, color = small)
                if (pct >= 0) Text(tr("下载中 $pct%", "Downloading $pct%"), fontSize = 13.sp)
                note?.let { Text(it, fontSize = 12.sp, color = Palette.stopped) }
                if (accepted && inst != null) SoftButton(onClick = { VScreenDetector.setSwitchedOn(ctx, !on); tick++ }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (on) tr("关闭", "Turn off") else tr("开启", "Turn on"))
                }
            }
        },
        confirmButton = {
            when {
                inst == null -> {}
                !accepted -> LinkButton(onClick = { VScreenDetector.acceptLicense(ctx); tick++ }) { Text(tr("已了解，开启", "I understand, turn on")) }
                !inst.builtIn -> LinkButton(enabled = !busy, onClick = { DetectorModels.remove(ctx); tick++ }) { Text(tr("改回内置模型", "Back to built-in")) }
                else -> LinkButton(enabled = !busy, onClick = {
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
                }) { Text(tr("下载大模型（约 40 MB，更慢）", "Download large model (~40 MB, slower)")) }
            }
        },
        dismissButton = {
            LinkButton(enabled = !busy, onClick = { open = false }) { Text(if (accepted) tr("完成", "Done") else tr("暂不", "Not now")) }
        },
    )
}
