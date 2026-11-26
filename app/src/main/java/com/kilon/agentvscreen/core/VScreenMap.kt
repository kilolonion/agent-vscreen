package com.kilon.agentvscreen.core

import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [vscreen] v49: what the agent wrote down about one app, and nothing it did not write.
 *
 *  - notes     lessons in the agent's words (`note <文字>`), shown when the app is launched;
 *  - segments  saved `act` step lists (`act … --save NAME`, replayed with the step `use NAME`);
 *  - profile   how to operate the app, set explicitly: `before` / `after` (steps every act stretch runs first / last:
 *              resume and pause a moving app, close a popup shown on every visit, …), `orientation`
 *              (how the app really ran, learned at launch when its manifest declares nothing), `record`.
 *              1.2: `resume` / `pause` of older files are read as `before` / `after`; the `realtime` flag is gone
 *              (`act --fast` says it per stretch).
 *
 * 1.2: a note carries who wrote it (the token's name) and the app's version at that time, so a reader can tell a
 * lesson about an old version, or another agent's guess, from its own fresh observation.
 *
 * Nothing here is learned behind the agent's back except the orientation (a fact the launch measures).
 * Screens, ways, rules, pause-key colours and page names of earlier builds are not read any more.
 * One JSON file per app under files/vscreen/maps.
 */
internal object VScreenMap {
    private const val TAG = "VScreenMap"
    const val MAX_NOTES = 30
    const val MAX_SEGMENTS = 30

    class Note(val id: Int, val text: String, val at: Long, val by: String? = null, val appVersion: String? = null) {
        fun json(): JSONObject = JSONObject().put("id", id).put("text", text).put("at", at)
            .also { o -> by?.let { o.put("by", it) }; appVersion?.let { o.put("app_version", it) } }
    }

    /** [start]: 1.4.0-beta4, a taught lesson's starting page (Teach.startJson); null for agent-saved segments and older lessons. */
    /** [by]: 1.4.0-beta7, the agent (token id) that saved it or asked for the lesson; lessons are shared, the source is said. */
    class Segment(val name: String, val steps: String, val at: Long, var uses: Int, val start: JSONObject? = null, val by: String? = null,
                  /** 1.4.0-beta13 (test B8): the user showed it (a lesson), as opposed to an agent writing it with note save. */
                  val taught: Boolean = false,
                  /** 1.4.0-beta13 (test B6): the teach request it answered ("what:name"), so the same teach finds it again. */
                  val asked: String? = null,
                  /** 2.0.0-beta16 (Item 4): epoch ms when this segment was last replayed via `use`. */
                  var lastUsedAt: Long = 0L) {
        fun json(): JSONObject = JSONObject().put("name", name).put("steps", steps).put("at", at).put("uses", uses).also { o -> start?.let { o.put("start", it) }; by?.let { o.put("by", it) }
            if (taught) o.put("taught", true); asked?.let { o.put("asked", it) }; if (lastUsedAt > 0L) o.put("last_used_at", lastUsedAt) }
    }

    class AppMap(val pkg: String) {
        val notes = ArrayList<Note>()
        val segments = ArrayList<Segment>()
        val profile = LinkedHashMap<String, String>()
        var seq = 0
        fun empty() = notes.isEmpty() && segments.isEmpty() && profile.isEmpty()
    }

    @Volatile private var appContext: Context? = null
    private val cache = HashMap<String, AppMap>()
    private val filePkgCache = HashMap<String, Pair<Long, String>>()

    fun init(context: Context) { if (appContext == null) appContext = context.applicationContext }

    private fun dir(): File? = appContext?.let { File(File(it.filesDir, "vscreen"), "maps").apply { mkdirs() } }
    private fun file(pkg: String): File? = dir()?.let { File(it, VScreenStore.safeName(pkg) + ".json") }
    private fun arr(o: JSONObject, k: String): List<JSONObject> = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    @Synchronized fun load(pkg: String): AppMap = cache[pkg] ?: read(pkg).also { cache[pkg] = it }

    private fun read(pkg: String): AppMap {
        val m = AppMap(pkg)
        val f = file(pkg)
        if (f != null && f.isFile) runCatching {
            val o = JSONObject(f.readText())
            m.seq = o.optInt("seq")
            arr(o, "notes").forEach { n -> m.notes += Note(n.getInt("id"), n.getString("text"), n.optLong("at"), n.optString("by").ifEmpty { null }, n.optString("app_version").ifEmpty { null }) }
            arr(o, "segments").forEach { s -> m.segments += Segment(s.getString("name"), s.getString("steps"), s.optLong("at"), s.optInt("uses"), s.optJSONObject("start"), s.optString("by").takeIf { it.isNotEmpty() },
                s.optBoolean("taught"), s.optString("asked").takeIf { it.isNotEmpty() }, s.optLong("last_used_at", 0L)) }
            o.optJSONObject("profile")?.let { p -> p.keys().forEach { k0 ->
                val k = LEGACY_KEYS[k0] ?: k0
                PROFILE_KEYS[k]?.invoke(p.optString(k0))?.let { v -> if (k !in m.profile || k == k0) m.profile[k] = v } } }
            if (m.seq < (m.notes.maxOfOrNull { it.id } ?: 0)) m.seq = m.notes.maxOf { it.id }
        }.onFailure { AppLogger.warning(TAG, "load($pkg): ${it.message}") }
        return m
    }

    @Synchronized fun save(m: AppMap) {
        val f = file(m.pkg) ?: return
        runCatching {
            if (m.empty()) { f.delete(); filePkgCache.remove(f.name); return }
            f.writeText(JSONObject().put("package", m.pkg).put("seq", m.seq)
                .put("notes", JSONArray(m.notes.map { it.json() }))
                .put("segments", JSONArray(m.segments.map { it.json() }))
                .put("profile", JSONObject(m.profile as Map<*, *>)).toString(1))
            filePkgCache[f.name] = f.lastModified() to m.pkg
        }.onFailure { AppLogger.warning(TAG, "save(${m.pkg}): ${it.message}") }
    }

    /** Every app with something written down. */
    @Synchronized fun packages(): List<String> = knownPkgs().filter { !load(it).empty() }

    // ── notes ────────────────────────────────────────────────────────

    @Synchronized fun notes(pkg: String): List<Note> = load(pkg).notes.toList()

    /** Add a note; the same text again only refreshes its time. Oldest dropped past [MAX_NOTES]. */
    @Synchronized fun addNote(pkg: String, text: String, by: String? = null, appVersion: String? = null): Note {
        val m = load(pkg)
        val t = text.trim().take(400)
        m.notes.firstOrNull { it.text == t }?.let { old ->
            m.notes.remove(old); val n = Note(old.id, t, System.currentTimeMillis(), by, appVersion); m.notes += n; save(m); return n
        }
        val n = Note(++m.seq, t, System.currentTimeMillis(), by, appVersion)
        m.notes += n
        while (m.notes.size > MAX_NOTES) m.notes.removeAt(0)
        save(m)
        return n
    }

    @Synchronized fun deleteNote(pkg: String, id: Int): Boolean {
        val m = load(pkg)
        return m.notes.removeAll { it.id == id }.also { if (it) save(m) }
    }

    // ── saved segments ───────────────────────────────────────────────

    @Synchronized fun segments(pkg: String): List<Segment> = load(pkg).segments.toList()

    @Synchronized fun segment(pkg: String, name: String): Segment? = load(pkg).segments.firstOrNull { it.name == name }

    /** 1.4.0-beta4: a lesson taught before lessons kept their starting page (it has a contact sheet but no start). */
    fun oldLesson(pkg: String, s: Segment): Boolean = s.start == null && pictureFile(pkg, s.name)?.isFile == true

    @Synchronized fun saveSegment(pkg: String, name: String, steps: String, start: JSONObject? = null, by: String? = com.kilon.agentvscreen.cmd.Call.current()?.tokenId,
                                  taught: Boolean = false, asked: String? = null): Segment {
        val m = load(pkg)
        // 1.4.0-beta11 (rainbow: the agent tries a lesson and then edits its steps): rewriting a segment keeps where it
        // starts and whom it was taught for. Before, `note save` over a taught lesson dropped its start, and the lesson
        // then read as one taught before 1.4 ("teach it again").
        val old = m.segments.firstOrNull { it.name == name }
        m.segments.removeAll { it.name == name }
        val s = Segment(name, steps, System.currentTimeMillis(), 0, start ?: old?.start, old?.by ?: by,
            taught || old?.taught == true || (old != null && oldLesson(pkg, old)), asked ?: old?.asked)
        m.segments += s
        while (m.segments.size > MAX_SEGMENTS) m.segments.removeAt(0)
        save(m)
        return s
    }

    @Synchronized private fun knownPkgs(): List<String> {
        val out = LinkedHashSet<String>()
        cache.forEach { (pkg, m) -> if (!m.empty()) out += pkg }
        dir()?.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().forEach { f ->
            val mod = f.lastModified()
            val cached = filePkgCache[f.name]?.takeIf { it.first == mod }?.second
            val pkg = cached ?: runCatching { JSONObject(f.readText()).optString("package").ifEmpty { null } }.getOrNull()
                ?.also { filePkgCache[f.name] = mod to it }
            if (pkg != null) out += pkg
        }
        return out.toList()
    }

    /** 1.4.0-beta13: the user showed it (kept as taught, or a lesson from before the mark: it has a contact sheet). */
    fun isTaught(pkg: String, s: Segment): Boolean = s.taught || pictureFile(pkg, s.name)?.isFile == true

    /** 1.4.0-beta13 (test B6): the segment saved for this teach request, newest first; null when none is kept any more. */
    @Synchronized fun forTeach(asked: String): Pair<String, Segment>? =
        knownPkgs().flatMap { p -> load(p).segments.filter { it.asked == asked }.map { p to it } }.maxByOrNull { it.second.at }

    /** 1.4.0-beta14: the newest taught lesson called [name], in any app. */
    @Synchronized fun taughtNamed(name: String): Pair<String, Segment>? =
        knownPkgs().flatMap { p -> load(p).segments.filter { it.name == name && isTaught(p, it) }.map { p to it } }.maxByOrNull { it.second.at }

    /** 1.3.0-beta15: every saved segment of every app (the app's "saved lessons" page), newest first. */
    @Synchronized fun allSegments(): List<Pair<String, Segment>> =
        knownPkgs().flatMap { p -> load(p).segments.map { p to it } }.sortedByDescending { it.second.at }

    /**
     * 1.4.0-beta11 (rainbow: the agent's own experience and the user's lessons are one thing). Everything kept for each
     * app, whoever kept it: segments (taught or saved), notes, and the app's habits (before / after / human pacing).
     */
    class Kept(val pkg: String, val segments: List<Segment>, val notes: List<Note>, val habits: Map<String, String>)

    @Synchronized fun allKept(): List<Kept> = knownPkgs().map { p -> val m = load(p)
        Kept(p, m.segments.sortedByDescending { it.at }, m.notes.sortedByDescending { it.at }, m.profile.filterKeys { it in setOf("before", "after", "human") })
    }.filter { it.segments.isNotEmpty() || it.notes.isNotEmpty() || it.habits.isNotEmpty() }
        .sortedByDescending { k -> maxOf(k.segments.maxOfOrNull { it.at } ?: 0L, k.notes.maxOfOrNull { it.at } ?: 0L) }

    /**
     * 1.4.0-beta11 (rainbow: one kind of experience, whoever made it). A segment an agent saved knows no start; the
     * first time it runs through, the page it started from becomes its start, checked from then on like a lesson's.
     */
    @Synchronized fun learnStart(pkg: String, name: String, start: JSONObject) {
        val m = load(pkg)
        val i = m.segments.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return
        val s = m.segments[i]
        if (s.start != null) return
        m.segments[i] = Segment(s.name, s.steps, s.at, s.uses, start, s.by, s.taught, s.asked, s.lastUsedAt)
        save(m)
    }

    @Synchronized fun markUsed(pkg: String, name: String) {
        val m = load(pkg)
        m.segments.firstOrNull { it.name == name }?.let { it.uses++; it.lastUsedAt = System.currentTimeMillis(); save(m) }
    }

    @Synchronized fun deleteSegment(pkg: String, name: String): Boolean {
        val m = load(pkg)
        return m.segments.removeAll { it.name == name }.also { if (it) { save(m); pictureFile(pkg, name)?.delete() } }
    }

    /**
     * 1.3.0 (rainbow): a taught segment keeps a small contact sheet of the lesson (key steps as thumbnails), so an
     * agent can look at what the steps were done on. One file per segment, deleted with it. Written when the lesson
     * is saved; a later `note save` that rewrites the steps leaves it older than the segment ([pictureStale]).
     */
    fun pictureFile(pkg: String, name: String): File? = appContext?.let {
        File(File(File(it.filesDir, "vscreen"), "lessons"), VScreenStore.safeName(pkg) + "/" + VScreenStore.safeName(name) + ".jpg")
    }
    fun savePicture(pkg: String, name: String, bmp: android.graphics.Bitmap): File? = runCatching {
        val f = pictureFile(pkg, name) ?: return null
        f.parentFile?.mkdirs()
        Pictures.write(bmp, f, Pictures.Use.LESSON)
        f
    }.getOrNull()
    fun picture(pkg: String, name: String): File? = pictureFile(pkg, name)?.takeIf { it.isFile }
    /** The steps were rewritten after the picture was made. */
    fun pictureStale(pkg: String, name: String): Boolean {
        val f = picture(pkg, name) ?: return false
        val seg = segment(pkg, name) ?: return false
        return seg.at > f.lastModified() + 2_000
    }

    // ── profile ──────────────────────────────────────────────────────

    private fun bool(v: String) = when (v.lowercase()) { "true", "on", "1", "yes" -> "true"; "false", "off", "0", "no" -> "false"; else -> null }

    /** Keys a profile may hold, with a checker returning the normalised value or null (invalid). */
    val PROFILE_KEYS: Map<String, (String) -> String?> = mapOf(
        "before" to { v -> v.trim().takeIf { it.isNotEmpty() && it.length <= 300 } },
        "after" to { v -> v.trim().takeIf { it.isNotEmpty() && it.length <= 300 } },
        "record" to { v -> bool(v) },
        // 1.3.0-beta19: human pacing for every act on this app (note human on|off).
        "human" to { v -> bool(v) },
        "orientation" to { v -> v.trim().lowercase().takeIf { it == "landscape" || it == "portrait" } },
    )

    /** Names of older files: read as the new key, never written again. */
    val LEGACY_KEYS: Map<String, String> = mapOf("resume" to "before", "pause" to "after")

    @Synchronized fun profile(pkg: String): Map<String, String> = load(pkg).profile.toMap()

    /** Merge [changes] (an empty value removes the key). Returns the new profile. */
    @Synchronized fun setProfile(pkg: String, changes: Map<String, String>): Map<String, String> {
        val m = load(pkg)
        for ((k, v) in changes) if (v.isEmpty()) m.profile.remove(k) else m.profile[k] = v
        save(m)
        return m.profile.toMap()
    }

    /** For tests: forget what is cached. */
    @Synchronized fun clearCache() { cache.clear() }
}
