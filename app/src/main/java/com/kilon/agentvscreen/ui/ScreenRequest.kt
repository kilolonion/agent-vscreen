package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
import android.content.Context
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.cmd.Teach
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.host.UserAsk
import com.kilon.agentvscreen.tr
import org.json.JSONObject

/*
 * 1.4.0-beta4 (rainbow: "回复问题和直接全屏虚拟屏有的类似功能却有两套ui"). There is one screen page. Whatever an agent
 * wants from the user (a question, a place on the screen, a lesson) is a card on top of the live screen, and the card
 * changes with the moment; the user never goes to a separate page with a still picture.
 *  - point / area: marked on the live picture. "先滑过去" hands the user the screen to bring the target into view first;
 *    "回来标注" goes back to marking on what is there now (the answer says the screen was moved).
 *  - choose / confirm / text: answered in the card.
 *  - teach: 去示范 → 准备 (the user has the screen, nothing is written down) → 开始示范 → 撤掉上一步 / 暂停 / 完成.
 *  - "说一句" on every card: the user's own words go back instead (USER_FEEDBACK), for a wrong question or lesson.
 */

/** Marks on the live picture while answering point / area (picture coordinates; the page turns them into the screen's). */
internal class MarkState {
    var a by mutableStateOf<Offset?>(null)
    var b by mutableStateOf<Offset?>(null)
    /** The user brought the target into view first ("先滑过去"). */
    var moved by mutableStateOf(false)
    /** The user is moving the screen to find the target (marking resumes with "回来标注"). */
    var finding by mutableStateOf(false)
    var forId = -1
    fun reset(id: Int) { if (forId != id) { forId = id; a = null; b = null; moved = false; finding = false } }
}

internal fun feedback(ctx: Context, a: UserAsk.Ask, text: String) =
    UserAsk.answer(ctx, a.id, true, JSONObject().put("kind", "feedback").put("text", text.trim()))

/**
 * The card. [a]: the request (null: a lesson of the user's own). [recording], [prep]: the lesson's phase.
 * [send]: turns the marks into the reply (null while nothing is marked).
 */
