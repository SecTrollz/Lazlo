package com.evan.lazlo.ui.inspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearnedJsonPathCacheTest {

    @Test
    fun `nothing learned yet returns null`() {
        assertNull(LearnedJsonPathCache().get("api.example.com"))
    }

    @Test
    fun `a learned finding is returned for its host`() {
        val cache = LearnedJsonPathCache()
        val finding = JsonStructureScanner.Finding("$.data.items", 5)
        cache.learn("api.example.com", finding)
        assertEquals(finding, cache.get("api.example.com"))
    }

    @Test
    fun `hosts don't share cached findings`() {
        val cache = LearnedJsonPathCache()
        cache.learn("api.example.com", JsonStructureScanner.Finding("$.a", 1))
        assertNull(cache.get("other.example.com"))
    }

    @Test
    fun `forget clears a specific host without touching others`() {
        val cache = LearnedJsonPathCache()
        cache.learn("api.example.com", JsonStructureScanner.Finding("$.a", 1))
        cache.learn("other.example.com", JsonStructureScanner.Finding("$.b", 2))
        cache.forget("api.example.com")
        assertNull(cache.get("api.example.com"))
        assertEquals(2, cache.get("other.example.com")?.itemCount)
    }

    @Test
    fun `learning again for the same host overwrites the previous finding`() {
        val cache = LearnedJsonPathCache()
        cache.learn("api.example.com", JsonStructureScanner.Finding("$.old", 1))
        cache.learn("api.example.com", JsonStructureScanner.Finding("$.new", 9))
        assertEquals("$.new", cache.get("api.example.com")?.path)
    }
}
