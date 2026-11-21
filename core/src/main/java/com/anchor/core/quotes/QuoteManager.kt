// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.quotes

import com.anchor.core.models.Quote
import com.anchor.core.models.QuoteSlot
import com.anchor.core.models.QuoteSource
import com.anchor.core.models.QuoteStateCounts
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.StreamEvent
import com.anchor.core.provider.Target
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val limiter: com.anchor.core.provider.RateLimiter? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _currentQuote = MutableStateFlow<Quote?>(null)
    val currentQuote: StateFlow<Quote?> = _currentQuote.asStateFlow()

    private var liveGenJob: Job? = null

    // D-109: MORNING 05-12, AFTERNOON 12-17, EVENING 17-21, NIGHT 21-05
    fun getSlotForHour(hour: Int): QuoteSlot = when (hour) {
        in 5..11 -> QuoteSlot.MORNING
        in 12..16 -> QuoteSlot.AFTERNOON
        in 17..20 -> QuoteSlot.EVENING
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
     * D-109: Prepare-ahead fires 1 hour before each boundary: 04:00, 11:00, 16:00, 20:00.
     */
    fun shouldPrepareNextSlot(ts: Long = clock.now()): Pair<Boolean, QuoteSlot?> {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = ts }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            4 -> true to QuoteSlot.MORNING
            11 -> true to QuoteSlot.AFTERNOON
            16 -> true to QuoteSlot.EVENING
            20 -> true to QuoteSlot.NIGHT
            else -> false to null
        }
    }

    fun computeStateHash(counts: QuoteStateCounts): String {
        return "${counts.open}:${counts.overdue}:${counts.completedToday}:${counts.atRisk}"
    }

    suspend fun getQuoteStateCounts(ts: Long = clock.now()): QuoteStateCounts {
        val nodeCounts = storage.getActiveNodeCounts(ts)
        val relState = storage.getReliabilityState()
        val atRisk = relState.score == "at_risk"

        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = ts
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDayMs = cal.timeInMillis
        val endOfDayMs = startOfDayMs + 86400000L
        val completedToday = storage.getCompletedTodayCount(startOfDayMs, endOfDayMs)

        return QuoteStateCounts(
            open = nodeCounts.totalOpen,
            overdue = nodeCounts.overdue,
            completedToday = completedToday,
            atRisk = atRisk
        )
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
     * D-108 / D-109 / R8:
     * Generates live LLM quote asynchronously.
     * Enforces:
     * - First live generation has regenCount = 0
     * - Regenerations increment regenCount; max 2 regenerations per slot (at most 3 live texts)
     * - Regeneration allowed only when state_hash changed
     * - Prompt uses counts only (no titles, no soul facts, no local_only, no health content)
     * - Rethrows CancellationException
     */
    suspend fun generateLiveQuote(
        slot: QuoteSlot,
        dayKey: String,
        stateCounts: QuoteStateCounts? = null,
        forceRegen: Boolean = false
    ): Quote? {
        val existing = storage.getQuoteSlot(slot, dayKey)
        if (orchestrator == null) return existing

        val ep = storage.getSetting("ep") ?: ""
        val key = storage.getSetting("key") ?: ""
        val mod = storage.getSetting("mod") ?: ""
        if (ep.isBlank() || key.isBlank() || mod.isBlank()) return existing

        val counts = stateCounts ?: getQuoteStateCounts()
        val newHash = computeStateHash(counts)

        val isFirstLive = existing == null || existing.source == QuoteSource.PRESET

        if (!isFirstLive && existing != null && existing.source == QuoteSource.LIVE) {
            val currentRegens = existing.regenCount
            // Max 2 regenerations per slot
            if (currentRegens >= 2) {
                return existing
            }
            // Regeneration allowed only when state_hash changed
            if (existing.stateHash == newHash && !forceRegen) {
                return existing
            }
        }

        if (key.isNotBlank() && limiter?.getCooldownExpiry(key) != null) {
            return existing
        }

        val newRegenCount = if (isFirstLive) 0 else (existing?.regenCount ?: 0) + 1

        return try {
            val prompt = "Provide a single concise grounding reflection (under 15 words) for the $slot slot. Status: ${counts.open} open, ${counts.overdue} overdue, ${counts.completedToday} completed today, at-risk: ${counts.atRisk}. Output only the quote text without quotes or preamble."
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

            val target = Target(ep, key, 50, model = mod, limiterKey = "bg:$key")
            val builder = StringBuilder()
            orchestrator.chat(listOf(target), body)
                .catch { e ->
                    if (e is CancellationException) throw e
                }
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
                    stateHash = newHash,
                    source = QuoteSource.LIVE,
                    regenCount = newRegenCount
                )
                storage.saveQuoteSlot(quote)
                quote
            } else {
                existing
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            existing
        }
    }

    /**
     * D-108 / R8: Single entry point for opening Home (idempotent).
     * Opening Home shows the stored preset instantly, then the live one replaces it with the stored swap.
     */
    suspend fun onHomeOpened(ts: Long = clock.now()): Quote {
        val slot = getSlot(ts)
        val dayKey = getDayKey(ts)

        // 1. Instantly return and emit current quote (persisted quote or seed preset)
        val current = getQuote(ts)
        _currentQuote.value = current

        // 2. Asynchronously check if live generation or regeneration is eligible
        val counts = getQuoteStateCounts(ts)
        val newHash = computeStateHash(counts)

        val needsGeneration = when {
            current.source == QuoteSource.PRESET -> true
            current.source == QuoteSource.LIVE -> current.regenCount < 2 && current.stateHash != newHash
            else -> false
        }

        if (needsGeneration && (liveGenJob == null || liveGenJob?.isActive == false)) {
            liveGenJob = coroutineScope.launch {
                val live = generateLiveQuote(slot, dayKey, counts)
                if (live != null) {
                    _currentQuote.value = live
                }
            }
        }

        return current
    }

    suspend fun prepareNextSlotQuote(ts: Long = clock.now()): Quote? {
        val setting = storage.getSetting("prepare_next_quote_early") ?: "true"
        if (setting.lowercase(Locale.US) != "true") return null

        val (shouldPrepare, nextSlot) = shouldPrepareNextSlot(ts)
        if (!shouldPrepare || nextSlot == null) return null

        val dayKey = getDayKey(ts)
        val counts = getQuoteStateCounts(ts)
        return generateLiveQuote(nextSlot, dayKey, counts)
    }
}
