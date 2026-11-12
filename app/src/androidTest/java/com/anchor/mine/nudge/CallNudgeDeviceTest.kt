// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.nudge

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.nudge.CallEvent
import com.anchor.core.nudge.CallNudgeState
import com.anchor.mine.MineApp
import com.anchor.mine.alarms.NotificationChannels
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CallNudgeDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
    }

    @Test
    fun testNotificationChannel_ZeroVibration() {
        NotificationChannels.ensureCreated(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(NotificationChannels.CALL_NUDGE)
        assertNotNull("Call nudge channel must exist", channel)
        assertFalse("Call nudge channel must NOT vibrate", channel.shouldVibrate())
    }

    @Test
    fun testShowNotification_HasRequiredActions() {
        val eventId = "test_call_${UUID.randomUUID()}"
        CallNudgeNotifications.show(context, eventId, contactName = "Test Contact")

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifications = manager.activeNotifications
        val active = notifications.find { it.id == eventId.hashCode() }

        assertNotNull("Notification must be active in notification shade", active)
        val notif = active!!.notification
        val actions = notif.actions
        assertNotNull("Notification actions must exist", actions)
        assertEquals("Must have exactly 3 actions: Reply, Voice note, Skip", 3, actions.size)

        // Verify actions
        val replyAction = actions[0]
        assertEquals("Reply", replyAction.title.toString())
        assertNotNull("Reply action must have RemoteInput", replyAction.remoteInputs)

        val voiceAction = actions[1]
        assertEquals("Voice note", voiceAction.title.toString())

        val skipAction = actions[2]
        assertEquals("Skip", skipAction.title.toString())

        // Verify public version conceals contact name
        val publicVer = notif.publicVersion
        assertNotNull("Public version must be configured for lock screen", publicVer)
        assertEquals("Call done", publicVer.extras.getString(android.app.Notification.EXTRA_TITLE))

        CallNudgeNotifications.dismiss(context, eventId.hashCode())
    }

    @Test
    fun testCallNudgeActionReceiver_ReplyStoresMessageAndMarksAnswered() = runBlocking {
        val app = context.applicationContext as MineApp
        val storage = app.sharedStorage.await()

        val eventId = "test_call_${UUID.randomUUID()}"
        val callEvent = CallEvent(
            id = eventId,
            startedAt = System.currentTimeMillis() - 100_000L,
            endedAt = System.currentTimeMillis(),
            durationS = 100,
            state = CallNudgeState.PENDING,
            createdAt = System.currentTimeMillis()
        )
        storage.saveCallEvent(callEvent)

        // Post notification
        CallNudgeNotifications.show(context, eventId)

        // Send Reply action broadcast
        val intent = Intent(context, CallNudgeActionReceiver::class.java).apply {
            action = CallNudgeNotifications.ACTION_REPLY
            putExtra(CallNudgeNotifications.EXTRA_CALL_EVENT_ID, eventId)
            putExtra(CallNudgeNotifications.EXTRA_NOTIFICATION_ID, eventId.hashCode())
        }
        val bundle = android.os.Bundle().apply {
            putCharSequence(CallNudgeNotifications.RESULT_KEY, "Follow up with client on Friday")
        }
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(CallNudgeNotifications.RESULT_KEY).build()),
            intent,
            bundle
        )

        CallNudgeActionReceiver().onReceive(context, intent)

        // Allow async coroutine to complete
        var updated = storage.getCallEvent(eventId)
        var tries = 0
        while (tries < 20 && updated?.state != CallNudgeState.ANSWERED) {
            delay(50)
            updated = storage.getCallEvent(eventId)
            tries++
        }

        assertEquals(CallNudgeState.ANSWERED, updated?.state)
        assertNotNull("Answered nudge must record message ID", updated?.nudgeId)

        // Stored message must exist
        val msg = storage.getRawMessage(updated!!.nudgeId!!)
        assertEquals("Follow up with client on Friday", msg)
    }

    @Test
    fun testCallNudgeActionReceiver_SkipMarksSkippedWithoutGuilt() = runBlocking {
        val app = context.applicationContext as MineApp
        val storage = app.sharedStorage.await()

        val eventId = "test_call_${UUID.randomUUID()}"
        val callEvent = CallEvent(
            id = eventId,
            startedAt = System.currentTimeMillis() - 80_000L,
            endedAt = System.currentTimeMillis(),
            durationS = 80,
            state = CallNudgeState.PENDING,
            createdAt = System.currentTimeMillis()
        )
        storage.saveCallEvent(callEvent)

        // Post notification
        CallNudgeNotifications.show(context, eventId)

        // Send Skip action broadcast
        val intent = Intent(context, CallNudgeActionReceiver::class.java).apply {
            action = CallNudgeNotifications.ACTION_SKIP
            putExtra(CallNudgeNotifications.EXTRA_CALL_EVENT_ID, eventId)
            putExtra(CallNudgeNotifications.EXTRA_NOTIFICATION_ID, eventId.hashCode())
        }

        CallNudgeActionReceiver().onReceive(context, intent)

        var updated = storage.getCallEvent(eventId)
        var tries = 0
        while (tries < 20 && updated?.state != CallNudgeState.SKIPPED) {
            delay(50)
            updated = storage.getCallEvent(eventId)
            tries++
        }

        assertEquals(CallNudgeState.SKIPPED, updated?.state)
    }

    @Test
    fun testCallNudgeActionReceiver_EvaluateCallNudgeUsesRealStorageState() = runBlocking {
        val app = context.applicationContext as MineApp
        val storage = app.sharedStorage.await()

        val eventId = "test_eval_call_${UUID.randomUUID()}"
        val callEvent = CallEvent(
            id = eventId,
            startedAt = System.currentTimeMillis() - 90_000L,
            endedAt = System.currentTimeMillis() - 25_000L,
            durationS = 65,
            state = CallNudgeState.PENDING,
            createdAt = System.currentTimeMillis() - 25_000L
        )
        storage.saveCallEvent(callEvent)

        val intent = Intent(context, CallNudgeActionReceiver::class.java).apply {
            action = CallNudgeActionReceiver.ACTION_EVALUATE_CALL_NUDGE
            putExtra(CallNudgeNotifications.EXTRA_CALL_EVENT_ID, eventId)
        }

        CallNudgeActionReceiver().onReceive(context, intent)

        // Give coroutine time to execute evaluation
        delay(300)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifications = manager.activeNotifications
        val active = notifications.find { it.id == eventId.hashCode() }
        assertNotNull("Delayed evaluation must trigger notification", active)

        val updated = storage.getCallEvent(eventId)
        assertEquals("Call event state must be transitioned to NUDGED", CallNudgeState.NUDGED, updated?.state)

        CallNudgeNotifications.dismiss(context, eventId.hashCode())
    }
}
