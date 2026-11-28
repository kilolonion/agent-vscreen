package com.kilon.agentvscreen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 2.0.1-beta2 (rainbow: the home pill, the records chips and the day headers each started at their own indent).
 * Two left edges for every page, the ones the miuix bar and lists already use:
 * - [text]: the big title, group titles (SmallTitle), and pills, chips or status lines that sit between them;
 * - [card]: cards, picture strips and the rows inside a lazy list.
 * The top bar's navigation and action slots are already [slot] in, so a pill placed there pads by `text - slot`.
 */
object Edge {
    val text = 28.dp
    val card = 12.dp
    val slot = 16.dp
}

/**
 * 2.0.1-beta2 (rainbow: the state dot overlapped its title, and the second line slid under the dot). A status line:
 * the dot sits in a fixed lead column centred on the first text line, and every line of [content] starts after it.
 */
@Composable
fun DotLine(
    color: Color,
    modifier: Modifier = Modifier,
    dot: Dp = 8.dp,
    firstLine: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier) {
        Box(Modifier.width(dot + 10.dp).height(firstLine), contentAlignment = Alignment.CenterStart) { Dot(color, dot) }
        Column(Modifier.weight(1f), content = content)
    }
}

/**
 * 2.0.1-beta2 (rainbow: input text and hints were bigger than every row around them, and long hints wrapped into
 * three lines). One input field for every page: body-size text, a short one-line placeholder, an optional short
 * [title] above it; longer explanations belong in a note under it or behind a "?".
 */
@Composable
fun AvsField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    title: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val c = MiuixTheme.colorScheme
    Column(modifier) {
        if (title != null) Text(title, fontSize = 13.sp, color = c.onSurfaceVariantSummary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = singleLine, keyboardOptions = keyboardOptions,
            textStyle = TextStyle(fontSize = 15.sp, color = c.onSurface),
            cursorBrush = SolidColor(c.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.secondaryContainer).padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = c.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
    }
}

/**
 * 2.0.1-beta2 (rainbow: "最近" small and bold beside a large, thin, bare "全部记录" that did not read as a way in).
 * A group title with an optional way deeper on the same line: the same size, with a chevron and a full-height touch
 * area. Without [action] it is the plain group title.
 */
@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(end = Edge.text - 12.dp), verticalAlignment = Alignment.CenterVertically) {
        top.yukonga.miuix.kmp.basic.SmallTitle(title, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Row(
            Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAction).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(action, fontSize = 14.sp, color = c.primary)
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = c.primary, modifier = Modifier.size(18.dp))
        }
    }
}
