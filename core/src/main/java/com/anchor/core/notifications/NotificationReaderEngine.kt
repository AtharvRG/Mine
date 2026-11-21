// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.notifications

import com.anchor.core.ambient.AmbientReport
import com.anchor.core.ambient.AmbientReportItem
import com.anchor.core.models.Node
import com.anchor.core.models.NodeStateSnapshot
import com.anchor.core.models.RawEvent
import com.anchor.core.storage.Storage
import com.anchor.core.time.DayRollover
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * One call per posted notification: triage it, record it in the feed, and pull out a suggestion.
 * The Android service only has to speak [Outcome.speech] and persist [Outcome.suggestion].
 */
class NotificationReaderEngine(
    private val triage: NotificationTriage = NotificationTriage(),
    val feed: HeardFeed = HeardFeed(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    data class Outcome(
        val decision: ReadDecision,
        /** Text to read aloud now, or null. */
        val speech: String?,
        /** The feed row created for this notification, or null when it was dropped. */
        val heard: HeardNotification?,
        /** A commitment worth proposing, or null. */
        val suggestion: NotificationSuggestion?,
    )

    fun onPosted(n: IncomingNotification, settings: NotificationReadSettings, ctx: ReaderContext): Outcome {
        val decision = triage.evaluate(n, settings, ctx)
        if (decision is ReadDecision.Drop) return Outcome(decision, null, null, null)

        val parts = NotificationSpeech.parts(n)
        val suggestion = if (settings.captureSuggestions) NotificationSuggestions.extract(n) else null
        val heard = HeardNotification(
            id = newId(),
            postedAt = n.postedAt,
            packageName = n.packageName,
            appLabel = parts.app,
            from = if (ctx.hideContent && !n.isMessaging) null else (parts.group?.let { g -> parts.from?.let { "$it · $g" } ?: g } ?: parts.from),
            preview = NotificationSpeech.preview(n, ctx.hideContent),
            spoken = decision is ReadDecision.Speak,
            holdReason = (decision as? ReadDecision.Hold)?.reason,
            suggestion = suggestion,
        )
        feed.add(heard)
        val speech = (decision as? ReadDecision.Speak)?.speech
        return Outcome(decision, speech, heard, suggestion)
    }

    fun takeCatchUp(settings: NotificationReadSettings, ctx: ReaderContext): String? = triage.takeCatchUp(settings, ctx)

    fun pendingCatchUpCount(): Int = triage.pendingCatchUpCount()

    fun reset() {
        triage.reset()
        feed.clear()
    }
}

/**
 * Saves a [NotificationSuggestion] into today's ambient review list, as a proposed node — the
 * same shape ambient listening uses, so Accept / Reject / Accept all already work and the item
 * is never active until the user says so. Duplicate summaries for the same day are ignored.
 */
class NotificationSuggestionWriter(
    private val storage: Storage,
    private val now: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** @return the new report item's id, or null when an identical suggestion already exists. */
    suspend fun save(s: NotificationSuggestion): String? {
        val ts = now()
        val dayRule = storage.getEffectiveDayRule(ts)
        val dayKey = DayRollover.computeDayKey(ts, dayRule)
        val report = storage.getLatestAmbientReport()?.takeIf { it.dayKey == dayKey }
            ?: AmbientReport(id = "amb_rep_${UUID.randomUUID()}", dayKey = dayKey, generatedAt = ts).also { storage.saveAmbientReport(it) }

        val existing = storage.getAmbientReportItems(report.id).map { it.summary.trim().lowercase() }.toSet()
        if (s.summary.trim().lowercase() in existing) return null

        val title = if (s.dueText != null) "${s.title} ${s.dueText}" else s.title
        val node = Node(
            id = "n_${UUID.randomUUID()}",
            type = "task",
            title = title.take(200),
            status = "proposed",
            origin = "notification",
            createdAt = ts,
            updatedAt = ts,
        )
        val event = RawEvent(
            opId = "u_${UUID.randomUUID()}",
            opType = "PROPOSE",
            targetType = "node",
            targetId = node.id,
            afterJson = json.encodeToString(NodeStateSnapshot(node, null)),
            tsUtc = ts,
        )
        storage.createNodeAndCommit(node, event, reminder = null)
        val itemId = UUID.randomUUID().toString()
        storage.saveAmbientReportItem(
            AmbientReportItem(
                id = itemId,
                reportId = report.id,
                windowId = null,
                kind = "proposal",
                summary = s.summary,
                nodeId = node.id,
                flags = FLAG_FROM_NOTIFICATION,
                state = "pending",
            )
        )
        return itemId
    }

    companion object {
        const val FLAG_FROM_NOTIFICATION = "from_notification"
    }
}
