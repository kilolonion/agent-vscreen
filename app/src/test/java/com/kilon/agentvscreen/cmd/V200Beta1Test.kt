package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.ApiServer
import com.kilon.agentvscreen.core.Meter
import com.kilon.agentvscreen.core.Pictures
import com.kilon.agentvscreen.core.TaskLog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
class V200Beta1Test {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ApiConfig.init(ctx)
        com.kilon.agentvscreen.api.Tokens.init(ctx)
        com.kilon.agentvscreen.host.Audit.init(ctx)
        TaskLog.init(ctx)
    }

    @Test
    fun meterPureChineseTenCharsEqualsTen() {
        val cost = Meter.of("一二三四五六七八九十", emptyList())
        assertEquals(10, cost.chars)
        assertEquals(10, cost.estTokens)
        assertEquals(0, cost.imageTokens)
        assertTrue(cost.images.isEmpty())
    }

    @Test
    fun meterAsciiEightCharsEqualsTwo() {
        val cost = Meter.of("abcdefgh", emptyList())
        assertEquals(8, cost.chars)
        assertEquals(2, cost.estTokens)
        assertEquals(0, cost.imageTokens)
    }

    @Test
    fun meterMixedCjkAndAsciiRoundsUpNonCjkByFour() {
        val cost = Meter.of("打开 app", emptyList())
        assertEquals(6, cost.chars)
        assertEquals(3, cost.estTokens)
        assertEquals(0, cost.imageTokens)
    }

    @Test
    fun meterImage1280x720Equals1229() {
        val cost = Meter.of("", listOf(intArrayOf(1280, 720)))
        assertEquals(0, cost.chars)
        assertEquals(0, cost.estTokens)
        assertEquals(1, cost.images.size)
        assertEquals(1280, cost.images[0][0])
        assertEquals(720, cost.images[0][1])
        assertEquals(1229, cost.imageTokens)
    }

    @Test
    fun meterFullwidthPunctuationCountsAsCjk() {
        // U+FF0C '，' and U+3002 '。' are in CJK/fullwidth ranges (1 token each).
        val cost = Meter.of("好，。", emptyList())
        assertEquals(3, cost.chars)
        assertEquals(3, cost.estTokens)
    }

    @Test
    fun apiEnvelopeIncludesMeterWithoutAlteringTextReply() {
        val aliveField = com.kilon.agentvscreen.host.HostService::class.java.getDeclaredField("isAlive").apply { isAccessible = true }
        val wasAlive = aliveField.getBoolean(null)
        aliveField.setBoolean(null, true)
        val srv = ApiServer(ctx)
        try {
            srv.start(18890)
            val port = srv.boundPort
            assertTrue("ApiServer bound a port", port > 0)
            com.kilon.agentvscreen.api.AgentLedger.init(ctx)
            Call.with(Call("s", "main")) {
                com.kilon.agentvscreen.api.AgentLedger.remembered("unit-test")
                com.kilon.agentvscreen.api.AgentLedger.call()
            }
            val body = JSONObject().put("args", org.json.JSONArray(listOf("apps", "nonexistent_pkg_xyz"))).toString().toByteArray()

            // JSON reply carries "meter"
            val jsonConn = (URL("http://127.0.0.1:$port/v2/run").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Host", "127.0.0.1:$port")
                setRequestProperty("Authorization", "Bearer ${ApiConfig.token}")
                setRequestProperty("Content-Type", "application/json")
            }
            jsonConn.outputStream.use { it.write(body) }
            val respJson = JSONObject(jsonConn.inputStream.bufferedReader().readText())
            assertTrue("JSON reply contains meter", respJson.has("meter"))
            val meter = respJson.getJSONObject("meter")
            val textInJson = respJson.getString("text")
            assertEquals(textInJson.length, meter.getInt("chars"))
            assertEquals(Meter.of(textInJson, emptyList()).estTokens, meter.getInt("est_tokens"))
            assertEquals(0, meter.getInt("image_tokens"))
            assertFalse("text reply does not contain meter JSON", textInJson.contains("\"meter\""))

            // Plain-text reply is completely unchanged
            val txtConn = (URL("http://127.0.0.1:$port/v2/run?format=text").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Host", "127.0.0.1:$port")
                setRequestProperty("Authorization", "Bearer ${ApiConfig.token}")
                setRequestProperty("Content-Type", "application/json")
            }
            txtConn.outputStream.use { it.write(body) }
            val plainText = txtConn.inputStream.bufferedReader().readText()
            assertEquals(textInJson, plainText)
        } finally {
            srv.stop()
            aliveField.setBoolean(null, wasAlive)
        }
    }

    @Test
    fun taskLogSaveStepRecordsFourMeterFieldsWithSentImageDimensions() {
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val shotRef = ShotStore.save(ctx, bmp, "meter_test", Pictures.Use.LOOK, sent = true)
        bmp.recycle()
        assertNotNull(shotRef)

        // Simulate an act/look command reply whose sent compact copy (Pictures.Use.SENT: long side 1024 -> 1024x576) is picked.
        val outText = "画面结果 $shotRef"
        val res = NativeOffloadResult(
            exitCode = 0,
            output = outText,
            code = "OK",
            data = JSONObject().put("picture_changed", true).put("steps", org.json.JSONArray().put(JSONObject().put("n", "1"))),
        )
        TaskLog.onCommand("s_meter", "main", listOf("act", "tap 1 1"), "OK", 0, 42L, outText, res)
        TaskLog.flushForTest()

        val recent = TaskLog.recent(5)
        assertTrue(recent.isNotEmpty())
        val steps = TaskLog.steps(recent.first().id)
        assertEquals(1, steps.size)
        val step = steps.first()
        assertTrue(step.has("chars"))
        assertTrue(step.has("est_tokens"))
        assertTrue(step.has("images"))
        assertTrue(step.has("image_tokens"))
        val imgs = step.getJSONArray("images")
        assertEquals(1, imgs.length())
        // Compact SENT image is scaled to long side 1024 (1024x576) -> ceil(1024*576/750) = 787
        val w = imgs.getJSONArray(0).getInt(0)
        val h = imgs.getJSONArray(0).getInt(1)
        assertEquals(1024, w)
        assertEquals(576, h)
        assertEquals(787, step.getInt("image_tokens"))
    }

    @Test
    fun meterSentDimensionsIsStrictlyReadOnlyAndPrefersExistingCompact() {
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        // Save with sent=false so no compact copy is pre-written on disk.
        val shotRef = ShotStore.save(ctx, bmp, "readonly_test", Pictures.Use.LOOK, sent = false)
        bmp.recycle()
        assertNotNull(shotRef)
        val shotName = ShotStore.refs(shotRef!!).single()
        val compactName = "vscreen_c" + shotName.removePrefix("vscreen")
        val compactFile = java.io.File(ShotStore.dir(ctx), compactName)
        assertFalse("compact file must not exist yet", compactFile.exists())

        val res = NativeOffloadResult(
            exitCode = 0,
            output = shotRef,
            code = "OK",
            data = JSONObject().put("picture_changed", true).put("steps", org.json.JSONArray().put(JSONObject().put("n", "1"))),
        )
        // Without compact file on disk, sentDimensions reads original 1280x720 and never writes compactFile.
        val dimsBefore = Meter.sentDimensions(ctx, res, listOf(shotName))
        assertFalse("sentDimensions must never create compact file", compactFile.exists())
        assertEquals(1, dimsBefore.size)
        assertEquals(1280, dimsBefore[0][0])
        assertEquals(720, dimsBefore[0][1])

        // Once compact file exists (written by save/ApiServer/Mcp), sentDimensions reads compact 1024x576.
        com.kilon.agentvscreen.api.ImagePolicy.compact(ctx, shotName)
        assertTrue("compact file exists after ImagePolicy.compact", compactFile.exists())
        val dimsAfter = Meter.sentDimensions(ctx, res, listOf(shotName))
        assertEquals(1, dimsAfter.size)
        assertEquals(1024, dimsAfter[0][0])
        assertEquals(576, dimsAfter[0][1])
    }

    @Test
    fun evalTasksCoverAllThirteenScenesAndCurrentStageStepsParse() {
        val dir = java.io.File("../tools/eval/tasks")
        if (!dir.isDirectory) return
        val files = dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()
        assertTrue("expected at least 26 eval task files", files.size >= 26)
        val byScene = HashMap<String, MutableList<JSONObject>>()
        for (f in files) {
            val o = JSONObject(f.readText())
            val scene = o.getString("scene")
            byScene.getOrPut(scene) { ArrayList() } += o
            if (!o.has("since")) {
                val cmds = o.getJSONArray("commands")
                for (i in 0 until cmds.length()) {
                    val argv = cmds.getJSONArray(i).let { a -> List(a.length()) { a.getString(it) } }
                    if (argv.firstOrNull() == "act") {
                        val rest = VScreenOptions.normalize("act", argv.drop(1))
                        val args = VScreenOptions.args("act", rest)
                        val body = args.positional.joinToString(" ").trim()
                        if (body.isNotEmpty()) {
                            assertTrue("${f.name}: parsed steps non-empty", Steps.parseAll(body).isNotEmpty())
                        }
                    }
                }
            }
        }
        for (i in 1..13) {
            val s = "S%02d".format(i)
            val list = byScene[s].orEmpty()
            assertTrue("scene $s has at least 2 tasks", list.size >= 2)
            assertTrue("scene $s has a current-stage task", list.any { !it.has("since") })
            assertTrue("scene $s has a future-stage task", list.any { it.has("since") })
        }
    }
}
