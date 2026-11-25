package com.kilon.agentvscreen.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import com.kilon.agentvscreen.host.PublicAccess
import com.kilon.agentvscreen.tr
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
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
        title = tr("设置指南", "Setup guide"),
        subtitle = channel.title,
    ) {
        if (channel == PublicAccess.Channel.CLOUDFLARE) CloudflareGuide() else TailscaleGuide()
    }
}

private data class Btn(val label: String, val run: () -> Unit)

@Composable
private fun Step(n: Int, title: String, body: String, code: String? = null, codeLabel: String = "", buttons: List<Btn> = emptyList()) {
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
        if (code != null) WrappableCode(code) { host.copy(codeLabel, code) }
        if (buttons.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons.forEach { b -> SoftButton(onClick = b.run) { Text(b.label, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) } }
        }
    }
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

@Composable
private fun TailscaleGuide() {
    val nav = LocalNav.current
    val open = openUrl()
    Group(tr("适合谁", "Who it suits"), padded = true) {
        Text(tr("没有自己的域名，想最快开通。地址由 Tailscale 分配，形如 https://手机名.你的网络.ts.net/mcp。HTTPS 在手机上解密，中间的服务器看不到内容。需要官方 Tailscale 账号（免费），Headscale 不支持。",
            "You have no domain and want the quickest setup. Tailscale assigns the address, like https://phone.your-tailnet.ts.net/mcp. HTTPS is decrypted on the phone, so no server in between sees the content. Needs an official Tailscale account (free); Headscale is not supported."),
            fontSize = 13.5.sp, lineHeight = 20.sp)
    }
    Group(tr("步骤", "Steps")) {
        Step(1, tr("登录内置 Tailscale", "Sign in to the built-in Tailscale"),
            tr("在 接入 › 其它设备（Tailscale）选择“内置 Tailscale”并登录官方账号。登录后这台手机会出现在你的 Tailscale 设备列表里。", "In Connect › Other devices (Tailscale), choose Built-in Tailscale and sign in with an official account. The phone then shows in your Tailscale device list."),
            buttons = listOf(Btn(tr("打开 Tailscale 设置", "Open Tailscale settings")) { nav.go(Route.Remote) }))
        Step(2, tr("开启 HTTPS 证书", "Turn on HTTPS certificates"),
            tr("Tailscale 管理页 › DNS，找到 HTTPS Certificates，点 Enable。只需做一次。", "Tailscale admin › DNS, find HTTPS Certificates and press Enable. Needed once."),
            buttons = listOf(Btn(tr("打开 DNS 设置", "Open DNS settings")) { open(PublicAccess.ADMIN_DNS_URL) }))
        Step(3, tr("允许这台手机使用 Funnel", "Allow Funnel for this phone"),
            tr("公网页面的清单通常会给出“一键开启”的链接；没有时，打开 Access controls，把下面这段加入策略文件后保存。", "The checklist on the public page usually offers a one-tap link; otherwise open Access controls, add the snippet below to the policy file and save."),
            code = PublicAccess.FUNNEL_NODE_ATTRS_SNIPPET, codeLabel = "nodeAttrs",
            buttons = listOf(Btn(tr("打开访问控制", "Open Access controls")) { open(PublicAccess.ADMIN_ACL_URL) }))
        Step(4, tr("开启公网接入", "Turn on public access"),
            tr("回到公网页面，清单会自动重查。全部 ✓ 后同意风险说明，点“开启公网接入”并验证指纹。第一次申请证书约 30–60 秒。", "Back on the public page the checklist rechecks by itself. When all show ✓, accept the risk note, press Turn on public access and confirm with your fingerprint. The first certificate takes about 30–60 seconds."))
        Step(5, tr("把地址交给云端客户端", "Give the address to the cloud client"),
            tr("复制 MCP 地址，在客户端里添加（如 claude.ai 的“添加连接器”）。第一次连接时，手机上会弹出配对请求：核对配对码一致再批准，并选择这个客户端的权限档位。", "Copy the MCP address and add it in the client (e.g. Add connector on claude.ai). On the first connection the phone shows a pairing request: check the code matches, approve, and pick the client's permission level."))
    }
    Group(tr("常见问题", "Common problems")) {
        Problem(tr("清单提示未开启 HTTPS", "Checklist says HTTPS is off"), tr("完成第 2 步，回到 App 会自动重查。", "Do step 2; returning to the app rechecks."))
        Problem(tr("提示 Funnel 未授权或 443 不可用", "Funnel not allowed or port 443 unavailable"), tr("完成第 3 步；改完策略后关闭再开启公网接入。", "Do step 3; after changing the policy turn public access off and on."))
        Problem(tr("一直显示正在申请证书", "Stuck requesting the certificate"), tr("首次可能需要一分钟以上；超过 3 分钟请关闭再开启。开着代理 VPN 时，把 Agent-VScreen 设为直连。", "The first one can take over a minute; after 3 minutes turn it off and on. With a proxy VPN on, let Agent-VScreen bypass it."))
        Problem(tr("用的是 Headscale", "Using Headscale"), tr("Headscale 没有 Funnel，请改用 Cloudflare Tunnel。", "Headscale has no Funnel; use Cloudflare Tunnel instead."))
    }
    Footnote(tr("Tailscale 管理页的菜单名称以其当前界面为准。", "Menu names in the Tailscale admin may change; follow what it shows now."))
}

