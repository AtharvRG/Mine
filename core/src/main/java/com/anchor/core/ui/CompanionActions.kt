// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.capture.RuleBasedQuickAdd
import com.anchor.core.gateway.Gateway
import com.anchor.core.gateway.ToolCall
import com.anchor.core.models.AgentWakeup
import com.anchor.core.models.NodeStateSnapshot
import com.anchor.core.models.RawEvent
import com.anchor.core.models.ToolResult
import com.anchor.core.notifications.NotificationReadSettings
import com.anchor.core.notifications.NotificationSuggestionWriter
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import com.anchor.core.time.DayRollover
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Deterministic actions behind the companion UI's buttons: complete, reschedule, quick add,
 * reminders, breaks, privacy toggles and preferences. None of these call the model.
 *
 * Everything that changes an item goes through [Gateway] when a gateway path exists, so the
 * same dedupe, time resolution, alarm scheduling and undo log apply whether the change came from
 * a chat turn or a tap. Each action yields exactly one [ActionResult]; failures are reported,
 * never thrown, so a tap can never crash the app or leave a spinner behind.
 */
internal class CompanionActions(
    private val storage: Storage,
    private val gateway: Gateway,
    private val alarmScheduler: AlarmScheduler,
    private val clock: Clock,
    private val quickAdd: RuleBasedQuickAdd,
    private val dateResolver: com.anchor.core.time.DateResolver,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun handle(action: UiAction.Direct): ActionResultDraft = try {
        run(action)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ActionResultDraft("Couldn't do that: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    private suspend fun run(a: UiAction.Direct): ActionResultDraft = when (a) {
        is UiAction.QuickAdd -> quickAdd(a.text)
        is UiAction.CompleteNode -> fromTool(gateway.handleUpdateNodeStatus(a.nodeId, "done"), a.nodeId)
        is UiAction.RescheduleNode -> {
            if (a.whenText.isBlank()) ActionResultDraft("To when?", isError = true)
            else fromTool(
                tool("propose_change", "intent" to "RESCHEDULE", "target_ref" to a.nodeId, "due_text" to a.whenText.trim()),
                a.nodeId,
            )
        }
        is UiAction.SetNodeLocalOnly -> setNodeLocalOnly(a.nodeId, a.localOnly)
        is UiAction.SetSoulFactLocalOnly -> setSoulLocalOnly(a.factId, a.localOnly)
        is UiAction.SetNoteReferenceMode -> setNoteMode(a.noteId, a.mode)
        is UiAction.CreateReminder -> createReminder(a)
        is UiAction.SnoozeReminder -> snooze(a.reminderId, a.minutes)
        is UiAction.CancelReminderUndoable -> cancelReminder(a.reminderId)
        is UiAction.StartBreak -> startBreak(a.minutes)
        is UiAction.EndBreak -> endBreak()
        is UiAction.PauseNudges -> {
            if (a.untilMs == null || a.untilMs <= clock.now()) {
                storage.saveSetting(KEY_NUDGES_PAUSED, "")
                ActionResultDraft("Nudges are back on")
            } else {
                storage.saveSetting(KEY_NUDGES_PAUSED, a.untilMs.toString())
                ActionResultDraft("Nudges paused")
            }
        }
        is UiAction.SetUserName -> {
            val name = a.name.trim().take(40)
            storage.saveSetting(KEY_USER_NAME, name)
            ActionResultDraft(if (name.isEmpty()) "Name cleared" else "Hi, $name")
        }
        is UiAction.SetLanguages -> {
            val langs = a.languages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf("English") }
            storage.saveSetting(KEY_LANGUAGES, langs.joinToString(","))
            ActionResultDraft("Languages updated")
        }
        is UiAction.SetDayWindow -> {
            val start = a.startMinute.coerceIn(0, 1439)
            val end = a.endMinute.coerceIn(0, 1439)
            if (start == end) ActionResultDraft("Pick a day that is not zero hours long.", isError = true)
            else {
                storage.saveDayRule(DayRollover.defaultRule(clock.now()).copy(startMinute = start, endMinute = end))
                ActionResultDraft("Day updated")
            }
        }
        is UiAction.SetPref -> {
            val key = a.key.trim()
            if (PREF_PREFIXES.none { key.startsWith(it) } || key.length > 64) ActionResultDraft("Unknown setting", isError = true)
            else {
                storage.saveSetting(key, a.value.take(512))
                ActionResultDraft("")
            }
        }
        is UiAction.SaveNotificationReading -> {
            storage.saveSettings(a.settings.toSettings())
            ActionResultDraft(if (a.settings.enabled) "Notification reading is on" else "Notification reading is off")
        }
        is UiAction.SaveNotificationSuggestion -> {
            val id = NotificationSuggestionWriter(storage) { clock.now() }.save(a.suggestion)
            if (id == null) ActionResultDraft("Already in your review list")
            else ActionResultDraft("Added to review: ${a.suggestion.title}", targetId = id)
        }
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun tool(name: String, vararg args: Pair<String, String?>): ToolResult {
        val map: Map<String, JsonElement> = args.filter { it.second != null }.associate { it.first to JsonPrimitive(it.second) }
        return gateway.executeTool(ToolCall(name, map), executedCalls = mutableSetOf())
    }

    private fun fromTool(r: ToolResult, targetId: String? = null): ActionResultDraft = when (r) {
        is ToolResult.Committed -> ActionResultDraft(r.message, undoToken = r.undoToken, targetId = r.targetId)
        is ToolResult.Ambiguous -> ActionResultDraft("Which one? Pick it in your inbox.", needsChoice = true, targetId = targetId)
        is ToolResult.NeedsConfirm -> ActionResultDraft(r.message, needsChoice = true, targetId = targetId)
        is ToolResult.NeedsTime -> ActionResultDraft("Which time did you mean?", needsChoice = true, targetId = targetId)
        is ToolResult.NotFound -> ActionResultDraft("Couldn't find that item", isError = true, targetId = targetId)
        is ToolResult.Error -> ActionResultDraft(r.reason, isError = true, targetId = targetId)
        is ToolResult.Data -> ActionResultDraft(r.text, targetId = targetId)
    }

    private suspend fun quickAdd(raw: String): ActionResultDraft {
        val text = raw.trim()
        if (text.isEmpty()) return ActionResultDraft("Type something to add", isError = true)
        val parsed = quickAdd.parse(text)
        val title = (parsed?.title ?: text).trim().take(200)
        val due = parsed?.dueText
        val first = fromTool(tool("propose_change", "intent" to "CREATE", "title" to title, "due_text" to due, "tier" to parsed?.tier?.name?.lowercase()))
        // A time the gateway could not read must not lose the item: keep it, without the time.
        if (first.isError && due != null) {
            val retry = fromTool(tool("propose_change", "intent" to "CREATE", "title" to title))
            if (!retry.isError) return retry.copy(message = "${retry.message} (couldn't read \"$due\" — no reminder set)")
        }
        return first
    }

    private suspend fun createReminder(a: UiAction.CreateReminder): ActionResultDraft {
        val title = a.title.trim().ifEmpty { return ActionResultDraft("Give the reminder a name", isError = true) }
        val tier = if (a.gentle) "gentle" else "alarm"
        return if (a.leaveHome) {
            val fallbackAt = a.fallbackWhenText?.takeIf { it.isNotBlank() }?.let { resolveMillis(it) }
            fromTool(
                tool(
                    "propose_change", "intent" to "CREATE", "title" to title, "tier" to tier,
                    "context_trigger" to "ON_LEAVE_HOME", "fallback_fire_at" to fallbackAt?.toString(),
                    "recurrence_rule" to a.recurrence,
                )
            )
        } else {
            if (a.whenText.isBlank()) return ActionResultDraft("When should it go off?", isError = true)
            fromTool(
                tool(
                    "propose_change", "intent" to "CREATE", "title" to title, "due_text" to a.whenText.trim(),
                    "tier" to tier, "label" to if (a.gentle) "reminder" else "alarm", "recurrence_rule" to a.recurrence,
                )
            )
        }
    }

    private suspend fun resolveMillis(text: String): Long? {
        val now = clock.now()
        val rule = storage.getEffectiveDayRule(now)
        val res = dateResolver.resolve(text, rule.startMinute, DayRollover.safeTimeZone(rule.tzId))
        return (res as? com.anchor.core.time.DateResolution.Exact)?.timeMillis?.takeIf { it > now }
    }

    private suspend fun setNodeLocalOnly(nodeId: String, localOnly: Boolean): ActionResultDraft {
        val node = storage.getNode(nodeId) ?: return ActionResultDraft("Couldn't find that item", isError = true)
        if (node.localOnly == localOnly) return ActionResultDraft(if (localOnly) "Already kept on this phone" else "Already shared with your AI")
        val now = clock.now()
        val updated = node.copy(localOnly = localOnly, updatedAt = now)
        val rem = storage.getReminderByNodeId(nodeId)
        val opId = "u_${UUID.randomUUID()}"
        val event = RawEvent(
            opId = opId, opType = "UPDATE", targetType = "node", targetId = nodeId,
            beforeJson = json.encodeToString(NodeStateSnapshot(node, rem)),
            afterJson = json.encodeToString(NodeStateSnapshot(updated, rem)),
            tsUtc = now,
        )
        storage.updateNodeAndCommit(updated, event, deleteReminderId = null)
        return ActionResultDraft(
            if (localOnly) "Kept on this phone — your AI provider won't see it" else "Visible to your AI again",
            undoToken = opId, targetId = nodeId,
        )
    }

    private suspend fun setSoulLocalOnly(factId: String, localOnly: Boolean): ActionResultDraft {
        val fact = storage.getActiveSoulFacts(includeLocalOnly = true).firstOrNull { it.id == factId }
            ?: return ActionResultDraft("Couldn't find that memory", isError = true)
        storage.saveSoulFact(fact.copy(localOnly = localOnly))
        return ActionResultDraft(if (localOnly) "Kept on this phone" else "Shared with your AI", targetId = factId)
    }

    private suspend fun setNoteMode(noteId: String, mode: String): ActionResultDraft {
        val m = if (mode == "read_only") "read_only" else "read_write"
        val refs = storage.getReferencesForNote(noteId)
        if (refs.isEmpty()) {
            storage.saveSetting("note.mode.$noteId", m)
        } else {
            refs.forEach { storage.linkNoteReference(it.copy(mode = m)) }
        }
        return ActionResultDraft(if (m == "read_only") "Mine can read this note but won't edit it" else "Mine can update this note", targetId = noteId)
    }

    private suspend fun snooze(reminderId: String, minutes: Int): ActionResultDraft {
        val mins = minutes.coerceIn(1, 24 * 60)
        val rem = storage.getReminderById(reminderId) ?: return ActionResultDraft("That reminder is gone", isError = true)
        val nodeId = rem.nodeId
        if (nodeId != null && storage.getNode(nodeId) != null) {
            val r = fromTool(tool("propose_change", "intent" to "RESCHEDULE", "target_ref" to nodeId, "due_text" to "in $mins minutes"), nodeId)
            return if (r.isError) r else r.copy(message = "Snoozed $mins min")
        }
        val fireAt = clock.now() + mins * 60_000L
        val moved = rem.copy(fireAtMillis = fireAt)
        storage.saveReminder(moved)
        val res = alarmScheduler.schedule(moved)
        return if (res is ScheduleResult.Failed) ActionResultDraft("Snoozed, but the alarm may be late: ${res.reason}", isError = true)
        else ActionResultDraft("Snoozed $mins min", targetId = reminderId)
    }

    private suspend fun cancelReminder(reminderId: String): ActionResultDraft {
        val rem = storage.getReminderById(reminderId)
            ?: storage.getPendingReminders().firstOrNull { it.id == reminderId }
            ?: return ActionResultDraft("That reminder is already gone")
        alarmScheduler.cancel(reminderId)
        storage.deleteRegisteredAlarm(reminderId)
        val opId = "u_${UUID.randomUUID()}"
        val event = RawEvent(opId, "DELETE", "reminder", reminderId, beforeJson = json.encodeToString(rem), afterJson = null, tsUtc = clock.now())
        storage.deleteReminderAndCommit(reminderId, event)
        return ActionResultDraft("Reminder removed", undoToken = opId, targetId = reminderId)
    }

    private suspend fun startBreak(minutes: Int): ActionResultDraft {
        val mins = minutes.coerceIn(1, 240)
        val now = clock.now()
        val until = now + mins * 60_000L
        endBreakWakeups()
        storage.saveSetting(KEY_BREAK_UNTIL, until.toString())
        val id = "aw_break_${UUID.randomUUID().toString().take(8)}"
        storage.saveAgentWakeup(
            AgentWakeup(
                id = id, fireAt = until,
                reason = "Your $mins-minute break is up. Ready to jump back in?",
                payloadJson = """{"type":"BREAK_TIMER","mins":$mins}""",
                state = "pending", createdAt = now,
            )
        )
        val res = alarmScheduler.scheduleAgentWakeup(id, until)
        return if (res is ScheduleResult.Failed) ActionResultDraft("Break started, but the check-in may be late: ${res.reason}", isError = true)
        else ActionResultDraft("Break for $mins min. I'll check in after.", targetId = id)
    }

    private suspend fun endBreak(): ActionResultDraft {
        storage.saveSetting(KEY_BREAK_UNTIL, "")
        endBreakWakeups()
        return ActionResultDraft("Break ended")
    }

    private suspend fun endBreakWakeups() {
        storage.getPendingAgentWakeups(Long.MAX_VALUE)
            .filter { it.payloadJson?.contains("\"BREAK_TIMER\"") == true }
            .forEach {
                runCatching { alarmScheduler.cancelAgentWakeup(it.id) }
                storage.markAgentWakeupState(it.id, "cancelled")
            }
    }

    companion object {
        const val KEY_USER_NAME = "user.name"
        const val KEY_LANGUAGES = "user.languages"
        const val KEY_BREAK_UNTIL = "break.until_ms"
        const val KEY_NUDGES_PAUSED = "nudge.paused_until_ms"
        val PREF_PREFIXES = listOf("ui.", "voice.", "chat.", "consent.", "ambient.")
    }
}

/** An [ActionResult] before the controller stamps it with a sequence number and the action's tag. */
internal data class ActionResultDraft(
    val message: String,
    val undoToken: String? = null,
    val isError: Boolean = false,
    val needsChoice: Boolean = false,
    val targetId: String? = null,
)
