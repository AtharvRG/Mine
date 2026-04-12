package com.anchor.mine.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anchor.mine.audio.AmbientListeningService
import com.anchor.mine.security.KeyManager
import com.anchor.mine.ui.components.EditorialCard
import com.anchor.mine.ui.components.HairlineDivider
import com.anchor.mine.ui.theme.DeepTeal
import com.anchor.mine.ui.theme.HairlineLight
import com.anchor.mine.ui.theme.TealSurface
import com.anchor.mine.ui.theme.WarmCream
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch
import java.util.Calendar

fun getDynamicGreeting(): Pair<String, String> {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val timeGreeting = when (hour) {
        in 5..11 -> "Good Morning."
        in 12..16 -> "Good Afternoon."
        in 17..21 -> "Good Evening."
        else -> "Late Night Hustle."
    }
    val prefixes = listOf("Hey Atharv!", "Welcome back,", "Ready to build?", "Let's get to work,")
    return Pair(prefixes.random(), timeGreeting)
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    onNavigateToChat: (String) -> Unit = {},
    onNavigateToReview: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {}
) {
    val context = LocalContext.current
    val keyManager = remember { KeyManager(context) }
    
    val db = remember { com.anchor.mine.brain.MineDatabase.getDatabase(context) }
    val nimClient = remember { com.anchor.mine.network.NimClient(context) }
    val chatSessions by db.memoryDao().getAllChatSessions().collectAsState(initial = emptyList())
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val menuInteraction = remember { MutableInteractionSource() }
    val reviewInteraction = remember { MutableInteractionSource() }
    val settingsDrawerInteraction = remember { MutableInteractionSource() }
    val newChatInteraction = remember { MutableInteractionSource() }
    val ambientHistoryInteraction = remember { MutableInteractionSource() }

    var inputText by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(true) }
    
    var isAmbientActive by remember { mutableStateOf(keyManager.isAmbientActive()) }
    var isNudgeActive by remember { mutableStateOf(keyManager.isNudgeActive()) }

    var drawerSearchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    
    var showRenameDialog by remember { mutableStateOf<Int?>(null) }
    var newTitleText by remember { mutableStateOf("") }

    val greeting = remember { getDynamicGreeting() }

    val permissionsList = mutableListOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val permissionState = rememberMultiplePermissionsState(permissions = permissionsList)

    LaunchedEffect(drawerSearchQuery) {
        if (drawerSearchQuery.length > 2) {
            isSearching = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val queryVectorJson = nimClient.getEmbedding(drawerSearchQuery, "query")
                if (queryVectorJson != "[]") {
                    val qVec = com.anchor.mine.brain.VectorMath.parseVector(queryVectorJson)
                    val allMsgs = db.memoryDao().getAllSearchableMessages()
                    
                    val results = allMsgs.mapNotNull { msg ->
                        if (msg.vectorEmbedding != null) {
                            val msgVec = com.anchor.mine.brain.VectorMath.parseVector(msg.vectorEmbedding)
                            val score = com.anchor.mine.brain.VectorMath.cosineSimilarity(qVec, msgVec)
                            if (score > 0.5f) Pair(score, Pair(msg.sessionId, msg.text)) else null
                        } else null
                    }.sortedByDescending { it.first }.take(5).map { it.second }
                    
                    searchResults = results
                }
                isSearching = false
            }
        } else {
            searchResults = emptyList()
        }
    }

    // FIXED: Beautiful Teal/Cream Rename Dialog
    if (showRenameDialog != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            containerColor = TealSurface,
            titleContentColor = WarmCream,
            textContentColor = WarmCream,
            title = { Text("Rename Chat") },
            text = {
                BasicTextField(
                    value = newTitleText, onValueChange = { newTitleText = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = WarmCream),
                    cursorBrush = SolidColor(WarmCream),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(DeepTeal).padding(16.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        db.memoryDao().updateChatTitle(showRenameDialog!!, newTitleText)
                        showRenameDialog = null
                    }
                }) { Text("Save", color = WarmCream, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text("Cancel", color = WarmCream.copy(alpha = 0.6f)) }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = TealSurface, modifier = Modifier.width(320.dp)) {
                Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
                    
                    // Search Bar
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, HairlineLight, RoundedCornerShape(8.dp)).background(DeepTeal).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = WarmCream.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (drawerSearchQuery.isEmpty()) Text("Search Chats...", style = MaterialTheme.typography.bodyLarge, color = WarmCream.copy(alpha = 0.5f))
                                BasicTextField(
                                    value = drawerSearchQuery, onValueChange = { drawerSearchQuery = it },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = WarmCream), cursorBrush = SolidColor(WarmCream), modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(interactionSource = newChatInteraction, indication = LocalIndication.current, onClick = { onNavigateToChat("") }), 
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("New Chat", style = MaterialTheme.typography.bodyLarge, color = WarmCream)
                        Icon(Icons.Default.Add, contentDescription = "New", tint = WarmCream)
                    }
                    
                    HairlineDivider(modifier = Modifier.padding(vertical = 24.dp))
                    
                    if (drawerSearchQuery.isNotEmpty()) {
                        Text("Semantic Results", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(16.dp))
                        if (isSearching) {
                            Text("Thinking...", style = MaterialTheme.typography.bodyLarge, color = WarmCream.copy(alpha = 0.4f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        } else {
                            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                searchResults.forEach { (_, snippet) ->
                                    Text(
                                        text = "\"$snippet\"", style = MaterialTheme.typography.bodyLarge, color = WarmCream,
                                        modifier = Modifier.fillMaxWidth().clickable { }.padding(vertical = 12.dp),
                                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else {
                        Text("History", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            if (chatSessions.isEmpty()) {
                                Text("No recent chats.", style = MaterialTheme.typography.bodyLarge, color = WarmCream.copy(alpha = 0.4f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            } else {
                                chatSessions.forEach { session ->
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            text = session.title, style = MaterialTheme.typography.bodyLarge, color = WarmCream,
                                            modifier = Modifier.weight(1f).clickable {
                                                // FIXED: Navigate to the chat and pass the ID!
                                                onNavigateToChat("&sessionId=${session.sessionId}")
                                            },
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        // FIXED: Actual Edit Icon
                                        Icon(Icons.Default.Edit, contentDescription = "Rename", tint = WarmCream.copy(alpha = 0.4f), modifier = Modifier.size(16.dp).clickable { 
                                            newTitleText = session.title
                                            showRenameDialog = session.sessionId 
                                        })
                                        Spacer(modifier = Modifier.width(12.dp))
                                        // FIXED: Actual Delete Icon
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = WarmCream.copy(alpha = 0.4f), modifier = Modifier.size(16.dp).clickable { 
                                            scope.launch(kotlinx.coroutines.Dispatchers.IO) { db.memoryDao().deleteChatSession(session.sessionId) }
                                        })
                                    }
                                }
                            }
                        }
                    }
                    
                    HairlineDivider(modifier = Modifier.padding(vertical = 16.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth().clickable(interactionSource = ambientHistoryInteraction, indication = LocalIndication.current, onClick = onNavigateToHistory).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Ambient Listenings", style = MaterialTheme.typography.bodyLarge, color = WarmCream)
                    }
                    Row(modifier = Modifier.fillMaxWidth().clickable(interactionSource = settingsDrawerInteraction, indication = LocalIndication.current, onClick = onNavigateToSettings).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = WarmCream, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Settings", style = MaterialTheme.typography.bodyLarge, color = WarmCream)
                    }
                }
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize().background(DeepTeal).systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu", tint = WarmCream, modifier = Modifier.clickable(interactionSource = menuInteraction, indication = LocalIndication.current) { scope.launch { drawerState.open() } })
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(text = greeting.first, style = MaterialTheme.typography.displayMedium, color = WarmCream)
                Text(text = greeting.second, style = MaterialTheme.typography.displayLarge, color = WarmCream)
            }

            Row(modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                EditorialCard(modifier = Modifier.weight(1f).fillMaxHeight(), backgroundColor = TealSurface, borderColor = HairlineLight) {
                    Column {
                        Text("Things on priority", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.6f))
                        HairlineDivider(modifier = Modifier.padding(vertical = 12.dp))
                        Text("1) Buy a belt", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp, color = WarmCream)
                        Text("2) Call David", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp, color = WarmCream)
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().height(80.dp).clip(com.anchor.mine.ui.components.EditorialShape).border(1.dp, HairlineLight, com.anchor.mine.ui.components.EditorialShape).background(TealSurface).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Ambient\nListening", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.8f))
                            Switch(
                                checked = isAmbientActive, 
                                onCheckedChange = { isChecked ->
                                    if (isChecked) {
                                        if (permissionState.allPermissionsGranted) {
                                            isAmbientActive = true
                                            keyManager.setAmbientActive(true)
                                            val intent = Intent(context, AmbientListeningService::class.java)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                                        } else {
                                            permissionState.launchMultiplePermissionRequest()
                                        }
                                    } else {
                                        isAmbientActive = false
                                        keyManager.setAmbientActive(false)
                                        val intent = Intent(context, AmbientListeningService::class.java).apply { action = AmbientListeningService.ACTION_STOP }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = DeepTeal, checkedTrackColor = WarmCream, uncheckedThumbColor = WarmCream.copy(alpha=0.5f), uncheckedTrackColor = DeepTeal)
                            )
                        }
                    }
                    
                    Box(modifier = Modifier.fillMaxWidth().height(80.dp).clip(com.anchor.mine.ui.components.EditorialShape).border(1.dp, HairlineLight, com.anchor.mine.ui.components.EditorialShape).background(TealSurface).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Nudge\nTalks", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.8f))
                            Switch(
                                checked = isNudgeActive, onCheckedChange = { isNudgeActive = it; keyManager.setNudgeActive(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = DeepTeal, checkedTrackColor = WarmCream, uncheckedThumbColor = WarmCream.copy(alpha=0.5f), uncheckedTrackColor = DeepTeal)
                            )
                        }
                    }

                    EditorialCard(modifier = Modifier.weight(1f).fillMaxWidth().clickable(interactionSource = reviewInteraction, indication = LocalIndication.current, onClick = onNavigateToReview), backgroundColor = TealSurface, borderColor = HairlineLight) {
                        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                            Text("Memory\nNodes", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.6f))
                            Text("12", style = MaterialTheme.typography.displayLarge.copy(fontSize = 48.sp), color = WarmCream)
                        }
                    }
                }
            }

            AdvancedChatInput(
                value = inputText, onValueChange = { inputText = it },
                isThinking = isThinking, onThinkingToggle = { isThinking = !isThinking },
                onSubmit = {
                    val textToSend = inputText.trim()
                    inputText = "" 
                    if (textToSend.isNotEmpty()) onNavigateToChat(textToSend)
                }
            )
        }
    }
}

