package com.kilon.agentvscreen

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 2.0.1-beta2: the Japanese table (tools/i18n/build_ja.py) loads, keeps its holes, and fills templates. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class I18nJaTest {
    @Test fun tableLoadsAndEveryTemplateKeepsItsHoles() {
        val ctx = RuntimeEnvironment.getApplication()
        val o = org.json.JSONObject(ctx.assets.open("i18n/ja.json").bufferedReader().use { it.readText() })
        assertTrue(o.getJSONObject("exact").length() > 500)
        val hole = Regex("\\{(\\d+)\\}")
        val tpl = o.getJSONArray("tpl")
        for (i in 0 until tpl.length()) {
            val p = tpl.getJSONArray(i)
            assertEquals(p.toString(), hole.findAll(p.getString(0)).map { it.value }.sorted().toList(),
                hole.findAll(p.getString(1)).map { it.value }.sorted().toList())
        }
    }

    @Test fun japaneseUiLooksUpEnglishAndFallsBackToIt() {
        Ja.load(org.json.JSONObject().put("exact", org.json.JSONObject().put("Settings", "設定"))
            .put("tpl", org.json.JSONArray().put(org.json.JSONArray().put("{0} asks for \"{1}\"").put("{0} が「{1}」を求めています"))))
        assertEquals("設定", Ja.of("Settings"))
        assertEquals("Agent 2 が「look」を求めています", Ja.of("Agent 2 asks for \"look\""))
        assertEquals("Something new", Ja.of("Something new"))
        I18n.init(RuntimeEnvironment.getApplication())
        val before = I18n.choice
        try {
            I18n.choice = I18n.Lang.JA
            assertTrue(I18n.en)
            assertTrue(I18n.ja)
            // A caller that forced English (agents) gets English, not the app's Japanese.
            assertEquals("Settings", I18n.with("en") { tr("设置", "Settings") })
            I18n.choice = I18n.Lang.ZH
            assertEquals("设置", tr("设置", "Settings"))
        } finally { I18n.choice = before }
    }
}
