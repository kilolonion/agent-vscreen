package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.Changes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * P0-1 single-source doc sync gate: keeps version lines in `README.md`, `README.zh-CN.md`, and
 * `API.md` strictly synchronized with [BuildConfig], and ensures [Changes.releases] tracks the
 * current build version. Like [AbilitiesTest], rewrites files only when `AVS_REGEN=1`.
 */
@RunWith(RobolectricTestRunner::class)
class DocsSyncTest {
    private fun replaceBetween(text: String, start: String, end: String, body: String): String {
        val a = text.indexOf(start)
        val b = text.indexOf(end)
        require(a >= 0 && b > a) { "markers $start / $end missing" }
        return text.substring(0, a + start.length) + "\n" + body + "\n" + text.substring(b)
    }

    @Test
    fun latestChangeEntryMatchesBuildConfig() {
        assertTrue("Changes.releases must not be empty", Changes.releases.isNotEmpty())
        val latest = Changes.releases.first()
        assertEquals(
            "Add a Changes.Release entry matching app/build.gradle.kts versionCode",
            BuildConfig.VERSION_CODE,
            latest.versionCode,
        )
        assertEquals(
            "Add a Changes.Release entry matching app/build.gradle.kts versionName",
            BuildConfig.VERSION_NAME,
            latest.versionName,
        )
    }

    @Test
    fun versionBlocksStaySynced() {
        val root = File("..")
        if (!File(root, "README.md").isFile) return

        val ver = BuildConfig.VERSION_NAME
        val code = BuildConfig.VERSION_CODE
        val regen = System.getenv("AVS_REGEN") == "1"
        val changed = ArrayList<String>()

        fun sync(file: File, start: String, end: String, body: String) {
            val now = file.readText(Charsets.UTF_8)
            val want = replaceBetween(now, start, end, body)
            if (want != now) {
                if (regen) file.writeText(want, Charsets.UTF_8)
                changed += file.path
            }
        }

        val zhVersion = "- `main` 分支版本：$ver（versionCode $code） · [本版 APK](https://github.com/kilolonion/agent-vscreen/releases/tag/v$ver) · [最新正式版 APK](https://github.com/kilolonion/agent-vscreen/releases/latest)"
        val enVersion = "- Version on `main`: $ver (versionCode $code) · [Release APK](https://github.com/kilolonion/agent-vscreen/releases/tag/v$ver) · [Latest stable APK](https://github.com/kilolonion/agent-vscreen/releases/latest)"
        val apiVersion = "- 包名 `com.kilon.agentvscreen`，`main` 分支版本 $ver（versionCode $code）。官方测试版与正式版同签名可覆盖安装；安装前可按 [README 的说明](README.zh-CN.md#验证下载的-apk)验证签名。"

        sync(File(root, "README.zh-CN.md"), "<!-- version:start -->", "<!-- version:end -->", zhVersion)
        sync(File(root, "README.md"), "<!-- version:start -->", "<!-- version:end -->", enVersion)
        sync(File(root, "API.md"), "<!-- version:start -->", "<!-- version:end -->", apiVersion)

        assertTrue(
            "out of date, generated from BuildConfig (./build.sh regen rewrites them): $changed",
            regen || changed.isEmpty(),
        )
    }

    @Test
    fun docsContainNoContradictedLegacySecurityStatements() {
        val root = File("..")
        if (!File(root, "README.md").isFile) return
        val forbidden = listOf(
            "本应用不提供公网链接",
            "The app never offers a public link",
            "为什么不做公网链接",
            "不提供 Funnel 之类的公网入口",
        )
        for (name in listOf("README.md", "README.zh-CN.md", "API.md")) {
            val text = File(root, name).readText(Charsets.UTF_8)
            for (phrase in forbidden) {
                assertFalse("$name must not contain contradicted legacy statement: $phrase", text.contains(phrase))
            }
        }
    }
}
