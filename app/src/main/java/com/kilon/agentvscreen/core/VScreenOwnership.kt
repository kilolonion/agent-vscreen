package com.kilon.agentvscreen.core

import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 1.4.0-beta2: who has each app the agent uses. Three owners only:
 *  - the agent, on the background screen (no entry here);
 *  - the user, on the background screen (the existing takeover: BackgroundOperation.takeoverSessionId);
 *  - the user, on the main screen ([given]): handed out with `handoff out`, or pulled there by the user (opening the
 *    app from the launcher or recents moves a single-instance app's task off our screen).
 * Every way in (agent command, launcher, overlay button, notification) only changes this; the overlay, the replies and
 * the events read it. Detection compares task ids and display ids, never package names or app kinds, so it is the
 * same for every app.
 */
object VScreenOwnership {
    private const val TAG = "VScreenOwnership"
    /** How long after the user leaves a handed-over app before one notification asks whether to hand it back. */
    private const val NUDGE_MS = 30_000L

    data class Given(
        val pkg: String, val taskId: Int, val session: String, val at: Long,
        /** "agent" (handoff out), "user" (the user opened it on the main screen) or "ours" (our action moved it there). */
        val why: String,
        /** The activity the app was on when it left the background screen. */
        val page: String?,
        val leftAt: Long = 0L, val nudged: Boolean = false,
        /** The agent launched another app after this one left: its actions are about that app now. */
        val agentMovedOn: Boolean = false,
    )

    private val _given = MutableStateFlow<Map<String, Given>>(emptyMap())
    val given: StateFlow<Map<String, Given>> = _given.asStateFlow()

    /** Notes for an agent's next reply (session → lines), e.g. "the user also opened X on the main screen". */
    private val pendingNotes = HashMap<String, MutableList<String>>()
    /** Packages already said to be open on both screens, per session (said once). */
    private val bothSaid = HashMap<String, MutableSet<String>>()

    @Volatile private var thread: Thread? = null
    /** Tasks seen on the background screen at the last tick: task id → package. */
    @Volatile private var lastOnScreen: Map<Int, String> = emptyMap()

    fun label(ctx: Context, pkg: String): String =
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** The entry that blocks [session]'s actions now: an app of its that the user has, and it has not moved on. */
    fun blocking(session: String?): Given? = session?.let { s -> _given.value.values.firstOrNull { it.session == s && !it.agentMovedOn } }

    fun givenFor(pkg: String): Given? = _given.value[pkg]

    /** The agent launched [pkg]: entries of other apps stop blocking it (it works on another app meanwhile). */
    fun agentLaunched(session: String, pkg: String) {
        _given.update { m -> m.mapValues { (k, g) -> if (g.session == session && k != pkg) g.copy(agentMovedOn = true) else g } }
    }

    fun takeNotes(session: String): List<String> = synchronized(pendingNotes) { pendingNotes.remove(session).orEmpty() }

    /** `handoff out` moved [pkg] (task [taskId]) to the main screen on purpose. */
    fun handedOut(ctx: Context, session: String, pkg: String, taskId: Int, page: String?) = record(ctx, Given(pkg, taskId, session, System.currentTimeMillis(), "agent", page))

    private fun record(ctx: Context, g: Given) {
        _given.update { it + (g.pkg to g) }
        AppLogger.info(TAG, "${g.pkg} (task ${g.taskId}) is with the user on the main screen (${g.why})")
        val app = label(ctx, g.pkg)
        if (g.why != "ours") com.kilon.agentvscreen.host.quiet("handover-event") {
            com.kilon.agentvscreen.api.EventHub.post(ctx, g.session, "handover", g.pkg,
                if (g.why == "user") tr("用户在主屏打开了 $app，它已经交给用户（副屏上没有它了）。在用户交回之前不要操作 $app；用 vscreen events --wait 等 handback，或去做别的应用。",
                    "The user opened $app on the main screen; it is with the user now (no longer on the background screen). Don't operate $app until the user hands it back; wait for handback with vscreen events --wait, or work on another app.")
                else tr("$app 已交给用户。用户交回时会有 handback 事件。", "$app is with the user. A handback event comes when the user hands it back."))
        }
        com.kilon.agentvscreen.ui.Overlay.refresh()
    }

    /**
     * Hand [pkg] back to the agent: its task goes back to the background screen on the page the user left it. When the
     * task is gone (closed on the main screen, app restarted) nothing is moved and the agent is told to launch again.
     * [by]: "overlay", "notification" or "agent". Returns what happened, for the caller to show.
     */
    fun handBack(ctx: Context, pkg: String, by: String): String {
        val g = _given.value[pkg] ?: return tr("$pkg 不在你手上", "$pkg is not with you")
        val vs = BackgroundOperation.state.value.displayId
        val onMain = runCatching { com.kilon.agentvscreen.core.VScreenHandoff.tasksOn(0) }.getOrDefault(emptyList()).any { it.taskId == g.taskId }
        val app = label(ctx, pkg)
        val (moved, line) = when {
            vs == null -> false to tr("副屏已经释放，agent 下次 launch $app 时重新打开", "The screen was released; the agent's next launch of $app opens it again")
            !onMain -> false to tr("$app 原来的页面已经没有了（在主屏关掉了或应用重启了），agent 下次 launch 时从首页打开", "$app's page is gone (closed on the main screen or the app restarted); the agent's next launch opens it from its home page")
            else -> {
                val m = com.kilon.agentvscreen.core.VScreenHandoff.move(g.taskId, pkg, vs)
                if (m.ok) true to tr("$app 已移回副屏，停在你最后的页面", "$app is back on the background screen, on the page you left it")
                else false to tr("系统没让 $app 移回副屏（${m.detail}）", "The system did not move $app back (${m.detail})")
            }
        }
        if (!moved && vs != null && onMain) return line // nothing changed: it stays with the user
        _given.update { it - pkg }
        runCatching { ctx.getSystemService(android.app.NotificationManager::class.java)?.cancel(nudgeId(pkg)) }
        if (moved) runCatching { BackgroundOperation.currentDisplay()?.requestFreshFrame() }
        val now = if (moved && vs != null) runCatching { VScreenShell.topActivity(vs) }.getOrNull() else null
        AppLogger.info(TAG, "$pkg handed back by $by (moved=$moved)")
        com.kilon.agentvscreen.host.quiet("handback-event") {
            com.kilon.agentvscreen.api.EventHub.post(ctx, g.session, "handback", pkg, line + "。" +
                (if (moved) tr("现在的页面 ${now ?: "?"}" + (if (now != null && g.page != null && now != g.page) "（交出时是 ${g.page}，用户换过页面）" else "") + "。先看一眼现在的画面再继续。",
                    "Page now: ${now ?: "?"}" + (if (now != null && g.page != null && now != g.page) " (it was ${g.page} when handed over; the user moved on)" else "") + ". Look at the screen before going on.")
                else tr("launch $pkg 重新打开。", "launch $pkg to open it again.")))
        }
        com.kilon.agentvscreen.ui.Overlay.refresh()
        return line
    }

    // ── stretches: the one place that decides who moved an app between the screens ─────────────────────────────

    /** An agent command that may move things (launch, act, stop, release): moves seen while it runs are ours. */
    private class Stretch(val session: String, val pkgs: Set<String>, val at: Long = System.currentTimeMillis())
    private val open = HashMap<Long, Stretch>()
    private var nextToken = 1L
    /** The last stretch that ended and when: an app that opens a page a moment after the last tap is still ours. */
    private var lastEnded: Pair<Stretch, Long>? = null
    private const val GRACE_MS = 1_500L
    /** Lines with code MAIN_SCREEN_CHANGED for the session's current reply. */
    private val leaks = HashMap<String, MutableList<String>>()

    /** A command of [session] that may touch apps [pkgs] starts. Returns the token for [stretchEnd]. */
    fun stretchBegin(ctx: Context, session: String, pkgs: Collection<String?>): Long {
        val known = pkgs.filterNotNull().filter { it != ctx.packageName }.toSet()
        runCatching { tick(ctx) } // the baseline: what moved before this stretch is not ours
        // A session runs one command at a time: a stretch of it still open was left by a command that threw.
        return synchronized(this) { open.values.removeAll { it.session == session }; val t = nextToken++; open[t] = Stretch(session, known); t }
    }

    /** The stretch ends: one last look, then what of ours reached the main screen during it (null when nothing). */
    fun stretchEnd(ctx: Context, token: Long): String? {
        val st = synchronized(this) { open.remove(token)?.also { lastEnded = it to System.currentTimeMillis() } } ?: return null
        runCatching { tick(ctx) }
        return synchronized(leaks) { leaks.remove(st.session) }?.distinct()?.joinToString(tr("；", "; "))
    }

    /** The stretch a move seen now belongs to: one running, or one that ended within [GRACE_MS]. */
    @Synchronized private fun stretchNow(): Stretch? = open.values.also { v -> v.removeAll { System.currentTimeMillis() - it.at > 10 * 60_000L } }.lastOrNull()
        ?: lastEnded?.takeIf { System.currentTimeMillis() - it.second < GRACE_MS }?.first

    private fun leak(session: String, ctx: Context, pkg: String, frontBefore: String?, frontNow: String?) {
        val app = label(ctx, pkg)
        synchronized(leaks) { leaks.getOrPut(session) { ArrayList() } += tr("副屏这边的 $app 跑到了主屏（主屏前台 ${frontBefore ?: "?"} → ${frontNow ?: "?"}）：用户正在用的主屏被我们打扰了。停下，告诉用户；要继续，handoff in 把它拿回副屏",
            "$app from our screen came to the main screen (front app ${frontBefore ?: "?"} → ${frontNow ?: "?"}): the screen the user is using was disturbed by us. Stop and tell the user; to go on, take it back with handoff in") }
    }

    /** The screen is gone: nothing is with the user any more, no hand-back to ask for. */
    fun clear(ctx: Context, why: String) {
        val had = _given.value
        if (had.isEmpty() && lastOnScreen.isEmpty()) return
        _given.value = emptyMap()
        synchronized(this) { lastOnScreen = emptyMap() }
        synchronized(bothSaid) { bothSaid.clear() }
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        for (g in had.values) runCatching { nm?.cancel(nudgeId(g.pkg)) }
        if (had.isNotEmpty()) AppLogger.info(TAG, "cleared ${had.keys} ($why)")
        com.kilon.agentvscreen.ui.Overlay.refresh()
    }

    /** One notification per handed-over app ("yours now", later "done with it?"); clear of the watch (7300+) and ask (7400–7899) ids. */
    fun nudgeId(pkg: String) = 8100 + (pkg.hashCode() and 0xff)

    fun handBackIntent(ctx: Context, pkg: String): android.app.PendingIntent = android.app.PendingIntent.getBroadcast(ctx, nudgeId(pkg),
        android.content.Intent(ctx, com.kilon.agentvscreen.host.NoteActions::class.java).setAction(com.kilon.agentvscreen.host.HostService.ACTION_HAND_BACK).putExtra("pkg", pkg),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

    /** Start watching (idempotent). Runs while the service runs; ticks only do work while there is a screen. */
    fun start(ctx: Context) {
        if (thread?.isAlive == true) return
        val app = ctx.applicationContext
        BackgroundOperation.onScreenReleased { clear(app, "screen released") }
        thread = Thread({
            while (true) {
                try { Thread.sleep(1_000); tick(app) } catch (_: InterruptedException) { break } catch (t: Throwable) { com.kilon.agentvscreen.host.Swallowed.note("ownership", t) }
            }
        }, "vscreen-ownership").apply { isDaemon = true; start() }
    }

    /** Main-screen tasks at the last tick (null before the first one: nothing is "new" then). */
    private var lastMain: Map<Int, String>? = null
    private var lastFront: String? = null

    /**
     * One look at both screens. Every "an app of ours is on the main screen now" goes through here, once:
     *  - a task of ours moved to the main screen: during a stretch our action did it (MAIN_SCREEN_CHANGED in that reply,
     *    and the app counts as with the user until handoff in); otherwise the user pulled it (handover);
     *  - a new task of an app of ours came to the front of the main screen: during a stretch our action opened it
     *    there; otherwise the user opened their own copy (a note, once).
     * When either screen's task list cannot be read the round is skipped: unknown is not "gone".
     */
    @Synchronized private fun tick(ctx: Context) {
        val st = BackgroundOperation.state.value
        val vs = st.displayId
        val stretch = stretchNow()
        val session = st.ownerSessionId ?: stretch?.session
        val main = VScreenHandoff.tasksOrNull(0) ?: return
        val onScreen = if (vs == null) emptyMap() else (VScreenHandoff.tasksOrNull(vs) ?: return).associate { it.taskId to it.pkg }
        val mainIds = main.associate { it.taskId to it.pkg }
        val front = main.firstOrNull { it.visible }
        val frontBefore = lastFront
        if (session != null) {
            val seen = see(lastOnScreen, onScreen, mainIds, lastMain, front, stretch?.pkgs.orEmpty(), _given.value.keys)
            for ((id, pkg) in seen.moved) {
                if (stretch != null) leak(session, ctx, pkg, frontBefore, front?.pkg)
                record(ctx, Given(pkg, id, session, System.currentTimeMillis(), if (stretch != null) "ours" else "user",
                    runCatching { synchronized(com.kilon.agentvscreen.cmd.VScreenOffloadHandler.lastActivity) { vs?.let { com.kilon.agentvscreen.cmd.VScreenOffloadHandler.lastActivity[it] } } }.getOrNull()))
            }
            if (front != null && seen.came) {
                if (stretch != null) leak(session, ctx, front.pkg, frontBefore, front.pkg)
                else if (front.pkg in onScreen.values) {
                    val first = synchronized(bothSaid) { bothSaid.getOrPut(session) { HashSet() }.add(front.pkg) }
                    if (first) synchronized(pendingNotes) { pendingNotes.getOrPut(session) { ArrayList() } += tr("用户也在主屏上用 ${label(ctx, front.pkg)}（另一份，和副屏这份互不影响）：不要做会影响用户那份的事（退出登录、清数据、改账号设置）。",
                        "The user is also using ${label(ctx, front.pkg)} on the main screen (a separate copy; the two don't affect each other): don't do things that would affect theirs (log out, clear data, change account settings).") }
                }
            }
        }
        // A handed-over task back on our screen by another way (the agent's handoff in, recents): it is the agent's again.
        for (g in _given.value.values) if (g.taskId in onScreen) {
            _given.update { it - g.pkg }
            runCatching { ctx.getSystemService(android.app.NotificationManager::class.java)?.cancel(nudgeId(g.pkg)) }
            com.kilon.agentvscreen.ui.Overlay.refresh()
        }
        lastOnScreen = onScreen
        lastMain = mainIds
        lastFront = front?.pkg
        checkLeft(ctx)
    }

    /** What one look found: tasks of ours now on the main screen, and whether the main screen's new front task is an app of ours. */
    internal data class Seen(val moved: List<Pair<Int, String>>, val came: Boolean)

    /** Pure: compare two looks. [stretchPkgs]: apps the running command works on; [given]: apps already with the user. */
    internal fun see(lastOnScreen: Map<Int, String>, onScreen: Map<Int, String>, mainIds: Map<Int, String>, prevMain: Map<Int, String>?,
            front: VScreenHandoff.TaskRef?, stretchPkgs: Set<String>, given: Set<String>): Seen {
        val moved = lastOnScreen.filter { (id, pkg) -> id !in onScreen && mainIds[id] == pkg && pkg !in given }.map { it.key to it.value }
        val ours = onScreen.values.toSet() + lastOnScreen.values + stretchPkgs
        val came = front != null && prevMain != null && front.taskId !in prevMain && front.taskId !in onScreen &&
            front.pkg in ours && front.pkg !in given && moved.none { it.first == front.taskId }
        return Seen(moved, came)
    }

    /** The user left a handed-over app: after [NUDGE_MS] one notification asks whether to hand it back. Never automatic. */
    private fun checkLeft(ctx: Context) {
        if (_given.value.isEmpty()) return
        val top = runCatching { VScreenShell.topPackage(0) }.getOrNull()
        val now = System.currentTimeMillis()
        for (g in _given.value.values) {
            val away = top != null && top != g.pkg
            when {
                !away && g.leftAt != 0L -> _given.update { it + (g.pkg to g.copy(leftAt = 0L)) }
                away && g.leftAt == 0L -> _given.update { it + (g.pkg to g.copy(leftAt = now)) }
                away && !g.nudged && now - g.leftAt >= NUDGE_MS -> {
                    _given.update { it + (g.pkg to g.copy(nudged = true)) }
                    val app = label(ctx, g.pkg)
                    val who = com.kilon.agentvscreen.api.Agents.nameOfSession(g.session)
                    VScreenHandoff.alert(ctx, nudgeId(g.pkg), tr("$app 用完了吗？", "Done with $app?"),
                        tr("「$who」在等你把 $app 交回去。用完了就点“交回”，它会从你离开的页面接着做；也可以点开 Agent-VScreen 处理。",
                            "\"$who\" is waiting for $app. When you are done, tap Hand back and it carries on from where you left it, or open Agent-VScreen."),
                        com.kilon.agentvscreen.host.Needs.open(ctx, com.kilon.agentvscreen.host.Needs.Page.HOME, 7),
                        listOf(tr("交回", "Hand back") to handBackIntent(ctx, g.pkg)))
                }
            }
        }
    }
}
