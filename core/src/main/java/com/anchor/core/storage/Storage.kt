// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.storage

import com.anchor.core.models.ChatMessage
import com.anchor.core.models.DayRule
import com.anchor.core.models.Edge
import com.anchor.core.models.Node
import com.anchor.core.models.NodeCounts
import com.anchor.core.models.OutboxJob
import com.anchor.core.models.Proposal
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.core.models.SearchResult
import com.anchor.core.models.SoulFact
import kotlinx.coroutines.CoroutineDispatcher

interface Storage {
    val ioDispatcher: CoroutineDispatcher

    suspend fun insertRawMessage(id: String, role: String, content: String)

    suspend fun insertRawMessage(
        id: String,
        role: String,
        content: String,
        causeMsgId: String?,
        dayKey: String? = null
    )
    suspend fun redactMessage(id: String)
    suspend fun getRawMessage(id: String): String?
    suspend fun hasRawMessage(id: String): Boolean
    /** The newest [limit] messages, **oldest first** — the order the conversation was written. */
    suspend fun getRecentMessages(limit: Int): List<ChatMessage>

    suspend fun getSetting(key: String): String?
    suspend fun saveSetting(key: String, value: String)

    /**
     * Writes several settings as one unit. A crash or throw mid-way must leave the previous
     * values intact: a half-written provider config (new endpoint, old key) fails every request
     * with an error the user cannot interpret.
     */
    suspend fun saveSettings(values: Map<String, String>) {
        values.forEach { (k, v) -> saveSetting(k, v) }
    }

    suspend fun getEffectiveDayRule(tsUtc: Long): DayRule
    suspend fun saveDayRule(rule: DayRule)

    suspend fun getActiveNodes(limit: Int, cursor: String? = null): List<Node>
    suspend fun findActiveNodeByTitle(title: String): Node?
    suspend fun findActiveNodesByTitle(title: String): List<Node>
    suspend fun getNodesDueBetween(startMs: Long, endMs: Long, limit: Int): List<Node>
    suspend fun getOverdueNodes(beforeMs: Long, limit: Int): List<Node>
    suspend fun getRankedCandidates(now: Long, staleCutoffMs: Long, perTierLimit: Int = 25): List<Node>
    suspend fun getActiveNodeCounts(now: Long, staleCutoffMs: Long = 30L * 24 * 3600 * 1000L): NodeCounts
    suspend fun getStaleNodes(now: Long, cutoffMs: Long = 30L * 24 * 3600 * 1000L): List<Node>
    /** Blueprint 51 §3.1 / §3.9 (Gate E-12): Returns cancelled nodes from the last 30 days. */
    suspend fun getTrashNodes(now: Long, limit: Int = 100): List<Node>
    /** Blueprint 51 §3.1 / §3.9 (Gate E-12): Permanently scrubs and erases a node, its reminder, edges, and event tombstones. */
    suspend fun eraseNode(nodeId: String)
    /** Blueprint 51 §3.9 (Gate E-12): Permanently scrubs and erases a soul fact and its raw events. */
    suspend fun eraseSoulFact(factId: String)
    /** Blueprint 12: Returns true if a soul fact with this statement was previously rejected. */
    suspend fun isSoulFactSuppressed(statement: String): Boolean
    /**
     * Reminders whose fire instant is still ahead of [now]. R-25: the caller's clock is passed in
     * rather than read from the storage layer, so a test (or a device whose wall clock was moved)
     * sees the same "active" set the rest of the system computed against.
     */
    suspend fun getActiveReminders(now: Long, limit: Int = ACTIVE_REMINDER_PAGE_LIMIT): List<Reminder>
    /**
     * Every reminder row, in fire order, bounded to [limit]. R-26: the unbounded version made
     * reconcile and the missed-reminder sweeper read the whole history on every run — tolerable
     * at boot, a recurring full-table read once the sweeper runs periodically (H-02).
     *
     * The bound is a refusal to silently truncate, not a product rule: a full page means the
     * caller must not treat the result as complete. [AlarmReconciler] and the sweeper check
     * [PENDING_REMINDER_PAGE_LIMIT] and report a reliability issue instead of dropping the tail.
     */
    suspend fun getPendingReminders(limit: Int = PENDING_REMINDER_PAGE_LIMIT): List<Reminder>

    suspend fun saveProposal(proposal: Proposal)
    suspend fun getProposal(id: String): Proposal?
    suspend fun consumeProposal(id: String)
    suspend fun consumeProposalCas(id: String): Boolean
    suspend fun updateProposalStatus(id: String, status: String)
    suspend fun reopenProposal(id: String): Boolean

