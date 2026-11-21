package com.mine.companion.ui.components

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mine.companion.store.AmbiguityPrompt
import com.mine.companion.store.DeterministicReceipt
import com.mine.companion.store.MineAction
import com.mine.companion.store.ModalId
import com.mine.companion.store.ReceiptActionType
import com.mine.companion.store.mineDispatch
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.icons.LucideGlyph
import com.mine.companion.ui.primitives.Fx
import com.mine.companion.ui.primitives.Presence
import com.mine.companion.ui.primitives.Pressable
import com.mine.companion.ui.theme.HapticKind
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Shapes
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.matCard
import com.mine.companion.ui.theme.matRaised
import com.mine.companion.ui.theme.rememberHaptics
import com.mine.companion.ui.theme.specular
import com.mine.companion.ui.theme.w

private val ClarifyPrefix = Regex("^Clarify:\\s*")

/**
 * components/chat/ProposalRow.tsx — Resolution.Ambiguous → Proposal → ChatItem.ProposalRow.
 * Choosing an option morphs that chip into the confirmed state (shared bounds in a
 * LookaheadScope, spring.snappy) and the other options fold away. One tap, no dialog.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
fun ProposalRow(messageId: String, prompt: AmbiguityPrompt) {
    val dispatch = mineDispatch()
    val haptics = rememberHaptics()
    val resolved = prompt.options.find { it.id == prompt.resolvedOptionId }
    val transform = BoundsTransform { _, _ -> MineMotion.snappy(Rect.VisibilityThreshold) }

    LookaheadScope {
        Column(
            Modifier
                .fillMaxWidth()
                .animateBounds(this, boundsTransform = transform),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt(
                if (resolved != null) "You chose" else prompt.question.replace(ClarifyPrefix, ""),
                MineType.Caption.w(500), Mine.Ink3,
                Modifier.animateBounds(this@LookaheadScope, boundsTransform = transform),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                prompt.options.forEach { opt ->
                    val chosen = opt.id == prompt.resolvedOptionId
                    if (resolved != null && !chosen) return@forEach
                    Pressable(
                        onClick = {
                            haptics.confirm()
                            dispatch(MineAction.ResolveAmbiguity(messageId, opt.id))
                        },
                        enabled = resolved == null,
                        disabledAlpha = 1f,
                        feel = MineMotion.Press.Chip,
                        modifier = Modifier
                            .animateBounds(this@LookaheadScope, boundsTransform = transform)
                            .height(40.dp)
                            .then(
                                if (chosen) Modifier.background(Mine.Ink1, Shapes.R14)
                                else Modifier.matRaised(Shapes.R14).specular(Shapes.R14),
                            )
                            .padding(horizontal = 16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (chosen) LIcon(Lucide.Check, 14.dp, Mine.Surface0, strokeWidth = 2.5f)
                            Txt(opt.label, MineType.Footnote.w(500), if (chosen) Mine.Surface0 else Mine.Ink1)
                        }
                    }
                }
            }
        }
    }
}

private fun receiptIcon(t: ReceiptActionType): LucideGlyph = when (t) {
    ReceiptActionType.reschedule -> Lucide.CalendarClock
    ReceiptActionType.note_update -> Lucide.NotebookText
    ReceiptActionType.reminder_add -> Lucide.AlarmClock
    ReceiptActionType.wakeup_armed -> Lucide.AlarmClock
    ReceiptActionType.ambiguity_resolved -> Lucide.GitMerge
    ReceiptActionType.created -> Lucide.Plus
    ReceiptActionType.completed -> Lucide.Check
    ReceiptActionType.removed -> Lucide.Trash2
    ReceiptActionType.memory -> Lucide.Brain
    ReceiptActionType.error -> Lucide.TriangleAlert
}

/**
 * components/chat/ReceiptChip.tsx — proof that Mine did something (engine ReceiptState).
 * Reads like a sentence; Undo only while not undone; an undone receipt collapses to a
 * struck-through line instead of disappearing, so history stays honest.
 */
@Composable
fun ReceiptChip(messageId: String, receipt: DeterministicReceipt) {
    val dispatch = mineDispatch()
    val undone = receipt.undone == true
    val tone = if (receipt.isError) Mine.Urgent else Mine.Settled
    Presence(visible = true, initial = Fx(alpha = 0f, y = 4.dp), enter = MineMotion.gentle()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .animateContentSize(MineMotion.gentle())
                .then(if (undone) Modifier else Modifier.matCard(Shapes.R14))
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .then(if (undone) Modifier else Modifier.background(tone.copy(alpha = 0.14f), Shapes.R8)),
                contentAlignment = Alignment.Center,
            ) {
                LIcon(
                    if (undone) Lucide.Check else receiptIcon(receipt.actionType),
                    14.dp, if (undone) Mine.Ink4 else tone, strokeWidth = 2f,
                )
            }
            Txt(
                receipt.text, MineType.Footnote, if (undone) Mine.Ink4 else Mine.Ink2,
                Modifier.weight(1f),
                decoration = if (undone) TextDecoration.LineThrough else null,
            )
            Presence(
                visible = !undone && (receipt.undoToken != null || receipt.linkedNoteId != null),
                initial = Fx(alpha = 0f, scale = 0.9f),
                enter = MineMotion.snappy(),
                animateOnFirst = false,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val note = receipt.linkedNoteId
                    if (note != null) {
                        Pressable(
                            onClick = { dispatch(MineAction.OpenModal(ModalId.M07, noteId = note)) },
                            modifier = Modifier.height(32.dp).padding(horizontal = 10.dp),
                        ) { Txt("Open", MineType.Caption.w(500), Mine.Ink2) }
                    }
                    if (receipt.undoToken != null) Pressable(
                        onClick = { dispatch(MineAction.UndoReceipt(messageId, receipt.id)) },
                        haptics = HapticKind.Tick,
                        modifier = Modifier
                            .height(32.dp)
                            .background(Color.White.copy(alpha = 0.06f), Shapes.R10)
                            .padding(horizontal = 10.dp),
                    ) { Txt("Undo", MineType.Caption.w(600), Mine.Ink1) }
                }
            }
        }
    }
}
