package com.kilon.agentvscreen.ui

import androidx.compose.foundation.border

import androidx.compose.material.icons.filled.Check

import androidx.compose.material.icons.filled.Block

import androidx.compose.material.icons.filled.Bolt

import androidx.compose.material.icons.filled.Fullscreen

import androidx.compose.material.icons.filled.PowerSettingsNew

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.TaskLog
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.Readiness
import com.kilon.agentvscreen.host.ServiceControl
import com.kilon.agentvscreen.host.Wake
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 1.4.0-beta3 首页 = 现在 (rainbow approved the prototype). beta2 was a status card and two buttons over an empty half
 * screen. Now the page is built around the background screen, top to bottom in the order the user needs it:
 *  - the title is the state as a sentence ("正在天气里操作", "待命中", "需要你处理"), its line above says who and where;
 *  - questions waiting (only when there are any), sliding in from the top;
 *  - the stage: the live screen with a breathing frame, what the agent does now, take over / stop. It turns into a
 *    fix card when something is broken, and into a three-step guide before any agent was ever connected;
 *  - 接下来: bookings and watches as a row of small cards; 刚才: the last three tasks.
 * The service switch and "block agents" left the page: they sit behind the shield in the top bar.
 */

private var lastFlip = 0L

private enum class Home { ACTING, IDLE, BROKEN, FIRST }

@Composable
fun OverviewTab(bottom: Dp) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val s by BackgroundOperation.state.collectAsState()
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); beat++ } }
    var health by remember { mutableStateOf<List<Readiness.Item>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<TaskLog.Task>>(emptyList()) }
    var used by remember { mutableStateOf(true) }
    LaunchedEffect(beat, app.tick) {
        withContext(Dispatchers.IO) {
            tasks = TaskLog.recent(3); used = everConnected()
            health = runCatching { Readiness.now(ctx) }.getOrDefault(emptyList())
        }
    }
    var shield by remember { mutableStateOf(false) }
    val mode = modeOf(s)
    // What only the user can fix. A stop the user pressed is not "broken": the stage says it and offers to resume.
    // 1.4.0-beta4: a service the user turned off is their choice, not a fault: the stage says so (no fix card).
    val svc by ServiceControl.state.collectAsState()
    val off = svc == ServiceControl.State.BLOCKED
    val problems = health.filter { it.code != "user_stopped" && it.code != "service_off" && (it.blocking || it.code in setOf("no_accessibility", "accessibility_cannot_see_display", "frozen_in_background")) }
    val setupLeft = app.items.isNotEmpty() && !app.allOk
    val home = when {
        problems.isNotEmpty() || setupLeft || app.liveProblem != null -> if (!used && setupLeft) Home.FIRST else Home.BROKEN
        !used && s.displayId == null -> Home.FIRST
        s.displayId != null && !s.idle -> Home.ACTING
        else -> Home.IDLE
    }
    val lastApp = appOf(tasks.firstOrNull()?.pkg).first
    val title = when (home) {
        Home.ACTING -> s.agentTitle ?: (lastApp?.let { tr("正在${it}里操作", "Working in $it") } ?: Palette.word(mode))
        Home.IDLE -> if (off) tr("服务已关闭", "Service off") else if (s.userStopped) tr("你停止了 AI", "You stopped the AI") else tr("待命中", "Standing by")
        Home.BROKEN -> tr("需要你处理", "Needs you")
        Home.FIRST -> tr("接上你的 agent", "Connect your agent")
    }
    val eyebrow = when (home) {
        Home.ACTING -> Palette.word(mode) + (s.ownerSessionId?.let { " · " + com.kilon.agentvscreen.api.Agents.nameOfSession(it) } ?: "")
        Home.IDLE -> if (app.serviceOn) tr("agent 随时连得上", "Agents can connect any time") else ServiceControl.title(ctx)
        Home.BROKEN -> tr("agent 现在用不了副屏", "Agents cannot use the screen right now")
        Home.FIRST -> tr("还没有 agent 用过", "No agent has used it yet")
    }

    MiuixPage(title, back = false, bottom = bottom, lead = { StatusPill(eyebrow, mode, home) }, actions = {
        IconButton(onClick = { shield = true }, modifier = Modifier.padding(end = 12.dp)) { Icon(Icons.Filled.PowerSettingsNew, tr("服务", "Service")) }
    }) {
        NeedsGroup()

        val stage = @Composable { m: Modifier ->
            AnimatedContent(home, modifier = m, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) }, label = "stage") { h ->
                Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    when (h) {
                        Home.ACTING, Home.IDLE -> Stage(s, mode, tasks.firstOrNull(), off) { shield = true }
                        Home.BROKEN -> FixCard(problems, setupLeft)
                        Home.FIRST -> FirstUse(setupLeft)
                    }
                }
            }
        }
        val rest = @Composable {
            // 1.4.0-beta10: after the first call the guide is not over until some agent saved the note; one line of
            // what is left, until then.
            val agentsNow by com.kilon.agentvscreen.api.AgentLedger.agents.collectAsState()
            if (home != Home.FIRST && agentsNow.values.any { it.lastCallAt > 0 } && agentsNow.values.none { it.remembered }) {
                Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clickable { nav.tab(TAB_CONNECT) }, insideMargin = PaddingValues(16.dp)) {
                    Text(tr("下一步：让 agent 记下说明", "Next: have the agent save the note"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(tr("它连上了，但还没说把说明存在哪。不存的话，新对话里它可能不知道自己能用手机。点这里看是哪个 agent。",
                        "It connected but has not said where it saved the note; without it, new conversations may not know they can use the phone. Tap to see which agent."),
                        fontSize = 13.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (home != Home.FIRST) {
                NextUp(beat, app.tick)
                if (tasks.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallTitle(tr("刚才", "Just now"), modifier = Modifier.weight(1f))
                        LinkButton(onClick = { nav.tab(TAB_RECORDS) }) { Text(tr("全部记录", "All records")) }
                    }
                    Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                        tasks.forEach { t -> TaskRow(t) { nav.go(Route.Task(t.id)) } }
                    }
                }
            }
        }
        // 1.4.0-beta4: a wide window (the phone turned, a tablet, a foldable) puts the stage and the rest side by
        // side instead of stretching one column across the screen.
        if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                stage(Modifier.weight(1.1f))
                Column(Modifier.weight(1f)) { rest() }
            }
        } else { stage(Modifier); rest() }
    }
    ShieldSheet(shield) { shield = false }
}

