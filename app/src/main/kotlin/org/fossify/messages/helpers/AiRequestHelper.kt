package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.models.AiCategory
import org.fossify.messages.models.AiClassification
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
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
    data class Error(val code: Int, val retryAfterMillis: Long?) : AiHttpResponse
    object TransportFailure : AiHttpResponse
}

/**
 * Blocking AI classifier built on [HttpURLConnection].
 *
 * [classify] performs network I/O and must be called off the main thread. The API key is only ever
 * sent in a request header; it never appears in the URL. Any failure is surfaced as
 * [AiResult.Failure] so callers can fall back to the normal notification path.
 *
 * Every request is retried on transient transport errors and retryable HTTP statuses, but the whole
 * call is bounded by a time budget so it fits inside a broadcast receiver's `goAsync()` window.
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
    private const val HEADER_RETRY_AFTER = "Retry-After"
    private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private const val GEMINI_ENDPOINT_PREFIX = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val GEMINI_ENDPOINT_SUFFIX = ":generateContent"
    private const val REASON_NOT_CONFIGURED = "AI is not configured"
    private const val REASON_UNPARSEABLE = "AI response could not be parsed"
    private const val REASON_REQUEST_FAILED = "AI request failed"
    private const val REASON_DECIDE_FAILED = "Decide classification failed"
    private const val REDACTED = "***"

    // Retry budget. Total wall clock is capped so a broadcast receiver's goAsync window is respected.
    private const val MAX_ATTEMPTS = 3
    private const val INITIAL_BACKOFF_MILLIS = 250L
    private const val MAX_BACKOFF_MILLIS = 2000L
    private const val TOTAL_BUDGET_MILLIS = 9000L
    private const val MIN_REMAINING_BUDGET_MILLIS = 1000L
    private const val MIN_TIMEOUT_MILLIS = 500
    private const val NANOS_PER_MILLIS = 1_000_000L
    private const val MILLIS_PER_SECOND = 1000L
    private const val NO_REMAINING_BUDGET = 0L

    private const val MAX_REASONING_LENGTH = 120
    private const val NOTIFY_NOUL_THRESHOLD = 0.5
    private const val CATEGORY_RULE_WEIGHT = 2
    private const val NOTIFY_BIAS = 2
    private const val NEGATIVE_NOTIFY_WEIGHT = -2
    private const val UNKNOWN_SENDER = "unknown"

    // Decide request and response JSON keys.
    private const val FIELD_MODEL = "model"
    private const val FIELD_STATE = "state"
    private const val FIELD_QUESTIONS = "questions"
    private const val FIELD_MESSAGE = "message"
    private const val FIELD_MESSAGE_BODY = "message.body"
    private const val FIELD_BODY = "body"
    private const val FIELD_SENDER = "sender"
    private const val FIELD_TYPE = "type"
    private const val FIELD_INSTRUCTIONS = "instructions"
    private const val FIELD_CRITERIA = "criteria"
    private const val FIELD_OPTIONS = "options"
    private const val FIELD_RULES = "rules"
    private const val FIELD_BIAS = "bias"
    private const val FIELD_IF = "if"
    private const val FIELD_WEIGHT = "weight"
    private const val FIELD_CONTAINS = "contains"
    private const val FIELD_ANSWERS = "answers"
    private const val FIELD_ANSWER = "answer"
    private const val FIELD_CHOICE = "choice"
    private const val FIELD_NOUL = "noul"
    private const val FIELD_CONFIDENCE = "confidence"
    private const val FIELD_BECAUSE = "because"
    private const val FIELD_RULE = "rule"
    private const val QUESTION_CATEGORY = "category"
    private const val QUESTION_NOTIFY = "notify"
    private const val TYPE_CHOICE = "choice"
    private const val TYPE_NOUL = "noul"
    private const val TYPE_YES_NO = "yes_no"
    private const val ANSWER_YES = "yes"
    private const val CRITERIA_TRUE = "true"
    private const val CRITERIA_FALSE = "false"

    private val OTP_PATTERN = Regex("\\b\\d{4,8}\\b")

    // Case-insensitive "contains" keywords used by the built-in rules fallback.
    private val PERSONAL_KEYWORDS = listOf(
        "call me", "how are you", "see you", "miss you", "happy birthday", "love you",
        "on my way", "let's meet",
    )
    private val OTP_KEYWORDS = listOf(
        "otp", "one time password", "one-time password", "verification code", "security code",
        "login code", "passcode", "your code", "code is", "do not share",
    )
    private val TRANSACTION_KEYWORDS = listOf(
        "transaction", "debited", "credited", "payment", "invoice", "refund", "order",
        "delivery", "shipped", "amount", "balance", "bank", "upi", "receipt",
    )
    private val MARKETING_KEYWORDS = listOf(
        "offer", "discount", "sale", "deal", "promo", "coupon", "buy now", "subscribe",
        "limited time", "cashback", "unsubscribe",
    )
    private val UPDATES_KEYWORDS = listOf(
        "update", "appointment", "reminder", "scheduled", "maintenance", "downtime",
        "expires", "renewal", "booking", "confirm",
    )
    private val SPAM_KEYWORDS = listOf(
        "winner", "lottery", "prize", "free money", "click here", "congratulations",
        "claim", "urgent", "loan", "gift card",
    )
    private val IRRELEVANT_KEYWORDS = listOf(
        "good morning", "good night", "have a nice day", "forward this", "good luck",
    )

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
        val deadlineNanos = System.nanoTime() + TOTAL_BUDGET_MILLIS * NANOS_PER_MILLIS
        return try {
            if (isDecideProvider(config.provider)) {
                classifyWithDecide(config, key, senderName, message, deadlineNanos)
            } else {
                classifyWithLlm(config, key, senderName, message, deadlineNanos)
            }
        } catch (e: Exception) {
            AiResult.Failure(sanitizeMessage(e.message, key))
        }
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

    /**
     * Builds the decide "model" request. The key, when set, is only ever sent as a header.
     */
    fun buildDecideModelRequest(
        endpoint: String,
        model: String,
        key: String,
        senderName: String,
        message: String,
    ): AiHttpRequest {
        val resolvedModel = model.ifBlank { AiConfigProvider.DEFAULT_DECIDE_MODEL }
        return AiHttpRequest(
            url = endpoint,
            headers = decideHeaders(key),
            body = buildDecideModelBody(resolvedModel, senderName, message),
        )
    }

    /**
     * Builds the decide "rules" request used as the fallback when the model mode fails. The key,
     * when set, is only ever sent as a header.
     */
    fun buildDecideRulesRequest(
        endpoint: String,
        key: String,
        senderName: String,
        message: String,
    ): AiHttpRequest {
        return AiHttpRequest(
            url = endpoint,
            headers = decideHeaders(key),
            body = buildDecideRulesBody(senderName, message),
        )
    }

    /**
     * Picks the first usable classification from already-fetched decide bodies. The model body wins
     * over the rules body; a `null` body means that attempt failed and yields no classification.
     */
    fun resolveDecideOutcome(
        modelBody: String?,
        rulesBody: String?,
        message: String,
    ): AiClassification? {
        modelBody?.let { body ->
            parseDecideModelResponse(body, message)?.let { return it }
        }
        rulesBody?.let { body ->
            parseDecideRulesResponse(body, message)?.let { return it }
        }
        return null
    }

    /** Parses a decide model-mode response body, or returns `null` when it is unusable. */
    fun parseDecideModelResponse(body: String, message: String): AiClassification? {
        val answers = parseAnswers(body) ?: return null
        val category = parseChoiceCategory(answers, QUESTION_CATEGORY) ?: return null
        val notify = parseNoulNotify(answers, QUESTION_NOTIFY) ?: return null
        return AiClassification(
            notify = notify,
            category = category,
            reasoning = buildModelReasoning(answers, category),
            otp = extractDecideOtp(message, category),
        )
    }

    /** Parses a decide rules-mode response body, or returns `null` when it is unusable. */
    fun parseDecideRulesResponse(body: String, message: String): AiClassification? {
        val answers = parseAnswers(body) ?: return null
        val category = parseChoiceCategory(answers, QUESTION_CATEGORY) ?: return null
        val notify = parseYesNoNotify(answers, QUESTION_NOTIFY) ?: return null
        return AiClassification(
            notify = notify,
            category = category,
            reasoning = buildRulesReasoning(answers, category),
            otp = extractDecideOtp(message, category),
        )
    }

    /**
     * Extracts a 4..8 digit OTP from [body], but only when [category] is [AiCategory.Otp].
     * The decide API has no free-text output, so the code is always recovered locally.
     */
    fun extractDecideOtp(body: String, category: AiCategory?): String {
        if (category != AiCategory.Otp) return ""
        val match = OTP_PATTERN.find(body) ?: return ""
        return AiClassificationParser.sanitizeOtp(match.value)
    }

    /** Returns whether an HTTP status is transient and worth retrying. */
    fun isRetryable(code: Int): Boolean = code in RETRYABLE_STATUS_CODES

    @Suppress("MagicNumber")
    private val RETRYABLE_STATUS_CODES = setOf(408, 425, 429, 500, 502, 503, 504)

    private fun isDecideProvider(provider: String): Boolean {
        return provider.equals(AiConfigProvider.PROVIDER_DECIDE, ignoreCase = true)
    }

    private fun classifyWithLlm(
        config: AiConfigProvider,
        key: String,
        senderName: String,
        message: String,
        deadlineNanos: Long,
    ): AiResult {
        val prompt = buildFullPrompt(config.customPrompt, senderName, message)
        val request = buildRequest(config.provider, config.model, config.customEndpoint, key, prompt)
        return when (val response = sendWithRetry(request, deadlineNanos)) {
            is AiHttpResponse.Success -> parseResponse(config.provider, response.body)
            else -> AiResult.Failure(failureReason(response))
        }
    }

    private fun classifyWithDecide(
        config: AiConfigProvider,
        key: String,
        senderName: String,
        message: String,
        deadlineNanos: Long,
    ): AiResult {
        val endpoint = config.decideEndpoint
        val modelRequest = buildDecideModelRequest(endpoint, config.decideModel, key, senderName, message)
        val modelBody = fetchBody(modelRequest, deadlineNanos)
        val modelOutcome = resolveDecideOutcome(modelBody, null, message)
        if (modelOutcome != null) return AiResult.Classified(modelOutcome)

        val rulesRequest = buildDecideRulesRequest(endpoint, key, senderName, message)
        val rulesBody = fetchBody(rulesRequest, deadlineNanos)
        // The model body already resolved to null above, so only the rules body needs parsing here.
        val outcome = resolveDecideOutcome(null, rulesBody, message)
        return if (outcome != null) {
            AiResult.Classified(outcome)
        } else {
            AiResult.Failure(sanitizeMessage(REASON_DECIDE_FAILED, key))
        }
    }

    private fun fetchBody(request: AiHttpRequest, deadlineNanos: Long): String? {
        return when (val response = sendWithRetry(request, deadlineNanos)) {
            is AiHttpResponse.Success -> response.body
            else -> null
        }
    }

    private fun failureReason(response: AiHttpResponse): String {
        return if (response is AiHttpResponse.Error) "HTTP ${response.code}" else REASON_REQUEST_FAILED
    }

    private fun sendWithRetry(request: AiHttpRequest, deadlineNanos: Long): AiHttpResponse {
        var lastFailure: AiHttpResponse? = null
        var attempt = 0
        var remaining = remainingMillis(deadlineNanos)
        while (attempt < MAX_ATTEMPTS && remaining >= MIN_REMAINING_BUDGET_MILLIS) {
            attempt++
            val response = attemptOnce(request, remaining)
            if (response is AiHttpResponse.Success) return response
            if (response is AiHttpResponse.Error && !isRetryable(response.code)) return response
            lastFailure = response
            remaining = remainingAfterBackoff(attempt, response, deadlineNanos)
        }
        return lastFailure ?: AiHttpResponse.TransportFailure
    }

    private fun remainingAfterBackoff(
        attempt: Int,
        response: AiHttpResponse,
        deadlineNanos: Long,
    ): Long {
        if (attempt >= MAX_ATTEMPTS) return NO_REMAINING_BUDGET
        val retryAfter = (response as? AiHttpResponse.Error)?.retryAfterMillis
        val delay = nextBackoffMillis(attempt, retryAfter)
        return if (sleepWithinBudget(delay, deadlineNanos)) remainingMillis(deadlineNanos) else NO_REMAINING_BUDGET
    }

    @Suppress("SwallowedException")
    private fun attemptOnce(request: AiHttpRequest, budgetMillis: Long): AiHttpResponse {
        return try {
            sendRequest(request, budgetMillis)
        } catch (e: IOException) {
            AiHttpResponse.TransportFailure
        }
    }

    private fun sendRequest(request: AiHttpRequest, budgetMillis: Long): AiHttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = clampTimeout(CONNECT_TIMEOUT_MILLIS, budgetMillis)
            readTimeout = clampTimeout(READ_TIMEOUT_MILLIS, budgetMillis)
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
                AiHttpResponse.Error(code, parseRetryAfterMillis(connection))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun clampTimeout(baseMillis: Int, budgetMillis: Long): Int {
        return minOf(baseMillis.toLong(), budgetMillis).toInt().coerceAtLeast(MIN_TIMEOUT_MILLIS)
    }

    private fun parseRetryAfterMillis(connection: HttpURLConnection): Long? {
        val raw = connection.getHeaderField(HEADER_RETRY_AFTER)?.trim() ?: return null
        val seconds = raw.toLongOrNull() ?: return null
        if (seconds <= 0) return null
        return seconds * MILLIS_PER_SECOND
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        return (deadlineNanos - System.nanoTime()) / NANOS_PER_MILLIS
    }

    private fun nextBackoffMillis(attempt: Int, retryAfterMillis: Long?): Long {
        val shift = (attempt - 1).coerceAtLeast(0)
        val exponential = INITIAL_BACKOFF_MILLIS shl shift
        val retryAfter = retryAfterMillis ?: 0L
        return minOf(maxOf(exponential, retryAfter), MAX_BACKOFF_MILLIS)
    }

    @Suppress("SwallowedException")
    private fun sleepWithinBudget(delayMillis: Long, deadlineNanos: Long): Boolean {
        val remaining = remainingMillis(deadlineNanos)
        if (remaining < MIN_REMAINING_BUDGET_MILLIS) return false
        val waitMillis = minOf(delayMillis, remaining)
        if (waitMillis <= 0) return false
        return try {
            Thread.sleep(waitMillis)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
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

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parseAnswers(body: String): JSONObject? {
        return try {
            JSONObject(body).optJSONObject(FIELD_ANSWERS)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseChoiceCategory(answers: JSONObject, question: String): AiCategory? {
        val raw = answers.optJSONObject(question)?.optString(FIELD_CHOICE) ?: return null
        return AiCategory.matching(raw)
    }

    private fun parseNoulNotify(answers: JSONObject, question: String): Boolean? {
        val value = answers.optJSONObject(question)?.optDouble(FIELD_NOUL, Double.NaN) ?: return null
        if (value.isNaN()) return null
        return value >= NOTIFY_NOUL_THRESHOLD
    }

    private fun parseYesNoNotify(answers: JSONObject, question: String): Boolean? {
        val answer = answers.optJSONObject(question)?.optString(FIELD_ANSWER)?.trim() ?: return null
        if (answer.isEmpty()) return null
        return answer.equals(ANSWER_YES, ignoreCase = true)
    }

    private fun buildModelReasoning(answers: JSONObject, category: AiCategory): String {
        val confidence = answers.optJSONObject(QUESTION_CATEGORY)?.optDouble(FIELD_CONFIDENCE, Double.NaN)
            ?: Double.NaN
        val base = "decide model chose " + category.wireName
        val text = if (confidence.isNaN()) base else base + " (confidence " + confidence + ")"
        return text.take(MAX_REASONING_LENGTH)
    }

    private fun buildRulesReasoning(answers: JSONObject, category: AiCategory): String {
        val notifyPart = describeFirstRule(answers.optJSONObject(QUESTION_NOTIFY), QUESTION_NOTIFY)
        val categoryLabel = QUESTION_CATEGORY + " " + category.wireName
        val categoryPart = describeFirstRule(answers.optJSONObject(QUESTION_CATEGORY), categoryLabel)
        val parts = listOfNotNull(notifyPart, categoryPart)
        val summary = if (parts.isEmpty()) "decide rules chose " + category.wireName else parts.joinToString("; ")
        return summary.take(MAX_REASONING_LENGTH)
    }

    private fun describeFirstRule(question: JSONObject?, label: String): String? {
        val because = question?.optJSONArray(FIELD_BECAUSE) ?: return null
        if (because.length() == 0) return null
        val entry = because.optJSONObject(0) ?: return null
        val rule = entry.optInt(FIELD_RULE, 0)
        val weight = entry.optInt(FIELD_WEIGHT, 0)
        val sign = if (weight >= 0) "+" else ""
        return label + " rule " + rule + "(" + sign + weight + ")"
    }

    private fun buildDecideModelBody(model: String, senderName: String, message: String): String {
        val questions = JSONObject()
            .put(QUESTION_CATEGORY, modelCategoryQuestion())
            .put(QUESTION_NOTIFY, modelNotifyQuestion())
        return JSONObject()
            .put(FIELD_MODEL, model)
            .put(FIELD_STATE, decideState(senderName, message))
            .put(FIELD_QUESTIONS, questions)
            .toString()
    }

    private fun buildDecideRulesBody(senderName: String, message: String): String {
        val questions = JSONObject()
            .put(QUESTION_CATEGORY, rulesCategoryQuestion())
            .put(QUESTION_NOTIFY, rulesNotifyQuestion())
        return JSONObject()
            .put(FIELD_STATE, decideState(senderName, message))
            .put(FIELD_QUESTIONS, questions)
            .toString()
    }

    private fun decideState(senderName: String, message: String): JSONObject {
        val sender = senderName.trim().ifEmpty { UNKNOWN_SENDER }
        val messageObj = JSONObject()
            .put(FIELD_BODY, message)
            .put(FIELD_SENDER, sender)
        return JSONObject().put(FIELD_MESSAGE, messageObj)
    }

    private fun modelCategoryQuestion(): JSONObject {
        val criteria = JSONObject()
            .put(AiCategory.Personal.wireName, "A personal message from a real person.")
            .put(AiCategory.Otp.wireName, "A one-time password or verification code.")
            .put(AiCategory.Transaction.wireName, "A bank, payment or delivery transaction.")
            .put(AiCategory.Updates.wireName, "A service, appointment or account update.")
            .put(AiCategory.Marketing.wireName, "A promotional or advertising message.")
            .put(AiCategory.Spam.wireName, "A scam, phishing or bulk unsolicited message.")
            .put(AiCategory.Irrelevant.wireName, "Anything else that fits no other category.")
        return JSONObject()
            .put(FIELD_TYPE, TYPE_CHOICE)
            .put(FIELD_INSTRUCTIONS, "Pick the single best category for the message.")
            .put(FIELD_CRITERIA, criteria)
    }

    private fun modelNotifyQuestion(): JSONObject {
        val criteria = JSONObject()
            .put(CRITERIA_TRUE, "The user should be notified about this message.")
            .put(CRITERIA_FALSE, "The message is spam or irrelevant and should stay silent.")
        return JSONObject()
            .put(FIELD_TYPE, TYPE_NOUL)
            .put(FIELD_INSTRUCTIONS, "Decide whether this message should notify the user.")
            .put(FIELD_CRITERIA, criteria)
    }

    private fun rulesCategoryQuestion(): JSONObject {
        val options = JSONObject()
            .put(AiCategory.Personal.wireName, keywordRules(PERSONAL_KEYWORDS))
            .put(AiCategory.Otp.wireName, keywordRules(OTP_KEYWORDS))
            .put(AiCategory.Transaction.wireName, keywordRules(TRANSACTION_KEYWORDS))
            .put(AiCategory.Marketing.wireName, keywordRules(MARKETING_KEYWORDS))
            .put(AiCategory.Updates.wireName, keywordRules(UPDATES_KEYWORDS))
            .put(AiCategory.Spam.wireName, keywordRules(SPAM_KEYWORDS))
            .put(AiCategory.Irrelevant.wireName, keywordRules(IRRELEVANT_KEYWORDS))
        return JSONObject()
            .put(FIELD_TYPE, TYPE_CHOICE)
            .put(FIELD_OPTIONS, options)
    }

    private fun rulesNotifyQuestion(): JSONObject {
        val rules = JSONArray()
        SPAM_KEYWORDS.forEach { rules.put(keywordRule(it, NEGATIVE_NOTIFY_WEIGHT)) }
        MARKETING_KEYWORDS.forEach { rules.put(keywordRule(it, NEGATIVE_NOTIFY_WEIGHT)) }
        return JSONObject()
            .put(FIELD_TYPE, TYPE_YES_NO)
            .put(FIELD_BIAS, NOTIFY_BIAS)
            .put(FIELD_RULES, rules)
    }

    private fun keywordRules(keywords: List<String>): JSONArray {
        val rules = JSONArray()
        keywords.forEach { rules.put(keywordRule(it, CATEGORY_RULE_WEIGHT)) }
        return rules
    }

    private fun keywordRule(keyword: String, weight: Int): JSONObject {
        val contains = JSONObject().put(FIELD_CONTAINS, keyword)
        val condition = JSONObject().put(FIELD_MESSAGE_BODY, contains)
        return JSONObject().put(FIELD_IF, condition).put(FIELD_WEIGHT, weight)
    }

    private fun decideHeaders(key: String): Map<String, String> {
        val headers = mutableMapOf(
            HEADER_CONTENT_TYPE to CONTENT_TYPE_JSON,
            HEADER_ACCEPT to CONTENT_TYPE_JSON,
        )
        if (key.isNotBlank()) {
            headers[HEADER_AUTHORIZATION] = "Bearer $key"
        }
        return headers
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
