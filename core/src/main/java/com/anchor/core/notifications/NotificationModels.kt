// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.notifications

/**
 * Notification reading while Ambient Listening is on.
 *
 * The Android listener turns each posted `StatusBarNotification` into an [IncomingNotification]
 * and hands it to [NotificationTriage]. Everything that decides *whether* and *how* to read a
 * notification lives here, in plain Kotlin, so it is unit-tested without a device.
 *
 * Privacy rules this package enforces (they are not optional settings):
 *  - Verification codes, card numbers and other long digit runs are never spoken or stored.
 *  - Notifications from Mine itself, ongoing/foreground-service notifications, progress bars,
 *    calls, alarms and media transport controls are never read.
 *  - Nothing is read unless the user turned the feature on AND ambient listening is active.
 *  - When "hide notification content" privacy is on, only the app (and sender) is spoken.
 */
data class IncomingNotification(
    /** `StatusBarNotification.key`: stable per notification slot; reposts reuse it. */
    val key: String,
    val packageName: String,
    /** Human label of the app ("WhatsApp"). Falls back to the package name when unknown. */
    val appLabel: String,
    val title: String?,
    val text: String?,
    /** `EXTRA_BIG_TEXT` when present — usually the whole message instead of a truncated line. */
    val bigText: String? = null,
    /** `Notification.category` (msg, email, call, alarm, progress, transport, …). */
    val category: String? = null,
    val postedAt: Long,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    /** MessagingStyle: the sender of the newest message, when the app provides it. */
    val sender: String? = null,
    /** MessagingStyle: the group/conversation title ("Family"), null for 1:1 chats. */
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
    /** NotificationChannel importance (0..5). Null when unknown; treated as default (3). */
    val importance: Int? = null,
    /** True when the notification is posted silently (`setSilent`, only-alert-once repost). */
    val isSilentUpdate: Boolean = false,
    /** True when Android's DND policy lets this notification interrupt. Null = not in DND. */
    val passesDnd: Boolean? = null,
) {
    /** The best body we have: big text beats the one-line text. */
    val body: String? get() = bigText?.takeIf { it.isNotBlank() } ?: text?.takeIf { it.isNotBlank() }

    val isMessaging: Boolean
        get() = category == CATEGORY_MESSAGE || sender != null || conversationTitle != null

    companion object {
        const val CATEGORY_MESSAGE = "msg"
        const val CATEGORY_EMAIL = "email"
        const val CATEGORY_CALL = "call"
        const val CATEGORY_MISSED_CALL = "missed_call"
        const val CATEGORY_ALARM = "alarm"
        const val CATEGORY_PROGRESS = "progress"
        const val CATEGORY_TRANSPORT = "transport"
        const val CATEGORY_SERVICE = "service"
        const val CATEGORY_SYSTEM = "sys"
        const val CATEGORY_NAVIGATION = "navigation"
        const val CATEGORY_STATUS = "status"
        const val CATEGORY_STOPWATCH = "stopwatch"
        const val CATEGORY_LOCATION_SHARING = "location_sharing"
        const val CATEGORY_WORKOUT = "workout"
        const val CATEGORY_REMINDER = "reminder"
        const val CATEGORY_EVENT = "event"
        const val CATEGORY_PROMO = "promo"
        const val CATEGORY_RECOMMENDATION = "recommendation"
        const val CATEGORY_SOCIAL = "social"

        /** Categories that are never read aloud: they are state, not news. */
        val NEVER_READ_CATEGORIES = setOf(
            CATEGORY_CALL, CATEGORY_ALARM, CATEGORY_PROGRESS, CATEGORY_TRANSPORT, CATEGORY_SERVICE,
            CATEGORY_SYSTEM, CATEGORY_NAVIGATION, CATEGORY_STATUS, CATEGORY_STOPWATCH,
            CATEGORY_LOCATION_SHARING, CATEGORY_WORKOUT,
        )

        /** Marketing: read only if the user explicitly allowed the app. */
        val LOW_VALUE_CATEGORIES = setOf(CATEGORY_PROMO, CATEGORY_RECOMMENDATION)
    }
}

