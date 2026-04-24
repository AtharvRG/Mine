package com.anchor.mine.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anchor.mine.ui.theme.DeepTeal
import com.anchor.mine.ui.theme.HairlineLight
import com.anchor.mine.ui.theme.TealSurface
import com.anchor.mine.ui.theme.WarmCream
val EditorialShape = RoundedCornerShape(8.dp)

@Composable
fun EditorialCard(
    modifier: Modifier = Modifier, // Just use the standard modifier!
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier, // The clickable gets applied here perfectly
        shape = EditorialShape,
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 0.dp

    ) {
        Box(modifier = Modifier.padding(24.dp), content = content)
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth() // Added this so it stretches across the screen!
            .height(1.dp)   // Fixed height for that crisp 1dp line
            .background(MaterialTheme.colorScheme.outline)
    )
}

@Composable
fun AdvancedChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    isThinking: Boolean,
    onThinkingToggle: () -> Unit,
    onSubmit: () -> Unit
) {
    val sendButtonInteraction = remember { MutableInteractionSource() }
    val thinkingInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, HairlineLight, RoundedCornerShape(16.dp))
            .background(TealSurface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp, max = 150.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            if (value.isEmpty()) {
                Text("Type or ask something...", style = MaterialTheme.typography.bodyLarge, color = WarmCream.copy(alpha = 0.4f))
            }

            BasicTextField(
                value = value,
                onValueChange = {
                    // Strip out newlines if they sneak in from the hardware enter key
                    if (!it.contains("\n")) onValueChange(it)
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = WarmCream),
                cursorBrush = SolidColor(WarmCream),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onKeyEvent { event ->
                        // Safely catch hardware Enter key (KeyUp only to prevent double fire)
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                            onSubmit()
                            return@onKeyEvent true
                        }
                        // Consume KeyDown so it doesn't type a newline
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                            return@onKeyEvent true
                        }
                        false
                    }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DeepTeal.copy(alpha = 0.3f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(WarmCream.copy(alpha = 0.05f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Attach", tint = WarmCream.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                }

                val toggleBgColor = if (isThinking) WarmCream else WarmCream.copy(alpha = 0.05f)
                val toggleContentColor = if (isThinking) DeepTeal else WarmCream.copy(alpha = 0.5f)

                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(toggleBgColor)
                        .clickable(
                            interactionSource = thinkingInteraction,
                            indication = LocalIndication.current,
                            onClick = onThinkingToggle
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lightbulb, contentDescription = "Thinking", tint = toggleContentColor, modifier = Modifier.size(16.dp))

                    AnimatedVisibility(
                        visible = isThinking,
                        enter = expandHorizontally(animationSpec = tween(250)) + androidx.compose.animation.fadeIn(animationSpec = tween(250)),
                        exit = shrinkHorizontally(animationSpec = tween(250)) + androidx.compose.animation.fadeOut(animationSpec = tween(250))
                    ) {
                        Text(
                            text = "Thinking",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                            color = toggleContentColor,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }

            val sendBgColor = if (value.isNotBlank()) WarmCream else WarmCream.copy(alpha = 0.05f)
            val sendIconColor = if (value.isNotBlank()) DeepTeal else WarmCream.copy(alpha = 0.4f)

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(sendBgColor)
                    .clickable(
                        interactionSource = sendButtonInteraction,
                        indication = LocalIndication.current,
                        onClick = onSubmit
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = sendIconColor, modifier = Modifier.size(16.dp))
            }
        }
    }
}