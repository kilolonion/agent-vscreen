package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
import androidx.compose.foundation.layout.fillMaxHeight

import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.basic.Card
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.ServiceControl
import com.kilon.agentvscreen.host.Updates
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 1.4.0-beta3 设置 (rainbow approved the prototype). beta2 was five groups of equal rows, so the two things people
 * come here for (who can reach the phone, how much an agent may do on its own) looked like any toggle. Now those two
 * are big cards at the top, drawn rather than listed; the screen's own switches follow as one group, and everything
 * rare (recognition, language, updates, the raw log, diagnostics) is gathered at the bottom under 关于与诊断.
 */

@Composable
fun SettingsTab(bottom: Dp) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    val s by BackgroundOperation.state.collectAsState()
    val rules by com.kilon.agentvscreen.core.VScreenStore.rules.collectAsState()
    var eventDialog by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Updates.Outcome?>(null) }

    MiuixPage(tr(S.app_settings), back = false, bottom = bottom) {
        // 2.0.1-beta2 (rainbow): every way in (this phone, Tailscale, cloud, tokens) lives on the 接入 tab only.

        // 2.0.0-beta8 (feedback 2): how much an agent may do is set per agent (接入 › the agent); here only what is about
        // apps, and the table of levels to read.
        SmallTitle(tr(S.settings_permissions))
        Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(title = tr(S.settings_permission_levels), summary = tr(S.settings_default_handling_of_each_action),
                onClick = { nav.go(Route.Security) })
            ArrowPreference(title = tr(S.settings_app_rules), summary = tr(S.settings_per_app_blocked_read_only),
                endActions = { Value(if (rules.isEmpty()) tr(S.settings_none) else tr(S.settings_rule_count, rules.size)) }, onClick = { nav.go(Route.Rules) })
        }

        Group(tr(S.settings_background_screen)) {
            ArrowPreference(
                title = tr(S.settings_floating_window),
                summary = if (s.showPreview) tr(S.settings_on_mini_window_behavior_buttons) else tr(S.automation_off),
                onClick = { nav.go(Route.FloatingWindow) },
            )
            // 1.4.0-beta13 (rainbow): sound has its own page: the screen's muting, what is silent now, muting by hand.
            ArrowPreference(title = tr(S.settings_app_sound), summary = if (com.kilon.agentvscreen.core.VScreenMute.enabled) tr(S.settings_background_screen_muting_on_manage)
                else tr(S.settings_background_screen_muting_off_manage), onClick = { nav.go(Route.Sound) })
            val idleSave by com.kilon.agentvscreen.core.VScreenStore.idlePowerSave.collectAsState()
            SwitchPreference(checked = idleSave, onCheckedChange = { BackgroundOperation.setIdlePowerSave(it) },
                title = tr(S.settings_idle_power_saving), summary = tr(S.settings_pause_background_screen_rendering_after))
            // Issue #48 & #60: 3-mode phone screen behavior during tasks (allow sleep / keep on / wake when needed).
            val screenMode by com.kilon.agentvscreen.core.VScreenStore.phoneScreenMode.collectAsState()
            val sleepsWithPhone = BackgroundOperation.currentDisplay()?.let { !com.kilon.agentvscreen.core.VScreenAwake.canWakeAlone(it) } == true
            val inputBlocked = com.kilon.agentvscreen.core.InputReach.lastBlocked()
            val modeItems = listOf(
                tr(S.settings_allow_sleep),
                tr(S.settings_keep_on_during_tasks) + if (sleepsWithPhone && !inputBlocked) tr(S.settings_recommended) else "",
                tr(S.settings_wake_when_needed) + if (inputBlocked) tr(S.settings_recommended) else "",
            )
            val modeSummary = when {
                inputBlocked && screenMode != com.kilon.agentvscreen.core.VScreenStore.PhoneScreenMode.WAKE_WHEN_NEEDED ->
                    tr(S.settings_the_system_blocks_background_screen)
                sleepsWithPhone && screenMode == com.kilon.agentvscreen.core.VScreenStore.PhoneScreenMode.ALLOW_SLEEP ->
                    tr(S.settings_the_background_screen_pauses_when)
                else -> null
            }
            OverlayDropdownPreference(
                items = modeItems,
                selectedIndex = screenMode.ordinal,
                title = tr(S.settings_when_screen_is_off),
                summary = modeSummary,
                onSelectedIndexChange = { i ->
                    com.kilon.agentvscreen.core.VScreenStore.setPhoneScreenMode(com.kilon.agentvscreen.core.VScreenStore.PhoneScreenMode.entries[i])
                    com.kilon.agentvscreen.core.VScreenAwake.syncScreenLock()
                },
            )
            val heatStopOn by com.kilon.agentvscreen.host.ThermalGuard.hardStopEnabled.collectAsState()
            ArrowPreference(
                title = tr(S.settings_thermal_protection),
                endActions = {
                    Value(
                        if (heatStopOn) tr(S.connect_on) else tr(S.automation_off),
                        if (!heatStopOn) Palette.stopped else MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                },
                onClick = { nav.go(Route.Thermal) },
            )
            val opts = listOf(1, 3, 5, 10, 30)
            var quiet by remember { mutableIntStateOf(ApiConfig.quietMinutes) }
            OverlayDropdownPreference(items = opts.map { tr(S.settings_min, it) }, selectedIndex = opts.indexOf(quiet).coerceAtLeast(0),
                title = tr(S.settings_turn_idle_timeout), summary = tr(S.settings_a_turn_ends_after_this),
                onSelectedIndexChange = { i -> ApiConfig.quietMinutes = opts[i]; quiet = opts[i] })
        }

        Group(tr(S.settings_general)) {
            ArrowPreference(title = tr(S.settings_setup), endActions = {
                Value(if (app.items.isEmpty()) "…" else if (app.allOk) tr(S.settings_ready) else tr(S.settings_left, app.left),
                    if (app.items.isNotEmpty() && !app.allOk) Palette.stopped else MiuixTheme.colorScheme.onSurfaceVariantActions)
            }, onClick = { nav.go(Route.Setup) })
            // 1.4.2-beta3: where the shell-level permission comes from (what each means: the "?" on the setup step).
            val pv by com.kilon.agentvscreen.host.Privileged.snapshot.collectAsState()
            val detected = com.kilon.agentvscreen.host.ShizukuManager.providerLabel()
            val pvSummary = when {
                pv.state == com.kilon.agentvscreen.host.ShizukuManager.State.READY -> pv.label
                pv.via == com.kilon.agentvscreen.host.Privileged.Via.SHIZUKU && detected != null ->
                    tr(S.settings_detected, detected) + com.kilon.agentvscreen.host.Privileged.notReadyHint().take(48)
                android.os.Build.VERSION.SDK_INT >= 37 && pv.via == com.kilon.agentvscreen.host.Privileged.Via.SHIZUKU ->
                    tr(S.settings_on_android_17_use_a)
                else -> com.kilon.agentvscreen.host.Privileged.notReadyHint().take(60)
            }
            OverlayDropdownPreference(items = listOf(tr(S.settings_shizuku_forks), "Root"), selectedIndex = pv.via.ordinal,
                title = tr(S.settings_permission_source),
                summary = pvSummary,
                onSelectedIndexChange = { i -> val v = com.kilon.agentvscreen.host.Privileged.Via.entries[i]
                    if (v != pv.via) scope.launch(Dispatchers.IO) { com.kilon.agentvscreen.host.Privileged.choose(ctx, v) } })
            val names = listOf(tr(S.settings_system), "中文", "English", "日本語")
            OverlayDropdownPreference(items = names, selectedIndex = I18n.Lang.entries.indexOf(I18n.choice), title = "语言 / Language",
                onSelectedIndexChange = { i ->
                    if (I18n.Lang.entries[i] != I18n.choice) {
                        I18n.choice = I18n.Lang.entries[i]
                        Overlay.refresh()
                        if (HostService.isAlive) HostService.start(ctx)
                        host.recreateForLanguage()
                    }
                })
            // 2.0.0-beta11: what agents read is separate from the UI language (screen text is never translated).
            OverlayDropdownPreference(items = listOf(tr(S.settings_english_recommended), tr(S.settings_same_as_the_app)),
                selectedIndex = if (I18n.agentLang == I18n.AGENT_AUTO) 1 else 0,
                title = tr(S.settings_language_of_replies_to_agents),
                onSelectedIndexChange = { i -> I18n.agentLang = if (i == 1) I18n.AGENT_AUTO else I18n.AGENT_EN })
        }

        Group(tr(S.settings_recognition_and_recording)) {
            SwitchPreference(checked = s.accurateOcr, onCheckedChange = { BackgroundOperation.setAccurateOcr(it) },
                title = tr(S.settings_accurate_text_recognition), summary = tr(S.settings_pp_ocrv6_offline_on_this))
            DetectorPreference()
            SwitchPreference(checked = s.recording, onCheckedChange = { BackgroundOperation.setRecording(it) },
                title = tr(S.settings_background_recording), summary = tr(S.settings_keeps_background_screen_video_for))
        }

        Group(tr(S.settings_updates)) {
            val newer by Updates.newer.collectAsState()
            val checking by Updates.checking.collectAsState()
            ArrowPreference(title = tr(S.settings_check_for_updates), endActions = {
                Value(when { checking -> tr(S.settings_checking); newer != null -> tr(S.settings_new, newer!!.version); else -> BuildConfig.VERSION_NAME },
                    if (newer != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions)
            }, onClick = { if (!checking) scope.launch { update = withContext(Dispatchers.IO) { Updates.check(ctx) } } })
            ArrowPreference(
                title = tr(S.settings_what_s_new),
                endActions = { Value(BuildConfig.VERSION_NAME) },
                onClick = { ChangesSheetHost.show(com.kilon.agentvscreen.Changes.allUserReleases()) },
            )
            // 1.4.2 (rainbow): which releases the check looks at.
            var channel by remember { mutableStateOf(Updates.channel(ctx)) }
            OverlayDropdownPreference(items = listOf(tr(S.settings_releases), tr(S.settings_betas_and_releases)), selectedIndex = channel.ordinal,
                title = tr(S.settings_update_channel),
                onSelectedIndexChange = { i -> val c = Updates.Channel.entries[i]; if (c != channel) { channel = c; Updates.setChannel(ctx, c)
                    scope.launch { update = withContext(Dispatchers.IO) { Updates.check(ctx) } } } })
        }

        Group(tr(S.settings_records_and_diagnostics)) {
            ArrowPreference(title = tr(S.settings_event_broadcast), endActions = { Value(ApiConfig.eventTarget ?: tr(S.settings_off)) }, onClick = { eventDialog = true })
            ArrowPreference(title = tr(S.records_activity_log), summary = tr(S.settings_every_command_agents_sent_with), onClick = { nav.go(Route.Log) })
            ArrowPreference(title = tr(S.records_diagnostics_and_feedback), onClick = { nav.go(Route.Diag) })
            ArrowPreference(title = tr(S.settings_help), summary = tr(S.settings_what_each_setting_does), onClick = { nav.go(Route.Help) })
            // 2.0.1 (rainbow): the project page.
            ArrowPreference(title = tr(S.settings_about), summary = "GitHub · kilolonion/agent-vscreen",
                endActions = { Value(BuildConfig.VERSION_NAME) },
                onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(com.kilon.agentvscreen.host.Feedback.REPO)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } })
        }
        Text("Agent-VScreen ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }

    if (eventDialog) {
        var target by remember { mutableStateOf(ApiConfig.eventTarget.orEmpty()) }
        GlassDialog(
            onDismissRequest = { eventDialog = false },
            title = { DialogTitle(tr(S.settings_event_broadcast)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr(S.settings_when_a_watch_sees_its),
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    AvsField(value = target, onValueChange = { target = it.trim() }, placeholder = tr(S.settings_package))
                }
            },
            confirmButton = { LinkButton(onClick = { ApiConfig.eventTarget = target.ifEmpty { null }; eventDialog = false; app.bump() }) { Text(tr(S.automation_save)) } },
            dismissButton = { LinkButton(onClick = { eventDialog = false }) { Text(tr(S.app_picker_cancel)) } },
        )
    }
    update?.let { u -> UpdateDialog(u) { update = null } }
}

