package com.mine.app.ui

import android.content.Context
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mine.app.ai.CuratorAgent
import com.mine.app.ai.ToolExecutor
import com.mine.app.data.local.db.dao.GraphDao
import com.mine.app.data.local.db.entity.BranchEntity
import com.mine.app.data.local.db.entity.NodeEntity
import com.mine.app.data.remote.NimApi
import com.mine.app.data.remote.RivaClientManager
import com.mine.app.data.remote.dto.NimChatRequest
import com.mine.app.data.remote.dto.NimMessage
import com.mine.app.util.AudioRecorder
import com.mine.app.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val toolExecutor: ToolExecutor,
    private val graphDao: GraphDao,
    private val nimApi: NimApi,
    private val curatorAgent: CuratorAgent,
    private val rivaClientManager: RivaClientManager,
    networkMonitor: NetworkMonitor,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val isOnline = networkMonitor.isOnline

    private val _logText = MutableStateFlow("System Ready. Waiting for input...\n")
    val logText: StateFlow<String> = _logText

    private val audioRecorder = AudioRecorder(context)
    private val audioStream = java.io.ByteArrayOutputStream()
    private var mediaPlayer: MediaPlayer? = null
    private var lastAudioFile: File? = null

    fun appendLog(msg: String) {
        _logText.value = _logText.value + "\n" + msg
    }

    fun startRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            appendLog("🎤 Recording started...")
            audioStream.reset()
            audioRecorder.startRecording()

            val buffer = ByteArray(2048)
            while (audioRecorder.isRecording) {
                val read = audioRecorder.readAudioChunk(buffer)
                if (read > 0) {
                    audioStream.write(buffer, 0, read)
                }
                kotlinx.coroutines.delay(10)
            }
        }
    }

    fun stopRecordingAndProcess() {
        viewModelScope.launch(Dispatchers.IO) {
            appendLog("🛑 Stopping recording...")
            audioRecorder.stopRecording()

            val rawPcmAudio = audioStream.toByteArray()
            if (rawPcmAudio.isEmpty()) {
                appendLog("❌ Audio file was empty!")
                return@launch
            }

            // --- SAVE AUDIO FOR PLAYBACK ---
            saveAsWavFile(rawPcmAudio)
            // ADD THIS: Read the WAV file we just created (which includes the 44-byte header!)
            val wavBytes = lastAudioFile!!.readBytes()

            appendLog("🌐 Sending ${wavBytes.size} bytes to NVIDIA Riva ASR...")

            try {
                // TODO: Put your real NIM API key here
                val apiKey = "YOU_API_KEY"

                // SEND wavBytes instead of rawPcmAudio
                val transcript = rivaClientManager.transcribeAudio(apiKey, wavBytes)
                appendLog("📝 Riva Transcript: $transcript")

                if (transcript.isNotBlank()) {
                    appendLog("🧠 Passing transcript to Curator...")
                    val result = curatorAgent.processTranscript(apiKey, transcript)
                    appendLog("⚙️ Curator Result: $result")
                    fetchAndDisplayDatabase()
                } else {
                    appendLog("❌ NVIDIA returned an empty transcript. Try playing the audio to see if it's silent.")
                }

            } catch (e: Exception) {
                appendLog("❌ Riva Error: ${e.message}")
            }
        }
    }

    // --- AUDIO PLAYBACK LOGIC ---

    fun playLastRecording() {
        if (lastAudioFile == null || !lastAudioFile!!.exists()) {
            appendLog("❌ No audio file found to play.")
            return
        }

        try {
            appendLog("🔊 Playing last recording...")
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(lastAudioFile!!.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    appendLog("🔇 Playback finished.")
                }
            }
        } catch (e: Exception) {
            appendLog("❌ Playback failed: ${e.message}")
        }
    }

    private fun saveAsWavFile(pcmData: ByteArray) {
        try {
            lastAudioFile = File(context.cacheDir, "debug_recording.wav")
            val out = FileOutputStream(lastAudioFile)

            // Generate 44-byte WAV Header
            val totalDataLen = pcmData.size + 36
            val sampleRate = 16000
            val channels = 1
            val bitRate = 16
            val byteRate = sampleRate * channels * (bitRate / 8)
            val header = ByteArray(44)

            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte(); header[5] = (totalDataLen shr 8 and 0xff).toByte(); header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] = (totalDataLen shr 24 and 0xff).toByte()
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
            header[20] = 1; header[21] = 0; header[22] = channels.toByte(); header[23] = 0
            header[24] = (sampleRate and 0xff).toByte(); header[25] = (sampleRate shr 8 and 0xff).toByte(); header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] = (sampleRate shr 24 and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte(); header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
            header[32] = (channels * (bitRate / 8)).toByte(); header[33] = 0; header[34] = bitRate.toByte(); header[35] = 0
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            header[40] = (pcmData.size and 0xff).toByte(); header[41] = (pcmData.size shr 8 and 0xff).toByte(); header[42] = (pcmData.size shr 16 and 0xff).toByte(); header[43] = (pcmData.size shr 24 and 0xff).toByte()

            out.write(header)
            out.write(pcmData)
            out.close()
            appendLog("💾 Saved WAV file for playback.")
        } catch (e: Exception) {
            appendLog("❌ Failed to save WAV: ${e.message}")
        }
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
                val apiKey = "YOU_API_KEY"

                val request = NimChatRequest(
                    model = "openai/gpt-oss-20b",
                    messages = listOf(
                        NimMessage(
                            role = "system",
                            content = "You are a helpful assistant. Reply in exactly one short sentence."
                        ),
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
                val apiKey = "YOU_API_KEY"

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

            // Use getAllNodes instead of searchNodes for the debug view
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
}
