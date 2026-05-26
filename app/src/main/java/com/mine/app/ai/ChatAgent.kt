package com.mine.app.ai

import com.mine.app.data.local.db.dao.ChatDao
import com.mine.app.data.local.db.dao.GraphDao
import com.mine.app.data.local.db.dao.QueueDao
import com.mine.app.data.local.db.entity.ChatMessageEntity
import com.mine.app.data.local.db.entity.MemoryQueueEntity
import com.mine.app.data.remote.NimApi
import com.mine.app.data.remote.dto.NimChatRequest
import com.mine.app.data.remote.dto.NimEmbeddingRequest
import com.mine.app.data.remote.dto.NimMessage
import com.mine.app.util.NetworkMonitor
import com.mine.app.util.PreferencesManager
import com.mine.app.util.VectorUtils
import javax.inject.Inject

class ChatAgent @Inject constructor(
    private val nimApi: NimApi,
    private val graphDao: GraphDao,
    private val chatDao: ChatDao,
    private val queueDao: QueueDao,
    private val networkMonitor: NetworkMonitor,
    private val prefs: PreferencesManager
) {
    suspend fun sendMessage(apiKey: String, userMessage: String): String {
        
        // 1. Save User Message to Chat UI History
        chatDao.insertMessage(ChatMessageEntity(dayTimestamp = 0L, role = "user", content = userMessage))

        // 2. SECRET DELEGATION: Queue the message for the Background Curator to process later!
        queueDao.insertMemory(
            MemoryQueueEntity(rawText = userMessage, source = "chat", status = "pending")
        )

        // 3. FAST CONTEXT RETRIEVAL: Vector Search the local DB
        val embReq = NimEmbeddingRequest(input = listOf(userMessage), inputType = "query")
        val userVector = nimApi.getEmbeddings(apiKey, embReq).data[0].embedding.toFloatArray()
        
        val allEmbeddings = graphDao.getAllEmbeddings()
        val topNodeIds = allEmbeddings.map { dbEmb ->
            val dbVector = VectorUtils.byteArrayToFloatArray(dbEmb.embedding)
            Pair(dbEmb.node_id, VectorUtils.cosineSimilarity(userVector, dbVector))
        }.filter { it.second > 0.2f }.sortedByDescending { it.second }.take(5).map { it.first }

        val contextBuilder = StringBuilder()
        topNodeIds.forEach { id ->
            graphDao.getNodeById(id)?.let { node ->
                contextBuilder.append("- ${node.content}\n")
            }
        }

        // 4. Build the Impeccable System Prompt
        val currentTime = com.mine.app.util.TimeUtils.getCurrentTimestamp()
        val locationContext = if (prefs.isLocationContextEnabled) {
            "\n[SPATIAL CONTEXT: User is currently on ${networkMonitor.networkType.value}]"
        } else ""
        val basePrompt = com.mine.app.util.PersonaManager.getSystemPrompt(com.mine.app.util.PersonaType.OPERATOR)

        // 4. Build the Impeccable System Prompt
        val systemPrompt = """
            [CURRENT SYSTEM TIME: $currentTime]
            
            $locationContext
            
            $basePrompt
            
            [EXISTING CONTEXT]
            $contextBuilder
        """.trimIndent()


        // 5. Fetch recent chat history so Kimi remembers the flow of the conversation
        val recentHistory = chatDao.getRecentMessagesList(limit = 10).reversed().map {
            NimMessage(role = it.role, content = it.content)
        }

        // 6. Build the Request for Kimi K2.6
        val messages = mutableListOf(NimMessage(role = "system", content = systemPrompt))
        messages.addAll(recentHistory) // History already includes the user's new message from Step 1

        val request = NimChatRequest(
            model = "moonshotai/kimi-k2.6", // <-- Using Kimi!
            messages = messages,
            maxTokens = 1024
        )

        // 7. Call Kimi and Save Response
        val response = nimApi.getChatCompletion(apiKey, request)
        val aiReply = response.choices.firstOrNull()?.message?.content ?: "..."

        chatDao.insertMessage(ChatMessageEntity(dayTimestamp = 0L, role = "mine", content = aiReply))

        return aiReply
    }
}
