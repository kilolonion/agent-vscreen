package com.kilon.agentvscreen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.api.AgentGuide
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.Consent
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 1.2: what the user decides about agents, in the app: answer a confirmation, give each agent its own token and
// permission, see what was done (audit), and the safety settings of remote access.

@Composable
private fun Small(t: String) = Text(t, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Questions waiting for the user's yes (the same ones the notifications ask). Nothing is shown when there is none. */
@Composable
fun ConsentCards() {
    val open by Consent.open.collectAsState()
    val ctx = LocalContext.current
    open.forEach { a ->
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(tr("需要你确认", "Needs your confirmation"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(tr("agent「${a.tokenName}」想要：${a.title}", "Agent \"${a.tokenName}\" wants to: ${a.title}"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(a.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Consent.answer(ctx, a.id, true) }) { Text(tr("允许", "Allow")) }
                OutlinedButton(onClick = { Consent.answer(ctx, a.id, false) }) { Text(tr("拒绝", "Deny")) }
            }
        }
    }
}

/** One token per agent: make, limit, pre-authorize, revoke. [copy] puts text on the clipboard; [base] is the address agents use. */
@Composable
fun TokensPanel(base: String, copy: (String, String) -> Unit) {
    val list by Tokens.list.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<Pair<Tokens.Token, String>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); tick++ } }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.ROOT) }
    Small(tr("给每个 agent 单独发一个令牌：可以只给“只看”或“可操作”的权限，单独撤销，日志里也分得清是谁做的。不同令牌的 agent 互相抢不了对方的会话。",
        "Give each agent its own token: it can be limited to \"look only\" or \"operate\", revoked alone, and the log shows who did what. Agents with different tokens cannot take each other's sessions."))
    list.forEach { t ->
        @Suppress("UNUSED_EXPRESSION") tick
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (t.id == Tokens.MAIN) tr("主令牌（上面“接入 agent”用的）", "Main token (the one used under Connect an agent)") else t.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Small(tr("权限：", "Permission: ") + scopeWord(t.scope) + " · " + (if (t.lastUsedAt > 0) tr("上次使用 ", "last used ") + fmt.format(Date(t.lastUsedAt)) else tr("还没用过", "never used")) +
                if (t.trusted) tr(" · 免确认到 ", " · no confirmations until ") + fmt.format(Date(t.trustUntil)) else "")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t.id != Tokens.MAIN) TextButton(onClick = { Tokens.setScope(t.id, Tokens.SCOPES[(Tokens.SCOPES.indexOf(t.scope) + 1) % Tokens.SCOPES.size]) }) { Text(tr("改权限", "Change")) }
                if (t.trusted) TextButton(onClick = { Tokens.trust(t.id, 0) }) { Text(tr("结束免确认", "End no-confirm")) }
                else TextButton(onClick = { Tokens.trust(t.id, 30); Audit.security("trust", "${t.name} 30min") }) { Text(tr("免确认 30 分钟", "No confirm 30 min")) }
                if (t.id != Tokens.MAIN) TextButton(onClick = { Tokens.revoke(t.id); Audit.security("revoke", t.name) }) { Text(tr("撤销", "Revoke"), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    Small(tr("免确认：这段时间里这个令牌的付费、不可撤销、强制结束等步骤不再弹确认（仍会记进日志）。只在你盯着它做事时开。",
        "No confirmations: during this period this token's payment, irreversible, force-stop and similar steps are not asked about (they are still logged). Turn it on only while you are watching."))
    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("新建令牌", "New token")) }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var scope by remember { mutableIntStateOf(1) }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text(tr("新建令牌", "New token")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(24) }, singleLine = true, placeholder = { Text(tr("给谁用（如 电脑上的助手）", "Who it is for (e.g. desktop assistant)")) }, modifier = Modifier.fillMaxWidth())
                    Tokens.SCOPES.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = scope == i, onClick = { scope = i })
                            Column { Text(scopeWord(s), fontSize = 14.sp); Small(scopeHint(s)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { made = Tokens.create(name, Tokens.SCOPES[scope]); Audit.security("token_created", "${made!!.first.name} ${made!!.first.scope}"); adding = false }) { Text(tr("创建", "Create")) } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(tr("取消", "Cancel")) } })
    }
    made?.let { (t, text) ->
        AlertDialog(onDismissRequest = { made = null }, title = { Text(tr("令牌「${t.name}」", "Token \"${t.name}\"")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Small(tr("令牌只显示这一次（App 里只存它的指纹）。现在就复制给 agent 的话，或单独复制令牌。", "The token is shown only this once (the app keeps only its fingerprint). Copy the message for the agent now, or the token alone."))
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Button(onClick = { copy(tr("给 agent 的话", "Message for your agent"), AgentGuide.onboard(base, text)) }, modifier = Modifier.fillMaxWidth()) { Text(tr("复制给 agent 的话（命令行）", "Copy message for the agent (shell)")) }
                    OutlinedButton(onClick = { copy(tr("给 agent 的话", "Message for your agent"), AgentGuide.onboardMcp(base, text)) }, modifier = Modifier.fillMaxWidth()) { Text(tr("复制给 agent 的话（MCP）", "Copy message for the agent (MCP)")) }
                    OutlinedButton(onClick = { copy(tr("给 agent 的话", "Message for your agent"), AgentGuide.onboardHttp(base, text)) }, modifier = Modifier.fillMaxWidth()) { Text(tr("复制给 agent 的话（HTTP）", "Copy message for the agent (HTTP)")) }
                    OutlinedButton(onClick = { copy(tr("令牌", "Token"), text) }, modifier = Modifier.fillMaxWidth()) { Text(tr("只复制令牌", "Copy the token only")) }
                }
            },
            confirmButton = { TextButton(onClick = { made = null }) { Text(tr("我已复制", "I copied it")) } })
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
            Small(tr("其它设备来的请求必须签名，令牌本身不上网络，回复也带校验。vscreen 命令行会自动签名；MCP 客户端不会，用 MCP 远程接入才需要关掉。",
                "Requests from other devices must be signed: the token itself never travels, and replies are verified too. The vscreen CLI signs by itself; MCP clients do not, so turn this off only to use MCP remotely."))
        }
        Switch(checked = signed, onCheckedChange = { signed = it; ApiConfig.remoteSigned = it; Audit.security("remote_signed", it.toString()) })
    }
    OutlinedTextField(peers, { peers = it; ApiConfig.tailnetPeers = it.split(',', '\n', ' ').map { p -> p.trim().lowercase() }.filter { p -> p.isNotEmpty() }.toSet() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("只允许这些设备（留空 = 网络里的所有设备）", "Only these devices (empty = every device in the network)")) },
        placeholder = { Text(tr("设备名、登录名或 100.x 地址，逗号分开", "device names, logins or 100.x addresses, comma separated")) })
    OutlinedTextField(suffix, { suffix = it; ApiConfig.tailnetSuffix = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("自建控制服务器的域名后缀（可选）", "Name suffix of a self-hosted control server (optional)")) }, placeholder = { Text("例 / e.g. tail.example.com") })
}

