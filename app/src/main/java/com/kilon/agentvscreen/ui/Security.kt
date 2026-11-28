package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
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
            Text(if (t.id == Tokens.MAIN) tr(S.security_main_token_built_in_default) else t.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Small(com.kilon.agentvscreen.api.Policy.levelTitle(t.level) + (if (t.custom.isNotEmpty() && t.level != com.kilon.agentvscreen.api.Policy.CUSTOM) tr(S.security_changed, t.custom.size) else "") +
                " · " + (if (t.lastUsedAt > 0) tr(S.security_last_used) + fmt.format(Date(t.lastUsedAt)) else tr(S.security_never_used)) +
                (if (t.trusted) tr(S.security_payments_unconfirmed_until) + fmt.format(Date(t.trustUntil)) else "") +
                when { t.expired -> tr(S.security_expired); t.expiresAt > 0 -> tr(S.security_expires) + fmt.format(Date(t.expiresAt)); else -> "" })
        }
        if (t.id != Tokens.MAIN) LinkButton(onClick = remove) { Text(tr(S.security_remove), color = MiuixTheme.colorScheme.error) }
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
    if (m == null) GlassDialog(onDismissRequest = close, title = { Text(tr(S.security_new_token)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AvsField(value = name, onValueChange = { name = it.take(24) }, placeholder = tr(S.security_who_it_is_for_e))
                Tokens.LEVELS.filter { it != com.kilon.agentvscreen.api.Policy.CUSTOM }.forEach { lv ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        top.yukonga.miuix.kmp.basic.RadioButton(selected = level == lv, onClick = { level = lv })
                        Column { Text(com.kilon.agentvscreen.api.Policy.levelTitle(lv), fontSize = 14.sp); Small(com.kilon.agentvscreen.api.Policy.levelHint(lv)) }
                    }
                }
            }
        },
        confirmButton = { LinkButton(onClick = {
            val want = name.trim().ifEmpty { tr(S.security_new_agent) }
            val draft = Tokens.Token("new", want, "", Tokens.scopeOfLevel(level), System.currentTimeMillis(), level = level)
            val req = com.kilon.agentvscreen.host.StrongConfirm.createUiMultiRiskRequest(actx, listOf(com.kilon.agentvscreen.host.Risk.NEW_CLIENT), draft,
                tr(S.connect_connect, want, com.kilon.agentvscreen.api.Policy.levelTitle(level)), com.kilon.agentvscreen.api.Policy.levelHint(level),
                onApprove = {
                    val r = Tokens.createLevel(want, level)
                    com.kilon.agentvscreen.host.StrongConfirm.grantAgent(r.first.id)
                    Audit.security("token_created", "${r.first.name} ${r.first.level}")
                    made = r
                })
            StrongConfirmActivity.open(actx, req.id)
        }) { Text(tr(S.security_create)) } },
        dismissButton = { LinkButton(onClick = close) { Text(tr(S.app_picker_cancel)) } })
    else GlassDialog(onDismissRequest = close, title = { Text(tr(S.security_token, m.first.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Small(tr(S.security_the_token_is_shown_only))
                Text(m.second, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                PrimaryButton(onClick = { copy(tr(S.connect_setup_message), AgentGuide.onboard(base, m.second)) }, modifier = Modifier.fillMaxWidth()) { Text(tr(S.connect_copy_setup_message_shell)) }
                SoftButton(onClick = { copy(tr(S.connect_setup_message), AgentGuide.onboardMcp(base, m.second)) }, modifier = Modifier.fillMaxWidth()) { Text(tr(S.security_copy_setup_message_mcp)) }
                SoftButton(onClick = { copy(tr(S.connect_setup_message), AgentGuide.onboardHttp(base, m.second)) }, modifier = Modifier.fillMaxWidth()) { Text(tr(S.connect_copy_setup_message_http)) }
                SoftButton(onClick = { copy(tr(S.connect_token), m.second) }, modifier = Modifier.fillMaxWidth()) { Text(tr(S.security_copy_the_token_only)) }
            }
        },
        confirmButton = { LinkButton(onClick = close) { Text(tr(S.security_i_copied_it)) } })
}

private fun scopeWord(s: String) = when (s) { "look" -> tr(S.security_look_only); "operate" -> tr(S.security_operate); else -> tr(S.security_everything_incl_system_repair_force) }
private fun scopeHint(s: String) = when (s) {
    "look" -> tr(S.security_can_read_status_the_app)
    "operate" -> tr(S.security_can_open_and_operate_apps)
    else -> tr(S.security_the_same_as_the_main)
}

/** Safety of access from other devices: signed requests only, the allowed devices, a self-hosted name suffix. */
@Composable
fun RemoteSafety() {
    var signed by remember { mutableStateOf(ApiConfig.remoteSigned) }
    var peers by remember { mutableStateOf(ApiConfig.tailnetPeers.joinToString(", ")) }
    var suffix by remember { mutableStateOf(ApiConfig.tailnetSuffix.orEmpty()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(tr(S.security_signed_requests_only), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Small(tr(S.security_requests_via_the_external_app))
        }
        Switch(checked = signed, onCheckedChange = { signed = it; ApiConfig.remoteSigned = it; Audit.security("remote_signed", it.toString()) })
    }
    // 2.0.1-beta2: a short title and a short placeholder; what goes in is said under the field.
    AvsField(value = peers, onValueChange = { peers = it; ApiConfig.tailnetPeers = it.split(',', '\n', ' ').map { p -> p.trim().lowercase() }.filter { p -> p.isNotEmpty() }.toSet() },
        title = tr(S.security_only_these_devices), placeholder = tr(S.security_blank_every_device), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    Small(tr(S.security_device_names_logins_or_100))
    AvsField(value = suffix, onValueChange = { suffix = it; ApiConfig.tailnetSuffix = it },
        title = tr(S.security_self_hosted_control_server_suffix), placeholder = tr(S.security_optional_e_g_tail_example), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

