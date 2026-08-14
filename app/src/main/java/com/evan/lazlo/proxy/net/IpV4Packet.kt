package com.evan.lazlo.proxy.net

/**
 * Minimal IPv4 header parser/builder (RFC 791) — just enough to route
 * and reframe TCP/UDP payloads captured off the VPN's TUN interface.
 * IPv6 is not handled: [com.evan.lazlo.proxy.MitmVpnService]'s route
 * table only requests IPv4 delivery from the TUN, so an IPv6 packet
 * should never reach [parse] to begin with.
 *
 * Not a data class on purpose — several fields are [ByteArray]s, and a
 * data class's generated equals()/hashCode() would silently fall back
 * to reference identity for those, which is a well-known footgun.
 */
class IpV4Packet(
    val headerLength: Int,
    val protocol: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    /** Everything after the IPv4 header: the TCP/UDP/other segment. */
    val payload: ByteArray,
) {
    companion object {
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17

        fun parse(buffer: ByteArray, length: Int): IpV4Packet? {
            if (length < 20) return null
            val versionAndIhl = buffer[0].toInt() and 0xFF
            val version = versionAndIhl shr 4
            if (version != 4) return null
            val headerLength = (versionAndIhl and 0x0F) * 4
            if (headerLength < 20 || length < headerLength) return null
            val totalLength = u16(buffer, 2)
            val protocol = buffer[9].toInt() and 0xFF
            val sourceAddress = buffer.copyOfRange(12, 16)
            val destinationAddress = buffer.copyOfRange(16, 20)
            val payloadEnd = minOf(totalLength, length)
            if (payloadEnd < headerLength) return null
            val payload = buffer.copyOfRange(headerLength, payloadEnd)
            return IpV4Packet(headerLength, protocol, sourceAddress, destinationAddress, payload)
        }

        /** Builds a full IPv4 packet (20-byte header, no options, + payload) with a correct checksum. */
        fun build(sourceAddress: ByteArray, destinationAddress: ByteArray, protocol: Int, payload: ByteArray): ByteArray {
            val header = ByteArray(20)
            header[0] = 0x45 // version 4, IHL 5 (20 bytes / no options)
            header[1] = 0 // DSCP/ECN
            writeU16(header, 2, 20 + payload.size)
            writeU16(header, 4, 0) // identification
            header[6] = 0x40 // flags: don't fragment
            header[7] = 0
            header[8] = 64 // TTL
            header[9] = protocol.toByte()
            writeU16(header, 10, 0) // checksum placeholder
            System.arraycopy(sourceAddress, 0, header, 12, 4)
            System.arraycopy(destinationAddress, 0, header, 16, 4)
            val checksum = checksum(header)
            writeU16(header, 10, checksum)
            return header + payload
        }

        /** RFC 1071 Internet checksum over `data[start until start+length)`. */
        fun checksum(data: ByteArray, start: Int = 0, length: Int = data.size): Int {
            var sum = 0L
            var i = start
            val end = start + length
            while (i + 1 < end) {
                sum += u16(data, i)
                i += 2
            }
            if (i < end) {
                sum += (data[i].toInt() and 0xFF) shl 8
            }
            while (sum shr 16 != 0L) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            return (sum.inv() and 0xFFFF).toInt()
        }

        fun u16(b: ByteArray, offset: Int): Int =
            ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)

        fun writeU16(b: ByteArray, offset: Int, value: Int) {
            b[offset] = (value ushr 8).toByte()
            b[offset + 1] = value.toByte()
        }
    }
}
