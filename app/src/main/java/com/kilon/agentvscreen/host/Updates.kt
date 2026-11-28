package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
import android.content.Context
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * 1.3.0-beta19: check for a newer version on the project's GitHub releases (rainbow). By hand from the app's home
 * ("Check for updates"), and quietly at most once a day when the app is opened; a newer one shows on that row. Nothing
 * is downloaded or installed by itself: the user opens the release page / APK link. No data about the phone is sent
 * (a generic User-Agent, no token).
 */
object Updates {
    const val PAGE = "https://github.com/kilolonion/agent-vscreen/releases"
    private const val API = "https://api.github.com/repos/kilolonion/agent-vscreen/releases?per_page=15"
    private const val DAY_MS = 24 * 3600_000L

    class Release(val version: String, val name: String, val notes: String, val page: String, val apk: String?, val prerelease: Boolean)
    sealed class Outcome {
        class Newer(val r: Release) : Outcome()
        class Latest(val newest: String?) : Outcome()
        class Failed(val why: String) : Outcome()
    }

    /**
     * 1.4.2 (rainbow): which releases count. STABLE: only full releases (no "-beta", not marked pre-release on GitHub);
     * BETA: those and the pre-releases. Chosen in Settings; until chosen it follows this build (a beta build looks for
     * betas, a release build for releases).
     */
    enum class Channel { STABLE, BETA }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun channel(ctx: Context): Channel = prefs(ctx).getString("channel", null)?.let { runCatching { Channel.valueOf(it) }.getOrNull() }
        ?: if (BuildConfig.VERSION_NAME.contains('-')) Channel.BETA else Channel.STABLE

    fun setChannel(ctx: Context, c: Channel) {
        prefs(ctx).edit().putString("channel", c.name).putLong("checked_at", 0L).apply()
        newer.value = null
    }

    /** Pure: is [r] on [c]? */
    fun onChannel(r: Release, c: Channel): Boolean = c == Channel.BETA || (!r.prerelease && !r.version.contains('-'))

    /** Pure: the newest release on [c] that is newer than [current], else null. */
    fun pick(list: List<Release>, c: Channel, current: String): Release? =
        list.filter { onChannel(it, c) }.maxWithOrNull { x, y -> compare(x.version, y.version) }?.takeIf { compare(it.version, current) > 0 }

    /** The newest release seen that is newer than this build (null: none, or not checked yet). */
    val newer = MutableStateFlow<Release?>(null)
    val checking = MutableStateFlow(false)

    /** Version order: 1.3.0 > 1.3.0-beta19 > 1.3.0-beta2 > 1.2.9; a leading v is ignored. Pure. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String): Pair<List<Int>, Int> {
            val s = v.trim().removePrefix("v").removePrefix("V")
            val main = s.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val pre = s.substringAfter('-', "").let { if (it.isEmpty()) Int.MAX_VALUE else it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            return main to pre
        }
        val (ma, pa) = parts(a); val (mb, pb) = parts(b)
        for (i in 0 until maxOf(ma.size, mb.size)) { val x = ma.getOrElse(i) { 0 }; val y = mb.getOrElse(i) { 0 }; if (x != y) return x.compareTo(y) }
        return pa.compareTo(pb)
    }

    /** Releases from the GitHub API answer (drafts left out). Pure. */
    fun parse(json: String): List<Release> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("draft") }.map { o ->
            val assets = o.optJSONArray("assets") ?: JSONArray()
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
            Release(o.optString("tag_name").removePrefix("v"), o.optString("name"), o.optString("body"), o.optString("html_url").ifEmpty { PAGE }, apk, o.optBoolean("prerelease"))
        }
    }

    /** Ask GitHub now (network; call off the main thread). */
    fun check(ctx: Context): Outcome {
        checking.value = true
        try {
            val c = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Agent-VScreen")
            }
            val code = c.responseCode
            if (code != 200) return Outcome.Failed(tr(S.updates_github_answered, code) + if (code == 403) tr(S.updates_too_many_checks_try_again) else "")
            val list = parse(c.inputStream.bufferedReader().use { it.readText() })
            prefs(ctx).edit().putLong("checked_at", System.currentTimeMillis()).apply()
            val ch = channel(ctx)
            val best = pick(list, ch, BuildConfig.VERSION_NAME)
            return if (best != null) { newer.value = best; Outcome.Newer(best) }
            else { newer.value = null; Outcome.Latest(list.filter { onChannel(it, ch) }.maxWithOrNull { x, y -> compare(x.version, y.version) }?.version) }
        } catch (e: Exception) {
            return Outcome.Failed(tr(S.updates_could_not_reach_github, e.javaClass.simpleName))
        } finally { checking.value = false }
    }

    /** At most once a day, quietly (the app's home calls it when opened). */
    fun checkDaily(ctx: Context) {
        val at = prefs(ctx).getLong("checked_at", 0L)
        if (System.currentTimeMillis() - at < DAY_MS || checking.value) return
        Thread({ runCatching { check(ctx.applicationContext) } }, "vscreen-update-check").apply { isDaemon = true }.start()
    }
}
