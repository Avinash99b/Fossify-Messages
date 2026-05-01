package org.fossify.messages.models

data class LLMClassificationResult(
    val notify: Boolean,
    val category: Category
) {
    enum class Category {
        normal, spam, irrelevant
    }
}

