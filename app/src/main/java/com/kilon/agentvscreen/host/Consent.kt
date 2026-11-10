package com.kilon.agentvscreen.host

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 1.2: the user's yes, given on the phone, for the steps an agent must not decide alone: a payment or irreversible
 * button, force-stopping an app, changing system settings, taking the app off the user's main screen, and anything in
 * an app the user set to "ask every time".
 *
 * Before 1.2 the agent added `--confirmed` itself after "asking the user": the tool could not tell a real yes from a
 * model that simply added the flag. Now the question appears on the phone (a heads-up notification with Allow / Deny,
 * and a card in the app), the command waits up to [WAIT_MS], and only the user's tap lets it through. No answer in
 * time is CONFIRM_PENDING: the request stays open for [VALID_MS], so the user can still allow it and the agent's
 * resend goes through. A user who trusts a token can give it a period without questions in the app (per token, with
 * an end time); those passes are written to the audit log like every answer.
 */
object Consent {
    const val WAIT_MS = 60_000L
    const val VALID_MS = 5 * 60_000L
    private const val CHANNEL = "agent_vscreen_consent"
    const val ACTION = "com.kilon.agentvscreen.CONSENT"

    class Ask(val id: Int, val key: String, val token: String, val tokenName: String, val session: String,
              val title: String, val detail: String, val at: Long, val reusable: Boolean) {
        /** null = not answered; true = allowed; false = denied. */
        @Volatile var answer: Boolean? = null
        @Volatile var answeredAt = 0L
        @Volatile var used = false
    }

    private val lock = Object()
    private val asks = ArrayList<Ask>()
    private var seq = 100
    private val _open = MutableStateFlow<List<Ask>>(emptyList())
    /** Questions waiting for the user (the app shows them too). */
    val open: StateFlow<List<Ask>> = _open.asStateFlow()

    private fun publish() { _open.value = asks.filter { it.answer == null && System.currentTimeMillis() - it.at < VALID_MS } }

    /**
     * Returns when the user allowed [key]; throws USER_DENIED / CONFIRM_PENDING otherwise. [reusable]: an allow covers
     * the same key of the same token for [VALID_MS] (an app set to "ask every time" is asked once per few minutes, not
     * once per tap); otherwise an allow is used up by one pass.
     */
    fun require(ctx: Context, key: String, title: String, detail: String, reusable: Boolean = false, kind: String = "pay") {
        val call = Call.current()
        val token = call?.tokenId ?: "main"; val name = call?.tokenName ?: "main"; val session = call?.session ?: "default"
        if (call?.trusted == true) { Audit.consent(name, session, "$title — $detail", "pre-authorized"); return }
        if (!asks(kind, runCatching { com.kilon.agentvscreen.api.ApiConfig.autonomy }.getOrDefault("auto"))) {
            Audit.consent(name, session, "$title — $detail", "automatic"); return
        }
        val now = System.currentTimeMillis()
        val ask = synchronized(lock) {
            asks.removeAll { now - it.at > VALID_MS && (it.answer == null || now - it.answeredAt > VALID_MS) }
            val old = asks.lastOrNull { it.key == key && it.token == token && !(it.used && !it.reusable) }
            when {
                old?.answer == true && now - old.answeredAt <= VALID_MS -> { old.used = true; publish(); return }
                old != null && old.answer == null -> old
                else -> Ask(++seq, key, token, name, session, title, detail, now, reusable).also { asks += it; publish(); post(ctx, it) }
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        synchronized(lock) {
            while (ask.answer == null && SystemClock.elapsedRealtime() - t0 < WAIT_MS && call?.cancelled != true) lock.wait(500)
        }
        when (ask.answer) {
            true -> { synchronized(lock) { ask.used = true; publish() }; return }
            false -> throw ActionError("USER_DENIED", tr("用户在手机上拒绝了：$title。没有做。不要换一种方式再试，告诉用户你停在了这里", "The user denied it on the phone: $title. Not done. Don't try another way; tell the user where you stopped"))
            null -> throw ActionError("CONFIRM_PENDING", tr("需要用户在手机上确认：$title（$detail）。已在手机上弹出通知，等了 ${WAIT_MS / 1000} 秒没有答复，这一步没有做。告诉用户去手机通知栏或 Agent-VScreen 里点“允许”；${VALID_MS / 60_000} 分钟内允许后，把这条命令原样重发一次",
                "The user must confirm on the phone: $title ($detail). A notification is showing; no answer within ${WAIT_MS / 1000} s, so this step was not done. Tell the user to tap Allow in the phone's notifications or in Agent-VScreen; once allowed (within ${VALID_MS / 60_000} minutes), send this command again as it is"))
        }
    }

    /**
     * 1.2.0-beta2: which kinds of step ask the user at each autonomy level. One table, shown to the user as it is.
     * Kinds: "pay" (a button that moves money), "app" (an app the user set to ask every time), "spend" (a step toward
     * buying), "irreversible" (delete, uninstall …), "system" (force-stop, taking an app off the main screen).
     * The default level lets the agent run hands-off: only money and the user's own per-app rule stop it.
     */
    fun asks(kind: String, level: String): Boolean = when (kind) {
        "pay", "app" -> true
        else -> level == "careful"
    }

    /** The user's tap (notification action or the card in the app). */
    fun answer(ctx: Context, id: Int, allow: Boolean) {
        val a = synchronized(lock) {
            val a = asks.firstOrNull { it.id == id && it.answer == null } ?: return
            a.answer = allow; a.answeredAt = System.currentTimeMillis()
            publish(); lock.notifyAll(); a
        }
        Audit.consent(a.tokenName, a.session, "${a.title} — ${a.detail}", if (allow) "allowed" else "denied")
        quiet("consent-cancel") { ctx.getSystemService(NotificationManager::class.java)?.cancel(noteId(id)) }
    }

    private fun noteId(id: Int) = 7400 + (id % 500)

    private fun post(ctx: Context, a: Ask) {
        quiet("consent-notify") {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return@quiet
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("需要你确认的操作", "Actions that need your confirmation"), NotificationManager.IMPORTANCE_HIGH))
            fun pi(allow: Boolean) = PendingIntent.getService(ctx, a.id * 2 + (if (allow) 1 else 0),
                Intent(ctx, HostService::class.java).setAction(ACTION).putExtra("id", a.id).putExtra("allow", allow),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(ctx, 6, Intent(ctx, com.kilon.agentvscreen.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val body = tr("${a.detail}\n来自：${a.tokenName}（会话 ${a.session}）", "${a.detail}\nFrom: ${a.tokenName} (session ${a.session})")
            nm.notify(noteId(a.id), NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
                .setContentTitle(tr("AI 请求：${a.title}", "AI asks: ${a.title}"))
                .setContentText(a.detail).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open).setAutoCancel(false).setTimeoutAfter(VALID_MS)
                .addAction(0, tr("拒绝", "Deny"), pi(false)).addAction(0, tr("允许", "Allow"), pi(true))
                .build())
        }
    }

    /** For tests. */
    internal fun reset() = synchronized(lock) { asks.clear(); publish() }
    internal fun pendingId(key: String): Int? = synchronized(lock) { asks.lastOrNull { it.key == key && it.answer == null }?.id }
}
