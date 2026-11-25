package com.kilon.agentvscreen.cmd

import android.content.Context
import com.kilon.agentvscreen.host.Risk
import com.kilon.agentvscreen.host.StrongConfirm
import com.kilon.agentvscreen.host.UserAsk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 2.0.0-beta11 (rainbow: an expired strong confirmation stayed in 等你处理 and could not be dismissed): running out of
 * time is an event. Waiting requests leave the published lists on their own, with no other event needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V200Beta11Test {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @After fun tearDown() { StrongConfirm.resetForTests(); UserAsk.reset() }

    private fun request(key: String, nowMs: Long, validMs: Long) = StrongConfirm.getOrCreateRequest(
        risk = Risk.TRUST_SET, key = key, tokenId = "t1", tokenName = "agent", tokenScope = "operate", client = "c",
        commandName = "trust", detail = "", validMs = validMs, reusable = false, allowHold = false, nowMs = nowMs,
    ).first

    private fun waitUntil(ms: Long, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(20)
    }

    @Test fun expiredStrongConfirmLeavesPendingWithoutAnotherEvent() {
        StrongConfirm.resetForTests()
        StrongConfirm.postNotification = false
        var t = 1_000L
        StrongConfirm.clock = { t }
        request("trust:a", t, 200)
        assertEquals(1, StrongConfirm.pending.value.size)
        assertEquals(1_200L, StrongConfirm.nextExpiryAt)
        t = 1_300L
        waitUntil(2_000) { StrongConfirm.pending.value.isEmpty() }
        assertTrue(StrongConfirm.pending.value.isEmpty())
        assertNull(StrongConfirm.nextExpiryAt)
    }

    @Test fun wakeRearmsForTheNextRequest() {
        StrongConfirm.resetForTests()
        StrongConfirm.postNotification = false
        var t = 1_000L
        StrongConfirm.clock = { t }
        request("trust:a", t, 200)
        request("trust:b", t, 600)
        assertEquals(1_200L, StrongConfirm.nextExpiryAt)
        t = 1_300L
        waitUntil(2_000) { StrongConfirm.pending.value.size == 1 }
        assertEquals("trust:b", StrongConfirm.pending.value.single().key)
        assertEquals(1_600L, StrongConfirm.nextExpiryAt)
    }

    @Test fun expireDueDropsAnExpiredRequestAtOnce() {
        StrongConfirm.resetForTests()
        StrongConfirm.postNotification = false
        var t = 1_000L
        StrongConfirm.clock = { t }
        request("trust:a", t, 60_000)
        t = 70_000L
        StrongConfirm.expireDue()
        assertTrue(StrongConfirm.pending.value.isEmpty())
    }

    @Test fun expiredQuestionLeavesOpenWithoutAnotherEvent() {
        UserAsk.reset(); UserAsk.bind(null)
        UserAsk.create(ctx, UserAsk.Kind.TEACH, "teach:e", "教一下", "", validMs = 150)
        assertEquals(1, UserAsk.open.value.size)
        waitUntil(2_000) { UserAsk.open.value.isEmpty() }
        assertTrue(UserAsk.open.value.isEmpty())
        assertNull(UserAsk.nextExpiryAt)
    }
}
