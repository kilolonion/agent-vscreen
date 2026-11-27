package com.kilon.agentvscreen.core

/**
 * Pure JVM-testable command allowlist enforced by [VScreenDisplayService.EXEC].
 *
 * Rejects every shell interpreter (`sh`, `su`, `bash`, `toybox`, etc.), arbitrary `cwd`, arbitrary `env`,
 * and any command/subcommand outside the explicit privileged operations required by Agent-VScreen.
 */
object ExecAllowlist {
    private val FORBIDDEN_BINS = setOf(
        "sh", "/system/bin/sh", "su", "/system/bin/su", "bash", "ash", "dash", "zsh", "toybox", "busybox",
    )
    private val PROP_RE = Regex("^[A-Za-z0-9._-]+$")
    private val SETTING_KEY_RE = Regex("^[A-Za-z0-9._-]+$")
    private val SIMPLE_TOKEN_RE = Regex("^[A-Za-z0-9_.-]+$")
    private val SIZE_RE = Regex("^\\d+x\\d+$")
    private val PROC_NET_PATHS = setOf("/proc/net/tcp", "/proc/net/tcp6")
    private val PROC_PID_FILE_RE = Regex("^/proc/\\d+/(cgroup|wchan)$")
    private val CGROUP_UID_DIR_RE = Regex("^/sys/fs/cgroup/uid_\\d+$")
    private val CGROUP_EVENTS_RE = Regex("^/sys/fs/cgroup/[A-Za-z0-9_./:-]+/cgroup\\.events$")
    private val READONLY_DUMPSYS = setOf(
        "audio", "activity", "window", "display", "package", "power",
        "media_session", "input_method", "SurfaceFlinger",
    )
    private val SETTINGS_NS = setOf("secure", "global", "system")
    private val OWN_APPOPS = setOf("SYSTEM_ALERT_WINDOW", "RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND")
    private val PROTECTED_STOP_PKGS = setOf("android", "com.android.systemui", "system_server")

    /**
     * Returns `null` when the invocation is permitted, or a concise denial reason otherwise.
     */
    fun check(
        argv: Array<String>,
        env: Array<String>?,
        cwd: String?,
        ownPkg: String,
        ownSourceDir: String? = null,
    ): String? {
        if (argv.isEmpty() || argv[0].isBlank()) return "empty argv"
        if (argv.any { it.contains('\u0000') || it.contains('\n') || it.contains('\r') }) {
            return "control character in argv"
        }
        if (cwd != null) return "cwd not allowed"

        val bin = argv[0]
        if (bin in FORBIDDEN_BINS || bin.endsWith("/sh") || bin.endsWith("/su")) {
            return "shell interpreter forbidden: $bin"
        }
        if ('/' in bin && bin != "/system/bin/app_process") {
            return "absolute binary path not allowed: $bin"
        }

        if (env != null && env.isNotEmpty()) {
            if (bin != "/system/bin/app_process") return "env not allowed for $bin"
            if (env.size != 1 || ownSourceDir.isNullOrBlank() || env[0] != "CLASSPATH=$ownSourceDir") {
                return "env restricted to own CLASSPATH for app_process"
            }
        }

        return when (bin) {
            "/system/bin/app_process" -> checkAppProcess(argv, env, ownSourceDir)
            "id" -> if (argv.size == 1) null else "id takes no arguments"
            "getprop" -> if (argv.size == 2 && argv[1].length <= 128 && PROP_RE.matches(argv[1])) null else "invalid getprop args"
            "service" -> if (argv.size == 2 && argv[1] == "list") null else "only 'service list' is allowed"
            "pidof" -> if (argv.size >= 2 && argv.drop(1).all { PackageNames.isValidProcess(it) }) null else "invalid pidof target"
            "ps" -> checkPs(argv)
            "ls" -> checkLs(argv)
            "cat" -> checkCat(argv)
            "input" -> checkInput(argv)
            "wm" -> checkWm(argv)
            "dumpsys" -> checkDumpsys(argv, ownPkg)
            "settings" -> checkSettings(argv)
            "appops" -> checkAppops(argv, ownPkg)
            "pm" -> checkPm(argv, ownPkg)
            "cmd" -> checkCmd(argv, ownPkg)
            "am" -> checkAm(argv, ownPkg)
            else -> "binary not in allowlist: $bin"
        }
    }

