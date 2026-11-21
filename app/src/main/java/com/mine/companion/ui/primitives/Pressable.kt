package com.mine.companion.ui.primitives

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mine.companion.ui.theme.HapticKind
import com.mine.companion.ui.theme.LocalHaze
import com.mine.companion.ui.theme.LocalReducedMotion
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.matGlass
import com.mine.companion.ui.theme.rememberHaptics
import com.mine.companion.ui.theme.specular
import kotlinx.coroutines.launch

/** Content colour for children, like CSS currentColor. */
val LocalContentColor = androidx.compose.runtime.compositionLocalOf { Mine.Ink1 }

/**
 * The one button primitive (primitives/Pressable.tsx).
 *  - Sinks on press with spring.snappy, comes back with the same spring.
 *  - Optional haptic on pointer-down.
 *  - Disabled = 40% opacity, no input.
 * The press scale lives in graphicsLayer and is driven from interactions,
 * so pressing never recomposes the button.
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    feel: MineMotion.Press = MineMotion.Press.Control,
    haptics: HapticKind? = null,
    enabled: Boolean = true,
    label: String? = null,
    disabledAlpha: Float = 0.4f,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val reduce = LocalReducedMotion.current
    val scale = remember { Animatable(1f) }
    val haptic = rememberHaptics()
    val currentHaptics by rememberUpdatedState(haptics)

    LaunchedEffect(interactionSource, reduce) {
        interactionSource.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> {
                    currentHaptics?.let { haptic.play(it) }
                    if (!reduce) launch { scale.animateTo(feel.scale, MineMotion.snappy()) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel ->
                    launch { scale.animateTo(1f, MineMotion.snappy()) }
            }
        }
    }

    Box(
        // The layer wraps the caller's modifier so backgrounds, shadows and glass sink with the content.
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = if (enabled) 1f else disabledAlpha
            }
            .then(modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        contentAlignment = contentAlignment,
        content = content,
    )
}

enum class IconTone { Ghost, Glass, Solid }

/** Round icon button (IconButton in Pressable.tsx). */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tone: IconTone = IconTone.Ghost,
    haptics: HapticKind? = null,
    label: String? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.() -> Unit,
) {
    val haze = LocalHaze.current
    val toneModifier = when (tone) {
        IconTone.Glass -> Modifier.matGlass(CircleShape, haze).specular(CircleShape)
        IconTone.Solid -> Modifier.background(Mine.Ink1, CircleShape)
        IconTone.Ghost -> Modifier
    }
    val contentColor = when (tone) {
        IconTone.Glass -> Mine.Ink1
        IconTone.Solid -> Mine.Surface0
        IconTone.Ghost -> Mine.Ink2
    }
    Pressable(
        onClick = onClick,
        modifier = modifier.size(size).then(toneModifier),
        feel = MineMotion.Press.Icon,
        haptics = haptics,
        label = label,
        interactionSource = interactionSource,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/** Plain HTML `<button>`: tappable, no visual press state (the legacy screens never had one). */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(
        interactionSource = null,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClick = onClick,
    ),
)
