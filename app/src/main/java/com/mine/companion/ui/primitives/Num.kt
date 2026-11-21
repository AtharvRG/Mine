package com.mine.companion.ui.primitives

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import com.mine.companion.ui.theme.LocalReducedMotion
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.num
import kotlin.math.roundToInt

/**
 * Counter that rolls between values without changing width (primitives/Num.tsx):
 * the old digit slides down 0.6em and fades while the new one drops in from −0.6em,
 * both on spring.snappy. Tabular digits keep the width stable.
 */
@Composable
fun Num(
    value: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    suffix: String? = null,
) {
    val reduce = LocalReducedMotion.current
    val shift = with(LocalDensity.current) { (style.fontSize.toPx() * 0.6f).roundToInt() }
    val numStyle = style.num()
    Row(modifier.clipToBounds(), verticalAlignment = Alignment.Bottom) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                val slide = MineMotion.snappy(IntOffset.VisibilityThreshold)
                val fade = MineMotion.snappy<Float>()
                val transform = if (reduce) {
                    fadeIn(fade) togetherWith fadeOut(fade)
                } else {
                    (slideInVertically(slide) { -shift } + fadeIn(fade)) togetherWith
                        (slideOutVertically(slide) { shift } + fadeOut(fade))
                }
                // popLayout: width changes land instantly, the digits roll inside the clip
                transform using SizeTransform(clip = false) { _, _ -> snap() }
            },
            label = "num",
        ) { v -> Txt(v.toString(), numStyle, color) }
        if (suffix != null) Txt(suffix, numStyle, color)
    }
}

/** Smoothly interpolated number for percentages and progress, still tabular (NumTween). */
@Composable
fun NumTween(
    value: Float,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    suffix: String = "",
) {
    val v by animateFloatAsState(
        targetValue = value,
        animationSpec = MineMotion.fm(140f, 24f),
        label = "num-tween",
    )
    Txt("%.${decimals}f".format(v) + suffix, style.num(), color, modifier)
}
