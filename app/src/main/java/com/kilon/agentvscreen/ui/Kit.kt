package com.kilon.agentvscreen.ui

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.tr

/*
 * 1.3 beta3: the app's one design system. Everything is glass on a soft violet backdrop: cards in the app, the
 * question card on the floating window and the full screen, the folded tab. One accent family (the brand violet),
 * with the state colours (standby blue, takeover green, stopped red, asking orchid) only as small signals.
 * Text is short where it is shown; the long explanations live behind a "?" ([Help]).
 */

object Glass {
    /** Backdrop blur needs RuntimeShader (API 33) and hardware drawing; screenshot tests draw in software and turn it off. */
    @Volatile var blurOk: Boolean = android.os.Build.VERSION.SDK_INT >= 33

    /** Over the live background screen (floating window, full screen): dark, translucent, a light edge on top. */
    // 1.3.0-beta17: nearly opaque. Cards now float over anything (the home screen, a list): at 76–87% the words
    // behind showed through and mixed with the card's own.
    val darkFill = Brush.verticalGradient(listOf(Color(0xF2251F42), Color(0xF7120F22)))
    val darkEdge = Brush.verticalGradient(listOf(Color(0x73FFFFFF), Color(0x1AFFFFFF)))
    /** A button on dark glass that is not the main one. */
    val darkChip = Color(0x29FFFFFF)
    /**
     * The main action ON DARK GLASS over the live screen (floating window, full screen, answer sheet): brand violet into
     * the asking orchid. 1.4.0-beta15 (rainbow: the schedule page's purple was too bright, pages did not share one
     * design language): the app's own pages never use it; there the main action is [PrimaryButton] (the miuix theme
     * colour), second actions [SoftButton] / secondaryContainer, text actions [LinkButton].
     */
    val accent = Brush.horizontalGradient(listOf(Color(0xFF7B6CFF), Color(0xFFB57BFF)))
}

/** Dark glass for things that float over the background screen. */
fun Modifier.glassDark(shape: Shape): Modifier = this.clip(shape).background(Glass.darkFill).border(1.dp, Glass.darkEdge, shape)

/** Light glass (dark glass in dark mode) for the app's own cards: the miuix card colour, translucent over the backdrop. */
@Composable
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), lift: Boolean = true): Modifier {
    val dark = isSystemInDarkTheme()
    val c = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
    val fill = c.surfaceContainer.copy(alpha = if (dark) 0.78f else 0.86f)
    val edge = if (dark) Brush.verticalGradient(listOf(Color(0x26FFFFFF), Color(0x08FFFFFF)))
        else Brush.verticalGradient(listOf(Color(0xCCFFFFFF), Color(0x40C9C0F5)))
    return this
        .then(if (lift && !dark) Modifier.shadow(8.dp, shape, ambientColor = Color(0x1A5B4BDB), spotColor = Color(0x1A5B4BDB)) else Modifier)
        .clip(shape).background(fill).border(1.dp, edge, shape)
}

/**
 * 1.3.0-beta17: glass for what FLOATS over content (a dialog, a bar over a scrolling list). [glass] is for cards lying
 * on the soft backdrop; over text it let the text behind show through ("调整预约" over the schedule list). This one is
 * nearly opaque, tinted violet, with a deeper violet shadow so it still reads as a lifted pane of glass.
 */
@Composable
fun Modifier.glassSheet(shape: Shape = RoundedCornerShape(28.dp)): Modifier {
    val dark = isSystemInDarkTheme()
    val fill = if (dark) Brush.verticalGradient(listOf(Color(0xF7272B44), Color(0xFA1A1D2E)))
        else Brush.verticalGradient(listOf(Color(0xFAFCFBFF), Color(0xF7F3F0FF)))
    val edge = if (dark) Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x14FFFFFF)))
        else Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0x80C9C0F5)))
    return this.shadow(24.dp, shape, ambientColor = Color(0x405B4BDB), spotColor = Color(0x405B4BDB))
        .clip(shape).background(fill).border(1.dp, edge, shape)
}

/**
 * 1.3.0-beta17: this element is a hit target of its own. Compose gives a touch to the topmost sibling it lands on, so a
 * card or button bar drawn over the live picture takes the touch and the picture under it never sees it (never sent to
 * the background screen, never recorded in a lesson). Its own buttons still get their taps: nothing is consumed here.
 */
