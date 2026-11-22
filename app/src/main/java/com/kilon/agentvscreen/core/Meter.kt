package com.kilon.agentvscreen.core

import android.content.Context
import android.graphics.BitmapFactory
import com.kilon.agentvscreen.api.ImagePolicy
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.cmd.ShotStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2.0.0-beta1: pure token and image cost meter for every command reply.
 * CJK characters (U+3000..U+9FFF, U+FF00..U+FFEF) count 1 token each; all other characters count
 * ceil(count / 4) tokens; each sent image counts ceil(w * h / 750) tokens.
 *
 * Note on [TaskLog] step metering vs live replies: `ApiServer` and `Mcp` meter the exact reply text
 * formatted for their transport, whereas [TaskLog] reconstructs text on its worker using a placeholder
 * `/v2/shot/<name>` URL (HTTP callers see the bound host URL; MCP callers receive inline image blocks
 * with `vscreen-shot://` lines stripped). The `chars` and `est_tokens` recorded in [TaskLog] are
 * therefore a transport-independent approximation within a few tokens of the wire reply.
 */
object Meter {
    const val CJK_LOW_START = 0x3000
    const val CJK_LOW_END = 0x9FFF
    const val CJK_FULL_START = 0xFF00
    const val CJK_FULL_END = 0xFFEF
    const val OTHER_CHARS_PER_TOKEN = 4
    const val PIXELS_PER_IMAGE_TOKEN = 750L

    data class Cost(
        val chars: Int,
        val estTokens: Int,
        val images: List<IntArray>,
        val imageTokens: Int,
    ) {
        fun imagesJson(): JSONArray = JSONArray(images.map { JSONArray().put(it[0]).put(it[1]) })
        fun json(): JSONObject = JSONObject()
            .put("chars", chars)
            .put("est_tokens", estTokens)
            .put("images", imagesJson())
            .put("image_tokens", imageTokens)
    }

    fun isCjk(cp: Int): Boolean =
        (cp in CJK_LOW_START..CJK_LOW_END) || (cp in CJK_FULL_START..CJK_FULL_END)

    fun of(text: String, images: List<IntArray>): Cost {
        var cjk = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (isCjk(cp)) cjk++ else other++
            i += Character.charCount(cp)
        }
        val otherTokens = (other + OTHER_CHARS_PER_TOKEN - 1) / OTHER_CHARS_PER_TOKEN
        var imgTokens = 0
        val cleanImages = ArrayList<IntArray>(images.size)
        for (dim in images) {
            if (dim.size >= 2 && dim[0] > 0 && dim[1] > 0) {
                val w = dim[0]
                val h = dim[1]
                cleanImages += intArrayOf(w, h)
                imgTokens += ((w.toLong() * h.toLong() + PIXELS_PER_IMAGE_TOKEN - 1L) / PIXELS_PER_IMAGE_TOKEN).toInt()
            }
        }
        return Cost(
            chars = text.length,
            estTokens = cjk + otherTokens,
            images = cleanImages,
            imageTokens = imgTokens,
        )
    }

    /**
     * Strictly read-only header lookup (`inJustDecodeBounds`) for the images sent to the caller.
     * Never calls [ImagePolicy.sendName] or [ImagePolicy.compact] (which may write files), avoiding
     * races between [TaskLog]'s background worker and `ApiServer` / `Mcp` serving the same shot:
     * when a compact copy is applicable and already exists on disk, its dimensions are read;
     * otherwise the original shot's dimensions are read.
     */
    fun sentDimensions(ctx: Context?, res: NativeOffloadResult?, sentShots: List<String>, fullSize: Boolean = false): List<IntArray> {
        if (ctx == null || sentShots.isEmpty()) return emptyList()
        val wantCompact = !fullSize && res != null && !ImagePolicy.asked(res)
        val out = ArrayList<IntArray>(sentShots.size)
        for (name in sentShots) {
            val f = if (wantCompact) {
                val cname = if (name.startsWith("vscreen_c_")) name else "vscreen_c" + name.removePrefix("vscreen")
                ShotStore.file(ctx, cname) ?: ShotStore.file(ctx, name)
            } else {
                ShotStore.file(ctx, name)
            } ?: continue
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) out += intArrayOf(opts.outWidth, opts.outHeight)
        }
        return out
    }
}
