package com.kilon.avstorture

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

private const val ATK_TAG = "AVS_ATK"

private fun emitAtk(ev: String, data: JSONObject = JSONObject()) {
    Log.i(ATK_TAG, JSONObject().put("t", System.currentTimeMillis()).put("ev", ev).put("data", data).toString())
}

/**
 * 2.0.0-beta6 (6.10 #3): test Activity on display 0 with a focused EditText, used by `confirm_attack.py`
 * to prove that `act "type …"` on a virtual display never leaks into a focused main-screen input box.
 */
class MainInputActivity : Activity() {
    private lateinit var edit: EditText

    private val queryRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_QUERY_MAIN_INPUT) {
                emitAtk(
                    "main_input_state",
                    JSONObject()
                        .put("text", edit.text?.toString().orEmpty())
                        .put("focused", edit.isFocused)
                        .put("displayId", runCatching { display?.displayId ?: -1 }.getOrDefault(-1)),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dispId = runCatching { display?.displayId ?: -1 }.getOrDefault(-1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Main Screen Input Target (display=$dispId)"
            textSize = 18f
            setTextColor(Color.BLACK)
        }
        edit = EditText(this).apply {
            hint = "main_screen_input"
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            isFocusable = true
            isFocusableInTouchMode = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    emitAtk("main_input_changed", JSONObject().put("text", s?.toString().orEmpty()))
                }
            })
        }
        root.addView(title)
        root.addView(edit)
        setContentView(root)
        val f = IntentFilter(ACTION_QUERY_MAIN_INPUT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(queryRx, f, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(queryRx, f)
        }
        edit.post {
            edit.requestFocus()
            emitAtk("main_input_ready", JSONObject().put("displayId", dispId).put("focused", edit.isFocused))
        }
    }

    override fun onResume() {
        super.onResume()
        edit.requestFocus()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(queryRx) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_QUERY_MAIN_INPUT = "com.kilon.avstorture.QUERY_MAIN_INPUT"
    }
}

/**
 * 2.0.0-beta6 (6.9): external test AccessibilityService used by `confirm_attack.py` to attempt
 * `ACTION_CLICK` and `ACTION_LONG_CLICK` directly on Agent-VScreen's StrongConfirmActivity confirm button.
 */
class AttackA11yService : AccessibilityService() {
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_ATTACK_CLICK) return
            val targetPkg = intent.getStringExtra("pkg") ?: "com.kilon.agentvscreen"
            val targetDesc = intent.getStringExtra("desc") ?: "strong_confirm_button"
            var node: AccessibilityNodeInfo? = null
            val seen = ArrayList<String>()
            for (attempt in 0 until 10) {
                seen.clear()
                node = findTarget(targetPkg, targetDesc, seen)
                if (node != null) break
                runCatching { Thread.sleep(200) }
            }
            if (node == null) {
                emitAtk("a11y_click_result", JSONObject().put("found", false).put("seen", seen.take(20).joinToString("; ")))
                return
            }
            val b = Rect()
            node.getBoundsInScreen(b)
            val clickOk = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            val longOk = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)
            emitAtk(
                "a11y_click_result",
                JSONObject()
                    .put("found", true)
                    .put("bounds", "${b.left},${b.top},${b.right},${b.bottom}")
                    .put("clickOk", clickOk)
                    .put("longClickOk", longOk),
            )
        }
    }

    private fun findTarget(pkg: String, desc: String, seen: MutableList<String>): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        runCatching {
            val all = windowsOnAllDisplays
            for (idx in 0 until all.size()) {
                val disp = all.keyAt(idx)
                val ws = all.valueAt(idx) ?: continue
                for (w in ws) {
                    val r = w.root
                    seen += "d$disp:${r?.packageName ?: "null"}"
                    if (disp == 0 && r != null) roots += r
                }
            }
        }
        runCatching {
            for (w in windows) {
                w.root?.let { r ->
                    seen += "w:${r.packageName}"
                    roots += r
                }
            }
        }
        runCatching {
            rootInActiveWindow?.let { r ->
                seen += "act:${r.packageName}"
                roots += r
            }
        }
        for (r in roots) {
            findRec(r, pkg, desc, seen)?.let { return it }
        }
        return null
    }

    private fun findRec(n: AccessibilityNodeInfo?, pkg: String, desc: String, seen: MutableList<String>): AccessibilityNodeInfo? {
        if (n == null) return null
        val p = n.packageName?.toString()
        val d = n.contentDescription?.toString()
        val vid = n.viewIdResourceName
        val txt = n.text?.toString()
        if (p == pkg) {
            if (d == desc || vid?.endsWith(":id/$desc") == true) return n
            if (txt != null && (txt.contains("Hold 3s") || txt.contains("按住确认") || txt == "Confirm" || txt == "确认")) return n
            if (!d.isNullOrBlank() || !txt.isNullOrBlank()) {
                if (seen.size < 25) seen += "n(d=${d ?: ""},t=${txt ?: ""},c=${n.className})"
            }
        }
        for (i in 0 until n.childCount) {
            findRec(runCatching { n.getChild(i) }.getOrNull(), pkg, desc, seen)?.let { return it }
        }
        return null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val f = IntentFilter(ACTION_ATTACK_CLICK)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(rx, f)
        }
        emitAtk("attack_a11y_connected", JSONObject().put("sdk", Build.VERSION.SDK_INT))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching { unregisterReceiver(rx) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_ATTACK_CLICK = "com.kilon.avstorture.ATTACK_A11Y_CLICK"
    }
}
