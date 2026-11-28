package com.kilon.agentvscreen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.kilon.agentvscreen.core.PlayAudio
import com.kilon.agentvscreen.core.VScreenKnowledge
import com.kilon.agentvscreen.core.VScreenMute
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 1.4.0-beta13 (rainbow: a place to mute and unmute apps by hand; agents can do the same with `sound`). One page for
 * app sound: the screen's own muting, the apps that are silent now (whoever did it) with a way back, the apps muted by
 * hand, and the apps that keep their sound on the screen. Every change goes through [VScreenMute], as `sound` does.
 */
@Composable
fun SoundPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    // 1.4.2: the last reading shows at once while a fresh one runs (it reads every package: seconds on a full phone).
    var silent by remember { mutableStateOf<List<PlayAudio.Hit>?>(VScreenMute.lastSilenced) }
    var scanning by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true: mute an app, false: an app that keeps its sound
    var screenMute by remember { mutableStateOf(VScreenMute.enabled) }
    LaunchedEffect(tick) { scanning = true; withContext(Dispatchers.IO) { VScreenMute.silenced() }?.let { silent = it }; scanning = false }
    fun label(p: String) = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
    fun said(c: VScreenMute.Change, done: String) = when {
        c.ok -> done
        c.why == "no-shizuku" -> tr("需要 Shizuku 已连接。", "Shizuku must be connected.")
        c.why == "denied" -> tr("系统不允许修改：小米 / 红米请在开发者选项里打开“USB 调试（安全设置）”。", "The system refused: on Xiaomi / Redmi turn on \"USB debugging (Security settings)\" in developer options.")
        else -> tr("没有改成，系统读回来还是原样。", "It did not change; the system still reads the same.")
    }
    // rainbow (beta13): a tap gave no sign of work and the list kept the restored apps, so people tapped again.
    // Every change now has one pending state: the tapped row shows a spinner and what it is doing, every other row
    // waits, and the list takes the change from the change's own verified reading at once (no second full scan).
    var busy by remember { mutableStateOf<String?>(null) }
    fun run(key: String, op: () -> Pair<String, ((List<PlayAudio.Hit>) -> List<PlayAudio.Hit>)?>) {
        if (busy != null) return
        busy = key
        scope.launch {
            val (m, update) = withContext(Dispatchers.IO) { op() }
            if (update != null) silent = silent?.let(update) else tick++
            busy = null; msg = m
        }
    }
    @Composable fun Working(t: String) = Row(verticalAlignment = Alignment.CenterVertically) {
        InfiniteProgressIndicator(size = 16.dp, color = MiuixTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Value(t)
    }
    val noShizuku = tr("需要 Shizuku 已连接。", "Shizuku must be connected.")
    fun repairAll(): Pair<String, ((List<PlayAudio.Hit>) -> List<PlayAudio.Hit>)?> = VScreenMute.repair()?.let { r ->
        (tr("恢复了 ${r.fixed.size} 个，回到应用重新点一次播放。", "Restored ${r.fixed.size}; start playing again in the app.") +
            (if (r.failed.isNotEmpty()) tr("${r.failed.size} 个没恢复。", " ${r.failed.size} could not be restored.") else "") +
            (if (r.denied) tr("系统不允许修改：小米 / 红米请打开“USB 调试（安全设置）”。", " The system refused: on Xiaomi / Redmi turn on \"USB debugging (Security settings)\".") else "")) to { _: List<PlayAudio.Hit> -> r.failed }
    } ?: (noShizuku to null)

    MiuixPage(tr("应用静音", "App sound")) {
        Group {
            SwitchPreference(checked = screenMute, onCheckedChange = { screenMute = it; VScreenMute.enabled = it },
                title = tr("副屏静音", "Mute the screen"), summary = tr("副屏中的应用静音，移出副屏后恢复", "Apps on the screen are silent until they leave it"))
        }
        Group(tr("当前已静音的应用", "Silent now")) {
            val l = silent
            when {
                scanning && l == null -> BasicComponent(title = tr("正在检查…", "Checking…"))
                l == null -> BasicComponent(title = tr("需要 Shizuku 已连接", "Shizuku must be connected"))
                l.isEmpty() -> BasicComponent(title = tr("没有", "None"))
                else -> l.forEach { h ->
                    val why = when {
                        h.user != 0 -> tr("分身", "clone")
                        h.pkg in VScreenMute.manual() -> tr("手动静音", "muted by hand")
                        h.pkg in VScreenMute.mutedNow() -> tr("在副屏上，离开后恢复", "on the screen, back when it leaves")
                        else -> tr("被留下的静音", "left silent")
                    }
                    val key = "${h.user}/${h.pkg}"
                    ArrowPreference(title = label(h.pkg), summary = why, enabled = busy == null || busy == key,
                        endActions = { if (busy == key) Working(tr("正在恢复…", "Unmuting…")) else Value(tr("恢复", "Unmute")) }, onClick = {
                        run(key) {
                            if (h.user != 0) repairAll()
                            else VScreenMute.unmute(h.pkg).let { c -> said(c, tr("${label(h.pkg)} 的声音恢复了，回到应用重新点一次播放。", "${label(h.pkg)} has its sound back; start playing again in the app.")) to
                                (if (c.ok) { l: List<PlayAudio.Hit> -> l.filterNot { it.user == 0 && it.pkg == h.pkg } } else null) }
                        }
                    })
                }
            }
            if (scanning && l != null) BasicComponent(title = tr("正在重新检查…", "Checking again…"), endActions = { InfiniteProgressIndicator(size = 16.dp, color = MiuixTheme.colorScheme.primary) })
            if (!l.isNullOrEmpty() && l.size > 1) ArrowPreference(title = tr("全部恢复", "Unmute all"), enabled = busy == null || busy == "all",
                summary = if (busy == "all") tr("逐个恢复并核实，应用多时要几十秒", "Each one is restored and read back; many apps take a while") else null,
                endActions = { if (busy == "all") Working(tr("正在恢复…", "Unmuting…")) },
                onClick = { run("all") { repairAll() } })
            ArrowPreference(title = tr("手动静音应用", "Mute an app"), summary = tr("在所有屏幕上静音，直到手动恢复", "Silent on every screen until you unmute it"),
                enabled = busy == null || busy == "mute", endActions = { if (busy == "mute") Working(tr("正在静音…", "Muting…")) }, onClick = { picking = true })
        }
        Group(tr("副屏中仍出声的应用", "Keeps its sound on the screen")) {
            val plays = VScreenMute.plays().sortedBy { label(it) }
            plays.forEach { p -> ArrowPreference(title = label(p), endActions = { Value(tr("移除", "Remove")) }, onClick = { VScreenMute.setPlays(p, false); tick++ }) }
            ArrowPreference(title = tr("添加应用", "Add an app"), onClick = { picking = false })
        }
        Text(tr("已经在播放的声音，系统要等应用重新播放才会改变。agent 也可以用 sound 命令做同样的事。", "Sound already playing changes only when the app plays again. Agents can do the same with the sound command."),
            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
    picking?.let { mute ->
        var q by remember { mutableStateOf("") }
        var apps by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
        LaunchedEffect(q) { apps = withContext(Dispatchers.IO) { VScreenKnowledge.findApps(q.trim().ifEmpty { null }).let { if (q.isBlank()) it.sortedBy { a -> a.first } else it }.take(60) } }
        GlassDialog(onDismissRequest = { picking = null },
            title = { DialogTitle(if (mute) tr("静音哪个应用", "Mute which app") else tr("哪个应用在副屏里也出声", "Which app keeps its sound")) },
            text = {
                Column {
                    AvsField(value = q, onValueChange = { q = it.take(30) }, placeholder = tr("搜索应用", "Search apps"))
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        apps.forEach { (name, pkg) ->
                            Text(name, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable {
                                picking = null
                                if (mute) run("mute") { said(VScreenMute.mute(pkg), tr("$name 已静音，直到你在这里恢复。", "$name is muted until you unmute it here.")) to null }
                                else { VScreenMute.setPlays(pkg, true); tick++ }
                            }.padding(vertical = 10.dp))
                        }
                    }
                }
            },
            confirmButton = { LinkButton(onClick = { picking = null }) { Text(tr("取消", "Cancel")) } })
    }
    msg?.let { m ->
        GlassDialog(onDismissRequest = { msg = null }, title = { DialogTitle(tr("应用静音", "App sound")) },
            text = { Text(m, fontSize = 14.sp, lineHeight = 21.sp) },
            confirmButton = { LinkButton(onClick = { msg = null }) { Text(tr("知道了", "Got it")) } })
    }
}
