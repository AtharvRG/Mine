// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.export

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.Node
import com.anchor.core.models.SoulFact
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableMarkdownExportTest {

    @Test
    fun testWarningHeaderIsPresentAndLoud() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage(clock)
        val exporter = ReadableMarkdownExporter(storage)

        val markdown = exporter.generateMarkdown()

        assertTrue("Header must indicate human readable export", markdown.contains("# Mine Data Export"))
        assertTrue("Header must contain loud unencrypted plain text warning", markdown.contains("WARNING: UNENCRYPTED PLAIN TEXT EXPORT"))
        assertTrue("Header must warn that export is not importable", markdown.contains("cannot be re-imported"))
    }

    @Test
    fun testExportContainsNodesHabitsAndSoulFacts() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage(clock)
        val exporter = ReadableMarkdownExporter(storage)

        val task = Node("n1", "task", "Submit quarterly report", "active", dueAt = 1700010000000L)
        val habit = Node("n2", "habit", "Daily Meditation", "active", recurrenceRule = "FREQ=DAILY", streakCurrent = 7, streakBest = 14)
        storage.saveNode(task)
        storage.saveNode(habit)

        val fact = SoulFact("f1", "preferences", "Prefers direct communication", 0.9, "user", "manual", "active", 1700000000000L)
        storage.saveSoulFact(fact)

        val markdown = exporter.generateMarkdown()

        assertTrue(markdown.contains("Submit quarterly report"))
        assertTrue(markdown.contains("Daily Meditation"))
        assertTrue(markdown.contains("Streak: 7"))
        assertTrue(markdown.contains("Prefers direct communication"))
        assertTrue(markdown.contains("preferences"))
    }

    @Test
    fun testExportGroupsMessagesByDayAndRespectsRedaction() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage(clock)
        val exporter = ReadableMarkdownExporter(storage)

        storage.insertRawMessage("m1", "user", "Hello world on day 1", causeMsgId = null, dayKey = "2026-11-10")
        storage.insertRawMessage("m2", "assistant", "Hi there!", causeMsgId = null, dayKey = "2026-11-10")
        storage.insertRawMessage("m3", "user", "Secret info that got redacted", causeMsgId = null, dayKey = "2026-11-11")

        storage.redactMessage("m3")

        val markdown = exporter.generateMarkdown()

        assertTrue("Must group under day header 2026-11-10", markdown.contains("## Day: 2026-11-10"))
        assertTrue("Must contain unredacted message", markdown.contains("Hello world on day 1"))
        assertTrue("Must contain assistant reply", markdown.contains("Hi there!"))
        assertTrue("Must group under day header 2026-11-11", markdown.contains("## Day: 2026-11-11"))
        assertFalse("Must not leak redacted text", markdown.contains("Secret info that got redacted"))
        assertTrue("Must indicate redacted message", markdown.contains("[Redacted]"))
    }
}
