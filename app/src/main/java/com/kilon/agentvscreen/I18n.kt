package com.kilon.agentvscreen

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * 1.1.7: one language for everything the app says, to the user (UI, overlay, notifications) and to the agent
 * (guide, memory note, command replies). AUTO follows the system: Chinese for zh-*, Japanese for ja-*, English
 * otherwise. Texts stay next to their use as tr("中文", "English") pairs, so a string and its translation never drift
 * apart.
 *
 * 2.0.1-beta2 (rainbow: support Japanese): Japanese is English underneath (agents, logs and anything untranslated
 * read English) with the user-facing English looked up in assets/i18n/ja.json ([Ja]); see tools/i18n.
 */
object I18n {
    enum class Lang(val key: String) { AUTO("auto"), ZH("zh"), EN("en"), JA("ja") }

    private var prefs: SharedPreferences? = null
    @Volatile private var cached: Lang = Lang.AUTO

    fun init(ctx: Context) {
        Ja.init(ctx)
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
            Lang.EN, Lang.JA -> true
            Lang.ZH -> false
            Lang.AUTO -> !Locale.getDefault().language.equals("zh", ignoreCase = true)
        }
    val code: String get() = if (en) "en" else "zh"

    /** The app's own language is Japanese (never while a caller forced a reply language on this thread). */
    val ja: Boolean
        get() = forced.get() == null && when (cached) {
            Lang.JA -> true
            Lang.AUTO -> Locale.getDefault().language.equals("ja", ignoreCase = true)
            else -> false
        }
}

/** Pick the text for the current language. */
fun tr(zh: String, en: String): String = if (!I18n.en) zh else if (I18n.ja) Ja.of(en) else en

/** "3 步" / "3 steps", "1 step". */
fun steps(n: Int): String = tr("$n 步", if (n == 1) "1 step" else "$n steps")

/**
 * 2.0.1-beta2: the Japanese table, keyed by the English text. Entries are exact texts, or templates whose `{0}`, `{1}`
 * stand for the values the Kotlin string put in (in its order); the Japanese side may reorder them. A text with no
 * entry stays English. Built from the tr() pairs by tools/i18n/build_ja.py.
 */
object Ja {
    private class Tpl(val parts: List<String>, val ja: String, val regex: Regex)
    @Volatile private var exact: Map<String, String> = emptyMap()
    @Volatile private var tpls: List<Tpl> = emptyList()
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 2048
    }
    private val HOLE = Regex("\\{(\\d+)\\}")

    fun init(ctx: Context) {
        runCatching {
            val raw = ctx.assets.open("i18n/ja.json").bufferedReader().use { it.readText() }
            load(org.json.JSONObject(raw))
        }
    }

    internal fun load(o: org.json.JSONObject) {
        val ex = HashMap<String, String>()
        o.optJSONObject("exact")?.let { e -> e.keys().forEach { k -> ex[k] = e.getString(k) } }
        val ts = ArrayList<Tpl>()
        o.optJSONArray("tpl")?.let { a ->
            for (i in 0 until a.length()) {
                val pair = a.getJSONArray(i)
                val en = pair.getString(0)
                val parts = HOLE.split(en)
                if (parts.sumOf { it.length } < 2) continue
                val rx = Regex(parts.joinToString("(.*?)") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL)
                ts += Tpl(parts, pair.getString(1), rx)
            }
        }
        exact = ex; tpls = ts.sortedByDescending { t -> t.parts.sumOf { it.length } }
        synchronized(cache) { cache.clear() }
    }

    /** [en] in Japanese, or [en] itself when the table has no entry for it. */
    fun of(en: String): String {
        exact[en]?.let { return it }
        if (tpls.isEmpty()) return en
        synchronized(cache) { cache[en] }?.let { return it }
        val out = tpls.firstNotNullOfOrNull { t ->
            if (!en.startsWith(t.parts.first()) || !en.endsWith(t.parts.last())) return@firstNotNullOfOrNull null
            val m = t.regex.matchEntire(en) ?: return@firstNotNullOfOrNull null
            HOLE.replace(t.ja) { h -> m.groupValues.getOrNull(h.groupValues[1].toInt() + 1) ?: "" }
        } ?: en
        synchronized(cache) { cache[en] = out }
        return out
    }
}
