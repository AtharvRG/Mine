package com.mine.companion.ui.primitives

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.lerp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * How a removed item leaves:
 *  - [PopLayout]: AnimatePresence mode="popLayout" — it is pulled out of flow at once and
 *    animates out in place while siblings glide (layout animation) into the gap.
 *  - [Collapse]: rowVariants — its height springs to 0 while it fades, pulling siblings up.
 */
enum class ExitMode { PopLayout, Collapse }

private class ColEntry<T>(val key: Any, var item: T, start: Float) {
    val progress = Animatable(start)
    val alpha = Animatable(start)
    var exiting = false
    var lastTop = Int.MIN_VALUE
    val shift = Animatable(0f)
}

private class ColHolder<T> {
    var entries: List<ColEntry<T>> = emptyList()
    var initialized = false
}

/**
 * A Column with AnimatePresence + `layout` semantics for short lists (≤ a few dozen rows).
 * Enter/exit run on per-item Animatables applied in graphicsLayer; position changes of the
 * survivors are FLIP-animated with [layoutSpec] at placement time (no recomposition).
 */
@Composable
fun <T> AnimatedColumn(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    enter: Fx = Fx(alpha = 0f, y = 6.dp),
    exit: Fx = Fx.Hidden,
    enterSpec: AnimationSpec<Float> = MineMotion.gentle(),
    exitSpec: AnimationSpec<Float> = MineMotion.snappy(),
    exitAlphaSpec: AnimationSpec<Float> = exitSpec,
    mode: ExitMode = ExitMode.PopLayout,
    layoutSpec: FiniteAnimationSpec<Float> = MineMotion.gentle(),
    animateInitial: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val holder = remember { ColHolder<T>() }
    var exitTick by remember { mutableIntStateOf(0) }
    // Reading the tick subscribes this composition, so reconciliation re-runs when an exit finishes.
    if (exitTick < 0) return
    val scope = rememberCoroutineScope()

    // ---- reconcile: survivors keep their entry, newcomers get one, leavers stay until done
    val keys = items.map(key)
    val keySet = keys.toHashSet()
    val old = holder.entries
    val byKey = old.associateBy { it.key }
    val fresh = ArrayList<ColEntry<T>>(items.size + 2)
    items.forEachIndexed { i, item ->
        val e = byKey[keys[i]]
        if (e != null) {
            e.item = item
            e.exiting = false
            fresh += e
        } else {
            fresh += ColEntry(keys[i], item, if (holder.initialized || animateInitial) 0f else 1f)
        }
    }
    old.forEachIndexed { oi, e ->
        if (e.key in keySet) return@forEachIndexed
        e.exiting = true
        var anchor = -1
        for (j in oi - 1 downTo 0) {
            val pos = fresh.indexOf(old[j])
            if (pos >= 0) { anchor = pos; break }
        }
        fresh.add(anchor + 1, e)
    }
    holder.entries = fresh
    holder.initialized = true
    val entries = fresh

    Layout(
        modifier = modifier,
        content = {
            entries.forEach { e ->
                key(e.key) {
                    val leaving = e.exiting
                    LaunchedEffect(leaving) {
                        if (leaving) {
                            launch { e.alpha.animateTo(0f, exitAlphaSpec) }
                            e.progress.animateTo(0f, exitSpec)
                            holder.entries = holder.entries.filter { it !== e }
                            exitTick++
                        } else {
                            launch { e.alpha.animateTo(1f, enterSpec) }
                            e.progress.animateTo(1f, enterSpec)
                        }
                    }
                    Box(
                        Modifier
                            .then(
                                if (mode == ExitMode.Collapse) Modifier
                                    .clipToBounds()
                                    .layout { m, c ->
                                        val p = m.measure(c)
                                        val h = if (e.exiting) (p.height * e.progress.value.coerceIn(0f, 1f)).roundToInt() else p.height
                                        layout(p.width, h) { p.place(0, 0) }
                                    }
                                else Modifier,
                            )
                            .graphicsLayer {
                                if (e.exiting) {
                                    if (mode == ExitMode.Collapse) fxLerp(Fx.Visible, Fx.Visible, 1f)
                                    else fxLerp(exit, Fx.Visible, e.progress.value)
                                    alpha = lerp(exit.alpha, 1f, e.alpha.value).coerceIn(0f, 1f)
                                } else {
                                    fxLerp(enter, Fx.Visible, e.progress.value)
                                    alpha = lerp(enter.alpha, 1f, e.alpha.value).coerceIn(0f, 1f)
                                }
                            },
                    ) { content(e.item) }
                }
            }
        },
    ) { measurables, constraints ->
        val childConstraints = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(childConstraints) }
        val gap = spacing.roundToPx()
        val tops = IntArray(placeables.size)
        val collapsing = mode == ExitMode.Collapse && entries.any { it.exiting }
        var y = 0
        var first = true
        var width = constraints.minWidth
        placeables.forEachIndexed { i, p ->
            val e = entries[i]
            width = maxOf(width, p.width)
            if (e.exiting && mode == ExitMode.PopLayout) {
                tops[i] = if (e.lastTop == Int.MIN_VALUE) y else e.lastTop
            } else {
                val f = if (e.exiting) e.progress.value.coerceIn(0f, 1f) else 1f
                if (!first) y += (gap * f).roundToInt()
                tops[i] = y
                y += p.height
                first = false
            }
        }
        layout(width.coerceAtMost(constraints.maxWidth), y) {
            placeables.forEachIndexed { i, p ->
                val e = entries[i]
                val top = tops[i]
                if (!e.exiting) {
                    if (e.lastTop != Int.MIN_VALUE && e.lastTop != top && !collapsing) {
                        val delta = (e.lastTop + e.shift.value) - top
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            e.shift.snapTo(delta)
                            e.shift.animateTo(0f, layoutSpec)
                        }
                    }
                    e.lastTop = top
                }
                p.placeRelativeWithLayer(0, top) { translationY = e.shift.value }
            }
        }
    }
}
