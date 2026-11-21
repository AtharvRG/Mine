package com.mine.companion.ui.icons

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mine.companion.ui.theme.Hex
import com.mine.companion.ui.theme.MineMotion

/*
 * components/icons/MineIcons.tsx — the hand-drawn 24×24 glyphs. Paths are parsed once,
 * then drawn in a 24-unit scaled canvas so stroke widths match the SVG exactly
 * (including fillOpacity and the dashed beacon ring, which ImageVector can't express).
 */
private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

private object Paths {
    val flameOuter = p("M12 2.5C12 2.5 17.5 7.2 17.5 13C17.5 16.59 15.04 19.5 12 19.5C8.96 19.5 6.5 16.59 6.5 13C6.5 10.6 7.8 8.3 9.2 6.8C9.2 9.2 10.8 10.2 10.8 10.2C10.2 7.2 12 2.5 12 2.5Z")
    val flameCore = p("M12 19.5C13.38 19.5 14.5 18.1 14.5 16.4C14.5 14.6 12 12.2 12 12.2C12 12.2 9.5 14.6 9.5 16.4C9.5 18.1 10.62 19.5 12 19.5Z")
    val vaultBody = p("M7.5 10.5H16.5A3 3 0 0 1 19.5 13.5V17.5A3 3 0 0 1 16.5 20.5H7.5A3 3 0 0 1 4.5 17.5V13.5A3 3 0 0 1 7.5 10.5Z")
    val vaultShackle = p("M8 10.5V7.5C8 5.29086 9.79086 3.5 12 3.5C14.2091 3.5 16 5.29086 16 7.5V10.5")
    val pin = p("M14.5 4.5L19.5 9.5L18 11L14.2 9.8L10.5 13.5L11.5 17.5L10 19L6.5 15.5L3 19L4.5 17.5L8.5 13.5L4.5 9.5L6 8L10 9L13.7 5.3L13 3.8L14.5 4.5Z")
    val departureArrow = p("M8.5 16.5L18.5 6.5M18.5 6.5H12.5M18.5 6.5V12.5")
    val alarmHands = p("M12 9.5V13L14.5 14.5")
    val alarmBells = p("M5 4.5L2.5 7M19 4.5L21.5 7")
    val bell = p("M18 9.5C18 6.18629 15.3137 3.5 12 3.5C8.68629 3.5 6 6.18629 6 9.5C6 15.5 3.5 17 3.5 17H20.5C20.5 17 18 15.5 18 9.5Z")
    val bellClapper = p("M10 20C10.5 20.8 11.2 21.2 12 21.2C12.8 21.2 13.5 20.8 14 20")
    val globeMeridian = p("M12 3.5A4 8.5 0 0 1 12 20.5A4 8.5 0 0 1 12 3.5Z")
    val globeLines = p("M4 9.5H20M4 14.5H20")
    val wave = p("M4 10V14M8 7V17M12 4V20M16 7V17M20 10V14")
    val vesselCup = p("M4.5 8.5H15.5V14.5C15.5 17.5376 13.0376 20 10 20C6.96243 20 4.5 17.5376 4.5 14.5V8.5Z")
    val vesselHandle = p("M15.5 10H17.5C19.1569 10 20.5 11.3431 20.5 13C20.5 14.6569 19.1569 16 17.5 16H15.5")
    val vesselSteam = p("M7.5 4.5V6M12.5 4.5V6")
    val noteBody = p("M7 3.5H17A2.5 2.5 0 0 1 19.5 6V18A2.5 2.5 0 0 1 17 20.5H7A2.5 2.5 0 0 1 4.5 18V6A2.5 2.5 0 0 1 7 3.5Z")
    val noteLines = p("M8.5 8.5H15.5M8.5 12.5H15.5M8.5 16.5H12.5")
    val checkMark = p("M8.5 12.2L10.8 14.5L15.5 9.5")
}

private fun stroke(w: Float) = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
private fun strokeButt(w: Float) = Stroke(width = w, cap = StrokeCap.Butt, join = StrokeJoin.Miter)

@Composable
private fun Glyph(size: Dp, modifier: Modifier, draw: DrawScope.() -> Unit) {
    Canvas(modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) { draw() }
    }
}

/** Geometric flame / streak pulse. */
@Composable
fun IconStreakFlame(size: Dp = 14.dp, tint: Color = Hex.Amber, modifier: Modifier = Modifier) = Glyph(size, modifier) {
    drawPath(Paths.flameOuter, tint, style = stroke(1.6f))
    drawPath(Paths.flameCore, tint.copy(alpha = tint.alpha * 0.28f), style = Fill)
}

/** Machined keyhole vault (Local-Only). */
@Composable
fun IconLocalVault(size: Dp = 14.dp, tint: Color = Hex.Gold, modifier: Modifier = Modifier) = Glyph(size, modifier) {
    drawPath(Paths.vaultBody, tint, style = strokeButt(1.6f))
    drawPath(Paths.vaultShackle, tint, style = Stroke(1.6f, cap = StrokeCap.Round))
    drawCircle(tint, radius = 1.25f, center = Offset(12f, 15.5f))
}

