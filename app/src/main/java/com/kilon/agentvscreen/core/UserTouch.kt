package com.kilon.agentvscreen.core

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicReference

/**
 * 1.2.0-beta2: the user's finger on the picture (floating window or full-screen page), sent to the background screen
 * as it moves.
 *
 * Before, a touch on the picture was replayed after the finger lifted: a swipe became one synthetic swipe sent on
 * release, so a list did not move under the finger and a drag could not be steered. Now the finger is a held pointer
 * on the screen: down when it lands, moved as it moves, up when it lifts. Taps, long presses, scrolling and dragging
 * all come out of that one stream, exactly as on a real screen.
 *
 * Injection is a call into the display service, so it runs on its own thread; moves that pile up while one call is
 * in flight collapse into the newest position, which keeps the screen at the finger instead of behind it.
 */
object UserTouch {
    /** The pointer id kept for the user (agents use the low ids). */
    private const val FINGER = 9

    private val thread = HandlerThread("vscreen-user-touch").apply { isDaemon = true; start() }
    private val handler = Handler(thread.looper)
    private val pendingMove = AtomicReference<Pair<Int, Int>?>(null)
    @Volatile private var displayId: Int? = null
    @Volatile var sent = 0L; private set

    /** For tests: what would have been injected. */
    internal var sink: ((Int, String, Int, Int) -> Unit)? = null

    private fun inject(id: Int, op: String, x: Int, y: Int) {
        sent++
        sink?.let { it(id, op, x, y); return }
        runCatching { VScreenShell.finger(id, op, FINGER, x, y) }
    }

    private val flushMove = Runnable {
        val p = pendingMove.getAndSet(null) ?: return@Runnable
        val id = displayId ?: return@Runnable
        inject(id, "move", p.first, p.second)
    }

    /** The finger landed at ([x], [y]) of the screen. False when [sessionId] is not the one the user took over. */
    fun down(sessionId: String, x: Int, y: Int): Boolean {
        val s = BackgroundOperation.state.value
        val id = s.displayId ?: return false
        if (s.takeoverSessionId != sessionId) return false
        // Frames at full rate while the finger is down and a little after, so the picture keeps up with it.
        BackgroundOperation.previewDisplayFor(sessionId)?.boost(1_500)
        begin(id, x, y)
        return true
    }

    internal fun begin(id: Int, x: Int, y: Int) {
        displayId = id
        pendingMove.set(null)
        handler.post { inject(id, "down", x, y) }
    }

    fun move(x: Int, y: Int) {
        if (displayId == null) return
        // Only the newest position is kept; one flush is queued at a time.
        if (pendingMove.getAndSet(x to y) == null) handler.post(flushMove)
    }

    fun up(x: Int, y: Int) = end("up", x, y)

    /** The gesture was taken away (a second finger, the window closed): lift without a tap at the last point. */
    fun cancel() = end("cancel", -1, -1)

    private fun end(op: String, x: Int, y: Int) {
        val id = displayId ?: return
        displayId = null
        val last = pendingMove.getAndSet(null)
        handler.post {
            // The last move the finger made before lifting decides where a fling starts from: never dropped.
            if (op == "up" && last != null) inject(id, "move", last.first, last.second)
            inject(id, op, x, y)
        }
        if (op == "up") BackgroundOperation.state.value.ownerSessionId?.let { BackgroundOperation.previewDisplayFor(it)?.boost(1_500) }
    }

    val active: Boolean get() = displayId != null

    /** For tests: wait until everything queued was sent. */
    internal fun drain() { val l = java.util.concurrent.CountDownLatch(1); handler.post { l.countDown() }; l.await(2, java.util.concurrent.TimeUnit.SECONDS) }
}
