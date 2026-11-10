package com.kilon.agentvscreen.api

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
            }
        }
    }

    private fun persist() = quiet("events-write") {
        val q = JSONObject()
        // The handed-out batch is still undelivered as far as the disk is concerned: after a restart it is given again.
        (queues.keys + handed.keys).forEach { k -> q.put(k, JSONArray(handed[k].orEmpty() + queues[k].orEmpty())) }
        file?.writeText(JSONObject().put("seq", seq).put("queues", q).toString())
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
        return tr("已放进会话 $session 的事件队列（vscreen events 取）", "Queued for session $session (collect with vscreen events)") + if (sent) tr("，并广播给 $target", " and broadcast to $target") else ""
    }

    /**
     * The events of [session]; when there is none, wait up to [waitMs]. Calling it confirms the previous batch.
     * [again]: the previous batch once more (plus nothing new).
     */
    fun take(session: String, waitMs: Long, again: Boolean = false): List<JSONObject> = lock.withLock {
        if (again) return@withLock handed[session].orEmpty()
        if (handed.remove(session) != null) persist()
        var left = TimeUnit.MILLISECONDS.toNanos(waitMs.coerceIn(0, 120_000))
        while ((queues[session]?.isEmpty() != false) && left > 0) left = arrived.awaitNanos(left)
        val q = queues[session] ?: return@withLock emptyList()
        val out = q.toList(); q.clear()
        if (out.isNotEmpty()) { handed[session] = out; persist() }
        out
    }

    fun pending(session: String): Int = lock.withLock { queues[session]?.size ?: 0 }

    internal fun resetForTest() = lock.withLock { queues.clear(); handed.clear(); seq = 0; file = null }
}
