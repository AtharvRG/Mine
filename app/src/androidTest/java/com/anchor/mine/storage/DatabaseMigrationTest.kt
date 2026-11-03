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
        assertEquals(0, storage.getStaleNodes(100000L, 1700000000000L).size)
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
}
