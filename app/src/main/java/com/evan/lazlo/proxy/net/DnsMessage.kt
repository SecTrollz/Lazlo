package com.evan.lazlo.proxy.net

/**
 * Best-effort parser for a DNS query's question name (RFC 1035 §4.1.2) —
 * used only to give UDP:53 traffic a readable label in the traffic log
 * (`example.com` instead of a bare destination IP), never for any
 * routing or protocol decision. UDP itself is never dropped or bypassed
 * to reach this — [TcpIpStack.handleUdp] already relays every UDP
 * datagram through a `protect()`-ed socket regardless of whether this
 * parse succeeds; this only affects what gets shown for it.
 */
object DnsMessage {
    private const val HEADER_SIZE = 12

    fun parseQuestionName(payload: ByteArray): String? {
        if (payload.size <= HEADER_SIZE) return null
        val labels = StringBuilder()
        var offset = HEADER_SIZE
        while (offset < payload.size) {
            val len = payload[offset].toInt() and 0xFF
            if (len == 0) break
            // A compression pointer (top two bits set) isn't expected in a
            // query's own first question name — bail rather than risk
            // mis-decoding into a wrong label.
            if (len and 0xC0 == 0xC0) return null
            offset += 1
            if (offset + len > payload.size) return null
            if (labels.isNotEmpty()) labels.append('.')
            labels.append(String(payload, offset, len, Charsets.US_ASCII))
            offset += len
        }
        return labels.toString().takeIf { it.isNotEmpty() }
    }
}