/** What a check found: the new version with its notes and a download, already the newest, or why it failed. */
/** A level to choose: lit and ringed when chosen. */
@Composable
private fun LevelCard(title: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MiuixTheme.colorScheme
    val bg by androidx.compose.animation.animateColorAsState(if (on) c.primary.copy(alpha = 0.14f) else c.surfaceContainerHigh, androidx.compose.animation.core.tween(240), label = "lvl")
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(bg)
        .border(1.5.dp, if (on) c.primary else Color.Transparent, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (on) c.primary else c.onSurface)
        Text(sub, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.onSurfaceVariantSummary)
    }
}

@Composable
private fun UpdateDialog(u: Updates.Outcome, close: () -> Unit) {
    val ctx = LocalContext.current
    fun view(url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; close() }
    when (u) {
        is Updates.Outcome.Newer -> GlassDialog(
            onDismissRequest = close,
            title = { DialogTitle(if (u.r.prerelease || u.r.version.contains('-')) tr(S.settings_beta, u.r.version) else tr(S.settings_new_version, u.r.version)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr(S.settings_current_version, BuildConfig.VERSION_NAME), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    // 1.4.2: the notes are Markdown (GitHub release body); shown rendered, not as raw marks.
                    if (u.r.notes.isNotBlank()) MarkdownText(u.r.notes.take(6000))
                }
            },
            confirmButton = { LinkButton(onClick = { view(u.r.apk ?: u.r.page) }) { Text(if (u.r.apk != null) tr(S.settings_download_apk) else tr(S.settings_open_the_release)) } },
            dismissButton = { LinkButton(onClick = close) { Text(tr(S.settings_later)) } },
        )
        is Updates.Outcome.Latest -> GlassDialog(
            onDismissRequest = close,
            title = { DialogTitle(tr(S.settings_up_to_date)) },
            text = { Text(tr(S.settings_current_version, BuildConfig.VERSION_NAME) + (u.newest?.let { tr(S.settings_the_newest_release_on_github, it) } ?: "")) },
            confirmButton = { LinkButton(onClick = close) { Text(tr(S.settings_ok)) } },
        )
        is Updates.Outcome.Failed -> GlassDialog(
            onDismissRequest = close,
            title = { DialogTitle(tr(S.settings_check_failed)) },
            text = { Text(u.why) },
            confirmButton = { LinkButton(onClick = { view(Updates.PAGE) }) { Text(tr(S.settings_open_releases)) } },
            dismissButton = { LinkButton(onClick = close) { Text(tr(S.ask_activity_close)) } },
        )
    }
}

