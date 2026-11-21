package com.mine.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.primitives.tap
import com.mine.companion.ui.theme.Hex
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.minePaperSheet
import com.mine.companion.ui.theme.mineText
import com.mine.companion.ui.theme.serif

/* Shared building blocks of the legacy (#131314 + paper) screens. */

val PaperTop = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
val R16 = RoundedCornerShape(16.dp)
val R12 = RoundedCornerShape(12.dp)

/** `font-serif` heading at a given size / leading. */
fun serifHeading(size: Float, lineHeight: Float = 1.5f): TextStyle = mineText(size, lineHeight = lineHeight).serif()

/** Full-screen dark base that sits under the system status bar. */
@Composable
fun LegacyScreen(
    modifier: Modifier = Modifier,
    background: Color = Hex.Night,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(background)
            .statusBarsPadding(),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** `rounded-t-[34px] mine-paper-sheet` that reaches the bottom edge, content clear of the nav bar. */
@Composable
fun PaperSheet(
    modifier: Modifier = Modifier,
    padding: Modifier = Modifier.padding(28.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .minePaperSheet(PaperTop)
            .navigationBarsPadding()
            .then(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** `w-full py-3.5 rounded-2xl text-[14px] font-medium` primary action. */
@Composable
fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    enabled: Boolean = true,
    disabledAlpha: Float = 0.3f,
    content: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else disabledAlpha }
            .background(if (dark) Hex.Jet else Hex.Cream, R16)
            .tap(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else Txt(label, mineText(14f, 500), if (dark) Hex.Cream else Hex.Jet)
    }
}

/** `w-full py-3 rounded-2xl bg-white/[0.06] text-[13.5px]` secondary action. */
@Composable
fun SecondaryButton(label: String, modifier: Modifier = Modifier, onPaper: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(if (onPaper) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.06f), R16)
            .tap(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, mineText(13.5f, if (onPaper) 500 else 400), if (onPaper) Hex.Jet else Hex.Bone)
    }
}

/** 48dp header: back chevron left, centred title, optional trailing (or a 24dp spacer). */
@Composable
fun BackHeader(
    title: String?,
    onBack: () -> Unit,
    titleStyle: TextStyle = mineText(14f, 500),
    titleColor: Color = Hex.Bone,
    chevron: Dp = 20.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.offset(x = (-8).dp).tap(onClick = onBack).padding(8.dp)) { // p-2 -ml-2
            LIcon(Lucide.ChevronLeft, chevron, Hex.Mute)
        }
        if (title != null) Txt(title, titleStyle, titleColor)
        if (trailing != null) trailing() else Spacer(Modifier.width(24.dp))
    }
}

/** Onboarding step header (TopStepNav). */
@Composable
fun TopStepNav(label: String, onBack: () -> Unit) {
    BackHeader(title = label, onBack = onBack, titleStyle = mineText(13f), titleColor = Hex.Mute)
}
