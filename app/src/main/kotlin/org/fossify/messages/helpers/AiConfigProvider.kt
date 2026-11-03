package org.fossify.messages.helpers

import android.content.Context
import java.net.URI

/**
 * Persists the AI backend configuration in a dedicated preference file.
 *
 * All getters are pure (they never write) and all writes go through `apply()`. The API key is
 * always transparently encrypted with [AiSecretStore].
 */
class AiConfigProvider(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var provider: String
        get() = prefs.getString(KEY_PROVIDER, PROVIDER_GEMINI) ?: PROVIDER_GEMINI
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /** Decrypted API key; writing a value stores it encrypted. */
    var apiKey: String
        get() = AiSecretStore.decrypt(prefs.getString(KEY_API_KEY_ENC, "") ?: "")
        set(value) = prefs.edit().putString(KEY_API_KEY_ENC, AiSecretStore.encrypt(value)).apply()

    var customEndpoint: String
        get() = prefs.getString(KEY_CUSTOM_ENDPOINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_ENDPOINT, value).apply()

    /** Endpoint used by the "decide" provider, falling back to [DEFAULT_DECIDE_ENDPOINT]. */
    val decideEndpoint: String
        get() = customEndpoint.ifBlank { DEFAULT_DECIDE_ENDPOINT }

    /** Decide model used by the "decide" provider, falling back to [DEFAULT_DECIDE_MODEL]. */
    val decideModel: String
        get() = model.ifBlank { DEFAULT_DECIDE_MODEL }

    /** Returns the stored prompt, or [DEFAULT_AI_PROMPT] when none was saved (without writing it). */
    var customPrompt: String
        get() {
            val stored = prefs.getString(KEY_CUSTOM_PROMPT, "") ?: ""
            return if (stored.isBlank()) DEFAULT_AI_PROMPT else stored
        }
        set(value) = prefs.edit().putString(KEY_CUSTOM_PROMPT, value).apply()

    /**
     * Returns whether the provider has enough configuration to run a request.
     *
     * The "decide" provider needs neither an API key nor a model; a custom endpoint, when set, must
     * still be a valid HTTPS URL. All other providers require an API key and a model.
     */
    fun isConfigured(): Boolean {
        if (provider.equals(PROVIDER_DECIDE, ignoreCase = true)) {
            return customEndpoint.isBlank() || isValidHttpsUrl(customEndpoint)
        }
        if (apiKey.isBlank() || model.isBlank()) return false
        val usesCustomProvider = provider.equals(PROVIDER_OTHER, ignoreCase = true)
        return !usesCustomProvider || isValidHttpsUrl(customEndpoint)
    }

    /** Clears every AI preference and the decision log. */
    fun clear() {
        prefs.edit().clear().apply()
        AiDecisionLogger.clearLogs(appContext)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun isValidHttpsUrl(raw: String): Boolean {
        return try {
            val uri = URI(raw)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_OTHER = "other"
        const val PROVIDER_DECIDE = "decide"

        /** Endpoint of the free public decide service (no API key required). */
        const val DEFAULT_DECIDE_ENDPOINT = "https://aisenseapi.com/services/v1/decide"

        /** Decide model used when the user leaves the model field blank. */
        const val DEFAULT_DECIDE_MODEL = "clef"

        private const val PREFS_NAME = "ai_config"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY_ENC = "api_key_enc"
        private const val KEY_CUSTOM_ENDPOINT = "custom_endpoint"
        private const val KEY_CUSTOM_PROMPT = "custom_prompt"
    }
}