/** Per-app limits: blocked, read-only, ask every time. */
@Composable
fun RulesPage() {
    MiuixPage(tr(S.settings_app_rules)) {
        Group(padded = true) { VScreenAppRules() }
        Footnote(tr(S.settings_applies_only_to_the_background))
    }
}

/**
 * The main token (show, copy, renew) and one token per agent. 2.0.0-beta9 (feedback 5, 6): a lazy list (hundreds of
 * tokens cost what a dozen did), and one action that removes every token no request ever used.
 */
@Composable
fun TokensPage() {
    val host = LocalHost.current
    val nav = LocalNav.current
    val actx = LocalContext.current.applicationContext
    var token by remember { mutableStateOf(ApiConfig.token) }
    var show by remember { mutableStateOf(false) }
    var askRenew by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var askUnused by remember { mutableStateOf<List<Tokens.Token>?>(null) }
    var askRemove by remember { mutableStateOf<Tokens.Token?>(null) }
    val list by Tokens.list.collectAsState()
    val agents by com.kilon.agentvscreen.api.AgentLedger.agents.collectAsState()
    val others = remember(list) { list.filter { it.id != Tokens.MAIN }.sortedByDescending { maxOf(it.lastUsedAt, it.createdAt) } }
    val tokenFold = rememberFold(4)
    val unused = remember(list, agents) { Tokens.neverUsed { id -> agents[id]?.used == true } }
    val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT) }
    MiuixLazyPage(tr(S.connect_tokens), actions = {
        LinkButton(onClick = { adding = true }, modifier = Modifier.padding(end = 12.dp)) { Text(tr(S.settings_new_2)) }
    }) {
        item(key = "main") {
            Group(tr(S.settings_main_token)) {
                BasicComponent(
                    title = if (show) token else "•".repeat(12) + token.takeLast(4),
                    summary = tr(S.settings_only_for_agents_you_trust),
                    endActions = {
                        IconButton(onClick = { show = !show }) { Icon(if (show) MiuixIcons.Hide else MiuixIcons.Show, tr(S.settings_show_token)) }
                        IconButton(onClick = { host.copy(tr(S.connect_token), token, sensitive = true) }) { Icon(MiuixIcons.Copy, tr(S.settings_copy_token)) }
                        IconButton(onClick = { askRenew = true }) { Icon(MiuixIcons.Refresh, tr(S.settings_regenerate_token)) }
                    },
                )
            }
        }
        if (unused.isNotEmpty()) item(key = "unused") {
            Group {
                BasicComponent(title = tr(S.settings_remove_never_used_tokens, unused.size),
                    summary = tr(S.settings_no_request_ever_used_them),
                    onClick = { askUnused = unused })
            }
        }
        cardItems(tr(S.settings_one_token_per_agent, others.size), others, key = { it.id }, fold = tokenFold) { t ->
            TokenRow(t, fmt, open = { nav.go(Route.Agent(t.id)) }, remove = { askRemove = t })
        }
    }
    if (adding) NewTokenDialog(ApiConfig.baseUrl, { l, t -> host.copy(l, t, sensitive = true) }) { adding = false }
    askUnused?.let { drop ->
        GlassDialog(
            onDismissRequest = { askUnused = null },
            title = { DialogTitle(tr(S.settings_remove_never_used_tokens_2, drop.size)) },
            text = { Text(tr(S.settings_these_tokens_were_never_used)) },
            confirmButton = { LinkButton(onClick = {
                val ids = drop.map { it.id }.toSet()
                Thread { com.kilon.agentvscreen.api.Agents.forgetMany(ids) }.start()
                Audit.security("token_cleanup", "removed ${ids.size} never-used tokens by hand")
                host.say(tr(S.settings_removed, ids.size))
                askUnused = null
            }) { Text(tr(S.security_remove), color = Palette.stopped) } },
            dismissButton = { LinkButton(onClick = { askUnused = null }) { Text(tr(S.app_picker_cancel)) } },
        )
    }
    askRemove?.let { t ->
        GlassDialog(
            onDismissRequest = { askRemove = null },
            title = { DialogTitle(tr(S.settings_remove, t.name)) },
            text = { Text(tr(S.settings_the_agent_s_token_stops)) },
            confirmButton = { LinkButton(onClick = {
                val id = t.id
                Thread { com.kilon.agentvscreen.api.Agents.forget(actx, id) }.start()
                Audit.security("revoke", t.name)
                askRemove = null
            }) { Text(tr(S.security_remove), color = Palette.stopped) } },
            dismissButton = { LinkButton(onClick = { askRemove = null }) { Text(tr(S.app_picker_cancel)) } },
        )
    }
    if (askRenew) GlassDialog(
        onDismissRequest = { askRenew = false },
        title = { DialogTitle(tr(S.settings_regenerate_the_main_token)) },
        text = { Text(tr(S.settings_the_old_token_stops_working)) },
        confirmButton = { LinkButton(onClick = { ApiConfig.resetToken(); token = ApiConfig.token; askRenew = false; host.say(tr(S.settings_token_regenerated)) }) { Text(tr(S.connect_regenerate)) } },
        dismissButton = { LinkButton(onClick = { askRenew = false }) { Text(tr(S.app_picker_cancel)) } },
    )
}

