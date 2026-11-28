package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.trPair
import android.content.Context
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.cmd.CommandSpec
import com.kilon.agentvscreen.cmd.OffloadArgs
import com.kilon.agentvscreen.tr

/**
 * 2.0.0-beta6 (6.3) → 2.0.0-beta8 (feedback 2–4): what stops a command for an on-phone confirmation ([StrongConfirm]).
 *
 * Since feedback 2 there is one setting per agent, its level ([Tokens.LEVELS]); the old per-scope approvals
 * (SCOPE_OPERATE / SCOPE_SYSTEM), the re-confirmation after 8 hours (SESSION_AGE) and the global security level
 * are gone. What is left:
 * - [NEW_CLIENT]: a token the user never confirmed (every agent made in the app is confirmed when it is made, so in
 *   practice only a token made some other way). It is per token, not per device: the same agent on a new device of
 *   the user's own tailnet does not ask again.
 * - [LEVEL_UP]: the agent asks for something above its level; one fingerprint raises the level.
 * - [HIGH_RISK]: a step of class money / destroy / caution or an app the user set to ask ([Consent]).
 * - [RATE]: unusually many actions a minute. Asked only at the careful level; at the free levels it is logged and
 *   shown in a notification (feedback 4: the top level is not interrupted).
 * - [TRUST_SET], [PUBLIC_ENABLE]: things the user starts in the app.
 *
 * `status` and `help` are never stopped, so setup flows can always see what is waiting.
 */
enum class Risk(
    val zh: String,
    val en: String,
) {
    NEW_CLIENT("新 agent 接入", "New agent"),
    LEVEL_UP("放宽权限", "Widen permissions"),
    HIGH_RISK("操作确认", "Action confirmation"),
    RATE("高频操作", "High action rate"),
    TRUST_SET("临时免确认支付", "Payments without confirmation"),
    PUBLIC_ENABLE("开启公网接入", "Enable public access");

    /** Approval lifetime in ms; 0 = valid until the user revokes it. Grants with a range carry their own end. */
    val ttlMs: Long get() = when (this) {
        NEW_CLIENT, TRUST_SET, PUBLIC_ENABLE, LEVEL_UP -> 0L
        HIGH_RISK -> Consent.VALID_MS
        RATE -> 0L
    }

    val title: String get() = trPair(zh, en)

    /**
     * Feedback 3: two strengths. A fingerprint (or screen-lock PIN) only where the user widens what an agent may do or
     * money moves; everything else is one real tap on "Allow" on the main screen (FLAG_SECURE, real finger only, never
     * on the agent's screen).
     */
    enum class Strength { BIOMETRIC, TAP }

    companion object {
        const val RATE_MAX_PER_MIN = 120
        const val RATE_WINDOW_MS = 60_000L
        /** How often a free-level agent's high rate is written to the log / notification at most. */
        const val RATE_NOTE_EVERY_MS = 60_000L

        const val CLIENT_LOCAL = "local"
        /** Wildcard client: an approval for the agent itself, whatever device it calls from. */
        const val CLIENT_ANY = "*"

        fun init(@Suppress("UNUSED_PARAMETER") ctx: Context) {}

        /** Test seam for deterministic time in unit tests. */
        @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

        /** Per-token in-memory 60 s sliding window of input actions (tap/press/swipe/drag/scroll/type/key/rule trigger). */
        private val rateLock = Any()
        private val rateBuckets = HashMap<String, ArrayDeque<Long>>()
        private val rateNoted = HashMap<String, Long>()

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

        /** Feedback 4: a high rate asks only when this agent's table says "ask"; otherwise it is logged and shown. */
        fun rateAsks(call: Call?): Boolean =
            (Tokens.byId(call?.tokenId)?.let { StrongConfirm.effectiveAct(it, com.kilon.agentvscreen.api.Policy.RATE) } ?: com.kilon.agentvscreen.api.Policy.Act.ASK) == com.kilon.agentvscreen.api.Policy.Act.ASK

        /**
         * Pure evaluation: every dimension that stops this command now, or an empty list when it may run.
         * The level itself is checked earlier ([VScreenOffloadHandler] asks for [LEVEL_UP] when the scope is short).
         */
        internal fun evaluateAll(
            call: Call,
            spec: CommandSpec.Cmd,
            @Suppress("UNUSED_PARAMETER") args: OffloadArgs,
            nowMs: Long = clock(),
        ): List<Risk> {
            if (spec.name == "status" || spec.name == "help") return emptyList()
            val out = ArrayList<Risk>(2)
            val client = clientOf(call)
            if (!StrongConfirm.hasAgentApproval(call.tokenId, client, nowMs)) out += NEW_CLIENT
            val n = inputActionCount(call.tokenId, nowMs)
            if (n > RATE_MAX_PER_MIN) {
                if (rateAsks(call)) {
                    if (!StrongConfirm.hasGrant(call.tokenId, StrongConfirm.grantKey("rate", "*"), nowMs)) out += RATE
                } else noteRate(call, n, nowMs)
            }
            return out
        }

        /** A free-level agent above the rate limit: one log line and one quiet notification a minute, no question. */
        private fun noteRate(call: Call, n: Int, nowMs: Long) {
            val due = synchronized(rateLock) {
                val last = rateNoted[call.tokenId] ?: 0L
                if (nowMs - last < RATE_NOTE_EVERY_MS) false else { rateNoted[call.tokenId] = nowMs; true }
            }
            if (!due) return
            Audit.security("rate_high", "${call.tokenName} $n/min")
            quiet("rate-note") { RateNotice.show(call.tokenName, n) }
        }

        /** First triggered [Risk] dimension for this command, or `null` when allowed. */
        internal fun evaluate(
            call: Call,
            spec: CommandSpec.Cmd,
            args: OffloadArgs,
            nowMs: Long = clock(),
        ): Risk? = evaluateAll(call, spec, args, nowMs).firstOrNull()

        /**
         * Gate called by `VScreenOffloadHandler` after the level check and before command execution.
         * Collects ALL dimensions and requests them in a single on-phone confirmation.
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

        /**
         * 2.0.0-beta11 (test R16: 160 taps in 56 s at the careful level, never asked): the rate was checked only when a
         * command started, so four 40-step acts counted 0, 40, 80 and 120 at their starts and never went over. Now every
         * input step counts itself and checks the window right then, before it acts; a stretch that crosses the limit
         * stops there and asks.
         */
        internal fun checkRate(ctx: Context, call: Call?) {
            if (call == null) return
            val now = clock()
            val n = inputActionCount(call.tokenId, now)
            if (n <= RATE_MAX_PER_MIN) return
            if (!rateAsks(call)) { noteRate(call, n, now); return }
            if (StrongConfirm.hasGrant(call.tokenId, StrongConfirm.grantKey("rate", "*"), now)) return
            StrongConfirm.requireForRisks(ctx, call, listOf(RATE), "act")
        }

        /** Test helper: reset in-memory rate counters and clock. */
        internal fun resetForTests() {
            synchronized(rateLock) { rateBuckets.clear(); rateNoted.clear() }
            clock = { System.currentTimeMillis() }
        }
    }
}
