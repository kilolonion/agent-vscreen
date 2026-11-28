package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.tr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.util.writeText
import androidx.core.app.NotificationCompat
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps — an act's stop condition that goes on watching after the turn (`act --stop C --keep wake|notify`).
 *
 * Between turns, on the phone only (the same condition evaluator as act, no model calls), for up to 12 hours: when it
 * is seen, wake the chat or notify the user, and end. Nothing is done on the screen by itself. While one runs the idle
 * screen is neither paused nor released, a foreground notification shows what is watched (with a stop button) and a
 * partial wake lock keeps the checks going. 1.2: they are written to disk and come back when the app restarts
 * (the screen itself does not: a restored watch waits until its session launches again).
 */
internal object VScreenRules {
    private const val TAG = "VScreenRules"
    const val MAX = 5
    /** 1.4.1-beta1: per agent is [MAX]; each watch is a thread checking every second, so the phone as a whole is bounded too. */
    const val MAX_TOTAL = 10

    // ── background rules: kept watching between turns, on the phone only ──

    data class Key(val agentId: String, val name: String)

    class Spec(
        val name: String, val sid: String,
        /** The condition as written in act --stop. */
        val cond: String,
        val app: String?, val everyMs: Long, val forMs: Long,
        val wake: Boolean, val notify: Boolean,
        val agentId: String = com.kilon.agentvscreen.api.Agents.ofSession(sid),
    ) {
        val key: Key get() = Key(agentId, name)
        fun describe(): String = tr(S.vscreen_rules_when, cond) + (app?.let { tr(S.public_guide_text, it) } ?: "")
    }

    class Live(val spec: Spec) {
        /** Wall-clock end (survives a restart, unlike elapsed time). */
        val endsAt = System.currentTimeMillis() + spec.forMs
        val startedAt = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()
        @Volatile var checks = 0
        @Volatile var status = tr(S.vscreen_rules_starting)
        @Volatile var lastValue: String? = null
        @Volatile var fired = 0
        @Volatile var lastFire: String? = null
        @Volatile var lastFireAt = 0L
        @Volatile var stopped = false
        @Volatile var endReason: String? = null
        @Volatile var endedAt = 0L
        @Volatile var thread: Thread? = null

        fun json(): JSONObject {
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
            return JSONObject().put("name", spec.name).put("agent", spec.agentId).put("condition", spec.describe())
                .put("every_ms", spec.everyMs)
                .put("running", !stopped)
                .put("status", if (stopped) (endReason ?: tr(S.records_ended)) else status)
                .put("started", fmt.format(java.util.Date(startedAt)))
                .put("ends_in_min", if (stopped) JSONObject.NULL else ((spec.forMs - (SystemClock.elapsedRealtime() - startedElapsed)) / 60_000L).coerceAtLeast(0))
                .put("checks", checks).put("fired", fired)
                .put("last_value", lastValue ?: JSONObject.NULL)
                .put("last_fire", lastFire ?: JSONObject.NULL)
                .put("last_fire_at", if (lastFireAt > 0) fmt.format(java.util.Date(lastFireAt)) else JSONObject.NULL)
                .put("action", JSONArray(listOfNotNull(if (spec.wake) tr(S.vscreen_rules_sends_an_event_to_the) else null, if (spec.notify) tr(S.vscreen_rules_notifies_the_user) else null)))
        }
    }

    /** What the tool handler gives the engine. */
    interface Host {
        /** null = not met; otherwise what was seen. */
        fun check(l: Live, d: VScreenDisplay): String?
    }

