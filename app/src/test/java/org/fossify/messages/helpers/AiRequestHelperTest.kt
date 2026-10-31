package org.fossify.messages.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRequestHelperTest {
    private val key = "test-secret-key"

    @Test
    fun geminiRequestKeepsKeyOutOfUrlAndUsesHeader() {
        val request = AiRequestHelper.buildRequest(
            provider = AiConfigProvider.PROVIDER_GEMINI,
            model = "gemini-2.0-flash",
            endpoint = "",
            key = key,
            prompt = "classify this",
        )

        assertFalse(request.url.contains(key))
        assertFalse(request.url.contains("key="))
        assertEquals(key, request.headers["x-goog-api-key"])
        assertTrue(request.body.contains("classify this"))
        assertTrue(request.url.contains("gemini-2.0-flash"))
    }

    @Test
    fun openAiRequestUsesBearerHeader() {
        val request = AiRequestHelper.buildRequest(
            provider = AiConfigProvider.PROVIDER_OPENAI,
            model = "gpt-4o-mini",
            endpoint = "",
            key = key,
            prompt = "classify this",
        )

        assertEquals("https://api.openai.com/v1/chat/completions", request.url)
        assertEquals("Bearer $key", request.headers["Authorization"])
        assertTrue(request.body.contains("\"model\":\"gpt-4o-mini\""))
    }

    @Test
    fun customProviderUsesConfiguredEndpoint() {
        val endpoint = "https://example.com/v1/chat/completions"
        val request = AiRequestHelper.buildRequest(
            provider = AiConfigProvider.PROVIDER_OTHER,
            model = "local-model",
            endpoint = endpoint,
            key = key,
            prompt = "classify this",
        )

        assertEquals(endpoint, request.url)
        assertEquals("Bearer $key", request.headers["Authorization"])
    }

    @Test
    fun defaultPromptHasStrictJsonContract() {
        val prompt = AiRequestHelper.defaultPrompt()

        assertTrue(prompt.contains("\"notify\""))
        assertTrue(prompt.contains("\"category\""))
        assertTrue(prompt.contains("\"reasoning\""))
        assertTrue(prompt.contains("\"otp\""))
    }
}
