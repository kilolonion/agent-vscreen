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
 * 2.0.1-beta2 (rainbow: support Japanese, languages on an equal footing): texts live in one table, a column per
 * language ([Strings]); code calls tr(S.<key>, values…). Agents read Chinese or English only.
 */
object I18n {
    enum class Lang(val key: String) { AUTO("auto"), ZH("zh"), EN("en"), JA("ja") }

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
            Lang.EN, Lang.JA -> true
            Lang.ZH -> false
            Lang.AUTO -> !Locale.getDefault().language.equals("zh", ignoreCase = true)
        }
    val code: String get() = if (en) "en" else "zh"

    /** The table column for the current text language: a caller's forced reply language, else the app's. */
    val column: Int
        get() {
            forced.get()?.let { return if (it) Strings.EN else Strings.ZH }
            val code = when (cached) {
                Lang.AUTO -> Locale.getDefault().language.lowercase()
                else -> cached.key
            }
            val c = Strings.column(code)
            return if (c >= 0) c else Strings.EN
        }

    /** The app's own language is Japanese (never while a caller forced a reply language on this thread). */
    val ja: Boolean
        get() = forced.get() == null && when (cached) {
            Lang.JA -> true
            Lang.AUTO -> Locale.getDefault().language.equals("ja", ignoreCase = true)
            else -> false
        }
}

/**
 * The text [id] (an `S.` constant) in the current language, with `{0}`, `{1}` filled from [args] in order. A language
 * column left empty (agent-only rows) falls back to English.
 */
fun tr(id: Int, vararg args: Any?): String {
    val text = Strings.text(id, I18n.column)
    return if (args.isEmpty()) text else Strings.fill(text, args)
}

/**
 * A pair that lives as data rather than in the table: enum titles, command specs, release notes, and replies an agent
 * composes from pieces (agents read Chinese or English only). In another language the English is looked up in the
 * table, so a title that is also a table text still shows translated.
 */
fun trPair(zh: String, en: String): String = when (val c = I18n.column) {
    Strings.ZH -> zh
    Strings.EN -> en
    else -> Strings.byEnglish(en, c) ?: en
}

/** "3 步" / "3 steps", "1 step". */
fun steps(n: Int): String = if (n == 1) tr(S.i18n_one_step) else tr(S.i18n_n_steps, n)

/**
 * 2.0.1-beta2 (rainbow: languages on an equal footing, more to come): every text the app shows is one row of
 * resources/i18n/strings.tsv (key, scope, then one column per language), and code names it by its [S] constant.
 * Edit the table with tools/i18n/strings.py, which regenerates [S] and checks every column is filled.
 */
object Strings {
    const val ZH = 0
    const val EN = 1

    private class Table(val langs: List<String>, val rows: Array<Array<String>>, val english: Map<String, Int>)

    private val table: Table by lazy { load() }

    private fun load(): Table {
        val stream = Strings::class.java.classLoader?.getResourceAsStream("i18n/strings.tsv")
            ?: error("i18n/strings.tsv is missing from the app")
        val lines = stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        val head = lines.first().split('\t')
        val langs = head.drop(2)
        val rows = ArrayList<Array<String>>(lines.size)
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val cells = line.split('\t')
            rows += Array(langs.size) { c -> cells.getOrNull(c + 2).orEmpty().replace('⏎', '\n') }
        }
        val english = HashMap<String, Int>(rows.size * 2)
        rows.forEachIndexed { i, r -> if (r[EN].indexOf('{') < 0) english.putIfAbsent(r[EN], i) }
        return Table(langs, rows.toTypedArray(), english)
    }

    /** The column of a language code ("zh", "en", "ja"…), or -1 when the table has none. */
    fun column(code: String): Int = table.langs.indexOf(code)

    fun text(id: Int, col: Int): String {
        val row = table.rows[id]
        val t = row.getOrNull(col).orEmpty()
        return if (t.isNotEmpty() || col == ZH) t else row[EN]
    }

    fun byEnglish(en: String, col: Int): String? =
        table.english[en]?.let { table.rows[it].getOrNull(col) }?.takeIf { it.isNotEmpty() }

    fun fill(text: String, args: Array<out Any?>): String {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{') {
                var j = i + 1
                while (j < text.length && text[j].isDigit()) j++
                if (j > i + 1 && j < text.length && text[j] == '}') {
                    val n = text.substring(i + 1, j).toInt()
                    if (n < args.size) { sb.append(args[n]); i = j + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }
}
