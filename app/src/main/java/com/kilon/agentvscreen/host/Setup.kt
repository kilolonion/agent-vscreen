package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.tr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.kilon.agentvscreen.core.VScreenPower
import com.kilon.agentvscreen.core.VScreenShell

/**
 * First-run setup with as few taps as possible. Everything goes through Shizuku (or root, 1.4.2-beta3, [Privileged]) (a shared privileged channel that any
 * number of apps use at once): nothing here touches adb, wireless debugging, adbd or its port, so an agent that uses
 * adb itself keeps it. Each step only ADDS this app (to the accessibility list, the battery whitelist…) and never
 * removes or replaces what other apps have.
 */
object Setup {
    data class Item(val key: String, val label: String, val ok: Boolean, val detail: String)

    fun check(ctx: Context): List<Item> {
        val pv = Privileged.snapshot.value
        val sh = ShizukuManager.snapshot.value
        val pw = VScreenPower.heal(ctx, "setup check")
        return listOf(
            // 1.4.2-beta3: one item for the way in, Shizuku or root, whichever the user chose.
            if (pv.via == Privileged.Via.ROOT) Item("privilege", tr("Root 权限", "Root permission"), Privileged.isReady(),
                if (Privileged.isReady()) pv.label else Privileged.notReadyHint())
            else Item("privilege", tr("Shizuku 授权", "Shizuku permission"), sh.state == ShizukuManager.State.READY, when (sh.state) {
                ShizukuManager.State.NOT_INSTALLED -> tr("没装 Shizuku（或 Sui）；手机有 root 也可以改用 Root", "Shizuku (or Sui) is not installed; a rooted phone can use root instead")
                ShizukuManager.State.NOT_RUNNING -> ShizukuManager.missingBinderHint()
                ShizukuManager.State.NEED_PERMISSION -> if (sh.denied) ShizukuManager.deniedHint() else tr("点“授权 Shizuku”并允许", "Tap \"Grant Shizuku\" and allow")
                ShizukuManager.State.READY -> if (sh.isSui) "Sui" else "Shizuku v${sh.version}"
            }),
            Item("overlay", tr("悬浮窗", "Floating window"), Settings.canDrawOverlays(ctx), tr("副屏小窗（看进度、接管）", "The screen's small window (progress, take over)")),
            Item("accessibility", tr("无障碍（只读）", "Accessibility (read-only)"), VScreenAccessibilityService.getInstance() != null, tr("元素列表和中文输入更准；不开也能用（只靠文字识别）", "More accurate element lists and text input; works without it (text recognition only)")),
            Item("notifications", tr("通知", "Notifications"), NotificationManagerCompat.from(ctx).areNotificationsEnabled(), tr("盯守提醒、交还提醒", "Watch alerts, hand-back reminders")),
            Item("battery", tr("电池优化和后台运行", "Battery optimization and background running"), pw.ok, when {
                pw.ok && VScreenPower.resets > 0 -> tr("已加入电池白名单、允许后台运行（系统收回过 ${VScreenPower.resets} 次：${VScreenPower.lastResetWhat}，每次都已自动补上，不用你管）", "Battery exemption and background running granted (the system took them back ${VScreenPower.resets} times: ${VScreenPower.lastResetWhat}; each time they were granted again automatically, nothing to do)")
                pw.ok -> tr("已加入电池白名单、允许后台运行（被系统收回时会自动补上）", "Battery exemption and background running granted (granted again automatically if the system takes them back)")
                Privileged.isReady() -> tr("自动设置没成功：", "Automatic setup failed: ") + pw.problems().joinToString(tr("、", ", ")) + tr("。请手动：", ". Please do it by hand: ") + VScreenPower.manualSteps()
                else -> pw.problems().joinToString(tr("、", ", ")) + tr("（${Privileged.name()} 就绪后会自动补上）", " (granted automatically once ${Privileged.name()} is ready)")
            }),
        ) + vendorItem(ctx) + detectorItem(ctx)
    }

    /**
     * 2.0: the built-in textless-icon detector is AGPL (a separate file next to the MIT app), so it stays off until
     * the user has read that; first-run setup asks once (optional: turn on or skip; Settings can do it later).
     */
    private fun detectorItem(ctx: Context): List<Item> {
        if (com.kilon.agentvscreen.core.DetectorModels.builtIn(ctx) == null) return emptyList()
        val on = com.kilon.agentvscreen.core.VScreenDetector.licenseAccepted(ctx)
        val skipped = com.kilon.agentvscreen.core.VScreenDetector.declined(ctx)
        return listOf(Item("detector", tr("无字图标识别", "Textless icon finder"), on || skipped, when {
            on -> tr("已开启：agent 的元素列表里会多出没有文字的图标、×、开关", "On: the agent's element list includes icons, × and switches without words")
            skipped -> tr("已跳过，可以在设置里随时开启", "Skipped; turn it on in Settings any time")
            else -> tr("帮 agent 找到图标、×、开关这类没有文字的按钮。", "Helps agents find buttons without words (icons, ×, switches). ") + com.kilon.agentvscreen.core.VScreenDetector.licenseNote()
        }))
    }

    /**
     * Phones with their own background manager (MagicOS, HarmonyOS/EMUI, MIUI/HyperOS, ColorOS, OriginOS) freeze apps
     * that the battery whitelist alone does not protect; that switch exists only on the vendor's page and only the user
     * can flip it. So it is a setup step of its own (stock Android has no such page and no such step).
     */
    private fun vendorItem(ctx: Context): List<Item> {
        val page = Liveness.vendorPage(ctx) ?: return emptyList()
        val done = Liveness.vendorSetupDone(ctx)
        return listOf(Item("vendor_bg", tr("系统后台管理", "System background management"), done,
            if (done) tr("已在“$page”里设置；只有亮屏使用时又被冻结，这一项才会重新亮起", "Set in \"$page\"; this item only lights up again if the app is frozen while the screen is on")
            else tr("在“$page”里把 Agent-VScreen 改成手动管理，允许自启动、关联启动和后台活动", "In \"$page\", set Agent-VScreen to manual management and allow auto-start, start by other apps and background activity")))
    }

    /** Grant everything Shizuku can grant. Returns one line per step. */
    fun grantAll(ctx: Context): List<String> {
        if (!Privileged.isReady()) return listOf(tr("${Privileged.name()} 还没就绪：", "${Privileged.name()} is not ready: ") + Privileged.notReadyHint())
        A11yHeal.want(ctx)
        val pkg = ctx.packageName
        val out = ArrayList<String>()
        fun step(label: String, argv: Array<String>) {
            val r = runCatching { VScreenShell.run(argv, 6_000L) }.getOrNull()
            out += if (r != null && r.exitCode == 0) "✓ $label" else "✗ $label：${r?.combined?.take(80) ?: tr("失败", "failed")}"
        }
        if (!Settings.canDrawOverlays(ctx)) step(tr("悬浮窗", "Floating window"), arrayOf("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"))
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            step(tr("通知", "Notifications"), arrayOf("pm", "grant", pkg, Manifest.permission.POST_NOTIFICATIONS))
        if (VScreenAccessibilityService.getInstance() == null) out += Heal.accessibility(ctx).let { (ok, m) -> (if (ok) "✓ " else "✗ ") + m }
        if (!VScreenPower.check(ctx).ok) out += Heal.power(ctx).let { (ok, m) -> (if (ok) "✓ " else "✗ ") + tr("后台白名单：$m", "Background exemption: $m") }
        if (out.isEmpty()) out += tr("全部已就绪", "All set")
        AppLogger.info("Setup", out.joinToString(" | "))
        return out
    }
}