    suspend fun saveReminder(reminder: Reminder)
    suspend fun deleteReminder(id: String)
    suspend fun getDebugStats(): Map<String, String>

    suspend fun saveSoulFact(fact: SoulFact)
    suspend fun createSoulFactAndCommit(fact: SoulFact, event: RawEvent)
    /**
     * Searches memory (nodes, soul facts and conversation chunks) using SQLite FTS.
     * Chat hits are returned with kind "chat" and [SearchResult.tsUtc] set.
     */
    suspend fun searchMemoryFts(query: String, limit: Int): List<SearchResult>

    /**
     * Blueprint 21 §3.3: On-device quantized vector embeddings for semantic search.
     */
    suspend fun saveEmbedding(ownerType: String, ownerId: String, modelVersion: String, vector: com.anchor.core.retrieval.QuantVector) {}
    suspend fun getEmbeddings(ownerType: String, modelVersion: String): List<Pair<String, com.anchor.core.retrieval.QuantVector>> = emptyList()
    suspend fun deleteEmbedding(ownerType: String, ownerId: String) {}

    /**
     * Blueprint 23 §5 `search_history`: the original messages themselves, newest first.
     * Redacted messages are never returned.
     */
    suspend fun searchHistory(query: String, limit: Int): List<SearchResult>

    suspend fun getNode(id: String): Node?
    suspend fun getEvent(opId: String): RawEvent?
    suspend fun hasUndo(token: String): Boolean
    suspend fun getUndoneOpIds(sinceUtc: Long = 0L): Set<String>
    suspend fun getRecentEvents(limit: Int): List<RawEvent>

    suspend fun getProactiveState(): com.anchor.core.models.ProactiveState
    suspend fun saveProactiveState(state: com.anchor.core.models.ProactiveState)

    suspend fun getReliabilityState(): com.anchor.core.models.ReliabilityState
    suspend fun saveReliabilityState(state: com.anchor.core.models.ReliabilityState)
    suspend fun recordReminderFire(fire: com.anchor.core.models.ReminderFire)

    suspend fun getOpenProposals(): List<Proposal>
    suspend fun getUrgentNodes(): List<Node>

    // P1-D 20 (D-121): Home queries. Ranking is code-side (Kotlin), not SQL.
    suspend fun getHomePriorityList(now: Long, limit: Int = 5): List<Node>
    suspend fun getRecentlyClosed(now: Long, limit: Int = 10): List<Node>
    suspend fun getLatestEventForTarget(targetType: String, targetId: String): RawEvent?
    suspend fun getActiveSoulFacts(includeLocalOnly: Boolean = false): List<SoulFact>
    suspend fun getProposedSoulFacts(): List<SoulFact>
    suspend fun updateSoulFactStatus(id: String, status: String)
    suspend fun updateSoulFactStatusAndCommit(id: String, status: String, event: RawEvent)
    suspend fun getReminderByNodeId(nodeId: String): Reminder?
    /** R-26: the by-id lookup that stays correct when [getPendingReminders] pages out the row. */
    suspend fun getReminderById(id: String): Reminder?
    suspend fun clearAlarmRegistry()
    suspend fun hasReminderFire(reminderId: String, outcome: String): Boolean
    suspend fun getReminderFire(reminderId: String, outcome: String): com.anchor.core.models.ReminderFire?
    suspend fun getRecentReminderFires(limit: Int = 20): List<com.anchor.core.models.ReminderFire>

    // Purely Transactional Gateways
    suspend fun createNodeAndCommit(node: Node, event: RawEvent, reminder: Reminder?, edge: Edge? = null, edgeEvent: RawEvent? = null, consumeProposalId: String? = null)
    suspend fun updateNodeAndCommit(node: Node, event: RawEvent, deleteReminderId: String?, upsertReminder: Reminder? = null, consumeProposalId: String? = null, softDeleteEdgeIds: List<String> = emptyList())
    suspend fun undoChangeAndCommit(node: Node, event: RawEvent, restoreReminder: Reminder?)
    suspend fun createReminderAndCommit(reminder: Reminder, event: RawEvent, consumeProposalId: String? = null)
    suspend fun deleteReminderAndCommit(reminderId: String, event: RawEvent)

