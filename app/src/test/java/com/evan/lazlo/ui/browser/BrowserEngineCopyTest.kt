package com.evan.lazlo.ui.browser

import com.evan.lazlo.browser.EngineKind
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserEngineCopyTest {

    @Test
    fun `each engine has a distinct explanation and label`() {
        assertNotEquals(
            BrowserEngineCopy.explanation(EngineKind.CHROMIUM),
            BrowserEngineCopy.explanation(EngineKind.GECKO),
        )
        assertNotEquals(
            BrowserEngineCopy.shortLabel(EngineKind.CHROMIUM),
            BrowserEngineCopy.shortLabel(EngineKind.GECKO),
        )
    }

    @Test
    fun `chromium explanation mentions the built-in webview`() {
        assertTrue(BrowserEngineCopy.explanation(EngineKind.CHROMIUM).contains("built-in", ignoreCase = true))
    }

    @Test
    fun `gecko explanation mentions firefox`() {
        assertTrue(BrowserEngineCopy.explanation(EngineKind.GECKO).contains("Firefox", ignoreCase = true))
    }

    @Test
    fun `chromium explanation calls out that it's hardened against WebView fingerprinting`() {
        val explanation = BrowserEngineCopy.explanation(EngineKind.CHROMIUM)
        assertTrue(explanation.contains("fingerprint", ignoreCase = true))
        assertTrue(BrowserEngineCopy.shortLabel(EngineKind.CHROMIUM).contains("hardened", ignoreCase = true))
    }

    @Test
    fun `gecko is labeled as the default, since it doesn't carry WebView's embedded-browser signature`() {
        assertTrue(BrowserEngineCopy.shortLabel(EngineKind.GECKO).contains("default", ignoreCase = true))
    }
}
