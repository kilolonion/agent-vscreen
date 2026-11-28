package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.tr

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kilon.agentvscreen.core.BackgroundOperation

/** Who has the background screen right now, as the user sees it (drives colours, buttons and words). */
enum class Mode { ACTING, STANDBY, IDLE, TAKEOVER, STOPPED }

/** 1.4.0-beta2: read from ScreenPhase (the one answer to "what is the screen in"), plus whether an action is running now. */
fun modeOf(s: BackgroundOperation.State): Mode = when (com.kilon.agentvscreen.core.ScreenPhase.of(s, null)) {
    com.kilon.agentvscreen.core.ScreenPhase.STOPPED -> Mode.STOPPED
    com.kilon.agentvscreen.core.ScreenPhase.TAKEN_OVER -> Mode.TAKEOVER
    com.kilon.agentvscreen.core.ScreenPhase.IDLE, com.kilon.agentvscreen.core.ScreenPhase.PARKED -> Mode.IDLE
    else -> if (s.operating) Mode.ACTING else Mode.STANDBY
}

object Palette {
    val rainbow = listOf(
        Color(0xFFFF5F6D), Color(0xFFFFB347), Color(0xFFFFE66D), Color(0xFF4ADE80),
        Color(0xFF22D3EE), Color(0xFF6C7BFF), Color(0xFFC06CFF), Color(0xFFFF5F6D),
    )
    val standby = Color(0xFF7CC8FF)
    val idle = Color(0xFF94A3B8)
    val takeover = Color(0xFF34D399)
    /** An agent is waiting for the user's answer: orchid, in the brand's violet family (not standby blue, not the turning rainbow). */
    val asking = Color(0xFFD67BFF)
    val stopped = Color(0xFFFF5A5F)
    val brandA = Color(0xFF6C5CE7)
    /** 1.4: the seed of the whole miuix palette. */
    val brand = Color(0xFF7B6CFF)
    val brandB = Color(0xFF00C2FF)
    val ink = Color(0xFF0E1220)

    fun of(m: Mode): Color = when (m) {
        Mode.ACTING -> Color(0xFF8B7CFF); Mode.STANDBY -> standby; Mode.IDLE -> idle
        Mode.TAKEOVER -> takeover; Mode.STOPPED -> stopped
    }

    fun word(m: Mode): String = when (m) {
        Mode.ACTING -> tr("agent 操作中", "Agent working"); Mode.STANDBY -> tr("agent 待机", "Agent standby"); Mode.IDLE -> tr("空闲", "Idle")
        Mode.TAKEOVER -> tr("你正在操作", "You are in control"); Mode.STOPPED -> tr("已停止 agent", "Agent stopped")
    }
}

/** A sweep gradient turned by [deg] (the rainbow runs around the frame). */
private class TurningSweep(val colors: List<Color>, val deg: Float) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val cx = size.width / 2f; val cy = size.height / 2f
        return android.graphics.SweepGradient(cx, cy, colors.map { it.toArgb() }.toIntArray(), null).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setRotate(deg, cx, cy) })
        }
    }
}

/**
 * The breathing border around the background screen, drawn OUTSIDE the content (give the element [pad] of room):
 * a turning rainbow while the AI acts, a calm blue breath while it waits, green while the user drives, red when the
 * user stopped it, grey when idle. A soft glow of the same colours sits under the line.
 */
/** False only in layout snapshots (an infinite animation never lets a test clock settle). */
@Volatile var animateLook = true

/** Respect both [animateLook] and the system `animator_duration_scale = 0` setting (reduced motion / headless CI). */
@Composable
internal fun shouldAnimateLook(): Boolean {
    if (!animateLook) return false
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(ctx) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
}

@Composable
fun Modifier.glowBorder(mode: Mode, corner: Dp, width: Dp = 2.5.dp, pad: Dp = 6.dp): Modifier {
    val anim = shouldAnimateLook()
    val turnState: androidx.compose.runtime.State<Float>?
    val breathState: androidx.compose.runtime.State<Float>?
    if (anim) {
        val t = rememberInfiniteTransition(label = "glow")
        turnState = t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (mode == Mode.ACTING) 2400 else 12000, easing = LinearEasing)), label = "turn")
        breathState = t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (mode == Mode.ACTING) 900 else 1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    } else {
        turnState = null
        breathState = null
    }
    return this.drawBehind {
        val turn = turnState?.value ?: 30f
        val breath = breathState?.value ?: 0.8f
        val stroke = width.toPx()
        val r = corner.toPx()
        val brush: Brush = if (mode == Mode.ACTING) TurningSweep(Palette.rainbow, turn) else SolidColor(Palette.of(mode))
        val glowMax = pad.toPx()
        val strength = when (mode) { Mode.IDLE -> 0.10f; Mode.ACTING -> 0.30f; else -> 0.22f } * (0.55f + 0.45f * breath)
        for (i in 4 downTo 1) {
            val grow = glowMax * i / 4f
            drawRoundRect(
                brush = brush, topLeft = Offset(-grow / 2, -grow / 2), size = Size(size.width + grow, size.height + grow),
                cornerRadius = CornerRadius(r + grow / 2), alpha = strength / i, style = Stroke(stroke + grow),
            )
        }
        val lineAlpha = if (mode == Mode.ACTING) 1f else 0.65f + 0.35f * breath
        drawRoundRect(brush = brush, topLeft = Offset(-stroke / 2, -stroke / 2), size = Size(size.width + stroke, size.height + stroke),
            cornerRadius = CornerRadius(r + stroke / 2), alpha = lineAlpha, style = Stroke(stroke))
    }
}

/**
 * The app's colours. 1.4: the pages are built with miuix; its palette is seeded from the brand violet (tonal spot,
 * light and dark follow the system), so every surface, switch and button stays in the violet family. Material 3 is
 * kept underneath with the same colours for the few pieces still drawn with it (the floating window's pages).
 */
@Composable
fun AvsTheme(content: @Composable () -> Unit) {
    val controller = androidx.compose.runtime.remember {
        top.yukonga.miuix.kmp.theme.ThemeController(
            top.yukonga.miuix.kmp.theme.ColorSchemeMode.MonetSystem,
            keyColor = Palette.brand,
            paletteStyle = top.yukonga.miuix.kmp.theme.ThemePaletteStyle.TonalSpot,
        )
    }
    top.yukonga.miuix.kmp.theme.MiuixTheme(controller = controller) {
        val c = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
        val dark = isSystemInDarkTheme()
        val base = if (dark) darkColorScheme() else lightColorScheme()
        val scheme: ColorScheme = base.copy(
            primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
            secondary = c.secondary, onSecondary = c.onSecondary, secondaryContainer = c.secondaryContainer, onSecondaryContainer = c.onSecondaryContainer,
            tertiary = Palette.takeover, background = c.background, onBackground = c.onBackground,
            surface = c.surface, onSurface = c.onSurface, surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.onSurfaceVariantSummary,
            surfaceContainer = c.surfaceContainer, surfaceContainerHigh = c.surfaceContainerHigh, surfaceContainerHighest = c.surfaceContainerHighest,
            surfaceContainerLow = c.surface, outline = c.outline, outlineVariant = c.dividerLine, error = c.error, onError = c.onError,
        )
        // Squircle corners draw with a RuntimeShader, like the blur: off where hardware shaders are not available.
        androidx.compose.runtime.CompositionLocalProvider(top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled provides Glass.blurOk) {
            MaterialTheme(colorScheme = scheme, content = content)
        }
    }
}
