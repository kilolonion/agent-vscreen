package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import com.kilon.agentvscreen.tr

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.VScreenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-app rules (禁止操作 / 只读), drawn inside the home page's common card (MainActivity.Block) like every other
 * section. Only the user changes these; the agent sees them in `status` and is refused by them.
 * (The idle power-saving switch lives with the other switches in 设置.)
 */
@Composable
fun VScreenAppRules() {
    val rules by VScreenStore.rules.collectAsState()
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            // 1.4.2: from the catalog read once (AppCatalog).
            com.kilon.agentvscreen.core.AppCatalog.launchable().map { it.label to it.pkg }
        }
        labels = apps.associate { it.second to it.first }
    }

    if (rules.isEmpty()) {
        Text(tr(S.vscreen_app_rules_no_rules_yet_every_app), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    } else {
        rules.entries.sortedBy { labels[it.key] ?: it.key }.forEach { (pkg, rule) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(labels[pkg] ?: pkg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${rule.zh} · $pkg", fontSize = 12.sp,
                        color = if (rule == VScreenStore.Rule.BLOCK) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                LinkButton(onClick = {
                    val all = VScreenStore.Rule.values()
                    VScreenStore.setRule(pkg, all[(all.indexOf(rule) + 1) % all.size])
                }) { Text(tr(S.vscreen_app_rules_change)) }
                LinkButton(onClick = { VScreenStore.setRule(pkg, null) }) { Text(tr(S.sound_page_remove)) }
            }
        }
    }
    SoftButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text(tr(S.vscreen_app_rules_add_app)) }

    if (adding) AddRuleDialog(apps, rules.keys, onDismiss = { adding = false }) { pkg, rule ->
        VScreenStore.setRule(pkg, rule)
        adding = false
    }
}


@Composable
private fun AddRuleDialog(
    apps: List<Pair<String, String>>,
    existing: Set<String>,
    onDismiss: () -> Unit,
    onPick: (String, VScreenStore.Rule) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(picked?.first ?: tr(S.vscreen_app_rules_choose_app)) },
        text = {
            val p = picked
            if (p == null) {
                Column {
                    AvsField(value = query, onValueChange = { query = it }, placeholder = tr(S.app_picker_search_name_or_package))
                    Spacer(Modifier.height(8.dp))
                    val q = query.trim().lowercase()
                    val shown = apps.filter { it.second !in existing && (q.isEmpty() || it.first.lowercase().contains(q) || it.second.lowercase().contains(q)) }
                    LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                        items(shown, key = { it.second }) { a ->
                            Column(Modifier.fillMaxWidth().clickable { picked = a }.padding(vertical = 8.dp)) {
                                Text(a.first, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(a.second, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.second, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    SoftButton(onClick = { onPick(p.second, VScreenStore.Rule.BLOCK) }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr(S.vscreen_app_rules_blocked_no_opening_reading_or))
                    }
                    SoftButton(onClick = { onPick(p.second, VScreenStore.Rule.READONLY) }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr(S.vscreen_app_rules_read_only_look_and_scroll))
                    }
                    SoftButton(onClick = { onPick(p.second, VScreenStore.Rule.ASK) }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr(S.vscreen_app_rules_confirm_every_time_asks_you))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            LinkButton(onClick = { if (picked != null) picked = null else onDismiss() }) { Text(if (picked != null) tr(S.app_back) else tr(S.app_picker_cancel)) }
        },
    )
}
