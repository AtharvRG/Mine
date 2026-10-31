// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.Proposal
import com.anchor.core.models.RawEvent
import com.anchor.core.models.SoulFact
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
    fun testFreshV1SmokeTest_RunsEveryStorageMethod() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_smoke_test.db")
        val key = ByteArray(32) { 2 }
        
        val helper = DatabaseProvider.getHelper(context, key, "mine_smoke_test.db")
        val storage = AndroidStorage(helper)
        
        // 1. Immutable Data
        storage.insertRawMessage("msg_s1", "user", "Hello")
        assertEquals("Hello", storage.getRawMessage("msg_s1"))
        storage.redactMessage("msg_s1")
        assertNull(storage.getRawMessage("msg_s1"))
        
        // 2. Proposals
        storage.saveProposal(Proposal("p_fresh", "ambiguous", IntentType.CREATE, listOf()))
        storage.consumeProposal("p_fresh")
        assertEquals("consumed", storage.getProposal("p_fresh")?.status)
        
        // 3. Settings
        storage.saveSetting("set1", "val1")
        assertEquals("val1", storage.getSetting("set1"))
        
        // 4. Nodes and Events
        val n = Node("n_s", "task", "Smoke Task", "active")
        val e = RawEvent("e_s", "CREATE", "node", "n_s")
        storage.createNodeAndCommit(n, e, null)
        assertNotNull(storage.getNode("n_s"))
        
        storage.updateNodeAndCommit(n.copy(status="done"), RawEvent("e_s2", "UPDATE", "node", "n_s"), null)
        assertEquals("done", storage.getNode("n_s")?.status)
        
        val stats = storage.getDebugStats()
        assertTrue(stats.containsKey("Rows in raw_message"))
        
        // 5. FTS Sync Verification
        val nFts = Node("n_fts", "task", "Bananas", "active")
        val eFts = RawEvent("e_fts", "CREATE", "node", "n_fts")
        storage.createNodeAndCommit(nFts, eFts, null)
        storage.createSoulFactAndCommit(SoulFact("s_1", "diet", "Allergic to Apples", 1.0, "chat", "approved", "active", 1000L), RawEvent("e_soul", "CREATE", "soul", "s_1"))
        
        val searchTasks = storage.searchMemoryFts("Bananas", 5)
        assertTrue("Node FTS Trigger must fire and populate index", searchTasks.any { it.title == "Bananas" })
        
        val searchFacts = storage.searchMemoryFts("Apples", 5)
        assertTrue("Soul FTS Trigger must fire and populate index", searchFacts.any { it.details == "Allergic to Apples" })
        
        helper.close()
    }
}
