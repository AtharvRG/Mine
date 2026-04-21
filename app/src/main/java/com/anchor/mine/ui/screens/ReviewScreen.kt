package com.anchor.mine.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.anchor.mine.ui.components.EditorialCard
import com.anchor.mine.ui.components.HairlineDivider

@Composable
fun ReviewScreen(onCommitSuccess: () -> Unit) {
    var editableText by remember { mutableStateOf("1. Push the Git hotfix commit.\n2. Pay the electricity bill immediately.") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
            .padding(24.dp)
    ) {
        Text(text = "Consolidated", style = MaterialTheme.typography.displayMedium)
        Text(text = "Memory.", style = MaterialTheme.typography.displayLarge)
        HairlineDivider(modifier = Modifier.padding(vertical = 24.dp))

        EditorialCard(modifier = Modifier.weight(1f).fillMaxWidth()) {
            BasicTextField(
                value = editableText,
                onValueChange = { editableText = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        EditorialCard(
            modifier = Modifier.fillMaxWidth().clickable { onCommitSuccess() },
            backgroundColor = MaterialTheme.colorScheme.onBackground
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Commit to Brain", color = MaterialTheme.colorScheme.background, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}