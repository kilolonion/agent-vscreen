package com.kilon.agentvscreen

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.0.1-beta2: one text table with a column per language, named by S constants. */
class StringsTest {
    @After fun reset() { I18n.choice = I18n.Lang.ZH }

    @Test fun eachLanguageReadsItsOwnColumn() {
        I18n.choice = I18n.Lang.ZH
        assertEquals("设置", tr(S.app_settings))
        I18n.choice = I18n.Lang.EN
        assertEquals("Settings", tr(S.app_settings))
        I18n.choice = I18n.Lang.JA
        assertEquals("設定", tr(S.app_settings))
    }

    @Test fun valuesFillInOrderAndLanguagesMayReorderThem() {
        I18n.choice = I18n.Lang.ZH
        assertEquals("在天气里操作了 3 步", tr(S.tasks_in, "天气", 3, steps(3)))
        I18n.choice = I18n.Lang.EN
        assertEquals("3 steps in Weather", tr(S.tasks_in, "Weather", 3, steps(3)))
        assertEquals("1 step", steps(1))
    }

    @Test fun agentRepliesStayChineseOrEnglish() {
        I18n.choice = I18n.Lang.JA
        assertEquals("Settings", I18n.with("en") { tr(S.app_settings) })
        assertEquals("设置", I18n.with("zh") { tr(S.app_settings) })
        assertEquals("Settings", I18n.with("en") { trPair("设置", "Settings") })
        // A data pair whose English is also a table text reads translated in Japanese.
        assertEquals("設定", trPair("设置", "Settings"))
        assertEquals("Not in the table", trPair("不在表里", "Not in the table"))
    }

    @Test fun fillLeavesOtherBracesAlone() {
        assertEquals("{x} a {5} {", Strings.fill("{x} {0} {5} {", arrayOf("a")))
        assertTrue(Strings.column("ja") > Strings.EN)
    }
}
