package com.kilon.agentvscreen.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [PackageNames] and [ExecAllowlist] (P1-1).
 */
class ExecAllowlistTest {
    private val ownPkg = "com.kilon.agentvscreen"
    private val ownApk = "/data/app/~~abc/com.kilon.agentvscreen-xyz/base.apk"

    private fun ok(vararg argv: String, env: Array<String>? = null, cwd: String? = null) {
        val denied = ExecAllowlist.check(arrayOf(*argv), env, cwd, ownPkg, ownApk)
        assertNull("Expected allowed: ${argv.joinToString(" ")} but got: $denied", denied)
    }

    private fun deny(vararg argv: String, env: Array<String>? = null, cwd: String? = null) {
        val denied = ExecAllowlist.check(arrayOf(*argv), env, cwd, ownPkg, ownApk)
        assertNotNull("Expected denied: ${argv.joinToString(" ")}", denied)
    }

    @Test
    fun packageNameValidationRejectsShellAndFlagInjection() {
        assertTrue(PackageNames.isValid("com.kilon.agentvscreen"))
        assertTrue(PackageNames.isValid("a.b"))
        assertTrue(PackageNames.isValid("com.4399.gamecenter"))
        assertTrue(PackageNames.isValidProcess("com.example.app:worker"))

        assertFalse(PackageNames.isValid(""))
        assertFalse(PackageNames.isValid("singleword"))
        assertFalse(PackageNames.isValid("-f"))
        assertFalse(PackageNames.isValid("--user"))
        assertFalse(PackageNames.isValid("com.foo;id"))
        assertFalse(PackageNames.isValid("com.foo\$(id)"))
        assertFalse(PackageNames.isValid("com.foo`id`"))
        assertFalse(PackageNames.isValid("com.foo bar"))
        assertFalse(PackageNames.isValid("com..foo"))
        assertFalse(PackageNames.isValid("123.456"))
        assertFalse(PackageNames.isValid("a." + "b".repeat(130)))
        assertFalse(PackageNames.isValidProcess("com.foo:bad;id"))
    }

    @Test
    fun playAudioArgvsRejectInvalidPackageNames() {
        val s = PlayAudio.silenceArgvs(0, "com.example.app", 10123)
        assertTrue(s.all { it[0] == "cmd" && it[1] == "appops" })
        val g = PlayAudio.giveBackArgvs(10, "com.example.app", null)
        assertTrue(g.all { it[0] == "cmd" && it[1] == "appops" })
        val bad = runCatching { PlayAudio.silenceArgvs(0, "com.example;id", 10123) }
        assertTrue(bad.isFailure)
        val badUser = runCatching { PlayAudio.giveBackArgvs(-1, "com.example.app", null) }
        assertTrue(badUser.isFailure)
    }