/**
 * The stage: the screen with its breathing frame, what is happening, and the things the user may do.
 * 1.4.0-beta4: take over and stop are always there (greyed while nothing runs), so the card keeps its shape; a
 * landscape screen gets the card's full width with the words under it instead of a sliver beside them.
 */
@Composable
private fun Stage(s: BackgroundOperation.State, mode: Mode, last: TaskLog.Task?, off: Boolean = false, power: () -> Unit = {}) {
    val ctx = LocalContext.current
    val live = s.displayId != null && !s.idle
    val ratio = if (s.screenHeight > 0) s.screenWidth.toFloat() / s.screenHeight else 0.45f
    val (app, icon) = appOf(last?.takeIf { it.running }?.pkg)
    val open = { MainDisplay.start(ctx, Intent(ctx, FullscreenActivity::class.java)) }
    val picture = @Composable { m: Modifier ->
        Box(m.aspectRatio(ratio).glowBorder(if (live) mode else Mode.IDLE, 16.dp, pad = 5.dp).clickable(enabled = s.displayId != null) { open() }) {
            if (s.displayId != null) ScreenThumb(s.ownerSessionId ?: "default", Modifier.fillMaxSize(), 16.dp)
            else TaskFrame(last?.frames?.lastOrNull(), Modifier.fillMaxSize(), 16.dp)
            if (!live) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.35f)))
        }
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        if (ratio >= 1f) {
            picture(Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { StageWords(s, mode, last, live, app, icon, off) }
                Spacer(Modifier.width(8.dp))
                StageActions(s, live, open, off, power)
            }
        } else Row {
            picture(Modifier.width(112.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).height(112.dp / ratio), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StageWords(s, mode, last, live, app, icon, off)
                Spacer(Modifier.weight(1f))
                StageActions(s, live, open, off, power)
            }
        }
    }
}

