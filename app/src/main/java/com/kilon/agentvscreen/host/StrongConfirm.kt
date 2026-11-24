package com.kilon.agentvscreen.host

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.app.NotificationCompat
import androidx.core.util.AtomicFile
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * 2.0.0-beta6 (6.1–6.6, 6.9, 6.10): strong confirmation coordinator and persistent approval store.
 *
 * Security invariants:
 * - Strong confirmations NEVER appear via `ask`, `teach`, the floating window, or the fullscreen view,
 *   and NO API endpoint or notification action button can approve them.
 * - Notifications contain NO "Allow" button; tapping only opens [com.kilon.agentvscreen.ui.StrongConfirmActivity]
 *   on the main display (`Display.DEFAULT_DISPLAY`).
 * - Persistent approvals live in `files/vscreen/approvals.json` written via [AtomicFile].
 * - Every lifecycle event (approved, denied, timeout, wrong display, L6 ignored, a11y ignored, revoked)
 *   is written to [Audit.security].
 */
object StrongConfirm {
    const val WAIT_MS = 30_000L
    const val VALID_MS = 600_000L
    const val HOLD_CONFIRM_MS = 3_000L

    /** Hidden framework `MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT` (0x800, verified in Stage 6 spike on API 34). */
    const val FLAG_IS_ACCESSIBILITY_EVENT = 0x800

    private const val CHANNEL = "agent_vscreen_strong_confirm"
    private const val PREFS_NAME = "agent_vscreen_strong_confirm"
    private const val PREF_MIGRATED_BETA6 = "migrated_beta6"
    private const val APPROVALS_REL = "vscreen/approvals.json"

    const val BY_BIOMETRIC = "biometric"
    const val BY_HOLD = "hold"
    const val BY_MIGRATED = "migrated"

    data class Approval(
        val risk: String,
        val token: String,
        val client: String,
        val at: Long,
        /** 0 = valid until user revokes; > 0 = expiry epoch ms. */
        val until: Long,
        val by: String,
    ) {
        fun activeAt(nowMs: Long): Boolean = until == 0L || until > nowMs
        fun json(): JSONObject = JSONObject()
            .put("risk", risk)
            .put("token", token)
            .put("client", client)
            .put("at", at)
            .put("until", until)
            .put("by", by)

        companion object {
            fun fromJson(o: JSONObject): Approval? {
                val risk = o.optString("risk").takeIf { it.isNotBlank() } ?: return null
                val token = o.optString("token").takeIf { it.isNotBlank() } ?: return null
                val client = o.optString("client").takeIf { it.isNotBlank() } ?: Risk.CLIENT_LOCAL
                val at = o.optLong("at", 0L)
                val until = o.optLong("until", 0L)
                val by = o.optString("by").takeIf { it.isNotBlank() } ?: return null
                return Approval(risk, token, client, at, until, by)
            }
        }
    }

    class Request(
        val id: String,
        val risk: Risk,
        val key: String,
        val tokenId: String,
        val tokenName: String,
        val tokenScope: String,
        val client: String,
        /** Command name only — never argument text (6.5). */
        val commandName: String,
        val detail: String = "",
        val at: Long,
        val validMs: Long = VALID_MS,
        val reusable: Boolean = false,
        val allowHold: Boolean = true,
        val onApprove: (() -> Unit)? = null,
        /** 2.0.0-beta7 (7.2, 7.7): public OAuth NEW_CLIENT pairing code, client name, redirect host & scope picker. */
        val pairingCode: String? = null,
        val publicClientName: String? = null,
        val redirectHost: String? = null,
        val selectableScopes: List<String> = emptyList(),
        val defaultScope: String = "look",
        val onApproveWithScope: ((String) -> Unit)? = null,
        val onDeny: (() -> Unit)? = null,
    ) {
        @Volatile var answer: Boolean? = null
        @Volatile var answeredBy: String? = null
        @Volatile var answeredAt: Long = 0L
        @Volatile var used: Boolean = false

        fun liveAt(nowMs: Long): Boolean = answer == null && nowMs - at < validMs
        val live: Boolean get() = liveAt(clock())
    }

