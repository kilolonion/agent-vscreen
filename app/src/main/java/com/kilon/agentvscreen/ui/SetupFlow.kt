package com.kilon.agentvscreen.ui

import top.yukonga.miuix.kmp.basic.Text
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.host.Liveness
import com.kilon.agentvscreen.host.Setup
import com.kilon.agentvscreen.host.ShizukuManager
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 1.4 首次配置: one step per screen. The step says what it is for and has one action; optional steps can be
 * skipped; the full list stays below so nothing is hidden. Finished steps drop out by themselves (polled every 2 s).
 */

private val OPTIONAL = setOf("accessibility", "vendor_bg", "detector")

@Composable
fun SetupFlowPage() {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val host = LocalHost.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val sh by ShizukuManager.snapshot.collectAsState()
    val pv by com.kilon.agentvscreen.host.Privileged.snapshot.collectAsState()
    val rootPhase by com.kilon.agentvscreen.host.RootBackend.phaseFlow.collectAsState()
    val root = pv.via == com.kilon.agentvscreen.host.Privileged.Via.ROOT
    val suVisible = remember { com.kilon.agentvscreen.host.RootBackend.suVisible() }
    val api37Plus = android.os.Build.VERSION.SDK_INT >= 37
    val skipped = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(emptyList<String>()) }
    val items = app.items
    val todo = items.filter { !it.ok && it.key !in skipped }
    val step = todo.firstOrNull()
    fun refresh() = scope.launch { app.items = withContext(Dispatchers.IO) { runCatching { Setup.check(ctx) }.getOrDefault(app.items) } }
    LaunchedEffect(sh, pv, rootPhase) { refresh() }
    fun choose(v: com.kilon.agentvscreen.host.Privileged.Via) = scope.launch {
        withContext(Dispatchers.IO) { com.kilon.agentvscreen.host.Privileged.choose(ctx, v) }
        kotlinx.coroutines.delay(600); refresh()
    }
    fun open(i: Intent) { runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { host.say(tr("这台手机打不开这个设置页", "This phone cannot open that settings page")) } }

    MiuixPage(tr("首次配置", "Setup"), subtitle = if (root) tr("用 Root，不需要 Shizuku", "Root, no Shizuku needed") else tr("支持 Shizuku / 兼容分支，不占 adb", "Shizuku / compatible forks, adb untouched")) {
        if (items.isEmpty()) { Footnote(tr("正在检查…", "Checking…")); return@MiuixPage }
        // Progress: one dot per item, filled when done.
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(
                if (it.ok) Palette.takeover else if (it == step) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.dividerLine)) }
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (step == null) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(Palette.takeover.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Text("✓", fontSize = 28.sp, color = Palette.takeover, fontWeight = FontWeight.Bold)
                    }
                    Text(tr("都配好了", "All set"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (skipped.isEmpty()) tr("现在可以去接入 agent。", "You can connect an agent now.") else tr("跳过的可选项在下面的列表里，随时能补上。", "Skipped optional items are in the list below; add them any time."),
                        fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
                    PrimaryButton(onClick = { nav.back(); nav.go(Route.Connect) }, modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.primary) {
                        Text(tr("接入 agent", "Connect an agent"), color = MiuixTheme.colorScheme.onPrimary)
                    }
                } else {
                    val n = items.indexOf(step) + 1
                    Text(tr("第 $n 步，共 ${items.size} 步", "Step $n of ${items.size}") + if (step.key in OPTIONAL) tr(" · 可选", " · optional") else "",
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(step.label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(step.detail, fontSize = 14.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
                    val ready = pv.state == ShizukuManager.State.READY
                    val starting = root && rootPhase == com.kilon.agentvscreen.host.RootBackend.Phase.STARTING
                    val providerName = ShizukuManager.providerLabel() ?: "Shizuku"
                    val (label, act) = when {
                        step.key == "detector" -> tr("已了解，开启", "I understand, turn on") to {
                            com.kilon.agentvscreen.core.VScreenDetector.acceptLicense(ctx); refresh(); Unit
                        }
                        step.key == "privilege" && root -> (if (starting) tr("启动中…", "Starting…") else tr("用 Root 启动", "Start with root")) to {
                            choose(com.kilon.agentvscreen.host.Privileged.Via.ROOT); Unit
                        }
                        step.key == "privilege" -> when (sh.state) {
                            ShizukuManager.State.NOT_INSTALLED -> tr("安装 Shizuku", "Install Shizuku") to { ShizukuManager.openInstallPage(ctx) }
                            ShizukuManager.State.NOT_RUNNING -> tr("打开 $providerName", "Open $providerName") to { ShizukuManager.openShizukuApp(ctx) }
                            else -> tr("授权 $providerName", "Grant $providerName") to { host.grantShizuku(); refresh(); Unit }
                        }
                        step.key == "vendor_bg" -> tr("去系统后台管理", "Open background management") to {
                            val opened = Liveness.openBackgroundSettings(ctx)
                            host.say(tr("已打开：$opened", "Opened: $opened")); app.liveProblem = Liveness.problem(); Unit
                        }
                        ready && step.key != "accessibility" -> (if (busy) tr("配置中…", "Setting up…") else tr("自动开启", "Turn it on")) to {
                            busy = true
                            scope.launch { log = withContext(Dispatchers.IO) { Setup.grantAll(ctx) }; busy = false; Overlay.refresh(); refresh() }; Unit
                        }
                        else -> tr("去系统设置打开", "Open system settings") to {
                            open(when (step.key) {
                                "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                                else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                            })
                        }
                    }
                    PrimaryButton(onClick = act, enabled = !busy && !starting, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = MiuixTheme.colorScheme.primary) {
                        Text(label, color = MiuixTheme.colorScheme.onPrimary)
                    }
                    // Promote explicit Root fallback when su is visible, Shizuku failed/missing, or on Android 17+.
                    if (step.key == "privilege" && !root && (suVisible || api37Plus || sh.state != ShizukuManager.State.READY)) {
                        SoftButton(
                            onClick = { choose(com.kilon.agentvscreen.host.Privileged.Via.ROOT) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (suVisible) tr("检测到 Root：改用 Root（无需 Shizuku）", "Root detected: use Root (no Shizuku needed)")
                                else tr("改用 Root（无需 Shizuku）", "Use Root instead (no Shizuku needed)"),
                            )
                        }
                    }
                    if (step.key == "privilege" && !root && api37Plus) {
                        Text(
                            tr("Android 17+ 上官方 Shizuku 可能受限，可使用兼容分支（如 AXManager / Sui）或直接改用 Root。",
                                "On Android 17+, official Shizuku may be limited; compatible forks (such as AXManager / Sui) or Root are supported."),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (step.key == "privilege") Row(verticalAlignment = Alignment.CenterVertically) {
                        if (root) {
                            LinkButton(onClick = { choose(com.kilon.agentvscreen.host.Privileged.Via.SHIZUKU) }) {
                                Text(tr("改用 Shizuku / 兼容分支", "Use Shizuku / compatible fork"))
                            }
                        }
                        Help(tr("Shizuku、兼容分支与 Root", "Shizuku, forks and Root"), H.privilege)
                    }
                    if (step.key in OPTIONAL) LinkButton(onClick = {
                        skipped += step.key
                        if (step.key == "detector") { com.kilon.agentvscreen.core.VScreenDetector.decline(ctx); refresh() }
                    }) { Text(tr("跳过这一步", "Skip this step")) }
                    if (step.key == "accessibility" && ready) Text(tr("无障碍只能由你在系统设置里打开。", "Only you can turn accessibility on, in system settings."),
                        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        log.forEach { Footnote(it) }
        Group(tr("全部项目", "All items")) {
            items.forEach { it ->
                BasicComponent(title = it.label + if (it.key in OPTIONAL) tr("（可选）", " (optional)") else "", summary = it.detail,
                    startAction = { Box(Modifier.padding(end = 12.dp)) { Dot(if (it.ok) Palette.takeover else if (it.key in OPTIONAL) Palette.idle else Palette.stopped, 10.dp) } },
                    onClick = if (!it.ok && it.key in skipped) ({ skipped -= it.key }) else null)
            }
        }
        Footnote(H.setup)
    }
}
