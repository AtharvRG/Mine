// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.media

import java.util.UUID

/**
 * Metadata reference for a rich media attachment (audio note, image, document).
 *
 * Invariant: Binary media is NEVER stored in the database.
 * Only lightweight metadata references are stored in node/message attrs.
 */
data class AttachmentRef(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long? = null,
    val sha256: String? = null,
    val createdAt: Long
) {
    fun toJson(): String {
        val durationPart = if (durationMs != null) ""","duration_ms":$durationMs""" else ""
        val hashPart = if (sha256 != null) ""","sha256":"$sha256"""" else ""
        return """{"id":"$id","uri":"$uri","mime_type":"$mimeType","size_bytes":$sizeBytes$durationPart$hashPart,"created_at":$createdAt}"""
    }

    companion object {
        fun fromJson(json: String): AttachmentRef? {
            val id = extractString(json, "id") ?: return null
            val uri = extractString(json, "uri") ?: return null
            val mime = extractString(json, "mime_type") ?: return null
            val size = extractLong(json, "size_bytes") ?: 0L
            val duration = extractLong(json, "duration_ms")
            val hash = extractString(json, "sha256")
            val createdAt = extractLong(json, "created_at") ?: 0L
            return AttachmentRef(
                id = id,
                uri = uri,
                mimeType = mime,
                sizeBytes = size,
                durationMs = duration,
                sha256 = hash,
                createdAt = createdAt
            )
        }

        private fun extractString(json: String, key: String): String? {
            val match = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(json)
            return match?.groupValues?.get(1)?.replace("\\\"", "\"")
        }

        private fun extractLong(json: String, key: String): Long? {
            val match = Regex(""""$key"\s*:\s*(\d+)""").find(json)
            return match?.groupValues?.get(1)?.toLongOrNull()
        }
    }
}
