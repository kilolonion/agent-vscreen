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

    /** Dimensions [w, h] of the actual files sent to the caller after [ImagePolicy], reading headers only. */
    fun sentDimensions(ctx: Context?, res: NativeOffloadResult?, sentShots: List<String>, fullSize: Boolean = false): List<IntArray> {
        if (ctx == null || sentShots.isEmpty()) return emptyList()
        val out = ArrayList<IntArray>(sentShots.size)
        for (name in sentShots) {
            val actual = if (fullSize || res == null) name else ImagePolicy.sendName(ctx, res, name)
            val f = ShotStore.file(ctx, actual) ?: ShotStore.file(ctx, name) ?: continue
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) out += intArrayOf(opts.outWidth, opts.outHeight)
        }
        return out
    }
}