/** The app that receives event broadcasts (none by default). */
@Composable
fun EventTargetRow() {
    var target by remember { mutableStateOf(ApiConfig.eventTarget.orEmpty()) }
    OutlinedTextField(target, { target = it.trim(); ApiConfig.eventTarget = it.trim().ifEmpty { null } }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("事件广播发给哪个应用（包名，留空 = 不广播）", "App that receives event broadcasts (package; empty = none)")) })
    Small(tr("盯守看到条件时的事件默认只放进队列（agent 用 vscreen events 取）。填了包名才会另外广播给那一个应用，别的应用收不到。",
        "When a watch sees its condition the event is only queued by default (agents collect it with vscreen events). With a package here it is also broadcast to that one app; no other app receives it."))
}

/** What agents did: every command with who, when, the result (typed text is never stored). */
@Composable
fun AuditPanel(copy: (String, String) -> Unit) {
    var rows by remember { mutableStateOf(emptyList<org.json.JSONObject>()) }
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(n) { rows = Audit.tail(60).reversed() }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT) }
    fun line(o: org.json.JSONObject) = "${fmt.format(Date(o.optLong("at")))} [${o.optString("token", "-")}${o.optString("peer").takeIf { it.isNotEmpty() && it != "null" }?.let { " @ $it" } ?: ""}] " + when (o.optString("kind")) {
        "command" -> "${o.optString("cmd")} → ${o.optString("code")} (${o.optLong("ms")}ms)"
        "consent" -> tr("确认：", "confirm: ") + "${o.optString("what")} → ${o.optString("answer")}"
        else -> "${o.optString("what")} ${o.optString("detail")}"
    }
    Small(tr("每条命令都记在这里：谁（哪个令牌、哪台设备）、什么时候、做了什么、结果。输入的文字只记字数。", "Every command is recorded here: who (which token, which device), when, what, and the result. Typed text is recorded as a length only."))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { n++ }) { Text(tr("刷新", "Refresh")) }
        OutlinedButton(onClick = { copy(tr("操作日志", "Activity log"), rows.joinToString("\n") { line(it) }) }) { Text(tr("复制", "Copy")) }
        TextButton(onClick = { Audit.clear(); n++ }) { Text(tr("清空", "Clear")) }
    }
    if (rows.isEmpty()) Small(tr("还没有记录。", "Nothing recorded yet."))
    else Text(rows.take(40).joinToString("\n") { line(it) }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp))
}
