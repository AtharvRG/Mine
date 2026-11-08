// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.anchor.core.ui.UiState
import com.anchor.mine.ProviderScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * O-2: after a save the app re-renders the Provider tab with the persisted config. The text
 * fields must show that config exactly, not the new value concatenated onto the old one.
 *
 * Observed on the R10g release build: `https://api.openai.com/vhttps://api.mistral.ai/v1/...`.
 * That is the signature of field state seeded once from an empty state and never re-seeded when
 * the real config arrived, so the user's typed text and the loaded text both ended up in the
 * buffer.
 */
class ProviderScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun testProviderScreen_RecomposedWithPersistedConfig_ShowsExactValues() {
        val saved = Triple(
            "https://api.mistral.ai/v1/chat/completions",
            "sk-live-key",
            "mistral-small-latest"
        )
        // First frame: the controller has not finished loading settings yet (empty). Second
        // frame: the persisted config arrives. The fields must follow it.
        val state = mutableStateOf(UiState())

        compose.setContent {
            ProviderScreen(state.value, onSave = { _, _, _ -> })
        }
        state.value = UiState(
            providerEndpoint = saved.first,
            providerKey = saved.second,
            providerModel = saved.third
        )
        compose.waitForIdle()

        // The field node's Text is the label, not the value, so assert on the editable content.
        compose.onNodeWithTag("providerEndpoint").assert(hasText(saved.first))
        compose.onNodeWithTag("providerModel").assert(hasText(saved.third))
    }

    @Test
    fun testProviderScreen_EditThenSave_ReportsExactlyWhatIsInTheField() {
        val initial = UiState(
            providerEndpoint = "https://api.openai.com/v1/chat/completions",
            providerKey = "sk-old",
            providerModel = "gpt-4o-mini"
        )
        var reported: Triple<String, String, String>? = null

        compose.setContent {
            ProviderScreen(state = initial) { ep, k, m -> reported = Triple(ep, k, m) }
        }

        compose.onNodeWithTag("providerEndpoint").performTextReplacement("https://api.mistral.ai/v1/chat/completions")
        compose.onNodeWithTag("providerModel").performTextReplacement("mistral-small-latest")
        compose.onNodeWithText("Save").performClick()

        assertEquals(
            "Save must report the field contents verbatim — no concatenation with the old value",
            Triple("https://api.mistral.ai/v1/chat/completions", "sk-old", "mistral-small-latest"),
            reported
        )
        assertEquals(1, compose.onAllNodesWithText("Save").fetchSemanticsNodes().size)
    }
}
