package com.mine.companion.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * `.squircle` → continuous-curvature corners. CSS uses corner-shape: superellipse(1.6);
 * Compose has no native superellipse, so this builds the same G2-continuous corner
 * that Figma/iOS use (cubic lead-in, circular arc, cubic lead-out), with
 * corner smoothing 0.6. Path ops are direct (no SVG parsing), so it is cheap
 * enough to rebuild while a size animates.
 */

/** Geometry tokens from index.css. */
object MineRadius {
    val Chip = 10.dp
    val Control = 14.dp
    val Card = 20.dp
    val Sheet = 28.dp
    val Device = 46.dp
}

private const val DEG = (Math.PI / 180.0).toFloat()

private class Corner(
    val r: Float, val p: Float, val a: Float, val b: Float, val c: Float, val d: Float,
    val arcSweep: Float, val arcStart: Float,
)

private fun corner(radius: Float, smoothing: Float, budget: Float): Corner? {
    if (radius <= 0.01f) return null
    val r = min(radius, budget)
    var s = smoothing
    var p = (1f + s) * r
    if (p > budget) {
        p = budget
        s = (p / r - 1f).coerceAtLeast(0f)
    }
    val arcMeasure = 90f * (1f - s)
    val arcSection = sin(arcMeasure / 2f * DEG) * r * sqrt(2f)
    val alpha = (90f - arcMeasure) / 2f
    val p3ToP4 = r * tan(alpha / 2f * DEG)
    val beta = 45f * s
    val c = p3ToP4 * cos(beta * DEG)
    val d = c * tan(beta * DEG)
    val b = (p - arcSection - c - d) / 3f
    val a = 2f * b
    return Corner(r, p, a, b, c, d, arcMeasure, alpha)
}

fun squirclePath(
    size: Size,
    topStart: Float,
    topEnd: Float,
    bottomEnd: Float,
    bottomStart: Float,
    smoothing: Float = 0.6f,
    path: Path = Path(),
): Path {
    path.reset()
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return path
    val budget = min(w, h) / 2f
    val tl = corner(topStart, smoothing, budget)
    val tr = corner(topEnd, smoothing, budget)
    val br = corner(bottomEnd, smoothing, budget)
    val bl = corner(bottomStart, smoothing, budget)

    path.moveTo(tl?.p ?: 0f, 0f)

    // top edge → top-right
    if (tr == null) path.lineTo(w, 0f) else with(tr) {
        path.lineTo(w - p, 0f)
        path.cubicTo(w - p + a, 0f, w - p + a + b, 0f, w - p + a + b + c, d)
        path.arcTo(Rect(w - 2 * r, 0f, w, 2 * r), -90f + arcStart, arcSweep, false)
        path.cubicTo(w, p - a - b, w, p - a, w, p)
    }
    // right edge → bottom-right
    if (br == null) path.lineTo(w, h) else with(br) {
        path.lineTo(w, h - p)
        path.cubicTo(w, h - p + a, w, h - p + a + b, w - d, h - p + a + b + c)
        path.arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f + arcStart, arcSweep, false)
        path.cubicTo(w - p + a + b, h, w - p + a, h, w - p, h)
    }
    // bottom edge → bottom-left
    if (bl == null) path.lineTo(0f, h) else with(bl) {
        path.lineTo(p, h)
        path.cubicTo(p - a, h, p - a - b, h, p - a - b - c, h - d)
        path.arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f + arcStart, arcSweep, false)
        path.cubicTo(0f, h - p + a + b, 0f, h - p + a, 0f, h - p)
    }
    // left edge → top-left
    if (tl == null) path.lineTo(0f, 0f) else with(tl) {
        path.lineTo(0f, p)
        path.cubicTo(0f, p - a, 0f, p - a - b, d, p - a - b - c)
        path.arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f + arcStart, arcSweep, false)
        path.cubicTo(p - a - b, 0f, p - a, 0f, p, 0f)
    }
    path.close()
    return path
}

@Immutable
class SquircleShape(
    private val topStart: Dp,
    private val topEnd: Dp = topStart,
    private val bottomEnd: Dp = topStart,
    private val bottomStart: Dp = topStart,
    private val smoothing: Float = 0.6f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val (ts, te, be, bs) = with(density) {
            listOf(topStart.toPx(), topEnd.toPx(), bottomEnd.toPx(), bottomStart.toPx())
        }
        val ltr = layoutDirection == LayoutDirection.Ltr
        return Outline.Generic(
            squirclePath(
                size,
                topStart = if (ltr) ts else te,
                topEnd = if (ltr) te else ts,
                bottomEnd = if (ltr) be else bs,
                bottomStart = if (ltr) bs else be,
                smoothing = smoothing,
            ),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is SquircleShape && other.topStart == topStart && other.topEnd == topEnd &&
            other.bottomEnd == bottomEnd && other.bottomStart == bottomStart && other.smoothing == smoothing

    override fun hashCode(): Int =
        ((((topStart.hashCode() * 31) + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 + bottomStart.hashCode()) * 31 +
            smoothing.hashCode()
}

/** Top-only squircle (`rounded-t-*`). */
fun SquircleTop(radius: Dp): SquircleShape = SquircleShape(radius, radius, 0.dp, 0.dp)

/** Common shapes, allocated once. */
object Shapes {
    val Chip = SquircleShape(MineRadius.Chip)
    val Control = SquircleShape(MineRadius.Control)
    val Card = SquircleShape(MineRadius.Card)
    val SheetTop = SquircleTop(MineRadius.Sheet)
    // Use for clip(): a plain rounded rect is GPU-clipped, whereas a squircle path clip is
    // slower and leaves jagged edges.
    val Group = RoundedCornerShape(18.dp)
    val R7 = SquircleShape(7.dp)
    val R8 = SquircleShape(8.dp)
    val R10 = SquircleShape(10.dp)
    val R12 = SquircleShape(12.dp)
    val R14 = SquircleShape(14.dp)
    val R16 = SquircleShape(16.dp)
    val R18 = SquircleShape(18.dp)
    val R20 = SquircleShape(20.dp)
}
