package com.anchor.mine.ui.screens

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anchor.mine.security.KeyManager
import com.anchor.mine.ui.components.EditorialCard
import com.anchor.mine.ui.components.HairlineDivider
import com.anchor.mine.ui.theme.DeepTeal
import com.anchor.mine.ui.theme.HairlineLight
import com.anchor.mine.ui.theme.TealSurface
import com.anchor.mine.ui.theme.WarmCream
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onNavigateBack: () -> Unit = {}) {
    val context = LocalContext.current
    val keyManager = remember { KeyManager(context) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val backInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepTeal)
            .systemBarsPadding()
            .padding(24.dp)
    ) {
        // --- HEADER ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = WarmCream,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(
                        interactionSource = backInteraction,
                        indication = LocalIndication.current,
                        onClick = onNavigateBack
                    )
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = "Settings", style = MaterialTheme.typography.displayMedium, color = WarmCream)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- TABS ---
        Row(modifier = Modifier.fillMaxWidth()) {
            val generalInteraction = remember { MutableInteractionSource() }
            val keysInteraction = remember { MutableInteractionSource() }

            Column(
                modifier = Modifier.weight(1f).clickable(interactionSource = generalInteraction, indication = LocalIndication.current) { selectedTab = 0 },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("General", style = MaterialTheme.typography.bodyLarge, color = if (selectedTab == 0) WarmCream else WarmCream.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(8.dp))
                if (selectedTab == 0) HairlineDivider()
            }
            Column(
                modifier = Modifier.weight(1f).clickable(interactionSource = keysInteraction, indication = LocalIndication.current) { selectedTab = 1 },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Keys (BYOK)", style = MaterialTheme.typography.bodyLarge, color = if (selectedTab == 1) WarmCream else WarmCream.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(8.dp))
                if (selectedTab == 1) HairlineDivider()
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- TAB CONTENT ---
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (selectedTab == 0) {
                GeneralTab()
            } else {
                KeysTab(keyManager)
            }
        }
    }
}

@Composable
fun GeneralTab() {
    var personaText by remember { mutableStateOf("I am MineAI, your direct and concise co-founder.") }
    var memoryText by remember { mutableStateOf("- Buy a belt\n- Call David\n- Feed the dog") }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {

        // 1. Persona Editor
        Column {
            Text("Persona", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.6f))
            Text("Soul.md (Markdown):", style = MaterialTheme.typography.bodyLarge, color = WarmCream)
            Spacer(modifier = Modifier.height(8.dp))
            EditorialCard(modifier = Modifier.fillMaxWidth().height(150.dp), backgroundColor = TealSurface) {
                BasicTextField(
                    value = personaText,
                    onValueChange = { personaText = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = WarmCream),
                    cursorBrush = SolidColor(WarmCream),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 2. Memory Editor
        Column {
            Text("Memory", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.6f))
            Text("Core.md (Bullet points):", style = MaterialTheme.typography.bodyLarge, color = WarmCream)
            Spacer(modifier = Modifier.height(8.dp))
            EditorialCard(modifier = Modifier.fillMaxWidth().height(150.dp), backgroundColor = TealSurface) {
                BasicTextField(
                    value = memoryText,
                    onValueChange = { memoryText = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = WarmCream),
                    cursorBrush = SolidColor(WarmCream),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 3. Heatmap Calendar (Visual Placeholder)
        Column {
            Text("Heatmap", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(8.dp))
            EditorialCard(modifier = Modifier.fillMaxWidth(), backgroundColor = TealSurface) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("[ Month | Year ]", style = MaterialTheme.typography.bodyLarge, color = WarmCream)
                    Spacer(modifier = Modifier.height(16.dp))

                    // A simple 7x4 grid to simulate the calendar heatmap
                    for (row in 0..3) {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            for (col in 1..7) {
                                val day = (row * 7) + col
                                val intensity = listOf(0.1f, 0.4f, 0.8f, 1.0f).random() // Random density for the visual

                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(if (day <= 28) WarmCream.copy(alpha = intensity) else DeepTeal),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (day <= 28) {
                                        Text(
                                            text = day.toString(),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (intensity > 0.5f) DeepTeal else WarmCream
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KeysTab(keyManager: KeyManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var groqKey by remember { mutableStateOf(keyManager.getGroqKey() ?: "") }
    var nimKey by remember { mutableStateOf(keyManager.getNimKey() ?: "") }
    var savedMessage by remember { mutableStateOf("") }

    val saveInteraction = remember { MutableInteractionSource() }
    val exportInteraction = remember { MutableInteractionSource() }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {

        Column {
            Text("Nvidia NIM API Key", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(8.dp))
            EditorialCard(modifier = Modifier.fillMaxWidth(), backgroundColor = TealSurface) {
                BasicTextField(
                    value = nimKey, onValueChange = { nimKey = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = WarmCream),
                    cursorBrush = SolidColor(WarmCream), modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Column {
            Text("Groq API Key", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(8.dp))
            EditorialCard(modifier = Modifier.fillMaxWidth(), backgroundColor = TealSurface) {
                BasicTextField(
                    value = groqKey, onValueChange = { groqKey = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = WarmCream),
                    cursorBrush = SolidColor(WarmCream), modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Save Button
        EditorialCard(
            modifier = Modifier.fillMaxWidth().clickable(interactionSource = saveInteraction, indication = LocalIndication.current) {
                keyManager.saveGroqKey(groqKey)
                keyManager.saveNimKey(nimKey)
                savedMessage = "Keys encrypted and saved."
            },
            backgroundColor = WarmCream
        ) {
            Text("Save Keys", color = DeepTeal, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        // Export Button
        EditorialCard(
            modifier = Modifier.fillMaxWidth().clickable(interactionSource = exportInteraction, indication = LocalIndication.current) {
                scope.launch {
                    savedMessage = "Exporting..."
                    savedMessage = com.anchor.mine.brain.DataExporter.exportLifeGraph(context)
                }
            },
            backgroundColor = TealSurface
        ) {
            Text("Download Life Graph (JSON-L)", color = WarmCream, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        if (savedMessage.isNotEmpty()) {
            Text(savedMessage, color = WarmCream, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}