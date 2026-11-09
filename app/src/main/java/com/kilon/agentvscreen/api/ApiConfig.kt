package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.tr

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/** Port, access token and turn length; everything a caller needs to connect, in one place. */
object ApiConfig {
    private const val PREFS = "agent_vscreen_api"
    const val DEFAULT_PORT = 18765
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("token", null).isNullOrEmpty()) prefs.edit().putString("token", newToken()).apply()
        Turns.quietMs = quietMinutes * 60_000L
    }

    val port: Int get() = prefs.getInt("port", DEFAULT_PORT)
    val token: String get() = prefs.getString("token", null) ?: ""
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** Minutes of silence after which a caller's turn is over (screen idles). */
    var quietMinutes: Int
        get() = prefs.getInt("quiet_min", 3)
        set(v) { prefs.edit().putInt("quiet_min", v.coerceIn(1, 60)).apply(); Turns.quietMs = v.coerceIn(1, 60) * 60_000L }

    /**
     * 1.1.6: how other devices reach this phone. OFF (default), BUILTIN (our own Tailscale node, no app needed) or
     * SYSTEM (the Tailscale app on this phone; the 1.1.1 mode). The old on/off switch maps to SYSTEM.
     */
    var tailnetMode: com.kilon.agentvscreen.host.Tailnet.Mode
        get() = prefs.getString("tailnet_mode", null)?.let { com.kilon.agentvscreen.host.Tailnet.Mode.of(it) }
            ?: if (prefs.getBoolean("tailnet", false)) com.kilon.agentvscreen.host.Tailnet.Mode.SYSTEM else com.kilon.agentvscreen.host.Tailnet.Mode.OFF
        set(v) { prefs.edit().putString("tailnet_mode", v.key).apply() }

    /** The system Tailscale app's address is also listened on (SYSTEM mode). */
    val tailnet: Boolean get() = tailnetMode == com.kilon.agentvscreen.host.Tailnet.Mode.SYSTEM

    /** Device name of the built-in node in the tailnet. */
    var tailnetHostname: String
        get() = prefs.getString("tailnet_host", null)?.takeIf { it.isNotBlank() } ?: "agent-vscreen"
        set(v) { prefs.edit().putString("tailnet_host", v.trim().lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').take(40)).apply() }

    /** Optional control server (Headscale); null = Tailscale's own. Stored normalized (see [normalizeControlUrl]). */
    var tailnetControlUrl: String?
        get() = normalizeControlUrl(prefs.getString("tailnet_control", null))
        set(v) { prefs.edit().putString("tailnet_control", normalizeControlUrl(v)).apply() }

    /**
     * 1.1.8: what a person types for a control server ("hs.example.com", "https://hs.example.com/") becomes the URL the
     * node needs. Without a scheme the node failed with "unsupported protocol scheme" and sat in NeedsLogin with no
     * hint. Tailscale's own server (or blank) is null.
     */
    fun normalizeControlUrl(v: String?): String? {
        var u = v?.trim()?.trimEnd('/') ?: return null
        if (u.isEmpty()) return null
        if (!u.contains("://")) u = "https://$u"
        val host = runCatching { java.net.URI(u).host }.getOrNull()?.lowercase()
        if (host == "controlplane.tailscale.com" || host == "login.tailscale.com") return null
        return u
    }

    /** Why [v] cannot be a control server address, or null when it can (blank = Tailscale's own, also fine). */
    fun controlUrlProblem(v: String): String? {
        val u = normalizeControlUrl(v) ?: return null
        val uri = runCatching { java.net.URI(u) }.getOrNull()
        return when {
            uri == null || uri.host.isNullOrBlank() -> tr("看不懂这个地址，应该像 https://hs.example.com", "Not an address; it should look like https://hs.example.com")
            uri.scheme != "https" && uri.scheme != "http" -> tr("只能是 https:// 或 http:// 开头", "It must start with https:// or http://")
            else -> null
        }
    }

    /** An auth key given once; dropped as soon as the node runs (the node's own state keeps it signed in). */
    var tailnetAuthKey: String?
        get() = prefs.getString("tailnet_authkey", null)?.takeIf { it.isNotBlank() }
        set(v) { prefs.edit().putString("tailnet_authkey", v?.trim()).apply() }

    fun setPort(p: Int) { prefs.edit().putInt("port", p.coerceIn(1024, 65535)).apply() }
    fun resetToken() { prefs.edit().putString("token", newToken()).apply() }

    /** One line that installs the `vscreen` command in Termux / a proot or chroot Linux / adb shell and stores the token. */
    /** 1.1.7: [base] is another address (Tailscale) when the agent runs on another device; the script then keeps it. */
    fun installLine(base: String = baseUrl): String =
        if (base == baseUrl) "curl -fsS $base/cli | sh -s -- install $token"
        else "curl -fsS $base/cli | VSCREEN_URL=$base sh -s -- install $token"

    /** MCP client config (streamable HTTP). */
    fun mcpJson(base: String = baseUrl): String = """{
  "mcpServers": {
    "agent-vscreen": {
      "type": "http",
      "url": "$base/mcp",
      "headers": { "Authorization": "Bearer $token" }
    }
  }
}"""

    private fun newToken(): String {
        val abc = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..24).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }
}
