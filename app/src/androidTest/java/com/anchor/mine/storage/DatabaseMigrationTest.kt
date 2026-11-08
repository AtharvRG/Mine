// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    @Test
    fun testFreshV5SmokeTest_RunsEveryStorageMethod() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_smoke_test.db")
        val key = ByteArray(32) { 2 }

        val helper = DatabaseProvider.getHelper(context, key, "mine_smoke_test.db")
        assertEquals(5, helper.readableDatabase.version)
        val storage = AndroidStorage(helper)

        // 1. Immutable Messages
        storage.insertRawMessage("msg_s1", "user", "Hello")
        assertEquals("Hello", storage.getRawMessage("msg_s1"))
        assertEquals(1, storage.getRecentMessages(10).size)
        storage.redactMessage("msg_s1")
        assertNull(storage.getRawMessage("msg_s1"))

        // 2. Proposals with v5 payload
        val p = Proposal("p_fresh", "ambiguous", IntentType.RESCHEDULE, listOf(ProposalOption("n1", "Choice 1")), payload = "{\"targetAtMillis\":1700000000000}")
        storage.saveProposal(p)
        val savedProp = storage.getProposal("p_fresh")
        assertNotNull(savedProp)
        assertEquals("{\"targetAtMillis\":1700000000000}", savedProp!!.payload)
        assertEquals(1, storage.getOpenProposals().size)
        storage.consumeProposal("p_fresh")
        assertEquals("consumed", storage.getProposal("p_fresh")?.status)
        assertEquals(0, storage.getOpenProposals().size)
        storage.updateProposalStatus("p_fresh", "open")
        assertEquals("open", storage.getProposal("p_fresh")?.status)
        assertEquals(1, storage.getOpenProposals().size)
        storage.consumeProposalCas("p_fresh")
        assertEquals("consumed", storage.getProposal("p_fresh")?.status)
        assertEquals(0, storage.getOpenProposals().size)
        val reopened = storage.reopenProposal("p_fresh")
        assertTrue(reopened)
        assertEquals("open", storage.getProposal("p_fresh")?.status)
        assertEquals(1, storage.getOpenProposals().size)
        val reopenAgain = storage.reopenProposal("p_fresh")
        org.junit.Assert.assertFalse(reopenAgain)

        // 3. Settings
        storage.saveSetting("set1", "val1")
        assertEquals("val1", storage.getSetting("set1"))

        // 4. Nodes and Events
        val n = Node("n_s", "task", "Smoke Task", "active", dueAt = 1700000000000L)
        val e = RawEvent("e_s", "CREATE", "node", "n_s")
        storage.createNodeAndCommit(n, e, null)
        assertNotNull(storage.getNode("n_s"))
        assertEquals(1, storage.getActiveNodes(limit = 100).size)
        assertEquals(1, storage.getActiveNodeCounts(1700000000000L).totalOpen)
        assertEquals(0, storage.getStaleNodes(1700000000000L, 100000L).size)
        assertEquals(1, storage.getUrgentNodes().size)

        storage.updateNodeAndCommit(n.copy(status="done"), RawEvent("e_s2", "UPDATE", "node", "n_s"), null)
        assertEquals("done", storage.getNode("n_s")?.status)
        assertNotNull(storage.getEvent("e_s2"))

        // 5. Reminders
        val rem = Reminder("rem_1", "Smoke Reminder", 1700000000000L, "n_s", AlarmTier.ALARM)
        storage.saveReminder(rem)
        assertEquals(rem.id, storage.getReminderByNodeId("n_s")?.id)
        assertEquals(1, storage.getPendingReminders().size)
        storage.deleteReminder("rem_1")
        assertNull(storage.getReminderByNodeId("n_s"))

        val stats = storage.getDebugStats()
        assertTrue(stats.containsKey("Rows in raw_message"))

        // 6. FTS Sync Verification
        val nFts = Node("n_fts", "task", "Bananas", "active")
        val eFts = RawEvent("e_fts", "CREATE", "node", "n_fts")
        storage.createNodeAndCommit(nFts, eFts, null)
        storage.createSoulFactAndCommit(SoulFact("s_1", "diet", "Allergic to Apples", 1.0, "chat", "approved", "active", 1000L), RawEvent("e_soul", "CREATE", "soul", "s_1"))

        val searchTasks = storage.searchMemoryFts("Bananas", 5)
        assertTrue("Node FTS Trigger must fire and populate index", searchTasks.any { it.title == "Bananas" })

        val searchFacts = storage.searchMemoryFts("Apples", 5)
        assertTrue("Soul FTS Trigger must fire and populate index", searchFacts.any { it.details == "Allergic to Apples" })

        storage.insertRawMessage("msg_smoke_chat", "user", "Remember to call Dr Watson")
        val searchChat = storage.searchMemoryFts("Watson", 5)
        assertTrue("Msg Chunk FTS Trigger must fire and populate index", searchChat.any { it.kind == "chat" && it.details.contains("Watson") })
        storage.redactMessage("msg_smoke_chat")
        val searchChatAfterRedact = storage.searchMemoryFts("Watson", 5)
        assertTrue("Msg Chunk FTS Trigger must delete entry upon redaction", searchChatAfterRedact.none { it.details.contains("Watson") })

        // 7. Edges
        val edge = Edge("e1", "n_s", "n_fts", EdgeTypes.FOLLOW_UP)
        storage.saveEdge(edge)
        assertNotNull(storage.getEdge("e1"))
        assertEquals(1, storage.getEdgesForSource("n_s").size)
        assertEquals(1, storage.getEdgesForDestination("n_fts").size)
        storage.deleteEdge("e1")
        assertNull(storage.getEdge("e1"))

        // 8. Quotes
        val quote = Quote(QuoteSlot.MORNING, "2026-11-03", "A fresh morning.", 1000L, source = QuoteSource.PRESET)
        storage.saveQuoteSlot(quote)
        assertEquals("A fresh morning.", storage.getQuoteSlot(QuoteSlot.MORNING, "2026-11-03")?.text)

        // 9. Reliability State & Alarm Registry
        val rel = ReliabilityState(score = "at_risk", issuesJson = "{\"error\":\"test\"}", lastCheckAt = 12345L)
        storage.saveReliabilityState(rel)
        val loadedRel = storage.getReliabilityState()
        assertEquals("at_risk", loadedRel.score)
        assertEquals("{\"error\":\"test\"}", loadedRel.issuesJson)
        assertEquals(12345L, loadedRel.lastCheckAt)

        storage.saveRegisteredAlarm("rem_reg_1", 1001, 1700000000000L)
        val registered = storage.getRegisteredAlarms()
        assertTrue(registered.any { it.first == "rem_reg_1" && it.second == 1700000000000L })
        storage.deleteRegisteredAlarm("rem_reg_1")
        assertTrue(storage.getRegisteredAlarms().none { it.first == "rem_reg_1" })

        helper.close()
    }

    /**
     * T19: an upgrade must repair triggers and indexes, not just add columns.
     *
     * Every trigger in the schema was declared `CREATE TRIGGER IF NOT EXISTS`, so a device that
     * had already created a trigger kept the old body forever - editing the trigger SQL never
     * reached an upgraded install. Indexes were worse: they existed only in `onCreate`, so a
     * device upgraded from v1 never got `idx_reminder_fire`.
     *
     * The test corrupts a trigger body and drops the index on a live DB, then runs `onUpgrade`
     * and asserts the schema is back to what a fresh install has.
     */
    @Test
    fun testOnUpgrade_RestoresTriggersAndIndexes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "mine_upgrade_test.db"
        context.deleteDatabase(dbName)
        val key = ByteArray(32) { 3 }

        val helper = DatabaseProvider.getHelper(context, key, dbName)
        val db = helper.writableDatabase

        // Simulate a stale install: a trigger with an old body that no longer enforces anything,
        // and a missing index.
        db.execSQL("DROP TRIGGER IF EXISTS trg_raw_msg_no_delete")
        db.execSQL(
            "CREATE TRIGGER trg_raw_msg_no_delete BEFORE DELETE ON raw_message " +
                "BEGIN SELECT RAISE(ABORT, 'stale body that does nothing'); END;"
        )
        db.execSQL("DROP INDEX IF EXISTS idx_reminder_fire")

        MineDatabaseCallback().onUpgrade(db, 1, 5)

        // The index must exist again.
        var indexFound = false
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_reminder_fire'").use { c ->
            indexFound = c.moveToFirst()
        }
        assertTrue("T19: onUpgrade must recreate idx_reminder_fire", indexFound)

        // The trigger body must be the current one, not the stale one.
        var triggerSql = ""
        db.query("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='trg_raw_msg_no_delete'").use { c ->
            if (c.moveToFirst()) triggerSql = c.getString(0)
        }
        assertTrue(
            "T19: onUpgrade must replace the stale trigger body (got: $triggerSql)",
            triggerSql.contains("raw_message is append-only") && !triggerSql.contains("stale body")
        )

        // And it must actually enforce: a delete is refused.
        db.execSQL(
            "INSERT INTO raw_message (id, role, content) VALUES ('t19_msg', 'user', 'x')"
        )
        var deleteRefused = false
        try {
            db.execSQL("DELETE FROM raw_message WHERE id = 't19_msg'")
        } catch (e: Exception) {
            deleteRefused = true
        }
        assertTrue("T19: the restored trigger must refuse a raw_message delete", deleteRefused)

        helper.close()
        context.deleteDatabase(dbName)
    }
}
