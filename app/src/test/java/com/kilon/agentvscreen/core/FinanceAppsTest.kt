package com.kilon.agentvscreen.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Unit tests for P2-4 financial/payment app default confirm-every-time rules. */
class FinanceAppsTest {

    @Before
    fun setUp() {
        VScreenStore.setRuleForTest("com.eg.android.AlipayGphone", null)
        VScreenStore.setRuleForTest("com.paypal.android.p2pmobile", null)
        VScreenStore.setRuleForTest("com.tencent.mm", null)
        VScreenStore.setRuleForTest("com.example.notes", null)
    }

    @After
    fun tearDown() {
        VScreenStore.setRuleForTest("com.eg.android.AlipayGphone", null)
        VScreenStore.setRuleForTest("com.paypal.android.p2pmobile", null)
        VScreenStore.setRuleForTest("com.tencent.mm", null)
        VScreenStore.setRuleForTest("com.example.notes", null)
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
}
