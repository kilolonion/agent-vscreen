package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.PlayAudio
import com.kilon.agentvscreen.core.VScreenKnowledge
import com.kilon.agentvscreen.core.VScreenMute
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 1.4.0-beta13 (rainbow: a place to mute and unmute apps by hand; agents can do the same with `sound`). One page for
 * app sound: the screen's own muting, the apps that are silent now (whoever did it) with a way back, the apps muted by
 * hand, and the apps that keep their sound on the screen. Every change goes through [VScreenMute], as `sound` does.
 */
@Composable
fun SoundPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    // 1.4.2: the last reading shows at once while a fresh one runs (it reads every package: seconds on a full phone).
    var silent by remember { mutableStateOf<List<PlayAudio.Hit>?>(VScreenMute.lastSilenced) }
    var scanning by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true: mute an app, false: an app that keeps its sound
    var screenMute by remember { mutableStateOf(VScreenMute.enabled) }
    LaunchedEffect(tick) { scanning = true; withContext(Dispatchers.IO) { VScreenMute.silenced() }?.let { silent = it }; scanning = false }
    fun label(p: String) = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
    fun said(c: VScreenMute.Change, done: String) = when {
        c.ok -> done
        c.why == "no-shizuku" -> tr(S.sound_page_shizuku_must_be_connected)
        c.why == "denied" -> tr(S.sound_page_the_system_refused_on_xiaomi)
        else -> tr(S.sound_page_it_did_not_change_the)
    }
    // rainbow (beta13): a tap gave no sign of work and the list kept the restored apps, so people tapped again.
    // Every change now has one pending state: the tapped row shows a spinner and what it is doing, every other row
    // waits, and the list takes the change from the change's own verified reading at once (no second full scan).
    var busy by remember { mutableStateOf<String?>(null) }
    fun run(key: String, op: () -> Pair<String, ((List<PlayAudio.Hit>) -> List<PlayAudio.Hit>)?>) {
        if (busy != null) return
        busy = key
        scope.launch {
            val (m, update) = withContext(Dispatchers.IO) { op() }
            if (update != null) silent = silent?.let(update) else tick++
            busy = null; msg = m
        }
    }
    @Composable fun Working(t: String) = Row(verticalAlignment = Alignment.CenterVertically) {
        InfiniteProgressIndicator(size = 16.dp, color = MiuixTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Value(t)
    }
    val noShizuku = tr(S.sound_page_shizuku_must_be_connected)
    fun repairAll(): Pair<String, ((List<PlayAudio.Hit>) -> List<PlayAudio.Hit>)?> = VScreenMute.repair()?.let { r ->
        (tr(S.sound_page_restored_start_playing_again_in, r.fixed.size) +
            (if (r.failed.isNotEmpty()) tr(S.sound_page_could_not_be_restored, r.failed.size) else "") +
            (if (r.denied) tr(S.sound_page_the_system_refused_on_xiaomi_2) else "")) to { _: List<PlayAudio.Hit> -> r.failed }
    } ?: (noShizuku to null)

    MiuixPage(tr(S.settings_app_sound)) {
        Group {
            SwitchPreference(checked = screenMute, onCheckedChange = { screenMute = it; VScreenMute.enabled = it },
                title = tr(S.settings_mute_the_screen), summary = tr(S.sound_page_apps_on_the_screen_are))
        }
        Group(tr(S.sound_page_silent_now)) {
            val l = silent
            when {
                scanning && l == null -> BasicComponent(title = tr(S.public_access_checking))
                l == null -> BasicComponent(title = tr(S.sound_page_shizuku_must_be_connected_2))
                l.isEmpty() -> BasicComponent(title = tr(S.sound_page_none))
                else -> l.forEach { h ->
                    val why = when {
                        h.user != 0 -> tr(S.sound_page_clone)
                        h.pkg in VScreenMute.manual() -> tr(S.sound_page_muted_by_hand)
                        h.pkg in VScreenMute.mutedNow() -> tr(S.sound_page_on_the_screen_back_when)
                        else -> tr(S.sound_page_left_silent)
                    }
                    val key = "${h.user}/${h.pkg}"
                    ArrowPreference(title = label(h.pkg), summary = why, enabled = busy == null || busy == key,
                        endActions = { if (busy == key) Working(tr(S.sound_page_unmuting)) else Value(tr(S.sound_page_unmute)) }, onClick = {
                        run(key) {
                            if (h.user != 0) repairAll()
                            else VScreenMute.unmute(h.pkg).let { c -> said(c, tr(S.sound_page_has_its_sound_back_start, label(h.pkg))) to
                                (if (c.ok) { l: List<PlayAudio.Hit> -> l.filterNot { it.user == 0 && it.pkg == h.pkg } } else null) }
                        }
                    })
                }
            }
            if (scanning && l != null) BasicComponent(title = tr(S.sound_page_checking_again), endActions = { InfiniteProgressIndicator(size = 16.dp, color = MiuixTheme.colorScheme.primary) })
            if (!l.isNullOrEmpty() && l.size > 1) ArrowPreference(title = tr(S.sound_page_unmute_all), enabled = busy == null || busy == "all",
                summary = if (busy == "all") tr(S.sound_page_each_one_is_restored_and) else null,
                endActions = { if (busy == "all") Working(tr(S.sound_page_unmuting)) },
                onClick = { run("all") { repairAll() } })
            ArrowPreference(title = tr(S.sound_page_mute_an_app), summary = tr(S.sound_page_silent_on_every_screen_until),
                enabled = busy == null || busy == "mute", endActions = { if (busy == "mute") Working(tr(S.sound_page_muting)) }, onClick = { picking = true })
        }
        Group(tr(S.sound_page_keeps_its_sound_on_the)) {
            val plays = VScreenMute.plays().sortedBy { label(it) }
            plays.forEach { p -> ArrowPreference(title = label(p), endActions = { Value(tr(S.sound_page_remove)) }, onClick = { VScreenMute.setPlays(p, false); tick++ }) }
            ArrowPreference(title = tr(S.sound_page_add_an_app), onClick = { picking = false })
        }
        Text(tr(S.sound_page_sound_already_playing_changes_only),
            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
    picking?.let { mute ->
        var q by remember { mutableStateOf("") }
        var apps by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
        LaunchedEffect(q) { apps = withContext(Dispatchers.IO) { VScreenKnowledge.findApps(q.trim().ifEmpty { null }).let { if (q.isBlank()) it.sortedBy { a -> a.first } else it }.take(60) } }
        GlassDialog(onDismissRequest = { picking = null },
            title = { DialogTitle(if (mute) tr(S.sound_page_mute_which_app) else tr(S.sound_page_which_app_keeps_its_sound)) },
            text = {
                Column {
                    AvsField(value = q, onValueChange = { q = it.take(30) }, placeholder = tr(S.sound_page_search_apps))
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        apps.forEach { (name, pkg) ->
                            Text(name, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable {
                                picking = null
                                if (mute) run("mute") { said(VScreenMute.mute(pkg), tr(S.sound_page_is_muted_until_you_unmute, name)) to null }
                                else { VScreenMute.setPlays(pkg, true); tick++ }
                            }.padding(vertical = 10.dp))
                        }
                    }
                }
            },
            confirmButton = { LinkButton(onClick = { picking = null }) { Text(tr(S.app_picker_cancel)) } })
    }
    msg?.let { m ->
        GlassDialog(onDismissRequest = { msg = null }, title = { DialogTitle(tr(S.settings_app_sound)) },
            text = { Text(m, fontSize = 14.sp, lineHeight = 21.sp) },
            confirmButton = { LinkButton(onClick = { msg = null }) { Text(tr(S.app_ok)) } })
    }
}
