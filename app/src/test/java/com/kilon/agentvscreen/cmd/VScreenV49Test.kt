package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** v49: the act step language, conditions, options, help ↔ parser consistency and the per-app notebook. */
@RunWith(RobolectricTestRunner::class)
class VScreenV49Test {

    private fun refused(block: () -> Unit): ActionError {
        try { block() } catch (e: ActionError) { return e }
        fail("expected ActionError"); throw IllegalStateException()
    }

    @Test fun splitKeepsQuotedSemicolons() {
        assertEquals(listOf("tap 搜索", "type \"a;b\"", "key enter"), Steps.split("tap 搜索; type \"a;b\"\nkey enter"))
        assertEquals(listOf("tap 1 2", "back"), Steps.split("tap 1 2；back;"))
    }

    @Test fun targets() {
        assertEquals(StepTarget.At(540, 1200), Steps.parse("tap 540 1200").target)
        assertEquals(StepTarget.At(540, 1200), Steps.parse("tap 540,1200").target)
        assertEquals(StepTarget.Ref(7), Steps.parse("tap #7").target)
        assertEquals(StepTarget.Words("我的 订单"), Steps.parse("tap 我的 订单").target)
        val p = Steps.parse("press #3 2s")
        assertEquals("press", p.kind); assertEquals(2_000L, p.ms)
        assertEquals(800L, Steps.parse("press 10 10").ms)
    }

    @Test fun gestures() {
        val s = Steps.parse("swipe 100 900 100 300 400ms")
        assertEquals("swipe", s.kind); assertEquals(listOf(100 to 900, 100 to 300), s.points); assertEquals(400L, s.ms)
        val d = Steps.parse("drag 1 2 3 4 5 6")
        assertEquals("drag", d.kind); assertEquals(3, d.points.size); assertEquals(600L, d.ms)
        refused { Steps.parse("swipe 1 2 3 4 5 6") }
        refused { Steps.parse("drag 1 2 3") }
        val sc = Steps.parse("scroll down #4")
        assertEquals("down", sc.text); assertEquals(StepTarget.Ref(4), sc.target)
        refused { Steps.parse("scroll sideways") }
    }

    @Test fun typeKeepsUntilOnlyWhenItParses() {
        val t = Steps.parse("type 耳机 until text:综合")
        assertEquals("耳机", t.text); assertEquals("text", t.until!!.single().kind)
        val raw = Steps.parse("type wait until tomorrow")
        assertEquals("wait until tomorrow", raw.text); assertNull(raw.until)
        assertTrue(Steps.parse("type+ 你好").append)
    }

    @Test fun keysAndWaits() {
        assertEquals("back", Steps.parse("back").text)
        assertEquals("enter", Steps.parse("key enter").text)
        refused { Steps.parse("key launchmissiles") }
        // 1.2: no home / recents on the background screen, by name or by keycode.
        assertTrue(refused { Steps.parse("key home") }.message.orEmpty().contains("没有 home 键"))
        refused { Steps.parse("key 3") }
        assertEquals("66", Steps.parse("key 66").text)
        assertEquals("stable", Steps.parse("wait stable").until!![0].kind)
        assertEquals("still", Steps.parse("wait still").until!![0].kind)
        val sl = Steps.parse("wait 2s"); assertEquals("sleep", sl.kind); assertEquals(2_000L, sl.ms)
        val w = Steps.parse("wait text:完成 or gone:加载中 30s")
        assertEquals("wait", w.kind); assertEquals(listOf("text", "gone"), w.until!!.map { it.kind }); assertEquals(30_000L, w.untilMs)
        assertEquals(Steps.WAIT_DEFAULT_MS, Steps.parse("wait stable").untilMs)
        assertEquals(2_000L, Steps.parse("tap 1 1 until change max 2s").untilMs)
        assertEquals("use", Steps.parse("use 收菜").kind)
        refused { Steps.parse("fly away") }
    }

