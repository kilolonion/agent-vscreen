package com.kilon.agentvscreen.host

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
        Policy.Act.ALLOW -> tr("直接执行", "runs directly")
        Policy.Act.ASK -> tr("每次在手机上确认后执行（等待中返回 CONFIRM_PENDING / NEEDS_CONFIRM）", "runs after the user confirms on the phone each time (CONFIRM_PENDING / NEEDS_CONFIRM while waiting)")
        Policy.Act.DENY -> tr("不能直接执行；先用 vscreen elevate 申请（命令撞上时手机也会弹出同样的申请）", "cannot run as is; ask first with vscreen elevate (a command that meets it brings up the same request)")
    }

    private fun actWord(a: Policy.Act): String = when (a) {
        Policy.Act.ALLOW -> tr("允许", "allowed")
        Policy.Act.ASK -> tr("每次确认", "confirm each time")
        Policy.Act.DENY -> tr("不允许", "not allowed")
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
            rows.put(JSONObject().put("class", r.key).put("title", r.title).put("covers", r.hint)
                .put("handling", eff.key).put("handling_title", actWord(eff)).put("meaning", meaning(eff))
                .also { o -> if (eff != base) o.put("table", base.key).put("raised_by", tr("临时许可", "a temporary allow")) })
        }
        return JSONObject()
            .put("agent", tok.name)
            .put("level", tok.level).put("level_title", Policy.levelTitle(tok.level))
            .put("classes", rows)
            .put("temporary", JSONArray(grants.map { g ->
                JSONObject().put("what", g.risk.removePrefix(StrongConfirm.GRANT_PREFIX)).put("until", g.until)
                    .put("ends", if (g.by.endsWith(":" + StrongConfirm.Range.ROUND.key)) tr("本轮任务结束时", "when this turn ends") else tr("今天结束时", "at the end of today"))
            }))
            .put("payments_without_confirm_until", if (tok.trusted) tok.trustUntil else JSONObject.NULL)
            .put("apps_confirm_every_time", JSONArray(VScreenStore.rules.value.filter { it.value == VScreenStore.Rule.ASK }.keys.toList()))
            .put("apps_blocked", JSONArray(VScreenStore.rules.value.filter { it.value == VScreenStore.Rule.BLOCK }.keys.toList()))
            .put("apps_read_only", JSONArray(VScreenStore.rules.value.filter { it.value == VScreenStore.Rule.READONLY }.keys.toList()))
            .put("fixed", JSONArray().apply {
                put(tr("支付每次都单独确认，不能申请免确认", "every payment is confirmed on its own and cannot be asked away"))
                if (public) put(tr("公网 MCP 不开放系统操作", "system actions are never available over public MCP"))
            })
            .put("enforced_by", tr("手机在每条命令执行前按上表检查，与你的判断无关", "the phone checks every command against this table before it runs, whatever you believe"))
            .put("ask_more", tr("vscreen elevate <class|level> --why \"原因\"（可加 --for round|today|always）", "vscreen elevate <class|level> --why \"reason\" (optionally --for round|today|always)"))
    }

    /** The same, as lines an agent can pass on to the user as is. */
    fun text(call: Call?, nowMs: Long = StrongConfirm.clock()): String {
        val o = json(call, nowMs)
        if (o.has("error")) return tr("令牌无效", "Invalid token")
        val b = StringBuilder()
        b.append(tr("权限（${o.getString("agent")}，档位「${o.getString("level_title")}」）：\n", "Permissions (${o.getString("agent")}, level \"${o.getString("level_title")}\"):\n"))
        val rows = o.getJSONArray("classes")
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            b.append("- ${r.getString("title")}（${r.getString("class")}）：${r.getString("handling_title")}")
            if (r.has("raised_by")) b.append(tr("（临时许可）", " (temporary)"))
            b.append(tr("，", "; ")).append(r.getString("covers")).append('\n')
        }
        val t = o.getJSONArray("temporary")
        if (t.length() > 0) b.append(tr("临时许可：", "Temporary: ") + (0 until t.length()).joinToString(tr("；", "; ")) { val g = t.getJSONObject(it); "${g.getString("what")} ${g.getString("ends")}" } + "\n")
        val ask = o.getJSONArray("apps_confirm_every_time"); if (ask.length() > 0) b.append(tr("每次确认的应用：", "Apps that confirm every time: ") + (0 until ask.length()).joinToString(", ") { ask.getString(it) } + "\n")
        val blk = o.getJSONArray("apps_blocked"); if (blk.length() > 0) b.append(tr("禁止操作的应用：", "Blocked apps: ") + (0 until blk.length()).joinToString(", ") { blk.getString(it) } + "\n")
        val ro = o.getJSONArray("apps_read_only"); if (ro.length() > 0) b.append(tr("只读的应用：", "Read-only apps: ") + (0 until ro.length()).joinToString(", ") { ro.getString(it) } + "\n")
        val f = o.getJSONArray("fixed"); for (i in 0 until f.length()) b.append("- ").append(f.getString(i)).append('\n')
        b.append(tr("「每次确认」= 手机上确认后执行；「不允许」= 先用 vscreen elevate 申请。手机在每条命令前按此检查。", "\"Confirm each time\" = runs after the user confirms; \"not allowed\" = ask first with vscreen elevate. The phone checks every command against this."))
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
        val what = if (diffs.isEmpty()) tr("应用规则或支付设置有变化", "app rules or payment settings changed") else diffs.joinToString(tr("；", "; "))
        return tr("（权限已变更：$what。完整权限见 vscreen permissions。）", "(Permissions changed: $what. Full list: vscreen permissions.)")
    }

    internal fun resetForTests() = synchronized(seen) { seen.clear() }
}
