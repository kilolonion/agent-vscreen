package com.kilon.agentvscreen.host

import android.content.Context
import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.cmd.CommandSpec
import com.kilon.agentvscreen.cmd.OffloadArgs
import com.kilon.agentvscreen.tr

/**
 * 2.0.0-beta6 (6.3): risk dimensions requiring on-phone strong confirmation ([StrongConfirm]).
 *
 * Checked once per command inside `VScreenOffloadHandler` right after scope check and before execution.
 * `status` and `help` are always exempt (even before `NEW_CLIENT` is approved, so agents and setup flows
 * like Operit `vscreen_configure` can inspect readiness and explain what is waiting).
 */
enum class Risk(
    val zh: String,
    val en: String,
) {
    NEW_CLIENT("新的接入", "New client"),
    SCOPE_OPERATE("允许操作手机", "Allow operating apps"),
    SCOPE_SYSTEM("允许系统操作", "Allow system actions"),
    HIGH_RISK("高风险操作", "High-risk action"),
    RATE("操作频率异常", "High action rate"),
    SESSION_AGE("长时间未重新确认", "Re-confirmation needed"),
    TRUST_SET("开启免确认", "Enable no-confirm mode"),
    PUBLIC_ENABLE("开启公网接入", "Enable public access");

    /** Approval lifetime in ms; 0 = valid until the user revokes it. */
    val ttlMs: Long
        get() = when (this) {
            NEW_CLIENT, SCOPE_OPERATE, TRUST_SET, PUBLIC_ENABLE -> 0L
            SCOPE_SYSTEM -> SCOPE_SYSTEM_MS
            HIGH_RISK -> Consent.VALID_MS
            RATE -> RATE_APPROVAL_MS
            SESSION_AGE -> SESSION_AGE_MS
        }

    val title: String get() = tr(zh, en)

    companion object {
        const val RATE_MAX_PER_MIN = 120
        const val RATE_WINDOW_MS = 60_000L
        const val RATE_APPROVAL_MS = 60 * 60_000L
        const val SCOPE_SYSTEM_MS = 24 * 60 * 60_000L
        const val SESSION_AGE_MS = 8 * 60 * 60_000L

        const val CLIENT_LOCAL = "local"

        /** Test seam for deterministic time in unit tests. */
        @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

        /** Per-token in-memory 60 s sliding window of input actions (tap/press/swipe/drag/scroll/type/key/rule trigger). */
        private val rateLock = Any()
        private val rateBuckets = HashMap<String, ArrayDeque<Long>>()

        fun of(name: String?): Risk? = entries.firstOrNull { it.name == name }

        /** Client identifier for [call]: `"local"` on loopback, tailnet peer over tailnet, `"public:<client_id>"` over public MCP. */
        fun clientOf(call: Call?): String {
            if (call == null) return CLIENT_LOCAL
            val raw = call.peer?.trim()?.takeIf { it.isNotEmpty() && it != "?" }
            return when (call.source) {
                Call.Source.PUBLIC -> {
                    val p = raw ?: "unknown"
                    if (p.startsWith("public:")) p else "public:$p"
                }
                Call.Source.TAILNET, Call.Source.LOCAL -> raw ?: CLIENT_LOCAL
            }
        }

        /** Record one input action for [tokenId] in the 60 s sliding window. */
        fun recordInputAction(tokenId: String?, nowMs: Long = clock()) {
            val id = tokenId?.takeIf { it.isNotBlank() } ?: return
            synchronized(rateLock) {
                val q = rateBuckets.getOrPut(id) { ArrayDeque() }
                pruneLocked(q, nowMs)
                q.addLast(nowMs)
            }
        }

        /** Number of input actions for [tokenId] within the last 60 s. */
        fun inputActionCount(tokenId: String, nowMs: Long = clock()): Int = synchronized(rateLock) {
            val q = rateBuckets[tokenId] ?: return 0
            pruneLocked(q, nowMs)
            q.size
        }

        private fun pruneLocked(q: ArrayDeque<Long>, nowMs: Long) {
            val cut = nowMs - RATE_WINDOW_MS
            while (q.isNotEmpty() && q.first() < cut) q.removeFirst()
        }

        /**
         * Pure evaluation: returns the first triggered [Risk] dimension for this command, or `null` when allowed.
         * `status` and `help` are never blocked by any dimension (6.4).
         */
        internal fun evaluate(
            call: Call,
            spec: CommandSpec.Cmd,
            args: OffloadArgs,
            nowMs: Long = clock(),
        ): Risk? {
            if (spec.name == "status" || spec.name == "help") return null
            val client = clientOf(call)
            if (!StrongConfirm.hasApproval(NEW_CLIENT, call.tokenId, client, nowMs)) return NEW_CLIENT
            val need = spec.scopeFor(args)
            if (need == CommandSpec.Scope.OPERATE || need == CommandSpec.Scope.SYSTEM) {
                if (!StrongConfirm.hasApproval(SCOPE_OPERATE, call.tokenId, client, nowMs)) return SCOPE_OPERATE
            }
            if (need == CommandSpec.Scope.SYSTEM) {
                if (!StrongConfirm.hasApproval(SCOPE_SYSTEM, call.tokenId, client, nowMs)) return SCOPE_SYSTEM
            }
            if (need == CommandSpec.Scope.OPERATE || need == CommandSpec.Scope.SYSTEM) {
                val lastConfirm = StrongConfirm.latestConfirmAt(call.tokenId)
                if (lastConfirm <= 0L || nowMs - lastConfirm > SESSION_AGE_MS) return SESSION_AGE
            }
            if (inputActionCount(call.tokenId, nowMs) > RATE_MAX_PER_MIN) {
                if (!StrongConfirm.hasApproval(RATE, call.tokenId, client, nowMs)) return RATE
            }
            return null
        }

        /**
         * Gate called by `VScreenOffloadHandler` after scope check and before command execution.
         * Throws `NEEDS_CONFIRM` / `USER_DENIED` when a risk dimension is not approved.
         */
        internal fun check(ctx: Context, call: Call?, spec: CommandSpec.Cmd, args: OffloadArgs) {
            if (call == null) return
            while (true) {
                val risk = evaluate(call, spec, args) ?: return
                StrongConfirm.requireForRisk(ctx, call, risk, spec.name)
            }
        }

        /** Test helper: reset in-memory rate counters and clock. */
        internal fun resetForTests() {
            synchronized(rateLock) { rateBuckets.clear() }
            clock = { System.currentTimeMillis() }
        }
    }
}
