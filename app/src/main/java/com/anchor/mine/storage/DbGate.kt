// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coroutine-aware reader-writer gate protecting SQLite operations from concurrent file swaps or resets.
 *
 * Normal reads and writes acquire read lock (allowing high concurrent throughput via WAL).
 * Swaps (import restore) and database wipes/resets acquire write lock exclusively,
 * guaranteeing zero queries are in flight when closing connections or renaming database files.
 */
object DbGate {
    private val writeMutex = Mutex()
    private val activeReaders = AtomicInteger(0)
    @Volatile private var allReadersFinished: CompletableDeferred<Unit>? = null

    suspend fun <T> withReadLock(block: suspend () -> T): T {
        writeMutex.withLock {
            activeReaders.incrementAndGet()
        }
        try {
            return block()
        } finally {
            val remaining = activeReaders.decrementAndGet()
            if (remaining == 0) {
                allReadersFinished?.complete(Unit)
            }
        }
    }

    suspend fun <T> withWriteLock(block: suspend () -> T): T {
        writeMutex.withLock {
            if (activeReaders.get() > 0) {
                val deferred = CompletableDeferred<Unit>()
                allReadersFinished = deferred
                if (activeReaders.get() > 0) {
                    deferred.await()
                }
                allReadersFinished = null
            }
            return block()
        }
    }

    fun <T> withReadLockSync(block: () -> T): T {
        return kotlinx.coroutines.runBlocking {
            withReadLock {
                block()
            }
        }
    }

    fun <T> withWriteLockSync(block: () -> T): T {
        return kotlinx.coroutines.runBlocking {
            withWriteLock {
                block()
            }
        }
    }
}
