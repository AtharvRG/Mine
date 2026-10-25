// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.storage

import com.anchor.core.models.Node
import com.anchor.core.models.Proposal
import com.anchor.core.models.RawEvent
import kotlinx.coroutines.CoroutineDispatcher

interface Storage {
    val ioDispatcher: CoroutineDispatcher

    // Raw logs
    suspend fun insertRawMessage(id: String, content: String)
    suspend fun getRawMessage(id: String): String?

    // Gateway operations
    suspend fun getActiveNodes(): List<Node>
    suspend fun updateNodeStatus(nodeId: String, newStatus: String)
    suspend fun logEvent(event: RawEvent)
    suspend fun saveProposal(proposal: Proposal)
}