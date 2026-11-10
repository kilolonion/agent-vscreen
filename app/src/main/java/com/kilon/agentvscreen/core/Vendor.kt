package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

/**
 * 1.2: everything that depends on who made the phone sits behind this one interface (before, `when (vendor)` blocks
 * were spread over the shell, the power and the downscale code). A new maker is one more object here; the rest of the
 * app asks [Vendor.current] and never looks at the brand itself.
 */
internal interface Vendor {
    /** Where the user turns off the system's resolution downscaling. */
    fun downscaleHint(): String = tr("在系统 设置 › 显示 里把屏幕分辨率调到最高档，并关闭系统/游戏助手对该应用的降分辨率", "In Settings › Display set the screen resolution to the highest, and turn off downscaling of this app by the system / game assistant")
    /** Things that commonly break background operation on this maker's system, for status. */
    fun hints(): List<String> = emptyList()
    /** The system property that names this maker's OS version. */
    fun osName(prop: (String) -> String): String = ""

    /** Phone maker and system. */
    data class Device(val vendor: String, val brand: String, val model: String, val os: String, val sdk: Int)

    private object Generic : Vendor

    private object Vivo : Vendor {
        override fun osName(prop: (String) -> String) = prop("ro.vivo.os.build.display.id").ifEmpty { "OriginOS/FuntouchOS " + prop("ro.vivo.os.version") }
        override fun downscaleHint() = tr("vivo/iQOO：设置 › 显示与亮度 › 屏幕分辨率 选最高档（关闭“智能切换”）；游戏还要在 游戏魔盒/电竞模式 里对该游戏关闭“超分辨率/插帧/画质增强”", "vivo/iQOO: Settings › Display and brightness › Screen resolution: choose the highest (turn off \"Smart switch\"); for games also turn off \"super resolution / frame interpolation / picture enhancement\" for that game in Game Cube / e-sports mode")
        override fun hints() = listOf(
            tr("开发者选项里打开“禁止权限监控”：否则 Shizuku 下的输入注入、wm/settings 命令可能被系统拦截（点击无效或命令失败）", "Turn on \"Disable permission monitoring\" in Developer options: otherwise input injection and wm/settings commands under Shizuku may be blocked (taps do nothing or commands fail)"),
            tr("i管家 › 应用管理 › 权限管理 › 自启动 允许 Agent-VScreen 和 Shizuku；设置 › 电池 › 后台耗电管理 选“允许后台高耗电”，否则后台副屏会被杀", "i Manager › App manager › Permissions › Autostart: allow Agent-VScreen and Shizuku; Settings › Battery › Background power consumption: choose \"Allow high background power use\", or the background screen gets killed"),
            tr("锁屏久了 OriginOS 可能停掉 Shizuku：status 报 not_connected 时用 fix 重连", "After a long lock OriginOS may stop Shizuku: when status reports not_connected, reconnect with fix"),
            tr("iQOO 电竞模式/游戏魔盒会拦截通知和悬浮窗：在副屏跑游戏时不要对该游戏开“免打扰/极速模式”", "iQOO e-sports mode / Game Cube blocks notifications and floating windows: don't turn on \"Do not disturb / turbo mode\" for a game running on the screen"),
        )
    }

    private object Honor : Vendor {
        override fun osName(prop: (String) -> String) = prop("ro.build.version.magic").ifEmpty { prop("ro.build.version.emui") }
        override fun downscaleHint() = tr("荣耀/华为：设置 › 显示和亮度 › 屏幕分辨率 关闭“智能分辨率”或选最高档（这是整机设置；能否去掉某个应用的降分辨率比例未经实测）", "Honor/Huawei: Settings › Display and brightness › Screen resolution: turn off \"Smart resolution\" or choose the highest (a phone-wide setting; untested whether it removes one app's downscale ratio)")
        override fun hints() = listOf(tr("设置 › 应用和服务 › 应用启动管理 把 Agent-VScreen 和 Shizuku 设为手动管理并全部允许", "Settings › Apps & services › App launch: set Agent-VScreen and Shizuku to manual management and allow everything"))
    }

    private object Xiaomi : Vendor {
        override fun osName(prop: (String) -> String) = prop("ro.mi.os.version.name").ifEmpty { "MIUI " + prop("ro.miui.ui.version.name") }
        override fun downscaleHint() = tr("小米：设置 › 显示 › 屏幕分辨率 选最高档；游戏加速里关闭该游戏的“画质/分辨率”优化", "Xiaomi: Settings › Display › Screen resolution: choose the highest; in Game Turbo turn off the \"quality / resolution\" optimization for that game")
        override fun hints() = listOf(tr("开发者选项里打开“USB 调试（安全设置）”，否则输入注入会被拒绝；省电策略选“无限制”", "Turn on \"USB debugging (Security settings)\" in Developer options, or input injection is refused; set battery saver to \"No restrictions\""))
    }

    private object Oppo : Vendor {
        override fun osName(prop: (String) -> String) = prop("ro.build.version.oplusrom").ifEmpty { prop("ro.build.version.opporom") }
        override fun downscaleHint() = tr("OPPO/一加：设置 › 显示与亮度 › 屏幕分辨率 选最高档（关闭“智能切换”）", "OPPO/OnePlus: Settings › Display and brightness › Screen resolution: choose the highest (turn off \"Smart switch\")")
        override fun hints() = listOf(tr("设置 › 电池 › 应用耗电管理 允许 Agent-VScreen 和 Shizuku 后台运行", "Settings › Battery › App battery management: allow Agent-VScreen and Shizuku to run in the background"))
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
