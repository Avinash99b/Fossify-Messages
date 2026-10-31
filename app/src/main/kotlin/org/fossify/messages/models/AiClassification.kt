package org.fossify.messages.models

/** Outcome of classifying a single message with the AI backend. */
data class AiClassification(
    val notify: Boolean,
    val category: AiCategory,
    val reasoning: String,
    val otp: String,
)
