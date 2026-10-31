package org.fossify.messages.models

/**
 * Categories produced by the AI classifier. [wireName] is the lowercase token used by the model,
 * while the enum constants stay PascalCase to satisfy the project's detekt naming rules.
 */
enum class AiCategory(val wireName: String) {
    Personal("personal"),
    Otp("otp"),
    Marketing("marketing"),
    Transaction("transaction"),
    Updates("updates"),
    Spam("spam"),
    Irrelevant("irrelevant"),
    ;

    companion object {
        /** Maps a raw wire token to a category, ignoring case and surrounding whitespace. */
        fun fromWire(raw: String?): AiCategory? {
            val normalized = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == normalized }
        }

        /** Like [fromWire], but also accepts the enum constant name (e.g. "Personal"). */
        fun matching(value: String?): AiCategory? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull {
                it.wireName == normalized || it.name.lowercase() == normalized
            }
        }
    }
}
