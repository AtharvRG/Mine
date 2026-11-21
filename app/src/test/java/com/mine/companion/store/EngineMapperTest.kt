package com.mine.companion.store

import com.anchor.core.ambient.AmbientReportItem
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.Proposal
import com.anchor.core.models.ProposalOption
import com.anchor.core.models.Reminder
import com.anchor.core.models.SoulFact as EngineSoulFact
import com.anchor.core.models.TopicNote
import com.anchor.core.ui.ChatItem
import com.anchor.core.ui.ReceiptState
import com.anchor.core.ui.RecentlyClosed
import com.anchor.core.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class EngineMapperTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val mapper = EngineMapper(zone, Locale.ENGLISH)
    private val now = LocalDateTime.of(2026, 11, 21, 14, 0).atZone(zone).toInstant().toEpochMilli()
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun map(e: UiState, is24h: Boolean = true) = mapper.map(e, now, is24h, null)

    @Test fun items_haveHumanDueLabels_andStatuses() {
        val e = UiState(
            providerKey = "k",
            activeNodes = listOf(
                Node("a", "task", "Ship the deck", "active", dueAt = at(21, 20)),
                Node("b", "task", "Gym", "in_progress", dueAt = at(22, 7, 30)),
                Node("c", "task", "Buy milk", "active", contextTrigger = "ON_LEAVE_HOME", fallbackFireAt = at(21, 18, 30)),
                Node("d", "habit", "Read", "active", streakCurrent = 4, streakBest = 9),
                Node("e", "task", "Someday", "paused"),
            ),
        )
        val items = map(e).items.associateBy { it.id }
        assertEquals("Today 20:00", items["a"]!!.dueLabel)
        assertEquals(ItemStatus.todo, items["a"]!!.status)
        assertEquals("Tomorrow 07:30", items["b"]!!.dueLabel)
        assertEquals(ItemStatus.in_progress, items["b"]!!.status)
        assertEquals("When I leave home", items["c"]!!.dueLabel)
        assertEquals(ItemType.errand, items["c"]!!.type)
        assertEquals("18:30", items["c"]!!.fallbackTime)
        assertEquals(ItemType.habit, items["d"]!!.type)
        assertEquals(4, items["d"]!!.streakDays)
        assertEquals("No date", items["e"]!!.dueLabel)
        assertEquals(ItemStatus.paused, items["e"]!!.status)
    }

    @Test fun twelveHourClock_isRespected() {
        val e = UiState(providerKey = "k", activeNodes = listOf(Node("a", "task", "Call", "active", dueAt = at(21, 20, 5))))
        assertEquals("Today 8:05 PM", map(e, is24h = false).items.single().dueLabel)
    }

    @Test fun slipHistory_becomesStrikethroughLabels() {
        val history = "[${at(19, 18)},${at(20, 21)}]"
        val e = UiState(providerKey = "k", activeNodes = listOf(Node("a", "task", "Lab", "active", dueAt = at(21, 20), dueHistoryJson = history, slipCount = 2)))
        val item = map(e).items.single()
        assertEquals(2, item.slipCount)
        assertEquals(listOf("Thu 19 Nov, 18:00", "Yesterday 21:00"), item.strikethroughHistory)
    }

    @Test fun recentlyClosed_showAsDone_withoutDuplicates() {
        val done = Node("x", "task", "Done thing", "done", closedAt = at(21, 12))
        val e = UiState(providerKey = "k", activeNodes = emptyList(), recentlyClosed = listOf(RecentlyClosed(done, true, "u1")))
        val s = map(e)
        assertEquals(ItemStatus.done, s.items.single().status)
        assertEquals(listOf("x"), s.recentlyClosedIds)
    }

    @Test fun receipts_moveUnderTheAnswer_andOrphansGetTheirOwnRow() {
        val items = listOf(
            ChatItem.Message("u1", "user", "move dinner to 8", at(21, 13)),
            ChatItem.Message("a1", "assistant", "Done, moved it.", at(21, 13, 1)),
            ChatItem.Message("u2", "user", "add milk", at(21, 13, 5)),
        )
        val receipts = listOf(
            ReceiptState("r1", "Rescheduled: Dinner to 20:00", "tok1", causeMsgId = "u1", timestamp = at(21, 13, 1)),
            ReceiptState("r2", "Created: Milk", "tok2", causeMsgId = "u2", timestamp = at(21, 13, 5)),
        )
        val msgs = map(UiState(providerKey = "k", chatItems = items, receipts = receipts)).todayMessages
        val answer = msgs.first { it.id == "a1" }
        assertEquals("tok1", answer.receipts!!.single().undoToken)
        assertEquals(ReceiptActionType.reschedule, answer.receipts!!.single().actionType)
        val orphan = msgs.last()
        assertEquals("rcpt-u2", orphan.id)
        assertEquals(Sender.assistant, orphan.sender)
        assertEquals("", orphan.text)
        assertEquals(ReceiptActionType.created, orphan.receipts!!.single().actionType)
    }

    @Test fun openProposal_ridesOnTheLatestAnswer() {
        val items = listOf(
            ChatItem.Message("u1", "user", "move dinner", at(21, 13)),
            ChatItem.Message("a1", "assistant", "Which dinner?", at(21, 13, 1)),
        )
        val p = Proposal("p1", "ambiguous", IntentType.RESCHEDULE, listOf(ProposalOption("n1", "Dinner with Riya"), ProposalOption("n2", "Dinner with Mom")))
        val msgs = map(UiState(providerKey = "k", chatItems = items, openProposals = listOf(p))).todayMessages
        val prompt = msgs.last().ambiguityPrompt
        assertNotNull(prompt)
        assertEquals("p1", prompt!!.proposalId)
        assertEquals("Which one should I move?", prompt.question)
        assertEquals(listOf("n1", "n2"), prompt.options.map { it.id })
    }

    @Test fun queuedAndRedactedMessages_areLabelled() {
        val items = listOf(ChatItem.Message("u1", "user", null, at(21, 9)), ChatItem.Message("u2", "user", "hello?", at(21, 9, 1)))
        val msgs = map(UiState(providerKey = "k", chatItems = items, queuedChatIds = listOf("u2"))).todayMessages
        assertEquals("This message is kept on this phone only.", msgs[0].text)
        assertTrue(msgs[1].queued)
    }

    @Test fun inbox_coversProposalsSoulAmbientAndStale() {
        val e = UiState(
            providerKey = "k",
            openProposals = listOf(Proposal("p1", "time", IntentType.CREATE, listOf(ProposalOption("1", "6:00 AM"), ProposalOption("2", "6:00 PM")))),
            proposedSoulFacts = listOf(EngineSoulFact("s1", "routine", "Runs every morning", 0.7, "chat", "worker", "proposed", 0L)),
            ambientReportItems = listOf(
                AmbientReportItem("a1", "rep", null, "proposal", "From WhatsApp · Riya: Call Riya — at 6pm", "n9", "from_notification", "pending"),
                AmbientReportItem("a2", "rep", null, "proposal", "Old", null, null, "accepted"),
            ),
            staleItems = listOf(Node("st", "task", "Fix bike", "active", updatedAt = at(1, 10))),
        )
        val inbox = map(e).inboxItems
        assertEquals(listOf("p:p1", "s:s1", "a:a1", "st:st"), inbox.map { it.id })
        assertEquals(2, inbox[0].choices.size)
        assertEquals("Which time did you mean?", inbox[0].title)
        assertEquals(InboxKind.ambient, inbox[2].kind)
        assertTrue(inbox[2].subtitle.contains("notification"))
        assertEquals(InboxKind.stale_30d, inbox[3].kind)
    }

    @Test fun statusChip_tellsTheTruth() {
        assertEquals(StatusChipState.no_ai, map(UiState()).statusChip)
        assertEquals(StatusChipState.backup_model, map(UiState(onDeviceFallbackEnabled = true)).statusChip)
        assertEquals(StatusChipState.offline, map(UiState(providerKey = "k", queuedChatIds = listOf("u"))).statusChip)
        assertEquals(StatusChipState.rate_limited, map(UiState(providerKey = "k", providerCooldownExpiry = now + 30_000)).statusChip)
        assertEquals(StatusChipState.reminders_at_risk, map(UiState(providerKey = "k", atRisk = true)).statusChip)
        assertEquals(StatusChipState.normal, map(UiState(providerKey = "k")).statusChip)
    }

    @Test fun chatError_offersRetryOnlyWhenThereIsSomethingToRetry() {
        val e = UiState(providerKey = "k", connectionError = "Your AI provider timed out. Tap retry.", connectionErrorRetryable = true, lastFailedMsgId = "u1")
        assertEquals(true, map(e).chatError!!.canRetry)
        assertEquals(false, map(e.copy(lastFailedMsgId = null)).chatError!!.canRetry)
        assertNull(map(UiState(providerKey = "k")).chatError)
    }

    @Test fun reminders_upcomingMissedAndDone() {
        val e = UiState(
            providerKey = "k",
            activeReminders = listOf(Reminder("r1", "Stretch", at(21, 16), tier = AlarmTier.GENTLE, rrule = "FREQ=DAILY")),
            missedReminders = listOf(com.anchor.core.alarms.ScheduleResult.Overdue("r0", "Pills", at(21, 8), 1000)),
        )
        val r = map(e).reminders
        assertEquals(ReminderStatus.upcoming, r[0].status)
        assertEquals("Today 16:00", r[0].time)
        assertEquals(AlarmTierUi.GENTLE, r[0].tier)
        assertEquals(Recurrence.Daily, r[0].recurrence)
        assertEquals(ReminderStatus.missed, r[1].status)
    }

    @Test fun notes_parseBulletsAndPinOrder() {
        val e = UiState(
            providerKey = "k",
            topicNotes = listOf(
                TopicNote("n1", "Trip", "", "[\"Passport\",\"Charger\"]", pinned = false, lastEditedAt = at(21, 13, 59), createdAt = 0),
                TopicNote("n2", "Work", "- one\n- two", "not json", pinned = true, lastEditedAt = at(20, 10), createdAt = 0),
            ),
        )
        val notes = map(e).livingNotes
        assertEquals("n2", notes[0].id)
        assertEquals(listOf("one", "two"), notes[0].bullets)
        assertEquals(listOf("Passport", "Charger"), notes[1].bullets)
        assertEquals("1 min ago", notes[1].updatedAt)
    }
}
