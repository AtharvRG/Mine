// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.export

import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock

/**
 * Scheduled auto-export manager per Blueprint 15 §3.5 and Roadmap v1.1 Item 5.
 * Coordinates periodic export schedule to a SAF-designated folder and enforces
 * retention window keeping the last K encrypted backups.
 */
class AutoExportScheduler(
    private val storage: Storage,
    private val clock: Clock
) {

    suspend fun isEnabled(): Boolean =
        storage.getSetting(KEY_ENABLED)?.toBooleanStrictOrNull() ?: false

    suspend fun setEnabled(enabled: Boolean) {
        storage.saveSetting(KEY_ENABLED, enabled.toString())
    }

    suspend fun getFolderUri(): String? =
        storage.getSetting(KEY_FOLDER_URI)

    suspend fun setFolderUri(uri: String?) {
        if (uri != null) {
            storage.saveSetting(KEY_FOLDER_URI, uri)
        } else {
            storage.saveSetting(KEY_FOLDER_URI, "")
        }
    }

    suspend fun getIntervalDays(): Int =
        storage.getSetting(KEY_INTERVAL_DAYS)?.toIntOrNull() ?: DEFAULT_INTERVAL_DAYS

    suspend fun setIntervalDays(days: Int) {
        storage.saveSetting(KEY_INTERVAL_DAYS, days.coerceAtLeast(1).toString())
    }

    suspend fun getRetentionCount(): Int =
        storage.getSetting(KEY_RETENTION_COUNT)?.toIntOrNull() ?: DEFAULT_RETENTION_COUNT

    suspend fun setRetentionCount(count: Int) {
        storage.saveSetting(KEY_RETENTION_COUNT, count.coerceAtLeast(1).toString())
    }

    suspend fun getLastExportTs(): Long =
        storage.getSetting(KEY_LAST_EXPORT_TS)?.toLongOrNull() ?: 0L

    suspend fun recordExportSuccess(now: Long = clock.now()) {
        storage.saveSetting(KEY_LAST_EXPORT_TS, now.toString())
    }

    suspend fun shouldRunAutoExport(): Boolean {
        if (!isEnabled()) return false
        val uri = getFolderUri()
        if (uri.isNullOrBlank()) return false

        val lastTs = getLastExportTs()
        if (lastTs == 0L) return true

        val intervalMs = getIntervalDays() * DAY_MS
        val now = clock.now()

        return (now - lastTs) >= intervalMs
    }

    companion object {
        const val KEY_ENABLED = "export.auto.enabled"
        const val KEY_FOLDER_URI = "export.auto.folder_uri"
        const val KEY_INTERVAL_DAYS = "export.auto.interval_days"
        const val KEY_RETENTION_COUNT = "export.auto.retention_count"
        const val KEY_LAST_EXPORT_TS = "export.auto.last_export_ts"

        const val DEFAULT_INTERVAL_DAYS = 3
        const val DEFAULT_RETENTION_COUNT = 5
        const val DAY_MS = 86_400_000L

        /**
         * Identifies files exceeding the retention limit [retentionCount] to prune.
         * Assumes input pairs of (fileIdentifier, modifiedTimestampMs).
         * Sorts newest first, returns the excess oldest items.
         */
        fun <T> findFilesToPrune(files: List<Pair<T, Long>>, retentionCount: Int = DEFAULT_RETENTION_COUNT): List<Pair<T, Long>> {
            if (files.size <= retentionCount) return emptyList()
            val sortedNewestFirst = files.sortedByDescending { it.second }
            return sortedNewestFirst.drop(retentionCount)
        }
    }
}
