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
            AiBackendCopy.AICORE_PROVIDER_ID,
            AiBackendCopy.MEDIAPIPE_PROVIDER_ID,
        ).map(AiBackendCopy::explanation)
        assertEquals(3, explanations.toSet().size)
    }

    @Test
    fun `api key backend explanation mentions the network`() {
        val text = AiBackendCopy.explanation(AiBackendCopy.API_KEY_PROVIDER_ID)
        assertTrue(text.contains("your own", ignoreCase = true))
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
