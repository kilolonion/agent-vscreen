package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
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
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
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
    val pend by com.kilon.agentvscreen.host.StrongConfirm.pending.collectAsState()
    val pairings = remember(pend) { pend.filter { it.pairingCode != null } }
    // 2.0.0-beta9 (feedback 6): a lazy list; only the agents on screen are composed.
    // 2.0.0 (rainbow: 过长列表自动折叠): most recently active first, so the folded ones are the agents not used lately.
    val shown = remember(tokens, agents) { tokens.filter { t -> agents[t.id]?.used == true || t.id != com.kilon.agentvscreen.api.Tokens.MAIN }
        .sortedByDescending { t -> maxOf(t.lastUsedAt, t.createdAt, agents[t.id]?.lastCallAt ?: 0L) } }
    val fold = rememberFold(4)
    MiuixLazyPage(tr(S.app_connect), back = !tab, bottom = bottom) {
        // 2.0.0-beta11 (rainbow: left the wizard while the agent was still connecting; nothing showed here). A cloud agent
        // exists only once its pairing is approved, so a pairing still waiting is listed here, above the connected ones.
        if (pairings.isNotEmpty()) item(key = "pairing") {
            Group(tr(S.connect_pending_approval, pairings.size)) {
                pairings.forEach { r ->
                    ArrowPreference(title = r.publicClientName ?: r.tokenName,
                        summary = tr(S.connect_cloud_public_mcp_pairing_code, r.pairingCode),
                        onClick = { StrongConfirmActivity.open(ctx, r.id) })
                }
            }
        }
        if (shown.isEmpty()) item(key = "empty") { Column(Modifier.padding(horizontal = 12.dp)) { EmptyState(androidx.compose.material.icons.Icons.Outlined.AddLink, tr(S.connect_no_agent_connected_yet),
            tr(S.connect_once_connected_each_agent_s)) } }
        else cardItems(tr(S.connect_connected, shown.size), shown, key = { it.id }, fold = fold) { t ->
            AgentCard(t.id, agents[t.id] ?: AgentLedger.Snapshot()) { nav.go(Route.Agent(t.id)) }
        }
        item(key = "rest") { Column {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            PrimaryButton(onClick = { nav.go(Route.NewAgent) }, modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.primary) {
                Text(tr(S.connect_connect_a_new_agent), color = MiuixTheme.colorScheme.onPrimary)
            }
        }
        Group(tr(S.connect_connection_methods)) {
            val localStatus = when (svcState) {
                ServiceControl.State.ON -> tr(S.connect_ready)
                ServiceControl.State.HIDDEN -> tr(S.connect_on_demand)
                ServiceControl.State.BLOCKED -> tr(S.automation_off)
            }
            BasicComponent(
                title = tr(S.connect_on_this_phone),
                summary = "127.0.0.1:${ApiConfig.port}",
                endActions = {
                    Value(localStatus, if (svcState == ServiceControl.State.BLOCKED) Palette.stopped else Palette.takeover)
                },
            )
            ArrowPreference(
                title = tr(S.connect_other_devices_tailscale),
                summary = remember(ts) { Tailnet.line(ctx) },
                onClick = { nav.go(Route.Remote) },
            )
            val pubChannel by com.kilon.agentvscreen.host.PublicAccess.channelFlow.collectAsState()
            val tunnel = pubChannel.tunnel
            val tunnelState = tunnel?.status?.collectAsState()?.value?.state
            val cf = tunnel != null
            val pubStatus = when {
                !pubOn -> tr(S.connect_off)
                cf && tunnelState == com.kilon.agentvscreen.host.TunnelState.CONNECTED -> tr(S.connect_on)
                cf && tunnelState == com.kilon.agentvscreen.host.TunnelState.ERROR -> tr(S.connect_failed)
                cf -> tr(S.connect_starting)
                ts.funnel == "on" -> tr(S.connect_on)
                ts.funnel == "cert" -> tr(S.connect_cert)
                ts.funnel == "error" -> tr(S.connect_failed_2)
                else -> tr(S.connect_starting)
            }
            ArrowPreference(
                title = tr(S.connect_cloud_public_mcp),
                summary = if (pubOn) com.kilon.agentvscreen.host.PublicAccess.mcpUrl() ?: tr(S.connect_via, pubChannel.title)
                    else tr(S.connect_for_cloud_mcp_clients_tailscale),
                endActions = {
                    Value(
                        pubStatus,
                        when {
                            pubOn && cf && tunnelState == com.kilon.agentvscreen.host.TunnelState.CONNECTED -> Palette.takeover
                            pubOn && cf && tunnelState == com.kilon.agentvscreen.host.TunnelState.ERROR -> Palette.stopped
                            pubOn && !cf && ts.funnel == "on" -> Palette.takeover
                            pubOn && !cf && ts.funnel == "error" -> Palette.stopped
                            pubOn -> Palette.standby
                            else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                        },
                    )
                },
                onClick = { nav.go(Route.PublicAccess) },
            )
        }
        Group {
            ArrowPreference(title = tr(S.connect_tokens), onClick = { nav.go(Route.Tokens) })
        }
        } }
    }
}

internal fun levelLabel(level: String): String = com.kilon.agentvscreen.api.Policy.levelTitle(level)
internal fun levelHint(level: String): String = com.kilon.agentvscreen.api.Policy.levelHint(level)

/**
 * 2.0.0-beta8 (rainbow 00:52): an agent's table, one row per class of action, three choices per row. Rows changed
 * from the level's preset carry a small mark. [onChange] decides whether a change needs a fingerprint.
 */
@Composable
internal fun PolicyForm(table: Map<String, com.kilon.agentvscreen.api.Policy.Act>, preset: Map<String, com.kilon.agentvscreen.api.Policy.Act>?,
                        publicOnly: Boolean, onChange: (String, com.kilon.agentvscreen.api.Policy.Act) -> Unit) {
    val c = MiuixTheme.colorScheme
    com.kilon.agentvscreen.api.Policy.ROWS.forEach { row ->
        if (publicOnly && row.key == com.kilon.agentvscreen.api.Policy.SYSTEM) return@forEach
        val cur = table[row.key] ?: com.kilon.agentvscreen.api.Policy.Act.ASK
        val changed = preset != null && preset[row.key] != cur
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (changed) Text(tr(S.connect_changed), fontSize = 11.sp, color = c.primary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.primary.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Text(row.hint, fontSize = 12.sp, lineHeight = 17.sp, color = c.onSurfaceVariantSummary)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceContainerHigh).padding(3.dp)) {
                listOf(com.kilon.agentvscreen.api.Policy.Act.ALLOW, com.kilon.agentvscreen.api.Policy.Act.ASK, com.kilon.agentvscreen.api.Policy.Act.DENY).forEach { a ->
                    val offered = a in row.choices
                    val on = a == cur
                    val tint = when (a) { com.kilon.agentvscreen.api.Policy.Act.ALLOW -> Palette.takeover; com.kilon.agentvscreen.api.Policy.Act.ASK -> c.primary; else -> Palette.stopped }
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) tint.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable(enabled = offered && !on) { onChange(row.key, a) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(a.title, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = when { on -> tint; offered -> c.onSurface; else -> c.onSurfaceVariantSummary.copy(alpha = 0.4f) })
                    }
                }
            }
        }
    }
}