@Composable
private fun StageWords(s: BackgroundOperation.State, mode: Mode, last: TaskLog.Task?, live: Boolean, app: String?, icon: androidx.compose.ui.graphics.ImageBitmap?, off: Boolean) {
    val c = MiuixTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (live && icon != null) { AppBadge(icon, 22.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (live) (app ?: tr("副屏", "Screen")) + " · ${s.screenWidth}×${s.screenHeight}" else tr("副屏在休息", "Screen resting"),
            fontSize = 13.sp, color = c.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    AnimatedContent(if (live) humanAction(LocalContext.current, s.action ?: Palette.word(mode)) else if (off) tr("服务关着，agent 连不上", "The service is off; agents cannot connect") else tr("agent 下次用时自动亮起", "Lights up when an agent next uses it"),
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "step") { t ->
        Text(t, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    val line = when {
        live && last?.running == true -> tr("第 ${last.commands} 步 · 已用 ${took(last)}", "Step ${last.commands} · ${took(last)}")
        last != null -> tr("上次：", "Last: ") + taskTitle(last, appOf(last.pkg).first) + " · " + taskOutcome(last).first
        else -> ""
    }
    if (line.isNotEmpty()) Text(line, fontSize = 12.sp, color = c.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun StageActions(s: BackgroundOperation.State, live: Boolean, open: () -> Unit, off: Boolean, power: () -> Unit) {
    val c = MiuixTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (off) { RoundAction(Icons.Filled.PowerSettingsNew, tr("打开服务", "Turn on"), c.primary) { power() }; return@Row }
        RoundAction(Icons.Filled.PanTool, tr("接管", "Take over"), c.primary, enabled = live && !s.userStopped) {
            s.ownerSessionId?.let { BackgroundOperation.beginTakeover(it) }; open()
        }
        if (s.userStopped) RoundAction(Icons.Filled.PlayArrow, tr("允许继续", "Resume"), c.primary) { BackgroundOperation.userResume() }
        else RoundAction(Icons.Filled.Stop, tr("停止", "Stop"), Palette.stopped, enabled = live) { BackgroundOperation.userStop() }
        if (s.displayId != null && !live) RoundAction(Icons.Filled.Fullscreen, tr("看一眼", "Look"), c.primary) { open() }
    }
}

/** "启动 com.android.settings" → "启动 设置": package names in a step read as the app's name. */
internal fun humanAction(ctx: android.content.Context, t: String): String = PKG.replace(t) { m ->
    runCatching { ctx.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(m.value, 0)).toString() } }.getOrDefault(m.value)
}
private val PKG = Regex("\\b[a-z][a-z0-9_]*(\\.[a-z0-9_]+){2,}\\b")

/** The line above the title, as a small pill with the state's colour (it used to float alone under the big title). */
@Composable
private fun StatusPill(text: String, mode: Mode, home: Home) {
    val c = MiuixTheme.colorScheme
    val dot = when (home) { Home.ACTING -> Palette.of(mode); Home.BROKEN -> Palette.stopped; else -> c.onSurfaceVariantSummary.copy(alpha = 0.6f) }
    Row(Modifier.padding(start = 20.dp).clip(RoundedCornerShape(50)).background(c.surfaceContainer).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = c.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Any agent ever reached this app: tasks, a call, a token used, a note remembered (survives updates; beta3 only looked at tasks). */
private fun everConnected(): Boolean = TaskLog.everUsed() ||
    com.kilon.agentvscreen.api.AgentLedger.snap.value.let { it.rememberedAt > 0 || it.lastLocalAt > 0 || it.lastRemoteAt > 0 } ||
    com.kilon.agentvscreen.api.Tokens.list.value.any { it.lastUsedAt > 0 }

/** A round button with its word under it; it shrinks a little under the finger. */
@Composable
private fun RoundAction(icon: ImageVector, label: String, tint0: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) tint0 else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val down by press.collectIsPressedAsState()
    val k by androidx.compose.animation.core.animateFloatAsState(if (down) 0.88f else 1f, label = "press")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(press, null, enabled = enabled) { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onClick() }.padding(4.dp)) {
        Box(Modifier.size(44.dp).graphicsLayer { scaleX = k; scaleY = k }
            .clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(label, fontSize = 11.5.sp, color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else tint)
    }
}

/** Something is broken: one sentence on what, one button that fixes it (or opens the place where it is fixed). */
@Composable
private fun FixCard(problems: List<Readiness.Item>, setupLeft: Boolean) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val host = LocalHost.current
    val first = problems.firstOrNull()
    val (title, body) = when {
        app.liveProblem != null && first == null && !setupLeft -> tr("后台运行受限", "Background running is restricted") to app.liveProblem!!
        first != null -> when (first.code) {
            "no_accessibility" -> tr("无障碍服务被关掉了", "Accessibility was turned off")
            "accessibility_cannot_see_display" -> tr("无障碍看不到副屏", "Accessibility cannot see the screen")
            "no_privilege" -> tr("Shizuku 尚未就绪", "Shizuku is not ready")
            "service_off" -> tr("服务关着", "The service is off")
            "frozen_in_background" -> tr("系统冻结了后台", "The system froze the app")
            else -> tr("有一项需要处理", "Something needs you")
        } to first.text
        else -> tr("还差 ${app.left} 项配置", "${app.left} setup steps left") to tr("配好之后 agent 才能用副屏", "Agents can use the screen once it is set up")
    }
    var busy by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Palette.stopped.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Text("!", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.stopped)
            }
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(body, fontSize = 14.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            PrimaryButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                when (first?.code) {
                    "no_accessibility", "accessibility_cannot_see_display" -> {
                        busy = true
                        Thread { val (ok, msg) = com.kilon.agentvscreen.host.Heal.accessibility(ctx, restart = first.code != "no_accessibility"); host.say(msg); if (ok) app.bump(); busy = false }.start()
                    }
                    "frozen_in_background" -> { val (_, msg) = com.kilon.agentvscreen.host.Heal.power(ctx); host.say(msg); app.bump() }
                    "remote_down", "not_foreground_service" -> {
                        busy = true
                        Thread { val (ok, msg) = if (first.code == "remote_down") com.kilon.agentvscreen.host.Heal.remote(ctx) else com.kilon.agentvscreen.host.Heal.foreground(ctx)
                            host.say(msg); if (ok) app.bump(); busy = false }.start()
                    }
                    "service_off" -> { ServiceControl.turnOn(ctx, "home fix"); app.serviceOn = true; app.bump() }
                    null -> if (app.liveProblem != null && !setupLeft) {
                        val opened = com.kilon.agentvscreen.host.Liveness.openBackgroundSettings(ctx)
                        host.say(tr("已打开“$opened”：允许自启动、关联启动和后台活动", "Opened \"$opened\": allow auto-start, start by other apps and background activity"))
                    } else nav.go(Route.Setup)
                    else -> nav.go(Route.Setup)
                }
            }) { Text(if (busy) tr("正在修复…", "Fixing…") else tr("修复", "Fix")) }
        }
    }
}

