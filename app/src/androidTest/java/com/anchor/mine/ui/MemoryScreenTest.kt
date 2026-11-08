// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.anchor.core.models.Node
import com.anchor.core.models.SoulFact
import com.anchor.core.ui.UiAction
import com.anchor.core.ui.UiState
import com.anchor.mine.MineAppUI
import com.anchor.mine.MemoryScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Row 20, read-only slice of blueprint 51. The audit's proof for this step: "Compose test: viewer
 * lists nodes/soul facts".
 *
 * These drive the stateless shell, so they pin what the user sees for a given state — not the
 * load, which `MemoryViewerLoadTest` pins.
 */
class MemoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun node(id: String, title: String) =
        Node(id, "task", title, "active", 5000L)

    private fun fact(id: String, statement: String) =
        SoulFact(id, "Preferences", statement, 0.9, "chat", "inferred", "active", 100L)

    @Test
    fun memoryScreen_ListsItemsAndSoulFacts_AndTheInboxCount() {
        compose.setContent {
            MemoryScreen(
                UiState(
                    memoryLoaded = true,
                    inboxCount = 3,
                    memoryNodes = listOf(node("n1", "Pay the electricity bill"), node("n2", "Book the dentist")),
                    memorySoulFacts = listOf(fact("s1", "Prefers tea over coffee"))
                )
            )
        }

        compose.onNodeWithTag("memoryNode-n1").assertIsDisplayed()
        compose.onNodeWithText("Pay the electricity bill").assertIsDisplayed()
        compose.onNodeWithTag("memoryNode-n2").assertIsDisplayed()
        compose.onNodeWithTag("memoryFact-s1").assertIsDisplayed()
        compose.onNodeWithText("Prefers tea over coffee").assertIsDisplayed()
        compose.onNodeWithText("Inbox: 3 waiting").assertIsDisplayed()
    }

    @Test
    fun memoryScreen_LocalOnlyRows_CarryTheLockMarker() {
        compose.setContent {
            MemoryScreen(
                UiState(
                    memoryLoaded = true,
                    memoryNodes = listOf(node("n1", "Private note").copy(localOnly = true)),
                    memorySoulFacts = listOf(fact("s1", "Private fact").copy(localOnly = true))
                )
            )
        }

        // Blueprint 51 §3.1 rule 6: a small lock marks local-only rows.
        compose.onNodeWithText("Private note  🔒").assertIsDisplayed()
        compose.onNodeWithText("Private fact  🔒").assertIsDisplayed()
    }

    @Test
    fun memoryScreen_EmptyTables_SaySoInsteadOfShowingNothing() {
        compose.setContent {
            MemoryScreen(UiState(memoryLoaded = true))
        }

        compose.onNodeWithTag("memoryItemsEmpty").assertIsDisplayed()
        compose.onNodeWithTag("memorySoulEmpty").assertIsDisplayed()
    }

    @Test
    fun memoryTab_FirstOpen_AsksForTheLoad_ExactlyOnce() {
        val actions = mutableListOf<UiAction>()
        val state = mutableStateOf(UiState())

        compose.setContent {
            MineAppUI(state.value, onAction = { actions.add(it) })
        }
        compose.onNodeWithText("Memory").performClick()
        compose.waitForIdle()

        assertTrue("the tab must request a load on first open: $actions", actions.contains(UiAction.LoadMemory))

        // The load lands; a later state change must not ask again.
        state.value = UiState(memoryLoaded = true, memoryNodes = listOf(node("n1", "Pay the electricity bill")))
        compose.waitForIdle()
        compose.onNodeWithTag("memoryNode-n1").assertIsDisplayed()
        assertTrue(
            "a loaded viewer must not re-read on every state change: $actions",
            actions.count { it == UiAction.LoadMemory } == 1
        )
    }
}
