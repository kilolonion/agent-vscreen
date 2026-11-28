package com.kilon.agentvscreen.ui

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
        Text(tr("还没有限制，所有应用都可以操作", "No rules yet: every app can be operated"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
                }) { Text(tr("换一种", "Change")) }
                LinkButton(onClick = { VScreenStore.setRule(pkg, null) }) { Text(tr("移除", "Remove")) }
            }
        }
    }
    SoftButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("添加应用", "Add app")) }

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
        title = { Text(picked?.first ?: tr("选择应用", "Choose app")) },
        text = {
            val p = picked
            if (p == null) {
                Column {
                    top.yukonga.miuix.kmp.basic.TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = tr("搜索名称或包名", "Search name or package"), useLabelAsPlaceholder = true, modifier = Modifier.fillMaxWidth(),
                    )
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
                        Text(tr("禁止操作（不能打开、读屏、操作）", "Blocked (no opening, reading or operating)"))
                    }
                    SoftButton(onClick = { onPick(p.second, VScreenStore.Rule.READONLY) }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("只读（能看和滚动，不能点击输入）", "Read-only (look and scroll, no tapping or typing)"))
                    }
                    SoftButton(onClick = { onPick(p.second, VScreenStore.Rule.ASK) }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("每次确认（操作前在手机上问你）", "Confirm every time (asks you on the phone before acting)"))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            LinkButton(onClick = { if (picked != null) picked = null else onDismiss() }) { Text(if (picked != null) tr("返回", "Back") else tr("取消", "Cancel")) }
        },
    )
}
