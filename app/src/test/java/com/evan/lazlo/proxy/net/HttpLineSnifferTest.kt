package com.evan.lazlo.proxy.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpLineSnifferTest {

    @Test
    fun `extracts a request line from the first chunk`() {
        val sniffer = HttpLineSniffer()
        val bytes = "GET /index.html HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        sniffer.feed(bytes, bytes.size)
        assertEquals("GET /index.html HTTP/1.1", sniffer.requestLine)
        assertNull(sniffer.statusCode)
    }

    @Test
    fun `extracts a status code from the first chunk`() {
        val sniffer = HttpLineSniffer()
        val bytes = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()
        sniffer.feed(bytes, bytes.size)
        assertEquals(404, sniffer.statusCode)
        assertNull(sniffer.requestLine)
    }

    @Test
    fun `non-HTTP binary data yields neither, without throwing`() {
        val sniffer = HttpLineSniffer()
        val bytes = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x50, -1, -2, -3) // looks like a TLS record, not HTTP
        sniffer.feed(bytes, bytes.size)
        assertNull(sniffer.requestLine)
        assertNull(sniffer.statusCode)
    }

    @Test
    fun `only the first fed chunk is ever inspected`() {
        val sniffer = HttpLineSniffer()
        val junk = "not http".toByteArray()
        val real = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        sniffer.feed(junk, junk.size)
        sniffer.feed(real, real.size) // arrives "too late" — first call already consumed the one chance
        assertNull(sniffer.requestLine)
    }
}
