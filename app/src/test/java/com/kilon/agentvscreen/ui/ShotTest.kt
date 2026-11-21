package com.kilon.agentvscreen.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Renders the home page to a PNG (only when AVS_SHOTS is set) so the layout can be looked at without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ShotTest {
    @Test fun home() {
        val out = System.getenv("AVS_SHOTS") ?: return
        animateLook = false; Glass.blurOk = false
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.kilon.agentvscreen.host.AppLogger.init(app)
        com.kilon.agentvscreen.api.ApiConfig.init(app)
        runCatching { com.kilon.agentvscreen.core.BackgroundOperation.init(app) }.onFailure { println("BO init: $it") }
        // 1.4.0-beta3: two finished tasks, so 首页 shows 刚才 and 记录 shows the timeline.
        com.kilon.agentvscreen.core.TaskLog.init(app)
        val now = System.currentTimeMillis()
        File(app.filesDir, "tasks/log.jsonl").writeText(listOf(
            com.kilon.agentvscreen.core.TaskLog.Task(1, "s1", "claude", now - 3_600_000, now - 3_400_000, "查明天上海的天气", "com.android.settings", 9),
            com.kilon.agentvscreen.core.TaskLog.Task(2, "s2", "claude", now - 900_000, now - 600_000, null, "com.android.settings", 14, "STEP_MISSED", 1),
        ).joinToString("\n") { it.json().toString() } + "\n")
        // 1.3 beta3: the home page with a question waiting, and each second-level page.
        com.kilon.agentvscreen.host.UserAsk.create(app, com.kilon.agentvscreen.host.UserAsk.Kind.CHOOSE, "shot", "这周末你想先动哪块？", "",
            options = listOf("考研单词", "大创器件", "先歇着"))
        for (page in listOf("RECORDS", "SETTINGS", "CONNECT", "REMOTE", "TOKENS", "RULES", "SCHEDULES", "LESSONS", "LOG", "SETUP", "DIAG", "HELP")) {
            val a = Robolectric.buildActivity(MainActivity::class.java, android.content.Intent().putExtra(MainActivity.EXTRA_PAGE, page)).setup().get()
            repeat(60) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
            val v = a.window.decorView
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(b))
            FileOutputStream(File(out, "page-${page.lowercase()}.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        run {
            val ask = com.kilon.agentvscreen.host.UserAsk.open.value.first { it.kind == com.kilon.agentvscreen.host.UserAsk.Kind.CHOOSE }
            val a = Robolectric.buildActivity(AskActivity::class.java, android.content.Intent().putExtra("id", ask.id)).setup().get()
            repeat(60) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
            val v = a.window.decorView
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(b))
            FileOutputStream(File(out, "ask-choose.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // 1.3 beta4: the hero when hidden (wakeable) and when agents are blocked.
        for ((name, blocked) in listOf("home-hidden" to false, "home-blocked" to true)) {
            com.kilon.agentvscreen.host.Prefs.setServiceOn(app, false)
            com.kilon.agentvscreen.host.Prefs.setBlocked(app, blocked)
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            repeat(60) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
            val v = a.window.decorView
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(b))
            FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        com.kilon.agentvscreen.host.Prefs.setBlocked(app, false)
        com.kilon.agentvscreen.host.Prefs.setServiceOn(app, true)
        for (night in listOf(false, true)) {
            if (night) org.robolectric.RuntimeEnvironment.setQualifiers("+night")
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            repeat(60) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
            val v = a.window.decorView
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(b))
            FileOutputStream(File(out, if (night) "home-dark.png" else "home.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // 1.4.0-beta4: the phone turned: stage and the rest side by side.
        org.robolectric.RuntimeEnvironment.setQualifiers("w840dp-h400dp-notnight")
        run {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            repeat(60) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
            val v = a.window.decorView
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(b))
            FileOutputStream(File(out, "home-wide.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

/** The floating window in each state, on a fake page (only when AVS_SHOTS is set). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w880dp-h420dp-xhdpi")
class OverlayShotTest {
    @Test fun states() {
        val out = System.getenv("AVS_SHOTS") ?: return
        animateLook = false
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.kilon.agentvscreen.host.AppLogger.init(app)
        com.kilon.agentvscreen.api.ApiConfig.init(app)
        val base = com.kilon.agentvscreen.core.BackgroundOperation.State(ownerSessionId = "default", displayId = 5, screenWidth = 1080, screenHeight = 2340)
        val states = listOf(
            "acting" to base.copy(operating = true, titleText = "在比价蓝牙耳机", titleSession = "default"),
            "standby" to base,
            "takeover" to base.copy(takeoverSessionId = "default"),
            "stopped" to base.copy(userStopped = true),
            "idle" to base.copy(idle = true, powerSaving = true),
        )
        com.kilon.agentvscreen.host.UserAsk.create(app, com.kilon.agentvscreen.host.UserAsk.Kind.CONFIRM, "shot", "副屏已经起来了，这次能正常看到这个提问框吗？", "")
        val a = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        val density = a.resources.displayMetrics.density
        Overlay.widthPx.floatValue = 150 * density
        a.setContent {
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFFE9EBF2)).padding(12.dp)) {
                states.forEach { (_, st) ->
                    OverlayContent(st) {
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White).padding(top = 48.dp, start = 10.dp, end = 10.dp)) {
                            repeat(9) { i ->
                                androidx.compose.material3.Text("搜索结果第 ${i + 1} 条：降噪耳机", fontSize = 9.sp, color = androidx.compose.ui.graphics.Color.DarkGray)
                                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(10.dp))
                            }
                        }
                    }
                }
                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    DockTab(Mode.STANDBY, true, 0)
                    DockTab(Mode.STANDBY, true, 1)
                    DockTab(Mode.STANDBY, true, 2)
                }
            }
        }
        repeat(20) { ShadowLooper.idleMainLooper(); Thread.sleep(30) }
        val v = a.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        FileOutputStream(File(out, "overlay.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** 1.4.0-beta4: the request card of the one screen page, in each moment (only when AVS_SHOTS is set). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w1200dp-h900dp-mdpi")
class RequestCardShotTest {
    @Test fun moments() {
        val out = System.getenv("AVS_SHOTS") ?: return
        animateLook = false; Glass.blurOk = false
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.kilon.agentvscreen.host.AppLogger.init(app)
        com.kilon.agentvscreen.api.ApiConfig.init(app)
        val teach = com.kilon.agentvscreen.host.UserAsk.create(app, com.kilon.agentvscreen.host.UserAsk.Kind.TEACH, "t", "新建一个 07:30 的闹钟", "")
        val point = com.kilon.agentvscreen.host.UserAsk.create(app, com.kilon.agentvscreen.host.UserAsk.Kind.POINT, "p", "你说的是哪一条闹钟？", "")
        val choose = com.kilon.agentvscreen.host.UserAsk.create(app, com.kilon.agentvscreen.host.UserAsk.Kind.CHOOSE, "c", "这周末你想先动哪块？", "", options = listOf("考研单词", "大创器件", "先歇着"))
        val a = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        a.setContent {
            AvsTheme {
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF07080D)).padding(12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    val w = androidx.compose.ui.Modifier.width(280.dp)
                    RequestCard(teach, "s", recording = false, prep = false, steps = 0, paused = false, takenOver = false, mark = MarkState(), send = { null }, modifier = w)
                    RequestCard(teach, "s", recording = false, prep = true, steps = 0, paused = false, takenOver = true, mark = MarkState(), send = { null }, modifier = w)
                    RequestCard(teach, "s", recording = true, prep = false, steps = 5, paused = false, takenOver = true, mark = MarkState(), send = { null }, modifier = w)
                    RequestCard(point, "s", recording = false, prep = false, steps = 0, paused = false, takenOver = false, mark = MarkState(), send = { null }, modifier = w)
                }
            }
        }
        repeat(20) { ShadowLooper.idleMainLooper(); Thread.sleep(30) }
        val v = a.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        FileOutputStream(File(out, "request-cards.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        @Suppress("UNUSED_VARIABLE") val unused = choose
    }
}
