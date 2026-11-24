package com.kilon.agentvscreen.cmd

import android.content.Context
import android.os.Parcel
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.api.ApiServer
import com.kilon.agentvscreen.api.Mcp
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.core.Gate
import com.kilon.agentvscreen.core.VScreenDisplayService
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.Consent
import com.kilon.agentvscreen.host.Risk
import com.kilon.agentvscreen.host.StrongConfirm
import com.kilon.agentvscreen.host.VScreenAccessibilityService
import com.kilon.agentvscreen.ui.StrongConfirmButtonView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 2.0.0-beta6 (Stage 6): unit tests covering:
 * - Risk.evaluate / Risk.check across NEW_CLIENT, SCOPE_OPERATE, SCOPE_SYSTEM, RATE, SESSION_AGE, HIGH_RISK, TRUST_SET
 * - NEW_CLIENT unapproved allows ONLY status & help (and MCP initialize/tools/list handshake)
 * - Beta6 upgrade migration (lastUsedAt > 0 local only, never tailnet) + AtomicFile approvals.json roundtrip & revoke
 * - L6 real-touch predicate & StrongConfirmButtonView (filterTouchesWhenObscured, a11y CLICK/LONG_CLICK & L6 audit)
 * - L3 single-gate own-package refusal (launch / handoff in / handoff park / handoff unpark -> PROTECTED_APP)
 * - Service-side MOVE_TASK / FOCUS_TASK / REMOVE_TASK / INJECT_DRAG guards
 * - Accessibility display-0 & own-package pre-action guards + audit
 * - 5.9 carryover: Gate.hold() privileged-side self-defense against system_server, systemui, launcher, Shizuku, own pkg, non-package names
 */
@RunWith(RobolectricTestRunner::class)
class V200Beta6Test {