    suspend fun saveEdge(edge: Edge)
    suspend fun getEdge(id: String): Edge?
    suspend fun getEdgesForSource(srcId: String, type: String? = null): List<Edge>
    suspend fun getEdgesForDestination(dstId: String, type: String? = null): List<Edge>
    suspend fun deleteEdge(id: String)
    suspend fun createEdgeAndCommit(edge: Edge, event: RawEvent)
    suspend fun deleteEdgeAndCommit(edgeId: String, event: RawEvent)

    /**
     * Blueprint 13 §3.7 / Roadmap v1.1 Item 9:
     * Graph traversal up to [maxHops] (1-2 hops) around [nodeId], capped at [cap] (default 150).
     */
    suspend fun getGraphNeighborhood(nodeId: String, maxHops: Int = 1, cap: Int = 150): List<Node> = emptyList()

    /**
     * Blueprint 51 §50 / Roadmap v1.1 Item 8:
     * Groups nodes due between [startMs] and [endMs] into day buckets (dayKey -> List<Node>).
     */
    suspend fun getCalendarBuckets(startMs: Long, endMs: Long): Map<String, List<Node>> = emptyMap()

    /**
     * Blueprint 51 §199 / Roadmap v1.1 Item 8:
     * Groups nodes into board columns ("todo", "in_progress", "done") capped per column.
     */
    suspend fun getBoardViewNodes(limitPerColumn: Int = 50): Map<String, List<Node>> = emptyMap()

    suspend fun getRegisteredAlarms(): List<Pair<String, Long>>
    suspend fun getRegisteredAlarmRecords(): List<com.anchor.core.models.RegisteredAlarm> = getRegisteredAlarms().map { com.anchor.core.models.RegisteredAlarm(it.first, it.second, "alarm_clock") }
    suspend fun saveRegisteredAlarm(reminderId: String, requestCode: Int, registeredFor: Long, api: String = "alarm_clock")
    suspend fun deleteRegisteredAlarm(reminderId: String)

    // Calendar & Day Navigation Queries (D-107, UI Spec E6)
    suspend fun getDaysWithMessages(startDayKey: String, endDayKey: String): Set<String>
    suspend fun getHeatMapCounts(startDayKey: String, endDayKey: String): Map<String, Int>
    suspend fun getMessagesForDay(dayKey: String): List<ChatMessage>
    /**
     * Reminders in `[startMs, endMs]`, in fire order, bounded to [limit]. R-26: the range predicate
     * already existed, so this was never a full-table read — but it had no ceiling either, and a
     * wide range (a year, a corrupted day key) returned every row in it. The bound is generous
     * enough that no real day or week reaches it; reaching it means the range was wrong.
     */
    suspend fun getRemindersBetween(startMs: Long, endMs: Long, limit: Int = REMINDER_RANGE_PAGE_LIMIT): List<Reminder>
    suspend fun getRemindersForDay(startOfDayMs: Long, endOfDayMs: Long, limit: Int = REMINDER_RANGE_PAGE_LIMIT): List<Reminder>

    // Quote Slots (D-108, Blueprint 40)
    suspend fun getQuoteSlot(slot: com.anchor.core.models.QuoteSlot, dayKey: String): com.anchor.core.models.Quote?
    suspend fun saveQuoteSlot(quote: com.anchor.core.models.Quote)
    suspend fun getCompletedTodayCount(startOfDayMs: Long, endOfDayMs: Long): Int

    // Offline outbox (blueprint 41 §4, audit §6 item 6)
    /**
     * Records one unit of deferred work. [idemKey] is UNIQUE in the table, so enqueueing the same
     * logical job twice is a no-op rather than a duplicate turn.
     */
    suspend fun enqueueOutbox(job: OutboxJob)
    /** TB-2: Claim a job by advancing nextTryAt using UPDATE outbox instead of INSERT OR IGNORE. */
    suspend fun claimOutbox(id: String, nextTryAt: Long, now: Long)
    /** `pending` jobs of [jobType] whose `next_try_at` has passed, highest priority first, then oldest. */
    suspend fun getDueOutbox(jobType: String, now: Long): List<OutboxJob>
    /** Every `pending` job of [jobType], highest priority first — what the UI marks as queued. */
    suspend fun getPendingOutbox(jobType: String): List<OutboxJob>
    suspend fun markOutboxDone(id: String, now: Long)
    suspend fun markOutboxFailed(id: String, now: Long, nextTryAt: Long, error: String)
    /** Blueprint 41 §3.3 / §3.5: Marks pending outbox jobs past their deadline as dropped. Returns count. */
    suspend fun dropStaleOutbox(now: Long): Int

