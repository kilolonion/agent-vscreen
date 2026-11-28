package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
import top.yukonga.miuix.kmp.basic.SmallTitle
import androidx.compose.ui.Alignment

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.material.icons.outlined.History

import androidx.compose.material.icons.outlined.QuestionAnswer

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.kilon.agentvscreen.core.TaskLog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 1.4.0-beta3 记录 = a timeline (rainbow approved the prototype). beta2 listed four boxes of settings-like rows. Now it
 * reads like a diary: day by day, each task an agent did as a card with its frames, the questions the user answered
 * between them. Chips on top narrow it to tasks, questions, plans (bookings and watches) or lessons. The raw command
 * log moved to Settings › 关于与诊断.
 */

private enum class Filter { ALL, TASKS, ASKS, PLANS, LESSONS }

private sealed class Line(val at: Long) {
    class T(val t: TaskLog.Task) : Line(t.start)
    class A(val o: JSONObject) : Line(o.optLong("at"))
}

private val dayFmt = SimpleDateFormat("M月d日", Locale.ROOT)

private fun dayOf(at: Long): String {
    val c = java.util.Calendar.getInstance()
    fun key(t: Long) = c.apply { timeInMillis = t }.let { it.get(java.util.Calendar.YEAR) * 400 + it.get(java.util.Calendar.DAY_OF_YEAR) }
    val now = System.currentTimeMillis()
    return when (key(at)) {
        key(now) -> tr(S.records_today)
        key(now - 86_400_000L) -> tr(S.records_yesterday)
        else -> if (!com.kilon.agentvscreen.I18n.en) dayFmt.format(Date(at)) else SimpleDateFormat("MMM d", Locale.ENGLISH).format(Date(at))
    }
}

/** 1.4.0-beta11: whose records the tab shows; an agent's page opens the tab on that agent's records. */
object RecordsWho { var value by androidx.compose.runtime.mutableStateOf<String?>(null) }

