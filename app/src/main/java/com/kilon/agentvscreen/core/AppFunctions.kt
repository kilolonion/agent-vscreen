package com.kilon.agentvscreen.core

import android.content.Context
import android.os.Build
import com.kilon.agentvscreen.cmd.ScreenText
import com.kilon.agentvscreen.host.Privileged
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android 16+ (API 36+) AppFunctions read-only discovery (`cmd app_function list-app-functions`).
 *
 * - Process-lifetime caches `shellSupported = false` when the system lacks the `app_function` shell service
 *   (`Unknown command` / `No shell command` / `Can't find service`).
 * - Caches per-package results by `(pkg, versionCode)` and invalidates via [AppCatalog] package broadcasts.
 * - Sanitizes all third-party function identifiers and descriptions through [ScreenText.clean] to maintain the
 *   prompt-injection boundary.
 */
internal object AppFunctions {
    const val MIN_API = 36
    private const val MAX_CACHE_ENTRIES = 128

    data class ShellOut(val exitCode: Int, val stdout: String, val stderr: String)

    data class FnInfo(
        val id: String,
        val pkg: String,
        val name: String,
        val description: String,
        val schema: JSONObject? = null,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id)
            .put("package", pkg)
            .put("name", name)
            .put("description", description)
            .also { o -> if (schema != null) o.put("schema", schema) }
    }

    sealed class QueryResult {
        data class Ok(val items: List<FnInfo>) : QueryResult()
        data object Unsupported : QueryResult()
        data object NotConnected : QueryResult()
    }

    private data class Cached(val versionCode: Long, val items: List<FnInfo>)

    private val lock = Object()
    private val cache = object : LinkedHashMap<String, Cached>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    @Volatile private var shellSupported: Boolean? = null

    @Volatile internal var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
    @Volatile internal var shellRunner: (Array<String>) -> ShellOut? = { argv -> defaultRun(argv) }

    internal fun resetForTest() = synchronized(lock) {
        cache.clear()
        shellSupported = null
        sdkIntProvider = { Build.VERSION.SDK_INT }
        shellRunner = { argv -> defaultRun(argv) }
    }

    fun isSupported(): Boolean {
        if (sdkIntProvider() < MIN_API) return false
        return shellSupported != false
    }

    /** Zero-I/O cache lookup used by `launch` so unsupported devices or apps without functions pay zero overhead. */
    fun cachedCount(pkg: String): Int {
        if (!isSupported()) return 0
        return synchronized(lock) { cache[pkg]?.items?.size ?: 0 }
    }

    /** Called by [AppCatalog] when a package is added/removed/changed/replaced (null = clear all). */
    fun invalidate(pkg: String?) = synchronized(lock) {
        if (pkg == null) cache.clear() else cache.remove(pkg)
    }

    fun listFor(ctx: Context, pkg: String): QueryResult {
        if (!PackageNames.isValid(pkg)) return QueryResult.Ok(emptyList())
        if (!isSupported()) return QueryResult.Unsupported

        val ver = versionCodeOf(ctx, pkg)
        synchronized(lock) {
            cache[pkg]?.takeIf { it.versionCode == ver }?.let { return QueryResult.Ok(it.items) }
        }

        val out = shellRunner(arrayOf("cmd", "app_function", "list-app-functions", "--package", pkg))
            ?: return QueryResult.NotConnected
        if (looksUnsupported(out)) {
            shellSupported = false
            return QueryResult.Unsupported
        }
        shellSupported = true
        val parsed = parseListOutput(out.stdout, fallbackPkg = pkg)
        synchronized(lock) { cache[pkg] = Cached(ver, parsed) }
        return QueryResult.Ok(parsed)
    }

    fun listAll(ctx: Context): QueryResult {
        if (!isSupported()) return QueryResult.Unsupported
        val out = shellRunner(arrayOf("cmd", "app_function", "list-app-functions"))
            ?: return QueryResult.NotConnected
        if (looksUnsupported(out)) {
            shellSupported = false
            return QueryResult.Unsupported
        }
        shellSupported = true
        val parsed = parseListOutput(out.stdout, fallbackPkg = null)
        val byPkg = parsed.groupBy { it.pkg }
        synchronized(lock) {
            for ((pkg, items) in byPkg) {
                if (PackageNames.isValid(pkg)) {
                    cache[pkg] = Cached(versionCodeOf(ctx, pkg), items)
                }
            }
        }
        return QueryResult.Ok(parsed)
    }

    internal fun looksUnsupported(out: ShellOut): Boolean {
        val combined = (out.stdout + "\n" + out.stderr).lowercase()
        if ("no shell command" in combined || "unknown command" in combined || "can't find service" in combined) {
            return true
        }
        if ("app_function" in combined && ("not found" in combined || "unavailable" in combined)) {
            return true
        }
        return false
    }

    /**
     * Parse output of `cmd app_function list-app-functions` into sanitized [FnInfo] entries.
     * Supports JSON arrays, `{functions/appFunctions/app_functions: [...]}`, package-keyed JSON maps,
     * and simple line-based fallback output.
     */
    internal fun parseListOutput(raw: String, fallbackPkg: String? = null): List<FnInfo> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<FnInfo>()

        if (text.startsWith("[")) {
            val arr = runCatching { JSONArray(text) }.getOrNull()
            if (arr != null) {
                collectArray(arr, fallbackPkg, out)
                return out
            }
        }
        if (text.startsWith("{")) {
            val obj = runCatching { JSONObject(text) }.getOrNull()
            if (obj != null) {
                val listKeys = listOf("functions", "appFunctions", "app_functions", "items")
                var handled = false
                for (k in listKeys) {
                    val arr = obj.optJSONArray(k)
                    if (arr != null) {
                        collectArray(arr, fallbackPkg, out)
                        handled = true
                    }
                }
                if (!handled) {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val arr = obj.optJSONArray(k)
                        if (arr != null) {
                            val pkg = if (PackageNames.isValid(k)) k else fallbackPkg
                            collectArray(arr, pkg, out)
                        }
                    }
                }
                return out
            }
        }

        // Plain-text fallback: lines containing package/function identifiers.
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val clean = ScreenText.clean(l, maxLen = 120)
            if (clean.isEmpty()) continue
            val pkg = fallbackPkg ?: extractPackage(clean) ?: continue
            if (!PackageNames.isValid(pkg)) continue
            out += FnInfo(
                id = clean.take(80),
                pkg = pkg,
                name = clean.take(64),
                description = "",
                schema = null,
            )
        }
        return out
    }

    private fun collectArray(arr: JSONArray, fallbackPkg: String?, out: MutableList<FnInfo>) {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val rawId = item.optString("id").ifBlank { item.optString("functionId") }.ifBlank { item.optString("function_id") }
            val rawName = item.optString("name").ifBlank { item.optString("displayName") }.ifBlank { rawId }
            val rawPkg = item.optString("packageName").ifBlank { item.optString("package") }.ifBlank { fallbackPkg.orEmpty() }
            if (rawPkg.isBlank() || !PackageNames.isValid(rawPkg)) continue
            val id = ScreenText.clean(rawId.ifBlank { rawName }, maxLen = 120)
            if (id.isEmpty()) continue
            val name = ScreenText.clean(rawName, maxLen = 80).ifEmpty { id }
            val desc = ScreenText.clean(item.optString("description"), maxLen = 160)
            val schema = item.optJSONObject("schema") ?: item.optJSONObject("parameters")
            out += FnInfo(id = id, pkg = rawPkg, name = name, description = desc, schema = schema)
        }
    }

    private fun extractPackage(line: String): String? {
        for (tok in line.split(' ', '/', ':', '=', ',', ';')) {
            val t = tok.trim()
            if (PackageNames.isValid(t)) return t
        }
        return null
    }

    private fun versionCodeOf(ctx: Context, pkg: String): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }.getOrDefault(-1L)

    private fun defaultRun(argv: Array<String>): ShellOut? {
        if (!Privileged.isReady()) return null
        val r = Privileged.runProcess(argv, timeoutMs = 8_000L)
        if (r.exitCode == -1 && r.stderr.contains("not connected", ignoreCase = true)) return null
        return ShellOut(r.exitCode, r.stdout, r.stderr)
    }
}
