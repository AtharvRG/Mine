// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DbGateTest {

    @Test
    fun testReentrantReadLock_SameThread_DoesNotDeadlock() {
        var executed = false
        DbGate.withReadLockSync {
            DbGate.withReadLockSync {
                DbGate.withReadLockSync {
                    executed = true
                }
            }
        }
        assertTrue("Reentrant read lock must execute without deadlocking", executed)
    }

    @Test
    fun testConcurrentReads_AllowedSimultaneously() {
        val count = 5
        val enterLatch = CountDownLatch(count)
        val releaseLatch = CountDownLatch(1)
        val activeReaders = AtomicInteger(0)
        val maxSimultaneous = AtomicInteger(0)

        val threads = (1..count).map {
            Thread {
                DbGate.withReadLockSync {
                    val current = activeReaders.incrementAndGet()
                    maxSimultaneous.updateAndGet { maxOf(it, current) }
                    enterLatch.countDown()
                    releaseLatch.await(2, TimeUnit.SECONDS)
                    activeReaders.decrementAndGet()
                }
            }
        }

        threads.forEach { it.start() }
        val enteredAll = enterLatch.await(2, TimeUnit.SECONDS)
        releaseLatch.countDown()
        threads.forEach { it.join(2000) }

        assertTrue("All reader threads must acquire read lock concurrently", enteredAll)
        assertEquals("All 5 readers must be in the read lock concurrently", count, maxSimultaneous.get())
    }

    @Test
    fun testWriteLock_ExcludesReadLock() {
        val writeEntered = CountDownLatch(1)
        val writeRelease = CountDownLatch(1)
        val readAttemptedWhileWriteActive = AtomicBoolean(false)
        val readFinished = CountDownLatch(1)

        val writeThread = Thread {
            DbGate.withWriteLockSync {
                writeEntered.countDown()
                writeRelease.await(2, TimeUnit.SECONDS)
            }
        }

        val readThread = Thread {
            writeEntered.await(2, TimeUnit.SECONDS)
            // Attempt read lock while write lock is held; should block until writeRelease
            DbGate.withReadLockSync {
                if (writeRelease.count > 0) {
                    readAttemptedWhileWriteActive.set(true)
                }
                readFinished.countDown()
            }
        }

        writeThread.start()
        readThread.start()

        writeEntered.await(2, TimeUnit.SECONDS)
        Thread.sleep(100) // Ensure readThread is waiting on the gate
        assertFalse("Read lock must not enter while write lock is held", readAttemptedWhileWriteActive.get())

        writeRelease.countDown()
        val readSucceeded = readFinished.await(2, TimeUnit.SECONDS)

        writeThread.join(2000)
        readThread.join(2000)

        assertTrue("Read lock must proceed once write lock is released", readSucceeded)
        assertFalse(readAttemptedWhileWriteActive.get())
    }
}
