// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.notifications

import android.content.Context
import com.anchor.core.storage.Storage
import com.anchor.core.ui.ChatEvents
import com.anchor.core.ui.MessageAttrs
import java.util.UUID

/**
 * Every time Mine starts a conversation by itself (a wake-up, a nudge, leaving home, after a
 * call), it goes through here: the text becomes a real assistant message in today's chat, tagged
 * proactive, the open chat refreshes at once, and — when asked — a notification points to it.
 * A notification alone is never the only trace.
 */
object ProactiveMessages {
    suspend fun post(
        context: Context,
        storage: Storage,
        text: String,
        source: String,
        kind: String = MessageAttrs.KIND_PROACTIVE,
        notify: Boolean = true,
    ): String {
        val id = "a_${UUID.randomUUID()}"
        storage.insertRawMessageWithAttrs(id, "assistant", text, null, MessageAttrs.proactive(source, kind))
        ChatEvents.externalMessageAdded(id)
        if (notify) ChatReplyNotifier.notify(context, text)
        return id
    }
}
