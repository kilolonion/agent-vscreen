package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import com.kilon.agentvscreen.core.DetectorMath
import com.kilon.agentvscreen.core.DetectorModels
import org.robolectric.RuntimeEnvironment
import com.kilon.agentvscreen.core.VScreenDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.0: the optional textless-element detector — decode, one-source-per-place merging, the release manifest. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DetectorTest {

    /** [1,5,n] head output from (cx, cy, w, h, score) columns. */
    private fun head(vararg cols: FloatArray): FloatArray {
        val n = cols.size
        return FloatArray(5 * n) { i -> cols[i % n][i / n] }
    }

    @Test fun letterboxFitsTheFrameTopLeftKeepingItsShape() {
        val lb = DetectorMath.letterbox(1080, 2400, 448, 992)
        assertEquals(446, lb.w); assertEquals(992, lb.h)
        val wide = DetectorMath.letterbox(1080, 1920, 448, 992)
        assertEquals(448, wide.w); assertEquals(796, wide.h)
    }

    @Test fun decodeKeepsTheBestOfOverlappingBoxesAndMapsBackToTheFrame() {
        val out = head(
            floatArrayOf(100f, 100f, 40f, 40f, 0.9f),
            floatArrayOf(102f, 101f, 40f, 40f, 0.6f),  // same icon, weaker: suppressed
            floatArrayOf(300f, 500f, 30f, 30f, 0.4f),
            floatArrayOf(200f, 200f, 30f, 30f, 0.1f),  // below threshold
        )
        val hits = DetectorMath.decode(out, 5, 4, 0.25f, 0.5, 1080, 2400)
        assertEquals(2, hits.size)
        assertEquals(Rect(160, 160, 240, 240), hits[0].box)
        assertEquals(Rect(570, 970, 630, 1030), hits[1].box)
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test fun detectedPlacesJoinTheListOnlyWhereNothingElseNamesThem() {
        val els = listOf(
            El(1, "设置", Rect(100, 100, 300, 160), true, clickable = true),                     // words: OCR / tree names it
            El(2, "", Rect(900, 80, 1000, 180), true, clickable = true, kind = ElKind.NO_TEXT),  // the app's own icon button
            El(3, "列表行 · 很长", Rect(0, 400, 1080, 560), true, clickable = true),              // a whole row
        )
        val icons = listOf(
            Rect(110, 105, 290, 155),   // the words' own box: same place
            Rect(910, 90, 990, 170),    // inside the app's icon button: same place
            Rect(980, 440, 1060, 520),  // a switch at the end of the row: the row is 20x bigger, so it is its own place
            Rect(500, 1500, 580, 1580), // a canvas icon nobody names: new
        )
        val got = detectedOnly(els, icons)
        assertEquals(listOf(Rect(980, 440, 1060, 520), Rect(500, 1500, 580, 1580)), got.map { it.box })
        assertTrue(got.all { it.detected && it.clickable && it.kind == ElKind.NO_TEXT })
        assertEquals("（无文字 图标）", got[0].label)
        assertEquals("detector", got[0].json().getString("source"))
    }

    @Test fun atMostTwentyDetectedPlacesAndNoDuplicatesAmongThem() {
        val icons = (0 until 40).map { Rect(10 + it * 25, 10, 30 + it * 25, 30) } + listOf(Rect(10, 10, 30, 30))
        val got = detectedOnly(emptyList(), icons)
        assertEquals(MAX_DETECTED, got.size)
        assertEquals(got.size, got.map { it.box }.toSet().size)
    }

    @Test fun manifestFromTheNotebookParsesAndBadEntriesAreRefused() {
        val ok = """{"format":1,"kind":"vscreen-detector","classes":["icon"],"models":[
            {"id":"student-992x448","file":"vscreen-detector-student-992x448.onnx","sha256":"${"a".repeat(64)}","bytes":5000000,
             "input":{"h":992,"w":448},"threshold":0.3,"val":{"hit_rate":0.81},"license":"AGPL-3.0","default":true}]}"""
        val offers = DetectorModels.parseManifest(ok, "GitHub", "https://x/")
        assertEquals(1, offers.size)
        assertEquals(448, offers[0].inW); assertEquals(992, offers[0].inH); assertTrue(offers[0].default)
        assertEquals(0.81, offers[0].hitRate!!, 1e-9)
        val badName = ok.replace("vscreen-detector-student-992x448.onnx", "../evil.onnx")
        assertTrue(runCatching { DetectorModels.parseManifest(badName, "GitHub", "https://x/") }.isFailure)
        val badSha = ok.replace("a".repeat(64), "xyz")
        assertTrue(runCatching { DetectorModels.parseManifest(badSha, "GitHub", "https://x/") }.isFailure)
    }

    @Test fun builtInModelIsDescribedAndStaysOffUntilTheLicenseNoticeIsAccepted() {
        val ctx = RuntimeEnvironment.getApplication()
        val m = DetectorModels.builtIn(ctx)
        assertNotNull(m)
        assertTrue(m!!.builtIn); assertEquals(320, m.inW); assertEquals(704, m.inH)
        assertEquals(m.sha256, DetectorModels.installed(ctx)!!.sha256)
        assertFalse(VScreenDetector.licenseAccepted(ctx)); assertFalse(VScreenDetector.active(ctx))
        VScreenDetector.acceptLicense(ctx)
        assertTrue(VScreenDetector.active(ctx))
        val f = DetectorModels.ensureFile(ctx, m)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(m.sha256, sha)
    }
}
