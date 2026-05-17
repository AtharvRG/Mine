package com.mine.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mine.app.BuildConfig
import com.mine.app.ai.ChronosAgent
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ChronosWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val chronosAgent: ChronosAgent
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // In a real production app, we would fetch the API key from EncryptedSharedPreferences.
        // For now, we use the BuildConfig key.
        val apiKey = BuildConfig.NVIDIA_API_KEY

        if (apiKey.isBlank()) return Result.failure()

        chronosAgent.runProactiveCheck(apiKey)

        return Result.success()
    }
}
