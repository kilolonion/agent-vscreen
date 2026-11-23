package com.kilon.agentvscreen.core

import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * [vscreen] 2.0: the separate download of the textless-element detector ([VScreenDetector]).
 *
 * The weights are AGPL-3.0 and do not ship in the (MIT) APK. A release is a folder with `manifest.json` (written by
 * tools/detector/train_colab.ipynb: each model's file, sha256, size, input size, threshold, license) and the model
 * files, mirrored on several hosts; the first host that answers is used, so a phone in mainland China that cannot
 * reach GitHub gets ModelScope. Every file is checked against the manifest's sha256 before it is used; a bad or
 * partial download is deleted, never loaded.
 *
 * Installed state is one small JSON next to the model (filesDir/detector/installed.json). Removing it (or the
 * model) turns the feature off; the app works the same without it.
 */
object DetectorModels {
    private const val TAG = "DetectorModels"
    private const val DIR = "detector"

    /**
     * Where a release lives, tried in order; each is a base URL that `manifest.json` and the files sit under. The
     * files are committed to the kilolonion/agent-vscreen-models repository (main) — no GitHub Release needed — and
     * jsDelivr serves the same files from a CDN that phones in mainland China usually reach when GitHub does not.
     */
    val SOURCES = listOf(
        "GitHub" to "https://raw.githubusercontent.com/kilolonion/agent-vscreen-models/main/",
        "jsDelivr" to "https://cdn.jsdelivr.net/gh/kilolonion/agent-vscreen-models@main/",
        "ModelScope" to "https://modelscope.cn/models/kilolonion/agent-vscreen-detector/resolve/master/",
        "Hugging Face" to "https://huggingface.co/kilolonion/agent-vscreen-detector/resolve/main/",
    )
    const val LICENSE = "AGPL-3.0"

    class Offer(val id: String, val file: String, val sha256: String, val bytes: Long, val inW: Int, val inH: Int,
                val threshold: Float, val default: Boolean, val hitRate: Double?, val source: String, val base: String)

    class Installed(val id: String, val file: File, val sha256: String, val inW: Int, val inH: Int, val threshold: Float, val source: String)

    @Volatile private var cache: Pair<Long, Installed?>? = null

    private fun dir(ctx: Context) = File(ctx.noBackupFilesDir ?: ctx.filesDir, DIR).apply { mkdirs() }

    /** The installed model, or null. Cheap (cached on the state file's time). */
    fun installed(ctx: Context): Installed? {
        val f = File(dir(ctx), "installed.json")
        val stamp = if (f.isFile) f.lastModified() else 0L
        cache?.let { if (it.first == stamp) return it.second }
        val v = if (stamp == 0L) null else runCatching {
            val o = JSONObject(f.readText())
            val file = File(dir(ctx), o.getString("file"))
            if (!file.isFile || file.length() != o.getLong("bytes")) null
            else Installed(o.getString("id"), file, o.getString("sha256"), o.getInt("w"), o.getInt("h"), o.getDouble("threshold").toFloat(), o.optString("source"))
        }.getOrNull()
        cache = stamp to v
        return v
    }

    /** Parse a release manifest (pure; see tools/detector/train_colab.ipynb step 8). */
    internal fun parseManifest(text: String, source: String, base: String): List<Offer> {
        val o = JSONObject(text)
        require(o.optString("kind") == "vscreen-detector") { "not a detector manifest" }
        val arr = o.getJSONArray("models")
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i); val inp = m.getJSONObject("input")
            val sha = m.getString("sha256").lowercase()
            require(Regex("^[0-9a-f]{64}$").matches(sha)) { "bad sha256" }
            val file = m.getString("file")
            require(Regex("^[A-Za-z0-9._-]{1,120}$").matches(file) && !file.startsWith(".")) { "bad file name" }
            Offer(m.getString("id"), file, sha, m.getLong("bytes"), inp.getInt("w"), inp.getInt("h"),
                m.optDouble("threshold", 0.25).toFloat(), m.optBoolean("default"), m.optJSONObject("val")?.optDouble("hit_rate"), source, base)
        }
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeoutMs; readTimeout = 30_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Agent-VScreen")
    }

    /** The offers of the first host that answers, with why the others did not. Network; call off the main thread. */
    fun fetchOffers(): Pair<List<Offer>, List<String>> {
        val errors = ArrayList<String>()
        for ((name, base) in SOURCES) {
            try {
                val c = open(base + "manifest.json", 6_000)
                try {
                    if (c.responseCode != 200) { errors += "$name: HTTP ${c.responseCode}"; continue }
                    val text = c.inputStream.use { it.readBytes() }.takeIf { it.size < 256 * 1024 }?.toString(Charsets.UTF_8)
                        ?: run { errors += "$name: manifest too large"; null } ?: continue
                    return parseManifest(text, name, base) to errors
                } finally { c.disconnect() }
            } catch (t: Throwable) { errors += "$name: ${t.javaClass.simpleName} ${t.message?.take(80).orEmpty()}" }
        }
        return emptyList<Offer>() to errors
    }

    /**
     * Download [offer], check its size and sha256, and install it (replacing any other model). [progress] gets
     * (bytes so far, total). Throws with a readable reason; nothing is installed on failure.
     */
    fun install(ctx: Context, offer: Offer, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val d = dir(ctx)
        val tmp = File(d, offer.file + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        val c = open(offer.base + offer.file, 10_000)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode} from ${offer.source}")
            var got = 0L
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        got += n
                        if (got > offer.bytes) error("larger than the manifest says (${offer.bytes} bytes)")
                        md.update(buf, 0, n); out.write(buf, 0, n); progress(got, offer.bytes)
                    }
                }
            }
            if (got != offer.bytes) error("incomplete: $got of ${offer.bytes} bytes")
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != offer.sha256) error("sha256 mismatch: got $sha")
            val dst = File(d, offer.file)
            if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
            d.listFiles()?.forEach { if (it.name != dst.name && it.name.endsWith(".onnx")) it.delete() }
            val state = JSONObject().put("id", offer.id).put("file", offer.file).put("sha256", offer.sha256).put("bytes", offer.bytes)
                .put("w", offer.inW).put("h", offer.inH).put("threshold", offer.threshold.toDouble()).put("source", offer.source)
                .put("license", LICENSE).put("at", System.currentTimeMillis())
            File(d, "installed.json.tmp").apply { writeText(state.toString()) }.renameTo(File(d, "installed.json"))
            cache = null
            VScreenDetector.reset(ctx)
            AppLogger.info(TAG, "installed ${offer.id} from ${offer.source}")
        } catch (t: Throwable) {
            tmp.delete(); throw t
        } finally { c.disconnect() }
    }

    fun remove(ctx: Context) {
        VScreenDetector.close()
        dir(ctx).listFiles()?.forEach { it.delete() }
        cache = null
        VScreenDetector.reset(ctx)
    }
}
