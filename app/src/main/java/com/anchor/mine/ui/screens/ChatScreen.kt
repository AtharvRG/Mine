package com.anchor.mine.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
// FIXED: Added FontWeight import
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
// FIXED: Importing the shared AdvancedChatInput
import com.anchor.mine.ui.components.AdvancedChatInput

import com.anchor.mine.ui.theme.DeepTeal
import com.anchor.mine.ui.theme.HairlineLight
import com.anchor.mine.ui.theme.TealSurface
import com.anchor.mine.ui.theme.WarmCream

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    initialMessage: String = "",
    loadSessionId: Int? = null, // <-- ADDED THIS
    onNavigateBack: () -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val backInteraction = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val haptics = remember { com.anchor.mine.sensors.TactileEngine(context) }

    // --- NEW: Trigger the initial message instantly! ---
    // Load history if a sessionId was passed!
    LaunchedEffect(loadSessionId) {
        if (loadSessionId != null) {
            viewModel.loadChatHistory(loadSessionId)
        }
    }

    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepTeal)
            .systemBarsPadding()
            .padding(top = 24.dp, bottom = 24.dp, start = 24.dp, end = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 24.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = WarmCream,
                modifier = Modifier.size(28.dp).clickable(interactionSource = backInteraction, indication = LocalIndication.current, onClick = onNavigateBack)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = "Conversation", style = MaterialTheme.typography.displayLarge.copy(fontSize = 36.sp), color = WarmCream)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            items(viewModel.messages) { message ->
                PremiumMessageBubble(message)
            }
        }

        // Calling the shared component from EditorialComponents.kt
        AdvancedChatInput(
            value = inputText,
            onValueChange = { inputText = it },
            isThinking = isThinking,
            onThinkingToggle = {
                isThinking = !isThinking
                haptics.tapLight() // <-- HAPTIC TICK
            },
            onSubmit = {
                val textToSend = inputText.trim()
                inputText = ""

                if (textToSend.isNotEmpty()) {
                    haptics.tapHeavy() // <-- HAPTIC CLICK
                    viewModel.sendMessage(textToSend, isThinking)
                }
            }
        )
    }
}

@Composable
fun PremiumMessageBubble(message: ChatMessage) {
    val isUser = message.isUser
    var showThinkingProcess by remember { mutableStateOf(false) }
    val toggleInteraction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.85f)) {

            // Hide the thinking process section entirely if there's no content in it yet
            if (!isUser && !message.thinkingProcess.isNullOrEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .clickable(
                            interactionSource = toggleInteraction,
                            indication = LocalIndication.current,
                            onClick = { showThinkingProcess = !showThinkingProcess }
                        )
                ) {
                    Icon(
                        imageVector = if (showThinkingProcess) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Toggle", tint = WarmCream.copy(alpha = 0.5f), modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Thought Process", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.5f))
                }

                AnimatedVisibility(
                    visible = showThinkingProcess,
                    enter = expandVertically(animationSpec = tween(300)),
                    exit = shrinkVertically(animationSpec = tween(300))
                ) {
                    Text(
                        text = message.thinkingProcess ?: "",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontStyle = FontStyle.Italic),
                        color = WarmCream.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp)
                    )
                }
            }

            // Hide the message bubble for AI responses until there is actually text to display
            if (isUser || message.text.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        ))
                        .background(if (isUser) DeepTeal.copy(alpha = 0.5f) else TealSurface)
                        .border(1.dp, HairlineLight, RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp, bottomStart = if (isUser) 16.dp else 4.dp, bottomEnd = if (isUser) 4.dp else 16.dp
                        ))
                        .padding(20.dp)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = if (!isUser && message.isThinking) WarmCream.copy(alpha = 0.5f) else WarmCream
                        )
                    )
                }
            }
        }
    }
}