package com.evan.lazlo.proxy.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsMessageTest {

    /** Builds a minimal, real DNS query byte layout (RFC 1035 §4.1.1/§4.1.2) for [name], type A, class IN. */
    private fun buildQuery(name: String): ByteArray {
        val header = ByteArray(12) // ID/flags/counts — irrelevant to question-name parsing, left zeroed
        val question = buildList {
            name.split(".").forEach { label ->
                add(label.length.toByte())
                label.forEach { add(it.code.toByte()) }
            }
            add(0) // root label terminator
            addAll(listOf(0, 1, 0, 1).map { it.toByte() }) // QTYPE=A, QCLASS=IN
        }
        return header + question.toByteArray()
    }

    @Test
    fun `parses a simple query name`() {
        assertEquals("example.com", DnsMessage.parseQuestionName(buildQuery("example.com")))
    }

    @Test
    fun `parses a multi-label subdomain`() {
        assertEquals("api.anthropic.com", DnsMessage.parseQuestionName(buildQuery("api.anthropic.com")))
    }

    @Test
    fun `too-short payload yields null instead of crashing`() {
        assertNull(DnsMessage.parseQuestionName(ByteArray(5)))
    }

    @Test
    fun `a compression pointer in the question section is not decoded`() {
        val payload = ByteArray(12) + byteArrayOf(0xC0.toByte(), 0x0C.toByte())
        assertNull(DnsMessage.parseQuestionName(payload))
    }

    @Test
    fun `a truncated label length does not throw`() {
        // Claims a 200-byte label but the buffer doesn't have it.
        val payload = ByteArray(12) + byteArrayOf(200.toByte(), 1, 2, 3)
        assertNull(DnsMessage.parseQuestionName(payload))
    }
}
