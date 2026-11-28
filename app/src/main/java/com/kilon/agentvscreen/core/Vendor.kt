package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.S
import com.kilon.agentvscreen.tr

/**
 * 1.2: everything that depends on who made the phone sits behind this one interface (before, `when (vendor)` blocks
 * were spread over the shell, the power and the downscale code). A new maker is one more object here; the rest of the
 * app asks [Vendor.current] and never looks at the brand itself.
 */
internal interface Vendor {
    /** Where the user turns off the system's resolution downscaling. */
    fun downscaleHint(): String = tr(S.vendor_in_settings_display_set_the)
    /** Things that commonly break background operation on this maker's system, for status. */
    fun hints(): List<String> = emptyList()
    /**
     * What turns off this maker's limit on adb when Shizuku says "adb permission limited" (Shizuku's own setup guide:
     * shizuku.rikka.app/guide/setup). Under that limit Shizuku cannot grant any app, so its "Allow" never shows.
     */
    fun adbLimitedFix(): String = tr(S.vendor_in_developer_options_find_the)
    /** The system property that names this maker's OS version. */
    fun osName(prop: (String) -> String): String = ""

    /** Phone maker and system. */
    data class Device(val vendor: String, val brand: String, val model: String, val os: String, val sdk: Int)

    private object Generic : Vendor

    private object Vivo : Vendor {
        override fun osName(prop: (String) -> String) = prop("ro.vivo.os.build.display.id").ifEmpty { "OriginOS/FuntouchOS " + prop("ro.vivo.os.version") }
        override fun downscaleHint() = tr(S.vendor_vivo_iqoo_settings_display_and)
        override fun hints() = listOf(
            tr(S.vendor_if_taps_do_nothing_or),
            tr(S.vendor_i_manager_app_manager_permissions),
            tr(S.vendor_after_a_long_lock_originos),
            tr(S.vendor_iqoo_e_sports_mode_game),
        )
    }

    private object Honor : Vendor {
        override fun osName(prop: (String) -> String) = prop("ro.build.version.magic").ifEmpty { prop("ro.build.version.emui") }
        override fun downscaleHint() = tr(S.vendor_honor_huawei_settings_display_and)
        override fun hints() = listOf(tr(S.vendor_settings_apps_services_app_launch))
    }

    private object Xiaomi : Vendor {
        override fun osName(prop: (String) -> String) = prop("ro.mi.os.version.name").ifEmpty { "MIUI " + prop("ro.miui.ui.version.name") }
        override fun adbLimitedFix() = tr(S.vendor_xiaomi_poco_in_developer_options)
        override fun downscaleHint() = tr(S.vendor_xiaomi_settings_display_screen_resolutio)
        override fun hints() = listOf(tr(S.vendor_turn_on_usb_debugging_security))
    }

    private object Oppo : Vendor {
        override fun osName(prop: (String) -> String) = prop("ro.build.version.oplusrom").ifEmpty { prop("ro.build.version.opporom") }
        override fun downscaleHint() = tr(S.vendor_oppo_oneplus_settings_display_and)
        override fun adbLimitedFix() = tr(S.vendor_oppo_oneplus_realme_coloros_switch)
        override fun hints() = listOf(
            tr(S.vendor_if_shizuku_says_adb_permission),
            tr(S.vendor_settings_battery_app_battery_management),
        )
    }

    companion object {
        /** Maker key from the build fields (pure, unit-tested). */
        fun keyOf(manufacturer: String?, brand: String?): String {
            val m = (manufacturer ?: "").lowercase(); val b = (brand ?: "").lowercase()
            return when {
                m.contains("vivo") || b.contains("vivo") || b.contains("iqoo") -> "vivo"
                m.contains("honor") || b.contains("honor") -> "honor"
                m.contains("huawei") -> "huawei"
                m.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> "xiaomi"
                m.contains("oppo") || b.contains("oneplus") || b.contains("realme") -> "oppo"
                m.contains("samsung") -> "samsung"
                else -> m.ifEmpty { "unknown" }
            }
        }

        fun of(key: String): Vendor = when (key) { "vivo" -> Vivo; "honor", "huawei" -> Honor; "xiaomi" -> Xiaomi; "oppo" -> Oppo; else -> Generic }

        @Volatile private var cached: Device? = null

        val device: Device get() = cached ?: run {
            fun prop(k: String) = runCatching { VScreenShell.run(arrayOf("getprop", k), 3_000L).combined.trim() }.getOrDefault("")
            val key = keyOf(android.os.Build.MANUFACTURER, android.os.Build.BRAND)
            Device(key, android.os.Build.BRAND ?: "", android.os.Build.MODEL ?: "", of(key).osName(::prop).trim(), android.os.Build.VERSION.SDK_INT).also { cached = it }
        }

        val current: Vendor get() = of(device.vendor)
    }
}
