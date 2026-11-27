package com.kilon.agentvscreen.host

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.kilon.agentvscreen.host.AppLogger
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-android-privileged-backend] Shizuku-protocol privileged-execution backend.
 *
 * Three providers speak the SAME Shizuku binder protocol and push their binder
 * into the SAME client-side [rikka.shizuku.ShizukuProvider]:
 *   • official Shizuku manager (`moe.shizuku.privileged.api`, RikkaApps) — APK
 *   • AXManager (`frb.axeron.manager`, Axeron) — APK
 *   • Sui (RikkaApps) — a Magisk/KernelSU **module**, usually NO APK at all
 *
 * The `rikka.shizuku.Shizuku` singleton is therefore a single slot, owned by
 * whichever provider the user authorized — the SDK and this class are unaware
 * of (and indifferent to) which one is running. The earlier two-backend split
 * confused detection because both `ShizukuBackend` and `AxeronBackend`
 * reported state off the same shared singleton; that abstraction has been
 * removed and must NOT come back for Sui either.
 *
 * [T-android-sui-support] (GH#110 / GH#97) Detection is **binder-first**: a
 * live binder means we are usable, full stop. The installed-package scan is
 * only consulted when the binder is DOWN, to explain why and pick the right
 * install guidance. The previous package-first order hard-blocked Sui: Sui
 * ships no APK, so the whitelist scan returned "not installed" and
 * short-circuited before `pingBinder()` was ever called — a live, authorized
 * Sui rendered as "Manager Not Installed".
 *
 * No Sui-specific dependency or init is needed: `dev.rikka.shizuku:provider`
 * calls `Sui.init(packageName)` automatically from `ShizukuProvider.onCreate`
 * (verified in the 13.1.5 bytecode), and `rikka.sui.Sui` ships inside
 * `dev.rikka.shizuku:api`. [Sui.isSui] is used for LABELS ONLY — never for
 * state decisions (there is no supported way to identify the binder's sender;
 * see the reverted probe in 09f12761).
 */
class ShizukuBackend(private val appContext: Context) {

    /**
     * Which permission request is current and whether Shizuku denied it. A denial alone does not say why: Shizuku
     * sends the same one for the user's Deny and for "adb permission limited" (the maker took the grant right from
     * adb), so the UI names both. Each request sends its own code (Shizuku hands it back), so a late answer to an
     * older request is not taken as the current one's; a new binder ends the current request and its checks too.
     */
    class Attempts {
        @Volatile var current = 0
            private set
        @Volatile private var deniedIn = 0
        @Synchronized fun start(): Int { deniedIn = 0; return ++current }
        fun codeOf(id: Int): Int = PERMISSION_REQUEST_CODE + id
        /** False when the answer belongs to another request (or another app's code) and was ignored. */
        @Synchronized fun answered(requestCode: Int, granted: Boolean): Boolean {
            if (current == 0 || requestCode != codeOf(current)) return false
            deniedIn = if (granted) 0 else current
            return true
        }
        @Synchronized fun invalidate() { deniedIn = 0; current++ }
        val lastDenied: Boolean get() = current > 0 && deniedIn == current
    }

    @Volatile private var onStateChanged: (() -> Unit)? = null
    val attempts = Attempts()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        AppLogger.info(TAG, "binder received")
        attempts.invalidate()
        onStateChanged?.invoke()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        AppLogger.warning(TAG, "binder died")
        onStateChanged?.invoke()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            val mine = attempts.answered(requestCode, granted)
            AppLogger.info(TAG, "permission result code=$requestCode grant=$granted attempt=${attempts.current} current=$mine")
            onStateChanged?.invoke()
        }

    /**
     * Any known Shizuku-protocol **manager APK** installed?
     *
     * [T-android-sui-support] NOTE: this is NOT "is a privileged backend
     * available" — Sui provides the binder with no APK to find. Never gate
     * binder work on this; use [snapshot] / [isPermissionGranted] instead.
     * It survives only to drive install guidance and the "open manager app"
     * affordance.
     */
    fun isInstalled(): Boolean = installedManagerPackage() != null

    /**
     * [T-android-sui-support] True when the live binder is provided by Sui
     * (root module) rather than a manager APK. Display-only: it picks labels
     * and hides the meaningless "Open Manager App" row for Sui users. It must
     * never influence state resolution — see [decideState].
     */
    fun isSui(): Boolean = runCatching { Sui.isSui() }.getOrDefault(false)

    /**
     * All discovered Shizuku-protocol manager packages on this device using 3-tier discovery:
     *   1. Authoritative owner of `moe.shizuku.manager.permission.API_V23` (works for renamed forks)
     *   2. Activities handling `moe.shizuku.manager.intent.action.REQUEST_PERMISSION`
     *   3. Known manager package list fallback
     */
    fun discoveredManagerPackages(): List<String> = Companion.discoverManagerPackages(appContext.packageManager)

    /**
     * The first discovered Shizuku-protocol manager package (permission owner first, then intent handlers,
     * then known list), or null if none installed.
     */
    fun installedManagerPackage(): String? = discoveredManagerPackages().firstOrNull()

    /** Human-readable provider label for display in Settings/status (never gates state). */
    fun providerLabel(): String? {
        if (isSui()) return "Sui"
        val pkg = installedManagerPackage() ?: return null
        return when (pkg) {
            SHIZUKU_PACKAGE -> "Shizuku"
            AXMANAGER_PACKAGE -> "AXManager"
            else -> runCatching {
                val ai = appContext.packageManager.getApplicationInfo(pkg, 0)
                appContext.packageManager.getApplicationLabel(ai).toString().trim().takeIf { it.isNotEmpty() }
            }.getOrNull() ?: pkg
        }
    }

    fun isBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun snapshot(): ShizukuManager.Snapshot {
        val binderAlive = isBinderAlive()
        val state = decideState(
            binderAlive = binderAlive,
            permissionGranted = binderAlive && isPermissionGranted(),
            managerApkInstalled = isInstalled(),
        )
        return if (state == ShizukuManager.State.READY) {
            ShizukuManager.Snapshot(state, versionOrUnknown(), uidOrUnknown(), isSui())
        } else {
            ShizukuManager.Snapshot(state, isSui = binderAlive && isSui(),
                denied = state == ShizukuManager.State.NEED_PERMISSION && attempts.lastDenied)
        }
    }

    private val pendingListeners = mutableMapOf<String, () -> Unit>(
        "binder received" to { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) },
        "binder dead" to { Shizuku.addBinderDeadListener(binderDeadListener) },
        "permission result" to { Shizuku.addRequestPermissionResultListener(permissionResultListener) },
    )

    @Synchronized fun registerListeners(onStateChanged: () -> Unit) {
        this.onStateChanged = onStateChanged
        pendingListeners.entries.removeAll { (name, register) ->
            runCatching(register).onFailure { AppLogger.warning(TAG, "register $name listener failed: ${it.message}") }.isSuccess
        }
    }

    /**
     * Launch the installed manager app (Shizuku or AXManager — whichever is
     * present). If none is installed, falls back to an install page; the
     * multi-option install UI lives in the Settings screen, not here.
     *
     * [T-android-sui-support] Sui has no launcher activity, so a Sui user has
     * nothing to open. The UI hides this row for them; if it is somehow
     * reached anyway, send them to Sui's page rather than to Shizuku's
     * releases (which is what the old unconditional fallback did).
     */
    fun openManagerApp(context: Context) {
        val pkg = installedManagerPackage()
        if (pkg != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure { AppLogger.warning(TAG, "open $pkg failed: ${it.message}") }
                return
            }
        }
        openInstallPage(context, if (isSui()) SUI_GITHUB_URL else SHIZUKU_GITHUB_URL)
    }

    fun openInstallPage(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { AppLogger.warning(TAG, "openInstallPage($url) failed: ${it.message}") }
    }

    /**
     * 1.3.0-beta18 (GitHub #2): what a tap on "grant" did. It used to return silently when Shizuku had the app on
     * record as denied, so the button "did nothing" and the user had to revoke it in Shizuku before asking again.
     */
    enum class Ask { NO_BINDER, ALREADY, ASKED, DENIED_ON_RECORD, FAILED }

    fun requestPermission(): Ask {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return Ask.NO_BINDER
        if (isPermissionGranted()) return Ask.ALREADY
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            AppLogger.warning(TAG, "Shizuku has this app on record as denied: it shows no dialog; the user allows it in the manager app")
            return Ask.DENIED_ON_RECORD
        }
        val id = attempts.start()
        return runCatching { Shizuku.requestPermission(attempts.codeOf(id)); Ask.ASKED }
            .getOrElse { AppLogger.warning(TAG, "requestPermission failed: ${it.message}"); Ask.FAILED }
    }

    private fun versionOrUnknown(): Int = runCatching {
        if (Shizuku.pingBinder()) Shizuku.getVersion() else -1
    }.getOrDefault(-1)

    private fun uidOrUnknown(): Int = runCatching {
        if (Shizuku.pingBinder()) Shizuku.getUid() else -1
    }.getOrDefault(-1)

    /**
     * Shizuku-SDK process path (reflection-based `Shizuku.newProcess`).
     */
    fun runProcess(
        argv: Array<String>,
        env: Array<String>?,
        cwd: String?,
        timeoutMs: Long,
    ): ShizukuManager.ProcessResult {
        if (!isPermissionGranted()) {
            return ShizukuManager.ProcessResult(
                exitCode = 126,
                stdout = "",
                stderr = "shizuku not ready (state=${snapshot().state})",
            )
        }
        // Shizuku.newProcess is hidden API — invoke via reflection so the
        // SDK upgrade doesn't break our build. The returned object is a
        // ShizukuRemoteProcess which extends java.lang.Process, so once we
        // have it, treat it as a normal Process and use the standard
        // waitFor(timeout, unit) API rather than reflecting for a
        // nonexistent `waitForTimeout` method (T343).
        val procAny = runCatching {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            m.isAccessible = true
            m.invoke(null, argv, env, cwd)
        }.getOrElse { err ->
            AppLogger.warning(TAG, "newProcess reflection failed: ${err.message}")
            if (com.kilon.agentvscreen.core.VScreenRemoteDisplay.isConnected()) {
                if (execFallbackLogged.compareAndSet(false, true)) {
                    AppLogger.info(TAG, "Falling back from Shizuku.newProcess to VScreenDisplayService EXEC")
                }
                return com.kilon.agentvscreen.core.VScreenRemoteDisplay.exec(argv, env, cwd, timeoutMs)
            }
            return ShizukuManager.ProcessResult(1, "", "shizuku.newProcess unavailable: ${err.message}")
        } ?: run {
            if (com.kilon.agentvscreen.core.VScreenRemoteDisplay.isConnected()) {
                return com.kilon.agentvscreen.core.VScreenRemoteDisplay.exec(argv, env, cwd, timeoutMs)
            }
            return ShizukuManager.ProcessResult(1, "", "shizuku.newProcess returned null")
        }

        val proc = procAny as? Process ?: return ShizukuManager.ProcessResult(
            1, "",
            "shizuku.newProcess returned ${procAny.javaClass.name}, not java.lang.Process",
        )

        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread {
            runCatching {
                proc.inputStream?.use { s ->
                    s.bufferedReader().forEachLine { l -> synchronized(out) { out.append(l).append('\n') } }
                }
            }
        }
        val errThread = Thread {
            runCatching {
                proc.errorStream?.use { s ->
                    s.bufferedReader().forEachLine { l -> synchronized(err) { err.append(l).append('\n') } }
                }
            }
        }
        outThread.isDaemon = true; errThread.isDaemon = true
        // GH #453: per-name thread counts.
        outThread.name = "shizuku-out"; errThread.name = "shizuku-err"
        outThread.start(); errThread.start()

        val exited: Boolean = try {
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: IllegalArgumentException) {
            // Shizuku v13 RemoteProcess.waitFor(long, TimeUnit) throws IllegalArgumentException
            // ("process hasn't exited") instead of blocking. Fall back to polling exitValue().
            if (polledFallbackLogged.compareAndSet(false, true)) {
                AppLogger.info(TAG, "Using polling fallback for Shizuku RemoteProcess.waitFor(timeout) (SDK bug — throws IllegalArgumentException)")
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            var done = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    proc.exitValue()
                    done = true
                    break
                } catch (e: RuntimeException) {
                    if (e is IllegalThreadStateException || e is IllegalArgumentException) {
                        Thread.sleep(50)
                    } else {
                        throw e
                    }
                }
            }
            done
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "Shizuku waitFor failed: type=${t::class.java.name} msg=${t.message}")
            com.kilon.agentvscreen.host.quiet("ShizukuBackend:267") { proc.destroy() }
            com.kilon.agentvscreen.host.quiet("ShizukuBackend:268") { outThread.join(2000) }
            com.kilon.agentvscreen.host.quiet("ShizukuBackend:269") { errThread.join(2000) }
            return ShizukuManager.ProcessResult(1, out.toString().trimEnd('\n'), (err.toString().trimEnd('\n') + "\nwaitFor failed: ${t.message}").trim())
        }

        if (!exited) {
            com.kilon.agentvscreen.host.quiet("ShizukuBackend:274") { proc.destroy() }
            com.kilon.agentvscreen.host.quiet("ShizukuBackend:275") { outThread.join(2000) }
            com.kilon.agentvscreen.host.quiet("ShizukuBackend:276") { errThread.join(2000) }
            // Linux convention: 124 = command timed out (coreutils `timeout`).
            return ShizukuManager.ProcessResult(124, out.toString().trimEnd('\n'), err.toString().trimEnd('\n'))
        }

        com.kilon.agentvscreen.host.quiet("ShizukuBackend:281") { outThread.join(2000) }
        com.kilon.agentvscreen.host.quiet("ShizukuBackend:282") { errThread.join(2000) }
        val rc = runCatching { proc.exitValue() }.getOrDefault(-1)
        return ShizukuManager.ProcessResult(rc, out.toString().trimEnd('\n'), err.toString().trimEnd('\n'))
    }

    companion object {
        private const val TAG = "ShizukuBackend"

        const val PERMISSION_API_V23 = "moe.shizuku.manager.permission.API_V23"
        const val ACTION_REQUEST_PERMISSION = "moe.shizuku.manager.intent.action.REQUEST_PERMISSION"

        // Known Shizuku-protocol manager packages (tier-3 fallback after dynamic permission owner + intent discovery).
        // Sui is deliberately ABSENT: it is a root module, not an APK, detected purely by its live binder.
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val AXMANAGER_PACKAGE = "frb.axeron.manager"
        val KNOWN_MANAGER_PACKAGES = listOf(
            SHIZUKU_PACKAGE,
            AXMANAGER_PACKAGE,
            "moe.shizuku.privileged.api.nightzuku",
            "io.github.the0x01.shevery",
            "com.nightzuku.manager",
        )
        val SHIZUKU_COMPATIBLE_PACKAGES = KNOWN_MANAGER_PACKAGES

        const val SHIZUKU_GITHUB_URL = "https://github.com/RikkaApps/Shizuku/releases"
        const val AXMANAGER_GITHUB_URL = "https://github.com/fahrez182/AxManager/releases"
        const val SUI_GITHUB_URL = "https://github.com/RikkaApps/Sui"

        const val PERMISSION_REQUEST_CODE = 0xC1A4D

        private val polledFallbackLogged = AtomicBoolean(false)
        private val execFallbackLogged = AtomicBoolean(false)

        /** Pure 3-tier manager package merger (unit-tested). */
        internal fun discoverManagerPackages(
            permissionOwner: String?,
            intentActivityPackages: List<String>,
            installedKnownPackages: List<String>,
        ): List<String> {
            val out = LinkedHashSet<String>()
            permissionOwner?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            for (p in intentActivityPackages) p.trim().takeIf { it.isNotEmpty() }?.let { out += it }
            for (p in installedKnownPackages) p.trim().takeIf { it.isNotEmpty() }?.let { out += it }
            return out.toList()
        }

        /** Query PackageManager using the 3-tier Shizuku/fork discovery pipeline. */
        fun discoverManagerPackages(pm: PackageManager): List<String> {
            val owner = runCatching { pm.getPermissionInfo(PERMISSION_API_V23, 0)?.packageName }.getOrNull()
            val intentPkgs = runCatching {
                pm.queryIntentActivities(Intent(ACTION_REQUEST_PERMISSION), PackageManager.MATCH_ALL)
                    .mapNotNull { it.activityInfo?.packageName }
            }.getOrDefault(emptyList())
            val knownInstalled = KNOWN_MANAGER_PACKAGES.filter { pkg ->
                runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
            }
            return discoverManagerPackages(owner, intentPkgs, knownInstalled)
        }


        /**
         * [T-android-sui-support] The binder-first decision, extracted as a
         * PURE function so every branch is unit-testable without a device, a
         * root environment, or the Shizuku SDK's static singleton
         * (ShizukuBackendStateTest).
         *
         * Order matters and is the whole fix:
         *   1. binder alive?  → READY / NEED_PERMISSION. Whoever supplied it
         *      (Shizuku APK, AXManager, or Sui module) is irrelevant — this is
         *      what makes Sui work with zero Sui-specific plumbing.
         *   2. binder down, manager APK present → NOT_RUNNING ("start it").
         *   3. binder down, nothing installed → NOT_INSTALLED ("install one").
         *
         * NOT_INSTALLED keeps its name for call-site compatibility but now
         * means "no binder AND no manager APK" — with Sui in the picture it
         * reads as "no privileged backend available", which is what the UI
         * copy says.
         */
        fun decideState(
            binderAlive: Boolean,
            permissionGranted: Boolean,
            managerApkInstalled: Boolean,
        ): ShizukuManager.State = when {
            binderAlive && permissionGranted -> ShizukuManager.State.READY
            binderAlive -> ShizukuManager.State.NEED_PERMISSION
            managerApkInstalled -> ShizukuManager.State.NOT_RUNNING
            else -> ShizukuManager.State.NOT_INSTALLED
        }
    }
}
