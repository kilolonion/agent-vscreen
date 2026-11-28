package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.host.UserAsk
import com.kilon.agentvscreen.steps
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.delay
import org.json.JSONObject

/*
 * 1.3 beta3: one question card, drawn in two materials: dark glass over the background screen (floating window, full
 * screen) and light glass in the app. The same answers everywhere ([UserAsk.answer]): allow / deny and options right
 * on the card; a point, an area or a line of text on the answer page.
 */

/** The questions agents are waiting on that are still open (re-checked every second: they expire). */
@Composable
internal fun pendingAsks(): List<UserAsk.Ask> {
    val open by UserAsk.open.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(open.isNotEmpty()) { while (open.isNotEmpty()) { delay(1000); tick++ } }
    return remember(open, tick) { open.filter { it.live && it.id != UserAsk.heldId }.sortedByDescending { it.at } }
}

internal object AskWords {
    fun who(a: UserAsk.Ask) = if (a.kind == UserAsk.Kind.CONFIRM) tr(S.asks_needs_your_ok, a.tokenName) else tr(S.asks_asks_you, a.tokenName)
    fun go(k: UserAsk.Kind) = when (k) {
        UserAsk.Kind.POINT -> tr(S.asks_select_a_location)
        UserAsk.Kind.AREA -> tr(S.asks_select_an_area)
        UserAsk.Kind.TEACH -> tr(S.asks_start_teaching)
        else -> tr(S.asks_answer)
    }
    /** Time left at [now]. Callers pass a ticking `now` ([rememberNow]) so the countdown redraws; reading the clock here alone never did. */
    fun left(a: UserAsk.Ask, now: Long): String {
        val s = ((a.at + a.validMs - now) / 1000).coerceAtLeast(0)
        return "${s / 60}:${"%02d".format(s % 60)}"
    }
    fun letter(i: Int) = ('A' + i).toString()
    fun answer(ctx: Context, a: UserAsk.Ask, allow: Boolean, reply: JSONObject? = null) = UserAsk.answer(ctx, a.id, allow, reply)
    fun choose(ctx: Context, a: UserAsk.Ask, i: Int) = answer(ctx, a, true, JSONObject().put("kind", "choose").put("index", i + 1).put("choice", a.options[i]))
    fun openPage(ctx: Context, a: UserAsk.Ask) {
        // A lesson starts right on the tap (take over, record), whatever page opens after it.
        if (a.kind == UserAsk.Kind.TEACH && !com.kilon.agentvscreen.cmd.Teach.beginFor(a) && com.kilon.agentvscreen.cmd.Teach.lesson.value !is com.kilon.agentvscreen.cmd.Lesson.None)
            android.widget.Toast.makeText(ctx, tr(S.asks_finish_the_current_routine_first), android.widget.Toast.LENGTH_SHORT).show()
        MainDisplay.start(ctx, UserAsk.answerIntent(ctx, a.id))
    }
}