@Composable
internal fun RequestCard(a: UserAsk.Ask?, session: String, recording: Boolean, prep: Boolean, steps: Int, paused: Boolean, takenOver: Boolean,
                         mark: MarkState, send: (() -> JSONObject?), modifier: Modifier = Modifier,
                         /** 1.4.0-beta7: fold into a ball the user can drag aside / move the card to the other edge (lost when the pages merged). */
                         onFold: (() -> Unit)? = null, onMove: (() -> Unit)? = null, atBottom: Boolean = false, startSaying: Boolean = false) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(true) }
    var saying by remember(a?.id, startSaying) { mutableStateOf(startSaying) }
    var said by remember(a?.id) { mutableStateOf("") }
    val shape = RoundedCornerShape(if (open) 22.dp else 50.dp)
    Column(
        modifier.fillMaxWidth().animateContentSize().eatTouches()
            .glassDark(shape).border(1.2.dp, Brush.linearGradient(listOf(Color(0xFF8B7CFF), Palette.asking)), shape)
            .padding(horizontal = 14.dp, vertical = if (open) 12.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val kind = a?.kind
        val head = when {
            recording -> tr(S.screen_request_recording, steps, com.kilon.agentvscreen.steps(steps)) + if (paused) tr(S.screen_request_paused) else ""
            prep -> tr(S.screen_request_getting_ready_go_to_the)
            a == null -> ""
            kind == UserAsk.Kind.TEACH -> tr(S.screen_request_requests_a_routine, a.tokenName)
            else -> AskWords.who(a)
        }
        Row(Modifier.clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            AskMark(18f)
            Spacer(Modifier.width(8.dp))
            Text(if (open) head else (a?.title ?: head), color = if (open) Color(0xCCFFFFFF) else Color.White, fontSize = if (open) 12.sp else 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Color(0x99FFFFFF), modifier = Modifier.size(18.dp))
            onMove?.let { mv ->
                Spacer(Modifier.width(6.dp))
                Icon(if (atBottom) Icons.Filled.VerticalAlignTop else Icons.Filled.VerticalAlignBottom, if (atBottom) tr(S.screen_request_move_up) else tr(S.screen_request_move_down),
                    tint = Color(0xCCFFFFFF), modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = mv).padding(5.dp))
            }
            onFold?.let { f ->
                Icon(Icons.Filled.Minimize, tr(S.screen_request_fold_into_a_button), tint = Color(0xCCFFFFFF), modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = f).padding(5.dp))
            }
        }
        if (!open) return@Column
        a?.let { Text(it.title, color = Color.White, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 5, overflow = TextOverflow.Ellipsis) }

        if (saying && a != null) {
            // "说一句": the user's words go back instead of the answer that was asked for.
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x22FFFFFF)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (said.isEmpty()) Text(tr(S.screen_request_e_g_that_s_the), color = Color(0x88FFFFFF), fontSize = 14.sp)
                BasicTextField(said, { said = it.take(300) }, Modifier.fillMaxWidth(), textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Palette.standby))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(tr(S.screen_request_send_to_the_agent), true, 1f, Modifier.weight(1f)) {
                    if (said.isBlank()) return@GlassPill
                    // 1.4.0-beta12: during a lesson (getting ready or recording) the words are a message to the agent;
                    // the lesson and its request go on. Before a lesson starts they answer the request instead.
                    if (recording || prep) {
                        if (UserAsk.say(ctx, a.id, said)) android.widget.Toast.makeText(ctx, tr(S.screen_request_sent_to_the_agent_teaching), android.widget.Toast.LENGTH_SHORT).show()
                        said = ""; saying = false
                        return@GlassPill
                    }
                    if (takenOver && kind == UserAsk.Kind.TEACH) BackgroundOperation.userResume()
                    feedback(ctx, a, said)
                }
                GlassPill(tr(S.app_picker_cancel), false, 1f) { saying = false }
            }
            return@Column
        }

        when {
            recording || prep -> {
                LessonStrip(session, 1f)
                if (a != null) Say { saying = true }
            }
            a == null -> {}
            kind == UserAsk.Kind.TEACH -> {
                Text(tr(S.screen_request_take_over_the_background_screen),
                    color = Color(0x99FFFFFF), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // DESIGN §4.6: one lesson at a time; another agent's request waits until this one is saved or dropped.
                    val busy = Teach.lesson.collectAsState().value !is com.kilon.agentvscreen.cmd.Lesson.None
                    GlassPill(if (busy) tr(S.screen_request_finish_the_current_routine_first) else tr(S.asks_start_teaching), !busy, 1f, Modifier.weight(1f)) { if (!busy) Teach.beginFor(a) }
                    GlassPill(tr(S.screen_request_skip), false, 1f) { UserAsk.answer(ctx, a.id, false) }
                }
                Say { saying = true }
            }
            kind == UserAsk.Kind.POINT || kind == UserAsk.Kind.AREA -> {
                mark.reset(a.id)
                val ready = send() != null
                Text(when {
                    mark.finding -> tr(S.screen_request_bring_the_target_into_view)
                    kind == UserAsk.Kind.POINT -> tr(S.screen_request_tap_the_target_on_the)
                    else -> tr(S.screen_request_drag_a_box_on_the)
                }, color = Color(0x99FFFFFF), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (mark.finding) GlassPill(tr(S.screen_request_return_to_mark), true, 1f, Modifier.weight(1f)) {
                        mark.finding = false; mark.moved = true; mark.a = null; mark.b = null
                        if (BackgroundOperation.state.value.takeoverSessionId != null) BackgroundOperation.userResume()
                    } else {
                        GlassPill(tr(S.ask_activity_send), ready, 1f, Modifier.weight(1f)) {
                            val r = send() ?: return@GlassPill
                            UserAsk.answer(ctx, a.id, true, r.put("moved", mark.moved))
                        }
                        GlassPill(tr(S.screen_request_move_there_first), false, 1f) {
                            mark.finding = true
                            BackgroundOperation.beginTakeover(session)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Say(Modifier.weight(1f)) { saying = true }
                    Skip { UserAsk.answer(ctx, a.id, false) }
                }
            }
            kind == UserAsk.Kind.CHOOSE -> {
                a.options.forEachIndexed { i, o ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Glass.darkChip).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(12.dp))
                        .clickable { AskWords.choose(ctx, a, i) }.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(Glass.accent), contentAlignment = Alignment.Center) {
                            Text(AskWords.letter(i), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(o, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Say(Modifier.weight(1f)) { saying = true }
                    Skip { UserAsk.answer(ctx, a.id, false) }
                }
            }
            kind == UserAsk.Kind.CONFIRM -> {
                if (a.detail.isNotBlank()) Text(a.detail, color = Color(0xB3FFFFFF), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(tr(S.ask_activity_allow), true, 1f, Modifier.weight(1f)) { UserAsk.answer(ctx, a.id, true) }
                    GlassPill(tr(S.ask_activity_deny), false, 1f, Modifier.weight(1f)) { UserAsk.answer(ctx, a.id, false) }
                }
                Say { saying = true }
            }
            else -> {
                // A line of text: the answer itself is free words, so it needs no separate "说一句".
                var text by remember(a.id) { mutableStateOf("") }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x22FFFFFF)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (text.isEmpty()) Text(tr(S.screen_request_write_an_answer), color = Color(0x88FFFFFF), fontSize = 14.sp)
                    BasicTextField(text, { text = it.take(500) }, Modifier.fillMaxWidth(), textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Palette.standby))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassPill(tr(S.ask_activity_send), text.isNotBlank(), 1f, Modifier.weight(1f)) {
                        if (text.isNotBlank()) UserAsk.answer(ctx, a.id, true, JSONObject().put("kind", "text").put("text", text.trim()))
                    }
                    Skip { UserAsk.answer(ctx, a.id, false) }
                }
            }
        }
    }
}

