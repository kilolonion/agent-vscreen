package com.kilon.agentvscreen.core

import android.app.Application
import com.kilon.agentvscreen.cmd.CommandSpec
import com.kilon.agentvscreen.cmd.fnCmd
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

    // ── Fix 11: Non-zero exit codes never poison cache; exit-0 JSON keywords never disable support ──

    @Test
    fun transientNonZeroFailureIsNotCachedAndSubsequentHealthyCallSucceeds() {
        var calls = 0
        AppFunctions.sdkIntProvider = { 36 }
        AppFunctions.shellRunner = {
            calls++
            if (calls == 1) {
                AppFunctions.ShellOut(1, "", "binder transaction transiently failed")
            } else {
                val json = """{"functions":[{"id":"com.example.shop#checkout","packageName":"com.example.shop","name":"Checkout","description":"Open cart"}]}"""
                AppFunctions.ShellOut(0, json, "")
            }
        }

        // First call: transient failure -> QueryResult.Error, isSupported() stays true, cache stays empty!
        val r1 = AppFunctions.listFor(ctx, "com.example.shop")
        assertTrue("Expected QueryResult.Error on exit 1, got $r1", r1 is AppFunctions.QueryResult.Error)
        assertEquals(1, (r1 as AppFunctions.QueryResult.Error).exitCode)
        assertTrue("Transient failure must NOT mark AppFunctions unsupported", AppFunctions.isSupported())
        assertEquals("Transient failure must NOT cache empty list", 0, AppFunctions.cachedCount("com.example.shop"))

        // Second call for the EXACT SAME (pkg, versionCode): retries shell and succeeds!
        val r2 = AppFunctions.listFor(ctx, "com.example.shop") as AppFunctions.QueryResult.Ok
        assertEquals(1, r2.items.size)
        assertEquals("com.example.shop#checkout", r2.items.single().id)
        assertEquals(2, calls)
        assertEquals(1, AppFunctions.cachedCount("com.example.shop"))

        // Verify VScreenFn surfaces QueryResult.Error as OPERATION_FAILED (not FN_UNSUPPORTED / FN_NOT_FOUND)
        AppFunctions.resetForTest()
        AppFunctions.sdkIntProvider = { 36 }
        AppFunctions.shellRunner = { AppFunctions.ShellOut(2, "", "temporary timeout") }
        val handler = com.kilon.agentvscreen.cmd.VScreenOffloadHandler(ctx)
        val args = com.kilon.agentvscreen.cmd.VScreenOptions.args("fn", emptyList())
        try {
            handler.fnCmd("s1", args)
            org.junit.Assert.fail("Expected ActionError OPERATION_FAILED")
        } catch (e: BackgroundOperation.ActionError) {
            assertEquals("OPERATION_FAILED", e.code)
        }
    }

    @Test
    fun zeroExitJsonWhoseDescriptionContainsErrorKeywordsStillParsesAndKeepsSupportEnabled() {
        AppFunctions.sdkIntProvider = { 36 }
        AppFunctions.shellRunner = {
            val json = """
                {
                  "functions": [
                    {
                      "id": "com.example.cli#diagnose",
                      "packageName": "com.example.cli",
                      "name": "Diagnose",
                      "description": "Reports unknown command, no shell command, or app_function not found messages safely"
                    }
                  ]
                }
            """.trimIndent()
            AppFunctions.ShellOut(0, json, "")
        }

        val res = AppFunctions.listFor(ctx, "com.example.cli")
        assertTrue("Exit-0 JSON with error words in description must NOT be treated as unsupported: $res", res is AppFunctions.QueryResult.Ok)
        val items = (res as AppFunctions.QueryResult.Ok).items
        assertEquals(1, items.size)
        assertTrue(items.single().description.contains("unknown command"))
        assertTrue(AppFunctions.isSupported())
    }
}
