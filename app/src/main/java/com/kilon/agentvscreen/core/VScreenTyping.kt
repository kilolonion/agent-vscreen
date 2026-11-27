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
    /** 1.3.0-beta14: the focused field, for the full screen page's box that mirrors it. [key] tells fields apart. */
    class FieldNow(val text: String, val password: Boolean, val key: String)

    fun fieldNow(displayId: Int): FieldNow? = runCatching {
        focusedEditable(displayId)?.let { n ->
            val b = Rect().also { n.getBoundsInScreen(it) }
            FieldNow(if (n.isPassword) "" else fieldText(n), n.isPassword, "${n.viewIdResourceName}@${b.left},${b.top},${b.right},${b.bottom}")
        }
    }.getOrNull()

    /**
     * 1.3.0-beta14: make the focused field read [value] (the user's box mirrors it: typing, deleting, the keyboard's
     * own keys). Accessibility writes the whole value; without it, the difference from [was] goes as delete keys and
     * then the added text (keys, or paste for non-ASCII). Replaces the old one-shot "append what was sent", which read
     * the field's hint as its content ("搜索设置项" + "蓝牙") and could not delete.
     */
    fun setField(displayId: Int, value: String, was: String) {
        val svc = VScreenAccessibilityService.getInstance()
        val node = if (svc != null) runCatching { focusedEditable(displayId) }.getOrNull() else null
        Control.admitInput()
        if (node != null && !node.isPassword && svc!!.setNodeText(node, value)) return
        val common = was.commonPrefixWith(value).length
        repeat(was.length - common) { VScreenShell.key(displayId, "del") }
        val add = value.substring(common)
        if (add.isEmpty()) return
        if (add.all { it.code in 32..126 }) { val r = VScreenShell.typeKeys(displayId, add); if (!r.ok) throw ActionError(r.code, r.message) }
        else pasteText(displayId, add)
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
        // 2.0.0-beta6 (6.10 #3): never call global `findFocus(FOCUS_INPUT)` — on Android it can escape to display 0
        // and return the user's main-screen input box. Search only inside the display-scoped, own-package-filtered tree.
        if (displayId <= android.view.Display.DEFAULT_DISPLAY) return null
        val snap = A11yTree.snapshot(displayId)
        val edits = snap.editables()
        edits.firstOrNull { it.focused }?.let { return it.info }
        snap.nodes.firstOrNull { it.editable && it.focused }?.let { return it.info }
        // 1.4.0-beta13: [at] is a frame point; the bounds are input space (see VScreenDisplay.toFrame).
        val d = BackgroundOperation.displayById(displayId)
        if (at != null) return edits.filter { (d?.toFrame(it.box) ?: it.box).contains(at.first, at.second) }.minByOrNull { it.box.width().toLong() * it.box.height() }?.info
        return null
    }

    /** What the field holds: never its hint (some apps report the hint as text without saying so). */
    private fun fieldText(n: AccessibilityNodeInfo): String {
        if (n.isShowingHintText) return ""
        val t = n.text?.toString().orEmpty()
        val hint = n.hintText?.toString()
        return if (!hint.isNullOrEmpty() && t == hint) "" else t
    }

    /**
     * v49: type [text] into the field that has input focus and say what happened, without a verdict: the method used
     * and what the field reads afterwards (null when no field could be read; a password field gives its length only).
     * The typed text is never logged.
     */
    /** What typing did: the method, the field it went to (bounds, id) and what that field reads afterwards. */
    /** [checked] false = accessibility could not look at the screen, so [field] null says nothing about focus. */
    class TypeFact(
        val method: String,
        val field: String?,
        val bounds: Rect?,
        val fieldId: String?,
        val checked: Boolean = true,
        val password: Boolean = false,
        /** 1.3.0-beta16: keys or paste went out, but the field reads exactly what it read before (a web page or a
         *  self-drawn box whose accessibility text is its placeholder): its content cannot be read back. */
        val unreadable: Boolean = false,
        /** True when `ACTION_SET_TEXT` wrote the field via accessibility without injecting key events. */
        val viaA11y: Boolean = false,
        /** True when `a11yOnly = true` was requested but no writable node accepted `ACTION_SET_TEXT`. */
        val a11yFailed: Boolean = false,
    )

    /** True when the accessibility service is connected and can see a focused/tapped editable node on [displayId]. */
    fun hasWritableNode(displayId: Int, tappedAt: Pair<Int, Int>? = null): Boolean {
        val svc = VScreenAccessibilityService.getInstance() ?: return false
        if (BackgroundOperation.nodeRoots(displayId).isEmpty()) return false
        return runCatching { focusedEditable(displayId, tappedAt) != null }.getOrDefault(false)
    }

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
    fun typeFact(
        displayId: Int,
        text: String,
        append: Boolean,
        tappedAt: Pair<Int, Int>? = null,
        focusBefore: String? = null,
        a11yOnly: Boolean = false,
    ): TypeFact {
        // 1.4.2-beta6: asked for every piece typed (slow and paced typing call this per character / piece), so typing
        // stops the moment the user takes over or stops, instead of going on into whatever box they are using.
        Control.admitInput()
        val svc = VScreenAccessibilityService.getInstance()
        val roots = if (svc != null) BackgroundOperation.nodeRoots(displayId) else emptyList()
        val a11y = svc != null && roots.isNotEmpty()
        val d = BackgroundOperation.displayById(displayId)
        // Focus can land a moment after the tap (Flutter): look twice.
        val node = if (a11y) focusedEditable(displayId, tappedAt) ?: run { Thread.sleep(300); focusedEditable(displayId, tappedAt) } else null
        val nbRaw = node?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
        val nb = nbRaw?.let { d?.toFrame(it) ?: it }
        val nid = node?.viewIdResourceName?.substringAfterLast('/')
        // v0.5: the field with focus is not where the agent just tapped (a page's own box that accessibility cannot
        // see, while the address bar keeps focus): typing would land in the wrong place. Do not type; say where focus is.
        if (tappedAt != null && nbRaw != null && nb != null && focusBefore != null && focusBefore == "${node.viewIdResourceName}@${nbRaw.left},${nbRaw.top},${nbRaw.right},${nbRaw.bottom}") {
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
        val rawBefore = node?.text?.toString()
        var wrote = false
        // Reading the box took time (up to 300 ms more): ask again right before writing into it.
        Control.admitInput()
        val method = when {
            node != null && svc!!.setNodeText(node, if (append) before + text else text) -> { wrote = true; tr("无障碍写入", "accessibility write") }
            a11yOnly -> return TypeFact(
                method = tr("无障碍写入", "accessibility write"),
                field = null,
                bounds = nb,
                fieldId = nid,
                checked = a11y,
                viaA11y = false,
                a11yFailed = true,
            )
            text.all { it.code in 32..126 } -> {
                val r = VScreenShell.typeKeys(displayId, text)
                if (!r.ok) throw ActionError(r.code, r.message)
                tr("按键注入", "key injection")
            }
            // 2.0.0-beta12 (test F3: `type 蓝牙` with no box focused wrote the user's clipboard, which the background
            // cannot read back): when the screen can be read and no box has focus, nothing would take the paste; the
            // user's clipboard is not touched for it.
            a11y && node == null -> throw ActionError("TYPE_FAILED", tr(
                "没有输入：画面上没有获得焦点的输入框（这段字要走剪贴板，没有框接收时不动用户的剪贴板）。先点输入框，等【现在】里标“有焦点”再 type",
                "Nothing typed: no input box has focus (this text would go through the clipboard, and the user's clipboard is not touched when nothing would take it). Tap the input box first and type once [Now] marks it \"focused\""))
            else -> { pasteText(displayId, text); tr("剪贴板粘贴", "clipboard paste") }
        }
        if (!a11y) return TypeFact(method, null, null, null, checked = false, viaA11y = wrote)
        // 1.2.0-beta2: wait until the field shows the text (at most 2 s) instead of reading it once after a fixed
        // 250 ms: a web page reports its box's new value late, and the single early read called a typed text missing.
        var after = focusedEditable(displayId, tappedAt) ?: node
        val until = android.os.SystemClock.elapsedRealtime() + 2_000L
        while (true) {
            Thread.sleep(150)
            after = focusedEditable(displayId, tappedAt) ?: node
            com.kilon.agentvscreen.host.quiet("VScreenTyping:137") { after?.refresh() }
            val seen = after?.let { if (it.isPassword) fieldText(it).length != before.length || text.isEmpty() else fieldText(it).contains(text) } ?: true
            if (seen || android.os.SystemClock.elapsedRealtime() >= until || com.kilon.agentvscreen.cmd.Call.current()?.cancelled == true) break
        }
        // A password box gives its length only: what is in it never leaves the phone.
        val nowRaw = after?.let { if (it.isPassword) "•".repeat(fieldText(it).length) else fieldText(it) }
        val ab = after?.let { n -> Rect().also { n.getBoundsInScreen(it) } }?.let { d?.toFrame(it) ?: it }
        val unreadable = !wrote && after != null && !after.isPassword && nowRaw?.contains(text) != true && after.text?.toString() == rawBefore
        val now = nowRaw?.let { if (after?.isPassword == true) it else com.kilon.agentvscreen.cmd.ScreenText.clean(it, maxLen = 120) }
        return TypeFact(
            method = method,
            field = now,
            bounds = ab,
            fieldId = after?.viewIdResourceName?.substringAfterLast('/')?.let { com.kilon.agentvscreen.cmd.ScreenText.clean(it, maxLen = 64) },
            password = after?.isPassword == true,
            unreadable = unreadable,
            viaA11y = wrote,
        )
    }

    private fun restoreClip(cm: ClipboardManager, prev: ClipData?) {
        Handler(Looper.getMainLooper()).post { runCatching { if (prev != null) cm.setPrimaryClip(prev) else cm.clearPrimaryClip() } }
    }

    /** Put [text] on the clipboard and press PASTE on [displayId]'s focused field. */
    @Throws(ActionError::class)
    fun pasteText(displayId: Int, text: String): String {
        Control.admitInput()
        // Writing the clipboard is allowed from the background; reading it usually is not
        // (Android 10+), so the user's previous clip can be put back only when we could read it.
        val cm = (BackgroundOperation.appContextOrNull() ?: error("not initialized")).getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var previous: ClipData? = null
        val latch = CountDownLatch(1)
        var clipOk = false
        Handler(Looper.getMainLooper()).post {
            previous = runCatching { cm.primaryClip }.getOrNull()
            val clip = ClipData.newPlainText("agent-vscreen", text).apply {
                description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            clipOk = runCatching { cm.setPrimaryClip(clip) }.isSuccess
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        if (!clipOk) throw ActionError("TYPE_FAILED", tr("无法写入剪贴板", "Cannot write the clipboard"))
        // 1.4.0-beta14 (test K6: the paste key did nothing in a web page, while ctrl+v there worked): paste with
        // ctrl+v, the shortcut every text box handles (native boxes too); the paste key only when that cannot be sent.
        // 1.4.2-beta6: the clipboard is put back whatever happens to the paste (it is refused once the user takes over).
        val r = try { VScreenShell.key(displayId, "ctrl+v").takeIf { it.ok } ?: VScreenShell.key(displayId, "paste") }
            catch (e: ActionError) { restoreClip(cm, previous); throw e }
        if (!r.ok) { restoreClip(cm, previous); throw ActionError(r.code, r.message) }
        // Give the app a moment to read the clip, then do not leave our text on the clipboard.
        Thread.sleep(400)
        val restore = CountDownLatch(1)
        var how = "unchanged"
        Handler(Looper.getMainLooper()).post {
            how = runCatching {
                val prev = previous
                if (prev != null) { cm.setPrimaryClip(prev); "restored" }
                else { cm.clearPrimaryClip(); "cleared" }
            }.getOrDefault("unchanged")
            restore.countDown()
        }
        restore.await(2, TimeUnit.SECONDS)
        return tr("clipboard paste（剪贴板：${when (how) { "restored" -> "已恢复原内容"; "cleared" -> "已清空（系统不让后台读取原内容，无法恢复）"; else -> "未能清理" }}）",
            "clipboard paste (clipboard: ${when (how) { "restored" -> "original content restored"; "cleared" -> "cleared (the system does not let the background read the original, so it cannot be restored)"; else -> "could not be cleaned" }})")
    }
}
