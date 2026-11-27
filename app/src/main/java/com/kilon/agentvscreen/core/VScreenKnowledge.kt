package com.kilon.agentvscreen.core

import android.content.Context

/**
 * [vscreen] One path for app know-how, whichever memory tool the agent reaches for.
 *
 * Minis has two long-term stores: the main memory (memory_write / memory_get) and the per-app notes of the
 * background screen ([VScreenMap]: notes, saved segments, profile), shown when the app is launched. This object
 * makes both ends meet: the prompt carries a short index of apps with notes; memory_write entries that are clearly
 * app operating know-how are also filed as that app's note; memory_get also searches the notes.
 */
object VScreenKnowledge {
    private class Touch(val pkg: String, val atMs: Long)
    private val lastApps = HashMap<String, Touch>()
    private val labels = HashMap<String, String?>()
    private val journeys = HashMap<String, String>()

    private fun ctx(): Context? = BackgroundOperation.appContextOrNull()

    // ── the app a session works on ───────────────────────────────────

    fun touch(sid: String?, pkg: String?) {
        if (sid.isNullOrEmpty() || pkg.isNullOrEmpty() || pkg == ctx()?.packageName) return
        synchronized(lastApps) { lastApps[sid] = Touch(pkg, System.currentTimeMillis()) }
    }

    /** The app [sid] launched: pages of other packages it leads to still belong to it (its notes, its pause steps). */
    fun startJourney(sid: String?, pkg: String?) {
        if (sid.isNullOrEmpty() || pkg.isNullOrEmpty() || pkg == ctx()?.packageName) return
        synchronized(journeys) { journeys[sid] = pkg }
        touch(sid, pkg)
    }
    fun endJourney(sid: String?) { if (sid != null) synchronized(journeys) { journeys.remove(sid) } }
    fun journey(sid: String?): String? = sid?.let { s -> synchronized(journeys) { journeys[s] } }

    /** The app [sid] last worked on within [withinMs]. */
    fun lastApp(sid: String?, withinMs: Long = 6 * 3600_000L): String? = sid?.let { s ->
        synchronized(lastApps) { lastApps[s] }?.takeIf { System.currentTimeMillis() - it.atMs <= withinMs }?.pkg
    }

    // ── names ────────────────────────────────────────────────────────

    fun label(pkg: String): String? = synchronized(labels) {
        if (labels.containsKey(pkg)) return labels[pkg]
        val l = ctx()?.packageManager?.let { pm ->
            runCatching { com.kilon.agentvscreen.cmd.ScreenText.clean(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(), maxLen = 64) }.getOrNull()
        }?.takeIf { it.isNotEmpty() && it != pkg }
        labels[pkg] = l
        l
    }

    fun knownPackages(): Set<String> = runCatching { VScreenMap.packages().toCollection(LinkedHashSet()) }.getOrDefault(emptySet())

    /** A package name, or an app name the user would say (美团, Mini Metro). Null when nothing fits. */
    /**
     * 1.3 beta2: the one way an app is found by what the agent calls it, used by both `launch` and `apps`. Before they
     * matched differently: `apps settings` found 设置 by its package name, `launch Settings` said APP_NOT_FOUND (on a
     * phone in Chinese the English name is nowhere but in the package). Ranked: exact name, exact package, a word of
     * the package (settings, calculator), name contains, package contains. Launchable apps first.
     */
    fun findApps(query: String?): List<Pair<String, String>> {
        // 1.4.2: from the catalog read once (AppCatalog), not the package manager on every call.
        val (launcher, appLabels) = AppCatalog.pairs()
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return launcher.distinctBy { it.second }
        val launchable = launcher.map { it.second }.toSet()
        fun rank(p: Pair<String, String>): Int {
            val name = p.first.lowercase(); val pkg = p.second.lowercase()
            return when {
                name == q -> 0
                pkg == q -> 1
                pkg.split('.').any { it == q } -> 2
                name.contains(q) -> 3
                pkg.contains(q) -> 4
                else -> 9
            }
        }
        return (launcher + appLabels).map { it to rank(it) }.filter { it.second < 9 }
            .sortedWith(compareBy({ it.second }, { if (it.first.second in launchable) 0 else 1 }, { it.first.first }))
            .map { it.first }.distinctBy { it.second }
    }

    fun resolveApp(nameOrPkg: String?): String? {
        val q = nameOrPkg?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val c = ctx()
        if (q.contains('.') && (c == null || runCatching { c.packageManager.getApplicationInfo(q, 0); true }.getOrDefault(false) || q in knownPackages())) return q
        val lq = q.lowercase()
        knownPackages().firstOrNull { p -> label(p)?.lowercase() == lq }?.let { return it }
        return findApps(q).firstOrNull()?.second
    }
}
