package com.mine.companion.ui.primitives

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.mine.companion.ui.theme.LocalHaze
import com.mine.companion.ui.theme.LocalReducedMotion
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Shapes
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.interp
import com.mine.companion.ui.theme.matGlass
import com.mine.companion.ui.theme.matPaper
import com.mine.companion.ui.theme.rememberHaptics
import com.mine.companion.ui.theme.specular
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Detent { Content, Medium, Large }
enum class SheetMaterial { Glass, Paper }

data class SheetSpec(val title: String, val detents: List<Detent>, val material: SheetMaterial = SheetMaterial.Glass)

private class LastKey<K> { var value: K? = null }

/**
 * primitives/Sheet.tsx: bottom sheet with detents, drag-to-dismiss and a scrim that tracks
 * the drag. Drag from the 28dp grabber, or from the body once its content is scrolled to the
 * top (nested scroll, so lists inside still scroll natively). Opens with spring.sheet from fully
 * off-screen; leaves on the same spring at stiffness 420. Flings project 0.18s ahead to pick a
 * detent. Back / scrim tap close it through [onClose].
 */
@Composable
fun <K : Any> SheetHost(
    open: K?,
    onClose: () -> Unit,
    spec: (K) -> SheetSpec,
    content: @Composable (K) -> Unit,
) {
    val last = remember { LastKey<K>() }
    if (open != null) last.value = open
    var present by remember { mutableStateOf(false) }
    val key = open ?: (if (present) last.value else null) ?: return

    val density = LocalDensity.current
    val reduce = LocalReducedMotion.current
    val haptic = rememberHaptics()
    val haze = LocalHaze.current
    val scope = rememberCoroutineScope()
    val currentClose by rememberUpdatedState(onClose)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val containerH = constraints.maxHeight.toFloat()
        val s = spec(key)
        val grabberPx = with(density) { 28.dp.toPx() }
        val topLimit = WindowInsets.statusBars.getTop(density) + with(density) { 16.dp.toPx() }
        val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

        var y by remember { mutableFloatStateOf(containerH) }
        var contentH by remember { mutableIntStateOf(0) }
        var job by remember { mutableStateOf<Job?>(null) }
        var restingContent by remember { mutableStateOf(false) }
        val effectKey = remember { LastKey<K>() }

        fun detentY(d: Detent): Float = when (d) {
            Detent.Large -> topLimit
            Detent.Medium -> (containerH * 0.46f).roundToInt().toFloat()
            Detent.Content -> max(topLimit, containerH - grabberPx - contentH - with(density) { 8.dp.toPx() })
        }

        val ys = s.detents.map(::detentY)
        val minY = ys.min()
        val closedY = containerH

        fun runTo(target: Float, velocity: Float = 0f, stiffness: Float = 380f, then: (() -> Unit)? = null) {
            job?.cancel()
            job = scope.launch {
                if (reduce) {
                    y = target
                } else {
                    animate(y, target, velocity, MineMotion.fm(stiffness, 38f, 0.9f)) { v, _ -> y = v }
                }
                then?.invoke()
            }
        }

        fun settle(velocity: Float) {
            val target = MineMotion.projectToDetent(y, velocity, ys + closedY)
            if (target == closedY) {
                haptic.tick()
                currentClose()
                return
            }
            if (target != y) haptic.tick()
            runTo(target, velocity) { restingContent = s.detents.first() == Detent.Content && target == ys.first() }
        }

        // Open / re-snap when sizes change; animate away when closed.
        LaunchedEffect(open, contentH, containerH) {
            val keyChanged = effectKey.value != open
            effectKey.value = open
            if (open == null) {
                restingContent = false
                if (present) runTo(closedY, stiffness = 420f) { present = false }
            } else {
                present = true
                val initial = s.detents.first()
                if (initial != Detent.Content || contentH > 0) {
                    if (!keyChanged && restingContent && initial == Detent.Content && job?.isActive != true) {
                        // Content is resizing (the calendar opening into a month): follow it frame by frame.
                        y = detentY(initial)
                    } else {
                        restingContent = false
                        runTo(detentY(initial)) { restingContent = initial == Detent.Content }
                    }
                }
            }
        }

        var rawDrag by remember { mutableFloatStateOf(0f) }
        fun elastic(raw: Float): Float = when {
            raw < minY -> minY + (raw - minY) * 0.08f
            raw > closedY -> closedY + (raw - closedY) * 0.9f
            else -> raw
        }
        val dragState = rememberDraggableState { d ->
            rawDrag += d
            y = elastic(rawDrag)
        }

        var bodyDragged by remember { mutableStateOf(false) }
        val latestMin = rememberUpdatedState(minY)
        val latestClosed = rememberUpdatedState(closedY)
        val latestSettle = rememberUpdatedState<(Float) -> Unit> { v -> settle(v) }
        val nested = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    val dy = available.y
                    val minNow = latestMin.value
                    if (dy < 0f && y > minNow + 0.5f) {
                        job?.cancel()
                        val next = max(minNow, y + dy)
                        val used = next - y
                        y = next
                        bodyDragged = true
                        restingContent = false
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    val dy = available.y
                    if (dy > 0f) {
                        job?.cancel()
                        y = min(latestClosed.value, y + dy)
                        bodyDragged = true
                        restingContent = false
                        return Offset(0f, dy)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!bodyDragged) return Velocity.Zero
                    bodyDragged = false
                    latestSettle.value(available.y)
                    return available
                }
            }
        }

        // Scrim follows the sheet: 0 when closed, 1 at 35% of the height.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = interp(y, containerH, containerH * 0.35f, 0f, 1f) }
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = null, indication = null) { currentClose() },
        )

        val sheetShape = Shapes.SheetTop
        val materialModifier = when (s.material) {
            SheetMaterial.Paper -> Modifier.matPaper(sheetShape)
            SheetMaterial.Glass -> Modifier.matGlass(sheetShape, haze).specular(sheetShape)
        }

        Column(
            Modifier
                .offset { IntOffset(0, y.roundToInt()) }
                .fillMaxWidth()
                .height(with(density) { containerH.toDp() })
                .then(materialModifier)
                .semantics { paneTitle = s.title },
        ) {
            // Grabber: the whole top 28dp is the drag handle, not just the 36dp bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStarted = {
                            restingContent = false
                            job?.cancel()
                            rawDrag = y
                        },
                        onDragStopped = { v -> settle(v) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 5.dp)
                        .background(
                            if (s.material == SheetMaterial.Paper) Color.Black.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.20f),
                            CircleShape,
                        ),
                )
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(nested)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { contentH = it.height }
                        .padding(bottom = maxOf(24.dp, navBottom)),
                ) {
                    content(key)
                }
            }
        }
    }
}

/** Standard sheet header: title left, optional trailing. No close X: drag, scrim and back close it. */
@Composable
fun SheetHeader(
    title: String,
    meta: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, MineType.Headline, Mine.Ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta != null) Txt(meta, MineType.Footnote, Mine.Ink3, Modifier.padding(top = 2.dp))
        }
        trailing?.invoke()
    }
}
