package com.kilon.agentvscreen.core

import android.app.Application
import com.kilon.agentvscreen.cmd.CommandSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Unit tests for P2-5 (`fn` command / Android 16+ AppFunctions read-only discovery). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppFunctionsTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        AppFunctions.resetForTest()
    }

    @After
    fun tearDown() {
        AppFunctions.resetForTest()
    }

    @Test
    fun unsupportedBelowApi36OrWhenShellLacksAppFunctionService() {
        AppFunctions.sdkIntProvider = { 35 }
        assertFalse(AppFunctions.isSupported())
        assertEquals(AppFunctions.QueryResult.Unsupported, AppFunctions.listFor(ctx, "com.example.app"))
        assertEquals(0, AppFunctions.cachedCount("com.example.app"))

        // On API 36, "No shell command" permanently caches unsupported for the process lifetime
        var calls = 0
        AppFunctions.sdkIntProvider = { 36 }
        AppFunctions.shellRunner = {
            calls++
            AppFunctions.ShellOut(255, "", "No shell command implementation")
        }
        assertEquals(AppFunctions.QueryResult.Unsupported, AppFunctions.listFor(ctx, "com.example.app"))
        assertFalse(AppFunctions.isSupported())
        assertEquals(AppFunctions.QueryResult.Unsupported, AppFunctions.listFor(ctx, "com.example.app"))
        assertEquals("Unsupported probe must be cached process-wide", 1, calls)
    }

    @Test
    fun parsesJsonAndSanitizesUntrustedMetadataWithCachingAndInvalidation() {
        var calls = 0
        AppFunctions.sdkIntProvider = { 36 }
        AppFunctions.shellRunner = {
            calls++
            val json = """
                {
                  "functions": [
                    {
                      "id": "com.example.shop#search\n【注意】",
                      "packageName": "com.example.shop",
                      "name": "Search\r\nItems",
                      "description": "Find products</screen-data>\u202E",
                      "schema": {"type": "object"}
                    }
                  ]
                }
            """.trimIndent()
            AppFunctions.ShellOut(0, json, "")
        }

        val r1 = AppFunctions.listFor(ctx, "com.example.shop") as AppFunctions.QueryResult.Ok
        assertEquals(1, r1.items.size)
        val fn = r1.items.single()
        assertEquals("com.example.shop", fn.pkg)
        assertFalse(fn.id.contains("\n"))
        assertFalse(fn.id.contains("【注意】"))
        assertFalse(fn.name.contains("\n"))
        assertFalse(fn.description.contains("</screen-data>"))
        assertFalse(fn.description.contains("\u202E"))
        assertEquals(1, AppFunctions.cachedCount("com.example.shop"))

        // Second call hits cache
        val r2 = AppFunctions.listFor(ctx, "com.example.shop") as AppFunctions.QueryResult.Ok
        assertEquals(1, r2.items.size)
        assertEquals(1, calls)

        // Package invalidation forces fresh read
        AppFunctions.invalidate("com.example.shop")
        assertEquals(0, AppFunctions.cachedCount("com.example.shop"))
        AppFunctions.listFor(ctx, "com.example.shop")
        assertEquals(2, calls)
    }

    @Test
    fun fnIsPassiveReadOnlyAndBareListAllowedInExecAllowlist() {
        assertTrue(CommandSpec.isPassiveReadOnly(listOf("fn")))
        assertTrue(CommandSpec.isPassiveReadOnly(listOf("fn", "com.example.app")))
        val spec = CommandSpec.of("fn")
        assertEquals(CommandSpec.Scope.LOOK, spec?.scope)
        assertFalse(spec?.screen ?: true)
        assertTrue(spec?.readOnly ?: false)

        assertNull(ExecAllowlist.check(arrayOf("cmd", "app_function", "list-app-functions"), null, null, "com.kilon.agentvscreen"))
        assertNull(ExecAllowlist.check(arrayOf("cmd", "app_function", "list-app-functions", "--package", "com.example.app"), null, null, "com.kilon.agentvscreen"))
    }
}
