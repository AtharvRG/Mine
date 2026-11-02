// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.quotes

import com.anchor.core.models.Quote
import com.anchor.core.models.QuoteSlot
import com.anchor.core.models.QuoteSource
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.StreamEvent
import com.anchor.core.provider.Target
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class QuoteManager(
    private val storage: Storage,
    private val orchestrator: ProviderOrchestrator? = null,
    private val clock: Clock,
    private val timeZone: TimeZone = TimeZone.getDefault()
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getSlotForHour(hour: Int): QuoteSlot = when (hour) {
        in 6..11 -> QuoteSlot.MORNING
        in 12..16 -> QuoteSlot.AFTERNOON
        in 17..21 -> QuoteSlot.EVENING
        else -> QuoteSlot.NIGHT
    }

    fun getSlot(ts: Long = clock.now()): QuoteSlot {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = ts }
        return getSlotForHour(cal.get(Calendar.HOUR_OF_DAY))
    }

    fun getDayKey(ts: Long = clock.now()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = this@QuoteManager.timeZone
        }.format(java.util.Date(ts))
    }

    fun getNextSlot(slot: QuoteSlot): QuoteSlot = when (slot) {
        QuoteSlot.MORNING -> QuoteSlot.AFTERNOON
        QuoteSlot.AFTERNOON -> QuoteSlot.EVENING
        QuoteSlot.EVENING -> QuoteSlot.NIGHT
        QuoteSlot.NIGHT -> QuoteSlot.MORNING
    }

    /**
     * D-108: Prepares ~1 hour before slot change (at hours 5, 11, 16, 21).
     */
    fun shouldPrepareNextSlot(ts: Long = clock.now()): Pair<Boolean, QuoteSlot?> {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = ts }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            5 -> true to QuoteSlot.MORNING
            11 -> true to QuoteSlot.AFTERNOON
            16 -> true to QuoteSlot.EVENING
            21 -> true to QuoteSlot.NIGHT
            else -> false to null
        }
    }

    /**
     * Returns current slot's quote instantly.
     * Uses persisted quote if present; otherwise seeds from preset pool.
     */
    suspend fun getQuote(ts: Long = clock.now()): Quote {
        val slot = getSlot(ts)
        val dayKey = getDayKey(ts)
        val existing = storage.getQuoteSlot(slot, dayKey)
        if (existing != null) {
            return existing
        }

        val presetText = PresetQuotes.getPreset(slot, dayKey)
        val newQuote = Quote(
            slot = slot,
            dayKey = dayKey,
            text = presetText,
            generatedAt = ts,
            source = QuoteSource.PRESET,
            regenCount = 0
        )
        storage.saveQuoteSlot(newQuote)
        return newQuote
    }

    /**
     * D-108: Generates live LLM quote asynchronously.
     * Enforces max 2 regenerations per slot.
     */
    suspend fun generateLiveQuote(
        slot: QuoteSlot,
        dayKey: String,
        contextSummary: String = "",
        forceRegen: Boolean = false
    ): Quote? {
        if (orchestrator == null) return null

        val ep = storage.getSetting("ep") ?: ""
        val key = storage.getSetting("key") ?: ""
        val mod = storage.getSetting("mod") ?: ""
        if (ep.isBlank() || key.isBlank() || mod.isBlank()) return null

        val existing = storage.getQuoteSlot(slot, dayKey)
        if (existing != null && existing.source == QuoteSource.LIVE && !forceRegen) {
            return existing
        }

        val currentRegens = existing?.regenCount ?: 0
        if (currentRegens >= 2) {
            return existing
        }

        return try {
            val prompt = "Provide a single concise grounding reflection (under 15 words) for the $slot slot. Context: $contextSummary. Output only the quote text without quotes or preamble."
            val body = buildJsonObject {
                put("model", mod)
                put("stream", true)
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", "You are a thoughtful, quiet personal assistant.")
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
            }.toString()

            val target = Target(ep, key, 50)
            val builder = StringBuilder()
            orchestrator.chat(listOf(target), body)
                .catch { /* keep existing on stream error */ }
                .collect { event ->
                    when (event) {
                        is StreamEvent.Clear -> builder.clear()
                        is StreamEvent.TextDelta -> builder.append(event.text)
                        else -> {}
                    }
                }

            val raw = builder.toString().trim()
            if (raw.isNotBlank()) {
                val clean = raw.lines().firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.removeSurrounding("'") ?: raw

                val quote = Quote(
                    slot = slot,
                    dayKey = dayKey,
                    text = clean,
                    generatedAt = clock.now(),
                    source = QuoteSource.LIVE,
                    regenCount = currentRegens + 1
                )
                storage.saveQuoteSlot(quote)
                quote
            } else {
                existing
            }
        } catch (_: Exception) {
            existing
        }
    }
}
