package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.S
import android.content.Context
import android.content.Intent
import com.kilon.agentvscreen.host.quiet
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Events for callers that are not running when something happens (a kept watch saw its condition).
 *
 * 1.2:
 *  - Delivery is at-least-once. `events` hands out a batch and keeps it until the caller's NEXT `events` call (which
 *    confirms the previous batch arrived); `events --again` returns the last batch once more. Before, the queue was
 *    emptied the moment the reply was written, so a reply lost on the network lost the events.
 *  - Events are on disk: an app restart does not drop them.
 *  - Every event has a number ([seq]) that only grows, so a caller can tell a repeat from a new one.
 *  - The broadcast is no longer sent to every app on the phone (it carried what was seen on the screen). It goes
 *    only to the one package the user names in the app (ApiConfig.eventTarget); none by default.
 */
object EventHub {
    const val ACTION = "com.kilon.agentvscreen.action.EVENT"
    private const val MAX = 50
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val queues = HashMap<String, ArrayDeque<JSONObject>>()
    /** Per session: the batch handed out by the last `events`, kept until the next one. */
    private val handed = HashMap<String, List<JSONObject>>()
    /** When each [handed] batch went out. */
    private val handedAt = HashMap<String, Long>()
    /**
     * 1.3.0-beta17: an unconfirmed batch is given again after a restart only while this young. A one-shot caller often
     * never calls `events` again, so its last batch stayed "unconfirmed" for days and came back after every app
     * restart or update, as noise in front of the event it was really waiting for. Older than this, the reply that
     * carried it surely arrived (or nobody is there to want it). A batch already given again is never given a third time.
     */
    internal const val REDELIVER_MS = 10 * 60_000L
    private var seq = 0L
    @Volatile private var file: File? = null

    fun init(ctx: Context) {
        file = File(File(ctx.filesDir, "vscreen").apply { mkdirs() }, "events.json")
        quiet("events-read") {
            val o = file?.takeIf { it.isFile }?.readText()?.let { JSONObject(it) } ?: return@quiet
            lock.withLock {
                seq = o.optLong("seq")
                val q = o.optJSONObject("queues") ?: JSONObject()
                for (k in q.keys()) { val a = q.optJSONArray(k) ?: continue; queues[k] = ArrayDeque((0 until a.length()).mapNotNull { a.optJSONObject(it) }) }
                // 1.3 beta2: a batch handed out before the restart and never confirmed is given again, marked as a
                // repeat (same seq), so the caller can tell it already had it. Before, it came back looking new.
                val h = o.optJSONObject("handed") ?: JSONObject()
                val hAt = o.optJSONObject("handed_at") ?: JSONObject()
                val now = System.currentTimeMillis()
                for (k in h.keys()) {
                    val a = h.optJSONArray(k) ?: continue
                    val at = hAt.optLong(k, 0L).takeIf { it > 0 } ?: (0 until a.length()).maxOfOrNull { a.optJSONObject(it)?.optLong("at") ?: 0L } ?: 0L
                    if (now - at > REDELIVER_MS) continue
                    val back = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { !it.optBoolean("repeat") }.onEach { it.put("repeat", true) }
                    val qq = queues.getOrPut(k) { ArrayDeque() }
                    back.asReversed().forEach { qq.addFirst(it) }
                }
            }
        }
    }

    private fun persist() = quiet("events-write") {
        val q = JSONObject(); val h = JSONObject(); val hAt = JSONObject()
        // The handed-out batch is not confirmed yet: after a restart it is given again, as a repeat.
        queues.keys.forEach { k -> q.put(k, JSONArray(queues[k].orEmpty())) }
        handed.forEach { (k, v) -> h.put(k, JSONArray(v)); hAt.put(k, handedAt[k] ?: System.currentTimeMillis()) }
        file?.writeText(JSONObject().put("seq", seq).put("queues", q).put("handed", h).put("handed_at", hAt).toString())
    }

    /** Returns a short note of where it went. */
    fun post(ctx: Context, session: String, kind: String, name: String, text: String): String {
        val ev = lock.withLock {
            val e = JSONObject().put("seq", ++seq).put("session", session).put("kind", kind).put("name", name).put("text", text).put("at", System.currentTimeMillis())
            val q = queues.getOrPut(session) { ArrayDeque() }
            q.addLast(e); while (q.size > MAX) q.removeFirst()
            persist()
            arrived.signalAll()
            e
        }
        val target = ApiConfig.eventTarget
        val sent = target != null && quiet("event-broadcast") {
            ctx.sendBroadcast(Intent(ACTION).setPackage(target).putExtra("session", session).putExtra("kind", kind).putExtra("name", name)
                .putExtra("seq", ev.optLong("seq")).putExtra("text", text).putExtra("json", ev.toString())); true
        } == true
        return tr(S.event_hub_queued_for_session_collect_with, session) + if (sent) tr(S.event_hub_and_broadcast_to, target) else ""
    }

    /**
     * The events of [session]; when there is none, wait up to [waitMs]. Calling it confirms the previous batch.
     * [again]: the previous batch once more (plus nothing new).
     */
    fun take(session: String, waitMs: Long, again: Boolean = false): List<JSONObject> = lock.withLock {
        if (again) return@withLock handed[session].orEmpty()
        if (handed.remove(session) != null) { handedAt.remove(session); persist() }
        // 1.2.0-beta2: wait in short slices and look whether the caller is still there. A waiter whose connection was
        // gone used to stay until its time ran out and then took the batch that arrived, to nowhere.
        val call = com.kilon.agentvscreen.cmd.Call.current()
        var left = TimeUnit.MILLISECONDS.toNanos(waitMs.coerceIn(0, 120_000))
        while ((queues[session]?.isEmpty() != false) && left > 0 && call?.cancelled != true) {
            val slice = minOf(left, TimeUnit.MILLISECONDS.toNanos(500))
            val rest = arrived.awaitNanos(slice)
            left -= slice - rest.coerceAtLeast(0)
        }
        if (call?.cancelled == true) return@withLock emptyList()
        val q = queues[session] ?: return@withLock emptyList()
        val out = q.toList(); q.clear()
        if (out.isNotEmpty()) { handed[session] = out; handedAt[session] = System.currentTimeMillis(); persist() }
        out
    }

    fun pending(session: String): Int = lock.withLock { queues[session]?.size ?: 0 }

    internal fun resetForTest() = lock.withLock { queues.clear(); handed.clear(); handedAt.clear(); seq = 0; file = null }
}
