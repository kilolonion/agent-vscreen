package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.S
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
    val call = Call.current() ?: throw ActionError("NO_SESSION", tr(S.vscreen_elevate_no_caller))
    val raw = args.positional.joinToString(" ").trim()
    val why = args.get("why")?.trim().orEmpty()
    if (raw.isEmpty()) throw ActionError("INVALID_ARGS", tr(S.vscreen_elevate_say_what_you_ask_for, rowNames(), levelNames()))
    if (why.isEmpty()) throw ActionError("INVALID_ARGS", tr(S.vscreen_elevate_give_the_reason_with_why))
    if (why.length > 120) throw ActionError("INVALID_ARGS", tr(S.vscreen_elevate_the_reason_has_at_most, why.length))
    val prefer = args.get("for")?.let { k ->
        StrongConfirm.Range.entries.firstOrNull { it.key == k && it != StrongConfirm.Range.ONCE }
            ?: throw ActionError("INVALID_ARGS", tr(S.vscreen_elevate_for_is_round_today_or, k))
    } ?: StrongConfirm.Range.ROUND
    val tok = Tokens.byId(call.tokenId) ?: throw ActionError("SCOPE_DENIED", tr(S.vscreen_elevate_this_token_is_no_longer))
    val public = tok.publicOnly || call.source == Call.Source.PUBLIC

    val row = Policy.ROWS.firstOrNull { it.key == raw || it.zh == raw || it.en.equals(raw, true) }
    val level = if (row == null) Tokens.LEVELS.firstOrNull { it == raw || Policy.levelTitle(it) == raw } else null
    if (row == null && level == null) throw ActionError("INVALID_ARGS", tr(S.vscreen_elevate_unknown_classes_levels, raw, rowNames(), levelNames()))

    row?.let { r -> com.kilon.agentvscreen.host.Permissions.refusal(r.key, public)?.let { throw ActionError("SCOPE_DENIED", it) } }
    if (level == Policy.CUSTOM) throw ActionError("INVALID_ARGS", tr(S.vscreen_elevate_custom_is_not_a_level))
    if (public && level != null && level !in Tokens.PUBLIC_LEVELS) throw ActionError("SCOPE_DENIED", tr(S.vscreen_elevate_system_actions_are_never_available))

    // Already covered: say so instead of asking the user for nothing.
    val now = StrongConfirm.clock()
    val missing: List<String> = if (row != null) listOf(row.key).filter { StrongConfirm.effectiveAct(tok, it, now) != Policy.clamp(it, Policy.Act.ALLOW) }
        else Policy.table(level!!, emptyMap(), tok.publicOnly).filter { (k, a) -> a.rank > StrongConfirm.effectiveAct(tok, k, now).rank }.keys.toList()
    val what = row?.title ?: tr(S.vscreen_elevate_the_level, Policy.levelTitle(level!!))
    if (missing.isEmpty()) return text(tr(S.vscreen_elevate_is_already_allowed_nothing_to, what))

    val target = if (row != null) "row:${row.key}" else level!!
    val risks = buildList {
        if (!StrongConfirm.hasAgentApproval(call.tokenId, Risk.clientOf(call))) add(Risk.NEW_CLIENT)
        add(Risk.LEVEL_UP)
    }
    val got = StrongConfirm.requireForRisks(context, call, risks, "elevate", targetLevel = target, detail = why, prefer = prefer)
    val range = when (got) {
        StrongConfirm.Range.ALWAYS -> tr(S.vscreen_elevate_permanently_saved_in_this_agent)
        StrongConfirm.Range.TODAY -> tr(S.vscreen_elevate_for_today)
        else -> tr(S.vscreen_elevate_for_this_turn_taken_back)
    }
    return text(tr(S.vscreen_elevate_the_user_approved, what, range))
}

private fun rowNames() = Policy.ROWS.filter { it.key != Policy.MONEY }.joinToString("、") { "${it.key}=${it.zh}" }
private fun levelNames() = listOf(Tokens.CAREFUL, Tokens.FREE, Tokens.FREE_SYSTEM).joinToString("、") { "$it=${Policy.levelTitle(it)}" }
