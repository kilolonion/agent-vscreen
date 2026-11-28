package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.trPair
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
    private const val K_PHONE_SCREEN_MODE = "phone_screen_mode"

    enum class PhoneScreenMode(val key: String) {
        ALLOW_SLEEP("allow_sleep"),
        KEEP_ON("keep_on"),
        WAKE_WHEN_NEEDED("wake_when_needed");

        companion object {
            fun of(s: String?): PhoneScreenMode? = entries.firstOrNull { it.key == s }

            /** Pure migration: new key wins when valid; otherwise legacy true -> KEEP_ON, false/null -> ALLOW_SLEEP. */
            internal fun migrate(newVal: String?, legacyKeepOn: Boolean?): PhoneScreenMode =
                of(newVal) ?: if (legacyKeepOn == true) KEEP_ON else ALLOW_SLEEP
        }
    }

    enum class Rule(val key: String, private val zhText: String, private val enText: String) {
        BLOCK("block", "禁止操作", "blocked"), READONLY("readonly", "只读", "read-only"),
        /** 1.2: every stretch that acts in this app is confirmed by the user on the phone first. */
        ASK("ask", "每次确认", "confirm every time");
        val zh: String get() = trPair(zhText, enText)
        companion object { fun of(s: String?): Rule? = values().firstOrNull { it.key == s } }
    }

    @Volatile private var appContext: Context? = null
    private lateinit var prefs: SharedPreferences

    private val _rules = MutableStateFlow<Map<String, Rule>>(emptyMap())
    /** package -> rule, for the settings screen. */
    val rules: StateFlow<Map<String, Rule>> = _rules.asStateFlow()

    private val _idlePowerSave = MutableStateFlow(true)
    val idlePowerSave: StateFlow<Boolean> = _idlePowerSave.asStateFlow()

    /** Three-mode phone screen policy during active agent turns (migrated from legacy keep_screen_on). */
    private val _phoneScreenMode = MutableStateFlow(PhoneScreenMode.ALLOW_SLEEP)
    val phoneScreenMode: StateFlow<PhoneScreenMode> = _phoneScreenMode.asStateFlow()

    /** Compatibility flag derived from [phoneScreenMode] (`mode != ALLOW_SLEEP`). */
    val keepScreenOnEnabled: Boolean get() = _phoneScreenMode.value != PhoneScreenMode.ALLOW_SLEEP

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _rules.value = runCatching {
            val o = JSONObject(prefs.getString(K_RULES, "{}") ?: "{}")
            o.keys().asSequence().mapNotNull { k -> Rule.of(o.optString(k))?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
        _idlePowerSave.value = prefs.getBoolean(K_IDLE_SAVE, true)
        loadAndMigratePhoneScreenMode(prefs)
    }

    internal fun loadAndMigratePhoneScreenMode(sp: SharedPreferences): PhoneScreenMode {
        val rawNew = sp.getString(K_PHONE_SCREEN_MODE, null)
        val hasLegacy = sp.contains(K_KEEP_SCREEN_ON)
        val legacy = if (hasLegacy) sp.getBoolean(K_KEEP_SCREEN_ON, false) else null
        val mode = PhoneScreenMode.migrate(rawNew, legacy)
        if (rawNew == null && hasLegacy) {
            sp.edit().putString(K_PHONE_SCREEN_MODE, mode.key).remove(K_KEEP_SCREEN_ON).apply()
        }
        _phoneScreenMode.value = mode
        return mode
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

    fun setPhoneScreenMode(mode: PhoneScreenMode) {
        _phoneScreenMode.value = mode
        if (!ready()) return
        prefs.edit().putString(K_PHONE_SCREEN_MODE, mode.key).remove(K_KEEP_SCREEN_ON).apply()
    }

    /** File-name-safe key; keeps Chinese, letters, digits, '-', '_' and '.'. */
    fun safeName(s: String): String =
        s.trim().replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(60).ifEmpty { "_" }
}
