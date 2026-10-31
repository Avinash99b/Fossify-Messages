package org.fossify.messages.helpers

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One persisted AI decision. */
data class AiLogEntry(
    val timestamp: Long,
    val senderName: String,
    val messagePreview: String,
    val notify: Boolean,
    val reasoning: String,
    val category: String,
    val status: String,
)

/**
 * Append-only ring buffer of AI decisions (at most [MAX_ENTRIES]) persisted as a JSON array.
 *
 * Access is synchronized and every write uses `apply()`. Getters never mutate state.
 */
object AiDecisionLogger {
    const val STATUS_NOTIFIED = "notified"
    const val STATUS_SUPPRESSED = "suppressed"
    const val STATUS_ERROR = "error"

    private const val PREFS_NAME = "ai_logs"
    private const val KEY_LOGS = "logs"
    private const val MAX_ENTRIES = 200
    private const val MAX_PREVIEW_LENGTH = 120
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_SENDER = "sender"
    private const val KEY_PREVIEW = "preview"
    private const val KEY_NOTIFY = "notify"
    private const val KEY_REASONING = "reasoning"
    private const val KEY_CATEGORY = "category"
    private const val KEY_STATUS = "status"

    @Synchronized
    fun logDecision(
        context: Context,
        senderName: String,
        messagePreview: String,
        notify: Boolean,
        reasoning: String,
        category: String,
        status: String,
    ) {
        val entry = AiLogEntry(
            timestamp = System.currentTimeMillis(),
            senderName = senderName,
            messagePreview = messagePreview.trim().take(MAX_PREVIEW_LENGTH),
            notify = notify,
            reasoning = reasoning,
            category = category,
            status = status,
        )
        val entries = readEntries(prefs(context))
        entries.add(0, entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.size - 1)
        }
        val array = JSONArray()
        entries.forEach { array.put(formatLogEntry(it)) }
        prefs(context).edit().putString(KEY_LOGS, array.toString()).apply()
    }

    /** Returns the stored decisions, newest first. */
    @Synchronized
    fun getLogs(context: Context): List<AiLogEntry> {
        return readEntries(prefs(context)).sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun clearLogs(context: Context) {
        prefs(context).edit().remove(KEY_LOGS).apply()
    }

    fun formatLogEntry(entry: AiLogEntry): JSONObject {
        return JSONObject()
            .put(KEY_TIMESTAMP, entry.timestamp)
            .put(KEY_SENDER, entry.senderName)
            .put(KEY_PREVIEW, entry.messagePreview)
            .put(KEY_NOTIFY, entry.notify)
            .put(KEY_REASONING, entry.reasoning)
            .put(KEY_CATEGORY, entry.category)
            .put(KEY_STATUS, entry.status)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun readEntries(prefs: SharedPreferences): MutableList<AiLogEntry> {
        val raw = prefs.getString(KEY_LOGS, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val result = mutableListOf<AiLogEntry>()
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                result.add(
                    AiLogEntry(
                        timestamp = obj.optLong(KEY_TIMESTAMP, 0L),
                        senderName = obj.optString(KEY_SENDER, ""),
                        messagePreview = obj.optString(KEY_PREVIEW, ""),
                        notify = obj.optBoolean(KEY_NOTIFY, false),
                        reasoning = obj.optString(KEY_REASONING, ""),
                        category = obj.optString(KEY_CATEGORY, ""),
                        status = obj.optString(KEY_STATUS, ""),
                    )
                )
            }
            result
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
