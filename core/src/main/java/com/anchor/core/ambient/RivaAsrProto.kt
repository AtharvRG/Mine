// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ambient

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Just enough protobuf and gRPC framing to call NVIDIA Riva's offline `Recognize` (which is how
 * NIM serves Whisper), without generated stubs or the protobuf runtime.
 *
 * Field numbers from nvidia-riva/common `riva/proto/riva_asr.proto` and `riva_audio.proto`:
 *  - RecognizeRequest { RecognitionConfig config = 1; bytes audio = 2; }
 *  - RecognitionConfig { AudioEncoding encoding = 1; int32 sample_rate_hertz = 2;
 *      string language_code = 3; int32 max_alternatives = 4; int32 audio_channel_count = 7;
 *      bool enable_automatic_punctuation = 11; string model = 13; }
 *  - AudioEncoding LINEAR_PCM = 1
 *  - RecognizeResponse { repeated SpeechRecognitionResult results = 1; }
 *  - SpeechRecognitionResult { repeated SpeechRecognitionAlternative alternatives = 1; }
 *  - SpeechRecognitionAlternative { string transcript = 1; float confidence = 2; }
 */
object RivaAsrProto {
    const val METHOD_PATH = "/nvidia.riva.asr.RivaSpeechRecognition/Recognize"
    const val LINEAR_PCM = 1

    data class Config(
        val sampleRateHz: Int = 16_000,
        val languageCode: String = "en",
        val maxAlternatives: Int = 1,
        val audioChannelCount: Int = 1,
        val automaticPunctuation: Boolean = true,
        val model: String? = null,
    )

    fun encodeRecognizeRequest(config: Config, audioPcm16le: ByteArray): ByteArray {
        val cfg = ProtoWriter().apply {
            varintField(1, LINEAR_PCM.toLong())
            varintField(2, config.sampleRateHz.toLong())
            stringField(3, config.languageCode)
            varintField(4, config.maxAlternatives.toLong())
            varintField(7, config.audioChannelCount.toLong())
            if (config.automaticPunctuation) varintField(11, 1)
            config.model?.takeIf { it.isNotBlank() }?.let { stringField(13, it) }
        }.bytes()
        return ProtoWriter().apply {
            bytesField(1, cfg)
            bytesField(2, audioPcm16le)
        }.bytes()
    }

    /** The first alternative of every result, in order, blanks dropped. */
    fun decodeTranscripts(response: ByteArray): List<String> {
        val out = ArrayList<String>()
        ProtoReader(response).forEachField { field, wire, r ->
            if (field == 1 && wire == 2) {
                val result = r.readBytes()
                var first: String? = null
                ProtoReader(result).forEachField { f2, w2, r2 ->
                    if (f2 == 1 && w2 == 2) {
                        val alt = r2.readBytes()
                        if (first == null) {
                            ProtoReader(alt).forEachField { f3, w3, r3 ->
                                if (f3 == 1 && w3 == 2) first = String(r3.readBytes(), Charsets.UTF_8) else r3.skip(w3)
                            }
                        }
                    } else r2.skip(w2)
                }
                first?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            } else r.skip(wire)
        }
        return out
    }

    /** gRPC length-prefixed message: 1 byte "compressed" (0), 4 bytes big-endian length, payload. */
    fun frame(message: ByteArray): ByteArray =
        ByteBuffer.allocate(5 + message.size).order(ByteOrder.BIG_ENDIAN).put(0).putInt(message.size).put(message).array()

    /** Splits a gRPC response body into its messages. Throws on a truncated body. */
    fun unframe(body: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var pos = 0
        while (pos < body.size) {
            require(pos + 5 <= body.size) { "truncated gRPC frame header" }
            val compressed = body[pos].toInt()
            require(compressed == 0) { "compressed gRPC messages are not supported" }
            val len = ByteBuffer.wrap(body, pos + 1, 4).order(ByteOrder.BIG_ENDIAN).int
            require(len >= 0 && pos + 5 + len <= body.size) { "truncated gRPC message" }
            out += body.copyOfRange(pos + 5, pos + 5 + len)
            pos += 5 + len
        }
        return out
    }

    /** 16 kHz float samples in [-1, 1] → signed 16-bit little-endian PCM. */
    fun floatToPcm16(samples: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return bb.array()
    }

    /** gRPC status codes that mean "this key is out of budget right now": rotate to the next key. */
    fun isRateLimited(grpcStatus: Int?, httpStatus: Int): Boolean = httpStatus == 429 || grpcStatus == 8

    // ---- minimal protobuf wire format ----------------------------------------------------------

    internal class ProtoWriter {
        private val out = ByteArrayOutputStream()
        fun bytes(): ByteArray = out.toByteArray()
        private fun varint(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
        }
        private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun varintField(field: Int, v: Long) { tag(field, 0); varint(v) }
        fun bytesField(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun stringField(field: Int, s: String) = bytesField(field, s.toByteArray(Charsets.UTF_8))
    }

    internal class ProtoReader(private val buf: ByteArray) {
        private var pos = 0
        fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                require(pos < buf.size) { "truncated varint" }
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }
        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            require(len >= 0 && pos + len <= buf.size) { "truncated field" }
            val b = buf.copyOfRange(pos, pos + len)
            pos += len
            return b
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> readBytes()
                5 -> pos += 4
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
        }
        fun forEachField(block: (field: Int, wire: Int, reader: ProtoReader) -> Unit) {
            while (pos < buf.size) {
                val key = readVarint().toInt()
                block(key ushr 3, key and 7, this)
            }
        }
    }
}
