// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.anchor.core.ui.UiAction
import com.anchor.core.ui.UiState
import com.anchor.mine.DebugScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Blueprint 92 §2 (About & updates) & Blueprint 52 §3.4 (Settings controls in DebugScreen).
 */
class DebugScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<UiAction>()

    private fun show(state: UiState) {
        compose.setContent {
            DebugScreen(
                state = state,
                onAction = { actions += it }
            )
        }
    }

    @Test
    fun debugScreen_RendersSectionsAndAboutDetails() {
        show(UiState(onboardingDone = true, debugStats = "Nodes: 12\nReminders: 3"))

        compose.onNodeWithText("=== Privacy & Security ===").assertIsDisplayed()
        compose.onNodeWithTag("appLockSwitch").assertIsDisplayed()
        compose.onNodeWithTag("blockScreenshotsSwitch").assertIsDisplayed()

        compose.onNodeWithText("=== AI & Performance ===").assertIsDisplayed()
        compose.onNodeWithTag("compactModeSwitch").assertIsDisplayed()

        compose.onNodeWithText("=== About Mine ===").assertIsDisplayed()
        compose.onNodeWithTag("aboutVersion").assertIsDisplayed()
        compose.onNodeWithTag("aboutSchemaVersion").assertIsDisplayed()
        compose.onNodeWithTag("aboutExportVersion").assertIsDisplayed()
        compose.onNodeWithTag("aboutLicense").assertIsDisplayed()
        compose.onNodeWithTag("checkForUpdatesButton").assertIsDisplayed()

        compose.onNodeWithText("=== Storage Stats ===").assertIsDisplayed()
        compose.onNodeWithTag("storageStatsText").assertIsDisplayed()
        compose.onNodeWithTag("refreshStatsButton").assertIsDisplayed()
    }

    @Test
    fun togglingSettings_DispatchesActions() {
        show(UiState(onboardingDone = true, appLockEnabled = true, relockTimeoutSec = 60))

        compose.onNodeWithTag("appLockSwitch").performClick()
        assertTrue("Dispatches SetAppLock", actions.any { it is UiAction.SetAppLock && !it.enabled })

        compose.onNodeWithTag("blockScreenshotsSwitch").performClick()
        assertTrue("Dispatches SetBlockScreenshots", actions.any { it is UiAction.SetBlockScreenshots && it.enabled })

        compose.onNodeWithTag("compactModeSwitch").performClick()
        assertTrue("Dispatches SetCompactMode", actions.any { it is UiAction.SetCompactMode && it.enabled })

        compose.onNodeWithTag("relockTimeout-300").performClick()
        assertEquals(
            "Dispatches SetRelockTimeout(300)",
            UiAction.SetRelockTimeout(300),
            actions.filterIsInstance<UiAction.SetRelockTimeout>().firstOrNull()
        )

        compose.onNodeWithTag("refreshStatsButton").performClick()
        assertTrue("Dispatches RefreshStats", actions.contains(UiAction.RefreshStats))
    }
}
