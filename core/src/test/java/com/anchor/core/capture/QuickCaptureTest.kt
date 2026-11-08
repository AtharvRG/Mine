// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.capture

import com.anchor.core.SharedFakeStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Blueprint 50 §3.14 + scenario E-17: a captured message is stored immediately and processed
// when possible. These tests pin the three properties the receivers depend on: nothing blank is
// stored, a capture is "pending" only until something answers it, and pending order is write order.
class QuickCaptureTest {

    @Test
    fun store_BlankOrWhitespace_StoresNothing() = runBlocking {
        val storage = SharedFakeStorage()

        assertNull(QuickCapture.store(storage, ""))
        assertNull(QuickCapture.store(storage, "   \n\t "))
        assertEquals(0, storage.messages.size)
    }

    @Test
    fun store_TrimsAndWritesAUserRow_WithQuickCaptureId() = runBlocking {
        val storage = SharedFakeStorage()

        val id = QuickCapture.store(storage, "  call the dentist  ")

        assertNotNull(id)
        assertTrue("capture ids are recognisable without a tags column", id!!.startsWith("q_"))
        assertEquals(1, storage.messages.size)
        val row = storage.messages.single()
        assertEquals(id, row.id)
        assertEquals("user", row.role)
        assertEquals("call the dentist", row.content)
    }

    @Test
    fun store_GeneratesADistinctIdPerCapture() = runBlocking {
        val storage = SharedFakeStorage()

        val a = QuickCapture.store(storage, "first")
        val b = QuickCapture.store(storage, "second")

        assertTrue(a != b)
        assertEquals(2, storage.messages.size)
    }

    @Test
    fun isUnanswered_TrueUntilAnAssistantMessageFollows() = runBlocking {
        val storage = SharedFakeStorage()
        val id = QuickCapture.store(storage, "remember the milk")!!

        assertTrue(QuickCapture.isUnanswered(storage, id))

        storage.insertRawMessage("a_1", "assistant", "Noted.")
        assertFalse(QuickCapture.isUnanswered(storage, id))
    }

    @Test
    fun isUnanswered_BlankAssistantReply_DoesNotCount() = runBlocking {
        // A turn that produced nothing is not an answer; the capture must still be retried.
        val storage = SharedFakeStorage()
        val id = QuickCapture.store(storage, "remember the milk")!!

        storage.insertRawMessage("a_1", "assistant", "   ")

        assertTrue(QuickCapture.isUnanswered(storage, id))
    }

    @Test
    fun isUnanswered_UnknownId_IsFalse() = runBlocking {
        val storage = SharedFakeStorage()
        assertFalse(QuickCapture.isUnanswered(storage, "q_neverstored"))
    }

    @Test
    fun pending_ReturnsUnansweredCapturesOldestFirst() = runBlocking {
        val storage = SharedFakeStorage()
        storage.insertRawMessage("m1", "user", "typed in the app")
        val first = QuickCapture.store(storage, "capture one")!!
        val second = QuickCapture.store(storage, "capture two")!!

        assertEquals(listOf(first, second), QuickCapture.pending(storage))
    }

    @Test
    fun pending_SkipsCapturesThatWereAlreadyAnswered() = runBlocking {
        val storage = SharedFakeStorage()
        val answered = QuickCapture.store(storage, "capture one")!!
        storage.insertRawMessage("a_1", "assistant", "Done.")
        val open = QuickCapture.store(storage, "capture two")!!

        assertEquals(listOf(open), QuickCapture.pending(storage))
        assertFalse(QuickCapture.isUnanswered(storage, answered))
    }

    @Test
    fun pending_IgnoresTypedMessages_EvenWhenUnanswered() = runBlocking {
        // Only captures are retried on restart; an unanswered typed message is the user's own
        // in-app state and must not be re-sent behind their back.
        val storage = SharedFakeStorage()
        storage.insertRawMessage("u_typed", "user", "typed but never sent")

        assertTrue(QuickCapture.pending(storage).isEmpty())
    }

    @Test
    fun captureNotification_IsOffUntilExplicitlyEnabled() = runBlocking {
        // Blueprint 50 §3.14: optional and off by default. An unwritten setting is off, and any
        // value other than "true" is off too — a typo must not turn a persistent notification on.
        val storage = SharedFakeStorage()
        assertFalse(QuickCapture.isCaptureNotificationEnabled(storage))

        storage.saveSetting(QuickCapture.SETTING_CAPTURE_NOTIFICATION, "yes")
        assertFalse(QuickCapture.isCaptureNotificationEnabled(storage))

        storage.saveSetting(QuickCapture.SETTING_CAPTURE_NOTIFICATION, "true")
        assertTrue(QuickCapture.isCaptureNotificationEnabled(storage))
    }

    @Test
    fun shortcut_IsOnUntilExplicitlyDisabled() = runBlocking {
        // Blueprint 52 §12: on by default, so an unwritten setting is on.
        val storage = SharedFakeStorage()
        assertTrue(QuickCapture.isShortcutEnabled(storage))

        storage.saveSetting(QuickCapture.SETTING_CAPTURE_SHORTCUT, "false")
        assertFalse(QuickCapture.isShortcutEnabled(storage))
    }
}
