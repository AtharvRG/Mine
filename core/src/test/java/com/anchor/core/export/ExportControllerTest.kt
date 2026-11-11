// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B5: the export/import screen had no backend wiring at all — `sharedExportManager` was created
 * in MineApp and never read. These tests pin the flow rules the UI depends on: a weak passphrase
 * never reaches the file, a wrong passphrase is reported as an error rather than a success, and
 * an import marks the process for restart because the live DB was swapped underneath it.
 */
class ExportControllerTest {

    private class FakeManager : ExportManager {
        var exportCalls = 0
        var importCalls = 0
        var verifyCalls = 0
        var lastPassphrase: String? = null
        var lastIncludeApiKeys: Boolean? = null
        var failWith: Exception? = null

        override suspend fun exportTo(file: File, passphrase: String, includeApiKeys: Boolean, includeEmbeddings: Boolean): ExportStats {
            exportCalls++
            lastPassphrase = passphrase
            lastIncludeApiKeys = includeApiKeys
            failWith?.let { throw it }
            return ExportStats(messages = 3, nodes = 2, events = 5, verified = true)
        }

        override suspend fun verifyOrImport(file: File, passphrase: String, isImport: Boolean): ExportStats {
            if (isImport) importCalls++ else verifyCalls++
            lastPassphrase = passphrase
            failWith?.let { throw it }
            return ExportStats(messages = 3, nodes = 2, events = 5, verified = true)
        }
    }

    private fun controller(manager: ExportManager) =
        ExportController(manager, TestScope(UnconfinedTestDispatcher()))

    @Test
    fun testExport_WeakPassphrase_IsRejectedBeforeTheFileIsTouched() = runTest {
        val manager = FakeManager()
        val c = controller(manager)

        c.export(File("out.mnex"), "short", includeApiKeys = false)

        assertEquals("The manager must not be called at all", 0, manager.exportCalls)
        assertTrue(c.state.value.resultIsError)
        assertNotNull(c.state.value.result)
        assertFalse(c.state.value.isBusy)
    }

    @Test
    fun testExport_Success_ReportsCountsAndIsNotAnError() = runTest {
        val manager = FakeManager()
        val c = controller(manager)

        c.export(File("out.mnex"), "correct horse", includeApiKeys = true)

        assertEquals(1, manager.exportCalls)
        assertEquals("correct horse", manager.lastPassphrase)
        assertEquals(true, manager.lastIncludeApiKeys)
        val s = c.state.value
        assertFalse(s.resultIsError)
        assertFalse(s.isBusy)
        assertTrue("Result must show what was exported: ${s.result}", s.result!!.contains("3 messages"))
        assertTrue("Result must show the item count: ${s.result}", s.result!!.contains("2 items"))
    }

