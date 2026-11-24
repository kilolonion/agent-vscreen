package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.steps
import com.kilon.agentvscreen.tr

import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kilon.agentvscreen.api.Turns
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.host.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The floating window over every app (a system overlay), so the user sees the background screen whatever agent app is
 * in front. The picture fills a rounded card edge to edge, inside a breathing border whose colour says who has the
 * screen (rainbow: the AI acts; blue: it waits; green: you drive; red: you stopped it; grey: idle).
 *
 * Gestures (handled on raw screen coordinates by [GestureFrame], so moving the window never jitters):
 *  - one finger drags the window, and it keeps gliding when you let go of it moving (inertia);
 *  - a fling only moves it (1.3.0-beta16); the size changes by pinching only;
 *  - let go (or fling) with half of it past the left / right edge: it slides on into that edge until a tab-wide strip
 *    shows, and the tab takes that very strip (1.3.0-beta17); tap the tab and the window slides back out; pull the tab
 *    inward and the window opens under the finger;
 *  - two fingers pinch to resize, anchored at the point between them (120 dp … 85 % of the screen);
 *  - 1.4.2-beta6 (rainbow): a tap shows a bar at the bottom of the window, like a phone's navigation bar; press at its
 *    height and drag up or down to resize the window with one finger (its top edge and centre stay put);
 *  - drag the window into the top right corner and let go: it becomes a small window parked there (both orientations);
 *    a tap on the small window opens it large in the middle of the screen;
 *  - hold one finger still for 0.5 s to take over (a ring fills); a light tap only shows these hints;
 *  - while you hold the screen, taps / swipes / long presses on the picture go to the background screen, and you move
 *    the window by its title pill.
 * Buttons (1.1.6: one job per place, so a quick double tap can never stop the AI by accident):
 *  - top right: ⤢ full screen and × close. × is always there and always one tap: while the AI works it only hides the
 *    window for this turn (the AI goes on; the notification can bring it back); when nothing runs it closes the screen.
 *    Closing never stops or locks the AI.
 *  - bottom centre, only while the AI acts or waits: the red tr("停止 AI", "Stop AI") pill (its calls get USER_STOPPED until you allow
 *    it again: green 交还 AI pill here, the "已停止 AI" notification, or the app's home).
 * While our own full-screen page is in front, the window steps aside (the page already shows the same picture).
 */
object Overlay {
    private const val PREFS = "agent_vscreen_overlay"
    private var ctx: Context? = null
    private var root: GestureFrame? = null
    /** The window manager the window lives in, and whether that is the accessibility service's (see [show]). */
    private var hostWm: WindowManager? = null
    @Volatile var hostedByA11y = false; private set
    private var owner: OverlayOwner? = null
    internal var lp: WindowManager.LayoutParams? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // UI state, main thread only.
    internal val widthPx = mutableFloatStateOf(0f)
    internal val docked = mutableStateOf(false)
    internal val dockRight = mutableStateOf(true)
    internal val hintAt = mutableLongStateOf(0L)
    /** 1.4.2-beta6: the bottom bar (one-finger resize) is showing; it hides [BAR_MS] after the last touch on it. */
    internal val barShown = mutableStateOf(false)
    /** 1.4.2-beta6: the small window parked in the top right corner (tap to open it large). */
    internal val mini = mutableStateOf(false)
    /** 1.4.2-beta6: a drag has the finger in the top right corner: letting go there makes the small window. */
    internal val cornerHot = mutableStateOf(false)
    /** The width before the window became small (what the orientation's width key keeps meanwhile). */
    private var fullWidth = 0f
    private const val BAR_MS = 5_000L
    /** Height of the bottom strip that is the bar's grip (the bar itself is drawn in its middle). */
    internal const val BAR_ZONE = 30f
    private const val CORNER_ZONE = 110f
    private val hideBar = Runnable { barShown.value = false }

    /** A tap on the window: show the bottom bar (again) for [BAR_MS]. */
    internal fun showBar() {
        if (mini.value || docked.value) return
        barShown.value = true
        uiHandler.removeCallbacks(hideBar); uiHandler.postDelayed(hideBar, BAR_MS)
    }
    internal fun holdBar() { uiHandler.removeCallbacks(hideBar) }

    /** Is (x, y), in the window's own coordinates, on the bar's grip strip? */
    internal fun onBar(x: Float, y: Float): Boolean {
        if (!barShown.value || mini.value || docked.value) return false
        val picH = widthPx.floatValue / aspect()
        return x >= pad() && x <= pad() + widthPx.floatValue && y >= pad() + picH - dp(BAR_ZONE) && y <= pad() + picH + pad()
    }

    internal fun inCorner(rawX: Float, rawY: Float): Boolean = WindowSizes.inMiniCorner(rawX, rawY, placeable(), dp(CORNER_ZONE))

    private fun miniWidth(): Float {
        val r = placeable()
        return WindowSizes.miniWidth(aspect(), r.width.toFloat(), r.height.toFloat(), dp(72f))
    }
    private fun miniPlace(w: Float): Pair<Int, Int> {
        val r = placeable()
        return (r.right - w - 2 * pad() - dp(8f)).toInt().coerceAtLeast(r.left) to r.top
    }

    /** Let go in the corner: shrink into the small window parked there. */
    internal fun toMini() {
        cornerHot.value = false
        if (!mini.value) fullWidth = widthPx.floatValue
        barShown.value = false
        val w = miniWidth()
        val (x, y) = miniPlace(w)
        mini.value = true
        animateTo(x, y, width = w, ms = 220L)
    }

    /** A tap on the small window: open it large, in the middle of the screen. */
    internal fun fromMini() {
        if (!mini.value) return
        mini.value = false
        val r = placeable()
        val sw = r.width.toFloat(); val sh = r.height.toFloat()
        val w = clampWidth(WindowSizes.bigWidth(aspect(), sw, sh))
        fullWidth = w
        val gw = w + 2 * pad(); val gh = w / aspect() + 2 * pad()
        val x = (r.left + (sw - gw) / 2f).clampIn(r.left.toFloat(), (r.right - gw).coerceAtLeast(r.left.toFloat()))
        val y = (r.top + (sh - gh) / 2f).clampIn(r.top.toFloat(), (r.bottom - gh).coerceAtLeast(r.top.toFloat()))
        animateTo(x.toInt(), y.toInt(), width = w, ms = 240L) { save(); fitGroup() }
    }

    /** The phone or the background screen turned while small: stay small, in the corner, at that orientation's size. */
    private fun keepMini() {
        if (!mini.value) return
        val w = miniWidth(); widthPx.floatValue = w
        val (x, y) = miniPlace(w); place(x, y); save()
    }
    /** 1.3.0-beta16: what the current touch is, said by the picture layer (see GestureFrame). */
    @Volatile internal var pictureDown = false
    @Volatile internal var pictureLive = false
    @Volatile internal var pictureHeld = false
    @Volatile internal var frameClaimed = false

    /** A question card hangs below the window, or above it when the window sits too low for it. */
    /**
     * 1.4.0-beta3: the window and its question card are ONE group for placement. beta2 placed the window by the
     * picture's height alone (opening from the tab, and the 190 dp guess that chose above / below), so the card hung
     * past the bottom edge and a later settle() jumped the whole thing up. Now the card is measured as it lays out
     * ([cardH]), it always hangs below the picture, and every resting place (opening, release, a card arriving) keeps
     * the whole group on screen; the window glides up in step with the card growing in.
     */
    internal val cardH = androidx.compose.runtime.mutableFloatStateOf(0f)
    internal val cardShown = androidx.compose.runtime.mutableStateOf(false)
    private const val CARD_GUESS = 190f
    /** The group's height when it comes to rest: picture (with its frame margin) plus the card and the gap. */
    internal fun groupH(): Float {
        val pic = (widthPx.floatValue + 2 * pad()) / aspect()
        return pic + if (cardShown.value) (cardH.floatValue.takeIf { it > 0f } ?: dp(CARD_GUESS)) + dp(6f) else 0f
    }
    /** Keep the whole group on screen: glide up (or down) only as far as needed, in [ms] (the card's grow time). */
    internal fun fitGroup(ms: Long = CARD_MS) {
        val l = lp ?: return
        if (docked.value) return
        val r = placeable(); val h = groupH()
        val maxY = (r.bottom - h).coerceAtLeast(r.top.toFloat())
        val y = l.y.toFloat().clampIn(r.top.toFloat().coerceAtMost(maxY), maxY)
        if (y.toInt() != l.y) animateTo(l.x, y.toInt(), ms = ms)
    }
    /** How long the card takes to grow in or out; the window's glide uses the same time and easing. */
    internal const val CARD_MS = 260L

    /** Our full-screen page is in front: it shows the same picture, so the floating window steps aside. */
    /**
     * 1.3.0-beta18: how many of our own full pages are in front (the full screen page, a question's answer page such
     * as pick-a-point / draw-a-box, the lesson review). While any is, the window steps aside: those pages show the
     * screen themselves, and the window and its question card would sit on top of what the user has to touch. A count,
     * not a flag: going from one page to the next starts the new one before the old one stops.
     */
    private val pagesInFront = MutableStateFlow(0)
    fun fullscreenShown(open: Boolean) { pagesInFront.update { (if (open) it + 1 else it - 1).coerceAtLeast(0) } }

    /** 2.0.0-beta8 (G.3): synchronously show the overlay on the main thread right before a full-screen page finishes. */
    fun showNowIfEligible() {
        val s = BackgroundOperation.state.value
        if (job?.isActive == true && s.displayId != null && s.showPreview && !s.windowHidden && pagesInFront.value == 0) {
            show()
        }
    }

    fun attach(c: Context) {
        ctx = c.applicationContext
        job?.cancel()
        job = scope.launch {
            combine(BackgroundOperation.state.map { it.displayId != null && it.showPreview && !it.windowHidden }.distinctUntilChanged(), pagesInFront.map { it > 0 }.distinctUntilChanged()) { up, full -> up to full }
                .distinctUntilChanged().collect { (up, full) ->
                    AppLogger.info("Overlay", when { !up -> "no screen / preview off / closed by the user: hide"; full -> "full-screen page in front: hide"; else -> "screen up: show" })
                    if (up && !full) show() else hide()
                }
        }
    }

    fun detach() { job?.cancel(); job = null; hide() }

    private val uiHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Re-check after the overlay permission was granted. */
    fun refresh() {
        uiHandler.post { showNowIfEligible() }
    }

    /** 1.1.6: the × button. One tap, never a lock: see [BackgroundOperation.closeWindow]. */
    fun closeTapped() {
        // 1.4.0-beta11 (DESIGN §4.2): while the user shows a lesson the × folds the window to the edge; it never hands
        // the screen back or ends the lesson (that is 完成 / 不教 on the strip).
        val l = com.kilon.agentvscreen.cmd.Teach.lesson.value
        if (l is com.kilon.agentvscreen.cmd.Lesson.Ready || l is com.kilon.agentvscreen.cmd.Lesson.Recording) {
            dock(dockRight.value)
            ctx?.let { android.widget.Toast.makeText(it, tr("收到边上了，示教还在继续", "Folded to the edge; the lesson goes on"), android.widget.Toast.LENGTH_SHORT).show() }
            return
        }
        val msg = BackgroundOperation.closeWindow()
        ctx?.let { android.widget.Toast.makeText(it, msg, android.widget.Toast.LENGTH_LONG).show() }
    }

    /** The accessibility service came or went: move the window to the better host. Main thread. */
    fun rehost() {
        android.os.Handler(Looper.getMainLooper()).post {
            val want = com.kilon.agentvscreen.host.VScreenAccessibilityService.getInstance() != null
            if (root != null && want != hostedByA11y) { AppLogger.info("Overlay", "rehost (a11y=$want)"); hide(); refresh() }
        }
    }

    private fun prefs() = ctx!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Main-thread geometry cache. MOVE and animations must not query WindowManager for each sample.
    // 2.0.0-beta8 (G.1/G.2): unified placeable area in full-display coordinates (with LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS).
    private var cachedPlaceable: WindowSizes.Placeable? = null
    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null
    private var lastDisplayRotation: Int = -1
    internal fun invalidateBounds() { cachedPlaceable = null }
    internal fun placeable(wm: WindowManager? = hostWm): WindowSizes.Placeable = cachedPlaceable ?: run {
        val wmNow = wm ?: ctx?.getSystemService(WindowManager::class.java)
        val metrics = runCatching { wmNow?.maximumWindowMetrics }.getOrNull()
        val bounds = metrics?.bounds
        val dm = ctx?.resources?.displayMetrics
        val fullW = if (bounds != null && bounds.width() > 0) bounds.width() else (dm?.widthPixels ?: 1080)
        val fullH = if (bounds != null && bounds.height() > 0) bounds.height() else (dm?.heightPixels ?: 2400)
        val insets = runCatching {
            metrics?.windowInsets?.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.systemBars(),
            )
        }.getOrNull()
        WindowSizes.computePlaceable(
            fullW = fullW,
            fullH = fullH,
            insetLeft = insets?.left ?: 0,
            insetTop = insets?.top ?: 0,
            insetRight = insets?.right ?: 0,
            insetBottom = insets?.bottom ?: 0,
            minTop = dp(8f).toInt(),
            minBottom = dp(8f).toInt(),
        ).also { cachedPlaceable = it }
    }
    internal fun screenW() = placeable().width
    internal fun screenH() = placeable().height
    internal fun dp(v: Float) = v * (ctx?.resources?.displayMetrics?.density ?: 3f)

    /** Aspect (w / h) of the background screen. */
    internal fun aspect(): Float {
        val s = BackgroundOperation.state.value
        val d = s.ownerSessionId?.let { BackgroundOperation.previewDisplayFor(it) }
        val w = d?.width ?: s.screenWidth; val h = d?.height ?: s.screenHeight
        return if (w > 0 && h > 0) w.toFloat() / h else 9f / 19.5f
    }

    internal fun clampWidth(w: Float): Float {
        val a = aspect()
        val r = placeable()
        // A landscape screen on an upright phone may use almost the whole width: it is short, and anything narrower
        // makes its text unreadable.
        val max = minOf(r.width * (if (a > 1f) 0.94f else 0.85f), r.height * 0.85f * a)
        return w.clampIn(dp(120f).coerceAtMost(max), max)
    }

    /**
     * Where the window lives. Not a copy of Minis (whose preview sat inside its own chat activity): a system overlay of an
     * ordinary app (TYPE_APPLICATION_OVERLAY) is hidden by Android on EVERY display while any window that asks to hide
     * non-system overlays is showing — Settings, the package installer, permission dialogs, many bank apps do — and the
     * agent opens exactly those on the background screen. So the window is an accessibility overlay of our read-only
     * service when it is on (those are never hidden that way), and an app overlay only as the fallback.
     */
    private fun show() {
        val c = ctx ?: return
        if (root != null) return
        val svc = com.kilon.agentvscreen.host.VScreenAccessibilityService.getInstance()
        val a11y = svc != null
        if (!a11y && !Settings.canDrawOverlays(c)) { AppLogger.info("Overlay", "no overlay permission and no accessibility: window not shown"); return }
        val wm: WindowManager = if (svc != null) svc.getSystemService(WindowManager::class.java)
            else runCatching {
                val dm = c.getSystemService(android.hardware.display.DisplayManager::class.java)
                c.createDisplayContext(dm.getDisplay(android.view.Display.DEFAULT_DISPLAY))
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    .getSystemService(WindowManager::class.java)
            }.getOrElse { c.getSystemService(WindowManager::class.java) }
        invalidateBounds()
        val area = placeable(wm)
        val o = OverlayOwner().also { owner = it }
        val p = prefs()
        shownLandscape = aspect() > 1f
        widthPx.floatValue = clampWidth(p.getFloat(widthKey(shownLandscape), defaultWidth(shownLandscape)))
        docked.value = p.getBoolean("docked", false)
        dockRight.value = p.getBoolean("dock_right", true)
        mini.value = p.getBoolean("mini", false)
        fullWidth = widthPx.floatValue
        if (mini.value) widthPx.floatValue = miniWidth()
        val l = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (a11y) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 2.0.0-beta8 (G.1): anchor window coordinates in full-display space so cutout-side placement never drifts.
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = p.getInt("x", (area.right - widthPx.floatValue - dp(16f)).toInt())
            y = p.getInt("y", area.top + (area.height * 0.14f).toInt())
        }
        if (docked.value) {
            l.x = if (dockRight.value) area.right - dp(TAB_W).toInt() else area.left
            l.y = l.y.clampIn(area.top, (area.bottom - dp(TAB_H).toInt()).coerceAtLeast(area.top))
        } else if (mini.value) {
            val (mx, my) = miniPlace(widthPx.floatValue); l.x = mx; l.y = my
        } else {
            // 1.2.0: the window opens wholly on screen. The place is remembered for whatever size it had then; a
            // wider window (landscape, or a larger default) used to open partly off the right edge.
            val w = (widthPx.floatValue + dp(14f)).toInt()
            val h = (widthPx.floatValue / aspect() + dp(14f)).toInt()
            l.x = l.x.clampIn(area.left, (area.right - w).coerceAtLeast(area.left))
            l.y = l.y.clampIn(area.top, (area.bottom - h).coerceAtLeast(area.top))
        }
        lp = l
        val g = GestureFrame(c)
        val v = ComposeView(c).apply {
            setViewTreeLifecycleOwner(o); setViewTreeSavedStateRegistryOwner(o); setViewTreeViewModelStoreOwner(o)
            setContent { OverlayContent() }
        }
        g.setViewTreeLifecycleOwner(o); g.setViewTreeSavedStateRegistryOwner(o); g.setViewTreeViewModelStoreOwner(o)
        g.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        runCatching { wm.addView(g, l); root = g; hostWm = wm; hostedByA11y = a11y }
            .onSuccess {
                AppLogger.info("Overlay", "window added (${if (a11y) "accessibility overlay" else "app overlay"})")
                registerDisplayListener(c)
                // Once laid out its real size is known: keep all of it on screen.
                if (!docked.value) g.post { keepOnScreen() }
            }
            .onFailure { AppLogger.warning("Overlay", "addView: ${it.message}"); o.destroy(); owner = null }
    }

    private fun registerDisplayListener(c: Context) {
        unregisterDisplayListener()
        val dm = runCatching { c.getSystemService(android.hardware.display.DisplayManager::class.java) }.getOrNull() ?: return
        lastDisplayRotation = runCatching { dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: -1 }.getOrDefault(-1)
        val l = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                val rot = runCatching { dm.getDisplay(displayId)?.rotation ?: -1 }.getOrDefault(-1)
                if (rot != lastDisplayRotation) {
                    lastDisplayRotation = rot
                    invalidateBounds()
                    root?.post { rotated() }
                }
            }
        }
        runCatching { dm.registerDisplayListener(l, uiHandler); displayListener = l }
    }

    private fun unregisterDisplayListener() {
        val l = displayListener ?: return
        displayListener = null
        runCatching { ctx?.getSystemService(android.hardware.display.DisplayManager::class.java)?.unregisterDisplayListener(l) }
    }

    private fun hide() {
        stopAnim()
        unregisterDisplayListener()
        val v = root ?: return
        v.removeCallbacks(applyPlace)
        invalidateBounds()
        runCatching { hostWm?.removeView(v) }.onFailure { AppLogger.warning("Overlay", "removeView: ${it.message}") }
        placeQueued = false
        root = null; hostWm = null; owner?.destroy(); owner = null
    }

    // 1.2.0-beta2: the window's width is remembered per orientation of the background screen. One number for both
    // meant a landscape screen opened at the width chosen for a portrait one: a strip a third of the screen wide.
    private var shownLandscape = false
    // 1.2.0: a landscape screen opens at about three quarters of the phone's width (readable, and the app behind it
    // stays usable); the key is new so the larger beta default is not kept.
    private fun widthKey(landscape: Boolean) = if (landscape) "w_landscape" else "w"
    private fun defaultWidth(landscape: Boolean) = if (landscape) minOf(screenW(), screenH()) * 0.76f else minOf(screenW(), screenH()) * 0.34f

    /** The background screen changed between portrait and landscape: use that orientation's own width. */
    internal fun orientationChanged(landscape: Boolean) {
        if (root == null || landscape == shownLandscape) return
        invalidateBounds()
        prefs().edit().putFloat(widthKey(shownLandscape), if (mini.value) fullWidth else widthPx.floatValue).apply()
        shownLandscape = landscape
        widthPx.floatValue = clampWidth(prefs().getFloat(widthKey(landscape), defaultWidth(landscape)))
        fullWidth = widthPx.floatValue
        if (mini.value) { root?.post { keepMini() }; return }
        // Moved back wholly on screen, never folded: the window grew, the user did not push it to an edge. The new
        // size is known only after the next layout, so it is checked again then.
        root?.post { if (docked.value) dock(dockRight.value) else keepOnScreen() }
        root?.postDelayed({ if (!docked.value) keepOnScreen() }, 120L)
    }

    /** Move the window the least needed to have all of it on screen. */
    internal fun keepOnScreen() {
        val l = lp ?: return
        val r = placeable()
        val x = l.x.clampIn(r.left, (r.right - winW()).toInt().coerceAtLeast(r.left))
        val y = l.y.clampIn(r.top, (r.bottom - winH()).toInt().coerceAtLeast(r.top))
        if (x != l.x || y != l.y) { place(x, y); save() }
    }

    // 1.2.0-beta2: moving the window is one call to the window manager per display frame, with the newest position.
    // Before, every touch sample (120–240 a second) made that call and waited for it, so the main thread fell behind
    // the finger and the window trailed it.
    private var placeQueued = false
    private val applyPlace = Runnable {
        placeQueued = false
        val v = root ?: return@Runnable; val l = lp ?: return@Runnable
        runCatching { hostWm?.updateViewLayout(v, l) }
    }

    internal fun place(x: Int, y: Int) {
        val v = root ?: return; val l = lp ?: return
        val r = placeable()
        val boundedY = y.clampIn(if (docked.value) r.top else r.top - (winH() * 0.6f).toInt(), r.bottom - dp(48f).toInt())
        if (l.x == x && l.y == boundedY) return
        l.x = x; l.y = boundedY
        if (!placeQueued) { placeQueued = true; v.postOnAnimation(applyPlace) }
    }

    internal fun save() {
        val l = lp ?: return
        // While small, the orientation's width key keeps the width it had before (the small size is derived).
        prefs().edit().putFloat(widthKey(shownLandscape), if (mini.value) fullWidth else widthPx.floatValue).putInt("x", l.x).putInt("y", l.y)
            .putBoolean("docked", docked.value).putBoolean("dock_right", dockRight.value).putBoolean("mini", mini.value).apply()
    }

    // ---- Where the window comes to rest (release, fling, rotation). ----
    private const val FOLD = 0.5f      // half the window past a side edge (on release) → fold into a tab
    private const val GLIDE_S = 0.2f   // inertia: where a fling would carry the window (velocity × this)
    private var anim: android.animation.ValueAnimator? = null

    internal fun winW(): Float = root?.width?.takeIf { it > 0 }?.toFloat() ?: (widthPx.floatValue + dp(12f))
    internal fun winH(): Float = root?.height?.takeIf { it > 0 }?.toFloat() ?: (widthPx.floatValue / aspect() + dp(12f))
    /** The folded tab (see [DockTab]). */
    internal const val TAB_W = 26f
    internal const val TAB_H = 60f
    internal fun stopAnim() { anim?.cancel(); anim = null }

    /** Glide to (x, y), optionally changing the width, then run [then] (default: remember the place). */
    private fun animateTo(x: Int, y: Int, width: Float? = null, ms: Long = 240L, then: (() -> Unit)? = null) {
        if (lp == null) return
        stopAnim()
        val l = lp!!
        val x0 = l.x; val y0 = l.y; val w0 = widthPx.floatValue
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (animateLook) ms else 0L
        a.interpolator = android.view.animation.DecelerateInterpolator(1.5f)
        a.addUpdateListener {
            val t = it.animatedValue as Float
            if (width != null) widthPx.floatValue = w0 + (width - w0) * t
            place((x0 + (x - x0) * t).roundToInt(), (y0 + (y - y0) * t).roundToInt())
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (anim === a) anim = null
                if (!cancelled) { if (then != null) then() else save() }
            }
        })
        anim = a; a.start()
    }

    /** After a plain release or a rotation: fold if a good part is past an edge, else keep it fully on screen. */
    internal fun settle(fingerY: Float? = null) { val l = lp ?: return; restAt(l.x.toFloat(), l.y.toFloat(), fingerY) }

    /** The phone turned: recompute placeable bounds and keep the tab/mini/window inside the new area. */
    internal fun rotated() {
        invalidateBounds()
        if (!mini.value) {
            widthPx.floatValue = clampWidth(widthPx.floatValue)
            fullWidth = widthPx.floatValue
        }
        when {
            docked.value -> dock(dockRight.value)
            mini.value -> keepMini()
            else -> {
                settle()
                root?.postDelayed({ if (!docked.value && !mini.value) { invalidateBounds(); keepOnScreen() } }, 140L)
            }
        }
    }

    /**
     * The window was let go while moving (raw px/s). It glides on; flung toward a corner it shrinks to the smallest size
     * there; a small one flung back toward the middle grows to its earlier size; otherwise it rests like a release.
     */
    internal fun fling(vx: Float, vy: Float, fingerY: Float? = null) {
        val l = lp ?: return
        val px = l.x + vx * GLIDE_S; val py = l.y + vy * GLIDE_S
        // 1.3.0-beta16: a fling only moves the window (it used to shrink it into a corner or grow it back): the size
        // changes by pinching only. Flung half past an edge, it folds there (decided now, at the release).
        restAt(px, py, fingerY?.let { it + vy * GLIDE_S }, vx)
    }

    /**
     * Come to rest with the window's top-left at (px, py): fold past any edge, else glide fully onto the screen.
     * The tab lands where the user let go ([fingerY], centred on the finger), not at the window's middle — otherwise
     * it appears somewhere other than where it was put. Without a finger (rotation) it keeps the window's top.
     */
    private fun restAt(px: Float, py: Float, fingerY: Float? = null, vx: Float = 0f) {
        val r = placeable()
        val w = winW(); val h = maxOf(winH(), groupH())
        val side: Boolean? = when {
            px < r.left - w * FOLD -> false
            px + w > r.right + w * FOLD -> true
            else -> null
        }
        if (side != null) { foldInto(side, fingerY, vx); return }
        val maxX = (r.right - w).coerceAtLeast(r.left.toFloat())
        val maxY = (r.bottom - h).coerceAtLeast(r.top.toFloat())
        val x = px.clampIn(r.left.toFloat(), maxX)
        val y = py.clampIn(r.top.toFloat().coerceAtMost(maxY), maxY)
        animateTo(x.toInt(), y.toInt())
    }

    internal fun dock(right: Boolean, y: Int? = null) {
        val l = lp ?: return
        stopAnim()
        docked.value = true; dockRight.value = right
        val r = placeable()
        val ty = (y ?: l.y).clampIn(r.top, (r.bottom - dp(TAB_H).toInt()).coerceAtLeast(r.top))
        place(if (right) r.right - dp(TAB_W).toInt() else r.left, ty)
        save()
    }

    // ---- The window <-> tab change: one continuous slide at the edge (1.3.0-beta17). ----
    // Before, the window shrank where it was let go (or where a fling left it, still well inside the screen) and the
    // tab then appeared at the edge: a small thing away from the edge that jumped to its final place. Now the window
    // keeps its size and slides on, at the speed it was let go with, until only a tab-wide strip of it shows at the
    // edge; the tab takes over exactly that strip. Opening plays the same slide backwards.
    private fun content(): android.view.View? = root?.getChildAt(0)
    /** The transparent margin around the window's frame (PAD in [OverlayContent]). */
    private fun pad() = dp(7f)

    /** Where the window's left edge is when only the tab-wide strip of it shows on [right]'s side. */
    private fun stripX(right: Boolean, w: Float): Float {
        val r = placeable()
        return if (right) r.right - dp(TAB_W) - pad() else r.left - (w - dp(TAB_W) - pad())
    }

    /** A duration whose opening speed matches [v] px/s (DecelerateInterpolator(1) starts at twice the mean speed). */
    private fun slideMs(dist: Float, v: Float): Long =
        if (abs(v) < 1f) 220L else (2f * abs(dist) / abs(v) * 1000f).toLong().clampIn(120L, 260L)

    /** Released half past a side edge (or flung there): slide into that edge and become the tab there. */
    private fun foldInto(right: Boolean, fingerY: Float?, vx: Float) {
        val l = lp ?: return
        val r = placeable(); val w = winW(); val h = winH(); val tabH = dp(TAB_H)
        // The tab sits where the finger let go, but never outside the window's own height (no jump up or down);
        // only the screen's margins may move it, and then the window slides by the same amount.
        val within = ((fingerY ?: (l.y + h / 2)) - tabH / 2).clampIn(l.y.toFloat(), (l.y + h - tabH).coerceAtLeast(l.y.toFloat()))
        val ty = within.clampIn(r.top.toFloat(), (r.bottom - tabH).coerceAtLeast(r.top.toFloat()))
        val x0 = l.x.toFloat(); val y0 = l.y.toFloat()
        val x1 = stripX(right, w); val y1 = y0 + (ty - within)
        val v = content()
        stopAnim()
        if (v == null || !animateLook) { dock(right, ty.toInt()); return }
        v.animate().cancel(); v.scaleX = 1f; v.scaleY = 1f
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = slideMs(x1 - x0, if ((x1 - x0) * vx > 0) vx else 0f)
        a.interpolator = android.view.animation.DecelerateInterpolator(1f)
        a.addUpdateListener {
            val t = it.animatedValue as Float
            v.alpha = 1f - 0.35f * ((t - 0.6f) / 0.4f).clampIn(0f, 1f)
            place((x0 + (x1 - x0) * t).roundToInt(), (y0 + (y1 - y0) * t).roundToInt())
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true; v.alpha = 1f }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (anim === a) anim = null
                if (!cancelled) { swapFade(); dock(right, ty.toInt()) }
            }
        })
        anim = a; a.start()
    }

    /** The content is about to change between window and tab: hide the stale frames, then fade the new one in. */
    private fun swapFade(then: (() -> Unit)? = null) {
        val v = content() ?: return
        v.animate().cancel(); v.alpha = 0f; v.scaleX = 1f; v.scaleY = 1f
        // Two frames: the window manager has the new size and place before anything shows.
        v.postOnAnimationDelayed({
            if (then != null) then() else v.animate().alpha(1f).setDuration(if (animateLook) 110L else 0L).start()
        }, 34L)
    }

    /** While a finger holds it: become (or stay) the tab on that side, centred on the finger, [give] px inward. */
    internal fun dockLive(right: Boolean, fingerY: Float, give: Float) {
        stopAnim()
        if (!docked.value || dockRight.value != right) { if (!docked.value) swapFade(); docked.value = true; dockRight.value = right }
        val r = placeable()
        val ty = (fingerY - dp(TAB_H) / 2).clampIn(r.top.toFloat(), (r.bottom - dp(TAB_H)).coerceAtLeast(r.top.toFloat()))
        place(if (right) (r.right - dp(TAB_W) - give).toInt() else (r.left + give).toInt(), ty.toInt())
    }

    /** While a finger holds the tab and pulls it inward: open the window under the finger, held near its edge side. */
    internal fun undockAt(fingerX: Float, fingerY: Float) {
        stopAnim()
        val right = dockRight.value
        docked.value = false
        val w = widthPx.floatValue + dp(12f)
        val h = w / aspect()
        place((fingerX - if (right) w * 0.8f else w * 0.2f).toInt(), (fingerY - h * 0.2f).toInt())
        swapFade()
    }

    /**
     * A tap on the tab: the window opens wholly on screen, at its final place beside that edge, and grows in from
     * the tab's side. 1.3.0-beta18: beta17 first placed it mostly off screen and slid it in; the window manager showed
     * that off-screen frame before the slide began, so it flashed half outside and then jumped in. Now it is never
     * anywhere but where it ends, and stays invisible until it has its real size there.
     */
    internal fun undock() {
        val l = lp ?: return
        stopAnim()
        val right = dockRight.value
        val r = placeable()
        val w = widthPx.floatValue + 2 * pad()
        val h = groupH()
        // The tab stays a third of the way down the window, as when it folded.
        val y = (l.y + dp(TAB_H) / 2 - h * 0.3f).clampIn(r.top.toFloat(), (r.bottom - h).coerceAtLeast(r.top.toFloat()))
        val x = (if (right) r.right - w - dp(8f) else r.left + dp(8f)).clampIn(r.left.toFloat(), (r.right - w).coerceAtLeast(r.left.toFloat()))
        val v = content()
        v?.animate()?.cancel(); v?.alpha = 0f
        docked.value = false
        place(x.toInt(), y.toInt())
        save()
        if (v == null || !animateLook) { v?.alpha = 1f; return }
        swapFade {
            v.pivotX = if (right) w else 0f; v.pivotY = (l.y + dp(TAB_H) / 2 - y).clampIn(0f, h)
            v.scaleX = 0.82f; v.scaleY = 0.82f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        }
    }

    internal fun openFull() {
        val c = ctx ?: return
        // A lesson waiting to be saved opens its save pane; otherwise the live screen (a recording goes on there).
        if (com.kilon.agentvscreen.cmd.Teach.result != null) Surface.openReview(c) else Surface.openLive(c)
    }

    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val reg = LifecycleRegistry(this)
        private val ssr = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        init { ssr.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = reg
        override val savedStateRegistry: SavedStateRegistry get() = ssr.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
        fun destroy() { reg.currentState = Lifecycle.State.DESTROYED; store.clear() }
    }
}

