package com.kilon.agentvscreen.host

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.util.writeText
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.3: on call at agreed times.
 *
 * An agent with a timer (every morning at 07:00, every 2 hours) reaches the phone when nobody is using it: screen off
 * for hours, the app long in the background, maybe just rebooted. That is when the system freezes or kills us, and
 * when Shizuku may be gone. Starting the service once is not enough; what the agent needs is an agreement:
 *
 *  - The agent says when it will come (`vscreen schedule "weekdays 07:00"`). We keep the schedule on disk.
 *  - [LEAD_MS] before each window an exact alarm (allowed in Doze, and one of the cases where Android lets an app
 *    start its foreground service from the background) brings the service up and checks [Readiness]. With Shizuku we
 *    also put the app on the system's temporary power allowlist for the window (`cmd deviceidle tempwhitelist`) and
 *    re-apply the battery exemptions the system may have taken back.
 *  - The check is written down (status shows it, the session gets an event) and, when something blocks, the user gets
 *    a notification in time to fix it ("Shizuku is not running; the 07:00 call will fail").
 *  - During the window a bounded partial wake lock keeps the CPU up.
 *
 * Nothing here knows what the agent will do; it only keeps the door open at the times it named.
 *
 * Not verified on a phone yet (marked so in the docs): whether a vendor's own background manager (MagicOS, MIUI …)
 * honours the exact alarm and the temporary allowlist for a frozen app.
 */
object Wake {
    private const val TAG = "Wake"
    const val LEAD_MS = 3 * 60_000L
    const val MAX_ENTRIES = 8
    /** 1.4.1-beta1: per agent is [MAX_ENTRIES]; this keeps the whole phone bounded however many agents are connected. */
    const val MAX_TOTAL = 24
    const val MIN_EVERY_MIN = 30
    const val DEFAULT_FOR_MIN = 10
    const val MAX_FOR_MIN = 30
    const val ACTION_ALARM = "com.kilon.agentvscreen.WAKE_ALARM"
    /** Sent by a local CLI that found the app silent: start the service (and with it, thaw the process). */
    const val ACTION_WAKE = "com.kilon.agentvscreen.WAKE"

    // ── the schedule language ────────────────────────────────────────

    /**
     * When: `[days] HH:MM[,HH:MM…]` or `[days] every N(m|h) [from HH:MM] [to HH:MM]`.
     * Days: daily (default) / weekdays / weekends / mon-fri / mon,wed,fri; 每天 / 工作日 / 周末 too.
     * [days] bit 0 = Monday … bit 6 = Sunday. Times are minutes of the day, local time.
     */
    data class Spec(val days: Int, val times: List<Int>, val everyMin: Int = 0, val fromMin: Int = 0, val toMin: Int = 24 * 60,
                    /** 1.3 beta2: one day only (`today 10:11`, `once 10:11`, `11-15 10:11`); the entry goes when it is over. */
                    val date: LocalDate? = null) {
        fun minutesOfDay(): List<Int> = if (everyMin > 0) (fromMin until toMin step everyMin).toList() else times
        fun runsOn(d: DayOfWeek): Boolean = days and (1 shl (d.value - 1)) != 0
        /** The text that means exactly this (a one-off is stored with its date, whatever word booked it). */
        fun canonical(): String {
            fun hm(m: Int) = "%02d:%02d".format(java.util.Locale.ROOT, m / 60, m % 60)
            return if (date != null) "$date " + times.joinToString(",") { hm(it) } else ""
        }
        fun describe(): String {
            if (date != null) return tr("仅 ${date.monthValue}-${date.dayOfMonth} ", "only on ${date.monthValue}-${date.dayOfMonth} ") + times.joinToString(",") { "%02d:%02d".format(it / 60, it % 60) } + tr("（一次）", " (once)")
            val dayText = when (days) {
                0x7F -> tr("每天", "daily"); 0x1F -> tr("工作日", "weekdays"); 0x60 -> tr("周末", "weekends")
                // 1.4.0-beta8: named days read like the other kinds (were left as "mon,wed,fri").
                else -> DayOfWeek.entries.filter { runsOn(it) }.joinToString(tr("、", ",")) { tr("周" + "一二三四五六日"[it.ordinal], it.name.take(3).lowercase()) }
            }
            fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
            return if (everyMin > 0) "$dayText " + tr("每 ${everyMin} 分钟", "every $everyMin min") + (if (fromMin > 0 || toMin < 1440) " ${hm(fromMin)}–${hm(toMin)}" else "")
            else "$dayText " + times.joinToString(",") { hm(it) }
        }
    }

