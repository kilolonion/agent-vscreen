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

    /**
     * 1.2: a caller may ask for its own reply language (`lang` in the request). It holds for the thread that runs the
     * command; work handed to another thread keeps it through [carry]. The app's own UI is never affected.
     */
    private val forced = ThreadLocal<Boolean?>()

    /** "zh" / "en" → that language for [block] on this thread; anything else → the app's language. */
    fun <T> with(lang: String?, block: () -> T): T {
        val want = when (lang?.trim()?.lowercase()?.take(2)) { "zh" -> false; "en" -> true; else -> null }
        if (want == null) return block()
        val before = forced.get()
        forced.set(want)
        try { return block() } finally { if (before == null) forced.remove() else forced.set(before) }
    }

    /** A wrapper that runs work on another thread in this thread's reply language. */
    fun <T> carry(block: () -> T): () -> T {
        val f = forced.get() ?: return block
        return { val before = forced.get(); forced.set(f); try { block() } finally { if (before == null) forced.remove() else forced.set(before) } }
    }

    /** True when the app (or this thread's caller) currently speaks English. */
    val en: Boolean
        get() = forced.get() ?: when (cached) {
            Lang.EN -> true
            Lang.ZH -> false
            Lang.AUTO -> !Locale.getDefault().language.equals("zh", ignoreCase = true)
        }
    val code: String get() = if (en) "en" else "zh"
}

/** Pick the text for the current language. */
fun tr(zh: String, en: String): String = if (I18n.en) en else zh
