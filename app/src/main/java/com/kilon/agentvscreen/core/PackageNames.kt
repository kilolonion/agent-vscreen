package com.kilon.agentvscreen.core

/**
 * Strict validation for Android package names and application process names passed to privileged
 * shell commands or read from shared `/data/local/tmp` state files.
 */
object PackageNames {
    const val MAX_LENGTH = 128
    const val MAX_PROCESS_LENGTH = 192

    private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val PROCESS_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+(:[A-Za-z0-9_.-]+)?$")

    fun isValid(pkg: String): Boolean =
        pkg.length in 3..MAX_LENGTH && PACKAGE_RE.matches(pkg)

    fun isValidProcess(proc: String): Boolean =
        proc.length in 3..MAX_PROCESS_LENGTH && PROCESS_RE.matches(proc)

    fun requireValid(pkg: String): String {
        require(isValid(pkg)) { "Invalid package name: ${pkg.take(64)}" }
        return pkg
    }
}