    // Learning & Behavioral Preferences (Blueprint 25)
    suspend fun recordSignal(signal: com.anchor.core.models.SignalLog)
    suspend fun getSignals(kind: String? = null, sinceUtc: Long = 0L, limit: Int = 1000): List<com.anchor.core.models.SignalLog>
    suspend fun getLearnedPref(key: String, scope: String): com.anchor.core.models.LearnedPref?
    suspend fun getAllLearnedPrefs(): List<com.anchor.core.models.LearnedPref>
    suspend fun saveLearnedPref(pref: com.anchor.core.models.LearnedPref)
    suspend fun resetLearning(preserveLocked: Boolean = true)

    /** Blueprint 43 §4 (v2 Context Sensing): Places and device-local sensing signals. */
    suspend fun savePlace(place: com.anchor.core.sensing.Place, signal: com.anchor.core.sensing.PlaceSignal? = null)
    suspend fun getPlaces(): List<com.anchor.core.sensing.Place>
    suspend fun getPlace(id: String): com.anchor.core.sensing.Place?
    suspend fun getPlaceSignals(): List<Pair<com.anchor.core.sensing.Place, com.anchor.core.sensing.PlaceSignal>>
    suspend fun deletePlace(id: String, deletedAt: Long)
    suspend fun recordSensingEvent(event: com.anchor.core.sensing.SensingEvent)
    suspend fun getRecentSensingEvents(limit: Int = 100): List<com.anchor.core.sensing.SensingEvent>
    suspend fun pruneSensingEvents(olderThanUtc: Long): Int

    /** Blueprint 44 §4 (v2 Call Nudge): Operational, device-local call events. */
    suspend fun saveCallEvent(callEvent: com.anchor.core.nudge.CallEvent)
    suspend fun getCallEvent(id: String): com.anchor.core.nudge.CallEvent?
    suspend fun getRecentCallEvents(limit: Int = 50): List<com.anchor.core.nudge.CallEvent>
    suspend fun getCallEventsBetween(startUtc: Long, endUtc: Long): List<com.anchor.core.nudge.CallEvent>
    suspend fun updateCallEventState(id: String, state: com.anchor.core.nudge.CallNudgeState, nudgeId: String? = null)
    suspend fun pruneCallEvents(olderThanUtc: Long): Int

    /** Blueprint 60 §4 (v2 Voice): Voice jobs and model assets. */
    suspend fun saveVoiceJob(job: com.anchor.core.voice.VoiceJob)
    suspend fun getVoiceJob(id: String): com.anchor.core.voice.VoiceJob?
    suspend fun getRecentVoiceJobs(limit: Int = 50): List<com.anchor.core.voice.VoiceJob>
    suspend fun updateVoiceJobState(id: String, state: com.anchor.core.voice.VoiceJobState, error: String? = null)
    suspend fun saveModelAsset(asset: com.anchor.core.voice.ModelAsset)
    suspend fun getModelAssets(): List<com.anchor.core.voice.ModelAsset>
    suspend fun getModelAsset(id: String): com.anchor.core.voice.ModelAsset?

    /** Prunes expired operational logs (diag_log 7d, reminder_fire 90d, outbox done/dropped 7d). */
    suspend fun pruneOperationalLogs(now: Long): Int

    /** Blueprint 61 §4 (v3 Ambient Listening): Voiceprint, sessions, windows, segments, reports. */
    suspend fun saveVoiceprint(voiceprint: com.anchor.core.ambient.Voiceprint)
    suspend fun getVoiceprint(): com.anchor.core.ambient.Voiceprint?
    suspend fun deleteVoiceprint()

    suspend fun saveAmbientSession(session: com.anchor.core.ambient.AmbientSession)
    suspend fun getRecentAmbientSessions(limit: Int = 50): List<com.anchor.core.ambient.AmbientSession>

    suspend fun saveAmbientWindow(window: com.anchor.core.ambient.AmbientWindow)
    suspend fun getAmbientWindows(sessionId: String): List<com.anchor.core.ambient.AmbientWindow>

    suspend fun saveAmbientSegment(segment: com.anchor.core.ambient.AmbientSegment)
    suspend fun getAmbientSegments(windowId: String): List<com.anchor.core.ambient.AmbientSegment>

    suspend fun saveAmbientReport(report: com.anchor.core.ambient.AmbientReport)
    suspend fun getAmbientReport(id: String): com.anchor.core.ambient.AmbientReport?
    suspend fun getLatestAmbientReport(): com.anchor.core.ambient.AmbientReport?
    suspend fun updateAmbientReportState(id: String, state: String)

