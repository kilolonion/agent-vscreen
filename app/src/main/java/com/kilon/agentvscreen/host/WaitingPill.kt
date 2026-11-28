package com.kilon.agentvscreen.host

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 2.0.0-beta13 (rainbow: many people keep notifications silent and never look at the shade, so an agent's request
 * sits unseen while the agent keeps trying to get around it). While anything waits on the user — a strong
 * confirmation, a question, a lesson to record — and none of the app's own pages is in front, a small pill sits at
 * the top of the main screen: "an agent is waiting for you · n". A tap opens the app's home page, where the
 * 等你处理 group holds every waiting item (host.Needs). It is the same for every request and every app; a
 * notification is still posted, the pill is what stays visible when that notification is silent.
 *
 * Hosted like the floating window: an accessibility overlay when the service is on (never hidden by apps that hide
 * overlays), else an app overlay with the overlay permission, else nothing (the notification remains).
 */
object WaitingPill {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var view: TextView? = null
    private var wm: WindowManager? = null
    @Volatile var shownCount = 0; private set

    /** What waits on the user now: strong confirmations plus live questions and lessons. */
    fun waitingNow(): Int = StrongConfirm.pending.value.size + UserAsk.open.value.count { it.live }

    fun attach(ctx: Context) {
        val c = ctx.applicationContext
        job?.cancel()
        job = scope.launch {
            launch { combine(StrongConfirm.pending, UserAsk.open) { _, _ -> Unit }.collect { update(c) } }
            // The app's own pages coming to the front or leaving, and questions running out of time.
            while (true) { delay(if (waitingNow() > 0) 1_500L else 10_000L); update(c) }
        }
    }

    fun detach() { job?.cancel(); job = null; hide() }

    private fun update(c: Context) {
        val n = waitingNow()
        if (n == 0 || AppFront.visible) hide() else show(c, n)
    }

    private fun text(n: Int) = tr("$n 项待处理  ›", "$n items need you  ›")

    private fun show(c: Context, n: Int) {
        view?.let { if (shownCount != n) { it.text = text(n); shownCount = n }; return }
        val svc = VScreenAccessibilityService.getInstance()
        if (svc == null && !Settings.canDrawOverlays(c)) return
        val w = (svc ?: c).getSystemService(WindowManager::class.java) ?: return
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics) }
        val tv = TextView(c).apply {
            text = text(n)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16f).toInt(), dp(9f).toInt(), dp(16f).toInt(), dp(9f).toInt())
            background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(0xE66A4FC8.toInt()) }
            elevation = dp(6f)
            contentDescription = text
            setOnClickListener {
                runCatching { Needs.open(c, Needs.Page.HOME, 9).send() }
                hide()
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (svc != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40f).toInt()
            title = "AgentVScreenWaiting"
        }
        runCatching { w.addView(tv, lp) }
            .onSuccess { view = tv; wm = w; shownCount = n; AppLogger.info("WaitingPill", "shown ($n waiting)") }
            .onFailure { AppLogger.warning("WaitingPill", "addView: ${it.message}") }
    }

    private fun hide() {
        val v: View = view ?: return
        quiet("WaitingPill.hide") { wm?.removeView(v) }
        view = null; wm = null; shownCount = 0
    }
}
