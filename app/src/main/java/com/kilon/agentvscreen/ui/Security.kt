package com.kilon.agentvscreen.ui

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.api.AgentGuide
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 1.2: what the user decides about agents, in the app: answer a confirmation, give each agent its own token and
// permission, see what was done (audit), and the safety settings of remote access.

@Composable
private fun Small(t: String) = Text(t, fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)

/** One token per agent: make, limit, pre-authorize, revoke. [copy] puts text on the clipboard; [base] is the address agents use. */
@Composable
fun TokensPanel(base: String, copy: (String, String) -> Unit) {
    val actx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val list by Tokens.list.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<Pair<Tokens.Token, String>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); tick++ } }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.ROOT) }
    list.forEach { t ->
        @Suppress("UNUSED_EXPRESSION") tick
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (t.id == Tokens.MAIN) tr("主令牌（默认内置令牌）", "Main token (built-in default)") else t.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Small(tr("权限：", "Permission: ") + scopeWord(t.scope) + " · " + (if (t.lastUsedAt > 0) tr("上次使用 ", "last used ") + fmt.format(Date(t.lastUsedAt)) else tr("还没用过", "never used")) +
                if (t.trusted) tr(" · 免确认到 ", " · no confirmations until ") + fmt.format(Date(t.trustUntil)) else "")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t.id != Tokens.MAIN) LinkButton(onClick = { Tokens.setScope(t.id, Tokens.SCOPES[(Tokens.SCOPES.indexOf(t.scope) + 1) % Tokens.SCOPES.size]) }) { Text(tr("改权限", "Change")) }
                if (t.trusted) LinkButton(onClick = { Tokens.trust(t.id, 0) }) { Text(tr("结束免确认", "End no-confirm")) }
                else LinkButton(onClick = {
                    val req = com.kilon.agentvscreen.host.StrongConfirm.createUiRequest(
                        actx,
                        com.kilon.agentvscreen.host.Risk.TRUST_SET,
                        t,
                        tr("免确认 30 分钟", "No confirm 30 min"),
                    ) {
                        Tokens.trust(t.id, 30)
                        Audit.security("trust", "${t.name} 30min")
                    }
                    StrongConfirmActivity.open(actx, req.id)
                }) { Text(tr("免确认 30 分钟", "No confirm 30 min")) }
                if (t.id != Tokens.MAIN) LinkButton(onClick = { val id = t.id; Thread { com.kilon.agentvscreen.api.Agents.forget(actx, id) }.start(); Audit.security("revoke", t.name) }) { Text(tr("撤销", "Revoke"), color = MiuixTheme.colorScheme.error) }
            }
        }
    }
    SoftButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("新建令牌", "New token")) }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var scope by remember { mutableIntStateOf(1) }
        GlassDialog(onDismissRequest = { adding = false }, title = { Text(tr("新建令牌", "New token")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    top.yukonga.miuix.kmp.basic.TextField(value = name, onValueChange = { name = it.take(24) }, label = tr("给谁用（如 电脑上的助手）", "Who it is for (e.g. desktop assistant)"), useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Tokens.SCOPES.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            top.yukonga.miuix.kmp.basic.RadioButton(selected = scope == i, onClick = { scope = i })
                            Column { Text(scopeWord(s), fontSize = 14.sp); Small(scopeHint(s)) }
                        }
                    }
                }
            },
            confirmButton = { LinkButton(onClick = { made = Tokens.create(name, Tokens.SCOPES[scope]); Audit.security("token_created", "${made!!.first.name} ${made!!.first.scope}"); adding = false }) { Text(tr("创建", "Create")) } },
            dismissButton = { LinkButton(onClick = { adding = false }) { Text(tr("取消", "Cancel")) } })
    }
    made?.let { (t, text) ->
        GlassDialog(onDismissRequest = { made = null }, title = { Text(tr("令牌「${t.name}」", "Token \"${t.name}\"")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Small(tr("令牌只显示这一次（App 里只存它的指纹）。现在就复制给 agent 的话，或单独复制令牌。", "The token is shown only this once (the app keeps only its fingerprint). Copy the message for the agent now, or the token alone."))
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    PrimaryButton(onClick = { copy(tr("给 agent 的话", "Message for your agent"), AgentGuide.onboard(base, text)) }, modifier = Modifier.fillMaxWidth()) { Text(tr("复制给 agent 的话（命令行）", "Copy message for the agent (shell)")) }
                    SoftButton(onClick = { copy(tr("给 agent 的话", "Message for your agent"), AgentGuide.onboardMcp(base, text)) }, modifier = Modifier.fillMaxWidth()) { Text(tr("复制给 agent 的话（MCP）", "Copy message for the agent (MCP)")) }
                    SoftButton(onClick = { copy(tr("给 agent 的话", "Message for your agent"), AgentGuide.onboardHttp(base, text)) }, modifier = Modifier.fillMaxWidth()) { Text(tr("复制给 agent 的话（HTTP）", "Copy message for the agent (HTTP)")) }
                    SoftButton(onClick = { copy(tr("令牌", "Token"), text) }, modifier = Modifier.fillMaxWidth()) { Text(tr("只复制令牌", "Copy the token only")) }
                }
            },
            confirmButton = { LinkButton(onClick = { made = null }) { Text(tr("我已复制", "I copied it")) } })
    }
}