    private fun checkAppProcess(argv: Array<String>, env: Array<String>?, ownSourceDir: String?): String? {
        if (env == null || env.size != 1 || ownSourceDir.isNullOrBlank() || env[0] != "CLASSPATH=$ownSourceDir") {
            return "app_process requires exact own CLASSPATH env"
        }
        if (argv.size == 5 &&
            argv[1] == "/system/bin" &&
            argv[2] == "com.kilon.agentvscreen.core.VScreenShellMain" &&
            argv[3] == "ime-hide" &&
            isPositiveInt(argv[4])
        ) return null
        return "app_process only permitted for VScreenShellMain ime-hide"
    }

    private fun checkPs(argv: Array<String>): String? = when {
        argv.contentEquals(arrayOf("ps", "-A", "-o", "NAME")) -> null
        argv.contentEquals(arrayOf("ps", "-A", "-o", "WCHAN:32,NAME")) -> null
        else -> "disallowed ps arguments"
    }

    private fun checkLs(argv: Array<String>): String? {
        if (argv.size == 3 && (argv[1] == "-1" || argv[1] == "-1d") && CGROUP_UID_DIR_RE.matches(argv[2])) return null
        return "disallowed ls arguments"
    }

    private fun checkCat(argv: Array<String>): String? {
        if (argv.size != 2) return "cat requires exactly 1 path"
        val path = argv[1]
        if (path in PROC_NET_PATHS) return null
        if (PROC_PID_FILE_RE.matches(path)) return null
        if (!path.contains("..") && CGROUP_EVENTS_RE.matches(path)) return null
        return "cat path not allowed: $path"
    }

    private fun checkInput(argv: Array<String>): String? {
        if (argv.size == 5 &&
            argv[1] == "-d" && isPositiveInt(argv[2]) &&
            argv[3] == "keyevent" && argv[4] == "KEYCODE_WAKEUP"
        ) return null
        return "input only permitted for KEYCODE_WAKEUP on a non-0 display"
    }

    private fun checkWm(argv: Array<String>): String? {
        if (argv.size < 2) return "wm requires subcommand"
        val sub = argv[1]
        if (sub != "size" && sub != "density") return "wm subcommand not allowed: $sub"
        if (argv.size == 2) return null
        // Any wm mutation or display-scoped query must specify -d <positive displayId> (never display 0).
        val dIdx = argv.indexOf("-d")
        if (dIdx < 2 || dIdx != argv.lastIndex - 1 || !isPositiveInt(argv[dIdx + 1])) {
            return "wm requires -d <displayId> (>0)"
        }
        val middle = argv.slice(2 until dIdx)
        return when (middle.size) {
            0 -> null
            1 -> {
                val arg = middle[0]
                if (arg == "reset" || (sub == "size" && SIZE_RE.matches(arg)) || (sub == "density" && isPositiveInt(arg))) null
                else "invalid wm argument: $arg"
            }
            else -> "too many wm arguments"
        }
    }

    private fun checkDumpsys(argv: Array<String>, ownPkg: String): String? {
        if (argv.size < 2) return "dumpsys requires service name"
        val svc = argv[1]
        if (svc == "deviceidle") {
            if (argv.size == 4 && argv[2] == "whitelist" && ownPkg.isNotBlank() && argv[3] == "+$ownPkg") return null
            return "dumpsys deviceidle only allowed for own whitelist"
        }
        if (svc !in READONLY_DUMPSYS) return "dumpsys service not allowed: $svc"
        if (argv.size > 4) return "too many dumpsys arguments"
        if (argv.drop(2).any { !SIMPLE_TOKEN_RE.matches(it) || it.startsWith("-") }) {
            return "disallowed dumpsys sub-argument"
        }
        return null
    }

