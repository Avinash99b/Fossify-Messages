package org.fossify.messages.helpers

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.androidnetworking.AndroidNetworking
import com.androidnetworking.error.ANError
import org.fossify.messages.models.LLMClassificationResult

object AiRequestHelper {
    private val TAG = "AiRequestHelper"

    private val promptStart = """
You are a message classification AI specialized in spam and irrelevance detection.

Your task is to analyze a given message and classify it into one of the following categories:
- normal (legitimate, meaningful message)
- spam (unsolicited, promotional, scam, or repetitive content)
- irrelevant (not useful or unrelated to the user)

You must respond ONLY in valid JSON format with no extra text.

Output format:
{
  "notify": boolean,
  "category": "normal" | "spam" | "irrelevant",
  "reasoning": "brief explanation of why you made this decision"
}

Rules:
- Set "notify" to true only if the message is important or requires user attention.
- Set "notify" to false for spam or irrelevant messages.
- Provide a brief but clear reasoning explaining your classification decision (max 100 characters).
- Include explanations that help understand why the message should or should not notify.
- Do not include explanations other than in the reasoning field.
- Ensure the JSON is strictly valid.

Now classify the following message:
"""

    fun getFullPrompt(senderName: String, message: String, customPrompt: String? = null): String {
        val basePrompt = customPrompt ?: promptStart
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
            logAiDecision(context, senderName, message, false, "No API key configured", "")
            onResult(null)
            return
        }
        val modelName = aiConfigProvider.model ?: run {
            logAiDecision(context, senderName, message, false, "No model configured", "")
            onResult(null)
            return
        }

        AndroidNetworking.initialize(context)

        val url =
            "https://generativelanguage.googleapis.com/v1/models/$modelName:generateContent?key=$apiKey"

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

                        // Log the decision
                        logAiDecision(context, senderName, message, notify, reasoning, categoryStr)

                        onResult(result)

                    } catch (e: Exception) {
                        e.printStackTrace()
                        Log.e(TAG, "Error parsing AI response: ${e.message}")
                        logAiDecision(context, senderName, message, false, "Error parsing response", "")
                        onResult(null)
                    }
                }

                override fun onError(anError: ANError) {
                    anError.printStackTrace()
                    Log.e(TAG, "AI Request Error: ${anError.message}")
                    logAiDecision(context, senderName, message, false, "API Error: ${anError.message}", "")
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
        category: String
    ) {
        val timestamp = System.currentTimeMillis()
        val logEntry = "[$timestamp] From: $senderName | Notify: $notify | Category: $category | Reasoning: $reasoning"
        Log.i(TAG, logEntry)

        // You can extend this to write to a file or database for persistent logging
        AiDecisionLogger.logDecision(context, senderName, message, notify, reasoning, category)
    }
}