/**
 * 2.0.0-beta8: connecting one agent in 4 steps: name + level, where (local / Tailscale / cloud), how (or the cloud
 * checklist), then what to send it. Feedback 5: the agent's token is made exactly once, by the user's confirmation
 * when leaving step 3 (an action, never during composition), and the suggested name is fixed when the page opens.
 */
@Composable
fun NewAgentPage() {
    val host = LocalHost.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val actx = ctx.applicationContext
    var where by rememberSaveable { mutableIntStateOf(com.kilon.agentvscreen.host.Access.lastWay()) }
    var how by rememberSaveable { mutableIntStateOf(0) }
    var raw by remember { mutableStateOf(false) }
    var level by rememberSaveable { mutableStateOf(com.kilon.agentvscreen.api.Tokens.FREE) }
    // Rows changed on top of the level (kept as JSON so it survives rotation).
    var customJson by rememberSaveable { mutableStateOf("{}") }
    val custom = remember(customJson) { com.kilon.agentvscreen.api.Policy.fromJson(runCatching { org.json.JSONObject(customJson) }.getOrNull()) }
    var showTable by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    // Never save credentials/onboarding codes into savedInstanceState Bundle.
    var made by remember { mutableStateOf<String?>(null) }
    var madeIsCode by remember { mutableStateOf(false) }
    var madeId by rememberSaveable { mutableStateOf<String?>(null) }
    // Token lifetime: permanent (default), 30 / 7 / 1 days, or (shell / HTTP only) a 10-minute one-time code.
    var lifetime by rememberSaveable { mutableIntStateOf(0) }
    val oneTime = how != 1 && lifetime == ONE_TIME_CHOICE
    val staticToken = !oneTime
    fun expiryNow(): Long = com.kilon.agentvscreen.api.Tokens.LIFETIMES_MS.getOrNull(lifetime)?.takeIf { it > 0L }?.let { System.currentTimeMillis() + it } ?: 0L
    // Fixed once: a name computed from the live token list moved every time a token was made (feedback 5).
    val suggested = rememberSaveable {
        val used = com.kilon.agentvscreen.api.Tokens.list.value.map { it.name }.toSet()
        val base = tr(S.connect_default_agent)
        "$base ${generateSequence(1) { it + 1 }.first { "$base $it" !in used }}"
    }
    val want = name.trim().ifEmpty { suggested }

    val ts by Tailnet.status.collectAsState()
    val pubEnabled by com.kilon.agentvscreen.host.PublicAccess.enabledFlow.collectAsState()
    var mode by remember { mutableStateOf(ApiConfig.tailnetMode) }
    LaunchedEffect(Unit) { while (true) { mode = ApiConfig.tailnetMode; delay(1_000) } }
    val remoteBase = rememberRemoteBase(mode)
    val remoteReady = where == 1 && remoteBase != null
    val pubChannel by com.kilon.agentvscreen.host.PublicAccess.channelFlow.collectAsState()
    val cfCfg by com.kilon.agentvscreen.host.Cloudflared.config.collectAsState()
    val ngCfg by com.kilon.agentvscreen.host.Ngrok.config.collectAsState()
    val cloudUrl = remember(ts.dnsName, pubChannel, cfCfg, ngCfg) { com.kilon.agentvscreen.host.PublicAccess.mcpUrl() }
    val base = if (where == 1 && remoteBase != null) remoteBase else ApiConfig.baseUrl
    val canCopyLocal = where == 0 || remoteReady
    val canAdvanceWhere = where == 0 || remoteReady || where == 2
    val canAdvanceHow = if (where == 2) (pubEnabled && !cloudUrl.isNullOrBlank()) else canCopyLocal

    /** When this wizard opened: a cloud agent that pairs or calls after this moment is the one being connected. */
    val openedAt = rememberSaveable { System.currentTimeMillis() }
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = listOf(tr(S.connect_name), tr(S.connect_location), tr(S.connect_method), tr(S.ask_activity_send))

    /** Leaving step 3 for a local / tailnet agent: make (or update) its onboarding code or static MCP token, confirmed once with a fingerprint. */
    fun confirmAgent() {
        val T = com.kilon.agentvscreen.api.Tokens
        val OC = com.kilon.agentvscreen.api.OnboardingCodes
        val existing = madeId?.let { T.byId(it) }
        fun ensureCredentialForExisting(tok: com.kilon.agentvscreen.api.Tokens.Token) {
            if (staticToken) {
                if (tok.isPending) {
                    val activated = T.activateWithSecret(tok.id)
                    if (activated != null) {
                        OC.revokeForToken(tok.id)
                        made = activated.second
                        madeIsCode = false
                    }
                }
            } else if (tok.isPending && (made == null || !madeIsCode || !OC.hasActive(tok.id))) {
                made = OC.issue(tok.id)
                madeIsCode = true
            }
        }
        if (existing != null && (made != null || existing.isPending)) {
            if (existing.name != want) T.rename(existing.id, want)
            val next = com.kilon.agentvscreen.api.Policy.table(level, custom)
            if (com.kilon.agentvscreen.api.Policy.widened(existing.policy, next).isEmpty()) {
                T.setLevel(existing.id, level, custom)
                ensureCredentialForExisting(T.byId(existing.id) ?: existing)
                step = 3
                return
            }
            val req = com.kilon.agentvscreen.host.StrongConfirm.createUiMultiRiskRequest(actx, listOf(com.kilon.agentvscreen.host.Risk.LEVEL_UP), existing,
                tr(S.connect_raise_to, want, levelLabel(level)), levelHint(level),
                onApprove = {
                    T.setLevel(existing.id, level, custom)
                    com.kilon.agentvscreen.host.Audit.security("level_up", "$want ${existing.level}->$level")
                    ensureCredentialForExisting(T.byId(existing.id) ?: existing)
                    step = 3
                })
            StrongConfirmActivity.open(actx, req.id)
            return
        }
        val draft = com.kilon.agentvscreen.api.Tokens.Token("new", want, "", T.scopeOfLevel(level), System.currentTimeMillis(), level = level)
        val req = com.kilon.agentvscreen.host.StrongConfirm.createUiMultiRiskRequest(actx, listOf(com.kilon.agentvscreen.host.Risk.NEW_CLIENT), draft,
            tr(S.connect_connect, want, levelLabel(level)), levelHint(level),
            onApprove = {
                if (staticToken) {
                    val (t, text) = T.createLevel(want, level, custom, expiryNow())
                    com.kilon.agentvscreen.host.StrongConfirm.grantAgent(t.id)
                    com.kilon.agentvscreen.host.Audit.security("token_created", "${t.name} ${t.level}")
                    made = text; madeIsCode = false; madeId = t.id; step = 3
                } else {
                    val t = T.createPending(want, level, custom)
                    val code = OC.issue(t.id)
                    com.kilon.agentvscreen.host.StrongConfirm.grantAgent(t.id)
                    com.kilon.agentvscreen.host.Audit.security("onboard_code_issued", "${t.name} ${t.level}")
                    made = code; madeIsCode = true; madeId = t.id; step = 3
                }
            })
        StrongConfirmActivity.open(actx, req.id)
    }

    MiuixPage(tr(S.connect_connect_a_new_agent)) {
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
            Group(tr(S.connect_name)) {
                AvsField(value = name, onValueChange = { name = it.take(24) }, placeholder = tr(S.connect_e_g_general_assistant, suggested),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
            }
            Group(tr(S.connect_permission_level)) {
                com.kilon.agentvscreen.api.Tokens.LEVELS.forEach { lv ->
                    RadioButtonPreference(title = levelLabel(lv), summary = levelHint(lv), selected = level == lv, onClick = {
                        // Custom starts from what is on screen now; a preset starts clean.
                        customJson = if (lv == com.kilon.agentvscreen.api.Policy.CUSTOM) com.kilon.agentvscreen.api.Policy.toJson(com.kilon.agentvscreen.api.Policy.table(level, custom)).toString() else "{}"
                        level = lv
                        if (lv == com.kilon.agentvscreen.api.Policy.CUSTOM) showTable = true
                    })
                }
                ArrowPreference(title = tr(S.connect_per_action_permissions),
                    summary = if (custom.isEmpty() || level == com.kilon.agentvscreen.api.Policy.CUSTOM) tr(S.connect_same_as_the_level_preset) else tr(S.connect_changed_2, custom.size),
                    endActions = { Value(if (showTable) tr(S.connect_hide) else tr(S.connect_show)) }, onClick = { showTable = !showTable })
                AnimatedVisibility(showTable) {
                    Column {
                        PolicyForm(com.kilon.agentvscreen.api.Policy.table(level, custom), com.kilon.agentvscreen.api.Policy.PRESETS[level], false) { k, a ->
                            val base = com.kilon.agentvscreen.api.Policy.PRESETS[level]
                            val next = if (base != null && base[k] == a) custom - k else custom + (k to a)
                            customJson = com.kilon.agentvscreen.api.Policy.toJson(next).toString()
                        }
                    }
                }
            }
            Footnote(tr(S.connect_a_cloud_client_s_level))
        }

        if (step == 1) Group(tr(S.connect_where_the_agent_runs)) {
            RadioButtonPreference(
                title = tr(S.connect_on_this_phone),
                summary = tr(S.connect_termux_operit_direct_to_127, ApiConfig.port),
                selected = where == 0,
                onClick = { where = 0 },
            )
            RadioButtonPreference(
                title = tr(S.connect_other_device_tailscale),
                summary = tr(S.connect_computer_or_another_phone_over),
                selected = where == 1,
                onClick = { where = 1 },
            )
            if (where == 1) Row(Modifier.fillMaxWidth().clickable { nav.go(Route.Remote) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(tr(S.connect_tailscale_connection), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(remoteBase ?: tr(S.connect_connect_tailscale_first_to_copy),
                        fontSize = 14.sp, fontFamily = if (remoteBase != null) FontFamily.Monospace else null, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    DotLine(if (remoteReady) Palette.takeover else Palette.stopped, dot = 7.dp, firstLine = 19.dp) {
                        Text(remember(ts, mode, remoteBase) { Tailnet.line(ctx) }, fontSize = 13.sp, lineHeight = 19.sp, color = if (remoteReady) Palette.takeover else Palette.stopped, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            RadioButtonPreference(
                title = tr(S.connect_cloud_public_mcp_experimental),
                summary = tr(S.connect_cloud_mcp_clients_via_tailscale),
                selected = where == 2,
                onClick = { where = 2 },
            )
        }

        if (step == 2) {
            if (where == 2) {
                PublicChannelPicker()
                if (pubEnabled) {
                    PublicRunningSection(ts = ts, mcpUrl = cloudUrl, onDisable = null)
                } else {
                    PublicChecklistSection(showEnableButton = true)
                }
            } else {
                Group(tr(S.connect_how_it_connects)) {
                    listOf(
                        Triple(tr(S.connect_shell), tr(S.connect_terminal_agents_installs_vscreen_and), 0),
                        Triple("MCP", tr(S.connect_mcp_agents_config_and_ability), 1),
                        Triple("HTTP", tr(S.connect_custom_http_clients_address_token), 2),
                    ).forEach { (t, sub, i) -> RadioButtonPreference(title = t, summary = sub, selected = how == i, onClick = { how = i; raw = false }) }
                }
                Group(tr(S.connect_token)) {
                    val choices = listOf(tr(S.connect_permanent), tr(S.connect_30_days), tr(S.connect_7_days), tr(S.connect_1_day)) +
                        (if (how != 1) listOf(tr(S.connect_setup_code_one_time)) else emptyList())
                    if (lifetime >= choices.size) lifetime = 0
                    OverlayDropdownPreference(
                        items = choices,
                        selectedIndex = lifetime,
                        title = tr(S.connect_valid_for),
                        summary = if (oneTime) tr(S.connect_the_agent_must_exchange_it) else null,
                        onSelectedIndexChange = { lifetime = it },
                    )
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
                        0 -> step = 1
                        1 -> step = 2
                        else -> if (where == 2) step = 3 else confirmAgent()
                    }
                },
                enabled = nextEnabled,
                modifier = Modifier.fillMaxWidth(),
                color = MiuixTheme.colorScheme.primary,
            ) {
                Text(if (step == 2 && where != 2 && made == null) tr(S.connect_confirm) else tr(S.connect_next), color = MiuixTheme.colorScheme.onPrimary)
            }
        }

        if (step == 3) {
            if (where == 2) {
                // Feedback 1: the cloud also gets a message for the agent (config without a token + memory + remembered).
                val url = cloudUrl.orEmpty()
                if (url.isNotBlank()) {
                    val msg = remember(url) { AgentGuide.onboardPublic(url) }
                    Group(tr(S.connect_1_address_you_add_it)) {
                        WrappableCode(url) { host.copy(tr(S.connect_public_mcp_url), url) }
                        BasicComponent(title = tr(S.connect_copy_address), summary = tr(S.connect_for_clients_where_you_add),
                            onClick = { host.copy(tr(S.connect_public_mcp_url), url) })
                    }
                    Group(tr(S.connect_2_setup_message)) {
                        BasicComponent(title = tr(S.connect_so_the_agent_remembers_this),
                            summary = tr(S.connect_agents_that_can_edit_their))
                    }
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        PrimaryButton(onClick = { host.copy(tr(S.connect_setup_message), msg) }, modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.primary) {
                            Text(tr(S.connect_copy_setup_message), color = MiuixTheme.colorScheme.onPrimary)
                        }
                    }
                    Footnote(tr(S.connect_the_first_connection_opens_an))
                    WizardStatus(where, null, openedAt)
                    Group(tr(S.connect_preview)) {
                        ArrowPreference(title = tr(S.connect_full_message), endActions = { Value(if (raw) tr(S.connect_hide) else tr(S.connect_show_2)) }, onClick = { raw = !raw })
                        AnimatedVisibility(raw) { Code(msg) { host.copy(tr(S.connect_setup_message), msg) } }
                    }
                }
            } else {
                val tok = made
                val currentTok = madeId?.let { com.kilon.agentvscreen.api.Tokens.byId(it) }
                if (tok == null) {
                    Footnote(tr(S.connect_not_confirmed_yet_go_back))
                } else {
                    val useCode = madeIsCode && !staticToken
                    val messageText = remember(how, base, tok, useCode) {
                        when {
                            how == 1 -> AgentGuide.onboardMcp(base, tok)
                            useCode && how == 2 -> AgentGuide.onboardHttpWithCode(base, tok)
                            useCode -> AgentGuide.onboardWithCode(base, tok)
                            how == 2 -> AgentGuide.onboardHttp(base, tok)
                            else -> AgentGuide.onboard(base, tok)
                        }
                    }
                    Group(tr(S.connect_send_to, want)) {
                        BasicComponent(title = tr(S.connect_location), summary = if (where == 0) tr(S.connect_this_phone_127_0_0, ApiConfig.port) else tr(S.connect_other_device_tailscale_2, base))
                        BasicComponent(title = tr(S.connect_level), summary = levelLabel(level))
                        BasicComponent(title = tr(S.connect_method), summary = listOf(tr(S.connect_shell), "MCP", "HTTP")[how])
                        if (useCode) {
                            BasicComponent(
                                title = tr(S.connect_setup_code_valid_10_minutes),
                                summary = tr(S.connect_expires_after_one_use),
                                endActions = {
                                    if (currentTok?.isPending == true) LinkButton(onClick = {
                                        made = com.kilon.agentvscreen.api.OnboardingCodes.issue(currentTok.id)
                                        com.kilon.agentvscreen.host.Audit.security("onboard_code_reissued", currentTok.name)
                                        host.say(tr(S.connect_new_setup_code_generated_valid))
                                    }) { Text(tr(S.connect_regenerate)) }
                                },
                            )
                        }
                    }
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        PrimaryButton(
                            onClick = { host.copy(tr(S.connect_setup_message), messageText, sensitive = true) },
                            enabled = canCopyLocal,
                            modifier = Modifier.fillMaxWidth(),
                            color = MiuixTheme.colorScheme.primary,
                        ) {
                            Text(tr(S.connect_copy_setup_message), color = MiuixTheme.colorScheme.onPrimary)
                        }
                    }
                    WizardStatus(where, madeId, openedAt)
                    Footnote(
                        if (useCode) tr(S.connect_the_setup_code_is_valid)
                        else tr(S.connect_the_token_is_shown_only),
                    )
                    Group(tr(S.connect_preview_pieces)) {
                        val pieceText = remember(how, base, tok, useCode) {
                            when {
                                how == 0 && useCode -> ApiConfig.installLineForCode(base, tok)
                                how == 0 -> ApiConfig.installLine(base, tok)
                                how == 1 -> ApiConfig.mcpJson(base, tok)
                                else -> messageText
                            }
                        }
                        ArrowPreference(
                            title = if (how == 0) tr(S.connect_install_command) else if (how == 1) tr(S.connect_mcp_config) else tr(S.connect_message_preview),
                            endActions = { Value(if (raw) tr(S.connect_hide) else tr(S.connect_show_2)) },
                            enabled = canCopyLocal,
                            onClick = { raw = !raw },
                        )
                        AnimatedVisibility(raw && canCopyLocal) {
                            Code(pieceText) { host.copy(if (how == 0) tr(S.connect_install_command) else if (how == 1) tr(S.connect_mcp_config) else tr(S.connect_setup_message), pieceText, sensitive = true) }
                        }
                        BasicComponent(title = tr(S.connect_ability_note), summary = tr(S.connect_paste_into_the_agent_s),
                            endActions = {
                                IconButton(onClick = { host.copy(tr(S.connect_ability_note), when (how) { 1 -> AgentGuide.memoryMcp(base); 2 -> AgentGuide.memoryHttp(base); else -> AgentGuide.memory(base) }) }, enabled = canCopyLocal) {
                                    Icon(MiuixIcons.Copy, tr(S.connect_copy))
                                }
                            })
                    }
                }
            }
        }
    }
}

/**
 * 2.0.0-beta11 (rainbow: the agent needed a while to connect; with no finish button they left unsure, and the result
 * did not show). The last step says what is happening with this agent, seen not guessed, and always offers 完成:
 * leaving early loses nothing, because a local agent's entry already exists and a cloud pairing waits in 接入 and 等你处理.
 */
@Composable
private fun WizardStatus(where: Int, madeId: String?, openedAt: Long) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val agents by AgentLedger.agents.collectAsState()
    val tokens by com.kilon.agentvscreen.api.Tokens.list.collectAsState()
    val pend by com.kilon.agentvscreen.host.StrongConfirm.pending.collectAsState()
    val pairing = pend.firstOrNull { it.pairingCode != null && it.at >= openedAt }
    val joined: String? = if (where == 2) {
        tokens.filter { it.publicOnly && !it.revoked && (it.createdAt >= openedAt || (agents[it.id]?.lastCallAt ?: 0L) >= openedAt) }
            .maxByOrNull { it.createdAt }?.id
    } else madeId?.takeIf { (agents[it]?.lastCallAt ?: 0L) > 0L }
    val (text, color) = when {
        joined != null -> tr(S.connect_connected_2, com.kilon.agentvscreen.api.Agents.name(joined)) to Palette.takeover
        pairing != null -> tr(S.connect_pairing_request_received_waiting_for) to Palette.standby
        else -> tr(S.connect_waiting_for_the_agent_to) to MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Group(tr(S.connect_status)) {
        BasicComponent(title = text, summary = if (joined == null) tr(S.connect_the_agent_may_need_a) else null,
            endActions = { Dot(color, 8.dp) })
        if (pairing != null && joined == null) BasicComponent(title = tr(S.connect_check_the_pairing_code_and),
            summary = tr(S.connect_pairing_code, pairing.pairingCode),
            onClick = { StrongConfirmActivity.open(ctx, pairing.id) })
    }
    CardAction(tr(S.connect_done), primary = false) { nav.back() }
}

/**
 * An agent's own page: state, its level (lower at once, higher with a fingerprint), payments for a while, the allows
 * it holds, name, forget. 2.0.0-beta8 (feedback 2): the level is the one setting per agent.
 */
@Composable
fun AgentPage(id: String) {
    val nav = LocalNav.current
    val host = LocalHost.current
    val actx = LocalContext.current.applicationContext
    val main = id == com.kilon.agentvscreen.api.Tokens.MAIN
    val agents by AgentLedger.agents.collectAsState()
    val tokens by com.kilon.agentvscreen.api.Tokens.list.collectAsState()
    val approvals by com.kilon.agentvscreen.host.StrongConfirm.approvals.collectAsState()
    val token = remember(tokens, id) { tokens.firstOrNull { it.id == id } }
    val l = agents[id] ?: AgentLedger.Snapshot()
    var n by remember { mutableStateOf(com.kilon.agentvscreen.api.Agents.name(id)) }
    var sure by remember { mutableStateOf(false) }
    var freshCode by remember(id) { mutableStateOf<String?>(null) }
    MiuixPage(com.kilon.agentvscreen.api.Agents.name(id)) {
        Group { AgentCard(id, l) {} }
        val k = keptOf(l)
        InfoCard(if (k == Kept.CAME_BACK) Palette.takeover else if (k == Kept.CONNECTED) Palette.stopped else Palette.standby,
            when (k) { Kept.CAME_BACK -> tr(S.connect_saved_still_in_use); Kept.SAVED -> tr(S.connect_ability_note_saved); Kept.CONNECTED -> tr(S.connect_save_location_not_confirmed); Kept.NONE -> tr(S.connect_never_connected) },
            remember(l, id) { keptText(id, l) })
        if (token != null && token.isPending) {
            Group(tr(S.connect_setup_code_not_used_yet)) {
                BasicComponent(
                    title = tr(S.connect_regenerate_setup_code_10_min),
                    summary = tr(S.connect_the_earlier_code_stops_working),
                    onClick = {
                        freshCode = com.kilon.agentvscreen.api.OnboardingCodes.issue(token.id)
                        com.kilon.agentvscreen.host.Audit.security("onboard_code_reissued", token.name)
                        host.say(tr(S.connect_new_setup_code_generated_valid))
                    },
                )
                freshCode?.let { code ->
                    BasicComponent(
                        title = tr(S.connect_copy_setup_message_shell),
                        summary = tr(S.connect_includes_the_setup_code_10),
                        onClick = { host.copy(tr(S.connect_setup_message), AgentGuide.onboardWithCode(ApiConfig.baseUrl, code), sensitive = true) },
                    )
                    BasicComponent(
                        title = tr(S.connect_copy_setup_message_http),
                        summary = tr(S.connect_for_agents_that_only_send),
                        onClick = { host.copy(tr(S.connect_setup_message), AgentGuide.onboardHttpWithCode(ApiConfig.baseUrl, code), sensitive = true) },
                    )
                    BasicComponent(
                        title = tr(S.connect_copy_setup_code_only),
                        summary = tr(S.connect_paste_into_operit_or_similar),
                        onClick = { host.copy(tr(S.connect_setup_code), code, sensitive = true) },
                    )
                }
            }
        }
        AgentNow(id)
        if (token != null) {
            val T = com.kilon.agentvscreen.api.Tokens
            val allowed = if (token.publicOnly) T.PUBLIC_LEVELS else T.LEVELS
            /** Apply a new table: narrower or equal at once, anything wider with a fingerprint. */
            fun apply(level: String, custom: Map<String, com.kilon.agentvscreen.api.Policy.Act>, what: String) {
                val next = com.kilon.agentvscreen.api.Policy.table(level, custom, token.publicOnly)
                val wider = com.kilon.agentvscreen.api.Policy.widened(token.policy, next)
                if (wider.isEmpty()) {
                    T.setLevel(id, level, custom)
                    com.kilon.agentvscreen.host.Audit.security("policy_narrow", "${token.name} $what")
                    host.say(tr(S.connect_changed_3, what))
                } else {
                    val rows = wider.mapNotNull { com.kilon.agentvscreen.api.Policy.row(it)?.title }.joinToString(tr(S.automation_list_sep))
                    val req = com.kilon.agentvscreen.host.StrongConfirm.createUiMultiRiskRequest(actx, listOf(com.kilon.agentvscreen.host.Risk.LEVEL_UP), token,
                        tr(S.connect_name_value, token.name, what), tr(S.connect_wider, rows),
                        onApprove = {
                            T.setLevel(id, level, custom)
                            com.kilon.agentvscreen.host.Audit.security("policy_widen", "${token.name} $what")
                        })
                    StrongConfirmActivity.open(actx, req.id)
                }
            }
            Group(tr(S.connect_permission_level)) {
                allowed.forEach { lv ->
                    RadioButtonPreference(
                        title = levelLabel(lv),
                        summary = levelHint(lv),
                        selected = token.level == lv,
                        onClick = {
                            if (lv == token.level) return@RadioButtonPreference
                            apply(lv, if (lv == com.kilon.agentvscreen.api.Policy.CUSTOM) token.policy else emptyMap(), levelLabel(lv))
                        },
                    )
                }
            }
            val preset = com.kilon.agentvscreen.api.Policy.PRESETS[token.level]
            // rainbow: the per-action table is folded by default; the level above is the usual choice.
            var showTable by rememberSaveable(id) { mutableStateOf(false) }
            Group {
                ArrowPreference(title = tr(S.connect_per_action_permissions),
                    summary = if (preset == null) tr(S.connect_custom) else if (token.custom.isEmpty()) tr(S.connect_same_as_the_level_preset) else tr(S.connect_changed_2, token.custom.size),
                    endActions = { Value(if (showTable) tr(S.connect_hide) else tr(S.connect_show)) }, onClick = { showTable = !showTable })
                AnimatedVisibility(showTable) { Column {
                PolicyForm(token.policy, preset, token.publicOnly) { k, a ->
                    val next = if (preset != null && preset[k] == a) token.custom - k else token.custom + (k to a)
                    apply(token.level, next, "${com.kilon.agentvscreen.api.Policy.row(k)?.title ?: k} → ${a.title}")
                }
                } }
                if (preset != null && token.custom.isNotEmpty()) BasicComponent(title = tr(S.connect_restore_the_preset, levelLabel(token.level)),
                    summary = tr(S.connect_undo_the_changed_rows, token.custom.size),
                    onClick = { apply(token.level, emptyMap(), tr(S.connect_restore_preset)) })
            }
            // Feedback 2: "no confirmations for 30 minutes" is a temporary action here, not a setting.
            if (token.act(com.kilon.agentvscreen.api.Policy.OPERATE) != com.kilon.agentvscreen.api.Policy.Act.DENY) Group {
                run {
                    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT) }
                    if (token.trusted) BasicComponent(title = tr(S.connect_payments_without_confirmation_until, fmt.format(java.util.Date(token.trustUntil))),
                        summary = tr(S.connect_confirmation_resumes_when_it_ends),
                        endActions = { LinkButton(onClick = { T.trust(id, 0); com.kilon.agentvscreen.host.Audit.security("trust_end", token.name) }) { Text(tr(S.connect_end_now)) } })
                    else ArrowPreference(title = tr(S.connect_no_confirmation_for_payments_30),
                        summary = tr(S.connect_requires_a_fingerprint_apps_set),
                        onClick = {
                            val req = com.kilon.agentvscreen.host.StrongConfirm.createUiRequest(actx, com.kilon.agentvscreen.host.Risk.TRUST_SET, token,
                                tr(S.connect_payments_without_confirmation_for_30, token.name)) {
                                T.trust(id, 30)
                                com.kilon.agentvscreen.host.Audit.security("trust", "${token.name} 30min")
                            }
                            StrongConfirmActivity.open(actx, req.id)
                        })
                }
            }
            val grants = remember(approvals, id) { approvals.filter { it.token == id && it.risk.startsWith(com.kilon.agentvscreen.host.StrongConfirm.GRANT_PREFIX) } }
            if (grants.isNotEmpty()) Group(tr(S.connect_active_temporary_allowances)) {
                FoldedRows(grants) { g ->
                    BasicComponent(title = grantTitle(g.risk), summary = grantRange(g),
                        endActions = { LinkButton(onClick = { com.kilon.agentvscreen.host.StrongConfirm.revoke(g) }) { Text(tr(S.connect_revoke), color = MiuixTheme.colorScheme.error) } })
                }
            }
        }
        Group(tr(S.connect_name)) {
            AvsField(value = n, onValueChange = { n = it.take(24) }, placeholder = tr(S.connect_name),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
            val can = n.isNotBlank() && n.trim() != com.kilon.agentvscreen.api.Agents.name(id)
            Text(tr(S.connect_save_name), fontSize = 15.sp, color = if (can) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().clickable(enabled = can) { com.kilon.agentvscreen.api.Tokens.rename(id, n.trim()) }.padding(horizontal = 16.dp, vertical = 14.dp))
        }
        if (!main) Group {
            Column(Modifier.fillMaxWidth().clickable {
                if (!sure) sure = true else {
                    Thread { com.kilon.agentvscreen.api.Agents.forget(actx, id) }.start()
                    com.kilon.agentvscreen.host.Audit.security("revoke", n); nav.back()
                }
            }.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (sure) tr(S.connect_tap_again_to_confirm) else tr(S.connect_remove_this_agent), fontSize = 15.sp, color = MiuixTheme.colorScheme.error)
                Text(tr(S.connect_its_token_stops_working_at), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

/** "GRANT:<kind>:<pkg>" as the user reads it. */
internal fun grantTitle(key: String): String {
    val rest = key.removePrefix(com.kilon.agentvscreen.host.StrongConfirm.GRANT_PREFIX)
    val kind = rest.substringBefore(':'); val pkg = rest.substringAfter(':', "*")
    val elevation = kind == com.kilon.agentvscreen.host.StrongConfirm.ROW_GRANT || kind == com.kilon.agentvscreen.host.StrongConfirm.LEVEL_GRANT
    val app = if (pkg == "*" || elevation) "" else (com.kilon.agentvscreen.core.VScreenKnowledge.label(pkg) ?: pkg)
    val what = when (kind) {
        com.kilon.agentvscreen.host.Consent.DESTROY -> tr(S.connect_irreversible_actions)
        com.kilon.agentvscreen.host.Consent.CAUTION -> tr(S.connect_sensitive_actions)
        com.kilon.agentvscreen.host.Consent.APP -> tr(S.connect_actions_in_a_confirm_every)
        "rate" -> tr(S.connect_high_action_rate)
        // 2.0.0-beta11: temporary elevations the agent asked for.
        com.kilon.agentvscreen.host.StrongConfirm.ROW_GRANT -> tr(S.connect_temporarily_allowed) + (com.kilon.agentvscreen.api.Policy.row(pkg)?.title ?: pkg)
        com.kilon.agentvscreen.host.StrongConfirm.LEVEL_GRANT -> tr(S.connect_temporarily_raised_to) + com.kilon.agentvscreen.api.Policy.levelTitle(pkg)
        else -> kind
    }
    return if (app.isEmpty()) what else "$what · $app"
}

internal fun grantRange(g: com.kilon.agentvscreen.host.StrongConfirm.Approval): String {
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
    return if (g.by.endsWith(":" + com.kilon.agentvscreen.host.StrongConfirm.Range.ROUND.key)) tr(S.connect_this_turn_ends_after_the)
    else tr(S.connect_today_until, fmt.format(java.util.Date(g.until)))
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
    Group(tr(S.connect_now)) {
        if (using) DotLine(Palette.brand, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(tr(S.connect_using_the_screen_now), fontSize = 15.sp, lineHeight = 22.sp)
        }
        if (waits > 0) ArrowPreference(title = tr(S.connect_waiting_for_you, waits), summary = tr(S.connect_at_the_top_of_the), onClick = { nav.tab(0) })
        FoldedRows(books) { e ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr(S.connect_schedule, e.name), fontSize = 15.sp)
                    Text(e.spec.describe(), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Text(tr(S.app_picker_cancel), color = MiuixTheme.colorScheme.error, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { Thread { com.kilon.agentvscreen.host.Wake.remove(ctx.applicationContext, e.name, id); tick++ }.start() }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        FoldedRows(watches) { l ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr(S.connect_watch, l.spec.name), fontSize = 15.sp)
                    Text(l.status, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
                }
                Text(tr(S.connect_stop), color = MiuixTheme.colorScheme.error, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { com.kilon.agentvscreen.core.VScreenRules.stop(l.spec.agentId, l.spec.name, tr(S.connect_stopped_by_the_user_in)); tick++ }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        if (!using && waits == 0 && books.isEmpty() && watches.isEmpty())
            Text(tr(S.connect_not_using_the_background_screen), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
        ArrowPreference(title = tr(S.connect_its_records), onClick = { RecordsWho.value = id; nav.tab(TAB_RECORDS) })
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
        IconButton(onClick = onCopy, modifier = Modifier.align(Alignment.TopEnd)) { Icon(MiuixIcons.Copy, tr(S.connect_copy), tint = Color(0xFFE8E4F2)) }
    }
}

/** A tinted note inside a group: a state in a word plus what it means. */
@Composable
fun InfoCard(color: Color, title: String, body: String = "") {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.12f).compositeOver(MiuixTheme.colorScheme.surfaceContainer)).padding(14.dp),
    ) {
        DotLine(color) {
            Text(title, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            if (body.isNotEmpty()) SelectionContainer { Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 4.dp)) }
        }
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

private val agentFmt get() = java.text.SimpleDateFormat(tr(S.connect_mmm_d_hh_mm), if (I18n.en) java.util.Locale.US else java.util.Locale.CHINA)

/** What the agent's current step means, and the one thing to do about it (the detail dialog). */
internal fun keptText(id: String, l: AgentLedger.Snapshot): String {
    fun at(t: Long) = agentFmt.format(java.util.Date(t))
    val main = when (keptOf(l)) {
        Kept.CAME_BACK -> tr(S.connect_the_ability_note_is_saved, l.comebacks, at(l.lastComebackAt)) +
            (l.rememberedWhere?.let { tr(S.connect_saved_in, it) } ?: "")
        Kept.SAVED -> tr(S.connect_it_says_it_saved_the, l.rememberedWhere, at(l.rememberedAt))
        Kept.CONNECTED -> tr(S.connect_connected_but_the_agent_has, at(l.installAt))
        Kept.NONE -> tr(S.connect_not_connected_yet_send_the)
    }
    val ack = AgentLedger.ackVersion(id)
    val ver = if (!l.used || ack >= BuildConfig.VERSION_CODE) "" else "\n\n" + (
        if (ack <= 0) tr(S.connect_the_version_of_the_saved)
        else tr(S.connect_its_note_is_from_its, com.kilon.agentvscreen.api.AgentNews.nameOf(ack)))
    return main + ver
}

@Composable
private fun AgentCard(id: String, l: AgentLedger.Snapshot, edit: () -> Unit) {
    fun at(t: Long) = agentFmt.format(java.util.Date(t))
    val way = when {
        l.lastCallAt == 0L -> tr(S.connect_never_connected)
        l.lastRemoteAt > l.lastLocalAt -> tr(S.connect_another_device_last, at(l.lastCallAt))
        else -> tr(S.connect_this_phone_last, at(l.lastCallAt))
    }
    val k = keptOf(l)
    Column(Modifier.fillMaxWidth().clickable(onClick = edit).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(com.kilon.agentvscreen.api.Agents.name(id), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, modifier = Modifier.scrollIfLong())
                Text(way, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // 1.4.0-beta10 (rainbow: "自己再用" meant nothing and never lit): two facts the user can check, connected and
            // the note saved. Whether it later came back on its own is said in words on the agent's page, with when.
            listOf(Kept.CONNECTED to tr(S.connect_connected_3), Kept.SAVED to tr(S.connect_ability_note_saved)).forEach { (step, label) ->
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

    MiuixPage(tr(S.connect_remote_access), subtitle = tr(S.connect_only_from_your_own_tailscale)) {
        Group(tr(S.connect_mode)) {
            listOf(
                tr(S.connect_off_2) to tr(S.connect_only_agents_on_this_phone),
                tr(S.connect_built_in_tailscale) to tr(S.connect_no_tailscale_app_needed),
                tr(S.connect_external_tailscale_app) to tr(S.connect_uses_the_tailscale_app_already),
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
            Tailnet.Mode.BUILTIN -> Group(tr(S.connect_state)) { BuiltinState(ts, host, ::open) { m -> mode = m; ApiConfig.tailnetMode = m; HostService.server?.start(ApiConfig.port); Tailnet.sync(ctx.applicationContext) } }
            Tailnet.Mode.SYSTEM -> Group(tr(S.connect_state)) {
                if (remoteBase == null) InfoCard(Palette.stopped, tr(S.connect_no_tailscale_address_yet),
                    tr(S.connect_install_and_log_in_to))
                else InfoCard(Palette.takeover, tr(S.connect_connected_through_the_tailscale_app), tr(S.connect_address) + " $remoteBase")
            }
            else -> {}
        }
        if (mode == Tailnet.Mode.BUILTIN && Tailnet.available(ctx)) {
            Group {
                ArrowPreference(title = tr(S.connect_advanced), summary = tr(S.connect_own_server_headscale_auth_key), onClick = { advanced = true })
            }
        }
        if (mode != Tailnet.Mode.OFF) Group(tr(S.connect_safety), padded = true) { RemoteSafety() }
    }

    OverlayBottomSheet(show = advanced, title = tr(S.connect_advanced), onDismissRequest = { advanced = false }) {
        var key by remember { mutableStateOf("") }
        var ctl by remember { mutableStateOf(ApiConfig.tailnetControlUrl ?: "") }
        val problem = ApiConfig.controlUrlProblem(ctl)
        Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AvsField(value = ctl, onValueChange = { ctl = it }, title = tr(S.connect_control_server), placeholder = tr(S.connect_blank_tailscale_s_own))
            Text(problem ?: ApiConfig.normalizeControlUrl(ctl)?.let { tr(S.connect_will_use, it) } ?: tr(S.connect_using_tailscale_s_own_server),
                fontSize = 12.sp, color = if (problem != null) Palette.stopped else MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 12.dp))
            AvsField(value = key, onValueChange = { key = it }, title = tr(S.connect_auth_key_optional), placeholder = tr(S.connect_instead_of_the_web_login))
            Text(tr(S.connect_each_server_keeps_its_own),
                fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 12.dp))
            PrimaryButton(onClick = {
                if (problem != null) { host.say(problem); return@PrimaryButton }
                val k = key.trim(); val c = ctl
                Thread { Tailnet.configure(ctx.applicationContext, c, k) }.start()
                advanced = false
                host.say(tr(S.connect_saved_built_in_tailscale_is))
            }, modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.primary) { Text(tr(S.connect_save_and_reconnect), color = MiuixTheme.colorScheme.onPrimary) }
        }
    }
}

/** 1.4.0-beta10: another VPN is on: what it means and the three ways out, the easiest first. */
@Composable
private fun VpnGuide(host: AppHost, switchTo: (Tailnet.Mode) -> Unit) {
    val ctx = LocalContext.current
    val hasApp = remember { runCatching { ctx.packageManager.getPackageInfo("com.tailscale.ipn", 0); true }.getOrDefault(false) }
    var how by remember { mutableStateOf(false) }
    InfoCard(Palette.standby, tr(S.connect_another_vpn_is_active_on),
        tr(S.connect_a_vpn_on_this_phone))
    if (hasApp) ArrowPreference(title = tr(S.connect_use_the_tailscale_app_on),
        summary = tr(S.connect_it_is_installed_open_it),
        endActions = { Value(tr(S.connect_switch)) }, onClick = { switchTo(Tailnet.Mode.SYSTEM) })
    ArrowPreference(title = tr(S.connect_let_the_vpn_skip_this, if (hasApp) "②" else "①"),
        summary = tr(S.connect_in_your_vpn_proxy_app),
        endActions = { Value(if (how) tr(S.connect_hide) else tr(S.connect_how_to)) }, onClick = { how = !how })
    if (how) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(tr(S.connect_open_your_vpn_or_proxy),
            tr(S.connect_find_per_app_proxy_app),
            tr(S.connect_set_agent_vscreen_to_direct),
            tr(S.connect_return_here_it_connects_automatically)).forEachIndexed { i, t ->
            Text("${i + 1}. $t", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Text(tr(S.connect_copy_package_name), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { host.copy(tr(S.connect_package_name), ctx.packageName) }.padding(vertical = 4.dp))
    }
    BasicComponent(title = tr(S.connect_turn_that_vpn_off_for, if (hasApp) "③" else "②"),
        summary = tr(S.connect_it_connects_by_itself_turning))
}

/** The built-in node: one phase decides the card (Tailnet.phase), the same word the settings row and agents see. */
@Composable
private fun BuiltinState(ts: Tailnet.Status, host: AppHost, open: (String) -> Unit, switchTo: (Tailnet.Mode) -> Unit) {
    val ctx = LocalContext.current
    if (!Tailnet.available(ctx)) { InfoCard(Palette.stopped, tr(S.connect_this_package_has_no_built), tr(S.connect_only_arm64_phones_are_supported)); return }
    val phase = Tailnet.phase(ctx)
    // VPN troubleshooting is relevant only after a failed or stalled connection.
    var vpnOn by remember { mutableStateOf(Tailnet.throughVpn(ctx)) }
    LaunchedEffect(Unit) { while (true) { vpnOn = Tailnet.throughVpn(ctx); delay(2_000) } }
    val loginStalled = (phase == TsPhase.NEEDS_LOGIN || phase == TsPhase.EXPIRED) &&
        ts.authUrl.isNullOrBlank() && (ts.waitingS >= 15 || !ts.loginError.isNullOrBlank())
    if (vpnOn && (loginStalled || phase == TsPhase.UNSTABLE || phase == TsPhase.ERROR)) {
        VpnGuide(host, switchTo)
        var more by remember { mutableStateOf(false) }
        ArrowPreference(title = tr(S.connect_details), endActions = { Value(if (more) tr(S.connect_hide) else tr(S.connect_show_2)) }, onClick = { more = !more })
        if (!more) return
    }
    val known = remember(ts.loggedIn, phase) { Tailnet.known(ctx) }
    var wasOut by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase == TsPhase.NEEDS_LOGIN || phase == TsPhase.EXPIRED || phase == TsPhase.NEEDS_APPROVAL) wasOut = true
        if (phase == TsPhase.CONNECTED && wasOut) { wasOut = false; host.say(tr(S.connect_connected_to_tailscale)) }
    }
    when (phase) {
        TsPhase.SERVICE_OFF -> {
            InfoCard(Palette.idle, if (ServiceControl.now(ctx) == ServiceControl.State.BLOCKED) tr(S.connect_the_service_is_off_the)
                else tr(S.connect_the_service_is_on_standby),
                (known?.let { tr(S.connect_login_kept_reconnects_when_the, it.dnsName ?: it.ip) } ?: tr(S.connect_turn_the_service_on_then)))
            CardAction(tr(S.connect_switch_to_always_running)) { ServiceControl.turnOn(ctx, "tailnet card") }
        }
        TsPhase.CONNECTED -> {
            InfoCard(Palette.takeover, tr(S.connect_connected_to_tailscale),
                tr(S.connect_name) + " ${ts.dnsName ?: ApiConfig.tailnetHostname}\n" + tr(S.connect_address) + " ${ts.ip}" +
                    (ts.user?.let { "\n" + tr(S.connect_account) + " $it" } ?: "") +
                    (if (ts.ownServer) "\n" + tr(S.connect_server) + " ${ts.serverName}" else "") +
                    "\n" + tr(S.connect_devices_online, ts.online) +
                    (ts.relay?.let { "\n" + tr(S.connect_relay) + " $it" } ?: "") +
                    (if (ts.flaps >= 2) "\n" + tr(S.connect_dropped_times_in_10_minutes, ts.flaps) else "") +
                    (ts.lastPeer?.let { "\n" + tr(S.connect_last_access) + " $it" } ?: ""))
            val days = if (ts.keyExpiry > 0) ((ts.keyExpiry - System.currentTimeMillis()) / 86_400_000L).toInt() else -1
            if (days in 0..14) Footnote(tr(S.connect_the_login_expires_in_days, days))
            CardAction(tr(S.connect_log_out_of_tailscale), primary = false) { Tailnet.logout(ctx) }
        }
        TsPhase.NEEDS_APPROVAL -> InfoCard(Palette.standby, tr(S.connect_waiting_for_this_device_to),
            tr(S.connect_the_login_is_done_your, if (ts.ownServer) ts.serverName + " 的管理端" else "Tailscale 控制台（login.tailscale.com/admin/machines）", ApiConfig.tailnetHostname, if (ts.ownServer) "the admin of " + ts.serverName else "the Tailscale console (login.tailscale.com/admin/machines)"))
        TsPhase.NEEDS_LOGIN, TsPhase.EXPIRED -> if (ts.authUrl != null) {
            InfoCard(Palette.standby,
                if (phase == TsPhase.EXPIRED) tr(S.connect_tailscale_login_expired_log_in) else tr(S.connect_log_in_to_tailscale_once),
                if (ts.ownServer) tr(S.connect_open_the_registration_page_of, ts.serverName)
                else tr(S.connect_log_in_with_the_same))
            CardAction(if (ts.ownServer) tr(S.connect_open_the_registration_page) else tr(S.connect_log_in_in_the_browser)) { open(ts.authUrl) }
            CardAction(tr(S.connect_copy_login_link), primary = false) { host.copy(tr(S.connect_login_link), ts.authUrl, sensitive = true) }
        } else {
            val slow = ts.waitingS >= 15 || ts.loginError != null
            InfoCard(if (slow) Palette.stopped else Palette.standby,
                if (ts.loginError != null) tr(S.connect_cannot_reach_the_control_server, ts.serverName)
                else if (slow) tr(S.connect_no_login_link_yet_waited, ts.waitingS)
                else tr(S.connect_requesting_a_login_link_from, ts.serverName),
                if (!slow) tr(S.connect_it_usually_appears_within_seconds)
                else (ts.loginError?.let { tr(S.connect_error) + it.take(240) + "\n" } ?: "") +
                    (if (ts.ownServer) tr(S.connect_check_that_the_address_and, ts.control)
                    else tr(S.connect_this_happens_when_the_phone)) +
                    (if (ts.loginError == null) ts.health?.let { "\n" + tr(S.connect_tailscale_says) + it.take(240) } ?: "" else ""))
            CardAction(tr(S.connect_get_a_login_link_again), primary = false) { Tailnet.login() }
        }
        TsPhase.UNSTABLE -> InfoCard(Palette.standby, tr(S.connect_tailscale_dropped_reconnecting),
            tr(S.connect_still_logged_in_but_the, ts.dnsName ?: ts.ip) +
                (if (!ts.controlOk) tr(S.connect_the_tailscale_server) else tr(S.connect_the_relay, ts.relay?.let { " $it" } ?: "")) +
                tr(S.connect_so_other_devices_cannot_reach, ts.downS) +
                (if (ts.flaps >= 2) tr(S.connect_it_dropped_times_in_10, ts.flaps) else ""))
        TsPhase.ERROR -> InfoCard(Palette.stopped, tr(S.connect_built_in_tailscale_failed_retrying), ts.error ?: ts.state)
        else -> {
            val waited = Tailnet.waitedMs()
            InfoCard(Palette.standby, tr(S.connect_connecting_to_tailscale) + if (waited > Tailnet.SLOW_MS) tr(S.connect_s, waited / 1000) else "",
                (known?.let { tr(S.connect_using_the_saved_login_no, it.dnsName ?: it.ip) } ?: tr(S.connect_usually_a_few_seconds)) +
                    (if (waited > Tailnet.SLOW_MS) "\n" + tr(S.connect_not_reached_yet, if (ts.loggedIn) "中继" else ts.serverName, if (ts.loggedIn) "the relay" else ts.serverName) +
                        ((ts.loginError ?: ts.health)?.let { "：${it.take(160)}" } ?: "") else ""))
        }
    }
    val appVpn = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { appVpn.value = withContext(Dispatchers.IO) { com.kilon.agentvscreen.api.ApiServer.tailnetAddress()?.hostAddress }; delay(5_000) } }
    if (appVpn.value != null) Footnote(tr(S.connect_the_tailscale_app_on_this, appVpn.value))
}

/** Index of the "one-time code" entry in the new-agent lifetime picker (after the [com.kilon.agentvscreen.api.Tokens.LIFETIMES_MS] entries). */
private const val ONE_TIME_CHOICE = 4
