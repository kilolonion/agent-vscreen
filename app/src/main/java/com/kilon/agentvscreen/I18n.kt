package com.kilon.agentvscreen

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * 1.1.7: one language for everything the app says, to the user (UI, overlay, notifications) and to the agent
 * (guide, memory note, command replies). AUTO follows the system: Chinese for zh-*, English otherwise.
 * Texts stay next to their use as tr("中文", "English") pairs, so a string and its translation never drift apart.
 */
object I18n {
    enum class Lang(val key: String) { AUTO("auto"), ZH("zh"), EN("en") }

    private var prefs: SharedPreferences? = null
    @Volatile private var cached: Lang = Lang.AUTO

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("agent_vscreen_api", Context.MODE_PRIVATE)
        cached = Lang.entries.firstOrNull { it.key == prefs?.getString("lang", null) } ?: Lang.AUTO
    }

    var choice: Lang
        get() = cached
        set(v) { cached = v; prefs?.edit()?.putString("lang", v.key)?.apply() }

    /** True when the app currently speaks English. */
    val en: Boolean
        get() = when (cached) {
            Lang.EN -> true
            Lang.ZH -> false
            Lang.AUTO -> !Locale.getDefault().language.equals("zh", ignoreCase = true)
        }

    val code: String get() = if (en) "en" else "zh"
}

/** Pick the text for the current language. */
fun tr(zh: String, en: String): String = if (I18n.en) en else zh
