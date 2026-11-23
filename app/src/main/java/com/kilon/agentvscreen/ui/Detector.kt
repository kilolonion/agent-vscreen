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
 * 2.0: the optional textless-element detector, one row in Settings. Tap: a glass dialog that says what it is and its
 * license in one line, then download (first host that answers, sha256 checked), switch on/off, or remove.
 */
@Composable
fun DetectorPreference() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    val inst = remember(tick) { DetectorModels.installed(ctx) }
    val on = remember(tick) { VScreenDetector.active(ctx) }
    ArrowPreference(title = tr("无字图标识别", "Textless icon finder"),
        summary = tr("可选下载，帮 agent 找到没有文字的按钮", "Optional download; helps agents find buttons without words"),
        endActions = { Value(when { inst == null -> tr("未安装", "Not installed"); on -> tr("已开启", "On"); else -> tr("已关闭", "Off") }) },
        onClick = { open = true })
    if (!open) return
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var pct by remember { mutableIntStateOf(-1) }
    GlassDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { DialogTitle(tr("无字图标识别", "Textless icon finder")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("在本机找出图标、×、开关这类没有文字的可点目标，agent 的元素列表里会多出“（无文字 图标）”，可以直接 tap #编号。模型单独下载（约 5 MB，${DetectorModels.LICENSE} 许可），不装也照常使用。",
                    "Finds tappable things without words (icons, ×, switches) on this phone; the agent's element list gains \"(no text icon)\" entries it can tap by #number. Downloaded separately (about 5 MB, ${DetectorModels.LICENSE}); everything works without it."),
                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(VScreenDetector.status(ctx), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (pct >= 0) Text(tr("下载中 $pct%", "Downloading $pct%"), fontSize = 13.sp)
                note?.let { Text(it, fontSize = 12.sp, color = Palette.stopped) }
                if (inst != null) SoftButton(onClick = { VScreenDetector.setSwitchedOn(ctx, !VScreenDetector.switchedOn(ctx)); tick++ }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (VScreenDetector.switchedOn(ctx)) tr("关闭", "Turn off") else tr("开启", "Turn on"))
                }
            }
        },
        confirmButton = {
            LinkButton(enabled = !busy, onClick = {
                busy = true; note = null; pct = 0
                scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        runCatching {
                            val (offers, errors) = DetectorModels.fetchOffers()
                            val pick = offers.firstOrNull { it.default } ?: offers.firstOrNull()
                                ?: error(tr("所有下载源都连不上：", "No download source answered: ") + errors.joinToString("; "))
                            DetectorModels.install(ctx, pick) { got, total -> pct = (got * 100 / maxOf(1L, total)).toInt() }
                        }.exceptionOrNull()
                    }
                    busy = false; pct = -1; tick++
                    note = err?.let { tr("没装上：", "Not installed: ") + (it.message ?: it.javaClass.simpleName) }
                }
            }) { Text(if (inst == null) tr("下载", "Download") else tr("重新下载", "Download again")) }
        },
        dismissButton = {
            if (inst != null) LinkButton(enabled = !busy, onClick = { DetectorModels.remove(ctx); tick++ }) { Text(tr("删除", "Remove")) }
            else LinkButton(enabled = !busy, onClick = { open = false }) { Text(tr("关闭", "Close")) }
        },
    )
}
