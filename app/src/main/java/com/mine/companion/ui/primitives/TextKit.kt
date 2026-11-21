package com.mine.companion.ui.primitives

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mine.companion.ui.theme.LocalLowPower
import com.mine.companion.ui.theme.LocalReducedMotion
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.blurEffect
import com.mine.companion.ui.theme.lineHeightPx
import kotlin.math.max
import kotlin.math.roundToInt

private const val WORD_REVEAL_LIMIT = 60

@Composable
fun StrikeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    strikeColor: Color = color,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Txt(
        text = text, style = style, color = color, maxLines = maxLines, overflow = overflow,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            val l = layout ?: return@drawWithContent
            val fs = style.fontSize.toPx()
            val thickness = max(1.dp.toPx(), fs * 0.065f)
            for (i in 0 until l.lineCount) {
                val y = l.getLineBaseline(i) - fs * 0.28f
                drawLine(strikeColor, Offset(l.getLineLeft(i), y), Offset(l.getLineRight(i), y), thickness)
            }
        },
    )
}

enum class TextAnimation { FadeIn, BlurIn, BlurInUp }

private val SegmentSplit = Regex("(?<=\\S)(?=\\s)|(?<=\\s)(?=\\S)")

fun splitSegments(text: String): List<String> = text.split(SegmentSplit).filter { it.isNotEmpty() }

@Composable
fun InkReveal(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    feather: Dp = 36.dp,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Txt(
        text = text,
        style = style,
        color = color,
        textAlign = textAlign,
        onTextLayout = { layout = it },
        modifier = modifier
            .graphicsLayer {
                compositingStrategy = if (progress() < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                val p = progress()
                val n = text.length
                if (p >= 1f || n == 0) return@drawWithContent
                val head = (p.coerceAtLeast(0f) * n).toInt().coerceIn(0, n - 1)
                val line = l.getLineForOffset(head)
                val x = l.getHorizontalPosition(head, usePrimaryDirection = true)
                val f = feather.toPx()
                for (i in line until l.lineCount) {
                    val top = l.getLineTop(i)
                    val h = l.getLineBottom(i) - top
                    if (i == line) {
                        drawRect(
                            brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = x - f, endX = x),
                            topLeft = Offset(0f, top), size = Size(size.width, h), blendMode = BlendMode.DstOut,
                        )
                    } else {
                        drawRect(Color.Black, topLeft = Offset(0f, top), size = Size(size.width, h), blendMode = BlendMode.DstOut)
                    }
                }
            },
    )
}

@Composable
fun TextAnimate(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    duration: Float = 0.5f,
    delay: Float = 0f,
    animation: TextAnimation = TextAnimation.BlurInUp,
) {
    if (LocalReducedMotion.current) {
        Txt(text, style, color, modifier)
        return
    }
    val words = remember(text) { splitSegments(text).count { it.isNotBlank() } }
    if (LocalLowPower.current || words > WORD_REVEAL_LIMIT) {
        InkTextAnimate(text, style, color, modifier, duration, delay)
    } else {
        WordReveal(text, style, color, modifier, duration, delay, animation)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordReveal(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier,
    duration: Float,
    delay: Float,
    animation: TextAnimation,
) {
    val segments = remember(text) { splitSegments(text) }
    val stagger = duration / max(1, segments.size)
    val elapsed = remember(text) { mutableFloatStateOf(0f) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val spaceWidth = remember(style, density) {
        with(density) { measurer.measure(" ", style).size.width.toDp() }
    }
    val lineHeight = with(density) { style.lineHeightPx(this).toDp() }

    LaunchedEffect(text) {
        val total = delay + stagger * segments.size + 0.35f
        val t0 = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            elapsed.floatValue = (now - t0) / 1_000_000_000f
            if (elapsed.floatValue > total) break
        }
    }

    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(spaceWidth)) {
        segments.forEachIndexed { i, seg ->
            if (seg.isBlank()) {
                val breaks = seg.count { it == '\n' }
                if (breaks > 0) Spacer(Modifier.fillMaxWidth().height(lineHeight * (breaks - 1)))
            } else {
                Txt(
                    seg, style, color,
                    Modifier.graphicsLayer {
                        val local = elapsed.floatValue - delay - i * stagger
                        when (animation) {
                            TextAnimation.FadeIn -> {
                                val p = MineMotion.FmEaseInOut.transform((local / 0.25f).coerceIn(0f, 1f))
                                alpha = p
                                translationY = (1f - p) * 10.dp.toPx()
                            }
                            TextAnimation.BlurIn -> {
                                val p = MineMotion.FmEaseInOut.transform((local / 0.28f).coerceIn(0f, 1f))
                                alpha = p
                                renderEffect = blurEffect((1f - p) * 8.dp.toPx())
                            }
                            TextAnimation.BlurInUp -> {
                                val py = MineMotion.FmEaseInOut.transform((local / 0.25f).coerceIn(0f, 1f))
                                val po = MineMotion.FmEaseInOut.transform((local / 0.30f).coerceIn(0f, 1f))
                                alpha = po
                                translationY = (1f - py) * 12.dp.toPx()
                                renderEffect = blurEffect((1f - py) * 8.dp.toPx())
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun InkTextAnimate(text: String, style: TextStyle, color: Color, modifier: Modifier, duration: Float, delay: Float) {
    val progress = remember(text) { Animatable(0f) }
    LaunchedEffect(text) {
        val ms = (duration * 1000f + text.length * 8f).roundToInt().coerceIn(450, 1600)
        progress.animateTo(1f, tween(ms, delayMillis = (delay * 1000f).roundToInt(), easing = FastOutSlowInEasing))
    }
    val annotated = remember(text) { AnnotatedString(text) }
    InkReveal(
        annotated, style, color,
        progress = { progress.value },
        modifier = modifier.graphicsLayer { translationY = (1f - progress.value) * 6.dp.toPx() },
    )
}

@Composable
fun BlurStream(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Txt(text, style, color, modifier)
}
