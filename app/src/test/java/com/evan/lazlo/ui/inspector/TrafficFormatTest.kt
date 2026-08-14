package com.evan.lazlo.ui.inspector

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TrafficFormatTest {

    @Test
    fun `relativeTime under 5 seconds reads just now`() {
        val now = Instant.now()
        assertEquals("just now", TrafficFormat.relativeTime(now.minusSeconds(2), now))
    }

    @Test
    fun `relativeTime in seconds`() {
        val now = Instant.now()
        assertEquals("30s ago", TrafficFormat.relativeTime(now.minusSeconds(30), now))
    }

    @Test
    fun `relativeTime in minutes`() {
        val now = Instant.now()
        assertEquals("5m ago", TrafficFormat.relativeTime(now.minusSeconds(300), now))
    }

    @Test
    fun `relativeTime in hours`() {
        val now = Instant.now()
        assertEquals("2h ago", TrafficFormat.relativeTime(now.minusSeconds(7200), now))
    }

    @Test
    fun `relativeTime in days`() {
        val now = Instant.now()
        assertEquals("3d ago", TrafficFormat.relativeTime(now.minusSeconds(3L * 86_400), now))
    }

    @Test
    fun `relativeTime never goes negative for clock skew`() {
        val now = Instant.now()
        assertEquals("just now", TrafficFormat.relativeTime(now.plusSeconds(5), now))
    }

    @Test
    fun `humanBytes under 1024 shows bytes`() {
        assertEquals("512 B", TrafficFormat.humanBytes(512))
        assertEquals("0 B", TrafficFormat.humanBytes(0))
    }

    @Test
    fun `humanBytes in kilobytes`() {
        assertEquals("4.0 KB", TrafficFormat.humanBytes(4096))
    }

    @Test
    fun `humanBytes in megabytes`() {
        assertEquals("1.0 MB", TrafficFormat.humanBytes(1024L * 1024))
    }

    @Test
    fun `statusLabel falls back to an em dash for a missing status`() {
        assertEquals("200", TrafficFormat.statusLabel(200))
        assertEquals("—", TrafficFormat.statusLabel(null))
    }
}