    private fun checkSettings(argv: Array<String>): String? {
        if (argv.size < 3) return "settings requires subcommand and namespace"
        val sub = argv[1]
        val ns = argv[2]
        if (ns !in SETTINGS_NS) return "invalid settings namespace: $ns"
        return when (sub) {
            "list" -> if (argv.size == 3) null else "settings list takes no extra args"
            "get" -> if (argv.size == 4 && argv[3].length <= 128 && SETTING_KEY_RE.matches(argv[3])) null else "invalid settings get key"
            "delete" -> if (argv.size == 4 && ns == "secure" && argv[3] == "enabled_accessibility_services") null else "settings delete not allowed"
            "put" -> {
                if (argv.size != 5) return "settings put requires key and value"
                val key = argv[3]
                val allowed = (ns == "secure" && (key == "enabled_accessibility_services" || key == "accessibility_enabled")) ||
                    (ns == "global" && key == "stay_on_while_plugged_in")
                if (allowed) null else "settings put key not allowed: $ns/$key"
            }
            else -> "settings subcommand not allowed: $sub"
        }
    }

    private fun checkAppops(argv: Array<String>, ownPkg: String): String? {
        if (argv.size == 5 &&
            argv[1] == "set" &&
            ownPkg.isNotBlank() && argv[2] == ownPkg &&
            argv[3] in OWN_APPOPS &&
            argv[4] == "allow"
        ) return null
        return "appops only allowed for own background/overlay ops"
    }

    private fun checkPm(argv: Array<String>, ownPkg: String): String? {
        if (argv.size < 2) return "pm requires subcommand"
        return when (argv[1]) {
            "list" -> checkPackageList(argv, startIdx = 2)
            "path" -> if (argv.size == 3 && PackageNames.isValid(argv[2])) null else "pm path requires valid package"
            "grant" -> if (argv.size == 4 && ownPkg.isNotBlank() && argv[2] == ownPkg && argv[3] == "android.permission.POST_NOTIFICATIONS") {
                null
            } else {
                "pm grant restricted to own package POST_NOTIFICATIONS"
            }
            else -> "pm subcommand not allowed: ${argv[1]}"
        }
    }

    private fun checkCmd(argv: Array<String>, ownPkg: String): String? {
        if (argv.size < 3) return "cmd requires service and subcommand"
        return when (argv[1]) {
            "appops" -> checkCmdAppops(argv, ownPkg)
            "package" -> checkCmdPackage(argv)
            "game" -> checkCmdGame(argv)
            "deviceidle" -> {
                if (argv.size == 6 && argv[2] == "tempwhitelist" && argv[3] == "-d" &&
                    isPositiveInt(argv[4]) && ownPkg.isNotBlank() && argv[5] == ownPkg
                ) null else "cmd deviceidle restricted to own tempwhitelist"
            }
            "netpolicy" -> {
                if (argv.size == 5 && argv[2] == "add" && argv[3] == "restrict-background-whitelist" && isNonNegInt(argv[4])) {
                    null
                } else {
                    "cmd netpolicy restricted to restrict-background-whitelist"
                }
            }
            "app_function" -> checkCmdAppFunction(argv)
            else -> "cmd service not allowed: ${argv[1]}"
        }
    }

    private fun checkCmdAppops(argv: Array<String>, ownPkg: String): String? {
        val action = argv[2]
        if (action != "get" && action != "set") return "cmd appops action not allowed: $action"
        var i = 3
        var hasUser = false
        if (i + 1 < argv.size && argv[i] == "--user") {
            if (!isNonNegInt(argv[i + 1])) return "invalid --user"
            hasUser = true
            i += 2
        }
        if (action == "get") {
            if (i + 1 == argv.lastIndex && PackageNames.isValid(argv[i]) && argv[i + 1] == "PLAY_AUDIO") return null
            return "invalid cmd appops get arguments"
        }
        // action == "set"
        if (i < argv.size && argv[i] == "--uid") {
            if (i + 3 != argv.lastIndex) return "invalid cmd appops set --uid arguments"
            val target = argv[i + 1]
            val op = argv[i + 2]
            val mode = argv[i + 3]
            val targetOk = isNonNegInt(target) || PackageNames.isValid(target)
            if (targetOk && op == "PLAY_AUDIO" && (mode == "allow" || mode == "ignore")) return null
            return "disallowed cmd appops set --uid"
        }
        if (i + 2 != argv.lastIndex) return "invalid cmd appops set arguments"
        val pkg = argv[i]
        val op = argv[i + 1]
        val mode = argv[i + 2]
        if (op == "PLAY_AUDIO" && PackageNames.isValid(pkg) && (mode == "allow" || mode == "ignore")) return null
        if (!hasUser && ownPkg.isNotBlank() && pkg == ownPkg && op in OWN_APPOPS && mode == "allow") return null
        return "disallowed cmd appops set"
    }

