package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.setValue

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.layout.heightIn

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.TaskLog
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.steps
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 1.4.0-beta3: a task as the user sees it (TaskLog): what it was called, in which app, how it ended, four frames.
 * Shown as a row on the home page (刚才), as a card on the 记录 timeline, and as its own page with every command.
 */

/** The app's name and icon for [pkg] (null when unknown). */
@Composable
fun appOf(pkg: String?): Pair<String?, ImageBitmap?> {
    val ctx = LocalContext.current
    return remember(pkg) {
        if (pkg == null) null to null else runCatching {
            val pm = ctx.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString() to iconBitmap(pm.getApplicationIcon(ai)).asImageBitmap()
        }.getOrDefault(null to null)
    }
}

private fun iconBitmap(d: android.graphics.drawable.Drawable): android.graphics.Bitmap {
    val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c); return b
}

/** What the user reads as the task's name: the agent's own title, else what happened where. */
fun taskTitle(t: TaskLog.Task, app: String?): String = t.title
    ?: if (app != null) tr(S.tasks_in, app, t.commands, steps(t.commands)) else tr(S.tasks_steps_done, t.commands, steps(t.commands))

/** How it ended, and whether that is good news. */
fun taskOutcome(t: TaskLog.Task): Pair<String, Boolean> = when {
    t.running -> tr(S.tasks_running) to true
    t.stoppedByUser -> tr(S.tasks_stopped_by_you) to false
    t.lastCode != "OK" -> tr(S.tasks_not_finished) to false
    else -> tr(S.connect_done) to true
}

private val hm = SimpleDateFormat("HH:mm", Locale.ROOT)
fun hhmm(at: Long): String = hm.format(Date(at))

fun took(t: TaskLog.Task): String {
    val s = (((if (t.running) System.currentTimeMillis() else t.end) - t.start) / 1000).coerceAtLeast(1)
    return if (s < 60) tr(S.tasks_s, s) else tr(S.tasks_m_s, s / 60, s % 60)
}

/** One of a task's small frames, read off the disk once. */
@Composable
fun TaskFrame(name: String?, modifier: Modifier, corner: Dp = 8.dp) {
    val img by produceState<ImageBitmap?>(null, name) {
        value = withContext(Dispatchers.IO) { name?.let { TaskLog.frameFile(it) }?.let { f -> decodeFit(f.path, 360)?.asImageBitmap() } }
    }
    // 2.0.1-beta2 (rainbow: blank tiles beside one or two frames): a missing frame keeps its column empty, no tile.
    if (name == null) { Spacer(modifier); return }
    Box(modifier.clip(RoundedCornerShape(corner)).background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** A picture file no larger than about [side] px (thumbnails do not decode full-size frames). */
/** A frame at its own proportions: as wide as the space for a wide picture, narrower for a tall one. */
@Composable
private fun NaturalFrame(name: String, modifier: Modifier) {
    val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, name) {
        value = withContext(Dispatchers.IO) { TaskLog.frameFile(name)?.let { f -> decodeFit(f.path, 1280)?.asImageBitmap() } }
    }
    val b = img ?: run { Box(modifier.fillMaxWidth(0.6f).aspectRatio(0.45f).clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.surfaceContainer)); return }
    val r = b.width.toFloat() / b.height.coerceAtLeast(1)
    Image(b, null, modifier.fillMaxWidth(if (r < 0.8f) 0.6f else 1f).aspectRatio(r).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit)
}

private fun decodeFit(path: String, side: Int): android.graphics.Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var k = 1
    while (maxOf(o.outWidth, o.outHeight) / (k * 2) >= side) k *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = k })
}.getOrNull()

@Composable
fun AppBadge(icon: ImageBitmap?, size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(MiuixTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        // 1.4.0-beta9: a task with no app (it never launched one) shows the agent mark, not an empty tile.
        if (icon != null) Image(icon, null, Modifier.fillMaxSize())
        else top.yukonga.miuix.kmp.basic.Icon(androidx.compose.material.icons.Icons.Filled.SmartToy, null, Modifier.size(size * 0.6f), tint = MiuixTheme.colorScheme.primary)
    }
}