@Composable
fun RecordsTab(bottom: Dp) {
    val app = LocalApp.current
    val nav = LocalNav.current
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(4_000); beat++ } }
    var filter by remember { mutableStateOf(Filter.ALL) }
    // 1.4.0-beta7: whose records (null = every agent); shown once more than one agent left any.
    var who by RecordsWho::value
    var lines by remember { mutableStateOf(emptyList<Line>()) }
    LaunchedEffect(beat, app.tick) {
        lines = withContext(Dispatchers.IO) {
            val tasks = TaskLog.recent(60).map { Line.T(it) }
            val asks = runCatching { Audit.tail(400).filter { it.optString("kind") == "consent" && answerWord(it.optString("answer")) != null }.map { Line.A(it) } }.getOrDefault(emptyList())
            (tasks + asks).sortedByDescending { it.at }
        }
    }
    fun agentOf(l: Line) = com.kilon.agentvscreen.api.Agents.ofSession(when (l) { is Line.T -> l.t.session; is Line.A -> l.o.optString("session") })
    val whoList = lines.map(::agentOf).distinct()
    var picking by remember { mutableStateOf(false) }
    // 1.4.0-beta9 (rainbow: the page was flat, two rows of chips stacked): one row of kinds; whose records is a
    // choice in the top bar, shown only when more than one agent left any.
    // 2.0.0-beta9 (feedback 6): the records are a lazy list (each task card, question and day header is its own item).
    val shown = remember(lines, filter, who) {
        lines.filter { (filter == Filter.ALL || (filter == Filter.TASKS && it is Line.T) || (filter == Filter.ASKS && it is Line.A)) && (who == null || agentOf(it) == who) }
    }
    val now = remember(shown) { shown.filter { it is Line.T && it.t.running } }
    val past = remember(shown, now) { (shown - now.toSet()) }
    MiuixLazyPage(tr(S.app_records), back = false, bottom = bottom, actions = {
        if (whoList.size > 1) Row(Modifier.padding(end = Edge.text - Edge.slot).clip(RoundedCornerShape(50)).background(if (who != null) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) else MiuixTheme.colorScheme.surfaceContainer)
            .clickable { picking = true }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(who?.let { com.kilon.agentvscreen.api.Agents.name(it) } ?: tr(S.records_every_agent), fontSize = 13.sp,
                color = if (who != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface, maxLines = 1, softWrap = false,
                modifier = Modifier.widthIn(max = 200.dp).scrollIfLong())
            Text(" ▾", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }) {
        item(key = "chips") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Edge.text, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Filter.ALL to tr(S.records_all), Filter.TASKS to tr(S.records_tasks), Filter.ASKS to tr(S.records_questions),
                    Filter.PLANS to tr(S.records_schedules_and_watches), Filter.LESSONS to tr(S.records_routines)).forEach { (f, label) ->
                    FilterChip(label, filter == f) { filter = f }
                }
            }
        }
        when (filter) {
            Filter.PLANS -> item(key = "plans") { Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Plans(beat) } }
            Filter.LESSONS -> item(key = "lessons") { Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { LessonsList(app.tick) } }
            else -> {
                if (shown.isEmpty()) item(key = "empty") {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        if (filter == Filter.ASKS) EmptyState(androidx.compose.material.icons.Icons.Outlined.QuestionAnswer, tr(S.records_no_questions_yet),
                            tr(S.records_when_an_agent_needs_input))
                        else EmptyState(androidx.compose.material.icons.Icons.Outlined.History, tr(S.records_no_tasks_yet),
                            tr(S.records_what_an_agent_did_on))
                    }
                }
                if (now.isNotEmpty()) {
                    item(key = "now") { SmallTitle(tr(S.records_under_way)) }
                    items(now.size, key = { i -> "now:" + lineKey(now[i]) }) { i -> val l = now[i] as Line.T
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) { TaskCard(l.t) { nav.go(Route.Task(l.t.id)) } } }
                }
                // Day headers are their own items: computed once per list, not while composing.
                val rows = ArrayList<Any>(past.size + 8)
                var day = ""
                past.forEach { l -> val d = dayOf(l.at); if (d != day) { day = d; rows += d }; rows += l }
                items(rows.size, key = { i -> rows[i].let { r -> if (r is String) "day:$r" else lineKey(r as Line) } }) { i ->
                    when (val r = rows[i]) {
                        is String -> SmallTitle(r)
                        is Line.T -> Box(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) { TaskCard(r.t) { nav.go(Route.Task(r.t.id)) } }
                        is Line.A -> Box(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
                            val a = r.o.optString("answer")
                            TimelineNote(hhmm(r.at) + " · " + r.o.optString("what").substringBefore(" — ").take(40) + " → " + answerWord(a),
                                if (a == "allowed" || a.startsWith("allowed") || a.startsWith("answered") || a == "covered") Palette.takeover else Palette.stopped)
                        }
                    }
                }
            }
        }
    }
    if (picking) GlassDialog(onDismissRequest = { picking = false }, title = { Text(tr(S.records_whose_records)) }, text = {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items((listOf<String?>(null) + whoList).size) { i -> val id = (listOf<String?>(null) + whoList)[i]
                Text((if (who == id) "✓  " else "    ") + (id?.let { com.kilon.agentvscreen.api.Agents.name(it) } ?: tr(S.records_every_agent)), fontSize = 15.sp,
                    color = if (who == id) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { who = id; picking = false }.padding(vertical = 10.dp))
            }
        }
    }, confirmButton = {}, dismissButton = {})
}

private fun lineKey(l: Line): String = when (l) { is Line.T -> "t:" + l.t.id; is Line.A -> "a:" + l.at + ":" + System.identityHashCode(l.o) }

/** What the user did with a question, in their words; null for answers the app gave itself (pre-authorized, automatic). */
private fun answerWord(a: String): String? = when {
    a == "allowed" || a.startsWith("allowed (") -> tr(S.records_you_allowed)
    a == "covered" -> tr(S.records_allowed_by_an_earlier_permission)
    a == "not allowed" -> tr(S.records_not_allowed_by_your_rules)
    a == "denied" -> tr(S.records_you_refused)
    a.startsWith("answered") -> tr(S.records_you_answered)
    a == "declined" -> tr(S.records_you_declined)
    a == "feedback" -> tr(S.records_you_said_something)
    a.startsWith("ended") -> tr(S.records_not_answered_in_time)
    else -> null
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = MiuixTheme.colorScheme
    Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) c.onPrimary else c.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.primary else c.surfaceContainer).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp))
}