/** Precision architectural pin (Living Notes max-3 pin). */
@Composable
fun IconPinNeedle(size: Dp = 14.dp, tint: Color = Hex.Gold, filled: Boolean = false, modifier: Modifier = Modifier) =
    Glyph(size, modifier) {
        if (filled) drawPath(Paths.pin, tint.copy(alpha = tint.alpha * 0.25f), style = Fill)
        drawPath(Paths.pin, tint, style = Stroke(1.5f, join = StrokeJoin.Round))
    }

/** Opportunistic departure vector ("When I leave home"). */
@Composable
fun IconDepartureVector(size: Dp = 14.dp, tint: Color = Hex.Moss, modifier: Modifier = Modifier) = Glyph(size, modifier) {
    drawCircle(tint, radius = 2.5f, center = Offset(6f, 18f), style = strokeButt(1.6f))
    drawPath(Paths.departureArrow, tint, style = stroke(1.6f))
}

/** ALARM-tier precision clock. */
@Composable
fun IconAlarmClock(size: Dp = 14.dp, tint: Color, modifier: Modifier = Modifier) = Glyph(size, modifier) {
    drawCircle(tint, radius = 7.5f, center = Offset(12f, 13f), style = strokeButt(1.6f))
    drawPath(Paths.alarmHands, tint, style = stroke(1.6f))
    drawPath(Paths.alarmBells, tint, style = Stroke(1.6f, cap = StrokeCap.Round))
}

/** Gentle-tier bell. */
@Composable
fun IconGentleBell(size: Dp = 14.dp, tint: Color, modifier: Modifier = Modifier) = Glyph(size, modifier) {
    drawPath(Paths.bell, tint, style = stroke(1.6f))
    drawPath(Paths.bellClapper, tint, style = Stroke(1.6f, cap = StrokeCap.Round))
}

/** Orbital web-search globe (B08 citations). */
@Composable
fun IconOrbitalGlobe(size: Dp = 15.dp, tint: Color, modifier: Modifier = Modifier) = Glyph(size, modifier) {
    drawCircle(tint, radius = 8.5f, center = Offset(12f, 12f), style = strokeButt(1.5f))
    drawPath(Paths.globeMeridian, tint, style = strokeButt(1.5f))
    drawPath(Paths.globeLines, tint, style = Stroke(1.4f, cap = StrokeCap.Round))
}

/** Acoustic waveform bars; `active` adds Tailwind's animate-pulse (2s, opacity 1 → .5 → 1). */
@Composable
fun IconAcousticWave(size: Dp = 15.dp, tint: Color, active: Boolean = false, modifier: Modifier = Modifier) {
    val alpha = if (active) {
        val t = rememberInfiniteTransition(label = "wave-pulse")
        t.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = InfiniteRepeatableSpec(
                keyframes {
                    durationMillis = 2000
                    1f at 0 using MineMotion.Pulse
                    0.5f at 1000 using MineMotion.Pulse
                    1f at 2000
                },
                RepeatMode.Restart,
            ),
            label = "wave-alpha",
        )
    } else null
    Glyph(size, modifier) {
        drawPath(Paths.wave, tint.copy(alpha = tint.alpha * (alpha?.value ?: 1f)), style = Stroke(1.8f, cap = StrokeCap.Round))
    }
}

private val beaconDash = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f)

/** Spatial beacon (B07 context chip): solid core + dashed 3/3 ring. */
@Composable
fun IconSpatialBeacon(size: Dp = 14.dp, tint: Color, modifier: Modifier = Modifier) = Glyph(size, modifier) {
    drawCircle(tint, radius = 3f, center = Offset(12f, 12f))
    drawCircle(tint, radius = 7.5f, center = Offset(12f, 12f), style = Stroke(1.5f, pathEffect = beaconDash))
}

/** Espresso vessel (B06 break pill). */
@Composable
fun IconBreakVessel(size: Dp = 14.dp, tint: Color = Hex.Gold, modifier: Modifier = Modifier) = Glyph(size, modifier) {
    drawPath(Paths.vesselCup, tint, style = strokeButt(1.6f))
    drawPath(Paths.vesselHandle, tint, style = strokeButt(1.6f))
    drawPath(Paths.vesselSteam, tint, style = Stroke(1.5f, cap = StrokeCap.Round))
}

/** Living-note matrix. */
@Composable
fun IconLivingNote(size: Dp = 14.dp, tint: Color, modifier: Modifier = Modifier) = Glyph(size, modifier) {
    drawPath(Paths.noteBody, tint, style = strokeButt(1.5f))
    drawPath(Paths.noteLines, tint, style = Stroke(1.5f, cap = StrokeCap.Round))
}

/** Deterministic receipt check. */
@Composable
fun IconDeterministicCheck(size: Dp = 14.dp, tint: Color = Hex.Moss, modifier: Modifier = Modifier) = Glyph(size, modifier) {
    drawCircle(tint.copy(alpha = tint.alpha * 0.12f), radius = 8.5f, center = Offset(12f, 12f))
    drawCircle(tint, radius = 8.5f, center = Offset(12f, 12f), style = strokeButt(1.5f))
    drawPath(Paths.checkMark, tint, style = stroke(1.7f))
}
