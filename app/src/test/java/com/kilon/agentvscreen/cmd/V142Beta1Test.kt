package com.kilon.agentvscreen.cmd

import androidx.compose.ui.graphics.Color
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.core.ManualScreen
import com.kilon.agentvscreen.core.ThreadGuard
import com.kilon.agentvscreen.host.Updates
import com.kilon.agentvscreen.ui.Md
import com.kilon.agentvscreen.ui.clampIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.4.2-beta1: stability fixes the forks showed, update channel, Markdown notes, the user's own screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V142Beta1Test {

    @Test fun versionAndNewsName() {
        assertEquals(62, com.kilon.agentvscreen.BuildConfig.VERSION_CODE)
        assertEquals("2.0.0-beta5", com.kilon.agentvscreen.BuildConfig.VERSION_NAME)
        assertEquals("1.4.2-beta1", com.kilon.agentvscreen.api.AgentNews.nameOf(52))
        assertEquals("1.4.2-beta2", com.kilon.agentvscreen.api.AgentNews.nameOf(53))
        assertEquals("1.4.2-beta3", com.kilon.agentvscreen.api.AgentNews.nameOf(54))
        assertEquals("1.4.2-beta4", com.kilon.agentvscreen.api.AgentNews.nameOf(55))
        assertEquals("1.4.2-beta5", com.kilon.agentvscreen.api.AgentNews.nameOf(56))
        assertEquals("1.4.2-beta6", com.kilon.agentvscreen.api.AgentNews.nameOf(57))
        assertEquals("2.0.0-beta1", com.kilon.agentvscreen.api.AgentNews.nameOf(58))
        assertEquals("2.0.0-beta2", com.kilon.agentvscreen.api.AgentNews.nameOf(59))
        assertEquals("2.0.0-beta3", com.kilon.agentvscreen.api.AgentNews.nameOf(60))
        assertEquals("2.0.0-beta4", com.kilon.agentvscreen.api.AgentNews.nameOf(61))
        assertEquals("2.0.0-beta5", com.kilon.agentvscreen.api.AgentNews.nameOf(62))
    }

    @Test fun backgroundThreadExceptionsAreNotFatalButMainAndVmErrorsAre() {
        assertFalse(ThreadGuard.fatal(false, java.io.InterruptedIOException("stream closed")))
        assertFalse(ThreadGuard.fatal(false, IllegalStateException("x")))
        assertTrue(ThreadGuard.fatal(true, IllegalStateException("x")))
        assertTrue(ThreadGuard.fatal(false, OutOfMemoryError()))
    }

    @Test fun clampNeverThrowsOnUpsideDownRanges() {
        assertEquals(40f, 50f.clampIn(40f, 20f), 0f)
        assertEquals(0f, 5f.clampIn(0f, -1f), 0f)
        assertEquals(-1f, (-5f).clampIn(0f, -1f), 0f)
        assertEquals(0f, Float.NaN.clampIn(0f, 10f), 0f)
        assertEquals(3, 7.clampIn(3, 1))
        assertEquals(120L, 10L.clampIn(260L, 120L))
    }

    @Test fun staleNoteOnlyWhenTheScreenCouldNotDraw() {
        I18n.choice = I18n.Lang.EN
        // A still screen that is ON: no new frame is normal, nothing to say.
        assertNull(staleFrameNote(lastFrameAtMs = 1_000, askedAtMs = 5_000, nowMs = 6_000, on = true, parked = false))
        // A fresh frame came: nothing to say.
        assertNull(staleFrameNote(6_000, 5_000, 6_100, on = false, parked = false))
        // Asleep and no frame since the ask: say how old.
        val n = staleFrameNote(1_000, 5_000, 9_000, on = false, parked = false)
        assertNotNull(n); assertTrue(n!!, n.contains("8 s old") && n.contains("asleep"))
        assertTrue(staleFrameNote(1_000, 5_000, 9_000, on = null, parked = true)!!.contains("parked"))
        I18n.choice = I18n.Lang.ZH
    }

    private fun rel(v: String, pre: Boolean = false) = Updates.Release(v, v, "", "", null, pre)

    @Test fun updateChannelPicksReleasesOrBetas() {
        val list = listOf(rel("1.4.1"), rel("1.4.2-beta1", pre = true), rel("1.4.0"))
        assertEquals(null, Updates.pick(list, Updates.Channel.STABLE, "1.4.1"))
        assertEquals("1.4.2-beta1", Updates.pick(list, Updates.Channel.BETA, "1.4.1")?.version)
        assertEquals("1.4.1", Updates.pick(list, Updates.Channel.STABLE, "1.4.0")?.version)
        // A beta tag not marked pre-release on GitHub still is not a release.
        assertFalse(Updates.onChannel(rel("1.5.0-beta1"), Updates.Channel.STABLE))
        // On the beta channel a newer release wins over an older beta.
        assertEquals("1.4.2", Updates.pick(list + rel("1.4.2"), Updates.Channel.BETA, "1.4.2-beta1")?.version)
    }

    @Test fun markdownNotesParseIntoBlocks() {
        val b = Md.parse("## 新功能\n- **手动开副屏**：不用 agent\n- 更新渠道\n\n普通段落\n接着一行\n\n```\ncode\n```\n---\n1. 第一\n> 引用")
        assertEquals(Md.Block.Heading(2, "新功能"), b[0])
        assertEquals(Md.Block.Item("•", "**手动开副屏**：不用 agent", 0), b[1])
        assertEquals(Md.Block.Para("普通段落 接着一行"), b[3])
        assertEquals(Md.Block.Code("code"), b[4])
        assertEquals(Md.Block.Rule, b[5])
        assertEquals(Md.Block.Item("1.", "第一", 0), b[6])
        assertEquals(Md.Block.Quote("引用"), b[7])
    }

    @Test fun markdownInlineMarksAreRemovedFromTheText() {
        val s = Md.inline("**粗** *斜* `code` [链接](https://example.com) ~~删~~ 和 https://a.b/c", Color.Blue, Color.Gray)
        assertEquals("粗 斜 code 链接 删 和 https://a.b/c", s.text)
        assertEquals(2, s.getLinkAnnotations(0, s.length).size)
        // Lone marks stay as they are.
        assertEquals("a * b and 2*3", Md.inline("a * b and 2*3", Color.Blue, Color.Gray).text)
    }

    @Test fun theUsersOwnSessionCannotBeSentByAnAgent() {
        // ApiServer.sessionOf keeps only these characters; the reserved name must contain one it drops.
        val kept = ManualScreen.SESSION.replace(Regex("[^A-Za-z0-9_.:-]"), "_")
        assertFalse(kept == ManualScreen.SESSION)
        assertTrue(ManualScreen.isManual(ManualScreen.SESSION))
        assertFalse(ManualScreen.isManual("you"))
    }

    @Test fun stepCountReadsSingular() {
        assertEquals("1 step", I18n.with("en") { com.kilon.agentvscreen.steps(1) })
        assertEquals("3 steps", I18n.with("en") { com.kilon.agentvscreen.steps(3) })
        assertEquals("0 steps", I18n.with("en") { com.kilon.agentvscreen.steps(0) })
        assertEquals("1 步", I18n.with("zh") { com.kilon.agentvscreen.steps(1) })
    }

    /**
     * A fake late reaction: the clock [now] (ms since the action started) moves [stepMs] per picture; the action itself
     * took [actMs], and the look is timed from when it finished, as the step's first wait is. [frames] say "same as before".
     */
    private class Late(var now: Long, val stepMs: Long, val frames: List<Boolean?>, val stopAt: Int = -1, val settleOut: WaitOut? = null, val settleMs: Long = 0, val actMs: Long = 0) {
        var i = -1; var settled = 0
        fun run() = lookLate(Probe.LATE_MS, { now - actMs }, { i++; now += stepMs; frames.getOrElse(i) { true } }, { if (i == stopAt) "text:Battery" else null },
            { settled++; now += settleMs; settleOut!! })
    }

    @Test fun lateChangeThatMeetsTheStopConditionStops() {
        val l = Late(1_000, 500, listOf(true, false), stopAt = 1)
        val out = l.run()
        assertEquals("text:Battery", out.stopped)
        assertEquals(0, l.settled)
        assertEquals("stop condition text:Battery at 2.0s", I18n.with("en") { out.waitText() })
        val during = Late(1_000, 500, listOf(false), settleOut = WaitOut(null, "text:Battery", 400), settleMs = 400).run()
        assertEquals("text:Battery", during.stopped)
        assertEquals(1_500L, during.changedMs)
        assertEquals("stop condition text:Battery at 1.9s", I18n.with("en") { during.waitText() })
    }

    @Test fun lateChangeIsTimedWhenFirstSeenAndStillChangingIsSaid() {
        // First different picture 2 s after the action, then it animates past the 3 s settle wait.
        val l = Late(1_000, 500, listOf(true, false), settleOut = WaitOut(null, null, 3_000), settleMs = 3_000)
        val out = l.run()
        assertNull(out.stopped)
        assertEquals(false, out.same)
        assertEquals(2_000L, out.changedMs)
        assertNull(out.settle?.met)
        assertEquals("the screen changed late, 2.0s after the action, and was still changing when time was up at 5.0s", I18n.with("en") { out.waitText() })
        val calm = Late(1_000, 500, listOf(false), settleOut = WaitOut("stable", null, 800), settleMs = 800).run()
        assertEquals("the screen changed late, 1.5s after the action, and settled at 2.3s", I18n.with("en") { calm.waitText() })
    }

    @Test fun lateChangeAfterASlowActionCountsBothTimesFromItsEnd() {
        // The action takes 2.0 s, the first different picture comes 2.0 s after it, and the screen is calm 0.6 s later.
        val calm = Late(3_500, 500, listOf(false), settleOut = WaitOut("stable", null, 600), settleMs = 600, actMs = 2_000).run()
        assertEquals(2_000L, calm.changedMs); assertEquals(2_600L, calm.endMs)
        assertEquals("the screen changed late, 2.0s after the action, and settled at 2.6s", I18n.with("en") { calm.waitText() })
        val busy = Late(3_500, 500, listOf(false), settleOut = WaitOut(null, null, 3_000), settleMs = 3_000, actMs = 2_000).run()
        assertEquals("the screen changed late, 2.0s after the action, and was still changing when time was up at 5.0s", I18n.with("en") { busy.waitText() })
    }

    @Test fun noLateChangeLooksOnlyForTheWindow() {
        val l = Late(1_000, 100, emptyList())
        val out = l.run()
        assertEquals(true, out.same)
        assertNull(out.changedMs); assertNull(out.stopped); assertNull(out.waitText())
        assertEquals(15, l.i + 1)
        assertEquals(null, Late(1_000, 100, listOf(null)).run().same)
    }
}