/** Every explanation, one group per setting: what the rows only name. */
@Composable
fun HelpPage() {
    MiuixPage(tr(S.settings_help)) {
        listOf(
            tr(S.settings_always_running_on_demand_and) to ServiceControl.help(),
            tr(S.connect_where_the_agent_runs) to H.where,
            tr(S.settings_how_it_connects) to H.how,
            tr(S.connect_tokens) to (H.token + "\n\n" + H.tokens),
            tr(S.settings_confirmations) to H.autonomy,
            tr(S.settings_app_rules) to H.rules,
            tr(S.settings_floating_window) to H.window,
            tr(S.settings_idle_power_saving) to H.idle,
            tr(S.settings_when_screen_is_off) to H.keepOn,
            tr(S.settings_thermal_protection) to H.thermal,
            tr(S.settings_mute_the_screen) to H.mute,
            tr(S.settings_turn_idle_timeout) to H.turn,
            tr(S.records_schedules) to H.schedule,
            tr(S.records_routines) to H.lessons,
            tr(S.records_activity_log) to H.audit,
            tr(S.settings_event_broadcast) to H.events,
            tr(S.settings_setup) to H.setup,
            tr(S.settings_diagnostics) to H.diag,
        ).forEach { (t, body) ->
            Group(t, padded = true) { SelectionContainer { Text(body, fontSize = 14.sp, lineHeight = 22.sp) } }
        }
    }
}

