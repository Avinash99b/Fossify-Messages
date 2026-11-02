package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.models.AiClassification
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A fully resolved HTTP request, built before any network access happens. */
data class AiHttpRequest(val url: String, val headers: Map<String, String>, val body: String)

/** Result of one AI classification attempt. */
sealed interface AiResult {
    data class Classified(val classification: AiClassification) : AiResult
    data class Failure(val reason: String) : AiResult
}

private sealed interface AiHttpResponse {
    data class Success(val body: String) : AiHttpResponse
    data class Error(val code: Int) : AiHttpResponse
}

/**
 * Blocking AI classifier built on [HttpURLConnection].
 *
 * [classify] performs network I/O and must be called off the main thread. The API key is only ever
 * sent in a request header; it never appears in the URL. Any failure is surfaced as
 * [AiResult.Failure] so callers can fall back to the normal notification path.
 */
@Suppress("TooManyFunctions")
object AiRequestHelper {
    // Kept short because classification runs inside a broadcast receiver's goAsync window.
    private const val CONNECT_TIMEOUT_MILLIS = 3000
    private const val READ_TIMEOUT_MILLIS = 5000
    private const val MAX_RESPONSE_BYTES = 65536
    private const val READ_BUFFER_SIZE = 4096
    private const val HTTP_SUCCESS_MIN = 200
    private const val HTTP_SUCCESS_MAX = 299
    private const val CONTENT_TYPE_JSON = "application/json"
    private const val HEADER_CONTENT_TYPE = "Content-Type"
    private const val HEADER_ACCEPT = "Accept"
    private const val HEADER_AUTHORIZATION = "Authorization"
    private const val HEADER_GEMINI_API_KEY = "x-goog-api-key"
    private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private const val GEMINI_ENDPOINT_PREFIX = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val GEMINI_ENDPOINT_SUFFIX = ":generateContent"
    private const val REASON_NOT_CONFIGURED = "AI is not configured"
    private const val REASON_UNPARSEABLE = "AI response could not be parsed"
    private const val REASON_REQUEST_FAILED = "AI request failed"
    private const val REDACTED = "***"

    fun defaultPrompt(): String = DEFAULT_AI_PROMPT

    /**
     * Classifies [message] sent by [senderName] using the configured provider.
     *
     * Blocking; the caller is responsible for running this off the main thread.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun classify(context: Context, senderName: String, message: String): AiResult {
        val config = AiConfigProvider(context)
        if (!config.isConfigured()) {
            return AiResult.Failure(REASON_NOT_CONFIGURED)
        }
        val key = config.apiKey
        val maxAttempts = 4
        var lastFailure: AiResult.Failure? = null

        val prompt = buildFullPrompt(config.customPrompt, senderName, message)
        val request = buildRequest(config.provider, config.model, config.customEndpoint, key, prompt)

        for (attempt in 1..maxAttempts) {
            try {
                when (val response = sendRequest(request)) {
                    is AiHttpResponse.Success -> {
                        val result = parseResponse(config.provider, response.body)
                        if (result is AiResult.Classified) {
                            return result
                        }
                        lastFailure = if (result is AiResult.Failure) result else AiResult.Failure(REASON_UNPARSEABLE)
                    }
                    is AiHttpResponse.Error -> {
                        lastFailure = AiResult.Failure("HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                lastFailure = AiResult.Failure(sanitizeMessage(e.message, key))
            }
            if (attempt < maxAttempts) {
                Thread.sleep(1000)
            }
        }

        return lastFailure ?: AiResult.Failure(REASON_REQUEST_FAILED)
    }

    /**
     * Builds the provider-specific HTTP request without performing I/O. Kept pure so tests can
     * assert that the key is never embedded in the URL.
     */
    fun buildRequest(
        provider: String,
        model: String,
        endpoint: String,
        key: String,
        prompt: String,
    ): AiHttpRequest {
        return when (provider.lowercase()) {
            AiConfigProvider.PROVIDER_OPENAI -> AiHttpRequest(
                url = OPENAI_ENDPOINT,
                headers = bearerHeaders(key),
                body = buildChatBody(model, prompt),
            )

            AiConfigProvider.PROVIDER_OTHER -> AiHttpRequest(
                url = endpoint,
                headers = bearerHeaders(key),
                body = buildChatBody(model, prompt),
            )

            else -> AiHttpRequest(
                url = geminiUrl(model),
                headers = mapOf(
                    HEADER_CONTENT_TYPE to CONTENT_TYPE_JSON,
                    HEADER_ACCEPT to CONTENT_TYPE_JSON,
                    HEADER_GEMINI_API_KEY to key,
                ),
                body = buildGeminiBody(prompt),
            )
        }
    }

