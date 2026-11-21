package com.mine.companion.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.mine.companion.store.DeterministicReceipt
import com.mine.companion.store.MineAction
import com.mine.companion.store.ModalId
import com.mine.companion.store.ScreenId
import com.mine.companion.store.mineDispatch
import com.mine.companion.store.mineSelect
import com.mine.companion.ui.icons.IconDeterministicCheck
import com.mine.companion.ui.icons.IconLivingNote
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.primitives.Fx
import com.mine.companion.ui.primitives.FxSwap
import com.mine.companion.ui.primitives.Pressable
import com.mine.companion.ui.primitives.tap
import com.mine.companion.ui.theme.Hex
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.mineText
import com.mine.companion.ui.theme.serif
import com.mine.companion.ui.theme.w

/* -------------------------------- C13 Quote card -------------------------------- */

private val QuoteShape = RoundedCornerShape(26.dp)

/** C13: evening reflection; tap swaps preset ↔ live synthesis (y ±4, .2s, mode wait). */
@Composable
fun C13QuoteCard() {
    var liveMode by remember { mutableStateOf(false) }
    Pressable(
        onClick = { liveMode = !liveMode },
        feel = MineMotion.Press.Card,
        contentAlignment = Alignment.TopStart,
        modifier = Modifier
            .fillMaxWidth()
            .dropShadow(QuoteShape, Shadow(radius = 30.dp, color = Color.Black.copy(alpha = 0.6f), spread = (-10).dp, offset = DpOffset(0.dp, 14.dp)))
            .background(Mine.Surface0, QuoteShape)
            .innerShadow(QuoteShape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.14f), offset = DpOffset(0.dp, 1.dp)))
            .border(1.dp, Color.White.copy(alpha = 0.09f), QuoteShape)
            .clip(QuoteShape)
            .padding(16.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val micro = mineText(10f, mono = true, tracking = 0.14f)
                Txt("EVENING REFLECTION • 18:00–22:00 SLOT", micro, Hex.Gold.copy(alpha = 0.8f))
                Txt(if (liveMode) "LIVE SYNTHESIS" else "TAP FOR CONTEXT", micro, Hex.Bone.copy(alpha = 0.45f))
            }
            FxSwap(
                target = liveMode,
                initial = Fx(alpha = 0f, y = 4.dp),
                exit = Fx(alpha = 0f, y = (-4).dp),
                enter = tween(200, easing = MineMotion.FmEaseInOut),
                wait = true,
            ) { live ->
                val style = mineText(19f, 400, lineHeight = 1.32f, italic = true).serif()
                if (!live) Txt("“Complexity in systems is tamed not by haste, but by isolating the invariant that refuses to hold.”", style, Hex.Bone)
                else Txt("“Two slips on the page-table lab only mean the problem was too large—twenty focused minutes on walk() changes the evening.”", style, Hex.Gold)
            }
        }
    }
}

/* ----------------------- C25 legacy deterministic receipt ----------------------- */

