// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.rebuild

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.Edge
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
import com.anchor.core.models.SoulFact
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RebuildManagerTest {

    private lateinit var storage: SharedFakeStorage
    private lateinit var clock: FakeClock
    private lateinit var manager: RebuildManager

    @Before
    fun setUp() {
        storage = SharedFakeStorage()
        clock = FakeClock(1700000000000L)
        manager = RebuildManager(storage, clock)
    }

    @Test
    fun testPlanRebuild_CalculatesCountsAndAnchors() = runBlocking {
        // Insert an approved node and an extracted node
        val anchorNode = Node("n1", "task", "Anchor Task", "active", origin = "approved")
        val extractedNode = Node("n2", "task", "AI Extracted", "active", origin = "extracted")
        storage.createNodeAndCommit(anchorNode, RawEvent("e1", "CREATE", "node", "n1", tsUtc = clock.now()), null)
        storage.createNodeAndCommit(extractedNode, RawEvent("e2", "CREATE", "node", "n2", tsUtc = clock.now()), null)

        // Insert an approved soul fact
        val fact = SoulFact("s1", "preference", "Likes tea", 0.9, "manual", "user_edit", "active", clock.now())
        storage.createSoulFactAndCommit(fact, RawEvent("e3", "CREATE", "soul_fact", "s1", tsUtc = clock.now()))

        // Insert raw messages
        storage.insertRawMessage("m1", "user", "Message 1")
        storage.insertRawMessage("m2", "assistant", "Message 2")

        val plan = manager.planRebuild(RebuildTarget.DERIVED_NODES)
        assertEquals(RebuildTarget.DERIVED_NODES, plan.target)
        assertEquals(2, plan.totalRawMessages)
        assertEquals(1, plan.anchorNodeCount)
        assertEquals(1, plan.anchorSoulFactCount)
        assertEquals(clock.now(), plan.startedAt)

        val progress = manager.getProgress()
        assertEquals(RebuildStatus.PLANNING, progress.status)
        assertEquals(2, progress.totalMessages)
    }

    @Test
    fun testValidateAnchors_FailsWhenAnchorAltered() {
        val anchor1 = Node("n1", "task", "Important Anchor", "active", origin = "approved")
        val originalAnchors = listOf(anchor1)

        // Candidate where title was overwritten
        val corruptedCandidate = listOf(
            anchor1.copy(title = "Changed Title")
        )
        assertFalse(manager.validateAnchors(originalAnchors, corruptedCandidate))

        // Candidate where anchor was completely dropped
        assertFalse(manager.validateAnchors(originalAnchors, emptyList()))

        // Candidate where anchor is preserved intact
        assertTrue(manager.validateAnchors(originalAnchors, listOf(anchor1)))
    }

    @Test
    fun testValidateIntegrity_DetectsOrphanEdgesAndBlankTitles() {
        val anchor = Node("n1", "task", "Valid Anchor", "active", origin = "approved")
        val node2 = Node("n2", "task", "Second Node", "active", origin = "extracted")

        // Valid edge between n1 and n2
        val validEdge = Edge("ed1", "n1", "n2", type = "follow_up")
        val resultValid = manager.validateIntegrity(listOf(anchor), listOf(anchor, node2), listOf(validEdge))
        assertTrue(resultValid.isValid)
        assertEquals(0, resultValid.orphanEdgeCount)

        // Orphan edge pointing to non-existent node n99
        val orphanEdge = Edge("ed2", "n1", "n99", type = "follow_up")
        val resultOrphan = manager.validateIntegrity(listOf(anchor), listOf(anchor, node2), listOf(orphanEdge))
        assertFalse(resultOrphan.isValid)
        assertEquals(1, resultOrphan.orphanEdgeCount)
        assertTrue(resultOrphan.errors.any { it.contains("orphan edges") })

        // Node with blank title
        val blankNode = Node("n3", "task", "   ", "active", origin = "extracted")
        val resultBlank = manager.validateIntegrity(listOf(anchor), listOf(anchor, blankNode), emptyList())
        assertFalse(resultBlank.isValid)
        assertTrue(resultBlank.errors.any { it.contains("blank titles") })
    }

    @Test
    fun testProgressPersistence_And_ResumeOrDiscard() = runBlocking {
        // Record progress during a rebuild
        val initialProgress = RebuildProgress(
            target = RebuildTarget.FULL,
            processedMessages = 50,
            totalMessages = 100,
            status = RebuildStatus.RUNNING
        )
        manager.recordProgress(initialProgress)

        val retrieved = manager.getProgress()
        assertEquals(RebuildStatus.RUNNING, retrieved.status)
        assertEquals(50, retrieved.processedMessages)
        assertEquals(100, retrieved.totalMessages)

        // Simulate app kill and restart: resumeOrDiscard should mark status as PAUSED
        val statusOnRestart = manager.resumeOrDiscard(clock.now())
        assertEquals(RebuildStatus.PAUSED, statusOnRestart)
        assertEquals(RebuildStatus.PAUSED, manager.getProgress().status)

        // User discards rebuild
        manager.discardRebuild()
        assertEquals(RebuildStatus.IDLE, manager.getProgress().status)
    }
}
