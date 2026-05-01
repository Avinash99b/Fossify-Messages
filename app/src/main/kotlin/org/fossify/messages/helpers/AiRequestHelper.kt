package org.fossify.messages.helpers

import android.content.Context
import android.widget.Toast
import com.androidnetworking.AndroidNetworking
import com.androidnetworking.error.ANError
import org.fossify.messages.models.LLMClassificationResult

object AiRequestHelper {
    private val promptStart = """
You are a message classification AI.

Your task is to analyze a given message and classify it into one of the following categories:
- normal (legitimate, meaningful message)
- spam (unsolicited, promotional, scam, or repetitive content)
- irrelevant (not useful or unrelated to the user)

You must respond ONLY in valid JSON format with no extra text.

Output format:
{
  "notify": boolean,
  "category": "normal" | "spam" | "irrelevant"
}

Rules:
- Set "notify" to true only if the message is important or requires user attention.
- Set "notify" to false for spam or irrelevant messages.
- Do not include explanations, comments, or additional fields.
- Ensure the JSON is strictly valid.

Now classify the following message:
"""


    fun getFullPrompt(senderName: String, message: String): String {
        val prompt = promptStart + "\nSender: $senderName\nMessage: $message\n"
        return prompt
    }

    fun classifyMessage(
        context: Context,
        senderName: String,
        message: String,
        onResult: (result: LLMClassificationResult?) -> Unit
    ) {
        val prompt = getFullPrompt(senderName, message)

        val aiConfigProvider = AiConfigProvider(context)
        val apiKey = aiConfigProvider.apiKey ?: run {
            onResult(null)
            return
        }
        val modelName = aiConfigProvider.model ?: run {
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

                        val category = try {
                            LLMClassificationResult.Category.valueOf(categoryStr)
                        } catch (e: Exception) {
                            LLMClassificationResult.Category.irrelevant
                        }

                        onResult(
                            LLMClassificationResult(
                                notify = notify,
                                category = category
                            )
                        )

                    } catch (e: Exception) {
                        e.printStackTrace()
                        onResult(null)
                    }
                }

                override fun onError(anError: ANError) {
                    anError.printStackTrace()
                    onResult(null)
                }
            })
    }

}
