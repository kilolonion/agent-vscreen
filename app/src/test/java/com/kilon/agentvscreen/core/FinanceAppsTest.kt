package com.kilon.agentvscreen.core

import android.app.Application
import android.graphics.Rect
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.cmd.El
import com.kilon.agentvscreen.cmd.ElSet
import com.kilon.agentvscreen.cmd.VScreenOffloadHandler
import com.kilon.agentvscreen.cmd.evaluatePayGuardContext
import com.kilon.agentvscreen.cmd.payGuardCheck
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.Consent
import com.kilon.agentvscreen.host.StrongConfirm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for P2-4 financial/payment app default confirm-every-time rules and PR #66 Fix 06
 * (same-page payment context classification that never uses stale `lastElements` across page transitions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FinanceAppsTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(ctx.filesDir, "p24-fin-test-${System.nanoTime()}").apply { mkdirs() }
        ApiConfig.init(ctx)
        Tokens.init(ctx)
        Audit.init(ctx)
        StrongConfirm.resetForTests(File(tempDir, "approvals.json"))
        StrongConfirm.postNotification = false
        StrongConfirm.waitMsOverride = 10L
        VScreenStore.setRuleForTest("com.eg.android.AlipayGphone", null)
        VScreenStore.setRuleForTest("com.paypal.android.p2pmobile", null)
        VScreenStore.setRuleForTest("com.tencent.mm", null)
        VScreenStore.setRuleForTest("com.example.notes", null)
        VScreenStore.setRuleForTest("com.example.shop", null)
    }

    @After
    fun tearDown() {
        StrongConfirm.waitMsOverride = null
        StrongConfirm.resetForTests()
        synchronized(VScreenOffloadHandler.lastElements) {
            VScreenOffloadHandler.lastElements.remove("s-pay-ctx")
        }
        VScreenStore.setRuleForTest("com.eg.android.AlipayGphone", null)
        VScreenStore.setRuleForTest("com.paypal.android.p2pmobile", null)
        VScreenStore.setRuleForTest("com.tencent.mm", null)
        VScreenStore.setRuleForTest("com.example.notes", null)
        VScreenStore.setRuleForTest("com.example.shop", null)
        tempDir.deleteRecursively()
    }

    @Test
    fun curatedFinancialAppsDefaultToAskWhileSocialSuperAppsDoNot() {
        assertTrue(FinanceApps.isFinancial("com.eg.android.AlipayGphone"))
        assertTrue(FinanceApps.isFinancial("com.unionpay"))
        assertTrue(FinanceApps.isFinancial("com.icbc"))
        assertTrue(FinanceApps.isFinancial("com.paypal.android.p2pmobile"))
        assertTrue(FinanceApps.isFinancial("com.google.android.apps.walletnfcrel"))

        // General messaging super-apps stay excluded from blanket app-level ASK (protected at payment-step level).
        assertFalse(FinanceApps.isFinancial("com.tencent.mm"))
        assertFalse(FinanceApps.isFinancial("com.tencent.mobileqq"))
        assertFalse(FinanceApps.isFinancial("org.telegram.messenger"))
        assertFalse(FinanceApps.isFinancial("com.example.notes"))

        assertEquals(VScreenStore.Rule.ASK, VScreenStore.ruleFor("com.eg.android.AlipayGphone"))
        assertTrue(VScreenStore.isDefaultFinancialAsk("com.eg.android.AlipayGphone"))
        assertEquals(null, VScreenStore.ruleFor("com.tencent.mm"))
        assertEquals(null, VScreenStore.ruleFor("com.example.notes"))
    }

    @Test
    fun guardEnforcesDefaultAskAndHonorsExplicitAllowOrBlockOverrides() {
        assertTrue(Guard.check(Guard.Act.INPUT, "com.eg.android.AlipayGphone") is Guard.Verdict.Ask)
        assertTrue(Guard.check(Guard.Act.READ, "com.eg.android.AlipayGphone") is Guard.Verdict.Allow)
        assertTrue(Guard.check(Guard.Act.BACK, "com.eg.android.AlipayGphone") is Guard.Verdict.Allow)

        // Explicit user ALLOW relaxes default financial ASK (ruleFor returns null = unrestricted)
        VScreenStore.setRuleForTest("com.eg.android.AlipayGphone", VScreenStore.Rule.ALLOW)
        assertFalse(VScreenStore.isDefaultFinancialAsk("com.eg.android.AlipayGphone"))
        assertEquals(VScreenStore.Rule.ALLOW, VScreenStore.explicitRuleFor("com.eg.android.AlipayGphone"))
        assertEquals(null, VScreenStore.ruleFor("com.eg.android.AlipayGphone"))
        assertTrue(Guard.check(Guard.Act.INPUT, "com.eg.android.AlipayGphone") is Guard.Verdict.Allow)

        // Explicit user BLOCK overrides financial default
        VScreenStore.setRuleForTest("com.paypal.android.p2pmobile", VScreenStore.Rule.BLOCK)
        assertTrue(Guard.check(Guard.Act.OPEN, "com.paypal.android.p2pmobile") is Guard.Verdict.Deny)
    }

    // ── Fix 06: Fresh same-page payment context across page transitions ──────

    @Test
    fun payGuardUsesCurrentPageElementsNotStaleLastElementsInBothDirectionsAndMidActSwitch() {
        val sid = "s-pay-ctx"
        val handler = VScreenOffloadHandler(ctx)
        val normalEls = listOf(
            El(1, "设置已保存", Rect(100, 200, 500, 280), fromA11y = true),
            El(2, "确定", Rect(300, 1200, 780, 1320), fromA11y = true, clickable = true),
        )
        val paymentEls = listOf(
            El(10, "订单支付 应付金额 ¥199.00", Rect(100, 200, 900, 300), fromA11y = true),
            El(11, "付款方式 零钱", Rect(100, 350, 900, 430), fromA11y = true),
            El(12, "确定", Rect(300, 1200, 780, 1320), fromA11y = true, clickable = true),
        )

        // 1. Pure same-page classification on generic (non-finance) super-app `com.tencent.mm`
        assertEquals(
            "Generic '确定' on payment page must escalate to Consent.MONEY",
            Consent.MONEY,
            evaluatePayGuardContext(ctx, "com.tencent.mm", "确定", 1260, paymentEls),
        )
        assertNull(
            "Generic '确定' on normal page must NOT trigger payment confirmation",
            evaluatePayGuardContext(ctx, "com.tencent.mm", "确定", 1260, normalEls),
        )

        // 2. Same-act transition: Normal page (in stale lastElements) -> Payment page (in live contextEls)
        synchronized(VScreenOffloadHandler.lastElements) {
            VScreenOffloadHandler.lastElements[sid] = ElSet(1L, System.currentTimeMillis(), normalEls, "com.tencent.mm/.NormalUi")
        }
        val call = Call(sid, Tokens.MAIN, "main", setOf("look", "operate", "system"))
        Call.with(call) {
            try {
                handler.payGuardCheck(
                    sid = sid,
                    displayId = 1,
                    label = "确定",
                    x = 540,
                    y = 1260,
                    fromRule = false,
                    contextEls = paymentEls,
                    pkgOverride = "com.tencent.mm",
                )
                fail("Expected CONFIRM_PENDING when mid-act navigation moved from normal page to payment page")
            } catch (e: com.kilon.agentvscreen.cmd.StepRefused) {
                assertEquals("CONFIRM_PENDING", e.code)
            }
        }

        // 3. Reverse transition: Payment page (in stale lastElements) -> Normal page (in live contextEls)
        synchronized(VScreenOffloadHandler.lastElements) {
            VScreenOffloadHandler.lastElements[sid] = ElSet(1L, System.currentTimeMillis(), paymentEls, "com.tencent.mm/.PayUi")
        }
        Call.with(call) {
            // Must return cleanly without false-positive confirmation!
            handler.payGuardCheck(
                sid = sid,
                displayId = 1,
                label = "确定",
                x = 540,
                y = 1260,
                fromRule = false,
                contextEls = normalEls,
                pkgOverride = "com.tencent.mm",
            )
        }

        // 4. Same-page `besideInput` check: "清空" beside an editable box on the current page is benign even if
        //    stale lastElements had no editable box at that row.
        val searchBoxEls = listOf(
            El(20, "搜索", Rect(100, 100, 800, 200), fromA11y = true, editable = true),
            El(21, "清空", Rect(810, 110, 920, 190), fromA11y = true, clickable = true),
        )
        assertNull(
            "Clear button beside an editable input box on current page must not trigger confirmation",
            evaluatePayGuardContext(ctx, "com.example.shop", "清空", 150, searchBoxEls),
        )
        assertEquals(
            "Standalone bare clear button NOT beside any input box must classify as CAUTION",
            Consent.CAUTION,
            evaluatePayGuardContext(ctx, "com.example.shop", "清空", 150, normalEls),
        )
        assertEquals(
            "Standalone clear-data button NOT beside any input box must classify as DESTROY",
            Consent.DESTROY,
            evaluatePayGuardContext(ctx, "com.example.shop", "清除数据", 150, normalEls),
        )
    }
}
