package com.kilon.agentvscreen.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.cmd.Teach
import com.kilon.agentvscreen.cmd.TeachLine
import com.kilon.agentvscreen.core.VScreenMap
import com.kilon.agentvscreen.host.UserAsk
import com.kilon.agentvscreen.steps
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.3 示教: what the user just showed, as the steps an agent will run. Untick a step that was a slip, choose whether
 * typed words are kept as they were or become a blank the agent fills ({1}), name it, save. Saved, it is a segment of
 * the app (`use 名字`); when an agent asked for the lesson, the agent gets the steps as the answer.
 * 1.4.0-beta11: the REVIEW pane of the one screen page (was its own page, TeachActivity).
 */
private fun defaultName(r: Teach.Result): String = (r.name ?: r.purpose?.take(16) ?: tr("示教", "routine") + "-" +
        java.text.SimpleDateFormat("HHmm", java.util.Locale.ROOT).format(java.util.Date())).trim().replace(Regex("\\s+"), "-")

@Composable
internal fun ReviewPane(r: Teach.Result, screenGone: Boolean, onLeave: () -> Unit, onAgain: () -> Unit) {
        var name by remember { mutableStateOf(defaultName(r)) }
        var keep by remember { mutableStateOf(r.lines.indices.toSet()) }
        var literal by remember { mutableStateOf(emptySet<Int>()) }
        // 1.4.0-beta11: after which steps the agent checks (marked while recording, or added here), and what it looks at.
        var checks by remember { mutableStateOf(r.lines.withIndex().mapNotNull { (i, l) -> l.check?.let { i to it } }.toMap()) }
        val (steps, params) = Teach.build(r.lines, keep, literal, checks)
        val ask = r.ask ?: r.askId?.let { UserAsk.byId(it) }
        val ctx = LocalContext.current
        var sure by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("保存刚才的示教", "Save this routine"), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    ask?.let { Text(tr("「${it.tokenName}」请求示教：${it.title}", "\"${it.tokenName}\" asked you to show: ${it.title}"), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary) }
                    Text((r.app ?: tr("没认出应用", "app not recognised")) + " · " + steps(r.lines.size),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 1.4.0-beta11: nobody waits for it (no request, or the request ended while recording): say where it goes.
                    if (ask == null) Text(tr("没有 agent 在等待这段示教。保存后归你所有，所有 agent 都可以 use。", "No agent is waiting for this routine. It is saved as your own, and every agent can use it."),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(name, { name = it.replace(Regex("\\s+"), "-").take(32) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(tr("名称（agent 用 use 名称 调用）", "Name (agents call it with use <name>)")) }, shape = RoundedCornerShape(18.dp))
                if (r.lines.isEmpty()) Text(tr("没有记到操作。", "Nothing was recorded."), fontSize = 15.sp)
                r.lines.forEachIndexed { i, l -> StepRow(i, l, i in keep, i in literal, params = paramOf(r.lines, keep, literal, i),
                    onKeep = { keep = if (i in keep) keep - i else keep + i }, onLiteral = { literal = if (i in literal) literal - i else literal + i },
                    check = checks[i], onCheck = { v -> checks = if (v == null) checks - i else checks + (i to v.take(80)) }) }
                if (keep.isNotEmpty()) Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), lift = false).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("agent 这样用", "How an agent runs it"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("act \"use $name" + (1..params).joinToString("") { " <$it>" } + "\"", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Row(Modifier.fillMaxWidth().glassSheet(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 1.4.0-beta11: discarding asks once more (it cannot be undone).
                TextButton(onClick = { if (sure) { discard(ctx, r); onLeave() } else sure = true }) {
                    Text(if (sure) tr("确定丢弃？", "Discard for sure?") else tr("丢弃", "Discard"), color = if (sure) Palette.stopped else androidx.compose.ui.graphics.Color.Unspecified)
                }
                Spacer(Modifier.weight(1f))
                // 1.4.0-beta11: 重录 stays on this page (the live pane); without the screen it is off and says why.
                OutlinedButton(enabled = !screenGone, onClick = onAgain, modifier = Modifier.height(48.dp)) { Text(if (screenGone) tr("副屏已关闭，无法重新录制", "Background screen closed; cannot record again") else tr("重新录制", "Record again")) }
                Button(enabled = name.isNotBlank() && keep.isNotEmpty() && r.app != null, onClick = { save(ctx, r, name, steps, params, keep); onLeave() }, modifier = Modifier.height(48.dp)) {
                    Text(tr("保存", "Save"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

/** The blank number this typing line gets, or 0. */
private fun paramOf(lines: List<TeachLine>, keep: Set<Int>, literal: Set<Int>, i: Int): Int {
        fun blank(j: Int) = (lines[j].kind == "type" || (lines[j].kind == "value" && lines[j].typed != null)) && j in keep && !(j in literal && !lines[j].secret)
        if (!blank(i)) return 0
        return (0..i).count { blank(it) }
    }

@Composable
private fun StepRow(i: Int, l: TeachLine, kept: Boolean, lit: Boolean, params: Int, onKeep: () -> Unit, onLiteral: () -> Unit,
                        check: String? = null, onCheck: (String?) -> Unit = {}) {
        val shown = when {
            l.kind == "value" && l.typed != null && !lit -> l.head + " until " + l.until.orEmpty().replaceFirst("text:${l.typed} in ", "text:{$params} in ")
            l.kind != "type" -> l.head + (l.until?.let { " until $it" } ?: "")
            lit && !l.secret -> "type ${l.typed}"
            else -> "type {$params}"
        }
        Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), lift = false).clickable(onClick = onKeep).padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top) {
            Checkbox(kept, { onKeep() })
            Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(Glass.accent), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(shown, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = if (kept) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                }
                Text(l.why, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                l.warn?.let { Text(it, fontSize = 12.sp, color = Palette.stopped) }
                // 1.4.0-beta11: a check after this step: the run stops here and the agent looks (or puts it right) first.
                if (kept) {
                    if (check == null) Text(tr("＋ 添加检查点（agent 复核）", "+ Add a checkpoint (agent verifies)"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onCheck(Teach.CHECK_DEFAULT) }.padding(vertical = 2.dp))
                    else Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        OutlinedTextField(check, { onCheck(it) }, Modifier.fillMaxWidth(), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            label = { Text(tr("检查点：", "Checkpoint: ")) }, shape = RoundedCornerShape(14.dp))
                        Text(tr("去掉这个检查", "Remove this check"), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onCheck(null) }.padding(vertical = 2.dp))
                    }
                }
                if (l.kind == "value" && l.typed != null && kept) Text(if (lit) tr("固定设置为「${l.typed}」 · 点按改为由 agent 填写", "Fixed to \"${l.typed}\" · tap to let the agent fill it in")
                    else tr("每次由 agent 填写（{$params}），本次为「${l.typed}」 · 点按改为固定此值", "Filled in by the agent each time ({$params}), \"${l.typed}\" this time · tap to fix this value"),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLiteral).padding(vertical = 2.dp))
                if (l.kind == "type" && kept) {
                    if (l.secret) Text(tr("不会保存密码，调用时由 agent 填写", "The password is not saved; the agent fills it in when calling"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    else Text(if (lit) tr("固定输入此文字 · 点按改为由 agent 填写", "Always types this text · tap to let the agent fill it in")
                        else tr("由 agent 每次填（{$params}）· 点这里改成固定输入原文", "Filled by the agent each time ({$params}) · tap to always type the original"),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLiteral).padding(vertical = 2.dp))
                }
            }
        }
    }

private fun save(ctx: android.content.Context, r: Teach.Result, name: String, steps: String, params: Int, keep: Set<Int>) {
    val app = r.app ?: return
    VScreenMap.saveSegment(app, name, steps, r.start, com.kilon.agentvscreen.api.Agents.ofSession(r.session), taught = true,
        // 1.4.0-beta14 (test R2: B6 still asked again): the request it answered is written down even when that request
        // ended or was forgotten meanwhile (expired, words said, app restarted): its text and name are the same key.
        asked = (r.ask ?: r.askId?.let { com.kilon.agentvscreen.host.UserAsk.byId(it) })?.key?.let { Teach.askedOf(it) }
            ?: r.purpose?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it:${r.name.orEmpty()}" })
    // 1.3.0: the lesson's contact sheet, kept with the segment (before the answer: the agent's reply attaches it).
    Teach.sheet(r, keep)?.let { VScreenMap.savePicture(app, name, it); it.recycle() } ?: VScreenMap.pictureFile(app, name)?.delete()
    if (r.askId != null || r.ask != null) {
        val lines = JSONArray(r.lines.withIndex().filter { it.index in keep }.map { it.value.why })
        Teach.deliver(ctx, r, JSONObject().put("kind", "teach").put("name", name).put("app", app).put("steps", steps)
            .put("params", params).put("described", lines))
    }
    Toast.makeText(ctx, tr("已存成「$name」", "Saved as \"$name\""), Toast.LENGTH_SHORT).show()
    Teach.clearResult(ctx)
}

private fun discard(ctx: android.content.Context, r: Teach.Result) {
    r.askId?.let { UserAsk.answer(ctx, it, false) }
    Teach.clearResult(ctx)
}
