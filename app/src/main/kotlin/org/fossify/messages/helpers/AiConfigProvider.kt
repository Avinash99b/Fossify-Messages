package org.fossify.messages.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AiConfigProvider(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

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

    fun clear() {
        prefs.edit(commit = true) { clear() }
    }

    companion object {
        private const val PREF_NAME = "aiconfig"

        private const val MODEL_NAME_KEY="model_name"
        private const val KEY_API_KEY = "api_key"
    }
}