/**
 * 2.0.0-beta15: the current time, redrawn every [everyMs]. A countdown that only reads System.currentTimeMillis() is
 * drawn once and freezes (the beta13 test saw "还剩 9:59" for half a minute): read this instead.
 */
@Composable
fun rememberNow(everyMs: Long = 1000L): Long {
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(everyMs) { while (true) { kotlinx.coroutines.delay(everyMs); now = System.currentTimeMillis() } }
    return now
}

/** A one-line title too long for its room scrolls through in a loop instead of ending in "…"; one that fits stays still. */
@Composable
fun Modifier.scrollIfLong(): Modifier =
    if (shouldAnimateLook()) this.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 2000, velocity = 28.dp)
    else this

fun Modifier.eatTouches(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (awaitPointerEvent().changes.any { it.pressed }) Unit
    }
}

/** The app's backdrop: soft violet and cyan light behind the glass, so the glass reads as glass. */
@Composable
fun Modifier.backdrop(): Modifier {
    val dark = isSystemInDarkTheme()
    val base = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
    return this.background(base).drawBehind {
        val a = if (dark) 0.20f else 0.22f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF7B6CFF).copy(alpha = a), Color.Transparent), center = Offset(size.width * 0.1f, size.height * 0.04f), radius = size.width * 0.9f),
            radius = size.width * 0.9f, center = Offset(size.width * 0.1f, size.height * 0.04f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF22D3EE).copy(alpha = a * 0.7f), Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.18f), radius = size.width * 0.75f),
            radius = size.width * 0.75f, center = Offset(size.width * 0.95f, size.height * 0.18f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFD67BFF).copy(alpha = a * 0.6f), Color.Transparent), center = Offset(size.width * 0.3f, size.height * 0.75f), radius = size.width * 0.8f),
            radius = size.width * 0.8f, center = Offset(size.width * 0.3f, size.height * 0.75f))
    }
}

/** A small "?" that opens the explanation: the long words live here, not under every setting. */
@Composable
fun Help(title: String, body: String, tint: Color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantActions) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.size(32.dp).clip(CircleShape).clickable { open = true }, contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, tr("说明", "About this"), tint = tint, modifier = Modifier.size(18.dp))
    }
    if (open) GlassDialog(
        onDismissRequest = { open = false },
        title = { DialogTitle(title) },
        text = { Text(body, fontSize = 14.sp, lineHeight = 21.sp) },
        confirmButton = { LinkButton(onClick = { open = false }) { Text(tr("知道了", "Got it")) } },
    )
}

/** A switch row (miuix switch preference): title, a few words under it, the "?" when there is more to say. */
@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, help: String? = null, onChange: (Boolean) -> Unit) {
    top.yukonga.miuix.kmp.preference.SwitchPreference(
        checked = checked, onCheckedChange = onChange, title = title, summary = sub,
        endActions = { if (help != null) Help(title, help) },
        insideMargin = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    )
}

/** A short grey line. One line where it can be. */
@Composable
fun Note(t: String, color: Color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier: Modifier = Modifier) =
    Text(t, fontSize = 12.sp, lineHeight = 17.sp, color = color, modifier = modifier.fillMaxWidth())

/**
 * A pill button for dark glass: the main one in the accent gradient, the rest as quiet glass chips.
 * 1.4.0-beta11 (rainbow: 完成 and 让 agent 看一下 were cut in half on the narrow floating window): the label never
 * draws past the pill. It used to be unwrapped and allowed to overflow, so a row of pills wider than its card pushed
 * the last one out of view. Now the label shrinks to fit (down to 9 sp) and only then ends in "…".
 */
@Composable
fun GlassPill(label: String, primary: Boolean, k: Float = 1f, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(50)).then(if (primary) Modifier.background(Glass.accent) else Modifier.background(Glass.darkChip).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(50)))
            .clickable(onClick = onClick).padding(horizontal = (12 * k).dp, vertical = (7 * k).dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.text.BasicText(label,
            style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = (12.5f * k).sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = (9 * k).sp, maxFontSize = (12.5f * k).sp))
    }
}
