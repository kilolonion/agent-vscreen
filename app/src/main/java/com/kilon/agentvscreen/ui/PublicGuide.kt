package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import com.kilon.agentvscreen.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.host.Cloudflared
import com.kilon.agentvscreen.host.Ngrok
import com.kilon.agentvscreen.host.PublicTunnel
import com.kilon.agentvscreen.host.TunnelSelfTest
import com.kilon.agentvscreen.host.TunnelState
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.tr
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 2.0.0-beta11 (rainbow: support Cloudflare, with detailed, friendly guidance for it and the current Tailscale). One
 * guide page per public channel: who it suits, what to prepare, numbered steps each with the button or the text to
 * copy that it needs, and the common problems with their fixes. The pages of the third-party dashboards are named
 * as they read today; the guide says so, because those names change.
 */
@Composable
fun PublicGuidePage(channelKey: String) {
    val channel = PublicAccess.Channel.of(channelKey)
    MiuixPage(
        title = tr(S.public_guide_setup_guide),
        subtitle = channel.title,
    ) {
        when (channel) {
            PublicAccess.Channel.CLOUDFLARE -> CloudflareGuide()
            PublicAccess.Channel.NGROK -> NgrokGuide()
            PublicAccess.Channel.TAILSCALE -> TailscaleGuide()
        }
    }
}

private data class Btn(val label: String, val run: () -> Unit)

/** An annotated screenshot of the third-party page a step happens on; the numbers drawn on it are explained in [caption]. */
private data class Shot(@DrawableRes val res: Int, val caption: String)

/**
 * 2.0.0-beta13 (rainbow: the text alone did not get them to the right page): a step can carry an annotated
 * screenshot of the page it happens on. Tapping it opens it full screen, where it can be pinched to zoom.
 */
