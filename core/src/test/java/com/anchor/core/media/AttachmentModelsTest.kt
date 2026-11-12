// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AttachmentModelsTest {

    @Test
    fun testAttachmentRefSerializationRoundtrip() {
        val attachment = AttachmentRef(
            id = "att-123",
            uri = "content://com.anchor.mine/audio/note_1.pcm",
            mimeType = "audio/pcm",
            sizeBytes = 48000L,
            durationMs = 1500L,
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            createdAt = 1700000000L
        )

        val json = attachment.toJson()
        val parsed = AttachmentRef.fromJson(json)

        assertNotNull(parsed)
        assertEquals(attachment.id, parsed!!.id)
        assertEquals(attachment.uri, parsed.uri)
        assertEquals(attachment.mimeType, parsed.mimeType)
        assertEquals(attachment.sizeBytes, parsed.sizeBytes)
        assertEquals(attachment.durationMs, parsed.durationMs)
        assertEquals(attachment.sha256, parsed.sha256)
        assertEquals(attachment.createdAt, parsed.createdAt)
    }
}
