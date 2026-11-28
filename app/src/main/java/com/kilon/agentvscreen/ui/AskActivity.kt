package com.kilon.agentvscreen.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.host.UserAsk
import com.kilon.agentvscreen.tr
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 1.3: where the user answers an agent's question that is not a plain yes / no: tap a place on the picture of the
 * background screen, drag a box on it, pick an option, or write a line. The answer goes back to the waiting `ask`.
 * Points are sent in background-screen coordinates (the same ones act uses), worked out from where the finger
 * landed on the picture as drawn.
 *
 * 1.3 beta3: redrawn in the app's glass look: who asks and how long the question stays open at the top, the question
 * on its own card, the picture framed, options as lettered rows you pick and then send, and one bar at the bottom
 * (send on the right, decline on the left) in the same place for every kind.
 */
class AskActivity : PageActivity() {
    // 1.3.0-beta18: like the full screen page, this page takes the floating window away while it is in front (it
    // came back with its card over the draw-a-box picture) and gives it back when it closes after the answer.
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (MainDisplay.moved(this)) return
        val id = intent.getIntExtra("id", -1)
        val ask = UserAsk.byId(id)
        setContent { AvsTheme { Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            Box(Modifier.fillMaxSize().backdrop()) { if (ask == null || !ask.live) Gone() else Answer(ask) }
        } } }
    }

    @Composable
    private fun Gone() = Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        AskMark(48f, alpha = 0.5f)
        Text(tr("这个问题已经回答过或过期了", "This question was already answered or has expired"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Button(onClick = { back() }) { Text(tr("关闭", "Close")) }
    }

    private fun send(a: UserAsk.Ask, reply: JSONObject?) {
        UserAsk.answer(this, a.id, reply != null, reply)
        back()
    }

    @Composable
    private fun Answer(a: UserAsk.Ask) {
        val bmp = remember { a.picture?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) } }
        DisposableEffect(bmp) { onDispose { bmp?.recycle() } }
        var point by remember { mutableStateOf<Offset?>(null) }
        var boxA by remember { mutableStateOf<Offset?>(null) }
        var boxB by remember { mutableStateOf<Offset?>(null) }
        var view by remember { mutableStateOf(IntSize.Zero) }
        var text by remember { mutableStateOf("") }
        var picked by remember { mutableIntStateOf(-1) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(a.id) { while (true) { delay(1000); now = System.currentTimeMillis(); if (!a.live) { back(); break } } }
        // From the picture as drawn (fills the width, keeps the aspect) to the screen's own coordinates.
        fun toScreen(o: Offset): Pair<Int, Int> {
            val w = view.width.coerceAtLeast(1); val h = view.height.coerceAtLeast(1)
            return (o.x / w * a.screenW).roundToInt().coerceIn(0, a.screenW - 1) to (o.y / h * a.screenH).roundToInt().coerceIn(0, a.screenH - 1)
        }
        val ready = when (a.kind) {
            UserAsk.Kind.POINT -> point != null
            UserAsk.Kind.AREA -> boxA != null && boxB != null && boxA != boxB
            UserAsk.Kind.CHOOSE -> picked >= 0
            UserAsk.Kind.CONFIRM -> true
            else -> text.isNotBlank()
        }
        fun reply(): JSONObject = when (a.kind) {
            UserAsk.Kind.POINT -> { val (x, y) = toScreen(point!!); JSONObject().put("kind", a.kind.key).put("x", x).put("y", y) }
            UserAsk.Kind.AREA -> {
                val (x1, y1) = toScreen(boxA!!); val (x2, y2) = toScreen(boxB!!)
                JSONObject().put("kind", a.kind.key).put("x1", minOf(x1, x2)).put("y1", minOf(y1, y2)).put("x2", maxOf(x1, x2)).put("y2", maxOf(y1, y2))
            }
            UserAsk.Kind.CHOOSE -> JSONObject().put("kind", "choose").put("index", picked + 1).put("choice", a.options[picked])
            else -> JSONObject().put("kind", "text").put("text", text.trim())
        }

        val accent = Color(0xFFB57BFF)

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // Who asks, and how long the question stays open.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AskMark(30f)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(AskWords.who(a), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    @Suppress("UNUSED_EXPRESSION") now
                    Text(tr("会话 ${a.session} · 还剩 ${AskWords.left(a, now)}", "Session ${a.session} · ${AskWords.left(a, now)} left"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { back() }) { Icon(Icons.Filled.Close, tr("稍后回答", "Answer later")) }
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // The question.
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(a.title, fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
                    if (a.detail.isNotBlank()) Text(a.detail, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (a.kind) {
                    UserAsk.Kind.POINT, UserAsk.Kind.AREA -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (a.kind == UserAsk.Kind.POINT) tr("点击画面中的目标位置", "Tap the target on the screen")
                                else tr("在画面上拖出一个框", "Drag a box on the picture"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (point != null || boxA != null) TextButton(onClick = { point = null; boxA = null; boxB = null }) {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(tr("重新选择", "Reselect"))
                            }
                        }
                        if (bmp == null) Text(tr("无法获取画面", "Screen unavailable"), color = MaterialTheme.colorScheme.error)
                        else Box(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp).shadow(16.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                                .aspectRatio(bmp.width.toFloat() / bmp.height).onSizeChanged { view = it }
                                .pointerInput(a.kind) {
                                    if (a.kind == UserAsk.Kind.POINT) detectTapGestures { point = it }
                                    else detectDragGestures(onDragStart = { boxA = it; boxB = it }) { c, _ -> boxB = c.position }
                                },
                        ) {
                            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                            Canvas(Modifier.fillMaxSize()) {
                                point?.let { p ->
                                    drawCircle(Color(0x55000000), radius = 34f, center = p)
                                    drawCircle(accent, radius = 26f, center = p, style = Stroke(6f))
                                    drawCircle(Color.White, radius = 7f, center = p)
                                }
                                val s = boxA; val e = boxB
                                if (s != null && e != null) {
                                    val tl = Offset(minOf(s.x, e.x), minOf(s.y, e.y)); val sz = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y))
                                    drawRect(accent.copy(alpha = 0.22f), topLeft = tl, size = sz)
                                    drawRect(accent, topLeft = tl, size = sz, style = Stroke(5f))
                                }
                            }
                        }
                    }
                    UserAsk.Kind.CHOOSE -> a.options.forEachIndexed { i, o -> OptionRow(i, o, selected = picked == i) { picked = i } }
                    UserAsk.Kind.CONFIRM -> {}
                    else -> OutlinedTextField(text, { text = it.take(500) }, Modifier.fillMaxWidth(), minLines = 4,
                        placeholder = { Text(tr("写下你的回答", "Write your answer")) },
                        supportingText = { Text("${text.length}/500") }, shape = RoundedCornerShape(18.dp))
                }
            }

            // One bar for every kind: decline on the left, send on the right.
            Row(Modifier.fillMaxWidth().glassSheet(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { send(a, null) }) { Text(when (a.kind) {
                    UserAsk.Kind.CHOOSE -> tr("以上都不是 / 不回答", "None / decline")
                    UserAsk.Kind.CONFIRM -> tr("拒绝", "Deny")
                    else -> tr("不回答", "Decline")
                }) }
                Spacer(Modifier.weight(1f))
                Button(enabled = ready, onClick = { if (a.kind == UserAsk.Kind.CONFIRM) { UserAsk.answer(this@AskActivity, a.id, true, null); back() } else send(a, reply()) }, modifier = Modifier.height(48.dp)) {
                    Text(when (a.kind) {
                        UserAsk.Kind.POINT, UserAsk.Kind.AREA -> tr("选择此处", "Select here")
                        UserAsk.Kind.CONFIRM -> tr("允许", "Allow")
                        else -> tr("发送", "Send")
                    }, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
