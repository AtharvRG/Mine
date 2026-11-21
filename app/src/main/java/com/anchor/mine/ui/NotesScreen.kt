// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.anchor.core.models.TopicNote
import com.anchor.core.storage.Storage
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun NotesScreen(
    storage: Storage?,
    onAction: ((com.anchor.core.ui.UiAction) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var notes by remember { mutableStateOf<List<TopicNote>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var noteToEditBullets by remember { mutableStateOf<TopicNote?>(null) }
    val scope = rememberCoroutineScope()
    val json = remember { Json { ignoreUnknownKeys = true } }

    val reloadNotes: () -> Unit = {
        scope.launch {
            if (storage != null) {
                notes = if (searchQuery.isBlank()) {
                    storage.getAllTopicNotes()
                } else {
                    storage.searchTopicNotes(searchQuery)
                }
            }
        }
    }

    LaunchedEffect(storage, searchQuery) {
        reloadNotes()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("notesScreen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Living Notes",
                style = MaterialTheme.typography.headlineMedium
            )
            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.testTag("addNoteButton")
            ) {
                Text("+ New Note")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("notesSearchField"),
            placeholder = { Text("Search living notes...") },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isBlank()) "No notes yet. Create one or let the AI organize your thoughts!" else "No matching notes found.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(notes, key = { it.id }) { note ->
                    TopicNoteCard(
                        note = note,
                        onPinToggle = {
                            val willPin = !note.pinned
                            if (willPin && notes.count { it.pinned && it.id != note.id } >= 3) {
                                android.widget.Toast.makeText(context, "Max 3 notes can be pinned", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                if (onAction != null) {
                                    onAction(com.anchor.core.ui.UiAction.TogglePinTopicNote(note.id))
                                    scope.launch {
                                        kotlinx.coroutines.delay(100)
                                        reloadNotes()
                                    }
                                } else {
                                    scope.launch {
                                        storage?.saveTopicNote(note.copy(pinned = willPin, lastEditedAt = System.currentTimeMillis()))
                                        reloadNotes()
                                    }
                                }
                            }
                        },
                        onDelete = {
                            if (onAction != null) {
                                onAction(com.anchor.core.ui.UiAction.DeleteTopicNote(note.id))
                                scope.launch {
                                    kotlinx.coroutines.delay(100)
                                    reloadNotes()
                                }
                            } else {
                                scope.launch {
                                    storage?.deleteTopicNote(note.id)
                                    reloadNotes()
                                }
                            }
                        },
                        onAddBullet = {
                            noteToEditBullets = note
                        }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateTopicNoteDialog(
            pinnedCount = notes.count { it.pinned },
            onDismiss = { showCreateDialog = false },
            onConfirm = { topic, bullets, pinned ->
                val bulletsList = bullets.lines().map { it.trim().removePrefix("-").removePrefix("*").trim() }.filter { it.isNotBlank() }
                if (onAction != null) {
                    onAction(com.anchor.core.ui.UiAction.CreateTopicNote(topic.trim(), bulletsList, pinned))
                    showCreateDialog = false
                    scope.launch {
                        kotlinx.coroutines.delay(100)
                        reloadNotes()
                    }
                } else {
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val id = "tn_${UUID.randomUUID().toString().take(8)}"
                        val newNote = TopicNote(
                            id = id,
                            topic = topic.trim(),
                            content = bulletsList.joinToString("\n") { "- $it" },
                            bulletsJson = json.encodeToString(bulletsList),
                            pinned = pinned,
                            lastEditedAt = now,
                            createdAt = now
                        )
                        storage?.saveTopicNote(newNote)
                        showCreateDialog = false
                        reloadNotes()
                    }
                }
            }
        )
    }

    noteToEditBullets?.let { targetNote ->
        AddBulletDialog(
            note = targetNote,
            onDismiss = { noteToEditBullets = null },
            onAdd = { newBulletText ->
                if (onAction != null) {
                    onAction(com.anchor.core.ui.UiAction.AddTopicNoteBullet(targetNote.id, newBulletText))
                    noteToEditBullets = null
                    scope.launch {
                        kotlinx.coroutines.delay(100)
                        reloadNotes()
                    }
                } else {
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val existingBullets = try {
                            json.decodeFromString<List<String>>(targetNote.bulletsJson)
                        } catch (_: Exception) {
                            targetNote.content.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotBlank() }
                        }
                        val added = newBulletText.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotBlank() }
                        val merged = (existingBullets + added).distinct()
                        val updated = targetNote.copy(
                            content = merged.joinToString("\n") { "- $it" },
                            bulletsJson = json.encodeToString(merged),
                            lastEditedAt = now
                        )
                        storage?.saveTopicNote(updated)
                        noteToEditBullets = null
                        reloadNotes()
                    }
                }
            }
        )
    }
}

@Composable
private fun TopicNoteCard(
    note: TopicNote,
    onPinToggle: () -> Unit,
    onDelete: () -> Unit,
    onAddBullet: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val json = remember { Json { ignoreUnknownKeys = true } }
    val bullets = remember(note.bulletsJson, note.content) {
        try {
            json.decodeFromString<List<String>>(note.bulletsJson)
        } catch (_: Exception) {
            note.content.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotBlank() }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("noteCard_${note.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (note.pinned) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.pinned) {
                        Text("📌 ", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = note.topic,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = dateFormat.format(Date(note.lastEditedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            bullets.forEach { bullet ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = "• ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = bullet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onAddBullet) {
                    Text("+ Item")
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onPinToggle) {
                    Text(if (note.pinned) "Unpin" else "Pin")
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun CreateTopicNoteDialog(
    pinnedCount: Int = 0,
    onDismiss: () -> Unit,
    onConfirm: (topic: String, bullets: String, pinned: Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var topic by remember { mutableStateOf("") }
    var bullets by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Living Note") },
        text = {
            Column {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Topic (e.g. Packing List, Meeting Notes)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = bullets,
                    onValueChange = { bullets = it },
                    label = { Text("Bullets (one per line)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = pinned,
                        onCheckedChange = { willPin ->
                            if (willPin && pinnedCount >= 3) {
                                android.widget.Toast.makeText(context, "Max 3 notes can be pinned", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                pinned = willPin
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pin to top")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (topic.isNotBlank()) onConfirm(topic, bullets, pinned) },
                enabled = topic.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AddBulletDialog(
    note: TopicNote,
    onDismiss: () -> Unit,
    onAdd: (newBullet: String) -> Unit
) {
    var newBullet by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to '${note.topic}'") },
        text = {
            Column {
                OutlinedTextField(
                    value = newBullet,
                    onValueChange = { newBullet = it },
                    label = { Text("Bullet items (one per line)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (newBullet.isNotBlank()) onAdd(newBullet) },
                enabled = newBullet.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
