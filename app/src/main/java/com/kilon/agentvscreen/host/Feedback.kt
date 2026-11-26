package com.kilon.agentvscreen.host

import android.net.Uri

/**
 * 2.0.1 (rainbow: 设置里加关于项目的链接；快捷反馈跳转邮箱或 GitHub，附脱敏数据).
 *
 * Diagnostics already leave out tokens and typed text. Feedback leaves the phone (a GitHub issue is public), so it is
 * redacted once more here, in one place: addresses, host names of tunnels / tailnets / the user's own domain, email
 * addresses and anything shaped like a key. The result keeps what helps with a bug (versions, device, flags, codes,
 * timings, log lines) and loses what says who or where the user is.
 */
object Feedback {
    const val REPO = "https://github.com/kilolonion/agent-vscreen"
    const val ISSUES_NEW = "$REPO/issues/new"
    /** Where "email feedback" goes; null until the maintainer sets one (the row is hidden while null). */
    val EMAIL: String? = null

    /** GitHub rejects very long issue URLs; the body in the URL is a summary, the full report goes to the clipboard. */
    private const val URL_BODY_MAX = 5_000

    private val IPV4 = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""")
    private val IPV6 = Regex("""\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\b""")
    private val EMAIL_ADDR = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    /** Host in a URL (scheme://host), except the project's own and well-known public hosts. */
    private val URL_HOST = Regex("""(?i)\b(https?|wss?)://([^/\s:?#"']+)""")
    private val KEEP_HOSTS = setOf("github.com", "api.github.com", "shizuku.rikka.app", "developers.cloudflare.com", "tailscale.com", "ngrok.com", "dashboard.ngrok.com")
    /** Bare host names of tailnets and tunnels (also outside URLs). */
    private val TUNNEL_HOST = Regex("""(?i)\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:ts\.net|trycloudflare\.com|ngrok(?:-free)?\.(?:app|dev|io)|cfargotunnel\.com)\b""")
    /** Long runs of key-like characters (tokens, secrets, ids): 24+ chars mixing letters and digits. */
    private val KEYLIKE = Regex("""\b(?=[A-Za-z0-9_\-]*\d)(?=[A-Za-z0-9_\-]*[A-Za-z])[A-Za-z0-9_\-]{24,}\b""")
    /** A hex digest (sha-256 of the APK, certificate) is not a secret; keep 64-hex as is. */
    private val HEX64 = Regex("""^[0-9a-f]{64}$""")

    /** [text] without addresses, private host names, email addresses and key-like strings. [extraHosts]: the user's own domains. */
    fun redact(text: String, extraHosts: Collection<String> = emptyList()): String {
        var s = text
        extraHosts.filter { it.isNotBlank() }.forEach { h -> s = s.replace(h, "<domain>", ignoreCase = true) }
        s = EMAIL_ADDR.replace(s, "<email>")
        s = URL_HOST.replace(s) { m ->
            val host = m.groupValues[2].lowercase()
            if (host in KEEP_HOSTS || host == "localhost" || host == "127.0.0.1") m.value else "${m.groupValues[1]}://<host>"
        }
        s = TUNNEL_HOST.replace(s, "<host>")
        s = IPV4.replace(s) { m -> if (m.value == "127.0.0.1" || m.value == "0.0.0.0") m.value else "<ip>" }
        s = IPV6.replace(s, "<ip>")
        s = KEYLIKE.replace(s) { m -> if (HEX64.matches(m.value)) m.value else "<redacted>" }
        return s
    }

    /** The user's own public host names (Cloudflare route, ngrok domain): always masked, wherever they appear. */
    fun userHosts(): List<String> = listOfNotNull(Cloudflared.config.value.hostname, Ngrok.config.value.domain)
        .map { it.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') }.filter { it.contains('.') }

    /** The first lines of a redacted report (versions, device, state), cut to fit in an issue URL. */
    fun summary(redacted: String): String {
        val head = redacted.lineSequence().takeWhile { it.isNotBlank() }.joinToString("\n")
        return (head.ifEmpty { redacted }).take(URL_BODY_MAX)
    }

    /** A new-issue link with [title] and a body holding the summary in a code block. */
    fun issueUrl(title: String, intro: String, redacted: String): String {
        val body = "$intro\n\n```\n${summary(redacted)}\n```\n"
        return Uri.parse(ISSUES_NEW).buildUpon().appendQueryParameter("title", title).appendQueryParameter("body", body).build().toString()
    }
}
