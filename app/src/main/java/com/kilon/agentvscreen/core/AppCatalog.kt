package com.kilon.agentvscreen.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.util.LruCache
import com.kilon.agentvscreen.host.AppLogger

/**
 * 1.4.2 (rainbow: "every time I open an app or mute one, it loads all the apps again"): the phone's apps, read once.
 *
 * Before, every app list (the 打开应用 picker, the sound page's search, `launch <name>`, `apps`) asked the package
 * manager again, and loaded every label (and in the picker every icon) before showing anything; the sound page did it
 * on every keystroke. Now one catalog: launcher entries and every installed app's label, read once in the background
 * when the app starts, kept until a package is installed, removed or changed (or the language changes), and read again
 * then. Icons are loaded one at a time when a row shows, and kept in a small cache.
 */
object AppCatalog {
    /** [launchable]: has a launcher entry. */
    /** [appLabel]: the application's own label when it differs from the launcher entry's (Honor: 时钟 vs 时钟服务). */
    class App(val label: String, val pkg: String, val launchable: Boolean, val appLabel: String? = null)

    @Volatile private var cache: List<App>? = null
    @Volatile private var appCtx: Context? = null
    private val icons = LruCache<String, Bitmap>(160)

    fun init(ctx: Context) {
        if (appCtx != null) return
        val app = ctx.applicationContext
        appCtx = app
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                cache = null
                i.data?.schemeSpecificPart?.let { icons.remove(it) } ?: icons.evictAll()
                warm()
            }
        }
        runCatching {
            app.registerReceiver(rx, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
            })
            app.registerReceiver(rx, IntentFilter(Intent.ACTION_LOCALE_CHANGED))
        }.onFailure { AppLogger.warning("AppCatalog", "no package receiver: ${it.message}") }
        warm()
    }

    /** Read the list in the background if it is not there. */
    fun warm() {
        if (cache == null) Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            runCatching { all() }
        }, "vscreen-apps").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Every app, launchable ones first within the same label order. Blocks the first time (call off the main thread). */
    fun all(): List<App> {
        cache?.let { return it }
        val c = appCtx ?: BackgroundOperation.appContextOrNull() ?: return emptyList()
        synchronized(this) {
            cache?.let { return it }
            val pm = c.packageManager
            val launcher = runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { App(it.loadLabel(pm).toString().trim(), it.activityInfo.packageName, true) }
            }.getOrDefault(emptyList()).distinctBy { it.pkg }
            val appLabels = runCatching {
                pm.getInstalledApplications(0).mapNotNull { ai -> runCatching { ai.packageName to pm.getApplicationLabel(ai).toString().trim() }.getOrNull() }
            }.getOrDefault(emptyList()).toMap()
            val seen = launcher.map { it.pkg }.toHashSet()
            val withAlt = launcher.map { a -> appLabels[a.pkg]?.takeIf { it != a.label }?.let { App(a.label, a.pkg, true, it) } ?: a }
            val others = appLabels.filterKeys { it !in seen }.map { (p, l) -> App(l, p, false) }
            return (withAlt + others).sortedBy { it.label.lowercase() }.also { cache = it }
        }
    }

    /** The launchable ones (pickers), without this app. */
    fun launchable(): List<App> = all().filter { it.launchable && it.pkg != appCtx?.packageName }

    /** Launcher entries plus app labels as (label, pkg), the shape findApps has always worked on. */
    fun pairs(): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
        val a = all()
        return a.filter { it.launchable }.map { it.label to it.pkg } to a.map { (it.appLabel ?: it.label) to it.pkg }
    }

    /** One app's icon, cached. Off the main thread. */
    fun icon(pkg: String, sizePx: Int = 96): Bitmap? {
        icons.get(pkg)?.let { return it }
        val c = appCtx ?: return null
        return runCatching {
            val d = c.packageManager.getApplicationIcon(pkg)
            val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(b); d.setBounds(0, 0, sizePx, sizePx); d.draw(cv)
            b
        }.getOrNull()?.also { icons.put(pkg, it) }
    }
}
