// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.capture

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.capture.QuickCapture
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.Reminder
import com.anchor.mine.MineApp
import com.anchor.mine.alarms.AlarmReceiver
import com.anchor.mine.alarms.AndroidAlarmScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Blueprint 50 §3.14 / E-17 on the real device. The :core test proves the store/pending rules
 * against a fake; only this can prove the notification actually carries a reply action, that the
 * reply reaches the receiver through `RemoteInput`, and that the row lands in SQLCipher.
 */
@RunWith(AndroidJUnit4::class)
class QuickCaptureDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val reminderIds = mutableListOf<String>()

    @Before
    fun setup() {
        QuickCaptureReceiver.lastStoredId = null
        // The delivery path posts a notification; without the grant notify() is dropped silently
        // and the test would fail on "no notification" rather than on the reply action.
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
    }

    @After
    fun teardown() = runBlocking {
        val storage = (context.applicationContext as MineApp).sharedStorage.await()
        val scheduler = AndroidAlarmScheduler(context)
        reminderIds.forEach {
            storage.deleteReminder(it)
            scheduler.cancel(it)
        }
    }

    /**
     * The audit's proving test for row 19: `RemoteInput` action present on the alarm notification.
     *
     * The reminder is fired by broadcasting to `AlarmReceiver` directly rather than waiting for
     * AlarmManager. The alarm's own delivery is already covered by `AlarmSelfTest`; what this
     * test needs is the notification, and a real 5 s wait would add nothing but flake.
     */
    @Test
    fun alarmNotification_CarriesInlineReplyAction() = runBlocking {
        val app = context.applicationContext as MineApp
        val storage = app.sharedStorage.await()
        val id = "qtest_${UUID.randomUUID().toString().take(8)}"
        reminderIds += id
        storage.saveReminder(Reminder(id, "Reply to me", System.currentTimeMillis() + 60_000L, tier = AlarmTier.ALARM))

        fireReminder(id, "Reply to me")

        val notificationId = id.hashCode()
        val posted = awaitNotification(notificationId)
        assertNotNull("The reminder notification was not posted for id=$notificationId", posted)

        val reply = posted!!.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        assertNotNull(
            "Row 19: the alarm notification has no RemoteInput reply action. actions=" +
                posted.actions?.joinToString { it.title?.toString() ?: "<null>" },
            reply
        )
        assertEquals(
            "The reply action must read back under the key the receiver uses",
            CaptureNotifications.RESULT_KEY,
            reply!!.remoteInputs!!.single().resultKey
        )
    }

    /**
     * E-17: the reply text is stored in the raw log before anything is asked of the network, and
     * the row is a `q_` capture that `QuickCapture.pending` will retry if the turn never runs.
     */
    @Test
    fun reply_StoresTheTextAsAnUnansweredCapture_Immediately() = runBlocking {
        val app = context.applicationContext as MineApp
        val storage = app.sharedStorage.await()

        val text = "buy milk ${UUID.randomUUID().toString().take(6)}"
        // A targeted broadcast, not an implicit one: the manifest receiver is `exported=false`,
        // so an implicit send is dropped by the system with no error. The reply PendingIntent
        // that production builds is explicit for the same reason.
        val intent = Intent(context, QuickCaptureReceiver::class.java).apply {
            putExtra(QuickCaptureReceiver.EXTRA_NOTIFICATION_ID, 4242)
        }
        // The system delivers a reply by wrapping the results Bundle in an Intent and hanging it
        // off ClipData with mime `text/vnd.android.intent` and label `android.remoteinput.results`.
        // A plain-text ClipData is NOT read: RemoteInput.getResultsFromIntent returns null and the
        // receiver drops the reply silently. RemoteInput.addResultsToIntent writes the exact shape
        // the platform writes, so the test cannot drift from what a real reply looks like.
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(CaptureNotifications.RESULT_KEY).build()),
            intent,
            android.os.Bundle().apply { putCharSequence(CaptureNotifications.RESULT_KEY, text) }
        )
        context.sendBroadcast(intent)

        var storedId: String? = null
        var elapsed = 0
        while (storedId == null && elapsed < 10_000) {
            delay(100)
            elapsed += 100
            storedId = QuickCaptureReceiver.lastStoredId
        }
        assertNotNull("The reply was never stored within ${elapsed}ms", storedId)

        val content = storage.getRawMessage(storedId!!)
        assertEquals("The stored row must be the reply text", text, content)
        assertTrue("A capture id must be recognisable by its q_ prefix", storedId.startsWith("q_"))
        assertTrue(
            "The capture must still be pending: no turn can run with no provider configured",
            QuickCapture.pending(storage).contains(storedId)
        )
    }

    /** The launcher shortcut exists and targets MainActivity with ACTION_VIEW. */
    @Test
    fun launcherShortcut_IsRegistered() {
        val shortcutManager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
        assertNotNull("ShortcutManager is unavailable on this device", shortcutManager)
        val shortcut = shortcutManager!!.dynamicShortcuts + shortcutManager.manifestShortcuts
        val note = shortcut.firstOrNull { it.id == "new_note" }
        assertNotNull(
            "Row 19: the 'new_note' launcher shortcut is not registered. found=" +
                shortcut.map { it.id },
            note
        )
        val intent = note!!.intents?.firstOrNull()
        assertEquals(Intent.ACTION_VIEW, intent?.action)
    }

    private fun fireReminder(id: String, text: String) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("EXTRA_ID", id)
            putExtra("EXTRA_TEXT", text)
            putExtra("EXTRA_TIER", AlarmTier.ALARM.name)
        }
        context.sendBroadcast(intent)
    }

    private fun awaitNotification(id: Int): android.app.Notification? {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var elapsed = 0
        while (elapsed < 10_000) {
            val posted = manager.activeNotifications.firstOrNull { it.id == id }?.notification
            if (posted != null) return posted
            // The receiver does its work on Dispatchers.IO; sleeping here blocks only the test
            // thread, so the IO thread still runs.
            Thread.sleep(100)
            elapsed += 100
        }
        return null
    }
}
