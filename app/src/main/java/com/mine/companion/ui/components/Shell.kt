package com.mine.companion.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mine.companion.store.MineAction
import com.mine.companion.store.ModalId
import com.mine.companion.store.ScreenId
import com.mine.companion.store.StatusChipState
import com.mine.companion.store.mineDispatch
import com.mine.companion.store.mineSelect
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.primitives.Fx
import com.mine.companion.ui.primitives.FxSwap
import com.mine.companion.ui.primitives.IconButton
import com.mine.companion.ui.primitives.IconTone
import com.mine.companion.ui.primitives.LocalContentColor
import com.mine.companion.ui.primitives.Presence
import com.mine.companion.ui.primitives.Pressable
import com.mine.companion.ui.primitives.tap
import com.mine.companion.ui.theme.HapticKind
import com.mine.companion.ui.theme.LocalHaze
import com.mine.companion.ui.theme.LocalReducedMotion
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Shapes
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.lh
import com.mine.companion.ui.theme.matGlass
import com.mine.companion.ui.theme.num
import com.mine.companion.ui.theme.rememberHaptics
import com.mine.companion.ui.theme.specular
import com.mine.companion.ui.theme.w
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/* ============================ StatusChip (B01–B05) ============================ */

private enum class Tone { Urgent, Attention, Quiet }

private data class ChipCopy(val label: String, val tone: Tone)

private fun chipCopy(s: StatusChipState): ChipCopy? = when (s) {
    StatusChipState.normal -> null
    StatusChipState.offline -> ChipCopy("Offline", Tone.Quiet)
    StatusChipState.rate_limited -> ChipCopy("Model cooling down", Tone.Attention)
    StatusChipState.reminders_at_risk -> ChipCopy("Reminders at risk", Tone.Urgent)
    StatusChipState.backup_model -> ChipCopy("On backup model", Tone.Quiet)
    StatusChipState.no_ai -> ChipCopy("AI unavailable", Tone.Attention)
}

/**
 * Engine conditions surfaced only when the user must know something; "normal" renders nothing.
 * Sits just under the system status bar, in the dead zone the prototype's status row used.
 */
@Composable
fun StatusChip(onPress: () -> Unit) {
    val status = mineSelect { it.statusChip }
    val cooldown = mineSelect { it.providerCooldownUntil }
    val queued = mineSelect { it.queuedChatCount }
    val haze = LocalHaze.current
    val copy = chipCopy(status)
    val last = remember { arrayOfNulls<ChipCopy>(1) }
    if (copy != null) last[0] = copy
    val shown = copy ?: last[0]

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val until = if (status == StatusChipState.rate_limited) cooldown else null
    LaunchedEffect(until) {
        if (until == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val countdown = until?.let {
        val s = maxOf(0L, ((it - now) / 1000.0).roundToInt().toLong())
        "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    Presence(
        visible = copy != null,
        initial = Fx(alpha = 0f, scale = 0.9f, y = (-4).dp),
        enter = MineMotion.snappy(),
    ) {
        if (shown != null) {
            Pressable(
                onClick = onPress,
                modifier = Modifier
                    .height(28.dp)
                    .matGlass(CircleShape, haze)
                    .padding(start = 10.dp, end = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(6.dp).background(
                            when (shown.tone) {
                                Tone.Urgent -> Mine.Urgent
                                Tone.Attention -> Mine.Attention
                                Tone.Quiet -> Mine.Ink3
                            },
                            CircleShape,
                        ),
                    )
                    Txt(shown.label, MineType.Caption, Mine.Ink1)
                    if (countdown != null) Txt(countdown, MineType.Caption.num(), Mine.Ink3)
                    if (status == StatusChipState.offline && queued > 0) Txt("· $queued waiting", MineType.Caption.num(), Mine.Ink3)
                }
            }
        }
    }
}

/* ================================== Toast ================================== */

/**
 * Sits ABOVE the thumb dock, swipes away downward, and carries Undo when the store attached one.
 * Enter: gentle spring from y16/scale .96; exit: .14s to y10/scale .97 (popLayout per message).
 */
@Composable
fun Toast(modifier: Modifier = Modifier) {
    val message = mineSelect { it.toastMessage }
    val hasUndo = mineSelect { it.toastUndo != null }
    val dispatch = mineDispatch()
    val density = LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 84.dp + navBottom),
        contentAlignment = Alignment.BottomCenter,
    ) {
        FxSwap(
            target = message,
            initial = Fx(alpha = 0f, y = 16.dp, scale = 0.96f),
            exit = Fx(alpha = 0f, y = 10.dp, scale = 0.97f),
            enter = MineMotion.gentle(),
            exitSpec = tween(140),
            wait = false,
            contentAlignment = Alignment.BottomCenter,
        ) { msg ->
            if (msg != null) ToastBody(msg, hasUndo, onDismiss = { dispatch(MineAction.DismissToast) }, onUndo = { dispatch(MineAction.RunToastUndo) })
        }
    }
}

