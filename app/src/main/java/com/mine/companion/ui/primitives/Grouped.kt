package com.mine.companion.ui.primitives

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mine.companion.ui.theme.HairlineWidth
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Shapes
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.rememberHaptics
import com.mine.companion.ui.theme.w

/** iOS inset-grouped container: one tinted plane, hairline border, inset separators. */
@Composable
fun GroupedSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Shapes.Group)
            .background(Mine.Surface1)
            .border(HairlineWidth, Mine.Hairline, Shapes.Group),
        content = content,
    )
}

@Composable
fun GroupRow(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    divider: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Highlight snaps in (60ms) and drifts out (280ms), the way iOS table rows release.
    val highlight by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 60 else 280), label = "row-highlight")
    val tint = Mine.Surface3
    val rule = Mine.Hairline
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .drawBehind {
                if (highlight > 0f) drawRect(tint, alpha = highlight)
                if (divider) {
                    val inset = 16.dp.toPx()
                    val t = HairlineWidth.toPx()
                    drawRect(rule, topLeft = Offset(inset, size.height - t), size = Size(size.width - inset, t))
                }
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, MineType.Callout.w(500), Mine.Ink1)
            if (detail != null) {
                Txt(detail, MineType.Footnote, Mine.Ink3, Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

/**
 * Spring thumb (ζ 0.82); while pressed it stretches 5dp toward its direction of travel.
 * Everything is read in the draw phase, so toggling never recomposes the row.
 */
@Composable
fun MineSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pos by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = 480f), label = "switch-pos")
    val stretch by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 1f, stiffness = 900f), label = "switch-stretch")
    val on = Mine.Accent
    val off = Mine.Surface4
    val thumb = Mine.Thumb
    val ring = Mine.Hairline
    Canvas(
        modifier
            .size(46.dp, 28.dp)
            .toggleable(value = checked, interactionSource = interaction, indication = null, role = Role.Switch) {
                haptics.toggle(it)
                onCheckedChange(it)
            }
            .semantics { contentDescription = label },
    ) {
        val p = pos.coerceIn(0f, 1f)
        val h = size.height
        drawRoundRect(lerp(off, on, p), cornerRadius = CornerRadius(h / 2f))
        val pad = 2.dp.toPx()
        val d = h - pad * 2f
        val tw = d + 5.dp.toPx() * stretch
        val x = pad + (size.width - pad * 2f - tw) * p
        drawRoundRect(thumb, topLeft = Offset(x, pad), size = Size(tw, d), cornerRadius = CornerRadius(d / 2f))
        drawRoundRect(
            ring, topLeft = Offset(x, pad), size = Size(tw, d),
            cornerRadius = CornerRadius(d / 2f), style = Stroke(0.5.dp.toPx()),
        )
    }
}