    @Volatile private var appContext: Context? = null
    /** 1.2: builds the evaluator of a kept condition (set by the command layer); with it kept watches come back after a restart. */
    @Volatile var hostFactory: ((String) -> Host)? = null
    private var seq = 0
    private val agentSeq = HashMap<String, Int>()
    fun nextName(agentId: String = com.kilon.agentvscreen.api.Tokens.MAIN): String = synchronized(live) {
        val p = tr(S.vscreen_rules_watch)
        var i = maxOf(seq, agentSeq[agentId] ?: 0) + 1
        while (Key(agentId, "$p$i") in live || ended.any { it.spec.agentId == agentId && it.spec.name == "$p$i" }) i++
        agentSeq[agentId] = i
        if (agentId == com.kilon.agentvscreen.api.Tokens.MAIN) seq = maxOf(seq, i)
        "$p$i"
    }
    private val live = LinkedHashMap<Key, Live>()
    private val ended = ArrayDeque<Live>()

    fun init(context: Context) { appContext = context.applicationContext }

    internal fun resetForTest() {
        stopAll("test")
        synchronized(live) { ended.clear(); seq = 0; agentSeq.clear() }
        file()?.delete()
    }

    fun list(): List<Live> = synchronized(live) { live.values.toList() + ended.toList().reversed().filter { it.spec.key !in live } }
    fun running(): List<Live> = synchronized(live) { live.values.toList() }

    fun start(spec: Spec, host: Host): Live {
        val ctx = appContext ?: throw IllegalStateException(tr(S.vscreen_rules_background_operation_is_not_initialized))
        val l = synchronized(live) {
            live[spec.key]?.let { stopInner(it, tr(S.vscreen_rules_replaced_by_a_new_watch), save = false) }
            register(spec).also { persist() }
        }
        activate(ctx, l, host)
        return l
    }

    /** Caller holds live while checking limits and publishing the entry. */
    private fun register(spec: Spec): Live {
        if (com.kilon.agentvscreen.host.ThermalGuard.locked) com.kilon.agentvscreen.host.ThermalGuard.throwStopped()
        if (live.values.count { it.spec.agentId == spec.agentId } >= MAX) {
            throw IllegalStateException(tr(S.vscreen_rules_at_most_conditions_can_be, MAX))
        }
        if (live.size >= MAX_TOTAL) {
            throw IllegalStateException(tr(S.vscreen_rules_at_most_watches_on_the, MAX_TOTAL))
        }
        return Live(spec).also { live[spec.key] = it }
    }

    private fun activate(ctx: Context, l: Live, host: Host) {
        synchronized(live) {
            if (live[l.spec.key] !== l) return
            BackgroundOperation.hold(true)
            val worker = Thread({ loop(ctx, l, host) }, "vscreen-rule-${l.spec.name.take(12)}").apply { isDaemon = true }
            l.thread = worker
            worker.start()
        }
        RuleService.refresh(ctx)
        AppLogger.info(TAG, "keep ${l.spec.agentId}/${l.spec.name} started: ${l.spec.describe()}")
    }

    // ── 1.2: kept watches survive the app being restarted ───────────────────────────────────────────────────────

    private fun file(): java.io.File? = appContext?.let { java.io.File(java.io.File(it.filesDir, "vscreen").apply { mkdirs() }, "keeps.json") }

    private fun persist() = com.kilon.agentvscreen.host.quiet("keeps-persist") {
        synchronized(live) {
            val arr = JSONArray(live.values.map { l -> JSONObject().put("name", l.spec.name).put("agent", l.spec.agentId).put("sid", l.spec.sid).put("cond", l.spec.cond)
                .put("app", l.spec.app ?: JSONObject.NULL).put("every", l.spec.everyMs).put("ends_at", l.endsAt).put("wake", l.spec.wake).put("notify", l.spec.notify) })
            val seqObj = JSONObject().also { o -> agentSeq.forEach { (k, v) -> o.put(k, v) } }
            file()?.let { AtomicFile(it).writeText(JSONObject().put("seq", seq).put("agent_seq", seqObj).put("keeps", arr).toString()) }
        }
    }

