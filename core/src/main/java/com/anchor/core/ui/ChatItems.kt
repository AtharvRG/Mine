// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.models.Node
import com.anchor.core.models.Proposal

/**
 * P1-D 20 (D-121): Structured chat timeline items (Blueprint 50 s3.2/s3.5).
 * The UI renders each item by type; raw_message roles map to Message,
 * receipts render next to their cause message, proposals render as chip rows,
 * nudges carry a tag chip and a "Why?" reason, and quiet lifecycle lines
 * are SystemEvents.
 */
sealed interface ChatItem {
    val tsUtc: Long

    data class DayHeader(val dayKey: String, override val tsUtc: Long) : ChatItem
    data class TimeDivider(override val tsUtc: Long) : ChatItem
    data class Message(val id: String, val role: String, val content: String?, override val tsUtc: Long) : ChatItem
    data class SystemEvent(val text: String, override val tsUtc: Long) : ChatItem
    data class ReceiptItem(val receipt: ReceiptState) : ChatItem {
        override val tsUtc: Long get() = receipt.timestamp
    }
    data class ProposalRow(val proposal: Proposal) : ChatItem {
        override val tsUtc: Long = 0L
    }
    data class CheckinCard(val title: String, val summary: String, override val tsUtc: Long) : ChatItem
    data class Nudge(val content: String, val tag: String?, val reason: String?, override val tsUtc: Long) : ChatItem
    data class StatusNotice(val text: String, val isError: Boolean, override val tsUtc: Long) : ChatItem
}

/** P1-D 20: A closed node on the Home list plus whether its closing event is still undoable. */
data class RecentlyClosed(
    val node: Node,
    val undoValid: Boolean,
    val undoToken: String?
)

/** P1-D 20 (Blueprint 31): The single open proactive thread, if any. */
data class ActiveThread(
    val msgId: String,
    val openedAt: Long
)
