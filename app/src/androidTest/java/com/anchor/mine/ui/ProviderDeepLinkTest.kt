// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.anchor.mine.MainActivity
import com.anchor.mine.MineApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderDeepLinkTest {

    @Test
    fun testProviderDeepLink_ImportsConfigViaIntent() {
        val app = ApplicationProvider.getApplicationContext<MineApp>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("mine://provider?endpoint=https%3A%2F%2Fapi.groq.com%2Fopenai%2Fv1&model=llama-3.3-70b-versatile&key=gsk-deep-link")).apply {
            setClass(app, MainActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.onNewIntent(intent)
            }
            val storage = app.sharedStorage
            runBlocking {
                val s = storage.await()
                var attempts = 0
                while (attempts++ < 200 && s.getSetting("mod") != "llama-3.3-70b-versatile") {
                    kotlinx.coroutines.delay(100L)
                }
                assertEquals("https://api.groq.com/openai/v1", s.getSetting("ep"))
                assertEquals("llama-3.3-70b-versatile", s.getSetting("mod"))
                assertEquals("gsk-deep-link", s.getSetting("key"))
            }
        }
    }
}