    /** Start again the watches that were running when the app went down and still have time left. Their count. */
    fun restore(): Int {
        if (com.kilon.agentvscreen.host.ThermalGuard.locked) return 0
        val factory = hostFactory ?: return 0
        val ctx = appContext ?: return 0
        val restored = mutableListOf<Pair<Live, Host>>()
        synchronized(live) {
            val o = com.kilon.agentvscreen.host.quiet("keeps-read") {
                val f = file() ?: return@quiet null
                try {
                    AtomicFile(f).openRead().bufferedReader().use { JSONObject(it.readText()) }
                } catch (_: java.io.FileNotFoundException) { null }
            } ?: return 0
            seq = maxOf(seq, o.optInt("seq"))
            o.optJSONObject("agent_seq")?.let { s ->
                val keys = s.keys()
                while (keys.hasNext()) { val k = keys.next(); agentSeq[k] = maxOf(agentSeq[k] ?: 0, s.optInt(k)) }
            }

            val arr = o.optJSONArray("keeps") ?: return 0
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val k = arr.optJSONObject(i) ?: continue
                val left = k.optLong("ends_at") - now
                val name = k.optString("name")
                // 1.4.1-beta1: keeps from before the explicit agentId belong to the agent their session names (since
                // 1.4.0-beta7 "<token id>.<session>"); a session without a known prefix is the main token's.
                val agentId = k.optString("agent").takeIf { it.isNotEmpty() && it != "null" }
                    ?: com.kilon.agentvscreen.api.Agents.ofSession(k.optString("sid"))
                val key = Key(agentId, name)
                if (left < 10_000L || live.containsKey(key)) continue
                com.kilon.agentvscreen.host.quiet("keeps-restore") {
                    val spec = Spec(name, k.optString("sid"), k.optString("cond"), k.optString("app").takeIf { it.isNotEmpty() && it != "null" }, k.optLong("every", 1_000L), left, k.optBoolean("wake"), k.optBoolean("notify"), agentId)
                    val host = factory(spec.cond)
                    restored.add(register(spec) to host)
                }
            }
            // The old complete file stays intact until every surviving watch is registered.
            persist()
        }
        restored.forEach { (watch, host) -> activate(ctx, watch, host) }
        if (restored.isNotEmpty()) AppLogger.info(TAG, "restored ${restored.size} kept watches after a restart")
        return restored.size
    }

    fun stop(agentId: String, name: String, reason: String = tr(S.vscreen_rules_stopped_as_asked)): Boolean {
        val l = synchronized(live) { live[Key(agentId, name)] } ?: return false
        stopInner(l, reason)
        return true
    }

    /** Forget ended watches of [agentId] ([name] null = all of [agentId]); the number forgotten. */
    fun clearEnded(agentId: String, name: String? = null): Int = synchronized(live) {
        val before = ended.size
        ended.removeAll { it.spec.agentId == agentId && (name == null || it.spec.name == name) }
        before - ended.size
    }

    internal fun discardForHeat(reason: String) { stopAll(reason); persist() }

    fun stopAll(reason: String = tr(S.vscreen_rules_stopped_as_asked)): Int {
        val all = running()
        all.forEach { stopInner(it, reason) }
        return all.size
    }

    private fun stopInner(l: Live, reason: String, save: Boolean = true) {
        val removed = synchronized(live) {
            if (l.stopped) return
            l.stopped = true; l.endReason = reason; l.endedAt = System.currentTimeMillis()
            val r = live.remove(l.spec.key) != null
            ended.removeAll { it.spec.key == l.spec.key }
            ended.addLast(l); while (ended.size > 10) ended.removeFirst()
            r
        }
        l.thread?.interrupt()
        if (removed) {
            if (l.thread != null) BackgroundOperation.hold(false)
            if (save) persist()
        }
        appContext?.let { RuleService.refresh(it) }
        AppLogger.info(TAG, "keep ${l.spec.agentId}/${l.spec.name} ended: $reason")
    }

    private fun loop(ctx: Context, l: Live, host: Host) {
        val s = l.spec
        var appOk = true
        var n = 0
        try {
            while (!l.stopped) {
                if (SystemClock.elapsedRealtime() - l.startedElapsed >= s.forMs) {
                    stopInner(l, if (l.fired > 0) tr(S.vscreen_rules_time_is_up_fired_times, l.fired) else tr(S.vscreen_rules_time_is_up_the_condition))
                    break
                }
                val st = BackgroundOperation.state.value
                val d = BackgroundOperation.previewDisplayFor(s.sid)
                when {
                    d == null -> l.status = if (st.ownerSessionId != null && st.ownerSessionId != s.sid)
                        tr(S.vscreen_rules_the_screen_is_used_by)
                    else tr(S.vscreen_rules_the_screen_was_released_checks)
                    st.takeoverSessionId != null -> l.status = tr(S.vscreen_rules_the_user_is_controlling_the)
                    else -> {
                        if (d.parked) runCatching { BackgroundOperation.unpark(d) }
                        if (s.app != null && (n % maxOf(1, (3_000 / s.everyMs).toInt()) == 0)) appOk = VScreenShell.topPackage(d.displayId) == s.app
                        n++
                        if (!appOk) l.status = tr(S.vscreen_rules_the_top_of_the_screen, s.app)
                        else {
                            var failed: String? = null
                            val hit = try { host.check(l, d) } catch (e: InterruptedException) { throw e } catch (t: Throwable) {
                                failed = tr(S.vscreen_rules_check_error, t.javaClass.simpleName, t.message?.take(80)); null }
                            l.checks++
                            l.status = failed ?: tr(S.vscreen_rules_watching)
                            if (hit != null) fire(ctx, l, host, hit)
                        }
                    }
                }
                if (l.stopped) break
                Thread.sleep(s.everyMs)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "keep ${s.name}: ${t.message}")
            stopInner(l, tr(S.vscreen_rules_stopped_on_an_error, t.javaClass.simpleName, t.message?.take(100)))
        }
    }

    private fun fire(ctx: Context, l: Live, @Suppress("UNUSED_PARAMETER") host: Host, hit: String) {
        val s = l.spec
        l.fired++
        l.lastFireAt = System.currentTimeMillis()
        val parts = arrayListOf(tr(S.vscreen_rules_seen, hit))
        var woke: String? = null
        if (s.wake) {
            val text = tr(S.vscreen_rules_watch_saw_it_condition_this, s.name, hit, s.cond)
            woke = runCatching { com.kilon.agentvscreen.api.EventHub.post(ctx, s.sid, "keep", s.name, text) }
                .getOrElse { tr(S.vscreen_rules_failed, it.message) }
            parts += tr(S.vscreen_rules_event, woke)
        }
        if (s.notify || woke?.let { it == "Rejected" || it.startsWith(tr(S.vscreen_rules_failed_2)) } == true) {
            val okN = VScreenHandoff.alert(ctx, 7300 + (s.name.hashCode() and 0xff), tr(S.vscreen_rules_watch_saw_it, s.name), parts.joinToString("\n"),
                com.kilon.agentvscreen.host.Needs.open(ctx, com.kilon.agentvscreen.host.Needs.Page.RECORDS, 3))
            if (!okN) parts += tr(S.vscreen_rules_notification_not_sent) + (VScreenHandoff.notifyBlocked(ctx) ?: tr(S.vscreen_rules_sending_failed)) + tr(S.agent_guide_text_2)
        }
        l.lastFire = parts.joinToString(tr(S.agent_guide_sep))
        BackgroundOperation.backgroundDone(s.sid)
        AppLogger.info(TAG, "keep ${s.name} fired: ${l.lastFire}")
        stopInner(l, tr(S.vscreen_rules_seen_2, l.lastFire?.take(160)))
    }

    // ── keeping it alive: foreground notification + wake lock ─────────────

    class RuleService : Service() {
        private var wake: PowerManager.WakeLock? = null

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            // 1.3.0: a service started with startForegroundService must call startForeground on EVERY start, even one
            // that only ends it. A watch whose condition held at once ended 7 ms after it began; this start then found
            // no watch and stopped without startForeground, and Android killed the whole app
            // (ForegroundServiceDidNotStartInTimeException). So: foreground first, then decide.
            val list = running()
            runCatching {
                com.kilon.agentvscreen.host.Fg.start(this, NOTE_ID, notification(this, list))
            }.onFailure { AppLogger.warning(TAG, "startForeground: ${it.message}") }
            pendingAt = 0L
            if (intent?.action == ACTION_STOP) {
                stopAll(tr(S.vscreen_rules_the_user_tapped_stop_all))
                stopSelfSafely(); return START_NOT_STICKY
            }
            if (com.kilon.agentvscreen.host.ThermalGuard.locked || running().isEmpty()) { stopSelfSafely(); return START_NOT_STICKY }
            if (wake == null) runCatching {
                wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agentvscreen:rules").apply { setReferenceCounted(false); acquire(12 * 3_600_000L + 60_000L) }
            }
            return START_NOT_STICKY
        }

        private fun stopSelfSafely() {
            com.kilon.agentvscreen.host.quiet("VScreenRules:273") { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }

        override fun onDestroy() {
            com.kilon.agentvscreen.host.quiet("VScreenRules:278") { wake?.takeIf { it.isHeld }?.release() }
            wake = null
            super.onDestroy()
        }

        companion object {
            const val ACTION_STOP = "com.kilon.agentvscreen.core.RULES_STOP"
            private const val CHANNEL = "vscreen_rules"
            private const val NOTE_ID = 7299
            @Volatile var lastStartError: String? = null
            /** A startForegroundService was sent and its onStartCommand has not run yet: it must not be stopped from outside. */
            @Volatile private var pendingAt = 0L
            private val pending get() = pendingAt > 0 && android.os.SystemClock.elapsedRealtime() - pendingAt < 10_000L

            private fun notification(ctx: Context, list: List<Live>): android.app.Notification {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm?.getNotificationChannel(CHANNEL) == null)
                    nm?.createNotificationChannel(NotificationChannel(CHANNEL, tr(S.vscreen_rules_screen_watches), NotificationManager.IMPORTANCE_LOW))
                val stop = PendingIntent.getService(ctx, 1, Intent(ctx, RuleService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val body = list.joinToString("\n") { tr(S.vscreen_rules_text, it.spec.name, it.spec.describe()) }
                return NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle(tr(S.vscreen_rules_the_screen_is_watching_conditions, list.size))
                    .setContentText(body.lineSequence().first())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setOngoing(true)
                    .setContentIntent(com.kilon.agentvscreen.host.Needs.open(ctx, com.kilon.agentvscreen.host.Needs.Page.HOME, 4))
                    .addAction(0, tr(S.vscreen_rules_stop_all), stop)
                    .build()
            }

            /** Start, update or stop the service to match the running keeps. */
            fun refresh(ctx: Context) {
                val list = running()
                val i = Intent(ctx, RuleService::class.java)
                // A start on its way stops the service itself once it has gone foreground (see onStartCommand).
                if (list.isEmpty()) { if (!pending) runCatching { ctx.stopService(i) }; return }
                lastStartError = runCatching {
                    pendingAt = android.os.SystemClock.elapsedRealtime()
                    ctx.startForegroundService(i)
                    null
                }.getOrElse { pendingAt = 0L; "${it.javaClass.simpleName}: ${it.message}" }
                if (lastStartError != null) {
                    AppLogger.warning(TAG, "keep service not started: $lastStartError")
                    // At least show what is being watched.
                    com.kilon.agentvscreen.host.quiet("VScreenRules:320") { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, notification(ctx, list)) }
                }
            }
        }
    }
}
