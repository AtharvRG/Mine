// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.anchor.core.ui.UiAction
import com.anchor.core.ui.UiState
import com.anchor.mine.MineAppUI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Blueprint 52 §3.1: the first-run flow owns the screen until it is finished.
 *
 * Pins the gate itself (a fresh install cannot reach the tabs; a finished one never sees the flow),
 * that consent cannot be skipped, and that the controls dispatch the actions the controller listens
 * for — a screen that renders but wires nothing is the failure mode this is here to catch.
 */
class OnboardingGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<UiAction>()

    private fun show(state: UiState) {
        compose.setContent { MineAppUI(state, onAction = { actions += it }) }
    }

    @Test
    fun freshInstall_ShowsTheFlow_AndNoTabs() {
        show(UiState(onboardingDone = false))

        compose.onNodeWithTag("onboardingScreen").assertIsDisplayed()
        compose.onNodeWithText("Welcome to Mine").assertIsDisplayed()
        compose.onNodeWithText("Chat").assertDoesNotExist()
        compose.onNodeWithText("Debug").assertDoesNotExist()
    }

    @Test
    fun finishedInstall_ShowsTheTabs_AndNoFlow() {
        show(UiState(onboardingDone = true))

        compose.onNodeWithTag("onboardingScreen").assertDoesNotExist()
        compose.onNodeWithText("Chat").assertIsDisplayed()
    }

    @Test
    fun consentStep_CannotContinueUntilItIsAccepted() {
        show(UiState(onboardingDone = false, onboardingStep = 1))

        compose.onNodeWithText("Your data stays on this phone.").assertIsDisplayed()
        compose.onNodeWithTag("onboardingNext").assertIsNotEnabled()

        compose.onNodeWithTag("consentCheckbox").performClick()
        compose.onNodeWithTag("onboardingNext").assertIsEnabled().performClick()

        assertTrue("Continue dispatches OnboardingNext", actions.contains(UiAction.OnboardingNext))
    }

    @Test
    fun dayStep_ConfirmDispatchesTheChosenWindow() {
        show(UiState(onboardingDone = false, onboardingStep = 2))

        compose.onNodeWithTag("dayLengthHint").assertIsDisplayed()
        compose.onNodeWithTag("onboardingConfirmDay").performClick()

        val confirm = actions.filterIsInstance<UiAction.OnboardingConfirmDay>().single()
        assertEquals("the defaults are the blueprint's 04:00–22:00", 4 * 60, confirm.startMinute)
        assertEquals(22 * 60, confirm.endMinute)
    }

    @Test
    fun soulStep_TypingAndTheHealthSwitchDispatchTheirActions() {
        show(UiState(onboardingDone = false, onboardingStep = 3))

        compose.onNodeWithTag("soulAnswer-identity").performTextInput("I am a nurse")
        compose.onNodeWithTag("healthLocalOnlySwitch").performClick()
        compose.onNodeWithTag("onboardingFinish").performClick()

        assertTrue(
            "typing dispatches the answer for its category",
            actions.any { it is UiAction.OnboardingAnswer && it.category == "identity" && it.text == "I am a nurse" }
        )
        assertTrue(
            "the switch dispatches local-only",
            actions.contains(UiAction.OnboardingHealthLocalOnly(true))
        )
        assertTrue("Finish dispatches OnboardingFinish", actions.contains(UiAction.OnboardingFinish))
    }

    @Test
    fun error_IsShownOnTheFlow() {
        show(UiState(onboardingDone = false, onboardingStep = 3, onboardingError = "Could not save your answers: disk full"))

        compose.onNodeWithTag("onboardingError").assertIsDisplayed()
    }
}