/** 2.0.0-beta6 (6.6): confirmed access records grouped by token; revoking requires no strong confirmation. */
@Composable
fun ApprovalsPanel() {
    val approvals by com.kilon.agentvscreen.host.StrongConfirm.approvals.collectAsState()
    val tokens by Tokens.list.collectAsState()
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.ROOT) }
    val tokenNames = remember(tokens) {
        tokens.associate { it.id to (if (it.id == Tokens.MAIN) tr("主令牌", "Main token") else it.name) }
    }
    if (approvals.isEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp),
        ) {
            Small(tr("还没有已确认的接入记录。", "No confirmed access records yet."))
        }
        return
    }
    val grouped = remember(approvals) { approvals.groupBy { it.token } }
    grouped.forEach { (tid, items) ->
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(tokenNames[tid] ?: tid, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            items.forEach { a ->
                val riskTitle = com.kilon.agentvscreen.host.Risk.of(a.risk)?.title ?: a.risk
                val byLabel = when (a.by) {
                    com.kilon.agentvscreen.host.StrongConfirm.BY_BIOMETRIC -> tr("生物识别/密码", "biometric/PIN")
                    com.kilon.agentvscreen.host.StrongConfirm.BY_HOLD -> tr("长按", "hold")
                    com.kilon.agentvscreen.host.StrongConfirm.BY_MIGRATED -> tr("升级迁移", "migrated")
                    else -> a.by
                }
                val untilLabel = if (a.until == 0L) tr("直到撤销", "until revoked") else tr("有效期至 ", "until ") + fmt.format(Date(a.until))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("$riskTitle · ${com.kilon.agentvscreen.host.StrongConfirm.clientLabel(a.client)}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Small("$byLabel · $untilLabel")
                    }
                    LinkButton(onClick = { com.kilon.agentvscreen.host.StrongConfirm.revoke(a) }) {
                        Text(tr("撤销", "Revoke"), color = MiuixTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun scopeWord(s: String) = when (s) { "look" -> tr("只看", "look only"); "operate" -> tr("可操作", "operate"); else -> tr("全部（含系统修复、强制结束）", "everything (incl. system repair, force-stop)") }
private fun scopeHint(s: String) = when (s) {
    "look" -> tr("能看状态、应用列表、截图分析；不能打开或操作应用", "can read status, the app list and analyse screenshots; cannot open or operate apps")
    "operate" -> tr("能打开和操作应用；不能改系统设置或强制结束应用", "can open and operate apps; cannot change system settings or force-stop apps")
    else -> tr("和主令牌一样", "the same as the main token")
}

/** Safety of access from other devices: signed requests only, the allowed devices, a self-hosted name suffix. */
@Composable
fun RemoteSafety() {
    var signed by remember { mutableStateOf(ApiConfig.remoteSigned) }
    var peers by remember { mutableStateOf(ApiConfig.tailnetPeers.joinToString(", ")) }
    var suffix by remember { mutableStateOf(ApiConfig.tailnetSuffix.orEmpty()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(tr("只接受签名请求", "Signed requests only"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Small(tr("经外部 App 来的请求要签名；内置不受影响", "Requests via the external app must be signed; built-in is not affected"))
        }
        Switch(checked = signed, onCheckedChange = { signed = it; ApiConfig.remoteSigned = it; Audit.security("remote_signed", it.toString()) })
    }
    top.yukonga.miuix.kmp.basic.TextField(value = peers, onValueChange = { peers = it; ApiConfig.tailnetPeers = it.split(',', '\n', ' ').map { p -> p.trim().lowercase() }.filter { p -> p.isNotEmpty() }.toSet() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = tr("只允许这些设备（设备名、登录名或 100.x 地址，逗号分开；留空 = 所有设备）", "Only these devices (names, logins or 100.x addresses, comma separated; empty = every device)"))
    top.yukonga.miuix.kmp.basic.TextField(value = suffix, onValueChange = { suffix = it; ApiConfig.tailnetSuffix = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = tr("自建控制服务器的域名后缀（可选，如 tail.example.com）", "Name suffix of a self-hosted control server (optional, e.g. tail.example.com)"))
}

