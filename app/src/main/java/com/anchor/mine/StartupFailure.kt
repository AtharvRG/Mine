// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

/**
 * H-06 (R-11): the startup path used to catch `Exception` only. A `LinkageError` from a bad
 * Keystore provider, or any `Error`, escaped the catch, so neither deferred was ever completed —
 * `sharedStorage.await()` in `MainViewModel` then suspended forever and the app sat on a blank
 * screen with no banner and no reset path. Every throwable must settle both deferreds.
 */
internal fun startupFailureMessage(e: Throwable): String =
    if (e.message == "KEYSTORE_LOST") "KEYSTORE_LOST" else "Storage failed: ${e.message ?: e.javaClass.simpleName}"

/**
 * Catches `Throwable` so an `Error` cannot leave the deferreds unsettled, but rethrows
 * [kotlinx.coroutines.CancellationException] — a cancelled scope is not a startup failure.
 */
internal inline fun <T> runSettlingStartup(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
