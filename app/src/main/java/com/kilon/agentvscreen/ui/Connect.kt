package com.kilon.agentvscreen.ui

import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.size
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.AgentGuide
import com.kilon.agentvscreen.api.AgentLedger
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.ServiceControl
import com.kilon.agentvscreen.host.Tailnet
import com.kilon.agentvscreen.host.Tailnet.Phase as TsPhase
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import com.kilon.agentvscreen.core.BackgroundOperation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 1.4 接入 agent: two questions (where the agent runs, how it connects), one button that copies the message,
 * then the raw pieces for people who want them. 远程连接 is its own page: the Tailscale phase, its one action,
 * and the advanced options in a sheet. No toasts: feedback goes through the snackbar (host.say).
 */

/** The address an agent on another device uses; kept through short gaps so what the user copies does not flip back. */
@Composable
private fun rememberRemoteBase(mode: Tailnet.Mode): String? {
    val ts by Tailnet.status.collectAsState()
    var sysAddr by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { sysAddr = HostService.server?.tailAddress; delay(1_000) } }
    val live = when (mode) {
        Tailnet.Mode.BUILTIN -> ts.ip?.takeIf { ts.loggedIn }
        Tailnet.Mode.SYSTEM -> sysAddr
        else -> null
    }
    var last by remember(mode) { mutableStateOf<String?>(null) }
    if (live != null && live != last) last = live
    return (live ?: last)?.let { "http://$it:${ApiConfig.port}" }
}

@Composable
fun ConnectPage(tab: Boolean = false, bottom: androidx.compose.ui.unit.Dp = 0.dp) {
    // 2.0.0-beta8 (F.1): connected agents + add button + 3 peer connection methods (this phone / Tailscale / Cloud MCP).
    val nav = LocalNav.current
    val agents by AgentLedger.agents.collectAsState()
    val tokens by com.kilon.agentvscreen.api.Tokens.list.collectAsState()
    val ctx = LocalContext.current
    val ts by Tailnet.status.collectAsState()
    val pubOn by com.kilon.agentvscreen.host.PublicAccess.enabledFlow.collectAsState()
    val svcState = ServiceControl.now(ctx)
    MiuixPage(tr("接入", "Connect"), back = !tab, bottom = bottom) {
        val shown = tokens.filter { t -> agents[t.id]?.used == true || t.id != com.kilon.agentvscreen.api.Tokens.MAIN }
        if (shown.isEmpty()) EmptyState(androidx.compose.material.icons.Icons.Outlined.AddLink, tr("还没有接入 agent", "No agent connected yet"),
            tr("接入后，每个 agent 的记录、任务和提问各归各的", "Once connected, each agent's records, tasks and questions stay apart"))
        else Group(tr("已经接入的 · ${shown.size}", "Connected · ${shown.size}")) {
            shown.forEach { t -> AgentCard(t.id, agents[t.id] ?: AgentLedger.Snapshot()) { nav.go(Route.Agent(t.id)) } }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            PrimaryButton(onClick = { nav.go(Route.NewAgent) }, modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.primary) {
                Text(tr("接入新的 agent", "Connect a new agent"), color = MiuixTheme.colorScheme.onPrimary)
            }
        }
        Group(tr("连接方式", "Connection methods")) {
            val localStatus = when (svcState) {
                ServiceControl.State.ON -> tr("可用", "Ready")
                ServiceControl.State.HIDDEN -> tr("用到时唤起", "On demand")
                ServiceControl.State.BLOCKED -> tr("已关闭", "Off")
            }
            BasicComponent(
                title = tr("这台手机上", "On this phone"),
                summary = "127.0.0.1:${ApiConfig.port}",
                endActions = {
                    Value(localStatus, if (svcState == ServiceControl.State.BLOCKED) Palette.stopped else Palette.takeover)
                },
            )
            ArrowPreference(
                title = tr("其它设备（Tailscale）", "Other devices (Tailscale)"),
                summary = remember(ts) { Tailnet.line(ctx) },
                onClick = { nav.go(Route.Remote) },
            )
            val pubStatus = when {
                !pubOn -> tr("未开启", "Off")
                ts.funnel == "on" -> tr("已开启", "On")
                ts.funnel == "cert" -> tr("申请证书中", "Cert…")
                ts.funnel == "error" -> tr("启动失败", "Failed")
                else -> tr("启动中", "Starting")
            }
            ArrowPreference(
                title = tr("云端（公网 MCP）", "Cloud (Public MCP)"),
                summary = if (pubOn) com.kilon.agentvscreen.host.PublicAccess.mcpUrl() ?: tr("通过 Tailscale Funnel 连接", "Via Tailscale Funnel")
                    else tr("供云端 MCP 客户端连接", "For cloud MCP clients"),
                endActions = {
                    Value(
                        pubStatus,
                        when {
                            pubOn && ts.funnel == "on" -> Palette.takeover
                            pubOn && ts.funnel == "error" -> Palette.stopped
                            pubOn -> Palette.standby
                            else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                        },
                    )
                },
                onClick = { nav.go(Route.PublicAccess) },
            )
        }
        Group {
            ArrowPreference(title = tr("令牌", "Tokens"), onClick = { nav.go(Route.Tokens) })
        }
    }
}

private fun scopeLabel(scope: String): String = when (scope) {
    "look" -> tr("只看", "Look only")
    "operate" -> tr("看和操作", "Look & operate")
    "system" -> tr("看、操作和系统操作", "Look, operate & system")
    else -> scope
}

private fun scopeRisksFor(scope: String, includeNewClient: Boolean): List<com.kilon.agentvscreen.host.Risk> = buildList {
    if (includeNewClient) add(com.kilon.agentvscreen.host.Risk.NEW_CLIENT)
    if (scope == "operate" || scope == "system") add(com.kilon.agentvscreen.host.Risk.SCOPE_OPERATE)
    if (scope == "system") add(com.kilon.agentvscreen.host.Risk.SCOPE_SYSTEM)
}

