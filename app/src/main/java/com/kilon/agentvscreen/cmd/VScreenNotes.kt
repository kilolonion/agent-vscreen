package com.kilon.agentvscreen.cmd
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
    throw ActionError("NO_APP", tr("不知道记到哪个应用：先 launch 应用", "no app to keep it for: launch an app first"))
}

/**
 * 1.4.0-beta13 (test B8: every segment read "saved for Muse", the user's lessons too): where a segment came from, as
 * an agent reads it: the user showed it (and for whom), or an agent wrote it.
 */
internal fun segmentFrom(pkg: String, s: VScreenMap.Segment): String {
    val who = s.by?.let { com.kilon.agentvscreen.api.Agents.name(it) }
    return if (VScreenMap.isTaught(pkg, s)) tr("用户示教的", "taught by the user") + (who?.let { tr("，为 $it", ", for $it") } ?: "")
    else who?.let { tr("$it 存的", "saved by $it") } ?: tr("agent 存的", "saved by an agent")
}

/** Where a segment starts, as an agent reads it (null: not known yet). */
internal fun segmentStart(s: VScreenMap.Segment): String? = s.start?.let { st ->
    val app = st.optString("app").takeIf { it.isNotEmpty() && it != "null" }
    val act = st.optString("activity").takeIf { it.isNotEmpty() && it != "null" }?.substringAfterLast('.')
    val words = st.optJSONArray("words")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    listOfNotNull(app, act).joinToString(" / ") + (if (words.isNotEmpty()) tr("，页面上有「${words.joinToString("」「")}」", ", a page showing \"${words.joinToString("\", \"")}\"") else "")
}

