// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.retrieval

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anchor.core.retrieval.Embedder
import com.anchor.core.retrieval.HybridRetriever
import com.anchor.core.sensing.ContextEngine
import com.anchor.core.sensing.MotionAxis
import com.anchor.core.sensing.Place
import com.anchor.core.sensing.PlaceSignal
import com.anchor.core.time.FakeClock
import com.anchor.mine.sensing.ContextSensingManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbedderAndSensingDeviceTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun testLiteRtEmbedder_SemanticSimilarity_HighForSynonyms_LowForUnrelated() = runBlocking {
        val embedder = LiteRtEmbedder(context)

        val docText1 = "doctor appointment scheduled for tomorrow morning"
        val docText2 = "physician annual review and clinic checkup"
        val unrelatedText = "irs tax filing deadline and accounting audit"

        val vectors = embedder.embed(listOf(docText1, docText2, unrelatedText), Embedder.Kind.DOCUMENT)
        val vDoc1 = vectors[0]
        val vDoc2 = vectors[1]
        val vUnrelated = vectors[2]

        val simRelated = HybridRetriever.cosineSimilarity(vDoc1, vDoc2)
        val simUnrelated = HybridRetriever.cosineSimilarity(vDoc1, vUnrelated)

        assertTrue(
            "Synonyms (doctor vs physician) must have high cosine similarity (got $simRelated)",
            simRelated >= 0.70f
        )
        assertTrue(
            "Unrelated domains (doctor vs tax) must have low cosine similarity (got $simUnrelated)",
            simUnrelated < 0.35f
        )
    }

    @Test
    fun testContextEngine_FormatNowContext_AndVehicleHold() {
        val clock = FakeClock(1_000_000L)
        val engine = ContextEngine(clock)

        val homePlace = Place(id = "p1", kind = "home", label = "Home", updatedAt = 0L)
        val homeSignal = PlaceSignal(placeId = "p1", wifiJson = "{\"ssid\":\"HomeSweetHome\"}")
        val knownPlaces = listOf(homePlace to homeSignal)

        // 1. Send Home Wi-Fi signal
        engine.onWifiSignal("HomeSweetHome", listOf("AA:BB:CC:DD:EE:FF"), knownPlaces, tsUtc = 1_000_000L)
        // Advance past debounce
        clock.currentTime += 3 * 60_000L
        engine.checkTimeouts(clock.now())

        val nowContext = engine.formatNowContext(java.util.TimeZone.getTimeZone("UTC"), clock.now())
        assertNotNull("Context line must not be null when Home is active", nowContext)
        assertTrue("Context line must mention Home (got '$nowContext')", nowContext!!.contains("at Home"))

        // 2. Motion signal: vehicle
        engine.onMotionSignal(MotionAxis.VEHICLE, confidence = 1.0f, tsUtc = clock.now())
        clock.currentTime += 60_000L
        engine.checkTimeouts(clock.now())

        assertTrue("Vehicle hold must be active during vehicle motion", engine.isVehicleHoldActive())
    }

    @Test
    fun testContextSensingManager_Lifecycle() {
        val clock = FakeClock(1_000_000L)
        val engine = ContextEngine(clock)
        val sensingMgr = ContextSensingManager(
            context = context,
            contextEngine = engine,
            storageProvider = { null }
        )

        sensingMgr.start()
        // Must safely start without throwing exceptions
        sensingMgr.stop()
    }
}
