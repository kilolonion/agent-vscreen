package com.kilon.agentvscreen.ui

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
import top.yukonga.miuix.kmp.basic.TextField
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
        title = tr("云端（公网 MCP · 实验性）", "Cloud (Public MCP · Experimental)"),
        subtitle = tr("实验性功能 · 供云端 MCP 客户端连接 · ${channel.title}", "Experimental · For cloud MCP clients · ${channel.title}"),
        resetScrollKey = enabled,
    ) {
        PublicChannelPicker()
        if (enabled) Footnote(tr("切换通道会先关闭当前的公网接入；已授权的客户端需要用新地址重新配对。", "Switching channel turns the current public access off; authorized clients need to pair again with the new address."))
        if (enabled) {
            PublicRunningSection(
                ts = ts,
                mcpUrl = mcpUrl,
                onDisable = {
                    PublicAccess.disable(actx, "user")
                    host.say(tr("已关闭公网接入", "Public access turned off"))
                },
            )
            Footnote(
                tr(
                    "关闭公网接入不会撤销已授权的客户端；要彻底断开请在下方撤销全部公网客户端。",
                    "Turning off public access does not revoke authorized clients; revoke them below if needed.",
                ),
            )
        } else {
            PublicChecklistSection(showEnableButton = true)
        }

        Group(tr("已授权的公网客户端 · ${publicClients.size}", "Authorized public clients · ${publicClients.size}"), padded = true) {
            if (publicClients.isEmpty()) {
                Text(
                    tr("还没有已授权的公网客户端。", "No authorized public clients yet."),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                publicClients.forEach { t ->
                    ArrowPreference(
                        title = t.name,
                        summary = if (t.scope == "operate") tr("看和操作", "Look & operate") else tr("只看", "Look only"),
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
                        host.say(tr("已撤销 $n 个公网客户端", "Revoked $n public clients"))
                    }
                },
                enabled = publicClients.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (revokeConfirm) tr("再点一次确认撤销全部公网客户端", "Tap again to revoke all public clients")
                    else tr("撤销全部公网客户端", "Revoke all public clients"),
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
    Group(tr("公网通道", "Public channel")) {
        top.yukonga.miuix.kmp.preference.RadioButtonPreference(
            title = PublicAccess.Channel.TAILSCALE.title,
            summary = tr("无需域名；地址为 *.ts.net；需要官方 Tailscale 账号", "No domain needed; a *.ts.net address; needs an official Tailscale account"),
            selected = channel == PublicAccess.Channel.TAILSCALE,
            onClick = { PublicAccess.setChannel(actx, PublicAccess.Channel.TAILSCALE) },
        )
        top.yukonga.miuix.kmp.preference.RadioButtonPreference(
            title = PublicAccess.Channel.CLOUDFLARE.title,
            summary = tr("使用你自己的域名；需要 Cloudflare 账号；也适用于 Headscale", "Uses your own domain; needs a Cloudflare account; also works with Headscale"),
            selected = channel == PublicAccess.Channel.CLOUDFLARE,
            onClick = { PublicAccess.setChannel(actx, PublicAccess.Channel.CLOUDFLARE) },
        )
        top.yukonga.miuix.kmp.preference.RadioButtonPreference(
            title = PublicAccess.Channel.NGROK.title,
            summary = tr("无需域名；免费账号送一个固定域名；需要 ngrok 账号；也适用于 Headscale", "No domain needed; free accounts get one static domain; needs an ngrok account; also works with Headscale"),
            selected = channel == PublicAccess.Channel.NGROK,
            onClick = { PublicAccess.setChannel(actx, PublicAccess.Channel.NGROK) },
        )
        ArrowPreference(title = tr("适合谁和常见问题", "Who it suits and common problems"), summary = tr("${channel.title}：开通步骤就在下面", "${channel.title}: the setup steps are right below"),
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
            Icon(MiuixIcons.Copy, tr("复制", "Copy"), tint = Color(0xFFE8E4F2))
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
                errorMsg = tr("配对码应为 8 位字母或数字（如 ABCD-EFGH，不含 0/O/1/I）。", "Pairing code must be 8 letters/digits (e.g. ABCD-EFGH, without 0/O/1/I).")
            }
            PublicOAuth.ClaimResult.NotFound -> {
                errorMsg = tr("没有找到对应的待配对请求，请核对配对码是否输错。", "No waiting pairing request matches this code. Please double-check it.")
            }
            PublicOAuth.ClaimResult.Expired -> {
                errorMsg = tr("这条配对请求已过期或已处理，请在客户端重新发起连接。", "This pairing request expired or was already handled. Start pairing again from the client.")
            }
            PublicOAuth.ClaimResult.TooManyActive -> {
                errorMsg = tr("当前有太多待确认的配对卡片，请先处理“等你处理”里的请求。", "Too many pairing cards are open right now. Handle existing requests in Needs you first.")
            }
            PublicOAuth.ClaimResult.NoLockscreen -> {
                errorMsg = tr("手机未设置锁屏密码或生物识别，无法批准公网配对。", "Set a screen lock PIN or biometric on the phone before approving public pairings.")
            }
        }
    }

    var byCode by remember { mutableStateOf(false) }
    Group(tr("配对", "Pairing"), padded = true) {
        // New clients pop up a confirmation card on their own; entering a code is only a fallback for a request that
        // stayed queued because several cards were already open.
        ArrowPreference(
            title = tr("用配对码找回请求", "Find a request by its code"),
            endActions = { Value(if (byCode) tr("收起", "Hide") else tr("展开", "Show")) },
            onClick = { byCode = !byCode },
        )
        if (byCode) TextField(
            value = codeInput,
            onValueChange = { codeInput = it; errorMsg = null },
            label = tr("8 位配对码（如 ABCD-EFGH）", "8-character pairing code (e.g. ABCD-EFGH)"),
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
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
                        host.say(tr("剪贴板是空的", "Clipboard is empty"))
                    } else {
                        codeInput = clip.trim()
                        errorMsg = null
                    }
                },
            ) {
                Text(tr("粘贴", "Paste"), color = MiuixTheme.colorScheme.primary)
            }
            PrimaryButton(
                onClick = { submit(codeInput) },
                enabled = codeInput.isNotBlank(),
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.primary,
            ) {
                Text(tr("核对并确认", "Verify & confirm"), color = MiuixTheme.colorScheme.onPrimary)
            }
        }
        SwitchRow(
            title = tr("仅在手动开启窗口时允许新配对", "Only allow new pairings during a manual window"),
            sub = tr("已授权的客户端不受影响", "Authorized clients are not affected"),
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
                    tr("配对窗口已开启 · 剩余 $fmt", "Pairing window open · $fmt left"),
                    tr("现在可以发起新客户端配对；批准后会自动关闭。", "New clients can initiate pairing now; the window closes automatically after approval."),
                )
                SoftButton(
                    onClick = { PublicAccess.closePairingWindow() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("立即关闭窗口", "Close window now"), color = MiuixTheme.colorScheme.error)
                }
            } else {
                SoftButton(
                    onClick = { PublicAccess.openPairingWindow() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("开启配对窗口（3 分钟）", "Open pairing window (3 min)"), color = MiuixTheme.colorScheme.primary)
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
    Group(tr("运行状态", "Status")) {
        val tunnel = channel.tunnel
        if (tunnel != null) TunnelRunning(tunnel, mcpUrl) else when (ts.funnel) {
            "on" -> {
                InfoCard(
                    Palette.takeover,
                    tr("公网接入已开启", "Public access is on"),
                    tr(
                        "HTTPS 证书已就绪，云端 MCP 客户端可连接下方地址。24 小时无已认证请求自动关闭，App 更新后恢复关闭。",
                        "HTTPS certificate is ready. Cloud MCP clients can connect below. Auto-closes after 24 h with no authenticated requests and resets to off after app updates.",
                    ),
                )
                if (mcpUrl != null) {
                    WrappableCode(mcpUrl) { host.copy(tr("MCP 地址", "MCP URL"), mcpUrl) }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        PrimaryButton(
                            onClick = { host.copy(tr("MCP 地址", "MCP URL"), mcpUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MiuixTheme.colorScheme.primary,
                        ) {
                            Text(tr("复制 MCP 地址", "Copy MCP URL"), color = MiuixTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
            "cert" -> {
                InfoCard(
                    Palette.standby,
                    tr("正在申请 HTTPS 证书", "Requesting HTTPS certificate"),
                    tr(
                        "正在申请 HTTPS 证书，首次约 30–60 秒。",
                        "Requesting HTTPS certificate; first time takes ~30–60 seconds.",
                    ) + (mcpUrl?.let { "\n$it" } ?: ""),
                )
            }
            "error" -> {
                val msg = PublicAccess.translateFunnelError(ts.funnelReason)
                InfoCard(
                    Palette.stopped,
                    msg?.title ?: tr("Funnel 启动失败", "Funnel failed to start"),
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
                            Text(tr("打开 Tailscale 管理页", "Open Tailscale Admin"), color = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            }
            else -> {
                InfoCard(
                    Palette.standby,
                    tr("正在启动 Tailscale Funnel…", "Starting Tailscale Funnel…"),
                    (mcpUrl?.let { "$it\n" } ?: "") + tr(
                        "正在等待 Funnel 启动并申请 HTTPS 证书。",
                        "Waiting for Funnel startup and HTTPS certificate.",
                    ),
                )
            }
        }
        if (onDisable != null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                SoftButton(onClick = onDisable, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("关闭公网接入", "Turn off public access"), color = MiuixTheme.colorScheme.error)
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
    Group(tr("开启前提清单", "Prerequisites")) {
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
                    if (checklist.checking) tr("正在检查…", "Checking…") else tr("重新检查", "Recheck"),
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }

    Group(tr("风险说明", "Risk warning"), padded = true) {
        if (consentAccepted && !showRiskDetail) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    tr("✓ 已了解并同意公网风险说明", "✓ Public access risks acknowledged"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.takeover,
                )
                LinkButton(onClick = { showRiskDetail = true }) {
                    Text(tr("查看", "View"))
                }
            }
        } else {
            val warnings = listOf(
                tr(
                    "1. 开启后，你的手机会有一个任何人都能访问的公网地址。这个地址会出现在公开的证书日志里，可能被扫描到。没有你在手机上确认，谁也连不进来。",
                    "1. Once enabled, your phone will have a public address reachable by anyone. This address appears in public certificate logs and may be scanned. Nobody can connect without your confirmation on the phone.",
                ),
                tr(
                    "2. 你授权的云端客户端会保存访问凭证。如果那个服务被入侵，对方可以在凭证有效期内操作你的手机。不用时请关闭或撤销。",
                    "2. Authorized cloud clients store access credentials. If that service is compromised, an attacker could operate your phone while those credentials remain valid. Turn off or revoke when not in use.",
                ),
                tr(
                    "3. 屏幕上的文字可能是别人写给 agent 的恶意指令（比如网页、消息里的内容）。付款、发送、安装等操作仍会要求你在手机上确认。",
                    "3. Text on the screen may contain malicious instructions written for the agent (such as content in web pages or messages). Payments, sending messages, installs, and similar actions will still require your confirmation on the phone.",
                ),
                tr(
                    "4. 配对时默认“省心”档（付款、发送等仍要确认），批准前可以改；“系统”权限永远不会开放到公网。",
                    "4. Pairing defaults to \"Easy\" (payments, sending and similar still ask) and can be changed before approving; \"system\" is never exposed to the public internet.",
                ),
            )
            val shown = when (channel) {
                PublicAccess.Channel.CLOUDFLARE -> warnings + tr(
                    "5. 走 Cloudflare 时，HTTPS 在 Cloudflare 的服务器上解开再转发到手机，Cloudflare 技术上看得到请求内容；隧道令牌只保存在本机，泄露后请在 Cloudflare 刷新。",
                    "5. With Cloudflare, HTTPS is decrypted on Cloudflare's servers and forwarded to the phone, so Cloudflare can technically see requests; the tunnel token stays on this phone, refresh it in Cloudflare if it leaks.",
                )
                PublicAccess.Channel.NGROK -> warnings + tr(
                    "5. 走 ngrok 时，HTTPS 在 ngrok 的服务器上解开再转发到手机，ngrok 技术上看得到请求内容；authtoken 只保存在本机，泄露后请在 ngrok 后台重置。",
                    "5. With ngrok, HTTPS is decrypted on ngrok's servers and forwarded to the phone, so ngrok can technically see requests; the authtoken stays on this phone, reset it in the ngrok dashboard if it leaks.",
                )
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
                        tr("我已了解以上风险（记住本次同意）", "I understand these risks (remember)"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    LinkButton(onClick = { showRiskDetail = false }) {
                        Text(tr("收起", "Hide"))
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
                Text(tr("开启公网接入", "Turn on public access"), color = MiuixTheme.colorScheme.onPrimary)
            }
            if (!checklist.allOk) {
                Text(
                    tr("请先完成上方全部 ${checklist.items.size} 项前提条件，回到 App 会自动重查。", "Complete all ${checklist.items.size} items above first; returning to the app rechecks automatically."),
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