    @Test
    fun allowlistPermitsAllLegitimatePrivilegedCommands() {
        ok("id")
        ok("getprop", "ro.build.version.release")
        ok("service", "list")
        ok("pidof", "com.example.app", "com.example.app:worker")
        ok("ps", "-A", "-o", "NAME")
        ok("ps", "-A", "-o", "WCHAN:32,NAME")
        ok("ls", "-1", "/sys/fs/cgroup/uid_10123")
        ok("ls", "-1d", "/sys/fs/cgroup/uid_10123")
        ok("cat", "/proc/net/tcp")
        ok("cat", "/proc/net/tcp6")
        ok("cat", "/proc/1234/cgroup")
        ok("cat", "/proc/1234/wchan")
        ok("cat", "/sys/fs/cgroup/uid_10123/pid_1234/cgroup.events")
        ok("input", "-d", "2", "keyevent", "KEYCODE_WAKEUP")
        ok("wm", "size", "-d", "2")
        ok("wm", "size", "reset", "-d", "2")
        ok("wm", "size", "1080x2400", "-d", "2")
        ok("dumpsys", "audio")
        ok("dumpsys", "activity", "processes")
        ok("dumpsys", "activity", "activities")
        ok("dumpsys", "window", "windows")
        ok("dumpsys", "media_session")
        ok("dumpsys", "input")
        ok("dumpsys", "input_method")
        ok("dumpsys", "SurfaceFlinger")
        ok("dumpsys", "deviceidle", "whitelist", "+$ownPkg")
        ok("settings", "get", "secure", "enabled_accessibility_services")
        ok("settings", "delete", "secure", "enabled_accessibility_services")
        ok("settings", "put", "secure", "enabled_accessibility_services", "$ownPkg/.Svc")
        ok("settings", "put", "secure", "accessibility_enabled", "1")
        ok("settings", "list", "global")
        ok("appops", "set", ownPkg, "SYSTEM_ALERT_WINDOW", "allow")
        ok("pm", "list", "users")
        ok("pm", "list", "packages", "-U", "--user", "0")
        ok("pm", "list", "packages", "--uid", "10123")
        ok("pm", "path", "com.example.app")
        ok("pm", "grant", ownPkg, "android.permission.POST_NOTIFICATIONS")
        ok("cmd", "appops", "get", "com.example.app", "PLAY_AUDIO")
        ok("cmd", "appops", "get", "--user", "0", "com.example.app", "PLAY_AUDIO")
        ok("cmd", "appops", "set", "--user", "0", "com.example.app", "PLAY_AUDIO", "ignore")
        ok("cmd", "appops", "set", "--user", "0", "--uid", "10123", "PLAY_AUDIO", "allow")
        ok("cmd", "appops", "set", ownPkg, "RUN_ANY_IN_BACKGROUND", "allow")
        ok("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "com.example.app")
        ok("cmd", "package", "query-activities", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
        ok("cmd", "package", "list", "packages", "-U", "com.example.app")
        ok("cmd", "package", "path", ownPkg)
        ok("cmd", "game", "set", "--downscale", "disable", "com.example.game")
        ok("cmd", "game", "reset", "com.example.game")
        ok("cmd", "deviceidle", "tempwhitelist", "-d", "60000", ownPkg)
        ok("cmd", "netpolicy", "add", "restrict-background-whitelist", "10123")
        ok("am", "help")
        ok("am", "force-stop", "com.example.app")
        ok("am", "compat", "disable", "DOWNSCALED", "com.example.app")
        ok("am", "freeze", "--sticky", "com.example.app")
        ok("am", "unfreeze", "com.example.app:worker")
        ok("am", "start-foreground-service", "-n", "$ownPkg/com.kilon.agentvscreen.host.HostService")
        ok("am", "set-standby-bucket", ownPkg, "active")
        ok("am", "display", "move-stack", "42", "2")
        ok("am", "start", "-W", "--display", "2", "--windowingMode", "1", "-f", "0x18000000", "-n", "com.example.app/.MainActivity")
        ok(
            "/system/bin/app_process", "/system/bin", "com.kilon.agentvscreen.core.VScreenShellMain", "ime-hide", "2",
            env = arrayOf("CLASSPATH=$ownApk"),
        )
    }

    @Test
    fun allowlistBlocksShellInterpretersArbitraryEnvCwdAndAbuse() {
        // Shell interpreters & arbitrary binaries
        deny("sh", "-c", "id")
        deny("/system/bin/sh", "-c", "id")
        deny("su", "-c", "id")
        deny("bash", "-c", "id")
        deny("toybox", "cat", "/etc/hosts")
        deny("rm", "-rf", "/data/local/tmp")
        deny("/system/bin/ls", "/data")

        // Arbitrary env or cwd
        deny("id", cwd = "/data/local/tmp")
        deny("id", env = arrayOf("LD_PRELOAD=/tmp/evil.so"))
        deny(
            "/system/bin/app_process", "/system/bin", "com.kilon.agentvscreen.core.VScreenShellMain", "ime-hide", "2",
            env = arrayOf("CLASSPATH=/data/local/tmp/evil.apk"),
        )
        deny(
            "/system/bin/app_process", "/system/bin", "com.evil.Main", "ime-hide", "2",
            env = arrayOf("CLASSPATH=$ownApk"),
        )

        // Cat path traversal / arbitrary file read
        deny("cat", "/data/system/users/0/settings_secure.xml")
        deny("cat", "/proc/1234/environ")
        deny("cat", "/sys/fs/cgroup/../../etc/passwd/cgroup.events")

        // Main display protection
        deny("input", "-d", "0", "keyevent", "KEYCODE_WAKEUP")
        deny("input", "-d", "2", "tap", "100", "100")
        deny("wm", "size", "1080x2400", "-d", "0")
        deny("wm", "size", "reset")

        // Privilege escalation / protected packages
        deny("pm", "grant", "com.other.app", "android.permission.READ_SMS")
        deny("pm", "grant", ownPkg, "android.permission.WRITE_SECURE_SETTINGS")
        deny("pm", "install", "/data/local/tmp/evil.apk")
        deny("settings", "put", "global", "adb_enabled", "1")
        deny("am", "force-stop", ownPkg)
        deny("am", "force-stop", "com.android.systemui")
        deny("am", "force-stop", "--user")
        deny("am", "freeze", ownPkg)
        deny("am", "freeze", "system_server")
        deny("am", "start-foreground-service", "-n", "com.other.app/.Svc")
        deny("am", "start", "--display", "2", "-n", "$ownPkg/.ui.MainActivity")
        deny("am", "start", "--user", "10", "-n", "com.example.app/.Main")
        deny("dumpsys", "deviceidle", "whitelist", "+com.other.app")
        deny("cmd", "deviceidle", "tempwhitelist", "-d", "60000", "com.other.app")
        deny("cmd", "appops", "set", "com.other.app", "SYSTEM_ALERT_WINDOW", "allow")
        deny("cmd", "appops", "set", "com.foo;id", "PLAY_AUDIO", "ignore")
    }
}