/** Before any agent ever connected: three steps, the current one lit; the stage takes over once one does. */
@Composable
private fun FirstUse(setupLeft: Boolean) {
    val nav = LocalNav.current
    val c = MiuixTheme.colorScheme
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("把你的 agent 接上这台手机", "Connect your agent to this phone"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(tr("接上之后，agent 在后台的副屏里操作应用，不占你正在用的屏幕。", "Once connected, the agent works in apps on a background screen, not on the one you are using."),
                fontSize = 14.sp, lineHeight = 21.sp, color = c.onSurfaceVariantSummary)
            // 1.4.0-beta10 (rainbow: is there a guide for new users?): the steps follow what has really happened, read
            // from the phone: permissions, an agent connected (its first call), the agent saved the note (remembered).
            val agents by com.kilon.agentvscreen.api.AgentLedger.agents.collectAsState()
            val connected = agents.values.any { it.lastCallAt > 0 }
            val saved = agents.values.any { it.remembered }
            val steps = listOf(
                tr("授权", "Permissions") to tr("Shizuku 和无障碍：让它能在后台开一块副屏", "Shizuku and accessibility: so it can open a background screen"),
                tr("接入一个 agent", "Connect an agent") to tr("起个名字，复制给它的话，发给它", "Name it, copy its message, send it"),
                tr("它第一次连上", "Its first call") to tr("agent 照着那段话装好后会自己连上", "The agent sets itself up from the message and calls in"),
                tr("它记下了说明", "It saved the note") to tr("以后的新对话里它也知道自己能用手机", "New conversations will know they can use the phone"),
            )
            val cur = when { setupLeft -> 0; !connected -> 1; !saved -> 3; else -> 4 }
            steps.forEachIndexed { i, (t, sub) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(when { i < cur -> Palette.takeover; i == cur -> c.primary; else -> c.surfaceContainerHigh }),
                        contentAlignment = Alignment.Center) {
                        Text(if (i < cur) "✓" else "${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (i <= cur) Color.White else c.onSurfaceVariantSummary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column { Text(t, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text(sub, fontSize = 13.sp, color = c.onSurfaceVariantSummary) }
                }
            }
            PrimaryButton(onClick = { nav.go(if (setupLeft) Route.Setup else Route.NewAgent) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (setupLeft) tr("去授权", "Grant permissions") else tr("接入新的 agent", "Connect a new agent"))
            }
        }
    }
}

