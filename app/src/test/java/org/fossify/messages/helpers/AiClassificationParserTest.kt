package org.fossify.messages.helpers

import org.fossify.messages.models.AiCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiClassificationParserTest {

    @Test
    fun parsesPlainJson() {
        val result = AiClassificationParser.parse(
            """{"notify":true,"category":"otp","reasoning":"Login code","otp":"123456"}"""
        )

        assertEquals(true, result?.notify)
        assertEquals(AiCategory.Otp, result?.category)
        assertEquals("Login code", result?.reasoning)
        assertEquals("123456", result?.otp)
    }

    @Test
    fun parsesFencedJsonWithSurroundingProse() {
        val raw = """
            Here is the classification:
            ```json
            {"notify": false, "category": "spam", "reasoning": "Prize scam", "otp": ""}
            ```
        """.trimIndent()

        val result = AiClassificationParser.parse(raw)

        assertEquals(false, result?.notify)
        assertEquals(AiCategory.Spam, result?.category)
        assertEquals("", result?.otp)
    }

    @Test
    fun categoryMatchingIsCaseInsensitive() {
        assertEquals(AiCategory.Personal, AiCategory.matching("Personal"))
        assertEquals(AiCategory.Updates, AiCategory.matching(" updates "))
        assertNull(AiCategory.matching("unknown"))
    }

    @Test
    fun rejectsMissingNotifyFlag() {
        assertNull(AiClassificationParser.parse("""{"category":"otp"}"""))
    }

    @Test
    fun rejectsUnknownCategory() {
        assertNull(
            AiClassificationParser.parse(
                """{"notify":true,"category":"gossip","reasoning":"","otp":""}"""
            )
        )
    }

    @Test
    fun rejectsMalformedJson() {
        assertNull(AiClassificationParser.parse("not json at all"))
    }

    @Test
    fun sanitizesOtp() {
        assertEquals("123456", AiClassificationParser.sanitizeOtp(" 123456 "))
        assertEquals("", AiClassificationParser.sanitizeOtp("123"))
        assertEquals("", AiClassificationParser.sanitizeOtp("123456789"))
        assertEquals("", AiClassificationParser.sanitizeOtp("12 34 56"))
        assertEquals("", AiClassificationParser.sanitizeOtp("abc123"))
    }

    @Test
    fun truncatesLongReasoning() {
        val longReasoning = "x".repeat(300)
        val result = AiClassificationParser.parse(
            """{"notify":true,"category":"personal","reasoning":"$longReasoning","otp":""}"""
        )

        assertEquals(120, result?.reasoning?.length)
    }
}
