package com.mine.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mine.app.ai.CuratorAgent
import com.mine.app.ai.ToolExecutor
import com.mine.app.data.local.db.dao.GraphDao
import com.mine.app.data.local.db.entity.BranchEntity
import com.mine.app.data.local.db.entity.NodeEntity
import com.mine.app.data.remote.NimApi
import com.mine.app.data.remote.NimWhisperClient
import com.mine.app.data.remote.dto.NimChatRequest
import com.mine.app.data.remote.dto.NimMessage
import com.mine.app.service.AudioRecorderManager
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
    private val audioRecorderManager: AudioRecorderManager,
    private val nimWhisperClient: NimWhisperClient,
    networkMonitor: NetworkMonitor
) : ViewModel() {


    val isOnline = networkMonitor.isOnline

    private val _logText = MutableStateFlow("System Ready. Waiting for input...\n")
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording
    val logText: StateFlow<String> = _logText

    fun appendLog(msg: String) {
        _logText.value = _logText.value + "\n" + msg
    }

    fun addManualTodo(task: String) {
        viewModelScope.launch(Dispatchers.IO) {
            appendLog("✏️ Manual Entry: Saving '$task' directly to DB...")

            var branch = graphDao.getBranchByPath("personal/tasks")
            if (branch == null) {
                val branchId = graphDao.insertBranch(BranchEntity(groupId = 1, name = "tasks", path = "personal/tasks")).toInt()
                branch = BranchEntity(id = branchId, groupId = 1, name = "tasks", path = "personal/tasks")
            }

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

    fun runRealAiTest() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!isOnline.value) {
                appendLog("❌ Cannot reach AI. You are offline!")
                return@launch
            }

            appendLog("🌐 Sending request to REAL NVIDIA NIM (gpt-oss-20b)...")

            try {
                val apiKey = "YOU_API_KEY" // Replace with real key

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

            val fakeTranscript = "Hey man, are we still on for beers tonight? Actually I can't, I'm doing Keto now, no carbs. Plus I have that massive Q3 report due tomorrow."
            appendLog("🎙️ Transcript: $fakeTranscript")

            try {
                val apiKey = "YOU_API_KEY" // Replace with real key

                val result = curatorAgent.processTranscript(apiKey, fakeTranscript)
                appendLog("⚙️ Curator Result: $result")

                fetchAndDisplayDatabase()
            } catch (e: Exception) {
                appendLog("❌ Curator Error: ${e.message}")
            }
        }
    }

    fun runMockToolExecution() {
        viewModelScope.launch(Dispatchers.IO) {
            val mockJson = """
                {
                  "discard_transcript": false,
                  "nodes_to_create": [
                    {
                      "content": "Testing tool executor with embeddings",
                      "branch": "personal",
                      "confidence": 1.0
                    }
                  ],
                  "nodes_to_update": [],
                  "contradictions": [],
                  "trigger_nudge": false
                }
            """.trimIndent()
            val success = toolExecutor.executeUpdateMemoryGraph("YOU_API_KEY", mockJson)
            appendLog("🛠️ Mock Tool Execution: ${if (success) "Success" else "Failed"}")
            fetchAndDisplayDatabase()
        }
    }

    fun fetchAndDisplayDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            appendLog("\n--- CURRENT DATABASE STATE ---")
            val nodes = graphDao.getAllNodes(limit = 20)
            if (nodes.isEmpty()) {
                appendLog("Database is empty.")
            } else {
                nodes.forEach { node ->
                    appendLog("[ID: ${node.id}] [${node.source}]: ${node.content}")
                }
            }
        }
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            // STOP RECORDING & TRANSCRIBE
            _isRecording.value = false
            appendLog("⏹️ Recording stopped. Sending to NVIDIA Whisper...")

            viewModelScope.launch(Dispatchers.IO) {
                val audioBytes = audioRecorderManager.stopAndGetAudio()

                try {
                    // TODO: Put your real NIM API key here
                    val apiKey = "YOU_API_KEY"

                    val transcript = nimWhisperClient.transcribeAudio(apiKey, audioBytes)
                    appendLog("🗣️ You said: \"$transcript\"")

                    if (transcript.isNotBlank()) {
                        appendLog("🧠 Feeding transcript to Curator...")
                        val result = curatorAgent.processTranscript(apiKey, transcript)
                        appendLog("⚙️ Curator Result: $result")
                        fetchAndDisplayDatabase()
                    }
                } catch (e: Exception) {
                    appendLog("❌ Whisper Error: ${e.message}")
                    e.printStackTrace()
                }
            }
        } else {
            // START RECORDING
            _isRecording.value = true
            audioRecorderManager.startRecording()
            appendLog("🎙️ Recording started... Speak into your phone!")
        }
    }


}
