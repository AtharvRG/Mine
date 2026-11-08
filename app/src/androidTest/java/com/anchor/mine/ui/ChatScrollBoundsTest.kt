// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Quote
import com.anchor.core.models.QuoteSlot
import com.anchor.core.models.QuoteSource
import com.anchor.core.ui.UiState
import com.anchor.mine.ChatScreen
import org.junit.Rule
import org.junit.Test

/**
 * H-09 (R-13): `ChatScreen` computed the scroll target as `state.chatMessages.size + receipts +
 * proposals + …` and passed it straight to `animateScrollToItem`, assuming that sum equals the
 * LazyColumn's item count. The fix scrolls to `layoutInfo.totalItemsCount - 1` instead.
 *
 * H-09b (measured on `moto g73 5G - 14`, 2026-11-07): the clamped version still never scrolled.
 * A `LaunchedEffect` starts after the first composition commits but *before* the LazyColumn's
 * first measure, so `layoutInfo.totalItemsCount` was 0 on entry, `lastIndex` was -1, and the
 * `>= 0` guard skipped the scroll — the list stayed at item 0. The fix awaits the first non-empty
 * layout (`snapshotFlow { totalItemsCount }.first { it > 0 }`) before computing the target.
 *
 * What these tests can and cannot prove (measured, not assumed):
 * - They CANNOT prove the defect by injecting an oversized index: `animateScrollToItem` accepts
 *   any index >= 0 and its loop stops at the list end when `delta != consumed` ("Hit end without
 *   finding the item"). An oversized index does not throw — it just lands at the bottom.
 * - They CAN prove the contract: after the effect settles, `firstVisibleItemIndex` is the last
 *   item and the whole composition ran without an exception. A regression that skips the scroll
 *   (the H-09b defect) fails on the index assertion, not on `assertExists`.
 * - There was never a hang. An earlier record of `ComposeNotIdleException` here was wrong; all
 *   three tests complete in ~1-2 s each.
 */
class ChatScrollBoundsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun testScrollLandsOnLastRenderedItem() {
        // The quote card and the loading row are rendered but are not LazyColumn items in the
        // summed sense the old code used: the state's hand-summed total (40 + 1) does not equal
        // the list's item count (40). The effect must still land on the real last item.
        val state = UiState(
            chatMessages = (1..40).map { ChatMessage("m$it", "user", "message $it") },
            homeQuote = Quote(QuoteSlot.MORNING, "2026-10-28", "quote", 0L, source = QuoteSource.PRESET),
            isChatLoading = true
        )
        var listState: LazyListState? = null
        compose.setContent {
            val s = rememberLazyListState()
            listState = s
            ChatScreen(state, onAction = {}, listState = s)
        }
        // waitForIdle rethrows any exception raised by the LaunchedEffect's scroll coroutine.
        compose.waitForIdle()

        // The last message must be composed by the time the scroll settles: if the effect threw
        // or scrolled short, it is not in the node tree.
        compose.onNodeWithText("USER: message 40").assertExists()
        // H-09b: the effect used to read `layoutInfo.totalItemsCount` before the LazyColumn's
        // first measure (0 on entry), take the `lastIndex < 0` branch, and never scroll — the
        // list stayed at item 0. Assert the position, not just presence, so a future regression
        // that composes item 40 without scrolling to it still fails. The last *visible* index is
        // the right probe: a lazy list fills the viewport, so firstVisibleItemIndex lands well
        // before 40 (22 on a moto g73) and only the last visible index reaches the bottom.
        org.junit.Assert.assertEquals(
            "scroll must land on the last item",
            40,
            listState!!.layoutInfo.visibleItemsInfo.last().index
        )
    }

    @Test
    fun testShortList_ComposesWithoutHanging() {
        // Isolates "the effect never settles" from "the list is long": three messages fit on
        // screen, so no scrolling is needed at all. If this hangs too, the busy measure/layout
        // is not caused by scroll distance.
        val state = UiState(chatMessages = (1..3).map { ChatMessage("m$it", "user", "message $it") })
        compose.setContent { ChatScreen(state, onAction = {}) }
        compose.waitForIdle()
        compose.onNodeWithText("USER: message 3").assertExists()
    }

    @Test
    fun testEmptyState_DoesNotScrollOrThrow() {
        // lastIndex is -1 with an empty list; the `>= 0` guard must keep it from scrolling.
        compose.setContent { ChatScreen(UiState(), onAction = {}) }
        compose.waitForIdle()
        compose.onNodeWithText("Send").assertExists()
    }
}
