package com.kilon.agentvscreen

import android.content.Context
import com.kilon.agentvscreen.ui.Route

/**
 * 2.0.0-beta8 (H): single source of truth for version changelog entries shared by:
 * 1. The in-app post-upgrade bottom sheet ([Audience.USER] / [Audience.BOTH]) with clickable [Route] navigation.
 * 2. Agent update notices via [com.kilon.agentvscreen.api.AgentNews] ([Audience.AGENT] / [Audience.BOTH]).
 * 3. Release note formatting (grouped into New / Improved).
 */
object Changes {
    enum class Audience { USER, AGENT, BOTH }
    enum class Kind { NEW, IMPROVED }

    data class Item(
        val kind: Kind,
        val audience: Audience,
        val zhTitle: String,
        val zhDetail: String,
        val enTitle: String,
        val enDetail: String,
        val route: Route? = null,
    ) {
        val title: String get() = tr(zhTitle, enTitle)
        val detail: String get() = tr(zhDetail, enDetail)
    }

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val items: List<Item>,
    )

    const val PREFS_NAME = "agent_vscreen_changes"
    const val KEY_LAST_SEEN = "last_seen_changes_code"

    /** Releases ordered newest first. Add a new [Release] entry whenever versionCode bumps. */
    val releases: List<Release> = listOf(
        Release(
            versionCode = 70,
            versionName = "2.0.0-beta13",
            items = listOf(
                Item(Kind.IMPROVED, Audience.USER, "Cloudflare 指南说明更清楚",
                    "说明了 Cloudflare 安装页的“连接状态”为什么一直转圈（连接器在手机里，开启公网后才上线），以及之后去哪里添加域名。",
                    "Clearer Cloudflare guide",
                    "Explains why Connection status on Cloudflare's install page keeps spinning (the connector is this phone and comes online when public access is on), and where to add the hostname next.",
                    Route.PublicAccess),
            ),
        ),
        Release(
            versionCode = 69,
            versionName = "2.0.0-beta12",
            items = listOf(
                Item(Kind.IMPROVED, Audience.USER, "公网设置指南里直接填写",
                    "Cloudflare 和 ngrok 指南的每一步里就能粘贴令牌和域名，复制后回来点“粘贴并保存”，不用退出指南。",
                    "Fill in right in the public setup guide",
                    "In the Cloudflare and ngrok guides, paste the token and domain in the step that asks for them: copy, come back and press Paste and save, without leaving the guide.",
                    Route.PublicAccess),
                Item(Kind.IMPROVED, Audience.USER, "关闭就是彻底关闭",
                    "服务选“关闭”后，公网、远程、定时和副屏服务都停掉，离开应用后进程也退出，后台什么都不留；再打开时之前开着的公网自动恢复。",
                    "Off means off",
                    "Choosing Off stops public and remote access, schedules and the screen service, and the app's process ends once you leave it; public access that was on comes back when you turn the service on again.",
                    null),
                Item(Kind.IMPROVED, Audience.USER, "过热保护",
                    "手机过热时自动停止工作，发热时暂停读屏和后台识别；降温后在首页电源菜单恢复。agent 会被告知是降温暂停，不是故障。",
                    "Heat protection",
                    "When the phone overheats, work stops; while it is hot, screen reading and background recognition pause. Resume from the power menu on Home once it cools. Agents are told it is a cool-down pause, not a fault.",
                    null),
                Item(Kind.IMPROVED, Audience.USER, "返回键和读屏更可靠",
                    "副屏上的返回键按系统导航的方式发出；读屏不再因“忙”频繁换用备用引擎；被上层页面盖住的字不再算作在屏上；小的界面变化（按钮文字、菜单）也算“有变化”；拖动结果按元素判断；正在播放视频的应用冻不住时说明原因。",
                    "Back key and screen reading more reliable",
                    "Back on the screen is sent the way system navigation sends it; screen reading no longer falls back to the spare engine when busy; words covered by a page on top no longer count as shown; small changes (a button label, a menu) count as a change; drag results are judged by the elements; an app playing video that cannot be paused says why.",
                    null),
                Item(Kind.IMPROVED, Audience.AGENT, "步骤写法",
                    "repeat N: 一步 可以不加花括号；文字偏移的第二个数可以不带正负号（tap 热门 +186,0）；type 在没有焦点的输入框时不再动用户剪贴板，直接报 TYPE_FAILED。",
                    "Step forms",
                    "repeat N: <one step> works without braces; the second number of a word offset may go without a sign (tap Hot +186,0); type with no focused input box no longer touches the user's clipboard and reports TYPE_FAILED.",
                    null),
            ),
        ),
        Release(
            versionCode = 68,
            versionName = "2.0.0-beta11",
            items = listOf(
                Item(Kind.NEW, Audience.USER, "agent 申请提权",
                    "agent 可以说明原因，申请临时或永久的更高许可；手机上指纹确认，可选本轮任务内、今天内或永久。命令遇到“不允许”的类别时也用同一张确认卡，不再直接永久放开。",
                    "Agents ask for permission",
                    "An agent can ask, with a reason, for temporary or permanent extra permission; you confirm by fingerprint and pick this turn, today or permanently. A command that meets a Not allowed class uses the same card instead of widening permanently.",
                    null),
                Item(Kind.NEW, Audience.USER, "公网 MCP 新增 Cloudflare 和 ngrok",
                    "除 Tailscale Funnel 外，可选用自己域名的 Cloudflare Tunnel，或免费账号自带固定域名的 ngrok。每种通道都有分步设置指南、连接状态和公网自检。",
                    "Public MCP over Cloudflare and ngrok",
                    "Besides Tailscale Funnel, use Cloudflare Tunnel with your own domain, or ngrok with the free static domain. Each channel has a step-by-step guide, live connection status and a public self-test.",
                    null),
                Item(Kind.IMPROVED, Audience.USER, "过期的请求自动消失",
                    "等你处理里过期的确认和提问会按时自动移除。",
                    "Expired requests leave on their own",
                    "Expired confirmations and questions now leave 等你处理 on time.",
                    null),
                Item(Kind.IMPROVED, Audience.USER, "接入向导显示连接状态",
                    "最后一步显示等待连接、待批准配对或已连上，并有“完成”按钮；接入页列出等待批准的云端配对。",
                    "Connect wizard shows status",
                    "The last step shows waiting, pairing to approve or connected, with a Done button; the Connect page lists cloud pairings waiting for approval.",
                    null),
                Item(Kind.NEW, Audience.USER, "云端 agent 无需浏览器配对",
                    "支持标准设备码配对：没有浏览器的云端或命令行 agent 告诉你配对码，你在手机弹出的卡片上核对批准即可。浏览器授权页批准后会保留可复制的回调地址。",
                    "Cloud agents pair without a browser",
                    "Standard device-code pairing: an agent without a browser gives you a code and you approve it on the card the phone shows. The browser page now keeps a copyable callback address after approval.",
                    null),
                Item(Kind.IMPROVED, Audience.USER, "给 agent 的回复默认英文",
                    "agent 收到的回复、错误和诊断默认用英文，更省 token 也更好解析；屏幕原文不翻译。可在 设置 › 通用 改回跟随界面语言。",
                    "Replies to agents in English by default",
                    "Replies, errors and diagnostics for agents are in English by default; screen text is never translated. Settings › General can switch back to the app language.",
                    null),
                Item(Kind.IMPROVED, Audience.USER, "权限执行更严",
                    "坐标点击也会识别付款按钮；强行停止应用需要确认，清空搜索不再多问；高频操作在一段操作中途也会拦下；你调低权限时同时收回临时许可；公网 agent 的权限取配对时和当前设置中较小的一方。",
                    "Permissions enforced more strictly",
                    "Coordinate taps are checked for payment buttons; force stop asks while clearing a search no longer does; the action-rate limit also stops a stretch midway; lowering permissions also ends temporary allows; a public agent gets the smaller of what it was paired with and what is set now.",
                    Route.Security),
                Item(Kind.IMPROVED, Audience.USER, "拖动和暂停的判断",
                    "关掉一排相似标签中的一个时，结果不再误报为“留在落点/移到别处”；在荣耀等机型上能核实应用是否已暂停。",
                    "Drag and pause results",
                    "Closing one of a row of similar tabs is no longer reported as stayed or moved; pausing an app can be verified on HONOR and similar phones.",
                    null),
            ),
        ),
        Release(
            versionCode = 67,
            versionName = "2.0.0-beta10",
            items = listOf(
                Item(Kind.IMPROVED, Audience.USER, "设置重新分组",
                    "设置按 接入、权限、副屏、识别与录制、通用、更新、记录与诊断 分组；agent 页的分项权限默认收起。",
                    "Settings regrouped",
                    "Settings are grouped into Connections, Permissions, Background screen, Recognition and recording, General, Updates, Records and diagnostics; the per-action table on an agent page is folded by default.",
                    null),
                Item(Kind.IMPROVED, Audience.USER, "用语更规范",
                    "权限类别改为 应用操作、敏感操作、不可撤销操作、支付、系统操作、高频操作；帮助、确认页和通知的说明同步改写。",
                    "Clearer wording",
                    "Permission rows are now App actions, Sensitive, Irreversible, Payments, System and High action rate; help, confirmation and notification texts rewritten to match.",
                    Route.Security),
            ),
        ),
        Release(
            versionCode = 66,
            versionName = "2.0.0-beta9",
            items = listOf(
                Item(Kind.NEW, Audience.USER, "每个 agent 一张权限表",
                    "档位改为 只看 / 稳妥 / 省心 / 全权 / 自定义；每一类操作都能单独设为 允许、问我 或 不允许，改过的行有标记，可一键恢复。",
                    "One permission table per agent",
                    "Levels are now Look only / Steady / Easy / Full / Custom; each kind of action can be set to Allowed, Ask me or Not allowed, changed rows are marked and can be restored.",
                    Route.Connect),
                Item(Kind.IMPROVED, Audience.USER, "确认更轻",
                    "放宽权限和付钱用指纹，其它确认轻点“允许”即可，并可选“这一轮任务内”或“今天”不再问。",
                    "Lighter confirmations",
                    "A fingerprint only to widen what an agent may do or to pay; everything else is one tap on Allow, optionally for this run or today.",
                    Route.Security),
                Item(Kind.IMPROVED, Audience.USER, "接入页不再卡",
                    "修复接入新 agent 时不停生成令牌的问题，并自动清理之前多出来的；令牌和记录列表改为按需加载。",
                    "Connect page no longer lags",
                    "Fixed new-agent setup creating tokens in a loop and removed the extra ones; token and record lists load on demand.",
                    Route.Tokens),
                Item(Kind.IMPROVED, Audience.BOTH, "公网接入更顺",
                    "云端第 4 步也给出“给 agent 的话”；授权码 10 分钟内有效；配对默认档位不再是只看；锁定提醒不重复；诊断里能看到来源地址。",
                    "Smoother public access",
                    "Cloud step 4 gives a message for the agent too; auth codes last 10 minutes; pairing no longer defaults to look-only; lockout alerts don't repeat; diag shows the source address.",
                    Route.PublicAccess),
                Item(Kind.IMPROVED, Audience.AGENT, "被拒时说清原因",
                    "某类操作被设为“不允许”时，回复写明是哪一类、去哪里改，不再提示公网接入去调系统权限。",
                    "Clear refusals",
                    "When a kind of action is not allowed, the reply names it and where the user changes it; public clients are no longer told to adjust system scope."),
            ),
        ),
        Release(
            versionCode = 65,
            versionName = "2.0.0-beta8",
            items = listOf(
                Item(
                    kind = Kind.NEW,
                    audience = Audience.USER,
                    zhTitle = "公网接入前提清单",
                    zhDetail = "开启前一次查全 5 项条件，每项一键处理，回到 App 自动重查；风险同意只需确认一次。",
                    enTitle = "Public access checklist",
                    enDetail = "Checks all 5 prerequisites at once with one-tap fixes, auto-recheck on return, and once-only risk consent.",
                    route = Route.PublicAccess,
                ),
                Item(
                    kind = Kind.NEW,
                    audience = Audience.USER,
                    zhTitle = "安全级别与一次授权",
                    zhDetail = "新增「标准 / 严格」安全级别；接入新 agent 时可直接选权限并用一次确认完成授权，详情页可随时调整。",
                    enTitle = "Security levels & one-shot grant",
                    enDetail = "Choose Standard or Strict security level, pick agent scope during onboarding in one confirmation, and edit scope anytime.",
                    route = Route.Security,
                ),
                Item(
                    kind = Kind.NEW,
                    audience = Audience.USER,
                    zhTitle = "版本更新日志",
                    zhDetail = "每次更新后首次打开显示本版变化，点击条目可直达对应功能；设置里可随时重看。",
                    enTitle = "In-app changelog",
                    enDetail = "Shows what changed on first launch after an update with clickable links to each feature; replayable in Settings.",
                    route = null,
                ),
                Item(
                    kind = Kind.NEW,
                    audience = Audience.USER,
                    zhTitle = "悬浮窗小窗开关",
                    zhDetail = "设置 › 悬浮窗新增「小窗显示按钮」和「点小窗自动放大」开关。",
                    enTitle = "Mini floating window toggles",
                    enDetail = "New Settings › Floating window switches for mini-window buttons and tap-to-expand.",
                    route = Route.FloatingWindow,
                ),
                Item(
                    kind = Kind.IMPROVED,
                    audience = Audience.BOTH,
                    zhTitle = "公网 HTTPS 证书与按来源限流",
                    zhDetail = "修复内置 Tailscale 在 Android 上无法签发 Funnel 证书的问题，开启以拿到证书为准；公网限额按客户端来源分桶，互不影响。",
                    enTitle = "Public HTTPS cert & per-source limits",
                    enDetail = "Fixed built-in Tailscale Funnel certificate issuance on Android and isolated public rate limits per client source.",
                    route = Route.PublicAccess,
                ),
                Item(
                    kind = Kind.IMPROVED,
                    audience = Audience.USER,
                    zhTitle = "接入页三种连接方式平级",
                    zhDetail = "本机、Tailscale、云端（公网 MCP）平级展示；新建 agent 选云端直接走清单，不发令牌。",
                    enTitle = "Peer connection methods",
                    enDetail = "This phone, Tailscale, and Cloud (Public MCP) sit at the same level; cloud onboarding uses the checklist without tokens.",
                    route = Route.Connect,
                ),
                Item(
                    kind = Kind.IMPROVED,
                    audience = Audience.AGENT,
                    zhTitle = "精确滑块搜索与观察后回复",
                    zhDetail = "seek 改为闭环搜索；--hold 先核实冻结再回复并事前拒绝系统进程；until 超时区分未出现、动作前已存在与 OCR 忙；系统 Toast 进入事件流；guide <主题> 等同 help <主题>。",
                    enTitle = "Closed-loop seek & observation-backed replies",
                    enDetail = "seek uses closed-loop search; --hold verifies freeze before replying and refuses system processes upfront; until separates absent / pre-existing / OCR busy; system Toasts feed into events; guide <topic> aliases help <topic>.",
                    route = null,
                ),
                Item(
                    kind = Kind.IMPROVED,
                    audience = Audience.USER,
                    zhTitle = "横屏悬浮窗几何与全屏切回",
                    zhDetail = "统一挖孔屏坐标系修复横屏偏移与边缘球丢失；全屏切回悬浮窗无任务动画，收小窗不盖灰。",
                    enTitle = "Landscape floating window & seamless return",
                    enDetail = "Unified display-cutout coordinates fix landscape offset and edge handle; returning from fullscreen drops task animation and keeps preview clear.",
                    route = Route.FloatingWindow,
                ),
            ),
        ),
        Release(
            versionCode = 64,
            versionName = "2.0.0-beta7",
            items = listOf(
                Item(
                    kind = Kind.NEW,
                    audience = Audience.BOTH,
                    zhTitle = "公网 MCP 接入",
                    zhDetail = "支持通过 Tailscale Funnel 让云端 MCP 客户端连接手机副屏，所有客户端需在手机上配对确认。",
                    enTitle = "Public MCP access",
                    enDetail = "Connect cloud MCP clients through Tailscale Funnel with on-phone pairing confirmation.",
                    route = Route.PublicAccess,
                ),
            ),
        ),
    )

    /** Releases newer than [lastSeenCode] (up to [currentCode]) filtered to user-visible items. */
    fun userReleasesSince(lastSeenCode: Int, currentCode: Int = BuildConfig.VERSION_CODE): List<Release> {
        if (lastSeenCode <= 0 || currentCode <= lastSeenCode) return emptyList()
        return releases
            .filter { it.versionCode in (lastSeenCode + 1)..currentCode }
            .mapNotNull { rel ->
                val kept = rel.items.filter { it.audience == Audience.USER || it.audience == Audience.BOTH }
                if (kept.isEmpty()) null else rel.copy(items = kept)
            }
            .sortedByDescending { it.versionCode }
    }

    /** Latest user-visible release (used for Settings › About › What's new replay). */
    fun allUserReleases(currentCode: Int = BuildConfig.VERSION_CODE): List<Release> =
        releases
            .filter { it.versionCode <= currentCode }
            .mapNotNull { rel ->
                val kept = rel.items.filter { it.audience == Audience.USER || it.audience == Audience.BOTH }
                if (kept.isEmpty()) null else rel.copy(items = kept)
            }
            .sortedByDescending { it.versionCode }

    /** Agent-facing changelog items added after [sinceCode] up to [currentCode]. */
    fun agentItemsSince(sinceCode: Int, currentCode: Int = BuildConfig.VERSION_CODE): List<Pair<Release, Item>> {
        if (currentCode <= sinceCode) return emptyList()
        return releases
            .filter { it.versionCode in (sinceCode + 1)..currentCode }
            .sortedBy { it.versionCode }
            .flatMap { rel ->
                rel.items
                    .filter { it.audience == Audience.AGENT || it.audience == Audience.BOTH }
                    .map { rel to it }
            }
    }

    /**
     * Pure selection logic for automatic post-upgrade display:
     * - Fresh install (`savedCode < 0 && !isExistingInstall`): returns empty (never pops on first install).
     * - Upgrade from a pre-changelog version (`savedCode == 0` or `savedCode < 0 && isExistingInstall`): shows releases at [currentCode].
     * - Upgrade between versions (`savedCode in 1 until currentCode`): shows all releases in `(savedCode..currentCode]`.
     * - Same or older version: returns empty.
     */
    fun selectAutoShow(savedCode: Int, isExistingInstall: Boolean, currentCode: Int): List<Release> = when {
        savedCode < 0 && !isExistingInstall -> emptyList()
        savedCode == 0 || (savedCode < 0 && isExistingInstall) -> userReleasesSince(currentCode - 1, currentCode)
        savedCode in 1 until currentCode -> userReleasesSince(savedCode, currentCode)
        else -> emptyList()
    }

    /**
     * Must run early in [com.kilon.agentvscreen.host.VScreenApp.onCreate] BEFORE [com.kilon.agentvscreen.api.ApiConfig.init],
     * so an empty `agent_vscreen_api` prefs file distinguishes a genuine fresh install from an upgrade over a
     * pre-changelog release (like beta7) that already populated its API token.
     */
    fun init(ctx: Context, currentCode: Int = BuildConfig.VERSION_CODE) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LAST_SEEN)) return
        val existing = isExistingInstall(ctx)
        prefs.edit().putInt(KEY_LAST_SEEN, if (existing) 0 else currentCode).apply()
    }

    /** Inspect prefs on home launch; records fresh install without showing, or returns releases to display after upgrade. */
    fun consumeLaunchAutoShow(ctx: Context, currentCode: Int = BuildConfig.VERSION_CODE): List<Release> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getInt(KEY_LAST_SEEN, -1)
        val existing = if (saved < 0) isExistingInstall(ctx) else true
        val toShow = selectAutoShow(saved, existing, currentCode)
        if (saved < 0 && !existing) {
            // Fresh install: record current version silently so the next upgrade triggers the sheet.
            prefs.edit().putInt(KEY_LAST_SEEN, currentCode).apply()
        }
        return toShow
    }

    fun markSeen(ctx: Context, versionCode: Int = BuildConfig.VERSION_CODE) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_SEEN, versionCode)
            .apply()
    }

    private fun isExistingInstall(ctx: Context): Boolean {
        val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        if (pi != null && pi.lastUpdateTime > pi.firstInstallTime) return true
        val apiPrefs = ctx.getSharedPreferences("agent_vscreen_api", Context.MODE_PRIVATE)
        return apiPrefs.all.isNotEmpty()
    }

    fun versionNameOf(code: Int): String? =
        releases.firstOrNull { it.versionCode == code }?.versionName
}