/** How much of a notification is spoken. */
enum class ContentLevel(val wire: String, val label: String) {
    /** "WhatsApp, Riya: running ten minutes late." */
    FULL("full", "Sender and message"),
    /** "New WhatsApp message from Riya." */
    SENDER_ONLY("sender", "Sender only"),
    /** "New notification from Gmail." */
    APP_ONLY("app", "App name only");

    companion object {
        fun fromWire(v: String?): ContentLevel = entries.firstOrNull { it.wire == v } ?: FULL
    }
}

/** Which apps may be read. */
enum class AppScope(val wire: String) {
    /** Every app that sends a normal-or-higher importance notification, minus the blocklist. */
    ALL("all"),
    /** Only conversations: messaging-style notifications and chat apps the user allowed. */
    MESSAGES("messages"),
    /** Only apps on the allowlist. */
    ALLOWLIST("allowlist");

    companion object {
        fun fromWire(v: String?): AppScope = entries.firstOrNull { it.wire == v } ?: MESSAGES
    }
}

/**
 * User settings for notification reading. Persisted as plain string settings so they ride the
 * existing encrypted settings table, the export allowlist and the backup format unchanged.
 */
data class NotificationReadSettings(
    val enabled: Boolean = false,
    /** Read aloud. When false, notifications are only captured as suggestions and listed. */
    val speak: Boolean = true,
    val contentLevel: ContentLevel = ContentLevel.FULL,
    val scope: AppScope = AppScope.MESSAGES,
    val allowedPackages: Set<String> = emptySet(),
    val blockedPackages: Set<String> = emptySet(),
    /** Speak only when headphones (wired, USB, Bluetooth audio) are connected. */
    val headphonesOnly: Boolean = false,
    /** Stay quiet outside the day window the user set in onboarding. */
    val respectQuietHours: Boolean = true,
    /** Turn "call me at 6" / "meeting moved to 5pm" into ambient suggestions for review. */
    val captureSuggestions: Boolean = true,
    /** Upper bound on spoken notifications per rolling minute; the rest are summarised. */
    val maxPerMinute: Int = 4,
) {
    fun toSettings(): Map<String, String> = mapOf(
        KEY_ENABLED to if (enabled) "1" else "0",
        KEY_SPEAK to if (speak) "1" else "0",
        KEY_CONTENT to contentLevel.wire,
        KEY_SCOPE to scope.wire,
        KEY_ALLOW to allowedPackages.sorted().joinToString(","),
        KEY_BLOCK to blockedPackages.sorted().joinToString(","),
        KEY_HEADPHONES to if (headphonesOnly) "1" else "0",
        KEY_QUIET to if (respectQuietHours) "1" else "0",
        KEY_CAPTURE to if (captureSuggestions) "1" else "0",
        KEY_MAX_PER_MIN to maxPerMinute.coerceIn(1, 20).toString(),
    )

    companion object {
        const val KEY_ENABLED = "notif_read.enabled"
        const val KEY_SPEAK = "notif_read.speak"
        const val KEY_CONTENT = "notif_read.content"
        const val KEY_SCOPE = "notif_read.scope"
        const val KEY_ALLOW = "notif_read.allow"
        const val KEY_BLOCK = "notif_read.block"
        const val KEY_HEADPHONES = "notif_read.headphones_only"
        const val KEY_QUIET = "notif_read.quiet_hours"
        const val KEY_CAPTURE = "notif_read.capture"
        const val KEY_MAX_PER_MIN = "notif_read.max_per_min"

        val ALL_KEYS = listOf(
            KEY_ENABLED, KEY_SPEAK, KEY_CONTENT, KEY_SCOPE, KEY_ALLOW, KEY_BLOCK,
            KEY_HEADPHONES, KEY_QUIET, KEY_CAPTURE, KEY_MAX_PER_MIN,
        )

        private fun csv(v: String?): Set<String> =
            v.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        /** Reads from any key-value source; unknown or corrupt values fall back to defaults. */
        fun fromSettings(get: (String) -> String?): NotificationReadSettings {
            val d = NotificationReadSettings()
            return NotificationReadSettings(
                enabled = get(KEY_ENABLED) == "1",
                speak = get(KEY_SPEAK)?.let { it == "1" } ?: d.speak,
                contentLevel = ContentLevel.fromWire(get(KEY_CONTENT)),
                scope = AppScope.fromWire(get(KEY_SCOPE)),
                allowedPackages = csv(get(KEY_ALLOW)),
                blockedPackages = csv(get(KEY_BLOCK)),
                headphonesOnly = get(KEY_HEADPHONES) == "1",
                respectQuietHours = get(KEY_QUIET)?.let { it == "1" } ?: d.respectQuietHours,
                captureSuggestions = get(KEY_CAPTURE)?.let { it == "1" } ?: d.captureSuggestions,
                maxPerMinute = get(KEY_MAX_PER_MIN)?.toIntOrNull()?.coerceIn(1, 20) ?: d.maxPerMinute,
            )
        }
    }
}

