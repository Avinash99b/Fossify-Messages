package org.fossify.messages.helpers

import android.content.Context
import android.util.Log
import com.androidnetworking.AndroidNetworking
import com.androidnetworking.error.ANError
import org.fossify.messages.models.LLMClassificationResult

object AiRequestHelper {
    private val TAG = "AiRequestHelper"

    fun defaultPrompt() = defaultAiPrompt()

    fun getFullPrompt(senderName: String, message: String, customPrompt: String? = null): String {
        val basePrompt = customPrompt?.takeIf { it.isNotBlank() } ?: defaultAiPrompt()
        val prompt = basePrompt + "\nSender: $senderName\nMessage: $message\n"
        return prompt
    }

    fun classifyMessage(
        context: Context,
        senderName: String,
        message: String,
        onResult: (result: LLMClassificationResult?) -> Unit
    ) {
        val aiConfigProvider = AiConfigProvider(context)
        val customPrompt = aiConfigProvider.customPrompt
        val prompt = getFullPrompt(senderName, message, customPrompt)

        val apiKey = aiConfigProvider.apiKey ?: run {
            logAiDecision(context, senderName, message, false, "No API key configured", "", "skipped")
            onResult(null)
            return
        }
        val modelName = aiConfigProvider.model ?: run {
            logAiDecision(context, senderName, message, false, "No model configured", "", "skipped")
            onResult(null)
            return
        }

        AndroidNetworking.initialize(context)

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        val requestBody = org.json.JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                org.json.JSONObject().put(
                    "parts",
                    org.json.JSONArray().put(
                        org.json.JSONObject().put("text", prompt)
                    )
                )
            ))
        }

        AndroidNetworking.post(url)
            .addJSONObjectBody(requestBody)
            .setTag("ai_classification")
            .setPriority(com.androidnetworking.common.Priority.HIGH)
            .build()
            .getAsJSONObject(object : com.androidnetworking.interfaces.JSONObjectRequestListener {

                override fun onResponse(response: org.json.JSONObject) {
                    try {
                        val rawText = response
                            .getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")

                        // Clean markdown if model wraps JSON
                        val cleaned = rawText
                            .replace("```json", "")
                            .replace("```", "")
                            .trim()

                        val json = org.json.JSONObject(cleaned)

                        val notify = json.getBoolean("notify")
                        val categoryStr = json.getString("category")
                        val reasoning = json.optString("reasoning", "")

                        val category = try {
                            LLMClassificationResult.Category.valueOf(categoryStr)
                        } catch (e: Exception) {
                            LLMClassificationResult.Category.irrelevant
                        }

                        val result = LLMClassificationResult(
                            notify = notify,
                            category = category,
                            reasoning = reasoning
                        )

                        val status = if (notify) "notification_shown" else "notification_hidden"
                        logAiDecision(context, senderName, message, notify, reasoning, categoryStr, status)

                        onResult(result)

                    } catch (e: Exception) {
                        e.printStackTrace()
                        Log.e(TAG, "Error parsing AI response")
                        logAiDecision(context, senderName, message, false, "Error parsing response", "", "parse_error")
                        onResult(null)
                    }
                }

                override fun onError(anError: ANError) {
                    anError.printStackTrace()
                    Log.e(TAG, "AI Request Error: ${anError.message}")
                    logAiDecision(context, senderName, message, false, "API Error: ${anError.message}", "", "request_error")
                    onResult(null)
                }
            })
    }

    private fun logAiDecision(
        context: Context,
        senderName: String,
        message: String,
        notify: Boolean,
        reasoning: String,
        category: String,
        status: String
    ) {
        val timestamp = System.currentTimeMillis()
        val logEntry = "[$timestamp] From: $senderName | Status: $status | Notify: $notify | Category: $category | Reasoning: $reasoning"
        Log.i(TAG, logEntry)

        AiDecisionLogger.logDecision(context, senderName, message, notify, reasoning, category, status)
    }
}
