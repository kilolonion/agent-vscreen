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
        SmallTitle(tr("谁能连进来", "Who can connect"))
        Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
            val ways = remember(ts, app.serviceOn, app.tick) { com.kilon.agentvscreen.host.Access.ways(ctx) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ways.forEachIndexed { i, w ->
                    WayLine(w, onClick = { if (i == 0) nav.go(Route.Connect) else nav.go(Route.Remote) })
                }
                if (ways.size == 1) WayLine(null, onClick = { nav.go(Route.Remote) })
                WayLine(
                    com.kilon.agentvscreen.host.Access.Way(
                        name = tr("云端（公网 MCP）", "Cloud (Public MCP)"),
                        state = when {
                            !pubOn -> tr("默认关闭 · Tailscale Funnel", "Off · Tailscale Funnel")
                            ts.funnel == "on" -> tr("已开启", "On")
                            ts.funnel == "cert" -> tr("申请证书中…", "Fetching cert…")
                            ts.funnel == "error" -> tr("启动失败", "Failed")
                            else -> tr("启动中…", "Starting…")
                        },
                        ok = pubOn && ts.funnel == "on",
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
        SmallTitle(tr("agent 能做多少", "How much an agent may do"))
        Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(title = tr("各档怎么处理", "How each level handles things"), summary = tr("只看、稳妥、省心、全权；每个 agent 在自己的页面里选", "Look only, Steady, Easy, Full; each agent picks on its own page"),
                onClick = { nav.go(Route.Security) })
            ArrowPreference(title = tr("应用规则", "App rules"), summary = tr("禁止、只读或每次询问", "Blocked, read-only or ask every time"),
                endActions = { Value(if (rules.isEmpty()) tr("无", "None") else tr("${rules.size} 条", "${rules.size}")) }, onClick = { nav.go(Route.Rules) })
        }

        Group(tr("副屏", "Background screen")) {
            ArrowPreference(
                title = tr("悬浮窗", "Floating window"),
                summary = if (s.showPreview) tr("显示中 · 小窗交互与按钮", "Shown · mini-window behavior & buttons") else tr("已关闭", "Off"),
                onClick = { nav.go(Route.FloatingWindow) },
            )
            // 1.4.0-beta13 (rainbow): sound has its own page: the screen's muting, what is silent now, muting by hand.
            ArrowPreference(title = tr("应用静音", "App sound"), summary = if (com.kilon.agentvscreen.core.VScreenMute.enabled) tr("副屏静音开着；可手动静音、恢复应用", "Screen muting on; mute or unmute apps by hand")
                else tr("副屏静音关着；可手动静音、恢复应用", "Screen muting off; mute or unmute apps by hand"), onClick = { nav.go(Route.Sound) })
            val idleSave by com.kilon.agentvscreen.core.VScreenStore.idlePowerSave.collectAsState()
            SwitchPreference(checked = idleSave, onCheckedChange = { BackgroundOperation.setIdlePowerSave(it) },
                title = tr("空闲省电", "Idle power saving"), summary = tr("一轮结束后暂停渲染", "Pause rendering between turns"))
            val opts = listOf(1, 3, 5, 10, 30)
            var quiet by remember { mutableIntStateOf(ApiConfig.quietMinutes) }
            OverlayDropdownPreference(items = opts.map { tr("$it 分钟", "$it min") }, selectedIndex = opts.indexOf(quiet).coerceAtLeast(0),
                title = tr("一轮的长度", "Turn length"), summary = tr("agent 安静多久算这一轮结束", "How long the agent stays quiet before its turn ends"),
                onSelectedIndexChange = { i -> ApiConfig.quietMinutes = opts[i]; quiet = opts[i] })
        }

        Group(tr("关于与诊断", "About and diagnostics")) {
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
            SwitchPreference(checked = s.accurateOcr, onCheckedChange = { BackgroundOperation.setAccurateOcr(it) },
                title = tr("精确文字识别", "Accurate text recognition"), summary = tr("PP-OCRv6，在本机运行", "PP-OCRv6, on this phone"))
            DetectorPreference()
            SwitchPreference(checked = s.recording, onCheckedChange = { BackgroundOperation.setRecording(it) },
                title = tr("后台录制", "Background recording"), summary = tr("回看刚才发生了什么时用", "Used to replay what just happened"))
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
            ArrowPreference(title = tr("事件广播", "Event broadcast"), endActions = { Value(ApiConfig.eventTarget ?: tr("不广播", "Off")) }, onClick = { eventDialog = true })
            ArrowPreference(title = tr("操作日志", "Activity log"), summary = tr("agent 发来的每条命令", "Every command agents sent"), onClick = { nav.go(Route.Log) })
            ArrowPreference(title = tr("诊断", "Diagnostics"), onClick = { nav.go(Route.Diag) })
            ArrowPreference(title = tr("帮助", "Help"), summary = tr("每个设置是什么意思", "What each setting means"), onClick = { nav.go(Route.Help) })
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
        Footnote(tr("只在后台副屏里生效，不影响你自己用。", "Applies only to the background screen, not to your own use."))
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
    val unused = remember(list, agents) { Tokens.neverUsed { id -> agents[id]?.used == true } }
    val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT) }
    MiuixLazyPage(tr("令牌", "Tokens"), actions = {
        LinkButton(onClick = { adding = true }, modifier = Modifier.padding(end = 12.dp)) { Text(tr("新建", "New")) }
    }) {
        item(key = "main") {
            Group(tr("主令牌", "Main token")) {
                BasicComponent(
                    title = if (show) token else "•".repeat(12) + token.takeLast(4),
                    summary = tr("只给你信任的 agent", "Only for agents you trust"),
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
                BasicComponent(title = tr("删除从没用过的 ${unused.size} 个令牌", "Remove ${unused.size} never-used tokens"),
                    summary = tr("没有任何请求用过它们；主令牌和公网客户端不受影响", "No request ever used them; the main token and public clients are kept"),
                    onClick = { askUnused = unused })
            }
        }
        cardItems(tr("每个 agent 一个令牌 · ${others.size}", "One token per agent · ${others.size}"), others, key = { it.id }) { t ->
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
            tr("agent 在哪", "Where the agent runs") to H.where,
            tr("接入方式", "How it connects") to H.how,
            tr("令牌", "Tokens") to (H.token + "\n\n" + H.tokens),
            tr("什么时候问你", "When you are asked") to H.autonomy,
            tr("应用规则", "App rules") to H.rules,
            tr("悬浮窗", "Floating window") to H.window,
            tr("空闲省电", "Idle power saving") to H.idle,
            tr("副屏静音", "Mute the screen") to H.mute,
            tr("一轮的长度", "Turn length") to H.turn,
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
    val where get() = tr("同一台手机上的 agent（Termux、Minis、Operit 等）直接连本机 127.0.0.1:${ApiConfig.port}，什么都不用设。\n\n电脑、云端或另一台手机上的 agent 通过 Tailscale 连进来：只有你自己 Tailscale 网络里的设备能连，走加密隧道，局域网和公网都连不到，令牌照样要。“内置”不用装 Tailscale App，“外部 App”用这台手机上已装的 Tailscale。",
        "Agents on this phone (Termux, Minis, Operit, ...) connect to 127.0.0.1:${ApiConfig.port} directly; nothing to set up.\n\nAgents on a computer, in the cloud or on another phone connect through Tailscale: only devices in your own Tailscale network can, through an encrypted tunnel; the local network and the internet cannot, and the token is still required. \"Built-in\" needs no Tailscale app; \"External app\" uses the Tailscale app already on this phone.")
    val how get() = tr("命令行：带终端的 agent。把“给 agent 的话”发给它，它会装好 vscreen 命令，并把“我能在手机上后台操作应用”记进长期记忆，以后的新对话里也会用。只给安装命令的话，大多数 agent 装完就忘了。\n\nMCP：支持 MCP 的 agent。发给它的话里有 MCP 配置（streamable HTTP，每条命令一个工具，截图附在结果里）和能力说明。\n\nHTTP：自己写调用代码的 agent 应用。返回 JSON（ok、code、next、text、images、steps、screen、notes），完整说明在 GET /v2/help。\n\n能力说明也可以直接贴进 agent 的“自定义指令”或“记忆”设置。",
        "Shell: agents with a terminal. Send them the message: it installs the vscreen command and has the agent save \"I can operate apps on the phone in the background\" in its long-term memory, so new conversations use it too. With only the install command, most agents forget it.\n\nMCP: agents with MCP. The message has the MCP config (streamable HTTP, one tool per command, screenshots attached) and the ability note.\n\nHTTP: agent apps that write their own calls. Replies are JSON (ok, code, next, text, images, steps, screen, notes); full reference at GET /v2/help.\n\nYou can also paste the ability note into the agent's custom instructions or memory setting.")
    val token get() = tr("令牌只给你信任的 agent：拿到它就能在副屏上操作你的应用。服务只接受本机（和打开远程时你 Tailscale 网络里的设备），网页调用一律拒绝。换新令牌后旧的立即失效，各 agent 要重新接入。",
        "Give the token only to agents you trust: with it they can operate your apps on the screen. Only this phone (and, with remote access on, devices in your Tailscale network) can connect; calls from web pages are always refused. A new token stops the old one at once; every agent has to connect again.")
    val autonomy get() = tr("付钱、接入新 agent、放宽一个 agent 能做的事：用指纹（没设锁屏时按住 3 秒）。\n\n其它要问的（删了回不来的、要小心的、你设了“每次询问”的应用）：手机主屏上点一下“允许”。可以选“只这一次”“这一轮任务内”（agent 安静超过“一轮的长度”就结束）或“今天”，范围内同一个 agent、同一类步骤、同一个应用不再问；付钱只能一次一问。\n\n不管哪一档：每一步都记进操作日志；悬浮窗上随时可以停止或接管；没答复的确认 60 秒后这一步不做，agent 会来找你。",
        "Paying, a new agent, widening what an agent may do: fingerprint (a 3-second hold without a screen lock).\n\nEverything else that asks (what cannot be undone, steps that need care, apps you set to ask every time): one tap on Allow on the phone's main screen. You can pick Just this once, This run (ends when the agent has been quiet for a turn length) or Today; within it the same agent, kind of step and app are not asked again. Paying is always asked step by step.\n\nAt any level: every step is logged; you can stop or take over from the floating window at any time; a confirmation left unanswered for 60 s skips that step and the agent comes to you.")
    val rules get() = tr("禁止操作：AI 不能在后台打开、读屏或操作这个应用。\n只读：AI 可以打开、看和滚动，但不能点击、输入或滑动。\n每次询问：AI 要点击或输入前，手机上先弹确认，可以选这一次、这一轮或今天内不再问。\n\n只在后台副屏里生效，不影响你自己用。",
        "Blocked: the AI cannot open, read or operate the app in the background.\nRead-only: the AI can open, look and scroll, but not tap, type or swipe.\nAsk every time: before the AI taps or types, the phone asks you; you can allow it once, for this run or for today.\n\nApplies only to the background screen, not to your own use.")
    val tokens get() = tr("每个 agent 一个令牌：各有自己的档位，可以单独删掉，日志里也分得清是谁做的。不同令牌的 agent 互相抢不了对方的会话。\n\n“接下来 30 分钟付钱也不问”在 agent 页里：这段时间里它连付款也不再问你（仍会记进日志）。只在你盯着它付款时用。",
        "One token per agent: each has its own level, can be removed alone, and the log shows who did what. Agents with different tokens cannot take each other's sessions.\n\n\"Don't ask about payments for 30 minutes\" is on the agent's page: meanwhile it is not asked even about payments (still logged). Use it only while you watch it pay.")
    val window get() = tr("有副屏时在所有应用上方显示。拖动移动，双指缩放，推到屏幕边上收成胶囊，长按画面 0.5 秒接管。agent 在等你回答时，胶囊会变成紫色的问号。",
        "Shown above all apps while there is a screen. Drag to move, pinch to resize, push to an edge to fold it into a tab, hold the picture 0.5 s to take over. While an agent waits for your answer, the tab turns into a violet question mark.")
    val idle get() = tr("一轮结束后暂停副屏渲染，页面保留，下一条命令自动恢复。个别应用恢复时会重新加载，遇到可以关掉。", "Pauses rendering after a turn ends; the page is kept and the next command resumes it. A few apps reload on resume: turn this off if you see that.")
    val mute get() = tr("副屏里的应用不从手机放声音（视频、直播）。应用移到主屏、副屏释放时恢复声音；App 异常退出后，下次连接时先恢复。", "Apps on the background screen make no sound on the phone (videos, live streams). Their sound comes back when they move to the main screen or the screen is released; after a crash it is restored on the next connection.")
    val turn get() = tr("agent 安静多久算这一轮结束：之后副屏省电暂停，10 分钟后释放。agent 也可以用 vscreen done 马上结束。", "How long the agent stays quiet before its turn ends: the screen then pauses to save power and is released after 10 minutes. The agent can also end at once with vscreen done.")
    val lessons get() = tr("你在副屏上演示过、保存下来的操作。agent 用 act \"use 名字\" 照着做一遍；点开能看到每一步，用不到的可以删掉。\n\n示教在全屏页录：agent 用 teach 请你演示，或者你接管后自己点录制。",
        "What you showed on the background screen and saved. An agent runs one with act \"use name\"; open one to see its steps, delete the ones you no longer need.\n\nLessons are recorded on the full screen page: an agent asks with teach, or you take over and tap record.")
    val schedule get() = tr("agent 的定时任务要用手机时，可以用 vscreen schedule 预约时间。到点前 3 分钟这里会拉起服务并检查，没准备好会通知你。\n\n服务暂停时，下一个预约到点会自动打开；完全关闭时，到点不会打开，只通知你。\n\n点开一条可以像闹钟一样改时间、重复和保持时长；关掉开关会保留这条预约，但到点不触发。",
        "An agent whose timed task uses the phone can book the time with vscreen schedule. 3 minutes before, the service is started and checked here, and you are notified if it is not ready.\n\nWhile the service is paused, the next booking turns it on; while it is off, a booking only notifies you.\n\nOpen one to change its time, repeat and length like an alarm; switching it off keeps it but it does not fire.")
    val audit get() = tr("每条命令都记在这里：谁（哪个令牌、哪台设备）、什么时候、做了什么、结果。输入的文字只记字数。", "Every command is recorded here: who (which token, which device), when, what, and the result. Typed text is recorded as a length only.")
    val events get() = tr("盯守看到条件时的事件默认只放进队列（agent 用 vscreen events 取）。填了包名才会另外广播给那一个应用，别的应用收不到。", "When a watch sees its condition the event is only queued by default (agents collect it with vscreen events). With a package here it is also broadcast to that one app; no other app receives it.")
    val privilege get() = tr("Agent-VScreen 需要 shell 级权限来开副屏、点按和启动应用，有两种来源，二选一：\n\n• Shizuku（默认）：不需要 root。Shizuku 每次手机重启后要在它的 App 里重新启动。\n• Root：手机已用 Magisk、KernelSU、APatch 等 root 时可用，不需要装 Shizuku。第一次会弹 root 授权框，之后开机打开 App 就自动启动。副屏服务尽量以 shell 身份运行，和 Shizuku 的 ADB 模式完全一样。\n\n切换时当前副屏会关闭，下一条命令通过新来源自动重连。",
        "Agent-VScreen needs shell-level permission to open the background screen, tap and start apps. It comes from one of two sources:\n\n• Shizuku (default): no root needed. Shizuku must be started again in its app after each phone restart.\n• Root: for phones rooted with Magisk, KernelSU, APatch and the like; Shizuku is not needed. The root manager asks once; after that it starts by itself when the app opens. The screen service runs as the shell user where it can, exactly as under Shizuku's ADB mode.\n\nSwitching closes the current screen; the next command reconnects through the new source by itself.")
    val setup get() = tr("只用 Shizuku（或 Root）这一条特权通道：不占用 adb，不改无线调试，不重启 adbd。每一项都只把本应用“加进去”，不动别的应用的设置。你的 agent 自己的 adb / Shizuku 照常用。", "Uses only Shizuku (or root) as its privilege channel: adb is not taken, wireless debugging is not changed, adbd is not restarted. Each item only adds this app and leaves other apps' settings alone. Your agent's own adb / Shizuku keep working.")
    val diag get() = tr("出问题时把诊断信息发给开发者：版本、配置状态和最近日志（不含令牌、不含你输入的字）。", "When something goes wrong, send this to the developer: version, setup state and recent log (no token, nothing you typed).")
}

/**
 * 2.0.0-beta8 (feedback 2, rainbow 00:52): the levels side by side, one row per class of action, as a table the user
 * reads once. Each agent picks a level (or its own table) on its own page; nothing here is a setting.
 */
@Composable
fun SecurityPage() {
    val c = MiuixTheme.colorScheme
    val levels = Tokens.LEVELS.filter { it != com.kilon.agentvscreen.api.Policy.CUSTOM }
    MiuixPage(title = tr("各档怎么处理", "How each level handles things")) {
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
        Footnote(tr("每个 agent 在 接入 › 这个 agent 里选一档，也可以逐项改或用“自定义”。放宽要指纹，收紧马上生效。", "Each agent picks a level under Connect › the agent, and can change single rows or use Custom. Widening takes a fingerprint; narrowing applies at once."))
        Group(tr("问你的时候", "When you are asked"), padded = true) {
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
                        "• 显示悬浮窗：有副屏时在最上层显示实时画面。\n\n• 小窗显示按钮：开启后，右上角小窗也显示标题、全屏、关闭和停止 AI 按钮。\n\n• 点小窗自动放大：默认开启；关闭后轻点小窗不再居中放大，只保留拖动、贴边折叠和长按操作。",
                        "• Show floating window: displays the live background screen above other apps.\n\n• Show buttons on mini window: when on, the compact corner window also shows title, fullscreen, close, and stop-AI buttons.\n\n• Tap mini window to expand: on by default; turn off so tapping the mini window stays compact instead of expanding to center.",
                    ),
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = { LinkButton(onClick = { help = false }) { Text(tr("完成", "Done")) } },
        )
    }
}


