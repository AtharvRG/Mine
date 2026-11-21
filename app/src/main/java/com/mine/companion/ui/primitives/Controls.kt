package com.mine.companion.ui.primitives

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.Txt
import kotlin.math.roundToInt

/**
 * `<input>` styled by its container. Placeholder sits in the same text box, the caret takes
 * the text colour (browser default), and Enter maps to the IME action.
 */
@Composable
fun MineField(
    value: String,
    onValueChange: (String) -> Unit,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    placeholderColor: Color = color.copy(alpha = 0.5f), // Tailwind v4 preflight: currentColor @ 50%
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.CenterStart,
) {
    val submit by rememberUpdatedState(onSubmit)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = style.copy(color = color),
        singleLine = singleLine,
        cursorBrush = SolidColor(color),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { submit?.invoke() }),
        decorationBox = { inner ->
            Box(contentAlignment = contentAlignment) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Txt(placeholder, style, placeholderColor, maxLines = if (singleLine) 1 else Int.MAX_VALUE)
                inner()
            }
        },
    )
}

/**
 * Native `<input type="checkbox">` in a dark colour-scheme with `accent-color`: a 16dp, 2dp-radius
 * box; checked fills with the accent and draws a contrasting tick.
 */
@Composable
fun NativeCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val fill by animateColorAsState(if (checked) accent else Color(0xFF3B3B3B), tween(120), label = "cb-fill")
    val border by animateColorAsState(if (checked) accent else Color(0xFF858585), tween(120), label = "cb-border")
    val tick = if (accent.luminance() > 0.5f) Color(0xFF111111) else Color.White
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .size(size)
            .background(fill, shape)
            .border(1.dp, border, shape)
            .semantics {
                role = Role.Checkbox
                stateDescription = if (checked) "Checked" else "Not checked"
            }
            .tap { onCheckedChange(!checked) },
    ) {
        if (checked) {
            Canvas(Modifier.size(size)) {
                val w = this.size.width
                val s = 1.8.dp.toPx()
                drawLine(tick, Offset(w * 0.22f, w * 0.52f), Offset(w * 0.42f, w * 0.72f), s, StrokeCap.Round)
                drawLine(tick, Offset(w * 0.42f, w * 0.72f), Offset(w * 0.78f, w * 0.30f), s, StrokeCap.Round)
            }
        }
    }
}

/** Native `<input type="range">` with accent-color: filled track up to a 16dp thumb. */
@Composable
fun NativeRange(
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int,
    max: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    width: Dp = 192.dp,
) {
    val change by rememberUpdatedState(onValueChange)
    fun pick(x: Float, w: Float, thumb: Float): Int {
        val t = ((x - thumb / 2f) / (w - thumb)).coerceIn(0f, 1f)
        return (min + t * (max - min)).roundToInt()
    }
    Canvas(
        modifier
            .width(width)
            .height(16.dp)
            .semantics { role = Role.Button; stateDescription = "$value" }
            .pointerInput(min, max) {
                detectTapGestures { o -> change(pick(o.x, size.width.toFloat(), 16.dp.toPx())) }
            }
            .pointerInput(min, max) {
                detectHorizontalDragGestures { c, _ ->
                    change(pick(c.position.x, size.width.toFloat(), 16.dp.toPx()))
                    c.consume()
                }
            },
    ) {
        val thumb = 16.dp.toPx()
        val trackH = 4.dp.toPx()
        val t = (value - min).toFloat() / (max - min).toFloat()
        val cx = thumb / 2f + t * (size.width - thumb)
        val cy = size.height / 2f
        drawRoundRect(Color.White.copy(alpha = 0.2f), Offset(0f, cy - trackH / 2f), Size(size.width, trackH), CornerRadius(trackH / 2f))
        drawRoundRect(accent, Offset(0f, cy - trackH / 2f), Size(cx, trackH), CornerRadius(trackH / 2f))
        drawCircle(accent, radius = thumb / 2f, center = Offset(cx, cy))
    }
}
