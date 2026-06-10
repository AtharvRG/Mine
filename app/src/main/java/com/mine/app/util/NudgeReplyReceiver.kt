package com.mine.app.util

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.mine.app.BuildConfig
import com.mine.app.ai.ChatAgent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NudgeReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var chatAgent: ChatAgent

    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence("nudge_reply")?.toString()
        val notificationId = intent.getIntExtra("notif_id", 0)

        if (!replyText.isNullOrBlank()) {
            Log.d("NudgeReply", "User replied from notification: $replyText")
            
            // Keep the receiver alive while we do network work
            val pendingResult = goAsync()
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Send the reply to Kimi! (This automatically saves it to the Chat UI too)
                    chatAgent.sendMessage(BuildConfig.NVIDIA_API_KEY_CHAT, replyText)
                    
                    // Update the notification to show a checkmark
                    val repliedNotification = NotificationCompat.Builder(context, "nudge_channel")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentText("Reply sent to MineAI.")
                        .setTimeoutAfter(3000) // Auto-dismiss after 3 seconds
                        .build()
                        
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(notificationId, repliedNotification)
                } catch (e: Exception) {
                    Log.e("NudgeReply", "Failed to send reply: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
