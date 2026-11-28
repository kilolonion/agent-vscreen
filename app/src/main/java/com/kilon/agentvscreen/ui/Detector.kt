package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.S
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.DetectorModels
import com.kilon.agentvscreen.core.VScreenDetector
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 2.0 + 2.0.0-beta8 (K.1): textless-element detector settings dialog.
 *
 * - Single toggle switch row for on/off (no confusing "关闭" button next to "完成").
 * - Two radio rows for model selection (built-in small model vs downloadable large model).
 * - Non-breaking space inside "（无文字\u00A0图标）".
 * - Raw model debug summary lives in Diagnostics, not here.
 */
@Composable
fun DetectorPreference() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    val inst = remember(tick) { DetectorModels.installed(ctx) }
    val accepted = remember(tick) { VScreenDetector.licenseAccepted(ctx) }
    val on = remember(tick) { VScreenDetector.active(ctx) }
    ArrowPreference(
        title = tr(S.detector_icon_without_text_detection),
        summary = tr(S.detector_finds_buttons_and_icons_without),
        endActions = {
            Value(
                when {
                    inst == null -> tr(S.detector_unavailable)
                    !accepted -> tr(S.detector_not_on)
                    on -> tr(S.connect_on)
                    else -> tr(S.automation_off)
                },
            )
        },
        onClick = { open = true },
    )
    if (!open) return
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var pct by remember { mutableIntStateOf(-1) }
    val small = MiuixTheme.colorScheme.onSurfaceVariantSummary

    fun downloadLarge() {
        if (busy || inst == null || !inst.builtIn) return
        busy = true; note = null; pct = 0
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val (offers, errors) = DetectorModels.fetchOffers()
                    if (offers.isEmpty()) error(tr(S.detector_no_download_source_answered) + errors.joinToString("; "))
                    val pick = offers.filter { it.sha256 != inst.sha256 }.maxByOrNull { it.bytes }
                        ?: error(tr(S.detector_no_larger_model_yet))
                    DetectorModels.install(ctx, pick) { got, total -> pct = (got * 100 / maxOf(1L, total)).toInt() }
                }.exceptionOrNull()
            }
            busy = false; pct = -1; tick++
            note = err?.let { tr(S.detector_not_installed) + (it.message ?: it.javaClass.simpleName) }
        }
    }

    GlassDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { DialogTitle(tr(S.detector_icon_without_text_detection)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr(S.detector_finds_tappable_items_without_words),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = small,
                )
                if (!accepted) {
                    Text(tr(S.detector_license) + VScreenDetector.licenseNote(), fontSize = 13.sp, lineHeight = 19.sp)
                } else if (inst != null) {
                    SwitchPreference(
                        checked = on,
                        onCheckedChange = { checked ->
                            if (!busy) {
                                VScreenDetector.setSwitchedOn(ctx, checked)
                                tick++
                            }
                        },
                        title = tr(S.detector_enable_icon_finder),
                    )
                    RadioButtonPreference(
                        title = tr(S.detector_built_in_small_model),
                        selected = inst.builtIn,
                        onClick = {
                            if (!busy && !inst.builtIn) {
                                DetectorModels.remove(ctx)
                                note = null
                                tick++
                            }
                        },
                    )
                    RadioButtonPreference(
                        title = tr(S.detector_large_model_40_mb_slower),
                        selected = !inst.builtIn,
                        onClick = { if (!busy && inst.builtIn) downloadLarge() },
                    )
                }
                if (pct >= 0) Text(tr(S.detector_downloading, pct), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                note?.let { Text(it, fontSize = 12.sp, color = Palette.stopped) }
            }
        },
        confirmButton = {
            if (!accepted && inst != null) {
                LinkButton(onClick = { VScreenDetector.acceptLicense(ctx); tick++ }) {
                    Text(tr(S.detector_i_understand_turn_on))
                }
            } else {
                LinkButton(enabled = !busy, onClick = { open = false }) {
                    Text(tr(S.connect_done))
                }
            }
        },
        dismissButton = if (!accepted && inst != null) {
            { LinkButton(enabled = !busy, onClick = { open = false }) { Text(tr(S.detector_not_now)) } }
        } else null,
    )
}
