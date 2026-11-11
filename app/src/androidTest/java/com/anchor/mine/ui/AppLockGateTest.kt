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
import com.anchor.mine.MineAppUI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Blueprint 16 §3.2 & Blueprint 90 Row 1: App lock as a UI gate.
 *
 * Verifies that when App Lock is active and locked, the tabs and content are inaccessible,
 * and unlocking dispatches UnlockApp to the controller.
 */
class AppLockGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<UiAction>()

    private fun show(state: UiState) {
        compose.setContent {
            MineAppUI(
                state = state,
                onAction = { actions += it },
                onUnlockRequested = { actions += UiAction.UnlockApp }
            )
        }
    }

    @Test
    fun appLocked_ShowsLockScreen_AndNoTabs() {
        show(UiState(onboardingDone = true, appLockEnabled = true, isAppLocked = true))

        compose.onNodeWithTag("appLockScreen").assertIsDisplayed()
        compose.onNodeWithText("Mine is Locked").assertIsDisplayed()
        compose.onNodeWithTag("unlockAppButton").assertIsDisplayed()

        compose.onNodeWithText("Chat").assertDoesNotExist()
        compose.onNodeWithText("Reminders").assertDoesNotExist()
        compose.onNodeWithText("Debug").assertDoesNotExist()
    }

    @Test
    fun unlockButton_DispatchesUnlockApp() {
        show(UiState(onboardingDone = true, appLockEnabled = true, isAppLocked = true))

        compose.onNodeWithTag("unlockAppButton").performClick()

        assertTrue("Tapping unlock button dispatches UnlockApp", actions.contains(UiAction.UnlockApp))
    }

    @Test
    fun appUnlocked_ShowsTabs_AndNoLockScreen() {
        show(UiState(onboardingDone = true, appLockEnabled = true, isAppLocked = false))

        compose.onNodeWithTag("appLockScreen").assertDoesNotExist()
        compose.onNodeWithText("Chat").assertIsDisplayed()
        compose.onNodeWithText("Debug").assertIsDisplayed()
    }

    @Test
    fun appLockDisabled_ShowsTabs_EvenIfLockedFlagIsTrue() {
        show(UiState(onboardingDone = true, appLockEnabled = false, isAppLocked = true))

        compose.onNodeWithTag("appLockScreen").assertDoesNotExist()
        compose.onNodeWithText("Chat").assertIsDisplayed()
    }
}
