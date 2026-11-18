package com.kilon.agentvscreen.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** [vscreen] The live picture of the background screen (floating window and full screen). */
@Composable
fun VScreenLiveFrame(sessionId: String, modifier: Modifier = Modifier) {
    // Follow the session's CURRENT screen. Before, the frame only recomposed on the old screen's
    // frame counter: after a rebuild (orientation / restart) the window kept the released screen
    // and showed "loading" or a stale picture forever, while the agent worked on the new one.
    val st = BackgroundOperation.state.collectAsState().value
    var tick by remember { mutableIntStateOf(0) }
    val display = remember(sessionId, st.displayId, st.ownerSessionId, tick) { BackgroundOperation.previewDisplayFor(sessionId) }
    LaunchedEffect(sessionId, display) {
        while (true) {
            delay(1000)
            if (BackgroundOperation.previewDisplayFor(sessionId) !== display) { tick++; break }
        }
    }
    if (display == null) {
        Box(modifier.background(Color(0xFFF2F2F7)), contentAlignment = Alignment.Center) {
            Text(tr("当前没有副屏画面", "No background screen right now"), fontSize = 11.sp, color = Color.Gray)
        }
        return
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // 1.2.0-beta2: the viewer's own pictures, at the size shown and at a rate made for eyes (30 a second while the
    // user controls the screen, 20 while watching), instead of the agent's full-size frames at 8 a second.
    val fast = st.takeoverSessionId == sessionId
    val viewer = remember { Any() }
    DisposableEffect(display, fast, viewSize) {
        val long = maxOf(viewSize.width, viewSize.height)
        if (long > 0) display.setPreview(viewer, if (fast) 33L else 50L, long)
        onDispose { display.clearPreview(viewer) }
    }
    // Recompose on each new frame while this composable is on screen.
    val version = display.frameVersion.collectAsState().value
    val shown = display.previewVersion.collectAsState().value
    val frame = remember(display, version, shown) { display.previewFrame() ?: display.latestFrame() }
    // A frame that is one flat colour (app gone, page not drawn yet) reads as a broken white box. Checked when the
    // agent's frame changes (8 a second at most), not for every picture shown.
    val blank by produceState(false, display, version) {
        val sample = display.latestFrame()
        value = sample != null && withContext(Dispatchers.Default) { isBlank(sample) }
    }
    // v40: a blank frame with no app on the screen is the normal idle state, not a capture problem.
    val noApp = produceState(false, blank, display) {
        value = blank && withContext(Dispatchers.IO) {
            runCatching { VScreenShell.topActivity(display.displayId) == null }.getOrDefault(false)
        }
    }.value

    Box(
        modifier
            .background(Color.White)
            .onSizeChanged { viewSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null && !frame.isRecycled) {
            // Always draw the real frame (a white loading page with a little text is content, and
            // a coarse sample used to replace it with "blank"); only label a truly flat frame.
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            if (blank) Text(
                if (noApp) tr("副屏上没有运行的应用（空闲）", "No app is running on the background screen")
                else tr("副屏暂时没有画面", "The background screen is blank right now"),
                fontSize = 9.sp, color = Color.Gray,
                modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xCCFFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(tr("正在加载…", "Loading…"), fontSize = 9.sp, color = Color.Gray)
            }
        }
    }
}

/** True when a 47x47 sample of the frame is all (almost) the same colour. */
private fun isBlank(b: android.graphics.Bitmap): Boolean = runCatching {
    val first = b.getPixel(b.width / 2, b.height / 2)
    val row = IntArray(b.width)
    for (j in 1..47) {
        b.getPixels(row, 0, b.width, 0, b.height * j / 48, b.width, 1)
        for (i in 1..47) {
            val c = row[b.width * i / 48]
            val d = abs(((c shr 16) and 0xff) - ((first shr 16) and 0xff)) +
                abs(((c shr 8) and 0xff) - ((first shr 8) and 0xff)) + abs((c and 0xff) - (first and 0xff))
            if (d > 24) return@runCatching false
        }
    }
    true
}.getOrDefault(false)
