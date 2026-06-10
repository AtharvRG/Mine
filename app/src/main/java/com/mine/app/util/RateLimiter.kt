package com.mine.app.util

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RateLimiter @Inject constructor() {
    private val maxRequestsPerMinute = 39
    private val windowMillis = 60_000L

    // Tracks timestamps of requests for each API key
    private val requestLogs = mutableMapOf<String, MutableList<Long>>()
    private val mutex = Mutex()

    suspend fun waitAndConsume(apiKey: String) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val logs = requestLogs.getOrPut(apiKey) { mutableListOf() }

            // Remove timestamps older than 60 seconds
            logs.removeAll { it < now - windowMillis }

            if (logs.size >= maxRequestsPerMinute) {
                // We hit the limit! Calculate how long to wait until the oldest request expires
                val oldest = logs.first()
                val waitTime = (oldest + windowMillis) - now + 100 // Add 100ms buffer
                Log.w("RateLimiter", "⚠️ 39 RPM limit reached for key ending in ${apiKey.takeLast(4)}. Pausing for ${waitTime}ms...")
                delay(waitTime)
                
                // After waiting, clean up again
                val newNow = System.currentTimeMillis()
                logs.removeAll { it < newNow - windowMillis }
                logs.add(newNow)
            } else {
                logs.add(now)
            }
        }
    }
}
