package com.evan.lazlo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
