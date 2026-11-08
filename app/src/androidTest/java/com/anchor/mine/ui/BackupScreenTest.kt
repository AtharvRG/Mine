// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.anchor.core.export.ExportManager
import com.anchor.core.export.ExportStats
import com.anchor.mine.BackupScreen
import com.anchor.mine.PassphraseDialog
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * B5: the Backup screen must not offer an action it cannot complete, and it must never accept a
 * passphrase the backend will reject. The flow rules themselves are unit-tested in core
 * (ExportControllerTest); this covers what the user can actually touch.
 */
class BackupScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val noop = object : ExportManager {
        override suspend fun exportTo(file: File, passphrase: String, includeApiKeys: Boolean, includeEmbeddings: Boolean) =
            ExportStats(1, 1, 1, verified = true)
        override suspend fun verifyOrImport(file: File, passphrase: String, isImport: Boolean) =
            ExportStats(1, 1, 1, verified = true)
    }

    @Test
    fun testBackupScreen_WithoutAManager_SaysTheDatabaseIsStillOpening() {
        compose.setContent { BackupScreen(exportManager = null) }

        compose.onNodeWithText("Opening the encrypted database…").assertIsDisplayed()
        // Offering a button here would be a lie: the backend does not exist yet.
        compose.onNodeWithTag("backupExport").assertDoesNotExist()
    }

    @Test
    fun testBackupScreen_WithAManager_OffersExportAndImport() {
        compose.setContent { BackupScreen(noop) }

        compose.onNodeWithTag("backupExport").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("backupImport").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun testPassphraseDialog_ShortPassphrase_BlocksConfirm() {
        compose.setContent { PassphraseDialog("Passphrase", onDismiss = {}, onConfirm = { _, _ -> }) }

        compose.onNodeWithTag("backupConfirm").assertIsNotEnabled()
        compose.onNodeWithTag("backupPassphrase").performTextReplacement("1234567")
        compose.onNodeWithTag("backupConfirm").assertIsNotEnabled()
        compose.onNodeWithText("At least 8 characters.").assertIsDisplayed()
    }

    @Test
    fun testPassphraseDialog_ValidPassphrase_EnablesConfirmAndReportsTheChosenMode() {
        var confirmed: Pair<String, Boolean>? = null
        compose.setContent {
            PassphraseDialog("Passphrase", onDismiss = {}, onConfirm = { p, imp -> confirmed = p to imp })
        }

        compose.onNodeWithTag("backupPassphrase").performTextReplacement("correct horse")
        compose.onNodeWithTag("backupConfirm").assertIsEnabled().performClick()

        // Import is the default: a user who picked a file intends to restore it.
        org.junit.Assert.assertEquals("correct horse" to true, confirmed)
    }

    @Test
    fun testPassphraseDialog_ModeSwitch_TurnsImportIntoVerifyOnly() {
        var confirmed: Pair<String, Boolean>? = null
        compose.setContent {
            PassphraseDialog("Passphrase", onDismiss = {}, onConfirm = { p, imp -> confirmed = p to imp })
        }

        compose.onNodeWithText(" Import (replaces this app's data)").assertIsDisplayed()
        compose.onNodeWithTag("backupModeSwitch").performClick()
        compose.onNodeWithText(" Verify only").assertIsDisplayed()

        compose.onNodeWithTag("backupPassphrase").performTextReplacement("correct horse")
        compose.onNodeWithTag("backupConfirm").performClick()

        org.junit.Assert.assertEquals("correct horse" to false, confirmed)
    }
}
