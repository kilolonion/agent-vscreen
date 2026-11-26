package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * [vscreen] v21: what background operation keeps across sessions and restarts.
 *
 *  - App rules (set by the USER in settings, the agent can only read them):
 *      block    = the agent may not open, read or touch this app on the background screen
 *      readonly = the agent may open, read and scroll it, but not tap / type / swipe in it
 *      ask      = the agent may act in it only after the user allowed it on the phone (per stretch, valid 5 minutes)
 * What the agent writes down about an app (notes, saved segments, profile) is in [VScreenMap].
 *  - Idle power saving switch.
 *
 * Everything lives in the app's private storage (files/vscreen + prefs), never in the chat sandbox.
 */
internal object VScreenStore {
    private const val TAG = "VScreenStore"
    private const val PREFS = "vscreen_store"
    private const val K_RULES = "app_rules"
    private const val K_IDLE_SAVE = "idle_power_save"
    private const val K_KEEP_SCREEN_ON = "keep_screen_on"

    enum class Rule(val key: String, private val zhText: String, private val enText: String) {
        BLOCK("block", "禁止操作", "blocked"), READONLY("readonly", "只读", "read-only"),
        /** 1.2: every stretch that acts in this app is confirmed by the user on the phone first. */
        ASK("ask", "每次确认", "confirm every time");
        val zh: String get() = tr(zhText, enText)
        companion object { fun of(s: String?): Rule? = values().firstOrNull { it.key == s } }
    }

    @Volatile private var appContext: Context? = null
    private lateinit var prefs: SharedPreferences

    private val _rules = MutableStateFlow<Map<String, Rule>>(emptyMap())
    /** package -> rule, for the settings screen. */
    val rules: StateFlow<Map<String, Rule>> = _rules.asStateFlow()

    private val _idlePowerSave = MutableStateFlow(true)
    val idlePowerSave: StateFlow<Boolean> = _idlePowerSave.asStateFlow()
    /** 2.0.0 (issue #48): keep the phone's own screen on (dimmed) while an agent turn runs. Off by default. */
    private val _keepScreenOn = MutableStateFlow(false)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _rules.value = runCatching {
            val o = JSONObject(prefs.getString(K_RULES, "{}") ?: "{}")
            o.keys().asSequence().mapNotNull { k -> Rule.of(o.optString(k))?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
        _idlePowerSave.value = prefs.getBoolean(K_IDLE_SAVE, true)
        _keepScreenOn.value = prefs.getBoolean(K_KEEP_SCREEN_ON, false)
    }

    private fun ready() = appContext != null

    // ── app rules ─────────────────────────────────────────────────────────

    fun ruleFor(pkg: String?): Rule? = pkg?.let { _rules.value[it] }

    /** Only the settings screen calls this: the agent cannot change its own limits. */
    fun setRule(pkg: String, rule: Rule?) {
        if (!ready()) return
        val m = _rules.value.toMutableMap()
        if (rule == null) m.remove(pkg) else m[pkg] = rule
        _rules.value = m
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k, v.key) }
        prefs.edit().putString(K_RULES, o.toString()).apply()
    }

    fun setIdlePowerSave(on: Boolean) {
        if (!ready()) return
        _idlePowerSave.value = on
        prefs.edit().putBoolean(K_IDLE_SAVE, on).apply()
    }

    fun setKeepScreenOn(on: Boolean) {
        if (!ready()) return
        _keepScreenOn.value = on
        prefs.edit().putBoolean(K_KEEP_SCREEN_ON, on).apply()
    }

    /** File-name-safe key; keeps Chinese, letters, digits, '-', '_' and '.'. */
    fun safeName(s: String): String =
        s.trim().replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(60).ifEmpty { "_" }
}
