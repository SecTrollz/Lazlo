package com.evan.lazlo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserScriptCodecTest {

    @Test
    fun `scripts round-trip through encode and decode`() {
        val scripts = listOf(
            BrowserScript(
                id = "1",
                name = "Auto-dismiss cookie banner",
                urlContains = "example.com",
                code = "document.querySelector('#cookie-banner')?.remove();",
            ),
            BrowserScript(id = "2", enabled = false, urlContains = "test.dev", code = "console.log('hi');"),
        )
        assertEquals(scripts, BrowserScriptCodec.decode(BrowserScriptCodec.encode(scripts)))
    }

    @Test
    fun `decoding blank or malformed input yields an empty list`() {
        assertTrue(BrowserScriptCodec.decode(null).isEmpty())
        assertTrue(BrowserScriptCodec.decode("").isEmpty())
        assertTrue(BrowserScriptCodec.decode("not json").isEmpty())
    }

    @Test
    fun `a script missing its id is skipped rather than crashing the whole decode`() {
        val json = """[{"name":"no id"},{"id":"ok","enabled":true}]"""
        val decoded = BrowserScriptCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded[0].id)
    }

    @Test
    fun `matchesUrl requires a non-blank pattern, unlike RewriteRule's blank-matches-anything`() {
        val noPattern = BrowserScript(id = "1", urlContains = "", code = "x")
        assertFalse(noPattern.matchesUrl("https://example.com"))

        val specific = BrowserScript(id = "2", urlContains = "Example", code = "x")
        assertTrue(specific.matchesUrl("https://api.example.com/page"))
        assertFalse(specific.matchesUrl("https://other.test"))
    }
}
