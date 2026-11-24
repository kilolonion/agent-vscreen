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
