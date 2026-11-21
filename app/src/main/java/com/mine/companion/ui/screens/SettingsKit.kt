package com.mine.companion.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mine.companion.store.MineAction
import com.mine.companion.store.mineDispatch
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.icons.LucideGlyph
import com.mine.companion.ui.primitives.GroupRow
import com.mine.companion.ui.primitives.GroupedSurface
import com.mine.companion.ui.primitives.MineSwitch
import com.mine.companion.ui.primitives.Pressable
import com.mine.companion.ui.theme.HairlineWidth
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Shapes
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.w

/*
 * The settings kit: one page scaffold and a handful of rows, used by every system screen so
 * they read as one product. Inset-grouped surfaces, 56dp minimum rows, section captions above,
 * explanatory footers below — the conventions people already know from their phone's settings.
 */

/** Large-title page with a back chevron, scrolling content and room for the home indicator. */
@Composable
fun SettingsPage(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dispatch = mineDispatch()
    Column(
        Modifier
            .fillMaxSize()
            .background(Mine.Canvas)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pressable(
                onClick = onBack ?: { dispatch(MineAction.GoBack) },
                feel = MineMotion.Press.Icon,
                label = "Back",
                modifier = Modifier.size(44.dp),
            ) { LIcon(Lucide.ChevronLeft, 22.dp, Mine.Ink2) }
            Spacer(Modifier.weight(1f))
            if (trailing != null) Box(Modifier.padding(end = 8.dp)) { trailing() }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 40.dp),
        ) {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(title, MineType.LargeTitle, Mine.Ink1, Modifier.semantics { heading() })
                if (subtitle != null) Txt(subtitle, MineType.Callout, Mine.Ink3)
            }
            content()
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Txt(
        text.uppercase(), MineType.Micro, Mine.Ink3,
        modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
fun SectionFooter(text: String) {
    Txt(text, MineType.Footnote, Mine.Ink3, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
}

/** A grouped section: optional caption, rows, optional footer. */
@Composable
fun Section(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (header != null) SectionHeader(header) else Spacer(Modifier.height(16.dp))
    GroupedSurface(content = content)
    if (footer != null) SectionFooter(footer)
}

@Composable
fun SwitchRow(title: String, detail: String? = null, checked: Boolean, divider: Boolean = true, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    GroupRow(
        title = title,
        detail = detail,
        divider = divider,
        modifier = if (enabled) Modifier else Modifier.graphicsLayer { alpha = 0.45f },
        onClick = if (enabled) ({ onChange(!checked) }) else null,
        trailing = { MineSwitch(checked = checked, onCheckedChange = { if (enabled) onChange(it) }, label = title) },
    )
}

@Composable
fun NavRow(title: String, detail: String? = null, divider: Boolean = true, badge: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        GroupRow(
            title = title,
            detail = detail,
            divider = divider,
            onClick = onClick,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (badge != null) {
                        Box(Modifier.background(Mine.Accent, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp)) {
                            Txt(badge, MineType.Caption.w(600), Mine.OnAccent)
                        }
                    }
                    LIcon(Lucide.ChevronRight, 16.dp, Mine.Ink4)
                }
            },
        )
    }
}