/**
 * 1.4.0-beta11 (rainbow: notifications could not be tapped and the user did not know where agents' requests are
 * handled). Everything that waits on the user, from every agent, in one group at the top of the home page; every
 * notification opens this page (host.Needs). Each item names the agent it is for and offers the one tap that ends it.
 */
@Composable
private fun NeedsGroup() {
    val ctx = LocalContext.current
    val host = LocalHost.current
    val asks = pendingAsks()
    val lessonNow by com.kilon.agentvscreen.cmd.Teach.lesson.collectAsState()
    val review = (lessonNow as? com.kilon.agentvscreen.cmd.Lesson.Review)?.result
    var sureDiscard by remember(review) { mutableStateOf(false) }
    val givenAll by com.kilon.agentvscreen.core.VScreenOwnership.given.collectAsState()
    val given = givenAll.values.sortedBy { it.at }
    val s by BackgroundOperation.state.collectAsState()
    val refused by com.kilon.agentvscreen.host.Needs.waiting.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(refused.isNotEmpty()) { while (refused.isNotEmpty()) { delay(5_000); tick++ } }
    val queue = remember(refused, s.ownerSessionId, tick) { com.kilon.agentvscreen.host.Needs.waitingNow(owner = s.ownerSessionId) }
    val n = asks.size + (if (review != null) 1 else 0) + given.size + queue.size
    AnimatedVisibility(n > 0, enter = slideInVertically(tween(380)) { -it / 3 } + fadeIn(tween(300)) + expandVertically(tween(380)),
        exit = fadeOut(tween(220)) + shrinkVertically(tween(320))) {
        Column {
            SmallTitle(tr("等你处理 · $n", "Waiting for you · $n"))
            // Questions: the newest one as a full card; it leaves to the right when answered.
            AnimatedVisibility(asks.isNotEmpty(), enter = fadeIn(tween(300)) + expandVertically(tween(380)),
                exit = slideOutHorizontally(tween(280)) { it } + fadeOut(tween(220)) + shrinkVertically(tween(320, delayMillis = 160))) {
                asks.firstOrNull()?.let { a -> Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { HomeAskCard(a, asks.size) } }
            }
            review?.let { r ->
                NeedCard(ReviewWords.title(r), ReviewWords.who(r), tr("去保存", "Review and save"), { ReviewWords.open(ctx) },
                    if (sureDiscard) tr("确定丢弃？", "Discard for sure?") else tr("丢弃", "Discard")) { if (sureDiscard) ReviewWords.discard(ctx, r) else sureDiscard = true }
            }
            given.forEach { g ->
                val app = remember(g.pkg) { com.kilon.agentvscreen.core.VScreenOwnership.label(ctx, g.pkg) }
                val who = com.kilon.agentvscreen.api.Agents.nameOfSession(g.session)
                NeedCard(tr("$app 在你手上", "$app is with you"),
                    when (g.why) {
                        "user" -> tr("你在主屏打开了它；「$who」在等你交回，交回后从你离开的页面接着做", "You opened it on the main screen; \"$who\" waits for it and carries on from your page")
                        else -> tr("「$who」交给你的；用完点交回，它从你离开的页面接着做", "\"$who\" handed it to you; hand it back when done and it carries on from your page")
                    },
                    tr("交回", "Hand back"), {
                        Thread { val line = com.kilon.agentvscreen.core.VScreenOwnership.handBack(ctx.applicationContext, g.pkg, "app"); host.say(line) }.start()
                    },
                    tr("打开", "Open")) {
                    ctx.packageManager.getLaunchIntentForPackage(g.pkg)?.let { MainDisplay.start(ctx, it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            queue.forEach { w ->
                val who = com.kilon.agentvscreen.api.Agents.nameOfSession(w.session)
                val owner = s.ownerSessionId ?: w.owner
                val holder = com.kilon.agentvscreen.api.Agents.nameOfSession(owner)
                val left = com.kilon.agentvscreen.api.Turns.leftMs(owner)
                NeedCard(tr("「$who」也想用副屏", "\"$who\" wants the screen too"),
                    tr("「$holder」正在用。", "\"$holder\" is using it. ") + (left?.let { tr("它安静 ${(it + 59_999) / 60_000} 分钟内就会让出，「$who」会自己再试。", "It gives way within ${(it + 59_999) / 60_000} min of quiet; \"$who\" retries by itself.") }
                        ?: tr("它有命令正在执行，做完后会让出。", "It is running a command and gives way after.")),
                    if (left != null) tr("让「$who」先用", "Let \"$who\" go first") else null, { Thread { com.kilon.agentvscreen.api.Turns.finish(owner) }.start() })
            }
        }
    }
}

@Composable
private fun NeedCard(title: String, sub: String, main: String?, onMain: () -> Unit, second: String? = null, onSecond: () -> Unit = {}) {
    Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AskMark(22f)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, fontSize = 12.5.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (main != null || second != null) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            main?.let { PrimaryButton(onClick = onMain, modifier = Modifier.weight(1f)) { Text(it) } }
            second?.let { SoftButton(onClick = onSecond) { Text(it) } }
        }
    }
}

/** Bookings and watches still to come, as a row of small cards; nothing at all when there are none. */
@Composable
private fun NextUp(beat: Int, tick: Int) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var books by remember { mutableStateOf(emptyList<Wake.Entry>()) }
    LaunchedEffect(beat / 5, tick) { books = withContext(Dispatchers.IO) { runCatching { val now = System.currentTimeMillis(); Wake.list(ctx).filter { it.on && it.pending(now) != null } }.getOrDefault(emptyList()) } }
    val watches = remember(beat, tick) { VScreenRules.list().filter { !it.stopped } }
    if (books.isEmpty() && watches.isEmpty()) return
    SmallTitle(tr("接下来", "Coming up"))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        books.forEach { e ->
            val next = e.pending(System.currentTimeMillis())
            NextCard(tr("预约", "Booked"), next?.let { ServiceControl.hhmm(it) } ?: "--:--", e.spec.describe()) { nav.go(Route.Schedules) }
        }
        watches.forEach { l -> NextCard(tr("盯守", "Watching"), l.status, l.spec.name) { nav.tab(TAB_RECORDS) } }
    }
}

