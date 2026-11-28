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
            versionCode = 76,
            versionName = "2.0.1-beta2",
            items = listOf(
                Item(Kind.NEW, Audience.USER, "日语界面，语言默认跟随系统",
                    "新增日语界面。语言默认跟随系统：日语系统显示日语，中文系统显示中文，其它显示英文；可在设置 > 通用里改。",
                    "Japanese UI, language follows the system",
                    "Added a Japanese UI. The language follows the system by default: Japanese, Chinese, or English otherwise; change it in Settings > General.",
                    null),
                Item(Kind.IMPROVED, Audience.USER, "设置更简洁，页面对齐统一",
                    "接入功能只在接入页，设置里不再重复；通用排在识别与录制前面。各页标题、边距和分组标题对齐一致；首页滑动后状态胶囊收成小圆点，不再挤掉标题；“全部记录”等入口带箭头；输入框字号统一；任务详情只显示真实截图。",
                    "Cleaner settings and consistent layout",
                    "Connection features live only on the Connect tab; General now comes before Recognition. Page titles, edges and section headers line up across tabs; the home status pill shrinks to a dot when scrolled so the title stays readable; entries like \"All records\" show an arrow; input fields use one text size; task details show only real frames.",
                    null),
            ),
        ),
        Release(
            versionCode = 75,
            versionName = "2.0.1-beta1",
            items = listOf(
                Item(Kind.IMPROVED, Audience.USER, "过长的列表自动折叠",
                    "接入页的 agent、设置里的令牌、agent 页的临时许可和预约、盯守，太长时只显示前几项，点“还有 N 个”展开。接入页按最近使用排在前面。",
                    "Long lists fold",
                    "Connected agents, tokens, temporary allowances, bookings and watches show their first few rows when long; tap \"N more\" to see the rest. Connected agents are listed most recently used first.",
                    Route.Connect),
                Item(Kind.NEW, Audience.USER, "关于项目和快捷反馈",
                    "设置里新增“关于项目”，直达 GitHub 仓库。诊断页可以一键去 GitHub 提问题：诊断信息会先去掉 IP、域名、邮箱和令牌样的字符串，再填进 issue，完整版复制到剪贴板。",
                    "About and quick feedback",
                    "Settings has a new About row that opens the GitHub repository. The diagnostics page can open a GitHub issue in one tap: IPs, host names, emails and token-like strings are removed first, and the full report is copied to the clipboard.",
                    null),
                Item(Kind.NEW, Audience.BOTH, "令牌有效期可选与敏感剪贴板",
                    "新令牌默认长期有效，接入向导里可改为 30 天、7 天或 1 天；命令行、HTTP 和 Operit 还可选 10 分钟单次接入码（avc_），让令牌不进聊天记录和命令历史；复制令牌、接入码和登录链接时自动标记 Android IS_SENSITIVE 隐藏剪贴板预览。",
                    "Selectable token lifetime and sensitive clipboard",
                    "New tokens are permanent by default; the wizard can set 30, 7 or 1 day instead. CLI, HTTP and Operit can also use a 10-minute single-use code (avc_) so the token stays out of chat logs and shell history; copied tokens, codes and login URLs are marked IS_SENSITIVE to hide clipboard previews.",
                    Route.Connect),
                Item(Kind.IMPROVED, Audience.USER, "公网 MCP 配对、OAuth CIMD、空闲关闭与配对隔离",
                    "配对确认卡显示来源地址，同时打开的卡片满额后新请求排队并可用配对码找回；新增可选 3 分钟严格配对窗口，24 小时空闲关闭仅计已认证流量；支持 OAuth 2.1 CIMD（固定已校验 IP + 原主机名 TLS 防 DNS 重绑定，确认卡绿标显示已验证元数据文档来源域名）、同 CIMD URL 独立配对授权与任务隔离、仅设备码重启持久化、RFC 9207 iss 与 30 秒刷新宽限。",
                    "Public MCP pairing, OAuth CIMD, idle close and per-pairing isolation",
                    "Pairing cards show the source address, and requests beyond the open-card limit queue and can be found by code; added an optional 3-minute strict pairing window and authenticated-only 24h idle close; added OAuth 2.1 CIMD (pinned validated-IP TLS with original hostname verification and a verified metadata document domain badge), per-pairing grant and task isolation under shared CIMD URLs, device-only persistence across restarts, RFC 9207 iss, and a 30s refresh retry grace window.",
                    Route.PublicAccess),
                Item(Kind.NEW, Audience.BOTH, "MCP 2026-07-28 双栈、Tasks 扩展与幂等加固",
                    "同时支持无状态 MCP 2026-07-28（server/discover、扁平 ext-tasks 创建/内联结果/原子取消与多步 act 安全续跑）及旧版 initialize 客户端；HTTP 分块 SSE、幂等先查后淘汰、特权服务（协议 19）全事务 UID 强校验与 EXEC 白名单（运行时零 sh -c）。",
                    "Dual-stack MCP 2026-07-28, Tasks extension and idempotency hardening",
                    "Supports both stateless MCP 2026-07-28 (server/discover, flat ext-tasks descriptors with inline terminal results, atomic cancellation, and safe multi-step act continuation) and legacy initialize clients, alongside chunked SSE, lookup-before-evict idempotency, privileged service (protocol 19) UID verification on all transactions, and EXEC allowlisting with zero runtime sh -c.",
                    null),
                Item(Kind.NEW, Audience.BOTH, "付款确认与屏幕文字防注入隔离",
                    "在付款页点确认按钮时按当前同页实时观测自动升为付款强确认；所有第三方屏幕文本、Toast 与回读均经 ScreenText 清洗隔离，与系统说明字段（system_text）严格分离。",
                    "Payment confirmation and screen-text isolation",
                    "Apps automatically escalate confirmation taps to MONEY confirmation using live same-page payment context; third-party screen text, toasts, and readbacks are sanitized via ScreenText and strictly separated from platform system_text.",
                    null),
                Item(Kind.IMPROVED, Audience.BOTH, "熄屏输入路由、保活与两段式过热保护",
                    "新增熄屏三档策略（保持亮屏 / 需要时点亮 / 不亮屏优先无障碍路由），唤醒后直测目标副屏可交互再输入且隔离普通保亮锁与唤醒锁；只读命令不重开回合；过热保护改为两段式温控迟滞与可关闭自动停止开关，并由 LoopWatchdog 托管后台循环防静默失活。",
                    "Screen-off input routing, keep-awake and two-stage thermal protection",
                    "Added three screen-off modes (keep screen on, wake when needed, stay off with accessibility routing), direct virtual-display readiness checks after wake with separate keep-on and wake locks, passive read-only commands that do not reopen turns, two-stage thermal hysteresis with an optional hard-stop toggle, and LoopWatchdog supervision for background loops.",
                    Route.Thermal),
                Item(Kind.NEW, Audience.BOTH, "Shizuku 兼容分支与 Root 回退",
                    "三层动态发现官方 Shizuku、Sui、AXManager、Nightzuku、Shevery 等兼容分支，并加固 API 36/37 Root 回退体验。",
                    "Shizuku fork compatibility and Root fallback",
                    "Three-tier dynamic discovery for Shizuku, Sui, AXManager, Nightzuku, Shevery, and compatible forks alongside hardened API 36/37 Root fallback.",
                    Route.Setup),
            ),
        ),
        Release(
            versionCode = 74,
            versionName = "2.0.0",
            items = listOf(
                Item(Kind.NEW, Audience.BOTH, "任务中保持亮屏",
                    "有些手机上副屏会随主屏一起休眠，手机自动熄屏后 agent 的任务就停住了。设置里新增“任务中保持亮屏”（默认关）：只在 agent 任务进行时让屏幕不自动熄灭（可以变暗），任务结束立刻恢复；按电源键熄屏仍然有效。",
                    "Keep screen on during tasks",
                    "On some phones the background screen sleeps with the main one, so an agent's task stops when the phone times out. New setting (off by default): the phone screen does not time out (it may dim) only while an agent task runs, and is let go when it ends; the power button still turns it off.",
                    null),
                Item(Kind.IMPROVED, Audience.BOTH, "更多手机能熄屏运行",
                    "建副屏时，系统不给“锁屏时也可用”这项权限，就不再连带丢掉“副屏单独供电”，熄屏后副屏仍能单独保持唤醒。诊断和状态里会列出被系统拒绝的副屏选项和原因。",
                    "Screen-off running on more phones",
                    "When the system refuses the always-unlocked option for the background screen, it no longer also loses its own power group, so it can still stay awake with the phone screen off. Diagnostics and status list the display options the system refused, and why.",
                    null),
            ),
        ),
        Release(
            versionCode = 73,
            versionName = "2.0.0-beta16",
            items = listOf(
                Item(Kind.IMPROVED, Audience.AGENT, "输入后等待文字、MCP 工具表与短回复更精简准确",
                    "type 后写 until text:同内容超时时会明确说明输入已成功且条件写错；MCP tools/list 去掉重复描述并隐藏旧写法（zoom/frames）；短回复和差异回复的 structuredContent 只带展示出的元素，meter 增加 structured 体积统计；launch 只列最近用过的 5 个段名。",
                    "Cleaner replies, MCP tool list and type-wait diagnostics",
                    "until text: matching only the box just typed into now states that typing succeeded and why the wait missed; MCP tools/list removes duplicated descriptions and hides old forms (zoom/frames); short and diff replies carry only shown elements in structuredContent with structured size in meter; launch lists only the 5 most recently used segment names.",
                    null),
                Item(Kind.IMPROVED, Audience.AGENT, "元素融合、录像回看、色块与路径回顾修复",
                    "列表行左侧图标被 OCR 误读成 1–2 个字符时归并到行内，不再污染元素表和 collect；look --last 复用与【这段时间】相同的明显变化阈值，静止页不再被闪烁光标填满；spots 过滤无障碍文字并按屏幕比例设定大小范围；task recap 对控制流容器使用简短标记且不切在括号中间；修正英文模式标点与跨 agent 共享说明。",
                    "Cleaner element fusion, replay dedup, spots, recap and English text",
                    "1–2 char OCR icon noise inside clickable list rows is absorbed so it no longer pollutes [Now] or collect; look --last shares [Meanwhile]'s clear-change filter so blinking cursors no longer fill still stretches; spots skips accessibility text and scales size bounds to the screen; task recap records compact control-flow markers without cutting inside brackets; English outputs remove stray fullwidth punctuation and clarify shared notes/segments.",
                    null),
            ),
        ),
        Release(
            versionCode = 72,
            versionName = "2.0.0-beta15",
            items = listOf(
                Item(Kind.IMPROVED, Audience.USER, "待处理的倒计时会走了",
                    "首页和提问页上“还剩几分钟”之前一直停在打开时的数字，现在每秒更新。",
                    "The waiting countdown runs",
                    "The time left on the home page and the question page stayed at the number shown when it opened; it now updates every second.",
                    null),
                Item(Kind.IMPROVED, Audience.USER, "“关闭”服务后 App 真正退出",
                    "首页电源按钮选“关闭”并离开 App 后，进程会结束、从最近任务里消失。开着无障碍时，系统会马上为它单独拉起一个空闲进程，不运行任何服务。",
                    "Turning the service off really ends the app",
                    "After Off and leaving the app, its process ends and it leaves the recents list. With accessibility on, Android starts an idle process for that alone; no service runs.",
                    null),
                Item(Kind.IMPROVED, Audience.USER, "权限说明更准确",
                    "说清了支付和“临时免确认支付”的关系、“每次确认”的应用也能选本轮或今天内，以及经公网接入的 agent 任何档位都不能做系统操作。",
                    "Clearer permission notes",
                    "Says how payments relate to Payments without confirmation, that confirm-every-time apps also take This turn / Today, and that agents over the public internet never get system actions.",
                    Route.Security),
                Item(Kind.IMPROVED, Audience.AGENT, "esc、输入和等待时限的回复更准",
                    "key esc 在不处理 Esc 的页面上会接着按 back 并说明；输入后读不到输入框但画面变了时，回复“未确认”而不是 TYPE_FAILED；等用户时写的有效期和请求本身一致（演示 15 分钟）；swipe/drag 末尾的裸数字当作毫秒时长。",
                    "Truer replies for esc, typing and waiting times",
                    "key esc on a page that ignores Esc goes on to press back and says so; when no input box can be read after typing but the picture changed, the reply says unconfirmed instead of TYPE_FAILED; the waiting block gives the request's own expiry (a demonstration waits 15 minutes); a last bare number in swipe/drag is the duration in ms.",
                    null),
            ),
        ),
        Release(
            versionCode = 71,
            versionName = "2.0.0-beta14",
            items = listOf(
                Item(Kind.IMPROVED, Audience.USER, "agent 的名字不再被截断",
                    "agent 自己报的名字现在完整保存（之前公网接入的超过 16 个字会被截掉，已自动补回）；地方不够时单行滚动显示。",
                    "Agent names are no longer cut",
                    "The name an agent gives itself is now kept in full (public ones were cut at 16 characters before and have been restored); where space is short it scrolls on one line.",
                    Route.Connect),
                Item(Kind.IMPROVED, Audience.USER, "Cloudflare 第 4 步配上了截图",
                    "添加公网域名这一步配了三张标注截图：隧道里的“路由”标签和“添加路由”、选“已发布的应用程序”、表单每一格填什么。",
                    "Screenshots for Cloudflare step 4",
                    "The add-hostname step now has three annotated screenshots: the tunnel's Routes tab and Add route, picking Published application, and what goes in each field.",
                    Route.PublicAccess),
            ),
        ),
        Release(
            versionCode = 70,
            versionName = "2.0.0-beta13",
            items = listOf(
                Item(Kind.IMPROVED, Audience.USER, "公网设置步骤直接显示在页面上",
                    "选好公网通道后，开通步骤直接列在页面上，令牌和域名就在对应步骤里填。每步都有直达后台页面的按钮和官方文档链接；Cloudflare 步骤配了标注截图（点开可放大），并说明了不要进“网络 › 路由”、安装页一直转圈是正常的。",
                    "Public setup steps right on the page",
                    "Once a public channel is picked, its setup steps are listed on the page with the token and domain fields inside their steps. Every step has a button straight to the dashboard page and a link to the official docs; the Cloudflare steps have annotated screenshots (tap to enlarge) and say not to use Networking › Routes and that the spinning install page is expected.",
                    Route.PublicAccess),
                Item(Kind.NEW, Audience.USER, "Agent 在等你时，屏幕顶部会提示",
                    "Agent 发来确认、提问或示教请求时，除了通知，手机屏幕顶部还会出现一个紫色提示条“Agent 在等你处理”，通知静音时也看得到；点它打开首页的“待处理”。",
                    "A bar at the top when an agent waits for you",
                    "When an agent sends a confirmation, question or lesson request, besides the notification a purple bar \"An agent is waiting for you\" shows at the top of the screen, visible even with silent notifications; tap it to open Waiting for you on the home page."),
                Item(Kind.IMPROVED, Audience.USER, "云端 agent 的接入说明更清楚",
                    "给云端 agent 的接入的话改写了：先讲清令牌只能在手机上批准配对后拿到、不能自己编，401 只是还没配对；再按 agent 能做什么分成四种接法（网页添加连接器 / 自己改配置 / 设备码配对，带完整地址和示例请求 / 做不到就直说）。",
                    "Clearer connect message for cloud agents",
                    "The message for cloud agents is rewritten: first that a token only comes from approving the pairing on the phone and must never be made up, and that a 401 just means not paired yet; then four ways by what the agent can do (connector added in web settings / own config / device pairing with every URL and a sample request / say it can't)."),
                Item(Kind.IMPROVED, Audience.AGENT, "请求在手机上等用户时，回复会直接说明",
                    "需要用户在手机上确认、回答或演示时，回复的 next 是 user_on_phone，并带【等用户】一段和 user_action：说明请求已发到手机、用户可能没看到通知，给出一句可直接发给用户的话（告诉他点哪里），然后等用户说好了再原样重发，不要绕。guide 新增“什么时候找用户”。",
                    "Replies say when a request waits for the user on the phone",
                    "When the user must confirm, answer or demonstrate on the phone, next is user_on_phone and the reply carries a [Waiting for the user] block and user_action: the request is on the phone, the user may not have seen the notification, here is a line to send them (saying where to tap); wait until they say it's done, then resend unchanged instead of going around it. The guide has a new When to turn to the user section."),
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