/** Map a point inside the picture to background-screen pixels (null: outside the picture). */
internal fun pictureToScreen(lx: Float, ly: Float, frame: RectF): Pair<Int, Int>? {
    val s = BackgroundOperation.state.value
    val d = s.ownerSessionId?.let { BackgroundOperation.previewDisplayFor(it) } ?: return null
    if (frame.width() <= 0f || !frame.contains(lx, ly)) return null
    val x = ((lx - frame.left) / frame.width() * d.width).roundToInt().clampIn(0, d.width - 1)
    val y = ((ly - frame.top) / frame.height() * d.height).roundToInt().clampIn(0, d.height - 1)
    return x to y
}

/**
 * 1.3.0-beta16: window gestures only (REVIEW-OVERLAY-GESTURES-1.3, P0). Every touch goes to the content first; the
 * content decides what it means: buttons are buttons, and ONLY the picture layer sends fingers to the background
 * screen (it says so in [Overlay.pictureLive]). This frame takes a touch back only to move the window (a drag that is
 * not a live finger, or a long press anywhere but the picture) or to resize it (two fingers). No lists of rectangles.
 */
@SuppressLint("ViewConstructor")
private class GestureFrame(ctx: Context) : FrameLayout(ctx) {
    private enum class Claim { NONE, MOVE, PINCH, RESIZE }

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val main = Handler(Looper.getMainLooper())
    private var claim = Claim.NONE
    private var downRaw = Offset.Zero
    private var winX = 0; private var winY = 0
    private var pinchDist = 0f; private var pinchW = 0f; private var pinchMid = Offset.Zero
    /** 1.4.2-beta6: this touch landed on the small window (a tap opens it large; nothing reaches the content). */
    private var onMini = false
    private var childGotDown = false
    private var velocity: android.view.VelocityTracker? = null
    private val flingMin = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity.toFloat().coerceAtLeast(Overlay.dp(450f))

