// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.serialization.Serializable

// --- REQUEST MODELS ---
@Serializable
data class ChatRequest(
    val model: String,
    val stream: Boolean,
    val messages: List<RequestMessage>
)

@Serializable
data class RequestMessage(
    val role: String,
    val content: String
)

// --- RESPONSE MODELS ---
@Serializable data class ChatChunk(val choices: List<Choice>? = null)
@Serializable data class Choice(val delta: Delta? = null, val finish_reason: String? = null)
@Serializable data class Delta(val content: String? = null)
