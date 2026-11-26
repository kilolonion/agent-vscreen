package com.kilon.agentvscreen.ui

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
        Text(shot.caption + tr("（点图放大）", " (tap to enlarge)"), fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
        val all = buttons + listOfNotNull(docs?.let { url -> Btn(tr("官方文档", "Official docs")) { open(url) } })
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
        if (problem == null) { v = ""; host.say(tr("已保存", "Saved")) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (saved != null) Text("✓ " + saved, fontSize = 13.sp, color = Palette.takeover)
        TextField(value = v, onValueChange = { v = it; problem = null }, label = label, useLabelAsPlaceholder = true, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        problem?.let { Text(it, fontSize = 12.5.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton(onClick = {
                val clip = runCatching { ctx.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
                if (clip.isNullOrBlank()) host.say(tr("剪贴板是空的：先在网页里点复制", "The clipboard is empty: press copy on the web page first")) else save(clip)
            }) { Text(tr("粘贴并保存", "Paste and save"), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) }
            if (v.isNotBlank()) SoftButton(onClick = { save(v) }) { Text(tr("保存", "Save"), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) }
        }
    }
}

/** The last step's way back to the public page, where turning on needs the risk note and a fingerprint. */
@Composable
private fun backToPublic(): Btn {
    val nav = LocalNav.current
    return Btn(tr("回到公网页面开启", "Back to turn it on")) { nav.back() }
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
    if (here) tr("在本页下方：", "Further down this page: ") else tr("然后回到公网页面：", "Then go back to the public page: ")

@Composable
private fun TailscaleGuide() {
    Group(tr("适合谁", "Who it suits"), padded = true) {
        Text(tr("没有自己的域名，想最快开通。地址由 Tailscale 分配，形如 https://手机名.你的网络.ts.net/mcp。HTTPS 在手机上解密，中间的服务器看不到内容。需要官方 Tailscale 账号（免费），Headscale 不支持。",
            "You have no domain and want the quickest setup. Tailscale assigns the address, like https://phone.your-tailnet.ts.net/mcp. HTTPS is decrypted on the phone, so no server in between sees the content. Needs an official Tailscale account (free); Headscale is not supported."),
            fontSize = 13.5.sp, lineHeight = 20.sp)
    }
    TailscaleSteps(here = false)
    Group(tr("常见问题", "Common problems")) {
        Problem(tr("清单提示未开启 HTTPS", "Checklist says HTTPS is off"), tr("完成第 2 步，回到 App 会自动重查。", "Do step 2; returning to the app rechecks."))
        Problem(tr("提示 Funnel 未授权或 443 不可用", "Funnel not allowed or port 443 unavailable"), tr("完成第 3 步；改完策略后关闭再开启公网接入。", "Do step 3; after changing the policy turn public access off and on."))
        Problem(tr("一直显示正在申请证书", "Stuck requesting the certificate"), tr("首次可能需要一分钟以上；超过 3 分钟请关闭再开启。开着代理 VPN 时，把 Agent-VScreen 设为直连。", "The first one can take over a minute; after 3 minutes turn it off and on. With a proxy VPN on, let Agent-VScreen bypass it."))
        Problem(tr("用的是 Headscale", "Using Headscale"), tr("Headscale 没有 Funnel，请改用 Cloudflare Tunnel 或 ngrok。", "Headscale has no Funnel; use Cloudflare Tunnel or ngrok instead."))
    }
    Footnote(tr("Tailscale 管理页的菜单名称以其当前界面为准。", "Menu names in the Tailscale admin may change; follow what it shows now."))
}

@Composable
private fun TailscaleSteps(here: Boolean) {
    val nav = LocalNav.current
    val open = openUrl()
    Group(tr("步骤", "Steps")) {
        Step(1, tr("登录内置 Tailscale", "Sign in to the built-in Tailscale"),
            tr("在 接入 › 其它设备（Tailscale）选择“内置 Tailscale”并登录官方账号。登录后这台手机会出现在你的 Tailscale 设备列表里。", "In Connect › Other devices (Tailscale), choose Built-in Tailscale and sign in with an official account. The phone then shows in your Tailscale device list."),
            buttons = listOf(Btn(tr("打开 Tailscale 设置", "Open Tailscale settings")) { nav.go(Route.Remote) }))
        Step(2, tr("开启 HTTPS 证书", "Turn on HTTPS certificates"),
            tr("点“打开 DNS 设置”直接进到 Tailscale 管理页的 DNS 页面，拉到底部找到 HTTPS Certificates，点 Enable HTTPS。整个网络只需做一次。", "Open DNS settings goes straight to the DNS page of the Tailscale admin; scroll to HTTPS Certificates at the bottom and press Enable HTTPS. Needed once per tailnet."),
            buttons = listOf(Btn(tr("打开 DNS 设置", "Open DNS settings")) { open(PublicAccess.ADMIN_DNS_URL) }),
            docs = PublicAccess.DOCS_HTTPS_URL)
        Step(3, tr("允许这台手机使用 Funnel", "Allow Funnel for this phone"),
            tr("下方清单通常会给出“一键开启”的链接；没有时，点“打开访问控制”进到策略文件（Access controls），把下面这段加到最外层的大括号里，点 Save。", "The checklist below usually offers a one-tap link; otherwise Open Access controls goes to the policy file: add the snippet below inside the outermost braces and press Save."),
            code = PublicAccess.FUNNEL_NODE_ATTRS_SNIPPET, codeLabel = "nodeAttrs",
            buttons = listOf(Btn(tr("打开访问控制", "Open Access controls")) { open(PublicAccess.ADMIN_ACL_URL) }),
            docs = PublicAccess.DOCS_FUNNEL_URL)
        Step(4, tr("开启公网接入", "Turn on public access"),
            turnOnWhere(here) + tr("清单会自动重查。全部 ✓ 后同意风险说明，点“开启公网接入”并验证指纹。第一次申请证书约 30–60 秒。", "the checklist rechecks by itself. When all show ✓, accept the risk note, press Turn on public access and confirm with your fingerprint. The first certificate takes about 30–60 seconds."),
            buttons = if (here) emptyList() else listOf(backToPublic()))
        Step(5, tr("把地址交给云端客户端", "Give the address to the cloud client"),
            tr("复制 MCP 地址，在客户端里添加（如 claude.ai 的“添加连接器”）。第一次连接时手机不会自动弹窗：打开 App 的公网页面输入客户端显示的 8 位配对码，核对来源地址后批准，并选择权限档位。", "Copy the MCP address and add it in the client (e.g. Add connector on claude.ai). On the first connection the phone will not pop up automatically: enter the 8-character pairing code in this app's Public MCP page, check the source address, pick a permission level, and approve."))
    }
}

@Composable
private fun CloudflareGuide() {
    Group(tr("适合谁", "Who it suits"), padded = true) {
        Text(tr("有自己的域名（或愿意花几十元买一个），想要固定、好记的地址，例如 https://phone.example.com/mcp；也适合用 Headscale 的情况。手机主动连到 Cloudflare，不需要公网 IP，也不用开端口。",
            "You have a domain (or will buy a cheap one) and want a fixed, memorable address such as https://phone.example.com/mcp; also for Headscale users. The phone dials out to Cloudflare: no public IP, no open port."),
            fontSize = 13.5.sp, lineHeight = 20.sp)
        Text(tr("要知道：Cloudflare 会在它的服务器上解开 HTTPS 再转发，技术上看得到请求内容。配对、OAuth 和权限检查仍在手机上进行。在中国大陆，Cloudflare 的连接速度和稳定性因网络而异。",
            "Note: Cloudflare decrypts HTTPS on its servers before forwarding, so it can technically see requests. Pairing, OAuth and permission checks still happen on the phone. In mainland China, Cloudflare's speed and stability vary by network."),
            fontSize = 12.5.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    CloudflareSteps(here = false)
    Group(tr("常见问题", "Common problems")) {
        Problem(tr("找不到添加域名的地方", "Can't find where to add the hostname"), tr("在隧道列表里点隧道的名字，进入它里面的“路由”（Routes）标签页。左侧菜单里的“网络 › 路由”是另一个页面（私有网络用），不是这里。", "In the tunnel list press the tunnel's name and open its own Routes tab. The left menu's Networking › Routes is a different page (for private networks)."))
        Problem(tr("不认这个隧道令牌", "Token not accepted"), tr("重新从隧道页面复制整条命令。隧道被删除，或在 Cloudflare 上刷新过令牌，旧令牌都会失效。", "Copy the whole command again from the tunnel page. A deleted tunnel or a refreshed token invalidates the old one."))
        Problem(tr("自检：域名解析不到", "Self-test: hostname does not resolve"), tr("第 4 步的路由没加上，或刚加还在生效（等 1–2 分钟）；域名本身也必须托管在同一个 Cloudflare 账号。", "The route from step 4 is missing or still propagating (wait 1–2 minutes); the domain must be on the same Cloudflare account."))
        Problem(tr("自检：错误 1033 / 隧道未连接", "Self-test: error 1033 / tunnel not connected"), tr("这个域名指向的隧道没有在线的连接器：确认令牌和域名属于同一个隧道。", "No connector is online for the tunnel behind this hostname: check the token and hostname belong to the same tunnel."))
        Problem(tr("自检：502 / 转不到手机", "Self-test: 502 / cannot reach the phone"), tr("路由的服务地址填错了：应为 ${Cloudflared.ORIGIN_URL}。", "The route's service is wrong: it must be ${Cloudflared.ORIGIN_URL}."))
        Problem(tr("自检：证书错误", "Self-test: certificate error"), tr("新域名的证书要几分钟；免费证书不覆盖多级子域名（a.b.example.com），请用 phone.example.com 这样的一级子域名。", "A new hostname's certificate takes minutes; the free certificate does not cover multi-level subdomains (a.b.example.com), use one level like phone.example.com."))
        Problem(tr("自检：被重定向或被拦截", "Self-test: redirected or blocked"), tr("这个子域名上不要加 Cloudflare Access 登录、跳转规则，也要关闭“机器人对抗模式”等会拦截程序访问的防护。", "Don't put a Cloudflare Access login or redirect rule on this subdomain, and turn off Bot Fight Mode and similar protection that blocks programs."))
        Problem(tr("一直连不上 Cloudflare", "Never connects to Cloudflare"), tr("隧道使用 7844 端口（先 QUIC，不通时自动改 HTTP/2）。开着代理 VPN 时把 Agent-VScreen 设为直连；部分网络会拦这个端口，换个网络试试。", "The tunnel uses port 7844 (QUIC first, HTTP/2 automatically if blocked). With a proxy VPN, let Agent-VScreen bypass it; some networks block the port, try another."))
    }
    Footnote(tr("令牌相当于这个隧道的钥匙，只保存在本机。怀疑泄露时，在 Cloudflare 隧道页面刷新令牌，再把新令牌粘贴进来。截图来自 2026 年 11 月的 Cloudflare 后台，菜单名称以其当前界面为准。",
        "The token is the key to this tunnel and is kept only on this phone. If it may have leaked, refresh it on the Cloudflare tunnel page and paste the new one. Screenshots are of the Cloudflare dashboard in November 2026; follow what it shows now."))
}

@Composable
private fun CloudflareSteps(here: Boolean) {
    val open = openUrl()
    val cf by Cloudflared.config.collectAsState()
    val tunnels = Btn(tr("打开隧道列表", "Open the tunnel list")) { open(Cloudflared.TUNNELS_URL) }
    Group(tr("步骤", "Steps")) {
        Step(1, tr("注册 Cloudflare 账号并添加域名", "Create a Cloudflare account and add your domain"),
            tr("在 Cloudflare 添加你的域名（免费计划即可），按提示到域名注册商处把域名服务器（NS）改成 Cloudflare 给的两个。生效通常几分钟到几小时，状态变为“有效”后继续。已经托管在 Cloudflare 的域名跳过这步。",
                "Add your domain to Cloudflare (the free plan is enough) and, as prompted, change its nameservers at your registrar to the two Cloudflare gives. This takes minutes to hours; continue once it shows Active. Skip if the domain is already on Cloudflare."),
            buttons = listOf(Btn(tr("打开 Cloudflare", "Open Cloudflare")) { open(Cloudflared.ADD_SITE_URL) }))
        Step(2, tr("新建隧道", "Create a tunnel"),
            tr("点“打开隧道列表”直接进到 网络（Networking）› 隧道（Tunnels）页面（有多个账号时先选账号）。点“创建隧道”（Create a tunnel），类型选 Cloudflared，名字随意（如 agent-vscreen），点“创建隧道”。注意：左侧菜单里的“网络 › 路由”（Routes）是私有网络用的另一个页面，不要进去。",
                "Open the tunnel list goes straight to Networking › Tunnels (pick the account first if you have several). Press Create a tunnel, choose Cloudflared, name it (e.g. agent-vscreen) and press Create Tunnel. Note: Networking › Routes in the left menu is a different page for private networks; don't go there."),
            shots = listOf(Shot(R.drawable.guide_cf_not_routes, tr("✗ 不是这里：左侧菜单的“网络 › 路由”页面", "✗ Not here: the left menu's Networking › Routes page"))),
            buttons = listOf(tunnels), docs = Cloudflared.DOCS_URL)
        Step(3, tr("复制令牌，粘贴到本 App", "Copy the token into this app"),
            tr("创建后进入“安装并运行连接器”页面：任选一个系统，点命令右边的复制按钮 ①（其中 eyJ 开头的长串就是令牌），回来点“粘贴并保存”。不需要在电脑上运行这条命令：连接器就在这台手机上。那一页下面的“连接状态” ② 会一直转圈、“继续”是灰的：连接器要等第 5 步开启公网才上线，这是正常的，保存令牌后直接离开那一页。",
                "Next comes Install and run a connector: pick any system and press the copy button beside the command ① (the long string starting with eyJ is the token), come back and press Paste and save. Don't run it on a computer: the connector is this phone. Connection status ② keeps spinning and Continue stays grey: the connector comes online only when public access is on (step 5). That is expected; once the token is saved, just leave that page."),
            shots = listOf(Shot(R.drawable.guide_cf_install, tr("① 复制整条命令；② 一直转圈、“继续”是灰的，属正常", "① copy the whole command; ② spinning with Continue grey is expected"))),
            field = { GuideField(tr("隧道令牌或整条安装命令", "Tunnel token or the whole install command"), cf.tunnelId?.let { tr("隧道令牌已保存", "Tunnel token saved") }) { Cloudflared.save(it, null) } })
        Step(4, tr("添加公网域名，指向手机", "Add the public hostname, pointing at the phone"),
            tr("点“打开隧道列表”，点刚建的隧道名字，进入它里面的“路由”（Routes）标签 ①（隧道内部的标签，不是左侧菜单的“网络 › 路由”），点右上角“添加路由”（Add route）②，再选“已发布的应用程序”（Published application）③。在“添加已发布应用程序”里：子域名 ① 填如 phone；域 ② 下拉选你的域名；路径留空；Service URL ③ 填下面这个地址（不要写 localhost，也不要改端口；有单独的“类型”下拉时选 HTTP，URL 只填 127.0.0.1:${Cloudflared.ORIGIN_PORT}），点“添加路由” ④。弹出“路由添加成功”就好了。旧版界面叫“公共主机名”（Public Hostname）。隧道显示未连接没关系，开启公网后会变成已连接。",
                "Open the tunnel list, press the tunnel's name and open its Routes tab ① (inside the tunnel, not the left menu's Networking › Routes), press Add route ② at the top right, then Published application ③. In the form: subdomain ① e.g. phone; domain ② pick yours; leave the path empty; Service URL ③ exactly as below (not localhost, same port; if there is a separate Type drop-down pick HTTP and enter just 127.0.0.1:${Cloudflared.ORIGIN_PORT}), then press Add route ④. \"Route added\" means done. Older screens call it Public Hostname. The tunnel showing not connected is fine: it connects once public access is on."),
            code = Cloudflared.ORIGIN_URL, codeLabel = tr("服务地址", "Service URL"),
            shots = listOf(
                Shot(R.drawable.guide_cf_route_tab, tr("① 隧道里的“路由”标签；② 添加路由", "① the tunnel's Routes tab; ② Add route")),
                Shot(R.drawable.guide_cf_route_type, tr("③ 选“已发布的应用程序”", "③ pick Published application")),
                Shot(R.drawable.guide_cf_route_form, tr("① 子域名；② 你的域名；③ Service URL 填 ${Cloudflared.ORIGIN_URL}；④ 添加路由", "① subdomain; ② your domain; ③ Service URL ${Cloudflared.ORIGIN_URL}; ④ Add route"))),
            buttons = listOf(tunnels), docs = Cloudflared.DOCS_ROUTES_URL)
        Step(5, tr("填写域名并开启", "Enter the hostname and turn it on"),
            tr("在下面填刚才的完整域名（如 phone.example.com）。", "Enter the full hostname below (e.g. phone.example.com). ") +
                turnOnWhere(here) + tr("清单全部 ✓ 后同意风险说明，点“开启公网接入”并验证指纹。", "when the checklist is all ✓, accept the risk note, press Turn on public access and confirm with your fingerprint."),
            field = { GuideField(tr("公网域名，例如 phone.example.com", "Public hostname, e.g. phone.example.com"), cf.hostname) { Cloudflared.save(null, it) } },
            after = if (here) emptyList() else listOf(backToPublic()))
        Step(6, tr("等两项变绿，再交给客户端", "Wait for two green rows, then give it to the client"),
            tr("“隧道连接”显示已连上、“公网地址自检”通过，说明外网已经能到这台手机。复制 MCP 地址添加到云端客户端；第一次连接时手机不会自动弹窗，请在 App 的公网页面输入 8 位配对码，核对来源地址后批准。",
                "When Tunnel connection shows connected and Public address self-test passes, the internet can reach this phone. Copy the MCP address into the cloud client; on the first connection enter the 8-character pairing code in this app's Public MCP page, check the source address, and approve."))
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
    val where = listOfNotNull(if (st.connections > 1) tr("${st.connections} 条连接", "${st.connections} connections") else null, st.location).joinToString(" · ")
    val (title, color) = when (st.state) {
        TunnelState.CONNECTED -> tr("已连上 $p", "Connected to $p") + (if (where.isNotEmpty()) tr("（$where）", " ($where)") else "") to Palette.takeover
        TunnelState.STARTING -> tr("正在连接 $p…", "Connecting to $p…") to Palette.standby
        TunnelState.ERROR -> tr("没有连上 $p", "Not connected to $p") to Palette.stopped
        TunnelState.OFF -> tr("未运行", "Not running") to Palette.stopped
    }
    BasicComponent(title = tr("隧道连接", "Tunnel connection"), summary = title + (st.problem?.let { "\n$it" } ?: ""), endActions = { Dot(color, 8.dp) },
        onClick = if (st.raw != null) ({ raw = !raw }) else null)
    if (raw && st.raw != null) WrappableCode(st.raw!!) { host.copy(tr("详情", "Details"), st.raw!!) }
    val (tTitle, tColor) = when (val r = test) {
        null -> tr("连上后自动检查", "Runs once connected") to MiuixTheme.colorScheme.onSurfaceVariantSummary
        TunnelSelfTest.Running -> tr("正在从公网访问本机…", "Reaching this phone from the internet…") to Palette.standby
        TunnelSelfTest.Ok -> tr("通过：外网已能访问这台手机", "Passed: the internet reaches this phone") to Palette.takeover
        is TunnelSelfTest.Failed -> "${r.title}\n${r.fix}" to Palette.stopped
    }
    BasicComponent(title = tr("公网地址自检", "Public address self-test"), summary = tTitle, endActions = { Dot(tColor, 8.dp) })
    if (st.state == TunnelState.CONNECTED) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        SoftButton(onClick = { Thread({ t.runSelfTest() }, "vscreen-tunnel-test").apply { isDaemon = true }.start() }, modifier = Modifier.fillMaxWidth()) {
            Text(tr("重新自检", "Run the self-test again"), color = MiuixTheme.colorScheme.primary)
        }
    }
    if (mcpUrl != null && test == TunnelSelfTest.Ok) {
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

@Composable
private fun NgrokGuide() {
    Group(tr("适合谁", "Who it suits"), padded = true) {
        Text(tr("没有自己的域名、不想折腾 DNS，也不用 Tailscale；或者用的是 Headscale。注册 ngrok 免费账号即可，免费账号自带一个固定域名，地址形如 https://名字.ngrok-free.app/mcp（新账号是 .ngrok-free.dev）。手机主动连到 ngrok，不需要公网 IP，也不用开端口。",
            "You have no domain, don't want to deal with DNS, and don't use Tailscale; or you use Headscale. A free ngrok account is enough: it comes with one static domain, so the address looks like https://name.ngrok-free.app/mcp (.ngrok-free.dev for newer accounts). The phone dials out to ngrok: no public IP, no open port."),
            fontSize = 13.5.sp, lineHeight = 20.sp)
        Text(tr("要知道：ngrok 会在它的服务器上解开 HTTPS 再转发，技术上看得到请求内容。配对、OAuth 和权限检查仍在手机上进行。免费账号有流量和请求数上限，个人使用一般够用；同一账号同时只能有一个 ngrok 在线。在中国大陆，连接速度因网络而异。",
            "Note: ngrok decrypts HTTPS on its servers before forwarding, so it can technically see requests. Pairing, OAuth and permission checks still happen on the phone. Free accounts have traffic and request limits, usually enough for personal use, and allow one ngrok online at a time. In mainland China, speed varies by network."),
            fontSize = 12.5.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    NgrokSteps(here = false)
    Group(tr("常见问题", "Common problems")) {
        Problem(tr("不认这个 authtoken（ERR_NGROK_105）", "Authtoken not accepted (ERR_NGROK_105)"), tr("重新从 Your Authtoken 复制。在后台重置过令牌后，旧令牌会失效。", "Copy it again from Your Authtoken. Resetting it in the dashboard invalidates the old one."))
        Problem(tr("已有代理在线（ERR_NGROK_108）", "An agent is already online (ERR_NGROK_108)"), tr("免费账号同时只能一个 ngrok 在线：关掉电脑等其它地方的 ngrok，或在后台 Agents 页面断开。", "Free accounts allow one ngrok online at a time: stop ngrok elsewhere, or disconnect it under Agents in the dashboard."))
        Problem(tr("域名不属于这个账号", "Domain not on this account"), tr("填写的域名必须是这个账号 Domains 页面里列出的那个，注意拼写。", "The domain must be the one listed under Domains for this account; check the spelling."))
        Problem(tr("自检：地址离线（ERR_NGROK_3200）", "Self-test: address offline (ERR_NGROK_3200)"), tr("等“隧道连接”变为已连上再自检；一直离线时检查 authtoken 和域名是否属于同一个账号。", "Wait for Tunnel connection to show connected; if it stays offline, check the authtoken and domain are on the same account."))
        Problem(tr("一直连不上 ngrok", "Never connects to ngrok"), tr("开着代理 VPN 时把 Agent-VScreen 设为直连；部分网络会拦截 ngrok，换个网络试试。", "With a proxy VPN, let Agent-VScreen bypass it; some networks block ngrok, try another."))
    }
    Footnote(tr("authtoken 相当于这个 ngrok 账号的钥匙，只保存在本机。怀疑泄露时，在 ngrok 后台重置，再把新令牌粘贴进来。ngrok 后台的菜单名称以其当前界面为准。",
        "The authtoken is the key to this ngrok account and is kept only on this phone. If it may have leaked, reset it in the dashboard and paste the new one. ngrok's menu names may change; follow what it shows now."))
}

@Composable
private fun NgrokSteps(here: Boolean) {
    val open = openUrl()
    val ngrok by Ngrok.config.collectAsState()
    Group(tr("步骤", "Steps")) {
        Step(1, tr("注册 ngrok 账号", "Sign up for ngrok"),
            tr("用邮箱或 GitHub、Google 账号注册（免费），按提示完成验证。不需要下载或安装 ngrok：代理就在这台手机上。", "Sign up with email, GitHub or Google (free) and finish the verification it asks for. No need to download or install ngrok: the agent is this phone."),
            buttons = listOf(Btn(tr("打开 ngrok 注册", "Open ngrok sign-up")) { open(Ngrok.SIGNUP_URL) }), docs = Ngrok.DOCS_URL)
        Step(2, tr("复制 authtoken", "Copy your authtoken"),
            tr("点“打开 Your Authtoken”直接进到后台的 Getting Started › Your Authtoken，点复制，回来点“粘贴并保存”。复制整条 ngrok config add-authtoken 命令也可以。", "Open Your Authtoken goes straight to Getting Started › Your Authtoken in the dashboard; press copy, come back and press Paste and save. Copying the whole ngrok config add-authtoken command works too."),
            buttons = listOf(Btn(tr("打开 Your Authtoken", "Open Your Authtoken")) { open(Ngrok.TOKEN_URL) }),
            field = { GuideField(tr("authtoken", "Authtoken"), ngrok.token?.let { tr("authtoken 已保存", "Authtoken saved") }) { Ngrok.save(it, null) } })
        Step(3, tr("复制你的固定域名", "Copy your static domain"),
            tr("点“打开 Domains”直接进到后台的 Domains 页面（左侧 Universal Gateway › Domains）。免费账号已自动分配一个，形如 名字.ngrok-free.app 或 名字.ngrok-free.dev；列表为空时点“新建域名”领取。复制后回来点“粘贴并保存”。", "Open Domains goes straight to the Domains page (Universal Gateway › Domains on the left). Free accounts already have one, like name.ngrok-free.app or name.ngrok-free.dev; if the list is empty, create one. Copy it, come back and press Paste and save."),
            buttons = listOf(Btn(tr("打开 Domains", "Open Domains")) { open(Ngrok.DOMAINS_URL) }),
            field = { GuideField(tr("固定域名，例如 name.ngrok-free.app", "Static domain, e.g. name.ngrok-free.app"), ngrok.domain) { Ngrok.save(null, it) } })
        Step(4, tr("开启", "Turn it on"),
            tr("上面两项都保存后，", "With both saved above, ") + turnOnWhere(here) + tr("清单全部 ✓，同意风险说明，点“开启公网接入”并验证指纹。", "when the checklist is all ✓, accept the risk note, press Turn on public access and confirm with your fingerprint."),
            buttons = if (here) emptyList() else listOf(backToPublic()))
        Step(5, tr("等两项变绿，再交给客户端", "Wait for two green rows, then give it to the client"),
            tr("“隧道连接”显示已连上、“公网地址自检”通过，就可以复制 MCP 地址添加到云端客户端。第一次连接时手机不会自动弹窗，请在 App 的公网页面输入 8 位配对码，核对来源地址后批准。在浏览器里打开配对页时，ngrok 可能先显示一个提示页，点 Visit Site 继续即可。", "When Tunnel connection shows connected and Public address self-test passes, copy the MCP address into the cloud client. On the first connection enter the 8-character pairing code in this app's Public MCP page, check the source address, and approve. If a browser opens the pairing page, ngrok may show a notice first; press Visit Site to continue."))
    }
}
