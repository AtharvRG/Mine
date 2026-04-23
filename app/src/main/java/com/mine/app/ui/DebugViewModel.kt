package com.mine.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mine.app.ai.CuratorAgent
import com.mine.app.ai.ToolExecutor
import com.mine.app.data.local.db.dao.GraphDao
import com.mine.app.data.local.db.entity.BranchEntity
import com.mine.app.data.local.db.entity.NodeEntity
import com.mine.app.data.remote.NimApi
import com.mine.app.data.remote.dto.NimChatRequest
import com.mine.app.data.remote.dto.NimMessage
import com.mine.app.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val toolExecutor: ToolExecutor,
    private val graphDao: GraphDao,
    private val nimApi: NimApi,
    private val curatorAgent: CuratorAgent,
    networkMonitor: NetworkMonitor // Injected to watch the internet
) : ViewModel() {

    val isOnline = networkMonitor.isOnline

    private val _logText = MutableStateFlow("System Ready. Waiting for input...\n")
    val logText: StateFlow<String> = _logText

    fun appendLog(msg: String) {
        _logText.value = _logText.value + "\n" + msg
    }

    // --- MANUAL OFFLINE ENTRY ---
    fun addManualTodo(task: String) {
        viewModelScope.launch(Dispatchers.IO) {
            appendLog("✏️ Manual Entry: Saving '$task' directly to DB...")

            // 1. Ensure a "tasks" branch exists
            var branch = graphDao.getBranchByPath("personal/tasks")
            if (branch == null) {
                val branchId = graphDao.insertBranch(BranchEntity(groupId = 1, name = "tasks", path = "personal/tasks")).toInt()
                branch = BranchEntity(id = branchId, groupId = 1, name = "tasks", path = "personal/tasks")
            }

            // 2. Insert directly into SQLite (No AI needed!)
            graphDao.insertNode(
                NodeEntity(
                    branchId = branch.id,
                    content = task,
                    confidence = 1.0f,
                    source = "manual"
                )
            )
            appendLog("✅ Saved manually!")
            fetchAndDisplayDatabase()
        }
    }

    // --- REAL AI TEST ---
    fun runRealAiTest() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!isOnline.value) {
                appendLog("❌ Cannot reach AI. You are offline!")
                return@launch
            }

            appendLog("🌐 Sending request to REAL NVIDIA NIM (gpt-oss-20b)...")

            try {
                // TODO: Put your real NIM API key here for testing
                val apiKey = "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"

                val request = NimChatRequest(
                    model = "openai/gpt-oss-20b",
                    messages = listOf(
                        NimMessage(role = "system", content = "You are a helpful assistant. Reply in exactly one short sentence."),
                        NimMessage(role = "user", content = "What is the capital of France?")
                    )
                )

                val response = nimApi.getChatCompletion(apiKey, request)
                val aiReply = response.choices.firstOrNull()?.message?.content ?: "No reply"

                appendLog("🤖 AI says: $aiReply")

            } catch (e: Exception) {
                appendLog("❌ API Error: ${e.message}")
            }
        }
    }


    fun runFullCuratorLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!isOnline.value) {
                appendLog("❌ Offline! Cannot run Curator.")
                return@launch
            }

            appendLog("🧠 Starting Full Curator Loop...")

            // The ultimate test transcript
            val fakeTranscript = "Hey man, are we still on for beers tonight? Actually I can't, I'm doing Keto now, no carbs. Plus I have that massive Q3 report due tomorrow."
            appendLog("🎙️ Transcript: $fakeTranscript")

            try {
                // TODO: Put your real NIM API key here
                val apiKey = "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"

                val result = curatorAgent.processTranscript(apiKey, fakeTranscript)
                appendLog("⚙️ Curator Result: $result")

                fetchAndDisplayDatabase()
            } catch (e: Exception) {
                appendLog("❌ Curator Error: ${e.message}")
            }
        }
    }


    fun fetchAndDisplayDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            appendLog("\n--- CURRENT DATABASE STATE ---")
            val nodes = graphDao.searchNodes("", limit = 20) // Empty string matches all in FTS4
            if (nodes.isEmpty()) {
                appendLog("Database is empty.")
            } else {
                nodes.forEach { node ->
                    appendLog("[ID: ${node.id}] [${node.source}]: ${node.content}")
                }
            }
        }
    }
}
