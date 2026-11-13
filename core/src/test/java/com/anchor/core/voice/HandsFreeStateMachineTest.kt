// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.voice

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Reminder
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolver
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private class FakeScheduler : AlarmScheduler {
    override val lastFiredId = MutableStateFlow<String?>(null)
    override fun schedule(reminder: Reminder): ScheduleResult = ScheduleResult.Armed
    override fun cancel(reminderId: String) {}
}

private class FakeTransport : HttpTransport {
    override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
        emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Understood.\",\"calls\":[]}"}}]}""")
        emit("data: [DONE]")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HandsFreeStateMachineTest {

    @Test
    fun testInitialState_IsIdle() {
        val sm = HandsFreeStateMachine()
        assertEquals(HandsFreeState.IDLE, sm.state)
    }

    @Test
    fun testStart_TransitionsToListening_AndEmitsStartListening() {
        val sm = HandsFreeStateMachine()
        val effects = sm.transition(HandsFreeEvent.Start)

        assertEquals(HandsFreeState.LISTENING, sm.state)
        assertEquals(1, effects.size)
        assertTrue(effects.first() is HandsFreeEffect.StartListening)
    }

    @Test
    fun testListening_StopWords_TransitionToIdle_AndTerminateSession() {
        val stopPhrases = listOf(
            "stop", "exit", "quit", "cancel", "bye", "goodbye", "good bye",
            "that's all", "that is all", "thank you that's all", "done", "close"
        )

        for (phrase in stopPhrases) {
            val sm = HandsFreeStateMachine(HandsFreeState.LISTENING)
            assertTrue("'$phrase' must be recognized as stop phrase", HandsFreeStateMachine.isStopWord(phrase))

            val effects = sm.transition(HandsFreeEvent.UserUtterance(phrase))
            assertEquals("State must return to IDLE after '$phrase'", HandsFreeState.IDLE, sm.state)
            assertEquals(2, effects.size)
            assertTrue(effects[0] is HandsFreeEffect.StopListening)
            assertTrue(effects[1] is HandsFreeEffect.SessionEnded)
        }
    }

    @Test
    fun testListening_NormalUserUtterance_TransitionsToProcessing_AndDispatchesMessage() {
        val sm = HandsFreeStateMachine(HandsFreeState.LISTENING)
        val utterance = "Remind me to buy milk tomorrow at 10am"

        assertFalse(HandsFreeStateMachine.isStopWord(utterance))
        val effects = sm.transition(HandsFreeEvent.UserUtterance(utterance))

        assertEquals(HandsFreeState.PROCESSING, sm.state)
        assertEquals(2, effects.size)
        assertTrue(effects[0] is HandsFreeEffect.StopListening)
        val dispatch = effects[1] as HandsFreeEffect.DispatchUserMessage
        assertEquals(utterance, dispatch.text)
    }

    @Test
    fun testListening_SilenceTimeout_TransitionsToIdle() {
        val sm = HandsFreeStateMachine(HandsFreeState.LISTENING)
        val effects = sm.transition(HandsFreeEvent.SilenceTimeout)

        assertEquals(HandsFreeState.IDLE, sm.state)
        assertEquals(2, effects.size)
        assertTrue(effects[0] is HandsFreeEffect.StopListening)
        assertTrue(effects[1] is HandsFreeEffect.SessionEnded)
    }

    @Test
    fun testProcessing_TurnComplete_WithReply_TransitionsToSpeaking() {
        val sm = HandsFreeStateMachine(HandsFreeState.PROCESSING)
        val reply = "I've scheduled your reminder for tomorrow at 10:00 AM."

        val effects = sm.transition(HandsFreeEvent.TurnComplete(reply))
        assertEquals(HandsFreeState.SPEAKING, sm.state)
        assertEquals(1, effects.size)
        val speak = effects.first() as HandsFreeEffect.SpeakReply
        assertEquals(reply, speak.text)
    }

    @Test
    fun testProcessing_TurnComplete_WithBlankReply_LoopsBackToListening() {
        val sm = HandsFreeStateMachine(HandsFreeState.PROCESSING)
        val effects = sm.transition(HandsFreeEvent.TurnComplete("   "))

        assertEquals(HandsFreeState.LISTENING, sm.state)
        assertEquals(1, effects.size)
        assertTrue(effects.first() is HandsFreeEffect.StartListening)
    }

    @Test
    fun testSpeaking_PlaybackComplete_LoopsBackToListening_EnablingTalkHearTalkAgain() {
        val sm = HandsFreeStateMachine(HandsFreeState.SPEAKING)

        val effects = sm.transition(HandsFreeEvent.PlaybackComplete)
        assertEquals("Must loop back to LISTENING after playback ends", HandsFreeState.LISTENING, sm.state)
        assertEquals(1, effects.size)
        assertTrue("Must emit StartListening to reopen mic for next turn", effects.first() is HandsFreeEffect.StartListening)
    }

    @Test
    fun testSpeaking_StopEvent_TransitionsToIdle_Immediately() {
        val sm = HandsFreeStateMachine(HandsFreeState.SPEAKING)

        val effects = sm.transition(HandsFreeEvent.Stop("Phone call arrived"))
        assertEquals(HandsFreeState.IDLE, sm.state)
        assertEquals(1, effects.size)
        val ended = effects.first() as HandsFreeEffect.SessionEnded
        assertEquals("Phone call arrived", ended.reason)
    }

    @Test
    fun testChatController_SetHandsFreeAction_UpdatesUiState() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SharedFakeStorage()
        val testClock = object : Clock {
            var current = 1700000000000L
            override fun now(): Long = current
        }
        val dateResolver = DateResolver(testClock)
        val checkinBuilder = CheckinBuilder(storage, testClock, dateResolver)
        val contextBuilder = ContextBuilder(storage, testClock, dateResolver, checkinBuilder)
        val orchestrator = ProviderOrchestrator(RateLimiter { testClock.now() }, FakeTransport())
        val gateway = Gateway(storage, Resolver(), dateResolver, testClock)
        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = FakeScheduler(),
            coroutineScope = TestScope(testDispatcher),
            defaultDispatcher = testDispatcher,
            clock = testClock,
            dateResolver = dateResolver
        )

        assertEquals(HandsFreeState.IDLE, controller.state.value.handsFreeState)

        controller.onAction(UiAction.SetHandsFree(true))
        testScheduler.advanceUntilIdle()
        assertEquals(HandsFreeState.LISTENING, controller.state.value.handsFreeState)

        controller.onAction(UiAction.UpdateHandsFreeState(HandsFreeState.PROCESSING))
        testScheduler.advanceUntilIdle()
        assertEquals(HandsFreeState.PROCESSING, controller.state.value.handsFreeState)

        controller.onAction(UiAction.SetHandsFree(false))
        testScheduler.advanceUntilIdle()
        assertEquals(HandsFreeState.IDLE, controller.state.value.handsFreeState)
    }
}
