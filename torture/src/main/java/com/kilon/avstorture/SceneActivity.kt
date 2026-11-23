package com.kilon.avstorture

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

class SceneActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: TouchLoggingFrameLayout
    private var currentScene: String = PresetReceiver.DEFAULT_SCENE
    private var currentSeed: Int = PresetReceiver.DEFAULT_SEED
    private var backOverride: (() -> Boolean)? = null
    private val cleanupTasks = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        root = TouchLoggingFrameLayout(this) { action, x, y ->
            AvstLog.touch(currentScene, action, x, y)
        }
        root.setBackgroundColor(Color.WHITE)
        setContentView(root)
        applyIntent(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && backOverride?.invoke() == true) {
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && backOverride != null) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    override fun onDestroy() {
        clearScene()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (backOverride?.invoke() == true) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun clearScene() {
        handler.removeCallbacksAndMessages(null)
        cleanupTasks.forEach { runCatching { it() } }
        cleanupTasks.clear()
        backOverride = null
        root.removeAllViews()
    }

    private fun applyIntent(intent: Intent?) {
        clearScene()
        val prefs = getSharedPreferences(PresetReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        val rawScene = intent?.getStringExtra(PresetReceiver.EXTRA_SCENE)
            ?: prefs.getString(PresetReceiver.EXTRA_SCENE, PresetReceiver.DEFAULT_SCENE)
            ?: PresetReceiver.DEFAULT_SCENE
        currentScene = rawScene.uppercase()
        currentSeed = if (intent?.hasExtra(PresetReceiver.EXTRA_SEED) == true) {
            intent.getIntExtra(PresetReceiver.EXTRA_SEED, PresetReceiver.DEFAULT_SEED)
        } else {
            prefs.getInt(PresetReceiver.EXTRA_SEED, PresetReceiver.DEFAULT_SEED)
        }
        if (currentScene != "S04" && currentScene != "S11") {
            AvstLog.emit(
                scene = currentScene,
                ev = "enter",
                ok = true,
                data = JSONObject().put("seed", currentSeed),
            )
        }
        val keepCycling = if (intent?.hasExtra(PresetReceiver.EXTRA_KEEP_CYCLING) == true) {
            intent.getBooleanExtra(PresetReceiver.EXTRA_KEEP_CYCLING, false)
        } else {
            prefs.getBoolean(PresetReceiver.EXTRA_KEEP_CYCLING, false)
        }
        val rng = SceneRng(currentSeed)
        when (currentScene) {
            "S01" -> buildS01List()
            "S02" -> buildS02Popup(rng)
            "S03" -> buildS03Slow(rng)
            "S04" -> buildS04Drag(rng)
            "S05" -> buildS05LongPress()
            "S06" -> buildS06Canvas(rng)
            "S07" -> buildS07Toast()
            "S08" -> buildS08Keyboard()
            "S09" -> buildS09Timing(rng, keepCycling)
            "S10" -> buildS10Slider(rng)
            "S11" -> buildS11Charge(rng)
            "S12" -> buildS12Noise(rng)
            "S13" -> buildS13Find()
            else -> buildS01List()
        }
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    private fun label(text: String, sp: Float = BASE_TEXT_SP, bold: Boolean = false, color: Int = Color.rgb(20, 22, 26)): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun pillButton(text: String, bgColor: Int = Color.rgb(37, 99, 235)): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f).toFloat()
                setColor(bgColor)
            }
            setPadding(dp(24f), dp(14f), dp(24f), dp(14f))
        }

    // ── S01: 60-item list + detail page with 返回 ──────────────────────────────
    private fun buildS01List() {
        val items = List(S01_ITEM_COUNT) { "条目 ${it + 1}" }
        val listView = ListView(this).apply {
            dividerHeight = dp(1f)
            adapter = SimpleTextAdapter(this@SceneActivity, items)
        }
        val detailBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(64f), dp(24f), dp(24f))
            visibility = View.GONE
            setBackgroundColor(Color.WHITE)
        }
        val detailTitle = label("", sp = 22f, bold = true)
        val detailBody = label("", sp = 18f).apply { setPadding(0, dp(16f), 0, dp(32f)) }
        var openedIndex = 0
        val backBtn = pillButton("返回").apply {
            setOnClickListener {
                detailBox.visibility = View.GONE
                listView.visibility = View.VISIBLE
                AvstLog.emit("S01", "goal", ok = true, data = JSONObject().put("item", openedIndex))
            }
        }
        detailBox.addView(detailTitle)
        detailBox.addView(detailBody)
        detailBox.addView(backBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        listView.setOnItemClickListener { _, _, position, _ ->
            openedIndex = position + 1
            detailTitle.text = "条目 $openedIndex 详情"
            detailBody.text = "当前内容：条目 $openedIndex"
            listView.visibility = View.GONE
            detailBox.visibility = View.VISIBLE
            AvstLog.emit("S01", "detail", ok = true, data = JSONObject().put("item", openedIndex))
        }
        root.addView(listView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(detailBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ── S02: delayed popup (0.5–3s) with textless × icon + 确定 button ─────────
    private fun buildS02Popup(rng: SceneRng) {
        val delayMs = rng.nextRange(S02_MIN_DELAY_MS, S02_MAX_DELAY_MS).toLong()
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        val page = FrameLayout(this).apply { setBackgroundColor(Color.rgb(250, 250, 252)) }
        val header = label("活动确认页", sp = 22f, bold = true).apply { gravity = Gravity.CENTER }
        page.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (h * 0.08f).roundToInt()
        })
        val status = label("请点击下方按钮完成确认", sp = 18f).apply { gravity = Gravity.CENTER }
        page.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (h * 0.62f).roundToInt()
        })
        var goalDone = false
        val confirmBtn = pillButton("确定").apply {
            setOnClickListener {
                status.text = "已确定"
                if (!goalDone) {
                    goalDone = true
                    AvstLog.emit("S02", "goal", ok = true)
                }
            }
        }
        page.addView(confirmBtn, FrameLayout.LayoutParams((w * 0.44f).roundToInt(), dp(56f)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (h * 0.70f).roundToInt()
        })

        // Dimmed scrim + popup dialog (15%..90% area, detached from top/bottom, outside darkened >15%)
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(165, 0, 0, 0))
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        val dlgLeft = (w * 0.06f).roundToInt()
        val dlgTop = (h * 0.15f).roundToInt()
        val dlgWidth = (w * 0.88f).roundToInt()
        val dlgHeight = (h * 0.40f).roundToInt()

        val dialogCard = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16f).toFloat()
                setColor(Color.WHITE)
            }
            isClickable = true
        }
        val dlgBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        dlgBody.addView(label("限时优惠", sp = 26f, bold = true))
        dlgBody.addView(label("新人专享礼券包", sp = 18f).apply { setPadding(0, dp(16f), 0, 0) })
        dialogCard.addView(dlgBody, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top-right close icon × without any text or accessibility label; covers (980,420) on 1080x2400.
        val crossSize = (w * 0.11f).roundToInt().coerceAtLeast(dp(44f))
        val closeView = CloseCrossView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener {
                scrim.visibility = View.GONE
                AvstLog.emit("S02", "popup_closed", ok = true)
            }
        }
        dialogCard.addView(closeView, FrameLayout.LayoutParams(crossSize, crossSize).apply {
            gravity = Gravity.TOP or Gravity.END
        })
        scrim.addView(dialogCard, FrameLayout.LayoutParams(dlgWidth, dlgHeight).apply {
            leftMargin = dlgLeft
            topMargin = dlgTop
        })

        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        handler.postDelayed({
            scrim.visibility = View.VISIBLE
            AvstLog.emit("S02", "popup_shown", ok = true, data = JSONObject().put("delay_ms", delayMs))
        }, delayMs)
    }

    // ── S03: slow loading 2–6s -> 加载完成 ─────────────────────────────────────
    private fun buildS03Slow(rng: SceneRng) {
        val delayMs = rng.nextRange(S03_MIN_DELAY_MS, S03_MAX_DELAY_MS).toLong()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
        }
        val status = label("准备就绪", sp = 22f, bold = true).apply { setPadding(0, 0, 0, dp(24f)) }
        val spinner = ProgressBar(this).apply { visibility = View.GONE }
        val loadBtn = pillButton("加载")
        loadBtn.setOnClickListener {
            loadBtn.visibility = View.GONE
            status.text = "正在处理"
            spinner.visibility = View.VISIBLE
            handler.postDelayed({
                spinner.visibility = View.GONE
                status.text = "加载完成"
                AvstLog.emit("S03", "goal", ok = true, data = JSONObject().put("delay_ms", delayMs))
            }, delayMs)
        }
        box.addView(status)
        box.addView(spinner, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        box.addView(loadBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ── S04: 3 cards, 4 slots, 60dp snap, 250ms bounce back, no text hint ─────
    private fun buildS04Drag(rng: SceneRng) {
        val board = DragBoardView(this, currentSeed, rng)
        cleanupTasks += { board.stopAnimators() }
        val header = label("拖动下方卡片放入槽位", sp = 18f, bold = true).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(board, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28f)
        })
    }

    // ── S05: hold >= 600ms pops menu; early release does nothing ───────────────
    private fun buildS05LongPress() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        val menuBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f).toFloat()
                setColor(Color.rgb(241, 245, 249))
                setStroke(dp(2f), Color.rgb(100, 116, 139))
            }
            setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
        }
        menuBox.addView(label("菜单已打开", sp = 20f, bold = true))
        menuBox.addView(label("复制 · 收藏 · 分享", sp = 18f).apply { setPadding(0, dp(8f), 0, 0) })
        layer.addView(menuBox, FrameLayout.LayoutParams((w * 0.46f).roundToInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (h * 0.34f).roundToInt()
        })

        var menuShown = false
        val showRunnable = Runnable {
            if (!menuShown) {
                menuShown = true
                menuBox.visibility = View.VISIBLE
                AvstLog.emit("S05", "menu_shown", ok = true)
                AvstLog.emit("S05", "goal", ok = true)
            }
        }

        val holdTarget = pillButton("长按此处").apply {
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        handler.removeCallbacks(showRunnable)
                        handler.postDelayed(showRunnable, S05_HOLD_MS)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        handler.removeCallbacks(showRunnable)
                        true
                    }
                    else -> true
                }
            }
        }
        val btnW = (w * 0.44f).roundToInt()
        val btnH = dp(64f)
        layer.addView(holdTarget, FrameLayout.LayoutParams(btnW, btnH).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (h * 0.50f).roundToInt() - btnH / 2
        })
        root.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ── S06: Canvas with 5 moving colored shapes, zero text, tap red circle ────
    private fun buildS06Canvas(rng: SceneRng) {
        val view = MovingShapesView(this, currentSeed, rng)
        cleanupTasks += { view.stop() }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ── S07: button shows toast "已保存" for 2s in bottom 35% of screen ────────
    private fun buildS07Toast() {
        val dm = resources.displayMetrics
        val h = dm.heightPixels
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val toastView = label("已保存", sp = 18f, bold = true, color = Color.WHITE).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22f).toFloat()
                setColor(Color.argb(228, 38, 38, 42))
            }
            setPadding(dp(28f), dp(12f), dp(28f), dp(12f))
        }
        val hideRunnable = Runnable { toastView.visibility = View.GONE }
        val saveBtn = pillButton("保存").apply {
            setOnClickListener {
                handler.removeCallbacks(hideRunnable)
                toastView.visibility = View.VISIBLE
                AvstLog.emit("S07", "toast", ok = true, data = JSONObject().put("text", "已保存"))
                AvstLog.emit("S07", "goal", ok = true)
                handler.postDelayed(hideRunnable, S07_TOAST_MS)
            }
        }
        layer.addView(saveBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (h * 0.45f).roundToInt()
        })
        layer.addView(toastView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (h * 0.81f).roundToInt()
        })
        root.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ── S08: bottom input box; keyboard pops up and covers "提交" ──────────────
    private fun buildS08Keyboard() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        val title = label("反馈填写", sp = 22f, bold = true).apply { gravity = Gravity.CENTER }
        layer.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (h * 0.08f).roundToInt()
        })
        val status = label("输入内容后点击底部提交", sp = 18f).apply { gravity = Gravity.CENTER }
        layer.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (h * 0.22f).roundToInt()
        })

        val submitBtn = pillButton("提交")
        val editText = EditText(this).apply {
            hint = "请输入内容"
            showSoftInputOnFocus = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8f).toFloat()
                setColor(Color.rgb(243, 244, 246))
                setStroke(dp(1.5f), Color.rgb(156, 163, 175))
            }
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }

        // Occlusion panel representing the raised soft keyboard area covering the bottom 20% (including 提交).
        val imeOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(212, 216, 224))
            isClickable = true
            isFocusable = false
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        imeOverlay.addView(label("输入面板已展开", sp = 16f, color = Color.rgb(75, 85, 99)).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })

        fun setKeyboardShown(shown: Boolean) {
            imeOverlay.visibility = if (shown) View.VISIBLE else View.GONE
            submitBtn.importantForAccessibility = if (shown) {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            if (!shown) {
                editText.clearFocus()
                val imm = getSystemService(InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(editText.windowToken, 0)
            }
        }

        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                setKeyboardShown(true)
            }
        }
        editText.setOnClickListener { setKeyboardShown(true) }

        backOverride = {
            if (imeOverlay.visibility == View.VISIBLE) {
                setKeyboardShown(false)
                true
            } else {
                false
            }
        }

        submitBtn.setOnClickListener {
            val content = editText.text?.toString()?.trim().orEmpty()
            if (content.isNotEmpty()) {
                status.text = "提交成功"
                AvstLog.emit("S08", "goal", ok = true, data = JSONObject().put("len", content.length))
            } else {
                status.text = "请先输入文字"
            }
        }

        layer.addView(editText, FrameLayout.LayoutParams((w * 0.82f).roundToInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (h * 0.70f).roundToInt()
        })
        layer.addView(submitBtn, FrameLayout.LayoutParams((w * 0.44f).roundToInt(), dp(56f)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (h * 0.88f).roundToInt()
        })
        layer.addView(imeOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (h * 0.21f).roundToInt()).apply {
            gravity = Gravity.BOTTOM
        })
        root.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ── S09: every 3s shows "点我" at random seeded pos for 800ms ─────────────
    private fun buildS09Timing(rng: SceneRng, keepCycling: Boolean = false) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val status = label("等待目标出现", sp = 18f).apply { gravity = Gravity.CENTER }
        layer.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(32f)
        })

        val btnW = dp(148f)
        val btnH = dp(68f)
        var round = 0
        var activeRound = -1
        var goalEmitted = false
        val targetBtn = pillButton("点我", bgColor = Color.rgb(220, 38, 38)).apply {
            visibility = View.GONE
        }
        val lp = FrameLayout.LayoutParams(btnW, btnH)
        layer.addView(targetBtn, lp)

        val missRunnable = Runnable {
            if (targetBtn.visibility == View.VISIBLE && activeRound == round) {
                targetBtn.visibility = View.GONE
                AvstLog.emit("S09", "target_missed", ok = false, data = JSONObject().put("round", round))
            }
        }

        targetBtn.setOnClickListener {
            if (targetBtn.visibility == View.VISIBLE) {
                handler.removeCallbacks(missRunnable)
                targetBtn.visibility = View.GONE
                status.text = "命中 #$round"
                AvstLog.emit("S09", "target_hit", ok = true, data = JSONObject().put("round", round))
                if (!goalEmitted) {
                    goalEmitted = true
                    AvstLog.emit("S09", "goal", ok = true, data = JSONObject().put("round", round))
                }
            }
        }

        val cycleRunnable = object : Runnable {
            override fun run() {
                if (goalEmitted && !keepCycling) return
                round++
                activeRound = round
                val minX = dp(24f)
                val maxX = (w - btnW - dp(24f)).coerceAtLeast(minX)
                val minY = (h * 0.16f).roundToInt()
                val maxY = (h * 0.78f - btnH).roundToInt().coerceAtLeast(minY)
                val x = rng.nextRange(minX, maxX)
                val y = rng.nextRange(minY, maxY)
                lp.leftMargin = x
                lp.topMargin = y
                targetBtn.layoutParams = lp
                targetBtn.visibility = View.VISIBLE
                AvstLog.emit(
                    "S09",
                    "target_shown",
                    ok = true,
                    data = JSONObject().put("round", round).put("x", x + btnW / 2).put("y", y + btnH / 2),
                )
                handler.postDelayed(missRunnable, S09_STAY_MS)
                if (!goalEmitted || keepCycling) handler.postDelayed(this, S09_PERIOD_MS)
            }
        }
        handler.postDelayed(cycleRunnable, S09_INITIAL_DELAY_MS)
        root.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ── S10: slider 0–100, value text above, goal at 70±2 ─────────────────────
    private fun buildS10Slider(rng: SceneRng) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val initValue = rng.nextRange(5, 25)
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        val hint = label("拖动滑条停在 70（允许 68–72）", sp = 18f).apply { gravity = Gravity.CENTER }
        layer.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (h * 0.18f).roundToInt()
        })

        // Value sits cleanly inside [400,600,700,700] on 1080x2400 (center y = 650/2400 * h).
        val valueLabel = label(initValue.toString(), sp = 30f, bold = true).apply { gravity = Gravity.CENTER }
        layer.addView(valueLabel, FrameLayout.LayoutParams((w * 0.28f).roundToInt(), (h * 0.05f).roundToInt()).apply {
            leftMargin = (w * 0.36f).roundToInt()
            topMargin = (h * (600f / 2400f)).roundToInt()
        })

        var goalDone = false
        val slider = SliderBarView(this, initValue, onMove = { v ->
            valueLabel.text = v.toString()
        }, onRelease = { v ->
            valueLabel.text = v.toString()
            val ok = v in (S10_TARGET - S10_TOL)..(S10_TARGET + S10_TOL)
            AvstLog.emit("S10", "value", ok = ok, data = JSONObject().put("value", v))
            if (ok && !goalDone) {
                goalDone = true
                AvstLog.emit("S10", "goal", ok = true, data = JSONObject().put("value", v))
            }
        })
        layer.addView(slider, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ── S11: hold to charge 0–100%, release at 90–100%, >100% -> 过载 ──────────
    private fun buildS11Charge(rng: SceneRng) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val fullMs = rng.nextRange(S11_MIN_FULL_MS, S11_MAX_FULL_MS).toLong()
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        val title = label("按住充能至 90%–100% 松手", sp = 20f, bold = true).apply { gravity = Gravity.CENTER }
        layer.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (h * 0.16f).roundToInt()
        })

        val barView = ChargeBarView(this)
        var s11EnterSent = false
        barView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (s11EnterSent || v.width <= 0 || v.height <= 0) return@addOnLayoutChangeListener
            s11EnterSent = true
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val bx = loc[0] + maxOf(v.height, (v.width * 0.12f).roundToInt())
            val by = loc[1] + v.height / 2
            AvstLog.emit(
                scene = "S11",
                ev = "enter",
                ok = true,
                data = JSONObject()
                    .put("seed", currentSeed)
                    .put("bar", org.json.JSONArray().put(loc[0]).put(loc[1]).put(loc[0] + v.width).put(loc[1] + v.height))
                    .put("bar_probe", org.json.JSONArray().put(bx).put(by)),
            )
        }
        layer.addView(barView, FrameLayout.LayoutParams((w * 0.76f).roundToInt(), dp(32f)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (h * (1300f / 2400f)).roundToInt()
        })

        // Percent readout inside [300,1400,800,1500] on 1080x2400.
        val pctLabel = label("0%", sp = 28f, bold = true).apply { gravity = Gravity.CENTER }
        layer.addView(pctLabel, FrameLayout.LayoutParams((w * 0.46f).roundToInt(), (h * (100f / 2400f)).roundToInt()).apply {
            leftMargin = (w * (300f / 1080f)).roundToInt()
            topMargin = (h * (1400f / 2400f)).roundToInt()
        })

        var holding = false
        var overloaded = false
        var done = false
        var downAt = 0L
        var percent = 0

        val tickRunnable = object : Runnable {
            override fun run() {
                if (!holding || overloaded || done) return
                val elapsed = SystemClock.uptimeMillis() - downAt
                percent = ((elapsed * 100L) / fullMs).toInt()
                if (percent > S11_MAX_OK) {
                    overloaded = true
                    holding = false
                    barView.setProgress(100, Color.rgb(220, 38, 38))
                    pctLabel.text = "过载"
                    pctLabel.setTextColor(Color.rgb(220, 38, 38))
                    AvstLog.emit("S11", "fail", ok = false, data = JSONObject().put("reason", "overload"))
                    return
                }
                barView.setProgress(percent, if (percent >= S11_MIN_OK) Color.rgb(22, 163, 74) else Color.rgb(37, 99, 235))
                pctLabel.text = "${percent}%"
                handler.postDelayed(this, 30L)
            }
        }

        val chargeBtn = pillButton("按住充能").apply {
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!overloaded && !done) {
                            v.isPressed = true
                            holding = true
                            percent = 0
                            downAt = SystemClock.uptimeMillis()
                            pctLabel.text = "0%"
                            barView.setProgress(0, Color.rgb(37, 99, 235))
                            handler.removeCallbacks(tickRunnable)
                            handler.post(tickRunnable)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        if (holding && !overloaded && !done) {
                            holding = false
                            handler.removeCallbacks(tickRunnable)
                            val elapsed = SystemClock.uptimeMillis() - downAt
                            percent = ((elapsed * 100L) / fullMs).toInt()
                            if (percent > S11_MAX_OK) {
                                overloaded = true
                                barView.setProgress(100, Color.rgb(220, 38, 38))
                                pctLabel.text = "过载"
                                pctLabel.setTextColor(Color.rgb(220, 38, 38))
                                AvstLog.emit("S11", "fail", ok = false, data = JSONObject().put("reason", "overload"))
                            } else if (percent in S11_MIN_OK..S11_MAX_OK) {
                                done = true
                                pctLabel.text = "${percent}%"
                                barView.setProgress(percent, Color.rgb(22, 163, 74))
                                AvstLog.emit("S11", "goal", ok = true)
                            } else {
                                percent = 0
                                pctLabel.text = "0%"
                                barView.setProgress(0, Color.rgb(37, 99, 235))
                            }
                        }
                        true
                    }
                    else -> true
                }
            }
        }
        val btnH = dp(68f)
        layer.addView(chargeBtn, FrameLayout.LayoutParams((w * 0.44f).roundToInt(), btnH).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (h * (1600f / 2400f)).roundToInt() - btnH / 2
        })
        root.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ── S12: top auto-switching carousel every 2s + "确定" button ─────────────
    private fun buildS12Noise(rng: SceneRng) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        val carousel = CarouselBannerView(this, rng).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cleanupTasks += { carousel.stop() }
        layer.addView(carousel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (h * 0.19f).roundToInt()).apply {
            topMargin = dp(24f)
        })

        val status = label("忽略顶部轮播点击确定", sp = 18f).apply { gravity = Gravity.CENTER }
        layer.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (h * 0.50f).roundToInt()
        })

        var goalDone = false
        val okBtn = pillButton("确定").apply {
            setOnClickListener {
                status.text = "已确定"
                if (!goalDone) {
                    goalDone = true
                    AvstLog.emit("S12", "goal", ok = true)
                }
            }
        }
        layer.addView(okBtn, FrameLayout.LayoutParams((w * 0.44f).roundToInt(), dp(56f)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (h * 0.62f).roundToInt()
        })
        root.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ── S13: 200-row list, target row is "目标 147" ────────────────────────────
    private fun buildS13Find() {
        val items = List(S13_ITEM_COUNT) { idx ->
            val n = idx + 1
            if (n == S13_TARGET_INDEX) "目标 $n" else "列表项 $n"
        }
        val listView = ListView(this).apply {
            dividerHeight = dp(1f)
            adapter = SimpleTextAdapter(this@SceneActivity, items)
        }
        val detailBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(Color.WHITE)
        }
        val detailTitle = label("", sp = 22f, bold = true).apply { setPadding(0, 0, 0, dp(24f)) }
        val backBtn = pillButton("返回").apply {
            setOnClickListener {
                detailBox.visibility = View.GONE
                listView.visibility = View.VISIBLE
            }
        }
        detailBox.addView(detailTitle)
        detailBox.addView(backBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        listView.setOnItemClickListener { _, _, position, _ ->
            val n = position + 1
            listView.visibility = View.GONE
            detailBox.visibility = View.VISIBLE
            if (n == S13_TARGET_INDEX) {
                detailTitle.text = "已打开 目标 $n"
                AvstLog.emit("S13", "goal", ok = true, data = JSONObject().put("index", n))
            } else {
                detailTitle.text = "当前：列表项 $n"
            }
        }
        root.addView(listView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(detailBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    companion object {
        const val BASE_TEXT_SP = 18f
        const val BUTTON_TEXT_SP = 18f
        const val S01_ITEM_COUNT = 60
        const val S02_MIN_DELAY_MS = 500
        const val S02_MAX_DELAY_MS = 3000
        const val S03_MIN_DELAY_MS = 2000
        const val S03_MAX_DELAY_MS = 6000
        const val S04_SNAP_DP = 60f
        const val S04_SNAP_BACK_MS = 250L
        const val S05_HOLD_MS = 600L
        const val S06_RADIUS_DP = 64f
        const val S06_ORBIT_DP = 28f
        const val S07_TOAST_MS = 2000L
        const val S09_INITIAL_DELAY_MS = 1200L
        const val S09_PERIOD_MS = 3000L
        const val S09_STAY_MS = 800L
        const val S10_TARGET = 70
        const val S10_TOL = 2
        const val S11_MIN_OK = 90
        const val S11_MAX_OK = 100
        const val S11_MIN_FULL_MS = 2800
        const val S11_MAX_FULL_MS = 4200
        const val S12_PERIOD_MS = 2000L
        const val S13_ITEM_COUNT = 200
        const val S13_TARGET_INDEX = 147
    }
}

private class TouchLoggingFrameLayout(
    context: Context,
    private val onTouchLog: (action: String, x: Int, y: Int) -> Unit,
) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> onTouchLog("down", ev.rawX.roundToInt(), ev.rawY.roundToInt())
            MotionEvent.ACTION_UP -> onTouchLog("up", ev.rawX.roundToInt(), ev.rawY.roundToInt())
        }
        return super.dispatchTouchEvent(ev)
    }
}

