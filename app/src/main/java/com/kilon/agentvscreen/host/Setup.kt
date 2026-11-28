package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.S
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
            if (pv.via == Privileged.Via.ROOT) Item("privilege", tr(S.setup_root_permission), Privileged.isReady(),
                if (Privileged.isReady()) pv.label else Privileged.notReadyHint())
            else Item("privilege", tr(S.setup_shizuku_permission), sh.state == ShizukuManager.State.READY, when (sh.state) {
                ShizukuManager.State.NOT_INSTALLED -> tr(S.setup_shizuku_or_sui_is_not)
                ShizukuManager.State.NOT_RUNNING -> ShizukuManager.missingBinderHint()
                ShizukuManager.State.NEED_PERMISSION -> if (sh.denied) ShizukuManager.deniedHint() else tr(S.setup_tap_grant_shizuku_and_allow)
                ShizukuManager.State.READY -> if (sh.isSui) "Sui" else "Shizuku v${sh.version}"
            }),
            Item("overlay", tr(S.settings_floating_window), Settings.canDrawOverlays(ctx), tr(S.setup_the_screen_s_small_window)),
            Item("accessibility", tr(S.setup_accessibility_read_only), VScreenAccessibilityService.getInstance() != null, tr(S.setup_more_accurate_element_lists_and)),
            Item("notifications", tr(S.setup_notifications), NotificationManagerCompat.from(ctx).areNotificationsEnabled(), tr(S.setup_watch_alerts_hand_back_reminders)),
            Item("battery", tr(S.setup_battery_optimization_and_background_runn), pw.ok, when {
                pw.ok && VScreenPower.resets > 0 -> tr(S.setup_battery_optimization_exemption_and_backg, VScreenPower.resets, VScreenPower.lastResetWhat)
                pw.ok -> tr(S.setup_battery_exemption_and_background_running)
                Privileged.isReady() -> tr(S.setup_automatic_setup_failed) + pw.problems().joinToString(tr(S.automation_list_sep)) + tr(S.setup_please_do_it_by_hand) + VScreenPower.manualSteps()
                else -> pw.problems().joinToString(tr(S.automation_list_sep)) + tr(S.setup_granted_automatically_once_is_ready, Privileged.name())
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
        return listOf(Item("detector", tr(S.detector_icon_without_text_detection), on || skipped, when {
            on -> tr(S.setup_on_the_agent_s_element)
            skipped -> tr(S.setup_skipped_turn_it_on_in)
            else -> tr(S.setup_helps_agents_find_buttons_without) + com.kilon.agentvscreen.core.VScreenDetector.licenseNote()
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
        return listOf(Item("vendor_bg", tr(S.setup_system_background_management), done,
            if (done) tr(S.setup_set_in_this_item_appears, page)
            else tr(S.setup_in_set_agent_vscreen_to, page)))
    }

    /** Grant everything Shizuku can grant. Returns one line per step. */
    fun grantAll(ctx: Context): List<String> {
        if (!Privileged.isReady()) return listOf(tr(S.setup_is_not_ready, Privileged.name()) + Privileged.notReadyHint())
        A11yHeal.want(ctx)
        val pkg = ctx.packageName
        val out = ArrayList<String>()
        fun step(label: String, argv: Array<String>) {
            val r = runCatching { VScreenShell.run(argv, 6_000L) }.getOrNull()
            out += if (r != null && r.exitCode == 0) "✓ $label" else "✗ $label${tr(S.agent_guide_colon)}${r?.combined?.take(80) ?: tr(S.fullscreen_activity_failed)}"
        }
        if (!Settings.canDrawOverlays(ctx)) step(tr(S.settings_floating_window), arrayOf("cmd", "appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"))
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            step(tr(S.setup_notifications), arrayOf("pm", "grant", pkg, Manifest.permission.POST_NOTIFICATIONS))
        if (VScreenAccessibilityService.getInstance() == null) out += Heal.accessibility(ctx).let { (ok, m) -> (if (ok) "✓ " else "✗ ") + m }
        if (!VScreenPower.check(ctx).ok) out += Heal.power(ctx).let { (ok, m) -> (if (ok) "✓ " else "✗ ") + tr(S.setup_background_exemption, m) }
        if (out.isEmpty()) out += tr(S.setup_all_set)
        AppLogger.info("Setup", out.joinToString(" | "))
        return out
    }
}
