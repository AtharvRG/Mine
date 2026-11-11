// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.models

import com.anchor.core.SharedFakeStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxStaleTest {

    @Test
    fun dropStaleOutbox_DropsExpiredPendingJobsOnly() = runBlocking {
        val storage = SharedFakeStorage()
        val now = 100_000L

        // Expired job with deadline in the past
        val expiredJob = OutboxJob(
            id = "ob_expired",
            jobType = OutboxJob.JOB_OPENER_TEXT,
            priority = OutboxJob.PRIORITY_P1,
            payloadJson = "{}",
            idemKey = "idem_1",
            status = OutboxJob.STATUS_PENDING,
            nextTryAt = now - 5000L,
            deadlineAt = now - 1000L,
            createdAt = now - 10_000L,
            updatedAt = now - 10_000L
        )

        // Valid job with deadline in the future
        val validJob = OutboxJob(
            id = "ob_valid",
            jobType = OutboxJob.JOB_OPENER_TEXT,
            priority = OutboxJob.PRIORITY_P1,
            payloadJson = "{}",
            idemKey = "idem_2",
            status = OutboxJob.STATUS_PENDING,
            nextTryAt = now + 5000L,
            deadlineAt = now + 60_000L,
            createdAt = now - 10_000L,
            updatedAt = now - 10_000L
        )

        // Job without deadline (e.g. chat_turn)
        val noDeadlineJob = OutboxJob(
            id = "ob_no_deadline",
            jobType = OutboxJob.JOB_CHAT_TURN,
            priority = OutboxJob.PRIORITY_P0,
            payloadJson = "{}",
            idemKey = "idem_3",
            status = OutboxJob.STATUS_PENDING,
            nextTryAt = now - 5000L,
            deadlineAt = null,
            createdAt = now - 10_000L,
            updatedAt = now - 10_000L
        )

        storage.enqueueOutbox(expiredJob)
        storage.enqueueOutbox(validJob)
        storage.enqueueOutbox(noDeadlineJob)

        val droppedCount = storage.dropStaleOutbox(now)
        assertEquals(1, droppedCount)

        val pending = storage.getPendingOutbox(OutboxJob.JOB_OPENER_TEXT)
        assertEquals(listOf("ob_valid"), pending.map { it.id })

        val pendingTurns = storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN)
        assertEquals(listOf("ob_no_deadline"), pendingTurns.map { it.id })
    }
}