private class SimpleTextAdapter(
    private val context: Context,
    private val rows: List<String>,
) : BaseAdapter() {
    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val dm = context.resources.displayMetrics
        val padH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, dm).roundToInt()
        val padV = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, dm).roundToInt()
        val tv = (convertView as? TextView) ?: TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SceneActivity.BASE_TEXT_SP)
            setTextColor(Color.rgb(17, 24, 39))
            setPadding(padH, padV, padH, padV)
        }
        tv.text = rows[position]
        return tv
    }
}

private class CloseCrossView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 65, 81)
        strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3.5f, context.resources.displayMetrics)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = width * 0.30f
        canvas.drawLine(pad, pad, width - pad, height - pad, paint)
        canvas.drawLine(width - pad, pad, pad, height - pad, paint)
    }
}

private class DragBoardView(
    context: Context,
    private val seed: Int,
    private val rng: SceneRng,
) : View(context) {
    private data class Slot(val x: Float, val y: Float)
    private class Card(
        val index: Int,
        val homeX: Float,
        val homeY: Float,
        var x: Float,
        var y: Float,
        var snappedSlot: Int = -1,
        val borderColor: Int,
    )

    private val snapPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SceneActivity.S04_SNAP_DP, resources.displayMetrics)
    private val slots = ArrayList<Slot>()
    private val cards = ArrayList<Card>()
    private var activeCard: Card? = null
    private var grabDx = 0f
    private var grabDy = 0f
    private var goalEmitted = false
    private val animators = ArrayList<ValueAnimator>()

    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        setBackgroundColor(Color.rgb(245, 246, 250))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || slots.isNotEmpty()) return
        fun j(): Float = rng.nextRange(-14, 14).toFloat()
        slots += Slot(w * (220f / 1080f) + j(), h * (600f / 2400f) + j())
        slots += Slot(w * (540f / 1080f) + j(), h * (600f / 2400f) + j())
        slots += Slot(w * (860f / 1080f) + j(), h * (600f / 2400f) + j())
        slots += Slot(w * (540f / 1080f) + j(), h * (960f / 2400f) + j())

        val colors = listOf(Color.rgb(30, 64, 175), Color.rgb(180, 83, 9), Color.rgb(109, 40, 217))
        val xs = listOf(200f / 1080f, 540f / 1080f, 880f / 1080f)
        for (i in 0 until 3) {
            val hx = w * xs[i] + j()
            val hy = h * (1500f / 2400f) + j()
            cards += Card(i + 1, hx, hy, hx, hy, -1, colors[i])
        }
        val cardsArr = org.json.JSONArray()
        for (c in cards) cardsArr.put(org.json.JSONArray().put(c.homeX.roundToInt()).put(c.homeY.roundToInt()))
        val slotsArr = org.json.JSONArray()
        for (s in slots) slotsArr.put(org.json.JSONArray().put(s.x.roundToInt()).put(s.y.roundToInt()))
        AvstLog.emit(
            scene = "S04",
            ev = "enter",
            ok = true,
            data = JSONObject().put("seed", seed).put("cards", cardsArr).put("slots", slotsArr),
        )
    }

    private fun nearestFreeSlot(card: Card): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for ((idx, s) in slots.withIndex()) {
            val occupied = cards.any { it !== card && it.snappedSlot == idx }
            if (occupied) continue
            val d = hypot((card.x - s.x).toDouble(), (card.y - s.y).toDouble()).toFloat()
            if (d <= snapPx && d < bestDist) {
                bestDist = d
                best = idx
            }
        }
        return best
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val slotHalf = width * 0.085f
        val cardHalf = width * 0.072f
        val dragging = activeCard
        val hoverSlot = dragging?.let { nearestFreeSlot(it) } ?: -1

        for ((idx, s) in slots.withIndex()) {
            val occupied = cards.any { it.snappedSlot == idx }
            val active = occupied || hoverSlot == idx
            val r = RectF(s.x - slotHalf, s.y - slotHalf, s.x + slotHalf, s.y + slotHalf)
            slotPaint.style = Paint.Style.FILL
            slotPaint.color = if (active) Color.parseColor("#4CAF50") else Color.rgb(224, 228, 236)
            canvas.drawRoundRect(r, 20f, 20f, slotPaint)
            borderPaint.strokeWidth = 6f
            borderPaint.color = if (active) Color.rgb(27, 94, 32) else Color.rgb(148, 163, 184)
            canvas.drawRoundRect(r, 20f, 20f, borderPaint)
        }

        for (c in cards) {
            val r = RectF(c.x - cardHalf, c.y - cardHalf, c.x + cardHalf, c.y + cardHalf)
            fillPaint.color = Color.WHITE
            canvas.drawRoundRect(r, 18f, 18f, fillPaint)
            borderPaint.strokeWidth = 14f
            borderPaint.color = c.borderColor
            canvas.drawRoundRect(r, 18f, 18f, borderPaint)
            // Distinct corner markers + green center medallion (#4CAF50) for reliable Patch tracking & color check.
            fillPaint.color = c.borderColor
            val m = cardHalf * 0.28f
            canvas.drawRect(c.x - cardHalf + 10f, c.y - cardHalf + 10f, c.x - cardHalf + 10f + m, c.y - cardHalf + 10f + m, fillPaint)
            if (c.index >= 2) {
                canvas.drawRect(c.x + cardHalf - 10f - m, c.y - cardHalf + 10f, c.x + cardHalf - 10f, c.y - cardHalf + 10f + m, fillPaint)
            }
            if (c.index >= 3) {
                canvas.drawRect(c.x - cardHalf + 10f, c.y + cardHalf - 10f - m, c.x - cardHalf + 10f + m, c.y + cardHalf - 10f, fillPaint)
            }
            fillPaint.color = Color.parseColor("#4CAF50")
            canvas.drawCircle(c.x, c.y, cardHalf * 0.42f, fillPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cardHalf = width * 0.085f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = cards.asReversed().firstOrNull {
                    hypot((event.x - it.x).toDouble(), (event.y - it.y).toDouble()) <= cardHalf * 1.25
                } ?: return false
                hit.snappedSlot = -1
                activeCard = hit
                grabDx = hit.x - event.x
                grabDy = hit.y - event.y
                cards.remove(hit)
                cards.add(hit)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val cur = activeCard ?: return false
                cur.x = event.x + grabDx
                cur.y = event.y + grabDy
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cur = activeCard ?: return false
                activeCard = null
                val slotIdx = if (event.actionMasked == MotionEvent.ACTION_UP) nearestFreeSlot(cur) else -1
                if (slotIdx >= 0) {
                    cur.snappedSlot = slotIdx
                    cur.x = slots[slotIdx].x
                    cur.y = slots[slotIdx].y
                    invalidate()
                    AvstLog.emit(
                        "S04",
                        "snap",
                        ok = true,
                        data = JSONObject().put("card", cur.index).put("slot", slotIdx + 1),
                    )
                    if (!goalEmitted && cards.all { it.snappedSlot >= 0 }) {
                        goalEmitted = true
                        AvstLog.emit("S04", "goal", ok = true)
                    }
                } else {
                    val sx = cur.x
                    val sy = cur.y
                    val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = SceneActivity.S04_SNAP_BACK_MS
                        addUpdateListener { va ->
                            val f = va.animatedValue as Float
                            cur.x = sx + (cur.homeX - sx) * f
                            cur.y = sy + (cur.homeY - sy) * f
                            invalidate()
                        }
                    }
                    animators += anim
                    anim.start()
                    AvstLog.emit(
                        "S04",
                        "snap_back",
                        ok = false,
                        data = JSONObject().put("card", cur.index),
                    )
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun stopAnimators() {
        animators.forEach { it.cancel() }
        animators.clear()
    }
}

private class MovingShapesView(
    context: Context,
    private val seed: Int,
    private val rng: SceneRng,
) : View(context) {
    private data class Shape(val kind: Int, val color: Int, val baseX: Float, val baseY: Float, val phase: Float, val freq: Float)

    private val radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SceneActivity.S06_RADIUS_DP, resources.displayMetrics)
    private val orbitPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SceneActivity.S06_ORBIT_DP, resources.displayMetrics)
    private val startMs = SystemClock.uptimeMillis()
    private val shapes = ArrayList<Shape>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#2E7D32")
    }
    private val path = Path()
    private var running = true
    private var hitMarked = false

    init {
        setBackgroundColor(Color.rgb(248, 249, 250))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || shapes.isNotEmpty()) return
        val anchors = listOf(
            0.50f to 0.50f,
            0.25f to 0.25f,
            0.75f to 0.26f,
            0.26f to 0.76f,
            0.76f to 0.75f,
        )
        val palette = listOf(
            Color.parseColor("#E53935"), // 0: target red circle
            Color.parseColor("#1E88E5"), // 1: blue square
            Color.parseColor("#43A047"), // 2: green triangle
            Color.parseColor("#FB8C00"), // 3: orange diamond
            Color.parseColor("#8E24AA"), // 4: purple hexagon
        )
        for (i in 0 until 5) {
            val jx = (rng.nextFloat() - 0.5f) * w * 0.05f
            val jy = (rng.nextFloat() - 0.5f) * h * 0.03f
            shapes += Shape(
                kind = i,
                color = palette[i],
                baseX = w * anchors[i].first + jx,
                baseY = h * anchors[i].second + jy,
                phase = rng.nextFloat() * 6.28f,
                freq = 0.75f + i * 0.12f,
            )
        }
    }

    private fun posOf(s: Shape, nowMs: Long): Pair<Float, Float> {
        val t = (nowMs - startMs) / 1000f
        val x = s.baseX + orbitPx * sin(t * s.freq + s.phase)
        val y = s.baseY + orbitPx * cos(t * (s.freq * 0.82f) + s.phase)
        return x to y
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        for (s in shapes) {
            val (cx, cy) = posOf(s, now)
            paint.color = s.color
            when (s.kind) {
                0 -> {
                    canvas.drawCircle(cx, cy, radiusPx, paint)
                    if (hitMarked) canvas.drawCircle(cx, cy, radiusPx + 18f, ringPaint)
                }
                1 -> canvas.drawRect(cx - radiusPx * 0.85f, cy - radiusPx * 0.85f, cx + radiusPx * 0.85f, cy + radiusPx * 0.85f, paint)
                2 -> {
                    path.reset()
                    path.moveTo(cx, cy - radiusPx)
                    path.lineTo(cx - radiusPx * 0.92f, cy + radiusPx * 0.82f)
                    path.lineTo(cx + radiusPx * 0.92f, cy + radiusPx * 0.82f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                3 -> {
                    path.reset()
                    path.moveTo(cx, cy - radiusPx)
                    path.lineTo(cx + radiusPx, cy)
                    path.lineTo(cx, cy + radiusPx)
                    path.lineTo(cx - radiusPx, cy)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                else -> {
                    path.reset()
                    for (k in 0 until 6) {
                        val a = k * Math.PI / 3.0
                        val px = cx + (radiusPx * 0.9f * cos(a)).toFloat()
                        val py = cy + (radiusPx * 0.9f * sin(a)).toFloat()
                        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }
        if (running) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && shapes.isNotEmpty()) {
            val (rx, ry) = posOf(shapes[0], SystemClock.uptimeMillis())
            val d = hypot((event.x - rx).toDouble(), (event.y - ry).toDouble()).toFloat()
            if (d <= radiusPx) {
                hitMarked = true
                invalidate()
                AvstLog.emit("S06", "target_hit", ok = true)
                AvstLog.emit("S06", "goal", ok = true)
            } else {
                AvstLog.emit("S06", "target_missed", ok = false)
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    fun stop() {
        running = false
    }
}

private class SliderBarView(
    context: Context,
    initValue: Int,
    private val onMove: (Int) -> Unit,
    private val onRelease: (Int) -> Unit,
) : View(context) {
    private var value = initValue.coerceIn(0, 100)
    private var dragging = false
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun trackBounds(): Triple<Float, Float, Float> {
        val x1 = width * (100f / 1080f)
        val x2 = width * (980f / 1080f)
        val y = height * (800f / 2400f)
        return Triple(x1, x2, y)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (x1, x2, y) = trackBounds()
        val tx = x1 + (x2 - x1) * (value / 100f)
        trackPaint.strokeWidth = 16f
        trackPaint.color = Color.rgb(209, 213, 219)
        canvas.drawLine(x1, y, x2, y, trackPaint)
        trackPaint.color = Color.rgb(37, 99, 235)
        canvas.drawLine(x1, y, tx, y, trackPaint)
        thumbPaint.color = Color.rgb(29, 78, 216)
        canvas.drawCircle(tx, y, 32f, thumbPaint)
    }

    private fun updateFromX(x: Float) {
        val (x1, x2, _) = trackBounds()
        val ratio = ((x - x1) / (x2 - x1).coerceAtLeast(1f)).coerceIn(0f, 1f)
        value = (ratio * 100f).roundToInt().coerceIn(0, 100)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val (_, _, y) = trackBounds()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (kotlin.math.abs(event.y - y) <= height * 0.08f) {
                    dragging = true
                    updateFromX(event.x)
                    onMove(value)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    updateFromX(event.x)
                    onMove(value)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    updateFromX(event.x)
                    onRelease(value)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}

private class ChargeBarView(context: Context) : View(context) {
    private var pct = 0
    private var barColor = Color.rgb(37, 99, 235)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 231, 235) }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setProgress(p: Int, color: Int) {
        pct = p.coerceIn(0, 100)
        barColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = height / 2f
        canvas.drawRoundRect(rect, r, r, bgPaint)
        if (pct > 0) {
            fgPaint.color = barColor
            rect.set(0f, 0f, width * (pct / 100f), height.toFloat())
            canvas.drawRoundRect(rect, r, r, fgPaint)
        }
    }
}

private class CarouselBannerView(
    context: Context,
    rng: SceneRng,
) : View(context) {
    private val colors = listOf(
        Color.rgb(59, 130, 246),
        Color.rgb(16, 185, 129),
        Color.rgb(245, 158, 11),
        Color.rgb(139, 92, 246),
    )
    private val startIdx = rng.nextInt(colors.size)
    private val startMs = SystemClock.uptimeMillis()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var running = true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val elapsed = SystemClock.uptimeMillis() - startMs
        val slide = ((elapsed / SceneActivity.S12_PERIOD_MS).toInt() + startIdx) % colors.size
        val next = (slide + 1) % colors.size
        val inPeriod = elapsed % SceneActivity.S12_PERIOD_MS
        val shift = if (inPeriod >= SceneActivity.S12_PERIOD_MS - 400L) {
            (inPeriod - (SceneActivity.S12_PERIOD_MS - 400L)) / 400f
        } else {
            0f
        }
        val offset = -width * shift
        drawBannerSlide(canvas, offset, colors[slide], elapsed)
        if (shift > 0f) drawBannerSlide(canvas, offset + width, colors[next], elapsed)
        if (running) postInvalidateOnAnimation()
    }

    private fun drawBannerSlide(canvas: Canvas, x0: Float, bg: Int, elapsed: Long) {
        paint.color = bg
        canvas.drawRect(x0, 0f, x0 + width, height.toFloat(), paint)
        // Moving decorative stripes inside the banner so MotionTrack recognizes the banner region cleanly.
        paint.color = Color.argb(65, 255, 255, 255)
        val step = (width / 5f).coerceAtLeast(40f)
        val drift = ((elapsed / 12L) % step.toLong()).toFloat()
        var x = x0 - step + drift
        while (x < x0 + width + step) {
            canvas.drawCircle(x, height * 0.5f, height * 0.28f, paint)
            x += step
        }
    }

    fun stop() {
        running = false
    }
}
