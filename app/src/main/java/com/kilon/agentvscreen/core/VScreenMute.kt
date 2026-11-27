package com.kilon.agentvscreen.core

import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONObject
import java.io.File

/**
 * 1.3: apps on the background screen are silent. A video played there came out of the phone's speaker while the user
 * was doing something else on the main screen (field report, 2026-11-14), against the one promise of the product.
 *
 * The audio of an app is turned off with its PLAY_AUDIO app-op (the system mutes that app's players). That is a system
 * setting that outlives whoever set it, so since 1.4.0-beta11 it is owned by the screen service, which runs as the
 * shell beside the screens and outlives this app ([VScreenDisplayService], `Sound`). It gives an app its sound back as
 * soon as the app leaves our screens or comes to the front of the main screen, when a screen is released, when this app
 * dies or is uninstalled, and on its own next start after it was killed. Before, this object owned it and gave sound
 * back only while the app ran: an uninstall or an abnormal exit left apps silent until a command fixed them (rainbow,
 * 2026-11-17).
 *
 * This object decides WHAT should be silent ([sync]); the service does it and keeps the record.
 * Not muted: an app that is in front on the main screen (the user is using it; the app-op is per app, not per screen).
 */
object VScreenMute {
    private const val TAG = "VScreenMute"
    private const val PREFS = "vscreen_mute"
    private const val K_ON = "on"
    private var ctx: Context? = null
    /** What the service said it holds, last time we asked (for the reply and the start-up check). */
    @Volatile private var known: Set<String> = emptySet()

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        // Before beta11 the app kept its own list (files/vscreen/muted.json): give those back once, through the shell.
        legacy()?.takeIf { it.isNotEmpty() }?.let { old ->
            Thread {
                val left = old.filterNot { (p, _) ->
                    if (!PackageNames.isValid(p)) true
                    else runCatching {
                        PlayAudio.giveBackArgvs(0, p, null).forEach { argv -> VScreenShell.run(argv.toTypedArray(), 4_000L) }
                        val read = VScreenShell.run(PlayAudio.readArgv(0, p).toTypedArray(), 4_000L).combined
                        !PlayAudio.silent(PlayAudio.parse(read))
                    }.getOrDefault(false)
                }
                if (left.isEmpty()) legacyFile()?.delete()
                else AppLogger.warning(TAG, "could not give back ${left.keys}")
            }.apply { isDaemon = true; name = "vscreen-unmute-legacy" }.start()
        }
    }

    var enabled: Boolean
        get() = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(K_ON, true) ?: true
        set(v) {
            ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(K_ON, v)?.apply()
            // 1.4.0-beta13: off gives back what the screen muted; apps muted by hand stay as the user set them.
            if (!v) Thread { runCatching { VScreenRemoteDisplay.soundGiveAll() }; known = emptySet() }.apply { isDaemon = true }.start()
            else resync()
        }

    /**
     * 1.4.0-beta14 (test S6: turning muting back on left the apps on the screen audible until the next launch): the
     * rule changed, so what is on the screen now is put under it at once, not at the agent's next action.
     */
    fun resync() {
        Thread {
            runCatching {
                val d = BackgroundOperation.currentDisplay() ?: return@runCatching
                sync(d.displayId, VScreenShell.topActivity(0)?.substringBefore('/'))
            }.onFailure { AppLogger.warning(TAG, "resync: ${it.message}") }
        }.apply { isDaemon = true; name = "vscreen-mute-resync" }.start()
    }

    private fun legacyFile(): File? = ctx?.let { File(File(it.filesDir, "vscreen"), "muted.json") }

    private fun legacy(): Map<String, String>? = runCatching {
        legacyFile()?.takeIf { it.isFile }?.readText()?.let { t -> JSONObject(t).let { o -> o.keys().asSequence().associateWith { k -> o.optString(k, "default") } } }
    }.getOrNull()

    /** "allow" / "ignore" / "default" … as `appops get` says it, or null when it cannot be read. */
    internal fun parseMode(out: String): String? = VScreenDisplayService.parsePlayAudio(out)

    /**
     * Make what is on [displayId] silent and give back what left it. [mainTop] = the package in front on the main screen
     * (never muted, given back if it was). Returns the packages newly muted (for the reply).
     */
    fun sync(displayId: Int, mainTop: String?): List<String> {
        val c = ctx ?: return emptyList()
        if (!enabled) return emptyList()
        val here = runCatching { VScreenHandoff.tasksOn(displayId).map { it.pkg } }.getOrNull() ?: return emptyList()
        val want = here.filter { it != c.packageName && it != mainTop && !VScreenShell.isProtected(it) && it !in plays() }.toSet()
        val held = VScreenRemoteDisplay.soundList().getOrNull()?.toSet() ?: return emptyList()
        val codes = want.filter { it !in held }.associateWith { VScreenRemoteDisplay.soundMute(it).getOrNull() ?: 0 }
        val newly = codes.filterValues { it == 1 || it == 3 }.keys.toList()
        // 1.4.0-beta13: silenced, but a player it already had keeps playing until the app plays again (said, not hidden).
        heard = codes.filterValues { it == 3 }.keys.toList()
        newly.forEach { AppLogger.info(TAG, "muted $it") }
        held.filter { it !in want }.forEach { VScreenRemoteDisplay.soundGive(it) }
        known = VScreenRemoteDisplay.soundList().getOrNull()?.toSet() ?: (held + newly)
        return newly
    }

    /** Give [pkg] its sound back (handed over to the main screen). */
    fun restore(pkg: String) {
        if (VScreenRemoteDisplay.isConnected()) VScreenRemoteDisplay.soundGive(pkg)
        known = known - pkg
    }

    /** Give every app its sound back (screen released). */
    fun restoreAll() {
        if (VScreenRemoteDisplay.isConnected()) VScreenRemoteDisplay.soundGiveAll()
        known = emptySet()
    }

    /** What a repair found and did. [denied]: the system refused the shell (a ROM setting the user must turn on). */
    data class Repair(val fixed: List<PlayAudio.Hit>, val failed: List<PlayAudio.Hit>, val denied: Boolean)

    /** Every silenced app of every user the shell can read (both records, read one by one: query-op misses them). */
    /** 1.4.2: the last full reading, shown at once while the sound page reads again. */
    @Volatile var lastSilenced: List<PlayAudio.Hit>? = null; private set

    private fun scanDirect(): String? {
        val usersOut = runCatching { VScreenShell.run(arrayOf("pm", "list", "users"), 4_000L).combined }.getOrNull() ?: return null
        val users = PlayAudio.parseUsers(usersOut)
        val sb = StringBuilder()
        for (u in users) {
            val uStr = u.toString()
            val pkgsOut = runCatching { VScreenShell.run(arrayOf("pm", "list", "packages", "-U", "--user", uStr), 6_000L).combined }
                .getOrNull()?.takeIf { it.contains("package:") }
                ?: runCatching { VScreenShell.run(arrayOf("pm", "list", "packages", "--user", uStr), 6_000L).combined }.getOrDefault("")
            for ((pkg, uid) in PlayAudio.parsePackages(pkgsOut)) {
                sb.append("@@ ").append(u).append(' ').append(pkg)
                if (uid != null) sb.append(' ').append(uid)
                sb.append('\n')
                val read = runCatching { VScreenShell.run(PlayAudio.readArgv(u, pkg).toTypedArray(), 4_000L).combined }.getOrDefault("")
                sb.append(read).append('\n')
            }
        }
        return sb.toString()
    }

    fun silenced(): List<PlayAudio.Hit>? = runCatching {
        val raw = VScreenRemoteDisplay.soundScan().getOrNull()?.takeIf { it.contains("@@") }
            ?: scanDirect()?.takeIf { it.contains("@@") }
            ?: return@runCatching null
        PlayAudio.parseScan(raw).filter { PlayAudio.silent(it.modes) }
    }.getOrNull()?.also { lastSilenced = it }

    /**
     * 1.4.0-beta12, rebuilt in beta13 (rainbow: it reported success and the app stayed silent). Gives back EVERY app the
     * system has silenced, whoever did it: the service's own list first, then a full reading of every package of every
     * user, and each silenced one gets `allow` on its package and UID records, read again to confirm. Called by
     * turning 副屏静音 off and by 恢复被静音的应用. Null without the privileged connection. A player that was already
     * playing stays quiet until the app plays again (the caller says so). An app on the background screen right now is
     * muted again by the next action while the switch is on.
     */
    fun repair(keepIntended: Boolean = false): Repair? {
        // [keepIntended] (fix): leave the apps muted by hand and the ones on the screen now; only leftovers go back.
        val keep = if (keepIntended) manual() + known else emptySet()
        if (!keepIntended) { runCatching { VScreenRemoteDisplay.soundGiveAll() }; known = emptySet() }
        val bad = silenced()?.filterNot { it.user == 0 && it.pkg in keep } ?: return null
        if (bad.isEmpty()) return Repair(emptyList(), emptyList(), false)
        val ok = ArrayList<PlayAudio.Hit>()
        val no = ArrayList<PlayAudio.Hit>()
        val logOut = StringBuilder()
        var anyDenied = false
        for (h in bad) {
            if (!PackageNames.isValid(h.pkg)) { no += h; continue }
            val stepOut = StringBuilder()
            runCatching {
                for (argv in PlayAudio.giveBackArgvs(h.user, h.pkg, h.uid)) {
                    val r = VScreenShell.run(argv.toTypedArray(), 4_000L)
                    stepOut.append(r.combined).append('\n')
                }
            }
            val readOut = runCatching { VScreenShell.run(PlayAudio.readArgv(h.user, h.pkg).toTypedArray(), 4_000L).combined }.getOrDefault("")
            stepOut.append(readOut)
            val combined = stepOut.toString()
            if (PlayAudio.denied(combined)) anyDenied = true
            if (logOut.length < 300) logOut.append(combined)
            val after = PlayAudio.parse(readOut)
            if (!PlayAudio.silent(after)) ok += h else no += h
        }
        put(K_MANUAL, manual() - ok.filter { it.user == 0 }.map { it.pkg }.toSet())
        if (ok.isNotEmpty()) AppLogger.info(TAG, "repair gave back ${ok.map { "${it.user}/${it.pkg}" }}")
        if (no.isNotEmpty()) AppLogger.warning(TAG, "repair could not give back ${no.map { "${it.user}/${it.pkg}" }}: ${logOut.toString().take(300)}")
        return Repair(ok, no, anyDenied)
    }

    fun mutedNow(): Set<String> = known

    // ── 1.4.0-beta13 (rainbow: a place to mute and unmute apps by hand, and agents can do it too) ──

    private const val K_MANUAL = "manual"
    private const val K_PLAYS = "plays"
    private fun set(k: String): Set<String> = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getStringSet(k, emptySet())?.toSet() ?: emptySet()
    private fun put(k: String, v: Set<String>) { ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putStringSet(k, v)?.apply() }

    /** Apps muted by hand (the user or an agent): muted everywhere until unmuted; the screen never gives them back. */
    fun manual(): Set<String> = set(K_MANUAL)
    /** Apps that keep their sound on the background screen too. */
    fun plays(): Set<String> = set(K_PLAYS)

    private fun uidOf(pkg: String): Int? = runCatching {
        if (!PackageNames.isValid(pkg)) return null
        VScreenShell.run(arrayOf("cmd", "package", "list", "packages", "-U", pkg), 4_000L).combined.lines()
            .firstOrNull { it.trim().startsWith("package:$pkg ") }?.let { Regex("uid:(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }.getOrNull()

    /** What changing one app's sound did: ok, the modes read after, and why not when refused. */
    data class Change(val ok: Boolean, val modes: PlayAudio.Modes?, val why: String? = null)

    private fun applyArgvs(setArgvs: List<List<String>>, readArgv: List<String>, want: (PlayAudio.Modes) -> Boolean): Change {
        val sb = StringBuilder()
        for (argv in setArgvs) {
            val r = runCatching { VScreenShell.run(argv.toTypedArray(), 4_000L) }.getOrNull() ?: return Change(false, null, "no-shizuku")
            sb.append(r.combined).append('\n')
        }
        val read = runCatching { VScreenShell.run(readArgv.toTypedArray(), 4_000L) }.getOrNull() ?: return Change(false, null, "no-shizuku")
        sb.append(read.combined)
        val m = PlayAudio.parse(read.combined)
        val combined = sb.toString()
        return Change(want(m), m, if (PlayAudio.denied(combined)) "denied" else null)
    }

    /** Mute [pkg] everywhere (package and its own UID record), kept until [unmute]. */
    fun mute(pkg: String): Change {
        if (!PackageNames.isValid(pkg)) return Change(false, null, "invalid-package")
        val uid = uidOf(pkg)?.takeIf { it >= 10_000 }
        return applyArgvs(PlayAudio.silenceArgvs(0, pkg, uid), PlayAudio.readArgv(0, pkg)) { PlayAudio.silent(it) }
            .also { if (it.ok) { put(K_MANUAL, manual() + pkg); put(K_PLAYS, plays() - pkg) } }
    }

    /** Give [pkg] its sound back on both records (whoever muted it). */
    fun unmute(pkg: String): Change {
        if (!PackageNames.isValid(pkg)) return Change(false, null, "invalid-package")
        runCatching { if (VScreenRemoteDisplay.isConnected()) VScreenRemoteDisplay.soundGive(pkg) }
        return applyArgvs(PlayAudio.giveBackArgvs(0, pkg, uidOf(pkg)), PlayAudio.readArgv(0, pkg)) { !PlayAudio.silent(it) }
            .also { if (it.ok) put(K_MANUAL, manual() - pkg) }
    }

    /** [pkg] keeps (true) or loses (false) its sound while on the background screen. */
    fun setPlays(pkg: String, on: Boolean) {
        if (!PackageNames.isValid(pkg)) return
        put(K_PLAYS, if (on) plays() + pkg else plays() - pkg)
        resync()
    }

    /** One app's state now: its two records, and whether it can be heard. */
    fun state(pkg: String): PlayAudio.Modes? =
        if (!PackageNames.isValid(pkg)) null
        else runCatching { PlayAudio.parse(VScreenShell.run(arrayOf("cmd", "appops", "get", pkg, "PLAY_AUDIO"), 4_000L).combined) }.getOrNull()

    /** Apps muted by the last [sync] whose sound already playing was still heard. */
    @Volatile var heard: List<String> = emptyList()
        private set
}
