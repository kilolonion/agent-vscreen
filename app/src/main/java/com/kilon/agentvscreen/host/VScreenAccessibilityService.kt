package com.kilon.agentvscreen.host

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Read-only window-content access for the virtual display: the element list (text, ids, bounds, editable) and
 * SET_TEXT for typing Chinese. It never performs gestures and never turns on touch exploration, so it coexists with
 * any other accessibility service (an agent's own one included). Optional: without it the tool still works on OCR.
 */
class VScreenAccessibilityService : AccessibilityService() {
    /** 2.0.0-beta8 (C5): system Toast event captured via TYPE_NOTIFICATION_STATE_CHANGED. */
    data class ToastEvent(
        val epochMs: Long,
        val monoMs: Long,
        val packageName: String,
        val text: String,
    )

    companion object {
        @Volatile private var instance: VScreenAccessibilityService? = null
        fun getInstance(): VScreenAccessibilityService? = instance

        /**
         * 1.3: when the windows (a new page) or their content last changed, per display (elapsedRealtime; key -1 =
         * display unknown on this system). A wait after an action uses it to know the app reacted, and an element
         * number to know the page it was given on is gone.
         */
        private val stateAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        private val contentAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        private val displayOf: java.lang.reflect.Method? = runCatching { AccessibilityEvent::class.java.getMethod("getDisplayId") }.getOrNull()
        fun pageChangedAt(displayId: Int): Long = maxOf(stateAt[displayId] ?: 0L, if (displayOf == null) stateAt[-1] ?: 0L else 0L)
        fun contentChangedAt(displayId: Int): Long = maxOf(contentAt[displayId] ?: 0L, pageChangedAt(displayId), if (displayOf == null) contentAt[-1] ?: 0L else 0L)
        /** Whether events carry their display here (else changes on any display count). */
        val knowsDisplay: Boolean get() = displayOf != null

        private const val MAX_TOAST_EVENTS = 30
        private const val TOAST_DEDUP_MS = 500L
        private val toastLock = Any()
        private val toastRing = ArrayDeque<ToastEvent>(MAX_TOAST_EVENTS)

        internal fun recordToast(pkg: String, text: String, epochMs: Long = System.currentTimeMillis(), monoMs: Long = android.os.SystemClock.elapsedRealtime()) {
            val cleanPkg = pkg.trim()
            val cleanText = com.kilon.agentvscreen.cmd.ScreenText.clean(text, maxLen = 200)
            if (cleanPkg.isEmpty() || cleanText.isEmpty()) return
            synchronized(toastLock) {
                val dup = toastRing.lastOrNull()?.let { last ->
                    last.packageName == cleanPkg && last.text == cleanText && kotlin.math.abs(epochMs - last.epochMs) <= TOAST_DEDUP_MS
                } == true
                if (dup) return
                if (toastRing.size >= MAX_TOAST_EVENTS) toastRing.removeFirst()
                toastRing.addLast(ToastEvent(epochMs, monoMs, cleanPkg, cleanText))
            }
        }

        /** Return recorded system Toasts matching [pkg] whose timestamp lies in [[fromEpochMs], [toEpochMs]]. */
        fun recentToasts(pkg: String, fromEpochMs: Long, toEpochMs: Long): List<ToastEvent> {
            if (pkg.isBlank()) return emptyList()
            return synchronized(toastLock) {
                toastRing.filter { it.packageName == pkg && it.epochMs in fromEpochMs..toEpochMs }
            }
        }

        internal fun clearToastsForTests() = synchronized(toastLock) { toastRing.clear() }

        /**
         * 2.0.0-beta6 (6.9 / 6.10 #3): pure gate checking whether an accessibility action may target a node.
         * Must sit on a non-default (virtual) display and must never belong to our own package.
         */
        internal fun canPerformActionOn(displayId: Int?, nodePkg: String?, rootPkg: String?, ownPkg: String): Boolean {
            if (displayId == null || displayId <= android.view.Display.DEFAULT_DISPLAY) return false
            if (ownPkg.isNotBlank() && (nodePkg == ownPkg || rootPkg == ownPkg)) return false
            return true
        }

        /**
         * 2.0.0-beta6 (6.9 / 6.10 #3): pre-action check shared by [setNodeText] and [com.kilon.agentvscreen.core.A11yTree.Snapshot.scrollForward].
         * Refuses display 0, unknown display, and our own package; writes [Audit.security] on refusal.
         */
        internal fun verifyNodeForAction(node: AccessibilityNodeInfo, actionName: String): Boolean {
            val own = instance?.applicationContext?.packageName ?: com.kilon.agentvscreen.BuildConfig.APPLICATION_ID
            val w = runCatching { node.window }.getOrNull()
            val dId = runCatching { w?.displayId }.getOrNull()
            val nPkg = runCatching { node.packageName?.toString() }.getOrNull()
            val rPkg = runCatching { w?.root?.packageName?.toString() }.getOrNull()
            if (!canPerformActionOn(dId, nPkg, rPkg, own)) {
                Audit.security("a11y_action_blocked", "$actionName display=${dId ?: -1} pkg=${nPkg ?: rPkg ?: "unknown"}")
                AppLogger.warning("A11y", "blocked $actionName on display=${dId ?: -1} pkg=${nPkg ?: rPkg ?: "unknown"}")
                return false
            }
            return true
        }
    }

    override fun onServiceConnected() { instance = this; AppLogger.info("A11y", "connected"); com.kilon.agentvscreen.ui.Overlay.rehost() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; A11yHeal.onLost(applicationContext); com.kilon.agentvscreen.ui.Overlay.rehost(); return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val id = runCatching { displayOf?.invoke(e) as? Int }.getOrNull() ?: -1
        val now = android.os.SystemClock.elapsedRealtime()
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> stateAt[id] = now
            AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> contentAt[id] = now
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val cls = e.className?.toString().orEmpty()
                if (cls == "android.widget.Toast") {
                    val pkg = e.packageName?.toString().orEmpty()
                    val joined = com.kilon.agentvscreen.cmd.ScreenText.clean(
                        e.text.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }.joinToString(" "),
                        maxLen = 200,
                    )
                    if (pkg.isNotEmpty() && joined.isNotEmpty()) {
                        recordToast(pkg, joined, System.currentTimeMillis(), now)
                        AppLogger.info("A11yToast", "pkg=$pkg text=${joined.take(120)}")
                    }
                }
            }
        }
    }
    override fun onInterrupt() {}

    /**
     * 2.0.0-beta6 (6.10 #3): single entry point for reading accessibility nodes.
     * Refuses display 0 (`DEFAULT_DISPLAY`) and filters out any window belonging to our own package.
     */
    fun rootNodesForDisplay(displayId: Int): List<AccessibilityNodeInfo> {
        if (displayId <= android.view.Display.DEFAULT_DISPLAY) return emptyList()
        val own = runCatching { applicationContext.packageName }.getOrDefault(com.kilon.agentvscreen.BuildConfig.APPLICATION_ID)
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            val windows = windowsOnAllDisplays.get(displayId) ?: return emptyList()
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val wd = runCatching { w.displayId }.getOrDefault(displayId)
                if (wd <= android.view.Display.DEFAULT_DISPLAY || wd != displayId) continue
                val root = w.root ?: continue
                if (own.isNotBlank() && root.packageName?.toString() == own) continue
                out.add(root)
            }
        } catch (_: Throwable) {}
        return out
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!verifyNodeForAction(node, "ACTION_SET_TEXT")) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
