// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.app.Application
import com.anchor.core.storage.Storage
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import com.anchor.mine.storage.DbKeyProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MineApp : Application() {
    val sharedStorage = CompletableDeferred<Storage>()

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val key = DbKeyProvider(this@MineApp).getOrGenerateKey()
                val db = DatabaseProvider.getHelper(this@MineApp, key, "mine_encrypted.db")
                sharedStorage.complete(AndroidStorage(db))
            } catch (e: Exception) {
                sharedStorage.completeExceptionally(e)
            }
        }
    }
}
