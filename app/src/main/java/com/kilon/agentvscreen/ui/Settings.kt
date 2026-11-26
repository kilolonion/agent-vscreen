package com.kilon.agentvscreen.ui

import androidx.compose.foundation.layout.fillMaxHeight

import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.basic.Card
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.ServiceControl
import com.kilon.agentvscreen.host.Updates
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 1.4.0-beta3 设置 (rainbow approved the prototype). beta2 was five groups of equal rows, so the two things people
 * come here for (who can reach the phone, how much an agent may do on its own) looked like any toggle. Now those two
 * are big cards at the top, drawn rather than listed; the screen's own switches follow as one group, and everything
 * rare (recognition, language, updates, the raw log, diagnostics) is gathered at the bottom under 关于与诊断.
 */

@Composable
fun SettingsTab(bottom: Dp) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    val s by BackgroundOperation.state.collectAsState()
    val ts by com.kilon.agentvscreen.host.Tailnet.status.collectAsState()
    val tokens by Tokens.list.collectAsState()
    val rules by com.kilon.agentvscreen.core.VScreenStore.rules.collectAsState()
    var eventDialog by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Updates.Outcome?>(null) }

    MiuixPage(tr("设置", "Settings"), back = false, bottom = bottom) {
        val pubOn by com.kilon.agentvscreen.host.PublicAccess.enabledFlow.collectAsState()

        // Who can reach the phone: the phone in the middle, each way in as a line, lit when it works.
        SmallTitle(tr("接入", "Connections"))
        Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
            val ways = remember(ts, app.serviceOn, app.tick) { com.kilon.agentvscreen.host.Access.ways(ctx) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ways.forEachIndexed { i, w ->
                    WayLine(w, onClick = { if (i == 0) nav.go(Route.Connect) else nav.go(Route.Remote) })
                }
                if (ways.size == 1) WayLine(null, onClick = { nav.go(Route.Remote) })
                val pubChannel by com.kilon.agentvscreen.host.PublicAccess.channelFlow.collectAsState()
                val tunnelState = pubChannel.tunnel?.status?.collectAsState()?.value?.state
                WayLine(
                    com.kilon.agentvscreen.host.Access.Way(
                        name = tr("云端（公网 MCP）", "Cloud (Public MCP)"),
                        state = when {
                            !pubOn -> tr("默认关闭 · ${pubChannel.title}", "Off · ${pubChannel.title}")
                            tunnelState == com.kilon.agentvscreen.host.TunnelState.CONNECTED -> tr("已开启", "On")
                            tunnelState == com.kilon.agentvscreen.host.TunnelState.ERROR -> tr("连接失败", "Failed")
                            tunnelState != null -> tr("启动中…", "Starting…")
                            ts.funnel == "on" -> tr("已开启", "On")
                            ts.funnel == "cert" -> tr("申请证书中…", "Fetching cert…")
                            ts.funnel == "error" -> tr("启动失败", "Failed")
                            else -> tr("启动中…", "Starting…")
                        },
                        ok = pubOn && (if (tunnelState != null) tunnelState == com.kilon.agentvscreen.host.TunnelState.CONNECTED else ts.funnel == "on"),
                        inUse = false,
                    ),
                    onClick = { nav.go(Route.PublicAccess) },
                )
                // 1.4.0-beta8: connecting an agent is the 接入 tab now; here only the tokens.
                SoftButton(onClick = { nav.go(Route.Tokens) }, modifier = Modifier.fillMaxWidth()) { Text(tr("令牌 ${tokens.size}", "Tokens ${tokens.size}")) }
            }
        }

        // 2.0.0-beta8 (feedback 2): how much an agent may do is set per agent (接入 › the agent); here only what is about
        // apps, and the table of levels to read.
        SmallTitle(tr("权限", "Permissions"))
        Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(title = tr("权限档位", "Permission levels"), summary = tr("各档位对每类操作的默认处理；具体档位在各 agent 页面中设置", "Default handling of each action type per level; set per agent on its page"),
                onClick = { nav.go(Route.Security) })
            ArrowPreference(title = tr("应用规则", "App rules"), summary = tr("按应用设置禁止操作、只读或每次确认", "Per app: blocked, read-only or confirm every time"),
                endActions = { Value(if (rules.isEmpty()) tr("无", "None") else tr("${rules.size} 条", "${rules.size}")) }, onClick = { nav.go(Route.Rules) })
        }

        Group(tr("副屏", "Background screen")) {
            ArrowPreference(
                title = tr("悬浮窗", "Floating window"),
                summary = if (s.showPreview) tr("已开启 · 小窗交互与按钮", "On · mini-window behavior & buttons") else tr("已关闭", "Off"),
                onClick = { nav.go(Route.FloatingWindow) },
            )
            // 1.4.0-beta13 (rainbow): sound has its own page: the screen's muting, what is silent now, muting by hand.
            ArrowPreference(title = tr("应用静音", "App sound"), summary = if (com.kilon.agentvscreen.core.VScreenMute.enabled) tr("副屏静音已开启 · 可单独管理各应用", "Background-screen muting on · manage apps individually")
                else tr("副屏静音已关闭 · 可单独管理各应用", "Background-screen muting off · manage apps individually"), onClick = { nav.go(Route.Sound) })
            val idleSave by com.kilon.agentvscreen.core.VScreenStore.idlePowerSave.collectAsState()
            SwitchPreference(checked = idleSave, onCheckedChange = { BackgroundOperation.setIdlePowerSave(it) },
                title = tr("空闲省电", "Idle power saving"), summary = tr("每轮任务结束后暂停副屏渲染", "Pause background-screen rendering after each turn"))
            // 2.0.0 (issue #48): for phones where the background screen sleeps with the phone.
            val keepOn by com.kilon.agentvscreen.core.VScreenStore.keepScreenOn.collectAsState()
            val sleepsWithPhone = BackgroundOperation.currentDisplay()?.let { !com.kilon.agentvscreen.core.VScreenAwake.canWakeAlone(it) } == true
            SwitchPreference(checked = keepOn, onCheckedChange = { com.kilon.agentvscreen.core.VScreenStore.setKeepScreenOn(it); com.kilon.agentvscreen.core.VScreenAwake.syncScreenLock() },
                title = tr("任务中保持亮屏", "Keep screen on during tasks"),
                summary = if (sleepsWithPhone) tr("这台手机熄屏后任务会停住，建议打开", "On this phone tasks pause when the screen turns off; turning this on is recommended")
                    else tr("agent 任务进行时手机屏幕不自动熄灭", "The phone screen does not time out while an agent task runs"))
            val opts = listOf(1, 3, 5, 10, 30)
            var quiet by remember { mutableIntStateOf(ApiConfig.quietMinutes) }
            OverlayDropdownPreference(items = opts.map { tr("$it 分钟", "$it min") }, selectedIndex = opts.indexOf(quiet).coerceAtLeast(0),
                title = tr("任务轮次时长", "Turn length"), summary = tr("agent 无新命令多久后视为本轮结束", "Time without new commands before a turn ends"),
                onSelectedIndexChange = { i -> ApiConfig.quietMinutes = opts[i]; quiet = opts[i] })
        }

        Group(tr("识别与录制", "Recognition and recording")) {
            SwitchPreference(checked = s.accurateOcr, onCheckedChange = { BackgroundOperation.setAccurateOcr(it) },
                title = tr("精确文字识别", "Accurate text recognition"), summary = tr("PP-OCRv6，本机离线运行", "PP-OCRv6, offline on this phone"))
            DetectorPreference()
            SwitchPreference(checked = s.recording, onCheckedChange = { BackgroundOperation.setRecording(it) },
                title = tr("后台录制", "Background recording"), summary = tr("保存副屏画面，用于回放和排查", "Keeps background-screen video for replay and troubleshooting"))
        }

        Group(tr("通用", "General")) {
            ArrowPreference(title = tr("首次配置", "Setup"), endActions = {
                Value(if (app.items.isEmpty()) "…" else if (app.allOk) tr("已就绪", "Ready") else tr("还差 ${app.left} 项", "${app.left} left"),
                    if (app.items.isNotEmpty() && !app.allOk) Palette.stopped else MiuixTheme.colorScheme.onSurfaceVariantActions)
            }, onClick = { nav.go(Route.Setup) })
            // 1.4.2-beta3: where the shell-level permission comes from (what each means: the "?" on the setup step).
            val pv by com.kilon.agentvscreen.host.Privileged.snapshot.collectAsState()
            OverlayDropdownPreference(items = listOf("Shizuku", "Root"), selectedIndex = pv.via.ordinal,
                title = tr("权限来源", "Permission source"),
                summary = if (pv.state == com.kilon.agentvscreen.host.ShizukuManager.State.READY) pv.label else com.kilon.agentvscreen.host.Privileged.notReadyHint().take(60),
                onSelectedIndexChange = { i -> val v = com.kilon.agentvscreen.host.Privileged.Via.entries[i]
                    if (v != pv.via) scope.launch(Dispatchers.IO) { com.kilon.agentvscreen.host.Privileged.choose(ctx, v) } })
            val names = listOf(tr("跟随系统", "System"), "中文", "English")
            OverlayDropdownPreference(items = names, selectedIndex = I18n.Lang.entries.indexOf(I18n.choice), title = "语言 / Language",
                onSelectedIndexChange = { i ->
                    if (I18n.Lang.entries[i] != I18n.choice) {
                        I18n.choice = I18n.Lang.entries[i]
                        Overlay.refresh()
                        if (HostService.isAlive) HostService.start(ctx)
                        host.recreateForLanguage()
                    }
                })
            // 2.0.0-beta11: what agents read is separate from the UI language (screen text is never translated).
            OverlayDropdownPreference(items = listOf(tr("英文（推荐）", "English (recommended)"), tr("跟随界面语言", "Same as the app")),
                selectedIndex = if (I18n.agentLang == I18n.AGENT_AUTO) 1 else 0,
                title = tr("给 agent 的回复语言", "Language of replies to agents"),
                onSelectedIndexChange = { i -> I18n.agentLang = if (i == 1) I18n.AGENT_AUTO else I18n.AGENT_EN })
        }

        Group(tr("更新", "Updates")) {
            val newer by Updates.newer.collectAsState()
            val checking by Updates.checking.collectAsState()
            ArrowPreference(title = tr("检查更新", "Check for updates"), endActions = {
                Value(when { checking -> tr("检查中…", "Checking…"); newer != null -> tr("有新版本 ${newer!!.version}", "New: ${newer!!.version}"); else -> BuildConfig.VERSION_NAME },
                    if (newer != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions)
            }, onClick = { if (!checking) scope.launch { update = withContext(Dispatchers.IO) { Updates.check(ctx) } } })
            ArrowPreference(
                title = tr("更新日志", "What's new"),
                endActions = { Value(BuildConfig.VERSION_NAME) },
                onClick = { ChangesSheetHost.show(com.kilon.agentvscreen.Changes.allUserReleases()) },
            )
            // 1.4.2 (rainbow): which releases the check looks at.
            var channel by remember { mutableStateOf(Updates.channel(ctx)) }
            OverlayDropdownPreference(items = listOf(tr("正式版", "Releases"), tr("测试版（含正式版）", "Betas (and releases)")), selectedIndex = channel.ordinal,
                title = tr("更新渠道", "Update channel"),
                onSelectedIndexChange = { i -> val c = Updates.Channel.entries[i]; if (c != channel) { channel = c; Updates.setChannel(ctx, c)
                    scope.launch { update = withContext(Dispatchers.IO) { Updates.check(ctx) } } } })
        }

        Group(tr("记录与诊断", "Records and diagnostics")) {
            ArrowPreference(title = tr("事件广播", "Event broadcast"), endActions = { Value(ApiConfig.eventTarget ?: tr("不广播", "Off")) }, onClick = { eventDialog = true })
            ArrowPreference(title = tr("操作日志", "Activity log"), summary = tr("agent 发送的全部命令及结果", "Every command agents sent, with results"), onClick = { nav.go(Route.Log) })
            ArrowPreference(title = tr("诊断与反馈", "Diagnostics and feedback"), onClick = { nav.go(Route.Diag) })
            ArrowPreference(title = tr("帮助", "Help"), summary = tr("各设置项说明", "What each setting does"), onClick = { nav.go(Route.Help) })
            // 2.0.1 (rainbow): the project page.
            ArrowPreference(title = tr("关于项目", "About"), summary = "GitHub · kilolonion/agent-vscreen",
                endActions = { Value(BuildConfig.VERSION_NAME) },
                onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(com.kilon.agentvscreen.host.Feedback.REPO)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } })
        }
        Text("Agent-VScreen ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }

    if (eventDialog) {
        var target by remember { mutableStateOf(ApiConfig.eventTarget.orEmpty()) }
        GlassDialog(
            onDismissRequest = { eventDialog = false },
            title = { DialogTitle(tr("事件广播", "Event broadcast")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("盯守看到条件时，事件默认只放进队列。填一个应用的包名，事件会另外广播给它；留空就不广播。", "When a watch sees its condition, the event is only queued by default. With a package here it is also broadcast to that app; empty means none."),
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextField(value = target, onValueChange = { target = it.trim() }, label = tr("包名", "Package"), useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { LinkButton(onClick = { ApiConfig.eventTarget = target.ifEmpty { null }; eventDialog = false; app.bump() }) { Text(tr("保存", "Save")) } },
            dismissButton = { LinkButton(onClick = { eventDialog = false }) { Text(tr("取消", "Cancel")) } },
        )
    }
    update?.let { u -> UpdateDialog(u) { update = null } }
}

/** What a check found: the new version with its notes and a download, already the newest, or why it failed. */
/** One way in: a dot (green when it works), its name and state; tap opens where it is set up. */
@Composable
private fun WayLine(w: com.kilon.agentvscreen.host.Access.Way?, onClick: () -> Unit) {
    val c = MiuixTheme.colorScheme
    val tint = when { w == null -> c.onSurfaceVariantSummary; w.ok -> Palette.takeover; else -> Palette.stopped }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(w?.name ?: tr("其它设备", "Other devices"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(w?.let { it.state + if (it.inUse) tr(" · 刚用过", " · used lately") else "" } ?: tr("没开 · 经 Tailscale 从电脑或服务器连进来", "Off · from a computer or server through Tailscale"),
                fontSize = 12.5.sp, color = c.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.onSurfaceVariantSummary, modifier = Modifier.size(20.dp))
    }
}

/** A level to choose: lit and ringed when chosen. */
@Composable
private fun LevelCard(title: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MiuixTheme.colorScheme
    val bg by androidx.compose.animation.animateColorAsState(if (on) c.primary.copy(alpha = 0.14f) else c.surfaceContainerHigh, androidx.compose.animation.core.tween(240), label = "lvl")
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(bg)
        .border(1.5.dp, if (on) c.primary else Color.Transparent, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (on) c.primary else c.onSurface)
        Text(sub, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.onSurfaceVariantSummary)
    }
}

@Composable
private fun UpdateDialog(u: Updates.Outcome, close: () -> Unit) {
    val ctx = LocalContext.current
    fun view(url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; close() }
    when (u) {
        is Updates.Outcome.Newer -> GlassDialog(
            onDismissRequest = close,
            title = { DialogTitle(if (u.r.prerelease || u.r.version.contains('-')) tr("测试版 ${u.r.version}", "Beta ${u.r.version}") else tr("新版本 ${u.r.version}", "New version ${u.r.version}")) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("现在是 ${BuildConfig.VERSION_NAME}", "You have ${BuildConfig.VERSION_NAME}"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    // 1.4.2: the notes are Markdown (GitHub release body); shown rendered, not as raw marks.
                    if (u.r.notes.isNotBlank()) MarkdownText(u.r.notes.take(6000))
                }
            },
            confirmButton = { LinkButton(onClick = { view(u.r.apk ?: u.r.page) }) { Text(if (u.r.apk != null) tr("下载 APK", "Download APK") else tr("打开发布页", "Open the release")) } },
            dismissButton = { LinkButton(onClick = close) { Text(tr("稍后", "Later")) } },
        )
        is Updates.Outcome.Latest -> GlassDialog(
            onDismissRequest = close,
            title = { DialogTitle(tr("已是最新", "Up to date")) },
            text = { Text(tr("现在是 ${BuildConfig.VERSION_NAME}", "You have ${BuildConfig.VERSION_NAME}") + (u.newest?.let { tr("，GitHub 上最新的发布是 $it", "; the newest release on GitHub is $it") } ?: "")) },
            confirmButton = { LinkButton(onClick = close) { Text(tr("好", "OK")) } },
        )
        is Updates.Outcome.Failed -> GlassDialog(
            onDismissRequest = close,
            title = { DialogTitle(tr("没查到", "Could not check")) },
            text = { Text(u.why) },
            confirmButton = { LinkButton(onClick = { view(Updates.PAGE) }) { Text(tr("打开发布页", "Open releases")) } },
            dismissButton = { LinkButton(onClick = close) { Text(tr("关闭", "Close")) } },
        )
    }
}

/** Per-app limits: blocked, read-only, ask every time. */
@Composable
fun RulesPage() {
    MiuixPage(tr("应用规则", "App rules")) {
        Group(padded = true) { VScreenAppRules() }
        Footnote(tr("仅作用于副屏，不影响你自己使用。", "Applies only to the background screen, not to your own use."))
    }
}

/**
 * The main token (show, copy, renew) and one token per agent. 2.0.0-beta9 (feedback 5, 6): a lazy list (hundreds of
 * tokens cost what a dozen did), and one action that removes every token no request ever used.
 */
@Composable
fun TokensPage() {
    val host = LocalHost.current
    val nav = LocalNav.current
    val actx = LocalContext.current.applicationContext
    var token by remember { mutableStateOf(ApiConfig.token) }
    var show by remember { mutableStateOf(false) }
    var askRenew by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var askUnused by remember { mutableStateOf<List<Tokens.Token>?>(null) }
    var askRemove by remember { mutableStateOf<Tokens.Token?>(null) }
    val list by Tokens.list.collectAsState()
    val agents by com.kilon.agentvscreen.api.AgentLedger.agents.collectAsState()
    val others = remember(list) { list.filter { it.id != Tokens.MAIN }.sortedByDescending { maxOf(it.lastUsedAt, it.createdAt) } }
    val tokenFold = rememberFold(4)
    val unused = remember(list, agents) { Tokens.neverUsed { id -> agents[id]?.used == true } }
    val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT) }
    MiuixLazyPage(tr("令牌", "Tokens"), actions = {
        LinkButton(onClick = { adding = true }, modifier = Modifier.padding(end = 12.dp)) { Text(tr("新建", "New")) }
    }) {
        item(key = "main") {
            Group(tr("主令牌", "Main token")) {
                BasicComponent(
                    title = if (show) token else "•".repeat(12) + token.takeLast(4),
                    summary = tr("仅提供给信任的 agent", "Only for agents you trust"),
                    endActions = {
                        IconButton(onClick = { show = !show }) { Icon(if (show) MiuixIcons.Hide else MiuixIcons.Show, tr("显示令牌", "Show token")) }
                        IconButton(onClick = { host.copy(tr("令牌", "Token"), token) }) { Icon(MiuixIcons.Copy, tr("复制令牌", "Copy token")) }
                        IconButton(onClick = { askRenew = true }) { Icon(MiuixIcons.Refresh, tr("换一个令牌", "New token")) }
                    },
                )
            }
        }
        if (unused.isNotEmpty()) item(key = "unused") {
            Group {
                BasicComponent(title = tr("删除 ${unused.size} 个未使用的令牌", "Remove ${unused.size} never-used tokens"),
                    summary = tr("从未被任何请求使用；不影响主令牌和公网客户端", "No request ever used them; the main token and public clients are kept"),
                    onClick = { askUnused = unused })
            }
        }
        cardItems(tr("每个 agent 一个令牌 · ${others.size}", "One token per agent · ${others.size}"), others, key = { it.id }, fold = tokenFold) { t ->
            TokenRow(t, fmt, open = { nav.go(Route.Agent(t.id)) }, remove = { askRemove = t })
        }
    }
    if (adding) NewTokenDialog(ApiConfig.baseUrl, { l, t -> host.copy(l, t) }) { adding = false }
    askUnused?.let { drop ->
        GlassDialog(
            onDismissRequest = { askUnused = null },
            title = { DialogTitle(tr("删除 ${drop.size} 个没用过的令牌？", "Remove ${drop.size} never-used tokens?")) },
            text = { Text(tr("它们从没被任何请求用过。删掉后这些令牌立即作废。", "No request ever used them. They stop working at once.")) },
            confirmButton = { LinkButton(onClick = {
                val ids = drop.map { it.id }.toSet()
                Thread { com.kilon.agentvscreen.api.Agents.forgetMany(ids) }.start()
                Audit.security("token_cleanup", "removed ${ids.size} never-used tokens by hand")
                host.say(tr("已删除 ${ids.size} 个", "Removed ${ids.size}"))
                askUnused = null
            }) { Text(tr("删除", "Remove"), color = Palette.stopped) } },
            dismissButton = { LinkButton(onClick = { askUnused = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
    askRemove?.let { t ->
        GlassDialog(
            onDismissRequest = { askRemove = null },
            title = { DialogTitle(tr("删除「${t.name}」？", "Remove \"${t.name}\"?")) },
            text = { Text(tr("它的令牌作废，连不上了；做过的任务还留在记录里。", "Its token stops working; its past tasks stay in the records.")) },
            confirmButton = { LinkButton(onClick = {
                val id = t.id
                Thread { com.kilon.agentvscreen.api.Agents.forget(actx, id) }.start()
                Audit.security("revoke", t.name)
                askRemove = null
            }) { Text(tr("删除", "Remove"), color = Palette.stopped) } },
            dismissButton = { LinkButton(onClick = { askRemove = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
    if (askRenew) GlassDialog(
        onDismissRequest = { askRenew = false },
        title = { DialogTitle(tr("换一个主令牌？", "Make a new main token?")) },
        text = { Text(tr("旧的立即失效，用它的 agent 都要重新接入。", "The old one stops working at once; every agent using it has to connect again.")) },
        confirmButton = { LinkButton(onClick = { ApiConfig.resetToken(); token = ApiConfig.token; askRenew = false; host.say(tr("已换新令牌", "New token made")) }) { Text(tr("换新的", "Renew")) } },
        dismissButton = { LinkButton(onClick = { askRenew = false }) { Text(tr("取消", "Cancel")) } },
    )
}

/** Every explanation, one group per setting: what the rows only name. */
@Composable
fun HelpPage() {
    MiuixPage(tr("帮助", "Help")) {
        listOf(
            tr("常驻、按需和关闭", "Always on, on demand and off") to ServiceControl.help(),
            tr("运行位置", "Where the agent runs") to H.where,
            tr("接入方式", "How it connects") to H.how,
            tr("令牌", "Tokens") to (H.token + "\n\n" + H.tokens),
            tr("确认方式", "Confirmations") to H.autonomy,
            tr("应用规则", "App rules") to H.rules,
            tr("悬浮窗", "Floating window") to H.window,
            tr("空闲省电", "Idle power saving") to H.idle,
            tr("任务中保持亮屏", "Keep screen on during tasks") to H.keepOn,
            tr("副屏静音", "Mute the screen") to H.mute,
            tr("任务轮次时长", "Turn length") to H.turn,
            tr("定时预约", "Schedules") to H.schedule,
            tr("示教", "Lessons") to H.lessons,
            tr("操作日志", "Activity log") to H.audit,
            tr("事件广播", "Event broadcast") to H.events,
            tr("首次配置", "Setup") to H.setup,
            tr("诊断", "Diagnostics") to H.diag,
        ).forEach { (t, body) ->
            Group(t, padded = true) { SelectionContainer { Text(body, fontSize = 14.sp, lineHeight = 22.sp) } }
        }
    }
}

/** A grey note under a group. */
@Composable
fun Footnote(t: String) = Text(t, fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))

/** The long words. 1.4: all of them on the Help page (and nowhere else), one paragraph per setting. */
internal object H {
    val where get() = tr("本机运行的 agent（Termux、Minis、Operit 等）直接连接 127.0.0.1:${ApiConfig.port}，无需额外配置。\n\n电脑、云服务器或其他手机上的 agent 通过 Tailscale 连接：仅允许你的 Tailscale 网络内的设备访问，流量经加密隧道传输，局域网和公网均无法直接访问，且仍需令牌。“内置”模式无需安装 Tailscale App；“外部 App”模式使用本机已安装的 Tailscale。\n\n云端 agent 若不在你的 Tailscale 网络内，可使用公网 MCP（Tailscale Funnel、Cloudflare Tunnel 或 ngrok，均经手机上的 OAuth 配对），默认关闭。",
        "Agents on this phone (Termux, Minis, Operit, ...) connect to 127.0.0.1:${ApiConfig.port} directly; no setup is needed.\n\nAgents on a computer, a cloud server or another phone connect through Tailscale: only devices in your Tailscale network can reach the service, over an encrypted tunnel; the local network and the internet cannot, and a token is still required. Built-in mode needs no Tailscale app; External app mode uses the Tailscale app on this phone.\n\nCloud agents outside your Tailscale network can use public MCP (Tailscale Funnel, Cloudflare Tunnel or ngrok, all with OAuth pairing on the phone), which is off by default.")
    val how get() = tr("命令行：适用于带终端的 agent。将“给 agent 的话”发送给它，它会安装 vscreen 命令，并把这项能力写入长期记忆，供之后的新对话使用；只提供安装命令时，多数 agent 不会保留这项能力。\n\nMCP：适用于支持 MCP 的 agent。“给 agent 的话”包含 MCP 配置（streamable HTTP，每条命令对应一个工具，截图随结果返回）和能力说明。\n\nHTTP：适用于自行编写调用代码的 agent 应用。返回 JSON（ok、code、next、text、images、steps、screen、notes），完整说明见 GET /v2/help。\n\n能力说明也可以直接粘贴到 agent 的“自定义指令”或“记忆”设置中。",
        "Shell: agents with a terminal. Send them the message: it installs the vscreen command and has the agent save \"I can operate apps on the phone in the background\" in its long-term memory, so new conversations use it too. With only the install command, most agents forget it.\n\nMCP: agents with MCP. The message has the MCP config (streamable HTTP, one tool per command, screenshots attached) and the ability note.\n\nHTTP: agent apps that write their own calls. Replies are JSON (ok, code, next, text, images, steps, screen, notes); full reference at GET /v2/help.\n\nYou can also paste the ability note into the agent's custom instructions or memory setting.")
    val token get() = tr("令牌是 agent 的访问凭据，持有令牌即可在副屏上操作你的应用，请只提供给信任的 agent。服务只接受本机请求（开启远程后还接受你 Tailscale 网络内的设备），拒绝所有网页发起的调用。重新生成主令牌后旧令牌立即失效，使用它的 agent 需重新接入。",
        "A token is an agent's credential: whoever holds it can operate your apps on the background screen, so give it only to agents you trust. The service accepts only this phone (and, with remote access on, devices in your Tailscale network) and refuses all calls from web pages. Regenerating the main token invalidates the old one at once; agents using it must connect again.")
    val autonomy get() = tr("确认分两种强度：\n• 指纹或锁屏密码（未设锁屏时长按 3 秒）：支付、接入新 agent、放宽 agent 的权限、开启公网接入、临时免确认支付。\n• 单击“允许”：其余需确认的操作，包括不可撤销操作、敏感操作、高频操作，以及设为“每次确认”的应用中的操作。\n\n单击确认可选择许可范围：仅此一次、本轮任务内（超过“任务轮次时长”无新命令即结束）或今天内。范围内同一 agent 在同一应用中的同类操作不再请求确认（设为“每次确认”的应用同样适用）。支付不用这些范围，每一笔单独确认；唯一例外是你在 agent 详情页开启的“临时免确认支付（30 分钟）”。\n\n所有档位下：每一步都写入操作日志；可随时在悬浮窗停止 AI 或接管；确认请求 60 秒内未处理时该步不执行，agent 会提示你处理。",
        "Confirmations come in two strengths:\n• Fingerprint or screen-lock PIN (a 3-second hold without a screen lock): payments, connecting a new agent, widening an agent's permissions, enabling public access, payments without confirmation.\n• One tap on Allow: everything else that needs confirmation, including irreversible, sensitive and high-rate actions and actions in confirm-every-time apps.\n\nA tap can cover Just this once, This turn (ends once no command arrives for a turn length) or Today; within that range the same agent, action type and app are not asked again (apps set to confirm every time included). Payments take no scope: each one is confirmed on its own, unless you turned on \"Payments without confirmation (30 min)\" on the agent's page.\n\nAt every level: each step is logged; you can stop the AI or take over from the floating window at any time; a confirmation left unanswered for 60 s means the step is not done, and the agent will ask you to respond.")
    val rules get() = tr("禁止操作：AI 不能在副屏打开、读取或操作该应用。\n只读：AI 可以打开、查看和滚动，但不能点击、输入或滑动。\n每次确认：AI 点击或输入前需在手机上确认，可选择仅此一次、本轮任务内或今天内。\n\n应用规则对所有 agent 生效，优先于 agent 的权限档位；仅作用于副屏，不影响你自己使用。",
        "Blocked: the AI cannot open, read or operate the app on the background screen.\nRead-only: the AI can open, view and scroll, but cannot tap, type or swipe.\nConfirm every time: before the AI taps or types, you confirm on the phone, for just this once, this turn or today.\n\nApp rules apply to every agent and take precedence over its permission level. They affect only the background screen, not your own use.")
    val tokens get() = tr("每个 agent 使用独立令牌：各自有权限档位，可单独移除，操作日志按令牌区分来源；不同令牌的 agent 不能接管彼此的会话。\n\n“临时免确认支付（30 分钟）”位于 agent 详情页：开启期间该 agent 的支付操作不再请求确认（仍写入操作日志）。仅在你在场监督时使用。",
        "Each agent uses its own token: it has its own permission level, can be removed alone, and the activity log shows which token did what; agents with different tokens cannot take over each other's sessions.\n\n\"Payments without confirmation (30 min)\" is on the agent's page: while on, that agent's payments are not confirmed (still logged). Use it only while you are watching.")
    val window get() = tr("副屏存在时显示在所有应用上层。拖动可移动，双指缩放可调整大小，拖至屏幕边缘可收起为侧边标签，长按画面 0.5 秒可接管。agent 等待你回复时，侧边标签显示为紫色问号。",
        "Shown above all apps while the background screen exists. Drag to move, pinch to resize, push to an edge to fold it into a side tab, hold the picture 0.5 s to take over. While an agent waits for your reply, the tab shows a violet question mark.")
    val idle get() = tr("每轮任务结束后暂停副屏渲染以节省电量；页面状态保留，收到下一条命令时自动恢复。少数应用恢复时会重新加载，遇到这种情况可关闭此项。",
        "Pauses background-screen rendering after each turn to save power; the page is kept and resumes with the next command. A few apps reload on resume; turn this off if that happens.")
    val keepOn get() = tr("熄屏运行需要副屏能单独保持唤醒。有些手机上副屏会随主屏一起休眠（状态页会写明），这时手机自动熄屏、锁屏后，agent 的任务就停住了。打开此项后，只在 agent 任务进行时让手机屏幕保持亮着（可以变暗），任务结束、出错或被停止时立刻恢复。你按电源键熄屏仍然有效，不会被重新点亮。注意：任务期间手机不会自动锁屏，别人拿到手机就能操作；也会多耗电。",
        "Running with the screen off needs the background screen to stay awake on its own. On some phones it sleeps with the main screen (the status page says so); then an agent's task stops once the phone times out and locks. With this on, the phone screen stays on (it may dim) only while an agent task runs, and is let go as soon as the task ends, fails or is stopped. Pressing the power button still turns the screen off, and it is not turned back on. Note: the phone does not lock itself during a task, so anyone holding it can use it, and it uses more battery.")
    val mute get() = tr("副屏中的应用不通过手机扬声器播放声音（如视频、直播）。应用移到主屏或副屏释放时恢复声音；App 异常退出后，下次连接时自动恢复。",
        "Apps on the background screen do not play sound through the phone (videos, live streams). Sound returns when an app moves to the main screen or the screen is released; after a crash it is restored on the next connection.")
    val turn get() = tr("agent 超过该时长未发送新命令即视为本轮任务结束：副屏随后进入省电暂停，10 分钟后释放。agent 也可以用 vscreen done 立即结束本轮。该时长同时决定“本轮任务内”许可的有效期。",
        "A turn ends when the agent sends no command for this long: the screen then pauses to save power and is released 10 minutes later. The agent can end a turn at once with vscreen done. This length also sets how long a \"This turn\" allowance lasts.")
    val lessons get() = tr("示教是你在副屏上演示并保存的操作流程。agent 用 act \"use 名称\" 按步骤执行；点开可查看每一步，不再需要的可删除。\n\n示教在全屏页面录制：由 agent 用 teach 发起请求，或你接管后手动开始录制。",
        "A lesson is a procedure you demonstrated on the background screen and saved. An agent runs it with act \"use name\"; open one to see its steps, and delete those you no longer need.\n\nLessons are recorded on the full-screen page: an agent requests one with teach, or you take over and start recording.")
    val schedule get() = tr("agent 的定时任务需要使用手机时，可用 vscreen schedule 预约时间。预约时间前 3 分钟会启动服务并自检，未就绪时发送通知。\n\n服务处于暂停状态时，到点自动开启；服务已关闭时不自动开启，仅发送通知。\n\n点开预约可修改时间、重复规则和保持时长；关闭开关会保留预约但到点不触发。",
        "An agent whose timed task needs the phone can book the time with vscreen schedule. 3 minutes before, the service starts and checks itself, and you are notified if it is not ready.\n\nWhile the service is paused, a booking turns it on; while it is off, a booking only notifies you.\n\nOpen a booking to change its time, repeat rule and duration; switching it off keeps it without firing.")
    val audit get() = tr("记录每条命令的来源（令牌、设备或公网来源地址）、时间、内容和结果。输入的文字只记录字数。",
        "Records each command's source (token, device or public source address), time, content and result. Typed text is recorded as a length only.")
    val events get() = tr("盯守条件满足时产生的事件默认只进入队列，由 agent 通过 vscreen events 读取。填写应用包名后，事件会同时以广播发送给该应用，其他应用无法接收。",
        "Events from watches are queued by default and read by agents with vscreen events. With a package name set, each event is also broadcast to that app only.")
    val privilege get() = tr("Agent-VScreen 需要 shell 级权限来创建副屏、注入点击和启动应用，可从以下两种来源中选择一种：\n\n• Shizuku（默认）：无需 root。手机每次重启后需在 Shizuku App 中重新启动服务。\n• Root：适用于已通过 Magisk、KernelSU、APatch 等获取 root 的手机，无需安装 Shizuku。首次使用时需在 root 管理器中授权，之后打开 App 即自动启动。副屏服务优先以 shell 身份运行，行为与 Shizuku 的 ADB 模式一致。\n\n切换来源时当前副屏会关闭，下一条命令通过新来源自动重新连接。",
        "Agent-VScreen needs shell-level permission to open the background screen, tap and start apps. It comes from one of two sources:\n\n• Shizuku (default): no root needed. Shizuku must be started again in its app after each phone restart.\n• Root: for phones rooted with Magisk, KernelSU, APatch and the like; Shizuku is not needed. The root manager asks once; after that it starts by itself when the app opens. The screen service runs as the shell user where it can, exactly as under Shizuku's ADB mode.\n\nSwitching closes the current screen; the next command reconnects through the new source by itself.")
    val setup get() = tr("仅使用 Shizuku（或 Root）作为特权通道：不占用 adb、不修改无线调试、不重启 adbd。每一项只为本应用添加授权，不改动其他应用的设置；agent 自己使用的 adb / Shizuku 不受影响。",
        "Uses only Shizuku (or root) as the privilege channel: adb is not taken, wireless debugging is not changed, adbd is not restarted. Each item only grants this app and leaves other apps' settings alone; your agent's own adb / Shizuku keep working.")
    val diag get() = tr("汇总版本、配置状态和最近日志，用于问题反馈；不包含令牌和你输入的文字。",
        "Collects version, setup state and recent logs for bug reports; contains no tokens and nothing you typed.")
}

/**
 * 2.0.0-beta8 (feedback 2, rainbow 00:52): the levels side by side, one row per class of action, as a table the user
 * reads once. Each agent picks a level (or its own table) on its own page; nothing here is a setting.
 */
@Composable
fun SecurityPage() {
    val c = MiuixTheme.colorScheme
    val levels = Tokens.LEVELS.filter { it != com.kilon.agentvscreen.api.Policy.CUSTOM }
    MiuixPage(title = tr("权限档位", "Permission levels")) {
        Card(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Spacer(Modifier.weight(1.4f))
                levels.forEach { lv -> Text(com.kilon.agentvscreen.api.Policy.levelTitle(lv), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
            com.kilon.agentvscreen.api.Policy.ROWS.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.title, fontSize = 13.sp, modifier = Modifier.weight(1.4f))
                    levels.forEach { lv ->
                        val a = com.kilon.agentvscreen.api.Policy.PRESETS.getValue(lv).getValue(row.key)
                        val tint = when (a) { com.kilon.agentvscreen.api.Policy.Act.ALLOW -> Palette.takeover; com.kilon.agentvscreen.api.Policy.Act.ASK -> c.primary; else -> c.onSurfaceVariantSummary }
                        Text(a.title, fontSize = 12.sp, color = tint, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        Footnote(tr("在 接入 › agent 详情 中为每个 agent 选择档位，可逐项修改或使用“自定义”。放宽权限需指纹验证，收紧立即生效。经公网接入的 agent 在任何档位都不能做系统操作。", "Pick a level for each agent under Connect › the agent; rows can be changed one by one, or use Custom. Widening needs a fingerprint; narrowing applies at once. Agents connected over the public internet can never do system actions, at any level."))
        Group(tr("确认方式", "Confirmations"), padded = true) {
            Text(H.autonomy, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}

/** 2.0.0-beta8 (K.5): Settings › Floating window — main toggle + mini-window behavior switches with "?" help. */
@Composable
fun FloatingWindowPage() {
    val s by BackgroundOperation.state.collectAsState()
    var help by remember { mutableStateOf(false) }

    MiuixPage(
        title = tr("悬浮窗", "Floating window"),
        actions = {
            LinkButton(onClick = { help = true }) { Text("?") }
        },
    ) {
        Group {
            SwitchPreference(
                checked = s.showPreview,
                onCheckedChange = { BackgroundOperation.setShowPreview(it); if (it) Overlay.refresh() },
                title = tr("显示悬浮窗", "Show floating window"),
            )
            SwitchPreference(
                checked = s.miniShowControls,
                onCheckedChange = { BackgroundOperation.setMiniShowControls(it) },
                title = tr("小窗显示按钮", "Show buttons on mini window"),
            )
            SwitchPreference(
                checked = s.miniTapToExpand,
                onCheckedChange = { BackgroundOperation.setMiniTapToExpand(it) },
                title = tr("点小窗自动放大", "Tap mini window to expand"),
            )
        }
    }

    if (help) {
        GlassDialog(
            onDismissRequest = { help = false },
            title = { DialogTitle(tr("悬浮窗说明", "About floating window")) },
            text = {
                Text(
                    tr(
                        "• 打开方式：每次有新的副屏时，悬浮窗以右上角小窗打开，轻点放大；同一副屏期间，沿用你调整后的大小和位置。\n\n• 显示悬浮窗：有副屏时在最上层显示实时画面。\n\n• 小窗显示按钮：默认开启，右上角小窗也显示标题、全屏、关闭和停止 AI 按钮。\n\n• 点小窗自动放大：默认开启；关闭后轻点小窗不再居中放大，只保留拖动、贴边折叠和长按操作。",
                        "• How it opens: each new background screen opens as the small window in the top right corner; tap to enlarge. While the same screen is up, your size and position are kept.\n\n• Show floating window: displays the live background screen above other apps.\n\n• Show buttons on mini window: on by default; the compact corner window also shows title, fullscreen, close, and stop-AI buttons.\n\n• Tap mini window to expand: on by default; turn off so tapping the mini window stays compact instead of expanding to center.",
                    ),
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = { LinkButton(onClick = { help = false }) { Text(tr("完成", "Done")) } },
        )
    }
}


