package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** An app with a launcher entry: label, package, icon. */
internal class LaunchableApp(val label: String, val pkg: String, val icon: ImageBitmap?)

/**
 * 1.4.2: choose an app to open on the background screen yourself (ManualScreen). Searchable by name or package;
 * the icons load off the main thread.
 */
@Composable
internal fun AppPickerDialog(onDismiss: () -> Unit, onPick: (LaunchableApp) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        // 1.4.2: the catalog is read once per process (AppCatalog); icons load per row as they show.
        apps = withContext(Dispatchers.IO) { com.kilon.agentvscreen.core.AppCatalog.launchable().map { LaunchableApp(it.label, it.pkg, null) } }
    }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(tr(S.app_picker_open_an_app_on_the)) },
        text = {
            Column {
                Text(tr(S.app_picker_the_app_runs_on_the),
                    style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(8.dp))
                AvsField(value = query, onValueChange = { query = it }, placeholder = tr(S.app_picker_search_name_or_package))
                Spacer(Modifier.height(8.dp))
                val list = apps
                if (list == null) Text(tr(S.app_picker_loading_apps), style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(vertical = 16.dp))
                else {
                    val q = query.trim().lowercase()
                    val shown = list.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
                    LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                        items(shown, key = { it.pkg }) { a ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(a) }.padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                val icon by androidx.compose.runtime.produceState<ImageBitmap?>(null, a.pkg) {
                                    value = withContext(Dispatchers.IO) { com.kilon.agentvscreen.core.AppCatalog.icon(a.pkg)?.asImageBitmap() }
                                }
                                icon?.let { Image(it, null, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))) } ?: Spacer(Modifier.size(36.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.label, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(a.pkg, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { LinkButton(onClick = onDismiss) { Text(tr(S.app_picker_cancel)) } },
    )
}
