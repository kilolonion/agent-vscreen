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
        }
    }
    override fun onInterrupt() {}

    fun rootNodesForDisplay(displayId: Int): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            val windows = windowsOnAllDisplays.get(displayId) ?: return emptyList()
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                w.root?.let { out.add(it) }
            }
        } catch (_: Throwable) {}
        return out
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