/** A row that shows a value on the right; tappable when [onClick] is set. */
@Composable
fun ValueRow(title: String, value: String, detail: String? = null, divider: Boolean = true, valueColor: Color = Mine.Ink3, onClick: (() -> Unit)? = null) {
    GroupRow(
        title = title, detail = detail, divider = divider, onClick = onClick,
        trailing = { Txt(value, MineType.Callout, valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/** A full-width action inside a group: "Export backup", "Delete everything". */
@Composable
fun ActionRow(
    title: String,
    glyph: LucideGlyph? = null,
    tint: Color = Mine.Accent,
    divider: Boolean = true,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .drawBehind {
                if (divider) {
                    val t = HairlineWidth.toPx()
                    drawRect(Mine.Hairline, topLeft = Offset(16.dp.toPx(), size.height - t), size = Size(size.width - 16.dp.toPx(), t))
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pressable(
            onClick = onClick,
            enabled = enabled && !busy,
            feel = MineMotion.Press.Card,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (glyph != null) LIcon(glyph, 18.dp, tint)
                Txt(if (busy) "Working…" else title, MineType.Callout.w(500), tint)
            }
        }
    }
}

/** Segmented choice inside a group row: "Sender and message | Sender only | App only". */
@Composable
fun <T> ChoiceRow(title: String?, options: List<T>, selected: T, label: (T) -> String, divider: Boolean = true, onSelect: (T) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (divider) {
                    val t = HairlineWidth.toPx()
                    drawRect(Mine.Hairline, topLeft = Offset(16.dp.toPx(), size.height - t), size = Size(size.width - 16.dp.toPx(), t))
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) Txt(title, MineType.Callout.w(500), Mine.Ink1)
        Segmented(options, selected, label, onSelect)
    }
}

@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Mine.Surface2, Shapes.R12)
            .padding(3.dp),
    ) {
        val n = options.size.coerceAtLeast(1)
        val segW = (maxWidth - 2.dp * (n - 1)) / n
        val idx = options.indexOf(selected).coerceAtLeast(0)
        val x by animateDpAsState((segW + 2.dp) * idx, MineMotion.snappy(), label = "seg")
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .height(34.dp)
                .background(Mine.Surface4, Shapes.R10),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { o ->
                val on = o == selected
                val ink by animateColorAsState(if (on) Mine.Ink1 else Mine.Ink3, tween(160), label = "seg-ink")
                Pressable(
                    onClick = { onSelect(o) },
                    feel = MineMotion.Press.Chip,
                    modifier = Modifier.width(segW).height(34.dp),
                ) { Txt(label(o), MineType.Footnote.w(if (on) 600 else 500), ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** A labelled text field row (endpoint, model, API key, passphrase). */
@Composable
fun FieldRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    divider: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    var reveal by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (divider) {
                    val t = HairlineWidth.toPx()
                    drawRect(Mine.Hairline, topLeft = Offset(16.dp.toPx(), size.height - t), size = Size(size.width - 16.dp.toPx(), t))
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Txt(label, MineType.Caption.w(500), Mine.Ink3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MineType.Callout.copy(color = Mine.Ink1),
                cursorBrush = SolidColor(Mine.Accent),
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f).heightIn(min = 28.dp).semantics { contentDescription = label },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) Txt(placeholder, MineType.Callout, Mine.Ink4, maxLines = 1)
                        inner()
                    }
                },
            )
            if (secret && value.isNotEmpty()) {
                Pressable(onClick = { reveal = !reveal }, feel = MineMotion.Press.Icon, label = if (reveal) "Hide" else "Show", modifier = Modifier.size(36.dp)) {
                    LIcon(Lucide.Eye, 16.dp, if (reveal) Mine.Ink1 else Mine.Ink3)
                }
            }
        }
    }
}

enum class Tone { Neutral, Good, Warn, Bad }

/** A short status card at the top of a page: what is true right now, and what to do about it. */
@Composable
fun StatusCard(
    title: String,
    body: String? = null,
    tone: Tone = Tone.Neutral,
    glyph: LucideGlyph = Lucide.Info,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val color = when (tone) {
        Tone.Neutral -> Mine.Ink2
        Tone.Good -> Mine.Settled
        Tone.Warn -> Mine.Accent
        Tone.Bad -> Mine.Urgent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Mine.Surface1, Shapes.Group)
            .border(HairlineWidth, Mine.Hairline, Shapes.Group)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(32.dp).background(color.copy(alpha = 0.14f), Shapes.R10), contentAlignment = Alignment.Center) {
                LIcon(glyph, 17.dp, color)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Txt(title, MineType.Callout.w(600), Mine.Ink1)
                if (body != null) Txt(body, MineType.Footnote, Mine.Ink2)
            }
        }
        if (action != null && onAction != null) {
            Pressable(
                onClick = onAction,
                feel = MineMotion.Press.Control,
                modifier = Modifier.padding(start = 44.dp).height(34.dp).background(color.copy(alpha = 0.16f), Shapes.R10).padding(horizontal = 14.dp),
            ) { Txt(action, MineType.Footnote.w(600), color) }
        }
    }
}

/** Primary / secondary capsule buttons used at the bottom of forms. */
@Composable
fun CapsuleButton(label: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        enabled = enabled && !busy,
        feel = MineMotion.Press.Control,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(if (primary) Mine.Ink1 else Mine.Surface2, CircleShape),
    ) {
        Txt(if (busy) "Working…" else label, MineType.Callout.w(600), if (primary) Mine.Canvas else Mine.Ink1)
    }
}

/** A small inline dot + label, e.g. "● Listening". */
@Composable
fun RowScope.StatusDot(label: String, color: Color, size: Dp = 7.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(size).background(color, CircleShape))
        Txt(label, MineType.Footnote.w(500), Mine.Ink2)
    }
}