/** A task as one row: icon, name, how it ended, and a strip of its frames. */
@Composable
fun TaskRow(t: TaskLog.Task, onClick: () -> Unit) {
    val (app, icon) = appOf(t.pkg)
    val (word, good) = taskOutcome(t)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AppBadge(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // 2.0.0-beta8 (K.2): fixed single-line ellipsis instead of marquee so rows stay left-aligned and never clip a character in half.
            Text(taskTitle(t, app), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(word, fontSize = 12.5.sp, color = if (good) Palette.takeover else Palette.stopped)
                Text(" · " + steps(t.commands) + " · " + took(t) + " · " + hhmm(t.start) + " · " + com.kilon.agentvscreen.api.Agents.nameOfSession(t.session), fontSize = 12.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (t.frames.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                t.frames.takeLast(3).forEach { TaskFrame(it, Modifier.width(16.dp).height(32.dp), 4.dp) }
            }
        }
    }
}

/** A task as a card on the timeline: name, how it ended, four frames. */
@Composable
fun TaskCard(t: TaskLog.Task, onClick: () -> Unit) {
    val (app, icon) = appOf(t.pkg)
    val (word, good) = taskOutcome(t)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), insideMargin = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppBadge(icon, 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(taskTitle(t, app), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, modifier = Modifier.scrollIfLong())
                Row {
                    Text(word, fontSize = 12.5.sp, color = if (good) Palette.takeover else Palette.stopped)
                    Text(" · " + steps(t.commands) + " · " + took(t) + " · " + com.kilon.agentvscreen.api.Agents.nameOfSession(t.session), fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (t.frames.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until 4).forEach { i -> TaskFrame(t.frames.getOrNull(i), Modifier.weight(1f).aspectRatio(0.45f)) }
            }
        }
    }
}

/**
 * A task's own page: its frames large, then every command it sent.
 * 1.4.0-beta5: a frame opens large (swipe between them); a step opens its detail: the command, how it ended and what
 * that code means, how long it took, what the agent was told and the picture it got. Tasks from before beta5 have no
 * step records: their commands come from the audit log, without detail.
 */
@Composable
fun TaskPage(id: Long) {
    val t = remember(id) { TaskLog.recent().firstOrNull { it.id == id } }
    if (t == null) { MiuixPage(tr(S.tasks_task)) { Note(tr(S.tasks_this_record_no_longer_exists), modifier = Modifier.padding(24.dp)) }; return }
    val (app, _) = appOf(t.pkg)
    val (word, good) = taskOutcome(t)
    val rows by produceState(emptyList<JSONObject>(), id) {
        value = withContext(Dispatchers.IO) {
            TaskLog.steps(id).ifEmpty {
                runCatching { Audit.tail(600).filter { o -> o.optString("kind") == "command" && o.optString("session") == t.session &&
                    o.optLong("at") >= t.start - 1000 && (t.running || o.optLong("at") <= t.end + 1000) } }.getOrDefault(emptyList())
            }
        }
    }
    var preview by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var step by remember { mutableStateOf<JSONObject?>(null) }
    MiuixPage(taskTitle(t, app), marqueeTitle = true, subtitle = hhmm(t.start) + " · " + com.kilon.agentvscreen.api.Agents.nameOfSession(t.session)) {
        Row(Modifier.padding(horizontal = Edge.text, vertical = 8.dp)) {
            Text(word, fontSize = 14.sp, color = if (good) Palette.takeover else Palette.stopped)
            Text(" · " + steps(t.commands) + " · " + took(t) + (app?.let { " · $it" } ?: ""), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (t.frames.isNotEmpty()) Row(Modifier.padding(horizontal = Edge.card, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0 until 4).forEach { i ->
                val f = t.frames.getOrNull(i)
                TaskFrame(f, Modifier.weight(1f).aspectRatio(0.45f).clickable(enabled = f != null) { preview = t.frames to i }, 12.dp)
            }
        }
        Group(tr(S.tasks_every_step)) {
            if (rows.isEmpty()) Note(tr(S.tasks_the_log_no_longer_has), modifier = Modifier.padding(16.dp))
            // Newest first, as before; numbered in the order they were sent.
            rows.asReversed().forEachIndexed { i, o ->
                val ok = o.optInt("exit") == 0
                Row(Modifier.fillMaxWidth().clickable { step = o }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${rows.size - i}", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.optString("cmd").take(160), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(hhmmss(o.optLong("at")) + " · " + o.optString("code") + " · ${o.optLong("ms")} ms", fontSize = 12.sp,
                            color = if (ok) MiuixTheme.colorScheme.onSurfaceVariantSummary else Palette.stopped)
                    }
                    o.optString("frame").takeIf { it.isNotEmpty() && it != "null" }?.let { f ->
                        Spacer(Modifier.width(8.dp)); TaskFrame(f, Modifier.width(22.dp).height(44.dp), 4.dp)
                    }
                }
            }
        }
    }
    StepSheet(step, open = { f -> preview = listOf(f) to 0 }) { step = null }
    preview?.let { (list, at) -> FramePreview(list, at) { preview = null } }
}