/** 2.0.0-beta8 (B2, F.2, F.3, K.3): connecting one agent in 4 steps — name+scope (one-shot auth), where (local/Tailscale/Cloud), how (or Cloud checklist), then unified preview & copy. */
@Composable
fun NewAgentPage() {
    val host = LocalHost.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val actx = ctx.applicationContext
    var where by rememberSaveable { mutableIntStateOf(com.kilon.agentvscreen.host.Access.lastWay()) }
    var how by rememberSaveable { mutableIntStateOf(0) }
    var raw by remember { mutableStateOf(false) }
    var selectedScope by rememberSaveable { mutableStateOf("operate") }
    val tokens by com.kilon.agentvscreen.api.Tokens.list.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var made by rememberSaveable { mutableStateOf<String?>(null) }
    var madeId by rememberSaveable { mutableStateOf<String?>(null) }
    var madeFor by rememberSaveable { mutableStateOf<String?>(null) }
    var grantedScope by rememberSaveable { mutableStateOf<String?>(null) }
    val suggested = remember(tokens) {
        val used = tokens.map { it.name }.toSet()
        val base = tr("默认 Agent", "Default Agent")
        "$base ${generateSequence(1) { it + 1 }.first { "$base $it" !in used }}"
    }

    fun ensureLocalToken(): Pair<String, String> {
        val want = name.trim().ifEmpty { suggested }
        val existingText = made
        val existingId = madeId
        if (existingText != null && existingId != null && madeFor == want && com.kilon.agentvscreen.api.Tokens.byId(existingId) != null) {
            if (com.kilon.agentvscreen.api.Tokens.byId(existingId)?.scope != selectedScope) {
                com.kilon.agentvscreen.api.Tokens.setScope(existingId, selectedScope)
            }
            return existingId to existingText
        }
        val (t, text) = com.kilon.agentvscreen.api.Tokens.create(want, selectedScope)
        com.kilon.agentvscreen.host.Audit.security("token_created", "${t.name} ${t.scope}")
        made = text; madeId = t.id; madeFor = want; grantedScope = null
        return t.id to text
    }

    val ts by Tailnet.status.collectAsState()
    val pubEnabled by com.kilon.agentvscreen.host.PublicAccess.enabledFlow.collectAsState()
    var mode by remember { mutableStateOf(ApiConfig.tailnetMode) }
    LaunchedEffect(Unit) { while (true) { mode = ApiConfig.tailnetMode; delay(1_000) } }
    val remoteBase = rememberRemoteBase(mode)
    val remoteReady = where == 1 && remoteBase != null
    val cloudUrl = remember(ts.dnsName) { com.kilon.agentvscreen.host.PublicAccess.mcpUrl() }
    val base = if (where == 1 && remoteBase != null) remoteBase else ApiConfig.baseUrl
    val canCopyLocal = where == 0 || remoteReady
    val canAdvanceWhere = where == 0 || remoteReady || where == 2
    val canAdvanceHow = if (where == 2) (pubEnabled && !cloudUrl.isNullOrBlank()) else canCopyLocal

    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = listOf(tr("起名", "Name"), tr("在哪", "Where"), tr("怎么连", "How"), tr("发给它", "Send"))
    MiuixPage(tr("接入新的 agent", "Connect a new agent")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEachIndexed { i, label ->
                val on = i <= step
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled = i < step) { step = i }.padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh))
                    Text("${i + 1} · $label", fontSize = 12.sp, fontWeight = if (i == step) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }

        if (step == 0) {
            Group(tr("它叫什么", "Its name")) {
                TextField(value = name, onValueChange = { name = it.take(24) }, label = tr("例如：通用助手（$suggested）", "e.g. General assistant ($suggested)"),
                    useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
            }
            Group(tr("这个 agent 能做什么", "What this agent can do")) {
                listOf(
                    Triple("look", tr("只看", "Look only"), tr("查看副屏画面、文字和通知", "View screen, text and notifications")),
                    Triple("operate", tr("看和操作", "Look & operate"), tr("打开应用、点击、输入、滚动（默认）", "Open apps, tap, type, scroll (default)")),
                    Triple("system", tr("看、操作和系统操作", "Look, operate & system"), tr("包含修复权限、安装、清理等系统级命令", "Includes system-level commands like fix and cleanup")),
                ).forEach { (sc, title, sub) ->
                    RadioButtonPreference(
                        title = title,
                        summary = sub,
                        selected = selectedScope == sc,
                        onClick = { selectedScope = sc },
                    )
                }
            }
        }

        if (step == 1) Group(tr("agent 在哪", "Where the agent runs")) {
            RadioButtonPreference(
                title = tr("这台手机上", "On this phone"),
                summary = tr("Termux、Operit 等，直接连 127.0.0.1:${ApiConfig.port}", "Termux, Operit…, direct to 127.0.0.1:${ApiConfig.port}"),
                selected = where == 0,
                onClick = { where = 0 },
            )
            RadioButtonPreference(
                title = tr("其它设备（Tailscale）", "Other device (Tailscale)"),
                summary = tr("电脑或另一台手机，经你自己的 Tailscale 网络", "Computer or another phone over your own Tailscale network"),
                selected = where == 1,
                onClick = { where = 1 },
            )
            if (where == 1) Row(Modifier.fillMaxWidth().clickable { nav.go(Route.Remote) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(tr("Tailscale 连接", "Tailscale connection"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(remoteBase ?: tr("先连好 Tailscale，才能复制给其它设备的内容", "Connect Tailscale first to copy what the other device needs"),
                        fontSize = 14.sp, fontFamily = if (remoteBase != null) FontFamily.Monospace else null, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (remoteReady) Palette.takeover else Palette.stopped, 7.dp)
                        Text("  " + remember(ts, mode, remoteBase) { Tailnet.line(ctx) }, fontSize = 13.sp, color = if (remoteReady) Palette.takeover else Palette.stopped, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            RadioButtonPreference(
                title = tr("云端（公网 MCP）", "Cloud (Public MCP)"),
                summary = tr("Claude / ChatGPT 等云端客户端，经 Tailscale Funnel 配对连接", "Cloud MCP clients via Tailscale Funnel with on-phone pairing"),
                selected = where == 2,
                onClick = { where = 2 },
            )
        }

        if (step == 2) {
            if (where == 2) {
                if (pubEnabled) {
                    PublicRunningSection(ts = ts, mcpUrl = cloudUrl, onDisable = null)
                } else {
                    PublicChecklistSection(showEnableButton = true)
                }
            } else {
                // 2.0.0-beta8 (K.3): concise subtitles avoid single-character orphan line wraps.
                Group(tr("怎么连", "How it connects")) {
                    listOf(
                        Triple(tr("命令行", "Shell"), tr("带终端的 agent：安装 vscreen 并记住能力", "Terminal agents: installs vscreen and saves note"), 0),
                        Triple("MCP", tr("支持 MCP 的 agent：配置与能力说明", "MCP agents: config and ability note"), 1),
                        Triple("HTTP", tr("自写调用的 agent：地址、令牌和说明", "Custom HTTP clients: address, token and note"), 2),
                    ).forEach { (t, sub, i) -> RadioButtonPreference(title = t, summary = sub, selected = how == i, onClick = { how = i; raw = false }) }
                }
            }
        }

        if (step < 3) Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            val nextEnabled = when (step) {
                0 -> true
                1 -> canAdvanceWhere
                else -> canAdvanceHow
            }
            PrimaryButton(
                onClick = {
                    when (step) {
                        0 -> {
                            // 2.0.0-beta8 (B2): authorize NEW_CLIENT + chosen scopes in one confirmation.
                            val want = name.trim().ifEmpty { suggested }
                            val (tid, _) = ensureLocalToken()
                            if (grantedScope == selectedScope && madeFor == want) {
                                step = 1
                            } else {
                                val risks = scopeRisksFor(selectedScope, includeNewClient = true)
                                val tokObj = com.kilon.agentvscreen.api.Tokens.byId(tid)
                                    ?: com.kilon.agentvscreen.api.Tokens.Token(tid, want, "", selectedScope, System.currentTimeMillis())
                                val req = com.kilon.agentvscreen.host.StrongConfirm.createUiMultiRiskRequest(
                                    ctx = actx,
                                    risks = risks,
                                    token = tokObj,
                                    commandName = tr("接入新 agent：${scopeLabel(selectedScope)}", "Connect agent: ${scopeLabel(selectedScope)}"),
                                    detail = tr("确认后该 agent 在所选权限内无需逐项重复弹窗。", "After confirmation this agent will not prompt separately for each granted scope."),
                                    onApprove = {
                                        com.kilon.agentvscreen.host.StrongConfirm.grantForScope(tid, selectedScope)
                                        grantedScope = selectedScope
                                        step = 1
                                    },
                                )
                                StrongConfirmActivity.open(actx, req.id)
                            }
                        }
                        1 -> {
                            if (where == 2) {
                                // Cloud clients receive their own OAuth identity during on-phone pairing; discard unused draft local token.
                                madeId?.let { id ->
                                    if (AgentLedger.agents.value[id]?.used != true) {
                                        com.kilon.agentvscreen.api.Tokens.revoke(id)
                                        madeId = null; made = null; grantedScope = null
                                    }
                                }
                            }
                            step = 2
                        }
                        else -> step = 3
                    }
                },
                enabled = nextEnabled,
                modifier = Modifier.fillMaxWidth(),
                color = MiuixTheme.colorScheme.primary,
            ) {
                Text(tr("下一步", "Next"), color = MiuixTheme.colorScheme.onPrimary)
            }
        }

        if (step == 3) {
            if (where == 2) {
                val url = cloudUrl.orEmpty()
                Group(tr("添加到云端 MCP 客户端", "Add to your cloud MCP client"), padded = true) {
                    Text(
                        tr("复制下方地址填进云端客户端的 MCP 服务器设置，添加后在手机上确认配对即可（不需要令牌）。", "Paste the MCP URL below into your cloud client, then approve the pairing request on this phone (no token needed)."),
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (url.isNotBlank()) {
                    WrappableCode(url) { host.copy(tr("公网 MCP 地址", "Public MCP URL"), url) }
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        PrimaryButton(
                            onClick = { host.copy(tr("公网 MCP 地址", "Public MCP URL"), url) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MiuixTheme.colorScheme.primary,
                        ) {
                            Text(tr("复制公网 MCP 地址", "Copy public MCP URL"), color = MiuixTheme.colorScheme.onPrimary)
                        }
                    }
                }
            } else {
                // 2.0.0-beta8 (F.3): single computed string drives BOTH the on-screen preview AND clipboard copy.
                val (_, tok) = remember(made, madeId, madeFor, selectedScope) { ensureLocalToken() }
                val messageText = remember(how, base, tok) {
                    when (how) {
                        1 -> AgentGuide.onboardMcp(base, tok)
                        2 -> AgentGuide.onboardHttp(base, tok)
                        else -> AgentGuide.onboard(base, tok)
                    }
                }
                Group(tr("发给 ${name.trim().ifEmpty { suggested }}", "Send to ${name.trim().ifEmpty { suggested }}")) {
                    BasicComponent(title = tr("在哪", "Where"), summary = if (where == 0) tr("这台手机上 · 127.0.0.1:${ApiConfig.port}", "This phone · 127.0.0.1:${ApiConfig.port}") else tr("其它设备（Tailscale） · $base", "Other device (Tailscale) · $base"))
                    BasicComponent(title = tr("权限", "Scope"), summary = scopeLabel(selectedScope))
                    BasicComponent(title = tr("怎么连", "How"), summary = listOf(tr("命令行", "Shell"), "MCP", "HTTP")[how])
                }
                Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                    PrimaryButton(
                        onClick = { host.copy(tr("给 agent 的话", "Message for your agent"), messageText) },
                        enabled = canCopyLocal,
                        modifier = Modifier.fillMaxWidth(),
                        color = MiuixTheme.colorScheme.primary,
                    ) {
                        Text(tr("复制给 agent 的话", "Copy message for your agent"), color = MiuixTheme.colorScheme.onPrimary)
                    }
                }
                Footnote(tr("每个 agent 发它自己的一段话：记录、任务和提问各归各的。", "One message per agent: their records, tasks and questions stay apart."))
                Group(tr("预览与单独的部分", "Preview & pieces")) {
                    val pieceText = remember(how, base, tok) {
                        when (how) {
                            0 -> ApiConfig.installLine(base, tok)
                            1 -> ApiConfig.mcpJson(base, tok)
                            else -> messageText
                        }
                    }
                    ArrowPreference(
                        title = if (how == 0) tr("安装命令", "Install command") else if (how == 1) tr("MCP 配置", "MCP config") else tr("完整消息预览", "Message preview"),
                        endActions = { Value(if (raw) tr("收起", "Hide") else tr("查看", "Show")) },
                        enabled = canCopyLocal,
                        onClick = { raw = !raw },
                    )
                    AnimatedVisibility(raw && canCopyLocal) {
                        Code(pieceText) { host.copy(if (how == 0) tr("安装命令", "Install command") else if (how == 1) tr("MCP 配置", "MCP config") else tr("给 agent 的话", "Message"), pieceText) }
                    }
                    BasicComponent(title = tr("能力说明", "Ability note"), summary = tr("贴进 agent 的自定义指令或记忆", "Paste into the agent's custom instructions or memory"),
                        endActions = {
                            IconButton(onClick = { host.copy(tr("能力说明", "Ability note"), when (how) { 1 -> AgentGuide.memoryMcp(); 2 -> AgentGuide.memoryHttp(base); else -> AgentGuide.memory(base) }) }, enabled = canCopyLocal) {
                                Icon(MiuixIcons.Copy, tr("复制", "Copy"))
                            }
                        })
                }
            }
        }
    }
}

/** 1.4.0-beta9 + 2.0.0-beta8 (B2): an agent's own page: state, editable scope (downgrade immediate, upgrade confirmed), name, forget. */
@Composable
fun AgentPage(id: String) {
    val nav = LocalNav.current
    val host = LocalHost.current
    val actx = LocalContext.current.applicationContext
    val main = id == com.kilon.agentvscreen.api.Tokens.MAIN
    val agents by AgentLedger.agents.collectAsState()
    val tokens by com.kilon.agentvscreen.api.Tokens.list.collectAsState()
    val token = remember(tokens, id) { tokens.firstOrNull { it.id == id } }
    val l = agents[id] ?: AgentLedger.Snapshot()
    var n by remember { mutableStateOf(com.kilon.agentvscreen.api.Agents.name(id)) }
    var sure by remember { mutableStateOf(false) }
    MiuixPage(com.kilon.agentvscreen.api.Agents.name(id)) {
        Group { AgentCard(id, l) {} }
        val k = keptOf(l)
        InfoCard(if (k == Kept.CAME_BACK) Palette.takeover else if (k == Kept.CONNECTED) Palette.stopped else Palette.standby,
            when (k) { Kept.CAME_BACK -> tr("已记下，后来也在用", "Saved, and still using it"); Kept.SAVED -> tr("已记下说明", "Note saved"); Kept.CONNECTED -> tr("还没说记在哪", "Has not said where it saved it"); Kept.NONE -> tr("还没连上过", "Not connected yet") },
            remember(l, id) { keptText(id, l) })
        AgentNow(id)
        if (!main && token != null) {
            val allowedScopes = if (token.publicOnly) com.kilon.agentvscreen.api.Tokens.PUBLIC_SCOPES else com.kilon.agentvscreen.api.Tokens.SCOPES
            val curRank = allowedScopes.indexOf(token.scope).coerceAtLeast(0)
            Group(tr("权限", "Permissions")) {
                allowedScopes.forEachIndexed { idx, sc ->
                    RadioButtonPreference(
                        title = scopeLabel(sc),
                        summary = when (sc) {
                            "look" -> tr("只能查看副屏", "Read-only access")
                            "operate" -> tr("可点击、输入、滚动和打开应用", "Tap, type, scroll and open apps")
                            else -> tr("含系统级命令", "Includes system commands")
                        },
                        selected = token.scope == sc,
                        onClick = {
                            if (sc == token.scope) return@RadioButtonPreference
                            if (idx < curRank) {
                                // Downgrade takes effect immediately (B2).
                                com.kilon.agentvscreen.api.Tokens.setScope(id, sc)
                                com.kilon.agentvscreen.host.StrongConfirm.revokeScopesAbove(id, sc)
                                com.kilon.agentvscreen.host.Audit.security("scope_downgrade", "${token.name} ${token.scope}->$sc")
                                host.say(tr("已调整权限为：${scopeLabel(sc)}", "Scope updated: ${scopeLabel(sc)}"))
                            } else {
                                // Upgrade requires strong confirmation on phone (B2).
                                val addedRisks = buildList {
                                    if (curRank < 1 && idx >= 1) add(com.kilon.agentvscreen.host.Risk.SCOPE_OPERATE)
                                    if (curRank < 2 && idx >= 2) add(com.kilon.agentvscreen.host.Risk.SCOPE_SYSTEM)
                                }
                                val req = com.kilon.agentvscreen.host.StrongConfirm.createUiMultiRiskRequest(
                                    ctx = actx,
                                    risks = addedRisks,
                                    token = token,
                                    commandName = tr("提升权限为 ${scopeLabel(sc)}", "Upgrade scope to ${scopeLabel(sc)}"),
                                    detail = tr("确认后该 agent 将立即获得上述权限。", "Once confirmed, this agent immediately gains the selected scope."),
                                    onApprove = {
                                        com.kilon.agentvscreen.api.Tokens.setScope(id, sc)
                                        com.kilon.agentvscreen.host.StrongConfirm.grantForScope(id, sc)
                                        com.kilon.agentvscreen.host.Audit.security("scope_upgrade", "${token.name} ${token.scope}->$sc")
                                        host.say(tr("已提升权限为：${scopeLabel(sc)}", "Scope upgraded: ${scopeLabel(sc)}"))
                                    },
                                )
                                StrongConfirmActivity.open(actx, req.id)
                            }
                        },
                    )
                }
            }
        }
        Group(tr("名字", "Name")) {
            TextField(value = n, onValueChange = { n = it.take(24) }, label = tr("名字", "Name"), useLabelAsPlaceholder = true, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
            val can = n.isNotBlank() && n.trim() != com.kilon.agentvscreen.api.Agents.name(id)
            Text(tr("保存名字", "Save name"), fontSize = 15.sp, color = if (can) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().clickable(enabled = can) { com.kilon.agentvscreen.api.Tokens.rename(id, n.trim()) }.padding(horizontal = 16.dp, vertical = 14.dp))
        }
        if (!main) Group {
            Column(Modifier.fillMaxWidth().clickable {
                if (!sure) sure = true else {
                    Thread { com.kilon.agentvscreen.api.Agents.forget(actx, id) }.start()
                    com.kilon.agentvscreen.host.Audit.security("revoke", n); nav.back()
                }
            }.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (sure) tr("再点一次确认", "Tap again to confirm") else tr("忘掉这个 agent", "Forget this agent"), fontSize = 15.sp, color = MiuixTheme.colorScheme.error)
                Text(tr("它的令牌作废，连不上了；做过的任务还留在记录里", "Its token stops working; its past tasks stay in the records"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

/**
 * 1.4.0-beta11 (rainbow: with several agents, can the user manage them quickly?). What this agent has going on the
 * phone now, each with the tap that ends it: its bookings, its watches, what it waits on from the user, and its records.
 */
@Composable
private fun AgentNow(id: String) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    fun mine(session: String?) = com.kilon.agentvscreen.api.Agents.ofSession(session) == id
    var tick by remember { mutableIntStateOf(0) }
    var books by remember { mutableStateOf(emptyList<com.kilon.agentvscreen.host.Wake.Entry>()) }
    LaunchedEffect(tick) { while (true) { books = withContext(Dispatchers.IO) { runCatching { com.kilon.agentvscreen.host.Wake.list(ctx).filter { it.agentId == id } }.getOrDefault(emptyList()) }; delay(3_000) } }
    val watches = remember(tick, books) { com.kilon.agentvscreen.core.VScreenRules.list().filter { !it.stopped && it.spec.agentId == id } }
    val open by com.kilon.agentvscreen.host.UserAsk.open.collectAsState()
    val given by com.kilon.agentvscreen.core.VScreenOwnership.given.collectAsState()
    val waits = open.count { mine(it.session) } + given.values.count { mine(it.session) }
    val s by BackgroundOperation.state.collectAsState()
    val using = mine(s.ownerSessionId) && s.displayId != null
    Group(tr("它现在", "Now")) {
        if (using) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(Palette.brand); Text(tr("  正在用副屏", "  Using the screen now"), fontSize = 15.sp)
        }
        if (waits > 0) ArrowPreference(title = tr("等你处理 $waits 件", "$waits waiting for you"), summary = tr("在首页最上面", "At the top of the home page"), onClick = { nav.tab(0) })
        books.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("预约「${e.name}」", "Booking \"${e.name}\""), fontSize = 15.sp)
                    Text(e.spec.describe(), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Text(tr("取消", "Cancel"), color = MiuixTheme.colorScheme.error, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { Thread { com.kilon.agentvscreen.host.Wake.remove(ctx.applicationContext, e.name, id); tick++ }.start() }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        watches.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("盯守「${l.spec.name}」", "Watch \"${l.spec.name}\""), fontSize = 15.sp)
                    Text(l.status, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
                }
                Text(tr("停止", "Stop"), color = MiuixTheme.colorScheme.error, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { com.kilon.agentvscreen.core.VScreenRules.stop(l.spec.agentId, l.spec.name, tr("用户在 App 里停止", "stopped by the user in the app")); tick++ }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        if (!using && waits == 0 && books.isEmpty() && watches.isEmpty())
            Text(tr("没有在用副屏，也没有预约或盯守", "Not using the screen; no bookings or watches"), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
        ArrowPreference(title = tr("它的记录", "Its records"), onClick = { RecordsWho.value = id; nav.tab(TAB_RECORDS) })
    }
}

/** Code in a dark monospace box with its own copy button. */
@Composable
fun Code(text: String, onCopy: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF16131F))) {
        SelectionContainer {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp, color = Color(0xFFE8E4F2), softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 52.dp))
        }
        IconButton(onClick = onCopy, modifier = Modifier.align(Alignment.TopEnd)) { Icon(MiuixIcons.Copy, tr("复制", "Copy"), tint = Color(0xFFE8E4F2)) }
    }
}

/** A tinted note inside a group: a state in a word plus what it means. */
@Composable
fun InfoCard(color: Color, title: String, body: String = "") {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.12f).compositeOver(MiuixTheme.colorScheme.surfaceContainer)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) { Dot(color); Text("  $title", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        if (body.isNotEmpty()) SelectionContainer { Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
    }
}

/** One action under a card: a full-width button, primary or plain. */
@Composable
private fun CardAction(text: String, primary: Boolean = true, onClick: () -> Unit) =
    PrimaryButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = if (primary) MiuixTheme.colorScheme.primary else null) {
        Text(text, color = if (primary) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface)
    }

/**
 * 1.4.0-beta8 (rainbow: rework the remember part). What the app knows about an agent is three facts in order, each
 * seen, never guessed: it connected (installed or first call), it said where it saved the note (`vscreen remember`),
 * and it came back on its own in a later conversation (a call after a long silence). The card shows the three as
 * steps; what the current step means and what to do sits in the agent's detail (tap), not under every card.
 */
internal enum class Kept { NONE, CONNECTED, SAVED, CAME_BACK }

internal fun keptOf(l: AgentLedger.Snapshot): Kept = when {
    l.cameBack -> Kept.CAME_BACK
    l.remembered -> Kept.SAVED
    l.used -> Kept.CONNECTED
    else -> Kept.NONE
}

private val agentFmt get() = java.text.SimpleDateFormat(tr("M月d日 HH:mm", "MMM d HH:mm"), if (I18n.en) java.util.Locale.US else java.util.Locale.CHINA)

/** What the agent's current step means, and the one thing to do about it (the detail dialog). */
internal fun keptText(id: String, l: AgentLedger.Snapshot): String {
    fun at(t: Long) = agentFmt.format(java.util.Date(t))
    val main = when (keptOf(l)) {
        Kept.CAME_BACK -> tr("它记下了说明，而且隔了半小时以上又来用了 ${l.comebacks} 次（最近 ${at(l.lastComebackAt)}），说明新对话里也想得起来。", "It saved the note and came back after a break of half an hour or more ${l.comebacks} times (last ${at(l.lastComebackAt)}): new conversations remember it.") +
            (l.rememberedWhere?.let { tr("存在「$it」。", " Saved in \"$it\".") } ?: "")
        Kept.SAVED -> tr("它说把说明存在了「${l.rememberedWhere}」（${at(l.rememberedAt)}），新对话里应该也知道有这个能力。", "It says it saved the note in \"${l.rememberedWhere}\" (${at(l.rememberedAt)}), so new conversations should know about it.")
        Kept.CONNECTED -> tr("${at(l.installAt)} 连上了，但它没说把说明存在哪。新对话里它可能不知道有这个能力：再发一次给它的话，或把能力说明放进它的自定义指令。", "Connected ${at(l.installAt)}, but it has not said where it saved the note. New conversations may not know about it: send its message again, or put the ability note in its custom instructions.")
        Kept.NONE -> tr("还没连上过。把给它的话发给它。", "Not connected yet. Send it its message.")
    }
    val ack = AgentLedger.ackVersion(id)
    val ver = if (!l.used || ack >= BuildConfig.VERSION_CODE) "" else "\n\n" + (
        if (ack <= 0) tr("不知道它记下的说明是哪个版本的；它下次用时会收到更新提醒。", "Not known which version its note is for; its next use brings an update notice.")
        else tr("它记下的说明是 ${com.kilon.agentvscreen.api.AgentNews.nameOf(ack)} 的；下次用时会收到这一版的变化。", "Its note is from ${com.kilon.agentvscreen.api.AgentNews.nameOf(ack)}; its next use brings this version's changes."))
    return main + ver
}

@Composable
private fun AgentCard(id: String, l: AgentLedger.Snapshot, edit: () -> Unit) {
    fun at(t: Long) = agentFmt.format(java.util.Date(t))
    val way = when {
        l.lastCallAt == 0L -> tr("还没连上过", "Not connected yet")
        l.lastRemoteAt > l.lastLocalAt -> tr("其它设备 · 最近 ${at(l.lastCallAt)}", "another device · last ${at(l.lastCallAt)}")
        else -> tr("这台手机 · 最近 ${at(l.lastCallAt)}", "this phone · last ${at(l.lastCallAt)}")
    }
    val k = keptOf(l)
    Column(Modifier.fillMaxWidth().clickable(onClick = edit).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(com.kilon.agentvscreen.api.Agents.name(id), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(way, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // 1.4.0-beta10 (rainbow: "自己再用" meant nothing and never lit): two facts the user can check, connected and
            // the note saved. Whether it later came back on its own is said in words on the agent's page, with when.
            listOf(Kept.CONNECTED to tr("已连上", "Connected"), Kept.SAVED to tr("已记下说明", "Note saved")).forEach { (step, label) ->
                val done = k.ordinal >= step.ordinal
                val c = if (done) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
                Row(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = if (done) 0.14f else 0.06f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (done) "✓ " else "○ ", fontSize = 12.sp, color = c)
                    Text(label, fontSize = 12.sp, color = c)
                }
            }
        }
    }
}

/** 远程连接: off / built-in Tailscale / the Tailscale app on this phone, and what state it is in. */
@Composable
fun RemotePage() {
    val ctx = LocalContext.current
    val host = LocalHost.current
    val ts by Tailnet.status.collectAsState()
    var mode by remember { mutableStateOf(ApiConfig.tailnetMode) }
    var advanced by remember { mutableStateOf(false) }
    val remoteBase = rememberRemoteBase(mode)
    val M = Tailnet.Mode.entries
    fun open(url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    MiuixPage(tr("远程连接", "Remote access"), subtitle = tr("只限你自己的 Tailscale 网络", "Only from your own Tailscale network")) {
        Group(tr("方式", "Mode")) {
            listOf(
                tr("关", "Off") to tr("只有这台手机上的 agent 能连", "Only agents on this phone can connect"),
                tr("内置 Tailscale", "Built-in Tailscale") to tr("不用装 Tailscale App", "No Tailscale app needed"),
                tr("外部 Tailscale App", "External Tailscale app") to tr("用这台手机上已经装好的 Tailscale", "Uses the Tailscale app already on this phone"),
            ).forEachIndexed { i, (t, sub) ->
                RadioButtonPreference(title = t, summary = sub, selected = mode == M[i], onClick = {
                    if (mode != M[i]) {
                        mode = M[i]; ApiConfig.tailnetMode = M[i]
                        HostService.server?.start(ApiConfig.port)
                        Tailnet.sync(ctx.applicationContext)
                    }
                })
            }
        }
        when (mode) {
            Tailnet.Mode.BUILTIN -> Group(tr("状态", "State")) { BuiltinState(ts, host, ::open) { m -> mode = m; ApiConfig.tailnetMode = m; HostService.server?.start(ApiConfig.port); Tailnet.sync(ctx.applicationContext) } }
            Tailnet.Mode.SYSTEM -> Group(tr("状态", "State")) {
                if (remoteBase == null) InfoCard(Palette.stopped, tr("还没找到 Tailscale 地址", "No Tailscale address yet"),
                    tr("在这台手机上装好并登录 Tailscale App，打开连接后 20 秒内自动生效。不想装 App 就选“内置”。", "Install and log in to the Tailscale app on this phone; once it is connected this takes effect within 20 seconds. To skip the app, choose \"Built-in\"."))
                else InfoCard(Palette.takeover, tr("已连上 Tailscale App", "Connected through the Tailscale app"), tr("地址", "Address") + " $remoteBase")
            }
            else -> {}
        }
        if (mode == Tailnet.Mode.BUILTIN && Tailnet.available(ctx)) {
            Group {
                ArrowPreference(title = tr("高级选项", "Advanced"), summary = tr("自建服务器（Headscale）、auth key", "Own server (Headscale), auth key"), onClick = { advanced = true })
            }
        }
        if (mode != Tailnet.Mode.OFF) Group(tr("安全", "Safety"), padded = true) { RemoteSafety() }
    }

    OverlayBottomSheet(show = advanced, title = tr("高级选项", "Advanced"), onDismissRequest = { advanced = false }) {
        var key by remember { mutableStateOf("") }
        var ctl by remember { mutableStateOf(ApiConfig.tailnetControlUrl ?: "") }
        val problem = ApiConfig.controlUrlProblem(ctl)
        Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = ctl, onValueChange = { ctl = it }, label = tr("控制服务器（留空用 Tailscale）", "Control server (blank = Tailscale)"), useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(problem ?: ApiConfig.normalizeControlUrl(ctl)?.let { tr("将使用 $it", "Will use $it") } ?: tr("使用 Tailscale 官方服务器", "Using Tailscale's own server"),
                fontSize = 12.sp, color = if (problem != null) Palette.stopped else MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 12.dp))
            TextField(value = key, onValueChange = { key = it }, label = tr("auth key（可选，代替网页登录）", "Auth key (optional, instead of the web login)"), useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(tr("每个服务器的登录分开保存，换回原来的服务器不用重新登录。内置节点不接管手机上网，所以没有出口节点这一项。", "Each server keeps its own login, so switching back needs no new login. The built-in node does not carry the phone's own traffic, so there is no exit node option."),
                fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 12.dp))
            PrimaryButton(onClick = {
                if (problem != null) { host.say(problem); return@PrimaryButton }
                val k = key.trim(); val c = ctl
                Thread { Tailnet.configure(ctx.applicationContext, c, k) }.start()
                advanced = false
                host.say(tr("已保存，内置 Tailscale 重新连接中", "Saved, built-in Tailscale is reconnecting"))
            }, modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.primary) { Text(tr("保存并重连", "Save and reconnect"), color = MiuixTheme.colorScheme.onPrimary) }
        }
    }
}

/** 1.4.0-beta10: another VPN is on: what it means and the three ways out, the easiest first. */
@Composable
private fun VpnGuide(host: AppHost, switchTo: (Tailnet.Mode) -> Unit) {
    val ctx = LocalContext.current
    val hasApp = remember { runCatching { ctx.packageManager.getPackageInfo("com.tailscale.ipn", 0); true }.getOrDefault(false) }
    var how by remember { mutableStateOf(false) }
    InfoCard(Palette.standby, tr("手机开着另一个 VPN", "Another VPN is on"),
        tr("手机上的 VPN 可能影响内置 Tailscale 的连接。如果一直连不上，可以尝试以下办法：",
            "A VPN on this phone may affect the built-in Tailscale connection. If it keeps failing, try one of these options:"))
    if (hasApp) ArrowPreference(title = tr("① 改用手机上的 Tailscale App", "① Use the Tailscale app on this phone"),
        summary = tr("你已经装了它。打开它并连上，这里切到“外部 Tailscale App”", "It is installed. Open it and connect; this switches to \"External Tailscale app\""),
        endActions = { Value(tr("切换", "Switch")) }, onClick = { switchTo(Tailnet.Mode.SYSTEM) })
    ArrowPreference(title = tr("${if (hasApp) "②" else "①"} 让 VPN 放过本应用", "${if (hasApp) "②" else "①"} Let the VPN skip this app"),
        summary = tr("在你的 VPN / 代理 App 里把 Agent-VScreen 设为不走 VPN", "In your VPN / proxy app, set Agent-VScreen to bypass it"),
        endActions = { Value(if (how) tr("收起", "Hide") else tr("怎么做", "How")) }, onClick = { how = !how })
    if (how) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(tr("打开你的 VPN 或代理 App", "Open your VPN or proxy app"),
            tr("找“分应用代理”“应用分流”或“绕过的应用”", "Find \"per-app proxy\", \"app routing\" or \"bypassed apps\""),
            tr("把 Agent-VScreen 设为直连 / 不代理", "Set Agent-VScreen to direct / not proxied"),
            tr("回到这里，几秒内会自己连上", "Come back here; it connects by itself within seconds")).forEachIndexed { i, t ->
            Text("${i + 1}. $t", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Text(tr("复制包名", "Copy package name"), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { host.copy(tr("包名", "Package name"), ctx.packageName) }.padding(vertical = 4.dp))
    }
    BasicComponent(title = tr("${if (hasApp) "③" else "②"} 暂时关掉那个 VPN", "${if (hasApp) "③" else "②"} Turn that VPN off for now"),
        summary = tr("关掉后这里自己连上；之后再开，可能又会断", "It connects by itself; turning the VPN on again may break it again"))
}

/** The built-in node: one phase decides the card (Tailnet.phase), the same word the settings row and agents see. */
@Composable
private fun BuiltinState(ts: Tailnet.Status, host: AppHost, open: (String) -> Unit, switchTo: (Tailnet.Mode) -> Unit) {
    val ctx = LocalContext.current
    if (!Tailnet.available(ctx)) { InfoCard(Palette.stopped, tr("这个安装包里没有内置 Tailscale", "This package has no built-in Tailscale"), tr("只支持 arm64 手机。", "Only arm64 phones are supported.")); return }
    val phase = Tailnet.phase(ctx)
    // VPN troubleshooting is relevant only after a failed or stalled connection.
    var vpnOn by remember { mutableStateOf(Tailnet.throughVpn(ctx)) }
    LaunchedEffect(Unit) { while (true) { vpnOn = Tailnet.throughVpn(ctx); delay(2_000) } }
    val loginStalled = (phase == TsPhase.NEEDS_LOGIN || phase == TsPhase.EXPIRED) &&
        ts.authUrl.isNullOrBlank() && (ts.waitingS >= 15 || !ts.loginError.isNullOrBlank())
    if (vpnOn && (loginStalled || phase == TsPhase.UNSTABLE || phase == TsPhase.ERROR)) {
        VpnGuide(host, switchTo)
        var more by remember { mutableStateOf(false) }
        ArrowPreference(title = tr("详情", "Details"), endActions = { Value(if (more) tr("收起", "Hide") else tr("查看", "Show")) }, onClick = { more = !more })
        if (!more) return
    }
    val known = remember(ts.loggedIn, phase) { Tailnet.known(ctx) }
    var wasOut by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase == TsPhase.NEEDS_LOGIN || phase == TsPhase.EXPIRED || phase == TsPhase.NEEDS_APPROVAL) wasOut = true
        if (phase == TsPhase.CONNECTED && wasOut) { wasOut = false; host.say(tr("已连上 Tailscale", "Connected to Tailscale")) }
    }
    when (phase) {
        TsPhase.SERVICE_OFF -> {
            InfoCard(Palette.idle, if (ServiceControl.now(ctx) == ServiceControl.State.BLOCKED) tr("服务已关闭，内置 Tailscale 没在运行", "The service is off; the built-in Tailscale is not running")
                else tr("服务收起了，内置 Tailscale 只在服务醒着时在线", "The service is hidden; the built-in Tailscale is online only while it is awake"),
                (known?.let { tr("登录还在（${it.dnsName ?: it.ip}），醒来后自动连上。", "The login is kept (${it.dnsName ?: it.ip}); it reconnects when awake.") } ?: tr("打开服务后再登录。", "Turn the service on, then log in.")))
            CardAction(tr("打开常驻", "Keep it always on")) { ServiceControl.turnOn(ctx, "tailnet card") }
        }
        TsPhase.CONNECTED -> {
            InfoCard(Palette.takeover, tr("已连上 Tailscale", "Connected to Tailscale"),
                tr("名字", "Name") + " ${ts.dnsName ?: ApiConfig.tailnetHostname}\n" + tr("地址", "Address") + " ${ts.ip}" +
                    (ts.user?.let { "\n" + tr("账号", "Account") + " $it" } ?: "") +
                    (if (ts.ownServer) "\n" + tr("服务器", "Server") + " ${ts.serverName}" else "") +
                    "\n" + tr("在线设备 ${ts.online} 台", "${ts.online} devices online") +
                    (ts.relay?.let { "\n" + tr("中继", "Relay") + " $it" } ?: "") +
                    (if (ts.flaps >= 2) "\n" + tr("10 分钟内断过 ${ts.flaps} 次", "Dropped ${ts.flaps} times in 10 minutes") else "") +
                    (ts.lastPeer?.let { "\n" + tr("最近来访", "Last visitor") + " $it" } ?: ""))
            val days = if (ts.keyExpiry > 0) ((ts.keyExpiry - System.currentTimeMillis()) / 86_400_000L).toInt() else -1
            if (days in 0..14) Footnote(tr("登录 $days 天后过期，到时要重新登录。可以在 Tailscale 控制台给这台设备关掉“密钥过期”。", "The login expires in $days days; then it needs logging in again. You can turn off key expiry for this device in the Tailscale console."))
            CardAction(tr("退出 Tailscale 登录", "Log out of Tailscale"), primary = false) { Tailnet.logout(ctx) }
        }
        TsPhase.NEEDS_APPROVAL -> InfoCard(Palette.standby, tr("等待批准这台设备", "Waiting for this device to be approved"),
            tr("登录已经完成，你的网络要求新设备先批准。在 ${if (ts.ownServer) ts.serverName + " 的管理端" else "Tailscale 控制台（login.tailscale.com/admin/machines）"}里批准“${ApiConfig.tailnetHostname}”，批准后这里自动连上。",
                "The login is done; your network requires new devices to be approved. Approve \"${ApiConfig.tailnetHostname}\" in ${if (ts.ownServer) "the admin of " + ts.serverName else "the Tailscale console (login.tailscale.com/admin/machines)"}; this connects by itself afterwards."))
        TsPhase.NEEDS_LOGIN, TsPhase.EXPIRED -> if (ts.authUrl != null) {
            InfoCard(Palette.standby,
                if (phase == TsPhase.EXPIRED) tr("Tailscale 登录过期了，重新登录一次", "The Tailscale login ran out: log in once more") else tr("登录 Tailscale（只要一次）", "Log in to Tailscale (once)"),
                if (ts.ownServer) tr("打开 ${ts.serverName} 的注册页。Headscale 通常会给出一条注册命令，在服务器上运行它就连上了；也可以在高级选项里填预授权 key。", "Open the registration page of ${ts.serverName}. Headscale usually shows a register command: run it on the server and the node joins. Or put a pre-auth key in Advanced.")
                else tr("用你电脑上 Tailscale 的同一个账号登录，登录完回到这里会自动连上。手机上不方便登录的话，复制链接到电脑上打开。", "Log in with the same account as Tailscale on your computer; back here it connects by itself. If logging in on the phone is awkward, copy the link and open it on the computer."))
            CardAction(if (ts.ownServer) tr("打开注册页", "Open the registration page") else tr("在浏览器里登录", "Log in in the browser")) { open(ts.authUrl) }
            CardAction(tr("复制登录链接", "Copy login link"), primary = false) { host.copy(tr("登录链接", "Login link"), ts.authUrl) }
        } else {
            val slow = ts.waitingS >= 15 || ts.loginError != null
            InfoCard(if (slow) Palette.stopped else Palette.standby,
                if (ts.loginError != null) tr("连不上控制服务器 ${ts.serverName}", "Cannot reach the control server ${ts.serverName}")
                else if (slow) tr("拿不到登录链接（已等 ${ts.waitingS} 秒）", "No login link yet (waited ${ts.waitingS} s)")
                else tr("正在向 ${ts.serverName} 要登录链接…", "Asking ${ts.serverName} for a login link…"),
                if (!slow) tr("通常几秒内出现。", "It usually appears within seconds.")
                else (ts.loginError?.let { tr("出错原因：", "Error: ") + it.take(240) + "\n" } ?: "") +
                    (if (ts.ownServer) tr("请确认地址和端口跟你在 Tailscale App 里填的“登录服务器”完全一样，并且这台手机现在能打开它（浏览器打开 ${ts.control}/key?v=1 应该能看到一串字）。", "Check that the address and port are exactly your login server in the Tailscale app, and that this phone can open it now (${ts.control}/key?v=1 should show some text in a browser).")
                    else tr("这台手机连不上 Tailscale 的服务器（controlplane.tailscale.com）时会这样：换个网络或打开能访问它的代理后会自动出现。也可以在高级选项里填 auth key。", "This happens when the phone cannot reach Tailscale's server (controlplane.tailscale.com): switch networks or turn on a proxy that reaches it. Or put an auth key in Advanced.")) +
                    (if (ts.loginError == null) ts.health?.let { "\n" + tr("Tailscale 报告：", "Tailscale says: ") + it.take(240) } ?: "" else ""))
            CardAction(tr("重新获取登录链接", "Get a login link again"), primary = false) { Tailnet.login() }
        }
        TsPhase.UNSTABLE -> InfoCard(Palette.standby, tr("Tailscale 断开了，正在重连", "Tailscale dropped, reconnecting"),
            tr("登录还在（${ts.dnsName ?: ts.ip}），但手机现在连不上", "Still logged in (${ts.dnsName ?: ts.ip}), but the phone cannot reach ") +
                (if (!ts.controlOk) tr(" Tailscale 服务器", "the Tailscale server") else tr("中继${ts.relay?.let { " $it" } ?: ""}", "the relay${ts.relay?.let { " $it" } ?: ""}")) +
                tr("，其它设备暂时连不上它（已 ${ts.downS} 秒）。", ", so other devices cannot reach it for now (${ts.downS} s).") +
                (if (ts.flaps >= 2) tr("10 分钟内断了 ${ts.flaps} 次。", " It dropped ${ts.flaps} times in 10 minutes.") else ""))
        TsPhase.ERROR -> InfoCard(Palette.stopped, tr("内置 Tailscale 出错，会自动重试", "Built-in Tailscale failed, retrying automatically"), ts.error ?: ts.state)
        else -> {
            val waited = Tailnet.waitedMs()
            InfoCard(Palette.standby, tr("正在连接 Tailscale…", "Connecting to Tailscale…") + if (waited > Tailnet.SLOW_MS) tr("（已 ${waited / 1000} 秒）", " (${waited / 1000} s)") else "",
                (known?.let { tr("用上次的登录（${it.dnsName ?: it.ip}），不用重新登录。", "Using the saved login (${it.dnsName ?: it.ip}); no new login needed.") } ?: tr("通常几秒钟。", "Usually a few seconds.")) +
                    (if (waited > Tailnet.SLOW_MS) "\n" + tr("还没连上 ${if (ts.loggedIn) "中继" else ts.serverName}", "Not reached ${if (ts.loggedIn) "the relay" else ts.serverName} yet") +
                        ((ts.loginError ?: ts.health)?.let { "：${it.take(160)}" } ?: "") else ""))
        }
    }
    val appVpn = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { appVpn.value = withContext(Dispatchers.IO) { com.kilon.agentvscreen.api.ApiServer.tailnetAddress()?.hostAddress }; delay(5_000) } }
    if (appVpn.value != null) Footnote(tr("这台手机上的 Tailscale App 已经连着（${appVpn.value}）。手机要靠它的出口节点上网的话，App 得一直开着，这时直接选“外部 Tailscale App”更省事。",
        "The Tailscale app on this phone is already connected (${appVpn.value}). If the phone needs its exit node to get online, the app has to stay on anyway, so choosing \"External Tailscale app\" is simpler."))
}
