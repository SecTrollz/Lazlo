package com.evan.lazlo.ai

import com.google.ai.edge.aicore.GenerativeAIException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the exact real-world crash this class exists to translate:
 * "AICore failed with error type 2-INFERENCE_ERROR and error code
 * 8-NOT_AVAILABLE: Required LLM feature not found" — reported against a
 * genuinely AICore-supported Pixel. [AiCoreDiagnosis] turns that numeric
 * code into something a first-time user can act on instead of a raw SDK
 * error string.
 */
class AiCoreDiagnosisTest {

    @Test
    fun `NOT_AVAILABLE is diagnosed as device ineligibility, not a transient error`() {
        val code = GenerativeAIException.ErrorCode.NOT_AVAILABLE
        assertTrue(AiCoreDiagnosis.isDeviceIneligible(code))
        val message = AiCoreDiagnosis.messageFor(code, "Required LLM feature not found")
        assertTrue(message.contains("Developer Preview", ignoreCase = true) || message.contains("Play Services", ignoreCase = true))
    }

    @Test
    fun `NEEDS_SYSTEM_UPDATE is diagnosed as device ineligibility`() {
        val code = GenerativeAIException.ErrorCode.NEEDS_SYSTEM_UPDATE
        assertTrue(AiCoreDiagnosis.isDeviceIneligible(code))
        assertTrue(AiCoreDiagnosis.messageFor(code, null).contains("system update", ignoreCase = true))
    }

    @Test
    fun `transient errors are not flagged as device ineligibility`() {
        val busy = GenerativeAIException.ErrorCode.BUSY
        assertFalse(AiCoreDiagnosis.isDeviceIneligible(busy))
        assertTrue(AiCoreDiagnosis.messageFor(busy, null).contains("busy", ignoreCase = true))

        val disconnected = GenerativeAIException.ErrorCode.SERVICE_DISCONNECTED
        assertFalse(AiCoreDiagnosis.isDeviceIneligible(disconnected))
    }

    @Test
    fun `disk space error names the actual fix`() {
        val message = AiCoreDiagnosis.messageFor(GenerativeAIException.ErrorCode.NOT_ENOUGH_DISK_SPACE, null)
        assertTrue(message.contains("storage", ignoreCase = true))
    }

    @Test
    fun `unknown codes fall back to the SDK message without crashing`() {
        val message = AiCoreDiagnosis.messageFor(GenerativeAIException.ErrorCode.UNKNOWN, "something odd")
        assertTrue(message.contains("something odd"))
    }
}
