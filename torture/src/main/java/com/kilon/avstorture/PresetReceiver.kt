package com.kilon.avstorture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Allows tools/eval scripts to preset (scene, seed) before `vscreen launch com.kilon.avstorture`
 * so the initial Activity launch opens directly in that scene without an extra post-launch jump.
 * Direct `am start --es scene ... --ei seed ...` extras always take precedence over this preset.
 */
class PresetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ed = prefs.edit()
        intent.getStringExtra(EXTRA_SCENE)?.let { ed.putString(EXTRA_SCENE, it) }
        if (intent.hasExtra(EXTRA_SEED)) ed.putInt(EXTRA_SEED, intent.getIntExtra(EXTRA_SEED, DEFAULT_SEED))
        ed.putBoolean(EXTRA_KEEP_CYCLING, intent.getBooleanExtra(EXTRA_KEEP_CYCLING, false))
        ed.putInt(EXTRA_INIT_VALUE, intent.getIntExtra(EXTRA_INIT_VALUE, -1))
        ed.putInt(EXTRA_TARGET_VALUE, intent.getIntExtra(EXTRA_TARGET_VALUE, -1))
        ed.putBoolean(EXTRA_OCR_ONLY, intent.getBooleanExtra(EXTRA_OCR_ONLY, false))
        ed.commit()
    }

    companion object {
        const val PREFS_NAME = "avst_preset"
        const val EXTRA_SCENE = "scene"
        const val EXTRA_SEED = "seed"
        const val EXTRA_KEEP_CYCLING = "keep_cycling"
        const val EXTRA_INIT_VALUE = "init_value"
        const val EXTRA_TARGET_VALUE = "target_value"
        const val EXTRA_OCR_ONLY = "ocr_only"
        const val DEFAULT_SCENE = "S01"
        const val DEFAULT_SEED = 7
    }
}
