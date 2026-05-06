package org.fossify.messages.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AiConfigProvider(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, "")
        set(apiKey) {
            prefs.edit(commit = true) { putString(KEY_API_KEY, apiKey)  }
        }

    var model: String?
        get() = prefs.getString(MODEL_NAME_KEY, "")
        set(apiKey) {
            prefs.edit(commit = true) { putString(MODEL_NAME_KEY, apiKey)  }
        }

    var customPrompt: String?
        get() {
            val prompt = prefs.getString(CUSTOM_PROMPT_KEY, null).orEmpty().trim()
            return if (prompt.isEmpty()) {
                val defaultPrompt = defaultAiPrompt()
                prefs.edit(commit = true) { putString(CUSTOM_PROMPT_KEY, defaultPrompt) }
                defaultPrompt
            } else {
                prompt
            }
        }
        set(prompt) {
            val value = prompt?.trim().takeUnless { it.isNullOrEmpty() } ?: defaultAiPrompt()
            prefs.edit(commit = true) { putString(CUSTOM_PROMPT_KEY, value) }
        }

    fun clear() {
        prefs.edit(commit = true) { clear() }
        AiDecisionLogger.clearLogs(appContext)
    }

    companion object {
        private const val PREF_NAME = "aiconfig"

        private const val MODEL_NAME_KEY="model_name"
        private const val KEY_API_KEY = "api_key"
        private const val CUSTOM_PROMPT_KEY = "custom_prompt"
    }
}
