package com.kilon.agentvscreen.core

import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.kilon.agentvscreen.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Security unit tests for [VScreenDisplayService] UID authorization, GATE op 0 validation,
 * EXEC allowlist enforcement, Sound injection defense, and Gate hold-file hardening (P1-1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VScreenDisplayServiceSecurityTest {

    private fun transactPing(svc: VScreenDisplayService): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
            assertTrue(svc.transact(VScreenDisplayService.PING, data, reply, 0))
            reply.setDataPosition(0)
            val status = reply.readInt()
            if (status < 0) {
                reply.setDataPosition(0)
                reply.readException()
            }
            assertEquals(0, status)
            reply.readInt() // uid
            return reply.readInt() // protocol
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun assertTransactSecurityDenied(svc: VScreenDisplayService, tx: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            if (tx != VScreenDisplayService.DESTROY_CODE) {
                data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
            }
            var threw = false
            try {
                svc.transact(tx, data, reply, 0)
                reply.setDataPosition(0)
                reply.readException()
            } catch (_: SecurityException) {
                threw = true
            }
            assertTrue("tx=$tx must be rejected with SecurityException for unauthorized uid", threw)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    fun onTransactRefusesUnauthorizedAndUnresolvedCallerUids() {
        val svc = VScreenDisplayService().apply {
            ownAppUidFn = { 10123 }
            callingUidFn = { 10999 }
        }
        for (tx in listOf(
            VScreenDisplayService.PING,
            VScreenDisplayService.CREATE,
            VScreenDisplayService.SOUND,
            VScreenDisplayService.EXEC,
            VScreenDisplayService.GATE,
            VScreenDisplayService.HIDE_IME,
            VScreenDisplayService.RESTORE_MAIN_FOCUS,
            VScreenDisplayService.DESTROY_CODE,
        )) {
            assertTransactSecurityDenied(svc, tx)
        }

        // Shell uid (2000) is NOT allowed for normal transactions (CREATE..LAST_CALL).
        svc.callingUidFn = { 2000 }
        try {
            transactPing(svc)
            fail("uid 2000 must be rejected on PING")
        } catch (_: SecurityException) {
            // expected
        }

        // Different Android user with same appId (user 10 -> 1010123) must be rejected.
        svc.callingUidFn = { 10 * 100_000 + 10123 }
        try {
            transactPing(svc)
            fail("cross-user appId match must be rejected")
        } catch (_: SecurityException) {
            // expected
        }

        // Fail-closed when own app UID cannot be resolved.
        val failClosed = VScreenDisplayService().apply {
            ownAppUidFn = { null }
            callingUidFn = { 10123 }
        }
        try {
            transactPing(failClosed)
            fail("unresolvable own uid must fail closed")
        } catch (_: SecurityException) {
            // expected
        }
    }

    @Test
    fun onTransactRefreshesCachedUidOnceOnReinstall() {
        var currentUid = 10123
        val svc = VScreenDisplayService().apply {
            ownAppUidFn = { currentUid }
            callingUidFn = { 10123 }
        }
        assertEquals(VScreenDisplayService.PROTOCOL, transactPing(svc))

        // Simulate reinstall changing UID from 10123 -> 10124.
        currentUid = 10124
        svc.callingUidFn = { 10124 }
        assertEquals(VScreenDisplayService.PROTOCOL, transactPing(svc))

        // Old UID 10123 is now rejected.
        svc.callingUidFn = { 10123 }
        try {
            transactPing(svc)
            fail("old uid after reinstall must be rejected")
        } catch (_: SecurityException) {
            // expected
        }
    }

    @Test
    fun gateOp0RejectsForeignPackageOrProcessName() {
        val svc = VScreenDisplayService().apply {
            ownAppUidFn = { 10123 }
            callingUidFn = { 10123 }
        }
        fun callGateStart(pkg: String, proc: String): Pair<Int, String?> {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                data.writeInt(0)
                data.writeInt(18765)
                data.writeInt(18775)
                data.writeString(pkg)
                data.writeString(proc)
                data.writeInt(34)
                assertTrue(svc.transact(VScreenDisplayService.GATE, data, reply, 0))
                reply.setDataPosition(0)
                val status = reply.readInt()
                val msg = if (status != 0) reply.readString() else null
                return status to msg
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        val r1 = callGateStart("com.evil.app", BuildConfig.APPLICATION_ID)
        assertEquals(1, r1.first)
        assertTrue(r1.second.orEmpty().contains("SecurityException"))

        val r2 = callGateStart(BuildConfig.APPLICATION_ID, "com.evil.app")
        assertEquals(1, r2.first)
        assertTrue(r2.second.orEmpty().contains("SecurityException"))
    }

    @Test
    fun execTransactionDeniesShellAndWritesExecDeniedToStderr() {
        val svc = VScreenDisplayService().apply {
            ownAppUidFn = { 10123 }
            callingUidFn = { 10123 }
        }
        val outFile = File.createTempFile("avs-exec-out-", ".txt")
        val errFile = File.createTempFile("avs-exec-err-", ".txt")
        try {
            // 1) Direct exec() call verifies both exit code 126 and EXEC_DENIED written to stderr fd.
            val outFdDirect = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
            val errFdDirect = ParcelFileDescriptor.open(errFile, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
            assertEquals(126, svc.exec(arrayOf("sh", "-c", "id"), null, null, 3_000L, outFdDirect, errFdDirect))
            assertTrue("stderr must contain EXEC_DENIED", errFile.readText().contains("EXEC_DENIED:"))

            // 2) Full Binder EXEC transaction returns status=0, exitCode=126.
            val outFd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
            val errFd = ParcelFileDescriptor.open(errFile, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                data.writeStringArray(arrayOf("sh", "-c", "id"))
                data.writeStringArray(null)
                data.writeString(null)
                data.writeLong(3_000L)
                outFd.writeToParcel(data, 0)
                errFd.writeToParcel(data, 0)
                assertTrue(svc.transact(VScreenDisplayService.EXEC, data, reply, 0))
                reply.setDataPosition(0)
                assertEquals(0, reply.readInt())
                assertEquals(126, reply.readInt())
            } finally {
                data.recycle()
                reply.recycle()
                runCatching { outFd.close() }
                runCatching { errFd.close() }
            }
        } finally {
            outFile.delete()
            errFile.delete()
        }
    }

    @Test
    fun soundMuteRejectsInjectionPackageNamesAndDisplay0OpsRefused() {
        val svc = VScreenDisplayService().apply {
            ownAppUidFn = { 10123 }
            callingUidFn = { 10123 }
        }
        // SOUND op 0 with shell metacharacters returns 0 without spawning anything.
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
            data.writeInt(0)
            data.writeString("com.example.app; id")
            assertTrue(svc.transact(VScreenDisplayService.SOUND, data, reply, 0))
            reply.setDataPosition(0)
            assertEquals(0, reply.readInt())
            assertEquals(0, reply.readInt())
        } finally {
            data.recycle()
            reply.recycle()
        }

        // HIDE_IME and RESTORE_MAIN_FOCUS refuse display 0.
        fun callBool(tx: Int, write: (Parcel) -> Unit): Boolean {
            val d = Parcel.obtain(); val r = Parcel.obtain()
            try {
                d.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                write(d)
                assertTrue(svc.transact(tx, d, r, 0))
                r.setDataPosition(0)
                assertEquals(0, r.readInt())
                return r.readInt() != 0
            } finally { d.recycle(); r.recycle() }
        }
        assertFalse(callBool(VScreenDisplayService.HIDE_IME) { it.writeInt(0) })
        assertFalse(callBool(VScreenDisplayService.RESTORE_MAIN_FOCUS) { it.writeInt(0); it.writeInt(42) })
        assertFalse(callBool(VScreenDisplayService.RESTORE_MAIN_FOCUS) { it.writeInt(99); it.writeInt(42) })
    }

    @Test
    fun gateCleanupLeftoverHoldsRejectsSymlinksAndDeniedEntries() {
        val dir = Files.createTempDirectory("avs-gate-sec-").toFile()
        try {
            // 1) Tampered hold file with protected/invalid entries: only valid holdable process is unfrozen.
            val holdFile = File(dir, "holds.txt")
            holdFile.writeText(
                listOf(
                    "system_server",
                    BuildConfig.APPLICATION_ID,
                    "com.evil; id",
                    "-f",
                    "com.example.valid",
                ).joinToString("\n") + "\n",
            )
            val ran = ArrayList<List<String>>()
            val gate = Gate(
                service = null,
                shell = Gate.Shell { argv, _ -> ran += argv; 0 },
                startWatchThread = false,
                holdFile = holdFile,
                readWchan = { emptyList() },
                resolveLaunchers = { emptySet() },
            )
            assertEquals(1, gate.cleanupLeftoverHolds())
            assertTrue(ran.all { it.last() == "com.example.valid" })

            // 2) Symlink hold file is completely ignored (never follows symlink).
            val target = File(dir, "secret.txt").apply { writeText("com.example.secret\n") }
            val link = File(dir, "symlink-holds.txt")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            val ran2 = ArrayList<List<String>>()
            val gate2 = Gate(
                service = null,
                shell = Gate.Shell { argv, _ -> ran2 += argv; 0 },
                startWatchThread = false,
                holdFile = link,
                readWchan = { emptyList() },
                resolveLaunchers = { emptySet() },
            )
            assertEquals(0, gate2.cleanupLeftoverHolds())
            assertTrue(ran2.isEmpty())
            assertTrue("symlink target must remain untouched", target.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
