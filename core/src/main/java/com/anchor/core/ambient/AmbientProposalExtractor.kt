// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ambient

import com.anchor.core.models.Node
import com.anchor.core.resolver.Resolver
import java.util.UUID

/**
 * Extracts proposals and keypoints from dialogue window transcripts or LLM responses
 * per Blueprint 61 §3.5.
 *
 * Invariant: Proposal-Only. Ambient extraction can ONLY create items with
 * status = "proposed" and source = "ambient". It NEVER closes, modifies, or deletes existing nodes.
 */
class AmbientProposalExtractor(
    private val maxProposalsPerWindow: Int = 5
) {

    data class ExtractedItem(
        val kind: String, // "proposal" or "keypoint"
        val summary: String,
        val proposedNode: Node? = null,
        val flags: Set<String> = emptySet()
    )

    /**
     * Parses an LLM extraction JSON or falls back to deterministic extraction.
     */
    fun extract(
        window: DialogueWindowEngine.Window,
        llmResponseJson: String?,
        existingNodes: List<Node> = emptyList(),
        nowMs: Long
    ): List<ExtractedItem> {
        val parsed = if (!llmResponseJson.isNullOrBlank()) {
            parseLlmJson(llmResponseJson, window, nowMs)
        } else {
            fallbackRuleBasedExtraction(window, nowMs)
        }

        // Apply duplicate checks and attribution verification
        return parsed.take(maxProposalsPerWindow).map { item ->
            val flags = item.flags.toMutableSet()

            // Check if user turn was short / low confidence
            val userTurns = window.turns.filter { it.label == SpeakerLabel.USER }
            val hasWeakAttribution = userTurns.isEmpty() || userTurns.all { it.isShort || it.score < 0.70f }
            if (hasWeakAttribution && item.kind == "proposal") {
                flags.add("attribution_uncertain")
            }

            // Check for duplicate with existing nodes
            if (item.proposedNode != null) {
                val isDup = existingNodes.any {
                    it.title.trim().lowercase() == item.proposedNode.title.trim().lowercase()
                }
                if (isDup) {
                    flags.add("possible_duplicate")
                }
            }

            item.copy(flags = flags)
        }
    }

    /**
     * Parses the LLM JSON response extracting up to maxProposalsPerWindow items.
     */
    private fun parseLlmJson(
        json: String,
        window: DialogueWindowEngine.Window,
        nowMs: Long
    ): List<ExtractedItem> {
        val items = mutableListOf<ExtractedItem>()
        val clean = json.trim()

        // Extract JSON items block
        val itemsMatch = Regex(""""items"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(clean)
        val itemsBody = itemsMatch?.groupValues?.get(1) ?: clean

        val itemObjects = Regex("""\{([^{}]+)\}""").findAll(itemsBody)
        for (match in itemObjects) {
            val block = match.groupValues[1]
            val kind = extractJsonString(block, "kind") ?: "keypoint"
            val summary = extractJsonString(block, "summary") ?: continue
            val title = extractJsonString(block, "title") ?: summary
            val isUncertain = block.contains(""""attribution_uncertain"\s*:\s*true""".toRegex())

            val flags = mutableSetOf<String>()
            if (isUncertain) flags.add("attribution_uncertain")

            val proposedNode = if (kind == "proposal") {
                Node(
                    id = UUID.randomUUID().toString(),
                    type = "task",
                    title = title,
                    status = "proposed",
                    origin = "ambient",
                    createdAt = nowMs,
                    updatedAt = nowMs
                )
            } else null

            items.add(
                ExtractedItem(
                    kind = kind,
                    summary = summary,
                    proposedNode = proposedNode,
                    flags = flags
                )
            )
        }

        return if (items.isNotEmpty()) items else fallbackRuleBasedExtraction(window, nowMs)
    }

    /**
     * Deterministic rule-based extraction fallback when offline or LLM fails.
     * Prevents dead ends.
     */
    fun fallbackRuleBasedExtraction(
        window: DialogueWindowEngine.Window,
        nowMs: Long
    ): List<ExtractedItem> {
        val items = mutableListOf<ExtractedItem>()

        // Look for commitment / promise markers in user turns
        val commitmentRegex = Regex(
            """\b(?:I will|I'll|need to|have to|promise to|going to|remind me to)\s+([^.!?\n]{3,60})""",
            RegexOption.IGNORE_CASE
        )

        for (turn in window.turns) {
            if (turn.label == SpeakerLabel.USER) {
                val matches = commitmentRegex.findAll(turn.text)
                for (m in matches) {
                    val taskAction = m.groupValues[1].trim()
                    val title = "Task: $taskAction"
                    val flags = mutableSetOf<String>()
                    if (turn.isShort || turn.score < 0.70f) {
                        flags.add("attribution_uncertain")
                    }

                    items.add(
                        ExtractedItem(
                            kind = "proposal",
                            summary = "Promise to: $taskAction",
                            proposedNode = Node(
                                id = UUID.randomUUID().toString(),
                                type = "task",
                                title = title,
                                status = "proposed",
                                origin = "ambient",
                                createdAt = nowMs,
                                updatedAt = nowMs
                            ),
                            flags = flags
                        )
                    )
                }
            }
        }

        // If no explicit commitment, add high-level summary keypoint if window is non-empty
        if (items.isEmpty() && window.turns.isNotEmpty()) {
            val firstTurn = window.turns.first()
            val shortParaphrase = NumberScrubber.scrub(firstTurn.text.take(80))
            items.add(
                ExtractedItem(
                    kind = "keypoint",
                    summary = "Discussion point: $shortParaphrase",
                    proposedNode = null,
                    flags = emptySet()
                )
            )
        }

        return items
    }

    private fun extractJsonString(block: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
        return pattern.find(block)?.groupValues?.get(1)?.replace("\\\"", "\"")
    }
}