    private fun checkCmdPackage(argv: Array<String>): String? = when (argv[2]) {
        "list" -> checkPackageList(argv, startIdx = 3)
        "path" -> if (argv.size == 4 && PackageNames.isValid(argv[3])) null else "cmd package path requires valid package"
        "resolve-activity", "query-activities" -> {
            // Allowed flags: --brief, -a <action>, -c <category>, plus optional trailing valid package name.
            var i = 3
            var trailingPkg: String? = null
            while (i < argv.size) {
                when (argv[i]) {
                    "--brief" -> i++
                    "-a", "-c" -> {
                        if (i + 1 >= argv.size || !PROP_RE.matches(argv[i + 1])) return "invalid intent filter flag"
                        i += 2
                    }
                    else -> {
                        if (i == argv.lastIndex && PackageNames.isValid(argv[i]) && trailingPkg == null) {
                            trailingPkg = argv[i]
                            i++
                        } else {
                            return "disallowed cmd package ${argv[2]} arg: ${argv[i]}"
                        }
                    }
                }
            }
            null
        }
        else -> "cmd package subcommand not allowed: ${argv[2]}"
    }

    private fun checkPackageList(argv: Array<String>, startIdx: Int): String? {
        if (startIdx >= argv.size) return "missing list target"
        val what = argv[startIdx]
        if (what == "users") return if (argv.size == startIdx + 1) null else "list users takes no extra args"
        if (what != "packages") return "disallowed package list target: $what"
        var i = startIdx + 1
        var hasFilter = false
        while (i < argv.size) {
            when (argv[i]) {
                "-U" -> i++
                "--user", "--uid" -> {
                    if (i + 1 >= argv.size || !isNonNegInt(argv[i + 1])) return "invalid ${argv[i]} value"
                    i += 2
                }
                else -> {
                    if (i == argv.lastIndex && PackageNames.isValid(argv[i]) && !hasFilter) {
                        hasFilter = true
                        i++
                    } else {
                        return "disallowed package list arg: ${argv[i]}"
                    }
                }
            }
        }
        return null
    }

    private fun checkCmdGame(argv: Array<String>): String? = when (argv[2]) {
        "set" -> {
            if (argv.size == 6 && argv[3] == "--downscale" && argv[4] == "disable" && PackageNames.isValid(argv[5])) null
            else "cmd game set restricted to --downscale disable <pkg>"
        }
        "reset" -> {
            if (argv.size == 4 && PackageNames.isValid(argv[3])) null
            else "cmd game reset requires valid package"
        }
        else -> "cmd game subcommand not allowed: ${argv[2]}"
    }

    private fun checkCmdAppFunction(argv: Array<String>): String? {
        val sub = argv[2]
        if (sub != "list-app-functions" && sub != "execute-app-function") {
            return "cmd app_function subcommand not allowed: $sub"
        }
        val pkgIdx = argv.indexOf("--package")
        if (pkgIdx < 3 || pkgIdx + 1 >= argv.size || !PackageNames.isValid(argv[pkgIdx + 1])) {
            return "cmd app_function requires valid --package"
        }
        return null
    }

