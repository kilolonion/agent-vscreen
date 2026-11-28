package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.tr
import android.app.Application
import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T322 / [T-android-privileged-backend]: lifecycle + permission gateway for
 * Shizuku-protocol privileged execution.
 *
 * Thin façade around a single [ShizukuBackend], which recognises ALL THREE
 * Shizuku-protocol providers — the official Shizuku manager, AXManager, and
 * Sui (root module) — because they share the same client-side binder slot, so
 * there is no "active backend" choice to make at this layer. Public API
 * ([snapshot], [isReady], [runProcess], [openShizukuApp], [openInstallPage],
 * [requestPermission]) is unchanged from earlier callers.
 *
 * State machine ([T-android-sui-support]: binder-first — a live binder wins
 * before any package check, which is what lets APK-less Sui be detected):
 *   READY              Binder up + permission held — calls work.
 *   NEED_PERMISSION    Binder up but this app not authorized.
 *   NOT_RUNNING        No binder, but a manager APK is installed — start it.
 *   NOT_INSTALLED      No binder and no manager APK — nothing available.
 */
object ShizukuManager {
    private const val TAG = "ShizukuManager"

    enum class State { NOT_INSTALLED, NOT_RUNNING, NEED_PERMISSION, READY }

    data class Snapshot(
        val state: State,
        val version: Int = -1,
        val uid: Int = -1,
        /**
         * [T-android-sui-support] True when the live binder came from Sui (a
         * root module) rather than a manager APK. DISPLAY ONLY — drives the
         * "Sui (root)" label and hides the "Open Manager App" row that Sui
         * has no activity for. Never gate behaviour on it.
         */
        val isSui: Boolean = false,
        /** NEED_PERMISSION and Shizuku denied the last request: the user's Deny or the maker's adb limit, see [deniedHint]. */
        val denied: Boolean = false,
    )

    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val combined: String get() = if (stderr.isEmpty()) stdout else "$stdout\n$stderr".trimEnd()
    }

    @Volatile private var backend: ShizukuBackend? = null

    private val _snapshot = MutableStateFlow(Snapshot(State.NOT_INSTALLED))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    @Synchronized fun init(app: Application) {
        if (backend != null) return
        val b = ShizukuBackend(app.applicationContext)
        backend = b
        b.registerListeners { recompute("listener") }
        recompute("init")
    }

    fun refresh() {
        backend?.registerListeners { recompute("listener") }
        recompute("manual-refresh")
    }

    fun isReady(): Boolean = _snapshot.value.state == State.READY

    /**
     * Whether a manager **APK** is installed. [T-android-sui-support] This is
     * NOT an availability check — Sui provides the binder with no APK. Use
     * [isReady] / [snapshot] for "can we actually run privileged commands".
     */
    fun isInstalled(): Boolean = backend?.isInstalled() == true

    /** [T-android-sui-support] Display-only: is the live binder Sui's? */
    fun isSui(): Boolean = _snapshot.value.isSui

    /** First installed Shizuku-protocol manager package, or null. */
    fun installedManagerPackage(): String? = backend?.installedManagerPackage()

    /** All dynamically discovered Shizuku-protocol manager packages (including forks). */
    fun discoveredManagerPackages(): Set<String> =
        (backend?.discoveredManagerPackages().orEmpty() + ShizukuBackend.KNOWN_MANAGER_PACKAGES).toSet()

    /** Human-readable provider label ("Shizuku", "AXManager", "Sui", or fork app label). */
    fun providerLabel(): String? = backend?.providerLabel()

    fun diagnostics(ctx: Context): String {
        val b = backend ?: return "Shizuku: not initialized"
        val manager = b.installedManagerPackage()
        val version = manager?.let { runCatching { ctx.packageManager.getPackageInfo(it, 0).versionName }.getOrNull() }
        val managed = ctx.getSystemService(android.os.UserManager::class.java)?.isManagedProfile
        return "Shizuku: state=${b.snapshot().state} binder=${b.isBinderAlive()} permission=${b.isPermissionGranted()} " +
            "provider=${b.providerLabel() ?: "none"} manager=${manager ?: "none"} version=${version ?: "-"} appUid=${android.os.Process.myUid()} managedProfile=$managed"
    }

    fun missingBinderHint() = tr(S.shizuku_manager_shizuku_is_not_running_after)

    /** The id of the latest permission request, so a delayed check can tell its own answer from a retry's. */
    fun attempt(): Int = backend?.attempts?.current ?: 0

    fun deniedHint() = tr(S.shizuku_manager_shizuku_did_not_grant_if) + com.kilon.agentvscreen.core.Vendor.of(com.kilon.agentvscreen.core.Vendor.keyOf(android.os.Build.MANUFACTURER, android.os.Build.BRAND)).adbLimitedFix() +
        tr(S.shizuku_manager_after_changing_it_stop_and)

    /** What happened, for the button to say so (it is never silent). The state is re-read either way. */
    fun requestPermission(): ShizukuBackend.Ask {
        val b = backend ?: return ShizukuBackend.Ask.NO_BINDER
        return b.requestPermission().also {
            AppLogger.info(TAG, "permission request: $it")
            recompute("request-permission")
        }
    }

    /** Launch the installed manager app (Shizuku or AXManager). */
    fun openShizukuApp(context: Context) {
        backend?.openManagerApp(context)
    }

    /**
     * Open the official Shizuku install page. The "AXManager" alternative is
     * surfaced as a separate row in the Settings UI; this method preserves
     * the original single-CTA behaviour for callers that don't show a picker.
     */
    fun openInstallPage(context: Context) {
        backend?.openInstallPage(context, ShizukuBackend.SHIZUKU_GITHUB_URL)
    }

    /** Open a specific install page (Shizuku or AXManager). */
    fun openInstallPage(context: Context, url: String) {
        backend?.openInstallPage(context, url)
    }

    fun runProcess(
        argv: Array<String>,
        env: Array<String>? = null,
        cwd: String? = null,
        timeoutMs: Long = 5_000L,
    ): ProcessResult {
        if (!isReady()) {
            return ProcessResult(
                exitCode = 126,
                stdout = "",
                stderr = "shizuku not ready (state=${_snapshot.value.state})",
            )
        }
        return backend!!.runProcess(argv, env, cwd, timeoutMs)
    }

    private fun recompute(reason: String) {
        val b = backend ?: run {
            _snapshot.value = Snapshot(State.NOT_INSTALLED)
            return
        }
        val snap = b.snapshot()
        val prev = _snapshot.value
        _snapshot.value = snap
        // Only log on real transitions — refresh() is fired by every settings
        // screen entry + every listener callback, so logging unconditionally
        // floods logcat with NOT_RUNNING repeats.
        if (snap.state != prev.state || snap.version != prev.version ||
            snap.uid != prev.uid || snap.isSui != prev.isSui || snap.denied != prev.denied
        ) {
            // [T-android-sui-support] provider= is the field to ask GH#110 /
            // GH#97 reporters for: it distinguishes a Sui-supplied binder from
            // a manager APK without needing any (unsupported) sender probe.
            val provider = when {
                snap.isSui -> "sui"
                else -> b.installedManagerPackage() ?: "none"
            }
            AppLogger.info(
                TAG,
                "state=${snap.state} ver=${snap.version} uid=${snap.uid} provider=$provider denied=${snap.denied} ($reason)",
            )
        }
    }
}
