// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ambient

import com.anchor.core.models.Node
import com.anchor.core.voice.SyntheticAudioFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientCoreComponentsTest {

    @Test
    fun testVoiceprintSerializationRoundtrip() {
        val floats = floatArrayOf(0.1f, -0.5f, 0.9f, 0.0f, -0.3f)
        val vp = Voiceprint.fromFloatArray("vp-1", floats, "sherpa-onnx-speaker-v1", 1700000000L)
        assertEquals("vp-1", vp.id)
        val deserialized = vp.toFloatArray()
        assertEquals(floats.size, deserialized.size)
        for (i in floats.indices) {
            assertEquals(floats[i], deserialized[i], 1e-6f)
        }
    }

    @Test
    fun testNumberScrubber_ScrubsCreditCardPhoneAndOtp() {
        val input = "My card is 4111 2222 3333 4444 and my code is 849201. Call me at 555-123-4567 or SSN 123-45-6789."
        val scrubbed = NumberScrubber.scrub(input)
        assertFalse("Credit card should be scrubbed", scrubbed.contains("4111"))
        assertFalse("OTP code should be scrubbed", scrubbed.contains("849201"))
        assertFalse("Phone should be scrubbed", scrubbed.contains("555-123-4567"))
        assertFalse("SSN should be scrubbed", scrubbed.contains("123-45-6789"))
        assertTrue("Should contain [number]", scrubbed.contains("[number]"))
    }

    @Test
    fun testPresenceGate_EvaluatesPresenceAndTrimBuffer() {
        val presenceGate = PresenceGate(tHi = 0.80f, tLo = 0.60f, shortDurationSec = 1.5f, gateUserPresence = true)
        val userVoiceprint = SyntheticAudioFixtures.generateSyntheticVoiceprint(seed = 42L)
        val userSample = SyntheticAudioFixtures.generateSyntheticVoiceprint(seed = 42L)
        val otherSample = SyntheticAudioFixtures.generateSyntheticVoiceprint(seed = 999L)

        // User segment
        val userSegment = presenceGate.scoreSegment(
            tStartMs = 0L,
            tEndMs = 3000L,
            samples = floatArrayOf(),
            segmentEmbedding = userSample,
            voiceprintFloats = userVoiceprint
        )
        assertEquals(SpeakerLabel.USER, userSegment.label)
        assertFalse(userSegment.isShort)
        assertTrue(userSegment.score >= 0.80f)

        // Other segment
        val otherSegment = presenceGate.scoreSegment(
            tStartMs = 3500L,
            tEndMs = 6000L,
            samples = floatArrayOf(),
            segmentEmbedding = otherSample,
            voiceprintFloats = userVoiceprint
        )
        assertEquals(SpeakerLabel.OTHER, otherSegment.label)

        // Window with only 'other' should be rejected by gate
        assertFalse(presenceGate.evaluateWindowGate(listOf(otherSegment)))

        // Window with user segment should open gate
        assertTrue(presenceGate.evaluateWindowGate(listOf(otherSegment, userSegment)))

        // Test ring buffer trim
        val buffer = mutableListOf(userSegment, otherSegment)
        presenceGate.trimRingBuffer(buffer, maxRingDurationMs = 2000L, currentTimestampMs = 6000L)
        assertEquals(1, buffer.size)
        assertEquals(otherSegment, buffer.first())
    }

    @Test
    fun testDialogueWindowEngine_SplitsOnSilenceOrCaps() {
        val engine = DialogueWindowEngine(
            windowGapMs = 5000L,
            maxWindowDurationMs = 15000L,
            maxTurnsPerWindow = 3
        )

        val turns = listOf(
            DialogueWindowEngine.Turn(tStartMs = 1000L, tEndMs = 2000L, label = SpeakerLabel.OTHER, text = "Hello"),
            DialogueWindowEngine.Turn(tStartMs = 3000L, tEndMs = 4000L, label = SpeakerLabel.USER, text = "Hi there"),
            // 6 second gap -> split
            DialogueWindowEngine.Turn(tStartMs = 10000L, tEndMs = 12000L, label = SpeakerLabel.USER, text = "I'll do the report")
        )

        val windows = engine.splitIntoWindows(turns)
        assertEquals(2, windows.size)
        assertEquals(2, windows[0].turns.size)
        assertEquals(1, windows[1].turns.size)

        val prompt = engine.buildExtractionPrompt(windows[1])
        assertTrue(prompt.second.contains("[you]: I'll do the report"))
    }

    @Test
    fun testAmbientProposalExtractor_ProposalsAreProposedOnlyWithAttributionFlags() {
        val extractor = AmbientProposalExtractor(maxProposalsPerWindow = 5)
        val window = DialogueWindowEngine.Window(
            turns = listOf(
                DialogueWindowEngine.Turn(
                    tStartMs = 0L,
                    tEndMs = 1000L, // short
                    label = SpeakerLabel.USER,
                    text = "I will finish the audit tomorrow",
                    score = 0.65f,
                    isShort = true
                )
            ),
            startedAtMs = 0L,
            endedAtMs = 1000L
        )

        val existingNodes = listOf(
            Node(id = "n-1", type = "task", title = "Finish the audit", status = "active", createdAt = 0L, updatedAt = 0L)
        )

        val items = extractor.extract(
            window = window,
            llmResponseJson = null, // triggers rule-based fallback
            existingNodes = existingNodes,
            nowMs = 10000L
        )

        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("proposal", item.kind)
        val node = item.proposedNode
        assertNotNull(node)
        assertEquals("proposed", node!!.status)
        assertEquals("ambient", node.origin)
        // Attribution uncertain due to short / low score turn
        assertTrue(item.flags.contains("attribution_uncertain"))
    }

    @Test
    fun testPruneAmbientStaging_OnlyPrunesProposalsOlderThan7Days() = kotlinx.coroutines.runBlocking {
        val clock = com.anchor.core.time.FakeClock(10_000_000_000L) // e.g. Day 115
        val storage = com.anchor.core.SharedFakeStorage(clock)

        val now = clock.now()
        val eightDaysAgo = now - 8 * 86_400_000L
        val twoDaysAgo = now - 2 * 86_400_000L

        // Old report (> 7 days)
        val oldReport = AmbientReport("rep_old", "2026-11-01", eightDaysAgo)
        storage.saveAmbientReport(oldReport)
        storage.saveAmbientReportItem(
            AmbientReportItem("item_old", "rep_old", "win_1", "proposal", "Old task", "n_old", state = "pending")
        )

        // Recent report (< 7 days)
        val recentReport = AmbientReport("rep_recent", "2026-11-10", twoDaysAgo)
        storage.saveAmbientReport(recentReport)
        storage.saveAmbientReportItem(
            AmbientReportItem("item_recent", "rep_recent", "win_2", "proposal", "Recent task", "n_recent", state = "pending")
        )

        // Prune staging
        val prunedCount = storage.pruneAmbientStaging(now)
        assertEquals("Only items from reports > 7 days should be pruned", 1, prunedCount)

        val remainingOld = storage.getAmbientReportItems("rep_old")
        val remainingRecent = storage.getAmbientReportItems("rep_recent")
        assertEquals("Old report items should be pruned", 0, remainingOld.size)
        assertEquals("Recent pending proposal must NOT be pruned", 1, remainingRecent.size)
        assertEquals("item_recent", remainingRecent.first().id)
    }
}
