package com.evan.lazlo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChromiumUserAgentTest {

    @Test
    fun `strips the wv marker and the Version token from a real WebView default UA`() {
        val webViewUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240905.003.A2; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.6478.122 Mobile Safari/537.36"

        val hardened = ChromiumUserAgent.stripEmbeddedBrowserMarkers(webViewUa)

        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240905.003.A2) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36",
            hardened,
        )
    }

    @Test
    fun `neither tell survives`() {
        val hardened = ChromiumUserAgent.stripEmbeddedBrowserMarkers(
            "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0 Mobile Safari/537.36",
        )
        assertFalse(hardened.contains("; wv)"))
        assertFalse(hardened.contains("Version/"))
    }

    @Test
    fun `a UA with no WebView markers passes through unchanged, e g an already-real Chrome UA`() {
        val chromeUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
        assertEquals(chromeUa, ChromiumUserAgent.stripEmbeddedBrowserMarkers(chromeUa))
    }

    @Test
    fun `only strips the version token immediately before Chrome, not an unrelated Version string elsewhere`() {
        // A defensive case: this format doesn't occur in real WebView UAs, but the
        // trailing-space-anchored regex should only ever consume the WebView-inserted
        // token, not any other "Version/x.y" substring a UA might happen to contain.
        val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 MyVersion/9.9 Safari/537.36"
        assertEquals(ua, ChromiumUserAgent.stripEmbeddedBrowserMarkers(ua))
    }
}
