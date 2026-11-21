package com.mine.companion.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.anchor.mine.R

/** Flagship display face — headers, the date, the pill label. */
val Tangeren = FontFamily(Font(R.font.tangeren_bold, FontWeight.Bold))

/** Body face. Delete any line whose file isn't in your zip. */
val Novaform = FontFamily(
    Font(R.font.novaform_regular, FontWeight.Normal),
    Font(R.font.novaform_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.novaform_medium, FontWeight.Medium),
    Font(R.font.novaform_medium_italic, FontWeight.Medium, FontStyle.Italic),
    Font(R.font.novaform_semibold, FontWeight.SemiBold),
    Font(R.font.novaform_bold, FontWeight.Bold),
)

private const val TABULAR = "\"tnum\" 1"

private val Base = TextStyle(
    fontFamily = Novaform,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** `mono` now means tabular Novaform — no more system monospace. */
fun mineText(
    size: Float,
    weight: Int = 400,
    lineHeight: Float = 1.5f,
    tracking: Float = 0f,
    mono: Boolean = false,
    italic: Boolean = false,
    display: Boolean = false,
): TextStyle = Base.copy(
    fontSize = size.sp,
    fontWeight = if (display) FontWeight.Bold else FontWeight(weight),
    lineHeight = lineHeight.em,
    letterSpacing = tracking.em,
    fontFamily = if (display) Tangeren else Novaform,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontFeatureSettings = if (mono) TABULAR else null,
)

object MineType {
    val Display = mineText(58f, lineHeight = 1f, tracking = -0.03f, display = true)
    val LargeTitle = mineText(32f, lineHeight = 1.1f, tracking = -0.02f, display = true)
    val Title = mineText(24f, lineHeight = 1.15f, tracking = -0.015f, display = true)
    val Headline = mineText(17f, lineHeight = 1.25f, tracking = -0.005f, display = true)
    val Lede = mineText(20f, 400, 1.4f, -0.012f)
    val Body = mineText(16f, 400, 1.5f, -0.004f)
    val Callout = mineText(15f, 400, 1.4f, -0.003f)
    val Footnote = mineText(13f, 400, 1.38f, 0f)
    val Caption = mineText(12f, 400, 1.33f, 0.004f)
    val Micro = mineText(11f, 500, 1.27f, 0.06f)
}

fun TextStyle.num(): TextStyle = copy(fontFeatureSettings = TABULAR)

/** Kept for existing call sites: every former "serif" heading is now Tangeren. */
fun TextStyle.serif(): TextStyle = copy(fontFamily = Tangeren, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
fun TextStyle.display(): TextStyle = serif()
fun TextStyle.italic(): TextStyle = copy(fontStyle = FontStyle.Italic)
fun TextStyle.w(weight: Int): TextStyle = copy(fontWeight = FontWeight(weight))
fun TextStyle.lh(lineHeight: Float): TextStyle = copy(lineHeight = lineHeight.em)

fun TextStyle.lineHeightPx(density: Density): Float = with(density) {
    when {
        lineHeight.isEm -> fontSize.toPx() * lineHeight.value
        lineHeight.isSp -> lineHeight.toPx()
        else -> fontSize.toPx() * 1.5f
    }
}

@Composable
fun Txt(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    decoration: TextDecoration? = null,
    softWrap: Boolean = true,
    colorProducer: ColorProducer? = null,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(
            color = if (colorProducer != null) Color.Unspecified else color,
            textAlign = textAlign ?: style.textAlign,
            textDecoration = decoration,
        ),
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        color = colorProducer,
    )
}

@Composable
fun Txt(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = textAlign ?: style.textAlign),
        onTextLayout = onTextLayout,
        overflow = overflow,
        maxLines = maxLines,
    )
}
