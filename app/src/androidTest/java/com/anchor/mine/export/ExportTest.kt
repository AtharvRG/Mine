// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.core.time.FakeClock
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class ExportTest {

    private class FakeAlarmScheduler : AlarmScheduler {
        val scheduled = mutableListOf<Reminder>()
        val cancelled = mutableListOf<String>()
        override val lastFiredId: StateFlow<String?> = MutableStateFlow(null)

        override fun schedule(reminder: Reminder): ScheduleResult {
            scheduled.add(reminder)
            return ScheduleResult.Armed
        }

        override fun cancel(reminderId: String) {
            cancelled.add(reminderId)
        }
    }

    private fun writeEncryptedExport(
        file: File,
        passphrase: String,
        records: List<String>,
        mem: Int = 65536,
        iters: Int = 3,
        par: Int = 1,
        chunkSize: Int = 1024 * 1024
    ) {
        val rand = SecureRandom()
        val salt = ByteArray(16).apply { rand.nextBytes(this) }
        val wrapNonce = ByteArray(12).apply { rand.nextBytes(this) }
        val noncePrefix = ByteArray(8).apply { rand.nextBytes(this) }
        val dek = ByteArray(32).apply { rand.nextBytes(this) }

        val kek = AndroidExportManager.generateKek(passphrase, salt, mem, iters, par)
        val wrappedDek = AndroidExportManager.wrapKey(dek, kek, wrapNonce)

        val headerBuf = ByteBuffer.allocate(106)
        headerBuf.put("MNEX".toByteArray(Charsets.UTF_8))
        headerBuf.putShort(1)
        val flags = 1
        headerBuf.putShort(flags.toShort())
        headerBuf.put(1) // kdf_id = 1
        headerBuf.putInt(mem)
        headerBuf.putInt(iters)
        headerBuf.put(par.toByte())
        headerBuf.put(salt)
        headerBuf.put(wrapNonce)
        headerBuf.put(wrappedDek)
        headerBuf.putInt(chunkSize)
        headerBuf.put(noncePrefix)

        val headerBytes = headerBuf.array()
        val headerHash = MessageDigest.getInstance("SHA-256").digest(headerBytes)

        FileOutputStream(file).use { fos ->
            fos.write(headerBytes)
            ChunkedAesOutputStream(fos, dek, noncePrefix, headerHash, chunkSize).use { chunkedOut ->
                GZIPOutputStream(chunkedOut).use { gzipOut ->
                    val digestStream = HashingOutputStream(gzipOut)
                    OutputStreamWriter(digestStream, Charsets.UTF_8).use { writer ->
                        writer.write("""{"t":"manifest","v":1,"d":{"app_version":"1.0.0"}}""" + "\n")
                        for (r in records) {
                            writer.write(r + "\n")
                        }
                        writer.flush()
                        val footer = """{"t":"footer","d":{"sha256":"${digestStream.getHashHex()}"}}"""
                        writer.write(footer + "\n")
                    }
                }
            }
        }
    }

    @Test
    fun testExportAndVerify_RejectsBadPasswordAndTampering(): Unit = runBlocking {
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

        val manager = AndroidExportManager(
            context = context,
            dbHelper = dbHelper,
            dbKey = dbKey,
            liveDbName = "mine_export_test.db"
        )

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
            threwAuth = true
        }
        assertTrue("Must reject wrong password", threwAuth)

        // 4. Reject Tampering (Flip a byte in the encrypted payload)
        val raf = RandomAccessFile(exportFile, "rw")
        raf.seek(exportFile.length() - 10)
        val b = raf.read()
        raf.seek(exportFile.length() - 10)
        raf.write(b.inv())
        raf.close()

        var threwTamper = false
        try {
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

    @Test
    fun testHeaderKdfBounds_RejectsExcessiveMemoryOrIterationsOrChunkSize(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbKey = ByteArray(32) { 1 }
        val dbHelper = DatabaseProvider.getHelper(context, dbKey, "mine_kdf_test.db")
        val manager = AndroidExportManager(context, dbHelper, dbKey, liveDbName = "mine_kdf_test.db")

        fun testHeaderThrows(mem: Int, iters: Int, par: Int, chunkSize: Int, expectedSubstr: String) {
            val file = File(context.cacheDir, "kdf_test.mnex")
            if (file.exists()) file.delete()

            val headerBuf = ByteBuffer.allocate(106)
            headerBuf.put("MNEX".toByteArray(Charsets.UTF_8))
            headerBuf.putShort(1) // version
            headerBuf.putShort(1) // flags
            headerBuf.put(1) // kdf_id
            headerBuf.putInt(mem)
            headerBuf.putInt(iters)
            headerBuf.put(par.toByte())
            headerBuf.put(ByteArray(16)) // salt
            headerBuf.put(ByteArray(12)) // wrapNonce
            headerBuf.put(ByteArray(48)) // wrappedDek
            headerBuf.putInt(chunkSize)
            headerBuf.put(ByteArray(8)) // noncePrefix

            file.writeBytes(headerBuf.array())

            try {
                runBlocking { manager.verifyOrImport(file, "some_passphrase", false) }
                fail("Expected SecurityException for mem=$mem iters=$iters par=$par chunkSize=$chunkSize")
            } catch (e: SecurityException) {
                assertTrue(
                    "Message '${e.message}' should contain '$expectedSubstr'",
                    e.message!!.contains(expectedSubstr, ignoreCase = true)
                )
            } finally {
                file.delete()
            }
        }

        // 1. mem > 131072 KB
        testHeaderThrows(131073, 3, 1, 1024 * 1024, "safe bounds")

        // 2. mem <= 0
        testHeaderThrows(0, 3, 1, 1024 * 1024, "safe bounds")

        // 3. iters > 10
        testHeaderThrows(65536, 11, 1, 1024 * 1024, "safe bounds")

        // 4. iters <= 0
        testHeaderThrows(65536, 0, 1, 1024 * 1024, "safe bounds")

        // 5. par > 4
        testHeaderThrows(65536, 3, 5, 1024 * 1024, "safe bounds")

        // 6. chunkSize < 1024
        testHeaderThrows(65536, 3, 1, 512, "chunkSize")

        // 7. chunkSize > 16 MB
        testHeaderThrows(65536, 3, 1, 17 * 1024 * 1024, "chunkSize")

        dbHelper.close()
    }

    @Test
    fun testImport_RejectsUnknownTable(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_table_test.db")
        val dbKey = ByteArray(32) { 2 }
        val dbHelper = DatabaseProvider.getHelper(context, dbKey, "mine_table_test.db")
        val storage = AndroidStorage(dbHelper)
        storage.insertRawMessage("orig_msg", "user", "original")

        val manager = AndroidExportManager(context, dbHelper, dbKey, liveDbName = "mine_table_test.db")
        val testFile = File(context.cacheDir, "unknown_table.mnex")
        if (testFile.exists()) testFile.delete()

        val maliciousRecords = listOf(
            """{"t":"sqlite_master","v":1,"d":{"type":"table","name":"injected"}}"""
        )
        writeEncryptedExport(testFile, "password123", maliciousRecords)

        try {
            manager.verifyOrImport(testFile, "password123", isImport = true)
            fail("Expected SecurityException on unknown table")
        } catch (e: SecurityException) {
            assertTrue("Expected disallowed table error, got: ${e.message}", e.message!!.contains("Disallowed or unknown table"))
        }

        // Verify live DB was not corrupted/overwritten
        val cursor = dbHelper.readableDatabase.query("SELECT COUNT(*) FROM raw_message")
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
        cursor.close()

        dbHelper.close()
        testFile.delete()
    }

    @Test
    fun testImport_RejectsUnknownColumn(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_col_test.db")
        val dbKey = ByteArray(32) { 3 }
        val dbHelper = DatabaseProvider.getHelper(context, dbKey, "mine_col_test.db")
        val storage = AndroidStorage(dbHelper)
        storage.insertRawMessage("orig_msg2", "user", "original text")

        val manager = AndroidExportManager(context, dbHelper, dbKey, liveDbName = "mine_col_test.db")
        val testFile = File(context.cacheDir, "unknown_col.mnex")
        if (testFile.exists()) testFile.delete()

        val maliciousRecords = listOf(
            """{"t":"raw_message","v":1,"d":{"id":"bad_msg","role":"user","text":"hello","evil_column":"attack"}}"""
        )
        writeEncryptedExport(testFile, "password123", maliciousRecords)

        try {
            manager.verifyOrImport(testFile, "password123", isImport = true)
            fail("Expected SecurityException on unknown column")
        } catch (e: SecurityException) {
            assertTrue("Expected unknown column error, got: ${e.message}", e.message!!.contains("Disallowed or unknown column"))
        }

        // Verify live DB is intact
        val cursor = dbHelper.readableDatabase.query("SELECT COUNT(*) FROM raw_message")
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
        cursor.close()

        dbHelper.close()
        testFile.delete()
    }

    @Test
    fun testExport_ExcludesApiKeysWhenRequested(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_keys_src.db")
        context.deleteDatabase("mine_keys_dest1.db")
        context.deleteDatabase("mine_keys_dest2.db")

        val dbKey = ByteArray(32) { 4 }
        val srcHelper = DatabaseProvider.getHelper(context, dbKey, "mine_keys_src.db")
        val srcDb = srcHelper.writableDatabase

        // Insert API keys and normal meta into app_meta
        srcDb.execSQL("INSERT INTO app_meta (key, value) VALUES ('ep', 'https://api.openai.com')")
        srcDb.execSQL("INSERT INTO app_meta (key, value) VALUES ('key', 'sk-supersecret123')")
        srcDb.execSQL("INSERT INTO app_meta (key, value) VALUES ('mod', 'gpt-4o')")
        srcDb.execSQL("INSERT INTO app_meta (key, value) VALUES ('theme', 'dark_emerald')")

        val srcManager = AndroidExportManager(context, srcHelper, dbKey, liveDbName = "mine_keys_src.db")

        // 1. Export with includeApiKeys = false
        val fileNoKeys = File(context.cacheDir, "export_no_keys.mnex")
        srcManager.exportTo(fileNoKeys, "password123", includeApiKeys = false, includeEmbeddings = false)

        // Import into dest1
        val dest1Helper = DatabaseProvider.getHelper(context, dbKey, "mine_keys_dest1.db")
        val dest1Manager = AndroidExportManager(context, dest1Helper, dbKey, liveDbName = "mine_keys_dest1.db")
        dest1Manager.verifyOrImport(fileNoKeys, "password123", isImport = true)

        // Query dest1 app_meta: re-open helper
        val checkHelper1 = DatabaseProvider.getHelper(context, dbKey, "mine_keys_dest1.db")
        val checkDb1 = checkHelper1.readableDatabase
        val keysPresent = mutableSetOf<String>()
        checkDb1.query("SELECT key FROM app_meta").use { cursor ->
            while (cursor.moveToNext()) {
                keysPresent.add(cursor.getString(0))
            }
        }
        assertTrue("theme should be exported", keysPresent.contains("theme"))
        assertFalse("ep must NOT be exported when includeApiKeys=false", keysPresent.contains("ep"))
        assertFalse("key must NOT be exported when includeApiKeys=false", keysPresent.contains("key"))
        assertFalse("mod must NOT be exported when includeApiKeys=false", keysPresent.contains("mod"))
        checkHelper1.close()

        // 2. Export with includeApiKeys = true
        val fileWithKeys = File(context.cacheDir, "export_with_keys.mnex")
        srcManager.exportTo(fileWithKeys, "password123", includeApiKeys = true, includeEmbeddings = false)

        val dest2Helper = DatabaseProvider.getHelper(context, dbKey, "mine_keys_dest2.db")
        val dest2Manager = AndroidExportManager(context, dest2Helper, dbKey, liveDbName = "mine_keys_dest2.db")
        dest2Manager.verifyOrImport(fileWithKeys, "password123", isImport = true)

        val checkHelper2 = DatabaseProvider.getHelper(context, dbKey, "mine_keys_dest2.db")
        val checkDb2 = checkHelper2.readableDatabase
        val allKeys = mutableSetOf<String>()
        checkDb2.query("SELECT key FROM app_meta").use { cursor ->
            while (cursor.moveToNext()) {
                allKeys.add(cursor.getString(0))
            }
        }
        assertTrue(allKeys.contains("theme"))
        assertTrue(allKeys.contains("ep"))
        assertTrue(allKeys.contains("key"))
        assertTrue(allKeys.contains("mod"))

        srcHelper.close()
        checkHelper2.close()
        fileNoKeys.delete()
        fileWithKeys.delete()
    }

    @Test
    fun testPostImport_TriggersAlarmReconciliation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_alarm_src.db")
        context.deleteDatabase("mine_alarm_dest.db")

        val dbKey = ByteArray(32) { 5 }
        val now = 1770000000000L
        val fakeClock = FakeClock(now)
        val fakeScheduler = FakeAlarmScheduler()

        val srcHelper = DatabaseProvider.getHelper(context, dbKey, "mine_alarm_src.db")
        val srcStorage = AndroidStorage(srcHelper)

        val node = Node("node_r5", "task", "Alarm Test Task", "active")
        val reminder = Reminder(
            id = "rem_r5",
            text = "Time to check export alarms",
            fireAtMillis = now + 300_000L, // 5 mins in future
            nodeId = "node_r5",
            tier = AlarmTier.ALARM
        )
        srcStorage.createNodeAndCommit(node, RawEvent("e_r5", "CREATE", "node", "node_r5"), reminder)

        val exportFile = File(context.cacheDir, "alarm_export.mnex")
        val srcManager = AndroidExportManager(context, srcHelper, dbKey, liveDbName = "mine_alarm_src.db")
        srcManager.exportTo(exportFile, "alarm_pass123", includeApiKeys = false, includeEmbeddings = false)

        val destHelper = DatabaseProvider.getHelper(context, dbKey, "mine_alarm_dest.db")
        val destManager = AndroidExportManager(
            context = context,
            dbHelper = destHelper,
            dbKey = dbKey,
            liveDbName = "mine_alarm_dest.db",
            scheduler = fakeScheduler,
            clock = fakeClock
        )

        // Import should swap to mine_alarm_dest.db AND trigger post-import reconciliation
        val stats = destManager.verifyOrImport(exportFile, "alarm_pass123", isImport = true)
        assertTrue(stats.verified)

        // Verify scheduler received schedule call for rem_r5
        assertEquals(1, fakeScheduler.scheduled.size)
        assertEquals("rem_r5", fakeScheduler.scheduled.first().id)

        // Verify alarm_registry in dest DB has been updated
        val checkHelper = DatabaseProvider.getHelper(context, dbKey, "mine_alarm_dest.db")
        val checkStorage = AndroidStorage(checkHelper)
        val registered = checkStorage.getRegisteredAlarmRecords()
        assertEquals(1, registered.size)
        assertEquals("rem_r5", registered.first().reminderId)

        srcHelper.close()
        checkHelper.close()
        exportFile.delete()
    }
}