    @Test fun conditions() {
        val c = Steps.cond("change:0,0,100,50")
        assertEquals("change", c.kind); assertEquals(100, c.area!!.right)
        assertEquals("stable", Steps.cond("stable").kind)
        val col = Steps.cond("color:10,20=#FF0000~30")
        assertEquals(10, col.x); assertEquals(20, col.y); assertEquals(0xFF0000, col.color and 0xFFFFFF); assertEquals(30, col.tol)
        assertEquals("app", Steps.cond("app:com.x").kind)
        refused { Steps.cond("progress") }
        refused { Steps.cond("text:") }
        assertEquals(2, Steps.conds("text:到货 | text:有货").size)
    }

    @Test fun tooManySteps() {
        refused { Steps.parseAll((1..41).joinToString(";") { "back" }) }
        assertEquals(40, Steps.parseAll((1..40).joinToString(";") { "back" }).size)
    }

    @Test fun durationsNormalized() {
        assertEquals(listOf("--watch", "2000", "--for", "3600000"), VScreenOptions.normalize("act", listOf("--watch", "2s", "--for", "1h")))
        refused { VScreenOptions.normalize("act", listOf("--watch", "soon")) }
        assertEquals(VScreenOptions.Kind.SWITCH, VScreenOptions.kindOf("act", "paused"))
        assertEquals(VScreenOptions.Kind.VALUE, VScreenOptions.kindOf("act", "keep"))
    }

    @Test fun theCommandTableIsTheOptionSource() {
        val h = VScreenOffloadHandler(RuntimeEnvironment.getApplication())
        assertEquals(setOf("stop", "watch", "fast", "bare", "keep", "for", "save", "paused", "confirmed", "async"), h.optionsOf("act"))
        assertEquals(setOf("landscape", "portrait", "restart", "async"), h.optionsOf("launch"))
        assertNull(h.optionsOf("tap"))
        refused { h.checkOptions("act", listOf("--expect", "x")) }
        refused { h.checkOptions("act", listOf("--fast=1")) }
        refused { h.checkOptions("act", listOf("--stop")) }
        h.checkOptions("act", listOf("tap 1 1", "--stop", "text:x", "--bare"))
        // Deprecated options are accepted but not advertised.
        assertFalse(VScreenOffloadHandler.HELP.contains("--confirmed <") || VScreenOffloadHandler.HELP.contains("[--confirmed]"))
        // Every command has help, is in the quick help, and is dispatched (an unknown one is exit 2).
        for (c in CommandSpec.commands) {
            assertFalse("help ${c.name}", h.helpFor(c.name).startsWith("没有叫"))
            assertTrue("quick help mentions ${c.name}", VScreenOffloadHandler.QUICK_HELP.contains(c.name))
        }
        val r = h.handle(NativeOffloadRequest(listOf("vscreen", "frobnicate"), "s"))
        assertEquals(2, r.exitCode); assertEquals("UNKNOWN_COMMAND", r.code)
        val bad = h.handle(NativeOffloadRequest(listOf("vscreen", "act", "--nope"), "s"))
        assertEquals(2, bad.exitCode); assertEquals("UNKNOWN_OPTION", bad.code)
    }

    @Test fun notebookSegmentsAndProfile() {
        VScreenMap.init(RuntimeEnvironment.getApplication())
        VScreenMap.clearCache()
        val pkg = "com.example.v49"
        val n = VScreenMap.addNote(pkg, "搜索框在顶部")
        assertEquals(n.id, VScreenMap.addNote(pkg, "搜索框在顶部").id)
        VScreenMap.saveSegment(pkg, "收菜", "tap 100 100; tap 200 200")
        assertNotNull(VScreenMap.segment(pkg, "收菜"))
        VScreenMap.setProfile(pkg, mapOf("after" to "tap 10 10", "before" to "tap 20 20"))
        VScreenMap.clearCache()
        assertEquals("tap 10 10", VScreenMap.profile(pkg)["after"])
        assertEquals(1, VScreenMap.notes(pkg).size)
        val lines = noteLines(pkg).joinToString("\n")
        assertTrue(lines, lines.contains("前置步骤") && lines.contains("收菜"))
        VScreenMap.setProfile(pkg, mapOf("after" to ""))
        assertNull(VScreenMap.profile(pkg)["after"])
        assertTrue(VScreenMap.deleteSegment(pkg, "收菜"))
        assertTrue(VScreenMap.deleteNote(pkg, n.id))
    }
}
