package com.kilon.agentvscreen.cmd
import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.I18n

import com.kilon.agentvscreen.tr

import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenKnowledge
import com.kilon.agentvscreen.core.VScreenMap
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.core.VScreenShell

// What the agent writes down per app (`note`) and the watches kept after a turn (`keep`).
// Notes are the agent's own words; the phone never derives meaning from them. The profile holds what the runner
// acts on: the before / after steps of every act, and the orientation the app ran in.

/** The app `note` works on: the one this chat launched, else the one on the background screen. */
private fun VScreenOffloadHandler.noteApp(sid: String?): String {
    VScreenKnowledge.journey(sid)?.let { return it }
    val d = sid?.let { BackgroundOperation.previewDisplayFor(it) }
    d?.let { runCatching { VScreenShell.topPackage(it.displayId) }.getOrNull() }?.takeIf { it != context.packageName }?.let { return it }
    VScreenKnowledge.lastApp(sid)?.let { return it }
    throw ActionError("NO_APP", tr(S.vscreen_notes_no_app_to_keep_it))
}

/**
 * 1.4.0-beta13 (test B8: every segment read "saved for Muse", the user's lessons too): where a segment came from, as
 * an agent reads it: the user showed it (and for whom), or an agent wrote it.
 */
internal fun segmentFrom(pkg: String, s: VScreenMap.Segment): String {
    val who = s.by?.let { com.kilon.agentvscreen.api.Agents.name(it) }
    return if (VScreenMap.isTaught(pkg, s)) tr(S.vscreen_notes_taught_by_the_user) + (who?.let { tr(S.vscreen_notes_for, it) } ?: "")
    else who?.let { tr(S.automation_saved_by, it) } ?: tr(S.automation_saved_by_an_agent)
}

/** Where a segment starts, as an agent reads it (null: not known yet). */
internal fun segmentStart(s: VScreenMap.Segment): String? = s.start?.let { st ->
    val app = st.optString("app").takeIf { it.isNotEmpty() && it != "null" }
    val act = st.optString("activity").takeIf { it.isNotEmpty() && it != "null" }?.substringAfterLast('.')
    val words = st.optJSONArray("words")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    listOfNotNull(app, act).joinToString(" / ") + (if (words.isNotEmpty()) tr(S.vscreen_notes_a_page_showing, words.joinToString("」「"), words.joinToString("\", \"")) else "")
}

/** Everything written down about [pkg], as lines. */
internal fun noteLines(pkg: String, verNow: String? = null): List<String> {
    val m = VScreenMap.load(pkg)
    val out = ArrayList<String>()
    val name = VScreenKnowledge.label(pkg)?.let { "$it（$pkg）" } ?: pkg
    out += if (I18n.en) "[$name]" else "【$name】"
    if (m.notes.isEmpty()) out += tr(S.vscreen_notes_notes_none) else { out += tr(S.vscreen_notes_notes); m.notes.forEach { out += "  ${it.id}. ${it.text}" + noteOrigin(it, verNow) } }
    if (m.segments.isEmpty()) out += tr(S.vscreen_notes_segments_none) else { out += tr(S.vscreen_notes_segments_act_use_name); m.segments.forEach { out += "  ${it.name} = ${it.steps}" + tr(S.vscreen_notes_used_times, it.uses) + "（" + segmentFrom(pkg, it) + "）" +
        (if (VScreenMap.picture(pkg, it.name) != null) tr(S.vscreen_notes_lesson_thumbnails_note_show, it.name) else "") } }
    val p = m.profile
    val none = tr(S.vscreen_notes_not_kept)
    out += tr(S.vscreen_notes_before_steps) + (p["before"] ?: none) + tr(S.vscreen_notes_after_steps) + (p["after"] ?: none) +
        (if (p["before"] != null || p["after"] != null) tr(S.vscreen_notes_run_automatically_by_every_act) else "") +
        (p["orientation"]?.let { tr(S.vscreen_notes_orientation, it) } ?: "") +
        (if (p["human"] == "true") tr(S.vscreen_notes_human_pacing_on_note_human) else "")
    return out
}