/**
 * 1.4.0-beta4: an empty list is a small centred card (an icon in a soft circle, what will be here, how it gets here)
 * instead of a bare line printed under the filter chips; it never names a command.
 */
@Composable
internal fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) {
    val c = MiuixTheme.colorScheme
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), insideMargin = PaddingValues(horizontal = 24.dp, vertical = 36.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.primary, modifier = Modifier.size(28.dp))
            }
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(sub, fontSize = 13.sp, lineHeight = 19.sp, color = c.onSurfaceVariantSummary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Bookings (alarm-like cards) and watches (running first). */
@Composable
private fun Plans(beat: Int) {
    val app = LocalApp.current
    val tokens by com.kilon.agentvscreen.api.Tokens.list.collectAsState()
    val watches = remember(beat, app.tick) { VScreenRules.list() }
    SchedulesCards(app.tick, showEmpty = watches.isEmpty())
    if (watches.isNotEmpty()) Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        watches.forEach { l ->
            val running = !l.stopped
            val who = if (tokens.size > 1) " · " + com.kilon.agentvscreen.api.Agents.name(l.spec.agentId) else ""
            BasicComponent(
                title = tr(S.records_watch) + l.spec.name + who,
                summary = l.spec.describe() + " · " + (if (running) l.status + tr(S.records_min_left, l.json().opt("ends_in_min")) else (l.endReason ?: tr(S.records_ended))),
                endActions = {
                    LinkButton(onClick = { if (running) VScreenRules.stop(l.spec.agentId, l.spec.name) else VScreenRules.clearEnded(l.spec.agentId, l.spec.name); app.bump() }) {
                        Text(if (running) tr(S.connect_stop) else tr(S.records_clear))
                    }
                },
            )
        }
    }
}

private val logTime = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)

private fun logWhat(o: JSONObject): String = when (o.optString("kind")) {
    "command" -> o.optString("cmd")
    "consent" -> tr(S.records_confirm) + o.optString("what")
    else -> (o.optString("what") + " " + o.optString("detail")).trim()
}

private fun logMeta(o: JSONObject): String = logTime.format(Date(o.optLong("at"))) + " · " + o.optString("token", "-") +
    (o.optString("peer").takeIf { it.isNotEmpty() && it != "null" }?.let { " @ $it" } ?: "") +
    when (o.optString("kind")) {
        "command" -> " · ${o.optString("code")} · ${o.optLong("ms")} ms"
        "consent" -> " · ${o.optString("answer")}"
        else -> ""
    }

private fun logLine(o: JSONObject) = logMeta(o) + " · " + logWhat(o)

@Composable
private fun LogRow(o: JSONObject) = BasicComponent(title = logWhat(o).take(120), summary = logMeta(o))

