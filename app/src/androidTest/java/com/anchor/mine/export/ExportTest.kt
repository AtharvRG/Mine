// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class ExportTest {

    @Test
    fun testExportAndVerify_RejectsBadPasswordAndTampering() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_export_test.db")
        val dbKey = ByteArray(32) { 9 }
        val dbHelper = DatabaseProvider.getHelper(context, dbKey, "mine_export_test.db")
        val storage = AndroidStorage(dbHelper)

        // Add dummy data
        storage.insertRawMessage("msg1", "user", "Hello Export")
        storage.insertRawMessage("msg2", "assistant", "Hi there")
        storage.createNodeAndCommit(
            Node("n1", "task", "Export Task", "active"),
            RawEvent("e1", "CREATE", "node", "n1"), null
        )

        val exportFile = File(context.cacheDir, "test_export.mnex")
        if (exportFile.exists()) exportFile.delete()

        // Fix: Pass the required context and dbKey parameters to the updated constructor
        val manager = AndroidExportManager(context, dbHelper, dbKey)

        // 1. Export and verify success
        val stats = manager.exportTo(exportFile, "strong_password123", false, false)
        assertTrue(stats.verified)
        assertEquals(2, stats.messages)
        assertEquals(1, stats.nodes)
        assertEquals(1, stats.events)

        // 2. Read back manually
        val readStats = manager.verifyOrImport(exportFile, "strong_password123", false)
        assertTrue(readStats.verified)
        assertEquals(2, readStats.messages)

        // 3. Reject Wrong Password
        var threwAuth = false
        try {
            manager.verifyOrImport(exportFile, "wrong_password", false)
        } catch (e: javax.crypto.AEADBadTagException) {
            threwAuth = true // BouncyCastle throws this on failed GCM unwrap
        }
        assertTrue("Must reject wrong password", threwAuth)

        // 4. Reject Tampering (Flip a byte in the encrypted payload)
        val raf = RandomAccessFile(exportFile, "rw")
        raf.seek(exportFile.length() - 10) // Seek into the ciphertext chunk
        val b = raf.read()
        raf.seek(exportFile.length() - 10)
        raf.write(b.inv()) 
        raf.close()

        var threwTamper = false
        try {
            // C2: Assert that Live DB is unchanged on failure
            manager.verifyOrImport(exportFile, "strong_password123", true)
        } catch (e: SecurityException) {
            threwTamper = true
            assertTrue(e.message!!.contains("Tampered data"))
        }
        assertTrue("Must reject tampered file", threwTamper)
        
        // Verify Live DB was not wiped
        val countCursor = dbHelper.readableDatabase.query("SELECT COUNT(*) FROM raw_message")
        countCursor.moveToFirst()
        assertEquals(2, countCursor.getInt(0))
        countCursor.close()

        dbHelper.close()
    }
}
