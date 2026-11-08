// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Minimum passphrase length. Mirrors AndroidExportManager.exportTo's own check. */
const val MIN_PASSPHRASE_LENGTH = 8

data class BackupState(
    val isBusy: Boolean = false,
    /** Last completed operation's outcome, shown until the next one starts. */
    val result: String? = null,
    val resultIsError: Boolean = false,
    /** True once an import has swapped the database; the process must restart. */
    val requiresRestart: Boolean = false
)

/**
 * B5: the export/import flow behind the Backup screen. It owns no file pickers — the caller
 * supplies the [File] it obtained from SAF — so the whole flow is testable on the JVM.
 */
class ExportController(
    private val exportManager: ExportManager,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private var job: Job? = null

    /** Passphrase rules are checked before touching the file, so a bad passphrase costs nothing. */
    fun validatePassphrase(passphrase: String): String? =
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
            "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters."
        } else null

    fun export(file: File, passphrase: String, includeApiKeys: Boolean) {
        validatePassphrase(passphrase)?.let { return finish(it, isError = true) }
        start {
            val stats = exportManager.exportTo(file, passphrase, includeApiKeys, includeEmbeddings = false)
            "Exported ${stats.messages} messages, ${stats.nodes} items, ${stats.events} events. Verified."
        }
    }

    fun import(file: File, passphrase: String) {
        validatePassphrase(passphrase)?.let { return finish(it, isError = true) }
        start {
            val stats = exportManager.verifyOrImport(file, passphrase, isImport = true)
            _state.update { it.copy(requiresRestart = true) }
            "Imported ${stats.messages} messages, ${stats.nodes} items, ${stats.events} events. Restart the app to load the imported data."
        }
    }

    /** Verify only: decrypts and checks the footer hash without touching the live database. */
    fun verify(file: File, passphrase: String) {
        validatePassphrase(passphrase)?.let { return finish(it, isError = true) }
        start {
            val stats = exportManager.verifyOrImport(file, passphrase, isImport = false)
            "File is valid: ${stats.messages} messages, ${stats.nodes} items, ${stats.events} events."
        }
    }

    /**
     * The same three flows, but suspending until the operation settles and returning whether it
     * succeeded. Callers that must act on the result — e.g. copy a finished export to a SAF URI
     * and then delete the temp file — need this; polling [state] races the cleanup.
     */
    suspend fun runExport(file: File, passphrase: String, includeApiKeys: Boolean): Boolean =
        run { export(file, passphrase, includeApiKeys) }

    suspend fun runImport(file: File, passphrase: String): Boolean =
        run { import(file, passphrase) }

    suspend fun runVerify(file: File, passphrase: String): Boolean =
        run { verify(file, passphrase) }

    private suspend fun run(started: () -> Unit): Boolean {
        started()
        job?.join()
        return !_state.value.resultIsError
    }

    /** For failures outside the manager (SAF read/write), so they land in the same result line. */
    fun reportFailure(message: String) = finish(message, isError = true)

    private fun start(block: suspend () -> String) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, result = null, resultIsError = false) }
        job = scope.launch {
            try {
                finish(block(), isError = false)
            } catch (e: Exception) {
                // A wrong passphrase surfaces as AEADBadTagException → SecurityException. The
                // message is the only thing the user can act on, so show it verbatim.
                finish(e.message ?: e::class.java.simpleName, isError = true)
            }
        }
    }

    private fun finish(message: String, isError: Boolean) {
        _state.update { it.copy(isBusy = false, result = message, resultIsError = isError) }
    }
}