    /** A long press on the bar, a card or the action row picks the window up (the picture's long press is take over). */
    private val lift = Runnable {
        if (claim != Claim.NONE || Overlay.pictureDown) return@Runnable
        cancelChildren()
        claim = Claim.MOVE
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        AppLogger.info("Overlay", "window visibility ${if (visibility == VISIBLE) "visible" else "hidden ($visibility)"}" +
            if (!Overlay.hostedByA11y) tr("（app overlay：系统可能因副屏上的安全页面隐藏它；打开无障碍可避免）", "(app overlay: the system may hide it for a secure page on the screen; turning on accessibility prevents that)") else "")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        Overlay.invalidateBounds()
        // The main screen turned: keep the window (or its tab) on screen.
        post { Overlay.rotated() }
    }

    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        Overlay.invalidateBounds()
        return super.onApplyWindowInsets(insets)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(lift)
        velocity?.recycle(); velocity = null
        super.onDetachedFromWindow()
    }

    /** The window takes the touch: the content gets a cancel (a live finger on the screen is cancelled, not tapped). */
    private fun cancelChildren() {
        if (!childGotDown) return
        childGotDown = false
        Overlay.frameClaimed = true
        val now = SystemClock.uptimeMillis()
        val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(c); c.recycle()
    }

    private fun track(ev: MotionEvent) {
        val raw = MotionEvent.obtain(ev); raw.setLocation(ev.rawX, ev.rawY)
        velocity?.addMovement(raw); raw.recycle()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val docked = Overlay.docked.value
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { velocity?.recycle(); velocity = android.view.VelocityTracker.obtain() }
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                claim = Claim.NONE
                Overlay.stopAnim()
                downRaw = Offset(ev.rawX, ev.rawY)
                winX = Overlay.lp?.x ?: 0; winY = Overlay.lp?.y ?: 0
                Overlay.pictureDown = false; Overlay.pictureLive = false; Overlay.pictureHeld = false; Overlay.frameClaimed = false
                childGotDown = false
                onMini = !docked && Overlay.mini.value
                if (!docked && !onMini && Overlay.onBar(ev.x, ev.y)) {
                    // 1.4.2-beta6: the bottom bar's strip is the window's own from the first moment (never the picture's):
                    // pressing there and dragging resizes it with one finger.
                    claim = Claim.RESIZE
                    pinchW = Overlay.widthPx.floatValue
                    Overlay.holdBar()
                    return true
                }
                val miniHasControls = onMini && BackgroundOperation.state.value.miniShowControls
                if (!docked && (!onMini || miniHasControls)) {
                    childGotDown = true
                    super.dispatchTouchEvent(ev)
                    if (!onMini) main.postDelayed(lift, 400L)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!docked && !onMini && claim != Claim.RESIZE && ev.pointerCount == 2 && !Overlay.pictureHeld) {
                    main.removeCallbacks(lift)
                    cancelChildren()
                    claim = Claim.PINCH
                    pinchDist = dist(ev).coerceAtLeast(1f)
                    pinchW = Overlay.widthPx.floatValue
                    pinchMid = midRaw(ev)
                    winX = Overlay.lp?.x ?: 0; winY = Overlay.lp?.y ?: 0
                } else if (claim == Claim.NONE && childGotDown) super.dispatchTouchEvent(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val raw = Offset(ev.rawX, ev.rawY)
                when (claim) {
                    Claim.RESIZE -> {
                        // The bottom edge follows the finger; the top edge and the centre stay put.
                        val nw = Overlay.clampWidth(WindowSizes.resized(pinchW, Overlay.aspect(), raw.y - downRaw.y))
                        Overlay.widthPx.floatValue = nw
                        Overlay.place((winX + (pinchW - nw) / 2f).roundToInt(), winY)
                    }
                    Claim.PINCH -> if (ev.pointerCount >= 2) {
                        val nw = Overlay.clampWidth(pinchW * dist(ev) / pinchDist)
                        val k = nw / pinchW
                        Overlay.widthPx.floatValue = nw
                        Overlay.place((pinchMid.x - (pinchMid.x - winX) * k).roundToInt(), (pinchMid.y - (pinchMid.y - winY) * k).roundToInt())
                    }
                    Claim.MOVE -> {
                        if (docked) {
                            // The tab rides its edge under the finger; pulled well inward from the placeable edge it opens under the finger.
                            val right = Overlay.dockRight.value
                            val r = Overlay.placeable()
                            val inward = if (right) r.right - raw.x else raw.x - r.left
                            if (inward > Overlay.dp(72f)) {
                                Overlay.undockAt(raw.x, raw.y)
                                downRaw = raw; winX = Overlay.lp?.x ?: 0; winY = Overlay.lp?.y ?: 0
                            } else Overlay.dockLive(right, raw.y, (inward - Overlay.dp(Overlay.TAB_W)).clampIn(0f, Overlay.dp(16f)))
                        } else {
                            // Moving only moves: folding is decided when the finger lifts, never on the way.
                            val d = raw - downRaw
                            Overlay.place(winX + d.x.roundToInt(), winY + d.y.roundToInt())
                            // 1.4.2-beta6: the finger in the top right corner: letting go makes the small window there.
                            val hot = !onMini && Overlay.inCorner(raw.x, raw.y)
                            if (hot != Overlay.cornerHot.value) {
                                Overlay.cornerHot.value = hot
                                if (hot) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                    }
                    Claim.NONE -> when {
                        Overlay.pictureLive || Overlay.pictureHeld -> super.dispatchTouchEvent(ev)
                        hypot(raw.x - downRaw.x, raw.y - downRaw.y) > slop -> {
                            main.removeCallbacks(lift); cancelChildren()
                            claim = Claim.MOVE
                        }
                        childGotDown -> super.dispatchTouchEvent(ev)
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> { if (claim == Claim.NONE && childGotDown) super.dispatchTouchEvent(ev); return true }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(lift)
                when (claim) {
                    Claim.RESIZE -> { Overlay.save(); Overlay.settle(); Overlay.showBar() }
                    Claim.PINCH -> { Overlay.save(); Overlay.settle() }
                    Claim.MOVE -> if (!docked && !onMini && Overlay.cornerHot.value) Overlay.toMini() else if (docked) {
                        Overlay.lp?.let { Overlay.dock(Overlay.dockRight.value, it.y) }
                    } else {
                        val v = velocity?.let { it.computeCurrentVelocity(1000); it.xVelocity to it.yVelocity }
                        if (v != null && hypot(v.first, v.second) > flingMin) Overlay.fling(v.first, v.second, ev.rawY) else Overlay.settle(ev.rawY)
                    }
                    Claim.NONE -> when {
                        docked -> Overlay.undock()
                        onMini && !childGotDown -> if (BackgroundOperation.state.value.miniTapToExpand) Overlay.fromMini()
                        childGotDown -> super.dispatchTouchEvent(ev)
                    }
                }
                claim = Claim.NONE; childGotDown = false; onMini = false; Overlay.cornerHot.value = false
                velocity?.recycle(); velocity = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(lift); cancelChildren()
                Overlay.cornerHot.value = false; onMini = false
                if (claim == Claim.RESIZE) { Overlay.save(); Overlay.settle(); Overlay.showBar() }
                if (claim == Claim.MOVE) {
                    if (Overlay.docked.value) Overlay.lp?.let { Overlay.dock(Overlay.dockRight.value, it.y) } else Overlay.settle()
                }
                claim = Claim.NONE
                velocity?.recycle(); velocity = null
                return true
            }
        }
        return true
    }

    private fun dist(ev: MotionEvent): Float = if (ev.pointerCount < 2) 0f else hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    private fun midRaw(ev: MotionEvent): Offset {
        // Raw coordinates of the midpoint: the view's screen position plus the local midpoint.
        val loc = IntArray(2); getLocationOnScreen(loc)
        return Offset(loc[0] + (ev.getX(0) + ev.getX(1)) / 2f, loc[1] + (ev.getY(0) + ev.getY(1)) / 2f)
    }
}

private val PAD = 7.dp

/** Takes every touch (nothing under it gets one), and a plain tap the window did not take for a move runs [onTap]. */
private fun Modifier.tapOrEat(onTap: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (awaitPointerEvent().changes.any { it.pressed }) Unit
        if (!Overlay.frameClaimed) onTap()
    }
}
/** 1.3.0-beta18: one height for the title pill and the round buttons beside it. */
private val CHIP_H = 24.dp

@Composable
internal fun OverlayContent(fixed: BackgroundOperation.State? = null, picture: (@Composable (String) -> Unit)? = null) {
    val live by BackgroundOperation.state.collectAsState()
    val s = fixed ?: live
    val owner = s.ownerSessionId ?: return
    val mode = modeOf(s)
    val waiting = pendingAsks()
    // 1.4.0-beta10: a lesson waiting to be saved is a card too (it replaces the question it answers).
    val lesson by com.kilon.agentvscreen.cmd.Teach.lesson.collectAsState()
    val review = (lesson as? com.kilon.agentvscreen.cmd.Lesson.Review)?.result
    // 1.4.0-beta11: getting ready / recording: the lesson strip hangs under the window (same place as a question card).
    val showing = lesson is com.kilon.agentvscreen.cmd.Lesson.Ready || lesson is com.kilon.agentvscreen.cmd.Lesson.Recording
    // Known before any placement, docked or not, so opening from the tab already counts the card.
    androidx.compose.runtime.SideEffect { Overlay.cardShown.value = !Overlay.mini.value && (waiting.isNotEmpty() || review != null || showing) }
    if (Overlay.docked.value) { DockTab(mode, Overlay.dockRight.value, waiting.size + if (review != null || showing) 1 else 0); return }
    val density = LocalDensity.current
    val landscape = Overlay.aspect() > 1f
    LaunchedEffect(landscape) { Overlay.orientationChanged(landscape) }
    val w = with(density) { Overlay.widthPx.floatValue.toDp() }
    val h = w / Overlay.aspect()
    // 1.4.0-beta4: the controls scale with the window's short side. Scaled by the width, a landscape window (wide,
    // short) got the largest controls over the smallest picture height; now both orientations get the same size.
    val k = (minOf(w.value, h.value) / 200f).clampIn(0.75f, 1.15f)
    val corner = (14 + 6 * k).dp

    var left by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(owner, mode) {
        while (true) { left = Turns.leftMs(owner) ?: -1L; delay(1000) }
    }
    // 1.3.0-beta14: a lesson is recorded wherever the user is (this window or the full screen page).
    val teachN = (lesson as? com.kilon.agentvscreen.cmd.Lesson.Recording)?.steps
    val octx = LocalContext.current
    // 1.4.0-beta11: no effect here ends a lesson any more (a hand-back is handled once, in Teach.onTakeoverEnded).
    // 1.4.0-beta2: the app is with the user on the main screen: no stale picture, one way to hand it back.
    val givenAll by com.kilon.agentvscreen.core.VScreenOwnership.given.collectAsState()
    val mine = givenAll.values.firstOrNull { it.session == owner && !it.agentMovedOn }
    val mineLabel = remember(mine?.pkg) { mine?.let { com.kilon.agentvscreen.core.VScreenOwnership.label(octx, it.pkg) } }
    val title = mineLabel?.let { tr("已交给你 · $it", "With you · $it") } ?: teachN?.let { tr("示教录制中 · 已记 $it 步", "Recording a lesson · ${steps(it)}") } ?: (if (lesson is com.kilon.agentvscreen.cmd.Lesson.Ready) tr("示教准备中", "Getting ready to show") else null) ?: s.agentTitle ?: when (mode) {
        Mode.ACTING -> s.action?.take(18) ?: Palette.word(mode)
        Mode.STANDBY -> if (left >= 0) tr("AI 待命", "AI on standby") + " · ${left / 60000}:${"%02d".format((left / 1000) % 60)}" else Palette.word(mode)
        Mode.IDLE -> if (s.powerSaving) tr("空闲 · 省电暂停", "Idle · paused to save power") else tr("空闲", "Idle")
        else -> Palette.word(mode)
    }
    val chip = Color(0xCC12141C)
    val action: Triple<String, Color, () -> Unit>? = when {
        mine != null -> Triple(tr("交回 agent", "Hand back"), Palette.takeover) { val c = octx.applicationContext; val p = mine.pkg; Thread { com.kilon.agentvscreen.core.VScreenOwnership.handBack(c, p, "overlay") }.start() }
        showing -> null // the lesson strip under the window has 完成 / 不教
        mode == Mode.TAKEOVER || mode == Mode.STOPPED -> Triple(tr("交还 AI", "Hand back"), Palette.takeover) { BackgroundOperation.userResume() }
        // 1.3.0-beta19 (rainbow): stop only while the AI acts or waits; idle shows nothing.
        mode == Mode.ACTING || mode == Mode.STANDBY -> Triple(tr("停止 AI", "Stop AI"), Palette.stopped) { BackgroundOperation.userStop() }
        else -> null
    }

    // 1.4.2-beta6 + 2.0.0-beta8 (K.5): small window in the top-right corner; optionally shows title/fullscreen/close/stop controls.
    if (Overlay.mini.value) {
        val mk = (minOf(w.value, h.value) / 200f).clampIn(0.62f, 0.90f)
        Box(Modifier.padding(PAD).width(w).height(h).glowBorder(mode, corner, pad = PAD - 1.dp)) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(corner)).background(Palette.ink)
                    .then(if (s.miniShowControls) Modifier.tapOrEat { if (s.miniTapToExpand) Overlay.fromMini() } else Modifier),
            ) {
                if (picture != null) picture(owner) else VScreenLiveFrame(owner, Modifier.fillMaxSize())
                if (s.miniShowControls) {
                    Box(Modifier.fillMaxWidth().height((32 * mk).dp).background(Brush.verticalGradient(listOf(Color(0x80000000), Color.Transparent))))
                }
                val asks = waiting.size + if (review != null || showing) 1 else 0
                if (asks > 0) Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) { AskMark(22f, asks) }
            }
            if (s.miniShowControls) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(start = (5 * mk).dp, top = (5 * mk).dp).widthIn(max = (w - (56 * mk).dp).coerceAtLeast(36.dp))
                        .height(CHIP_H * mk).clip(RoundedCornerShape(50)).background(chip).padding(horizontal = (6 * mk).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size((6 * mk).dp).clip(CircleShape).background(if (mode == Mode.ACTING) Brush.sweepGradient(Palette.rainbow) else Brush.linearGradient(listOf(Palette.of(mode), Palette.of(mode)))))
                    Spacer(Modifier.width((4 * mk).dp))
                    Text(title, color = Color.White, fontSize = (10 * mk).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                }
                Row(Modifier.align(Alignment.TopEnd).padding(end = (5 * mk).dp, top = (5 * mk).dp), horizontalArrangement = Arrangement.spacedBy((4 * mk).dp)) {
                    RoundBtn(Icons.Filled.Fullscreen, tr("全屏", "Full screen"), chip, mk) { Overlay.openFull() }
                    RoundBtn(Icons.Filled.Close, if (mode == Mode.ACTING || mode == Mode.STANDBY || mode == Mode.TAKEOVER) tr("隐藏小窗", "Hide window") else tr("关闭副屏", "Close screen"), chip, mk) { Overlay.closeTapped() }
                }
                if (action != null) {
                    val dark = action.second == Palette.takeover
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = (6 * mk).dp)
                            .clip(RoundedCornerShape(50)).background(action.second).clickable(onClick = action.third)
                            .padding(horizontal = (9 * mk).dp, vertical = (4 * mk).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (dark) Icons.Filled.SmartToy else Icons.Filled.Stop, null, tint = if (dark) Color(0xFF052E1C) else Color.White, modifier = Modifier.size((11 * mk).dp))
                        Spacer(Modifier.width((2 * mk).dp))
                        Text(action.first, color = if (dark) Color(0xFF052E1C) else Color.White, fontSize = (10 * mk).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
        return
    }

    // 1.3.0-beta17: the controls sit ON the picture again (rainbow: no extra height above or below it), small and in
    // the corners: title pill top left, full screen / close top right, stop / hand back / done at the bottom. They do
    // not leak through: each is a hit target of its own (clickable or eatTouches), and Compose gives a touch only to
    // the topmost thing it lands on, so the picture under them never gets it (never sent, never recorded). A question
    // card still hangs OUTSIDE the window (below it, or above when there is no room below): a card over the picture
    // would hide what the user has to tap.
    val asking = waiting.firstOrNull()
    // The last question stays composed while its card shrinks away.
    var shownAsk by remember { mutableStateOf(asking) }
    if (asking != null) shownAsk = asking

    Column(Modifier.padding(PAD).width(w), verticalArrangement = Arrangement.spacedBy((6 * k).dp)) {
        Box(Modifier.width(w).height(h).glowBorder(mode, corner, pad = PAD - 1.dp)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(corner)).background(Palette.ink)) {
                // The picture: the only layer whose fingers reach the background screen.
                PictureLayer(owner, k, picture)
                if (mine != null) Column(
                    Modifier.fillMaxSize().eatTouches().background(Palette.ink).padding((12 * k).dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.PanTool, null, tint = Palette.takeover, modifier = Modifier.size((26 * k).dp))
                    Spacer(Modifier.height((6 * k).dp))
                    Text(tr("$mineLabel 在主屏由你使用", "$mineLabel is on your main screen"), color = Color.White, fontSize = (12 * k).sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    Text(tr("用完点“交回 agent”，它从你离开的页面接着做", "When done, tap Hand back; it carries on from where you left it"), color = Color(0xB3FFFFFF), fontSize = (10 * k).sp, textAlign = TextAlign.Center)
                }
                // A soft scrim under the pill and buttons keeps them readable on white pages (draws only, no touch).
                Box(Modifier.fillMaxWidth().height((40 * k).dp).background(Brush.verticalGradient(listOf(Color(0x80000000), Color.Transparent))))
                // 2.0.0-beta8 (G.4): dragged into top-right corner: overlay only a compact text pill, keeping the preview un-dimmed.
                if (Overlay.cornerHot.value) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        tr("松手收成小窗", "Let go for a small window"),
                        color = Color.White,
                        fontSize = (12 * k).sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xCC12141C))
                            .padding(horizontal = (12 * k).dp, vertical = (5 * k).dp),
                    )
                }
            }

            // 1.4.2-beta6 (rainbow): the bottom bar, like a phone's navigation bar. A tap on the window shows it; press at
            // its height and drag to resize with one finger (the strip is the window's own, see GestureFrame / Overlay.onBar).
            if (Overlay.barShown.value) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(Overlay.BAR_ZONE.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x59000000)))),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.width((w * 0.3f).coerceIn(40.dp, 140.dp)).height(5.dp).clip(RoundedCornerShape(50)).background(Color(0xE6FFFFFF)))
                }
            }

            // Title pill: a handle (drag or hold it to move the window, also while you hold the screen).
            Row(
                Modifier.align(Alignment.TopStart).padding(start = (7 * k).dp, top = (7 * k).dp).widthIn(max = w - (70 * k).dp)
                    .height(CHIP_H * k).tapOrEat { Overlay.showBar() }.clip(RoundedCornerShape(50)).background(chip)
                    .padding(horizontal = (8 * k).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 1.4.0-beta15 (rainbow): no grip icon; the state dot already marks the pill, and the grip took room from the title.
                Box(Modifier.size((7 * k).dp).clip(CircleShape).background(if (mode == Mode.ACTING) Brush.sweepGradient(Palette.rainbow) else Brush.linearGradient(listOf(Palette.of(mode), Palette.of(mode)))))
                Spacer(Modifier.width((5 * k).dp))
                Text(title, color = Color.White, fontSize = (11 * k).sp, maxLines = 1, fontWeight = FontWeight.Medium, modifier = Modifier.scrollIfLong())
            }

            // Full screen and close, top right. Same places in every state.
            // 1.3.0-beta18: the same height as the title pill, on the same line (rainbow).
            Row(Modifier.align(Alignment.TopEnd).padding(end = (7 * k).dp, top = (7 * k).dp), horizontalArrangement = Arrangement.spacedBy((5 * k).dp)) {
                // 1.4.0-beta13 (test L2: the bare ↗ was not read as "full screen"): the standard full screen mark, named.
                RoundBtn(Icons.Filled.Fullscreen, tr("全屏", "Full screen"), chip, k) { Overlay.openFull() }
                RoundBtn(Icons.Filled.Close, if (mode == Mode.ACTING || mode == Mode.STANDBY || mode == Mode.TAKEOVER) tr("隐藏小窗", "Hide window") else tr("关闭副屏", "Close screen"),
                    chip, k) { Overlay.closeTapped() }
            }

            // The one action of this state, bottom centre (one place for all of them): stop, done or hand back.
            if (action != null) {
                val dark = action.second == Palette.takeover
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = (8 * k).dp + if (Overlay.barShown.value) Overlay.BAR_ZONE.dp else 0.dp)
                        .clip(RoundedCornerShape(50)).background(action.second).clickable(onClick = action.third)
                        .padding(horizontal = (11 * k).dp, vertical = (5 * k).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (dark) Icons.Filled.SmartToy else Icons.Filled.Stop, null, tint = if (dark) Color(0xFF052E1C) else Color.White, modifier = Modifier.size((13 * k).dp))
                    Spacer(Modifier.width((3 * k).dp))
                    Text(action.first, color = if (dark) Color(0xFF052E1C) else Color.White, fontSize = (11 * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        val ease = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntSize>(Overlay.CARD_MS.toInt(), easing = androidx.compose.animation.core.LinearOutSlowInEasing)
        AnimatedVisibility(asking != null || review != null || showing,
            enter = androidx.compose.animation.expandVertically(ease, expandFrom = Alignment.Top) + fadeIn(androidx.compose.animation.core.tween(Overlay.CARD_MS.toInt())),
            exit = androidx.compose.animation.shrinkVertically(ease, shrinkTowards = Alignment.Top) + fadeOut(androidx.compose.animation.core.tween(160))) {
            val rv = review
            if (showing) androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().eatTouches().glassDark(RoundedCornerShape((18 * k).dp))
                .padding(horizontal = (12 * k).dp, vertical = (11 * k).dp)
                .onSizeChanged { sz -> if (sz.height.toFloat() != Overlay.cardH.floatValue) { Overlay.cardH.floatValue = sz.height.toFloat() }; Overlay.fitGroup() }) {
                LessonStrip(owner, k, withHead = true, onSay = { Surface.openLive(octx, say = true) })
            }
            else if (asking == null && rv != null) ReviewQuick(rv, k, Modifier.fillMaxWidth()
                .onSizeChanged { sz -> if (sz.height.toFloat() != Overlay.cardH.floatValue) { Overlay.cardH.floatValue = sz.height.toFloat() }; Overlay.fitGroup() })
            else shownAsk?.let { a ->
                // Measured at its full size from the first frame (the expansion only clips it), so the window starts
                // its glide up at the same moment and lands as the card finishes growing.
                AskQuick(a, waiting.size.coerceAtLeast(1), k, modifier = Modifier.fillMaxWidth()
                    .onSizeChanged { sz -> if (sz.height.toFloat() != Overlay.cardH.floatValue) { Overlay.cardH.floatValue = sz.height.toFloat() }; Overlay.fitGroup() })
            }
        }
    }
}