    suspend fun saveAmbientReportItem(item: com.anchor.core.ambient.AmbientReportItem)
    suspend fun getAmbientReportItems(reportId: String): List<com.anchor.core.ambient.AmbientReportItem>
    suspend fun updateAmbientReportItemState(id: String, state: String, reviewedAt: Long?)

    suspend fun pruneAmbientStaging(now: Long): Int
    suspend fun deleteAllAmbientData(): Int

    /** Blueprint 21 §3.7 / Blueprint 32 §3.4: Day Digest / Overnight Consolidation. */
    suspend fun saveDayDigest(digest: com.anchor.core.consolidation.DayDigest)
    suspend fun getDayDigest(dayKey: String): com.anchor.core.consolidation.DayDigest?

    /** Blueprint 32 §3.5-§3.6 / P-007, P-008: Insights & Analytics Queries. */
    suspend fun getNodesCreatedBetween(startMs: Long, endMs: Long, limit: Int = 1000): List<Node>
    suspend fun getNodesClosedBetween(startMs: Long, endMs: Long, limit: Int = 1000): List<Node>
    suspend fun getCancelledEventsBetween(startMs: Long, endMs: Long, limit: Int = 1000): List<RawEvent>
    suspend fun getGoalNodes(): List<Node>
    suspend fun getHabitNodes(): List<Node>
    suspend fun getActionTimestampsBetween(startMs: Long, endMs: Long, limit: Int = 5000): List<Long>
    suspend fun getFullInsightsReport(startMs: Long, endMs: Long, nowMs: Long, includeMood: Boolean): com.anchor.core.insights.FullInsightsReport

    companion object {
        /** R-26: page size for [getPendingReminders]. Hitting it means "more rows exist". */
        const val PENDING_REMINDER_PAGE_LIMIT = 500

        /**
         * R-26: page size for [getActiveReminders], which backs the "Active Reminders" list on the
         * Home screen. Larger than a list a person reads; the point is only that the query stops.
         */
        const val ACTIVE_REMINDER_PAGE_LIMIT = 1000

        /** R-26: page size for [getRemindersBetween] / [getRemindersForDay]. */
        const val REMINDER_RANGE_PAGE_LIMIT = 1000
    }
}

class TooManyActiveNodesException(val cap: Int) : Exception("Too many active items (exceeded $cap). Please be more specific.")

class ProposalAlreadyResolvedException(message: String = "Proposal already resolved") : IllegalStateException(message)

suspend fun Storage.getAllActiveNodesPaged(pageSize: Int = 200, hardCap: Int = 5000): List<Node> {
    val all = mutableListOf<Node>()
    var cursor: String? = null
    while (true) {
        val page = getActiveNodes(limit = pageSize, cursor = cursor)
        if (page.isEmpty()) break
        all.addAll(page)
        if (all.size > hardCap) {
            throw TooManyActiveNodesException(hardCap)
        }
        if (page.size < pageSize) break
        val last = page.last()
        cursor = "${last.dueAt ?: "null"}:${last.id}"
    }
    // H2: local_only items are invisible to the model and to chat. getActiveNodes does not filter
    // them (it backs the Home list, which may show them locally), so the resolver pool built from
    // it must. Filtering here keeps every caller safe, including the Ambiguous branch that writes
    // node.title into a ProposalOption and from there into the prompt.
    return all.filter { !it.localOnly }
}

/**
 * P1-D 20 (D-121): Code-side Home ranking (no SQL ORDER BY).
 * Rank: 1) overdue first; 2) in_progress; 3) pinned (explicit user boost);
 * 4) higher severity; 5) higher priority; 6) due soonest (most overdue first);
 * 7) most recently updated. Ties fall back to stable ordering by id.
 */
fun rankHomeNodes(nodes: List<Node>, now: Long): List<Node> {
    return nodes.sortedWith(
        compareBy<Node> { it.dueAt == null || it.dueAt >= now }                    // overdue (rank 1)
            .thenBy { it.status != "in_progress" }                                 // in_progress (rank 2)
            .thenBy { !it.pinned }                                                 // pinned (rank 3)
            .thenByDescending { it.severity }                                      // severity (rank 4)
            .thenByDescending { it.priority }                                     // priority (rank 5)
            .thenBy { it.dueAt ?: Long.MAX_VALUE }                                 // due soonest / most overdue (rank 6)
            .thenByDescending { maxOf(it.updatedAt, it.createdAt) }                // recency (rank 7)
            .thenBy { it.id }                                                     // stable tiebreak
    )
}