@Composable
private fun Say(modifier: Modifier = Modifier, onClick: () -> Unit) = Box(modifier) {
    Text(tr(S.screen_request_message_the_agent), color = Color(0xCCD9C8FF), fontSize = 12.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp))
}

@Composable
private fun Skip(onClick: () -> Unit) =
    Text(tr(S.ask_activity_decline), color = Color(0x99FFFFFF), fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp))

/**
 * 1.4.0-beta11 (DESIGN-SCREEN-LESSON-1.4 §4.3): the lesson's controls, the same in the floating window and on the
 * screen page: getting ready (开始示范 · 不教) and recording (撤销 · 暂停/继续 · 完成 · 让 agent 看一下). Done opens
 * the save pane: the only way a lesson leads to a page, and it is the user's own tap.
 */
@Composable
internal fun LessonStrip(session: String, k: Float, withHead: Boolean = false, onSay: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val lesson by Teach.lesson.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy((6 * k).dp)) {
        when (val l = lesson) {
            is com.kilon.agentvscreen.cmd.Lesson.Ready -> {
                val a = UserAsk.byId(l.askId)
                if (withHead) Text(tr(S.screen_request_teach, a?.tokenName ?: "agent", a?.title.orEmpty()),
                    color = Color.White, fontSize = (12.5f * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tr(S.screen_request_first_go_to_the_starting),
                    color = Color(0x99FFFFFF), fontSize = (11.5f * k).sp)
                Row(horizontalArrangement = Arrangement.spacedBy((6 * k).dp)) {
                    GlassPill(tr(S.screen_request_start_recording), true, k, Modifier.weight(2f)) { Teach.startPrepared(session) }
                    GlassPill(tr(S.screen_request_skip), false, k, Modifier.weight(1f)) { UserAsk.answer(ctx, l.askId, false); Teach.cancelPrep(); BackgroundOperation.userResume() }
                }
            }
            is com.kilon.agentvscreen.cmd.Lesson.Recording -> {
                if (withHead) Text(if (l.paused) tr(S.screen_request_paused_nothing_is_written_down) else tr(S.screen_request_recording, l.steps, com.kilon.agentvscreen.steps(l.steps)),
                    color = Color.White, fontSize = (12.5f * k).sp, fontWeight = FontWeight.SemiBold)
                Text(l.askEnded?.let { tr(S.screen_request_the_request_ended_the_recording, it) }
                    ?: if (l.paused) tr(S.screen_request_paused_nothing_is_written_down_2)
                    else tr(S.screen_request_operate_as_usual_every_step),
                    color = if (l.askEnded != null) Palette.asking else Color(0x99FFFFFF), fontSize = (11.5f * k).sp)
                // Small controls share one row; 完成 has a row of its own, so it is whole however narrow the window is.
                Row(horizontalArrangement = Arrangement.spacedBy((6 * k).dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!l.paused) GlassPill(tr(S.screen_request_undo), false, k, Modifier.weight(1f)) { Teach.undo() }
                    GlassPill(if (l.paused) tr(S.screen_request_resume) else tr(S.screen_request_pause), false, k, Modifier.weight(1f)) { Teach.setPaused(!l.paused) }
                }
                GlassPill(tr(S.connect_done), true, k, Modifier.fillMaxWidth()) { Teach.finish(ctx); Surface.openReview(ctx) }
                // 1.4.0-beta11 (rainbow): mark that the agent should look here before the lesson goes on (said better on the save page).
                if (!l.paused) GlassPill(tr(S.screen_request_agent_verifies_after_this_step), false, k, Modifier.fillMaxWidth()) {
                    Teach.markCheck(); android.widget.Toast.makeText(ctx, tr(S.screen_request_marked_the_agent_verifies_after), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            else -> {}
        }
        // 1.4.0-beta12: the floating window cannot type: 说一句 opens the screen page with the box open.
        if (onSay != null && lesson.askId != null && (lesson is com.kilon.agentvscreen.cmd.Lesson.Ready || lesson is com.kilon.agentvscreen.cmd.Lesson.Recording))
            Say(onClick = onSay)
    }
}
