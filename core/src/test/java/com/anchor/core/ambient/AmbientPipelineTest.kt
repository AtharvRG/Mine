// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ambient

import com.anchor.core.voice.SyntheticAudioFixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AmbientPipelineTest {

    private var n = 0
    private fun seg(start: Long, ms: Long, label: SpeakerLabel, score: Float = if (label == SpeakerLabel.USER) 0.8f else 0.2f) =
        AmbientPipeline.Segment("s${n++}", start, start + ms, FloatArray((ms * 16).toInt()), label, score, ms < 1500)

    // ---- presence gate -------------------------------------------------------------------

    @Test fun aTvAloneNeverReachesSpeechToText() {
        val p = AmbientPipeline(AmbientPipeline.Config(windowGapMs = 5_000))
        var t = 0L
        repeat(10) { p.add(seg(t, 3_000, SpeakerLabel.OTHER)); t += 3_500 }
        assertTrue(p.tick(t + 10_000).isEmpty())
        assertEquals(1, p.stats.windowsDropped)
        assertEquals(0, p.stats.windowsTranscribed)
    }

    @Test fun theOtherTurnBeforeTheUserSpeaksIsIncluded() {
        val p = AmbientPipeline(AmbientPipeline.Config(windowGapMs = 5_000))
        p.add(seg(0, 2_000, SpeakerLabel.OTHER))      // "can you send it by friday?"
        p.add(seg(2_500, 1_000, SpeakerLabel.USER))   // "yes"
        val w = p.tick(20_000).single()
        assertEquals(listOf(SpeakerLabel.OTHER, SpeakerLabel.USER), w.segments.map { it.label })
    }

    @Test fun aWeakUserMatchDoesNotOpenTheGate() {
        val p = AmbientPipeline(AmbientPipeline.Config(windowGapMs = 5_000, tLo = 0.6f))
        p.add(seg(0, 2_000, SpeakerLabel.USER, score = 0.55f))
        assertTrue(p.tick(20_000).isEmpty())
    }

    @Test fun gateOffTranscribesEverything() {
        val p = AmbientPipeline(AmbientPipeline.Config(windowGapMs = 5_000, gateUserPresence = false))
        p.add(seg(0, 2_000, SpeakerLabel.OTHER))
        assertEquals(1, p.tick(20_000).size)
    }

    // ---- windows and batches ---------------------------------------------------------------

    @Test fun silenceGapSplitsWindowsAndCapsCutLongTalks() {
        val p = AmbientPipeline(AmbientPipeline.Config(windowGapMs = 10_000, maxWindowMs = 60_000))
        p.add(seg(0, 2_000, SpeakerLabel.USER))
        val closed = p.add(seg(30_000, 2_000, SpeakerLabel.USER)) // 28 s of silence
        assertEquals(1, closed.size)
        assertEquals(0L, closed.single().startedAtMs)

        val q = AmbientPipeline(AmbientPipeline.Config(windowGapMs = 10_000, maxWindowMs = 20_000))
        var t = 0L
        val out = mutableListOf<AmbientPipeline.ReadyWindow>()
        repeat(12) { out += q.add(seg(t, 3_000, SpeakerLabel.USER)); t += 3_200 }
        assertTrue("a 38 s monologue is cut into windows of at most 20 s", out.size >= 1)
        assertTrue(out.all { it.endedAtMs - it.startedAtMs <= 23_000 })
    }

    @Test fun batchesFollowSpeakerRunsAndTheBatchLimit() {
        val p = AmbientPipeline(AmbientPipeline.Config(batchMs = 10_000))
        val segs = listOf(
            seg(0, 4_000, SpeakerLabel.OTHER), seg(4_500, 4_000, SpeakerLabel.OTHER),
            seg(9_000, 4_000, SpeakerLabel.USER), seg(13_500, 4_000, SpeakerLabel.USER), seg(18_000, 4_000, SpeakerLabel.USER),
        )
        val b = p.batchRuns(segs)
        assertEquals(listOf(SpeakerLabel.OTHER, SpeakerLabel.USER, SpeakerLabel.USER), b.map { it.label })
        assertEquals(listOf(2, 2, 1), b.map { it.segments.size })
        // joined audio = both segments + 300 ms of silence between them
        assertEquals(4_000 * 16 * 2 + 300 * 16, b[0].joinedSamples().size)
    }

    @Test fun ringKeepsOnlyTheLastTwoMinutes() {
        val p = AmbientPipeline(AmbientPipeline.Config(ringMs = 120_000, windowGapMs = 1_000))
        p.add(seg(0, 1_000, SpeakerLabel.OTHER))
        p.add(seg(200_000, 1_000, SpeakerLabel.OTHER))
        assertEquals(1, p.recent(201_000, 120_000).size)
        p.clear()
        assertTrue(p.recent(201_000, 120_000).isEmpty())
    }

    // ---- energy segmenter --------------------------------------------------------------------

    @Test fun energySegmenterCutsSpeechBetweenSilences() {
        val sr = 16_000
        val silence = SyntheticAudioFixtures.generateSilence(1.0f)
        val speech = SyntheticAudioFixtures.generateTone(220f, 1.5f, amplitude = 0.3f)
        val stream = silence + speech + silence + speech + silence
        val s = EnergySegmenter()
        // Feed in odd-sized chunks, like the recorder does.
        var i = 0
        val rnd = Random(7)
        while (i < stream.size) {
            val n = (500 + rnd.nextInt(3000)).coerceAtMost(stream.size - i)
            s.accept(stream.copyOfRange(i, i + n)); i += n
        }
        s.flush()
        val segs = s.drain()
        assertEquals(2, segs.size)
        // Each segment starts within 300 ms of the true onset and lasts about 1.5 s (+ padding).
        assertTrue(kotlin.math.abs(segs[0].startSample - sr * 1L) < sr * 0.3)
        assertTrue(kotlin.math.abs(segs[1].startSample - sr * 3.5) < sr * 0.3)
        assertTrue(segs.all { it.durationMs in 1_400..2_300 })
    }

    // ---- STT route ----------------------------------------------------------------------------

    @Test fun whisperWithoutKeysWarnsAndUsesThePhone() {
        val p = AmbientSttPlanner.plan(AmbientSttMode.CLOUD, deviceSttReady = true, nimKeyCount = 0, online = true, cloudSecondsToday = 0, cloudCapSeconds = 5400)
        assertEquals(SttRoute.DEVICE, p.route)
        assertEquals(AmbientSttPlanner.NO_KEY_WARNING, p.warning)

        val none = AmbientSttPlanner.plan(AmbientSttMode.CLOUD, deviceSttReady = false, nimKeyCount = 0, online = true, cloudSecondsToday = 0, cloudCapSeconds = 5400)
        assertEquals(SttRoute.NONE, none.route)

        val ok = AmbientSttPlanner.plan(AmbientSttMode.HYBRID, deviceSttReady = true, nimKeyCount = 2, online = true, cloudSecondsToday = 0, cloudCapSeconds = 5400)
        assertEquals(SttRoute.NIM_WHISPER, ok.route)
        assertNull(ok.warning)

        val capped = AmbientSttPlanner.plan(AmbientSttMode.HYBRID, deviceSttReady = true, nimKeyCount = 2, online = true, cloudSecondsToday = 5400, cloudCapSeconds = 5400)
        assertEquals(SttRoute.DEVICE, capped.route)

        val offline = AmbientSttPlanner.plan(AmbientSttMode.CLOUD, deviceSttReady = false, nimKeyCount = 2, online = false, cloudSecondsToday = 0, cloudCapSeconds = 5400)
        assertEquals(SttRoute.NONE, offline.route)
    }

    // ---- Riva wire format -------------------------------------------------------------------

    @Test fun rivaRequestMatchesTheProtoLayout() {
        val req = RivaAsrProto.encodeRecognizeRequest(RivaAsrProto.Config(languageCode = "en"), byteArrayOf(1, 2, 3))
        // config (field 1, length-delimited) holds: encoding=1, rate=16000, "en", max_alt=1, channels=1, punctuation=1
        val expectedConfig = byteArrayOf(0x08, 0x01, 0x10, 0x80.toByte(), 0x7D, 0x1A, 0x02, 'e'.code.toByte(), 'n'.code.toByte(), 0x20, 0x01, 0x38, 0x01, 0x58, 0x01)
        val expected = byteArrayOf(0x0A, expectedConfig.size.toByte()) + expectedConfig + byteArrayOf(0x12, 0x03, 1, 2, 3)
        assertArrayEquals(expected, req)
    }

    @Test fun rivaResponseDecodesFirstAlternatives() {
        fun str(field: Int, s: String) = byteArrayOf(((field shl 3) or 2).toByte(), s.length.toByte()) + s.toByteArray()
        fun msg(field: Int, b: ByteArray) = byteArrayOf(((field shl 3) or 2).toByte(), b.size.toByte()) + b
        val alt1 = str(1, " send it by friday ") + byteArrayOf(0x15, 0, 0, 0x80.toByte(), 0x3F) // confidence 1.0f (fixed32)
        val alt2 = str(1, "sent it by friday")
        val result1 = msg(1, alt1) + msg(1, alt2) + byteArrayOf(0x10, 0x00)            // channel_tag = 0
        val result2 = msg(1, str(1, "okay"))
        val response = msg(1, result1) + msg(1, result2)
        assertEquals(listOf("send it by friday", "okay"), RivaAsrProto.decodeTranscripts(response))
        assertEquals(listOf("okay"), RivaAsrProto.unframe(RivaAsrProto.frame(byteArrayOf(9)) + RivaAsrProto.frame(response)).drop(1).let { RivaAsrProto.decodeTranscripts(it.single()) }.drop(1))
    }

    @Test fun pcmConversionClampsAndIsLittleEndian() {
        val b = RivaAsrProto.floatToPcm16(floatArrayOf(1f, -2f, 0f))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F, 0x01, 0x80.toByte(), 0, 0), b)
    }
}
