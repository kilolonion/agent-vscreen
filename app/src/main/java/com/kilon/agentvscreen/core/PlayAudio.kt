package com.kilon.agentvscreen.core

/**
 * 1.4.0-beta13 (rainbow: the beta12 repair did not give the sound back). How an app's PLAY_AUDIO app-op is read, judged
 * and set, in one place for the screen service (which mutes) and the app (which repairs). Pure: tests cover it.
 *
 * What the field showed (Honor, MagicOS):
 *  - `cmd appops get` prints TWO records, the UID's first: `Uid mode: PLAY_AUDIO: default` then `PLAY_AUDIO: ignore`.
 *    Reading the first match read the UID line and missed the package's mute.
 *  - An EXPLICIT UID record of `default` mutes too (the audio service marks players `mutedState:opPlayAudio`), so
 *    "set it back to default" never brought sound back. Only `allow`, set on both records, does.
 *  - `cmd appops query-op` ignores `--user` and does not see UID records, so it cannot find silenced apps.
 *  - `--uid` takes a numeric uid on AOSP (a package name only on some ROMs).
 *  - Apps cloned into other users (twin apps, private space) have records of their own.
 *  - Players that already exist stay muted until the app starts playing again.
 */
object PlayAudio {
    const val OP = "PLAY_AUDIO"

    /** The package's record and the UID's record; null = none (the system default, which plays). */
    data class Modes(val pkg: String?, val uid: String?)

    private val MODE = Regex("PLAY_AUDIO:\\s*([a-z_]+)")

    fun parse(out: String): Modes {
        var pkg: String? = null; var uid: String? = null
        for (line in out.lines()) {
            val m = MODE.find(line)?.groupValues?.get(1) ?: continue
            if (line.contains("Uid mode", ignoreCase = true)) uid = m else if (pkg == null) pkg = m
        }
        return Modes(pkg, uid)
    }

    private val BLOCKS = setOf("ignore", "deny", "errored")

    /**
     * The system will not let this app play. AOSP's players play only when the op checks as exactly MODE_ALLOWED
     * (PlayerBase: `mHasAppOpsPlayAudio = (mode == MODE_ALLOWED)`), and a UID record, when there is one, is what the
     * check returns. So any STORED record other than allow (or foreground, which checks as allowed in front) mutes:
     * ignore, deny, errored, and an explicit `default` on either record (MODE_DEFAULT is not MODE_ALLOWED). No record
     * at all is the op's default, which for PLAY_AUDIO is allow.
     */
    fun silent(m: Modes): Boolean = muting(m.pkg) || muting(m.uid)
    private fun muting(mode: String?): Boolean = mode != null && mode != "allow" && mode != "foreground"

    /** Read output that says the shell may not change app-ops (Xiaomi without "USB debugging (security settings)", others). */
    fun denied(out: String): Boolean = Regex("SecurityException|Permission denial|not allowed", RegexOption.IGNORE_CASE).containsMatchIn(out)

    /** Pure argv commands that give [pkg] (user [user], numeric [uid] when known) its sound back on both records. */
    fun giveBackArgvs(user: Int, pkg: String, uid: Int?): List<List<String>> {
        require(user >= 0) { "Invalid user: $user" }
        PackageNames.requireValid(pkg)
        require(uid == null || uid >= 0) { "Invalid uid: $uid" }
        val u = user.toString()
        val uidArg = uid?.toString() ?: pkg
        return listOf(
            listOf("cmd", "appops", "set", "--user", u, pkg, OP, "allow"),
            listOf("cmd", "appops", "set", "--user", u, "--uid", uidArg, OP, "allow"),
        )
    }

    /** Pure argv commands that silence [pkg] on its package record and, given [uid] (the app's own), on its UID record. */
    fun silenceArgvs(user: Int, pkg: String, uid: Int?): List<List<String>> {
        require(user >= 0) { "Invalid user: $user" }
        PackageNames.requireValid(pkg)
        require(uid == null || uid >= 0) { "Invalid uid: $uid" }
        val u = user.toString()
        return buildList {
            add(listOf("cmd", "appops", "set", "--user", u, pkg, OP, "ignore"))
            if (uid != null) add(listOf("cmd", "appops", "set", "--user", u, "--uid", uid.toString(), OP, "ignore"))
        }
    }

    /** Pure argv command to read back both PLAY_AUDIO records for [pkg] in [user]. */
    fun readArgv(user: Int, pkg: String): List<String> {
        require(user >= 0) { "Invalid user: $user" }
        PackageNames.requireValid(pkg)
        return listOf("cmd", "appops", "get", "--user", user.toString(), pkg, OP)
    }

