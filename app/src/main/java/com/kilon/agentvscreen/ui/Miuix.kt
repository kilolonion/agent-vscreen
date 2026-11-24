package com.kilon.agentvscreen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 1.4: the app's own pieces on top of miuix, under names of their own (nothing here shadows a Material or miuix name):
 * three button weights (PrimaryButton, SoftButton, LinkButton) and the glass dialog. Pages call miuix directly for
 * everything else (Text, Icon, Switch, preference rows, Scaffold, TopAppBar, navigation). The floating window's own
 * screens (overlay, full screen, answer, teach review) keep Material 3, imported explicitly.
 */

/** The page's content layer, for glass that blurs what is behind it (API 33+; a translucent fill below that). */
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** The main action: filled violet. [color] for a coloured action (a banner's own button). */
@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color? = null, content: @Composable RowScope.() -> Unit) =
    top.yukonga.miuix.kmp.basic.Button(onClick, modifier, enabled,
        colors = if (color != null) ButtonDefaults.buttonColorsPrimary(color = color) else ButtonDefaults.buttonColorsPrimary(),
        content = content)

/** A second action: a quiet tinted pill. */
@Composable
fun SoftButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    top.yukonga.miuix.kmp.basic.Button(onClick, modifier, enabled,
        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer, contentColor = MiuixTheme.colorScheme.primary),
        content = content)

/** A text action (dialogs, small links): no fill, violet words; the same size as the other buttons so a row of them lines up. */
@Composable
fun LinkButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    top.yukonga.miuix.kmp.basic.Button(onClick, modifier, enabled,
        colors = ButtonDefaults.buttonColors(color = Color.Transparent, disabledColor = Color.Transparent, contentColor = MiuixTheme.colorScheme.primary),
        content = content)

/**
 * The app's dialog: a pane of violet-tinted frosted glass. Over a page that shares its [LocalBackdrop] the page behind
 * is blurred (API 33+); elsewhere, or on older Android, it is a translucent fill. Same slots as Material's AlertDialog.
 */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null,
) {
    val backdrop = LocalBackdrop.current
    val c = MiuixTheme.colorScheme
    val tint = c.surfaceContainer
    val body: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().then(
                if (backdrop != null) Modifier.drawBackdrop(backdrop, shape = { RoundedCornerShape(28.dp) },
                    effects = { blur(28.dp.toPx()) }, onDrawSurface = { drawRect(tint.copy(alpha = 0.72f)) })
                else Modifier
            ).padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides c.onSurface) {
                title?.let { t -> CompositionLocalProvider(LocalContentColor provides c.onSurface) { t() } }
                text?.let { t -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { t() } }
                DialogActionBar(dismissButton = dismissButton, confirmButton = confirmButton)
            }
        }
    }
    if (backdrop != null) top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = true, modifier = modifier, backgroundColor = if (Glass.blurOk) Color.Transparent else tint.copy(alpha = 0.94f),
        onDismissRequest = onDismissRequest, insideMargin = DpSize(0.dp, 0.dp), cornerRadius = 28.dp, content = body,
    ) else top.yukonga.miuix.kmp.window.WindowDialog(
        show = true, modifier = modifier, backgroundColor = tint.copy(alpha = 0.94f),
        onDismissRequest = onDismissRequest, insideMargin = DpSize(0.dp, 0.dp), cornerRadius = 28.dp, content = body,
    )
}

/** Places dialog buttons side-by-side when they fit on one line, or stacks them vertically without wrapping long labels (beta8 K.1). */
@Composable
private fun DialogActionBar(
    dismissButton: (@Composable () -> Unit)?,
    confirmButton: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        content = {
            dismissButton?.invoke()
            confirmButton()
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val maxW = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val totalW = placeables.sumOf { it.width } + gap * (placeables.size - 1).coerceAtLeast(0)
        if (placeables.size <= 1 || totalW <= maxW) {
            val h = placeables.maxOfOrNull { it.height } ?: 0
            layout(maxW, h) {
                var x = maxW
                for (p in placeables.asReversed()) {
                    x -= p.width
                    p.placeRelative(x, (h - p.height) / 2)
                    x -= gap
                }
            }
        } else {
            // Stack vertically (confirm on top, dismiss below) so long button text never wraps into 2 lines.
            val ordered = placeables.asReversed()
            val h = ordered.sumOf { it.height } + gap * (ordered.size - 1).coerceAtLeast(0)
            layout(maxW, h) {
                var y = 0
                for (p in ordered) {
                    p.placeRelative((maxW - p.width).coerceAtLeast(0), y)
                    y += p.height + gap
                }
            }
        }
    }
}

/** A dialog title in the app's style. */
@Composable
fun DialogTitle(t: String) = top.yukonga.miuix.kmp.basic.Text(t, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