/**
 * One step in full. 1.4.0-beta6 (rainbow: rows did nothing): a dialog of its own, not a sheet that needs the page's
 * popup host; every row opens it (older records show what they have).
 */
@Composable
private fun StepSheet(o: JSONObject?, open: (String) -> Unit, close: () -> Unit) {
    if (o == null) return
    val c = MiuixTheme.colorScheme
    val ok = o.optInt("exit") == 0
    val frame = o.optString("frame").takeIf { it.isNotEmpty() && it != "null" }
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = close),
            contentAlignment = Alignment.Center) {
            Column(Modifier.padding(20.dp).fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(28.dp)).background(c.surfaceContainer.copy(alpha = 0.96f))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { }
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(S.tasks_step, o.optInt("n")), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.primary)
                Text(o.optString("cmd"), fontSize = 15.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text(hhmmss(o.optLong("at")) + (if (o.has("ms")) " · ${o.optLong("ms")} ms" else "") + " · " + o.optString("code"), fontSize = 13.sp, color = if (ok) c.onSurfaceVariantSummary else Palette.stopped)
                com.kilon.agentvscreen.cmd.Codes.of(o.optString("code"))?.takeIf { !ok }?.let { Text(it.text, fontSize = 13.sp, lineHeight = 19.sp, color = c.onSurfaceVariantSummary) }
                // 1.4.0-beta9 (rainbow: the preview was cut to a phone shape; a reply picture can be wide): its own proportions.
                frame?.let { f -> NaturalFrame(f, Modifier.align(Alignment.CenterHorizontally).clickable { open(f) }) }
                Text(o.optString("text").ifEmpty { tr(S.tasks_no_reply_text_was_kept) }, fontSize = 12.sp, lineHeight = 17.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = c.onSurface)
            }
        }
    }
}

/** Frames large, on black: swipe between them, tap to close. */
@Composable
private fun FramePreview(frames: List<String>, start: Int, close: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start) { frames.size }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = close)) {
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                val img by produceState<ImageBitmap?>(null, frames[i]) {
                    value = withContext(Dispatchers.IO) { TaskLog.frameFile(frames[i])?.let { f -> runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() } }
                }
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
            }
            if (frames.size > 1) Text("${pager.currentPage + 1} / ${frames.size}", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
        }
    }
}

private val hms = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
private fun hhmmss(at: Long) = hms.format(Date(at))

/** A small line between cards on the timeline (an answered question, a watch, a booking). */
@Composable
fun TimelineNote(text: String, tint: Color) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(tint))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
