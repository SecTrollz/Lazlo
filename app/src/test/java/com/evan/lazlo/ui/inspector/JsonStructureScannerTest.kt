package com.evan.lazlo.ui.inspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonStructureScannerTest {

    @Test
    fun `finds a top-level array of records`() {
        val body = """{"items":[{"id":1,"name":"a"},{"id":2,"name":"b"},{"id":3,"name":"c"}]}"""
        val finding = JsonStructureScanner.scan(body)
        assertEquals("$.items", finding?.path)
        assertEquals(3, finding?.itemCount)
    }

    @Test
    fun `finds an array nested a few levels deep`() {
        val body = """{"data":{"result":{"rows":[{"a":1},{"a":2}]}}}"""
        val finding = JsonStructureScanner.scan(body)
        assertEquals("$.data.result.rows", finding?.path)
        assertEquals(2, finding?.itemCount)
    }

    @Test
    fun `prefers the larger, more consistent array over a smaller decoy`() {
        val body = """
            {"decoy":[{"x":1}],"payload":[{"id":1,"name":"a"},{"id":2,"name":"b"},{"id":3,"name":"c"},{"id":4,"name":"d"}]}
        """.trimIndent()
        val finding = JsonStructureScanner.scan(body)
        assertEquals("$.payload", finding?.path)
    }

    @Test
    fun `an array root is handled, not just an object root`() {
        val finding = JsonStructureScanner.scan("""[{"a":1},{"a":2},{"a":3}]""")
        assertEquals("$", finding?.path)
        assertEquals(3, finding?.itemCount)
    }

    @Test
    fun `an array of scalars is not mistaken for a record array`() {
        val finding = JsonStructureScanner.scan("""{"tags":["a","b","c"]}""")
        assertNull(finding)
    }

    @Test
    fun `invalid JSON returns null instead of throwing`() {
        assertNull(JsonStructureScanner.scan("not json at all"))
    }

    @Test
    fun `an empty body returns null`() {
        assertNull(JsonStructureScanner.scan(""))
    }

    @Test
    fun `resolveArrayLength walks a dotted path with indices back into a fresh body`() {
        val body = """{"data":{"items":[{"id":1},{"id":2},{"id":3}]}}"""
        assertEquals(3, JsonStructureScanner.resolveArrayLength(body, "$.data.items"))
    }

    @Test
    fun `resolveArrayLength returns null when the path no longer resolves`() {
        val body = """{"data":{"other":[{"id":1}]}}"""
        assertNull(JsonStructureScanner.resolveArrayLength(body, "$.data.items"))
    }

    @Test
    fun `a path produced by scan resolves cleanly against the same body`() {
        val body = """{"outer":{"list":[{"id":1},{"id":2}]}}"""
        val finding = requireNotNull(JsonStructureScanner.scan(body))
        assertEquals(finding.itemCount, JsonStructureScanner.resolveArrayLength(body, finding.path))
    }

    @Test
    fun `resolveArrayLength handles the root-array path`() {
        assertEquals(2, JsonStructureScanner.resolveArrayLength("""[{"a":1},{"a":2}]""", "$"))
    }
}
