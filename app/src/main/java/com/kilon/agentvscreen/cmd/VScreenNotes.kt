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

/** Everything written down about [pkg], as lines. */
internal fun noteLines(pkg: String, verNow: String? = null): List<String> {
    val m = VScreenMap.load(pkg)
    val out = ArrayList<String>()
    val name = VScreenKnowledge.label(pkg)?.let { "$it（$pkg）" } ?: pkg
    out += if (I18n.en) "[$name]" else "【$name】"
    if (m.notes.isEmpty()) out += tr("笔记：无", "Notes: none") else { out += tr("笔记：", "Notes:"); m.notes.forEach { out += "  ${it.id}. ${it.text}" + noteOrigin(it, verNow) } }
    if (m.segments.isEmpty()) out += tr("段：无", "Segments: none") else { out += tr("段（act \"use 名字\"）：", "Segments (act \"use name\"):"); m.segments.forEach { out += "  ${it.name} = ${it.steps}" + tr("（用过 ${it.uses} 次）", " (used ${it.uses} times)") } }
    val p = m.profile
    val none = tr("没记", "not kept")
    out += tr("前置步骤（before）：", "Before steps: ") + (p["before"] ?: none) + tr("；收尾步骤（after）：", "; after steps: ") + (p["after"] ?: none) +
        (if (p["before"] != null || p["after"] != null) tr("（每段带步骤或 --watch 的 act 自动做；--bare 跳过）", " (run automatically by every act with steps or --watch; --bare skips them)") else "") +
        (p["orientation"]?.let { tr("；方向：$it", "; orientation: $it") } ?: "")
    return out
}

/**
 * `note` — list; `note <text>` — add; `note before "<steps>"` / `note after "<steps>"` — steps every act on this app
 * runs first / last (e.g. resume and pause a game, dismiss a recurring popup); `note delete <number|before|after|segment>`.
 * 1.2: before/after replace pause/resume (old names still work); a note records who wrote it and for which app version.
 */
internal fun VScreenOffloadHandler.noteCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val p = args.positional
    val pkg = noteApp(sid)
    val ver = appVersion(pkg)
    val first = p.firstOrNull()?.lowercase()?.let { VScreenMap.LEGACY_KEYS[it] ?: it }
    val msg: String = when {
        p.isEmpty() -> return text(noteLines(pkg, ver).joinToString("\n"))
        first == "before" || first == "after" -> {
            val body = p.drop(1).joinToString(" ").trim()
            if (body.isEmpty()) throw ActionError("INVALID_ARGS", tr("note $first \"<步骤>\"，如 note $first \"tap 1840 60\"", "note $first \"<steps>\", e.g. note $first \"tap 1840 60\""))
            Steps.parseAll(body)
            VScreenMap.setProfile(pkg, mapOf(first to body))
            tr("已记 $pkg 的${if (first == "before") "前置" else "收尾"}步骤：$body。之后每段带步骤或 --watch 的 act 会${if (first == "before") "先" else "在最后"}做它（--bare 跳过；不带步骤的 act 只看，不做）",
                "Kept $pkg's $first steps: $body. Every act with steps or --watch now runs them ${if (first == "before") "first" else "last"} (--bare skips them; act without steps only looks)")
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
internal fun VScreenOffloadHandler.keepCmd(@Suppress("UNUSED_PARAMETER") sid: String?, args: OffloadArgs): NativeOffloadResult {
    val p = args.positional
    if (p.firstOrNull()?.lowercase() == "off") {
        val name = p.getOrNull(1) ?: throw ActionError("INVALID_ARGS", tr("keep off <名字|all>", "keep off <name|all>"))
        // 1.1: ended watches (seen / timed out) stay listed until cleared; off clears them too.
        if (name == "all") {
            val stopped = VScreenRules.stopAll(); val cleared = VScreenRules.clearEnded()
            return text(tr("已停 $stopped 个正在盯的", "Stopped $stopped running watches") + if (cleared > 0) tr("，清掉 $cleared 条已结束的", ", cleared $cleared ended ones") else "")
        }
        if (VScreenRules.stop(name)) { VScreenRules.clearEnded(name); return text(tr("已停「$name」", "Stopped \"$name\"")) }
        if (VScreenRules.clearEnded(name) > 0) return text(tr("「$name」已经结束了，已从列表清掉", "\"$name\" had already ended; cleared from the list"))
        throw ActionError("NOT_FOUND", tr("没有叫「$name」的盯守：keep 看列表", "no watch called \"$name\": keep lists them"))
    }
    if (p.isNotEmpty()) throw ActionError("INVALID_ARGS", tr("keep（列表）| keep off <名字|all>。开始盯守用 act --stop \"条件\" --keep notify|wake [--for 1h]", "keep (list) | keep off <name|all>. Start a watch with act --stop \"condition\" --keep notify|wake [--for 1h]"))
    val list = VScreenRules.list()
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
