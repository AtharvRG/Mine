// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.*
import com.anchor.mine.alarms.BootReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds

@RunWith(AndroidJUnit4::class)
class AndroidStorageTest {

    private lateinit var storage: AndroidStorage
    private lateinit var dbHelper: androidx.sqlite.db.SupportSQLiteOpenHelper

    @Before
    fun setup() {
        // Clear global state leaked from AlarmSelfTest
        com.anchor.mine.alarms.AlarmReceiver.lastFiredId = null

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_test.db") // Isolated Test DB
        val key = ByteArray(32) { 1 } // Stable key for test
        dbHelper = DatabaseProvider.getHelper(context, key, "mine_test.db")
        storage = AndroidStorage(dbHelper)
    }

    @After
    fun teardown() {
        dbHelper.close()
    }

    @Test
    fun testSaveAndReadProposal() = runBlocking {
        val p = Proposal("p1", "ambiguous", IntentType.CANCEL, listOf("n1", "n2"))
        storage.saveProposal(p)
        
        val cursor = dbHelper.readableDatabase.query("SELECT options FROM proposal WHERE id = 'p1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("n1,n2", cursor.getString(0))
        cursor.close()
    }

    @Test
    fun testMessages_RolesAndNewest30() = runBlocking {
        for (i in 1..40) {
            storage.insertRawMessage("msg_$i", if (i % 2 == 0) "assistant" else "user", "Content $i")
            Thread.sleep(2) // Ensure distinct timestamps
        }
        
        val recent = storage.getRecentMessages(30)
        assertEquals(30, recent.size)
        // Must be chronological: oldest of the 30 first
        assertEquals("msg_11", recent.first().id)
        assertEquals("user", recent.first().role)
        assertEquals("msg_40", recent.last().id)
        assertEquals("assistant", recent.last().role)
    }

    @Test
    fun testCommitChange_UpdateNodeAndEvent() = runBlocking {
        // Initial insert
        val n = Node("n1", "task", "Test Task", "active")
        val e1 = RawEvent("e1", "CREATE", "node", "n1")
        val r = Reminder("r1", "Test", System.currentTimeMillis() + 10000)
        storage.createNodeAndCommit(n, e1, r)

        // Update
        val updatedNode = n.copy(status = "done", closedAt = 12345L)
        val e2 = RawEvent("e2", "COMPLETE", "node", "n1", beforeJson = "{}", afterJson = "{}")
        storage.updateNodeAndCommit(updatedNode, e2, "r1")

        // Assertions
        val fetchedNode = storage.getNode("n1")
        assertEquals("done", fetchedNode?.status)
        assertEquals(12345L, fetchedNode?.closedAt)

        val fetchedEvent = storage.getEvent("e2")
        assertEquals("COMPLETE", fetchedEvent?.opType)
        assertEquals("{}", fetchedEvent?.beforeJson)

        assertTrue(storage.getActiveReminders().isEmpty())
    }

    @Test
    fun testBootReceiver_FiresMissedReminders() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Fix: Use the REAL app storage for this test, because BootReceiver and AlarmReceiver
        // explicitly connect to the app's real database.
        val appStorage = (context.applicationContext as com.anchor.mine.MineApp).sharedStorage.await()
        
        // Push the reminder 70 seconds into the past so it formally qualifies as "missed" by the Sweeper
        val r = com.anchor.core.models.Reminder("r_missed", "Missed Task", System.currentTimeMillis() - 70000L)
        appStorage.saveReminder(r)

        com.anchor.mine.alarms.AlarmReceiver.lastFiredId = null

        val receiver = com.anchor.mine.alarms.BootReceiver()
        val intent = android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED)

        receiver.onReceive(context, intent)

        var elapsed = 0
        while (com.anchor.mine.alarms.AlarmReceiver.lastFiredId != "r_missed" && elapsed < 5000) {
            kotlinx.coroutines.delay(250.milliseconds)
            elapsed += 250
        }

        // Cleanup
        appStorage.deleteReminder("r_missed")

        org.junit.Assert.assertEquals("BootReceiver must broadcast the missed alarm to AlarmReceiver", "r_missed", com.anchor.mine.alarms.AlarmReceiver.lastFiredId)
    }

    @Test
    fun testStrictRedactionTrigger() = runBlocking {
        storage.insertRawMessage("msg_redact", "user", "Secret info")
        
        // 1. Normal UPDATE aborts
        var threw = false
        try {
            dbHelper.writableDatabase.execSQL("UPDATE raw_message SET content = 'Changed' WHERE id = 'msg_redact'")
        } catch (_: Exception) { threw = true }
        assertTrue("Normal update must abort", threw)

        // 2. Valid Redaction passes
        storage.redactMessage("msg_redact")
        val content = storage.getRawMessage("msg_redact")
        assertNull("BP11 requires content to be NULL after redaction", content)

        // 3. Double redaction aborts
        threw = false
        try {
            storage.redactMessage("msg_redact")
        } catch (_: Exception) { threw = true }
        assertTrue("Double redaction must abort", threw)
    }

    @Test
    fun testMonotonicDayKey_PreventsTimeTravel() = kotlinx.coroutines.runBlocking {
        val db = dbHelper.writableDatabase
        
        // 1. Manually insert a message with a "future" day_key (simulating clock was far ahead)
        db.execSQL(
            "INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>("msg_future", "test_dev", System.currentTimeMillis() - 10000, 0, "2099-12-31", "user", "Future")
        )
        
        // 2. Insert a normal message via AndroidStorage (clock is now back to reality)
        storage.insertRawMessage("msg_present", "user", "Present")
        
        // 3. Verify the new message was forced to inherit the future day_key to maintain monotonicity
        dbHelper.readableDatabase.query("SELECT day_key FROM raw_message WHERE id = 'msg_present'").use {
            org.junit.Assert.assertTrue(it.moveToFirst())
            org.junit.Assert.assertEquals("2099-12-31", it.getString(0))
        }
    }
}
