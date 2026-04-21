package com.anchor.mine.ui.screens

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anchor.mine.ui.components.EditorialCard
import com.anchor.mine.ui.components.HairlineDivider
import com.anchor.mine.ui.theme.DeepTeal
import com.anchor.mine.ui.theme.HairlineLight
import com.anchor.mine.ui.theme.InkBlack
import com.anchor.mine.ui.theme.TealSurface
import com.anchor.mine.ui.theme.WarmCream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Dummy data for the UI before we wire the database
data class DummyHistoryItem(val date: Long, val summary: String, val rawText: String)

@Composable
fun AmbientHistoryScreen(onNavigateBack: () -> Unit = {}) {
    var searchQuery by remember { mutableStateOf("") }
    val backInteraction = remember { MutableInteractionSource() }

    // Placeholder data
    val historyItems = listOf(
        DummyHistoryItem(System.currentTimeMillis() - 86400000, "Discussed the Q3 roadmap and backend architecture.", "Yeah so we need to make sure the Q3 roadmap includes the new backend architecture before Friday."),
        DummyHistoryItem(System.currentTimeMillis() - 172800000, "Grocery list: Milk, Eggs, Coffee.", "Don't forget to buy milk, eggs, and coffee on the way home."),
        DummyHistoryItem(System.currentTimeMillis() - 259200000, "Gym motivation.", "I really don't want to go to the gym today, but I probably should.")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepTeal)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        // --- HEADER ---
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 24.dp)) {
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
            Column {
                Text(text = "Ambient", style = MaterialTheme.typography.displayMedium, color = WarmCream)
                Text(text = "Listenings.", style = MaterialTheme.typography.displayLarge, color = WarmCream)
            }
        }

        // --- SEARCH BAR ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, HairlineLight, RoundedCornerShape(8.dp))
                .background(TealSurface)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = WarmCream.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text("Search history...", style = MaterialTheme.typography.bodyLarge, color = WarmCream.copy(alpha = 0.4f))
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = WarmCream),
                        cursorBrush = SolidColor(WarmCream),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- TIMELINE LIST ---
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(historyItems) { item ->
                HistoryCard(item)
            }
        }
    }
}

@Composable
fun HistoryCard(item: DummyHistoryItem) {
    val formatter = SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault())
    val dateString = formatter.format(Date(item.date))

    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = TealSurface,
        borderColor = HairlineLight
    ) {
        Column {
            // Date Label
            Text(
                text = dateString,
                style = MaterialTheme.typography.labelSmall,
                color = WarmCream.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Clean Summary (L2)
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = WarmCream,
                fontSize = 20.sp
            )

            HairlineDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Raw Transcript (L1)
            Text("Raw Transcript:", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "\"${item.rawText}\"",
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = WarmCream.copy(alpha = 0.7f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}