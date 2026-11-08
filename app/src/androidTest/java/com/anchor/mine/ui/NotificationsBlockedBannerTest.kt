// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.anchor.core.ui.UiState
import com.anchor.mine.MineAppUI
import org.junit.Rule
import org.junit.Test

/**
 * H-11 (R-15): a denied POST_NOTIFICATIONS used to be silent — reminders were still armed and the
 * self-test still reported success, but nothing could alert the user. The banner is driven by
 * [UiState.notificationsBlocked]; this pins that it is shown, is visible on every tab, and is not
 * shown when notifications are allowed.
 */
class NotificationsBlockedBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: UiState) {
        compose.setContent { MineAppUI(state, onAction = {}) }
    }

    @Test
    fun testDeniedPermission_ShowsBannerOnEveryTab() {
        show(UiState(notificationsBlocked = true, onboardingDone = true))

        compose.onNodeWithTag("notificationsBlockedBanner").assertIsDisplayed()

        // The banner must survive tab switches: a lost reminder is not a Chat-only problem.
        compose.onNodeWithText("Reminders").performClick()
        compose.onNodeWithTag("notificationsBlockedBanner").assertIsDisplayed()
        compose.onNodeWithText("Debug").performClick()
        compose.onNodeWithTag("notificationsBlockedBanner").assertIsDisplayed()
    }

    @Test
    fun testAllowedNotifications_ShowsNoBanner() {
        show(UiState(notificationsBlocked = false, onboardingDone = true))

        compose.onNodeWithTag("notificationsBlockedBanner").assertDoesNotExist()
    }
}