    private fun checkAm(argv: Array<String>, ownPkg: String): String? {
        if (argv.size < 2) return "am requires subcommand"
        return when (argv[1]) {
            "help" -> if (argv.size == 2) null else "am help takes no arguments"
            "force-stop" -> {
                if (argv.size != 3) return "am force-stop requires exactly 1 package"
                val pkg = argv[2]
                if (!PackageNames.isValid(pkg)) return "invalid package for force-stop"
                if (pkg == ownPkg || pkg in PROTECTED_STOP_PKGS) return "protected package cannot be force-stopped"
                null
            }
            "compat" -> {
                if (argv.size == 5 && (argv[2] == "disable" || argv[2] == "reset") &&
                    argv[3] == "DOWNSCALED" && PackageNames.isValid(argv[4])
                ) null else "invalid am compat arguments"
            }
            "freeze", "unfreeze" -> {
                val proc = when {
                    argv.size == 3 -> argv[2]
                    argv.size == 4 && argv[2] == "--sticky" -> argv[3]
                    else -> return "invalid am ${argv[1]} arguments"
                }
                if (!PackageNames.isValidProcess(proc)) return "invalid process name for am ${argv[1]}"
                val base = proc.substringBefore(':')
                if (argv[1] == "freeze" && (base == ownPkg || base in PROTECTED_STOP_PKGS || base in com.kilon.agentvscreen.host.ShizukuBackend.KNOWN_MANAGER_PACKAGES)) {
                    return "protected process cannot be frozen"
                }
                null
            }
            "start-foreground-service" -> {
                if (argv.size == 4 && argv[2] == "-n" && ownPkg.isNotBlank() &&
                    argv[3] == "$ownPkg/com.kilon.agentvscreen.host.HostService"
                ) null else "am start-foreground-service restricted to own HostService"
            }
            "set-standby-bucket" -> {
                if (argv.size == 4 && ownPkg.isNotBlank() && argv[2] == ownPkg && argv[3] == "active") null
                else "am set-standby-bucket restricted to own package active"
            }
            "display" -> {
                if (argv.size == 5 && argv[2] == "move-stack" && isNonNegInt(argv[3]) && isNonNegInt(argv[4])) null
                else "am display restricted to move-stack <taskId> <displayId>"
            }
            "start" -> checkAmStart(argv, ownPkg)
            else -> "am subcommand not allowed: ${argv[1]}"
        }
    }

    private fun checkAmStart(argv: Array<String>, ownPkg: String): String? {
        if (argv.size < 3) return "am start requires arguments"
        var displayId: Int? = null
        var component: String? = null
        var i = 2
        while (i < argv.size) {
            when (val a = argv[i]) {
                "-W", "-S" -> i++
                "--display" -> {
                    val v = argv.getOrNull(i + 1)?.toIntOrNull() ?: return "invalid --display"
                    if (v < 0) return "negative --display"
                    displayId = v
                    i += 2
                }
                "--windowingMode" -> {
                    if (argv.getOrNull(i + 1)?.toIntOrNull() == null) return "invalid --windowingMode"
                    i += 2
                }
                "-f" -> {
                    val v = argv.getOrNull(i + 1) ?: return "missing -f value"
                    if (!Regex("^(0x[0-9a-fA-F]+|\\d+)$").matches(v)) return "invalid -f flags"
                    i += 2
                }
                "-n" -> {
                    val v = argv.getOrNull(i + 1) ?: return "missing -n component"
                    val slash = v.indexOf('/')
                    if (slash <= 0 || !PackageNames.isValid(v.substring(0, slash))) return "invalid -n component"
                    component = v
                    i += 2
                }
                "-a", "-c", "-d", "-t", "-p" -> {
                    if (i + 1 >= argv.size || argv[i + 1].isBlank()) return "missing $a value"
                    if (a == "-p" && !PackageNames.isValid(argv[i + 1])) return "invalid -p package"
                    i += 2
                }
                "--es", "--ez", "--ei", "--el", "--ef" -> {
                    if (i + 2 >= argv.size) return "incomplete extra $a"
                    i += 3
                }
                "--user" -> return "--user not allowed in am start"
                else -> return "disallowed am start option: $a"
            }
        }
        if (displayId != null && displayId > 0 && ownPkg.isNotBlank()) {
            if (component?.substringBefore('/') == ownPkg) return "cannot start own package on non-0 display"
        }
        return null
    }

    private fun isNonNegInt(s: String): Boolean = s.isNotEmpty() && s.length <= 10 && s.all { it.isDigit() }
    private fun isPositiveInt(s: String): Boolean = isNonNegInt(s) && (s.toIntOrNull() ?: 0) > 0
}