    private fun sendRequest(request: AiHttpRequest): AiHttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            doOutput = true
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            connection.outputStream.use { output ->
                output.write(request.body.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            if (code in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                AiHttpResponse.Success(readLimited(connection.inputStream))
            } else {
                connection.errorStream?.let { readLimited(it) }
                AiHttpResponse.Error(code)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(provider: String, body: String): AiResult {
        val text = if (isOpenAiCompatible(provider)) {
            extractOpenAiText(body)
        } else {
            extractGeminiText(body)
        }
        val classification = text?.let { AiClassificationParser.parse(it) }
        return if (classification == null) {
            AiResult.Failure(REASON_UNPARSEABLE)
        } else {
            AiResult.Classified(classification)
        }
    }

    private fun extractGeminiText(body: String): String? {
        val candidates = JSONObject(body).optJSONArray("candidates") ?: return null
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null
        return parts.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
    }

    private fun extractOpenAiText(body: String): String? {
        val choices = JSONObject(body).optJSONArray("choices") ?: return null
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
        return message.optString("content").takeIf { it.isNotBlank() }
    }

    private fun readLimited(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var total = 0
        stream.use { input ->
            while (total < MAX_RESPONSE_BYTES) {
                val remaining = MAX_RESPONSE_BYTES - total
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
            }
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    private fun buildGeminiBody(prompt: String): String {
        val part = JSONObject().put("text", prompt)
        val content = JSONObject().put("parts", JSONArray().put(part))
        val contents = JSONArray().put(content)
        return JSONObject().put("contents", contents).toString()
    }

    private fun buildChatBody(model: String, prompt: String): String {
        val message = JSONObject().put("role", "user").put("content", prompt)
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(message))
            .put("temperature", 0)
            .toString()
    }

    private fun buildFullPrompt(systemPrompt: String, senderName: String, message: String): String {
        val sender = senderName.trim().ifEmpty { "unknown" }
        return buildString {
            append(systemPrompt)
            append("\n\nSender: ")
            append(sender)
            append("\nMessage: ")
            append(message)
        }
    }

    private fun bearerHeaders(key: String): Map<String, String> {
        return mapOf(
            HEADER_CONTENT_TYPE to CONTENT_TYPE_JSON,
            HEADER_ACCEPT to CONTENT_TYPE_JSON,
            HEADER_AUTHORIZATION to "Bearer $key",
        )
    }

    private fun isOpenAiCompatible(provider: String): Boolean {
        return provider.equals(AiConfigProvider.PROVIDER_OPENAI, ignoreCase = true) ||
            provider.equals(AiConfigProvider.PROVIDER_OTHER, ignoreCase = true)
    }

    private fun geminiUrl(model: String): String {
        return GEMINI_ENDPOINT_PREFIX + model + GEMINI_ENDPOINT_SUFFIX
    }

    private fun sanitizeMessage(message: String?, key: String): String {
        val base = message?.takeIf { it.isNotBlank() } ?: REASON_REQUEST_FAILED
        return if (key.isBlank()) base else base.replace(key, REDACTED)
    }
}