    class BadSpec(msg: String) : IllegalArgumentException(msg)

    private val DAY_NAMES = mapOf("mon" to 0, "tue" to 1, "wed" to 2, "thu" to 3, "fri" to 4, "sat" to 5, "sun" to 6,
        "周一" to 0, "周二" to 1, "周三" to 2, "周四" to 3, "周五" to 4, "周六" to 5, "周日" to 6, "周天" to 6)

    private fun hhmm(t: String): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t.trim()) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (h == 24 && mi == 0) return 24 * 60
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }

    private fun days(t: String): Int? {
        when (t.lowercase()) { "daily", "everyday", "每天" -> return 0x7F; "weekdays", "工作日" -> return 0x1F; "weekends", "周末" -> return 0x60 }
        var mask = 0
        for (part in t.lowercase().split(',')) {
            val r = part.split('-')
            if (r.size == 2) {
                val a = DAY_NAMES[r[0].take(3)] ?: DAY_NAMES[r[0]] ?: return null
                val b = DAY_NAMES[r[1].take(3)] ?: DAY_NAMES[r[1]] ?: return null
                var i = a; while (true) { mask = mask or (1 shl i); if (i == b) break; i = (i + 1) % 7 }
            } else mask = mask or (1 shl (DAY_NAMES[part.take(3)] ?: DAY_NAMES[part] ?: return null))
        }
        return mask.takeIf { it != 0 }
    }

    private val DATE_MD = Regex("^(\\d{1,2})-(\\d{1,2})$")
    private val DATE_YMD = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")

    /**
     * A one-off day word: today / tomorrow / once (the next time the clock shows it) / MM-DD / YYYY-MM-DD.
     * [times] decides `once`: today if the first time is still ahead at [nowMs], else tomorrow.
     */
    private fun oneDay(word: String, times: List<Int>, nowMs: Long, zone: ZoneId): LocalDate? {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = now.toLocalDate()
        return when (word.lowercase()) {
            "today", "今天" -> today
            "tomorrow", "明天" -> today.plusDays(1)
            "once", "一次" -> if ((times.firstOrNull() ?: 0) > now.hour * 60 + now.minute) today else today.plusDays(1)
            else -> DATE_YMD.find(word)?.let { m -> runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() ?: throw BadSpec(tr("没有 $word 这一天", "there is no day $word")) }
                ?: DATE_MD.find(word)?.let { m -> runCatching { LocalDate.of(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()?.let { d -> if (d.isBefore(today)) d.plusYears(1) else d } ?: throw BadSpec(tr("没有 $word 这一天", "there is no day $word")) }
        }
    }

    private fun parseTimes(words: List<String>): List<Int> {
        val times = words.joinToString(",").split(',').filter { it.isNotBlank() }.map { t ->
            hhmm(t)?.takeIf { it < 1440 } ?: throw BadSpec(tr("时间写成 HH:MM（收到 $t）", "write times as HH:MM (got $t)"))
        }.distinct().sorted()
        if (times.isEmpty()) throw BadSpec(tr("没写时间", "no time given"))
        if (times.size > 24) throw BadSpec(tr("一条最多 24 个时间点", "at most 24 times per entry"))
        return times
    }

    fun parse(text: String, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Spec {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (words.isEmpty()) throw BadSpec(tr("没写时间", "no time given"))
        // A one-off: a day word, then times (no repeat, no days of the week).
        if (words.size >= 2 && (words[0].lowercase() in setOf("today", "tomorrow", "once", "今天", "明天", "一次") || DATE_MD.matches(words[0]) || DATE_YMD.matches(words[0]))) {
            val times = parseTimes(words.drop(1))
            val date = oneDay(words[0], times, nowMs, zone)!!
            val lastAt = ZonedDateTime.of(date.year, date.monthValue, date.dayOfMonth, times.last() / 60, times.last() % 60, 0, 0, zone).toInstant().toEpochMilli()
            if (lastAt <= nowMs) throw BadSpec(tr("${words[0]} ${times.joinToString(",") { "%02d:%02d".format(it / 60, it % 60) }} 已经过去了", "${words[0]} ${times.joinToString(",") { "%02d:%02d".format(it / 60, it % 60) }} is already past"))
            return Spec(0x7F, times, date = date)
        }
        val d = days(words.first())?.also { words.removeAt(0) } ?: 0x7F
        if (words.isEmpty()) throw BadSpec(tr("只有日期没有时间", "days without a time"))
        if (words.first().lowercase() in setOf("every", "每")) {
            val n = words.getOrNull(1) ?: throw BadSpec(tr("every 后面写间隔，例如 every 2h", "every needs an interval, e.g. every 2h"))
            val m = Regex("^(\\d+)(m|min|h)$").find(n.lowercase()) ?: throw BadSpec(tr("间隔写成 30m 或 2h（收到 $n）", "write the interval as 30m or 2h (got $n)"))
            val count = m.groupValues[1].toIntOrNull()?.takeIf { it <= 1440 } ?: 0
            val every = count * if (m.groupValues[2] == "h") 60 else 1
            if (every < MIN_EVERY_MIN || every > 24 * 60) throw BadSpec(tr("间隔要在 ${MIN_EVERY_MIN} 分钟到 24 小时之间", "the interval must be between $MIN_EVERY_MIN minutes and 24 hours"))
            var from = 0; var to = 24 * 60
            var i = 2
            while (i < words.size) {
                val k = words[i].lowercase(); val v = words.getOrNull(i + 1)?.let { hhmm(it) } ?: throw BadSpec(tr("$k 后面写 HH:MM", "$k needs HH:MM"))
                when (k) { "from", "从" -> from = v; "to", "到" -> to = v; else -> throw BadSpec(tr("看不懂 $k（只有 from / to）", "unknown word $k (only from / to)")) }
                i += 2
            }
            if (from >= to) throw BadSpec(tr("from 要早于 to", "from must be before to"))
            return Spec(d, emptyList(), every, from, to)
        }
        return Spec(d, parseTimes(words))
    }

    /** The first window start strictly after [afterMs], or null when the spec has no time at all. */
    fun next(spec: Spec, afterMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val start = Instant.ofEpochMilli(afterMs).atZone(zone).toLocalDate()
        val firstDay = spec.date ?: start
        if (firstDay.isBefore(start)) return null
        val daysAhead = if (spec.date == null) 7 else 0
        val times = spec.minutesOfDay()
        for (k in 0..daysAhead) {
            val day = firstDay.plusDays(k.toLong())
            if (!spec.runsOn(day.dayOfWeek)) continue
            for (m in times) {
                val at = ZonedDateTime.of(day.year, day.monthValue, day.dayOfMonth, m / 60, m % 60, 0, 0, zone).toInstant().toEpochMilli()
                if (at > afterMs) return at
            }
        }
        return null
    }

    // ── entries ──────────────────────────────────────────────────────

    data class Key(val agentId: String, val name: String)

    class Entry(
        val name: String, val text: String, val spec: Spec, val forMin: Int, val session: String, val by: String, val at: Long,
        val agentId: String = com.kilon.agentvscreen.api.Agents.ofSession(session),
    ) {
        val key: Key get() = Key(agentId, name)
        /** The window shown as next: the first one that has not ended (a window in progress is still "next"). */
        @Volatile var nextAt: Long = 0L
        /** 1.3 beta2: the start of the last window already opened (checked and told), so it is never opened twice. */
        @Volatile var opened: Long = 0L
        @Volatile var last: JSONObject? = null
        /** 1.3.0-beta15: the user's switch in the app (like an alarm's). Off: kept, never opened. */
        @Volatile var on: Boolean = true
        fun json(): JSONObject = JSONObject().put("name", name).put("agent", agentId).put("when", text).put("means", spec.describe()).put("for_min", forMin).put("on", on)
            .put("session", session).put("by", by).put("next_at", if (nextAt > 0) nextAt / 1000 else JSONObject.NULL)
            .put("next_local", if (nextAt > 0) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(nextAt)) else JSONObject.NULL)
            .put("last_check", last ?: JSONObject.NULL)
        fun store(): JSONObject = JSONObject().put("name", name).put("agent", agentId).put("when", text).put("for", forMin).put("session", session).put("by", by).put("at", at)
            .put("opened", opened).put("on", on).also { o -> last?.let { o.put("last", it) } }
        /** A one-off whose last window has ended. */
        fun over(now: Long): Boolean = spec.date != null && next(spec, now - forMin * 60_000L) == null

        fun pending(now: Long): Long? {
            if (!on) return null
            var t = next(spec, now - forMin * 60_000L) ?: return null
            while (t <= opened) t = next(spec, t) ?: return null
            return t
        }
    }

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(File(ctx.filesDir, "vscreen").apply { mkdirs() }, "wake.json")

    internal fun resetForTest() = synchronized(lock) { loaded = false; entries.clear() }

    private fun load(ctx: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            quiet("wake-load") {
                val json = try {
                    AtomicFile(file(ctx)).openRead().bufferedReader().use { JSONObject(it.readText()) }
                } catch (_: java.io.FileNotFoundException) { return@quiet }
                val a = json.optJSONArray("entries") ?: JSONArray()
                val byKey = LinkedHashMap<Key, Entry>()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    // A one-off is stored with its date; it parses whatever today is (and is dropped by arm() once over).
                    val spec = runCatching { parse(o.getString("when"), nowMs = 0L) }.getOrNull() ?: continue
                    // 1.4.1-beta1: bookings from before the explicit agentId belong to the agent their session names
                    // (since 1.4.0-beta7 "<token id>.<session>"); a session without a known prefix is the main token's.
                    val agentId = o.optString("agent").takeIf { it.isNotEmpty() && it != "null" }
                        ?: com.kilon.agentvscreen.api.Agents.ofSession(o.optString("session", "default"))
                    val e = Entry(o.getString("name"), o.getString("when"), spec, o.optInt("for", DEFAULT_FOR_MIN),
                        o.optString("session", "default"), o.optString("by", "main"), o.optLong("at"), agentId)
                        .also { it.last = o.optJSONObject("last"); it.opened = o.optLong("opened"); it.on = o.optBoolean("on", true) }
                    byKey.remove(e.key)
                    byKey[e.key] = e
                }
                entries.addAll(byKey.values)
            }
            loaded = true
        }
    }

    private fun save(ctx: Context) = quiet("wake-save") {
        synchronized(lock) {
            AtomicFile(file(ctx)).writeText(JSONObject().put("entries", JSONArray(entries.map { it.store() })).toString())
        }
    }

    /**
     * 1.4.0-beta11 (rainbow: a finished one-off booking stayed on the home page as "--:--"). A one-off is over once
     * its last window has ended; it goes from the store the moment anyone reads the list, not only when the next alarm
     * happens to re-arm (after the last window there was no alarm left, so it stayed for good).
     */
    private fun dropOver(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val over = synchronized(lock) { entries.filter { it.over(now) }.also { entries.removeAll(it.toSet()) } }
        if (over.isNotEmpty()) { save(ctx); AppLogger.info(TAG, "over: ${over.joinToString { "${it.agentId}/${it.name}" }}") }
        return over.isNotEmpty()
    }

    fun list(ctx: Context): List<Entry> { load(ctx); dropOver(ctx); return synchronized(lock) { entries.toList() } }

    /** What [add] did: the entry, and the existing one it matched when nothing new was added. */
    class Added(val entry: Entry, val same: Boolean)

    /**
     * Add or replace `(agentId, name)`. Throws [BadSpec] / IllegalStateException (too many). 1.3 beta2: an unnamed
     * booking that means the same as one this agent+session already has (same times, same window length) is not added twice.
     */
    fun add(
        ctx: Context, name: String?, text: String, forMin: Int?, session: String, by: String,
        agentId: String = com.kilon.agentvscreen.api.Agents.ofSession(session),
    ): Added {
        load(ctx)
        val spec = parse(text)
        val f = (forMin ?: DEFAULT_FOR_MIN).coerceIn(1, MAX_FOR_MIN)
        val stored = spec.canonical().ifEmpty { text.trim() }
        val given = name?.trim()?.takeIf { it.isNotEmpty() }?.take(32)
        dropOver(ctx)
        val e = synchronized(lock) {
            if (given == null) entries.firstOrNull {
                it.agentId == agentId && it.session == session && it.spec == spec && it.forMin == f
            }?.let { return Added(it, true) }
            val n = given ?: run {
                var i = entries.count { it.agentId == agentId } + 1
                while (entries.any { it.agentId == agentId && it.name == "w$i" }) i++
                "w$i"
            }
            val k = Key(agentId, n)
            val others = entries.filter { it.key != k }
            if (others.count { it.agentId == agentId } >= MAX_ENTRIES) {
                throw IllegalStateException(tr("最多 $MAX_ENTRIES 条预约：先 schedule off 一条", "At most $MAX_ENTRIES schedules: remove one with schedule off first"))
            }
            if (others.size >= MAX_TOTAL) {
                throw IllegalStateException(tr("手机上所有 agent 合计最多 $MAX_TOTAL 条预约：先 schedule off 一条，或请用户在 App 里删掉不用的", "At most $MAX_TOTAL schedules on the phone across all agents: remove one with schedule off, or ask the user to delete unused ones in the app"))
            }
            Entry(n, stored, spec, f, session, by, System.currentTimeMillis(), agentId).also { entries.clear(); entries.addAll(others + it) }
        }
        save(ctx); arm(ctx)
        return Added(e, false)
    }

    /**
     * 1.3.0-beta15: the user changed `(agentId, name)` in the app (time, days, window length, switch), like editing an
     * alarm. The agent that booked it and its session stay; a new time starts fresh (nothing counts as opened yet).
     * Throws [BadSpec] for a time that cannot be read (or a one-off already past).
     */
    fun edit(ctx: Context, agentId: String, name: String, text: String, forMin: Int, on: Boolean): Entry {
        load(ctx)
        val spec = parse(text)
        val k = Key(agentId, name)
        val e = synchronized(lock) {
            val old = entries.firstOrNull { it.key == k } ?: throw BadSpec(tr("这个预约已经不在了", "this schedule is gone"))
            val n = Entry(name, spec.canonical().ifEmpty { text.trim() }, spec, forMin.coerceIn(1, MAX_FOR_MIN), old.session, old.by, old.at, old.agentId)
            n.on = on; n.last = old.last
            if (spec == old.spec && n.forMin == old.forMin) n.opened = old.opened
            entries[entries.indexOf(old)] = n
            n
        }
        save(ctx); arm(ctx)
        AppLogger.info(TAG, "edited by the user: $agentId/$name")
        return e
    }

    /** A null name removes all of this agent's bookings. UI callers pass the exact name. */
    fun remove(ctx: Context, name: String?, agent: String): Int {
        load(ctx)
        val n = synchronized(lock) { val before = entries.size; entries.removeAll { it.agentId == agent && (name == null || it.name == name) }; before - entries.size }
        if (n > 0) { save(ctx); arm(ctx) }
        return n
    }

    // ── the alarm ────────────────────────────────────────────────────

    fun exactAllowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        runCatching { ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() }.getOrNull() == true

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 7701,
        Intent(ctx, WakeAlarmReceiver::class.java).setAction(ACTION_ALARM), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Work out every entry's next window and set one alarm for the earliest. Called on change, boot, app start and after each alarm. */
    @Synchronized fun arm(ctx: Context) {
        load(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        dropOver(ctx, now)
        val list = synchronized(lock) { entries.toList() }
        // 1.3 beta2: "next" is the first window that has not ended (one in progress stays next, it does not jump to
        // tomorrow), and the alarm is for the first window not opened yet. Before, any change of the list re-armed the
        // window in progress and it was opened (and announced) again each time.
        list.forEach { e -> e.nextAt = if (e.on) next(e.spec, now - e.forMin * 60_000L) ?: 0L else 0L }
        val first = list.mapNotNull { e -> e.pending(now)?.let { it - LEAD_MS } }.minOrNull()
        val pi = alarmIntent(ctx)
        if (first == null) { am.cancel(pi); return }
        val at = maxOf(first, now + 1_000L)
        runCatching {
            if (exactAllowed(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { AppLogger.warning(TAG, "arm: ${it.message}") }
        AppLogger.info(TAG, "armed for ${java.util.Date(at)} (${list.size} entries, exact=${exactAllowed(ctx)})")
    }

    /** The alarm went off: open every window that starts within the lead time. */
    internal fun onAlarm(ctx: Context) {
        load(ctx)
        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            entries.mapNotNull { e -> e.pending(now)?.takeIf { it - LEAD_MS <= now + 30_000L }?.let { e to it } }
                .onEach { (e, at) -> e.opened = at; e.nextAt = at }.map { it.first }
        }
        if (due.isNotEmpty()) {
            save(ctx)
            // 1.3 beta2: the user's "service off" is never overruled by a booking an agent made: the window is checked
            // (it reports service_off, the agent gets the event, the user a notification to turn it on), not started.
            // Before, the alarm started the service anyway while the switch still showed it off.
            // Unless the user chose "off until the next booking": then this window turns it back on.
            ServiceControl.windowDue(ctx)
            val until = due.maxOf { it.nextAt + it.forMin * 60_000L }
            openWindow(ctx, due, until)
        }
        arm(ctx)
    }

    private var lockHeld: PowerManager.WakeLock? = null
    private var heldUntilElapsed = 0L

    @Synchronized private fun holdWindow(ctx: Context, holdMs: Long) {
        val until = SystemClock.elapsedRealtime() + holdMs
        if (until <= heldUntilElapsed && lockHeld?.isHeld == true) return
        val wake = lockHeld ?: ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AgentVScreen:wake")
            .apply { setReferenceCounted(false); lockHeld = this }
        wake.acquire(holdMs)
        heldUntilElapsed = until
    }

    private fun openWindow(ctx: Context, due: List<Entry>, untilMs: Long) {
        val holdMs = (untilMs - System.currentTimeMillis()).coerceIn(60_000L, LEAD_MS + MAX_FOR_MIN * 60_000L)
        quiet("wake-lock") { holdWindow(ctx, holdMs) }
        Thread({
            val t0 = SystemClock.elapsedRealtime()
            // Shizuku comes up after us after a boot, and binding takes a moment: give it a minute.
            while (!ShizukuManager.isReady() && SystemClock.elapsedRealtime() - t0 < 60_000L) Thread.sleep(1_000)
            val did = ArrayList<String>()
            if (ShizukuManager.isReady()) {
                val r = runCatching { com.kilon.agentvscreen.core.VScreenShell.run(arrayOf("cmd", "deviceidle", "tempwhitelist", "-d", holdMs.toString(), ctx.packageName), 6_000L) }.getOrNull()
                did += if (r?.exitCode == 0) tr("临时省电豁免 ${holdMs / 60_000} 分钟", "temporary power allowlist for ${holdMs / 60_000} min") else tr("临时省电豁免没成功", "temporary power allowlist failed")
                quiet("wake-power") { com.kilon.agentvscreen.core.VScreenPower.heal(ctx, "wake") }
                val s = BackgroundOperation.state.value
                if (s.connection.name == "ERROR" && !s.needsExplicitReconnect) quiet("wake-connect") { BackgroundOperation.connect(); did += tr("重新连接了后台连接", "reconnected the background connection") }
            }
            BackgroundOperation.refreshStatus()
            val items = Readiness.now(ctx)
            val ok = Readiness.ready(items)
            val check = JSONObject().put("at", System.currentTimeMillis() / 1000).put("ready", ok)
                .put("problems", JSONArray(items.map { it.json() })).put("done", JSONArray(did)).put("exact_alarm", exactAllowed(ctx))
            synchronized(lock) { due.forEach { it.last = check } }
            save(ctx)
            val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
            fun windowNames(group: List<Entry>) = group.joinToString(tr("、", ", ")) { "${it.name} ${clock.format(java.util.Date(it.opened))}" }
            fun eventText(whenText: String) = if (ok) tr("预约窗口 $whenText 已就绪：服务在运行，Shizuku 可用。", "Window $whenText is ready: the service is running and Shizuku is available.") +
                    items.joinToString("") { tr("（提醒：${it.text}）", " (note: ${it.text})") }
                else tr("预约窗口 $whenText 前的检查没通过：", "The check before window $whenText failed: ") + items.filter { it.blocking }.joinToString(tr("；", "; ")) { it.text }
            due.groupBy { it.session }.forEach { (session, group) ->
                quiet("wake-event") { com.kilon.agentvscreen.api.EventHub.post(ctx, session, "wake", group.first().name, eventText(windowNames(group))) }
            }
            val whenText = windowNames(due)
            if (!ok) quiet("wake-alert") {
                // 1.3 beta4: say plainly what happens to the agent's task and what the user can do, with the one tap
                // that does it. "Off" is the user's own choice, so it is told as a consequence, not as a fault.
                val tap = Needs.open(ctx, Needs.Page.HOME, 5)
                val on = android.app.PendingIntent.getForegroundService(ctx, 7704, Intent(ctx, HostService::class.java).setAction(HostService.ACTION_ON), android.app.PendingIntent.FLAG_IMMUTABLE)
                if (items.any { it.blocking && it.code == "service_off" })
                    com.kilon.agentvscreen.core.VScreenHandoff.alert(ctx, 7702,
                        tr("agent 预约的 $whenText 到了，但服务是关闭的", "The agent's booking $whenText is due, but the service is off"),
                        tr("这次 agent 用不了手机。要让它来，现在打开服务；只想让服务不占通知栏的话，在电源键里选“按需”，预约照样能叫醒它。",
                            "The agent cannot use the phone this time. To let it in, turn the service on now. If you only want it out of the notification shade, choose On demand with the power button; bookings still wake it."),
                        tap, listOf(tr("现在打开服务", "Turn on now") to on))
                else com.kilon.agentvscreen.core.VScreenHandoff.alert(ctx, 7702,
                    tr("agent 预约的 $whenText 快到了，但还有问题要你处理", "The agent's booking $whenText is due, but something needs you"),
                    items.filter { it.blocking }.joinToString("\n") { it.text } + tr("\n点这里打开 Agent-VScreen 处理。", "\nTap to open Agent-VScreen."), tap)
            }
            AppLogger.info(TAG, "window $whenText: ready=$ok ${items.joinToString(",") { it.code }} did=$did")
        }, "vscreen-wake").apply { isDaemon = true }.start()
    }

    // ── the local wake-up (CLI) ──────────────────────────────────────

    @Volatile private var lastPoke = 0L

    /** A local caller found us silent. Start the service; nothing else, nothing returned, at most every 3 s. */
    internal fun poke(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPoke < 3_000L) return
        lastPoke = now
        AppLogger.info(TAG, "woken by a local caller")
        // 1.3 beta4: a hidden service is woken too; a blocked one is not (ServiceControl.wake).
        ServiceControl.wake(ctx, "local agent")
    }

    /** A booked window is open now (the service should stay awake for it). */
    fun windowOpen(ctx: Context, now: Long): Boolean = list(ctx).any { it.on && it.opened > 0 && now in (it.opened - LEAD_MS - 30_000L)..(it.opened + it.forMin * 60_000L) }
}

/** The schedule's alarm (not exported: only our own PendingIntent sends it). */
class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED) { Wake.arm(context.applicationContext); return }
        if (intent.action != Wake.ACTION_ALARM) return
        val pending = goAsync()
        Thread({ try { Wake.onAlarm(context.applicationContext) } finally { pending.finish() } }, "vscreen-wake-alarm").start()
    }
}

/**
 * Exported on purpose: a CLI on this phone (any app's terminal) that finds the app silent sends this explicit
 * broadcast. Delivering a broadcast thaws a frozen process, and the receiver makes sure the service runs. It takes no
 * extras and returns nothing, so it cannot be used to read or change anything.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Wake.ACTION_WAKE) return
        // 1.3 beta4: the CLI reads this answer from `am broadcast`: "blocked" = the user blocked agents (say so, do not
        // blame the system), "waking" = on its way.
        if (Prefs.blocked(context)) { resultData = "blocked"; return }
        resultData = "waking"
        Wake.poke(context.applicationContext)
    }
}
