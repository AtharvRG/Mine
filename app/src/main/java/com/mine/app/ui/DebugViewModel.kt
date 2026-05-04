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

// UI Imports for DebugScreen
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel

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

    // --- AMBIENT SERVICE STATE ---
    private val _isAmbientRunning = MutableStateFlow(false)
    val isAmbientRunning: StateFlow<Boolean> = _isAmbientRunning

    fun setAmbientRunning(isRunning: Boolean) {
        _isAmbientRunning.value = isRunning
        if (isRunning) {
            appendLog("🟢 Ambient Listening Service Started in Background.")
        } else {
            appendLog("⚪ Ambient Listening Service Stopped.")
        }
    }

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

            // --- NEW: PRINT THE EDGES ---
            val edges = graphDao.getAllEdges(limit = 20)
            if (edges.isNotEmpty()) {
                appendLog("\n--- RELATIONSHIPS (EDGES) ---")
                edges.forEach { edge ->
                    appendLog("[Node ${edge.sourceNodeId}] --(${edge.relation})--> [Node ${edge.targetNodeId}]")
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

// --- DEBUG UI ---

@Composable
fun DebugScreen(viewModel: DebugViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val logText by viewModel.logText.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isAmbientRunning by viewModel.isAmbientRunning.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Mine Debug Console", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(20.dp))

        Button(onClick = { viewModel.runRealAiTest() }, modifier = Modifier.fillMaxWidth()) {
            Text("Run Real AI Test")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = { viewModel.runFullCuratorLoop() }, modifier = Modifier.fillMaxWidth()) {
            Text("Run Full Curator Loop")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = { viewModel.runMockToolExecution() }, modifier = Modifier.fillMaxWidth()) {
            Text("Run Mock Tool Execution")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = { viewModel.fetchAndDisplayDatabase() }, modifier = Modifier.fillMaxWidth()) {
            Text("Fetch Database State")
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Standard Recording Toggle
        Button(
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.toggleRecording()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) Color.Red else Color.Gray)
        ) {
            Text(if (isRecording) "🔴 STOP RECORDING" else "🎙️ START RECORDING")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- AMBIENT LISTENING TOGGLE ---
        Button(
            onClick = {
                val intent = android.content.Intent(context, com.mine.app.service.AmbientListeningService::class.java)
                if (isAmbientRunning) {
                    intent.action = "STOP"
                    context.startService(intent)
                    viewModel.setAmbientRunning(false)
                } else {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        context.startForegroundService(intent)
                        viewModel.setAmbientRunning(true)
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isAmbientRunning) Color(0xFF4CAF50) else Color.DarkGray
            )
        ) {
            Text(if (isAmbientRunning) "🟢 Ambient Listening ON" else "⚪ Turn On Ambient Listening")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Log Console
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(Color.Black)
                .padding(8.dp)
        ) {
            Text(
                text = logText,
                color = Color.Green,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