/** A grey note under a group. */
@Composable
fun Footnote(t: String) = Text(t, fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))

/** The long words. 1.4: all of them on the Help page (and nowhere else), one paragraph per setting. */
internal object H {
    val where get() = tr(S.settings_agents_on_this_phone_termux, ApiConfig.port)
    val how get() = tr(S.settings_shell_agents_with_a_terminal)
    val token get() = tr(S.settings_a_token_is_an_agent)
    val autonomy get() = tr(S.settings_confirmations_come_in_two_strengths)
    val rules get() = tr(S.settings_blocked_the_ai_cannot_open)
    val tokens get() = tr(S.settings_each_agent_uses_its_own)
    val window get() = tr(S.settings_shown_above_all_apps_while)
    val idle get() = tr(S.settings_pauses_background_screen_rendering_after)
    val keepOn get() = tr(S.settings_controls_main_screen_wake_and)
    val thermal get() = tr(S.settings_thermal_protection_works_in_three)
    val mute get() = tr(S.settings_apps_on_the_background_screen)
    val turn get() = tr(S.settings_a_turn_ends_when_the)
    val lessons get() = tr(S.settings_a_routine_is_a_procedure)
    val schedule get() = tr(S.settings_an_agent_whose_timed_task)
    val audit get() = tr(S.settings_records_each_command_s_source)
    val events get() = tr(S.settings_events_from_watches_are_queued)
    val privilege get() = tr(S.settings_agent_vscreen_needs_shell_level)
    val setup get() = tr(S.settings_uses_only_shizuku_or_root)
    val diag get() = tr(S.settings_collects_version_setup_state_and)
}

