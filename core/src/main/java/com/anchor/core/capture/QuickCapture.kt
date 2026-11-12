// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.capture

import com.anchor.core.models.ChatMessage
import com.anchor.core.storage.Storage
import java.util.UUID

/**
 * Blueprint 50 §3.14 / 90 row 19: quick capture.
 *
 * Three entry points write a user message without the chat screen being open: the inline reply
 * on a notification, the optional capture notification, and the launcher shortcut. All three
 * land here so the write path is one function, not three copies that drift.
 *
 * E-17 ("quick capture with the app dead") requires the text to be stored **immediately**, and
 * processed later. The store and the process are therefore separate calls: the receiver stores
 * first, then asks the app to process. A process that never happens leaves a stored message,
 * never a lost one.
 */
object QuickCapture {

    /** The tag blueprint 50 §4 assigns to a message that arrived through quick capture. */
    const val TAG_QUICK_REPLY = "quick_reply"

    /** Notification id of the optional persistent capture notification (blueprint 50 §3.14). */
    const val CAPTURE_NOTIFICATION_ID = 0x4D494E45 // "MINE"

    /** Blueprint 50 §3.14: the capture notification is optional and off by default. */
    const val SETTING_CAPTURE_NOTIFICATION = "capture.notification"

    /** Blueprint 52 §12: the launcher shortcut is on by default. */
    const val SETTING_CAPTURE_SHORTCUT = "capture.shortcut"

    fun newMessageId(): String = "q_${UUID.randomUUID()}"

    /**
     * Blueprint 50 §3.14: the capture notification is off unless the user turned it on. A setting
     * that was never written is "off" — the default is the absence of the key, not a stored false.
     */
    suspend fun isCaptureNotificationEnabled(storage: Storage): Boolean =
        storage.getSetting(SETTING_CAPTURE_NOTIFICATION) == "true"

    /** Blueprint 52 §12: the shortcut is on unless the user turned it off. */
    suspend fun isShortcutEnabled(storage: Storage): Boolean =
        storage.getSetting(SETTING_CAPTURE_SHORTCUT) != "false"

    /**
     * Blueprint 50 §3.14: the notification that showed "Sending..." must say when the turn failed.
     * The stored row is untouched — it is still unanswered, so the next start retries it.
     *
     * The id is passed so the app can update the row of the notification that is actually waiting
     * on this capture, rather than whichever one was armed most recently. It is null when the
     * caller sent text that was never stored (the composer path), which has no notification.
     *
     * The UI half lives in the app module (`CaptureBridge`); core cannot build a notification.
     */
    @Volatile
    var onCaptureFailed: ((messageId: String?) -> Unit)? = null

    fun notifyCaptureFailed(messageId: String?) {
        onCaptureFailed?.invoke(messageId)
    }

    /**
     * Stores [text] as a user message and returns its id, or null when there is nothing to store.
     *
     * The id is generated here rather than by the caller so the notification that shows
     * "Sending..." and the row it refers to can never disagree.
     */
    suspend fun store(storage: Storage, text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val id = newMessageId()
        storage.insertRawMessage(id, "user", trimmed)
        return id
    }

    /**
     * True when [messageId] is a quick-capture message that has not yet been answered.
     *
     * A captured message is answered when a later assistant message exists. This is the
     * "process when possible" half of E-17: a stored message whose turn never ran is retried
     * on the next app start, and a message that was already answered is left alone.
     */
    suspend fun isUnanswered(storage: Storage, messageId: String): Boolean {
        val content = storage.getRawMessage(messageId) ?: return false
        if (content.isBlank()) return false
        val recent = storage.getRecentMessages(200)
        val mine = recent.indexOfFirst { it.id == messageId }
        if (mine < 0) return false
        return !hasAssistantAfter(recent, mine)
    }

    private fun hasAssistantAfter(messages: List<ChatMessage>, index: Int): Boolean =
        messages.drop(index + 1).any { it.role == "assistant" && !it.content.isNullOrBlank() }

    /**
     * The unanswered quick-capture messages, oldest first, so a restart can process them in the
     * order the user wrote them.
     */
    suspend fun pending(storage: Storage): List<String> {
        val outboxPending = try {
            storage.getPendingOutbox(com.anchor.core.models.OutboxJob.JOB_CHAT_TURN)
                .mapNotNull { job -> job.idemKey.ifBlank { null } ?: (job.id.removePrefix("ob_")) }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val recent = storage.getRecentMessages(200)
        return recent.withIndex()
            .filter { (i, m) ->
                m.role == "user" && m.id.startsWith("q_") && m.id !in outboxPending && !hasAssistantAfter(recent, i)
            }
            .map { it.value.id }
    }
}
