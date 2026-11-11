// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ChunkedAesStreamTest {

    private val dek = ByteArray(32) { 1 }
    private val noncePrefix = ByteArray(8) { 2 }
    private val headerHash = ByteArray(32) { 3 }

    @Test
    fun testChunkLength_Negative_ThrowsSecurityException() {
        val badStream = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(-50).array())
            write(ByteArray(50))
        }

        val inStream = ChunkedAesInputStream(
            ByteArrayInputStream(badStream.toByteArray()),
            dek,
            noncePrefix,
            headerHash
        )

        try {
            inStream.read()
            fail("Expected SecurityException on negative chunk length")
        } catch (e: SecurityException) {
            assertTrue("Message should indicate invalid length: ${e.message}", e.message!!.contains("chunk length", ignoreCase = true))
        } catch (e: NegativeArraySizeException) {
            fail("Failed with NegativeArraySizeException instead of SecurityException: $e")
        }
    }

    @Test
    fun testChunkLength_LessThan16_ThrowsSecurityException() {
        val badStream = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(8).array())
            write(ByteArray(8))
        }

        val inStream = ChunkedAesInputStream(
            ByteArrayInputStream(badStream.toByteArray()),
            dek,
            noncePrefix,
            headerHash
        )

        try {
            inStream.read()
            fail("Expected SecurityException on chunk length < 16")
        } catch (e: SecurityException) {
            assertTrue("Message should indicate invalid length: ${e.message}", e.message!!.contains("chunk length", ignoreCase = true))
        }
    }

    @Test
    fun testChunkLength_ExceedsMaxChunkSize_ThrowsSecurityException() {
        val badStream = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(1024 * 1024 + 100).array())
        }

        val inStream = ChunkedAesInputStream(
            ByteArrayInputStream(badStream.toByteArray()),
            dek,
            noncePrefix,
            headerHash
        )

        try {
            inStream.read()
            fail("Expected SecurityException on oversized chunk length")
        } catch (e: SecurityException) {
            assertTrue("Message should indicate invalid length: ${e.message}", e.message!!.contains("chunk length", ignoreCase = true))
        }
    }

    @Test
    fun testRoundTrip_ValidData_Success() {
        val originalData = "Hello, hardened export world! This is a test of ChunkedAesInputStream and ChunkedAesOutputStream.".toByteArray(Charsets.UTF_8)
        val outBytes = ByteArrayOutputStream()

        ChunkedAesOutputStream(outBytes, dek, noncePrefix, headerHash, 64).use { out ->
            out.write(originalData)
        }

        val inStream = ChunkedAesInputStream(
            ByteArrayInputStream(outBytes.toByteArray()),
            dek,
            noncePrefix,
            headerHash,
            chunkSize = 64
        )

        val readData = inStream.readBytes()
        assertEquals(String(originalData), String(readData))
        assertTrue(inStream.isFinalSeen)
    }

    @Test
    fun testWrongDek_ThrowsSecurityException() {
        val originalData = "Sensitive backup data".toByteArray(Charsets.UTF_8)
        val outBytes = ByteArrayOutputStream()
        ChunkedAesOutputStream(outBytes, dek, noncePrefix, headerHash, 64).use { it.write(originalData) }

        val wrongDek = ByteArray(32) { 9 }
        val inStream = ChunkedAesInputStream(
            ByteArrayInputStream(outBytes.toByteArray()),
            wrongDek,
            noncePrefix,
            headerHash,
            chunkSize = 64
        )
        try {
            inStream.readBytes()
            fail("Expected SecurityException on wrong DEK")
        } catch (e: SecurityException) {
            assertTrue("Message should indicate chunk authentication failed: ${e.message}",
                e.message!!.contains("authentication failed", ignoreCase = true))
        }
    }

    @Test
    fun testCorruptedTag_ThrowsSecurityException() {
        val originalData = "Sensitive backup data".toByteArray(Charsets.UTF_8)
        val outBytes = ByteArrayOutputStream()
        ChunkedAesOutputStream(outBytes, dek, noncePrefix, headerHash, 64).use { it.write(originalData) }

        val bytes = outBytes.toByteArray()
        // Corrupt a byte in the encrypted chunk
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()

        val inStream = ChunkedAesInputStream(
            ByteArrayInputStream(bytes),
            dek,
            noncePrefix,
            headerHash,
            chunkSize = 64
        )
        try {
            inStream.readBytes()
            fail("Expected SecurityException on corrupted tag")
        } catch (e: SecurityException) {
            assertTrue("Message should indicate chunk authentication failed: ${e.message}",
                e.message!!.contains("authentication failed", ignoreCase = true))
        }
    }

    @Test
    fun testTruncatedCiphertext_ThrowsSecurityException() {
        val originalData = "Sensitive backup data".toByteArray(Charsets.UTF_8)
        val outBytes = ByteArrayOutputStream()
        ChunkedAesOutputStream(outBytes, dek, noncePrefix, headerHash, 64).use { it.write(originalData) }

        val bytes = outBytes.toByteArray()
        // Cut off the last 5 bytes of ciphertext
        val truncated = bytes.copyOf(bytes.size - 5)

        val inStream = ChunkedAesInputStream(
            ByteArrayInputStream(truncated),
            dek,
            noncePrefix,
            headerHash,
            chunkSize = 64
        )
        try {
            inStream.readBytes()
            fail("Expected SecurityException on truncated ciphertext")
        } catch (e: SecurityException) {
            assertTrue("Message should indicate truncated ciphertext: ${e.message}",
                e.message!!.contains("Truncated ciphertext", ignoreCase = true))
        }
    }

    @Test
    fun testTruncatedChunkLength_ThrowsSecurityException() {
        // Stream only has 2 bytes of the 4-byte chunk length
        val badStream = ByteArray(2) { 0 }
        val inStream = ChunkedAesInputStream(
            ByteArrayInputStream(badStream),
            dek,
            noncePrefix,
            headerHash
        )
        try {
            inStream.read()
            fail("Expected SecurityException on truncated chunk length")
        } catch (e: SecurityException) {
            assertTrue("Message should indicate truncated: ${e.message}",
                e.message!!.contains("Truncated", ignoreCase = true))
        }
    }
}
