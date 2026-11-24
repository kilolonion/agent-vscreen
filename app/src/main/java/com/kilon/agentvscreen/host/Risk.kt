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

    /** Approval lifetime in ms under [STRICT] or [STANDARD] security level; 0 = valid until user revokes. */
    fun ttlMsFor(level: SecurityLevel = securityLevel): Long = when (this) {
        NEW_CLIENT, SCOPE_OPERATE, TRUST_SET, PUBLIC_ENABLE -> 0L
        SCOPE_SYSTEM -> if (level == SecurityLevel.STRICT) SCOPE_SYSTEM_MS else 0L
        HIGH_RISK -> Consent.VALID_MS
        RATE -> RATE_APPROVAL_MS
        SESSION_AGE -> SESSION_AGE_MS
    }

    /** Approval lifetime in ms under the current [securityLevel]; 0 = valid until the user revokes it. */
    val ttlMs: Long get() = ttlMsFor(securityLevel)

    val title: String get() = tr(zh, en)

    enum class SecurityLevel(val key: String) {
        STANDARD("standard"),
        STRICT("strict");

        companion object {
            fun of(raw: String?): SecurityLevel =
                entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: STANDARD
        }
    }

    companion object {
        private const val PREFS_NAME = "agent_vscreen_security"
        private const val KEY_LEVEL = "security_level"

        const val RATE_MAX_PER_MIN = 120
        const val RATE_WINDOW_MS = 60_000L
        const val RATE_APPROVAL_MS = 60 * 60_000L
        const val SCOPE_SYSTEM_MS = 24 * 60 * 60_000L
        const val SESSION_AGE_MS = 8 * 60 * 60_000L

        const val CLIENT_LOCAL = "local"
        /** Wildcard client recorded when the user pre-authorizes a newly created agent in the app UI (B2). */
        const val CLIENT_ANY = "*"

        @Volatile private var prefs: android.content.SharedPreferences? = null
        private val _securityLevelFlow = kotlinx.coroutines.flow.MutableStateFlow(SecurityLevel.STANDARD)
        val securityLevelFlow: kotlinx.coroutines.flow.StateFlow<SecurityLevel> = _securityLevelFlow

        var securityLevel: SecurityLevel
            get() = _securityLevelFlow.value
            set(value) {
                _securityLevelFlow.value = value
                prefs?.edit()?.putString(KEY_LEVEL, value.key)?.apply()
            }

        fun setSecurityLevel(ctx: Context, level: SecurityLevel) {
            if (prefs == null) init(ctx)
            securityLevel = level
            Audit.security("security_level", level.key)
        }

        fun init(ctx: Context) {
            val p = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            _securityLevelFlow.value = SecurityLevel.of(p.getString(KEY_LEVEL, null))
        }

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
         * 2.0.0-beta8 (B2): pure evaluation returning ALL unapproved [Risk] dimensions for this command,
         * or an empty list when allowed. `status` and `help` are never blocked by any dimension.
         *
         * Under [SecurityLevel.STANDARD] (default): granted `NEW_CLIENT` / `SCOPE_OPERATE` / `SCOPE_SYSTEM`
         * approvals stay valid until revoked, and `SESSION_AGE` does not trigger. `HIGH_RISK`, `RATE`, and
         * `PUBLIC_ENABLE` always remain enforced under both levels.
         */
        internal fun evaluateAll(
            call: Call,
            spec: CommandSpec.Cmd,
            args: OffloadArgs,
            nowMs: Long = clock(),
            level: SecurityLevel = securityLevel,
        ): List<Risk> {
            if (spec.name == "status" || spec.name == "help") return emptyList()
            val out = ArrayList<Risk>(4)
            val client = clientOf(call)
            val hasNewClient = StrongConfirm.hasApproval(NEW_CLIENT, call.tokenId, client, nowMs, level)
            if (!hasNewClient) out += NEW_CLIENT
            val need = spec.scopeFor(args)
            if (need == CommandSpec.Scope.OPERATE || need == CommandSpec.Scope.SYSTEM) {
                if (!StrongConfirm.hasApproval(SCOPE_OPERATE, call.tokenId, client, nowMs, level)) {
                    out += SCOPE_OPERATE
                }
            }
            if (need == CommandSpec.Scope.SYSTEM) {
                if (!StrongConfirm.hasApproval(SCOPE_SYSTEM, call.tokenId, client, nowMs, level)) {
                    out += SCOPE_SYSTEM
                }
            }
            if (level == SecurityLevel.STRICT && hasNewClient && (need == CommandSpec.Scope.OPERATE || need == CommandSpec.Scope.SYSTEM)) {
                val lastConfirm = StrongConfirm.latestConfirmAt(call.tokenId)
                if (lastConfirm <= 0L || nowMs - lastConfirm > SESSION_AGE_MS) {
                    out += SESSION_AGE
                }
            }
            if (inputActionCount(call.tokenId, nowMs) > RATE_MAX_PER_MIN) {
                if (!StrongConfirm.hasApproval(RATE, call.tokenId, client, nowMs, level)) {
                    out += RATE
                }
            }
            return out
        }

        /** First triggered [Risk] dimension for this command, or `null` when allowed. */
        internal fun evaluate(
            call: Call,
            spec: CommandSpec.Cmd,
            args: OffloadArgs,
            nowMs: Long = clock(),
        ): Risk? = evaluateAll(call, spec, args, nowMs).firstOrNull()

        /**
         * Gate called by `VScreenOffloadHandler` after scope check and before command execution.
         * Collects ALL unapproved dimensions and requests them in a single on-phone strong confirmation (B2).
         * Throws `NEEDS_CONFIRM` / `USER_DENIED` when unapproved.
         */
        internal fun check(ctx: Context, call: Call?, spec: CommandSpec.Cmd, args: OffloadArgs) {
            if (call == null) return
            while (true) {
                val missing = evaluateAll(call, spec, args)
                if (missing.isEmpty()) return
                StrongConfirm.requireForRisks(ctx, call, missing, spec.name)
            }
        }

        /** Test helper: reset in-memory rate counters, clock, and security level. */
        internal fun resetForTests(level: SecurityLevel = SecurityLevel.STANDARD) {
            synchronized(rateLock) { rateBuckets.clear() }
            clock = { System.currentTimeMillis() }
            _securityLevelFlow.value = level
            prefs?.edit()?.putString(KEY_LEVEL, level.key)?.apply()
        }
    }
}
