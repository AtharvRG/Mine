package com.mine.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroqTranscriptionResponse(
    @SerialName("text") val text: String
)
