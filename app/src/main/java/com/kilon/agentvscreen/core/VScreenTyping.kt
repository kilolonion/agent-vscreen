package com.kilon.agentvscreen.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.tr
import com.kilon.agentvscreen.host.VScreenAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 1.2: typing, taken out of BackgroundOperation (which owns the screen's life, not what is written on it).
 * Three ways, in this order: write into the focused field through accessibility (any language), inject key events
 * through the display service (ASCII), clipboard + paste (the rest). What the field reads afterwards is returned as a
 * fact; a password field gives its length only. The typed text is never logged.
 */
object VScreenTyping {
    /**
     * Type [text] into the focused field of [displayId]: accessibility
     * SET_TEXT first (works for Chinese), ASCII `input text` second,
     * clipboard + PASTE key last. Returns the method used.
     */
    @Throws(ActionError::class)
    fun typeText(displayId: Int, text: String, append: Boolean): String {
        val svc = VScreenAccessibilityService.getInstance()
        if (svc != null) {
            val focused = BackgroundOperation.nodeRoots(displayId).firstNotNullOfOrNull {
                runCatching { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            }
            if (focused != null && focused.isEditable) {
                val value = if (append) (focused.text?.toString().orEmpty() + text) else text
                if (svc.setNodeText(focused, value)) return "accessibility SET_TEXT"
            }
        }
        if (text.all { it.code in 32..126 }) {
            val r = VScreenShell.typeKeys(displayId, text)
            if (r.ok) return tr("按键注入", "key injection")
            throw ActionError(r.code, r.message)
        }
        pasteText(displayId, text)
        return tr("clipboard paste（无障碍未找到焦点输入框，已用剪贴板粘贴；请读屏核对）", "clipboard paste (accessibility found no focused input box, pasted via clipboard; check the screen)")
    }

    /**
     * The field typing goes into. findFocus(FOCUS_INPUT) first; Flutter and some WebViews do not report input focus,
     * so then a focused editable node, then the only visible editable node on the screen.
     */
    /**
     * The field typing goes to: the editable that has input focus. v0.5: an editable that merely exists (a browser's
     * address bar while the page's own field is invisible to accessibility) is NOT it — writing there typed into the
     * address bar while the tool said "typed". When the caller tapped at [at], an editable under that point counts too
     * (some toolkits report focus late or never).
     */
    private fun focusedEditable(displayId: Int, at: Pair<Int, Int>? = null): AccessibilityNodeInfo? {
        val roots = BackgroundOperation.nodeRoots(displayId)
        roots.firstNotNullOfOrNull { runCatching { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() }
            ?.takeIf { it.isEditable }?.let { return it }
        val edits = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 50 || edits.size > 16) return
            if (n.isVisibleToUser && n.isEditable) edits += n
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        }
        roots.forEach { walk(it, 0) }
        edits.firstOrNull { it.isFocused }?.let { return it }
        if (at != null) return edits.filter { e -> Rect().also { e.getBoundsInScreen(it) }.let { r -> r.contains(at.first, at.second) } }
            .minByOrNull { e -> Rect().also { e.getBoundsInScreen(it) }.let { r -> r.width().toLong() * r.height() } }
        return null
    }

    private fun fieldText(n: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && n.isShowingHintText) return ""
        return n.text?.toString().orEmpty()
    }

    /**
     * v49: type [text] into the field that has input focus and say what happened, without a verdict: the method used
     * and what the field reads afterwards (null when no field could be read; a password field gives its length only).
     * The typed text is never logged.
     */
    /** What typing did: the method, the field it went to (bounds, id) and what that field reads afterwards. */
    /** [checked] false = accessibility could not look at the screen, so [field] null says nothing about focus. */
    class TypeFact(val method: String, val field: String?, val bounds: Rect?, val fieldId: String?, val checked: Boolean = true, val password: Boolean = false)

    /** The field that has input focus now, as "id@l,t,r,b" (null: none / not visible to accessibility). */
    fun focusedFieldKey(displayId: Int): String? = runCatching {
        focusedEditable(displayId)?.let { n -> val b = Rect().also { n.getBoundsInScreen(it) }; "${n.viewIdResourceName}@${b.left},${b.top},${b.right},${b.bottom}" }
    }.getOrNull()

    /**
     * Type into the focused field. [tappedAt] / [focusBefore]: where the agent just tapped and which field had focus
     * before that tap. When the focused field is still the one from before the tap and is not where the tap went, the
     * tap did not move focus (it hit something that is not a field): nothing is typed and the error says where focus is.
     * A tap that opened another page whose field is elsewhere (a search bar that opens a search page) moved focus, so it
     * types normally.
     */
    @Throws(ActionError::class)
    fun typeFact(displayId: Int, text: String, append: Boolean, tappedAt: Pair<Int, Int>? = null, focusBefore: String? = null): TypeFact {
        val svc = VScreenAccessibilityService.getInstance()
        val roots = if (svc != null) BackgroundOperation.nodeRoots(displayId) else emptyList()
        val a11y = svc != null && roots.isNotEmpty()
        // Focus can land a moment after the tap (Flutter): look twice.
        val node = if (a11y) focusedEditable(displayId, tappedAt) ?: run { Thread.sleep(300); focusedEditable(displayId, tappedAt) } else null
        val nb = node?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
        val nid = node?.viewIdResourceName?.substringAfterLast('/')
        // v0.5: the field with focus is not where the agent just tapped (a page's own box that accessibility cannot
        // see, while the address bar keeps focus): typing would land in the wrong place. Do not type; say where focus is.
        if (tappedAt != null && nb != null && focusBefore != null && focusBefore == "${node.viewIdResourceName}@${nb.left},${nb.top},${nb.right},${nb.bottom}") {
            val slack = Rect(nb).apply { inset(-24, -24) }
            if (!slack.contains(tappedAt.first, tappedAt.second)) throw ActionError("FOCUS_ELSEWHERE",
                tr("没有输入：有焦点的输入框在 [${nb.left},${nb.top},${nb.right},${nb.bottom}]" + (nid?.let { "（$it）" } ?: "") +
                    "，不是你刚点的 (${tappedAt.first},${tappedAt.second})。你点的地方可能不是输入框（网页格子、画布），或者它没拿到焦点：" +
                    "看【现在】里哪个元素是“可输入”，或换一种操作（如点屏幕上的数字键）",
                    "Nothing typed: the focused input box is at [${nb.left},${nb.top},${nb.right},${nb.bottom}]" + (nid?.let { " ($it)" } ?: "") +
                    ", not where you just tapped (${tappedAt.first},${tappedAt.second}). What you tapped may not be an input box (web grid, canvas), or it did not get focus: " +
                    "check which element in [Now] is \"input\", or act differently (e.g. tap number keys on the screen)"))
        }
        val before = node?.let { fieldText(it) }.orEmpty()
        val method = when {
            node != null && svc!!.setNodeText(node, if (append) before + text else text) -> tr("无障碍写入", "accessibility write")
            text.all { it.code in 32..126 } -> {
                val r = VScreenShell.typeKeys(displayId, text)
                if (!r.ok) throw ActionError(r.code, r.message)
                tr("按键注入", "key injection")
            }
            else -> { pasteText(displayId, text); tr("剪贴板粘贴", "clipboard paste") }
        }
        if (!a11y) return TypeFact(method, null, null, null, checked = false)
        Thread.sleep(250)
        val after = focusedEditable(displayId, tappedAt) ?: node
        com.kilon.agentvscreen.host.quiet("VScreenTyping:137") { after?.refresh() }
        // A password box gives its length only: what is in it never leaves the phone.
        val now = after?.let { if (it.isPassword) "•".repeat(fieldText(it).length) else fieldText(it) }
        val ab = after?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
        return TypeFact(method, now, ab, after?.viewIdResourceName?.substringAfterLast('/'), password = after?.isPassword == true)
    }

    /** Put [text] on the clipboard and press PASTE on [displayId]'s focused field. */
    @Throws(ActionError::class)
    fun pasteText(displayId: Int, text: String): String {
        // Writing the clipboard is allowed from the background; reading it usually is not
        // (Android 10+), so the user's previous clip can be put back only when we could read it.
        val cm = (BackgroundOperation.appContextOrNull() ?: error("not initialized")).getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var previous: ClipData? = null
        val latch = CountDownLatch(1)
        var clipOk = false
        Handler(Looper.getMainLooper()).post {
            previous = runCatching { cm.primaryClip }.getOrNull()
            clipOk = runCatching { cm.setPrimaryClip(ClipData.newPlainText("agent-vscreen", text)) }.isSuccess
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        if (!clipOk) throw ActionError("TYPE_FAILED", tr("无法写入剪贴板", "Cannot write the clipboard"))
        val r = VScreenShell.key(displayId, "paste")
        if (!r.ok) throw ActionError(r.code, r.message)
        // Give the app a moment to read the clip, then do not leave our text on the clipboard.
        Thread.sleep(400)
        val restore = CountDownLatch(1)
        var how = "unchanged"
        Handler(Looper.getMainLooper()).post {
            how = runCatching {
                val prev = previous
                if (prev != null) { cm.setPrimaryClip(prev); "restored" }
                else if (Build.VERSION.SDK_INT >= 28) { cm.clearPrimaryClip(); "cleared" }
                else "unchanged"
            }.getOrDefault("unchanged")
            restore.countDown()
        }
        restore.await(2, TimeUnit.SECONDS)
        return tr("clipboard paste（剪贴板：${when (how) { "restored" -> "已恢复原内容"; "cleared" -> "已清空（系统不让后台读取原内容，无法恢复）"; else -> "未能清理" }}）",
            "clipboard paste (clipboard: ${when (how) { "restored" -> "original content restored"; "cleared" -> "cleared (the system does not let the background read the original, so it cannot be restored)"; else -> "could not be cleaned" }})")
    }
}
