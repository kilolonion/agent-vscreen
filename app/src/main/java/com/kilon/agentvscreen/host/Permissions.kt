package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.api.Policy
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.core.VScreenStore
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2.0.0-beta11 (rainbow: let the agent find out its own permission settings and say them clearly; what it gathers by
 * itself is passive and partial, so how is it kept to them).
 *
 * Two separate things:
 * - Keeping to them is the phone's job, never the agent's. Every command is checked on the phone against
 *   [StrongConfirm.effectiveAct] before it runs; what the agent believes changes nothing.
 * - Knowing them is so the agent can plan, ask ahead (`elevate`) and tell the user. This object is the one place that
 *   describes them, read from the same [StrongConfirm.effectiveAct] the checks use, so what the agent is told and
 *   what is enforced cannot drift apart. It is served three ways: `vscreen permissions` (and `status.permissions`)
 *   on demand, a short notice on the next reply whenever they change, and structured data on every refusal.
 */
object Permissions {
    /** What each handling means for the agent, in its terms. */
    fun meaning(a: Policy.Act): String = when (a) {
        Policy.Act.ALLOW -> tr(S.permissions_runs_directly)
        Policy.Act.ASK -> tr(S.permissions_runs_after_the_user_confirms)
        Policy.Act.DENY -> tr(S.permissions_cannot_run_as_is_ask)
    }

    private fun actWord(a: Policy.Act): String = when (a) {
        Policy.Act.ALLOW -> tr(S.permissions_allowed)
        Policy.Act.ASK -> tr(S.permissions_confirm_each_time)
        Policy.Act.DENY -> tr(S.permissions_not_allowed)
    }

    /**
     * 2.0.0-beta11 (review: "not allowed" read as "ask with elevate" even where elevate is refused): whether [key] may be
     * asked for with `elevate`, or why not. The one rule; `elevate` refuses by it and `permissions` reports it.
     */
    fun refusal(key: String, public: Boolean): String? = when {
        key == Policy.MONEY -> tr(S.permissions_payments_cannot_be_asked_for)
        public && key == Policy.SYSTEM -> tr(S.vscreen_elevate_system_actions_are_never_available)
        else -> null
    }

    /** What the agent does next for [key] now, as one machine-readable word. */
    private fun nextAction(key: String, eff: Policy.Act, public: Boolean, capped: Boolean): String = when {
        capped && key != Policy.RATE -> "repair_pairing"
        eff == Policy.Act.ALLOW -> "run"
        eff == Policy.Act.ASK -> "run_phone_confirms"
        refusal(key, public) != null -> "tell_user"
        else -> "elevate"
    }

    private fun tokenOf(call: Call?): Tokens.Token? = Tokens.byId(call?.tokenId ?: Tokens.MAIN)

    /** The full picture for the agent behind [call]. */
    fun json(call: Call?, nowMs: Long = StrongConfirm.clock()): JSONObject {
        val tok = tokenOf(call) ?: return JSONObject().put("error", "BAD_TOKEN")
        val public = tok.publicOnly || call?.source == Call.Source.PUBLIC
        val grants = StrongConfirm.activeGrants(nowMs).filter { it.token == tok.id }
        val rows = JSONArray()
        Policy.ROWS.forEach { r ->
            val base = tok.act(r.key)
            val eff = StrongConfirm.effectiveAct(tok, r.key, nowMs, touch = false)
            // A public credential paired to look only stays so whatever the level says now (PublicOAuth.effectiveScope).
            val capped = public && call != null && "operate" !in call.scopes && r.key != Policy.SYSTEM && r.key != Policy.MONEY
            val refused = refusal(r.key, public)
            rows.put(JSONObject().put("class", r.key).put("title", r.title).put("covers", r.hint)
                .put("handling", if (capped) Policy.Act.DENY.key else eff.key).put("handling_title", if (capped) actWord(Policy.Act.DENY) else actWord(eff))
                .put("meaning", if (capped) tr(S.permissions_this_pairing_may_only_look) else if (eff == Policy.Act.DENY && refused != null) refused else meaning(eff))
                .put("can_request", !capped && eff != Policy.clamp(r.key, Policy.Act.ALLOW) && refused == null)
                .put("next_action", nextAction(r.key, eff, public, capped))
                .also { o -> if (eff != base) o.put("table", base.key).put("raised_by", tr(S.permissions_a_temporary_allow)) })
        }
        return JSONObject()
            .put("agent", tok.name)
            .put("level", tok.level).put("level_title", Policy.levelTitle(tok.level))
            .put("classes", rows)
            .put("temporary", JSONArray(grants.map { g ->
                JSONObject().put("what", g.risk.removePrefix(StrongConfirm.GRANT_PREFIX)).put("until", g.until)
                    .put("ends", if (g.by.endsWith(":" + StrongConfirm.Range.ROUND.key)) tr(S.permissions_when_this_turn_ends) else tr(S.permissions_at_the_end_of_today))
            }))
            .put("payments_without_confirm_until", if (tok.trusted) tok.trustUntil else JSONObject.NULL)
            .put("apps_confirm_every_time", JSONArray(VScreenStore.rules.value.filter { it.value == VScreenStore.Rule.ASK }.keys.toList()))
            .put("apps_blocked", JSONArray(VScreenStore.rules.value.filter { it.value == VScreenStore.Rule.BLOCK }.keys.toList()))
            .put("apps_read_only", JSONArray(VScreenStore.rules.value.filter { it.value == VScreenStore.Rule.READONLY }.keys.toList()))
            .put("fixed", JSONArray().apply {
                put(tr(S.permissions_every_payment_is_confirmed_on))
                if (public) put(tr(S.permissions_system_actions_are_never_available))
            })
            .put("enforced_by", tr(S.permissions_the_phone_checks_every_command))
            .put("ask_more", tr(S.permissions_vscreen_elevate_class_level_why))
    }

