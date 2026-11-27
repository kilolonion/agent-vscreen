package com.kilon.agentvscreen.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w320dp-h640dp-mdpi")
class TaskTitleBarTest {
    @Test fun longTaskTitleKeepsTheSameSingleLineHeightAndCanCollapse() {
        val prevAnimate = animateLook
        animateLook = false
        try {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val title = mutableStateOf("正在操作")
            lateinit var bar: TopAppBarState
            activity.setContent {
                AvsTheme {
                    val behavior = MiuixScrollBehavior()
                    bar = behavior.state
                    TaskTitleBar(title.value, "11:10 · agent", behavior, Color.White, {}, {})
                }
            }
            fun frames() = ShadowLooper.idleMainLooper(180, TimeUnit.MILLISECONDS)
            frames()
            val shortExpansion = bar.heightOffsetLimit
            assertTrue(shortExpansion < 0f)
            title.value = "这行标题刚好四十个字用来测试悬浮窗会不会换行或被截断效果如何到底"
            frames()
            assertEquals(shortExpansion, bar.heightOffsetLimit, 0f)
            bar.heightOffset = bar.heightOffsetLimit
            frames()
            assertEquals(1f, bar.collapsedFraction, 0f)
            assertEquals(shortExpansion, bar.heightOffsetLimit, 0f)
            bar.heightOffset = 0f
            frames()
            assertEquals(0f, bar.collapsedFraction, 0f)
        } finally {
            animateLook = prevAnimate
        }
    }
}