@Composable
private fun ToastBody(message: String, hasUndo: Boolean, onDismiss: () -> Unit, onUndo: () -> Unit) {
    val haze = LocalHaze.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember { mutableFloatStateOf(0f) }
    val raw = remember { floatArrayOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    val state = rememberDraggableState { d ->
        raw[0] += d
        // constraints {top:0,bottom:0}, elastic {top:.1, bottom:.8}
        offset.floatValue = if (raw[0] < 0f) raw[0] * 0.1f else raw[0] * 0.8f
    }
    Row(
        Modifier
            .offset { IntOffset(0, offset.floatValue.roundToInt()) }
            .widthIn(max = 600.dp)
            .height(44.dp)
            .matGlass(Shapes.R18, haze)
            .specular(Shapes.R18)
            .semantics { contentDescription = message }
            .draggable(
                state = state,
                orientation = Orientation.Vertical,
                onDragStarted = { job?.cancel(); raw[0] = 0f },
                onDragStopped = { v ->
                    val dismiss = raw[0] > with(density) { 24.dp.toPx() } || v > with(density) { 300.dp.toPx() }
                    if (dismiss) onDismiss()
                    job = scope.launch { animate(offset.floatValue, 0f, v, MineMotion.gentle()) { x, _ -> offset.floatValue = x } }
                },
            )
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Txt(message, MineType.Callout, Mine.Ink1, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hasUndo) {
            Pressable(
                onClick = onUndo,
                haptics = HapticKind.Tick,
                modifier = Modifier
                    .height(32.dp)
                    .background(Color.White.copy(alpha = 0.08f), Shapes.R12)
                    .padding(horizontal = 12.dp),
            ) { Txt("Undo", MineType.Callout.w(600), Mine.Ink1) }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

/* ============================ Walk companion banner ============================ */

/** Context banner when leaving home with an errand. Drag it up past 20dp to dismiss. */
@Composable
fun WalkBanner(modifier: Modifier = Modifier) {
    val errands = mineSelect { s ->
        s.items.filter { it.triggerType == com.mine.companion.store.TriggerType.leave_home && it.status != com.mine.companion.store.ItemStatus.done }
    }
    val visible = mineSelect { it.walkNotificationVisible } && errands.isNotEmpty()
    val dispatch = mineDispatch()
    val haze = LocalHaze.current
    val density = LocalDensity.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val offset = remember { mutableFloatStateOf(0f) }
    val raw = remember { floatArrayOf(0f) }
    val scope = rememberCoroutineScope()
    val drag = rememberDraggableState { d ->
        raw[0] += d
        offset.floatValue = if (raw[0] < 0f) raw[0] * 0.8f else raw[0] * 0.1f
    }

    Presence(
        visible = visible,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusTop + 4.dp, start = 12.dp, end = 12.dp),
        initial = Fx(alpha = 0f, y = (-16).dp, scale = 0.98f),
        exit = Fx(alpha = 0f, y = (-12).dp, scale = 0.98f),
        enter = MineMotion.gentle(),
    ) {
        Row(
            Modifier
                .offset { IntOffset(0, offset.floatValue.roundToInt()) }
                .fillMaxWidth()
                .matGlass(Shapes.R20, haze)
                .specular(Shapes.R20)
                .draggable(
                    state = drag,
                    orientation = Orientation.Vertical,
                    onDragStarted = { raw[0] = 0f },
                    onDragStopped = { v ->
                        if (raw[0] * 0.8f < -with(density) { 20.dp.toPx() }) dispatch(MineAction.TriggerWalkNotification(false))
                        scope.launch { animate(offset.floatValue, 0f, v, MineMotion.gentle()) { x, _ -> offset.floatValue = x } }
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val first = errands.firstOrNull()
            Txt(
                buildAnnotatedString {
                    append("Leaving home? Don't forget ")
                    withStyle(MineType.Callout.w(500).copy(color = Mine.Ink1).toSpanStyle()) { append(first?.title ?: "") }
                    if (errands.size > 1) append(" and ${errands.size - 1} more")
                    val by = first?.fallbackTime
                    if (by != null) {
                        append(" before ")
                        withStyle(MineType.Callout.num().toSpanStyle()) { append(by) }
                    }
                    append(".")
                },
                MineType.Callout.lh(1.375f), // leading-snug
                Mine.Ink2,
                Modifier.weight(1f),
            )
            Txt(
                "Got it", MineType.Callout.w(600), Mine.Ink1,
                Modifier.tap { dispatch(MineAction.TriggerWalkNotification(false)) },
            )
        }
    }
}

/* ================================ Thumb dock ================================ */

/**
 * Bottom dock: [ Places ] ( Talk to Mine pill ) [ + Capture ], everything a thumb needs in the
 * bottom 88dp. Long-press Places (450ms) opens Preferences. The centre slot is left to the pill.
 */
@Composable
fun ThumbDock(hidden: Boolean = false, modifier: Modifier = Modifier) {
    val inbox = mineSelect { it.inboxItems.size }
    val dispatch = mineDispatch()
    val density = LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val y by animateFloatAsState(if (hidden) 96f else 0f, MineMotion.sheet(), label = "dock-y")
    val a by animateFloatAsState(if (hidden) 0f else 1f, MineMotion.sheet(), label = "dock-a")

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = y * density.density
                alpha = a
            },
    ) {
        PlacesButton(
            inbox = inbox,
            onTap = { dispatch(MineAction.OpenModal(ModalId.M03)) },
            onLongPress = { dispatch(MineAction.OpenModal(ModalId.M02)) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 13.dp + navBottom),
        )
        IconButton(
            onClick = { dispatch(MineAction.OpenModal(ModalId.M09)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 13.dp + navBottom),
            size = 44.dp,
            tone = IconTone.Glass,
            haptics = HapticKind.Tick,
            label = "Quick capture",
        ) {
            LIcon(Lucide.Plus, 19.dp, LocalContentColor.current, strokeWidth = 1.75f)
        }
    }
}

@Composable
private fun PlacesButton(inbox: Int, onTap: () -> Unit, onLongPress: () -> Unit, modifier: Modifier) {
    val haze = LocalHaze.current
    val haptics = rememberHaptics()
    val reduce = LocalReducedMotion.current
    val scale = remember { Animatable(1f) }
    Box(modifier) {
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .size(44.dp)
                .matGlass(CircleShape, haze)
                .specular(CircleShape)
                .semantics { contentDescription = if (inbox > 0) "Places, $inbox waiting in inbox" else "Places" }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            haptics.tick()
                            coroutineScope {
                                if (!reduce) launch { scale.animateTo(MineMotion.Press.Icon.scale, MineMotion.snappy()) }
                                var fired = false
                                val timer = launch {
                                    delay(450)
                                    fired = true
                                    onLongPress()
                                }
                                val released = tryAwaitRelease()
                                if (!fired) {
                                    timer.cancel()
                                    if (released) onTap()
                                }
                                launch { scale.animateTo(1f, MineMotion.snappy()) }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            LIcon(Lucide.LayoutGrid, 18.dp, Mine.Ink1, strokeWidth = 1.75f)
        }
        if (inbox > 0) {
            // 8dp attention dot at top/right 6dp with a 2dp surface-2 ring drawn outside it
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(12.dp)
                    .background(Mine.Surface2, CircleShape)
                    .padding(2.dp)
                    .background(Mine.Attention, CircleShape),
            )
        }
    }
}
