package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.tr
import org.json.JSONObject

/**
 * 2.0.0-beta8 (feedback 2–4, rainbow 00:52): what an agent may do, as one table per agent.
 *
 * Each row is a class of action, each cell how it is handled: allowed, asked on the phone, or not allowed. A level is
 * a preset of that table (只看 / 稳妥 / 省心 / 全权); the user can change any row of a preset for one agent (the page
 * marks the changed rows and can restore the preset) or start from 自定义. Widening a row takes a fingerprint,
 * narrowing applies at once. Per-app rules ("ask every time" / read-only / blocked) stay separate: they are about apps,
 * not agents.
 */
object Policy {
    enum class Act(val key: String, val zh: String, val en: String, val rank: Int) {
        DENY("deny", "不允许", "Not allowed", 0),
        ASK("ask", "问我", "Ask me", 1),
        ALLOW("allow", "允许", "Allowed", 2);
        val title: String get() = tr(zh, en)
        companion object { fun of(k: String?): Act? = entries.firstOrNull { it.key == k } }
    }

    /** One row: a class of action, the choices it offers, and how it reads. */
    class Row(val key: String, val zh: String, val en: String, val hintZh: String, val hintEn: String, val choices: List<Act>) {
        val title: String get() = tr(zh, en)
        val hint: String get() = tr(hintZh, hintEn)
    }

    const val OPERATE = "operate"
    const val SYSTEM = "system"
    const val MONEY = "money"
    const val DESTROY = "destroy"
    const val CAUTION = "caution"
    const val RATE = "rate"

    private val ALL3 = listOf(Act.ALLOW, Act.ASK, Act.DENY)
    val ROWS = listOf(
        Row(OPERATE, "操作应用", "Operate apps", "打开、点、输入、滑动", "Open, tap, type, swipe", ALL3),
        Row(CAUTION, "要小心的步骤", "Steps that need care", "普通删除、去购买的中间步骤、强制结束、把你在用的应用接到后台", "Ordinary delete, steps toward buying, force-stop, taking the app you use to the background", ALL3),
        Row(DESTROY, "删了回不来的", "Cannot be undone", "卸载、清除应用数据、彻底删除、注销账号、恢复出厂", "Uninstall, clear app data, delete for good, close an account, factory reset", ALL3),
        Row(MONEY, "付钱", "Paying", "支付、付款、提交订单、转账、充值（不能设为允许）", "Pay, place an order, transfer, top up (cannot be set to allowed)", listOf(Act.ASK, Act.DENY)),
        Row(SYSTEM, "系统操作", "System actions", "修复权限、清理、强制结束等系统级命令", "Repair, cleanup, force-stop and other system commands", ALL3),
        Row(RATE, "操作很频繁", "Many actions a minute", "一分钟超过 ${com.kilon.agentvscreen.host.Risk.RATE_MAX_PER_MIN} 次；允许时只记下并通知你", "Over ${com.kilon.agentvscreen.host.Risk.RATE_MAX_PER_MIN} a minute; when allowed it is only logged and you are notified", listOf(Act.ALLOW, Act.ASK)),
    )
    fun row(key: String): Row? = ROWS.firstOrNull { it.key == key }

    const val CUSTOM = "custom"

    private fun t(op: Act, sys: Act, money: Act, destroy: Act, caution: Act, rate: Act) =
        mapOf(OPERATE to op, SYSTEM to sys, MONEY to money, DESTROY to destroy, CAUTION to caution, RATE to rate)

    /** The presets. Keys stay as before 2.0.0-beta8's names changed (look / careful / free / free_system). */
    val PRESETS: Map<String, Map<String, Act>> = linkedMapOf(
        Tokens.LOOK to t(Act.DENY, Act.DENY, Act.DENY, Act.DENY, Act.DENY, Act.ASK),
        Tokens.CAREFUL to t(Act.ALLOW, Act.DENY, Act.ASK, Act.ASK, Act.ASK, Act.ASK),
        Tokens.FREE to t(Act.ALLOW, Act.DENY, Act.ASK, Act.ASK, Act.ALLOW, Act.ALLOW),
        Tokens.FREE_SYSTEM to t(Act.ALLOW, Act.ALLOW, Act.ASK, Act.ASK, Act.ALLOW, Act.ALLOW),
    )

    /** The table of [level] with [custom] rows on top; a public client never gets system actions. */
    fun table(level: String, custom: Map<String, Act>, publicOnly: Boolean = false): Map<String, Act> {
        val base = PRESETS[level] ?: PRESETS.getValue(Tokens.FREE)
        val out = LinkedHashMap(base)
        custom.forEach { (k, v) -> if (k in out) out[k] = clamp(k, v) }
        if (publicOnly) out[SYSTEM] = Act.DENY
        return out
    }

    /** A choice a row does not offer falls to the nearest stricter one (money is never "allowed"). */
    fun clamp(key: String, a: Act): Act {
        val choices = row(key)?.choices ?: return a
        if (a in choices) return a
        return choices.filter { it.rank <= a.rank }.maxByOrNull { it.rank } ?: choices.minBy { it.rank }
    }

    /** look ⊂ operate ⊂ system, as the rest of the app still checks it: a row that is not "not allowed" is in scope. */
    fun scopeOf(table: Map<String, Act>): String = when {
        table[SYSTEM] != Act.DENY -> "system"
        table[OPERATE] != Act.DENY -> "operate"
        else -> "look"
    }

    /** The rows of [after] that allow more than [before] (these take a fingerprint). */
    fun widened(before: Map<String, Act>, after: Map<String, Act>): List<String> =
        after.filter { (k, v) -> v.rank > (before[k]?.rank ?: 0) }.keys.toList()

    fun levelTitle(level: String): String = when (level) {
        Tokens.LOOK -> tr("只看", "Look only")
        Tokens.CAREFUL -> tr("稳妥", "Steady")
        Tokens.FREE -> tr("省心", "Easy")
        Tokens.FREE_SYSTEM -> tr("全权", "Full")
        CUSTOM -> tr("自定义", "Custom")
        else -> level
    }

    fun levelHint(level: String): String = when (level) {
        Tokens.LOOK -> tr("看画面、读状态，不动手", "Sees the screen and status; does not act")
        Tokens.CAREFUL -> tr("操作应用，要小心的、回不来的、付钱都先问你", "Operates apps; asks before careful steps, the irreversible and paying")
        Tokens.FREE -> tr("操作应用，只有回不来的和付钱问你（默认）", "Operates apps; asks only before the irreversible and paying (default)")
        Tokens.FREE_SYSTEM -> tr("省心再加系统操作", "Easy plus system actions")
        CUSTOM -> tr("每一类自己定", "You set each row")
        else -> ""
    }

    fun toJson(m: Map<String, Act>): JSONObject = JSONObject().apply { m.forEach { (k, v) -> put(k, v.key) } }
    fun fromJson(o: JSONObject?): Map<String, Act> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Act>()
        o.keys().forEach { k -> Act.of(o.optString(k))?.let { if (row(k) != null) out[k] = clamp(k, it) } }
        return out
    }
}
