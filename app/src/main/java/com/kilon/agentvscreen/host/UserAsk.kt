package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * 1.3: the one way an agent asks the user something on the phone.
 *
 * 1.2 had this only for yes/no ([Consent]: pay, force-stop …). Agents also need answers that words carry badly: "which
 * of these do you mean?" is one tap on the picture, not a paragraph. So the question is one thing with a kind:
 *  - CONFIRM  allow / deny (what [Consent] asks; its rules — what asks at which autonomy level, pre-authorization —
 *             stay in [Consent], the asking itself is here);
 *  - POINT    tap one place on a picture of the background screen;
 *  - AREA     drag a box on it;
 *  - CHOOSE   one of a few given options;
 *  - TEXT     a short line;
 *  - TEACH    show it once on the background screen (1.3 示教): the answer is the steps recorded.
 * One list of open questions (the app's cards and the notifications show it), one notification channel, one way to
 * answer, one wait that stops when the caller goes away. Every answer is written to the audit log.
 */
object UserAsk {
    enum class Kind(val key: String) { CONFIRM("confirm"), POINT("point"), AREA("area"), CHOOSE("choose"), TEXT("text"), TEACH("teach");
        companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } } }

    const val VALID_MS = 10 * 60_000L
    private const val CHANNEL = "agent_vscreen_consent"
    const val ACTION = "com.kilon.agentvscreen.CONSENT"

    class Ask(val id: Int, val kind: Kind, val key: String, val token: String, val tokenName: String, val session: String,
              val title: String, val detail: String, val at: Long, val reusable: Boolean,
              val options: List<String> = emptyList(),
              /** POINT / AREA: the picture the user answers on, and the screen size its pixels stand for. */
              val picture: File? = null, val screenW: Int = 0, val screenH: Int = 0,
              val validMs: Long = VALID_MS) {
        /** CONFIRM: null = not answered; true = allowed; false = denied. Other kinds: true = answered ([reply]), false = declined. */
        @Volatile var answer: Boolean? = null
        @Volatile var reply: JSONObject? = null
        @Volatile var answeredAt = 0L
        @Volatile var used = false
        /** 1.4.0-beta12: what the user said to the agent while showing a lesson (the lesson goes on). */
        val said = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var saidSeen = 0
        /** Open: not answered, and within its time, or taken by a lesson under way (which has no time limit). */
        val live: Boolean get() = answer == null && (System.currentTimeMillis() - at < validMs || id == heldId)
    }

    internal val lock = Object()
    private val asks = ArrayList<Ask>()
    private var seq = 100
    private val _open = MutableStateFlow<List<Ask>>(emptyList())
    /** Questions waiting for the user (the app shows them too). */
    val open: StateFlow<List<Ask>> = _open.asStateFlow()

    /**
     * 1.3.0-beta14: the question the user is answering right now (a lesson being recorded or reviewed). It stays open
     * for the agent, but no card shows it: the user already took it. Released when that work ends without an answer.
     */
    @Volatile var heldId: Int = -1; private set
    /**
     * 1.4.0-beta10 (rainbow: the card came back after a lesson; the lesson was lost): the question a lesson is
     * answering right now. Set only by [com.kilon.agentvscreen.cmd.Teach.sync], from the lesson's own state (getting
     * ready, recording, waiting to be saved), so it can never be left over. While bound, the question is the lesson's:
     * no card shows it, and the agent's turn ending, its command going away or the time running out do not end it.
     * Only the user ends it: saved (answered), discarded or skipped (declined).
     */
    internal fun bind(id: Int?) = synchronized(lock) { heldId = id ?: -1; publish() }
    private fun bound(a: Ask) = a.id == heldId && a.answer == null
    /** The user has taken this question into a lesson (getting ready, recording or saving). */
    fun taken(a: Ask) = bound(a)

    /** Republishes [open] when the earliest unanswered question runs out of time (see [ExpiryWake]). */
    private val expiry = ExpiryWake("ask") { synchronized(lock) { prune(System.currentTimeMillis()); publish(); lock.notifyAll() } }
    /** When the next shown question runs out of time, or null when none is shown (tests). */
    internal val nextExpiryAt: Long? get() = expiry.dueAt

    /** The only writer of [open]: publishes the shown questions and arms the wake for the earliest expiry. */
    private fun publish() {
        val shown = asks.filter { it.live && it.id != heldId }
        _open.value = shown
        expiry.at(shown.minOfOrNull { it.at + it.validMs }, System.currentTimeMillis())
    }

    private fun prune(now: Long) {
        val gone = asks.filter { !bound(it) && now - it.at > it.validMs && (it.answer == null || now - it.answeredAt > it.validMs) }
        gone.forEach { a -> a.picture?.delete() }
        asks.removeAll(gone.toSet())
    }

    /** The question with [key] of [token] that is still open or answered within its validity. */
    fun find(key: String, token: String): Ask? = synchronized(lock) {
        prune(System.currentTimeMillis())
        asks.lastOrNull { it.key == key && it.token == token && !(it.used && !it.reusable) }
    }

    /** Live asks of [kind], held or not. */
    fun liveOf(kind: Kind): List<Ask> = synchronized(lock) { asks.filter { it.kind == kind && it.live } }

    fun byId(id: Int): Ask? = synchronized(lock) { asks.firstOrNull { it.id == id } }

    /** Put a new question in front of the user. */
    fun create(ctx: Context, kind: Kind, key: String, title: String, detail: String, reusable: Boolean = false,
               options: List<String> = emptyList(), picture: File? = null, screenW: Int = 0, screenH: Int = 0, validMs: Long = VALID_MS): Ask {
        val call = Call.current()
        val a = synchronized(lock) {
            prune(System.currentTimeMillis())
            Ask(++seq, kind, key, call?.tokenId ?: "main", call?.tokenName ?: "main", call?.session ?: "default",
                title, detail, System.currentTimeMillis(), reusable, options, picture, screenW, screenH, validMs).also { asks += it; publish() }
        }
        post(ctx, a)
        return a
    }

    /** Wait up to [waitMs] for [a] to be answered; returns early when the caller went away. True = it was answered. */
    fun await(a: Ask, waitMs: Long): Boolean {
        val call = Call.current()
        val t0 = SystemClock.elapsedRealtime()
        synchronized(lock) {
            while (a.answer == null && SystemClock.elapsedRealtime() - t0 < waitMs && call?.cancelled != true) lock.wait(500)
        }
        return a.answer != null
    }

    fun markUsed(a: Ask) = synchronized(lock) { a.used = true; publish() }

    /**
     * 1.4.0-beta12 (rainbow: 示教中“说一句”有问题): a word to the agent in the middle of a lesson is a message, not an
     * answer. Before, it answered (ended) the request: the agent got USER_FEEDBACK while the user went on recording a
     * lesson that no longer answered anything, and every later send of the same teach returned that feedback again.
     * Now the request stays open, the agent hears it at once (events) and in its next teach reply.
     */
    fun say(ctx: Context, id: Int, text: String): Boolean {
        val a = synchronized(lock) { asks.firstOrNull { it.id == id && it.answer == null } } ?: return false
        a.said += text.trim().take(300)
        told(ctx, a, tr(S.user_ask_while_showing_the_lesson_the, text.trim().take(300)))
        return true
    }

    /** The user's answer (notification action, the card in the app, or the answer page). */
    fun answer(ctx: Context, id: Int, allow: Boolean, reply: JSONObject? = null) {
        val a = synchronized(lock) {
            val a = asks.firstOrNull { it.id == id && it.answer == null } ?: return
            a.reply = reply; a.answer = allow; a.answeredAt = System.currentTimeMillis()
            publish(); lock.notifyAll(); a
        }
        com.kilon.agentvscreen.cmd.Teach.askEnded(id, if (reply?.optString("kind") == "feedback") tr(S.user_ask_you_answered_in_words) else null)
        val what = when {
            reply?.optString("kind") == "feedback" -> "feedback"
            a.kind == Kind.CONFIRM -> if (allow) "allowed" else "denied"
            else -> if (allow) "answered ${a.kind.key}" else "declined"
        }
        Audit.consent(a.tokenName, a.session, "${a.title} — ${a.detail}", what)
        told(ctx, a, if (allow) tr(S.user_ask_the_user_answered_send_the, a.kind.key)
            else tr(S.user_ask_the_user_declined, a.kind.key))
        quiet("ask-cancel") { ctx.getSystemService(NotificationManager::class.java)?.cancel(noteId(id)) }
    }

    /**
     * 1.3.0-beta14: the background screen is gone (released, closed by the user). Questions answered ON that screen
     * (point, area, lesson) cannot be answered any more: they end now, with [reason], instead of waiting out their
     * 10–15 minutes while the agent is told "waiting for the user".
     */
    fun screenGone(ctx: Context?, reason: String) {
        val ended = synchronized(lock) {
            val now = System.currentTimeMillis()
            // The lesson's own question is left to the lesson (Teach.screenClosed: recording becomes a lesson to save,
            // getting ready ends as not shown): what was recorded does not need the screen to be saved.
            asks.filter { it.answer == null && it.kind in setOf(Kind.POINT, Kind.AREA, Kind.TEACH) && !bound(it) }.onEach {
                it.reply = JSONObject().put("ended", reason); it.answer = false; it.answeredAt = now
            }.also { if (it.isNotEmpty()) { publish(); lock.notifyAll() } }
        }
        ended.forEach { a ->
            Audit.consent(a.tokenName, a.session, "${a.title} — ${a.detail}", "ended: $reason")
            ctx?.let { told(it, a, tr(S.user_ask_the_background_screen_was_closed, reason)) }
            ctx?.let { c -> quiet("ask-ended") { c.getSystemService(NotificationManager::class.java)?.cancel(noteId(a.id)) } }
        }
    }

    /**
     * 1.3.0-beta16: an answer is also an event for the session that asked (`vscreen events`), so an agent that is not
     * blocked in the command hears about it (a lesson finished, a point picked) without polling.
     */
    private fun told(ctx: Context, a: Ask, text: String) = quiet("ask-event") {
        com.kilon.agentvscreen.api.EventHub.post(ctx, a.session, "ask", a.title.take(60), text)
    }

    /** Questions of [session] still waiting for the user (events can wait for their answers). */
    fun openFor(session: String): Int = synchronized(lock) { asks.count { it.session == session && it.live } }

    /** Why a declined question ended without the user (the screen went away), or null when the user declined. */
    fun endedWhy(a: Ask): String? = a.reply?.optString("ended")?.takeIf { it.isNotBlank() }

    /** The caller went away before an answer: take the question off the phone. */
    fun withdraw(ctx: Context, a: Ask) {
        // The user is answering it right now (a lesson under way or waiting to be saved): it stays.
        synchronized(lock) { if (bound(a)) return; if (a.answer == null) { asks.remove(a); a.picture?.delete(); publish() } }
        com.kilon.agentvscreen.cmd.Teach.askEnded(a.id, tr(S.user_ask_the_agent_withdrew_it))
        quiet("ask-withdraw") { ctx.getSystemService(NotificationManager::class.java)?.cancel(noteId(a.id)) }
    }

    /**
     * 1.4.0-beta5: the agent's turn is over (done, released, quiet): its unanswered requests about the screen (a
     * lesson, a point, an area) go with it; they only make sense on the page the agent was on. Before, they stayed live
     * for 10–15 minutes and came back on the screen page long after the agent had stopped asking. Plain questions
     * (choose, text, confirm) keep their time: the agent may still send them again for the answer.
     */
    fun withdrawSession(ctx: Context, session: String) {
        val gone = synchronized(lock) { asks.filter { it.session == session && it.answer == null && it.kind in setOf(Kind.TEACH, Kind.POINT, Kind.AREA) } }
        gone.forEach { withdraw(ctx, it) }
    }

    private fun noteId(id: Int) = 7400 + (id % 500)

    private fun post(ctx: Context, a: Ask) {
        quiet("ask-notify") {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return@quiet
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(S.user_ask_questions_that_need_your_answer), NotificationManager.IMPORTANCE_HIGH))
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(false).setTimeoutAfter(a.validMs)
            if (a.kind == Kind.CONFIRM) {
                fun pi(allow: Boolean) = PendingIntent.getBroadcast(ctx, a.id * 2 + (if (allow) 1 else 0),
                    Intent(ctx, NoteActions::class.java).setAction(ACTION).putExtra("id", a.id).putExtra("allow", allow),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val open = Needs.open(ctx, Needs.Page.HOME, 6)
                val body = tr(S.user_ask_from_session, a.detail, a.tokenName, a.session)
                b.setContentTitle(tr(S.user_ask_ai_asks, a.title))
                    .setContentText(a.detail).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(open)
                    .addAction(0, tr(S.ask_activity_deny), pi(false)).addAction(0, tr(S.ask_activity_allow), pi(true))
            } else {
                val open = PendingIntent.getActivity(ctx, 9000 + a.id % 500, answerIntent(ctx, a.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, com.kilon.agentvscreen.ui.MainDisplay.options())
                val how = when (a.kind) {
                    Kind.POINT -> tr(S.user_ask_open_it_and_tap_the)
                    Kind.AREA -> tr(S.user_ask_open_it_and_drag_a)
                    Kind.CHOOSE -> tr(S.user_ask_open_it_to_choose)
                    Kind.TEACH -> tr(S.user_ask_open_it_and_do_it)
                    else -> tr(S.user_ask_open_it_to_answer)
                }
                b.setContentTitle(tr(S.user_ask_the_ai_asks_you, a.title))
                    .setContentText(how).setStyle(NotificationCompat.BigTextStyle().bigText(tr(S.user_ask_from, how, a.tokenName)))
                    .setContentIntent(open)
            }
            nm.notify(noteId(a.id), b.build())
        }
    }

    /**
     * 1.4.0-beta4: every question is answered on the one screen page, on the live screen, while there is a screen.
     * Only a question with no screen behind it (choose / text / confirm before any launch) opens the plain answer page.
     */
    fun answerIntent(ctx: Context, id: Int): Intent {
        val st = com.kilon.agentvscreen.core.BackgroundOperation.state.value
        return if (st.displayId != null && st.ownerSessionId != null) Intent(ctx, com.kilon.agentvscreen.ui.FullscreenActivity::class.java)
            .putExtra("ask", id).apply { if (byId(id)?.kind == Kind.TEACH) putExtra("teach_ask", id) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        else Intent(ctx, com.kilon.agentvscreen.ui.AskActivity::class.java)
            .putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    /**
     * 1.4.0-beta10: a lesson's answer reaches the agent even when its question is no longer there (answered by a
     * decline elsewhere, or gone with an older build's rules): it is kept under the same key, so the agent's same
     * `teach` sent again gets it.
     */
    fun deliver(ctx: Context, id: Int?, key: String?, token: String?, tokenName: String?, session: String?, title: String, reply: JSONObject) {
        // 1.4.0-beta11: a lesson restored after a restart knows its request only by key (ids start again): the same
        // teach sent again meanwhile is the open one it answers.
        val open = synchronized(lock) { asks.firstOrNull { it.id == id && it.answer == null } ?: key?.let { k -> asks.firstOrNull { it.key == k && it.answer == null && it.kind == Kind.TEACH } } }
        if (open != null) { answer(ctx, open.id, true, reply); return }
        if (key == null || token == null) return
        val a = synchronized(lock) {
            Ask(++seq, Kind.TEACH, key, token, tokenName ?: token, session ?: "default", title, "", System.currentTimeMillis(), true, validMs = 15 * 60_000L).also {
                it.reply = reply; it.answer = true; it.answeredAt = System.currentTimeMillis(); asks += it; publish(); lock.notifyAll()
            }
        }
        Audit.consent(a.tokenName, a.session, a.title, "answered teach")
        told(ctx, a, tr(S.user_ask_the_user_saved_the_lesson))
    }

    /** For tests. */
    internal fun reset() = synchronized(lock) { asks.clear(); publish() }
    internal fun pendingId(key: String): Int? = synchronized(lock) { asks.lastOrNull { it.key == key && it.answer == null }?.id }
}
