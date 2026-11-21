// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.anchor.core.export.ExportController
import com.anchor.core.export.ExportManager
import com.anchor.core.export.MIN_PASSPHRASE_LENGTH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * B5: the Backup screen. The rules live in [ExportController] (pure JVM, unit-tested); this
 * composable only supplies what the controller deliberately does not own — the SAF pickers and
 * the `File` they resolve to.
 *
 * [ExportManager] takes a `File`, but SAF hands back a `content://` URI. Export therefore writes
 * to a cache file and copies it to the URI; import copies the URI into a cache file and reads
 * that. The cache copy is deleted in every path, including failure.
 */
@Composable
fun BackupScreen(
    exportManager: ExportManager?,
    storage: com.anchor.core.storage.Storage? = null,
    onAction: ((com.anchor.core.ui.UiAction) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maybeController = remember(exportManager) {
        exportManager?.let { ExportController(it, scope) }
    }
    val state = maybeController?.state?.collectAsState()?.value

    var pending by remember { mutableStateOf<PendingBackup?>(null) }
    var includeApiKeys by remember { mutableStateOf(false) }
    // A SAF URI the user picked for import/verify. The passphrase is asked for after the pick,
    // so the dialog knows which file it is unlocking.
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMarkdownUri by remember { mutableStateOf<Uri?>(null) }
    var showMarkdownWarning by remember { mutableStateOf(false) }
    var autoBackupEnabled by remember { mutableStateOf(false) }
    var autoBackupUri by remember { mutableStateOf<String?>(null) }
    var hasAutoBackupPassphrase by remember { mutableStateOf(false) }
    var showAutoBackupPassphraseDialog by remember { mutableStateOf(false) }
    var autoBackupPassphraseInput by remember { mutableStateOf("") }
    var autoBackupPassphraseError by remember { mutableStateOf<String?>(null) }
    var lastExportTs by remember { mutableStateOf<Long>(0L) }

    LaunchedEffect(storage) {
        if (storage != null) {
            val enabledVal = storage.getSetting("export.auto.enabled")
            autoBackupEnabled = (enabledVal == "1" || enabledVal.equals("true", ignoreCase = true)) || (storage.getSetting("backup.auto_enabled") == "1")
            autoBackupUri = storage.getSetting("export.auto.folder_uri") ?: storage.getSetting("backup.auto_uri")
            hasAutoBackupPassphrase = com.anchor.mine.export.BackupPassphraseVault.getPassphrase(context) != null ||
                !storage.getSetting("export.passphrase").isNullOrBlank() || !storage.getSetting("backup.passphrase").isNullOrBlank()
            lastExportTs = storage.getSetting(com.anchor.core.export.AutoExportScheduler.KEY_LAST_EXPORT_TS)?.toLongOrNull() ?: 0L
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                android.util.Log.w("BackupScreen", "Failed to take persistable URI permission: ${e.message}")
            }
            autoBackupUri = uri.toString()
            onAction?.invoke(com.anchor.core.ui.UiAction.SetAutoBackupUri(uri.toString()))
            scope.launch {
                storage?.saveSetting("export.auto.folder_uri", uri.toString())
                storage?.saveSetting("backup.auto_uri", uri.toString())
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pending = PendingBackup.Export(uri)
    }

    val exportMarkdownLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            pendingMarkdownUri = uri
            showMarkdownWarning = true
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingUri = uri
    }

    if (maybeController == null || state == null) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("=== Backup ===", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Opening the encrypted database…")
        }
        return
    }
    val controller = maybeController

    Column(modifier = Modifier.padding(8.dp)) {
        Text("=== Backup ===", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(
                checked = includeApiKeys,
                onCheckedChange = { includeApiKeys = it },
                modifier = Modifier.testTag("backupIncludeApiKeys")
            )
            Text(" Include API keys in the backup", modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { exportLauncher.launch("mine-backup.mnex") },
                enabled = !state.isBusy,
                modifier = Modifier.testTag("backupExport")
            ) { Text("Export") }
            OutlinedButton(
                onClick = { openLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !state.isBusy,
                modifier = Modifier.testTag("backupImport")
            ) { Text("Import") }
            if (storage != null) {
                OutlinedButton(
                    onClick = { exportMarkdownLauncher.launch("mine-export.md") },
                    enabled = !state.isBusy,
                    modifier = Modifier.testTag("backupExportMarkdown")
                ) { Text("Export Markdown") }
            }
        }

        if (state.isBusy) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp).testTag("backupBusy"))
                Text(" Working…", modifier = Modifier.padding(start = 8.dp))
            }
        }

        state.result?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result,
                color = if (state.resultIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("backupResult")
            )
        }

        if (state.requiresRestart) {
            Text(
                "The imported data is on disk. Close and reopen Mine to use it.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp).testTag("backupRestart")
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("=== Automatic Daily Backup ===", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Daily Auto-Backup", style = MaterialTheme.typography.titleSmall)
                Text("Automatically create an encrypted backup once a day in background", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = autoBackupEnabled,
                onCheckedChange = { checked ->
                    autoBackupEnabled = checked
                    onAction?.invoke(com.anchor.core.ui.UiAction.SetAutoBackup(checked))
                    scope.launch {
                        storage?.saveSetting("export.auto.enabled", if (checked) "true" else "false")
                        storage?.saveSetting("backup.auto_enabled", if (checked) "1" else "0")
                    }
                    if (checked && !hasAutoBackupPassphrase) {
                        showAutoBackupPassphraseDialog = true
                    }
                },
                modifier = Modifier.testTag("autoBackupSwitch")
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-Backup Folder", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = autoBackupUri ?: "No folder selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = { folderLauncher.launch(null) },
                modifier = Modifier.testTag("pickAutoBackupFolderBtn")
            ) {
                Text("Select Folder")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-Backup Encryption", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (hasAutoBackupPassphrase) "Passphrase configured" else "Passphrase required (AES-GCM)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasAutoBackupPassphrase) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
            OutlinedButton(
                onClick = {
                    autoBackupPassphraseInput = ""
                    autoBackupPassphraseError = null
                    showAutoBackupPassphraseDialog = true
                },
                modifier = Modifier.testTag("setAutoBackupPassphraseBtn")
            ) {
                Text(if (hasAutoBackupPassphrase) "Change Passphrase" else "Set Passphrase")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Last Backup Status", style = MaterialTheme.typography.titleSmall)
                val statusText = if (lastExportTs > 0L) {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    "Last successful backup: ${sdf.format(java.util.Date(lastExportTs))}"
                } else {
                    "No automatic backups recorded yet"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lastExportTs > 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("lastBackupStatusLabel")
                )
            }
        }
    }

    if (showAutoBackupPassphraseDialog && storage != null) {
        AlertDialog(
            onDismissRequest = {
                showAutoBackupPassphraseDialog = false
                autoBackupPassphraseInput = ""
                autoBackupPassphraseError = null
            },
            title = { Text("Set Auto-Backup Passphrase") },
            text = {
                Column {
                    Text(
                        "Auto-backups are encrypted with this passphrase. You will need it to restore your backups if you switch devices.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = autoBackupPassphraseInput,
                        onValueChange = {
                            autoBackupPassphraseInput = it
                            if (it.length >= MIN_PASSPHRASE_LENGTH) autoBackupPassphraseError = null
                        },
                        label = { Text("Passphrase (min $MIN_PASSPHRASE_LENGTH chars)") },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = autoBackupPassphraseError != null,
                        supportingText = autoBackupPassphraseError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                        modifier = Modifier.fillMaxWidth().testTag("autoBackupPassphraseInput")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (autoBackupPassphraseInput.length < MIN_PASSPHRASE_LENGTH) {
                            autoBackupPassphraseError = "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
                        } else {
                            scope.launch {
                                // P1-02 (Patch G): Encrypt auto-backup passphrase into hardware-backed Keystore vault
                                com.anchor.mine.export.BackupPassphraseVault.savePassphrase(context, autoBackupPassphraseInput)
                                storage.saveSetting("export.has_passphrase", "1")
                                storage.saveSetting("backup.has_passphrase", "1")
                                hasAutoBackupPassphrase = true
                                showAutoBackupPassphraseDialog = false
                                autoBackupPassphraseInput = ""
                            }
                        }
                    },
                    modifier = Modifier.testTag("saveAutoBackupPassphraseBtn")
                ) {
                    Text("Save Passphrase")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAutoBackupPassphraseDialog = false
                    autoBackupPassphraseInput = ""
                    autoBackupPassphraseError = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showMarkdownWarning && pendingMarkdownUri != null && storage != null) {
        val targetUri = pendingMarkdownUri!!
        AlertDialog(
            onDismissRequest = {
                showMarkdownWarning = false
                pendingMarkdownUri = null
            },
            title = { Text("Export Unencrypted Markdown?") },
            text = {
                Text(
                    "⚠️ WARNING: This will export all your notes, habits, soul facts, and chat history as plain, readable text WITHOUT encryption. Anyone with access to this file will be able to read it.\n\nThis export cannot be re-imported into Mine.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMarkdownWarning = false
                        pendingMarkdownUri = null
                        scope.launch {
                            withTempFile(context, ".md") { tmp ->
                                if (!controller.runExportMarkdown(tmp, storage)) return@withTempFile
                                try {
                                    copyToUri(context, tmp, targetUri)
                                } catch (e: Exception) {
                                    controller.reportFailure(
                                        "Could not write Markdown file: " + (e.message ?: e::class.java.simpleName)
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("backupConfirmMarkdown")
                ) {
                    Text("Export Plain Text", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMarkdownWarning = false
                    pendingMarkdownUri = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Passphrase dialog, requested after a file is chosen.
    val target = pending ?: pendingUri?.let { PendingBackup.Import(it) }
    if (target != null) {
        PassphraseDialog(
            title = when (target) {
                is PendingBackup.Export -> "Passphrase for the backup"
                is PendingBackup.Import -> "Passphrase for this file"
            },
            onDismiss = { pending = null; pendingUri = null },
            onConfirm = { passphrase, confirmed ->
                pending = null
                pendingUri = null
                scope.launch {
                    when (target) {
                        is PendingBackup.Export -> {
                            val picked = target.uri
                            withTempFile(context, ".mnex") { tmp ->
                                if (!controller.runExport(tmp, passphrase, includeApiKeys)) return@withTempFile
                                try {
                                    copyToUri(context, tmp, picked)
                                } catch (e: Exception) {
                                    controller.reportFailure(
                                        "The backup was written but could not be saved to the chosen file: " +
                                            (e.message ?: e::class.java.simpleName)
                                    )
                                }
                            }
                        }
                        is PendingBackup.Import -> {
                            val picked = target.uri
                            withTempFile(context, ".mnex") { tmp ->
                                try {
                                    copyFromUri(context, picked, tmp)
                                } catch (e: Exception) {
                                    controller.reportFailure(
                                        "Could not read the chosen file: " + (e.message ?: e::class.java.simpleName)
                                    )
                                    return@withTempFile
                                }
                                if (confirmed) {
                                    val ok = controller.runImport(tmp, passphrase)
                                    if (ok) {
                                        onAction?.invoke(com.anchor.core.ui.UiAction.ReloadStorage)
                                    }
                                } else {
                                    controller.runVerify(tmp, passphrase)
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

private sealed class PendingBackup {
    data class Export(val uri: Uri) : PendingBackup()
    data class Import(val uri: Uri) : PendingBackup()
}

/**
 * Runs [block] on a cache file and deletes it afterwards. On export the file starts empty; on
 * import [block] fills it from the picked URI.
 */
private suspend fun withTempFile(context: Context, suffix: String, block: suspend (File) -> Unit) {
    val tmp = withContext(Dispatchers.IO) {
        File.createTempFile("mine_backup_", suffix, context.cacheDir)
    }
    try {
        block(tmp)
    } finally {
        withContext(Dispatchers.IO) { tmp.delete() }
    }
}

private suspend fun copyToUri(context: Context, source: File, uri: Uri) = withContext(Dispatchers.IO) {
    val out = context.contentResolver.openOutputStream(uri, "wt")
        ?: throw java.io.IOException("Could not open the chosen file for writing")
    out.use { dest -> source.inputStream().use { it.copyTo(dest) } }
}

private suspend fun copyFromUri(context: Context, uri: Uri, dest: File) = withContext(Dispatchers.IO) {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw java.io.IOException("Could not open the chosen file for reading")
    input.use { src -> dest.outputStream().use { src.copyTo(it) } }
}

/**
 * Two fields: the passphrase and, for import, a confirm toggle that decides import vs verify.
 * The length rule comes from [ExportController.validatePassphrase] so the UI and the flow cannot
 * disagree about what a valid passphrase is.
 */
@Composable
internal fun PassphraseDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (passphrase: String, importNotVerify: Boolean) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmImport by remember { mutableStateOf(true) }
    val error = if (passphrase.isEmpty()) null
    else if (passphrase.length < MIN_PASSPHRASE_LENGTH) "At least $MIN_PASSPHRASE_LENGTH characters."
    else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth().testTag("backupPassphrase")
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(
                        checked = confirmImport,
                        onCheckedChange = { confirmImport = it },
                        modifier = Modifier.testTag("backupModeSwitch")
                    )
                    Text(
                        if (confirmImport) " Import (replaces this app's data)" else " Verify only",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase, confirmImport) },
                enabled = error == null && passphrase.isNotEmpty(),
                modifier = Modifier.testTag("backupConfirm")
            ) { Text(if (confirmImport) "Import" else "Verify") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
