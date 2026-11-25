package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.api.AgentGuide
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.0.0-beta13: requests waiting on the phone are said plainly, with the line for the user. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V200Beta13Test {
    @Test fun requestsOnThePhoneHaveTheirOwnNextMove() {
        listOf("NEEDS_CONFIRM", "CONFIRM_PENDING", "CONFIRM_REQUIRED", "ASK_PENDING").forEach { assertEquals(it, "user_on_phone", Codes.next(it)) }
        // Decisions the user already made stay "ask the user", not "wait on the phone".
        listOf("USER_DENIED", "ASK_DECLINED", "APP_BLOCKED", "USER_STOPPED").forEach { assertEquals(it, "ask_user", Codes.next(it)) }
        assertEquals(UserWait.Kind.LESSON, UserWait.kindOf("ASK_PENDING", "teach"))
        assertEquals(UserWait.Kind.QUESTION, UserWait.kindOf("ASK_PENDING", "ask"))
        assertEquals(UserWait.Kind.CONFIRM, UserWait.kindOf("CONFIRM_PENDING", "act"))
        assertNull(UserWait.kindOf("USER_DENIED", "act"))
    }

    @Test fun aPendingQuestionGivesTheAgentTheLineForTheUser() {
        val r = NativeOffloadResult(1, "【错误】ASK_PENDING：问题已显示在手机上\n", "ASK_PENDING", JSONObject().put("message", "m").put("next", "user_on_phone"))
        val out = UserWait.attach(r, "ask", "t1")
        val ua = out.data!!.getJSONObject("user_action")
        assertEquals("user_on_phone", ua.getString("waiting"))
        assertEquals("question", ua.getString("kind"))
        assertTrue(ua.getBoolean("may_be_unseen"))
        val say = ua.getString("tell_user")
        assertTrue(say, say.contains("Agent-VScreen") && say.contains("待处理"))
        assertTrue(out.output.contains(say))
        assertTrue(out.output.startsWith(r.output.trimEnd()))
    }

    @Test fun aRefusalThatSentNoRequestHasNothingToWaitFor() {
        // A rule meeting a pay button refuses with CONFIRM_PENDING but puts nothing on the phone.
        val r = NativeOffloadResult(1, "x\n", "CONFIRM_PENDING", JSONObject())
        assertSame(r, UserWait.attach(r, "act", "nobody-has-a-request"))
        val ok = NativeOffloadResult(0, "ok\n", "OK")
        assertSame(ok, UserWait.attach(ok, "ask", "t1"))
    }

    @Test fun theGuideSaysWhenToTurnToTheUserFromTheCodeTable() {
        val s = AgentGuide.userSection()
        listOf("NEEDS_CONFIRM", "CONFIRM_PENDING", "ASK_PENDING", "user_on_phone", "ask_user", "elevate").forEach { assertTrue(it, s.contains(it)) }
        assertFalse(s.contains("CONFIRM_REQUIRED"))
        assertTrue(AgentGuide.guide().contains(s.lines().first()))
        assertTrue(AgentGuide.topic("safety")!!.contains(s.lines().first()))
    }

    @Test fun thePublicConnectMessageForbidsMadeUpTokensAndOffersAWayOut() {
        val m = AgentGuide.onboardPublic("https://p.example.com/mcp")
        assertTrue(m.contains("不要自己编") && m.contains("我接不了") && m.contains("401"))
        // The no-browser path names every URL with the right base and ends in a working call.
        listOf("https://p.example.com/oauth/register", "https://p.example.com/oauth/device_authorization", "https://p.example.com/oauth/token", "resource=https://p.example.com/mcp", "tools/call").forEach { assertTrue(it, m.contains(it)) }
        assertFalse(m.contains("/mcp/oauth"))
    }
}