/**
 * The picture. Taken over: a finger here IS a finger on the background screen from the moment it lands (UserTouch),
 * cancelled (never tapped) when the window takes the touch back for a pinch. Not taken over: hold 0.5 s to take over
 * (a ring shows it filling), a tap shows the gesture hint, a drag is the window's (the frame takes it).
 */
@Composable
private fun PictureLayer(owner: String, k: Float, picture: (@Composable (String) -> Unit)?) {
    var hold by remember { mutableFloatStateOf(0f) }
    var holdAt by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(Modifier.fillMaxSize().pointerInput(owner) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            Overlay.pictureDown = true
            val frame = RectF(0f, 0f, size.width.toFloat(), size.height.toFloat())
            fun screen(p: Offset) = pictureToScreen(p.x.clampIn(0f, frame.right - 1f), p.y.clampIn(0f, frame.bottom - 1f), frame)
            val st = BackgroundOperation.state.value
            val taken = st.takeoverSessionId != null && st.takeoverSessionId == st.ownerSessionId
            if (taken) {
                val p0 = screen(down.position) ?: return@awaitEachGesture
                if (!com.kilon.agentvscreen.core.UserTouch.down(owner, p0.first, p0.second)) return@awaitEachGesture
                Overlay.pictureLive = true
                var ended = false
                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (!Overlay.frameClaimed) { val p = screen(ch.position); com.kilon.agentvscreen.core.UserTouch.up(p?.first ?: -1, p?.second ?: -1); ended = true }
                            break
                        }
                        screen(ch.position)?.let { com.kilon.agentvscreen.core.UserTouch.move(it.first, it.second) }
                        ch.consume()
                    }
                } finally {
                    if (!ended) com.kilon.agentvscreen.core.UserTouch.cancel()
                    Overlay.pictureLive = false
                }
            } else {
                holdAt = down.position
                val t0 = SystemClock.uptimeMillis()
                var held = false
                var up = false
                try {
                    while (true) {
                        val ev = withTimeoutOrNull(16L) { awaitPointerEvent() }
                        if (Overlay.frameClaimed) break
                        if (!held) {
                            hold = ((SystemClock.uptimeMillis() - t0 - 100L) / 400f).clampIn(0f, 1f)
                            if (hold >= 1f) {
                                held = true; hold = 0f; Overlay.pictureHeld = true
                                BackgroundOperation.beginTakeover(owner)
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            }
                        }
                        if (ev == null) continue
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { up = true; break }
                    }
                } finally { hold = 0f }
                if (up && !held && !Overlay.frameClaimed) { Overlay.hintAt.longValue = SystemClock.uptimeMillis(); Overlay.showBar() }
            }
        }
    }) {
        if (picture != null) picture(owner) else VScreenLiveFrame(owner, Modifier.fillMaxSize())

        // Gesture hint (a tap on the picture while the AI has the screen).
        var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
        LaunchedEffect(Overlay.hintAt.longValue) { now = SystemClock.uptimeMillis(); delay(3100); now = SystemClock.uptimeMillis() }
        val showHint = Overlay.hintAt.longValue > 0 && now - Overlay.hintAt.longValue < 2500
        AnimatedVisibility(showHint, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            // 1.3.0-beta18: one gesture per line (rainbow: three lines, not one run-on line).
            Text(tr("长按 0.5 秒接管\n按住底部横条拖动缩放\n拖到右上角收成小窗", "Hold 0.5 s to take over\nDrag the bottom bar to resize\nDrag to the top right for a small window"), color = Color.White, fontSize = (11 * k).sp,
                lineHeight = (16 * k).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xDD12141C)).padding(horizontal = 10.dp, vertical = 6.dp))
        }

        // Hold-to-take-over ring.
        if (hold > 0f) {
            val ringR = with(density) { (28 * k).dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color(0x66000000), ringR + 6f, holdAt)
                drawArc(Palette.takeover, -90f, 360f * hold, false, topLeft = Offset(holdAt.x - ringR, holdAt.y - ringR),
                    size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2), style = Stroke(width = 6f, cap = StrokeCap.Round))
            }
            Icon(Icons.Filled.PanTool, null, tint = Color.White, modifier = Modifier.offset { IntOffset(holdAt.x.roundToInt() - 24, holdAt.y.roundToInt() - 24) }.size(16.dp))
        }
    }
}

