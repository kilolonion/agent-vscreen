package com.kilon.agentvscreen.host

import android.content.AttributionSource
import android.os.Bundle
import com.kilon.agentvscreen.cmd.ActHold
import com.kilon.agentvscreen.core.ExecAllowlist
import com.kilon.agentvscreen.core.Gate
import com.kilon.agentvscreen.core.RootMain
import com.kilon.agentvscreen.core.VScreenDisplayService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1-3: Android 16/17 (API 36/37) hardening, 3-tier dynamic Shizuku/fork discovery,
 * manager freeze protection across Gate/ExecAllowlist/ActHold, and Root fallback reactivity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuDiscoveryTest {

    @Test
    fun discoverManagerPackagesPrioritizesPermissionOwnerThenIntentThenKnown() {
        val result = ShizukuBackend.discoverManagerPackages(
            permissionOwner = "org.example.customfork",
            intentActivityPackages = listOf("io.github.the0x01.shevery", "org.example.customfork", " "),
            installedKnownPackages = listOf(
                ShizukuBackend.SHIZUKU_PACKAGE,
                ShizukuBackend.AXMANAGER_PACKAGE,
                "io.github.the0x01.shevery",
            ),
        )
        assertEquals(
            listOf(
                "org.example.customfork",
                "io.github.the0x01.shevery",
                ShizukuBackend.SHIZUKU_PACKAGE,
                ShizukuBackend.AXMANAGER_PACKAGE,
            ),
            result,
        )
    }

    @Test
    fun discoverManagerPackagesHandlesNullOwnerAndFallsBackCleanly() {
        val intentOnly = ShizukuBackend.discoverManagerPackages(
            permissionOwner = null,
            intentActivityPackages = listOf("com.custom.shizuku.fork"),
            installedKnownPackages = emptyList(),
        )
        assertEquals(listOf("com.custom.shizuku.fork"), intentOnly)

        val knownOnly = ShizukuBackend.discoverManagerPackages(
            permissionOwner = "   ",
            intentActivityPackages = emptyList(),
            installedKnownPackages = listOf(ShizukuBackend.AXMANAGER_PACKAGE),
        )
        assertEquals(listOf(ShizukuBackend.AXMANAGER_PACKAGE), knownOnly)
    }

    @Test
    fun shellContextAttributionPackageSkipsShellPkgForRootUid() {
        assertNull(VScreenDisplayService.attributionPackageForUid(0))
        assertEquals("com.android.shell", VScreenDisplayService.attributionPackageForUid(2000))
    }

    @Test
    fun rootMainCallArgsSupportsAndroid10ThroughForwardCompatibleAttributionOverloads() {
        val attr = AttributionSource::class.java
        val s = String::class.java
        val b = Bundle::class.java
        val extras = Bundle().apply { putString("k", "v") }

        // Android 10: (String, String, String, String, Bundle)
        val a10 = RootMain.callArgs(arrayOf(s, s, s, s, b), "com.example.root", extras)
        assertNotNull(a10)
        assertEquals("com.example.root", a10!![1])
        assertEquals(RootMain.METHOD, a10[2])
        assertEquals(extras, a10[4])

        // Android 11: (String, String, String, String, String, Bundle)
        val a11 = RootMain.callArgs(arrayOf(s, s, s, s, s, b), "com.example.root", extras)
        assertNotNull(a11)
        assertNull(a11!![1])
        assertEquals("com.example.root", a11[2])
        assertEquals(RootMain.METHOD, a11[3])
        assertEquals(extras, a11[5])

        // Android 12+ standard: (AttributionSource, String, String, String, Bundle)
        val a12 = RootMain.callArgs(arrayOf(attr, s, s, s, b), "com.example.root", extras)
        assertNotNull(a12)
        val a12Attr = a12!![0] as AttributionSource
        if (android.os.Process.myUid() == 0) {
            assertNull("uid 0 should not spoof com.android.shell on API 36+", a12Attr.packageName)
        }
        assertEquals("com.example.root", a12[1])
        assertEquals(RootMain.METHOD, a12[2])
        assertNull(a12[3])
        assertEquals(extras, a12[4])

        // Forward-compatible Android 16/17+: trailing Int & Boolean optional parameters
        val a16 = RootMain.callArgs(
            arrayOf(attr, s, s, s, b, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            "com.example.root",
            extras,
        )
        assertNotNull(a16)
        assertEquals(7, a16!!.size)
        assertTrue(a16[0] is AttributionSource)
        assertEquals("com.example.root", a16[1])
        assertEquals(RootMain.METHOD, a16[2])
        assertEquals(extras, a16[4])
        assertEquals(0, a16[5])
        assertEquals(false, a16[6])

        // Forward-compatible 6-arg with feature/tag String before authority
        val a17 = RootMain.callArgs(
            arrayOf(attr, s, s, s, s, b, Int::class.javaPrimitiveType!!),
            "com.example.root",
            extras,
        )
        assertNotNull(a17)
        assertEquals(7, a17!!.size)
        assertTrue(a17[0] is AttributionSource)
        assertNull(a17[1])
        assertEquals("com.example.root", a17[2])
        assertEquals(RootMain.METHOD, a17[3])
        assertNull(a17[4])
        assertEquals(extras, a17[5])
        assertEquals(0, a17[6])

        // Unknown signature still rejected
        assertNull(RootMain.callArgs(arrayOf(attr, b), "com.example.root", extras))
    }

    @Test
    fun gateExecAllowlistAndActHoldRefuseShizukuForks() {
        val own = "com.kilon.agentvscreen"
        val launchers = setOf("com.android.launcher3")

        for (mgr in ShizukuBackend.KNOWN_MANAGER_PACKAGES) {
            assertFalse("Gate must refuse $mgr", Gate.isHoldableProcess(mgr, own, launchers))
            assertFalse("Gate must refuse $mgr:remote", Gate.isHoldableProcess("$mgr:remote", own, launchers))
            assertNotNull(
                "ExecAllowlist must refuse freezing $mgr",
                ExecAllowlist.check(arrayOf("am", "freeze", mgr), null, null, own),
            )
            assertNotNull(
                "ExecAllowlist must refuse freezing $mgr:svc",
                ExecAllowlist.check(arrayOf("am", "freeze", "$mgr:svc"), null, null, own),
            )
        }

        // Dynamic unknown renamed fork denied via extraDenied / protectedManagers
        val customFork = "org.custom.renamed.shizuku"
        assertFalse(Gate.isHoldableProcess(customFork, own, launchers, extraDenied = setOf(customFork)))
        assertFalse(Gate.isHoldableProcess("$customFork:daemon", own, launchers, extraDenied = setOf(customFork)))
        assertTrue(Gate.isHoldableProcess("com.example.target", own, launchers, extraDenied = setOf(customFork)))
        assertNull(ExecAllowlist.check(arrayOf("am", "freeze", "com.example.target"), null, null, own))

        val normalInfo = ActHold.AppInfoSnapshot(uid = 10200, persistent = false)
        assertNotNull(ActHold.upfrontRefusalReason(customFork, normalInfo, null, null, protectedManagers = listOf(customFork)))
        assertNull(ActHold.upfrontRefusalReason("com.example.target", normalInfo, null, null, protectedManagers = listOf(customFork)))
    }

    @Test
    fun rootBackendPhaseFlowMatchesPhaseProperty() {
        assertEquals(RootBackend.phase, RootBackend.phaseFlow.value)
    }
}
