package com.mine.app.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mine.app.BuildConfig
import com.mine.app.ai.CuratorAgent
import com.mine.app.data.local.db.dao.QueueDao
import com.mine.app.data.remote.NimWhisperClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class QueueWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val queueDao: QueueDao,
    private val whisperClient: NimWhisperClient,
    private val curatorAgent: CuratorAgent
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("QueueWorker", "Waking up to process offline backlog...")
        
        val apiKeyWhisper = BuildConfig.NVIDIA_API_KEY_WHISPER
        val apiKeyAgent = BuildConfig.NVIDIA_API_KEY_AGENT

        try {
            val pendingMemories = queueDao.getPendingMemories(limit = 10)
            if (pendingMemories.isEmpty()) {
                Log.d("QueueWorker", "Queue is clean.")
                return Result.success()
            }

            for (memory in pendingMemories) {
                Log.d("QueueWorker", "Processing offline memory ID: ${memory.id}")
                
                var transcriptToProcess = ""

                // 1. If it's an audio blob (from Ambient), transcribe it first
                if (memory.audioBlob != null) {
                    val transcript = whisperClient.transcribeAudio(apiKeyWhisper, memory.audioBlob)
                    if (transcript.isNotBlank()) {
                        transcriptToProcess = "[${memory.speakerLabel}]: $transcript"
                    }
                } 
                // 2. If it's raw text (from an offline Chat or Manual entry)
                else if (!memory.rawText.isNullOrBlank()) {
                    transcriptToProcess = memory.rawText
                }

                // 3. Send to Curator
                if (transcriptToProcess.isNotBlank()) {
                    curatorAgent.processTranscript(apiKeyAgent, transcriptToProcess)
                }

                // 4. DELETE the memory so the audio blob doesn't bloat the database!
                queueDao.deleteMemory(memory.id)
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e("QueueWorker", "Failed to process queue: ${e.message}")
            return Result.retry() // Tells Android to try again when internet is better
        }
    }
}