@Composable
private fun NextCard(kind: String, big: String, what: String, onClick: () -> Unit) {
    Column(Modifier.width(168.dp).clip(RoundedCornerShape(18.dp)).background(MiuixTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(kind, fontSize = 11.sp, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        Text(big, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(what, fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 1.4.0-beta4: the power button's sheet is the one place to choose how the service runs, as three plain choices with
 * the current one lit: always on / on demand / off. Before, it offered "block agents" and "turn the service on/off",
 * and while an agent had woken a hidden service it only offered "turn on": the notification could not be removed.
 * Choosing applies at once: on demand stops a woken service now, off stops it and nothing wakes it.
 */
@Composable
private fun ShieldSheet(show: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val host = LocalHost.current
    val st = remember(show, app.tick) { ServiceControl.now(ctx) }
    val awake = remember(show, app.tick) { HostService.isAlive }
    OverlayBottomSheet(show = show, title = tr("服务", "Service"), onDismissRequest = close) {
        Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(ServiceControl.title(ctx) + (ServiceControl.sub(ctx)?.let { " · $it" } ?: ""), fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 12.dp))
            fun pick(to: ServiceControl.State) {
                // 1.3.0: a flip back within 1.5 s is ignored (each flip restarts the Tailscale node).
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastFlip < 1_500L) { host.say(tr("稍等一下再切换", "Wait a moment before switching again")); return }
                lastFlip = now
                when (to) {
                    ServiceControl.State.ON -> ServiceControl.turnOn(ctx, "sheet")
                    ServiceControl.State.HIDDEN -> ServiceControl.hide(ctx, "sheet")
                    ServiceControl.State.BLOCKED -> ServiceControl.block(ctx, "sheet")
                }
                app.serviceOn = com.kilon.agentvscreen.host.Prefs.serviceOn(ctx); app.bump(); close()
            }
            ServiceChoice(Icons.Filled.PowerSettingsNew, tr("常驻", "Always on"),
                tr("通知栏里一直有它，本机和其它设备的 agent 随时连得上", "Stays in the notification shade; agents here and on other devices reach it any time"),
                st == ServiceControl.State.ON) { pick(ServiceControl.State.ON) }
            ServiceChoice(Icons.Filled.Bolt, tr("按需", "On demand"),
                if (st == ServiceControl.State.HIDDEN && awake) tr("现在被 agent 叫醒了，点这里马上收起", "An agent woke it; tap to put it away now")
                else tr("平时不在后台；本机 agent 或预约时间到了才叫醒，用完自己收起", "Not in the background; woken by an agent here or a booked time, then put away again"),
                st == ServiceControl.State.HIDDEN) { pick(ServiceControl.State.HIDDEN) }
            ServiceChoice(Icons.Filled.Block, tr("关闭", "Off"),
                tr("马上停下并退出通知栏，什么都叫不醒它，直到你回来选别的", "Stops now and leaves the shade; nothing wakes it until you choose again here"),
                st == ServiceControl.State.BLOCKED, danger = true) { pick(ServiceControl.State.BLOCKED) }
            HostService.server?.lastError?.let { e -> Note(e, Palette.stopped, Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            if (HostService.server?.lastError == null) HostService.server?.portNote?.let { n -> Note(n, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        }
    }
}

@Composable
private fun ServiceChoice(icon: ImageVector, title: String, sub: String, on: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val c = MiuixTheme.colorScheme
    val tint = if (danger) Palette.stopped else c.primary
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(if (on) tint.copy(alpha = 0.12f) else c.surfaceContainer)
        .border(1.5.dp, if (on) tint else Color.Transparent, RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (on) tint else c.onSurface)
            Text(sub, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.onSurfaceVariantSummary)
        }
        if (on) { Spacer(Modifier.width(8.dp)); Icon(Icons.Filled.Check, null, tint = tint, modifier = Modifier.size(20.dp)) }
    }
}

/** A row's current value, on the right before the arrow. */
@Composable
fun Value(t: String, color: Color = MiuixTheme.colorScheme.onSurfaceVariantActions) =
    Text(t, fontSize = 14.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp))

private class ThumbPool {
    val lock = Any()
    val slots = arrayOfNulls<Bitmap>(2)
    val dst = Rect()
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var next = 0
    var disposed = false

    fun render(src: Bitmap): ImageBitmap? = synchronized(lock) {
        if (disposed || src.isRecycled || src.width <= 0 || src.height <= 0) return null
        val tw = 240
        val th = (240f * src.height / src.width).toInt().coerceAtLeast(1)
        val idx = next
        var bmp = slots[idx]
        if (bmp == null || bmp.isRecycled || bmp.width != tw || bmp.height != th) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            slots[idx] = bmp
        }
        dst.set(0, 0, tw, th)
        val ok = runCatching { Canvas(bmp).drawBitmap(src, null, dst, paint); bmp.prepareToDraw(); true }.getOrDefault(false)
        if (!ok) return null
        next = 1 - idx
        bmp.asImageBitmap()
    }

    fun recycleAll() = synchronized(lock) {
        disposed = true
        for (i in slots.indices) {
            slots[i]?.recycle()
            slots[i] = null
        }
    }
}

/**
 * A small live picture of the screen from the agent's own frames. It does not use the viewer's preview stream (the
 * floating window's): turning that off when this page closes would stop the window's picture.
 * 1.4.1-beta1: scales off the main thread into a double-buffered pair of reusable Bitmaps, recycled on dispose.
 */
@Composable
fun ScreenThumb(session: String, modifier: Modifier, corner: Dp = 10.dp) {
    val st by BackgroundOperation.state.collectAsState()
    val display = remember(session, st.displayId) { BackgroundOperation.previewDisplayFor(session) }
    val v = display?.frameVersion?.collectAsState()?.value
    val pool = remember(display) { ThumbPool() }
    var img by remember(display) { mutableStateOf<ImageBitmap?>(null) }
    DisposableEffect(pool) {
        onDispose {
            img = null
            pool.recycleAll()
        }
    }
    LaunchedEffect(display, v, pool) {
        if (display == null) { img = null; return@LaunchedEffect }
        val nextImg = withContext(Dispatchers.Default) {
            display.latestFrame()?.takeIf { !it.isRecycled && it.width > 0 }?.let { pool.render(it) }
        }
        if (nextImg != null) img = nextImg
    }
    Box(modifier.clip(RoundedCornerShape(corner)).background(Palette.ink)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
