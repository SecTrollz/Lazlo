package com.evan.lazlo.proxy.net

import com.evan.lazlo.proxy.net.IpV4Packet.Companion.u16
import com.evan.lazlo.proxy.net.IpV4Packet.Companion.writeU16

/**
 * Minimal TCP header parser/builder (RFC 793): ports, sequence/ack
 * numbers, flags, window, and payload — enough for [TcpIpStack]'s flow
 * state machine.
 *
 * TCP options (MSS, window scale, SACK, timestamps) are parsed only far
 * enough to skip over them; none are honored on the way out. That's a
 * deliberate simplification for what this stack actually needs to
 * handle: one well-behaved *local* TCP peer (the device's own apps)
 * terminating directly into a relay we control, not a general-purpose
 * stack surviving a lossy, adversarial real network path.
 */
class TcpSegment(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Long,
    val acknowledgmentNumber: Long,
    val flags: Int,
    val window: Int,
    val payload: ByteArray,
) {
    val syn get() = flags and SYN != 0
    val ack get() = flags and ACK != 0
    val fin get() = flags and FIN != 0
    val rst get() = flags and RST != 0

    companion object {
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10

        /** Mask for the 32-bit sequence-number space these helpers wrap within. */
        const val SEQUENCE_MASK = 0xFFFFFFFFL

        /**
         * Advances a sequence number by [advanceBy] bytes, wrapping within TCP's
         * 32-bit sequence space (RFC 793 §3.3) rather than counting off the end
         * of it.
         *
         * This matters because [TcpIpStack] compares its tracked sequence
         * numbers against ones parsed straight off the wire, which are always
         * 0..2^32-1. A counter that just kept incrementing would silently stop
         * matching the moment a flow's sequence numbers wrapped — which happens
         * after 4GB on the flow, but also *immediately* for a flow whose
         * randomly-chosen initial sequence number started near the top of the
         * space.
         */
        fun nextSequence(sequenceNumber: Long, advanceBy: Long): Long = (sequenceNumber + advanceBy) and SEQUENCE_MASK

        /**
         * Serial-number comparison (RFC 1982): true when [a] sits *before* [b]
         * in TCP's wrapping sequence space. A plain `a < b` is wrong across a
         * wrap — 0xFFFFFFFF is "before" 0x00000001, not after it — which is
         * what distinguishes an already-acked retransmission from a genuinely
         * out-of-order future segment.
         */
        fun isBeforeSequence(a: Long, b: Long): Boolean = ((a - b) and SEQUENCE_MASK).toInt() < 0

        fun parse(buffer: ByteArray): TcpSegment? {
            if (buffer.size < 20) return null
            val sourcePort = u16(buffer, 0)
            val destinationPort = u16(buffer, 2)
            val sequenceNumber = u32(buffer, 4)
            val acknowledgmentNumber = u32(buffer, 8)
            val dataOffsetAndReserved = buffer[12].toInt() and 0xFF
            val headerLength = (dataOffsetAndReserved shr 4) * 4
            if (headerLength < 20 || buffer.size < headerLength) return null
            val flags = buffer[13].toInt() and 0x3F
            val window = u16(buffer, 14)
            val payload = buffer.copyOfRange(headerLength, buffer.size)
            return TcpSegment(sourcePort, destinationPort, sequenceNumber, acknowledgmentNumber, flags, window, payload)
        }

        /**
         * Builds a full TCP segment (20-byte header, no options, + payload)
         * with a correct checksum against an IPv4 pseudo-header — the
         * checksum needs the *reply's* addressing (source = this device
         * acting as the destination host, destination = the real app), not
         * the original packet's.
         */
        fun build(
            sourceAddress: ByteArray,
            destinationAddress: ByteArray,
            sourcePort: Int,
            destinationPort: Int,
            sequenceNumber: Long,
            acknowledgmentNumber: Long,
            flags: Int,
            window: Int,
            payload: ByteArray,
        ): ByteArray {
            val header = ByteArray(20)
            writeU16(header, 0, sourcePort)
            writeU16(header, 2, destinationPort)
            writeU32(header, 4, sequenceNumber)
            writeU32(header, 8, acknowledgmentNumber)
            header[12] = (5 shl 4).toByte() // data offset = 5 words (20 bytes), no options
            header[13] = flags.toByte()
            writeU16(header, 14, window)
            writeU16(header, 16, 0) // checksum placeholder
            writeU16(header, 18, 0) // urgent pointer

            val segment = header + payload
            val checksum = pseudoHeaderChecksum(sourceAddress, destinationAddress, segment)
            writeU16(segment, 16, checksum)
            return segment
        }

        private fun pseudoHeaderChecksum(src: ByteArray, dst: ByteArray, segment: ByteArray): Int {
            val buffer = ByteArray(12 + segment.size)
            System.arraycopy(src, 0, buffer, 0, 4)
            System.arraycopy(dst, 0, buffer, 4, 4)
            buffer[8] = 0
            buffer[9] = IpV4Packet.PROTOCOL_TCP.toByte()
            writeU16(buffer, 10, segment.size)
            System.arraycopy(segment, 0, buffer, 12, segment.size)
            return IpV4Packet.checksum(buffer)
        }

        private fun u32(b: ByteArray, offset: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
            return v and 0xFFFFFFFFL
        }

        private fun writeU32(b: ByteArray, offset: Int, value: Long) {
            b[offset] = (value ushr 24).toByte()
            b[offset + 1] = (value ushr 16).toByte()
            b[offset + 2] = (value ushr 8).toByte()
            b[offset + 3] = value.toByte()
        }
    }
}