/**
 * The device's situation at the moment a notification arrives. The app layer fills it; the
 * engine never asks Android anything itself.
 */
data class ReaderContext(
    val nowMs: Long,
    /** Ambient service is in the Listening (or Processing) state. */
    val ambientActive: Boolean,
    val ownPackage: String,
    /** A phone or VoIP call is ringing or in progress. */
    val inCall: Boolean = false,
    /** The hands-free conversation is listening or speaking: never talk over the user. */
    val conversationBusy: Boolean = false,
    val headphonesConnected: Boolean = false,
    /** Minute of the local day, 0..1439. */
    val minuteOfDay: Int = 12 * 60,
    /** The user's day window from onboarding; outside it is quiet time. */
    val dayStartMinute: Int = 7 * 60,
    val dayEndMinute: Int = 22 * 60,
    /** "Hide notification content" privacy switch. Forces [ContentLevel.SENDER_ONLY] at most. */
    val hideContent: Boolean = false,
    /** Do Not Disturb is filtering interruptions right now. */
    val dndActive: Boolean = false,
)

/** Why a notification was not read aloud. Shown in the feed so silence is never a mystery. */
enum class SkipReason(val label: String, val silent: Boolean) {
    DISABLED("Notification reading is off", true),
    AMBIENT_OFF("Ambient listening is off", true),
    OWN_APP("From Mine", true),
    ONGOING("Ongoing notification", true),
    GROUP_SUMMARY("Group summary", true),
    EMPTY("No text", true),
    SILENT_UPDATE("Silent update", true),
    CATEGORY("Not a message", true),
    BLOCKED_APP("App is muted", true),
    NOT_IN_SCOPE("App not selected", true),
    LOW_PRIORITY("Low priority", true),
    DUPLICATE("Already read", true),
    // The rest are "heard but not spoken": they still appear in the feed.
    SPEECH_OFF("Reading aloud is off", false),
    IN_CALL("You were on a call", false),
    CONVERSATION("You were talking to Mine", false),
    QUIET_HOURS("Quiet hours", false),
    DND("Do Not Disturb", false),
    NO_HEADPHONES("Headphones not connected", false),
    RATE_LIMITED("Too many at once", false),
}

/** What to do with one notification. */
sealed interface ReadDecision {
    /** Read [speech] aloud. [summaryOfBurst] is true when it stands for several notifications. */
    data class Speak(val speech: String, val summaryOfBurst: Boolean = false) : ReadDecision

    /** Not spoken, but kept in the feed (and eligible for suggestion capture). */
    data class Hold(val reason: SkipReason) : ReadDecision

    /** Dropped entirely: never shown, never captured. */
    data class Drop(val reason: SkipReason) : ReadDecision
}

/** A commitment noticed inside a notification, proposed for the user's review. */
data class NotificationSuggestion(
    val title: String,
    /** Natural-language due time as written ("at 6", "tomorrow 5pm"); resolved on accept. */
    val dueText: String?,
    val summary: String,
)

/** One row of the "Heard while listening" feed. Kept in memory only; never written to disk. */
data class HeardNotification(
    val id: String,
    val postedAt: Long,
    val packageName: String,
    val appLabel: String,
    /** Who it is from, already privacy-filtered (null when hidden or unknown). */
    val from: String?,
    /** What was (or would have been) read aloud — already scrubbed of codes and numbers. */
    val preview: String,
    val spoken: Boolean,
    val holdReason: SkipReason?,
    val suggestion: NotificationSuggestion? = null,
    val suggestionSaved: Boolean = false,
)
