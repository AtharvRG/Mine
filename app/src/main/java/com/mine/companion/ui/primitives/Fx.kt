package com.mine.companion.ui.primitives

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.blurEffect
import com.mine.companion.ui.theme.lerp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A framer-motion "target": the visual state an element enters from or exits to.
 * Applied entirely inside graphicsLayer, so animating it never recomposes or relayouts.
 */
@Immutable
data class Fx(
    val alpha: Float = 1f,
    val x: Dp = 0.dp,
    val y: Dp = 0.dp,
    val scale: Float = 1f,
    val blur: Dp = 0.dp,
) {
    companion object {
        val Visible = Fx()
        val Hidden = Fx(alpha = 0f)
    }
}

/** Interpolate from → to by t and write straight into the layer (no allocation). */
fun GraphicsLayerScope.fxLerp(from: Fx, to: Fx, t: Float) {
    alpha = lerp(from.alpha, to.alpha, t).coerceIn(0f, 1f)
    translationX = lerp(from.x.toPx(), to.x.toPx(), t)
    translationY = lerp(from.y.toPx(), to.y.toPx(), t)
    val s = lerp(from.scale, to.scale, t)
    scaleX = s
    scaleY = s
    renderEffect = blurEffect(lerp(from.blur.toPx(), to.blur.toPx(), t).coerceAtLeast(0f))
}

/**
 * AnimatePresence for a single child: mounts on [visible], animates from [initial] to
 * rest, and on hide animates to [exit] before leaving composition.
 * [animateOnFirst] = false mirrors `<AnimatePresence initial={false}>`.
 */
@Composable
fun Presence(
    visible: Boolean,
    modifier: Modifier = Modifier,
    initial: Fx = Fx.Hidden,
    exit: Fx = initial,
    enter: AnimationSpec<Float> = MineMotion.gentle(),
    exitSpec: AnimationSpec<Float> = enter,
    animateOnFirst: Boolean = true,
    origin: TransformOrigin = TransformOrigin.Center,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (visible && !animateOnFirst) 1f else 0f) }
    var mounted by remember { mutableStateOf(visible) }
    var exiting by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            exiting = false
            progress.animateTo(1f, enter)
        } else if (mounted) {
            exiting = true
            progress.animateTo(0f, exitSpec)
            mounted = false
            exiting = false
        }
    }

    if (mounted || visible) {
        Box(
            modifier.graphicsLayer {
                transformOrigin = origin
                if (exiting) fxLerp(exit, Fx.Visible, progress.value)
                else fxLerp(initial, Fx.Visible, progress.value)
            },
        ) { content() }
    }
}

private class SwapEntry<K>(val key: K, start: Float) {
    val progress = Animatable(start)
    var exiting by mutableStateOf(false)
}

/**
 * Keyed AnimatePresence. [wait] = true is `mode="wait"` (old leaves, then new enters);
 * false is `mode="popLayout"`/sync (both animate at once).
 */
@Composable
fun <K> FxSwap(
    target: K,
    modifier: Modifier = Modifier,
    initial: Fx = Fx.Hidden,
    exit: Fx = initial,
    enter: AnimationSpec<Float> = MineMotion.gentle(),
    exitSpec: AnimationSpec<Float> = enter,
    wait: Boolean = true,
    animateOnFirst: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    origin: TransformOrigin = TransformOrigin.Center,
    /** Per-key overrides, for swaps whose two sides animate differently. */
    initialFor: ((K) -> Fx)? = null,
    exitFor: ((K) -> Fx)? = null,
    enterFor: ((K) -> AnimationSpec<Float>)? = null,
    exitSpecFor: ((K) -> AnimationSpec<Float>)? = null,
    content: @Composable (K) -> Unit,
) {
    val entries = remember { mutableStateListOf(SwapEntry(target, if (animateOnFirst) 0f else 1f)) }

    LaunchedEffect(target) {
        val existing = entries.firstOrNull { it.key == target }
        val olds = entries.filter { it.key != target }
        olds.forEach { it.exiting = true }
        val enterSpec = enterFor?.invoke(target) ?: enter
        if (wait) {
            coroutineScope {
                olds.forEach { o -> launch { o.progress.animateTo(0f, exitSpecFor?.invoke(o.key) ?: exitSpec) } }
            }
            val e = existing ?: SwapEntry(target, 0f).also { entries.add(it) }
            entries.removeAll(olds)
            e.exiting = false
            e.progress.animateTo(1f, enterSpec)
        } else {
            val e = existing ?: SwapEntry(target, 0f).also { entries.add(it) }
            e.exiting = false
            coroutineScope {
                olds.forEach { o ->
                    launch {
                        o.progress.animateTo(0f, exitSpecFor?.invoke(o.key) ?: exitSpec)
                        entries.remove(o)
                    }
                }
                launch { e.progress.animateTo(1f, enterSpec) }
            }
        }
    }

    Box(modifier, contentAlignment = contentAlignment) {
        entries.forEach { e ->
            key(e.key) {
                val from = initialFor?.invoke(e.key) ?: initial
                val to = exitFor?.invoke(e.key) ?: exit
                Box(
                    Modifier.graphicsLayer {
                        transformOrigin = origin
                        if (e.exiting) fxLerp(to, Fx.Visible, e.progress.value)
                        else fxLerp(from, Fx.Visible, e.progress.value)
                    },
                ) { content(e.key) }
            }
        }
    }
}
