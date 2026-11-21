// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.models.ChatMessage

/**
 * P1-D 20 (D-121, Blueprint 50 s3.2/s3.5): composes the structured chat timeline.
 * Message roles map to bubbles; receipts attach after their cause message
 * (or the end of the timeline when unlinked); open proposals become chip rows;
 * quiet lifecycle lines are SystemEvents.
 */
object ChatTimelineBuilder {

    fun build(
        messages: List<ChatMessage>,
        receipts: List<ReceiptState>,
        openProposals: List<com.anchor.core.models.Proposal>
    ): List<ChatItem> {
        val items = mutableListOf<ChatItem>()
        var lastDayKey: String? = null

        for (msg in messages) {
            if (msg.dayKey != null && msg.dayKey != lastDayKey) {
                items.add(ChatItem.DayHeader(msg.dayKey, msg.tsUtc))
                lastDayKey = msg.dayKey
            }
            when (msg.role) {
                "system" -> if (!msg.content.isNullOrBlank()) {
                    items.add(ChatItem.SystemEvent(msg.content, msg.tsUtc))
                }
                else -> {
                    val attrs = MessageAttrs.parse(msg.attrs)
                    // A quoted message still in this window shows its current text (it may have been redacted since).
                    val reply = attrs.replyTo?.let { r ->
                        val live = messages.firstOrNull { it.id == r.id }
                        if (live != null) r.copy(preview = live.content?.take(MessageAttrs.PREVIEW_CHARS) ?: "This message was removed.") else r
                    }
                    items.add(ChatItem.Message(msg.id, msg.role, msg.content, msg.tsUtc, reply, attrs.kind))
                }
            }
            // Receipts caused by this message render directly under it.
            receipts.filter { it.causeMsgId == msg.id }.forEach { items.add(ChatItem.ReceiptItem(it)) }
        }

        // Receipts without a cause message (or whose cause message is outside the window) go last, in order.
        val linkedIds = messages.map { it.id }.toSet()
        receipts.filter { it.causeMsgId == null || it.causeMsgId !in linkedIds }.forEach { items.add(ChatItem.ReceiptItem(it)) }

        // Open proposals render as chip rows at the end of the timeline.
        openProposals.forEach { items.add(ChatItem.ProposalRow(it)) }

        return items
    }
}
