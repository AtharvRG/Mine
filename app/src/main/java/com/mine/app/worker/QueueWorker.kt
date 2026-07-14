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
                
                try {
                    var transcriptToProcess = ""

                    // 1. If it's an audio blob (from Ambient), transcribe it first
                    if (memory.audioBlob != null && memory.audioBlob.isNotEmpty()) {
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
                } catch (itemError: Exception) {
                    Log.e("QueueWorker", "Poison Pill caught! Failed memory ID ${memory.id}: ${itemError.message}")
                    // Mark as failed so it doesn't brick the rest of the queue!
                    queueDao.updateMemoryStatus(memory.id, "failed")
                }
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e("QueueWorker", "Failed to process queue: ${e.message}")

            // --- FIX: Don't retry forever on permanent API errors! ---
            val errorMessage = e.message ?: ""
            if (errorMessage.contains("401") || errorMessage.contains("400") || errorMessage.contains("403")) {
                Log.e("QueueWorker", "Fatal API Error. Canceling worker to save battery.")
                return Result.failure()
            }

            // Only retry for network timeouts or 500 server errors
            return Result.retry()
        }
    }
}