    /** Shell lines that give [pkg] (user [user], numeric [uid] when known) its sound back on both records, then read them. */
    @Deprecated("Use giveBackArgvs + readArgv without sh -c")
    fun giveBack(user: Int, pkg: String, uid: Int?): String {
        require(user >= 0) { "Invalid user: $user" }
        PackageNames.requireValid(pkg)
        require(uid == null || uid >= 0) { "Invalid uid: $uid" }
        val u = "--user $user"
        return "cmd appops set $u $pkg $OP allow 2>&1; " +
            "cmd appops set $u --uid ${uid ?: pkg} $OP allow 2>&1; " +
            "echo '@@after $user $pkg'; cmd appops get $u $pkg $OP 2>&1"
    }

    /** Shell lines that silence [pkg] on its package record and, given [uid] (the app's own), on its UID record; then read them. */
    @Deprecated("Use silenceArgvs + readArgv without sh -c")
    fun silence(user: Int, pkg: String, uid: Int?): String {
        require(user >= 0) { "Invalid user: $user" }
        PackageNames.requireValid(pkg)
        require(uid == null || uid >= 0) { "Invalid uid: $uid" }
        val u = "--user $user"
        return "cmd appops set $u $pkg $OP ignore 2>&1; " +
            (if (uid != null) "cmd appops set $u --uid $uid $OP ignore 2>&1; " else "") +
            "echo '@@after $user $pkg'; cmd appops get $u $pkg $OP 2>&1"
    }

    /**
     * Whether `dumpsys audio` shows a player of [uid] playing and NOT muted by the app-op. Null when this system's dump
     * does not say (no `mutedState`, before Android 13 or another format): unknown, never "fine".
     */
    fun audible(dump: String, uid: Int): Boolean? {
        if (uid < 0 || !dump.contains("mutedState")) return null
        val mine = dump.lines().filter { it.contains("u/pid:$uid/") && it.contains("state:started") }
        return mine.any { l -> !Regex("mutedState:[^\\s]*(opPlayAudio|appOps)", RegexOption.IGNORE_CASE).containsMatchIn(l) }
    }

    /**
     * A shell script that reads PLAY_AUDIO of every package of every user it may read (`pm list users`; a user the
     * shell may not list prints nothing). Each package's reading follows a line `@@ <user> <package> <uid>`.
     */
    // Users: `pm list users`, or user 0 alone when the shell may not list them. Packages: `pm list packages -U`, or
    // without uids where -U is unknown (the UID record is then set by package name, which some ROMs accept).
    const val SCAN = "us=\$(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); [ -z \"\$us\" ] && us=0; " +
        "for u in \$us; do (pm list packages -U --user \$u 2>/dev/null || pm list packages --user \$u 2>/dev/null) | grep '^package:' | while read l; do p=\${l#package:}; p=\${p%% *}; " +
        "i=\$(echo \"\$l\" | sed -n 's/.*uid:\\([0-9]*\\).*/\\1/p'); echo \"@@ \$u \$p \$i\"; cmd appops get --user \$u \$p PLAY_AUDIO 2>&1; done; done"

    data class Hit(val user: Int, val pkg: String, val uid: Int?, val modes: Modes)

    /** The readings of [SCAN] (or of [giveBack]'s `@@after` lines), one per package. */
    fun parseScan(out: String): List<Hit> {
        val hits = ArrayList<Hit>()
        var head: List<String>? = null
        val body = StringBuilder()
        fun flush() {
            val h = head ?: return
            val user = h.getOrNull(1)?.toIntOrNull() ?: return
            val pkg = h.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: return
            hits += Hit(user, pkg, h.getOrNull(3)?.toIntOrNull(), parse(body.toString()))
        }
        for (line in out.lines()) {
            if (line.startsWith("@@")) {
                flush(); body.setLength(0)
                head = line.removePrefix("@@after").removePrefix("@@").trim().split(Regex("\\s+")).let { listOf("@@") + it }
            } else body.append(line).append('\n')
        }
        flush()
        return hits
    }

    private val USER_INFO_RE = Regex("UserInfo\\{(\\d+):")
    private val PKG_UID_RE = Regex("uid:(\\d+)")

    /** Parse numeric Android user IDs from `pm list users`; defaults to `[0]` when none are parsed. */
    fun parseUsers(out: String): List<Int> {
        val ids = USER_INFO_RE.findAll(out).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it >= 0 }.distinct().toList()
        return ids.ifEmpty { listOf(0) }
    }

    /** Parse `(packageName, uid?)` entries from `pm list packages [-U]` output, filtering to valid package names. */
    fun parsePackages(out: String): List<Pair<String, Int?>> = out.lineSequence().mapNotNull { raw ->
        val t = raw.trim()
        if (!t.startsWith("package:")) return@mapNotNull null
        val rest = t.removePrefix("package:")
        val pkg = rest.substringBefore(' ').trim()
        if (!PackageNames.isValid(pkg)) return@mapNotNull null
        val uid = PKG_UID_RE.find(rest)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 0 }
        pkg to uid
    }.distinctBy { it.first }.toList()
}