@Composable
fun AdvancedChatInput(value: String, onValueChange: (String) -> Unit, isThinking: Boolean, onThinkingToggle: () -> Unit, onSubmit: () -> Unit) {
    val sendButtonInteraction = remember { MutableInteractionSource() }
    val thinkingInteraction = remember { MutableInteractionSource() }

    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, HairlineLight, RoundedCornerShape(16.dp)).background(TealSurface)) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 150.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (value.isEmpty()) Text("Type or ask something...", style = MaterialTheme.typography.bodyLarge, color = WarmCream.copy(alpha = 0.4f))
            BasicTextField(
                value = value,
                onValueChange = { if (!it.contains("\n")) onValueChange(it) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = WarmCream),
                cursorBrush = SolidColor(WarmCream),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter && !event.isShiftPressed) {
                        if (event.type == KeyEventType.KeyDown) onSubmit()
                        return@onPreviewKeyEvent true 
                    }
                    false
                }
            )
        }

        Row(modifier = Modifier.fillMaxWidth().background(DeepTeal.copy(alpha = 0.3f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(WarmCream.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, contentDescription = "Attach", tint = WarmCream.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                }

                val toggleBgColor = if (isThinking) WarmCream else WarmCream.copy(alpha = 0.05f)
                val toggleContentColor = if (isThinking) DeepTeal else WarmCream.copy(alpha = 0.5f)

                Row(
                    modifier = Modifier.height(36.dp).clip(CircleShape).background(toggleBgColor).clickable(interactionSource = thinkingInteraction, indication = LocalIndication.current, onClick = onThinkingToggle).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lightbulb, contentDescription = "Thinking", tint = toggleContentColor, modifier = Modifier.size(16.dp))
                    AnimatedVisibility(visible = isThinking, enter = expandHorizontally(animationSpec = tween(250)) + androidx.compose.animation.fadeIn(animationSpec = tween(250)), exit = shrinkHorizontally(animationSpec = tween(250)) + androidx.compose.animation.fadeOut(animationSpec = tween(250))) {
                        Text("Thinking", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp), color = toggleContentColor, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            val sendBgColor = if (value.isNotBlank()) WarmCream else WarmCream.copy(alpha = 0.05f)
            val sendIconColor = if (value.isNotBlank()) DeepTeal else WarmCream.copy(alpha = 0.4f)

            Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(sendBgColor).clickable(interactionSource = sendButtonInteraction, indication = LocalIndication.current, onClick = onSubmit), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = sendIconColor, modifier = Modifier.size(16.dp))
            }
        }
    }
}
