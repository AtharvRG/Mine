package com.anchor.mine.brain

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object DataExporter {
    suspend fun exportLifeGraph(context: Context): String = withContext(Dispatchers.IO) {
        try {
            val db = MineDatabase.getDatabase(context)

            // We use L2 because it contains the clean, consolidated memories
            // (We can't use the Flow here, so we need a quick one-off query)
            val memories = db.query("SELECT * FROM memory_nodes_l2", null)

            val exportFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "MineAI_LifeGraph_${System.currentTimeMillis()}.jsonl"
            )

            FileOutputStream(exportFile).bufferedWriter().use { writer ->
                while (memories.moveToNext()) {
                    val id = memories.getInt(memories.getColumnIndexOrThrow("id"))
                    val text = memories.getString(memories.getColumnIndexOrThrow("summaryText"))
                    val timestamp = memories.getLong(memories.getColumnIndexOrThrow("timestamp"))

                    val jsonObj = JSONObject().apply {
                        put("id", id)
                        put("timestamp", timestamp)
                        put("memory", text)
                    }

                    // JSON-L format: One valid JSON object per line
                    writer.write(jsonObj.toString())
                    writer.newLine()
                }
            }
            memories.close()

            Log.d("DataExporter", "Exported successfully to: ${exportFile.absolutePath}")
            return@withContext "Exported to Downloads folder!"

        } catch (e: Exception) {
            Log.e("DataExporter", "Export failed: ${e.message}")
            return@withContext "Export failed."
        }
    }
}