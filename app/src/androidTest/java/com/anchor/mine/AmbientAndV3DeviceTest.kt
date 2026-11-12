// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.ambient.AmbientProposalExtractor
import com.anchor.core.ambient.AmbientReport
import com.anchor.core.ambient.AmbientReportItem
import com.anchor.core.ambient.AmbientSegment
import com.anchor.core.ambient.AmbientSession
import com.anchor.core.ambient.AmbientWindow
import com.anchor.core.ambient.DialogueWindowEngine
import com.anchor.core.ambient.PresenceGate
import com.anchor.core.ambient.SpeakerLabel
import com.anchor.core.ambient.Voiceprint
import com.anchor.core.consolidation.ConsolidationEngine
import com.anchor.core.consolidation.DayDigest
import com.anchor.core.media.AttachmentRef
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
import com.anchor.core.models.SoulFact
import com.anchor.core.voice.SyntheticAudioFixtures
import com.anchor.mine.alarms.NotificationChannels
import com.anchor.mine.export.AndroidExportManager
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import com.anchor.mine.storage.MineDatabaseCallback
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Connected instrumentation test on physical device verifying the v3 Ambient Architecture,
 * Database Schema v12, Zero-Vibration Notification Invariant, Synthetic Audio Acoustic Pipeline,
 * Biometric Storage Round-trip, and Data Purging.
 */
@RunWith(AndroidJUnit4::class)
class AmbientAndV3DeviceTest {

