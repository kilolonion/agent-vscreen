package com.kilon.agentvscreen.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.Risk
import com.kilon.agentvscreen.host.StrongConfirm
import com.kilon.agentvscreen.tr
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 2.0.0-beta6 (6.1, 6.2, 6.5, 6.9, 6.10): six-layer strong confirmation page.
 *
 * - L1 (main display only): first line of [onCreate] checks `display.displayId != DEFAULT_DISPLAY`,
 *   writes [Audit.security] `"strong_confirm_wrong_display"`, and immediately calls [finish] without
 *   moving via [MainDisplay.moved]. Manifest sets `exported=false`, `excludeFromRecents=true`,
 *   `launchMode=singleTask`, and independent `taskAffinity`.
 * - L4 (biometric / device credential): when `KeyguardManager.isDeviceSecure == true`, uses framework
 *   [BiometricPrompt] (`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`). L4 protects because the agent neither
 *   knows the user's PIN nor has biometric credentials, and L2 prevents the agent from injecting onto display 0.
 * - L5 (anti-peek / anti-tapjacking): window sets `FLAG_SECURE`; the confirm button sets
 *   `filterTouchesWhenObscured = true`.
 * - L6 (6.2 no-lockscreen hold confirm): when `isDeviceSecure == false`, requires a 3 s real-touch hold
 *   with a progress ring filtered by [StrongConfirm.isRealTouch] (`deviceId > 0`, non-null `!isVirtual`
 *   touchscreen `InputDevice`, no `FLAG_IS_ACCESSIBILITY_EVENT`). Never attaches an `OnLongClickListener`,
 *   and ignores accessibility `ACTION_CLICK` / `ACTION_LONG_CLICK` with an audit log entry.
 */