/** The orchid question mark: the one sign of "an agent is waiting for you", on the card, the tab and the app. */
@Composable
internal fun AskMark(size: Float, count: Int = 1, alpha: Float = 1f) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF8B7CFF).copy(alpha = alpha), Palette.asking.copy(alpha = alpha)))),
        contentAlignment = Alignment.Center) {
        Text(if (count > 1) "$count" else "?", color = Color.White, fontSize = (size * 0.6f).sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Dark glass, over the background screen. [k] scales with the floating window.
 */
@Composable
internal fun AskQuick(a: UserAsk.Ask, count: Int, k: Float, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape((18 * k).dp)
    Column(
        modifier.eatTouches().glassDark(shape).padding(horizontal = (12 * k).dp, vertical = (11 * k).dp),
        verticalArrangement = Arrangement.spacedBy((8 * k).dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AskMark(16 * k)
            Spacer(Modifier.width((6 * k).dp))
            Text(AskWords.who(a), color = Color(0xCCFFFFFF), fontSize = (10.5f * k).sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (count > 1) Text(tr(S.asks_more_count, count - 1), color = Color(0x99FFFFFF), fontSize = (10 * k).sp)
        }
        Text(a.title, color = Color.White, fontSize = (13.5f * k).sp, lineHeight = (18 * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (a.detail.isNotBlank()) Text(a.detail, color = Color(0xB3FFFFFF), fontSize = (10.5f * k).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        when (a.kind) {
            UserAsk.Kind.CONFIRM -> Row(horizontalArrangement = Arrangement.spacedBy((8 * k).dp)) {
                GlassPill(tr(S.ask_activity_allow), true, k, Modifier.weight(1f)) { AskWords.answer(ctx, a, true) }
                GlassPill(tr(S.ask_activity_deny), false, k, Modifier.weight(1f)) { AskWords.answer(ctx, a, false) }
            }
            UserAsk.Kind.CHOOSE -> Column(verticalArrangement = Arrangement.spacedBy((6 * k).dp)) {
                a.options.take(4).forEachIndexed { i, o ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape((12 * k).dp)).background(Glass.darkChip)
                            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape((12 * k).dp)).clickable { AskWords.choose(ctx, a, i) }
                            .padding(horizontal = (8 * k).dp, vertical = (7 * k).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size((18 * k).dp).clip(CircleShape).background(Glass.accent), contentAlignment = Alignment.Center) {
                            Text(AskWords.letter(i), color = Color.White, fontSize = (10 * k).sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width((8 * k).dp))
                        Text(o, color = Color.White, fontSize = (12 * k).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(tr(S.ask_activity_decline), color = Color(0x99FFFFFF), fontSize = (11 * k).sp,
                    modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(50)).clickable { AskWords.answer(ctx, a, false) }
                        .padding(horizontal = (8 * k).dp, vertical = (3 * k).dp))
            }
            // 1.3.0-beta16: one wide main button and a quiet "decline" under it: fits the smallest window (side by side,
            // "不回答" was cut to "不回答…").
            else -> Column(verticalArrangement = Arrangement.spacedBy((4 * k).dp)) {
                GlassPill(AskWords.go(a.kind), true, k, Modifier.fillMaxWidth()) { AskWords.openPage(ctx, a) }
                Text(tr(S.ask_activity_decline), color = Color(0x99FFFFFF), fontSize = (11 * k).sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(50)).clickable { AskWords.answer(ctx, a, false) }
                        .padding(horizontal = (8 * k).dp, vertical = (3 * k).dp))
            }
        }
    }
}

/** Light glass, at the top of the app's home page. */
@Composable
internal fun HomeAskCard(a: UserAsk.Ask, count: Int) {
    val ctx = LocalContext.current
    val now = rememberNow()
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp))
            .border(1.5.dp, Brush.linearGradient(listOf(Color(0xFF8B7CFF), Palette.asking)), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AskMark(26f)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(AskWords.who(a), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.primary)
                Text(tr(S.ask_activity_session_left, a.session, AskWords.left(a, now)) + if (count > 1) tr(S.asks_more, count - 1) else "",
                    fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Text(a.title, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
        if (a.detail.isNotBlank()) Text(a.detail, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        when (a.kind) {
            UserAsk.Kind.CONFIRM -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(onClick = { AskWords.answer(ctx, a, true) }, modifier = Modifier.weight(1f)) { Text(tr(S.ask_activity_allow)) }
                SoftButton(onClick = { AskWords.answer(ctx, a, false) }, modifier = Modifier.weight(1f)) { Text(tr(S.ask_activity_deny)) }
            }
            UserAsk.Kind.CHOOSE -> {
                a.options.forEachIndexed { i, o -> OptionRow(i, o) { AskWords.choose(ctx, a, i) } }
                LinkButton(onClick = { AskWords.answer(ctx, a, false) }, modifier = Modifier.align(Alignment.End)) { Text(tr(S.ask_activity_none_decline)) }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(onClick = { AskWords.openPage(ctx, a) }, modifier = Modifier.weight(1f)) { Text(AskWords.go(a.kind)) }
                LinkButton(onClick = { AskWords.answer(ctx, a, false) }) { Text(tr(S.ask_activity_decline)) }
            }
        }
    }
}

/** One option of a choice, as a row you tap: a lettered badge and the words. */
@Composable
internal fun OptionRow(i: Int, text: String, selected: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .border(1.dp, if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.dividerLine, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Glass.accent), contentAlignment = Alignment.Center) {
            Text(AskWords.letter(i), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}


/**
 * 1.4.0-beta10: a lesson recorded and not yet saved. It waits for the user (leaving the review page does not drop it);
 * this card brings the review page back, or discards it (the agent then hears "not shown").
 */
internal object ReviewWords {
    fun title(r: com.kilon.agentvscreen.cmd.Teach.Result) = tr(S.asks_recorded_waiting_to_be_saved, r.lines.size, steps(r.lines.size))
    fun who(r: com.kilon.agentvscreen.cmd.Teach.Result) = r.ask?.let { tr(S.asks_asked, it.tokenName, it.title) }
        ?: tr(S.asks_a_routine_you_recorded)
    fun open(ctx: Context) = Surface.openReview(ctx)
    fun discard(ctx: Context, r: com.kilon.agentvscreen.cmd.Teach.Result) {
        r.askId?.let { UserAsk.answer(ctx, it, false) }
        com.kilon.agentvscreen.cmd.Teach.clearResult(ctx)
    }
}

@Composable
internal fun ReviewQuick(r: com.kilon.agentvscreen.cmd.Teach.Result, k: Float, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var sure by remember(r) { mutableStateOf(false) }
    Column(modifier.eatTouches().glassDark(RoundedCornerShape((18 * k).dp)).padding(horizontal = (12 * k).dp, vertical = (11 * k).dp),
        verticalArrangement = Arrangement.spacedBy((8 * k).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AskMark(16 * k)
            Spacer(Modifier.width((6 * k).dp))
            Text(ReviewWords.who(r), color = Color(0xCCFFFFFF), fontSize = (10.5f * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(ReviewWords.title(r), color = Color.White, fontSize = (13.5f * k).sp, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy((4 * k).dp)) {
            GlassPill(tr(S.asks_review_and_save), true, k, Modifier.fillMaxWidth()) { ReviewWords.open(ctx) }
            Text(if (sure) tr(S.asks_discard_for_sure) else tr(S.asks_discard), color = if (sure) Palette.stopped else Color(0x99FFFFFF), fontSize = (11 * k).sp,
                modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(50)).clickable { if (sure) ReviewWords.discard(ctx, r) else sure = true }.padding(horizontal = (8 * k).dp, vertical = (3 * k).dp))
        }
    }
}