/** Everything written down about [pkg], as lines. */
internal fun noteLines(pkg: String, verNow: String? = null): List<String> {
    val m = VScreenMap.load(pkg)
    val out = ArrayList<String>()
    val name = VScreenKnowledge.label(pkg)?.let { "$it（$pkg）" } ?: pkg
    out += if (I18n.en) "[$name]" else "【$name】"
    if (m.notes.isEmpty()) out += tr("笔记：无", "Notes: none") else { out += tr("笔记：", "Notes:"); m.notes.forEach { out += "  ${it.id}. ${it.text}" + noteOrigin(it, verNow) } }
    if (m.segments.isEmpty()) out += tr("段：无", "Segments: none") else { out += tr("段（act \"use 名字\"）：", "Segments (act \"use name\"):"); m.segments.forEach { out += "  ${it.name} = ${it.steps}" + tr("（用过 ${it.uses} 次）", " (used ${it.uses} times)") + "（" + segmentFrom(pkg, it) + "）" +
        (if (VScreenMap.picture(pkg, it.name) != null) tr("（有示教缩略图：note show ${it.name}）", " (lesson thumbnails: note show ${it.name})") else "") } }
    val p = m.profile
    val none = tr("没记", "not kept")
    out += tr("前置步骤（before）：", "Before steps: ") + (p["before"] ?: none) + tr("；收尾步骤（after）：", "; after steps: ") + (p["after"] ?: none) +
        (if (p["before"] != null || p["after"] != null) tr("（每段带步骤或 --watch 的 act 自动做；--bare 跳过）", " (run automatically by every act with steps or --watch; --bare skips them)") else "") +
        (p["orientation"]?.let { tr("；方向：$it", "; orientation: $it") } ?: "") +
        (if (p["human"] == "true") tr("；仿人节奏：开（note human off 关）", "; human pacing: on (note human off)") else "")
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
            else -> throw ActionError("INVALID_ARGS", tr("note reply quiet|full", "note reply quiet|full"))
        }
        com.kilon.agentvscreen.api.AgentLedger.setReplyMode(me, mode)
        val replyMsg = if (mode == com.kilon.agentvscreen.api.AgentLedger.REPLY_FULL)
            tr("已设为默认完整回复（full）：之后每条 act 都返回完整四段（note reply quiet 改回短回复）",
                "Default reply mode set to full: every act now returns the full four blocks (note reply quiet switches back to short replies)")
        else
            tr("已设为默认短回复（quiet）：符合预期的 act 只回一小段，有意外仍回完整四段（单次要完整加 act --full）",
                "Default reply mode set to quiet: expected act calls return a short reply, unexpected ones still return full four blocks (for one full reply use act --full)")
        return NativeOffloadResult(0, replyMsg + "\n", "OK", org.json.JSONObject().put("reply_mode", mode))
    }
    if (p.firstOrNull()?.lowercase() == "hold") {
        val me = Call.current()?.tokenId ?: com.kilon.agentvscreen.api.Agents.ofSession(sid ?: VScreenOffloadHandler.DEFAULT_SESSION)
        val on = when (p.getOrNull(1)?.lowercase()) {
            "on", "true", "1", "开" -> true
            "off", "false", "0", "关" -> false
            else -> throw ActionError("INVALID_ARGS", tr("note hold on|off", "note hold on|off"))
        }
        com.kilon.agentvscreen.api.AgentLedger.setHoldDefault(me, on)
        val holdMsg = if (on)
            tr("已设为默认开启 --hold（仅 Android 14+）：之后每条 act 回复发出后暂停副屏前台应用，下一条命令到达时恢复（note hold off 关掉）",
                "Default --hold set to on (Android 14+ only): after each act reply is sent, the foreground app on the screen is paused until the next command arrives (note hold off to turn off)")
        else
            tr("已关掉默认 --hold（单次暂停加 act --hold）",
                "Default --hold set to off (for one turn use act --hold)")
        return NativeOffloadResult(0, holdMsg + "\n", "OK", org.json.JSONObject().put("hold_default", on))
    }
    val pkg = noteApp(sid)
    val ver = appVersion(pkg)
    val first = p.firstOrNull()?.lowercase()?.let { VScreenMap.LEGACY_KEYS[it] ?: it }
    val msg: String = when {
        p.isEmpty() -> return text(noteLines(pkg, ver).joinToString("\n"))
        first == "before" || first == "after" -> {
            val body = p.drop(1).joinToString(" ").trim()
            if (body.isEmpty()) throw ActionError("INVALID_ARGS", tr("note $first \"<步骤>\"，如 note $first \"tap 1840 60\"", "note $first \"<steps>\", e.g. note $first \"tap 1840 60\""))
            // 1.4.0-beta5: `note before off` (the way human pacing is turned off) removes them too, same as note delete before.
            if (body.lowercase() in setOf("off", "none", "关")) { VScreenMap.setProfile(pkg, mapOf(first to "")); return text(tr("已删 $pkg 的 $first 步骤", "deleted $pkg's $first steps")) }
            Steps.parseAll(body)
            VScreenMap.setProfile(pkg, mapOf(first to body))
            tr("已记 $pkg 的${if (first == "before") "前置" else "收尾"}步骤：$body。之后每段带步骤或 --watch 的 act 会${if (first == "before") "先" else "在最后"}做它（--bare 跳过；不带步骤的 act 只看，不做）",
                "Kept $pkg's $first steps: $body. Every act with steps or --watch now runs them ${if (first == "before") "first" else "last"} (--bare skips them; act without steps only looks)")
        }
        first == "save" || first == "segment" -> {
            // 1.3.0-beta18: write or rewrite a segment (a taught lesson too) without running it: the same steps as act.
            val name = p.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: throw ActionError("INVALID_ARGS", tr("note save <段名> \"<步骤>\"", "note save <segment> \"<steps>\""))
            val body = p.drop(2).joinToString(" ").trim()
            if (body.isEmpty()) throw ActionError("INVALID_ARGS", tr("note save $name \"<步骤>\"：写上整段步骤（会替换同名的段）", "note save $name \"<steps>\": give the whole steps (replaces the segment of that name)"))
            Steps.parseAll(body)
            val had = VScreenMap.segment(pkg, name) != null
            VScreenMap.saveSegment(pkg, name, body)
            val n = Regex("\\{([1-9]\\d*)\\}").findAll(body).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
            val use = "use $name" + (1..n).joinToString("") { " <$it>" }
            tr("已${if (had) "改写" else "存下"} $pkg 的段“$name”（没有执行）。用：act \"$use\"", "${if (had) "Rewrote" else "Saved"} $pkg's segment \"$name\" (not run). Run it: act \"$use\"")
        }
        first == "show" -> {
            // 1.3.0 (rainbow): a saved segment, step by step, with its lesson's thumbnails when it was taught.
            val name = p.drop(1).joinToString(" ").trim().ifEmpty { throw ActionError("INVALID_ARGS", tr("note show <段名>", "note show <segment>")) }
            val seg = VScreenMap.segment(pkg, name) ?: throw ActionError("NOT_FOUND", tr("$pkg 没有叫“$name”的段（note 列出全部）", "$pkg has no segment called \"$name\" (note lists them)"))
            val lines = Steps.outline(seg.steps).mapIndexed { i, (depth, line) -> "  " + "  ".repeat(depth) + line }
            val pic = VScreenMap.picture(pkg, name)?.let { f -> runCatching { android.graphics.BitmapFactory.decodeFile(f.path) }.getOrNull() }?.let { bmp -> try { ShotStore.save(context, bmp, "lesson") } finally { bmp.recycle() } }
            val picLine = when {
                pic == null -> tr("没有缩略图（不是示教存的，或是 1.3.0 以前存的）", "No thumbnails (not taught, or saved before 1.3.0)")
                VScreenMap.pictureStale(pkg, name) -> tr("示教缩略图（步骤后来改写过，图是改写前录的，序号可能对不上）：$pic", "Lesson thumbnails (the steps were rewritten later; the picture is from before, numbers may not match): $pic")
                else -> tr("示教缩略图（数字是步骤序号，红圈是点的位置，最后一格是结束时）：$pic", "Lesson thumbnails (numbers are step numbers, red rings are taps, the last cell is the end): $pic")
            }
            val startLine = segmentStart(seg)?.let { tr("起点：$it（use 前先核对，不在这里一步不做）", "Start: $it (checked before use; elsewhere nothing is done)") }
                ?: tr("起点：还不知道（第一次从头顺利跑完时记下，之后 use 前核对）", "Start: not known yet (kept the first time it runs through from step 1, then checked before use)")
            return text((listOf(tr("段“$name”（$pkg，${segmentFrom(pkg, seg)}，用过 ${seg.uses} 次）：", "Segment \"$name\" ($pkg, ${segmentFrom(pkg, seg)}, used ${seg.uses} times):"), startLine) + lines + picLine).joinToString("\n"))
        }
        first == "human" -> {
            val v = p.getOrNull(1)?.lowercase()
            val on = when (v) { "on", "true", "1", "开" -> true; "off", "false", "0", "关" -> false; else -> throw ActionError("INVALID_ARGS", tr("note human on|off", "note human on|off")) }
            VScreenMap.setProfile(pkg, mapOf("human" to if (on) "true" else ""))
            if (on) tr("$pkg 以后每段 act 都用仿人节奏（动作前随机停顿，点击略偏，时长有波动，打字分小段）", "Every act on $pkg now uses human pacing (random pause before actions, taps slightly off-centre, varied durations, typing in small pieces)")
            else tr("$pkg 的仿人节奏已关（单段要用就 act --human）", "Human pacing is off for $pkg (for one stretch use act --human)")
        }
        first == "realtime" -> tr("note realtime 已取消：要动作之间不等画面稳定，在那一段 act 上加 --fast", "note realtime is gone: to skip waiting for the picture between actions, add --fast to that act")
        first == "delete" || first == "del" || first == "rm" -> {
            val what = p.drop(1).joinToString(" ").trim().ifEmpty { throw ActionError("INVALID_ARGS", tr("note delete <编号|before|after|段名>", "note delete <number|before|after|segment>")) }
            when {
                (VScreenMap.LEGACY_KEYS[what] ?: what) in setOf("before", "after") -> { val k = VScreenMap.LEGACY_KEYS[what] ?: what; VScreenMap.setProfile(pkg, mapOf(k to "")); tr("已删 $pkg 的 $k 步骤", "deleted $pkg's $k steps") }
                what.toIntOrNull() != null -> if (VScreenMap.deleteNote(pkg, what.toInt())) tr("已删笔记 $what", "deleted note $what") else throw ActionError("NOT_FOUND", tr("$pkg 没有第 $what 条笔记", "$pkg has no note $what"))
                else -> if (VScreenMap.deleteSegment(pkg, what)) tr("已删段“$what”", "deleted segment \"$what\"") else throw ActionError("NOT_FOUND", tr("$pkg 没有叫“$what”的笔记编号或段", "$pkg has no note number or segment called \"$what\""))
            }
        }
        else -> {
            val t = p.joinToString(" ").trim()
            if (t.length > 300) throw ActionError("INVALID_ARGS", tr("一条笔记最多 300 字（收到 ${t.length}）：写要点", "a note has at most 300 characters (got ${t.length}): keep to the point"))
            val n = VScreenMap.addNote(pkg, t, Call.current()?.tokenName, ver)
            tr("已记到 $pkg 第 ${n.id} 条（下次 launch 它会列出来）", "Kept as $pkg note ${n.id} (the next launch lists it)")
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
        val name = p.getOrNull(1) ?: throw ActionError("INVALID_ARGS", tr("keep off <名字|all>", "keep off <name|all>"))
        // 1.1: ended watches (seen / timed out) stay listed until cleared; off clears them too.
        if (name == "all") {
            val own = VScreenRules.list().filter(::mine)
            val stopped = own.count { !it.stopped && VScreenRules.stop(me, it.spec.name) }
            val cleared = VScreenRules.clearEnded(me, null)
            return text(tr("已停 $stopped 个正在盯的", "Stopped $stopped running watches") + if (cleared > 0) tr("，清掉 $cleared 条已结束的", ", cleared $cleared ended ones") else "")
        }
        if (VScreenRules.stop(me, name)) { VScreenRules.clearEnded(me, name); return text(tr("已停「$name」", "Stopped \"$name\"")) }
        if (VScreenRules.clearEnded(me, name) > 0) return text(tr("「$name」已经结束了，已从列表清掉", "\"$name\" had already ended; cleared from the list"))
        if (VScreenRules.list().any { it.spec.name == name && !mine(it) }) throw ActionError("NOT_FOUND", tr("没有叫「$name」的盯守（同名的那个是别的 agent 的）：keep 看你的列表", "no watch of yours called \"$name\" (that one is another agent's): keep lists yours"))
        throw ActionError("NOT_FOUND", tr("没有叫「$name」的盯守：keep 看列表", "no watch called \"$name\": keep lists them"))
    }
    if (p.isNotEmpty()) throw ActionError("INVALID_ARGS", tr("keep（列表）| keep off <名字|all>。开始盯守用 act --stop \"条件\" --keep notify|wake [--for 1h]", "keep (list) | keep off <name|all>. Start a watch with act --stop \"condition\" --keep notify|wake [--for 1h]"))
    val list = VScreenRules.list().filter(::mine)
    if (list.isEmpty()) return text(tr("没有盯守。开始：act --stop \"text:到货\" --keep notify [--for 2h]", "No watches. Start one: act --stop \"text:In stock\" --keep notify [--for 2h]"))
    fun action(l: VScreenRules.Live) = l.json().optJSONArray("action")?.join(tr("、", ", "))?.replace("\"", "")
    val running = list.filter { !it.stopped }.map { l ->
        run { val left = l.json().opt("ends_in_min"); val a = action(l); if (I18n.en) "\"${l.spec.name}\" ${l.spec.describe()} — ${l.status}, $left min left; when seen: $a" else "「${l.spec.name}」${l.spec.describe()} — ${l.status}，还剩 $left 分钟；看到时$a" }
    }
    val ended = list.filter { it.stopped }.map { l ->
        run { val why = l.endReason ?: tr("已结束", "ended"); if (I18n.en) "\"${l.spec.name}\" ${l.spec.describe()} — $why" else "「${l.spec.name}」${l.spec.describe()} — $why" }
    }
    return text(buildList {
        if (running.isNotEmpty()) { add(tr("正在盯：", "Watching:")); addAll(running) }
        if (ended.isNotEmpty()) { add(tr("已结束（keep off <名字> 或 keep off all 清掉）：", "Ended (clear with keep off <name> or keep off all):")); addAll(ended) }
    }.joinToString("\n"))
}

/** Plain text result. */
internal fun text(s: String): NativeOffloadResult = NativeOffloadResult(0, s.trimEnd() + "\n")
