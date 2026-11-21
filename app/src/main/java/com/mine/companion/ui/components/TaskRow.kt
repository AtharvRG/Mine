package com.mine.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mine.companion.store.ItemType
import com.mine.companion.store.MineItem
import com.mine.companion.store.TriggerType
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.primitives.StrikeText
import com.mine.companion.ui.primitives.tap
import com.mine.companion.ui.theme.LocalReducedMotion
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Shapes
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.hairlineBottom
import com.mine.companion.ui.theme.interp
import com.mine.companion.ui.theme.num
import com.mine.companion.ui.theme.rememberHaptics
import com.mine.companion.ui.theme.w
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SWIPE_COMMIT_DP = 96f

/** Bar colour from what the item IS, not from string-matching its id. */
private fun railColor(item: MineItem): Color = when {
    item.slipCount > 0 -> Mine.Urgent
    item.type == ItemType.errand -> Mine.Steel
    item.type == ItemType.habit -> Mine.Settled
    else -> Mine.PaperInk3
}

private val TickPath: Path = PathParser().parsePathString("M5 10.5 8.5 14 15 6.5").toPath()

/**
 * One row on the paper sheet (components/home/TaskRow.tsx).
 *  - Tap the box: the tick draws at once and the row settles 180ms later.
 *  - Swipe right past 96dp: done. Swipe left: push to tomorrow. Labels underneath fade in with the pull.
 *  - 56dp tall: the 48dp Material minimum plus breathing room.
 */
@Composable
fun TaskRow(
    item: MineItem,
    onOpen: () -> Unit,
    onComplete: () -> Unit,
    onSnooze: () -> Unit,
) {
    val reduce = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val commitPx = with(density) { SWIPE_COMMIT_DP.dp.toPx() }
    val p12 = with(density) { 12.dp.toPx() }

    var ticked by remember { mutableStateOf(false) }
    val x = remember { mutableFloatStateOf(0f) }
    val raw = remember { floatArrayOf(0f) }
    val anim = remember { arrayOfNulls<Job>(1) }
    val complete by rememberUpdatedState(onComplete)
    val snooze by rememberUpdatedState(onSnooze)

    fun commitDone() {
        if (ticked) return
        ticked = true
        haptics.confirm()
        scope.launch {
            delay(if (reduce) 0 else 180)
            complete()
        }
    }

    fun springX(to: Float, velocity: Float, sheet: Boolean) {
        anim[0]?.cancel()
        anim[0] = scope.launch {
            animate(x.floatValue, to, velocity, if (sheet) MineMotion.sheet<Float>() else MineMotion.snappy<Float>()) { v, _ -> x.floatValue = v }
        }
    }

    val dragState = rememberDraggableState { d ->
        raw[0] += d
        x.floatValue = raw[0] * 0.5f // dragElastic 0.5 against [0, 0] constraints
    }

    val prev = item.strikethroughHistory.lastOrNull()?.substringBefore(',')
    val due = item.dueLabel.replace("Today ", "")

    Box(Modifier.fillMaxWidth().height(56.dp)) {
        // Action layer revealed by the swipe
        Row(
            Modifier
                .matchParentSize()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.graphicsLayer {
                    alpha = interp(x.floatValue, p12, commitPx, 0f, 1f)
                    val s = interp(x.floatValue, 0f, commitPx, 0.6f, 1f)
                    scaleX = s
                    scaleY = s
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LIcon(Lucide.Check, 16.dp, Mine.Settled, strokeWidth = 2.5f)
                Txt("Done", MineType.Footnote.w(600), Mine.Settled)
            }
            Row(
                Modifier.graphicsLayer { alpha = interp(x.floatValue, -p12, -commitPx, 0f, 1f) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt("Tomorrow", MineType.Footnote.w(600), Mine.PaperInk2)
                LIcon(Lucide.MoonStar, 16.dp, Mine.PaperInk2, strokeWidth = 2f)
            }
        }

        // Foreground
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = x.floatValue }
                .background(Mine.Paper)
                .hairlineBottom(Mine.PaperHairline)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = !reduce,
                    onDragStarted = {
                        anim[0]?.cancel()
                        raw[0] = x.floatValue / 0.5f
                    },
                    onDragStopped = { v ->
                        val dx = x.floatValue + v * 0.12f
                        when {
                            dx > commitPx -> {
                                commitDone()
                                springX(with(density) { 420.dp.toPx() }, v, sheet = true)
                            }
                            dx < -commitPx -> {
                                haptics.tick()
                                springX(0f, v, sheet = false)
                                snooze()
                            }
                            else -> springX(0f, v, sheet = false)
                        }
                    },
                )
                .tap(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TickBox(ticked = ticked, title = item.title, onTick = ::commitDone)

            Box(Modifier.width(3.dp).height(20.dp).background(railColor(item), CircleShape))

            Column(Modifier.weight(1f)) {
                val titleColor by animateColorAsState(
                    if (ticked) Mine.PaperInk3 else Mine.PaperInk1,
                    tween(150, easing = MineMotion.TwEase),
                    label = "title",
                )
                Txt(
                    item.title, MineType.Callout.w(500), Mine.PaperInk1,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    colorProducer = { titleColor },
                )
                when {
                    item.slipCount > 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (prev != null) {
                            StrikeText(
                                prev, MineType.Caption.num(), Mine.PaperInk2,
                                Modifier.padding(end = 6.dp), strikeColor = Mine.PaperInk3, maxLines = 1,
                            )
                        }
                        Txt(
                            buildAnnotatedString {
                                append("Slipped ")
                                withStyle(MineType.Caption.num().toSpanStyle()) { append(item.slipCount.toString()) }
                                append("×")
                            },
                            MineType.Caption.w(500), Mine.Urgent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item.type == ItemType.habit && (item.streakDays ?: 0) > 0 -> Txt(
                        buildAnnotatedString {
                            withStyle(MineType.Caption.num().toSpanStyle()) { append("${item.streakDays}") }
                            append("-day streak · best ")
                            withStyle(MineType.Caption.num().toSpanStyle()) { append("${item.bestStreakDays}") }
                        },
                        MineType.Caption, Mine.PaperInk2,
                    )
                    item.triggerType == TriggerType.leave_home -> Txt(
                        buildAnnotatedString {
                            append("When you leave home · or ")
                            withStyle(MineType.Caption.num().toSpanStyle()) { append(item.fallbackTime ?: "") }
                        },
                        MineType.Caption, Mine.PaperInk2,
                    )
                }
            }

            Txt(due, MineType.Footnote.num(), Mine.PaperInk2)
        }
    }
}

