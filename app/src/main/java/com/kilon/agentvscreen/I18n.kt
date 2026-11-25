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
        agentCached = if (prefs?.getString("agent_lang", null) == AGENT_AUTO) AGENT_AUTO else AGENT_EN
    }

    /**
     * 2.0.0-beta11 (rainbow: agent replies default English, a setting can change it): the language of what agents
     * read (replies, errors, guide, status, diag) when the caller names none. "en" (default) or "auto" (the app's
     * language). Screen text, OCR words and the user's own text are never translated. The UI follows [choice].
     */
    @Volatile private var agentCached: String = AGENT_EN
    const val AGENT_EN = "en"
    const val AGENT_AUTO = "auto"
    var agentLang: String
        get() = agentCached
        set(v) { agentCached = if (v == AGENT_AUTO) AGENT_AUTO else AGENT_EN; prefs?.edit()?.putString("agent_lang", agentCached)?.apply() }
    /** The reply language for an agent request that named none: "en", or null for the app's language. */
    fun agentDefault(): String? = if (agentCached == AGENT_AUTO) null else "en"

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

/** "3 步" / "3 steps", "1 step". */
fun steps(n: Int): String = tr("$n 步", if (n == 1) "1 step" else "$n steps")
