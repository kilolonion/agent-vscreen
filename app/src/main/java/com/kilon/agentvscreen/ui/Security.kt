package com.kilon.agentvscreen.ui

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

/**
 * 2.0.0-beta9 (feedback 5, 6): one row per token, composed lazily by [TokensPage]. What an agent may do is set on its
 * own page (tap the row); here: its level, when it was last used, remove.
 */
@Composable
internal fun TokenRow(t: Tokens.Token, fmt: SimpleDateFormat, open: () -> Unit, remove: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (t.id == Tokens.MAIN) tr("主令牌（默认内置令牌）", "Main token (built-in default)") else t.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Small(com.kilon.agentvscreen.api.Policy.levelTitle(t.level) + (if (t.custom.isNotEmpty() && t.level != com.kilon.agentvscreen.api.Policy.CUSTOM) tr("（改过 ${t.custom.size} 项）", " (${t.custom.size} changed)") else "") +
                " · " + (if (t.lastUsedAt > 0) tr("上次使用 ", "last used ") + fmt.format(Date(t.lastUsedAt)) else tr("还没用过", "never used")) +
                (if (t.trusted) tr(" · 支付免确认至 ", " · payments unconfirmed until ") + fmt.format(Date(t.trustUntil)) else "") +
                when { t.expired -> tr(" · 已到期", " · expired"); t.expiresAt > 0 -> tr(" · 到期 ", " · expires ") + fmt.format(Date(t.expiresAt)); else -> "" })
        }
        if (t.id != Tokens.MAIN) LinkButton(onClick = remove) { Text(tr("删除", "Remove"), color = MiuixTheme.colorScheme.error) }
    }
}

/** New token (an agent made by hand, outside the connect flow): name and level, confirmed with a fingerprint. */
@Composable
internal fun NewTokenDialog(base: String, copy: (String, String) -> Unit, close: () -> Unit) {
    val actx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var name by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(Tokens.FREE) }
    var made by remember { mutableStateOf<Pair<Tokens.Token, String>?>(null) }
    val m = made
    if (m == null) GlassDialog(onDismissRequest = close, title = { Text(tr("新建令牌", "New token")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AvsField(value = name, onValueChange = { name = it.take(24) }, placeholder = tr("给谁用（如 电脑上的助手）", "Who it is for (e.g. desktop assistant)"))
                Tokens.LEVELS.filter { it != com.kilon.agentvscreen.api.Policy.CUSTOM }.forEach { lv ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        top.yukonga.miuix.kmp.basic.RadioButton(selected = level == lv, onClick = { level = lv })
                        Column { Text(com.kilon.agentvscreen.api.Policy.levelTitle(lv), fontSize = 14.sp); Small(com.kilon.agentvscreen.api.Policy.levelHint(lv)) }
                    }
                }
            }
        },
        confirmButton = { LinkButton(onClick = {
            val want = name.trim().ifEmpty { tr("新的 agent", "New agent") }
            val draft = Tokens.Token("new", want, "", Tokens.scopeOfLevel(level), System.currentTimeMillis(), level = level)
            val req = com.kilon.agentvscreen.host.StrongConfirm.createUiMultiRiskRequest(actx, listOf(com.kilon.agentvscreen.host.Risk.NEW_CLIENT), draft,
                tr("接入 $want（${com.kilon.agentvscreen.api.Policy.levelTitle(level)}）", "Connect $want (${com.kilon.agentvscreen.api.Policy.levelTitle(level)})"), com.kilon.agentvscreen.api.Policy.levelHint(level),
                onApprove = {
                    val r = Tokens.createLevel(want, level)
                    com.kilon.agentvscreen.host.StrongConfirm.grantAgent(r.first.id)
                    Audit.security("token_created", "${r.first.name} ${r.first.level}")
                    made = r
                })
            StrongConfirmActivity.open(actx, req.id)
        }) { Text(tr("创建", "Create")) } },
        dismissButton = { LinkButton(onClick = close) { Text(tr("取消", "Cancel")) } })
    else GlassDialog(onDismissRequest = close, title = { Text(tr("令牌「${m.first.name}」", "Token \"${m.first.name}\"")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Small(tr("令牌只显示这一次（App 里只存它的指纹）。现在就复制接入消息，或单独复制令牌。", "The token is shown only this once (the app keeps only its fingerprint). Copy the setup message now, or the token alone."))
                Text(m.second, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                PrimaryButton(onClick = { copy(tr("接入消息", "Setup message"), AgentGuide.onboard(base, m.second)) }, modifier = Modifier.fillMaxWidth()) { Text(tr("复制接入消息（命令行）", "Copy setup message (Shell)")) }
                SoftButton(onClick = { copy(tr("接入消息", "Setup message"), AgentGuide.onboardMcp(base, m.second)) }, modifier = Modifier.fillMaxWidth()) { Text(tr("复制接入消息（MCP）", "Copy setup message (MCP)")) }
                SoftButton(onClick = { copy(tr("接入消息", "Setup message"), AgentGuide.onboardHttp(base, m.second)) }, modifier = Modifier.fillMaxWidth()) { Text(tr("复制接入消息（HTTP）", "Copy setup message (HTTP)")) }
                SoftButton(onClick = { copy(tr("令牌", "Token"), m.second) }, modifier = Modifier.fillMaxWidth()) { Text(tr("只复制令牌", "Copy the token only")) }
            }
        },
        confirmButton = { LinkButton(onClick = close) { Text(tr("我已复制", "I copied it")) } })
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
    // 2.0.1-beta2: a short title and a short placeholder; what goes in is said under the field.
    AvsField(value = peers, onValueChange = { peers = it; ApiConfig.tailnetPeers = it.split(',', '\n', ' ').map { p -> p.trim().lowercase() }.filter { p -> p.isNotEmpty() }.toSet() },
        title = tr("只允许这些设备", "Only these devices"), placeholder = tr("留空 = 所有设备", "Blank = every device"), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    Small(tr("设备名、登录名或 100.x 地址，逗号分开", "Device names, logins or 100.x addresses, comma separated"))
    AvsField(value = suffix, onValueChange = { suffix = it; ApiConfig.tailnetSuffix = it },
        title = tr("自建控制服务器域名后缀", "Self-hosted control server suffix"), placeholder = tr("可选，如 tail.example.com", "Optional, e.g. tail.example.com"), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

