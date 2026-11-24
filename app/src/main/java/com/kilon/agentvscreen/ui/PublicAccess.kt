package com.kilon.agentvscreen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.host.StrongConfirm
import com.kilon.agentvscreen.host.Tailnet
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 2.0.0-beta7 (7.4, 7.7, 7.8): Settings › Remote access › Public access.
 *
 * Enabling sequence:
 * 1. Official built-in Tailscale connected (Headscale rejected immediately).
 * 2. Screen lock PIN/biometric set on the phone (no hold-to-confirm allowed).
 * 3. Verbatim 4-point risk warning + "我已了解以上风险" checkbox gating "继续".
 * 4. Strong confirmation (`Risk.PUBLIC_ENABLE`, biometric/PIN only).
 * 5. Live Funnel status, translated error (7.8 #2), MCP URL copy, turn off, and revoke all public clients.
 */
@Composable
fun PublicAccessPage() {
    val ctx = LocalContext.current
    val actx = ctx.applicationContext
    val host = LocalHost.current
    val ts by Tailnet.status.collectAsState()
    val enabled by PublicAccess.enabledFlow.collectAsState()
    val tokens by Tokens.list.collectAsState()
    var acknowledged by remember { mutableStateOf(false) }
    var block by remember { mutableStateOf(PublicAccess.checkPreconditions(ctx)) }
    var revokeConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(ts, enabled) {
        while (true) {
            block = PublicAccess.checkPreconditions(ctx)
            delay(1_500)
        }
    }

    val publicClients = remember(tokens) { tokens.filter { it.publicOnly } }
    val mcpUrl = remember(ts.dnsName) { PublicAccess.mcpUrl() }

    MiuixPage(
        title = tr("公网接入", "Public access"),
        subtitle = tr("通过 Tailscale Funnel 供云端 MCP 客户端连接", "For cloud MCP clients via Tailscale Funnel"),
    ) {
        if (enabled) {
            Group(tr("运行状态", "Status")) {
                when (ts.funnel) {
                    "on" -> {
                        InfoCard(
                            Palette.takeover,
                            tr("公网接入已开启", "Public access is on"),
                            (mcpUrl?.let { tr("MCP 地址：$it\n", "MCP URL: $it\n") } ?: "") +
                                tr(
                                    "24 小时没有任何公网请求会自动关闭；App 更新后也会自动恢复关闭状态。",
                                    "Closes automatically after 24 hours of inactivity, and resets to off after app updates.",
                                ),
                        )
                        if (mcpUrl != null) {
                            Code(mcpUrl) { host.copy(tr("MCP 地址", "MCP URL"), mcpUrl) }
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
                    "error" -> {
                        val msg = PublicAccess.translateFunnelError(ts.funnelReason)
                        InfoCard(
                            Palette.stopped,
                            msg?.title ?: tr("Funnel 启动失败", "Funnel failed to start"),
                            msg?.fix ?: ts.funnelReason.orEmpty(),
                        )
                    }
                    else -> {
                        InfoCard(
                            Palette.standby,
                            tr("正在启动 Tailscale Funnel…", "Starting Tailscale Funnel…"),
                            (mcpUrl?.let { "$it\n" } ?: "") + tr(
                                "首次开启可能需要等待 Tailscale 签发 HTTPS 证书。",
                                "First-time startup may take a moment while Tailscale issues an HTTPS certificate.",
                            ),
                        )
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    SoftButton(
                        onClick = {
                            PublicAccess.disable(actx, "user")
                            acknowledged = false
                            host.say(tr("已关闭公网接入", "Public access turned off"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("关闭公网接入", "Turn off public access"), color = MiuixTheme.colorScheme.error)
                    }
                }
            }
            Footnote(tr("关闭公网接入不会撤销已授权的客户端；要彻底断开请在下方撤销全部公网客户端。", "Turning off public access does not revoke authorized clients; revoke them below if needed."))
        } else {
            when (block) {
                PublicAccess.BlockReason.HEADSCALE -> Group(tr("暂不支持", "Not supported")) {
                    InfoCard(
                        Palette.stopped,
                        tr("不支持自建控制服务器（Headscale）", "Self-hosted control server (Headscale) is not supported"),
                        tr(
                            "公网接入只支持官方 Tailscale 协调服务器的 Funnel 功能。Headscale 没有 Funnel，请切回官方 Tailscale 后再开启。",
                            "Public access requires Funnel on official Tailscale servers. Headscale does not support Funnel.",
                        ),
                    )
                }
                PublicAccess.BlockReason.TAILNET_NOT_READY -> Group(tr("前置条件", "Prerequisite")) {
                    InfoCard(
                        Palette.standby,
                        tr("需要先连上官方内置 Tailscale", "Official built-in Tailscale must be connected"),
                        tr(
                            "请先在上一页“远程连接”选择“内置 Tailscale”并登录官方 Tailscale 账号。",
                            "Go back to Remote access, select Built-in Tailscale, and sign in to your official Tailscale account first.",
                        ),
                    )
                }
                PublicAccess.BlockReason.NO_LOCKSCREEN -> Group(tr("前置条件", "Prerequisite")) {
                    InfoCard(
                        Palette.stopped,
                        tr("请先设置手机锁屏密码", "Set a phone screen lock first"),
                        tr(
                            "公网接入的开启与所有客户端授权都必须通过系统生物识别或锁屏密码确认，不允许长按确认。请先在系统设置里开启锁屏密码。",
                            "Enabling public access and authorizing cloud clients require biometric or PIN lock verification (hold-to-confirm is not allowed). Set a screen lock in system settings first.",
                        ),
                    )
                }
                null -> {}
            }

            // 7.4: verbatim 4-item warning screen + checkbox gating "继续".
            Group(tr("风险说明", "Risk warning"), padded = true) {
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
                        "4. 默认只给“查看”权限；“系统”权限永远不会开放到公网。",
                        "4. Access defaults to \"look\" only; \"system\" scope is never exposed to the public internet.",
                    ),
                )
                warnings.forEach { w ->
                    Text(w, fontSize = 13.5.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurface)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { acknowledged = !acknowledged }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        state = ToggleableState(acknowledged),
                        onClick = { acknowledged = !acknowledged },
                    )
                    Text(
                        tr("我已了解以上风险", "I understand these risks"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                PrimaryButton(
                    onClick = {
                        val b = PublicAccess.checkPreconditions(ctx)
                        block = b
                        if (b != null) return@PrimaryButton
                        val req = StrongConfirm.createPublicEnableRequest(
                            ctx = actx,
                            onApprove = { PublicAccess.enable(actx) },
                        )
                        StrongConfirmActivity.open(actx, req.id)
                    },
                    enabled = block == null && acknowledged,
                    modifier = Modifier.fillMaxWidth(),
                    color = MiuixTheme.colorScheme.primary,
                ) {
                    Text(tr("继续", "Continue"), color = MiuixTheme.colorScheme.onPrimary)
                }
            }
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
                    Text(
                        "${t.name} · ${if (t.scope == "operate") tr("可操作", "operate") else tr("只看", "look only")}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
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