    private lateinit var ctx: Context
    private lateinit var tmpDir: File
    private lateinit var approvalsFile: File
    private val nowMs = AtomicLong(1_700_000_000_000L)

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        tmpDir = File(ctx.cacheDir, "s6-test-${System.nanoTime()}").apply { mkdirs() }
        approvalsFile = File(tmpDir, "approvals.json")
        Audit.init(ctx)
        Audit.clear()
        Tokens.init(ctx)
        Risk.resetForTests()
        StrongConfirm.resetForTests(approvalsFile)
        Risk.clock = { nowMs.get() }
        StrongConfirm.clock = { nowMs.get() }
        StrongConfirm.postNotification = false
        StrongConfirm.waitMsOverride = 10L
    }

    @After
    fun tearDown() {
        Risk.resetForTests()
        StrongConfirm.resetForTests(null)
        tmpDir.deleteRecursively()
    }

    private fun spec(name: String): CommandSpec.Cmd = CommandSpec.of(name)!!
    private fun emptyArgs() = OffloadArgs(emptyList())

    // ── 6.3 / 6.4: Risk dimensions & NEW_CLIENT exemptions ──────────────────

    @Test
    fun newClientBlocksEverythingExceptStatusAndHelp() {
        val handler = VScreenOffloadHandler(ctx)
        val call = Call("s1", tokenId = "tok1", tokenName = "agent-a", scopes = setOf("look", "operate", "system"), peer = null)
        assertNull(Risk.evaluate(call, spec("status"), emptyArgs(), nowMs.get()))
        // `help` is handled before CommandSpec / Risk.check so it always succeeds even for an unapproved client.
        assertEquals(0, Call.with(call) { handler.handle(NativeOffloadRequest(listOf("vscreen", "help"), "s1")) }.exitCode)
        assertEquals(Risk.NEW_CLIENT, Risk.evaluate(call, spec("apps"), emptyArgs(), nowMs.get()))
        assertEquals(Risk.NEW_CLIENT, Risk.evaluate(call, spec("look"), emptyArgs(), nowMs.get()))
        assertEquals(Risk.NEW_CLIENT, Risk.evaluate(call, spec("launch"), emptyArgs(), nowMs.get()))

        // Approve NEW_CLIENT for local; local look commands now pass, tailnet peer still blocked.
        val req = StrongConfirm.createUiRequest(ctx, Risk.NEW_CLIENT, Tokens.Token("tok1", "agent-a", "k", "system", 1L), "apps") {}
        assertTrue(StrongConfirm.approve(ctx, req.id, StrongConfirm.BY_BIOMETRIC))
        assertNull(Risk.evaluate(call, spec("apps"), emptyArgs(), nowMs.get()))

        val remoteCall = Call("s1", tokenId = "tok1", tokenName = "agent-a", scopes = setOf("look", "operate", "system"), peer = "laptop.ts.net")
        assertNull(Risk.evaluate(remoteCall, spec("status"), emptyArgs(), nowMs.get()))
        assertEquals(0, Call.with(remoteCall) { handler.handle(NativeOffloadRequest(listOf("vscreen", "help"), "s1")) }.exitCode)
        assertEquals(Risk.NEW_CLIENT, Risk.evaluate(remoteCall, spec("apps"), emptyArgs(), nowMs.get()))
    }

    @Test
    fun scopeOperateSystemSessionAgeAndRateTriggerAndExpire() {
        // 2.0.0-beta8 (B2 / Ruling 5): default level is STANDARD; explicitly exercise STRICT 8h/24h expiry rules here.
        Risk.setSecurityLevel(ctx, Risk.SecurityLevel.STRICT)
        val t0 = nowMs.get()
        val call = Call("s1", tokenId = "tok2", tokenName = "agent-b", scopes = setOf("look", "operate", "system"), peer = null)
        val tok = Tokens.Token("tok2", "agent-b", "k", "system", t0)

        // 1) Approve NEW_CLIENT first.
        val rNew = StrongConfirm.createUiRequest(ctx, Risk.NEW_CLIENT, tok, "launch") {}
        StrongConfirm.approve(ctx, rNew.id, StrongConfirm.BY_BIOMETRIC)

        // 2) SCOPE_OPERATE does not trigger on LOOK, triggers on OPERATE, passes once approved (permanent).
        assertNull(Risk.evaluate(call, spec("apps"), emptyArgs(), t0))
        assertEquals(Risk.SCOPE_OPERATE, Risk.evaluate(call, spec("launch"), emptyArgs(), t0))
        val rOp = StrongConfirm.createUiRequest(ctx, Risk.SCOPE_OPERATE, tok, "launch") {}
        StrongConfirm.approve(ctx, rOp.id, StrongConfirm.BY_BIOMETRIC)
        assertNull(Risk.evaluate(call, spec("launch"), emptyArgs(), t0))

        // 3) SCOPE_SYSTEM triggers on SYSTEM (`stop --force`), valid 24h, expires at 24h + 1ms.
        val forceArgs = VScreenOptions.args("stop", listOf("com.example.a", "--force"))
        assertEquals(Risk.SCOPE_SYSTEM, Risk.evaluate(call, spec("stop"), forceArgs, t0))
        val rSys = StrongConfirm.createUiRequest(ctx, Risk.SCOPE_SYSTEM, tok, "stop") {}
        StrongConfirm.approve(ctx, rSys.id, StrongConfirm.BY_BIOMETRIC)
        assertNull(Risk.evaluate(call, spec("stop"), forceArgs, t0 + Risk.SESSION_AGE_MS))

        // 4) SESSION_AGE: at 8h still passes; at 8h + 1ms triggers on OPERATE/SYSTEM but NOT on LOOK.
        val tWithin = t0 + Risk.SESSION_AGE_MS
        assertNull(Risk.evaluate(call, spec("launch"), emptyArgs(), tWithin))
        val tExpired = t0 + Risk.SESSION_AGE_MS + 1L
        assertNull(Risk.evaluate(call, spec("apps"), emptyArgs(), tExpired))
        assertEquals(Risk.SESSION_AGE, Risk.evaluate(call, spec("launch"), emptyArgs(), tExpired))
        assertEquals(Risk.SESSION_AGE, Risk.evaluate(call, spec("stop"), forceArgs, tExpired))

        // Re-confirm SESSION_AGE at t0 + 18h so SESSION_AGE is fresh across the 24h SCOPE_SYSTEM boundary.
        val tReconfirm = t0 + 18 * 60 * 60_000L
        nowMs.set(tReconfirm)
        val rAge = StrongConfirm.createUiRequest(ctx, Risk.SESSION_AGE, tok, "launch") {}
        StrongConfirm.approve(ctx, rAge.id, StrongConfirm.BY_HOLD)
        assertNull(Risk.evaluate(call, spec("launch"), emptyArgs(), tReconfirm + 1_000L))
        // SCOPE_SYSTEM still valid at 24h - 1ms, expires at 24h + 1ms.
        assertNull(Risk.evaluate(call, spec("stop"), forceArgs, t0 + Risk.SCOPE_SYSTEM_MS - 1L))
        assertEquals(Risk.SCOPE_SYSTEM, Risk.evaluate(call, spec("stop"), forceArgs, t0 + Risk.SCOPE_SYSTEM_MS + 1L))

        // 5) RATE: 120 input actions within 60s allowed; 121st triggers RATE; approval lasts 60 min; sliding window expires.
        val tRate = tReconfirm + 10_000L
        nowMs.set(tRate)
        repeat(Risk.RATE_MAX_PER_MIN) { Risk.recordInputAction("tok2", tRate) }
        assertNull(Risk.evaluate(call, spec("launch"), emptyArgs(), tRate))
        Risk.recordInputAction("tok2", tRate) // 121st
        assertEquals(Risk.RATE, Risk.evaluate(call, spec("launch"), emptyArgs(), tRate))

        // Approve RATE -> quiet for 60 min even when still >120/min.
        val rRate = StrongConfirm.createUiRequest(ctx, Risk.RATE, tok, "act") {}
        StrongConfirm.approve(ctx, rRate.id, StrongConfirm.BY_BIOMETRIC)
        assertNull(Risk.evaluate(call, spec("launch"), emptyArgs(), tRate + 500L))

        // After 60 min + 1ms, RATE approval expired; fresh burst of 121 actions triggers RATE again.
        val tAfterRateExp = tRate + Risk.RATE_APPROVAL_MS + 1L
        nowMs.set(tAfterRateExp)
        // Old 60s window emptied -> no RATE yet.
        assertEquals(0, Risk.inputActionCount("tok2", tAfterRateExp))
        assertNull(Risk.evaluate(call, spec("launch"), emptyArgs(), tAfterRateExp))
        repeat(Risk.RATE_MAX_PER_MIN + 1) { Risk.recordInputAction("tok2", tAfterRateExp) }
        assertEquals(Risk.RATE, Risk.evaluate(call, spec("launch"), emptyArgs(), tAfterRateExp))
    }

    @Test
    fun needsConfirmPayloadDedupAndReloadFromAtomicFile() {
        val call = Call("s1", tokenId = "tok3", tokenName = "agent-c", scopes = setOf("look", "operate"), peer = null)
        var err1: com.kilon.agentvscreen.core.BackgroundOperation.ActionError? = null
        try {
            Risk.check(ctx, call, spec("apps"), emptyArgs())
        } catch (e: com.kilon.agentvscreen.core.BackgroundOperation.ActionError) {
            err1 = e
        }
        assertNotNull(err1)
        assertEquals("NEEDS_CONFIRM", err1!!.code)
        assertEquals("ask_user", Codes.next("NEEDS_CONFIRM"))
        val c1 = err1.data!!.getJSONObject("confirm")
        assertEquals("NEW_CLIENT", c1.getString("risk"))
        val cid = c1.getString("id")
        assertTrue(cid.startsWith("c_"))
        assertTrue(c1.getLong("expires_in_s") in 1..600)

        // Second call within VALID_MS reuses the exact same request id (no duplicate notification).
        var err2: com.kilon.agentvscreen.core.BackgroundOperation.ActionError? = null
        try {
            Risk.check(ctx, call, spec("apps"), emptyArgs())
        } catch (e: com.kilon.agentvscreen.core.BackgroundOperation.ActionError) {
            err2 = e
        }
        assertEquals(cid, err2!!.data!!.getJSONObject("confirm").getString("id"))

        // Approve -> persisted in approvals.json via AtomicFile -> survives reload -> revoke removes it.
        assertTrue(StrongConfirm.approve(ctx, cid, StrongConfirm.BY_BIOMETRIC))
        assertTrue(approvalsFile.isFile)
        val raw = approvalsFile.readText()
        assertTrue(raw.contains("\"by\": \"biometric\""))
        assertTrue(raw.contains("\"risk\": \"NEW_CLIENT\""))

        // Reload from disk into a clean in-memory state.
        StrongConfirm.initFile(approvalsFile)
        assertTrue(StrongConfirm.hasApproval(Risk.NEW_CLIENT, "tok3", Risk.CLIENT_LOCAL, nowMs.get()))

        // Revoke removes from memory & disk and writes audit.
        val stored = StrongConfirm.approvals.value.first { it.token == "tok3" }
        StrongConfirm.revoke(stored)
        assertFalse(StrongConfirm.hasApproval(Risk.NEW_CLIENT, "tok3", Risk.CLIENT_LOCAL, nowMs.get()))
        assertTrue(Audit.tail(20).any { it.optString("what") == "approval_revoked" })
    }

    @Test
    fun beta6MigrationMigratesOnlyUsedLocalTokensNeverTailnet() {
        val t0 = nowMs.get()
        val usedOperate = Tokens.Token("u1", "used-op", "k1", "operate", t0 - 10_000, lastUsedAt = t0 - 5_000)
        val usedLook = Tokens.Token("u2", "used-look", "k2", "look", t0 - 10_000, lastUsedAt = t0 - 5_000)
        val unusedOp = Tokens.Token("u3", "unused-op", "k3", "operate", t0 - 10_000, lastUsedAt = 0L)
        val revokedOp = Tokens.Token("u4", "revoked-op", "k4", "operate", t0 - 10_000, lastUsedAt = t0 - 1_000, revoked = true)

        val added = StrongConfirm.migrateTokens(listOf(usedOperate, usedLook, unusedOp, revokedOp), t0)
        assertEquals(3, added) // u1: NEW_CLIENT + SCOPE_OPERATE, u2: NEW_CLIENT only

        assertTrue(StrongConfirm.hasApproval(Risk.NEW_CLIENT, "u1", Risk.CLIENT_LOCAL, t0))
        assertTrue(StrongConfirm.hasApproval(Risk.SCOPE_OPERATE, "u1", Risk.CLIENT_LOCAL, t0))
        assertFalse(StrongConfirm.hasApproval(Risk.NEW_CLIENT, "u1", "macbook", t0)) // tailnet NOT migrated

        assertTrue(StrongConfirm.hasApproval(Risk.NEW_CLIENT, "u2", Risk.CLIENT_LOCAL, t0))
        assertFalse(StrongConfirm.hasApproval(Risk.SCOPE_OPERATE, "u2", Risk.CLIENT_LOCAL, t0))

        assertFalse(StrongConfirm.hasApproval(Risk.NEW_CLIENT, "u3", Risk.CLIENT_LOCAL, t0))
        assertFalse(StrongConfirm.hasApproval(Risk.NEW_CLIENT, "u4", Risk.CLIENT_LOCAL, t0))

        val records = StrongConfirm.approvals.value
        assertTrue(records.all { it.by == StrongConfirm.BY_MIGRATED && it.client == Risk.CLIENT_LOCAL })
    }

    @Test
    fun consentRequireRoutesThroughStrongConfirmAndTrustSetRunsOnApproveOnly() {
        val call = Call("s1", tokenId = "tokC", tokenName = "agent-c", scopes = setOf("look", "operate", "system"))
        // Consent.require creates a StrongConfirm HIGH_RISK request and throws CONFIRM_PENDING on timeout.
        var caught: com.kilon.agentvscreen.core.BackgroundOperation.ActionError? = null
        Call.with(call) {
            try {
                Consent.require(ctx, "pay:demo", "付款", "支付 10 元", reusable = true, kind = "pay")
            } catch (e: com.kilon.agentvscreen.core.BackgroundOperation.ActionError) {
                caught = e
            }
        }
        assertEquals("CONFIRM_PENDING", caught?.code)
        val reqId = caught!!.data!!.getJSONObject("confirm").getString("id")
        assertTrue(StrongConfirm.pending.value.any { it.id == reqId && it.risk == Risk.HIGH_RISK })

        // Approving allows resend (and reusable works twice within VALID_MS).
        assertTrue(StrongConfirm.approve(ctx, reqId, StrongConfirm.BY_BIOMETRIC))
        Call.with(call) {
            Consent.require(ctx, "pay:demo", "付款", "支付 10 元", reusable = true, kind = "pay")
            Consent.require(ctx, "pay:demo", "付款", "支付 10 元", reusable = true, kind = "pay")
        }

        // TRUST_SET runs callback ONLY on approve, never on deny.
        val tok = Tokens.Token("tokT", "agent-t", "k", "operate", nowMs.get())
        val granted = AtomicBoolean(false)
        val rDeny = StrongConfirm.createUiRequest(ctx, Risk.TRUST_SET, tok, "免确认 30 分钟") { granted.set(true) }
        StrongConfirm.deny(ctx, rDeny.id)
        assertFalse(granted.get())
        val rOk = StrongConfirm.createUiRequest(ctx, Risk.TRUST_SET, tok, "免确认 30 分钟") { granted.set(true) }
        StrongConfirm.approve(ctx, rOk.id, StrongConfirm.BY_HOLD)
        assertTrue(granted.get())
    }

    // ── 6.1 L6 / 6.9 / 6.10 #1: Real-touch predicate & StrongConfirmButtonView ─

    @Test
    fun l6RealTouchPredicateAndConfirmButtonRejectSyntheticAndA11y() {
        // Real touchscreen hardware (matches spike API 30 & 34: deviceId=4, !isVirtual, supportsTouchscreen, flags=0).
        assertTrue(StrongConfirm.isRealTouchFields(deviceId = 4, flags = 0x0, hasDevice = true, isVirtual = false, supportsTouchscreen = true))
        // Reject our service / adb input / a11y dispatchGesture (deviceId=-1, Virtual).
        assertFalse(StrongConfirm.isRealTouchFields(deviceId = -1, flags = 0x0, hasDevice = true, isVirtual = true, supportsTouchscreen = false))
        // Reject deviceId == 0.
        assertFalse(StrongConfirm.isRealTouchFields(deviceId = 0, flags = 0x0, hasDevice = false, isVirtual = true, supportsTouchscreen = false))
        // Reject missing InputDevice.
        assertFalse(StrongConfirm.isRealTouchFields(deviceId = 5, flags = 0x0, hasDevice = false, isVirtual = false, supportsTouchscreen = true))
        // Reject virtual device even with positive id.
        assertFalse(StrongConfirm.isRealTouchFields(deviceId = 5, flags = 0x0, hasDevice = true, isVirtual = true, supportsTouchscreen = true))
        // Reject non-touchscreen device (e.g. keyboard).
        assertFalse(StrongConfirm.isRealTouchFields(deviceId = 2, flags = 0x0, hasDevice = true, isVirtual = false, supportsTouchscreen = false))
        // Reject FLAG_IS_ACCESSIBILITY_EVENT (0x800) even with positive id.
        assertFalse(StrongConfirm.isRealTouchFields(deviceId = 4, flags = 0x802, hasDevice = true, isVirtual = false, supportsTouchscreen = true))

        // StrongConfirmButtonView: filterTouchesWhenObscured=true, no OnLongClickListener, ignores a11y CLICK/LONG_CLICK & synthetic touch with audit.
        var approved = false
        val btn = StrongConfirmButtonView(
            context = ctx,
            requestId = "c_test01",
            biometricMode = false,
            enabledAction = true,
            onTriggerBiometric = { approved = true },
            onHoldCompleted = { approved = true },
        )
        assertTrue(btn.filterTouchesWhenObscured)
        assertFalse(btn.isLongClickable)
        assertFalse(btn.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertFalse(btn.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        assertFalse(btn.performClick())
        assertFalse(btn.performLongClick())
        assertFalse(approved)

        val t = SystemClock.uptimeMillis()
        val injected = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 50f, 20f, 0)
        try {
            assertFalse(btn.onTouchEvent(injected))
        } finally {
            injected.recycle()
        }
        assertFalse(approved)
        val audits = Audit.tail(30).map { it.optString("what") }
        assertTrue("expected strong_confirm_a11y_ignored audit", "strong_confirm_a11y_ignored" in audits)
        assertTrue("expected strong_confirm_l6_ignored audit", "strong_confirm_l6_ignored" in audits)
    }

    // ── 6.1 L3 & 6.10 #3: Single-gate own-package & service & a11y guards ───

    @Test
    fun ownPackageRefusedWithProtectedAppAcrossLaunchAndHandoffAndService() {
        val h = VScreenOffloadHandler(ctx)
        val own = ctx.packageName

        // App-side single gate: launch, handoff in, handoff park, handoff unpark all return PROTECTED_APP.
        for (argv in listOf(
            listOf("vscreen", "launch", own),
            listOf("vscreen", "handoff", "in", own),
            listOf("vscreen", "handoff", "park", own),
            listOf("vscreen", "handoff", "unpark", own),
        )) {
            val res = h.handle(NativeOffloadRequest(argv, "s-own"))
            assertEquals("command $argv must return PROTECTED_APP", "PROTECTED_APP", res.codeOrDefault)
            assertEquals("PROTECTED_APP next must be none", "none", Codes.next(res.codeOrDefault))
        }

        // Service-side MOVE_TASK helper refuses own package to non-0 display, allows other pkgs and display 0.
        assertTrue(VScreenDisplayService.shouldRefuseMoveTask(1, listOf(BuildConfig.APPLICATION_ID), BuildConfig.APPLICATION_ID))
        assertTrue(VScreenDisplayService.shouldRefuseMoveTask(2, listOf("com.other.app", BuildConfig.APPLICATION_ID), BuildConfig.APPLICATION_ID))
        assertFalse(VScreenDisplayService.shouldRefuseMoveTask(0, listOf(BuildConfig.APPLICATION_ID), BuildConfig.APPLICATION_ID))
        assertFalse(VScreenDisplayService.shouldRefuseMoveTask(1, listOf("com.other.app"), BuildConfig.APPLICATION_ID))

        // Service-side Binder transactions: FOCUS_TASK (d0 & omitted), REMOVE_TASK (d0), INJECT_DRAG (d0) all refused.
        val svc = VScreenDisplayService()
        fun callIntBool(tx: Int, write: (Parcel) -> Unit): Boolean {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                write(data)
                assertTrue(svc.transact(tx, data, reply, 0))
                reply.setDataPosition(0)
                assertEquals(0, reply.readInt())
                return reply.readInt() != 0
            } finally { data.recycle(); reply.recycle() }
        }
        assertFalse(callIntBool(VScreenDisplayService.FOCUS_TASK) { it.writeInt(42); it.writeInt(0) })
        assertFalse(callIntBool(VScreenDisplayService.FOCUS_TASK) { it.writeInt(42) })
        assertFalse(callIntBool(VScreenDisplayService.REMOVE_TASK) { it.writeInt(42); it.writeInt(0) })
        assertFalse(callIntBool(VScreenDisplayService.INJECT_DRAG) {
            it.writeInt(0); it.writeInt(10); it.writeInt(10); it.writeInt(10); it.writeInt(10); it.writeInt(100); it.writeInt(100)
        })
    }

    @Test
    fun accessibilityActionGateBlocksDisplay0AndOwnPackageWithAudit() {
        val own = BuildConfig.APPLICATION_ID
        assertFalse(VScreenAccessibilityService.canPerformActionOn(null, "com.example.app", "com.example.app", own))
        assertFalse(VScreenAccessibilityService.canPerformActionOn(0, "com.example.app", "com.example.app", own))
        assertFalse(VScreenAccessibilityService.canPerformActionOn(2, own, own, own))
        assertFalse(VScreenAccessibilityService.canPerformActionOn(2, "com.example.app", own, own))
        assertTrue(VScreenAccessibilityService.canPerformActionOn(2, "com.example.app", "com.example.app", own))

        // verifyNodeForAction with unattached Robolectric node (window == null -> display -1) must block and audit.
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.packageName = own
            assertFalse(VScreenAccessibilityService.verifyNodeForAction(node, "ACTION_SET_TEXT"))
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
        assertTrue(Audit.tail(10).any { it.optString("what") == "a11y_action_blocked" })
    }

    // ── 5.9 / 6.9 Carryover: Gate.hold() privileged self-defense ────────────

    @Test
    fun gateHoldRefusesProtectedSystemLauncherShizukuOwnAndNonPackageProcs() {
        val launchers = setOf("com.example.launcher", "com.example.home2")
        val own = "com.kilon.agentvscreen"
        assertFalse(Gate.isHoldableProcess("system_server", own, launchers))
        assertFalse(Gate.isHoldableProcess("android", own, launchers))
        assertFalse(Gate.isHoldableProcess("com.android.systemui", own, launchers))
        assertFalse(Gate.isHoldableProcess("com.android.systemui:sub", own, launchers))
        assertFalse(Gate.isHoldableProcess("com.example.launcher", own, launchers))
        assertFalse(Gate.isHoldableProcess("com.example.launcher:recents", own, launchers))
        assertFalse(Gate.isHoldableProcess("moe.shizuku.privileged.api", own, launchers))
        assertFalse(Gate.isHoldableProcess(own, own, launchers))
        assertFalse(Gate.isHoldableProcess("$own:root", own, launchers))
        assertFalse(Gate.isHoldableProcess("zygote", own, launchers))
        assertFalse(Gate.isHoldableProcess("adbd", own, launchers))
        assertFalse(Gate.isHoldableProcess("/system/bin/sh", own, launchers))
        assertFalse(Gate.isHoldableProcess("--flag", own, launchers))
        assertTrue(Gate.isHoldableProcess("com.example.target", own, launchers))
        assertTrue(Gate.isHoldableProcess("com.example.target:remote", own, launchers))

        // Verify Gate.hold() itself only passes safe processes to shell.
        val cmds = CopyOnWriteArrayList<List<String>>()
        val holdFile = File(tmpDir, "holds.txt")
        val gate = Gate(
            service = null,
            clock = Gate.Clock { 10_000L },
            shell = Gate.Shell { argv, _ -> cmds += argv; 0 },
            startWatchThread = false,
            holdFile = holdFile,
            readWchan = { emptyList() },
            resolveLaunchers = { launchers },
        )
        try {
            val n = gate.hold(
                listOf(
                    "system_server",
                    "com.android.systemui",
                    "com.example.launcher",
                    "moe.shizuku.privileged.api",
                    own,
                    "$own:bg",
                    "zygote64",
                    "com.example.target",
                    "com.example.target:worker",
                ),
            )
            assertEquals(2, n)
            assertEquals(
                listOf(
                    listOf("am", "freeze", "--sticky", "com.example.target"),
                    listOf("am", "freeze", "--sticky", "com.example.target:worker"),
                ),
                cmds.toList(),
            )
        } finally {
            gate.stop()
        }

        // Verify launcher parser handles `cmd package query-activities --brief` output.
        val parsed = Gate.parseLauncherOutput(
            "priority=0 preferredOrder=0 match=0x108000\n" +
                "  com.example.launcher/.HomeActivity\n" +
                "  com.example.home2/com.example.home2.Main\n",
        )
        assertEquals(setOf("com.example.launcher", "com.example.home2"), parsed)
    }

    // ── 6.9: MCP handshake works while NEW_CLIENT is unapproved / pending ───

    @Test
    fun mcpInitializeAndToolsListNeverBlockedByNewClient() {
        val (tok, _) = Tokens.create("mcp-fresh", "operate")
        val server = ApiServer(ctx)

        fun mcpReq(json: JSONObject): JSONObject {
            val req = ApiServer.Req("POST", "/mcp", emptyMap(), mapOf("content-type" to "application/json"), json.toString().toByteArray())
            req.auth = ApiServer.Auth(tok, signed = false, nonce = null)
            val resp = Mcp.handle(server, req)
            assertEquals(200, resp.status)
            return JSONObject(String(resp.body, Charsets.UTF_8))
        }

        // 1) initialize succeeds without NEW_CLIENT approval.
        val initResp = mcpReq(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize").put("params", JSONObject().put("protocolVersion", "2025-06-18")))
        assertTrue(initResp.has("result"))
        assertEquals("agent-vscreen", initResp.getJSONObject("result").getJSONObject("serverInfo").getString("name"))

        // 2) tools/list succeeds without NEW_CLIENT approval.
        val listResp = mcpReq(JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list"))
        assertTrue(listResp.getJSONObject("result").getJSONArray("tools").length() > 5)

        // 3) tools/call vscreen_apps hits NEEDS_CONFIRM (creates pending confirm).
        val appsResp = mcpReq(
            JSONObject().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call")
                .put("params", JSONObject().put("name", "vscreen_apps").put("arguments", JSONObject())),
        )
        val appsRes = appsResp.getJSONObject("result")
        assertTrue(appsRes.getBoolean("isError"))
        assertEquals("NEEDS_CONFIRM", appsRes.getJSONObject("structuredContent").getString("code"))
        assertEquals("NEW_CLIENT", appsRes.getJSONObject("structuredContent").getJSONObject("confirm").getString("risk"))

        // 4) While confirm is pending, MCP initialize, tools/list, and vscreen_help still succeed!
        val initAgain = mcpReq(JSONObject().put("jsonrpc", "2.0").put("id", 4).put("method", "initialize"))
        assertTrue(initAgain.has("result"))
        val listAgain = mcpReq(JSONObject().put("jsonrpc", "2.0").put("id", 5).put("method", "tools/list"))
        assertTrue(listAgain.getJSONObject("result").getJSONArray("tools").length() > 5)
        val helpResp = mcpReq(
            JSONObject().put("jsonrpc", "2.0").put("id", 6).put("method", "tools/call")
                .put("params", JSONObject().put("name", "vscreen").put("arguments", JSONObject().put("command", "help"))),
        )
        assertFalse(helpResp.getJSONObject("result").getBoolean("isError"))
    }
}
