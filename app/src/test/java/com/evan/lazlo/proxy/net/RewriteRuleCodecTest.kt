package com.evan.lazlo.proxy.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteRuleCodecTest {

    @Test
    fun `rules round-trip through encode and decode`() {
        val rules = listOf(
            RewriteRule(
                id = "1",
                label = "Strip auth",
                hostContains = "example.com",
                appliesToRequest = true,
                appliesToResponse = false,
                removeHeaderName = "Authorization",
            ),
            RewriteRule(
                id = "2",
                enabled = false,
                appliesToRequest = false,
                appliesToResponse = true,
                bodyFind = "old",
                bodyReplace = "new",
            ),
        )
        assertEquals(rules, RewriteRuleCodec.decode(RewriteRuleCodec.encode(rules)))
    }

    @Test
    fun `decoding blank or malformed input yields an empty list`() {
        assertTrue(RewriteRuleCodec.decode(null).isEmpty())
        assertTrue(RewriteRuleCodec.decode("").isEmpty())
        assertTrue(RewriteRuleCodec.decode("not json").isEmpty())
    }

    @Test
    fun `a rule missing its id is skipped rather than crashing the whole decode`() {
        val json = """[{"label":"no id"},{"id":"ok","enabled":true}]"""
        val decoded = RewriteRuleCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded[0].id)
    }

    @Test
    fun `matchesHost is blank-matches-anything, otherwise case-insensitive substring`() {
        val any = RewriteRule(id = "1", hostContains = "")
        assertTrue(any.matchesHost("example.com"))

        val specific = RewriteRule(id = "2", hostContains = "Example")
        assertTrue(specific.matchesHost("api.example.com"))
        assertTrue(!specific.matchesHost("other.test"))
    }
}
