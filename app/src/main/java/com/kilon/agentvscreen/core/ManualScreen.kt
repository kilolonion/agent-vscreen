package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.S
import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.tr
import com.kilon.agentvscreen.cmd.doLaunch

/**
 * 1.4.2 (rainbow, from the 0-0Nm fork's idea): the user opens the background screen with an app of their choice, no
 * agent needed: to keep an app running out of the way, to set an app up before handing it to an agent, or to get it
 * ready for a lesson.
 *
 * Not a second system: it is an ordinary turn on the one screen, owned by the reserved session [SESSION] (agents can
 * never send that name: ApiServer keeps only [A-Za-z0-9_.:-] in session names), opened with the same launch as
 * `vscreen launch`, and held by the user as a takeover from the start. While the user holds it, agents are told the
 * user is using it (BUSY). When the user hands it back (or the turn ends), the screen idles like any finished turn:
 * the next agent takes it over, page included, or it is released after the usual idle time.
 */
object ManualScreen {
    const val SESSION = "@you"
    /** The task log's "who" for the user's own turns. */
    const val TOKEN = "user"

    fun isManual(session: String?): Boolean = session == SESSION

    /** Open [pkg] on the background screen for the user. Network-free but slow (launch waits for the picture): call off the main thread. */
    fun open(ctx: Context, pkg: String): Result<String> = runCatching {
        val s = BackgroundOperation.state.value
        if (!s.enabled) error(tr(S.manual_screen_the_service_is_off_turn))
        val owner = s.ownerSessionId
        if (s.displayId != null && owner != null && owner != SESSION && BackgroundOperation.isTaskActive(owner))
            error(tr(S.manual_screen_an_agent_is_using_the))
        BackgroundOperation.onTaskStarted(SESSION)
        val t0 = System.currentTimeMillis()
        try {
            // 1.4.2-beta6: the user's own launch: not fenced like an agent's (the user may open an app while the AI is stopped).
            val facts = Control.asUser { with(com.kilon.agentvscreen.cmd.VScreenOffloadHandler(ctx.applicationContext)) { doLaunch(SESSION, pkg, null, false).done.joinToString("; ") } }
            BackgroundOperation.beginTakeover(SESSION)
            BackgroundOperation.setTitle(SESSION, label(ctx, pkg))
            // A task like an agent's: the home page and the task list show this one, not the agent's before it.
            TaskLog.onCommand(SESSION, TOKEN, listOf("launch", pkg), "OK", 0, System.currentTimeMillis() - t0, facts)
            AppLogger.info("ManualScreen", "user opened $pkg: $facts")
            tr(S.manual_screen_opened_on_the_background_screen, label(ctx, pkg))
        } catch (t: Throwable) {
            BackgroundOperation.onTaskFinished(SESSION)
            throw t
        }
    }

    /**
     * The user is done with their screen: it idles like any finished turn (an agent may take it over); [then] runs
     * after (closing the screen). Off the main thread: the turn's end picture is saved and apps may be closed.
     */
    fun end(then: (() -> Unit)? = null) {
        Thread({
            if (BackgroundOperation.state.value.ownerSessionId == SESSION || BackgroundOperation.isTaskActive(SESSION))
                BackgroundOperation.onTaskFinished(SESSION)
            then?.invoke()
        }, "vscreen-manual-end").apply { isDaemon = true }.start()
    }

    private fun label(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
    }.getOrDefault(pkg)
}