class StrongConfirmActivity : ComponentActivity() {
    internal data class LiveButton(
        val requestId: String,
        val biometricMode: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    companion object {
        const val EXTRA_ID = "confirm_id"

        /** Live screen bounds of the confirm button while [StrongConfirmActivity] is attached (debug hook only). */
        @Volatile internal var liveButtonState: LiveButton? = null
        @Volatile private var liveActivityRef: java.lang.ref.WeakReference<StrongConfirmActivity>? = null

        internal fun finishLiveForCi() {
            liveActivityRef?.get()?.let { act ->
                act.runOnUiThread {
                    if (!act.isFinishing && !act.isDestroyed) act.finish()
                }
            }
        }

        fun open(ctx: Context, confirmId: String) {
            val intent = Intent(ctx, StrongConfirmActivity::class.java)
                .putExtra(EXTRA_ID, confirmId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            MainDisplay.start(ctx, intent)
        }
    }

    private var requestIdState = mutableStateOf<String?>(null)
    private var secureState = mutableStateOf(false)
    private var statusMessageState = mutableStateOf<String?>(null)
    private var selectedScopeState = mutableStateOf("look")
    private var bioCancel: CancellationSignal? = null
    private var actionBar: LinearLayout? = null
    private var denyBtn: TextView? = null
    private var confirmBtn: StrongConfirmButtonView? = null

    private fun currentDisplayId(): Int =
        runCatching { display?.displayId }.getOrNull()
            ?: @Suppress("DEPRECATION") runCatching { windowManager?.defaultDisplay?.displayId }.getOrNull()
            ?: Display.DEFAULT_DISPLAY

    private fun rejectIfWrongDisplay(srcIntent: Intent?): Boolean {
        val dispId = currentDisplayId()
        if (dispId != Display.DEFAULT_DISPLAY) {
            Audit.security("strong_confirm_wrong_display", "displayId=$dispId id=${srcIntent?.getStringExtra(EXTRA_ID) ?: "-"}")
            finish()
            return true
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // L1: MUST be first — if launched on any non-default display, audit and finish immediately (never MainDisplay.moved).
        val dispId = currentDisplayId()
        if (dispId != Display.DEFAULT_DISPLAY) {
            super.onCreate(savedInstanceState)
            Audit.security("strong_confirm_wrong_display", "displayId=$dispId id=${intent?.getStringExtra(EXTRA_ID) ?: "-"}")
            finish()
            return
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        liveActivityRef = java.lang.ref.WeakReference(this)
        // L5: prevent screenshots and screen recordings from reading the confirmation screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        requestIdState.value = resolveId(intent)
        secureState.value = isDeviceSecure()
        selectedScopeState.value = resolveActiveRequest()?.defaultScope ?: "look"

        val root = FrameLayout(this)
        val compose = ComposeView(this).apply {
            setContent {
                AvsTheme {
                    StrongConfirmScreen()
                }
            }
        }
        root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Native bottom action bar (sibling of ComposeView, directly under DecorView) so Android 14+ accessibility
        // always exposes StrongConfirmButtonView directly to performAccessibilityAction (6.9).
        val density = resources.displayMetrics.density
        val padH = (20f * density).toInt()
        val padBBase = (24f * density).toInt()
        val btnH = (54f * density).toInt()
        val gap = (12f * density).toInt()
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, (8f * density).toInt(), padH, padBBase)
        }
        val denyBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = btnH / 2f
            setColor(if (dark) AColor.argb(210, 46, 46, 56) else AColor.argb(235, 240, 241, 248))
            setStroke((1f * density).toInt(), if (dark) AColor.argb(45, 255, 255, 255) else AColor.argb(30, 0, 0, 0))
        }
        val deny = TextView(this).apply {
            text = tr("拒绝", "Deny")
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (dark) AColor.WHITE else AColor.rgb(28, 28, 36))
            background = denyBg
            setOnClickListener {
                val activeId = resolveActiveRequest()?.id
                if (activeId != null) StrongConfirm.deny(applicationContext, activeId)
                finish()
            }
        }
        val confirm = StrongConfirmButtonView(
            context = this,
            requestId = requestIdState.value.orEmpty(),
            biometricMode = secureState.value,
            enabledAction = true,
            onTriggerBiometric = { activeId ->
                StrongConfirm.byId(activeId)?.let { launchBiometric(it) }
            },
            onHoldCompleted = { activeId ->
                StrongConfirm.approve(applicationContext, activeId, StrongConfirm.BY_HOLD)
                if (!isFinishing && !isDestroyed) finish()
            },
        )
        bar.addView(deny, LinearLayout.LayoutParams(0, btnH, 1f).apply { marginEnd = gap })
        bar.addView(confirm, LinearLayout.LayoutParams(0, btnH, 1.35f))
        // Observe insets on root (returning them unconsumed) so ComposeView never starves the native bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            bar.setPadding(padH, (8f * density).toInt(), padH, padBBase + nav.bottom)
            insets
        }
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        actionBar = bar
        denyBtn = deny
        confirmBtn = confirm
        setContentView(root)
        syncNativeBar()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (rejectIfWrongDisplay(intent)) return
        setIntent(intent)
        statusMessageState.value = null
        requestIdState.value = resolveId(intent)
        secureState.value = isDeviceSecure()
        selectedScopeState.value = resolveActiveRequest()?.defaultScope ?: "look"
        syncNativeBar()
    }

    override fun onResume() {
        super.onResume()
        if (rejectIfWrongDisplay(intent)) return
        secureState.value = isDeviceSecure()
        syncNativeBar()
    }

    private fun resolveActiveRequest(): StrongConfirm.Request? =
        StrongConfirm.byId(requestIdState.value) ?: StrongConfirm.latestPending()

    private fun syncNativeBar() {
        val req = resolveActiveRequest()
        val bar = actionBar ?: return
        if (req == null || !req.live) {
            bar.visibility = View.GONE
            liveButtonState = null
            return
        }
        val secure = secureState.value
        bar.visibility = View.VISIBLE
        denyBtn?.text = tr("拒绝", "Deny")
        confirmBtn?.bind(req.id, secure, secure || req.allowHold)
    }

    override fun onStart() {
        super.onStart()
        Overlay.fullscreenShown(true)
    }

    override fun onStop() {
        Overlay.fullscreenShown(false)
        super.onStop()
    }

    override fun onDestroy() {
        if (liveActivityRef?.get() === this) liveActivityRef = null
        runCatching { bioCancel?.cancel() }
        super.onDestroy()
    }

    private fun resolveId(i: Intent?): String? =
        i?.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() } ?: StrongConfirm.latestPending()?.id

    private fun isDeviceSecure(): Boolean =
        runCatching { getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)

    private fun launchBiometric(req: StrongConfirm.Request) {
        runCatching { bioCancel?.cancel() }
        val sig = CancellationSignal()
        bioCancel = sig
        statusMessageState.value = null
        try {
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val subtitle = if (req.pairingCode != null) {
                tr("配对码 ${req.pairingCode} · ${req.publicClientName ?: req.tokenName}", "Pairing ${req.pairingCode} · ${req.publicClientName ?: req.tokenName}")
            } else {
                tr("令牌「${req.tokenName}」· ${req.commandName}", "Token \"${req.tokenName}\" · ${req.commandName}")
            }
            val prompt = BiometricPrompt.Builder(this)
                .setTitle(req.risk.title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(sig, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    StrongConfirm.approve(applicationContext, req.id, StrongConfirm.BY_BIOMETRIC, selectedScopeState.value)
                    if (!isFinishing && !isDestroyed) finish()
                }

                override fun onAuthenticationFailed() {
                    statusMessageState.value = tr("验证未通过，请重试", "Verification did not match; try again")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    statusMessageState.value = errString.toString().ifBlank {
                        tr("已取消验证（代码 $errorCode）", "Verification cancelled (code $errorCode)")
                    }
                }
            })
        } catch (t: Throwable) {
            statusMessageState.value = tr("无法启动系统验证：${t.message}", "Cannot start system verification: ${t.message}")
        }
    }

    @Composable
    private fun StrongConfirmScreen() {
        val reqId = requestIdState.value
        val req = remember(reqId) { StrongConfirm.byId(reqId) ?: StrongConfirm.latestPending() }
        val secure = secureState.value
        var showHelp by remember { mutableStateOf(false) }
        val statusMsg = statusMessageState.value
        val chosenScope = selectedScopeState.value

        Box(
            Modifier
                .fillMaxSize()
                .backdrop()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            if (req == null || !req.live) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(26.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        tr("这个确认请求已处理或已过期", "This confirmation request is already handled or expired"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SoftButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("关闭", "Close"))
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Top bar: risk title + '?' help button (no prose wall on the card, per 6.5).
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            req.risk.title,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showHelp = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = tr("说明", "Help"),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }

                    // 6.2: permanent top warning banner when the phone has no screen lock PIN/biometric.
                    if (!secure) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0x26FF9800))
                                .border(1.dp, Color(0x66FF9800), RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                if (req.allowHold) tr("建议设置锁屏密码，确认会更安全", "Set a screen lock PIN or biometric for stronger confirmations")
                                else tr("公网相关确认必须先设置手机锁屏密码，不支持长按确认。", "Public access confirmations require a phone screen lock (PIN/biometric) and do not allow hold-to-confirm."),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // 6.5 / 7.2 / 7.7: glass card (public pairing shows pairing code, client_name, redirect host, look/operate).
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(24.dp))
                            .border(
                                1.2.dp,
                                Brush.linearGradient(listOf(Color(0xFF8B7CFF), Palette.asking)),
                                RoundedCornerShape(24.dp),
                            )
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (req.pairingCode != null) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0x1F7B6CFF))
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    tr("核对配对码", "Verify pairing code"),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Text(
                                    req.pairingCode,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp,
                                )
                            }
                            InfoLine(
                                label = tr("客户端", "Client"),
                                value = req.publicClientName ?: req.tokenName,
                            )
                            InfoLine(
                                label = tr("回调域名", "Redirect host"),
                                value = req.redirectHost ?: "-",
                            )
                            val opts = req.selectableScopes.filter { it in Tokens.PUBLIC_SCOPES }.ifEmpty { listOf("look") }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    tr("授予权限（公网永不开放“系统”权限）", "Granted scope (\"system\" is never available over public MCP)"),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                opts.forEach { sc ->
                                    val active = chosenScope == sc
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { selectedScopeState.value = sc }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        RadioButton(selected = active, onClick = { selectedScopeState.value = sc })
                                        Column {
                                            Text(scopeLabel(sc), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                            Text(
                                                if (sc == "operate") tr("可打开并操作应用（高风险步骤仍需在手机确认）", "Can open and operate apps (high-risk steps still confirm on phone)")
                                                else tr("仅查看屏幕和状态，不能点击或输入（默认）", "View screen and status only; cannot tap or type (default)"),
                                                fontSize = 12.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (req.risk == Risk.PUBLIC_ENABLE) {
                            InfoLine(
                                label = tr("操作", "Action"),
                                value = req.commandName,
                            )
                            if (req.detail.isNotBlank()) {
                                InfoLine(
                                    label = tr("说明", "Details"),
                                    value = req.detail,
                                )
                            }
                        } else {
                            InfoLine(
                                label = tr("令牌", "Token"),
                                value = "${req.tokenName}（${scopeLabel(req.tokenScope)}）",
                            )
                            InfoLine(
                                label = tr("客户端", "Client"),
                                value = StrongConfirm.clientLabel(req.client),
                            )
                            InfoLine(
                                label = tr("操作", "Action"),
                                value = req.commandName,
                            )
                        }
                        if (statusMsg != null) {
                            Text(
                                statusMsg,
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                        if (!secure && !req.allowHold) {
                            Text(
                                tr("此操作必须先设置手机锁屏密码，不支持长按确认。", "This action requires a screen lock PIN/biometric and does not allow hold-to-confirm."),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        if (showHelp) {
            GlassDialog(
                onDismissRequest = { showHelp = false },
                title = { DialogTitle(tr("为什么需要强确认", "Why strong confirmation is required")) },
                text = {
                    Text(
                        tr(
                            "为了防止 agent 自己点“同意”，重要接入与高风险操作必须在本机主屏完成验证：\n" +
                                "• 已设锁屏密码时，使用系统生物识别或锁屏密码验证；\n" +
                                "• 未设锁屏密码时，需要真实手指按住确认按钮 3 秒（自动忽略注入与无障碍点击）；\n" +
                                "• 随时可在 设置 › 令牌 › 已确认的接入 里撤销。",
                            "To stop an agent from approving its own requests, new access and high-risk actions must be verified on the main screen:\n" +
                                "• With a screen lock set, system biometric or PIN verification is used;\n" +
                                "• Without a screen lock, a 3-second real-finger hold is required (injected and accessibility clicks are ignored);\n" +
                                "• You can revoke any approval at any time under Settings › Tokens › Confirmed access.",
                        ),
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                },
                confirmButton = {
                    LinkButton(onClick = { showHelp = false }) { Text(tr("知道了", "Got it")) }
                },
            )
        }
    }

    @Composable
    private fun InfoLine(label: String, value: String) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    private fun scopeLabel(s: String): String = when (s) {
        "look" -> tr("只看", "look only")
        "operate" -> tr("可操作", "operate")
        else -> tr("全部", "system")
    }
}

/**
 * Custom Android [View] for the confirm button in [StrongConfirmActivity]:
 * - Enforces L5 `filterTouchesWhenObscured = true`.
 * - Attaches NO `OnLongClickListener` (`isLongClickable = false`).
 * - Intercepts accessibility `ACTION_CLICK` / `ACTION_LONG_CLICK` and [performClick]/[performLongClick],
 *   logging `strong_confirm_a11y_ignored` and never approving (6.9).
 * - In 6.2 hold mode (`!biometricMode`), applies L6 [StrongConfirm.isRealTouch] on every touch event,
 *   logging `strong_confirm_l6_ignored` on rejected events, and draws a 3 s circular progress ring.
 */
internal class StrongConfirmButtonView(
    context: Context,
    private var requestId: String,
    private var biometricMode: Boolean,
    private var enabledAction: Boolean,
    private val onTriggerBiometric: (String) -> Unit,
    private val onHoldCompleted: (String) -> Unit,
) : View(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var holding = false
    private var holdStartMs = 0L
    private var holdProgress = 0f
    private var completed = false
    private var downInBiometric = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AColor.argb(60, 255, 255, 255)
    }
    private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = AColor.argb(75, 255, 255, 255)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3.2f)
        color = AColor.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.WHITE
        textSize = sp(15.5f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()
    private val ringRect = RectF()

    private val ticker = object : Runnable {
        override fun run() {
            if (!holding || completed) return
            val elapsed = SystemClock.uptimeMillis() - holdStartMs
            holdProgress = (elapsed.toFloat() / StrongConfirm.HOLD_CONFIRM_MS).coerceIn(0f, 1f)
            invalidate()
            if (elapsed >= StrongConfirm.HOLD_CONFIRM_MS) {
                holding = false
                completed = true
                onHoldCompleted(requestId)
            } else {
                handler.postDelayed(this, 16L)
            }
        }
    }

    init {
        // L5: drop touch events when obscured by another window/overlay.
        filterTouchesWhenObscured = true
        // 6.9: confirm button must NEVER attach OnLongClickListener or respond to a11y click/long-click.
        isLongClickable = false
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = labelText()
    }

    fun bind(id: String, bio: Boolean, enabled: Boolean) {
        if (requestId != id) {
            cancelHold()
            completed = false
        }
        requestId = id
        biometricMode = bio
        enabledAction = enabled
        contentDescription = labelText()
        invalidate()
        post { publishBounds() }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        publishBounds()
    }

    private fun publishBounds() {
        if (!isAttachedToWindow || width <= 0 || height <= 0) return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        StrongConfirmActivity.liveButtonState = StrongConfirmActivity.LiveButton(
            requestId = requestId,
            biometricMode = biometricMode,
            left = loc[0],
            top = loc[1],
            right = loc[0] + width,
            bottom = loc[1] + height,
        )
    }

    private fun labelText(): String = when {
        !enabledAction -> tr("需设锁屏密码", "Screen lock required")
        biometricMode -> tr("确认", "Confirm")
        holding -> {
            val leftSec = (((1f - holdProgress) * StrongConfirm.HOLD_CONFIRM_MS) / 1000f).coerceAtLeast(0.1f)
            tr("继续按住 %.1fs".format(leftSec), "Keep holding %.1fs".format(leftSec))
        }
        else -> tr("按住确认（3秒）", "Hold 3s to confirm")
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.text = labelText()
        info.contentDescription = "strong_confirm_button"
        info.viewIdResourceName = "${context.packageName}:id/strong_confirm_button"
        info.isClickable = true
        info.isLongClickable = false
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
            // 6.9: never approve via accessibility CLICK / LONG_CLICK; record audit entry.
            Audit.security("strong_confirm_a11y_ignored", "id=$requestId action=$action")
            return false
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun performClick(): Boolean {
        Audit.security("strong_confirm_a11y_ignored", "id=$requestId action=performClick")
        return false
    }

    override fun performLongClick(): Boolean {
        Audit.security("strong_confirm_a11y_ignored", "id=$requestId action=performLongClick")
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!enabledAction || completed) return false
        if (biometricMode) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downInBiometric = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val wasDown = downInBiometric
                    downInBiometric = false
                    invalidate()
                    if (wasDown && inside(ev.x, ev.y)) {
                        onTriggerBiometric(requestId)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    downInBiometric = false
                    invalidate()
                    return true
                }
            }
            return true
        }

        // 6.2 no-lockscreen hold confirm with L6 real-touch filtering.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!StrongConfirm.isRealTouch(ev)) {
                    logL6Ignored(ev)
                    cancelHold()
                    return false
                }
                holding = true
                holdStartMs = SystemClock.uptimeMillis()
                holdProgress = 0f
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, 16L)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!holding) return false
                if (!StrongConfirm.isRealTouch(ev)) {
                    logL6Ignored(ev)
                    cancelHold()
                    return false
                }
                if (!inside(ev.x, ev.y)) {
                    cancelHold()
                    return false
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                return true
            }
        }
        return false
    }

    private fun logL6Ignored(ev: MotionEvent) {
        val dev = if (ev.deviceId > 0) InputDevice.getDevice(ev.deviceId) else null
        Audit.security(
            "strong_confirm_l6_ignored",
            "id=$requestId deviceId=${ev.deviceId} source=0x${Integer.toHexString(ev.source)} " +
                "flags=0x${Integer.toHexString(ev.flags)} dev=${dev?.name ?: "null"}",
        )
    }

    private fun cancelHold() {
        holding = false
        holdProgress = 0f
        handler.removeCallbacks(ticker)
        invalidate()
    }

    private fun inside(x: Float, y: Float): Boolean = x >= 0f && y >= 0f && x <= width && y <= height

    override fun onDetachedFromWindow() {
        cancelHold()
        StrongConfirmActivity.liveButtonState = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        bgPaint.color = when {
            !enabledAction -> AColor.argb(120, 123, 108, 255)
            downInBiometric || holding -> AColor.rgb(104, 88, 232)
            else -> AColor.rgb(123, 108, 255)
        }
        canvas.drawRoundRect(rect, r, r, bgPaint)

        if (!biometricMode && holdProgress > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, w * holdProgress, h)
            canvas.drawRoundRect(rect, r, r, fillPaint)
            canvas.restore()
        }

        val cy = h / 2f
        if (!biometricMode && enabledAction) {
            val radius = dp(10.5f)
            val cx = dp(24f)
            ringRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawOval(ringRect, ringBgPaint)
            if (holdProgress > 0f) {
                canvas.drawArc(ringRect, -90f, 360f * holdProgress, false, ringPaint)
            }
        }
        val textX = if (!biometricMode && enabledAction) w / 2f + dp(10f) else w / 2f
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(labelText(), textX, baseline, textPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