@Composable
private fun RoundBtn(icon: ImageVector, desc: String, bg: Color, k: Float, onClick: () -> Unit) {
    Box(
        Modifier.size(CHIP_H * k).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size((13 * k).dp)) }
}

/**
 * The folded window: a half capsule peeking from the edge, holding a tiny phone whose outline breathes in the same
 * colour as the big window (rainbow while the AI acts) — the app's own mark, so it reads as "the background screen".
 */
@Composable
internal fun DockTab(mode: Mode, right: Boolean, asking: Int = 0) {
    val shape = if (right) RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp) else RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp)
    val glass = listOf(Color(0xF0262A38), Color(0xF014161F))
    // 1.3 beta2: an agent is waiting for an answer: the folded tab says so by itself (the user may never open the
    // notification shade). Amber, pulsing, with a question mark where the little phone is: unlike standby and acting.
    if (asking > 0) {
        // 1.3 beta3: the same glass as the window, an orchid edge that breathes, and the question mark in its place.
        val pulse = if (shouldAnimateLook()) {
            val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "ask")
            t.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse").value
        } else 1f
        Box(
            Modifier.width(Overlay.TAB_W.dp).height(Overlay.TAB_H.dp)
                .shadow(14.dp, shape, ambientColor = Palette.asking, spotColor = Palette.asking)
                .glassDark(shape)
                .border(1.5.dp, Brush.verticalGradient(listOf(Color(0xFF8B7CFF).copy(alpha = pulse), Palette.asking.copy(alpha = pulse))), shape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.offset(x = if (right) 2.dp else (-2).dp)) { AskMark(18f, asking, 0.55f + 0.45f * pulse) }
        }
        return
    }
    Box(
        Modifier.width(Overlay.TAB_W.dp).height(Overlay.TAB_H.dp)
            .shadow(10.dp, shape)
            .clip(shape)
            .background(Brush.horizontalGradient(if (right) glass else glass.reversed()))
            .border(1.dp, Color(0x2EFFFFFF), shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.offset(x = if (right) 2.dp else (-2).dp).size(width = 11.dp, height = 20.dp)
                .glowBorder(mode, 3.5.dp, width = 1.6.dp, pad = 2.dp)
                .clip(RoundedCornerShape(3.5.dp)).background(Color(0xFF0B0D14)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // A navigation bar at its bottom: it reads as a phone screen, not an empty frame.
            Box(Modifier.padding(bottom = 2.dp).width(5.dp).height(1.2.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xCCFFFFFF)))
        }
    }
}