/**
 * `note` — list; `note <text>` — add; `note before "<steps>"` / `note after "<steps>"` — steps every act on this app
 * runs first / last (e.g. resume and pause a game, dismiss a recurring popup); `note delete <number|before|after|segment>`.
 * 1.2: before/after replace pause/resume (old names still work); a note records who wrote it and for which app version.
 */
internal fun VScreenOffloadHandler.noteCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val p = args.positional
    if (p.firstOrNull()?.lowercase() == "reply") {
        val me = Call.current()?.tokenId ?: com.kilon.agentvscreen.api.Agents.ofSession(sid ?: VScreenOffloadHandler.DEFAULT_SESSION)
        val mode = when (p.getOrNull(1)?.lowercase()) {
            "quiet", "短" -> com.kilon.agentvscreen.api.AgentLedger.REPLY_QUIET
            "full", "完整" -> com.kilon.agentvscreen.api.AgentLedger.REPLY_FULL
            else -> throw ActionError("INVALID_ARGS", tr(S.vscreen_notes_note_reply_quiet_full))
        }
        com.kilon.agentvscreen.api.AgentLedger.setReplyMode(me, mode)
        val replyMsg = if (mode == com.kilon.agentvscreen.api.AgentLedger.REPLY_FULL)
            tr(S.vscreen_notes_default_reply_mode_set_to)
        else
            tr(S.vscreen_notes_default_reply_mode_set_to_2)
        return NativeOffloadResult(0, replyMsg + "\n", "OK", org.json.JSONObject().put("reply_mode", mode))
    }
    if (p.firstOrNull()?.lowercase() == "hold") {
        val me = Call.current()?.tokenId ?: com.kilon.agentvscreen.api.Agents.ofSession(sid ?: VScreenOffloadHandler.DEFAULT_SESSION)
        val on = when (p.getOrNull(1)?.lowercase()) {
            "on", "true", "1", "开" -> true
            "off", "false", "0", "关" -> false
            else -> throw ActionError("INVALID_ARGS", tr(S.vscreen_notes_note_hold_on_off))
        }
        com.kilon.agentvscreen.api.AgentLedger.setHoldDefault(me, on)
        val holdMsg = if (on)
            tr(S.vscreen_notes_default_hold_set_to_on)
        else
            tr(S.vscreen_notes_default_hold_set_to_off)
        return NativeOffloadResult(0, holdMsg + "\n", "OK", org.json.JSONObject().put("hold_default", on))
    }
    val pkg = noteApp(sid)
    val ver = appVersion(pkg)
    val first = p.firstOrNull()?.lowercase()?.let { VScreenMap.LEGACY_KEYS[it] ?: it }
    val msg: String = when {
        p.isEmpty() -> return text(noteLines(pkg, ver).joinToString("\n"))
        first == "before" || first == "after" -> {
            val body = p.drop(1).joinToString(" ").trim()
            if (body.isEmpty()) throw ActionError("INVALID_ARGS", tr(S.vscreen_notes_note_steps_e_g_note, first))
            // 1.4.0-beta5: `note before off` (the way human pacing is turned off) removes them too, same as note delete before.
            if (body.lowercase() in setOf("off", "none", "关")) { VScreenMap.setProfile(pkg, mapOf(first to "")); return text(tr(S.vscreen_notes_deleted_s_steps, pkg, first)) }
            Steps.parseAll(body)
            VScreenMap.setProfile(pkg, mapOf(first to body))
            tr(S.vscreen_notes_kept_s_steps_every_act, pkg, if (first == "before") "前置" else "收尾", body, if (first == "before") "先" else "在最后", first, if (first == "before") "first" else "last")
        }
        first == "save" || first == "segment" -> {
            // 1.3.0-beta18: write or rewrite a segment (a taught lesson too) without running it: the same steps as act.
            val name = p.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: throw ActionError("INVALID_ARGS", tr(S.vscreen_notes_note_save_segment_steps))
            val body = p.drop(2).joinToString(" ").trim()
            if (body.isEmpty()) throw ActionError("INVALID_ARGS", tr(S.vscreen_notes_note_save_steps_give_the, name))
            Steps.parseAll(body)
            val had = VScreenMap.segment(pkg, name) != null
            VScreenMap.saveSegment(pkg, name, body)
            val n = Regex("\\{([1-9]\\d*)\\}").findAll(body).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
            val use = "use $name" + (1..n).joinToString("") { " <$it>" }
            tr(S.vscreen_notes_s_segment_not_run_run, if (had) "改写" else "存下", pkg, name, use, if (had) "Rewrote" else "Saved")
        }
        first == "show" -> {
            // 1.3.0 (rainbow): a saved segment, step by step, with its lesson's thumbnails when it was taught.
            val name = p.drop(1).joinToString(" ").trim().ifEmpty { throw ActionError("INVALID_ARGS", tr(S.vscreen_notes_note_show_segment)) }
            val seg = VScreenMap.segment(pkg, name) ?: throw ActionError("NOT_FOUND", tr(S.vscreen_notes_has_no_segment_called_note, pkg, name))
            val lines = Steps.outline(seg.steps).mapIndexed { i, (depth, line) -> "  " + "  ".repeat(depth) + line }
            val pic = VScreenMap.picture(pkg, name)?.let { f -> runCatching { android.graphics.BitmapFactory.decodeFile(f.path) }.getOrNull() }?.let { bmp -> try { ShotStore.save(context, bmp, "lesson") } finally { bmp.recycle() } }
            val picLine = when {
                pic == null -> tr(S.vscreen_notes_no_thumbnails_not_taught_or)
                VScreenMap.pictureStale(pkg, name) -> tr(S.vscreen_notes_lesson_thumbnails_the_steps_were, pic)
                else -> tr(S.vscreen_notes_lesson_thumbnails_numbers_are_step, pic)
            }
            val startLine = segmentStart(seg)?.let { tr(S.vscreen_notes_start_checked_before_use_elsewhere, it) }
                ?: tr(S.vscreen_notes_start_not_known_yet_kept)
            return text((listOf(tr(S.vscreen_notes_segment_used_times, name, pkg, segmentFrom(pkg, seg), seg.uses), startLine) + lines + picLine).joinToString("\n"))
        }
        first == "human" -> {
            val v = p.getOrNull(1)?.lowercase()
            val on = when (v) { "on", "true", "1", "开" -> true; "off", "false", "0", "关" -> false; else -> throw ActionError("INVALID_ARGS", tr(S.vscreen_notes_note_human_on_off)) }
            VScreenMap.setProfile(pkg, mapOf("human" to if (on) "true" else ""))
            if (on) tr(S.vscreen_notes_every_act_on_now_uses, pkg)
            else tr(S.vscreen_notes_human_pacing_is_off_for, pkg)
        }
        first == "realtime" -> tr(S.vscreen_notes_note_realtime_is_gone_to)
        first == "delete" || first == "del" || first == "rm" -> {
            val what = p.drop(1).joinToString(" ").trim().ifEmpty { throw ActionError("INVALID_ARGS", tr(S.vscreen_notes_note_delete_number_before_after)) }
            when {
                (VScreenMap.LEGACY_KEYS[what] ?: what) in setOf("before", "after") -> { val k = VScreenMap.LEGACY_KEYS[what] ?: what; VScreenMap.setProfile(pkg, mapOf(k to "")); tr(S.vscreen_notes_deleted_s_steps, pkg, k) }
                what.toIntOrNull() != null -> if (VScreenMap.deleteNote(pkg, what.toInt())) tr(S.vscreen_notes_deleted_note, what) else throw ActionError("NOT_FOUND", tr(S.vscreen_notes_has_no_note, pkg, what))
                else -> if (VScreenMap.deleteSegment(pkg, what)) tr(S.vscreen_notes_deleted_segment, what) else throw ActionError("NOT_FOUND", tr(S.vscreen_notes_has_no_note_number_or, pkg, what))
            }
        }
        else -> {
            val t = p.joinToString(" ").trim()
            if (t.length > 300) throw ActionError("INVALID_ARGS", tr(S.vscreen_notes_a_note_has_at_most, t.length))
            val n = VScreenMap.addNote(pkg, t, Call.current()?.tokenName, ver)
            tr(S.vscreen_notes_kept_as_note_the_next, pkg, n.id)
        }
    }
    return text(msg + "\n\n" + noteLines(pkg, ver).joinToString("\n"))
}

