package com.evan.lazlo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBackendCopyTest {

    @Test
    fun `each known backend has a distinct explanation`() {
        val explanations = listOf(
            AiBackendCopy.API_KEY_PROVIDER_ID,
            AiBackendCopy.OPENROUTER_PROVIDER_ID,
            AiBackendCopy.AICORE_PROVIDER_ID,
            AiBackendCopy.MEDIAPIPE_PROVIDER_ID,
        ).map(AiBackendCopy::explanation)
        assertEquals(4, explanations.toSet().size)
    }

    @Test
    fun `both BYOK backends' explanations mention the network`() {
        assertTrue(AiBackendCopy.explanation(AiBackendCopy.API_KEY_PROVIDER_ID).contains("your own", ignoreCase = true))
        assertTrue(AiBackendCopy.explanation(AiBackendCopy.OPENROUTER_PROVIDER_ID).contains("your own", ignoreCase = true))
    }

    @Test
    fun `each BYOK backend has a distinct service name`() {
        assertEquals("Anthropic", AiBackendCopy.serviceName(AiBackendCopy.API_KEY_PROVIDER_ID))
        assertEquals("OpenRouter", AiBackendCopy.serviceName(AiBackendCopy.OPENROUTER_PROVIDER_ID))
    }

    @Test
    fun `on-device backends explanation mentions offline`() {
        assertTrue(AiBackendCopy.explanation(AiBackendCopy.AICORE_PROVIDER_ID).contains("offline", ignoreCase = true))
        assertTrue(AiBackendCopy.explanation(AiBackendCopy.MEDIAPIPE_PROVIDER_ID).contains("offline", ignoreCase = true))
    }

    @Test
    fun `unknown provider id gets a fallback, not a crash`() {
        assertNotEquals("", AiBackendCopy.explanation("something-else"))
    }

    @Test
    fun `warns when an OpenRouter key is entered for the Anthropic slot`() {
        val warning = AiBackendCopy.keyFormatWarning(AiBackendCopy.API_KEY_PROVIDER_ID, "sk-or-v1-abc123")
        assertNotNull(warning)
        assertTrue(warning!!.contains("OpenRouter"))
    }

    @Test
    fun `warns when an Anthropic key is entered for the OpenRouter slot`() {
        val warning = AiBackendCopy.keyFormatWarning(AiBackendCopy.OPENROUTER_PROVIDER_ID, "sk-ant-api03-abc123")
        assertNotNull(warning)
        assertTrue(warning!!.contains("Anthropic"))
    }

    @Test
    fun `no warning for a correctly matched Anthropic key`() {
        assertNull(AiBackendCopy.keyFormatWarning(AiBackendCopy.API_KEY_PROVIDER_ID, "sk-ant-api03-abc123"))
    }

    @Test
    fun `no warning for a correctly matched OpenRouter key`() {
        assertNull(AiBackendCopy.keyFormatWarning(AiBackendCopy.OPENROUTER_PROVIDER_ID, "sk-or-v1-abc123"))
    }

    @Test
    fun `no warning for a blank key, so an empty field never lights up`() {
        assertNull(AiBackendCopy.keyFormatWarning(AiBackendCopy.API_KEY_PROVIDER_ID, "  "))
    }

    @Test
    fun `no warning for on-device providers, which have no key slot to mismatch`() {
        assertNull(AiBackendCopy.keyFormatWarning(AiBackendCopy.AICORE_PROVIDER_ID, "sk-or-v1-abc123"))
    }

    @Test
    fun `local-model explanation mentions the free downloadable model, not just bring-your-own-file`() {
        val explanation = AiBackendCopy.explanation(AiBackendCopy.MEDIAPIPE_PROVIDER_ID)
        assertTrue(explanation.contains("free", ignoreCase = true))
        assertTrue(explanation.contains("API key", ignoreCase = true))
    }

    @Test
    fun `no warning for a correctly formatted Hugging Face token`() {
        assertNull(AiBackendCopy.huggingFaceTokenFormatWarning("hf_abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `no warning for a blank Hugging Face token, so an empty field never lights up`() {
        assertNull(AiBackendCopy.huggingFaceTokenFormatWarning("  "))
    }

    @Test
    fun `warns when a Hugging Face token is missing its hf_ prefix`() {
        val warning = AiBackendCopy.huggingFaceTokenFormatWarning("abcdefghijklmnopqrstuvwxyz")
        assertNotNull(warning)
        assertTrue(warning!!.contains("hf_"))
    }
}