@Composable
private fun GuideImage(shot: Shot) {
    var big by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(painterResource(shot.res), contentDescription = shot.caption, contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .border(1.dp, MiuixTheme.colorScheme.dividerLine, RoundedCornerShape(10.dp)).clickable { big = true })
        Text(shot.caption + tr(S.public_guide_tap_to_enlarge), fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    if (big) Dialog(onDismissRequest = { big = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable { big = false }
            .pointerInput(Unit) { detectTransformGestures { _, p, z, _ -> scale = (scale * z).coerceIn(1f, 5f); pan = if (scale == 1f) Offset.Zero else pan + p } },
            contentAlignment = Alignment.Center) {
            Image(painterResource(shot.res), contentDescription = shot.caption, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(list: List<Btn>) {
    FlowRow(Modifier.fillMaxWidth().padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { b -> SoftButton(onClick = b.run) { Text(b.label, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) } }
    }
}

/**
 * One numbered step. [buttons] open the exact page the step happens on (a deep link into the dashboard where the
 * service has one) and [docs] the official documentation section for it, so no step has to be found from words alone.
 */
@Composable
private fun Step(n: Int, title: String, body: String, code: String? = null, codeLabel: String = "", buttons: List<Btn> = emptyList(),
                 field: (@Composable () -> Unit)? = null, after: List<Btn> = emptyList(), shots: List<Shot> = emptyList(), docs: String? = null) {
    val open = openUrl()
    val host = LocalHost.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Text("$n", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        shots.forEach { s -> Box(Modifier.fillMaxWidth().padding(start = 36.dp)) { GuideImage(s) } }
        if (code != null) WrappableCode(code) { host.copy(codeLabel, code) }
        val all = buttons + listOfNotNull(docs?.let { url -> Btn(tr(S.public_guide_official_docs)) { open(url) } })
        if (all.isNotEmpty()) Buttons(all)
        field?.let { Box(Modifier.fillMaxWidth().padding(start = 36.dp)) { it() } }
        if (after.isNotEmpty()) Buttons(after)
    }
}

/**
 * 2.0.0-beta12 (rainbow: fill in while following the guide, instead of leaving it for each value): the value a step
 * asks for is entered right in that step. Paste reads the clipboard (the user copies it in the browser and comes
 * back); a value saves on its own, so the steps can be done in any order and the guide never has to be left.
 */
@Composable
private fun GuideField(label: String, saved: String?, onSave: (String) -> String?) {
    val ctx = LocalContext.current
    val host = LocalHost.current
    var v by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    fun save(text: String) {
        problem = onSave(text)
        if (problem == null) { v = ""; host.say(tr(S.public_guide_saved)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (saved != null) Text("✓ " + saved, fontSize = 13.sp, color = Palette.takeover)
        AvsField(value = v, onValueChange = { v = it; problem = null }, placeholder = label)
        problem?.let { Text(it, fontSize = 12.5.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton(onClick = {
                val clip = runCatching { ctx.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
                if (clip.isNullOrBlank()) host.say(tr(S.public_guide_the_clipboard_is_empty_press)) else save(clip)
            }) { Text(tr(S.public_guide_paste_and_save), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) }
            if (v.isNotBlank()) SoftButton(onClick = { save(v) }) { Text(tr(S.automation_save), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) }
        }
    }
}

/** The last step's way back to the public page, where turning on needs the risk note and a fingerprint. */
@Composable
private fun backToPublic(): Btn {
    val nav = LocalNav.current
    return Btn(tr(S.public_guide_back_to_turn_it_on)) { nav.back() }
}

@Composable
private fun Problem(title: String, fix: String) {
    BasicComponent(title = title, summary = fix)
}

@Composable
private fun openUrl(): (String) -> Unit {
    val ctx = LocalContext.current
    return { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/**
 * The numbered steps of a channel, in one place for both the guide page and the public page (2.0.0-beta13, rainbow:
 * people missed the guide entry and were left with bare fields). [here] is true on the public page itself: the
 * fields live in their steps and the last step points down at the checklist instead of back to the page.
 */
@Composable
fun PublicChannelSteps(channel: PublicAccess.Channel, here: Boolean) {
    when (channel) {
        PublicAccess.Channel.CLOUDFLARE -> CloudflareSteps(here)
        PublicAccess.Channel.NGROK -> NgrokSteps(here)
        PublicAccess.Channel.TAILSCALE -> TailscaleSteps(here)
    }
}

/** Where turning on happens, worded for the page the steps are shown on. */
private fun turnOnWhere(here: Boolean) =
    if (here) tr(S.public_guide_further_down_this_page) else tr(S.public_guide_then_go_back_to_the)

@Composable
private fun TailscaleGuide() {
    Group(tr(S.public_guide_who_it_suits), padded = true) {
        Text(tr(S.public_guide_you_have_no_domain_and),
            fontSize = 13.5.sp, lineHeight = 20.sp)
    }
    TailscaleSteps(here = false)
    Group(tr(S.public_guide_common_problems)) {
        Problem(tr(S.public_guide_checklist_says_https_is_off), tr(S.public_guide_do_step_2_returning_to))
        Problem(tr(S.public_guide_funnel_not_allowed_or_port), tr(S.public_guide_do_step_3_after_changing))
        Problem(tr(S.public_guide_stuck_requesting_the_certificate), tr(S.public_guide_the_first_one_can_take))
        Problem(tr(S.public_guide_using_headscale), tr(S.public_guide_headscale_has_no_funnel_use))
    }
    Footnote(tr(S.public_guide_menu_names_in_the_tailscale))
}

@Composable
private fun TailscaleSteps(here: Boolean) {
    val nav = LocalNav.current
    val open = openUrl()
    Group(tr(S.public_guide_steps)) {
        Step(1, tr(S.public_guide_sign_in_to_the_built),
            tr(S.public_guide_in_connect_other_devices_tailscale),
            buttons = listOf(Btn(tr(S.public_guide_open_tailscale_settings)) { nav.go(Route.Remote) }))
        Step(2, tr(S.public_guide_turn_on_https_certificates),
            tr(S.public_guide_open_dns_settings_goes_straight),
            buttons = listOf(Btn(tr(S.public_guide_open_dns_settings)) { open(PublicAccess.ADMIN_DNS_URL) }),
            docs = PublicAccess.DOCS_HTTPS_URL)
        Step(3, tr(S.public_guide_allow_funnel_for_this_phone),
            tr(S.public_guide_the_checklist_below_usually_offers),
            code = PublicAccess.FUNNEL_NODE_ATTRS_SNIPPET, codeLabel = "nodeAttrs",
            buttons = listOf(Btn(tr(S.public_guide_open_access_controls)) { open(PublicAccess.ADMIN_ACL_URL) }),
            docs = PublicAccess.DOCS_FUNNEL_URL)
        Step(4, tr(S.public_access_turn_on_public_access),
            turnOnWhere(here) + tr(S.public_guide_the_checklist_rechecks_by_itself),
            buttons = if (here) emptyList() else listOf(backToPublic()))
        Step(5, tr(S.public_guide_give_the_address_to_the),
            tr(S.public_guide_copy_the_mcp_address_and))
    }
}

@Composable
private fun CloudflareGuide() {
    Group(tr(S.public_guide_who_it_suits), padded = true) {
        Text(tr(S.public_guide_you_have_a_domain_or),
            fontSize = 13.5.sp, lineHeight = 20.sp)
        Text(tr(S.public_guide_note_cloudflare_decrypts_https_on),
            fontSize = 12.5.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    CloudflareSteps(here = false)
    Group(tr(S.public_guide_common_problems)) {
        Problem(tr(S.public_guide_can_t_find_where_to), tr(S.public_guide_in_the_tunnel_list_press))
        Problem(tr(S.public_guide_token_not_accepted), tr(S.public_guide_copy_the_whole_command_again))
        Problem(tr(S.public_guide_self_test_hostname_does_not), tr(S.public_guide_the_route_from_step_4))
        Problem(tr(S.public_guide_self_test_error_1033_tunnel), tr(S.public_guide_no_connector_is_online_for))
        Problem(tr(S.public_guide_self_test_502_cannot_reach), tr(S.public_guide_the_route_s_service_is, Cloudflared.ORIGIN_URL))
        Problem(tr(S.public_guide_self_test_certificate_error), tr(S.public_guide_a_new_hostname_s_certificate))
        Problem(tr(S.public_guide_self_test_redirected_or_blocked), tr(S.public_guide_don_t_put_a_cloudflare))
        Problem(tr(S.public_guide_never_connects_to_cloudflare), tr(S.public_guide_the_tunnel_uses_port_7844))
    }
    Footnote(tr(S.public_guide_the_token_is_the_key))
}

@Composable
private fun CloudflareSteps(here: Boolean) {
    val open = openUrl()
    val cf by Cloudflared.config.collectAsState()
    val tunnels = Btn(tr(S.public_guide_open_the_tunnel_list)) { open(Cloudflared.TUNNELS_URL) }
    Group(tr(S.public_guide_steps)) {
        Step(1, tr(S.public_guide_create_a_cloudflare_account_and),
            tr(S.public_guide_add_your_domain_to_cloudflare),
            buttons = listOf(Btn(tr(S.public_guide_open_cloudflare)) { open(Cloudflared.ADD_SITE_URL) }))
        Step(2, tr(S.public_guide_create_a_tunnel),
            tr(S.public_guide_open_the_tunnel_list_goes),
            shots = listOf(Shot(R.drawable.guide_cf_not_routes, tr(S.public_guide_not_here_the_left_menu))),
            buttons = listOf(tunnels), docs = Cloudflared.DOCS_URL)
        Step(3, tr(S.public_guide_copy_the_token_into_this),
            tr(S.public_guide_next_comes_install_and_run),
            shots = listOf(Shot(R.drawable.guide_cf_install, tr(S.public_guide_copy_the_whole_command_spinning))),
            field = { GuideField(tr(S.public_guide_tunnel_token_or_the_whole), cf.tunnelId?.let { tr(S.public_guide_tunnel_token_saved) }) { Cloudflared.save(it, null) } })
        Step(4, tr(S.public_guide_add_the_public_hostname_pointing),
            tr(S.public_guide_open_the_tunnel_list_press, Cloudflared.ORIGIN_PORT),
            code = Cloudflared.ORIGIN_URL, codeLabel = tr(S.public_guide_service_url),
            shots = listOf(
                Shot(R.drawable.guide_cf_route_tab, tr(S.public_guide_the_tunnel_s_routes_tab)),
                Shot(R.drawable.guide_cf_route_type, tr(S.public_guide_pick_published_application)),
                Shot(R.drawable.guide_cf_route_form, tr(S.public_guide_subdomain_your_domain_service_url, Cloudflared.ORIGIN_URL))),
            buttons = listOf(tunnels), docs = Cloudflared.DOCS_ROUTES_URL)
        Step(5, tr(S.public_guide_enter_the_hostname_and_turn),
            tr(S.public_guide_enter_the_full_hostname_below) +
                turnOnWhere(here) + tr(S.public_guide_when_the_checklist_is_all),
            field = { GuideField(tr(S.public_guide_public_hostname_e_g_phone), cf.hostname) { Cloudflared.save(null, it) } },
            after = if (here) emptyList() else listOf(backToPublic()))
        Step(6, tr(S.public_guide_wait_for_two_green_rows),
            tr(S.public_guide_when_tunnel_connection_shows_connected))
    }
}

/** Live state of the tunnel and the self-test, while a tunnel channel (Cloudflare, ngrok) is on. */
@Composable
fun TunnelRunning(t: PublicTunnel, mcpUrl: String?) {
    val host = LocalHost.current
    val st by t.status.collectAsState()
    val test by t.selfTest.collectAsState()
    var raw by remember { mutableStateOf(false) }
    val p = t.provider
    val where = listOfNotNull(if (st.connections > 1) tr(S.public_guide_connections, st.connections) else null, st.location).joinToString(" · ")
    val (title, color) = when (st.state) {
        TunnelState.CONNECTED -> tr(S.public_guide_connected_to, p) + (if (where.isNotEmpty()) tr(S.public_guide_text, where) else "") to Palette.takeover
        TunnelState.STARTING -> tr(S.public_guide_connecting_to, p) to Palette.standby
        TunnelState.ERROR -> tr(S.public_guide_not_connected_to, p) to Palette.stopped
        TunnelState.OFF -> tr(S.public_guide_not_running) to Palette.stopped
    }
    BasicComponent(title = tr(S.public_guide_tunnel_connection), summary = title + (st.problem?.let { "\n$it" } ?: ""), endActions = { Dot(color, 8.dp) },
        onClick = if (st.raw != null) ({ raw = !raw }) else null)
    if (raw && st.raw != null) WrappableCode(st.raw!!) { host.copy(tr(S.connect_details), st.raw!!) }
    val (tTitle, tColor) = when (val r = test) {
        null -> tr(S.public_guide_runs_once_connected) to MiuixTheme.colorScheme.onSurfaceVariantSummary
        TunnelSelfTest.Running -> tr(S.public_guide_reaching_this_phone_from_the) to Palette.standby
        TunnelSelfTest.Ok -> tr(S.public_guide_passed_the_internet_reaches_this) to Palette.takeover
        is TunnelSelfTest.Failed -> "${r.title}\n${r.fix}" to Palette.stopped
    }
    BasicComponent(title = tr(S.public_guide_public_address_self_test), summary = tTitle, endActions = { Dot(tColor, 8.dp) })
    if (st.state == TunnelState.CONNECTED) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        SoftButton(onClick = { Thread({ t.runSelfTest() }, "vscreen-tunnel-test").apply { isDaemon = true }.start() }, modifier = Modifier.fillMaxWidth()) {
            Text(tr(S.public_guide_run_the_self_test_again), color = MiuixTheme.colorScheme.primary)
        }
    }
    if (mcpUrl != null && test == TunnelSelfTest.Ok) {
        WrappableCode(mcpUrl) { host.copy(tr(S.public_access_mcp_url), mcpUrl) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            PrimaryButton(onClick = { host.copy(tr(S.public_access_mcp_url), mcpUrl) }, modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.primary) {
                Text(tr(S.public_access_copy_mcp_url), color = MiuixTheme.colorScheme.onPrimary)
            }
        }
    } else if (mcpUrl != null) {
        Footnote(tr(S.public_guide_address_give_it_to_the, mcpUrl))
    }
}

@Composable
private fun NgrokGuide() {
    Group(tr(S.public_guide_who_it_suits), padded = true) {
        Text(tr(S.public_guide_you_have_no_domain_don),
            fontSize = 13.5.sp, lineHeight = 20.sp)
        Text(tr(S.public_guide_note_ngrok_decrypts_https_on),
            fontSize = 12.5.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    NgrokSteps(here = false)
    Group(tr(S.public_guide_common_problems)) {
        Problem(tr(S.public_guide_authtoken_not_accepted_err_ngrok), tr(S.public_guide_copy_it_again_from_your))
        Problem(tr(S.public_guide_an_ngrok_client_is_already), tr(S.public_guide_free_accounts_allow_one_ngrok))
        Problem(tr(S.public_guide_domain_not_on_this_account), tr(S.public_guide_the_domain_must_be_the))
        Problem(tr(S.public_guide_self_test_address_offline_err), tr(S.public_guide_wait_for_tunnel_connection_to))
        Problem(tr(S.public_guide_never_connects_to_ngrok), tr(S.public_guide_with_a_proxy_vpn_let))
    }
    Footnote(tr(S.public_guide_the_authtoken_is_the_key))
}

@Composable
private fun NgrokSteps(here: Boolean) {
    val open = openUrl()
    val ngrok by Ngrok.config.collectAsState()
    Group(tr(S.public_guide_steps)) {
        Step(1, tr(S.public_guide_sign_up_for_ngrok),
            tr(S.public_guide_sign_up_with_email_github),
            buttons = listOf(Btn(tr(S.public_guide_open_ngrok_sign_up)) { open(Ngrok.SIGNUP_URL) }), docs = Ngrok.DOCS_URL)
        Step(2, tr(S.public_guide_copy_your_authtoken),
            tr(S.public_guide_open_your_authtoken_goes_straight),
            buttons = listOf(Btn(tr(S.public_guide_open_your_authtoken)) { open(Ngrok.TOKEN_URL) }),
            field = { GuideField(tr(S.public_guide_authtoken), ngrok.token?.let { tr(S.public_guide_authtoken_saved) }) { Ngrok.save(it, null) } })
        Step(3, tr(S.public_guide_copy_your_static_domain),
            tr(S.public_guide_open_domains_goes_straight_to),
            buttons = listOf(Btn(tr(S.public_guide_open_domains)) { open(Ngrok.DOMAINS_URL) }),
            field = { GuideField(tr(S.public_guide_static_domain_e_g_name), ngrok.domain) { Ngrok.save(null, it) } })
        Step(4, tr(S.public_guide_turn_it_on),
            tr(S.public_guide_with_both_saved_above) + turnOnWhere(here) + tr(S.public_guide_when_the_checklist_is_all_2),
            buttons = if (here) emptyList() else listOf(backToPublic()))
        Step(5, tr(S.public_guide_wait_for_two_green_rows),
            tr(S.public_guide_when_tunnel_connection_shows_connected_2))
    }
}