    private val lock = Object()
    private val rng = SecureRandom()
    private val requests = ArrayList<Request>()
    private val approvalList = ArrayList<Approval>()
    /** Most recent strong-confirmation timestamp per token (includes one-shot HIGH_RISK and TRUST_SET). */
    private val lastConfirmByToken = HashMap<String, Long>()

    @Volatile private var storageFile: File? = null
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile internal var waitMsOverride: Long? = null
    @Volatile internal var postNotification: Boolean = true

    private val _approvals = MutableStateFlow<List<Approval>>(emptyList())
    val approvals: StateFlow<List<Approval>> = _approvals.asStateFlow()

    private val _pending = MutableStateFlow<List<Request>>(emptyList())
    val pending: StateFlow<List<Request>> = _pending.asStateFlow()

    fun init(ctx: Context) {
        initFile(File(ctx.filesDir, APPROVALS_REL))
    }

    internal fun initFile(file: File) {
        synchronized(lock) {
            storageFile = file
            approvalList.clear()
            lastConfirmByToken.clear()
            if (file.isFile) {
                runCatching {
                    val arr = JSONArray(file.readText(Charsets.UTF_8))
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val a = Approval.fromJson(o) ?: continue
                        approvalList += a
                        bumpLastConfirmLocked(a.token, a.at)
                    }
                }.onFailure { AppLogger.warning("StrongConfirm", "read approvals failed: ${it.message}") }
            }
            publishApprovalsLocked()
        }
    }

    /**
     * 2.0.0-beta6 (6.6): one-time upgrade migration on first beta6 launch.
     * Tokens with `lastUsedAt > 0` automatically receive `(token, local)` approvals for `NEW_CLIENT` and
     * (when scope >= operate) `SCOPE_OPERATE` with `by = "migrated"`. Tailnet clients are NEVER migrated.
     */
    fun migrateIfNeeded(ctx: Context, tokens: List<Tokens.Token>, nowMs: Long = clock()): Int {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_MIGRATED_BETA6, false)) return 0
        val added = migrateTokens(tokens, nowMs)
        prefs.edit().putBoolean(PREF_MIGRATED_BETA6, true).apply()
        return added
    }

    /** Pure/testable migration core (no SharedPreferences dependency). */
    internal fun migrateTokens(tokens: List<Tokens.Token>, nowMs: Long = clock()): Int {
        var added = 0
        synchronized(lock) {
            for (t in tokens) {
                if (t.revoked || t.lastUsedAt <= 0L) continue
                if (!hasApprovalLocked(Risk.NEW_CLIENT, t.id, Risk.CLIENT_LOCAL, nowMs)) {
                    putApprovalLocked(Approval(Risk.NEW_CLIENT.name, t.id, Risk.CLIENT_LOCAL, nowMs, 0L, BY_MIGRATED))
                    added++
                }
                if ("operate" in t.scopes && !hasApprovalLocked(Risk.SCOPE_OPERATE, t.id, Risk.CLIENT_LOCAL, nowMs)) {
                    putApprovalLocked(Approval(Risk.SCOPE_OPERATE.name, t.id, Risk.CLIENT_LOCAL, nowMs, 0L, BY_MIGRATED))
                    added++
                }
            }
            if (added > 0) {
                saveLocked()
                publishApprovalsLocked()
            }
        }
        return added
    }

    /** Debug/CI helper only (called exclusively from `src/debug/CiHook.kt` on default `CI_SEED` for the main token). */
    internal fun seedLocalForCi(tokenId: String, scopes: Set<String>, nowMs: Long = clock()) {
        synchronized(lock) {
            putApprovalLocked(Approval(Risk.NEW_CLIENT.name, tokenId, Risk.CLIENT_LOCAL, nowMs, 0L, BY_MIGRATED))
            if ("operate" in scopes) {
                putApprovalLocked(Approval(Risk.SCOPE_OPERATE.name, tokenId, Risk.CLIENT_LOCAL, nowMs, 0L, BY_MIGRATED))
            }
            if ("system" in scopes) {
                putApprovalLocked(Approval(Risk.SCOPE_SYSTEM.name, tokenId, Risk.CLIENT_LOCAL, nowMs, nowMs + Risk.SCOPE_SYSTEM_MS, BY_MIGRATED))
            }
            saveLocked()
            publishApprovalsLocked()
        }
    }

    // ── Approval queries & mutations ────────────────────────────────────────

    fun hasApproval(risk: Risk, tokenId: String, client: String, nowMs: Long = clock()): Boolean = synchronized(lock) {
        hasApprovalLocked(risk, tokenId, client, nowMs)
    }

    private fun hasApprovalLocked(risk: Risk, tokenId: String, client: String, nowMs: Long): Boolean {
        return approvalList.any { a ->
            a.risk == risk.name &&
                a.token == tokenId &&
                (risk != Risk.NEW_CLIENT || a.client == client) &&
                a.activeAt(nowMs)
        }
    }

    /** Latest timestamp of any strong confirmation (or beta6 migration) for [tokenId], or 0 if none. */
    fun latestConfirmAt(tokenId: String): Long = synchronized(lock) {
        val fromMap = lastConfirmByToken[tokenId] ?: 0L
        val fromList = approvalList.asSequence().filter { it.token == tokenId }.maxOfOrNull { it.at } ?: 0L
        maxOf(fromMap, fromList)
    }

    fun revoke(approval: Approval) {
        val removed = synchronized(lock) {
            val ok = approvalList.removeAll {
                it.risk == approval.risk && it.token == approval.token && it.client == approval.client && it.at == approval.at
            }
            if (ok) {
                saveLocked()
                publishApprovalsLocked()
            }
            ok
        }
        if (removed) {
            Audit.security("approval_revoked", "risk=${approval.risk} token=${approval.token} client=${approval.client}")
        }
    }

    fun revokeAllForToken(tokenId: String) {
        val removed = synchronized(lock) {
            val ok = approvalList.removeAll { it.token == tokenId }
            lastConfirmByToken.remove(tokenId)
            if (ok) {
                saveLocked()
                publishApprovalsLocked()
            }
            ok
        }
        if (removed) {
            Audit.security("approval_revoked", "token=$tokenId all=true")
        }
    }

    private fun putApprovalLocked(a: Approval) {
        approvalList.removeAll {
            it.risk == a.risk && it.token == a.token && (a.risk != Risk.NEW_CLIENT.name || it.client == a.client)
        }
        approvalList += a
        bumpLastConfirmLocked(a.token, a.at)
    }

    private fun bumpLastConfirmLocked(tokenId: String, at: Long) {
        val prev = lastConfirmByToken[tokenId] ?: 0L
        if (at > prev) lastConfirmByToken[tokenId] = at
    }

    private fun publishApprovalsLocked() {
        val now = clock()
        _approvals.value = approvalList.filter { it.activeAt(now) }.sortedByDescending { it.at }
    }

    private fun saveLocked() {
        val f = storageFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val arr = JSONArray()
            approvalList.forEach { arr.put(it.json()) }
            val af = AtomicFile(f)
            val os = af.startWrite()
            try {
                os.write(arr.toString(2).toByteArray(Charsets.UTF_8))
                os.flush()
                af.finishWrite(os)
            } catch (t: Throwable) {
                af.failWrite(os)
                throw t
            }
        }.onFailure { AppLogger.warning("StrongConfirm", "write approvals failed: ${it.message}") }
    }

    // ── Request lifecycle ───────────────────────────────────────────────────

    fun byId(id: String?): Request? = if (id.isNullOrBlank()) null else synchronized(lock) {
        pruneRequestsLocked(clock())
        requests.firstOrNull { it.id == id }
    }

    fun latestPending(): Request? = synchronized(lock) {
        val now = clock()
        pruneRequestsLocked(now)
        requests.lastOrNull { it.liveAt(now) }
    }

    /** Create a UI-initiated strong confirmation (e.g. `TRUST_SET` in `ui/Security.kt`). */
    fun createUiRequest(
        ctx: Context,
        risk: Risk,
        token: Tokens.Token,
        commandName: String,
        detail: String = "",
        onApprove: () -> Unit,
    ): Request {
        val key = "${risk.name}:${token.id}"
        val now = clock()
        val req = synchronized(lock) {
            pruneRequestsLocked(now)
            requests.removeAll { it.key == key && it.answer == null }
            Request(
                id = newId(),
                risk = risk,
                key = key,
                tokenId = token.id,
                tokenName = token.name,
                tokenScope = token.scope,
                client = Risk.CLIENT_LOCAL,
                commandName = commandName,
                detail = detail,
                at = now,
                validMs = VALID_MS,
                reusable = false,
                allowHold = true,
                onApprove = onApprove,
            ).also {
                requests += it
                publishPendingLocked(now)
            }
        }
        return req
    }

    /** 2.0.0-beta7 (7.4, 7.7): strong confirmation to enable public MCP access (never allows hold-to-confirm). */
    fun createPublicEnableRequest(
        ctx: Context,
        onApprove: () -> Unit,
        onDeny: (() -> Unit)? = null,
    ): Request {
        val key = "${Risk.PUBLIC_ENABLE.name}:main"
        val now = clock()
        return synchronized(lock) {
            pruneRequestsLocked(now)
            requests.removeAll { it.key == key && it.answer == null }
            Request(
                id = newId(),
                risk = Risk.PUBLIC_ENABLE,
                key = key,
                tokenId = Tokens.MAIN,
                tokenName = tr("本机设置", "Phone settings"),
                tokenScope = "operate",
                client = Risk.CLIENT_LOCAL,
                commandName = tr("开启公网 MCP 接入", "Enable public MCP access"),
                detail = tr("开启后将通过 Tailscale Funnel 提供公网 HTTPS MCP 入口", "Enables a public HTTPS MCP endpoint via Tailscale Funnel"),
                at = now,
                validMs = VALID_MS,
                reusable = false,
                allowHold = false,
                onApprove = onApprove,
                onDeny = onDeny,
            ).also {
                requests += it
                publishPendingLocked(now)
            }
        }
    }

    /**
     * 2.0.0-beta7 (7.2, 7.7, 7.8 #5): strong confirmation (`NEW_CLIENT`) for a public OAuth pairing.
     * Displays pairing code, client_name, redirect_uri host, and look/operate picker (never system, never hold).
     */
    fun createPublicPairingRequest(
        ctx: Context,
        pairingId: String,
        clientId: String,
        clientName: String,
        redirectHost: String,
        pairingCode: String,
        selectableScopes: List<String>,
        validMs: Long,
        onApproveWithScope: (String) -> Unit,
        onDeny: () -> Unit,
    ): Request {
        val safeScopes = selectableScopes.filter { it in Tokens.PUBLIC_SCOPES }.distinct().ifEmpty { listOf("look") }
        val key = "PUBLIC_PAIRING:$pairingId"
        val now = clock()
        val req = synchronized(lock) {
            pruneRequestsLocked(now)
            requests.removeAll { it.key == key && it.answer == null }
            Request(
                id = newId(),
                risk = Risk.NEW_CLIENT,
                key = key,
                tokenId = "pending:$pairingId",
                tokenName = clientName,
                tokenScope = "look",
                client = "public:$clientId",
                commandName = tr("公网 MCP 客户端配对", "Public MCP client pairing"),
                detail = redirectHost,
                at = now,
                validMs = validMs,
                reusable = false,
                allowHold = false,
                pairingCode = pairingCode,
                publicClientName = clientName,
                redirectHost = redirectHost,
                selectableScopes = safeScopes,
                defaultScope = "look",
                onApproveWithScope = onApproveWithScope,
                onDeny = onDeny,
            ).also {
                requests += it
                publishPendingLocked(now)
            }
        }
        notify(ctx, req)
        if (AppFront.visible) {
            quiet("public-pairing-open") { com.kilon.agentvscreen.ui.StrongConfirmActivity.open(ctx, req.id) }
        }
        return req
    }

    /**
     * 2.0.0-beta7 (7.2, 7.7): persist NEW_CLIENT (and SCOPE_OPERATE when granted) after a public OAuth pairing
     * is approved on-phone so the client can immediately call MCP tools within its granted scope.
     */
    internal fun recordPublicPairingApproval(tokenId: String, client: String, grantedScope: String, by: String = BY_BIOMETRIC) {
        val now = clock()
        synchronized(lock) {
            putApprovalLocked(Approval(Risk.NEW_CLIENT.name, tokenId, client, now, 0L, by))
            if (grantedScope == "operate") {
                putApprovalLocked(Approval(Risk.SCOPE_OPERATE.name, tokenId, client, now, 0L, by))
            }
            saveLocked()
            publishApprovalsLocked()
        }
    }

    /** Remove a public pairing request (e.g. when settled or cancelled externally). */
    internal fun clearPublicPairing(ctx: Context?, pairingId: String) {
        val key = "PUBLIC_PAIRING:$pairingId"
        val removed = synchronized(lock) {
            val ids = requests.filter { it.key == key }.map { it.id }
            requests.removeAll { it.key == key }
            publishPendingLocked(clock())
            ids
        }
        if (ctx != null) removed.forEach { cancelNotification(ctx, it) }
    }

    /**
     * Block a command for [risk] (6.3 / 6.4): reuses any pending request for the same key (no duplicate notification),
     * waits up to [WAIT_MS], throws `USER_DENIED` or `NEEDS_CONFIRM` (with structured `confirm` object).
     */
    internal fun requireForRisk(ctx: Context, call: Call, risk: Risk, commandName: String) {
        val client = Risk.clientOf(call)
        val key = if (risk == Risk.NEW_CLIENT) "${risk.name}:${call.tokenId}:$client" else "${risk.name}:${call.tokenId}"
        val tokenScope = Tokens.byId(call.tokenId)?.scope ?: scopeSummary(call.scopes)
        val now = clock()
        val (req, created) = getOrCreateRequest(
            risk = risk,
            key = key,
            tokenId = call.tokenId,
            tokenName = call.tokenName,
            tokenScope = tokenScope,
            client = client,
            commandName = commandName,
            detail = "",
            validMs = VALID_MS,
            reusable = false,
            allowHold = call.source != Call.Source.PUBLIC,
            nowMs = now,
        )
        if (created) notify(ctx, req)
        awaitRequest(req, call, waitMsOverride ?: WAIT_MS)
        when (req.answer) {
            true -> {
                synchronized(lock) { req.used = true; publishPendingLocked(clock()) }
                return
            }
            false -> {
                synchronized(lock) { req.used = true; publishPendingLocked(clock()) }
                throw ActionError(
                    "USER_DENIED",
                    tr(
                        "用户在手机上拒绝了确认（${risk.zh}）。这一步没有做。不要换一种方式再试，告诉用户你停在了这里",
                        "The user denied confirmation on the phone (${risk.en}). This step was not done. Don't try another way; tell the user where you stopped",
                    ),
                )
            }
            null -> {
                Audit.security("strong_confirm_timeout", "id=${req.id} risk=${risk.name} token=${req.tokenName} client=${req.client}")
                val leftSec = ((req.at + req.validMs - clock()) / 1000L).coerceAtLeast(1L)
                val data = JSONObject().put(
                    "confirm",
                    JSONObject()
                        .put("risk", risk.name)
                        .put("id", req.id)
                        .put("expires_in_s", leftSec),
                )
                throw ActionError(
                    "NEEDS_CONFIRM",
                    tr(
                        "需要用户在手机上的 Agent-VScreen 里确认（${risk.zh}）。已发通知。确认后把这条命令原样重发。这一步没有做。",
                        "The user must confirm this in Agent-VScreen on the phone (${risk.en}). A notification was sent. Once confirmed, resend the exact same command. This step was not done.",
                    ),
                    data = data,
                )
            }
        }
    }

    /**
     * 2.0.0-beta6 (6.3): `Consent.require` (`HIGH_RISK`) routed through strong confirmation instead of
     * notification "Allow" buttons. Preserves `Consent.WAIT_MS`, `Consent.VALID_MS`, `reusable`, `CONFIRM_PENDING`,
     * and `USER_DENIED`.
     */
    internal fun requireForConsent(
        ctx: Context,
        call: Call?,
        consentKey: String,
        title: String,
        detail: String,
        reusable: Boolean,
        waitMs: Long,
        validMs: Long,
    ) {
        val tokenId = call?.tokenId ?: Tokens.MAIN
        val tokenName = call?.tokenName ?: Tokens.MAIN
        val tokenScope = Tokens.byId(tokenId)?.scope ?: scopeSummary(call?.scopes ?: setOf("look", "operate", "system"))
        val client = Risk.clientOf(call)
        val key = "HIGH_RISK:$tokenId:$consentKey"
        val now = clock()
        // Check reusable / unused recent approval first.
        synchronized(lock) {
            pruneRequestsLocked(now)
            val prev = requests.lastOrNull { it.key == key && it.answer == true && now - it.answeredAt <= validMs && !(it.used && !it.reusable) }
            if (prev != null) {
                prev.used = true
                publishPendingLocked(now)
                return
            }
        }
        val (req, created) = getOrCreateRequest(
            risk = Risk.HIGH_RISK,
            key = key,
            tokenId = tokenId,
            tokenName = tokenName,
            tokenScope = tokenScope,
            client = client,
            commandName = title,
            detail = detail,
            validMs = validMs,
            reusable = reusable,
            allowHold = call?.source != Call.Source.PUBLIC,
            nowMs = now,
        )
        if (created) notify(ctx, req)
        awaitRequest(req, call, waitMsOverride ?: waitMs)
        when (req.answer) {
            true -> {
                synchronized(lock) { req.used = true; publishPendingLocked(clock()) }
                return
            }
            false -> {
                synchronized(lock) { req.used = true; publishPendingLocked(clock()) }
                throw ActionError(
                    "USER_DENIED",
                    tr(
                        "用户在手机上拒绝了：$title。没有做。不要换一种方式再试，告诉用户你停在了这里",
                        "The user denied it on the phone: $title. Not done. Don't try another way; tell the user where you stopped",
                    ),
                )
            }
            null -> {
                Audit.security("strong_confirm_timeout", "id=${req.id} risk=HIGH_RISK token=${req.tokenName} client=${req.client}")
                val leftSec = ((req.at + req.validMs - clock()) / 1000L).coerceAtLeast(1L)
                val data = JSONObject().put(
                    "confirm",
                    JSONObject()
                        .put("risk", Risk.HIGH_RISK.name)
                        .put("id", req.id)
                        .put("expires_in_s", leftSec),
                )
                throw ActionError(
                    "CONFIRM_PENDING",
                    tr(
                        "需要用户在手机上的 Agent-VScreen 强确认页里确认：$title（$detail）。已发通知，等了 ${waitMs / 1000} 秒没有答复，这一步没有做。请用户点通知打开强确认页完成验证；${validMs / 60_000} 分钟内确认后，把这条命令原样重发一次",
                        "The user must confirm this in Agent-VScreen's strong confirmation page: $title ($detail). A notification was sent; no answer within ${waitMs / 1000} s, so this step was not done. Ask the user to tap the notification to open strong confirmation; once confirmed (within ${validMs / 60_000} minutes), send this command again as it is",
                    ),
                    data = data,
                )
            }
        }
    }

    private fun getOrCreateRequest(
        risk: Risk,
        key: String,
        tokenId: String,
        tokenName: String,
        tokenScope: String,
        client: String,
        commandName: String,
        detail: String,
        validMs: Long,
        reusable: Boolean,
        allowHold: Boolean,
        nowMs: Long,
    ): Pair<Request, Boolean> = synchronized(lock) {
        pruneRequestsLocked(nowMs)
        val existing = requests.lastOrNull { it.key == key && it.liveAt(nowMs) }
        if (existing != null) return@synchronized existing to false
        val req = Request(
            id = newId(),
            risk = risk,
            key = key,
            tokenId = tokenId,
            tokenName = tokenName,
            tokenScope = tokenScope,
            client = client,
            commandName = commandName,
            detail = detail,
            at = nowMs,
            validMs = validMs,
            reusable = reusable,
            allowHold = allowHold,
        )
        requests += req
        publishPendingLocked(nowMs)
        req to true
    }

    private fun awaitRequest(req: Request, call: Call?, waitMs: Long) {
        if (waitMs <= 0L) return
        val deadlineNs = System.nanoTime() + waitMs * 1_000_000L
        synchronized(lock) {
            while (req.answer == null && call?.cancelled != true) {
                val leftNs = deadlineNs - System.nanoTime()
                if (leftNs <= 0L) break
                val leftMs = (leftNs + 999_999L) / 1_000_000L
                lock.wait(minOf(250L, leftMs))
            }
        }
    }

    /**
     * Called ONLY by [com.kilon.agentvscreen.ui.StrongConfirmActivity] after L4 biometric/PIN or 6.2 hold+L6 completes.
     */
    internal fun approve(ctx: Context, id: String, by: String, chosenScope: String? = null): Boolean {
        require(by == BY_BIOMETRIC || by == BY_HOLD) { "invalid strong confirm method: $by" }
        val now = clock()
        var holdRefusedRisk: String? = null
        val req = synchronized(lock) {
            val r = requests.firstOrNull { it.id == id && it.answer == null } ?: return false
            if (by == BY_HOLD && !r.allowHold) {
                holdRefusedRisk = r.risk.name
                return@synchronized null
            }
            r.answer = true
            r.answeredBy = by
            r.answeredAt = now
            if (!r.tokenId.startsWith("pending:")) {
                bumpLastConfirmLocked(r.tokenId, now)
            }
            if (r.risk != Risk.HIGH_RISK && r.risk != Risk.TRUST_SET && r.risk != Risk.PUBLIC_ENABLE && r.onApproveWithScope == null) {
                val until = if (r.risk.ttlMs == 0L) 0L else now + r.risk.ttlMs
                putApprovalLocked(Approval(r.risk.name, r.tokenId, r.client, now, until, by))
                saveLocked()
                publishApprovalsLocked()
            }
            publishPendingLocked(now)
            lock.notifyAll()
            r
        }
        if (holdRefusedRisk != null) {
            Audit.security("strong_confirm_hold_refused", "id=$id risk=$holdRefusedRisk")
            return false
        }
        if (req == null) return false
        Audit.security(
            "strong_confirm_approved",
            "id=${req.id} risk=${req.risk.name} token=${req.tokenName} client=${req.client} by=$by",
        )
        if (req.risk == Risk.HIGH_RISK) {
            Audit.consent(req.tokenName, "-", "${req.commandName} — ${req.detail}", "allowed ($by)")
        }
        if (req.onApproveWithScope != null) {
            val allowed = req.selectableScopes.filter { it in Tokens.PUBLIC_SCOPES }.ifEmpty { listOf("look") }
            val granted = chosenScope?.takeIf { it in allowed } ?: req.defaultScope.takeIf { it in allowed } ?: "look"
            runCatching { req.onApproveWithScope.invoke(granted) }
                .onFailure { AppLogger.warning("StrongConfirm", "onApproveWithScope failed: ${it.message}") }
        } else {
            runCatching { req.onApprove?.invoke() }.onFailure { AppLogger.warning("StrongConfirm", "onApprove failed: ${it.message}") }
        }
        cancelNotification(ctx, req.id)
        return true
    }

    /** Called by [com.kilon.agentvscreen.ui.StrongConfirmActivity] when the user taps "Deny". */
    internal fun deny(ctx: Context, id: String): Boolean {
        val now = clock()
        val req = synchronized(lock) {
            val r = requests.firstOrNull { it.id == id && it.answer == null } ?: return false
            r.answer = false
            r.answeredBy = "denied"
            r.answeredAt = now
            publishPendingLocked(now)
            lock.notifyAll()
            r
        }
        Audit.security(
            "strong_confirm_denied",
            "id=${req.id} risk=${req.risk.name} token=${req.tokenName} client=${req.client}",
        )
        if (req.risk == Risk.HIGH_RISK) {
            Audit.consent(req.tokenName, "-", "${req.commandName} — ${req.detail}", "denied")
        }
        runCatching { req.onDeny?.invoke() }.onFailure { AppLogger.warning("StrongConfirm", "onDeny failed: ${it.message}") }
        cancelNotification(ctx, req.id)
        return true
    }

    private fun pruneRequestsLocked(nowMs: Long) {
        requests.removeAll { r ->
            val expiredPending = r.answer == null && nowMs - r.at >= r.validMs
            val expiredAnswered = r.answer != null && nowMs - r.answeredAt > r.validMs
            expiredPending || expiredAnswered
        }
    }

    private fun publishPendingLocked(nowMs: Long) {
        _pending.value = requests.filter { it.liveAt(nowMs) }.sortedByDescending { it.at }
    }

    private fun newId(): String {
        val b = ByteArray(6)
        rng.nextBytes(b)
        return "c_" + b.joinToString("") { "%02x".format(it) }
    }

    private fun scopeSummary(scopes: Set<String>): String = when {
        "system" in scopes -> "system"
        "operate" in scopes -> "operate"
        else -> "look"
    }

    private fun noteId(id: String): Int = 7900 + ((id.hashCode() and 0x7fffffff) % 400)

    /**
     * 6.1: notification has NO "Allow" action button — tapping it only opens [StrongConfirmActivity] on display 0.
     */
    private fun notify(ctx: Context, req: Request) {
        if (!postNotification) return
        quiet("strong-confirm-notify") {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return@quiet
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        tr("需要强确认的安全操作", "Security confirmations"),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val openIntent = Intent(ctx, com.kilon.agentvscreen.ui.StrongConfirmActivity::class.java)
                .putExtra(com.kilon.agentvscreen.ui.StrongConfirmActivity.EXTRA_ID, req.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                ctx,
                noteId(req.id),
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                com.kilon.agentvscreen.ui.MainDisplay.options(),
            )
            val clientText = clientLabel(req.client)
            val body = if (req.pairingCode != null) {
                tr(
                    "配对码 ${req.pairingCode} · ${req.publicClientName ?: req.tokenName}（${req.redirectHost ?: "-"}）。点此核对并确认。",
                    "Pairing code ${req.pairingCode} · ${req.publicClientName ?: req.tokenName} (${req.redirectHost ?: "-"}). Tap to verify and confirm.",
                )
            } else {
                tr(
                    "令牌「${req.tokenName}」· $clientText · ${req.commandName}。点此打开强确认页。",
                    "Token \"${req.tokenName}\" · $clientText · ${req.commandName}. Tap to open strong confirmation.",
                )
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(false)
                .setTimeoutAfter(req.validMs)
                .setContentTitle(tr("需要确认：${req.risk.title}", "Confirm: ${req.risk.title}"))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .build()
            nm.notify(noteId(req.id), n)
        }
    }

    private fun cancelNotification(ctx: Context, id: String) {
        quiet("strong-confirm-cancel") {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(noteId(id))
        }
    }

    fun clientLabel(client: String): String = when (client) {
        Risk.CLIENT_LOCAL -> tr("本机", "This phone (local)")
        else -> client
    }

    // ── L6 real-touch check (6.1 L6 / 6.10 #1) ──────────────────────────────

    /** Pure L6 predicate finalized by Stage 6 spike (6.10 #1). */
    internal fun isRealTouchFields(
        deviceId: Int,
        flags: Int,
        hasDevice: Boolean,
        isVirtual: Boolean,
        supportsTouchscreen: Boolean,
    ): Boolean = deviceId > 0 &&
        (flags and FLAG_IS_ACCESSIBILITY_EVENT) == 0 &&
        hasDevice &&
        !isVirtual &&
        supportsTouchscreen

    /**
     * 2.0.0-beta6 (6.1 L6 / 6.10 #1): used ONLY in 6.2 no-lockscreen hold confirm.
     * Verified on API 30 & 34: our service injection, `adb shell input`, and accessibility `dispatchGesture`
     * all arrive with `deviceId = -1` (`Virtual`, `isVirtual = true`, `supportsTouchscreen = false`) and are
     * rejected 48/48; real touchscreen hardware (`virtio_input_multi_touch_1`) arrives with `deviceId > 0`,
     * `!isVirtual`, `supportsTouchscreen = true`, and no `FLAG_IS_ACCESSIBILITY_EVENT`, passing 16/16.
     */
    fun isRealTouch(ev: MotionEvent): Boolean {
        val id = ev.deviceId
        val dev = if (id > 0) InputDevice.getDevice(id) else null
        return isRealTouchFields(
            deviceId = id,
            flags = ev.flags,
            hasDevice = dev != null,
            isVirtual = dev?.isVirtual ?: true,
            supportsTouchscreen = dev?.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) == true,
        )
    }

    /** Test helper: clear all in-memory state and optionally switch storage file. */
    internal fun resetForTests(file: File? = null) {
        synchronized(lock) {
            requests.clear()
            approvalList.clear()
            lastConfirmByToken.clear()
            storageFile = file
            clock = { System.currentTimeMillis() }
            waitMsOverride = null
            postNotification = true
            _approvals.value = emptyList()
            _pending.value = emptyList()
        }
    }
}
