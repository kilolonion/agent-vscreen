package com.kilon.agentvscreen.host

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kilon.agentvscreen.tr

/**
 * 2.0.0-beta8 (feedback 4): at the free levels a high action rate does not stop the agent. The user is told instead,
 * in one quiet notification that is replaced, not stacked; stopping is on the floating window or in the app.
 */
object RateNotice {
    private const val CHANNEL = "agent_vscreen_activity"
    private const val ID = 7031
    @Volatile var ctx: Context? = null

    fun init(c: Context) { ctx = c.applicationContext }

    fun show(agent: String, perMin: Int) {
        val c = ctx ?: return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("agent 的动静", "What agents are doing"), NotificationManager.IMPORTANCE_LOW))
        }
        val open = c.packageManager.getLaunchIntentForPackage(c.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        val pi = PendingIntent.getActivity(c, ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = tr("$agent 一分钟操作了 $perMin 次。想停就在悬浮窗或 App 里停止。", "$agent made $perMin actions in a minute. To stop it, use the floating window or the app.")
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .setContentTitle(tr("操作很频繁", "Many actions"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .build()
        nm.notify(ID, n)
    }
}
