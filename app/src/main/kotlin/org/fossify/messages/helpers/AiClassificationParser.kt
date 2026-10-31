package org.fossify.messages.helpers

import org.fossify.messages.models.AiCategory
import org.fossify.messages.models.AiClassification
import org.json.JSONObject

/**
 * Tolerant parser for the JSON document returned by the AI backend.
 *
 * The model may wrap its answer in markdown code fences or surround it with prose, so the first
 * balanced `{...}` object is extracted before parsing. Any malformed or incomplete document
 * returns `null` so callers can fall back to the normal notification path.
 */
object AiClassificationParser {
    private const val MAX_REASONING_LENGTH = 120
    private const val MIN_OTP_LENGTH = 4
    private const val MAX_OTP_LENGTH = 8
    private const val FENCE = "```"

    fun parse(raw: String): AiClassification? {
        val json = extractJsonObject(raw) ?: return null
        val obj = parseObjectOrNull(json) ?: return null
        val notify = obj.opt("notify") as? Boolean
        val category = AiCategory.matching(obj.opt("category") as? String)
        val reasoning = (obj.opt("reasoning") as? String).orEmpty().trim().take(MAX_REASONING_LENGTH)
        val otp = sanitizeOtp((obj.opt("otp") as? String).orEmpty())
        if (notify == null || category == null) return null
        return AiClassification(notify = notify, category = category, reasoning = reasoning, otp = otp)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parseObjectOrNull(json: String): JSONObject? {
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            null
        }
    }

    /** Returns [raw] when it is a plain 4..8 digit code, otherwise an empty string. */
    fun sanitizeOtp(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length !in MIN_OTP_LENGTH..MAX_OTP_LENGTH) return ""
        if (!trimmed.all { it in '0'..'9' }) return ""
        return trimmed
    }

    @Suppress("CyclomaticComplexMethod")
    private fun extractJsonObject(raw: String): String? {
        val text = stripCodeFences(raw)
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString && escaped) {
                escaped = false
            } else if (inString && char == '\\') {
                escaped = true
            } else if (inString && char == '"') {
                inString = false
            } else if (!inString && char == '"') {
                inString = true
            } else if (!inString && char == '{') {
                depth++
            } else if (!inString && char == '}') {
                depth--
                if (depth == 0) return text.substring(start, index + 1)
            }
        }
        return null
    }

    private fun stripCodeFences(raw: String): String {
        var text = raw.trim()
        if (text.startsWith(FENCE)) {
            text = text.substring(FENCE.length).trimStart()
            val newline = text.indexOf('\n')
            if (newline >= 0 && !text.substring(0, newline).contains('{')) {
                text = text.substring(newline + 1)
            }
        }
        if (text.endsWith(FENCE)) {
            text = text.removeSuffix(FENCE).trimEnd()
        }
        return text
    }
}
