// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.app.Application
import com.anchor.core.storage.Storage
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import com.anchor.mine.storage.DbKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MineApp : Application() {
    val storage = MutableStateFlow<Storage?>(null)

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            val key = DbKeyProvider(this@MineApp).getOrGenerateKey()
            val db = DatabaseProvider.getHelper(this@MineApp, key)
            storage.value = AndroidStorage(db)
        }
    }
}