/**
 * 2.0.0-beta8 (feedback 2, rainbow 00:52): the levels side by side, one row per class of action, as a table the user
 * reads once. Each agent picks a level (or its own table) on its own page; nothing here is a setting.
 */
@Composable
fun SecurityPage() {
    val c = MiuixTheme.colorScheme
    val levels = Tokens.LEVELS.filter { it != com.kilon.agentvscreen.api.Policy.CUSTOM }
    MiuixPage(title = tr(S.settings_permission_levels)) {
        Card(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Spacer(Modifier.weight(1.4f))
                levels.forEach { lv -> Text(com.kilon.agentvscreen.api.Policy.levelTitle(lv), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
            com.kilon.agentvscreen.api.Policy.ROWS.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.title, fontSize = 13.sp, modifier = Modifier.weight(1.4f))
                    levels.forEach { lv ->
                        val a = com.kilon.agentvscreen.api.Policy.PRESETS.getValue(lv).getValue(row.key)
                        val tint = when (a) { com.kilon.agentvscreen.api.Policy.Act.ALLOW -> Palette.takeover; com.kilon.agentvscreen.api.Policy.Act.ASK -> c.primary; else -> c.onSurfaceVariantSummary }
                        Text(a.title, fontSize = 12.sp, color = tint, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        Footnote(tr(S.settings_pick_a_level_for_each))
        Group(tr(S.settings_confirmations), padded = true) {
            Text(H.autonomy, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}

/** 2.0.0-beta8 (K.5): Settings › Floating window — main toggle + mini-window behavior switches with "?" help. */
@Composable
fun FloatingWindowPage() {
    val s by BackgroundOperation.state.collectAsState()
    var help by remember { mutableStateOf(false) }

    MiuixPage(
        title = tr(S.settings_floating_window),
        actions = {
            LinkButton(onClick = { help = true }) { Text("?") }
        },
    ) {
        Group {
            SwitchPreference(
                checked = s.showPreview,
                onCheckedChange = { BackgroundOperation.setShowPreview(it); if (it) Overlay.refresh() },
                title = tr(S.settings_show_floating_window),
            )
            SwitchPreference(
                checked = s.miniShowControls,
                onCheckedChange = { BackgroundOperation.setMiniShowControls(it) },
                title = tr(S.settings_show_buttons_on_mini_window),
            )
            SwitchPreference(
                checked = s.miniTapToExpand,
                onCheckedChange = { BackgroundOperation.setMiniTapToExpand(it) },
                title = tr(S.settings_tap_mini_window_to_expand),
            )
        }
    }

    if (help) {
        GlassDialog(
            onDismissRequest = { help = false },
            title = { DialogTitle(tr(S.settings_about_floating_window)) },
            text = {
                Text(
                    tr(S.settings_how_it_opens_each_new),
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = { LinkButton(onClick = { help = false }) { Text(tr(S.connect_done)) } },
        )
    }
}

/** Settings › Thermal protection — live temperature/phase status and opt-out hard-stop switch with risk confirmation. */
@Composable
fun ThermalPage() {
    val hardStopOn by com.kilon.agentvscreen.host.ThermalGuard.hardStopEnabled.collectAsState()
    var snap by remember { mutableStateOf(com.kilon.agentvscreen.host.ThermalGuard.snapshot()) }
    var confirmOff by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(hardStopOn) {
        while (true) {
            try {
                snap = withContext(Dispatchers.IO) { com.kilon.agentvscreen.host.ThermalGuard.snapshot() }
                kotlinx.coroutines.delay(2_000L)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                kotlinx.coroutines.delay(2_000L)
            }
        }
    }

    val tempText = snap.tempTenths?.let { String.format(java.util.Locale.ROOT, "%.1f°C", it / 10.0) } ?: tr(S.settings_unknown)
    val statusText = com.kilon.agentvscreen.host.ThermalGuard.thermalStatusName(snap.status)
    val phaseText = when (snap.phase) {
        com.kilon.agentvscreen.host.HeatPolicy.Phase.NORMAL -> tr(S.settings_normal)
        com.kilon.agentvscreen.host.HeatPolicy.Phase.LOCKED_HOT -> tr(S.settings_locked_hot)
        com.kilon.agentvscreen.host.HeatPolicy.Phase.COOLING -> tr(S.settings_cooling)
        com.kilon.agentvscreen.host.HeatPolicy.Phase.READY -> tr(S.settings_ready_to_resume)
    }

    MiuixPage(
        title = tr(S.settings_thermal_protection),
        actions = {
            LinkButton(onClick = { help = true }) { Text("?") }
        },
    ) {
        Group(tr(S.settings_current_status)) {
            BasicComponent(
                title = tr(S.settings_battery_temperature),
                endActions = { Value(tempText) },
            )
            BasicComponent(
                title = tr(S.settings_thermal_status),
                endActions = { Value(statusText) },
            )
            BasicComponent(
                title = tr(S.settings_protection_state),
                summary = if (snap.phase != com.kilon.agentvscreen.host.HeatPolicy.Phase.NORMAL) {
                    com.kilon.agentvscreen.host.ThermalGuard.formatReason(snap)
                } else null,
                endActions = {
                    Value(
                        phaseText,
                        if (snap.phase == com.kilon.agentvscreen.host.HeatPolicy.Phase.LOCKED_HOT) Palette.stopped
                        else MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                },
            )
        }

        Group(tr(S.settings_control)) {
            SwitchPreference(
                checked = hardStopOn,
                onCheckedChange = { want ->
                    if (want) {
                        com.kilon.agentvscreen.host.ThermalGuard.setHardStopEnabled(true)
                        snap = com.kilon.agentvscreen.host.ThermalGuard.snapshot()
                    } else {
                        confirmOff = true
                    }
                },
                title = tr(S.settings_auto_stop_when_overheating),
            )
        }
    }

    if (confirmOff) {
        GlassDialog(
            onDismissRequest = { confirmOff = false },
            title = { DialogTitle(tr(S.settings_turn_off_overheat_auto_stop)) },
            text = {
                Text(
                    tr(S.settings_when_turned_off_long_running),
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                LinkButton(onClick = {
                    com.kilon.agentvscreen.host.ThermalGuard.setHardStopEnabled(false)
                    snap = com.kilon.agentvscreen.host.ThermalGuard.snapshot()
                    confirmOff = false
                }) { Text(tr(S.settings_turn_off), color = Palette.stopped) }
            },
            dismissButton = {
                LinkButton(onClick = { confirmOff = false }) { Text(tr(S.app_picker_cancel)) }
            },
        )
    }

    if (help) {
        GlassDialog(
            onDismissRequest = { help = false },
            title = { DialogTitle(tr(S.settings_about_thermal_protection)) },
            text = { Text(H.thermal, fontSize = 13.5.sp, lineHeight = 20.sp) },
            confirmButton = { LinkButton(onClick = { help = false }) { Text(tr(S.connect_done)) } },
        )
    }
}


