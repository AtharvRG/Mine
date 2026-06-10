package com.mine.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mine.app.ai.ChronosAgent
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
import com.mine.app.service.ServiceStateHolder
import com.mine.app.util.NetworkMonitor
import com.mine.app.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.mine.app.BuildConfig

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val toolExecutor: ToolExecutor,
    private val graphDao: GraphDao,
    private val nimApi: NimApi,
    private val curatorAgent: CuratorAgent,
    private val chronosAgent: ChronosAgent,
    private val audioRecorderManager: AudioRecorderManager,
    private val nimWhisperClient: NimWhisperClient,
    private val serviceStateHolder: ServiceStateHolder,
    networkMonitor: NetworkMonitor,
    val prefs: PreferencesManager
) : ViewModel() {


    val isOnline = networkMonitor.isOnline

    // --- TERMINAL STATE ---
    private val _logs = MutableStateFlow<List<String>>(listOf("System Ready. Waiting for input..."))
    val logs: StateFlow<List<String>> = _logs

    private val _transcripts = MutableStateFlow<List<com.mine.app.data.local.db.entity.TranscriptEntity>>(emptyList())
    val transcripts: StateFlow<List<com.mine.app.data.local.db.entity.TranscriptEntity>> = _transcripts

    fun loadTranscripts() {
        viewModelScope.launch(Dispatchers.IO) {
            _transcripts.value = graphDao.getRecentTranscripts()
        }
    }

    fun appendLog(msg: String) {
        // Adds the new message to the list
        _logs.value = _logs.value + msg
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    // --- AMBIENT SERVICE STATE ---
    val isAmbientRunning = serviceStateHolder.isAmbientRunning

    // --- GRAPH EDITOR STATE ---
    private val _graphNodes = MutableStateFlow<List<com.mine.app.data.local.db.entity.NodeEntity>>(emptyList())
    val graphNodes: StateFlow<List<com.mine.app.data.local.db.entity.NodeEntity>> = _graphNodes

    fun loadGraphNodes() {
        viewModelScope.launch(Dispatchers.IO) {
            // Fetch the latest 50 nodes to display in the editor
            _graphNodes.value = graphDao.getAllNodes(limit = 50)
        }
    }

    fun updateNodeStatus(nodeId: Int, newStatus: String) {
        viewModelScope.launch(Dispatchers.IO) {
            graphDao.updateNodeStatus(nodeId, newStatus)
            appendLog("✏️ Node $nodeId marked as $newStatus")
            loadGraphNodes() // Refresh UI
        }
    }

    fun updateNodeText(nodeId: Int, newText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            graphDao.updateNodeContent(nodeId, newText)
            appendLog("✏️ Node $nodeId content updated")
            loadGraphNodes() // Refresh UI
        }
    }

    fun deleteNodePermanently(nodeId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            graphDao.deleteNodePermanently(nodeId)
            appendLog("🗑️ Node $nodeId permanently deleted from database.")
            loadGraphNodes() // Refresh UI
        }
    }

    // --- MANUAL VAULT EXTRACTION ---
    fun extractTranscriptToGraph(transcript: com.mine.app.data.local.db.entity.TranscriptEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            appendLog("🧠 Manually extracting transcript to Graph...")
            try {
                val apiKey = com.mine.app.BuildConfig.NVIDIA_API_KEY_AGENT
                // Tag it so the AI knows it's a manual extraction
                val taggedTranscript = "[MANUAL EXTRACTION] [${transcript.speaker}]: ${transcript.text}"
                
                val result = curatorAgent.processTranscript(apiKey, taggedTranscript)
                appendLog("⚙️ Extraction Result: $result")
                loadGraphNodes() // Refresh UI to show the new node!
            } catch (e: Exception) {
                appendLog("❌ Extraction Error: ${e.message}")
            }
        }
    }

    fun addManualTodo(task: String) {
        viewModelScope.launch(Dispatchers.IO) {
            appendLog("✏️ Manual Entry: Routing '$task' through ToolExecutor for Embedding...")
            
            // We build a simple JSON payload to force the ToolExecutor to handle it properly
            val manualJson = """
                {
                  "discard_transcript": false,
                  "memory_operations": [
                    {
                      "action": "create",
                      "content": "$task",
                      "branch": "tasks",
                      "confidence": 1.0
                    }
                  ],
                  "trigger_nudge": false
                }
            """.trimIndent()

            val success = toolExecutor.executeUpdateMemoryGraph(BuildConfig.NVIDIA_API_KEY_AGENT, manualJson)
            
            if (success) {
                appendLog("✅ Saved manually and Vector Embedding generated!")
            } else {
                appendLog("❌ Failed to save manual entry.")
            }

            fetchAndDisplayDatabase()
            loadGraphNodes()
        }
    }

    fun forceChronosWakeup() {
        viewModelScope.launch(Dispatchers.IO) {
            appendLog("⏰ Forcing Chronos Worker to wake up...")
            chronosAgent.runProactiveCheck(BuildConfig.NVIDIA_API_KEY_AGENT)
            appendLog("⏰ Chronos check complete. Check Chat UI to see if it sent a message!")
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
                val apiKey = BuildConfig.NVIDIA_API_KEY_CHAT // Replace with real key

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
                val apiKey = BuildConfig.NVIDIA_API_KEY_AGENT // Replace with real key

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
            val success = toolExecutor.executeUpdateMemoryGraph(BuildConfig.NVIDIA_API_KEY_AGENT, mockJson)
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
                    // Now it prints: [ID: 1] [TRASHED] [ambient]: User wants pizza
                    appendLog("[ID: ${node.id}] [${node.status.uppercase()}] [${node.source}]: ${node.content}")
                }
            }

            // --- NEW: PRINT THE EDGES ---
            val edges = graphDao.getAllEdges(limit = 20)
            if (edges.isNotEmpty()) {
                appendLog("\n--- RELATIONSHIPS (EDGES) ---")
                edges.forEach { edge ->
                    appendLog("[Node ${edge.sourceNodeId}] --(${edge.relation})--> [Node ${edge.targetNodeId}]")
                }
            }
            loadGraphNodes()
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
                    val apiKey = BuildConfig.NVIDIA_API_KEY_WHISPER

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

    // --- CALL REFLECTION STATE ---
    private val _showReflectionDialog = MutableStateFlow(false)
    val showReflectionDialog: StateFlow<Boolean> = _showReflectionDialog

    fun setShowReflectionDialog(show: Boolean) {
        _showReflectionDialog.value = show
    }

    fun processCallReflection() {
        _isRecording.value = false
        _showReflectionDialog.value = false
        appendLog("⏹️ Reflection recorded. Processing...")

        viewModelScope.launch(Dispatchers.IO) {
            val audioBytes = audioRecorderManager.stopAndGetAudio()
            try {
                val apiKey = BuildConfig.NVIDIA_API_KEY_WHISPER
                val transcript = nimWhisperClient.transcribeAudio(apiKey, audioBytes)

                if (transcript.isNotBlank()) {
                    appendLog("📞 Call Reflection: \"$transcript\"")
                    // Prepend a tag so the AI knows the context!
                    val taggedTranscript = "[POST-CALL REFLECTION]: $transcript"
                    val result = curatorAgent.processTranscript(apiKey, taggedTranscript)
                    appendLog("⚙️ Curator Result: $result")
                    fetchAndDisplayDatabase()
                }
            } catch (e: Exception) {
                appendLog("❌ Reflection Error: ${e.message}")
            }
        }
    }
}
