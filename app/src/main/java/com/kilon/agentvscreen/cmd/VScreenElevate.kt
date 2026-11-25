package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.api.Policy
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.host.Risk
import com.kilon.agentvscreen.host.StrongConfirm
import com.kilon.agentvscreen.tr

/**
 * 2.0.0-beta11 `elevate <class|level> --why "…" [--for round|today|always]` (rainbow: can an agent ask for a
 * temporary or permanent elevation, confirmed from a notification). The agent asks before it starts, with its reason;
 * the user sees one card (notification → fingerprint) and picks the range. The same request is what an agent gets
 * when a command meets a class set to Not allowed, so asking ahead and running into the limit end the same way.
 */
internal fun VScreenOffloadHandler.elevateCmd(args: OffloadArgs): NativeOffloadResult {
    val call = Call.current() ?: throw ActionError("NO_SESSION", tr("没有调用方信息", "No caller"))
    val raw = args.positional.joinToString(" ").trim()
    val why = args.get("why")?.trim().orEmpty()
    if (raw.isEmpty()) throw ActionError("INVALID_ARGS", tr("要写明申请什么：一个类别（${rowNames()}）或一个档位（${levelNames()}）",
        "Say what you ask for: a class (${rowNames()}) or a level (${levelNames()})"))
    if (why.isEmpty()) throw ActionError("INVALID_ARGS", tr("要用 --why \"…\" 写明原因，用户在确认卡上会看到", "Give the reason with --why \"…\"; the user sees it on the card"))
    if (why.length > 120) throw ActionError("INVALID_ARGS", tr("原因最多 120 个字（收到 ${why.length}）", "The reason has at most 120 characters (got ${why.length})"))
    val prefer = args.get("for")?.let { k ->
        StrongConfirm.Range.entries.firstOrNull { it.key == k && it != StrongConfirm.Range.ONCE }
            ?: throw ActionError("INVALID_ARGS", tr("--for 只能是 round、today 或 always（收到 $k）", "--for is round, today or always (got $k)"))
    } ?: StrongConfirm.Range.ROUND
    val tok = Tokens.byId(call.tokenId) ?: throw ActionError("SCOPE_DENIED", tr("这个令牌已失效", "This token is no longer valid"))
    val public = tok.publicOnly || call.source == Call.Source.PUBLIC

    val row = Policy.ROWS.firstOrNull { it.key == raw || it.zh == raw || it.en.equals(raw, true) }
    val level = if (row == null) Tokens.LEVELS.firstOrNull { it == raw || Policy.levelTitle(it) == raw } else null
    if (row == null && level == null) throw ActionError("INVALID_ARGS", tr("不认识「$raw」。类别：${rowNames()}；档位：${levelNames()}",
        "Unknown \"$raw\". Classes: ${rowNames()}; levels: ${levelNames()}"))

    row?.let { r -> com.kilon.agentvscreen.host.Permissions.refusal(r.key, public)?.let { throw ActionError("SCOPE_DENIED", it) } }
    if (level == Policy.CUSTOM) throw ActionError("INVALID_ARGS", tr("「自定义」不是可以申请的档位；请申请具体的类别", "Custom is not a level you can ask for; ask for a class"))
    if (public && level != null && level !in Tokens.PUBLIC_LEVELS) throw ActionError("SCOPE_DENIED", tr(
        "公网 MCP 不开放系统操作，这是固定的边界，不能申请", "System actions are never available over public MCP; this cannot be asked for"))

    // Already covered: say so instead of asking the user for nothing.
    val now = StrongConfirm.clock()
    val missing: List<String> = if (row != null) listOf(row.key).filter { StrongConfirm.effectiveAct(tok, it, now) != Policy.clamp(it, Policy.Act.ALLOW) }
        else Policy.table(level!!, emptyMap(), tok.publicOnly).filter { (k, a) -> a.rank > StrongConfirm.effectiveAct(tok, k, now).rank }.keys.toList()
    val what = row?.title ?: tr("「${Policy.levelTitle(level!!)}」档位", "the ${Policy.levelTitle(level!!)} level")
    if (missing.isEmpty()) return text(tr("$what 已经在许可范围内，不用申请。", "$what is already allowed; nothing to ask."))

    val target = if (row != null) "row:${row.key}" else level!!
    val risks = buildList {
        if (!StrongConfirm.hasAgentApproval(call.tokenId, Risk.clientOf(call))) add(Risk.NEW_CLIENT)
        add(Risk.LEVEL_UP)
    }
    val got = StrongConfirm.requireForRisks(context, call, risks, "elevate", targetLevel = target, detail = why, prefer = prefer)
    val range = when (got) {
        StrongConfirm.Range.ALWAYS -> tr("永久（已写入这个 agent 的权限表）", "permanently (saved in this agent's table)")
        StrongConfirm.Range.TODAY -> tr("今天内", "for today")
        else -> tr("本轮任务内（超过任务轮次时长没有新命令即收回）", "for this turn (taken back once no command arrives for a turn length)")
    }
    return text(tr("用户已批准：$what，$range。", "The user approved $what, $range."))
}

private fun rowNames() = Policy.ROWS.filter { it.key != Policy.MONEY }.joinToString("、") { "${it.key}=${it.zh}" }
private fun levelNames() = listOf(Tokens.CAREFUL, Tokens.FREE, Tokens.FREE_SYSTEM).joinToString("、") { "$it=${Policy.levelTitle(it)}" }
