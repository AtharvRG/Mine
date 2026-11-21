package com.mine.companion.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val HairlineWidth: Dp = 0.5.dp

/** Kept so existing call sites compile. Backdrop blur is gone: it re-blurred the screen every frame. */
val LocalHaze = staticCompositionLocalOf<Any?> { null }

fun Modifier.matCard(shape: Shape): Modifier =
    background(Mine.Surface1, shape).border(HairlineWidth, Mine.Hairline, shape)

fun Modifier.matRaised(shape: Shape): Modifier =
    background(Mine.Surface2, shape).border(HairlineWidth, Mine.Hairline, shape)

@Suppress("UNUSED_PARAMETER")
fun Modifier.matGlass(shape: Shape, haze: Any? = null): Modifier =
    background(Mine.Surface2, shape).border(HairlineWidth, Mine.HairlineStrong, shape)

fun Modifier.matWell(shape: Shape): Modifier =
    background(Mine.Well, shape).border(HairlineWidth, Mine.Hairline, shape)

fun Modifier.matPaper(shape: Shape): Modifier = background(Mine.Paper, shape)

/** Formerly a glossy gradient stroke. The 0.5dp hairline in each material replaces it. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.specular(shape: Shape): Modifier = this

fun Modifier.mineSurface(shape: Shape): Modifier =
    background(Mine.Surface1, shape).border(HairlineWidth, Mine.Hairline, shape)

fun Modifier.mineSurfaceSubtle(shape: Shape): Modifier =
    background(Mine.Surface1.copy(alpha = 0.5f), shape).border(HairlineWidth, Mine.Hairline, shape)

fun Modifier.minePaperSheet(shape: Shape): Modifier = background(Mine.Paper, shape)

fun Modifier.mineRecessedTrack(shape: Shape): Modifier = matWell(shape)

fun Modifier.hairlineBottom(color: Color, width: Dp = HairlineWidth): Modifier = drawWithCache {
    val w = width.toPx()
    onDrawWithContent {
        drawContent()
        drawRect(color, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
    }
}

fun Modifier.hairlineTop(color: Color, width: Dp = HairlineWidth): Modifier = drawWithCache {
    val w = width.toPx()
    onDrawWithContent {
        drawContent()
        drawRect(color, topLeft = Offset.Zero, size = Size(size.width, w))
    }
}
