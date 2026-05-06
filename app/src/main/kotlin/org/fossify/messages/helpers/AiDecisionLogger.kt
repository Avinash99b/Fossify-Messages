package org.fossify.messages.helpers

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AiDecisionLogger {
    private const val TAG = "AiDecisionLogger"
    private const val PREF_NAME = "ai_logs"
    private const val KEY_LOGS = "logs"
    private const val MAX_LOG_ENTRIES = 200

    data class LogEntry(
        val timestamp: Long,
        val senderName: String,
        val status: String,
        val category: String,
        val reasoning: String,
        val notify: Boolean,
        val messagePreview: String,
    )

    fun logDecision(
        context: Context,
        senderName: String,
        message: String,
        notify: Boolean,
        reasoning: String,
        category: String,
        status: String,
    ) {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateString = formatter.format(Date())
        val logMessage = "[$dateString] From: $senderName | Status: $status | Notify: $notify | Category: $category | Reasoning: $reasoning"

        Log.i(TAG, logMessage)
        persistLog(
            context = context,
            entry = LogEntry(
                timestamp = System.currentTimeMillis(),
                senderName = senderName,
                status = status,
                category = category,
                reasoning = reasoning,
                notify = notify,
                messagePreview = message.take(160),
            )
        )
    }

    fun getLogs(context: Context): List<LogEntry> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LOGS, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        LogEntry(
                            timestamp = obj.optLong("timestamp"),
                            senderName = obj.optString("senderName"),
                            status = obj.optString("status"),
                            category = obj.optString("category"),
                            reasoning = obj.optString("reasoning"),
                            notify = obj.optBoolean("notify"),
                            messagePreview = obj.optString("messagePreview"),
                        )
                    )
                }
            }.sortedByDescending { it.timestamp }
        }.getOrDefault(emptyList())
    }

    @Suppress("unused")
    fun clearLogs(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) { remove(KEY_LOGS) }
    }

    @Suppress("unused")
    fun formatLogEntry(entry: LogEntry): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateString = formatter.format(Date(entry.timestamp))
        val status = entry.status.replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        return buildString {
            appendLine(dateString)
            appendLine("Sender: ${entry.senderName}")
            appendLine("Status: $status")
            appendLine("Category: ${entry.category.ifBlank { "-" }}")
            appendLine("Notify: ${if (entry.notify) "Yes" else "No"}")
            appendLine("Reason: ${entry.reasoning.ifBlank { "No reasoning provided" }}")
            if (entry.messagePreview.isNotBlank()) {
                append("Message: ${entry.messagePreview}")
            }
        }
    }

    private fun persistLog(context: Context, entry: LogEntry) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val updated = getLogs(context).toMutableList().apply {
            add(0, entry)
            if (size > MAX_LOG_ENTRIES) {
                subList(MAX_LOG_ENTRIES, size).clear()
            }
        }

        val array = JSONArray()
        updated.forEach { logEntry ->
            array.put(JSONObject().apply {
                put("timestamp", logEntry.timestamp)
                put("senderName", logEntry.senderName)
                put("status", logEntry.status)
                put("category", logEntry.category)
                put("reasoning", logEntry.reasoning)
                put("notify", logEntry.notify)
                put("messagePreview", logEntry.messagePreview)
            })
        }

        prefs.edit(commit = true) { putString(KEY_LOGS, array.toString()) }
    }
}