@Composable
private fun CloudflareGuide() {
    val open = openUrl()
    Group(tr("适合谁", "Who it suits"), padded = true) {
        Text(tr("有自己的域名（或愿意花几十元买一个），想要固定、好记的地址，例如 https://phone.example.com/mcp；也适合用 Headscale 的情况。手机主动连到 Cloudflare，不需要公网 IP，也不用开端口。",
            "You have a domain (or will buy a cheap one) and want a fixed, memorable address such as https://phone.example.com/mcp; also for Headscale users. The phone dials out to Cloudflare: no public IP, no open port."),
            fontSize = 13.5.sp, lineHeight = 20.sp)
        Text(tr("要知道：Cloudflare 会在它的服务器上解开 HTTPS 再转发，技术上看得到请求内容。配对、OAuth 和权限检查仍在手机上进行。在中国大陆，Cloudflare 的连接速度和稳定性因网络而异。",
            "Note: Cloudflare decrypts HTTPS on its servers before forwarding, so it can technically see requests. Pairing, OAuth and permission checks still happen on the phone. In mainland China, Cloudflare's speed and stability vary by network."),
            fontSize = 12.5.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    Group(tr("准备", "Before you start")) {
        Step(1, tr("注册 Cloudflare 账号并添加域名", "Create a Cloudflare account and add your domain"),
            tr("在 Cloudflare 添加你的域名（免费计划即可），按提示到域名注册商处把域名服务器（NS）改成 Cloudflare 给的两个。生效通常几分钟到几小时，状态变为“有效”后继续。已经托管在 Cloudflare 的域名跳过这步。",
                "Add your domain to Cloudflare (the free plan is enough) and, as prompted, change its nameservers at your registrar to the two Cloudflare gives. This takes minutes to hours; continue once it shows Active. Skip if the domain is already on Cloudflare."),
            buttons = listOf(Btn(tr("打开 Cloudflare", "Open Cloudflare")) { open(Cloudflared.ADD_SITE_URL) }))
    }
    Group(tr("步骤", "Steps")) {
        Step(2, tr("新建隧道", "Create a tunnel"),
            tr("打开 Cloudflare Zero Trust（第一次进入按提示选免费计划、起团队名），进入 网络（Networks）› 隧道（Tunnels）› 创建隧道，类型选 Cloudflared，名字随意（如 agent-vscreen），保存。",
                "Open Cloudflare Zero Trust (the first time, follow the prompts: free plan, team name), go to Networks › Tunnels › Create a tunnel, choose Cloudflared, name it (e.g. agent-vscreen) and save."),
            buttons = listOf(Btn(tr("打开 Zero Trust", "Open Zero Trust")) { open(Cloudflared.DASHBOARD_URL) }))
        Step(3, tr("复制令牌，粘贴到本 App", "Copy the token into this app"),
            tr("在“安装并运行连接器”页面，任选一个系统，复制显示的整条命令（其中 eyJ 开头的长串就是令牌），粘贴到公网页面的“隧道令牌”。不需要在电脑上运行这条命令：连接器就在这台手机上。",
                "On Install and run a connector, pick any system and copy the whole command shown (the long string starting with eyJ is the token), then paste it into Tunnel token on the public page. Don't run it on a computer: the connector is this phone."))
        Step(4, tr("添加公网域名，指向手机", "Add the public hostname, pointing at the phone"),
            tr("下一步“公共主机名”（新版界面可能叫“已发布的应用路由”）：子域名填如 phone，域名选你的域名，路径留空；服务类型选 HTTP，URL 填下面这个地址（不要写 localhost，也不要改端口），保存。",
                "Next, Public Hostname (newer screens may call it Published application routes): subdomain e.g. phone, pick your domain, leave the path empty; service type HTTP, URL exactly as below (not localhost, same port), then save."),
            code = Cloudflared.ORIGIN_URL.removePrefix("http://"), codeLabel = tr("服务地址", "Service URL"))
        Step(5, tr("填写域名并开启", "Enter the hostname and turn it on"),
            tr("回到公网页面，填写刚才的完整域名（如 phone.example.com）并保存。清单全部 ✓ 后同意风险说明，点“开启公网接入”并验证指纹。",
                "Back on the public page, enter the full hostname (e.g. phone.example.com) and save. When the checklist is all ✓, accept the risk note, press Turn on public access and confirm with your fingerprint."))
        Step(6, tr("等两项变绿，再交给客户端", "Wait for two green rows, then give it to the client"),
            tr("“隧道连接”显示已连上、“公网地址自检”通过，说明外网已经能到这台手机。复制 MCP 地址添加到云端客户端；第一次连接时在手机上核对配对码并批准。",
                "When Tunnel connection shows connected and Public address self-test passes, the internet can reach this phone. Copy the MCP address into the cloud client; on the first connection check the pairing code on the phone and approve."))
    }
    Group(tr("常见问题", "Common problems")) {
        Problem(tr("不认这个隧道令牌", "Token not accepted"), tr("重新从隧道页面复制整条命令。隧道被删除，或在 Cloudflare 上刷新过令牌，旧令牌都会失效。", "Copy the whole command again from the tunnel page. A deleted tunnel or a refreshed token invalidates the old one."))
        Problem(tr("自检：域名解析不到", "Self-test: hostname does not resolve"), tr("第 4 步的公共主机名没加上，或刚加还在生效（等 1–2 分钟）；域名本身也必须托管在同一个 Cloudflare 账号。", "The public hostname from step 4 is missing or still propagating (wait 1–2 minutes); the domain must be on the same Cloudflare account."))
        Problem(tr("自检：错误 1033 / 隧道未连接", "Self-test: error 1033 / tunnel not connected"), tr("这个域名指向的隧道没有在线的连接器：确认令牌和域名属于同一个隧道。", "No connector is online for the tunnel behind this hostname: check the token and hostname belong to the same tunnel."))
        Problem(tr("自检：502 / 转不到手机", "Self-test: 502 / cannot reach the phone"), tr("公共主机名的服务地址填错了：类型 HTTP，地址 127.0.0.1:${Cloudflared.ORIGIN_PORT}。", "The hostname's service is wrong: type HTTP, address 127.0.0.1:${Cloudflared.ORIGIN_PORT}."))
        Problem(tr("自检：证书错误", "Self-test: certificate error"), tr("新域名的证书要几分钟；免费证书不覆盖多级子域名（a.b.example.com），请用 phone.example.com 这样的一级子域名。", "A new hostname's certificate takes minutes; the free certificate does not cover multi-level subdomains (a.b.example.com), use one level like phone.example.com."))
        Problem(tr("自检：被重定向或被拦截", "Self-test: redirected or blocked"), tr("这个子域名上不要加 Cloudflare Access 登录、跳转规则，也要关闭“机器人对抗模式”等会拦截程序访问的防护。", "Don't put a Cloudflare Access login or redirect rule on this subdomain, and turn off Bot Fight Mode and similar protection that blocks programs."))
        Problem(tr("一直连不上 Cloudflare", "Never connects to Cloudflare"), tr("隧道使用 7844 端口（先 QUIC，不通时自动改 HTTP/2）。开着代理 VPN 时把 Agent-VScreen 设为直连；部分网络会拦这个端口，换个网络试试。", "The tunnel uses port 7844 (QUIC first, HTTP/2 automatically if blocked). With a proxy VPN, let Agent-VScreen bypass it; some networks block the port, try another."))
    }
    Footnote(tr("令牌相当于这个隧道的钥匙，只保存在本机。怀疑泄露时，在 Cloudflare 隧道页面刷新令牌，再把新令牌粘贴进来。Cloudflare 后台的菜单名称以其当前界面为准。",
        "The token is the key to this tunnel and is kept only on this phone. If it may have leaked, refresh it on the Cloudflare tunnel page and paste the new one. Cloudflare's menu names may change; follow what it shows now."))
}

/** The Cloudflare settings the user fills in (token, hostname), with the service URL to copy. */
@Composable
fun CloudflareSetupSection() {
    val host = LocalHost.current
    val cfg by Cloudflared.config.collectAsState()
    var token by rememberSaveable { mutableStateOf("") }
    var hostname by rememberSaveable(cfg.hostname) { mutableStateOf(cfg.hostname.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    Group(tr("Cloudflare 隧道", "Cloudflare tunnel")) {
        TextField(value = token, onValueChange = { token = it }, label = if (cfg.tunnelId != null) tr("隧道令牌（已保存，粘贴新的可替换）", "Tunnel token (saved; paste a new one to replace)") else tr("隧道令牌：粘贴安装命令或 eyJ… 令牌", "Tunnel token: paste the install command or eyJ… token"),
            useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        TextField(value = hostname, onValueChange = { hostname = it.take(253) }, label = tr("公网域名，例如 phone.example.com", "Public hostname, e.g. phone.example.com"),
            useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        problem?.let { Text(it, fontSize = 12.5.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            SoftButton(onClick = {
                problem = Cloudflared.save(token.takeIf { it.isNotBlank() }, hostname.takeIf { it.isNotBlank() })
                if (problem == null) { token = ""; host.say(tr("已保存", "Saved")) }
            }, modifier = Modifier.fillMaxWidth()) { Text(tr("保存", "Save"), color = MiuixTheme.colorScheme.primary) }
        }
        BasicComponent(title = tr("服务地址（填到 Cloudflare 的公共主机名里）", "Service URL (for the tunnel's public hostname)"), summary = tr("类型 HTTP，地址如下", "Type HTTP, address below"))
        WrappableCode(Cloudflared.ORIGIN_URL.removePrefix("http://")) { host.copy(tr("服务地址", "Service URL"), Cloudflared.ORIGIN_URL.removePrefix("http://")) }
    }
}

/** Live state of the tunnel and the self-test, while Cloudflare public access is on. */
@Composable
fun CloudflareRunning(mcpUrl: String?) {
    val host = LocalHost.current
    val st by Cloudflared.status.collectAsState()
    val test by Cloudflared.selfTest.collectAsState()
    var raw by remember { mutableStateOf(false) }
    val (title, color) = when (st.state) {
        Cloudflared.State.CONNECTED -> tr("已连上 Cloudflare（${st.connections} 条连接${st.location?.let { " · $it" } ?: ""}）", "Connected to Cloudflare (${st.connections} connections${st.location?.let { " · $it" } ?: ""})") to Palette.takeover
        Cloudflared.State.STARTING -> tr("正在连接 Cloudflare…", "Connecting to Cloudflare…") to Palette.standby
        Cloudflared.State.ERROR -> tr("没有连上 Cloudflare", "Not connected to Cloudflare") to Palette.stopped
        Cloudflared.State.OFF -> tr("未运行", "Not running") to Palette.stopped
    }
    BasicComponent(title = tr("隧道连接", "Tunnel connection"), summary = title + (st.problem?.let { "\n$it" } ?: ""), endActions = { Dot(color, 8.dp) },
        onClick = if (st.raw != null) ({ raw = !raw }) else null)
    if (raw && st.raw != null) WrappableCode(st.raw!!) { host.copy(tr("详情", "Details"), st.raw!!) }
    val (tTitle, tColor) = when (val t = test) {
        null -> tr("连上后自动检查", "Runs once connected") to MiuixTheme.colorScheme.onSurfaceVariantSummary
        Cloudflared.SelfTest.Running -> tr("正在从公网访问本机…", "Reaching this phone from the internet…") to Palette.standby
        Cloudflared.SelfTest.Ok -> tr("通过：外网已能访问这台手机", "Passed: the internet reaches this phone") to Palette.takeover
        is Cloudflared.SelfTest.Failed -> "${t.title}\n${t.fix}" to Palette.stopped
    }
    BasicComponent(title = tr("公网地址自检", "Public address self-test"), summary = tTitle, endActions = { Dot(tColor, 8.dp) })
    if (st.state == Cloudflared.State.CONNECTED) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        SoftButton(onClick = { Thread({ Cloudflared.selfTest() }, "vscreen-cf-test").apply { isDaemon = true }.start() }, modifier = Modifier.fillMaxWidth()) {
            Text(tr("重新自检", "Run the self-test again"), color = MiuixTheme.colorScheme.primary)
        }
    }
    if (mcpUrl != null && test == Cloudflared.SelfTest.Ok) {
        WrappableCode(mcpUrl) { host.copy(tr("MCP 地址", "MCP URL"), mcpUrl) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            PrimaryButton(onClick = { host.copy(tr("MCP 地址", "MCP URL"), mcpUrl) }, modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.primary) {
                Text(tr("复制 MCP 地址", "Copy MCP URL"), color = MiuixTheme.colorScheme.onPrimary)
            }
        }
    } else if (mcpUrl != null) {
        Footnote(tr("地址：$mcpUrl（自检通过后再交给客户端）", "Address: $mcpUrl (give it to the client after the self-test passes)"))
    }
}
