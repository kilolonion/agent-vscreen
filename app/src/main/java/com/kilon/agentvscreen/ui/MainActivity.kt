package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.host.A11yHeal
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.Prefs
import com.kilon.agentvscreen.host.Setup
import com.kilon.agentvscreen.host.ShizukuBackend
import com.kilon.agentvscreen.host.ShizukuManager
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/**
 * 1.4: the activity only hosts the app (AppRoot: four tabs and a page stack) and does what needs an activity:
 * the clipboard, the Shizuku permission request, recreating for a new language. Short results go to the snackbar.
 */
class MainActivity : ComponentActivity(), AppHost {
    companion object {
        const val EXTRA_PAGE = "page"

        /** 1.4.0-beta11: the tab a page name opens (notifications open HOME, RECORDS, CONNECT …). */
        fun tabOf(name: String?): Int = when (name?.uppercase()) { "RECORDS" -> TAB_RECORDS; "CONNECT" -> TAB_CONNECT; "SETTINGS" -> 3; else -> 0 }

        /**
         * A page asked for while the app is already open (it is singleTask: a notification tap reaches onNewIntent,
         * not onCreate). AppRoot follows it. Before beta11 such a tap only brought the app forward on whatever page.
         */
        val pageRequests = kotlinx.coroutines.flow.MutableStateFlow<Pair<Long, String>?>(null)

        /** A page name from a notification, a shortcut or a test: the route names, plus the 1.3 page names. */
        fun routeOf(name: String?): Route? = when (name?.uppercase()) {
            null, "", "MAIN" -> null
            "CONNECT" -> Route.Connect
            "REMOTE" -> Route.Remote
            "PUBLIC" -> Route.PublicAccess
            "SECURITY" -> Route.Security
            "FLOATING", "FLOATING_WINDOW" -> Route.FloatingWindow
            "TOKENS" -> Route.Tokens
            "RULES", "PERMS" -> Route.Rules
            "SCHEDULE", "SCHEDULES" -> Route.Schedules
            "LESSONS" -> Route.Lessons
            "LOG" -> Route.Log
            "SETUP" -> Route.Setup
            "DIAG" -> Route.Diag
            "HELP" -> Route.Help
            else -> null
        }
    }

    private val snack = SnackbarHostState()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (MainDisplay.moved(this)) return
        val page = intent?.getStringExtra(EXTRA_PAGE)
        setContent { AvsTheme { AppRoot(this, routeOf(page)?.takeIf { it != Route.Connect }, snack, tabOf(page)) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_PAGE)?.let { pageRequests.value = System.nanoTime() to it }
    }

    override fun onResume() {
        super.onResume()
        // In front now, so a foreground service may start: repair a start the system refused in the background,
        // and retry a port that was taken.
        if (Prefs.serviceOn(this)) HostService.start(this)
        // 2.0.1-beta1: on demand too: opening the app is how the user is told to fix "not running", so it must.
        HostService.healServer(this, "app opened")
        com.kilon.agentvscreen.host.Updates.checkDaily(this)
        Overlay.refresh()
        // Shizuku's (or root's) answer can change while we are away: read it again.
        com.kilon.agentvscreen.host.Privileged.refresh()
    }

    override fun say(text: String) { lifecycleScope.launch { snack.showSnackbar(text) } }

    override fun copy(label: String, text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        if (sensitive) {
            say(tr(S.main_activity_copied, label))
        } else {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            val preview = if (flat.length > 40) flat.take(40) + "…" else flat
            say(tr(S.main_activity_copied_2, label, preview))
        }
    }

    override fun recreateForLanguage() = recreate()

    /** The grant button always ends in something the user can see (GitHub #2): Shizuku's dialog, or its own app with what to do. */
    override fun grantShizuku() {
        fun inManager() {
            ShizukuManager.openShizukuApp(this)
            say(tr(S.main_activity_shizuku_showed_no_dialog_turn))
        }
        when (ShizukuManager.requestPermission()) {
            ShizukuBackend.Ask.ALREADY -> say(tr(S.main_activity_shizuku_is_authorized))
            ShizukuBackend.Ask.NO_BINDER -> { ShizukuManager.openShizukuApp(this); say(ShizukuManager.missingBinderHint()) }
            ShizukuBackend.Ask.DENIED_ON_RECORD, ShizukuBackend.Ask.FAILED -> inManager()
            // A dialog should show. A denial (the user's Deny or Shizuku's "adb permission limited") gets both
            // readings; no answer within 8 s means the request went nowhere. Checks belong to this request only.
            ShizukuBackend.Ask.ASKED -> {
                val attempt = ShizukuManager.attempt()
                var told = false
                fun check(last: Boolean) {
                    if (told || isFinishing || ShizukuManager.attempt() != attempt) return
                    ShizukuManager.refresh()
                    val s = ShizukuManager.snapshot.value
                    if (s.state != ShizukuManager.State.NEED_PERMISSION) return
                    if (s.denied) { told = true; say(ShizukuManager.deniedHint()) }
                    else if (last && hasWindowFocus()) inManager()
                }
                window.decorView.postDelayed({ check(last = false) }, 3_000L)
                window.decorView.postDelayed({ check(last = true) }, 8_000L)
            }
        }
    }

    override fun diagnostics(items: List<Setup.Item>): String {
        val s = BackgroundOperation.state.value
        return buildString {
            appendLine("Agent-VScreen ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("服务：${if (HostService.isAlive) "运行中" else "已停止"} · 127.0.0.1:${ApiConfig.port} · ${HostService.server?.lastError ?: "无错误"}")
            appendLine("后台：${com.kilon.agentvscreen.host.Liveness.json()}")
            appendLine("连接：${s.connection}${s.connectionError?.let { "（$it）" } ?: ""} · 副屏 ${s.displayId ?: "无"} ${s.screenWidth}x${s.screenHeight} · 会话 ${s.ownerSessionId ?: "-"} · ${Palette.word(modeOf(s))}")
            items.forEach { appendLine("${if (it.ok) "✓" else "✗"} ${it.label}：${it.detail}") }
            appendLine("无障碍自动恢复：${A11yHeal.lastResult ?: "-"}")
            appendLine()
            append(com.kilon.agentvscreen.cmd.Diag.text(this@MainActivity))
        }
    }
}