/** `keep` — the watches kept after turns; `keep off <名字|all>` — stop them. Started by `act --stop … --keep notify|wake`. */
internal fun VScreenOffloadHandler.keepCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val p = args.positional
    // 1.4.0-beta7 / 1.4.1-beta1: each agent owns its own (agentId, name) namespace of watches.
    val me = Call.current()?.tokenId ?: com.kilon.agentvscreen.api.Agents.ofSession(sid ?: VScreenOffloadHandler.DEFAULT_SESSION)
    fun mine(l: VScreenRules.Live) = l.spec.agentId == me
    if (p.firstOrNull()?.lowercase() == "off") {
        val name = p.getOrNull(1) ?: throw ActionError("INVALID_ARGS", tr(S.vscreen_notes_keep_off_name_all))
        // 1.1: ended watches (seen / timed out) stay listed until cleared; off clears them too.
        if (name == "all") {
            val own = VScreenRules.list().filter(::mine)
            val stopped = own.count { !it.stopped && VScreenRules.stop(me, it.spec.name) }
            val cleared = VScreenRules.clearEnded(me, null)
            return text(tr(S.vscreen_notes_stopped_running_watches, stopped) + if (cleared > 0) tr(S.vscreen_notes_cleared_ended_ones, cleared) else "")
        }
        if (VScreenRules.stop(me, name)) { VScreenRules.clearEnded(me, name); return text(tr(S.vscreen_notes_stopped, name)) }
        if (VScreenRules.clearEnded(me, name) > 0) return text(tr(S.vscreen_notes_had_already_ended_cleared_from, name))
        if (VScreenRules.list().any { it.spec.name == name && !mine(it) }) throw ActionError("NOT_FOUND", tr(S.vscreen_notes_no_watch_of_yours_called, name))
        throw ActionError("NOT_FOUND", tr(S.vscreen_notes_no_watch_called_keep_lists, name))
    }
    if (p.isNotEmpty()) throw ActionError("INVALID_ARGS", tr(S.vscreen_notes_keep_list_keep_off_name))
    val list = VScreenRules.list().filter(::mine)
    if (list.isEmpty()) return text(tr(S.vscreen_notes_no_watches_start_one_act))
    fun action(l: VScreenRules.Live) = l.json().optJSONArray("action")?.join(tr(S.automation_list_sep))?.replace("\"", "")
    val running = list.filter { !it.stopped }.map { l ->
        run { val left = l.json().opt("ends_in_min"); val a = action(l); if (I18n.en) "\"${l.spec.name}\" ${l.spec.describe()} — ${l.status}, $left min left; when seen: $a" else "「${l.spec.name}」${l.spec.describe()} — ${l.status}，还剩 $left 分钟；看到时$a" }
    }
    val ended = list.filter { it.stopped }.map { l ->
        run { val why = l.endReason ?: tr(S.records_ended); if (I18n.en) "\"${l.spec.name}\" ${l.spec.describe()} — $why" else "「${l.spec.name}」${l.spec.describe()} — $why" }
    }
    return text(buildList {
        if (running.isNotEmpty()) { add(tr(S.vscreen_notes_watching)); addAll(running) }
        if (ended.isNotEmpty()) { add(tr(S.vscreen_notes_ended_clear_with_keep_off)); addAll(ended) }
    }.joinToString("\n"))
}

/** Plain text result. */
internal fun text(s: String): NativeOffloadResult = NativeOffloadResult(0, s.trimEnd() + "\n")
