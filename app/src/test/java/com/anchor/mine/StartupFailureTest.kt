// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupFailureTest {

    @Test
    fun testErrorFromStartup_SettlesDeferredsInsteadOfHanging() = runBlocking {
        // H-06 (R-11): `catch (Exception)` does not catch an Error, so the deferreds were never
        // completed and every await() suspended forever — a blank screen with no banner.
        val storage = CompletableDeferred<String>()
        val export = CompletableDeferred<String>()
        CoroutineScope(Dispatchers.Default).launch {
            val result = runSettlingStartup<Unit> { throw NoClassDefFoundError("android/keystore/Key") }
            result.exceptionOrNull()?.let {
                storage.completeExceptionally(it)
                export.completeExceptionally(it)
            }
        }

        // withTimeout, not a poll loop: the defect is a hang, so a hang must fail the test.
        val failure = withTimeout(2_000) {
            runCatching { storage.await() }.exceptionOrNull()
        }
        assertNotNull("Startup must settle the storage deferred even on an Error", failure)
        assertTrue("The Error itself must be surfaced", failure is NoClassDefFoundError)
        // Await it rather than polling isCompleted: both deferreds are settled by the same block,
        // and checking the second one right after the first await() raced the completion.
        val exportFailure = withTimeout(2_000) { runCatching { export.await() }.exceptionOrNull() }
        assertNotNull("Export deferred must be settled too", exportFailure)
        assertTrue("Export deferred must fail, not report success", exportFailure is NoClassDefFoundError)
    }

    @Test
    fun testStartupFailureMessage_KeepsKeystoreSentinelAndNamesOtherErrors() {
        assertEquals("KEYSTORE_LOST", startupFailureMessage(IllegalStateException("KEYSTORE_LOST")))
        assertEquals(
            "Storage failed: android/keystore/Key",
            startupFailureMessage(NoClassDefFoundError("android/keystore/Key"))
        )
    }

    @Test
    fun testCancellationIsNotTreatedAsStartupFailure() {
        var rethrown = false
        try {
            runSettlingStartup<Unit> { throw kotlinx.coroutines.CancellationException("scope closed") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            rethrown = true
        }
        assertTrue("Cancellation must propagate, not be reported as a storage failure", rethrown)
    }
}
