// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.profile

import com.anchor.core.gateway.ToolCall
import com.anchor.core.persona.AboutYouInterviewer
import com.anchor.core.persona.AboutYouInterviewer.Turn
import com.anchor.core.provider.KeyPool
import com.anchor.core.provider.StreamEvent
import com.anchor.core.ui.UiAction
import com.anchor.mine.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * The isolated "tell Mine about yourself" chat on the About you page. Its history is its own
 * (never in the main chat or the raw log), it has no web search, no voice and no tools: the
 * model can only ask questions and propose facts, which go through `soul_propose` like any
 * other Soul proposal (review in the inbox, or auto-accept when the user turned that on).
 */
object AboutYouSession {
    data class Ui(
        val turns: List<Turn> = emptyList(),
        val busy: Boolean = false,
        /** Facts proposed during this conversation. */
        val learned: Int = 0,
        val problem: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private var loaded = false
    private var job: Job? = null

    fun ensureLoaded(g: AppGraph, scope: CoroutineScope) {
        if (loaded) return
        loaded = true
        scope.launch(Dispatchers.IO) {
            val saved = AboutYouInterviewer.decodeHistory(runCatching { g.storage.getSetting(AboutYouInterviewer.KEY_HISTORY) }.getOrNull())
            if (saved.isNotEmpty()) _ui.update { it.copy(turns = saved) }
            else {
                val name = g.chatController.state.value.profile.name
                val known = runCatching { g.storage.getActiveSoulFacts().size }.getOrDefault(0)
                _ui.update { it.copy(turns = listOf(Turn("assistant", AboutYouInterviewer.opening(name, known)))) }
            }
        }
    }

    fun send(g: AppGraph, scope: CoroutineScope, text: String) {
        val t = text.trim()
        if (t.isEmpty() || _ui.value.busy) return
        _ui.update { it.copy(turns = it.turns + Turn("user", t), problem = null) }
        ask(g, scope, cook = AboutYouInterviewer.isStale(_ui.value.turns))
    }

    /** "Cook a question": a fresh topic, from the model when connected, from the local list otherwise. */
    fun cook(g: AppGraph, scope: CoroutineScope) {
        if (_ui.value.busy) return
        ask(g, scope, cook = true)
    }

    fun reset(g: AppGraph, scope: CoroutineScope) {
        job?.cancel()
        scope.launch(Dispatchers.IO) {
            runCatching { g.storage.saveSetting(AboutYouInterviewer.KEY_HISTORY, "") }
            val name = g.chatController.state.value.profile.name
            _ui.value = Ui(turns = listOf(Turn("assistant", AboutYouInterviewer.opening(name, 3))))
        }
    }

    private fun ask(g: AppGraph, scope: CoroutineScope, cook: Boolean) {
        job = scope.launch(Dispatchers.IO) {
            _ui.update { it.copy(busy = true) }
            try {
                val st = g.chatController.state.value
                val connected = st.providerKey.isNotBlank()
                val history = _ui.value.turns
                if (!connected) {
                    val q = AboutYouInterviewer.localQuestion(history)
                    _ui.update { it.copy(turns = it.turns + Turn("assistant", q), problem = "Connect an AI in Settings to have a real conversation here. Until then I'll just ask.") }
                    return@launch
                }
                val known = g.storage.getActiveSoulFacts(includeLocalOnly = false).map { it.statement } // on-phone-only facts never leave
                val topic = if (cook) AboutYouInterviewer.nextTopic(history) else null
                val body = AboutYouInterviewer.requestBody(st.profile.name, known, history, topic)
                val rpm = st.providerRpm
                val targets = KeyPool.targets(st.providerEndpoint, listOf(st.providerKey) + st.providerExtraKeys, st.providerModel, rpm, g.rateLimiter)
                val sb = StringBuilder()
                g.orchestrator.chat(targets, body, envelopeMode = true).collect { ev ->
                    when (ev) {
                        is StreamEvent.TextDelta -> sb.append(ev.text)
                        is StreamEvent.Clear -> sb.clear()
                        else -> Unit
                    }
                }
                val reply = AboutYouInterviewer.parse(sb.toString())
                var learned = 0
                for (f in reply.facts) {
                    val r = runCatching {
                        g.gateway.executeTool(
                            ToolCall(
                                "soul_propose",
                                mapOf("statement" to JsonPrimitive(f.statement), "category" to JsonPrimitive(f.category), "confidence" to JsonPrimitive(f.confidence.toString())),
                            ),
                            executedCalls = mutableSetOf(),
                        )
                    }.getOrNull()
                    if (r !is com.anchor.core.models.ToolResult.Error) learned++
                }
                _ui.update { it.copy(turns = it.turns + Turn("assistant", reply.text), learned = it.learned + learned) }
                if (learned > 0) g.chatController.onAction(UiAction.LoadMemory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Offline or the provider failed: keep the conversation going with a local question.
                _ui.update { it.copy(turns = it.turns + Turn("assistant", AboutYouInterviewer.localQuestion(it.turns)), problem = "Couldn't reach your AI (${e.message ?: "error"}). Asking from my own list for now.") }
            } finally {
                _ui.update { it.copy(busy = false) }
                runCatching { g.storage.saveSetting(AboutYouInterviewer.KEY_HISTORY, AboutYouInterviewer.encodeHistory(_ui.value.turns)) }
            }
        }
    }
}
