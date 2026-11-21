// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.notifications

/**
 * Builds the sentence that is spoken for one notification, at the user's chosen detail level.
 *
 * The phrasing is written to be heard, not read: the source comes first so the listener can
 * decide in a second whether to keep listening ("Riya on WhatsApp: running late").
 */
object NotificationSpeech {

    data class Parts(
        val app: String,
        /** Person (1:1) or sender inside a group. */
        val from: String?,
        /** Group conversation title, when the message came from a group. */
        val group: String?,
        val body: String,
        val isCode: Boolean,
    )

    fun parts(n: IncomingNotification): Parts {
        val app = NotificationText.cleanName(n.appLabel) ?: n.packageName.substringAfterLast('.')
        val group = if (n.isGroupConversation) NotificationText.cleanName(n.conversationTitle) else null
        val from = NotificationText.cleanName(n.sender) ?: NotificationText.cleanName(n.title)
            ?.takeUnless { it.equals(app, ignoreCase = true) }
        val rawBody = n.body.orEmpty()
        val isCode = NotificationText.isVerificationCode(rawBody) || NotificationText.isVerificationCode(n.title)
        val cleaned = if (isCode) "" else NotificationText.cleanBody(rawBody)
        // Many apps put the newest line in both title and text; never say it twice.
        val body = if (from != null && cleaned.equals(from, ignoreCase = true)) "" else cleaned
        return Parts(app, from, group, body, isCode)
    }

    /**
     * The spoken sentence. [hideContent] caps detail at sender level whatever the user picked:
     * the privacy switch always wins over convenience.
     */
    fun compose(n: IncomingNotification, level: ContentLevel, hideContent: Boolean): String {
        val p = parts(n)
        val effective = if (hideContent && level == ContentLevel.FULL) ContentLevel.SENDER_ONLY else level

        if (p.isCode) return NotificationText.sentence("Verification code from ${p.app}. I won't read it out")

        if (n.category == IncomingNotification.CATEGORY_MISSED_CALL) {
            return when {
                effective == ContentLevel.APP_ONLY || p.from == null -> "Missed call."
                else -> NotificationText.sentence("Missed call from ${p.from}")
            }
        }

        val isEmail = n.category == IncomingNotification.CATEGORY_EMAIL
        return when (effective) {
            ContentLevel.APP_ONLY ->
                if (n.isMessaging) NotificationText.sentence("New ${p.app} message")
                else NotificationText.sentence("New notification from ${p.app}")

            ContentLevel.SENDER_ONLY -> when {
                isEmail && p.from != null -> NotificationText.sentence("Email from ${p.from}")
                p.group != null && p.from != null -> NotificationText.sentence("${p.from} in ${p.group}, on ${p.app}")
                p.group != null -> NotificationText.sentence("New message in ${p.group}, on ${p.app}")
                n.isMessaging && p.from != null -> NotificationText.sentence("Message from ${p.from} on ${p.app}")
                else -> NotificationText.sentence("New notification from ${p.app}")
            }

            ContentLevel.FULL -> {
                val body = p.body
                when {
                    isEmail && p.from != null ->
                        if (body.isBlank()) NotificationText.sentence("Email from ${p.from}")
                        else NotificationText.sentence("Email from ${p.from}: $body")
                    p.group != null && p.from != null ->
                        if (body.isBlank()) NotificationText.sentence("${p.from} in ${p.group}")
                        else NotificationText.sentence("${p.from} in ${p.group}: $body")
                    n.isMessaging && p.from != null ->
                        if (body.isBlank()) NotificationText.sentence("Message from ${p.from} on ${p.app}")
                        else NotificationText.sentence("${p.from} on ${p.app}: $body")
                    p.from != null && body.isNotBlank() ->
                        // Generic apps: the title is a headline ("Order shipped"), not a person.
                        if (body.startsWith(p.from, ignoreCase = true)) NotificationText.sentence("${p.app}: $body")
                        else NotificationText.sentence("${p.app}. ${NotificationText.sentence(p.from)} $body")
                    p.from != null -> NotificationText.sentence("${p.app}: ${p.from}")
                    body.isNotBlank() -> NotificationText.sentence("${p.app}: $body")
                    else -> NotificationText.sentence("New notification from ${p.app}")
                }
            }
        }
    }

    /**
     * What the feed shows for this notification: the same privacy filtering as speech, without
     * the spoken framing. Codes never appear here either.
     */
    fun preview(n: IncomingNotification, hideContent: Boolean): String {
        val p = parts(n)
        return when {
            p.isCode -> "Verification code (hidden)"
            hideContent -> if (n.isMessaging) "New message" else "New notification"
            p.body.isNotBlank() -> p.body
            p.from != null -> p.from
            else -> "New notification"
        }
    }

    /**
     * One line that stands for several held notifications: "Also: 3 messages from Riya on
     * WhatsApp, and 1 from Gmail." Senders are grouped so the sentence stays short.
     */
    fun burstSummary(items: List<IncomingNotification>, hideContent: Boolean): String? {
        if (items.isEmpty()) return null
        class Bucket(val label: String, val messaging: Boolean) { var count = 0 }
        val buckets = LinkedHashMap<String, Bucket>()
        for (n in items) {
            val p = parts(n)
            val who = when {
                p.isCode -> null
                hideContent && !n.isMessaging -> null
                p.group != null -> p.group
                n.isMessaging -> p.from
                else -> null
            }
            val label = if (who != null) "$who on ${p.app}" else p.app
            buckets.getOrPut(label) { Bucket(label, n.isMessaging) }.count++
        }
        val phrases = buckets.values.take(3).map { b ->
            val noun = when {
                b.messaging && b.count == 1 -> "message"
                b.messaging -> "messages"
                b.count == 1 -> "notification"
                else -> "notifications"
            }
            val count = if (b.count == 1) "a" else b.count.toString()
            "$count $noun from ${b.label}"
        }
        val rest = buckets.values.drop(3).sumOf { it.count }
        val joined = when (phrases.size) {
            1 -> phrases[0]
            2 -> "${phrases[0]} and ${phrases[1]}"
            else -> phrases.dropLast(1).joinToString(", ") + ", and " + phrases.last()
        }
        val tail = if (rest > 0) ", plus $rest more" else ""
        return NotificationText.sentence("Also, $joined$tail")
    }
}
