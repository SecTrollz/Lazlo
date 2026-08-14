package com.evan.lazlo.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlBarInputTest {

    @Test
    fun `blank input resolves to about blank`() {
        assertEquals("about:blank", UrlBarInput.resolve(""))
        assertEquals("about:blank", UrlBarInput.resolve("   "))
    }

    @Test
    fun `full https url is passed through unchanged`() {
        assertEquals("https://example.com/path", UrlBarInput.resolve("https://example.com/path"))
    }

    @Test
    fun `full http url is passed through unchanged`() {
        assertEquals("http://example.com", UrlBarInput.resolve("http://example.com"))
    }

    @Test
    fun `bare domain gets https prefixed`() {
        assertEquals("https://example.com", UrlBarInput.resolve("example.com"))
    }

    @Test
    fun `bare domain with path gets https prefixed`() {
        assertEquals("https://example.com/a/b", UrlBarInput.resolve("example.com/a/b"))
    }

    @Test
    fun `localhost with port is treated as a url`() {
        assertEquals("https://localhost:8080", UrlBarInput.resolve("localhost:8080"))
    }

    @Test
    fun `plain words become a search`() {
        val resolved = UrlBarInput.resolve("best pizza near me")
        assertTrue(resolved.startsWith("https://duckduckgo.com/html/?q="))
        assertTrue(resolved.contains("best+pizza+near+me") || resolved.contains("best%20pizza%20near%20me"))
    }

    @Test
    fun `single word with no dot is a search, not a host`() {
        val resolved = UrlBarInput.resolve("weather")
        assertTrue(resolved.startsWith("https://duckduckgo.com/html/?q=weather"))
    }

    @Test
    fun `surrounding whitespace is trimmed before resolving`() {
        assertEquals("https://example.com", UrlBarInput.resolve("  example.com  "))
    }
}
