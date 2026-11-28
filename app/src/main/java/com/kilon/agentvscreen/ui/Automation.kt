package com.kilon.agentvscreen.ui

import androidx.compose.material.icons.outlined.School

import androidx.compose.material.icons.outlined.Alarm

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import com.kilon.agentvscreen.core.VScreenMap
import com.kilon.agentvscreen.host.Wake
import com.kilon.agentvscreen.steps
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/*
 * 1.3.0-beta15: the "定时与示教" page. Schedules look and work like alarms (a big time, a switch, tap to change it);
 * the lessons the user saved are listed by app, each opening to its steps.
 */

private fun hm(m: Int) = "%02d:%02d".format(java.util.Locale.ROOT, m / 60, m % 60)

private val DAY_KEYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
private fun dayLabels() = listOf(tr("一", "M"), tr("二", "T"), tr("三", "W"), tr("四", "T"), tr("五", "F"), tr("六", "S"), tr("日", "S"))

/** What the editor holds; [text] is the same words an agent would book with (`Wake.parse` reads it). */
private data class Draft(
    val kind: Int, // 0 = at these times, 1 = every N minutes, 2 = one day only
    val days: Int, val times: List<Int>, val every: Int, val from: Int, val to: Int,
    val date: LocalDate, val forMin: Int, val on: Boolean,
) {
    private fun dayWord() = if (days == 0x7F) "daily" else DAY_KEYS.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(",")
    fun text(): String = when (kind) {
        1 -> "${dayWord()} every ${every}m from ${hm(from)} to ${hm(to)}"
        2 -> "$date " + times.joinToString(",") { hm(it) }
        else -> "${dayWord()} " + times.joinToString(",") { hm(it) }
    }

    companion object {
        fun of(e: Wake.Entry): Draft {
            val s = e.spec
            val kind = if (s.date != null) 2 else if (s.everyMin > 0) 1 else 0
            return Draft(kind, s.days, s.times.ifEmpty { listOf(8 * 60) }, s.everyMin.takeIf { it > 0 } ?: 120, s.fromMin, s.toMin,
                s.date ?: LocalDate.now(), e.forMin, e.on)
        }
    }
}

private fun pickTime(ctx: Context, at: Int, done: (Int) -> Unit) =
    TimePickerDialog(ctx, { _, h, m -> done(h * 60 + m) }, (at / 60) % 24, at % 60, true).show()

