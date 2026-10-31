package org.fossify.messages.models

import androidx.room.ColumnInfo

/** Lightweight projection of the persisted AI columns of a message. */
data class AiMessageMetadata(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "ai_notification_reasoning") val reasoning: String,
    @ColumnInfo(name = "ai_category") val category: String,
)