@Composable
fun C25DeterministicReceipt(messageId: String, receipt: DeterministicReceipt) {
    val dispatch = mineDispatch()
    val undone = receipt.undone == true
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .background(if (undone) Color.White.copy(alpha = 0.02f) else Hex.ReceiptBg.copy(alpha = 0.9f), shape)
            .border(1.dp, if (undone) Color.White.copy(alpha = 0.05f) else Hex.Moss.copy(alpha = 0.3f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val mono = mineText(11f, mono = true)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconDeterministicCheck(14.dp, if (undone) Hex.Bone.copy(alpha = 0.3f) else Hex.Moss)
            Txt(
                receipt.text, mono, if (undone) Hex.Bone.copy(alpha = 0.4f) else Hex.Bone.copy(alpha = 0.9f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                decoration = if (undone) TextDecoration.LineThrough else null,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val small = mineText(10f, mono = true)
            val note = receipt.linkedNoteId
            if (note != null && !undone) {
                Row(
                    Modifier
                        .background(Hex.Gold.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .tap { dispatch(MineAction.OpenModal(ModalId.M07, noteId = note)) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconLivingNote(11.dp, Hex.Gold)
                    Txt("View Note", small, Hex.Gold)
                }
            }
            if (!undone) {
                Txt(
                    "Undo", small, Hex.Bone.copy(alpha = 0.75f),
                    Modifier
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
                        .tap { dispatch(MineAction.UndoReceipt(messageId, receipt.id)) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/* ------------------------- C28 liquid edge scrubber ------------------------- */

private val TimeAnchors = listOf("14:15 • Thu", "17:42 • Eve", "17:43 • AI", "18:05 • Now", "Bottom")
private val ScrubberShape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)

/** C28: edge tabs that expand (spring 420/28) into time anchors; tap selects. */
@Composable
fun C28LiquidEdgeScrubber(modifier: Modifier = Modifier, onSelectAnchor: ((Int) -> Unit)? = null) {
    var activeIdx by remember { mutableStateOf<Int?>(null) }
    Column(
        modifier
            .fillMaxHeight()
            .padding(vertical = 16.dp)
            .padding(end = 2.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        TimeAnchors.forEachIndexed { idx, label ->
            val expanded = activeIdx == idx
            val w by animateDpAsState(if (expanded) 94.dp else 10.dp, MineMotion.fm(420f, 28f), label = "scrub-w")
            val h by animateDpAsState(if (expanded) 28.dp else 18.dp, MineMotion.fm(420f, 28f), label = "scrub-h")
            Box(
                Modifier
                    .width(w)
                    .height(h)
                    .dropShadow(ScrubberShape, Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.8f), offset = DpOffset((-6).dp, 0.dp)))
                    .background(Hex.Abyss, ScrubberShape)
                    .border(1.dp, Color.White.copy(alpha = 0.16f), ScrubberShape)
                    .clip(ScrubberShape)
                    .pointerInput(idx) {
                        detectTapGestures(
                            onPress = {
                                activeIdx = idx
                                if (!tryAwaitRelease()) activeIdx = null
                            },
                            onTap = { activeIdx = idx; onSelectAnchor?.invoke(idx) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (expanded) {
                    Txt(label, mineText(10.5f, 600, mono = true), Hex.Parchment, Modifier.padding(horizontal = 8.dp), maxLines = 1)
                } else {
                    Box(Modifier.size(width = 4.dp, height = 10.dp).background(Color.White.copy(alpha = 0.3f), CircleShape))
                }
            }
        }
    }
}

/* ------------------------ C31 strikethrough history ------------------------ */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun C31StrikethroughHistory(history: List<String>, currentDue: String, slipCount: Int, lightSurface: Boolean = false) {
    val mono = mineText(10.5f, mono = true)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        history.forEach { past ->
            Txt(
                past.substringBefore(','), mono,
                if (lightSurface) Hex.Ink111215.copy(alpha = 0.45f) else Hex.Bone.copy(alpha = 0.4f),
                decoration = TextDecoration.LineThrough,
            )
            Txt("→", mono, if (lightSurface) Hex.Ink111215.copy(alpha = 0.35f) else Hex.Bone.copy(alpha = 0.3f))
        }
        Txt(currentDue, mono.w(600), if (lightSurface) Hex.Ink111215 else Hex.Bone)
        if (slipCount > 0) {
            val shape = RoundedCornerShape(6.dp)
            Txt(
                "Slipped ${slipCount}x", mineText(9.5f, 600, mono = true),
                if (lightSurface) Hex.RoseDeep else Hex.RoseLight,
                Modifier
                    .background(Hex.Rose.copy(alpha = 0.15f), shape)
                    .border(1.dp, Hex.Rose.copy(alpha = 0.3f), shape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/* --------------------------- TopStatusBarHeader --------------------------- */

enum class HeaderVariant { Home, Chat, DayPage }

/** TopStatusBarHeader.tsx: ghost controls in a 48dp sticky header. */
@Composable
fun TopStatusBarHeader(
    variant: HeaderVariant,
    title: String? = null,
    onResetChatToCenter: (() -> Unit)? = null,
    isCenteredEmptyState: Boolean = false,
) {
    val dispatch = mineDispatch()
    val pastDate = mineSelect { it.selectedPastDate }
    val breakLeft = mineSelect { it.breakMinutesLeft }
    val ghost = Hex.Mute
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Hex.ChatBg.copy(alpha = 0.85f))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (variant == HeaderVariant.Home) {
                Box(Modifier.padding(end = 0.dp).tap { dispatch(MineAction.OpenModal(ModalId.M03)) }.padding(8.dp)) {
                    LIcon(Lucide.Menu, 18.dp, ghost)
                }
            } else {
                Box(Modifier.tap { dispatch(MineAction.Navigate(ScreenId.S14)) }.padding(8.dp)) {
                    LIcon(Lucide.ChevronLeft, 18.dp, ghost)
                }
                Row(
                    Modifier
                        .tap { dispatch(MineAction.OpenModal(ModalId.M01)) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Txt(title ?: if (variant == HeaderVariant.Chat) "Today" else pastDate, mineText(13f, 500), ghost)
                    LIcon(Lucide.ChevronDown, 14.dp, ghost.copy(alpha = 0.6f))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (variant == HeaderVariant.Chat && onResetChatToCenter != null) {
                Txt(
                    if (isCenteredEmptyState) "Show history" else "New prompt", mineText(12f), ghost,
                    Modifier.tap(onClick = onResetChatToCenter).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (breakLeft != null) {
                Txt(
                    "Break · ${breakLeft}m", mineText(12f), ghost,
                    Modifier
                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                        .tap { dispatch(MineAction.OpenModal(ModalId.M02)) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Box(Modifier.tap { dispatch(MineAction.OpenModal(ModalId.M02)) }.padding(8.dp)) {
                LIcon(Lucide.SlidersHorizontal, 17.dp, ghost)
            }
        }
    }
}