    /** The same, as lines an agent can pass on to the user as is. */
    fun text(call: Call?, nowMs: Long = StrongConfirm.clock()): String {
        val o = json(call, nowMs)
        if (o.has("error")) return tr(S.permissions_invalid_token)
        val b = StringBuilder()
        b.append(tr(S.permissions_permissions_level, o.getString("agent"), o.getString("level_title")))
        val rows = o.getJSONArray("classes")
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            b.append("- ${r.getString("title")}（${r.getString("class")}）：${r.getString("handling_title")}")
            if (r.has("raised_by")) b.append(tr(S.permissions_temporary))
            when (r.getString("next_action")) {
                "tell_user" -> b.append(tr(S.permissions_cannot_be_asked_for))
                "repair_pairing" -> b.append(tr(S.permissions_needs_a_new_pairing))
            }
            b.append(tr(S.permissions_sep)).append(r.getString("covers")).append('\n')
        }
        val t = o.getJSONArray("temporary")
        if (t.length() > 0) b.append(tr(S.permissions_temporary_2) + (0 until t.length()).joinToString(tr(S.agent_guide_sep)) { val g = t.getJSONObject(it); "${g.getString("what")} ${g.getString("ends")}" } + "\n")
        val ask = o.getJSONArray("apps_confirm_every_time"); if (ask.length() > 0) b.append(tr(S.permissions_apps_that_confirm_every_time) + (0 until ask.length()).joinToString(", ") { ask.getString(it) } + "\n")
        val blk = o.getJSONArray("apps_blocked"); if (blk.length() > 0) b.append(tr(S.permissions_blocked_apps) + (0 until blk.length()).joinToString(", ") { blk.getString(it) } + "\n")
        val ro = o.getJSONArray("apps_read_only"); if (ro.length() > 0) b.append(tr(S.permissions_read_only_apps) + (0 until ro.length()).joinToString(", ") { ro.getString(it) } + "\n")
        val f = o.getJSONArray("fixed"); for (i in 0 until f.length()) b.append("- ").append(f.getString(i)).append('\n')
        b.append(tr(S.permissions_confirm_each_time_runs_after))
        return b.toString().trimEnd() + "\n"
    }

    /** What changes a notice is about: the handling of each class and the app rules (not the expiry clocks). */
    internal fun signature(call: Call?, nowMs: Long = StrongConfirm.clock()): String {
        val tok = tokenOf(call) ?: return ""
        val acts = Policy.ROWS.joinToString(",") { "${it.key}=${StrongConfirm.effectiveAct(tok, it.key, nowMs, touch = false).key}" }
        val rules = VScreenStore.rules.value.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.key}" }
        return "$acts|$rules|${tok.trusted}"
    }

    private val seen = HashMap<String, Pair<String, Map<String, String>>>()

    /**
     * The notice for the next reply when this agent's permissions changed since it last heard (the user edited the
     * table, an allow ran out, an app rule changed), or null. The first reply only sets the baseline: the agent learns
     * the full table from `vscreen permissions` / `status`, the guide tells it to read that at the start.
     */
    fun changeNotice(call: Call?, nowMs: Long = StrongConfirm.clock()): String? {
        val tok = tokenOf(call) ?: return null
        val sig = signature(call, nowMs)
        val acts = Policy.ROWS.associate { it.key to StrongConfirm.effectiveAct(tok, it.key, nowMs, touch = false).key }
        val prev = synchronized(seen) { seen.put(tok.id, sig to acts) } ?: return null
        if (prev.first == sig) return null
        val diffs = Policy.ROWS.mapNotNull { r ->
            val a = prev.second[r.key]; val b = acts[r.key]
            if (a == b || b == null) null else "${r.title} ${actWord(Policy.Act.of(a) ?: Policy.Act.ASK)} → ${actWord(Policy.Act.of(b) ?: Policy.Act.ASK)}"
        }
        val what = if (diffs.isEmpty()) tr(S.permissions_app_rules_or_payment_settings) else diffs.joinToString(tr(S.agent_guide_sep))
        return tr(S.permissions_permissions_changed_full_list_vscreen, what)
    }

    internal fun resetForTests() = synchronized(seen) { seen.clear() }
}
