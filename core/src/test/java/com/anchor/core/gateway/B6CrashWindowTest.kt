// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.context.ContextBuilder
import com.anchor.core.models.*
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.resolver.Resolver
import com.anchor.core.storage.Storage
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * B6: Characterization of the crash window "proposal consumed but effect not committed".
 *
 * D-118 (in-transaction CAS) closes the EXCEPTION path: if the effect transaction
 * throws, the guarded rollback reopens the proposal
 * (see testCrashWindow_EffectTransactionThrows_ProposalRemainsOpenAndNoEffectRow).
 * What remains open is the HARD-KILL path: the process dies between two durable
 * writes without throwing, so no catch/finally and no reopenProposal runs.
 *
 * These tests PASS on current code on purpose: the task is to record the observed
 * behaviour, not to fix it. Option (a)/(b) is not picked here; the design sketch
 * for (b) lives in docs/agent/evidence/b6-crash-window.md.
 */
class B6CrashWindowTest {

    @Test
    fun testCrashWindow_ConsumedWithoutEffect_SilentlyVanishesAfterRestart() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val fireAt = clock.now() + 3600_000L
        val payload = ProposalPayload(intent = IntentType.CREATE, title = "Crash window task")
        val proposal = Proposal(
            "p_kill_1", "time", IntentType.CREATE,
            listOf(ProposalOption(fireAt.toString(), "11:00 AM")),
            status = "open", payload = Json.encodeToString(payload)
        )
        storage.saveProposal(proposal)

        // The crash window: exactly the durable write Gateway performs first
        // (consumeProposal, e.g. the near-match dedupe and terminal-consume sites),
        // then the process dies before createNodeAndCommit runs. No exception is
        // thrown, so the guarded rollback never reopens the proposal.
        storage.consumeProposal("p_kill_1")

        // === Observed behaviour after restart ===
        // 1. The row survives the kill, permanently "consumed"
        assertEquals("consumed", storage.getProposal("p_kill_1")?.status)
        // 2. It is invisible to the open-proposal view
        assertTrue(storage.getOpenProposals().isEmpty())
        // 3. The model's restart view shows no pending clarification for it
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val sysMsg = contextBuilder.buildPack().first { it.role == "system" }
        assertEquals(
            "Consumed-without-effect proposal must be absent from the pending clarifications",
            "No open proposals.",
            sysMsg.content!!.substringAfter("Pending Clarifications:\n")
        )
        // 4. Effect not committed: no node, no event, no receipt, no undo trace
        assertTrue(storage.activeNodes.isEmpty())
        assertTrue(storage.loggedEvents.isEmpty())
        // 5. Re-tapping the chip after restart is rejected: the confirmed choice is lost
        val retry = gateway.executeTool(
            ToolCall(
                "confirm_change",
                mapOf("proposal" to JsonPrimitive("p_kill_1"), "choice" to JsonPrimitive(fireAt.toString()))
            )
        )
        assertTrue("Retry after crash must be rejected: $retry", retry is ToolResult.Error)
        assertEquals("Proposal already resolved", (retry as ToolResult.Error).reason)
        // 6. After the 24h expiry even the forensic trace is gone
        clock.currentTime = clock.now() + 25 * 3600_000L
        assertNull("Proposal row must expire after 24h", storage.getProposal("p_kill_1"))
    }

    @Test
    fun testCrashWindow_NearMatchDedupe_ConsumePersistsBeforeReplacementProposalSave() = runBlocking {
        // The near-match dedupe path consumes the old proposal and saves the
        // replacement proposal in TWO separate durable writes (Gateway.kt:607-608).
        // This proves the window is reachable through real Gateway code: at the
        // moment the second write begins, the first is already durable, so a hard
        // kill between them loses the user's pending clarification permanently
        // (old proposal consumed, replacement never saved).
        val clock = FakeClock(1_760_000_000_000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock)

        val due1 = clock.now() + 3600_000L          // existing item: 1h from now
        val chosenMillis = clock.now() + 5400_000L  // user's pick: 1.5h from now (30min apart, within the 2h dedupe window)
        val node1 = Node(
            "n_dentist", "task", "Dentist appointment", "active",
            dueAt = due1, createdAt = clock.now(), updatedAt = clock.now()
        )
        storage.createNodeAndCommit(node1, RawEvent("u_init", "CREATE", "node", "n_dentist"), null)

        val payload = ProposalPayload(intent = IntentType.CREATE, title = "Dentist appointment")
        val proposal = Proposal(
            "p_near_1", "time", IntentType.CREATE,
            listOf(ProposalOption(chosenMillis.toString(), "1:30 PM")),
            status = "open", payload = Json.encodeToString(payload)
        )
        storage.saveProposal(proposal)

        var statusAtSecondWrite: String? = null
        val windowStorage = object : Storage by storage {
            override suspend fun saveProposal(replacement: Proposal) {
                if (replacement.kind == "needs_confirm") {
                    statusAtSecondWrite = storage.getProposal("p_near_1")?.status
                }
                storage.saveProposal(replacement)
            }
        }
        val gateway = Gateway(windowStorage, Resolver(), dateResolver, clock)

        val res = gateway.executeTool(
            ToolCall(
                "confirm_change",
                mapOf("proposal" to JsonPrimitive("p_near_1"), "choice" to JsonPrimitive(chosenMillis.toString()))
            )
        )

        // Without a kill the flow completes normally...
        assertTrue("Near-match confirm must return NeedsConfirm: $res", res is ToolResult.NeedsConfirm)
        // ...but the first write was already durable when the second began: the window is real.
        assertEquals(
            "Old proposal must already be durably 'consumed' before the replacement is saved (crash window)",
            "consumed", statusAtSecondWrite
        )
        val replacement = storage.savedProposals.single { it.kind == "needs_confirm" }
        assertEquals("Replacement proposal must be open and answerable when no kill happens", "open", replacement.status)
    }
}