    private lateinit var storage: AndroidStorage
    private lateinit var dbHelper: androidx.sqlite.db.SupportSQLiteOpenHelper
    private val dbName = "mine_v3_device_test.db"

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        val key = ByteArray(32) { 3 }
        dbHelper = DatabaseProvider.getHelper(context, key, dbName)
        storage = AndroidStorage(dbHelper)
    }

    @After
    fun teardown() {
        dbHelper.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
    }

    /**
     * Test 1: V11 -> V12 migration creates all 7 tables and 3 indexes.
     */
    @Test
    fun testV11ToV12Migration_CreatesAllTablesAndIndexes() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val migDbName = "mine_v11_v12_migration_test.db"
            context.deleteDatabase(migDbName)
            val key = ByteArray(32) { 12 }

            val helper = DatabaseProvider.getHelper(context, key, migDbName)
            val db = helper.writableDatabase

            // Drop V12 objects to simulate V11 state
            db.execSQL("DROP INDEX IF EXISTS idx_ambient_segment_window")
            db.execSQL("DROP INDEX IF EXISTS idx_ambient_segment_expires")
            db.execSQL("DROP INDEX IF EXISTS idx_ambient_report_item_report")
            db.execSQL("DROP TABLE IF EXISTS day_digest")
            db.execSQL("DROP TABLE IF EXISTS ambient_report_item")
            db.execSQL("DROP TABLE IF EXISTS ambient_report")
            db.execSQL("DROP TABLE IF EXISTS ambient_segment")
            db.execSQL("DROP TABLE IF EXISTS ambient_window")
            db.execSQL("DROP TABLE IF EXISTS ambient_session")
            db.execSQL("DROP TABLE IF EXISTS voiceprint")

            // Run onUpgrade 11 -> 12
            MineDatabaseCallback().onUpgrade(db, 11, 12)

            // Verify tables exist
            val expectedTables = setOf(
                "voiceprint",
                "ambient_session",
                "ambient_window",
                "ambient_segment",
                "ambient_report",
                "ambient_report_item",
                "day_digest"
            )
            val createdTables = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    if (name in expectedTables) createdTables.add(name)
                }
            }
            assertEquals("All 7 V12 tables must be created", expectedTables, createdTables)

            // Verify indexes exist
            val expectedIndexes = setOf(
                "idx_ambient_segment_window",
                "idx_ambient_segment_expires",
                "idx_ambient_report_item_report"
            )
            val createdIndexes = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='index'").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    if (name in expectedIndexes) createdIndexes.add(name)
                }
            }
            assertEquals("All 3 V12 indexes must be created", expectedIndexes, createdIndexes)

            helper.close()
            context.deleteDatabase(migDbName)
        }
    }

    /**
     * Test 2: Voiceprint storage roundtrip (float serialization & SQLCipher BLOB encryption).
     */
    @Test
    fun testVoiceprintStorageRoundTrip() {
        runBlocking {
            val testEmbed = FloatArray(192) { (it + 1) * 0.005f }
            val voiceprint = Voiceprint.fromFloatArray(
                id = "user_primary",
                floats = testEmbed,
                modelRef = "sherpa_campplus",
                createdAt = 1000L
            )

            storage.saveVoiceprint(voiceprint)
            val fetched = storage.getVoiceprint()
            assertNotNull(fetched)
            assertEquals("user_primary", fetched?.id)
            assertEquals("sherpa_campplus", fetched?.modelRef)

            val fetchedFloats = fetched!!.toFloatArray()
            assertEquals(192, fetchedFloats.size)

            for (i in 0 until 192) {
                assertEquals(testEmbed[i], fetchedFloats[i], 1e-6f)
            }

            // Delete voiceprint
            storage.deleteVoiceprint()
            assertNull(storage.getVoiceprint())
        }
    }

    /**
     * Test 3: Zero Vibration Invariant across all Notification Channels.
     */
    @Test
    fun testNotificationChannelsZeroVibration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NotificationChannels.ensureCreated(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val ambientChannel = manager.getNotificationChannel(NotificationChannels.AMBIENT)
        assertNotNull("AMBIENT channel must exist", ambientChannel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ambientChannel.importance)
        assertFalse("AMBIENT channel must NOT vibrate", ambientChannel.shouldVibrate())
        assertNull("AMBIENT channel vibrationPattern must be null", ambientChannel.vibrationPattern)

        val heartbeatChannel = manager.getNotificationChannel(NotificationChannels.AMBIENT_HEARTBEAT)
        assertNotNull("AMBIENT_HEARTBEAT channel must exist", heartbeatChannel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, heartbeatChannel.importance)
        assertFalse("AMBIENT_HEARTBEAT channel must NOT vibrate", heartbeatChannel.shouldVibrate())
        assertNull("AMBIENT_HEARTBEAT channel vibrationPattern must be null", heartbeatChannel.vibrationPattern)

        val alarmChannel = manager.getNotificationChannel(NotificationChannels.ALARM)
        assertNotNull("ALARM channel must exist", alarmChannel)
        assertFalse("ALARM channel must NOT vibrate", alarmChannel.shouldVibrate())
        assertNull("ALARM channel vibrationPattern must be null", alarmChannel.vibrationPattern)

        val gentleChannel = manager.getNotificationChannel(NotificationChannels.GENTLE)
        assertNotNull("GENTLE channel must exist", gentleChannel)
        assertFalse("GENTLE channel must NOT vibrate", gentleChannel.shouldVibrate())
        assertNull("GENTLE channel vibrationPattern must be null", gentleChannel.vibrationPattern)

        val nudgeChannel = manager.getNotificationChannel(NotificationChannels.CALL_NUDGE)
        assertNotNull("CALL_NUDGE channel must exist", nudgeChannel)
        assertFalse("CALL_NUDGE channel must NOT vibrate", nudgeChannel.shouldVibrate())
        assertNull("CALL_NUDGE channel vibrationPattern must be null", nudgeChannel.vibrationPattern)
    }

    /**
     * Test 4: Synthetic Acoustic Pipeline & Presence Gate.
     */
    @Test
    fun testSyntheticAudioVadAndPresenceGate() {
        runBlocking {
        // Deterministic synthetic audio generation
        val tone = SyntheticAudioFixtures.generateTone(frequencyHz = 440f, durationSec = 1.0f)
        val speech = SyntheticAudioFixtures.generateSpeechLikeFormants(durationSec = 1.5f, f0 = 130f)
        val silence = SyntheticAudioFixtures.generateSilence(durationSec = 1.0f)

        // RMS check
        val toneRms = SyntheticAudioFixtures.computeRmsEnergy(tone)
        val speechRms = SyntheticAudioFixtures.computeRmsEnergy(speech)
        val silenceRms = SyntheticAudioFixtures.computeRmsEnergy(silence)

        assertTrue("Tone RMS should be audible", toneRms > 0.1f)
        assertTrue("Speech burst RMS should be audible", speechRms > 0.05f)
        assertEquals("Silence RMS must be 0", 0f, silenceRms, 1e-6f)

        // Embeddings and Cosine similarity
        val userEmb1 = SyntheticAudioFixtures.generateSyntheticVoiceprint(192, seed = 42L)
        val userEmb2 = SyntheticAudioFixtures.generateSyntheticVoiceprint(192, seed = 42L)
        val strangerEmb = SyntheticAudioFixtures.generateSyntheticVoiceprint(192, seed = 999L)

        val selfSim = SyntheticAudioFixtures.cosineSimilarity(userEmb1, userEmb2)
        val strangerSim = SyntheticAudioFixtures.cosineSimilarity(userEmb1, strangerEmb)

        assertTrue("Self similarity should be ~ 1.0", selfSim > 0.99f)
        assertTrue("Stranger similarity should be < 0.60", strangerSim < 0.60f)

        // Presence Gate evaluation
        val gate = PresenceGate()
        val scoredUser = gate.scoreSegment(1000L, 3000L, speech, userEmb1, userEmb2)
        val scoredStranger = gate.scoreSegment(1000L, 3000L, speech, strangerEmb, userEmb1)

        assertTrue("User segment should be USER label", scoredUser.label == SpeakerLabel.USER)
        assertEquals("Stranger segment should be OTHER label", SpeakerLabel.OTHER, scoredStranger.label)
        assertTrue("Window with user segment should open gate", gate.evaluateWindowGate(listOf(scoredUser)))
        assertFalse("Window with only stranger should NOT open gate", gate.evaluateWindowGate(listOf(scoredStranger)))

        // Window and Proposal Extractor
        val userTurns = listOf(
            DialogueWindowEngine.Turn(
                tStartMs = 1000L,
                tEndMs = 3000L,
                label = SpeakerLabel.USER,
                text = "remind me to check the oven",
                score = 0.85f,
                isShort = false
            )
        )
        val window = DialogueWindowEngine.Window("win_1", userTurns, 1000L, 3000L)

        val extractor = AmbientProposalExtractor()
        val items = extractor.extract(window, null, emptyList(), 3000L)

        assertFalse("Extractor should find proposed items", items.isEmpty())
        val proposalItem = items.firstOrNull { it.kind == "proposal" }
        assertNotNull("Should extract a proposal", proposalItem)
        assertEquals("proposed", proposalItem?.proposedNode?.status)
        assertEquals("ambient", proposalItem?.proposedNode?.origin)
        }
    }

    /**
     * Test 5: Prune Staging (48h TTL) and Purge All Ambient Data.
     */
    @Test
    fun testPruneStagingAndPurgeAllAmbientData() {
        runBlocking {
        val now = 100_000_000L

        // Save session, window, and segments
        val session = AmbientSession("sess_1", now - 200_000L, now)
        val window = AmbientWindow("win_1", "sess_1", now - 100_000L, now - 50_000L)
        val expiredSegment = AmbientSegment(
            id = "seg_exp",
            windowId = "win_1",
            tStart = now - 100_000L,
            tEnd = now - 95_000L,
            label = "user",
            score = 0.9f,
            isShort = false,
            text = "[number] test",
            sttRef = null,
            expiresAt = now - 1000L
        )
        val activeSegment = AmbientSegment(
            id = "seg_act",
            windowId = "win_1",
            tStart = now - 50_000L,
            tEnd = now - 45_000L,
            label = "user",
            score = 0.9f,
            isShort = false,
            text = "keep me",
            sttRef = null,
            expiresAt = now + 100_000L
        )

        storage.saveAmbientSession(session)
        storage.saveAmbientWindow(window)
        storage.saveAmbientSegment(expiredSegment)
        storage.saveAmbientSegment(activeSegment)

        // Save report & item
        val report = AmbientReport("rep_1", "2026-11-12", now, "pending")
        val item = AmbientReportItem("item_1", "rep_1", "win_1", "proposal", "Buy milk", null, null, "pending", null)
        storage.saveAmbientReport(report)
        storage.saveAmbientReportItem(item)

        // Save voiceprint
        storage.saveVoiceprint(Voiceprint.fromFloatArray("user_primary", FloatArray(192) { 0.1f }, "campplus", now))

        // Prune expired staging
        val pruned = storage.pruneAmbientStaging(now)
        assertEquals(1, pruned) // Expired segment pruned

        // Verify active segment remains
        val remainingSegs = storage.getAmbientSegments("win_1")
        assertEquals(1, remainingSegs.size)
        assertEquals("seg_act", remainingSegs[0].id)

        // Purge ALL ambient data
        storage.deleteAllAmbientData()

        assertNull("Voiceprint must be deleted", storage.getVoiceprint())
        assertNull("Report must be deleted", storage.getLatestAmbientReport())
        assertEquals(0, storage.getAmbientReportItems("rep_1").size)
        assertEquals(0, storage.getAmbientSegments("win_1").size)
        }
    }

    /**
     * Test 6: Board View, Calendar Buckets, and Overnight Consolidation.
     */
    @Test
    fun testBoardViewCalendarAndConsolidation() {
        runBlocking {
        val now = 1700000000000L

        val todoNode = Node(id = "n_todo", type = "task", title = "Write report", status = "active", dueAt = now + 3600_000L)
        val doneNode = Node(id = "n_done", type = "task", title = "Morning run", status = "done", closedAt = now - 1000L)
        val proposedNode = Node(id = "n_prop", type = "task", title = "Doctor visit", status = "proposed")

        storage.createNodeAndCommit(todoNode, RawEvent("e_1", "CREATE", "node", "n_todo"), null)
        storage.createNodeAndCommit(doneNode, RawEvent("e_2", "CREATE", "node", "n_done"), null)
        storage.createNodeAndCommit(proposedNode, RawEvent("e_3", "CREATE", "node", "n_prop"), null)

        // Board view query
        val board = storage.getBoardViewNodes()
        assertTrue("Board must contain 'todo'", board.containsKey("todo"))
        assertTrue("Board must contain 'in_progress'", board.containsKey("in_progress"))
        assertTrue("Board must contain 'done'", board.containsKey("done"))
        assertTrue(board["todo"]!!.any { it.id == "n_todo" })
        assertTrue(board["done"]!!.any { it.id == "n_done" })

        // Calendar buckets query
        val buckets = storage.getCalendarBuckets(now - 10000L, now + 10_000_000L)
        assertFalse("Calendar buckets should not be empty", buckets.isEmpty())

        // Overnight Consolidation calculation
        val engine = ConsolidationEngine()
        val digest = engine.consolidateDay("2026-11-12", listOf(doneNode), listOf(todoNode), nowMs = now)
        assertEquals(1, digest.completedCount)
        assertEquals(1, digest.openCount)

        storage.saveDayDigest(digest)
        val fetchedDigest = storage.getDayDigest("2026-11-12")
        assertNotNull(fetchedDigest)
        assertEquals(1, fetchedDigest?.completedCount)

        // AttachmentRef serialization
        val ref = AttachmentRef(
            uri = "content://media/audio/1",
            mimeType = "audio/mp4",
            sizeBytes = 1024L,
            durationMs = 5000L,
            sha256 = "sha256abc",
            createdAt = now
        )
        val jsonStr = ref.toJson()
        val parsed = AttachmentRef.fromJson(jsonStr)
        assertEquals("content://media/audio/1", parsed?.uri)
        assertEquals(5000L, parsed?.durationMs)
        }
    }

    /**
     * Test 7: Privacy & Biometric Exclusions from Export Allowlist.
     */
    @Test
    fun testPrivacyExportExclusion() {
        val exported = AndroidExportManager.EXPORTED_TABLES

        assertFalse("voiceprint must NEVER be exported", exported.contains("voiceprint"))
        assertFalse("ambient_session must NEVER be exported", exported.contains("ambient_session"))
        assertFalse("ambient_window must NEVER be exported", exported.contains("ambient_window"))
        assertFalse("ambient_segment must NEVER be exported", exported.contains("ambient_segment"))
        assertFalse("ambient_report must NEVER be exported", exported.contains("ambient_report"))
        assertFalse("ambient_report_item must NEVER be exported", exported.contains("ambient_report_item"))
    }
}
