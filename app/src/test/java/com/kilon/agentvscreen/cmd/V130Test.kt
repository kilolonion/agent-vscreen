package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import com.kilon.agentvscreen.host.Readiness
import com.kilon.agentvscreen.host.UserAsk
import com.kilon.agentvscreen.host.Wake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime

/** 1.3: the schedule language and its next window, the readiness table, the ask channel and its point → element. */
@RunWith(RobolectricTestRunner::class)
class V130Test {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun parsesTimesDaysAndIntervals() {
        assertEquals(Wake.Spec(0x7F, listOf(7 * 60)), Wake.parse("07:00"))
        assertEquals(Wake.Spec(0x1F, listOf(8 * 60 + 30, 18 * 60)), Wake.parse("weekdays 18:00,08:30"))
        assertEquals(Wake.Spec(0x60, listOf(9 * 60)), Wake.parse("周末 9:00"))
        assertEquals(0b0010101, Wake.parse("mon,wed,fri 21:00").days)
        assertEquals(0x1F, Wake.parse("mon-fri 21:00").days)
        assertEquals(0b1000001, Wake.parse("sun-mon 21:00").days) // wraps over the week's end
        val e = Wake.parse("every 2h from 08:00 to 22:00")
        assertEquals(listOf(8, 10, 12, 14, 16, 18, 20).map { it * 60 }, e.minutesOfDay())
        assertEquals(listOf(0, 30, 60), Wake.parse("every 30m to 01:30").minutesOfDay().take(3))
        for (bad in listOf("", "25:00", "7", "every 10m", "every 2x", "weekdays", "every 1h from 10:00 to 09:00", "noday 07:00"))
            try { Wake.parse(bad); fail("accepted: $bad") } catch (_: Wake.BadSpec) {}
    }

    @Test fun nextWindowFollowsDaysAndTimes() {
        val wd = Wake.parse("weekdays 07:00,19:30")
        // Friday 2026-11-13 20:00 → Monday 07:00 (weekend skipped)
        assertEquals(at(2026, 11, 16, 7, 0), Wake.next(wd, at(2026, 11, 13, 20, 0), zone))
        // Monday 06:59 → the same morning; exactly 07:00 → the evening (strictly after)
        assertEquals(at(2026, 11, 16, 7, 0), Wake.next(wd, at(2026, 11, 16, 6, 59), zone))
        assertEquals(at(2026, 11, 16, 19, 30), Wake.next(wd, at(2026, 11, 16, 7, 0), zone))
        val ev = Wake.parse("every 2h from 08:00 to 22:00")
        assertEquals(at(2026, 11, 14, 10, 0), Wake.next(ev, at(2026, 11, 14, 8, 1), zone))
        assertEquals(at(2026, 11, 15, 8, 0), Wake.next(ev, at(2026, 11, 14, 20, 0), zone))
    }

    @Test fun oneOffCanBeBookedMonthsAhead() {
        val now = at(2026, 11, 18, 9, 0)
        val spec = Wake.parse("2027-02-01 08:00,12:00", now, zone)
        val first = at(2027, 2, 1, 8, 0)
        val last = at(2027, 2, 1, 12, 0)
        assertEquals(first, Wake.next(spec, now, zone))
        assertEquals(last, Wake.next(spec, first, zone))
        assertNull(Wake.next(spec, last, zone))
    }

    @Test fun rejectsEmptyTimeListsAndOversizedIntervals() {
        for (bad in listOf(",", "today ,", "every 4294967297h", "every 999999999999999999m")) {
            try { Wake.parse(bad); fail("accepted: $bad") } catch (_: Wake.BadSpec) {}
        }
    }

    private fun inputs(privilege: String = "READY", connection: String = "READY", serviceOn: Boolean = true, alive: Boolean = true,
                       fg: Boolean = true, stopped: Boolean = false, frozen: Boolean = false, remote: String = "off", up: Boolean = true) =
        Readiness.Inputs(serviceOn, alive, fg, privilege, connection, false, stopped, frozen, remote, up)

    @Test fun readinessSeparatesBlockersFromWarnings() {
        assertTrue(Readiness.ready(Readiness.evaluate(inputs())))
        assertTrue(Readiness.evaluate(inputs()).isEmpty())
        // Disconnected but able to connect by itself on launch: not a blocker.
        assertTrue(Readiness.ready(Readiness.evaluate(inputs(connection = "DISCONNECTED"))))
        assertEquals(listOf("no_privilege"), Readiness.evaluate(inputs(privilege = "NOT_RUNNING")).map { it.code })
        assertEquals(listOf("not_connected"), Readiness.evaluate(inputs(connection = "ERROR")).map { it.code })
        assertFalse(Readiness.ready(Readiness.evaluate(inputs(serviceOn = false))))
        assertFalse(Readiness.ready(Readiness.evaluate(inputs(stopped = true))))
        val warn = Readiness.evaluate(inputs(frozen = true, remote = "builtin", up = false))
        assertTrue(Readiness.ready(warn))
        assertEquals(listOf("frozen_in_background", "remote_down"), warn.map { it.code })
        val j = Readiness.json(Readiness.evaluate(inputs(privilege = "NOT_RUNNING")), withText = false)
        assertEquals("no_privilege", j.getJSONArray("blocking").getString(0))
    }

    private fun el(n: Int, l: Int, t: Int, r: Int, b: Int) = El(n, "e$n", Rect(l, t, r, b), true)

    @Test fun pointFindsTheSmallestElementUnderIt() {
        val list = listOf(el(1, 0, 0, 1000, 1000), el(2, 100, 100, 300, 200), el(3, 120, 120, 180, 160))
        assertEquals(3, elementAt(list, 150, 140)?.n)
        assertEquals(2, elementAt(list, 250, 150)?.n)
        assertEquals(1, elementAt(list, 900, 900)?.n)
        assertNull(elementAt(list, 2000, 10))
        assertEquals(listOf(2, 3), elementsIn(list, Rect(90, 90, 310, 210)).map { it.n })
    }

    @Test fun askChannelAnswersAndDeclines() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        UserAsk.reset()
        val a = UserAsk.create(ctx, UserAsk.Kind.CHOOSE, "k1", "which?", "", options = listOf("A", "B"))
        assertTrue(UserAsk.open.value.any { it.id == a.id })
        UserAsk.answer(ctx, a.id, true, org.json.JSONObject().put("index", 2).put("choice", "B"))
        assertTrue(UserAsk.await(a, 10))
        assertEquals("B", a.reply?.optString("choice"))
        assertFalse(UserAsk.open.value.any { it.id == a.id })
        val b = UserAsk.create(ctx, UserAsk.Kind.TEXT, "k2", "why?", "")
        UserAsk.answer(ctx, b.id, false)
        assertEquals(false, b.answer)
        // The same key finds the earlier question (an agent's resend picks up the answer).
        assertEquals(b.id, UserAsk.find("k2", "main")?.id)
        UserAsk.reset()
    }

    @Test fun newCommandsAreInTheTable() {
        for (c in listOf("schedule", "ask")) assertTrue(c, CommandSpec.of(c) != null)
        for (c in listOf("SCHEDULE_LIMIT", "NOT_READY", "ASK_PENDING", "ASK_DECLINED")) assertTrue(c, Codes.of(c) != null)
        assertTrue(CommandSpec.of("ask")!!.long)
        assertFalse(CommandSpec.of("schedule")!!.screen)
    }
}
