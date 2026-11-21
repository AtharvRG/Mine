package com.mine.companion.store

import com.anchor.core.models.Node
import com.anchor.core.models.Proposal
import com.anchor.core.ui.ChatItem as EngineChatItem
import com.anchor.core.ui.ReceiptState
import com.anchor.core.ui.UiState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Turns the engine's [UiState] into the presentation slices the screens were designed around.
 *
 * Pure and side-effect free: given the same engine state, clock and locale it returns the same
 * screen data. Labels are written for people ("Today 20:00", "Tomorrow 09:30", "Fri 20 Nov"),
 * never raw ids or epoch values.
 */
class EngineMapper(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Slices(
        val items: List<MineItem>,
        val recentlyClosedIds: List<String>,
        val reminders: List<ReminderEntry>,
        val todayMessages: List<ChatMessage>,
        val pastDayMessages: Map<String, List<ChatMessage>>,
        val livingNotes: List<LivingNote>,
        val soulFacts: List<SoulFact>,
        val agentWakeups: List<AgentWakeupUi>,
        val inboxItems: List<InboxTriageItem>,
        val statusChip: StatusChipState,
        val reliability: ReliabilityScore,
        val chatError: ChatErrorUi?,
    )

    fun map(e: UiState, now: Long, is24h: Boolean, selectedPastLabel: String?): Slices {
        val noteRefsByNode = e.noteReferences.groupBy { it.nodeId }
        val notesById = e.topicNotes.associateBy { it.id }

        val open = e.activeNodes.map { toItem(it, now, is24h, noteRefsByNode[it.id]?.firstOrNull()?.let { r -> r.noteId to r.mode }, notesById.keys) }
        val closed = e.recentlyClosed.map { rc -> toItem(rc.node, now, is24h, null, notesById.keys) }
        val items = (open + closed.filter { c -> open.none { it.id == c.id } })

        return Slices(
            items = items,
            recentlyClosedIds = e.recentlyClosed.map { it.node.id },
            reminders = reminders(e, now, is24h),
            todayMessages = timeline(e.chatItems, e, now, is24h),
            pastDayMessages = if (selectedPastLabel != null && e.selectedDayKey != null) {
                mapOf(selectedPastLabel to dayMessages(e, is24h))
            } else emptyMap(),
            livingNotes = notes(e, now),
            soulFacts = e.memorySoulFacts.map(::toSoul),
            agentWakeups = e.pendingWakeups.sortedBy { it.fireAt }.map { w ->
                AgentWakeupUi(
                    id = w.id,
                    scheduledFor = whenLabel(w.fireAt, now, is24h),
                    reasonMotto = w.reason,
                    sourceContext = when {
                        w.payloadJson?.contains("BREAK_TIMER") == true -> "Break timer"
                        w.payloadJson?.contains("DEFERRED_CHECKIN") == true -> "You asked me to check back"
                        else -> "Scheduled by Mine"
                    },
                    status = if (w.state == "fired") WakeupStatus.fired else WakeupStatus.armed,
                )
            },
            inboxItems = inbox(e, now, is24h),
            statusChip = statusChip(e, now),
            reliability = if (e.atRisk) ReliabilityScore.at_risk else ReliabilityScore.healthy,
            chatError = e.connectionError?.let { ChatErrorUi(it, e.connectionErrorRetryable && e.lastFailedMsgId != null, e.connectionErrorNeedsSettings) },
        )
    }

    // ---------------------------------------------------------------------------------------
    // Items
    // ---------------------------------------------------------------------------------------

    fun toItem(n: Node, now: Long, is24h: Boolean, noteRef: Pair<String, String>?, knownNotes: Set<String>): MineItem {
        val leaveHome = n.contextTrigger?.contains("LEAVE_HOME", ignoreCase = true) == true
        val history = parseHistory(n.dueHistoryJson).map { whenLabel(it, now, is24h, withComma = true) }
        return MineItem(
            id = n.id,
            title = n.title.ifBlank { "Untitled" },
            type = when {
                n.type == "habit" -> ItemType.habit
                leaveHome -> ItemType.errand
                else -> ItemType.task
            },
            status = status(n.status),
            dueLabel = when {
                leaveHome -> "When I leave home"
                n.dueAt != null -> whenLabel(n.dueAt!!, now, is24h)
                else -> "No date"
            },
            strikethroughHistory = history,
            slipCount = n.slipCount,
            linkedNoteId = noteRef?.first?.takeIf { it in knownNotes },
            linkedNoteMode = noteRef?.second?.let { if (it == "read_only") NoteAccessMode.read_only else NoteAccessMode.read_write },
            triggerType = if (leaveHome) TriggerType.leave_home else TriggerType.time,
            fallbackTime = n.fallbackFireAt?.let { timeOnly(it, is24h) },
            streakDays = n.streakCurrent.takeIf { it > 0 },
            bestStreakDays = n.streakBest.takeIf { it > 0 },
            localOnly = n.localOnly,
            provenance = provenance(n, now),
            relations = emptyList(),
        )
    }

    private fun status(s: String): ItemStatus = when (s.lowercase()) {
        "in_progress", "doing", "started" -> ItemStatus.in_progress
        "done", "completed", "closed" -> ItemStatus.done
        "paused", "blocked", "waiting" -> ItemStatus.paused
        "archived", "cancelled", "canceled" -> ItemStatus.archived
        "trashed", "deleted" -> ItemStatus.trashed
        else -> ItemStatus.todo
    }

    private fun provenance(n: Node, now: Long): String {
        val day = if (n.createdAt > 0) dayLabel(n.createdAt, now) else null
        val how = when (n.origin) {
            "chat", "chat_ai", "ai" -> "Created from chat"
            "quick_add", "offline_quick_add" -> "Added with quick add"
            "ambient" -> "Heard in conversation"
            "notification" -> "Suggested from a notification"
            "onboarding" -> "Added during setup"
            "worker" -> "Noticed by Mine in your chats"
            else -> "Added by you"
        }
        return if (day != null) "$how · $day" else how
    }

    private fun parseHistory(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.parseToJsonElement(raw).jsonArray.mapNotNull { it.jsonPrimitive.longOrNull } }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------------------------------
    // Reminders
    // ---------------------------------------------------------------------------------------

    private fun reminders(e: UiState, now: Long, is24h: Boolean): List<ReminderEntry> {
        val nodeById = e.activeNodes.associateBy { it.id }
        val upcoming = e.activeReminders.sortedBy { it.fireAtMillis }.map { r ->
            val node = r.nodeId?.let(nodeById::get)
            val leaveHome = node?.contextTrigger?.contains("LEAVE_HOME", true) == true
            ReminderEntry(
                id = r.id,
                title = r.text.ifBlank { node?.title ?: "Reminder" },
                time = whenLabel(r.fireAtMillis, now, is24h),
                tier = if (r.tier == com.anchor.core.models.AlarmTier.GENTLE) AlarmTierUi.GENTLE else AlarmTierUi.ALARM,
                status = ReminderStatus.upcoming,
                recurrence = recurrence(r.rrule ?: node?.recurrenceRule),
                triggerType = if (leaveHome) TriggerType.leave_home else TriggerType.time,
                fallbackTime = node?.fallbackFireAt?.let { timeOnly(it, is24h) },
            )
        }
        val missed = e.missedReminders.map { m ->
            ReminderEntry(
                id = m.reminderId,
                title = m.text,
                time = whenLabel(m.scheduledFor, now, is24h),
                tier = AlarmTierUi.ALARM,
                status = ReminderStatus.missed,
                recurrence = Recurrence.Once,
                triggerType = TriggerType.time,
            )
        }
        val done = e.recentlyClosed.filter { it.node.dueAt != null }.map { rc ->
            ReminderEntry(
                id = "done:${rc.node.id}",
                title = rc.node.title,
                time = rc.node.closedAt?.let { whenLabel(it, now, is24h) } ?: "Done",
                tier = AlarmTierUi.GENTLE,
                status = ReminderStatus.done,
                recurrence = recurrence(rc.node.recurrenceRule),
                triggerType = TriggerType.time,
            )
        }
        return upcoming + missed + done
    }

    private fun recurrence(rrule: String?): Recurrence {
        val r = rrule?.uppercase() ?: return Recurrence.Once
        return when {
            "BYDAY=MO,TU,WE,TH,FR" in r -> Recurrence.Weekdays
            "FREQ=DAILY" in r || r == "DAILY" -> Recurrence.Daily
            "FREQ=WEEKLY" in r || r == "WEEKLY" -> Recurrence.Weekly
            else -> Recurrence.Once
        }
    }

    // ---------------------------------------------------------------------------------------
    // Chat
    // ---------------------------------------------------------------------------------------

    private fun timeline(items: List<EngineChatItem>, e: UiState, now: Long, is24h: Boolean): List<ChatMessage> {
        val messages = items.filterIsInstance<EngineChatItem.Message>().map { ChatLine(it.id, it.role, it.content, it.tsUtc) }
        val statusLines = items.filterIsInstance<EngineChatItem.StatusNotice>()
        return buildThread(messages, e.receipts, e.openProposals, e.queuedChatIds.toSet(), is24h) +
            statusLines.map { s ->
                ChatMessage(
                    id = "status-${s.tsUtc}-${s.text.hashCode()}", sender = Sender.assistant, text = s.text,
                    timestamp = timeOnly(s.tsUtc, is24h), tsUtc = s.tsUtc, isSystem = true,
                )
            }.filter { it.tsUtc > 0 }
    }

    private fun dayMessages(e: UiState, is24h: Boolean): List<ChatMessage> =
        buildThread(e.selectedDayMessages.map { ChatLine(it.id, it.role, it.content, it.tsUtc) }, e.receipts, emptyList(), emptySet(), is24h)

    private data class ChatLine(val id: String, val role: String, val content: String?, val ts: Long)

    /**
     * Receipts are recorded against the user's message; the UI shows them under Mine's answer,
     * so each receipt moves to the first assistant message after its cause. A turn whose only
     * output was receipts gets a quiet assistant row of its own, so nothing is ever orphaned.
     */
    private fun buildThread(
        lines: List<ChatLine>,
        receipts: List<ReceiptState>,
        proposals: List<Proposal>,
        queued: Set<String>,
        is24h: Boolean,
    ): List<ChatMessage> {
        val byCause = receipts.filter { it.causeMsgId != null }.groupBy { it.causeMsgId!! }
        val out = ArrayList<ChatMessage>(lines.size + 4)
        var pendingReceipts = mutableListOf<ReceiptState>()
        var pendingCause: ChatLine? = null

        fun flushOrphans() {
            val cause = pendingCause ?: return
            if (pendingReceipts.isNotEmpty()) {
                out += ChatMessage(
                    id = "rcpt-${cause.id}", sender = Sender.assistant, text = "",
                    timestamp = timeOnly(pendingReceipts.last().timestamp.takeIf { it > 0 } ?: cause.ts, is24h),
                    tsUtc = cause.ts + 1, receipts = pendingReceipts.map(::toReceipt),
                )
            }
            pendingReceipts = mutableListOf()
            pendingCause = null
        }

        for (l in lines) {
            val isUser = l.role == "user"
            if (isUser) {
                flushOrphans()
                out += ChatMessage(
                    id = l.id, sender = Sender.user, text = l.content ?: "This message is kept on this phone only.",
                    timestamp = timeOnly(l.ts, is24h), tsUtc = l.ts, queued = l.id in queued,
                )
                pendingCause = l
                pendingReceipts = byCause[l.id].orEmpty().toMutableList()
            } else {
                val text = l.content.orEmpty()
                out += ChatMessage(
                    id = l.id, sender = Sender.assistant, text = text,
                    timestamp = timeOnly(l.ts, is24h), tsUtc = l.ts,
                    receipts = pendingReceipts.map(::toReceipt).ifEmpty { null },
                    isSystem = l.role == "system",
                )
                pendingReceipts = mutableListOf()
                pendingCause = null
            }
        }
        flushOrphans()

        // Open proposals ride on the newest assistant message (or a row of their own).
        val prompt = proposals.lastOrNull()?.let(::toPrompt)
        if (prompt != null) {
            val idx = out.indexOfLast { it.sender == Sender.assistant && !it.isSystem }
            if (idx >= 0 && idx == out.lastIndex) out[idx] = out[idx].copy(ambiguityPrompt = prompt)
            else out += ChatMessage(
                id = "prop-${proposals.last().id}", sender = Sender.assistant, text = "",
                timestamp = "", tsUtc = (out.lastOrNull()?.tsUtc ?: 0L) + 1, ambiguityPrompt = prompt,
            )
        }
        return out
    }

    fun toPrompt(p: Proposal): AmbiguityPrompt = AmbiguityPrompt(
        question = when (p.kind) {
            "time" -> "Which time did you mean?"
            "needs_confirm" -> "Something similar already exists."
            else -> when (p.intent) {
                com.anchor.core.models.IntentType.RESCHEDULE -> "Which one should I move?"
                com.anchor.core.models.IntentType.COMPLETE -> "Which one did you finish?"
                com.anchor.core.models.IntentType.CANCEL -> "Which one should I cancel?"
                else -> "Which one?"
            }
        },
        options = p.options.map { o ->
            AmbiguityOption(o.id, o.title, resolutionReceiptText = o.title)
        },
        proposalId = p.id,
    )

    private fun toReceipt(r: ReceiptState): DeterministicReceipt {
        val m = r.message.lowercase()
        val type = when {
            r.isError -> ReceiptActionType.error
            m.startsWith("resched") || m.startsWith("moved") || "snoozed" in m -> ReceiptActionType.reschedule
            m.startsWith("completed") || m.startsWith("done") -> ReceiptActionType.completed
            m.startsWith("cancel") || m.startsWith("deleted") || m.startsWith("removed") -> ReceiptActionType.removed
            "note" in m -> ReceiptActionType.note_update
            m.startsWith("reminder") || "alarm" in m -> ReceiptActionType.reminder_add
            "wake" in m || "check in" in m || "check back" in m -> ReceiptActionType.wakeup_armed
            m.startsWith("remembered") || "soul" in m || "fact" in m -> ReceiptActionType.memory
            m.startsWith("created") || m.startsWith("added") -> ReceiptActionType.created
            else -> ReceiptActionType.created
        }
        return DeterministicReceipt(
            id = r.id, text = r.message, timestamp = "", actionType = type,
            undoToken = r.undoToken, isError = r.isError,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Notes, soul, inbox
    // ---------------------------------------------------------------------------------------

    private fun notes(e: UiState, now: Long): List<LivingNote> {
        val refs = e.noteReferences.groupBy { it.noteId }
        return e.topicNotes.sortedWith(compareByDescending<com.anchor.core.models.TopicNote> { it.pinned }.thenByDescending { it.lastEditedAt }).map { n ->
            val bullets = runCatching { json.parseToJsonElement(n.bulletsJson).jsonArray.map { it.jsonPrimitive.content } }
                .getOrNull()?.filter { it.isNotBlank() }
                ?: n.content.lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }.filter { it.isNotEmpty() }
            val r = refs[n.id]
            val mode = r?.firstOrNull()?.mode ?: e.profile.prefs["note.mode.${n.id}"] ?: "read_write"
            LivingNote(
                id = n.id,
                title = n.topic.ifBlank { "Untitled note" },
                updatedAt = relative(n.lastEditedAt, now),
                pinned = n.pinned,
                linkedTaskId = r?.firstOrNull()?.nodeId,
                accessMode = if (mode == "read_only") NoteAccessMode.read_only else NoteAccessMode.read_write,
                bullets = bullets,
            )
        }
    }

    private fun toSoul(f: com.anchor.core.models.SoulFact): SoulFact = SoulFact(
        id = f.id,
        text = f.statement,
        category = soulCategory(f.category),
        confidence = when {
            f.confidence >= 0.8 -> 3
            f.confidence >= 0.5 -> 2
            else -> 1
        },
        source = when (f.source) {
            "chat", "chat_ai" -> SoulSource.chat
            "onboarding", "manual", "user" -> SoulSource.manual
            else -> SoulSource.inferred
        },
        pinned = f.status == "pinned",
        localOnly = f.localOnly,
        sensitive = f.sensitive,
    )

    private fun soulCategory(c: String): SoulCategory = when (c.lowercase().replace('-', '_').replace(' ', '_')) {
        "identity", "name", "work", "role", "about" -> SoulCategory.Identity
        "routine", "schedule", "habit", "habits", "day" -> SoulCategory.Routine
        "health", "medical", "fitness", "diet" -> SoulCategory.Health
        "reply_style", "style", "tone", "communication" -> SoulCategory.ReplyStyle
        "dislikes", "dislike", "avoid" -> SoulCategory.Dislikes
        "goals", "goal", "aspiration" -> SoulCategory.Goals
        "preference", "preferences", "likes", "like" -> SoulCategory.Preferences
        "people", "relationship", "family", "friends" -> SoulCategory.People
        else -> SoulCategory.Other
    }

    private fun inbox(e: UiState, now: Long, is24h: Boolean): List<InboxTriageItem> = buildList {
        for (p in e.openProposals) {
            val prompt = toPrompt(p)
            add(
                InboxTriageItem(
                    id = "p:${p.id}",
                    kind = if (p.kind == "needs_confirm") InboxKind.duplicate else InboxKind.ambiguity,
                    title = prompt.question,
                    subtitle = p.options.joinToString(" · ") { it.title }.take(140),
                    primaryActionLabel = p.options.firstOrNull()?.title ?: "Choose",
                    secondaryActionLabel = "Dismiss",
                    choices = prompt.options,
                )
            )
        }
        for (f in e.proposedSoulFacts) {
            add(
                InboxTriageItem(
                    id = "s:${f.id}",
                    kind = InboxKind.soul_proposal,
                    title = "Remember this? “${f.statement}”",
                    subtitle = "Mine noticed this in your chats. It's only saved if you accept.",
                    primaryActionLabel = "Remember",
                    secondaryActionLabel = "Not true",
                )
            )
        }
        for (a in e.ambientReportItems.filter { it.state == "pending" && it.kind == "proposal" }) {
            val flags = a.flags.orEmpty()
            add(
                InboxTriageItem(
                    id = "a:${a.id}",
                    kind = InboxKind.ambient,
                    title = a.summary,
                    subtitle = when {
                        "from_notification" in flags -> "From a notification while Mine was listening"
                        "manual_bookmark" in flags -> "From the moment you asked Mine to remember"
                        "attribution_uncertain" in flags -> "Heard nearby — not sure you said it"
                        "possible_duplicate" in flags -> "Heard in conversation · may already be on your list"
                        else -> "Heard in conversation"
                    },
                    primaryActionLabel = "Add to my list",
                    secondaryActionLabel = "Dismiss",
                )
            )
        }
        for (n in e.staleItems) {
            add(
                InboxTriageItem(
                    id = "st:${n.id}",
                    kind = InboxKind.stale_30d,
                    title = "Still want “${n.title}”?",
                    subtitle = "Untouched since ${dayLabel(n.updatedAt.takeIf { it > 0 } ?: n.createdAt, now)}.",
                    primaryActionLabel = "Keep it",
                    secondaryActionLabel = "Let it go",
                )
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------------------------------

    private fun statusChip(e: UiState, now: Long): StatusChipState = when {
        e.providerKey.isBlank() && !e.onDeviceFallbackEnabled -> StatusChipState.no_ai
        e.providerCooldownExpiry != null && e.providerCooldownExpiry!! > now -> StatusChipState.rate_limited
        e.atRisk || e.notificationsBlocked -> StatusChipState.reminders_at_risk
        e.queuedChatIds.isNotEmpty() -> StatusChipState.offline
        e.providerKey.isBlank() && e.onDeviceFallbackEnabled -> StatusChipState.backup_model
        else -> StatusChipState.normal
    }

    // ---------------------------------------------------------------------------------------
    // Time labels
    // ---------------------------------------------------------------------------------------

    private fun zdt(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

    fun timeOnly(ms: Long, is24h: Boolean): String {
        if (ms <= 0) return ""
        return zdt(ms).format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale))
    }

    /** "Today 20:00", "Tomorrow 9:30 AM", "Yesterday 18:00", "Fri 20 Nov, 18:00", "20 Nov 2027". */
    fun whenLabel(ms: Long, now: Long, is24h: Boolean, withComma: Boolean = false): String {
        val t = zdt(ms)
        val today = zdt(now).toLocalDate()
        val d = t.toLocalDate()
        val time = timeOnly(ms, is24h)
        val days = ChronoUnit.DAYS.between(today, d)
        return when {
            days == 0L -> "Today $time"
            days == 1L -> "Tomorrow $time"
            days == -1L -> "Yesterday $time"
            d.year == today.year -> t.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)) + ", $time"
            else -> t.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale)) + (if (withComma) ", $time" else "")
        }
    }

    fun dayLabel(ms: Long, now: Long): String {
        val d = zdt(ms).toLocalDate()
        val today = zdt(now).toLocalDate()
        return when (ChronoUnit.DAYS.between(d, today)) {
            0L -> "today"
            1L -> "yesterday"
            in 2L..6L -> d.format(DateTimeFormatter.ofPattern("EEEE", locale))
            else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMM" else "d MMM yyyy", locale))
        }
    }

    fun relative(ms: Long, now: Long): String {
        if (ms <= 0) return ""
        val diff = now - ms
        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000} min ago"
            zdt(ms).toLocalDate() == zdt(now).toLocalDate() -> "Today, ${timeOnly(ms, true)}"
            zdt(ms).toLocalDate() == zdt(now).toLocalDate().minusDays(1) -> "Yesterday, ${timeOnly(ms, true)}"
            else -> dayLabel(ms, now).replaceFirstChar { it.uppercase() }
        }
    }

    /** "Thu 12 Nov" — the label the day pages and date dial use for a past day. */
    fun pastDayLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
}
