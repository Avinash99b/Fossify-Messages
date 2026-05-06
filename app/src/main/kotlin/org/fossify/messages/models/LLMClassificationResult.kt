package org.fossify.messages.models

data class LLMClassificationResult(
    val notify: Boolean,
    val category: Category,
    val reasoning: String = ""
) {
    enum class Category {
        normal, spam, irrelevant
    }
}

