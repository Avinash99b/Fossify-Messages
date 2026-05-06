package org.fossify.messages.helpers

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AiDecisionLogger {
    private const val TAG = "AiDecisionLogger"

    fun logDecision(
        context: Context,
        senderName: String,
        message: String,
        notify: Boolean,
        reasoning: String,
        category: String
    ) {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateString = formatter.format(Date())
        val logMessage = "[$dateString] From: $senderName | Notify: $notify | Category: $category | Reasoning: $reasoning"

        // Log to system log
        Log.i(TAG, logMessage)

        // You can extend this to store logs persistently
        // For now, logs are available via Android Logcat
    }

    fun getLogDisplayText(
        notify: Boolean,
        reasoning: String,
        category: String
    ): String = "$category • ${if (notify) "Show" else "Hide"} • $reasoning"
}

