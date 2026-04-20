package com.mine.app.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mine.app.data.local.db.dao.QueueDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class QueueWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val queueDao: QueueDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("QueueWorker", "Waking up to process offline backlog...")

        try {
            // 1. Fetch pending audio/transcripts that happened while offline
            val pendingMemories = queueDao.getPendingMemories(limit = 10)

            if (pendingMemories.isEmpty()) {
                Log.d("QueueWorker", "No pending memories. Queue is clean.")
                return Result.success()
            }

            for (memory in pendingMemories) {
                Log.d("QueueWorker", "Processing offline memory ID: ${memory.id}")

                // TODO: In the future, we will send memory.audioBlob to Groq here,
                // then send the transcript to the ToolExecutor.

                // For now, we just mark it as processed so it leaves the queue
                queueDao.updateMemoryStatus(memory.id, "completed")
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e("QueueWorker", "Failed to process queue: ${e.message}")
            return Result.retry() // Tells Android to try again later
        }
    }
}