    @Test
    fun testExportMarkdown_Success_WritesReadableFile() = runTest {
        val manager = FakeManager()
        val c = controller(manager)
        val storage = com.anchor.core.SharedFakeStorage()
        val tempFile = File.createTempFile("test_export", ".md")
        try {
            val ok = c.runExportMarkdown(tempFile, storage)
            assertTrue("Markdown export must succeed", ok)
            val text = tempFile.readText()
            assertTrue(text.contains("WARNING: UNENCRYPTED PLAIN TEXT EXPORT"))
            assertFalse(c.state.value.resultIsError)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testImport_Success_RequiresRestart() = runTest {
        val manager = FakeManager()
        val c = controller(manager)

        c.import(File("in.mnex"), "correct horse")

        assertEquals(1, manager.importCalls)
        assertTrue("An import swaps the live DB; the app must restart to read it", c.state.value.requiresRestart)
        assertFalse(c.state.value.resultIsError)
    }

    @Test
    fun testVerify_DoesNotRequireRestart() = runTest {
        val manager = FakeManager()
        val c = controller(manager)

        c.verify(File("in.mnex"), "correct horse")

        assertEquals(1, manager.verifyCalls)
        assertEquals("Verify must not import", 0, manager.importCalls)
        assertFalse(c.state.value.requiresRestart)
        assertFalse(c.state.value.resultIsError)
    }

    @Test
    fun testImport_WrongPassphrase_IsAnErrorAndDoesNotAskForRestart() = runTest {
        val manager = FakeManager().apply { failWith = SecurityException("Chunk authentication failed (Tampered data)") }
        val c = controller(manager)

        c.import(File("in.mnex"), "wrong passphrase")

        val s = c.state.value
        assertTrue("A failed import must read as an error", s.resultIsError)
        assertEquals("Chunk authentication failed (Tampered data)", s.result)
        assertFalse("Nothing was swapped, so no restart is needed", s.requiresRestart)
        assertFalse(s.isBusy)
    }

    @Test
    fun testSecondOperationWhileBusy_IsIgnored() = runTest {
        // A manager that suspends until released, so the controller is genuinely mid-operation.
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val blocking = object : ExportManager {
            override suspend fun exportTo(file: File, passphrase: String, includeApiKeys: Boolean, includeEmbeddings: Boolean): ExportStats {
                calls++
                gate.await()
                return ExportStats(1, 1, 1, verified = true)
            }
            override suspend fun verifyOrImport(file: File, passphrase: String, isImport: Boolean): ExportStats =
                ExportStats(1, 1, 1, verified = true)
        }
        val c = ExportController(blocking, TestScope(UnconfinedTestDispatcher()))

        c.export(File("a.mnex"), "correct horse", includeApiKeys = false)
        assertTrue("First export must hold the controller busy", c.state.value.isBusy)

        c.export(File("b.mnex"), "correct horse", includeApiKeys = false)
        assertEquals("A second export while busy must be dropped", 1, calls)

        gate.complete(Unit)
        assertFalse(c.state.value.isBusy)
    }

    @Test
    fun testRunExport_SuspendsUntilDoneAndReportsSuccess() = runTest {
        val manager = FakeManager()
        val c = controller(manager)

        val ok = c.runExport(File("out.mnex"), "correct horse", includeApiKeys = false)

        assertTrue("runExport must not return before the write finished", ok)
        assertEquals(1, manager.exportCalls)
        assertFalse("The caller may copy the file only after isBusy clears", c.state.value.isBusy)
    }

    @Test
    fun testRunExport_Failure_ReturnsFalseAndLeavesTheStateAnError() = runTest {
        val manager = FakeManager().apply { failWith = SecurityException("Chunk authentication failed (Tampered data)") }
        val c = controller(manager)

        val ok = c.runExport(File("out.mnex"), "correct horse", includeApiKeys = false)

        assertFalse("The caller must not copy a failed export", ok)
        assertTrue(c.state.value.resultIsError)
        assertEquals("Chunk authentication failed (Tampered data)", c.state.value.result)
    }

    @Test
    fun testRunImport_SuspendsAndReturnsTrue() = runTest {
        val manager = FakeManager()
        val c = controller(manager)

        val ok = c.runImport(File("in.mnex"), "correct horse")

        assertTrue(ok)
        assertEquals(1, manager.importCalls)
        assertTrue(c.state.value.requiresRestart)
    }

    @Test
    fun testReportFailure_ShowsOutsideErrorInTheSameResultLine() = runTest {
        val c = controller(FakeManager())

        c.reportFailure("Could not read the chosen file: permission denied")

        assertTrue(c.state.value.resultIsError)
        assertEquals("Could not read the chosen file: permission denied", c.state.value.result)
        assertFalse(c.state.value.isBusy)
    }

    @Test
    fun testValidatePassphrase() {
        val c = controller(FakeManager())
        assertNull(c.validatePassphrase("12345678"))
        assertNotNull(c.validatePassphrase("1234567"))
    }
}
