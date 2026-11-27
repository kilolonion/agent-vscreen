package com.kilon.agentvscreen.host

import android.app.Application
import android.content.Context
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 1.4.2-beta3: the ONE source of privilege. Everything that needs the shell (screens, input, commands, setup grants)
 * asks here, never Shizuku or root directly.
 *
 * Two ways in, chosen by the user, one at a time:
 *  - [Via.SHIZUKU]: Shizuku / Sui / AXManager hosts the screen service and spawns commands ([ShizukuManager]).
 *  - [Via.ROOT]: `su` starts the same screen service and commands run through it ([RootBackend]); Shizuku is not
 *    needed at all.
 * The state has the same four values either way, so readiness, setup and the agent-facing checks need no branch.
 */
object Privileged {
    enum class Via(val key: String) { SHIZUKU("shizuku"), ROOT("root") }

    data class Snapshot(
        val via: Via,
        val state: ShizukuManager.State,
        /** What is serving, for status rows: "Shizuku v13", "Sui", "Root（shell）" … */
        val label: String = "",
    )

    private const val PREFS = "privilege"
    private const val K_VIA = "via"

    @Volatile private var prefs: android.content.SharedPreferences? = null
    @Volatile var via: Via = Via.SHIZUKU; private set

    private val _snapshot = MutableStateFlow(Snapshot(Via.SHIZUKU, ShizukuManager.State.NOT_INSTALLED))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized fun init(app: Application) {
        if (prefs != null) return
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        via = Via.entries.firstOrNull { it.key == p.getString(K_VIA, null) } ?: Via.SHIZUKU
        ShizukuManager.init(app)
        RootBackend.init { recompute() }
        RootBackend.remember(app)
        scope.launch { ShizukuManager.snapshot.collect { recompute() } }
        if (via == Via.ROOT) RootBackend.start(app, "app start")
        recompute()
    }

    fun isReady(): Boolean = _snapshot.value.state == ShizukuManager.State.READY

    fun refresh() {
        ShizukuManager.refresh()
        recompute()
    }

    /**
     * Switch the way in. The old service's screen is released and, for root, the old process is stopped (it gives
     * the sound back and closes its screens); the new way starts at once (root asks the root manager the first time).
     */
    fun choose(ctx: Context, next: Via) {
        if (next == via) { if (next == Via.ROOT) RootBackend.start(ctx, "chosen again"); return }
        AppLogger.info("Privileged", "way in: ${via.key} -> ${next.key}")
        val old = via
        if (old == Via.SHIZUKU) com.kilon.agentvscreen.core.VScreenRemoteDisplay.gateStopShizuku()
        com.kilon.agentvscreen.host.quiet("privilege-release") { com.kilon.agentvscreen.core.BackgroundOperation.privilegeChanged() }
        via = next
        prefs?.edit()?.putString(K_VIA, next.key)?.apply()
        if (old == Via.ROOT) RootBackend.stop()
        if (next == Via.ROOT) RootBackend.start(ctx, "chosen")
        recompute()
        com.kilon.agentvscreen.host.quiet("privilege-status") { com.kilon.agentvscreen.core.BackgroundOperation.refreshStatus() }
    }

    fun runProcess(argv: Array<String>, env: Array<String>? = null, cwd: String? = null, timeoutMs: Long = 5_000L): ShizukuManager.ProcessResult =
        when (via) {
            Via.SHIZUKU -> ShizukuManager.runProcess(argv, env, cwd, timeoutMs)
            Via.ROOT -> com.kilon.agentvscreen.core.VScreenRemoteDisplay.exec(argv, env, cwd, timeoutMs)
        }

    /** The way in's name, for messages ("Shizuku 不可用" / "AXManager 不可用" / "Root 不可用"). */
    fun name(): String = when (via) { Via.SHIZUKU -> ShizukuManager.providerLabel() ?: "Shizuku"; Via.ROOT -> "Root" }

    /** Why it is not ready and what the user does, in one sentence. */
    fun notReadyHint(): String = when (via) {
        Via.ROOT -> when (RootBackend.phase) {
            RootBackend.Phase.STARTING -> tr("正在用 root 启动副屏服务…（第一次会弹 root 授权框）", "Starting the screen service with root… (the first time, the root manager asks)")
            else -> RootBackend.lastError ?: tr("还没用 root 启动：在 Agent-VScreen 首页的配置里点“用 Root 启动”", "Not started with root yet: tap \"Start with root\" in Agent-VScreen's setup")
        }
        Via.SHIZUKU -> when (ShizukuManager.snapshot.value.state) {
            ShizukuManager.State.NOT_INSTALLED -> tr("没装 Shizuku（或其兼容分支 / Sui）：请用户安装并启动 Shizuku，或在 App 里改用 Root", "Shizuku (or a compatible fork / Sui) is not installed: ask the user to install and start Shizuku, or switch to root in the app")
            ShizukuManager.State.NOT_RUNNING -> ShizukuManager.missingBinderHint()
            ShizukuManager.State.NEED_PERMISSION -> tr("Agent-VScreen 尚未获得 ${name()} 授权", "Agent-VScreen has no ${name()} permission yet")
            ShizukuManager.State.READY -> ""
        }
    }

    fun diagnostics(ctx: Context): String =
        "Privilege: via=${via.key} state=${_snapshot.value.state} label=${_snapshot.value.label}\n" +
            ShizukuManager.diagnostics(ctx) + "\n" + RootBackend.diagnostics()

    @Volatile private var announced = false

    private fun recompute() {
        val next = when (via) {
            Via.SHIZUKU -> {
                val s = ShizukuManager.snapshot.value
                val provider = ShizukuManager.providerLabel() ?: "Shizuku"
                val label = when {
                    s.state != ShizukuManager.State.READY -> ""
                    s.isSui -> "Sui"
                    s.version > 0 -> "$provider v${s.version}"
                    else -> provider
                }
                Snapshot(via, s.state, label)
            }
            Via.ROOT -> Snapshot(via, rootState(RootBackend.phase, RootBackend.binder?.isBinderAlive == true),
                if (RootBackend.binder != null) "Root" + if (RootBackend.uid == 2000) tr("（shell 身份）", " (as shell)") else "" else "")
        }
        val prev = _snapshot.value
        _snapshot.value = next
        // The first reading is logged too (it equals the placeholder when Shizuku is missing): support and CI read it.
        if (!announced || prev.via != next.via || prev.state != next.state) AppLogger.info("Privileged", "via=${next.via.key} state=${next.state} ${next.label}")
        if (prev.via != next.via || prev.state != next.state) HostService.server?.onPrivilegeChanged()
        announced = true
    }

    /** The root phase in the shared four states. Pure, for tests. */
    internal fun rootState(phase: RootBackend.Phase, alive: Boolean): ShizukuManager.State = when {
        alive -> ShizukuManager.State.READY
        phase == RootBackend.Phase.NO_SU -> ShizukuManager.State.NOT_INSTALLED
        phase == RootBackend.Phase.STARTING || phase == RootBackend.Phase.FAILED -> ShizukuManager.State.NOT_RUNNING
        else -> ShizukuManager.State.NEED_PERMISSION
    }
}
