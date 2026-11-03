package org.fossify.messages.helpers

import org.fossify.messages.models.AiCategory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecideClassificationTest {
    private val key = "test-secret-key"
    private val endpoint = "https://example.com/services/v1/decide"
    private val message = "Your verification code is 123456"

    @Test
    fun modelRequestUsesEndpointAndKeepsKeyOutOfUrl() {
        val request = AiRequestHelper.buildDecideModelRequest(endpoint, "clef", key, "Bank", message)

        assertEquals(endpoint, request.url)
        assertFalse(request.url.contains(key))
        assertEquals("Bearer $key", request.headers["Authorization"])
        assertTrue(request.body.contains("\"model\""))
        assertTrue(request.body.contains("\"type\":\"noul\""))
        assertTrue(request.body.contains("\"type\":\"choice\""))
    }

    @Test
    fun modelRequestUsesDefaultModelWhenBlank() {
        val request = AiRequestHelper.buildDecideModelRequest(endpoint, "", key, "Bank", message)

        assertTrue(request.body.contains("\"model\":\"clef\""))
    }

    @Test
    fun modelRequestOmitsAuthorizationWhenKeyBlank() {
        val request = AiRequestHelper.buildDecideModelRequest(endpoint, "clef", "", "Bank", message)

        assertNull(request.headers["Authorization"])
        assertFalse(request.url.contains("Bearer"))
    }

    @Test
    fun rulesRequestUsesEndpointAndYesNoQuestion() {
        val request = AiRequestHelper.buildDecideRulesRequest(endpoint, key, "Bank", message)

        assertEquals(endpoint, request.url)
        assertFalse(request.url.contains(key))
        assertEquals("Bearer $key", request.headers["Authorization"])
        assertTrue(request.body.contains("\"type\":\"yes_no\""))
        assertTrue(request.body.contains("\"options\""))
        assertFalse(request.body.contains("\"model\""))
    }

    @Test
    fun rulesRequestOmitsAuthorizationWhenKeyBlank() {
        val request = AiRequestHelper.buildDecideRulesRequest(endpoint, "", "Bank", message)

        assertNull(request.headers["Authorization"])
    }

    @Test
    fun parsesModelModeResponse() {
        val body = JSONObject()
            .put(
                "answers",
                JSONObject()
                    .put("category", JSONObject().put("choice", "otp").put("confidence", 0.87))
                    .put("notify", JSONObject().put("noul", 0.95))
            )
            .toString()

        val result = AiRequestHelper.parseDecideModelResponse(body, message)

        assertEquals(true, result?.notify)
        assertEquals(AiCategory.Otp, result?.category)
        assertEquals("123456", result?.otp)
    }

    @Test
    fun parsesModelModeLowNoulAsNoNotify() {
        val body = JSONObject()
            .put(
                "answers",
                JSONObject()
                    .put("category", JSONObject().put("choice", "marketing"))
                    .put("notify", JSONObject().put("noul", 0.2))
            )
            .toString()

        val result = AiRequestHelper.parseDecideModelResponse(body, message)

        assertEquals(false, result?.notify)
        assertEquals(AiCategory.Marketing, result?.category)
        assertEquals("", result?.otp)
    }

    @Test
    fun parsesRulesModeYesAndSpam() {
        val body = rulesBody(answer = "yes", choice = "spam")

        val result = AiRequestHelper.parseDecideRulesResponse(body, message)

        assertEquals(true, result?.notify)
        assertEquals(AiCategory.Spam, result?.category)
        assertTrue(result?.reasoning?.contains("notify rule 0(+2)") == true)
        assertTrue(result?.reasoning?.contains("category spam rule 0(+3)") == true)
    }

    @Test
    fun rulesModeNoAnswerMeansNoNotifyAndExtractsOtp() {
        val body = rulesBody(answer = "no", choice = "otp")

        val result = AiRequestHelper.parseDecideRulesResponse(body, message)

        assertEquals(false, result?.notify)
        assertEquals(AiCategory.Otp, result?.category)
        assertEquals("123456", result?.otp)
    }

    @Test
    fun unparseableModelFallsBackToRules() {
        val rules = rulesBody(answer = "yes", choice = "otp")

        val result = AiRequestHelper.resolveDecideOutcome("{ not valid json", rules, message)

        assertEquals(AiCategory.Otp, result?.category)
        assertEquals(true, result?.notify)
    }

    @Test
    fun modelWinsOverRulesWhenBothParse() {
        val model = JSONObject()
            .put(
                "answers",
                JSONObject()
                    .put("category", JSONObject().put("choice", "personal"))
                    .put("notify", JSONObject().put("noul", 0.9))
            )
            .toString()
        val rules = rulesBody(answer = "no", choice = "spam")

        val result = AiRequestHelper.resolveDecideOutcome(model, rules, message)

        assertEquals(AiCategory.Personal, result?.category)
        assertEquals(true, result?.notify)
    }

    @Test
    fun bothBodiesUnusableReturnsNull() {
        assertNull(AiRequestHelper.resolveDecideOutcome(null, "garbage", message))
        assertNull(AiRequestHelper.resolveDecideOutcome("garbage", null, message))
    }

    @Test
    fun isRetryableMatchesTransientStatusesOnly() {
        assertTrue(AiRequestHelper.isRetryable(408))
        assertTrue(AiRequestHelper.isRetryable(425))
        assertTrue(AiRequestHelper.isRetryable(429))
        assertTrue(AiRequestHelper.isRetryable(500))
        assertTrue(AiRequestHelper.isRetryable(502))
        assertTrue(AiRequestHelper.isRetryable(503))
        assertTrue(AiRequestHelper.isRetryable(504))
        assertFalse(AiRequestHelper.isRetryable(400))
        assertFalse(AiRequestHelper.isRetryable(401))
        assertFalse(AiRequestHelper.isRetryable(404))
        assertFalse(AiRequestHelper.isRetryable(405))
        assertFalse(AiRequestHelper.isRetryable(413))
        assertFalse(AiRequestHelper.isRetryable(415))
        assertFalse(AiRequestHelper.isRetryable(200))
    }

    @Test
    fun extractDecideOtpOnlyForOtpCategory() {
        assertEquals("123456", AiRequestHelper.extractDecideOtp(message, AiCategory.Otp))
        assertEquals("", AiRequestHelper.extractDecideOtp(message, AiCategory.Personal))
        assertEquals("", AiRequestHelper.extractDecideOtp(message, null))
    }

    @Test
    fun extractDecideOtpRejectsInvalidLengths() {
        assertEquals("", AiRequestHelper.extractDecideOtp("code 123", AiCategory.Otp))
        assertEquals("", AiRequestHelper.extractDecideOtp("code 123456789", AiCategory.Otp))
        assertEquals("", AiRequestHelper.extractDecideOtp("no digits here", AiCategory.Otp))
    }

    private fun rulesBody(answer: String, choice: String): String {
        val notify = JSONObject()
            .put("type", "yes_no")
            .put("answer", answer)
            .put("because", becauseArray(rule = 0, weight = 2, option = null))
        val category = JSONObject()
            .put("type", "choice")
            .put("choice", choice)
            .put("because", becauseArray(rule = 0, weight = 3, option = choice))
        return JSONObject()
            .put("answers", JSONObject().put("notify", notify).put("category", category))
            .toString()
    }

    private fun becauseArray(rule: Int, weight: Int, option: String?): JSONArray {
        val entry = JSONObject().put("rule", rule).put("weight", weight)
        if (option != null) entry.put("option", option)
        return JSONArray().put(entry)
    }
}
