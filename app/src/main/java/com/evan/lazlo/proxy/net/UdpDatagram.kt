package com.evan.lazlo.proxy.net

import com.evan.lazlo.proxy.net.IpV4Packet.Companion.u16
import com.evan.lazlo.proxy.net.IpV4Packet.Companion.writeU16

/**
 * Minimal UDP datagram parser/builder (RFC 768). Used only for
 * passthrough (mainly DNS, see [TcpIpStack]) — datagrams aren't
 * inspected or logged, just forwarded so the rest of the device's
 * traffic keeps working while the inspector is on.
 */
class UdpDatagram(
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
) {
    companion object {
        fun parse(buffer: ByteArray): UdpDatagram? {
            if (buffer.size < 8) return null
            val sourcePort = u16(buffer, 0)
            val destinationPort = u16(buffer, 2)
            val length = u16(buffer, 4)
            val payloadEnd = minOf(length, buffer.size)
            if (payloadEnd < 8) return null
            return UdpDatagram(sourcePort, destinationPort, buffer.copyOfRange(8, payloadEnd))
        }

        /**
         * Builds a full UDP datagram (8-byte header + payload). The
         * checksum field is left as 0 ("not computed"), which RFC 768
         * explicitly allows for IPv4 — real stacks and middleboxes treat
         * it as valid, and computing it correctly would need the same
         * IPv4 pseudo-header plumbing [TcpSegment] uses for no gain here.
         */
        fun build(sourcePort: Int, destinationPort: Int, payload: ByteArray): ByteArray {
            val datagram = ByteArray(8 + payload.size)
            writeU16(datagram, 0, sourcePort)
            writeU16(datagram, 2, destinationPort)
            writeU16(datagram, 4, datagram.size)
            writeU16(datagram, 6, 0) // checksum: not computed
            System.arraycopy(payload, 0, datagram, 8, payload.size)
            return datagram
        }
    }
}
