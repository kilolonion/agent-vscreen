package com.kilon.agentvscreen.ui

import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.rotate
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.tr
import com.kilon.agentvscreen.R
import com.kilon.agentvscreen.api.AgentGuide
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.host.A11yHeal
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.Prefs
import com.kilon.agentvscreen.host.Setup
import com.kilon.agentvscreen.host.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home: is it running, is it set up, how an agent connects, the few settings, and diagnostics. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AvsTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Home() } } }
    }

    override fun onResume() {
        super.onResume()
        // In front now, so a foreground service may start: repair a start the system refused in the background,
        // and retry a port that was taken.
        if (Prefs.serviceOn(this)) HostService.start(this)
        Overlay.refresh()
    }

    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, tr("已复制：$label", "Copied: $label"), Toast.LENGTH_SHORT).show()
    }

    private fun diagnostics(items: List<Setup.Item>): String {
        val s = BackgroundOperation.state.value
        return buildString {
            appendLine("Agent-VScreen ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("服务：${if (HostService.isAlive) "运行中" else "已停止"} · 127.0.0.1:${ApiConfig.port} · ${HostService.server?.lastError ?: "无错误"}")
            appendLine("后台：${com.kilon.agentvscreen.host.Liveness.json()}")
            appendLine("连接：${s.connection}${s.connectionError?.let { "（$it）" } ?: ""} · 副屏 ${s.displayId ?: "无"} ${s.screenWidth}x${s.screenHeight} · 会话 ${s.ownerSessionId ?: "-"} · ${Palette.word(modeOf(s))}")
            items.forEach { appendLine("${if (it.ok) "✓" else "✗"} ${it.label}：${it.detail}") }
            appendLine("无障碍自动恢复：${A11yHeal.lastResult ?: "-"}")
            appendLine()
            append(com.kilon.agentvscreen.cmd.Diag.text(this@MainActivity))
        }
    }

    @Composable
    private fun Home() {
        val scope = rememberCoroutineScope()
        val s by BackgroundOperation.state.collectAsState()
        val sh by ShizukuManager.snapshot.collectAsState()
        var tick by remember { mutableIntStateOf(0) }
        var items by remember { mutableStateOf(emptyList<Setup.Item>()) }
        var setupLog by remember { mutableStateOf<List<String>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var serviceOn by remember { mutableStateOf(Prefs.serviceOn(this)) }
        var setupOpen by remember { mutableStateOf(false) }
        var liveProblem by remember { mutableStateOf(com.kilon.agentvscreen.host.Liveness.problem()) }
        LaunchedEffect(tick) {
            while (true) {
                items = withContext(Dispatchers.IO) { runCatching { BackgroundOperation.refreshStatus(); Setup.check(this@MainActivity) }.getOrDefault(items) }
                liveProblem = com.kilon.agentvscreen.host.Liveness.problem()
                delay(2_000)
            }
        }
        val allOk = items.isNotEmpty() && items.filter { it.key != "accessibility" }.all { it.ok }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConsentCards()
            Hero(s, serviceOn) { on ->
                serviceOn = on; Prefs.setServiceOn(this@MainActivity, on)
                if (on) HostService.start(this@MainActivity) else { BackgroundOperation.stopAndRelease(tr("用户停止了服务", "The user stopped the service")); HostService.stop(this@MainActivity) }
                tick++
            }

            if (s.userStopped) Banner(
                tr("你停止了 AI", "You stopped the AI"), run {
                    val at = if (s.stoppedAt > 0) java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(s.stoppedAt)) else null
                    tr("你在悬浮窗上按了“停止 AI”${at?.let { "（$it）" } ?: ""}。在你允许之前，agent 的每个操作都会被拒绝，它也会被告知来找你。关掉悬浮窗不会解除它。",
                        "You pressed \"Stop AI\" on the floating window${at?.let { " ($it)" } ?: ""}. Until you allow it, every action of the agent is refused and it is told to come to you. Closing the floating window does not lift it.")
                }, Palette.stopped,
                tr("允许继续", "Allow again"),
            ) { BackgroundOperation.userResume() }
            if (s.windowHidden && s.displayId != null && !s.userStopped) Banner(
                tr("小窗已收起", "Floating window hidden"), tr("AI 还在后台工作。下一轮开始时小窗会自己回来，也可以现在打开。", "The AI is still working in the background. The window comes back by itself on the next turn, or open it now."), Palette.standby, tr("显示小窗", "Show window"),
            ) { BackgroundOperation.showWindow() }

            liveProblem?.let { p ->
                Banner(tr("后台运行受限", "Background running restricted"), p, Palette.stopped, tr("去设置", "Settings")) {
                    val opened = com.kilon.agentvscreen.host.Liveness.openBackgroundSettings(this@MainActivity)
                    liveProblem = com.kilon.agentvscreen.host.Liveness.problem(); tick++
                    Toast.makeText(this@MainActivity, tr("已打开：$opened。把 Agent-VScreen 设为允许自启动、关联启动和后台活动", "Opened: $opened. Allow Agent-VScreen to auto-start, be started by other apps and run in the background"), Toast.LENGTH_LONG).show()
                }
            }

            LanguageCard()

            // ── setup ──
            val done = items.count { it.ok }
            Block(
                title = tr("首次配置", "First-time setup"),
                trailing = { Text("$done/${items.size}", color = if (allOk) Palette.takeover else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) },
                onHeader = if (allOk) ({ setupOpen = !setupOpen }) else null,
                open = if (allOk) setupOpen else null,
            ) {
                if (allOk && !setupOpen) {
                    Text(tr("全部就绪。只用 Shizuku：不占用 adb、不改无线调试，你的 agent 自己的 adb / Shizuku 照常用。", "All set. Shizuku only: adb and wireless debugging stay untouched, so your agent's own adb / Shizuku keep working."), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@Block
                }
                Text(tr("只用 Shizuku 这一条可共享的特权通道：不占用 adb，不改无线调试，不重启 adbd。每一项都只把本应用“加进去”，不动别的应用的设置。", "Uses only Shizuku, a privilege channel that can be shared: adb is not taken, wireless debugging is not changed, adbd is not restarted. Each item only adds this app and leaves other apps' settings alone."),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                items.forEachIndexed { i, it -> StepRow(i + 1, it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    when (sh.state) {
                        ShizukuManager.State.NEED_PERMISSION -> Button(onClick = { ShizukuManager.requestPermission(); tick++ }) { Text(tr("授权 Shizuku", "Grant Shizuku")) }
                        ShizukuManager.State.NOT_RUNNING -> Button(onClick = { ShizukuManager.openShizukuApp(this@MainActivity) }) { Text(tr("打开 Shizuku 启动", "Open Shizuku to start it")) }
                        ShizukuManager.State.NOT_INSTALLED -> Button(onClick = { ShizukuManager.openInstallPage(this@MainActivity) }) { Text(tr("安装 Shizuku", "Install Shizuku")) }
                        ShizukuManager.State.READY -> Button(enabled = !busy && items.any { !it.ok }, onClick = {
                            busy = true
                            scope.launch { setupLog = withContext(Dispatchers.IO) { Setup.grantAll(this@MainActivity) }; busy = false; Overlay.refresh(); tick++ }
                        }) { Text(if (busy) tr("配置中…", "Setting up…") else tr("一键配置其余项", "Set up the rest")) }
                    }
                    if (items.any { it.key == "vendor_bg" && !it.ok }) Button(onClick = {
                        val opened = com.kilon.agentvscreen.host.Liveness.openBackgroundSettings(this@MainActivity)
                    liveProblem = com.kilon.agentvscreen.host.Liveness.problem(); tick++
                        Toast.makeText(this@MainActivity, tr("已打开：$opened。把 Agent-VScreen 改成手动管理，允许自启动、关联启动和后台活动", "Opened: $opened. Set Agent-VScreen to manual management and allow auto-start, start by other apps and background activity"), Toast.LENGTH_LONG).show()
                        tick++
                    }) { Text(tr("去设置后台管理", "Background management")) }
                    OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text(tr("无障碍设置", "Accessibility")) }
                    if (!Settings.canDrawOverlays(this@MainActivity)) OutlinedButton(onClick = {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    }) { Text(tr("悬浮窗权限", "Overlay permission")) }
                }
                setupLog.forEach { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            Connect()

            // ── permissions: how freely the agent works, per app, per agent ──
            var tokensOpen by remember { mutableStateOf(false) }
            Block(tr("权限", "Permissions")) {
                SubTitle(tr("agent 能自己做多少", "How much the agent does on its own"))
                AutonomyPanel()
                SubTitle(tr("应用限制", "App rules"))
                VScreenAppRules()
                Disclosure(tr("令牌（给每个 agent 单独的权限）", "Tokens (separate permissions per agent)"), tokensOpen) { tokensOpen = !tokensOpen }
                if (tokensOpen) TokensPanel(ApiConfig.baseUrl) { l, t -> copy(l, t) }
            }

            // ── settings: the window and how the screen runs ──
            Block(tr("设置", "Settings")) {
                SubTitle(tr("悬浮窗", "Floating window"))
                Toggle(tr("显示悬浮窗", "Show the floating window"), tr("有副屏时在所有应用上方显示。拖动移动，双指缩放，贴边收起，长按画面 1 秒接管", "Shown above all apps while there is a screen. Drag to move, pinch to resize, push to an edge to fold, hold the picture 1 s to take over"), s.showPreview) { BackgroundOperation.setShowPreview(it); if (it) Overlay.refresh() }
                SubTitle(tr("副屏", "Background screen"))
                val idleSave by com.kilon.agentvscreen.core.VScreenStore.idlePowerSave.collectAsState()
                Toggle(tr("空闲省电", "Idle power saving"), tr("一轮结束后暂停副屏渲染，页面保留，下一条命令自动恢复；个别应用恢复时会重新加载，遇到可以关掉", "Pauses rendering after a turn ends; the page is kept and the next command resumes it. A few apps reload on resume: turn this off if you see that"), idleSave) {
                    BackgroundOperation.setIdlePowerSave(it)
                }
                Text(tr("一轮的长度", "Turn length"), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(tr("agent 安静多久算这轮结束：之后副屏省电暂停，10 分钟后释放（agent 也可以用 vscreen done 马上结束）", "How long the agent stays quiet before its turn ends: the screen then pauses to save power and is released after 10 minutes (the agent can also end at once with vscreen done)"),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var quiet by remember { mutableIntStateOf(ApiConfig.quietMinutes) }
                val opts = listOf(1, 3, 5, 10, 30)
                Segments(opts.map { tr("${it}分", "$it min") }, opts.indexOf(quiet)) { i -> ApiConfig.quietMinutes = opts[i]; quiet = opts[i] }
                SubTitle(tr("识别和记录", "Reading and recording"))
                Toggle(tr("精确文字识别", "Accurate text recognition"), tr("PP-OCR，在本机运行", "PP-OCR, runs on this phone"), s.accurateOcr) { BackgroundOperation.setAccurateOcr(it) }
                Toggle(tr("后台录制", "Background recording"), tr("【这段时间】里的拼图", "The picture strip in [Meanwhile]"), s.recording) { BackgroundOperation.setRecording(it) }
            }

            // ── what happened ──
            var auditOpen by remember { mutableStateOf(false) }
            Block(tr("操作日志", "Activity log"), onHeader = { auditOpen = !auditOpen }, open = auditOpen) {
                if (auditOpen) { AuditPanel { l, t -> copy(l, t) }; EventTargetRow() }
                else Text(tr("agent 做过的每条命令、每次确认：谁、什么时候、结果。", "Every command and confirmation: who, when, the result."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Diagnostics(items)
            Text(tr("Agent-VScreen ${BuildConfig.VERSION_NAME} ", "Agent-VScreen ${BuildConfig.VERSION_NAME}"), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
        }
    }

    /**
     * Its own card just above the setup, folded to one bilingual line by default, so someone who cannot read the
     * current language finds it without scrolling.
     */
    @Composable
    private fun LanguageCard() {
        var open by remember { mutableStateOf(false) }
        val names = listOf(tr("跟随系统", "System"), "中文", "English")
        Block(
            title = "语言 / Language",
            trailing = { Text(names[I18n.Lang.entries.indexOf(I18n.choice)], color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) },
            onHeader = { open = !open },
            open = open,
        ) {
            if (!open) return@Block
            Text(tr("App 界面、悬浮窗、通知，以及给 agent 的话和命令的返回都会换成这种语言", "Used for the app, the floating window, notifications, and everything said to the agent (messages and command replies)"),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Segments(names, I18n.Lang.entries.indexOf(I18n.choice)) { i ->
                if (I18n.Lang.entries[i] == I18n.choice) return@Segments
                I18n.choice = I18n.Lang.entries[i]
                Overlay.refresh()
                if (HostService.isAlive) HostService.start(this@MainActivity)
                recreate()
            }
        }
    }

    @Composable
    private fun Hero(s: BackgroundOperation.State, serviceOn: Boolean, setOn: (Boolean) -> Unit) {
        val mode = modeOf(s)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2B1F7A), Color(0xFF151A3D), Color(0xFF0B2A44))))
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_logo), null, Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Agent-VScreen", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(tr("给任何手机 agent 用的后台副屏", "A background screen for any phone agent"), color = Color(0xB3FFFFFF), fontSize = 13.sp)
                    }
                    Switch(checked = serviceOn, onCheckedChange = setOn)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ok = HostService.isAlive && HostService.server?.lastError == null
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Palette.takeover else Palette.stopped))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (HostService.isAlive) (HostService.server?.lastError ?: tr("服务运行中 · 127.0.0.1:${ApiConfig.port}", "Service running · 127.0.0.1:${ApiConfig.port}")) else tr("服务已停止", "Service stopped"),
                        color = Color.White, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (HostService.isAlive && HostService.server?.lastError != null)
                        if (HostService.server?.portBusy == true)
                            TextButton(onClick = {
                                val np = if (ApiConfig.port >= 65535) ApiConfig.DEFAULT_PORT + 1 else ApiConfig.port + 1
                                ApiConfig.setPort(np); HostService.server?.start(np)
                                Toast.makeText(this@MainActivity, tr("端口改成 $np：请在“接入 agent”里重新复制接入命令给 agent", "Port changed to $np: copy the connect message for your agent again under \"Connect an agent\""), Toast.LENGTH_LONG).show()
                            }) { Text(tr("换端口", "Change port"), color = Color.White) }
                        else TextButton(onClick = { HostService.server?.start(ApiConfig.port) }) { Text(tr("立即重试", "Retry now"), color = Color.White) }
                }
                // The screen right now.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 30.dp, height = 46.dp).glowBorder(if (s.displayId == null) Mode.IDLE else mode, 7.dp, width = 2.dp, pad = 4.dp)
                        .clip(RoundedCornerShape(7.dp)).background(Palette.ink), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.padding(bottom = 4.dp).width(12.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xB3FFFFFF)))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (s.displayId == null) tr("还没有副屏", "No screen yet") else Palette.word(mode), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            if (s.displayId == null) tr("agent 第一次 launch 时自动创建", "Created automatically on the agent's first launch")
                            else (s.agentTitle ?: tr("副屏 ${s.screenWidth}×${s.screenHeight} · 会话 ${s.ownerSessionId ?: "-"}", "Screen ${s.screenWidth}×${s.screenHeight} · session ${s.ownerSessionId ?: "-"}")),
                            color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (s.displayId != null) TextButton(onClick = { startActivity(Intent(this@MainActivity, FullscreenActivity::class.java)) }) {
                        Text(tr("查看", "View"), color = Color.White)
                    }
                }
            }
        }
    }

    // One look for every action in the connect card: a filled pill for the one thing to do, outlined pills of the same
    // size for the rest, a left-aligned row with a chevron for anything that folds. Labels stay on one line; the
    // explanation goes in the hint above, not in the button.
    @Composable
    private fun Hint(t: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
        Text(t, fontSize = 12.sp, lineHeight = 18.sp, color = color, modifier = Modifier.fillMaxWidth())

    @Composable
    private fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) =
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }

    @Composable
    private fun SecondaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) =
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }

    @Composable
    private fun Disclosure(text: String, open: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
        }
    }

    @Composable
    private fun SubTitle(t: String) = Text(t, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))

    @Composable
    private fun Segments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, t ->
                SegmentedButton(selected = selected == i, onClick = { onSelect(i) }, shape = SegmentedButtonDefaults.itemShape(i, labels.size), icon = {}) {
                    // Labels shrink to fit instead of being cut ("10 min" lost its "min", "Tailscale" became "Tailsc…").
                    androidx.compose.foundation.text.BasicText(
                        t, maxLines = 1,
                        style = androidx.compose.material3.LocalTextStyle.current.copy(
                            color = androidx.compose.material3.LocalContentColor.current, fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 13.sp, stepSize = 0.5.sp),
                    )
                }
            }
        }
    }

    /**
     * 1.1.7: one card, top to bottom in the order the user decides: where the agent runs (and, for another device, the
     * Tailscale setup right there), how it connects, what to copy, the token. The "where" switch is the user's choice
     * and always shown: it used to appear only while a Tailscale address existed, so it vanished whenever the node
     * restarted or the address poll came back empty for a moment.
     */
    @Composable
    private fun Connect() {
        var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
        var where by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
        var token by remember { mutableStateOf(ApiConfig.token) }
        var showToken by remember { mutableStateOf(false) }
        var rawOpen by remember { mutableStateOf(false) }
        var mode by remember { mutableStateOf(ApiConfig.tailnetMode) }
        val ledger by com.kilon.agentvscreen.api.AgentLedger.snap.collectAsState()
        val ts by com.kilon.agentvscreen.host.Tailnet.status.collectAsState()
        var sysAddr by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { while (true) { HostService.server?.tailAddress?.let { sysAddr = it }; delay(3_000) } }
        // The address an agent on another device uses. Kept through short gaps (node restarting, an empty poll) so
        // what the user copies does not flip back to 127.0.0.1; dropped when the mode changes.
        val liveHost = when (mode) {
            com.kilon.agentvscreen.host.Tailnet.Mode.BUILTIN -> ts.ip?.takeIf { ts.loggedIn }
            com.kilon.agentvscreen.host.Tailnet.Mode.SYSTEM -> sysAddr
            else -> null
        }
        var lastHost by remember(mode) { mutableStateOf<String?>(null) }
        if (liveHost != null && liveHost != lastHost) lastHost = liveHost
        val remoteBase = (liveHost ?: lastHost)?.let { "http://$it:${ApiConfig.port}" }
        val remoteReady = where == 1 && remoteBase != null
        val base = if (remoteReady) remoteBase!! else ApiConfig.baseUrl
        val canCopy = where == 0 || remoteReady

        Block(tr("接入 agent", "Connect an agent")) {
            SubTitle(tr("1. agent 在哪", "1. Where the agent runs"))
            Segments(listOf(tr("这台手机上", "On this phone"), tr("其它设备上（Tailscale）", "Another device (Tailscale)")), where) { where = it }
            if (where == 0) {
                Hint(tr("同一台手机上的 agent（Termux、Minis、Operit 等）直接连本机 127.0.0.1:${ApiConfig.port}，什么都不用设。",
                    "Agents on this phone (Termux, Minis, Operit, ...) connect to 127.0.0.1:${ApiConfig.port} directly; nothing to set up."))
            } else {
                Remote(ts, remoteBase, mode) { mode = it }
                if (mode != com.kilon.agentvscreen.host.Tailnet.Mode.OFF) RemoteSafety()
            }

            SubTitle(tr("2. 接入方式", "2. How it connects"))
            Segments(listOf(tr("命令行", "Shell"), "MCP", "HTTP"), tab) { tab = it; rawOpen = false }
            Hint(when (tab) {
                0 -> tr("带终端的 agent：把“给 agent 的话”发给它。它会装好 vscreen 命令，并把“我能在手机上后台操作应用”记进自己的长期记忆，以后的新对话里也会用。",
                    "Agents with a terminal: send them the message. It installs the vscreen command and has the agent save \"I can operate apps on the phone in the background\" in its long-term memory, so new conversations use it too.")
                1 -> tr("支持 MCP 的 agent：发给它的话里有 MCP 配置（streamable HTTP，每条命令一个工具，截图直接附在结果里）和要它记住的能力说明。",
                    "Agents with MCP: the message has the MCP config (streamable HTTP, one tool per command, screenshots attached to results) and the ability note for it to remember.")
                else -> tr("自己写调用代码的 agent 应用：发给它的话里有地址、令牌和能力说明。返回 JSON（ok、code、next、text、images、steps、screen、notes），完整说明 GET /v2/help。",
                    "Agent apps that write their own calls: the message has the address, token and ability note. Replies are JSON (ok, code, next, text, images, steps, screen, notes); full reference at GET /v2/help.")
            })
            if (where == 1) Hint(
                if (remoteReady) tr("下面复制的内容用 Tailscale 地址 $remoteBase。", "What you copy below uses the Tailscale address $remoteBase.")
                else tr("先在上面连好 Tailscale，下面才能复制给其它设备的内容。", "Connect Tailscale above first; then you can copy what the other device needs."),
                if (remoteReady) Palette.takeover else Palette.stopped)
            PrimaryAction(tr("复制给 agent 的话", "Copy message for your agent"), enabled = canCopy) {
                copy(tr("给 agent 的话", "Message for your agent"), when (tab) { 1 -> AgentGuide.onboardMcp(base); 2 -> AgentGuide.onboardHttp(base); else -> AgentGuide.onboard(base) })
            }
            if (tab != 2) {
                Disclosure(if (tab == 0) tr("只看安装命令", "Install command only") else tr("只看 MCP 配置", "MCP config only"), rawOpen) { rawOpen = !rawOpen }
                AnimatedVisibility(rawOpen && canCopy) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tab == 0) {
                            Hint(tr("只把命令给 agent 的话，大多数 agent 装完就忘了，新对话里不知道有 vscreen。", "With only the command, most agents forget vscreen after installing it, and new conversations don't know about it."))
                            Code(ApiConfig.installLine(base)) { copy(tr("安装命令", "Install command"), ApiConfig.installLine(base)) }
                        } else Code(ApiConfig.mcpJson(base)) { copy(tr("MCP 配置", "MCP config"), ApiConfig.mcpJson(base)) }
                    }
                }
            }
            SecondaryAction(tr("复制能力说明", "Copy ability note"), enabled = canCopy) {
                copy(tr("能力说明", "Ability note"), when (tab) { 1 -> AgentGuide.memoryMcp(); 2 -> AgentGuide.memoryHttp(base); else -> AgentGuide.memory(base) })
            }
            Hint(tr("能力说明也可以直接贴进 agent 的“自定义指令”或“记忆”设置。", "You can also paste the ability note into the agent's custom instructions or memory setting."))
            AgentMemoryRow(ledger)

            SubTitle(tr("3. 令牌", "3. Token"))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (showToken) token else "•".repeat(12) + token.takeLast(4), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { showToken = !showToken }) { Icon(if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, tr("显示令牌", "Show token")) }
                IconButton(onClick = { copy(tr("令牌", "Token"), token) }) { Icon(Icons.Filled.ContentCopy, tr("复制令牌", "Copy token")) }
                IconButton(onClick = { ApiConfig.resetToken(); token = ApiConfig.token; Toast.makeText(this@MainActivity, tr("已换新令牌：旧的立即失效，各 agent 要重新接入", "New token: the old one stops working now, every agent has to connect again"), Toast.LENGTH_LONG).show() }) {
                    Icon(Icons.Filled.Refresh, tr("换一个令牌", "New token"))
                }
            }
            Hint(if (mode == com.kilon.agentvscreen.host.Tailnet.Mode.OFF)
                tr("令牌只给你信任的 agent：拿到它就能在副屏上操作你的应用。服务只监听本机，网页调用一律拒绝。", "Give the token only to agents you trust: with it they can operate your apps on the screen. The service listens only on this phone, and calls from web pages are always refused.")
            else tr("令牌只给你信任的 agent：拿到它就能在副屏上操作你的应用。服务只接受本机和你 Tailscale 网络里的设备，网页调用一律拒绝。", "Give the token only to agents you trust: with it they can operate your apps on the screen. Only this phone and devices in your Tailscale network can connect, and calls from web pages are always refused."))
        }
    }

    /** 1.1.6: did the agent keep VScreen in mind? Facts only (see AgentLedger). */
    @Composable
    private fun AgentMemoryRow(l: com.kilon.agentvscreen.api.AgentLedger.Snapshot) {
        if (!l.installed) return
        val fmt = remember(I18n.en) { java.text.SimpleDateFormat(tr("M月d日 HH:mm", "MMM d HH:mm"), if (I18n.en) java.util.Locale.US else java.util.Locale.CHINA) }
        fun at(t: Long) = fmt.format(java.util.Date(t))
        val (color, title, body) = when {
            l.cameBack -> Triple(Palette.takeover, tr("agent 记住了", "The agent remembers"),
                tr("安装后，agent 在隔了一段时间的新对话里又主动用了 ${l.comebacks} 次（最近 ${at(l.lastComebackAt)}）。", "Since installing, the agent came back on its own in a later conversation ${l.comebacks} times (last ${at(l.lastComebackAt)}). ") +
                    (l.rememberedWhere?.let { tr("它说存在了「$it」。", "It says it saved it in \"$it\".") } ?: ""))
            l.remembered -> Triple(Palette.standby, tr("agent 说已记住", "The agent says it saved it"),
                tr("存在了「${l.rememberedWhere}」（${at(l.rememberedAt)}）。等它在新对话里主动用起来，这里会变成“记住了”。", "Saved in \"${l.rememberedWhere}\" (${at(l.rememberedAt)}). Once it uses vscreen on its own in a new conversation, this turns into \"remembers\"."))
            else -> Triple(Palette.stopped, tr("agent 还没确认记住", "The agent has not confirmed it saved it"),
                tr("${at(l.installAt)} 装好了 vscreen，但 agent 没有报告把说明存在哪。新对话里它可能不知道自己有这个能力：再把“给 agent 的话”发给它一次，或把能力说明放进它的自定义指令。",
                    "vscreen was installed ${at(l.installAt)}, but the agent has not said where it saved the note. New conversations may not know they have this ability: send the message for your agent again, or put the ability note in its custom instructions."))
        }
        StatusCard(color, title, body)
    }

    @Composable
    private fun StatusCard(color: Color, title: String, body: String) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.10f))
                .border(BorderStroke(1.dp, color.copy(alpha = 0.45f)), RoundedCornerShape(14.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
            if (body.isNotEmpty()) Text(body, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    /** 1.1.6: other devices: off / built-in Tailscale (no app needed) / the Tailscale app on this phone. */
    @Composable
    private fun Remote(ts: com.kilon.agentvscreen.host.Tailnet.Status, remoteBase: String?, mode: com.kilon.agentvscreen.host.Tailnet.Mode, onMode: (com.kilon.agentvscreen.host.Tailnet.Mode) -> Unit) {
        val M = com.kilon.agentvscreen.host.Tailnet.Mode.entries
        Hint(tr("电脑、云端或另一台手机上的 agent 通过 Tailscale 连进来：只有你自己 Tailscale 网络里的设备能连，走加密隧道，局域网和公网都连不到，令牌照样要。“内置”不用装 Tailscale App，“外部 App”用这台手机上已装的 Tailscale。",
            "Agents on a computer, in the cloud or on another phone connect through Tailscale: only devices in your own Tailscale network can, through an encrypted tunnel; the local network and the internet cannot, and the token is still required. \"Built-in\" needs no Tailscale app; \"External app\" uses the Tailscale app already on this phone."))
        Segments(listOf(tr("关", "Off"), tr("内置", "Built-in"), tr("外部 App", "External app")), M.indexOf(mode)) { i ->
            val m = M[i]
            onMode(m); ApiConfig.tailnetMode = m
            HostService.server?.start(ApiConfig.port)
            com.kilon.agentvscreen.host.Tailnet.sync(applicationContext)
        }
        when (mode) {
            com.kilon.agentvscreen.host.Tailnet.Mode.BUILTIN -> {
                var key by remember { mutableStateOf("") }
                var ctl by remember { mutableStateOf(ApiConfig.tailnetControlUrl ?: "") }
                var more by remember { mutableStateOf(false) }
                if (!com.kilon.agentvscreen.host.Tailnet.available(this@MainActivity)) {
                    StatusCard(Palette.stopped, tr("这个安装包里没有内置 Tailscale", "This package has no built-in Tailscale"), tr("只支持 arm64 手机。", "Only arm64 phones are supported."))
                } else when {
                    ts.loggedIn -> {
                        StatusCard(Palette.takeover, tr("已连上 Tailscale", "Connected to Tailscale"),
                            tr("名字", "Name") + " ${ts.dnsName ?: ApiConfig.tailnetHostname}\n" + tr("地址", "Address") + " ${ts.ip}" +
                                (ts.user?.let { "\n" + tr("账号", "Account") + " $it" } ?: "") +
                                (if (ts.ownServer) "\n" + tr("服务器", "Server") + " ${ts.serverName}" else "") +
                                "\n" + tr("在线设备 ${ts.online} 台", "${ts.online} devices online") +
                                (ts.lastPeer?.let { "\n" + tr("最近来访", "Last visitor") + " $it" } ?: ""))
                        SecondaryAction(tr("退出 Tailscale 登录", "Log out of Tailscale")) { com.kilon.agentvscreen.host.Tailnet.logout() }
                    }
                    ts.needsLogin && ts.authUrl != null -> {
                        if (ts.ownServer) Hint(tr("这是你的服务器 ${ts.serverName} 的注册页，只要做一次。Headscale 通常会在页面上给出一条注册命令，在服务器上运行它（用户名换成你的）就连上了；也可以在下面的高级选项里填预授权 key，不用开网页。",
                            "This is the registration page of your server ${ts.serverName}; it is needed once. Headscale usually shows a register command on that page: run it on the server (with your user name) and the node joins. Or put a pre-auth key in Advanced below and skip the page."))
                        else Hint(tr("第一次使用要登录一次 Tailscale，之后会记住。用你电脑上 Tailscale 的同一个账号登录；手机上不方便的话，复制链接到电脑上打开也行。", "The first time, log in to Tailscale once; it is remembered after that. Use the same account as Tailscale on your computer. If that's awkward on the phone, copy the link and open it on the computer."))
                        PrimaryAction(if (ts.ownServer) tr("打开注册页", "Open the registration page") else tr("在浏览器里登录 Tailscale", "Log in to Tailscale in the browser")) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ts.authUrl))) } }
                        SecondaryAction(tr("复制登录链接", "Copy login link")) { copy(tr("登录链接", "Login link"), ts.authUrl!!) }
                    }
                    ts.needsLogin -> {
                        // 1.1.7: logged out but no link yet. The bridge keeps asking; say why it may not come.
                        // 1.1.8: name the server actually in use, and show the node's own login error as soon as there is one.
                        val slow = ts.waitingS >= 15 || ts.loginError != null
                        StatusCard(if (slow) Palette.stopped else Palette.standby,
                            if (ts.loginError != null) tr("连不上控制服务器 ${ts.serverName}", "Cannot reach the control server ${ts.serverName}")
                            else if (slow) tr("拿不到登录链接（已等 ${ts.waitingS} 秒）", "No login link yet (waited ${ts.waitingS} s)")
                            else tr("正在向 ${ts.serverName} 要登录链接…", "Asking ${ts.serverName} for a login link…"),
                            if (!slow) tr("通常几秒内出现。", "It usually appears within seconds.")
                            else (ts.loginError?.let { tr("出错原因：", "Error: ") + it.take(240) + "\n" } ?: "") +
                                (if (ts.ownServer) tr("请确认地址和端口跟你在 Tailscale App 里填的“登录服务器”完全一样，并且这台手机现在能打开它（在浏览器里打开 ${ts.control}/key?v=1 应该能看到一串字）。也可以在下面的高级选项里填预授权 key。",
                                    "Check that the address and port are exactly what you use as the login server in the Tailscale app, and that this phone can open it now (${ts.control}/key?v=1 should show some text in a browser). You can also put a pre-auth key in Advanced below.")
                                else tr("这台手机连不上 Tailscale 的服务器（controlplane.tailscale.com）时会这样：换个网络或打开能访问它的代理后会自动出现。也可以在下面的高级选项里填 auth key 免浏览器登录。", "This happens when the phone cannot reach Tailscale's server (controlplane.tailscale.com): switch networks or turn on a proxy that reaches it and the link appears by itself. Or put an auth key in Advanced below to skip the browser login.")) +
                                (if (ts.loginError == null) ts.health?.let { "\n" + tr("Tailscale 报告：", "Tailscale says: ") + it.take(240) } ?: "" else ""))
                        SecondaryAction(tr("重新获取登录链接", "Get a login link again")) { com.kilon.agentvscreen.host.Tailnet.login() }
                    }
                    ts.error != null -> StatusCard(Palette.stopped, tr("内置 Tailscale 出错，会自动重试", "Built-in Tailscale failed, retrying automatically"), ts.error!!)
                    else -> StatusCard(Palette.standby, tr("内置 Tailscale 正在启动…", "Built-in Tailscale is starting…"), ts.state)
                }
                // 1.1.8: the built-in node is userspace: it only carries what other devices send to Agent-VScreen, never
                // this phone's own traffic, so an exit node has nothing to route there. Say so where people look for it.
                val appVpn = remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { while (true) { appVpn.value = withContext(Dispatchers.IO) { com.kilon.agentvscreen.api.ApiServer.tailnetAddress()?.hostAddress }; delay(5_000) } }
                if (appVpn.value != null) Hint(tr("这台手机上的 Tailscale App 已经连着（${appVpn.value}）。手机要靠它的出口节点上网的话，App 得一直开着，这时直接选上面的“外部 App”更省事，不用再开内置。",
                    "The Tailscale app on this phone is already connected (${appVpn.value}). If the phone needs its exit node to get online, the app has to stay on anyway, so choosing \"External app\" above is simpler than running the built-in one too."), Palette.standby)
                Disclosure(tr("高级选项（自建服务器、auth key）", "Advanced (own control server, auth key)"), more) { more = !more }
                AnimatedVisibility(more) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val ctlProblem = ApiConfig.controlUrlProblem(ctl)
                        androidx.compose.material3.OutlinedTextField(ctl, { ctl = it }, label = { Text(tr("控制服务器（自建 Headscale 时填，留空用 Tailscale）", "Control server (for your own Headscale; blank = Tailscale)")) },
                            placeholder = { Text("https://hs.example.com") }, singleLine = true, isError = ctlProblem != null,
                            supportingText = { Text(ctlProblem ?: ApiConfig.normalizeControlUrl(ctl)?.let { tr("将使用 $it", "Will use $it") } ?: tr("使用 Tailscale 官方服务器", "Using Tailscale's own server")) },
                            modifier = Modifier.fillMaxWidth())
                        androidx.compose.material3.OutlinedTextField(key, { key = it }, label = { Text(tr("auth key / 预授权 key（可选，代替网页登录）", "Auth key / pre-auth key (optional, instead of the web login)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Hint(tr("每个服务器的登录分开保存，换回原来的服务器不用重新登录。填了 key 会用它重新注册这个节点。出口节点只对这台手机自己的上网有用，内置节点不接管手机上网，所以这里没有这一项。",
                            "Each server keeps its own login, so switching back needs no new login. A key re-registers this node with it. An exit node only matters for this phone's own internet traffic, which the built-in node does not carry, so there is no such option here."))
                        SecondaryAction(tr("保存并重连", "Save and reconnect")) {
                            if (ctlProblem != null) { Toast.makeText(this@MainActivity, ctlProblem, Toast.LENGTH_SHORT).show(); return@SecondaryAction }
                            val k = key.trim(); val c = ctl
                            key = ""; ctl = ApiConfig.normalizeControlUrl(c) ?: ""
                            Thread { com.kilon.agentvscreen.host.Tailnet.configure(applicationContext, c, k) }.start()
                            Toast.makeText(this@MainActivity, tr("已保存，内置 Tailscale 重新连接中", "Saved, built-in Tailscale is reconnecting"), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            com.kilon.agentvscreen.host.Tailnet.Mode.SYSTEM -> {
                if (remoteBase == null) StatusCard(Palette.stopped, tr("还没找到 Tailscale 地址", "No Tailscale address yet"), tr("在这台手机上装好并登录 Tailscale App，打开连接后 20 秒内自动生效。不想装 App 就选“内置”。", "Install and log in to the Tailscale app on this phone; once it is connected this takes effect within 20 seconds. To skip the app, choose \"Built-in\"."))
                else { StatusCard(Palette.takeover, tr("已连上 Tailscale App", "Connected through the Tailscale app"), tr("地址", "Address") + " $remoteBase") }
            }
            else -> Hint(tr("现在是关闭的：选“内置”或“外部 App”打开。", "Off now: choose \"Built-in\" or \"External app\" to turn it on."), Palette.stopped)
        }
    }

    @Composable
    private fun Diagnostics(items: List<Setup.Item>) {
        var open by remember { mutableStateOf(false) }
        Block(tr("诊断", "Diagnostics"), trailing = { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null) }, onHeader = { open = !open }) {
            Text(tr("出问题时把诊断信息发给开发者：版本、配置状态和最近日志（不含令牌、不含你输入的字）。", "When something goes wrong, send this to the developer: version, setup state and recent log (no token, nothing you typed)."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(tr("诊断信息", "Diagnostics"), diagnostics(items)) }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("复制", "Copy")) }
                OutlinedButton(onClick = {
                    runCatching {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, tr("Agent-VScreen 诊断", "Agent-VScreen diagnostics")).putExtra(Intent.EXTRA_TEXT, diagnostics(items)), tr("发送诊断", "Send diagnostics")))
                    }
                }) { Icon(Icons.Filled.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("分享", "Share")) }
            }
            AnimatedVisibility(open) {
                SelectionContainer {
                    Text(AppLogger.tail(80), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp))
                }
            }
        }
    }

    @Composable
    private fun Banner(title: String, body: String, color: Color, action: String, onAction: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.12f))
                .border(BorderStroke(1.dp, color.copy(alpha = 0.5f)), RoundedCornerShape(20.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = color)
                Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = color)) { Text(action) }
        }
    }

    @Composable
    private fun StepRow(n: Int, it: Setup.Item) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (it.ok) Icon(Icons.Filled.CheckCircle, null, tint = Palette.takeover, modifier = Modifier.size(24.dp))
            else Box(Modifier.size(24.dp).clip(CircleShape).border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(it.label + if (it.key == "accessibility") tr("（可选）", "(optional)") else "", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(it.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun Block(title: String, trailing: (@Composable () -> Unit)? = null, onHeader: (() -> Unit)? = null, open: Boolean? = null, content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)).animateContentSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth().then(if (onHeader != null) Modifier.clickable(onClick = onHeader) else Modifier), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                trailing?.invoke()
                // A block that folds says so: a "展开 / 收起" chip with a turning chevron, not just a tappable title.
                if (open != null) {
                    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "chevron")
                    Spacer(Modifier.width(10.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (open) tr("收起", "Hide") else tr("展开", "Show"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp).rotate(turn))
                    }
                }
            }
            content()
        }
    }

    @Composable
    private fun Code(text: String, onCopy: () -> Unit) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF12141C))) {
            SelectionContainer {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp, color = Color(0xFFE6E8F0), softWrap = false,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 52.dp))
            }
            IconButton(onClick = onCopy, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Filled.ContentCopy, tr("复制", "Copy"), tint = Color(0xFFE6E8F0)) }
        }
    }

    @Composable
    private fun Toggle(label: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { set(!on) }) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = set)
        }
    }
}