/** 20dp squircle checkbox: colours on spring.snappy, a 1 → .86 → 1 squash, and a drawn tick (.22s ease-out-quint). */
@Composable
private fun TickBox(ticked: Boolean, title: String, onTick: () -> Unit) {
    val fill by animateColorAsState(if (ticked) Mine.PaperInk1 else Color.Transparent, MineMotion.snappy(), label = "fill")
    val border by animateColorAsState(if (ticked) Mine.PaperInk1 else Mine.PaperInk3, MineMotion.snappy(), label = "border")
    val squash = remember { Animatable(1f) }
    val draw = remember { Animatable(if (ticked) 1f else 0f) }
    LaunchedEffect(ticked) {
        if (ticked) {
            launch {
                squash.animateTo(1f, keyframes {
                    durationMillis = 300
                    1f at 0
                    0.86f at 120 using MineMotion.FmEaseInOut
                    1f at 300 using MineMotion.FmEaseInOut
                })
            }
            draw.animateTo(1f, tween(220, easing = MineMotion.EaseOutQuint))
        } else {
            draw.animateTo(0f, tween(220, easing = MineMotion.EaseOutQuint))
        }
    }
    val measure = remember { PathMeasure().apply { setPath(TickPath, false) } }
    val segment = remember { Path() }

    Box(
        Modifier
            .size(20.dp)
            .semantics {
                role = Role.Checkbox
                stateDescription = if (ticked) "Checked" else "Not checked"
                contentDescription = "Mark \"$title\" done"
            }
            .clickable(interactionSource = null, indication = null, onClick = onTick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = squash.value
                    scaleY = squash.value
                }
                .background(fill, Shapes.R7)
                .border(1.5.dp, border, Shapes.R7),
        )
        Canvas(Modifier.size(14.dp)) {
            val t = draw.value
            if (t <= 0f) return@Canvas
            segment.reset()
            measure.getSegment(0f, measure.length * t, segment, true)
            scale(size.width / 20f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                drawPath(segment, Mine.Paper, style = Stroke(2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** A finished row: tap anywhere to undo. */
@Composable
fun DoneRow(item: MineItem, onUndo: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .hairlineBottom(Mine.PaperHairline)
            .semantics { contentDescription = "Undo \"${item.title}\"" }
            .tap(onClick = onUndo),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).background(Mine.PaperInk1, Shapes.R7), contentAlignment = Alignment.Center) {
            LIcon(Lucide.Check, 12.dp, Mine.Paper, strokeWidth = 3f)
        }
        StrikeText(
            item.title, MineType.Callout, Mine.PaperInk3,
            Modifier.weight(1f), strikeColor = Mine.PaperInk3.copy(alpha = 0.38f * 0.6f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Txt("Undo", MineType.Caption.w(500), Mine.PaperInk2)
    }
}
