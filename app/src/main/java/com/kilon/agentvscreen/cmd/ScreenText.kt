package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n

/**
 * Prompt-injection boundary sanitizer and untrusted-data framing for text sourced from third-party
 * apps (accessibility trees, OCR, system Toasts, input readbacks, APK labels, page titles).
 *
 * Pure Kotlin helper with zero Android dependencies.
 */
object ScreenText {
    const val FENCE_OPEN = "<screen-data>"
    const val FENCE_CLOSE = "</screen-data>"

    // ICU-safe regexes (no bare `{` or `}`).
    private val MULTI_SPACE = Regex("\\s+")
    private val FENCE_TAG = Regex("</?\\s*screen-data\\s*>", RegexOption.IGNORE_CASE)

    /**
     * Sanitize untrusted third-party text into a single-line plain string:
     * - Replaces line breaks (`\r`, `\n`, `\t`, U+0085, U+2028, U+2029) with spaces so third-party
     *   text can never forge block headers (`【注意】`, `[Notes]`, `【等用户】`) on a new line.
     * - Strips C0/C1 control characters and Unicode Bidi / directional override characters
     *   (`U+202A..202E`, `U+2066..2069`, `U+200E..200F`, `U+061C`).
     * - Neutralizes embedded `<screen-data>` / `</screen-data>` fence breakouts.
     * - Neutralizes fullwidth block header brackets (`【` / `】`) to `[` / `]`.
     * - Collapses consecutive whitespace and optionally truncates to [maxLen] characters.
     */
    fun clean(raw: String, maxLen: Int = 80): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val code = ch.code
            when {
                ch == '\r' || ch == '\n' || ch == '\t' || code == 0x0085 || code == 0x2028 || code == 0x2029 -> sb.append(' ')
                code in 0x0000..0x001F || code in 0x007F..0x009F -> Unit
                code in 0x202A..0x202E || code in 0x2066..0x2069 || code in 0x200E..0x200F || code == 0x061C -> Unit
                ch == '【' -> sb.append('[')
                ch == '】' -> sb.append(']')
                else -> sb.append(ch)
            }
        }
        val defenced = FENCE_TAG.replace(sb.toString(), "[screen-data]")
        val collapsed = MULTI_SPACE.replace(defenced, " ").trim()
        if (maxLen <= 0 || collapsed.length <= maxLen) return collapsed
        return collapsed.take(maxLen) + "…"
    }

    /**
     * Clean [raw], escape internal quotes so untrusted text cannot break out of quoted literals,
     * and wrap in language-appropriate boundary quotes (`“...”` in ZH, `"..."` in EN).
     */
    fun quote(raw: String, maxLen: Int = 80): String {
        val cleaned = clean(raw, maxLen)
            .replace("\"", "'")
            .replace('“', '「')
            .replace('”', '」')
        return if (I18n.en) "\"$cleaned\"" else "“$cleaned”"
    }

    /** Wrap non-empty untrusted screen-data lines in `<screen-data>` … `</screen-data>`. */
    fun fence(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<String>(lines.size + 2)
        out += FENCE_OPEN
        out.addAll(lines)
        out += FENCE_CLOSE
        return out
    }
}
