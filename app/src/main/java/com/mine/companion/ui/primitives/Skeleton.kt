package com.mine.companion.ui.primitives

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.Shapes
import com.mine.companion.ui.theme.hairlineBottom
import com.mine.companion.ui.theme.matCard
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * `.skeleton`: linear-gradient(100deg, .04 30%, .09 50%, .04 70%), background-size 300%,
 * shimmer 1.6s ease-in-out-soft infinite, position 150% → −50%.
 * Skeletons copy the exact geometry of what they replace so nothing jumps on load.
 */
@Composable
fun Skeleton(modifier: Modifier, shape: Shape = CircleShape, onPaper: Boolean = false) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val pos = t.animateFloat(
        initialValue = 1.5f,
        targetValue = -0.5f,
        animationSpec = InfiniteRepeatableSpec(tween(1600, easing = MineMotion.EaseInOutSoft), RepeatMode.Restart),
        label = "shimmer-pos",
    )
    val base = if (onPaper) Color.Black.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.04f)
    val peak = if (onPaper) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.09f)
    Box(
        modifier
            .clip(shape)
            .drawWithCache {
                val tileW = size.width * 3f
                val tileH = size.height
                // 100deg gradient line through a tileW × tileH box
                val rad = Math.toRadians(100.0)
                val dx = sin(rad).toFloat()
                val dy = -cos(rad).toFloat()
                val half = (abs(tileW * dx) + abs(tileH * dy)) / 2f
                onDrawBehind {
                    // background-position p%: offset = (W − 3W) · p
                    val offX = (size.width - tileW) * pos.value
                    val cx = offX + tileW / 2f
                    val cy = tileH / 2f
                    val brush = Brush.linearGradient(
                        0f to base, 0.3f to base, 0.5f to peak, 0.7f to base, 1f to base,
                        start = Offset(cx - dx * half, cy - dy * half),
                        end = Offset(cx + dx * half, cy + dy * half),
                    )
                    drawRect(brush)
                }
            },
    )
}

/** Matches components/home/TaskRow: 56dp row, 20dp check, 13dp title line, 9dp meta line. */
@Composable
fun TaskRowSkeleton(widths: Pair<Float, Float> = 0.68f to 0.28f) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .hairlineBottom(Mine.PaperHairline),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Skeleton(Modifier.size(20.dp), Shapes.R7, onPaper = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Skeleton(Modifier.fillMaxWidth(widths.first).height(13.dp), onPaper = true)
            Skeleton(Modifier.fillMaxWidth(widths.second).height(9.dp), onPaper = true)
        }
        Skeleton(Modifier.width(40.dp).height(11.dp), onPaper = true)
    }
}

/** Matches the 2×2 home tile: 112dp tall, card radius, icon well + two text lines. */
@Composable
fun TileSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier
            .height(112.dp)
            .matCard(Shapes.Card)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Skeleton(Modifier.size(36.dp), Shapes.R12)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Skeleton(Modifier.fillMaxWidth(0.6f).height(12.dp))
            Skeleton(Modifier.fillMaxWidth(0.4f).height(9.dp))
        }
    }
}

/** Assistant message placeholder: three body-height lines, last one short. */
@Composable
fun MessageSkeleton() {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Skeleton(Modifier.width(96.dp).height(11.dp))
        Skeleton(Modifier.fillMaxWidth().height(14.dp))
        Skeleton(Modifier.fillMaxWidth(0.92f).height(14.dp))
        Skeleton(Modifier.fillMaxWidth(0.54f).height(14.dp))
    }
}

/** Inline skeleton used inside the home briefing sentence (h-[15px] w-40). */
@Composable
fun InlineSkeleton(modifier: Modifier = Modifier, width: Dp = 160.dp, height: Dp = 15.dp) {
    Skeleton(modifier.width(width).height(height))
}
