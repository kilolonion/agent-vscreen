package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.PublicOAuth
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.host.StrongConfirm
import com.kilon.agentvscreen.host.Tailnet
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 2.0.0-beta8 (A.3, B1, F.1, F.4, F.5): dedicated public MCP access page.
 *
 * - Unified header across checklist / running states (`resetScrollKey = enabled`).
 * - Prerequisite checklist checks all items at once, gives a one-tap remediation button for each
 *   missing item (preferring Tailscale `QueryFeature` URL, falling back to Admin DNS/ACL + copyable
 *   `nodeAttrs` snippet), and auto-rechecks on `ON_RESUME` and while `ShouldWait` is true.
 * - Risk consent is persisted by version (`PublicAccess.RISK_CONSENT_VERSION`) so it is asked once.
 * - `PUBLIC_ENABLE` strong confirmation runs only at the last tap once all checks pass.
 * - Running status distinguishes `starting`, `cert` ("正在申请 HTTPS 证书，首次约 30–60 秒"), `on`, and `error`.
 * - User-initiated pairing-code claim (`PublicPairingCodeCard`) + optional strict pairing window mode.
 */
@Composable
fun PublicAccessPage() {
    val ctx = LocalContext.current
    val actx = ctx.applicationContext
    val host = LocalHost.current
    val nav = LocalNav.current
    val ts by Tailnet.status.collectAsState()
    val enabled by PublicAccess.enabledFlow.collectAsState()
    val tokens by Tokens.list.collectAsState()
    var revokeConfirm by remember { mutableStateOf(false) }

    val publicClients = remember(tokens) { tokens.filter { it.publicOnly } }
    val channel by PublicAccess.channelFlow.collectAsState()
    val cfCfg by com.kilon.agentvscreen.host.Cloudflared.config.collectAsState()
    val ngCfg by com.kilon.agentvscreen.host.Ngrok.config.collectAsState()
    val mcpUrl = remember(ts.dnsName, channel, cfCfg, ngCfg) { PublicAccess.mcpUrl() }

    MiuixPage(
        title = tr(S.connect_cloud_public_mcp_experimental),
        subtitle = tr(S.public_access_experimental_for_cloud_mcp_clients, channel.title),
        resetScrollKey = enabled,
    ) {
        PublicChannelPicker()
        if (enabled) Footnote(tr(S.public_access_switching_channel_turns_the_current))
        if (enabled) {
            PublicRunningSection(
                ts = ts,
                mcpUrl = mcpUrl,
                onDisable = {
                    PublicAccess.disable(actx, "user")
                    host.say(tr(S.public_access_public_access_turned_off))
                },
            )
            Footnote(
                tr(S.public_access_turning_off_public_access_does),
            )
        } else {
            PublicChecklistSection(showEnableButton = true)
        }

        Group(tr(S.public_access_authorized_public_clients, publicClients.size), padded = true) {
            if (publicClients.isEmpty()) {
                Text(
                    tr(S.public_access_no_authorized_public_clients_yet),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                publicClients.forEach { t ->
                    ArrowPreference(
                        title = t.name,
                        summary = if (t.scope == "operate") tr(S.public_access_look_operate) else tr(S.public_access_look_only),
                        onClick = { nav.go(Route.Agent(t.id)) },
                    )
                }
            }
            SoftButton(
                onClick = {
                    if (!revokeConfirm) {
                        revokeConfirm = true
                    } else {
                        revokeConfirm = false
                        val n = PublicAccess.revokeAllPublicClients(actx)
                        host.say(tr(S.public_access_revoked_public_clients, n))
                    }
                },
                enabled = publicClients.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (revokeConfirm) tr(S.public_access_tap_again_to_revoke_all)
                    else tr(S.public_access_revoke_all_public_clients),
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 2.0.0-beta11: the public channels, each with its own guide (the public page and the connect wizard). */
@Composable
fun PublicChannelPicker() {
    val actx = LocalContext.current.applicationContext
    val nav = LocalNav.current
    val channel by PublicAccess.channelFlow.collectAsState()
    Group(tr(S.public_access_public_channel)) {
        top.yukonga.miuix.kmp.preference.RadioButtonPreference(
            title = PublicAccess.Channel.TAILSCALE.title,
            summary = tr(S.public_access_no_domain_needed_a_ts),
            selected = channel == PublicAccess.Channel.TAILSCALE,
            onClick = { PublicAccess.setChannel(actx, PublicAccess.Channel.TAILSCALE) },
        )
        top.yukonga.miuix.kmp.preference.RadioButtonPreference(
            title = PublicAccess.Channel.CLOUDFLARE.title,
            summary = tr(S.public_access_uses_your_own_domain_needs),
            selected = channel == PublicAccess.Channel.CLOUDFLARE,
            onClick = { PublicAccess.setChannel(actx, PublicAccess.Channel.CLOUDFLARE) },
        )
        top.yukonga.miuix.kmp.preference.RadioButtonPreference(
            title = PublicAccess.Channel.NGROK.title,
            summary = tr(S.public_access_no_domain_needed_free_accounts),
            selected = channel == PublicAccess.Channel.NGROK,
            onClick = { PublicAccess.setChannel(actx, PublicAccess.Channel.NGROK) },
        )
        ArrowPreference(title = tr(S.public_access_who_it_suits_and_common), summary = tr(S.public_access_the_setup_steps_are_right, channel.title),
            onClick = { nav.go(Route.PublicGuide(channel.key)) })
    }
}

/** Wrappable monospace box whose trailing copy button sits beside the text, never overlapping it (F.5). */
@Composable
fun WrappableCode(text: String, onCopy: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF16131F))
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Color(0xFFE8E4F2),
                softWrap = true,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(MiuixIcons.Copy, tr(S.connect_copy), tint = Color(0xFFE8E4F2))
        }
    }
}

/**
 * User-initiated pairing-code entry card plus optional strict pairing window control.
 * Shared by [PublicRunningSection] and Step 3 of [NewAgentPage].
 */
@Composable
fun PublicPairingCodeCard() {
    val ctx = LocalContext.current
    val actx = ctx.applicationContext
    val host = LocalHost.current
    val strictWindow by PublicAccess.strictPairingWindowFlow.collectAsState()
    val windowUntil by PublicAccess.pairingWindowUntilFlow.collectAsState()
    val now = rememberNow()
    var codeInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun submit(raw: String) {
        when (PublicOAuth.claimPairingByCode(actx, raw, openActivity = true)) {
            is PublicOAuth.ClaimResult.Opened -> {
                codeInput = ""
                errorMsg = null
            }
            PublicOAuth.ClaimResult.InvalidFormat -> {
                errorMsg = tr(S.public_access_pairing_code_must_be_8)
            }
            PublicOAuth.ClaimResult.NotFound -> {
                errorMsg = tr(S.public_access_no_waiting_pairing_request_matches)
            }
            PublicOAuth.ClaimResult.Expired -> {
                errorMsg = tr(S.public_access_this_pairing_request_expired_or)
            }
            PublicOAuth.ClaimResult.TooManyActive -> {
                errorMsg = tr(S.public_access_too_many_pairing_requests_are)
            }
            PublicOAuth.ClaimResult.NoLockscreen -> {
                errorMsg = tr(S.public_access_set_a_screen_lock_pin)
            }
        }
    }

    var byCode by remember { mutableStateOf(false) }
    Group(tr(S.public_access_pairing), padded = true) {
        // New clients pop up a confirmation card on their own; entering a code is only a fallback for a request that
        // stayed queued because several cards were already open.
        ArrowPreference(
            title = tr(S.public_access_find_a_request_by_its),
            endActions = { Value(if (byCode) tr(S.connect_hide) else tr(S.connect_show)) },
            onClick = { byCode = !byCode },
        )
        if (byCode) AvsField(
            value = codeInput,
            onValueChange = { codeInput = it; errorMsg = null },
            placeholder = tr(S.public_access_8_character_code_e_g),
        )
        if (byCode) errorMsg?.let { err ->
            Text(err, fontSize = 12.5.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.error)
        }
        if (byCode) Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SoftButton(
                onClick = {
                    val clip = runCatching {
                        ctx.getSystemService(android.content.ClipboardManager::class.java)
                            ?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    }.getOrNull()
                    if (clip.isNullOrBlank()) {
                        host.say(tr(S.public_access_clipboard_is_empty))
                    } else {
                        codeInput = clip.trim()
                        errorMsg = null
                    }
                },
            ) {
                Text(tr(S.public_access_paste), color = MiuixTheme.colorScheme.primary)
            }
            PrimaryButton(
                onClick = { submit(codeInput) },
                enabled = codeInput.isNotBlank(),
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.primary,
            ) {
                Text(tr(S.public_access_verify_confirm), color = MiuixTheme.colorScheme.onPrimary)
            }
        }
        SwitchRow(
            title = tr(S.public_access_only_allow_new_pairings_during),
            sub = tr(S.public_access_authorized_clients_are_not_affected),
            checked = strictWindow,
            onChange = { PublicAccess.setStrictPairingWindow(actx, it) },
        )
        if (strictWindow) {
            val leftMs = (windowUntil - now).coerceAtLeast(0L)
            if (leftMs > 0L) {
                val sec = (leftMs + 999L) / 1000L
                val fmt = "%d:%02d".format(java.util.Locale.ROOT, sec / 60, sec % 60)
                InfoCard(
                    Palette.takeover,
                    tr(S.public_access_pairing_window_open_left, fmt),
                    tr(S.public_access_new_clients_can_initiate_pairing),
                )
                SoftButton(
                    onClick = { PublicAccess.closePairingWindow() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr(S.public_access_close_window_now), color = MiuixTheme.colorScheme.error)
                }
            } else {
                SoftButton(
                    onClick = { PublicAccess.openPairingWindow() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr(S.public_access_open_pairing_window_3_min), color = MiuixTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Running status card + wrappable MCP URL + pairing-code claim + Turn Off button, shared by [PublicAccessPage] and [NewAgentPage]. */
@Composable
fun PublicRunningSection(
    ts: Tailnet.Status,
    mcpUrl: String?,
    onDisable: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val host = LocalHost.current
    fun openUrl(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    val channel by PublicAccess.channelFlow.collectAsState()
    Group(tr(S.public_access_status)) {
        val tunnel = channel.tunnel
        if (tunnel != null) TunnelRunning(tunnel, mcpUrl) else when (ts.funnel) {
            "on" -> {
                InfoCard(
                    Palette.takeover,
                    tr(S.public_access_public_access_is_on),
                    tr(S.public_access_https_certificate_is_ready_cloud),
                )
                if (mcpUrl != null) {
                    WrappableCode(mcpUrl) { host.copy(tr(S.public_access_mcp_url), mcpUrl) }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        PrimaryButton(
                            onClick = { host.copy(tr(S.public_access_mcp_url), mcpUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MiuixTheme.colorScheme.primary,
                        ) {
                            Text(tr(S.public_access_copy_mcp_url), color = MiuixTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
            "cert" -> {
                InfoCard(
                    Palette.standby,
                    tr(S.public_access_requesting_https_certificate),
                    tr(S.public_access_requesting_https_certificate_first_time) + (mcpUrl?.let { "\n$it" } ?: ""),
                )
            }
            "error" -> {
                val msg = PublicAccess.translateFunnelError(ts.funnelReason)
                InfoCard(
                    Palette.stopped,
                    msg?.title ?: tr(S.public_access_funnel_failed_to_start),
                    msg?.fix ?: ts.funnelReason.orEmpty(),
                )
                val raw = ts.funnelReason.orEmpty()
                val helpUrl = when {
                    raw.contains("HTTPS must be enabled") -> PublicAccess.ADMIN_DNS_URL
                    raw.contains("funnel") || raw.contains("443") -> PublicAccess.ADMIN_ACL_URL
                    else -> null
                }
                if (helpUrl != null) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        SoftButton(onClick = { openUrl(helpUrl) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr(S.public_access_open_tailscale_admin), color = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            }
            else -> {
                InfoCard(
                    Palette.standby,
                    tr(S.public_access_starting_tailscale_funnel),
                    (mcpUrl?.let { "$it\n" } ?: "") + tr(S.public_access_waiting_for_funnel_startup_and),
                )
            }
        }
        if (onDisable != null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                SoftButton(onClick = onDisable, modifier = Modifier.fillMaxWidth()) {
                    Text(tr(S.public_access_turn_off_public_access), color = MiuixTheme.colorScheme.error)
                }
            }
        }
    }
    PublicPairingCodeCard()
}

/**
 * Reusable B1 5-item prerequisite checklist + once-only versioned risk consent + `PUBLIC_ENABLE` trigger.
 * Used both in [PublicAccessPage] and Step 3 of [NewAgentPage] when "Cloud (Public MCP)" is chosen (F.2).
 */
@Composable
fun PublicChecklistSection(
    showEnableButton: Boolean = true,
    onChecklistEvaluated: ((PublicAccess.ChecklistSnapshot) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val actx = ctx.applicationContext
    val host = LocalHost.current
    val nav = LocalNav.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val ts by Tailnet.status.collectAsState()
    val funnelCheck by Tailnet.funnelCheck.collectAsState()
    val funnelChecking by Tailnet.funnelChecking.collectAsState()
    val consentAccepted by PublicAccess.riskConsentAcceptedFlow.collectAsState()

    var mode by remember { mutableStateOf(ApiConfig.tailnetMode) }
    var controlUrl by remember { mutableStateOf(ApiConfig.tailnetControlUrl) }
    var deviceSecure by remember { mutableStateOf(PublicAccess.isDeviceSecure(ctx)) }
    var showRiskDetail by remember(consentAccepted) { mutableStateOf(!consentAccepted) }
    var recheckTick by remember { mutableStateOf(0) }

    fun triggerRecheck() {
        mode = ApiConfig.tailnetMode
        controlUrl = ApiConfig.tailnetControlUrl
        deviceSecure = PublicAccess.isDeviceSecure(ctx)
        PublicAccess.recheckPrerequisites(mode, controlUrl, Tailnet.status.value)
        recheckTick++
    }

    // Auto-recheck whenever the user returns to the app from browser / system settings (B1).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                triggerRecheck()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Initial check and refresh whenever built-in Tailscale becomes logged in.
    LaunchedEffect(ts.loggedIn, mode, controlUrl) {
        mode = ApiConfig.tailnetMode
        controlUrl = ApiConfig.tailnetControlUrl
        deviceSecure = PublicAccess.isDeviceSecure(ctx)
        if (ts.loggedIn && mode == Tailnet.Mode.BUILTIN && controlUrl.isNullOrBlank()) {
            PublicAccess.recheckPrerequisites(mode, controlUrl, ts)
        }
    }

    val channel by PublicAccess.channelFlow.collectAsState()
    val cfCfg by com.kilon.agentvscreen.host.Cloudflared.config.collectAsState()
    val ngCfg by com.kilon.agentvscreen.host.Ngrok.config.collectAsState()
    val checklist = remember(mode, controlUrl, ts, funnelCheck, funnelChecking, deviceSecure, recheckTick, channel, cfCfg, ngCfg) {
        if (channel == PublicAccess.Channel.CLOUDFLARE) PublicAccess.cloudflareChecklist(cfCfg, deviceSecure)
        else if (channel == PublicAccess.Channel.NGROK) PublicAccess.ngrokChecklist(ngCfg, deviceSecure)
        else PublicAccess.evaluateChecklist(
            tailnetMode = mode,
            controlUrl = controlUrl,
            tsStatus = ts,
            funnelCheck = funnelCheck,
            funnelChecking = funnelChecking,
            deviceSecure = deviceSecure,
        )
    }

    LaunchedEffect(checklist) {
        onChecklistEvaluated?.invoke(checklist)
    }

    // Poll briefly when Tailscale QueryFeature returns ShouldWait (e.g. user clicked enable in browser).
    LaunchedEffect(checklist.shouldWait, ts.loggedIn, checklist.allOk) {
        if (checklist.shouldWait && ts.loggedIn && !checklist.allOk) {
            repeat(5) {
                delay(3_000)
                if (!PublicAccess.recheckPrerequisites()) return@LaunchedEffect
            }
        }
    }

    fun openUrl(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openLockScreenSettings() {
        val opened = runCatching {
            ctx.startActivity(Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (!opened) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    PublicChannelSteps(channel, here = true)
    Group(tr(S.public_access_prerequisites)) {
        checklist.items.forEach { item ->
            ChecklistRow(
                item = item,
                onAction = {
                    when (item.action) {
                        PublicAccess.CheckAction.OPEN_REMOTE -> nav.go(Route.Remote)
                        PublicAccess.CheckAction.OPEN_URL -> item.actionUrl?.let(::openUrl)
                        PublicAccess.CheckAction.OPEN_LOCKSCREEN -> openLockScreenSettings()
                        PublicAccess.CheckAction.RETRY_CHECK -> triggerRecheck()
                        PublicAccess.CheckAction.NONE -> {}
                    }
                },
                onCopySnippet = { snippet -> host.copy("nodeAttrs", snippet) },
            )
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SoftButton(onClick = { triggerRecheck() }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (checklist.checking) tr(S.public_access_checking) else tr(S.public_access_recheck),
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }

    Group(tr(S.public_access_risk_warning), padded = true) {
        if (consentAccepted && !showRiskDetail) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    tr(S.public_access_public_access_risks_acknowledged),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.takeover,
                )
                LinkButton(onClick = { showRiskDetail = true }) {
                    Text(tr(S.public_access_view))
                }
            }
        } else {
            val warnings = listOf(
                tr(S.public_access_1_once_enabled_your_phone),
                tr(S.public_access_2_authorized_cloud_clients_store),
                tr(S.public_access_3_text_on_the_screen),
                tr(S.public_access_4_pairing_defaults_to_easy),
            )
            val shown = when (channel) {
                PublicAccess.Channel.CLOUDFLARE -> warnings + tr(S.public_access_5_with_cloudflare_https_is)
                PublicAccess.Channel.NGROK -> warnings + tr(S.public_access_5_with_ngrok_https_is)
                PublicAccess.Channel.TAILSCALE -> warnings
            }
            shown.forEach { w ->
                Text(w, fontSize = 13.5.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurface)
            }
            if (!consentAccepted) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { PublicAccess.acceptRiskConsent(actx) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        state = ToggleableState(consentAccepted),
                        onClick = { PublicAccess.acceptRiskConsent(actx) },
                    )
                    Text(
                        tr(S.public_access_i_understand_these_risks_remember),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    LinkButton(onClick = { showRiskDetail = false }) {
                        Text(tr(S.connect_hide))
                    }
                }
            }
        }

        if (showEnableButton) {
            val canEnable = PublicAccess.canEnable(checklist, consentAccepted)
            PrimaryButton(
                onClick = {
                    val req = StrongConfirm.createPublicEnableRequest(
                        ctx = actx,
                        onApprove = { PublicAccess.enable(actx) },
                    )
                    StrongConfirmActivity.open(actx, req.id)
                },
                enabled = canEnable,
                modifier = Modifier.fillMaxWidth(),
                color = MiuixTheme.colorScheme.primary,
            ) {
                Text(tr(S.public_access_turn_on_public_access), color = MiuixTheme.colorScheme.onPrimary)
            }
            if (!checklist.allOk) {
                Text(
                    tr(S.public_access_complete_all_items_above_first, checklist.items.size),
                    fontSize = 12.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    item: PublicAccess.CheckItem,
    onAction: () -> Unit,
    onCopySnippet: (String) -> Unit,
) {
    val (glyph, glyphColor) = when (item.state) {
        PublicAccess.CheckState.OK -> "✓" to Palette.takeover
        PublicAccess.CheckState.FAIL -> "✗" to Palette.stopped
        PublicAccess.CheckState.CHECKING -> "…" to Palette.standby
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(glyph, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = glyphColor)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (item.detail.isNotBlank()) {
                    Text(
                        item.detail,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (item.action != PublicAccess.CheckAction.NONE && item.actionLabel.isNotBlank()) {
                SoftButton(onClick = onAction) {
                    Text(item.actionLabel, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                }
            }
        }
        if (item.copySnippet != null) {
            if (!item.copyGuide.isNullOrBlank()) {
                Text(
                    item.copyGuide,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            WrappableCode(item.copySnippet) { onCopySnippet(item.copySnippet) }
        }
    }
}
