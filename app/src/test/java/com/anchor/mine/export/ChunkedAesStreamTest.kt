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
}
