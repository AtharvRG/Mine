package com.mine.app.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mine.app.BuildConfig
import com.mine.app.ai.CuratorAgent
import com.mine.app.data.local.db.dao.ChatDao
import com.mine.app.data.local.db.dao.GraphDao
import com.mine.app.util.TimeUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class PruningWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val graphDao: GraphDao,
    private val chatDao: ChatDao,
    private val curatorAgent: CuratorAgent
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("PruningWorker", "Starting Nightly Pruning & Summarization...")

        try {
            // 1. Delete old trashed nodes (Older than 7 days)
            val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
            val deletedCount = graphDao.deleteOldTrashedNodes(sevenDaysAgo)
            Log.d("PruningWorker", "Permanently deleted $deletedCount trashed nodes.")

            // 2. Summarize and clear old chat logs
            val startOfToday = TimeUtils.getStartOfDayTimestamp()
            val oldChats = chatDao.getOldMessagesList(startOfToday)

            if (oldChats.isNotEmpty()) {
                Log.d("PruningWorker", "Found ${oldChats.size} old chat messages. Summarizing...")
                
                // Format the chat log into a single transcript
                val chatTranscript = oldChats.joinToString("\n") { "[${it.role}]: ${it.content}" }
                
                // TRUNCATE to the last 15,000 characters to prevent LLM Token Overflow crashes!
                val safeChatTranscript = if (chatTranscript.length > 15000) {
                    "..." + chatTranscript.takeLast(15000)
                } else {
                    chatTranscript
                }
                
                val taggedTranscript = "[DAILY CHAT SUMMARY]\n$safeChatTranscript"

                // Send it to the 120B Curator to extract any final commitments
                val apiKey = BuildConfig.NVIDIA_API_KEY_AGENT
                curatorAgent.processTranscript(apiKey, taggedTranscript)

                // Delete the old messages to keep the database light and fast
                chatDao.deleteOldMessages(startOfToday)
                Log.d("PruningWorker", "Old chats summarized and deleted.")
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e("PruningWorker", "Pruning failed: ${e.message}")
            return Result.retry()
        }
    }
}