/** The schedules, one alarm-like card each. */
@Composable
internal fun SchedulesCards(tick: Int, showEmpty: Boolean = true) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Wake.list(ctx)) }
    var again by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Wake.Entry?>(null) }
    LaunchedEffect(tick, again) { list = withContext(Dispatchers.IO) { Wake.arm(ctx); Wake.list(ctx) } }
    if (list.isEmpty()) {
        if (showEmpty) EmptyState(androidx.compose.material.icons.Icons.Outlined.Alarm, tr("还没有预约或监视", "No schedules or watches yet"),
            tr("agent 要定时用手机，或要等一件事发生时，会出现在这里", "When an agent books a time on the phone, or waits for something to happen, it shows up here"))
        return
    }
    list.forEach { e ->
        val j = e.json()
        val big = when {
            e.spec.everyMin > 0 -> tr("每 ${e.spec.everyMin} 分钟", "Every ${e.spec.everyMin} min")
            else -> e.spec.times.joinToString("  ") { hm(it) }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                .clickable { editing = e }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).alpha(if (e.on) 1f else 0.45f)) {
                Text(big, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.spec.describe() + tr(" · 保持 ${e.forMin} 分钟", " · ${e.forMin} min"), fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((if (e.on) tr("下次 ${j.optString("next_local", "—")}", "Next ${j.optString("next_local", "—")}") else tr("已关闭", "Off")) + " · ${e.name} · ${e.by}",
                    fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = e.on, onCheckedChange = { v ->
                runCatching { Wake.edit(ctx, e.agentId, e.name, e.text, e.forMin, v) }; again++
            })
        }
    }
    editing?.let { e -> ScheduleEditor(e, onClose = { editing = null; again++ }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditor(e: Wake.Entry, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var d by remember(e.agentId, e.name) { mutableStateOf(Draft.of(e)) }
    var error by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 1.3.0-beta17: dim the page behind firmly so its words never mix with the editor's own.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { w -> SideEffect { w.setDimAmount(0.55f) } }
        Column(
            Modifier.padding(horizontal = 18.dp).fillMaxWidth().glassSheet(RoundedCornerShape(28.dp))
                .verticalScroll(rememberScrollState()).padding(20.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("调整预约", "Edit schedule"), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(e.name, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }

            // How it repeats.
            Segmented(listOf(tr("按时间", "At times"), tr("按间隔", "Interval"), tr("仅一次", "Once")), d.kind) { d = d.copy(kind = it); error = null }

            when (d.kind) {
                1 -> {
                    EditGroup(tr("间隔", "Every")) {
                        Stepper("${d.every} " + tr("分钟", "min"), { d = d.copy(every = (d.every - 30).coerceAtLeast(Wake.MIN_EVERY_MIN)) }, { d = d.copy(every = (d.every + 30).coerceAtMost(24 * 60)) })
                    }
                    EditGroup(tr("时段", "Between")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            TimeChip(hm(d.from)) { pickTime(ctx, d.from) { d = d.copy(from = it) } }
                            Text("–", fontSize = 18.sp)
                            TimeChip(if (d.to >= 1440) "24:00" else hm(d.to)) { pickTime(ctx, d.to.coerceAtMost(1439)) { d = d.copy(to = if (it == 0) 1440 else it) } }
                        }
                    }
                }
                else -> EditGroup(tr("时间", "Times")) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.times.forEachIndexed { i, t ->
                            TimeChip(hm(t), onRemove = if (d.times.size > 1) ({ d = d.copy(times = d.times.filterIndexed { k, _ -> k != i }) }) else null) {
                                pickTime(ctx, t) { n -> d = d.copy(times = (d.times.filterIndexed { k, _ -> k != i } + n).distinct().sorted()) }
                            }
                        }
                        if (d.times.size < 24) Box(
                            Modifier.clip(RoundedCornerShape(50)).border(1.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(50))
                                .clickable { pickTime(ctx, (d.times.lastOrNull() ?: 480) + 60) { n -> d = d.copy(times = (d.times + n).distinct().sorted()) } }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) { Icon(Icons.Filled.Add, tr("加一个时间", "Add a time"), tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                    }
                }
            }

            if (d.kind == 2) EditGroup(tr("日期", "Day")) {
                TimeChip("${d.date.monthValue}-${d.date.dayOfMonth}") {
                    DatePickerDialog(ctx, { _, y, m, day -> d = d.copy(date = LocalDate.of(y, m + 1, day)) }, d.date.year, d.date.monthValue - 1, d.date.dayOfMonth)
                        .apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
                }
            } else EditGroup(tr("重复", "Repeat")) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dayLabels().forEachIndexed { i, l ->
                        val on = d.days and (1 shl i) != 0
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer)
                                .clickable { val n = d.days xor (1 shl i); if (n != 0) d = d.copy(days = n) },
                            contentAlignment = Alignment.Center,
                        ) { Text(l, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("每天", "Daily") to 0x7F, tr("工作日", "Weekdays") to 0x1F, tr("周末", "Weekends") to 0x60).forEach { (w, m) ->
                        Text(w, fontSize = 12.sp, color = if (d.days == m) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontWeight = if (d.days == m) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { d = d.copy(days = m) }.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }

            EditGroup(tr("每次保持", "Keep ready for")) {
                Stepper("${d.forMin} " + tr("分钟", "min"), { d = d.copy(forMin = (d.forMin - 1).coerceAtLeast(1)) }, { d = d.copy(forMin = (d.forMin + 1).coerceAtMost(Wake.MAX_FOR_MIN)) })
            }

            SwitchRow(tr("启用", "On"), checked = d.on) { d = d.copy(on = it) }

            error?.let { Text(it, fontSize = 13.sp, color = Palette.stopped) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                LinkButton(onClick = { askDelete = true }) { Text(tr("删除", "Delete"), color = Palette.stopped) }
                Spacer(Modifier.weight(1f))
                LinkButton(onClick = onClose) { Text(tr("取消", "Cancel")) }
                Spacer(Modifier.width(6.dp))
                PrimaryButton(onClick = {
                    try { Wake.edit(ctx, e.agentId, e.name, d.text(), d.forMin, d.on); onClose() }
                    catch (x: Wake.BadSpec) { error = x.message }
                }) { Text(tr("保存", "Save"), color = MiuixTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
    if (askDelete) GlassDialog(
        onDismissRequest = { askDelete = false },
        title = { Text(tr("删除这个预约？", "Delete this schedule?")) },
        text = { Text(tr("将删除 ${e.by} 预约的「${e.name}」，之后不会再为它启动服务。", "\"${e.name}\" scheduled by ${e.by} will be deleted; the service will no longer start for it.")) },
        confirmButton = { LinkButton(onClick = { Wake.remove(ctx, e.name, e.agentId); askDelete = false; onClose() }) { Text(tr("删除", "Delete"), color = Palette.stopped) } },
        dismissButton = { LinkButton(onClick = { askDelete = false }) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
private fun EditGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        content()
    }
}

@Composable
private fun Segmented(items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MiuixTheme.colorScheme.secondaryContainer).padding(4.dp)) {
        items.forEachIndexed { i, s ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).then(if (i == selected) Modifier.background(MiuixTheme.colorScheme.surface) else Modifier)
                    .clickable { onPick(i) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(s, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (i == selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        }
    }
}

@Composable
private fun TimeChip(label: String, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MiuixTheme.colorScheme.secondaryContainer).clickable(onClick = onClick)
            .padding(start = 16.dp, end = if (onRemove != null) 6.dp else 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        if (onRemove != null) Box(Modifier.padding(start = 4.dp).size(28.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, tr("去掉", "Remove"), modifier = Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun Stepper(value: String, minus: () -> Unit, plus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RoundIcon(Icons.Filled.Remove, tr("减少", "Less"), minus)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.heightIn(min = 28.dp))
        RoundIcon(Icons.Filled.Add, tr("增加", "More"), plus)
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(MiuixTheme.colorScheme.secondaryContainer).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

/** The lessons saved on this phone, by app; each opens to its steps. 1.3.0-beta18: search, and select several to delete at once. */
@Composable
internal fun LessonsList(tick: Int) {
    val ctx = LocalContext.current
    var again by remember { mutableIntStateOf(0) }
    var all by remember { mutableStateOf<List<Pair<String, VScreenMap.Segment>>>(emptyList()) }
    var kept by remember { mutableStateOf<List<VScreenMap.Kept>>(emptyList()) }
    LaunchedEffect(tick, again) { withContext(Dispatchers.IO) { kept = VScreenMap.allKept(); all = kept.flatMap { k -> k.segments.map { k.pkg to it } }.sortedByDescending { it.second.at } } }
    var open by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf<List<Pair<String, VScreenMap.Segment>>?>(null) }
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    if (kept.isEmpty()) { EmptyState(androidx.compose.material.icons.Icons.Outlined.School, tr("还没有示教", "No routines yet"),
        tr("你录制的示教和 agent 保存的示教，按应用归类显示在这里", "Routines you recorded and routines agents saved are listed here by app")); return }
    val labels = remember(kept) { kept.map { it.pkg }.distinct().associateWith { pkg -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) } }
    val q = query.trim()
    val shown = if (q.isEmpty()) all else all.filter { (p, s) -> lessonMatches(q, s.name, labels[p] ?: p, p, s.steps) }
    fun keyOf(p: String, s: VScreenMap.Segment) = "$p/${s.name}"
    // 1.4.0-beta8 (rainbow: the search box looked off): a slim pill with a search glyph, in the page's own surface
    // tone (not a tinted block); selecting is an icon at its end.
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(50)).background(MiuixTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(tr("搜索名字、应用或步骤", "Search name, app or steps"), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                androidx.compose.foundation.text.BasicTextField(value = query, onValueChange = { query = it.take(40) }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth())
            }
            if (query.isNotEmpty()) Icon(Icons.Filled.Close, tr("清空", "Clear"), Modifier.size(18.dp).clip(CircleShape).clickable { query = "" }, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Box(Modifier.size(42.dp).clip(CircleShape).background(if (picking) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f) else MiuixTheme.colorScheme.surfaceContainer)
            .clickable { picking = !picking; picked = emptySet() }, contentAlignment = Alignment.Center) {
            Icon(if (picking) Icons.Filled.Close else Icons.Filled.Checklist, if (picking) tr("取消选择", "Cancel") else tr("选择", "Select"), Modifier.size(20.dp),
                tint = if (picking) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
    if (picking) {
        val keys = shown.map { keyOf(it.first, it.second) }.toSet()
        val allOn = keys.isNotEmpty() && picked.containsAll(keys)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("已选 ${picked.size} 个", "${picked.size} selected"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
            LinkButton(onClick = { picked = if (allOn) picked - keys else picked + keys }) { Text(if (allOn) tr("全不选", "Select none") else tr("全选", "Select all")) }
            LinkButton(enabled = picked.isNotEmpty(), onClick = { askDelete = all.filter { keyOf(it.first, it.second) in picked } }) {
                Text(tr("删除", "Delete"), color = if (picked.isNotEmpty()) Palette.stopped else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
    // 1.4.0-beta11 (rainbow: one kind of experience): under each app, its segments (taught by the user or saved by an
    // agent, said which), then what agents noted about it and its habits; all can be seen and removed here.
    val keptOf = remember(kept) { kept.associateBy { it.pkg } }
    fun noteHits(k: VScreenMap.Kept?) = k?.notes.orEmpty().filter { q.isEmpty() || lessonMatches(q, it.text, labels[k!!.pkg] ?: k.pkg) }
    val pkgs = (shown.map { it.first } + kept.map { it.pkg }.filter { p -> noteHits(keptOf[p]).isNotEmpty() || (q.isEmpty() && keptOf[p]?.habits?.isNotEmpty() == true) }).distinct()
    if (pkgs.isEmpty()) Note(tr("没有匹配“$q”的示教", "No routines match \"$q\""))
    pkgs.forEach { pkg ->
        val list = shown.filter { it.first == pkg }
        Text(labels[pkg] ?: pkg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (!picking) KeptExtras(pkg, noteHits(keptOf[pkg]), if (q.isEmpty()) keptOf[pkg]?.habits.orEmpty() else emptyMap()) { again++ }
        list.forEach { (p, s) ->
            val key = keyOf(p, s)
            // 1.3.0-beta18: try / if / repeat show as blocks, their steps indented.
            val steps = remember(s.steps) { com.kilon.agentvscreen.cmd.Steps.outline(s.steps) }
            val count = remember(steps) { steps.count { (_, t) -> t != "}" && !t.startsWith("} ") } }
            val isOpen = open == key && !picking
            val on = key in picked
            val turn by androidx.compose.animation.core.animateFloatAsState(if (isOpen) 180f else 0f, label = "lesson")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (on) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) else MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                    .clickable { if (picking) picked = if (on) picked - key else picked + key else open = if (isOpen) null else key }
                    .padding(horizontal = 14.dp, vertical = 12.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (picking) {
                        top.yukonga.miuix.kmp.basic.Checkbox(state = androidx.compose.ui.state.ToggleableState(on), onClick = { picked = if (on) picked - key else picked + key })
                        Spacer(Modifier.width(4.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(steps(count) + (if (s.uses > 0) tr(" · 用过 ${s.uses} 次", " · used ${s.uses}×") else "") +
                            " · " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(s.at)) + " · " + segmentSource(p, s),
                            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (!picking) Icon(Icons.Filled.KeyboardArrowDown, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.rotate(turn))
                }
                if (isOpen) {
                    var no = 0
                    steps.forEach { (depth, st) ->
                        val closing = st == "}" || st.startsWith("} ")
                        Row(Modifier.padding(start = (depth * 18).dp), verticalAlignment = Alignment.Top) {
                            if (!closing) {
                                no++
                                Box(Modifier.size(22.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary.copy(alpha = if (depth == 0) 1f else 0.55f)), contentAlignment = Alignment.Center) {
                                    Text("$no", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(10.dp))
                            } else Spacer(Modifier.width(32.dp))
                            Text(st, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f), color = if (closing) MiuixTheme.colorScheme.onSurfaceVariantSummary else Color.Unspecified)
                        }
                    }
                    Row {
                        Text(tr("agent 用：act \"use ${s.name}\"", "Agents run it with: act \"use ${s.name}\""), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
                        LinkButton(onClick = { askDelete = listOf(p to s) }) { Text(tr("删除", "Delete"), color = Palette.stopped) }
                    }
                }
            }
        }
    }
    askDelete?.let { list ->
        val one = list.singleOrNull()?.second
        GlassDialog(
            onDismissRequest = { askDelete = null },
            title = { Text(if (one != null) tr("删除示教「${one.name}」？", "Delete routine \"${one.name}\"?") else tr("删除这 ${list.size} 个示教？", "Delete these ${list.size} routines?")) },
            text = { Text(if (one != null) tr("agent 以后不能再用 use ${one.name} 重放它。", "Agents can no longer replay it with use ${one.name}.")
                else list.joinToString(tr("、", ", ")) { it.second.name }.take(200) + tr("\nagent 以后不能再用 use 重放它们。", "\nAgents can no longer replay them with use.")) },
            confirmButton = { LinkButton(onClick = {
                list.forEach { (p, s) -> VScreenMap.deleteSegment(p, s.name) }
                picked = picked - list.map { keyOf(it.first, it.second) }.toSet(); if (picked.isEmpty()) picking = false
                askDelete = null; again++
            }) { Text(tr("删除", "Delete"), color = Palette.stopped) } },
            dismissButton = { LinkButton(onClick = { askDelete = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
}

/** Who a segment came from: the user's lesson (it has a contact sheet) for an agent, or an agent's own. */
internal fun segmentSource(pkg: String, s: VScreenMap.Segment): String {
    val who = s.by?.let { com.kilon.agentvscreen.api.Agents.name(it) }
    return if (VScreenMap.isTaught(pkg, s)) tr("你示教的", "taught by you") + (who?.let { tr("（为 $it）", " (for $it)") } ?: "")
    else (who?.let { tr("$it 存的", "saved by $it") } ?: tr("agent 存的", "saved by an agent"))
}

/** An app's notes (what agents learned about it) and habits (steps before / after every act, human pacing). */
@Composable
private fun KeptExtras(pkg: String, notes: List<VScreenMap.Note>, habits: Map<String, String>, changed: () -> Unit) {
    if (notes.isEmpty() && habits.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MiuixTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 8.dp)) {
        fun line(title: String, sub: String, remove: () -> Unit) = @Composable {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 14.sp, lineHeight = 20.sp)
                    Text(sub, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                LinkButton(onClick = remove) { Text(tr("删除", "Delete"), color = Palette.stopped) }
            }
        }
        habits["before"]?.let { line(tr("每次先做：$it", "Before every act: $it"), tr("这个应用的习惯", "A habit of this app")) { VScreenMap.setProfile(pkg, mapOf("before" to "")); changed() }() }
        habits["after"]?.let { line(tr("每次最后做：$it", "After every act: $it"), tr("这个应用的习惯", "A habit of this app")) { VScreenMap.setProfile(pkg, mapOf("after" to "")); changed() }() }
        habits["human"]?.takeIf { it == "true" }?.let { line(tr("用仿人节奏", "Human pacing"), tr("这个应用的习惯", "A habit of this app")) { VScreenMap.setProfile(pkg, mapOf("human" to "")); changed() }() }
        notes.forEach { n ->
            line(n.text, (n.by?.let { tr("${com.kilon.agentvscreen.api.Agents.name(it)} 记的", "noted by ${com.kilon.agentvscreen.api.Agents.name(it)}") } ?: tr("agent 记的", "noted by an agent")) +
                " · " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(n.at))) { VScreenMap.deleteNote(pkg, n.id); changed() }()
        }
    }
}

/** A lesson matches when every word of [q] is in its name, app, package or steps (case ignored). */
internal fun lessonMatches(q: String, vararg fields: String): Boolean {
    val hay = fields.joinToString("\n").lowercase()
    return q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { hay.contains(it) }
}