/** Every booking, each with its editor (time, repeat, length), like an alarm list. */
@Composable
fun SchedulesPage() {
    val app = LocalApp.current
    MiuixPage(tr(S.records_schedules)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { SchedulesCards(app.tick) }
        Text(tr(S.records_3_minutes_before_the_service),
            fontSize = 12.sp, color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

/** Every saved lesson: search, open to see its steps, select several to delete. */
@Composable
fun LessonsPage() {
    val app = LocalApp.current
    MiuixPage(tr(S.records_routines)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { LessonsList(app.tick) }
    }
}

/** The whole activity log; copy and clear are in the top bar. */
@Composable
fun LogPage() {
    val host = LocalHost.current
    var n by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<JSONObject>()) }
    var askClear by remember { mutableStateOf(false) }
    LaunchedEffect(n) { rows = withContext(Dispatchers.IO) { Audit.tail(200) } }
    MiuixLazyPage(tr(S.records_activity_log), actions = {
        IconButton(onClick = { n++ }) { Icon(MiuixIcons.Refresh, tr(S.records_refresh)) }
        IconButton(onClick = { host.copy(tr(S.records_activity_log), rows.joinToString("\n") { logLine(it) }) }) { Icon(MiuixIcons.Copy, tr(S.connect_copy)) }
        IconButton(onClick = { askClear = true }, modifier = Modifier.padding(end = 12.dp)) { Icon(MiuixIcons.Delete, tr(S.automation_clear)) }
    }) {
        // 2.0.0-beta9 (feedback 6): lazy rows.
        if (rows.isEmpty()) item(key = "none") { Group { BasicComponent(title = tr(S.records_nothing_recorded_yet)) } }
        else cardItems(null, rows.mapIndexed { i, o -> i to o }, key = { it.first }) { LogRow(it.second) }
        item(key = "foot") {
            Text(tr(S.records_who_token_device_when_what),
                fontSize = 12.sp, color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
    }
    if (askClear) GlassDialog(
        onDismissRequest = { askClear = false },
        title = { DialogTitle(tr(S.records_clear_the_activity_log)) },
        text = { Text(tr(S.records_this_cannot_be_undone)) },
        confirmButton = { LinkButton(onClick = { Audit.clear(); askClear = false; n++ }) { Text(tr(S.automation_clear), color = Palette.stopped) } },
        dismissButton = { LinkButton(onClick = { askClear = false }) { Text(tr(S.app_picker_cancel)) } },
    )
}

/** For the developer: copy or share the diagnostics, the recent app log. */
@Composable
fun DiagPage() {
    val host = LocalHost.current
    val app = LocalApp.current
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var collecting by remember { mutableStateOf(false) }
    fun collectDiagnostics(deliver: (String) -> Unit) {
        if (collecting) return
        collecting = true
        val items = app.items
        scope.launch {
            try {
                deliver(withContext(Dispatchers.IO) { host.diagnostics(items) })
            } finally {
                collecting = false
            }
        }
    }
    MiuixPage(tr(S.records_diagnostics_and_feedback)) {
        Group {
            ArrowPreference(title = tr(S.records_copy_diagnostics), summary = tr(S.records_version_setup_state_and_recent),
                onClick = { collectDiagnostics { host.copy(tr(S.records_diagnostics), it) } })
            ArrowPreference(title = tr(S.records_share_diagnostics), onClick = { collectDiagnostics { report ->
                runCatching {
                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, tr(S.records_agent_vscreen_diagnostics))
                        .putExtra(android.content.Intent.EXTRA_TEXT, com.kilon.agentvscreen.host.Feedback.redact(report, com.kilon.agentvscreen.host.Feedback.userHosts())), tr(S.records_send_diagnostics)))
                }
            } })
            SwitchPreference(checked = open, onCheckedChange = { open = it }, title = tr(S.records_show_the_recent_log))
        }
        // 2.0.1 (rainbow): feedback in one tap, with the report redacted once more (a GitHub issue is public).
        Group(tr(S.records_report_a_problem)) {
            fun redacted(report: String) = com.kilon.agentvscreen.host.Feedback.redact(report, com.kilon.agentvscreen.host.Feedback.userHosts())
            ArrowPreference(title = tr(S.records_report_on_github),
                summary = tr(S.records_opens_a_new_issue_with),
                onClick = { collectDiagnostics { report ->
                    val r = redacted(report)
                    host.copy(tr(S.records_diagnostics), r)
                    val url = com.kilon.agentvscreen.host.Feedback.issueUrl(
                        title = "[${com.kilon.agentvscreen.BuildConfig.VERSION_NAME}] ",
                        intro = tr(S.records_what_happened_steps_diagnostics_summary),
                        redacted = r)
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                } })
            com.kilon.agentvscreen.host.Feedback.EMAIL?.let { mail ->
                ArrowPreference(title = tr(S.records_report_by_email), summary = tr(S.records_the_body_is_the_full),
                    onClick = { collectDiagnostics { report ->
                        runCatching {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:"))
                                .putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(mail))
                                .putExtra(android.content.Intent.EXTRA_SUBJECT, "Agent-VScreen ${com.kilon.agentvscreen.BuildConfig.VERSION_NAME} " + tr(S.records_feedback))
                                .putExtra(android.content.Intent.EXTRA_TEXT, redacted(report))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    } })
            }
        }
        if (open) Group(padded = true) {
            SelectionContainer {
                Text(com.kilon.agentvscreen.host.AppLogger.tail(80), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
