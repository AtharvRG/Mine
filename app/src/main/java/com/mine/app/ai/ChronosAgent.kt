package com.mine.app.ai

import android.util.Log
import com.mine.app.data.local.db.dao.ChatDao
import com.mine.app.data.local.db.dao.GraphDao
import com.mine.app.data.local.db.entity.ChatMessageEntity
import com.mine.app.data.remote.NimApi
import com.mine.app.data.remote.dto.NimChatRequest
import com.mine.app.data.remote.dto.NimMessage
import com.mine.app.util.NotificationHelper
import com.mine.app.util.PersonaManager
import com.mine.app.util.PersonaType
import com.mine.app.util.TimeUtils
import javax.inject.Inject

class ChronosAgent @Inject constructor(
    private val nimApi: NimApi,
    private val graphDao: GraphDao,
    private val chatDao: ChatDao,
    private val notificationHelper: NotificationHelper,
    private val prefs: com.mine.app.util.PreferencesManager
) {

    suspend fun runProactiveCheck(apiKey: String) {
        Log.d("ChronosAgent", "Waking up to check active nodes...")

        val activeNodes = graphDao.getActiveNodes(limit = 20)
        if (activeNodes.isEmpty()) {
            Log.d("ChronosAgent", "No active nodes found. Going back to sleep.")
            return
        }

        val contextBuilder = StringBuilder()
        activeNodes.forEach { node ->
            contextBuilder.append("- ID ${node.id}: ${node.content}\n")
        }

        val currentTime = TimeUtils.getCurrentTimestamp()
        val personaType = com.mine.app.util.PersonaType.valueOf(prefs.currentPersona)
        val basePrompt = com.mine.app.util.PersonaManager.getSystemPrompt(personaType)

        val systemPrompt = """
            [CURRENT SYSTEM TIME: $currentTime]
            
            $basePrompt
            
            You are running a background check on the user's active tasks and memories.
            
            [ACTIVE CONTEXT]
            $contextBuilder
            
            TASK:
            Decide if you need to proactively message the user right now. 
            For example, if a task is due today or tomorrow, or if they haven't updated you on an important goal recently.
            
            RULES:
            1. If NO proactive message is needed right now, reply EXACTLY with the word "SKIP".
            2. If YES, write the exact message you want to send them. Keep it natural, in your persona's tone. Do not include any other text.
        """.trimIndent()

        try {
            val request = NimChatRequest(
                model = "moonshotai/kimi-k2.6",
                messages = listOf(NimMessage(role = "system", content = systemPrompt)),
                maxTokens = 256
            )

            val response = nimApi.getChatCompletion(apiKey, request)
            val reply = response.choices.firstOrNull()?.message?.content?.trim() ?: "SKIP"

            if (reply != "SKIP" && reply.isNotBlank()) {
                Log.d("ChronosAgent", "AI decided to speak: $reply")
                chatDao.insertMessage(ChatMessageEntity(dayTimestamp = TimeUtils.getStartOfDayTimestamp(), role = "mine", content = reply))
                notificationHelper.showNudgeNotification(reply) // <-- NEW
            }
            else {
                Log.d("ChronosAgent", "AI decided to SKIP.")
            }
        } catch (e: Exception) {
            Log.e("ChronosAgent", "Failed to run Chronos check: ${e.message}")
        }
    }
}
