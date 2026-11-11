// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.export

import com.anchor.core.SharedFakeStorage
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoExportSchedulerTest {

    @Test
    fun testShouldRunAutoExport_DisabledOrMissingFolder_ReturnsFalse() = runBlocking {
        val clock = FakeClock(100_000_000L)
        val storage = SharedFakeStorage(clock)
        val scheduler = AutoExportScheduler(storage, clock)

        // Disabled by default
        assertFalse(scheduler.shouldRunAutoExport())

        // Enabled but no folder URI
        scheduler.setEnabled(true)
        assertFalse(scheduler.shouldRunAutoExport())
    }

    @Test
    fun testShouldRunAutoExport_IntervalPassedAndFolderSet_ReturnsTrue() = runBlocking {
        val clock = FakeClock(100_000_000L)
        val storage = SharedFakeStorage(clock)
        val scheduler = AutoExportScheduler(storage, clock)

        scheduler.setEnabled(true)
        scheduler.setFolderUri("content://com.android.externalstorage.documents/tree/primary%3ABackups")
        scheduler.setIntervalDays(3)

        // Never run before -> true
        assertTrue(scheduler.shouldRunAutoExport())

        // Record run just now
        scheduler.recordExportSuccess(clock.now())
        assertFalse(scheduler.shouldRunAutoExport())

        // Advance 2 days (less than 3 days) -> still false
        clock.currentTime += 2L * 86_400_000L
        assertFalse(scheduler.shouldRunAutoExport())

        // Advance past 3 days -> true
        clock.currentTime += 1L * 86_400_000L + 1000L
        assertTrue(scheduler.shouldRunAutoExport())
    }

    @Test
    fun testPruneOldBackups_KeepsOnlyLastKFiles() {
        val files = listOf(
            "mine-backup-2026-11-01.mnex" to 1700010000000L,
            "mine-backup-2026-11-04.mnex" to 1700040000000L,
            "mine-backup-2026-11-07.mnex" to 1700070000000L,
            "mine-backup-2026-11-10.mnex" to 1700100000000L,
            "mine-backup-2026-11-13.mnex" to 1700130000000L,
            "mine-backup-2026-11-16.mnex" to 1700160000000L
        )

        // Retention = 3
        val toPrune = AutoExportScheduler.findFilesToPrune(files, retentionCount = 3)

        assertEquals("Must prune 3 oldest files to keep exactly 3", 3, toPrune.size)
        val prunedNames = toPrune.map { it.first }
        assertTrue(prunedNames.contains("mine-backup-2026-11-01.mnex"))
        assertTrue(prunedNames.contains("mine-backup-2026-11-04.mnex"))
        assertTrue(prunedNames.contains("mine-backup-2026-11-07.mnex"))
        assertFalse(prunedNames.contains("mine-backup-2026-11-16.mnex"))
    }
}
