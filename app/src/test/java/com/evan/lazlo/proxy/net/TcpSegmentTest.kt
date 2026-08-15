package com.evan.lazlo.proxy.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpSegmentTest {

    private val source = byteArrayOf(10, 0, 0, 2)
    private val destination = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)

    @Test
    fun `build then parse round-trips header fields and payload`() {
        val payload = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        val built = TcpSegment.build(
            sourceAddress = source,
            destinationAddress = destination,
            sourcePort = 51000,
            destinationPort = 443,
            sequenceNumber = 123456789L,
            acknowledgmentNumber = 987654321L,
            flags = TcpSegment.ACK or TcpSegment.PSH,
            window = 65535,
            payload = payload,
        )

        val parsed = TcpSegment.parse(built)!!

        assertEquals(51000, parsed.sourcePort)
        assertEquals(443, parsed.destinationPort)
        assertEquals(123456789L, parsed.sequenceNumber)
        assertEquals(987654321L, parsed.acknowledgmentNumber)
        assertEquals(65535, parsed.window)
        assertTrue(parsed.ack)
        assertFalse(parsed.syn)
        assertFalse(parsed.fin)
        assertFalse(parsed.rst)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `sequence numbers above Int range survive the round trip`() {
        // A 32-bit unsigned sequence number can exceed Int.MAX_VALUE;
        // the codec stores these as Long specifically to avoid silently
        // truncating or sign-flipping a real ISN drawn from the top half
        // of the 32-bit space.
        val big = 0xFFFFFFF0L
        val built = TcpSegment.build(source, destination, 1, 2, big, big, TcpSegment.SYN, 65535, ByteArray(0))
        val parsed = TcpSegment.parse(built)!!
        assertEquals(big, parsed.sequenceNumber)
        assertEquals(big, parsed.acknowledgmentNumber)
    }

    @Test
    fun `flag helpers reflect each individual bit`() {
        val built = TcpSegment.build(source, destination, 1, 2, 0, 0, TcpSegment.SYN or TcpSegment.RST, 0, ByteArray(0))
        val parsed = TcpSegment.parse(built)!!
        assertTrue(parsed.syn)
        assertTrue(parsed.rst)
        assertFalse(parsed.ack)
        assertFalse(parsed.fin)
    }

    @Test
    fun `nextSequence wraps within the 32-bit sequence space`() {
        // A flow whose ISN lands near the top of the space wraps almost
        // immediately; a counter that just kept adding would stop matching the
        // sequence numbers actually on the wire (which are always 32-bit).
        assertEquals(5L, TcpSegment.nextSequence(0xFFFFFFFBL, 10))
        assertEquals(0L, TcpSegment.nextSequence(0xFFFFFFFFL, 1))
        assertEquals(1_400L, TcpSegment.nextSequence(0L, 1_400))
        // Whatever it produces must survive the wire round trip unchanged.
        val wrapped = TcpSegment.nextSequence(0xFFFFFF00L, 0x200)
        val built = TcpSegment.build(source, destination, 1, 2, wrapped, wrapped, TcpSegment.ACK, 65535, ByteArray(0))
        assertEquals(wrapped, TcpSegment.parse(built)!!.sequenceNumber)
    }

    @Test
    fun `isBeforeSequence uses serial arithmetic rather than plain comparison`() {
        assertTrue(TcpSegment.isBeforeSequence(100L, 200L))
        assertFalse(TcpSegment.isBeforeSequence(200L, 100L))
        assertFalse(TcpSegment.isBeforeSequence(100L, 100L))
        // Across a wrap: 0xFFFFFFFF precedes 1, even though it's numerically
        // larger. A plain `a < b` gets this exactly backwards, which is how a
        // retransmission after a wrap would be mistaken for a future segment.
        assertTrue(TcpSegment.isBeforeSequence(0xFFFFFFFFL, 1L))
        assertFalse(TcpSegment.isBeforeSequence(1L, 0xFFFFFFFFL))
    }

    @Test
    fun `checksum against the pseudo-header is internally consistent`() {
        val built = TcpSegment.build(source, destination, 1, 2, 0, 0, TcpSegment.ACK, 65535, "x".toByteArray())
        val pseudoHeader = ByteArray(12 + built.size)
        System.arraycopy(source, 0, pseudoHeader, 0, 4)
        System.arraycopy(destination, 0, pseudoHeader, 4, 4)
        pseudoHeader[9] = IpV4Packet.PROTOCOL_TCP.toByte()
        IpV4Packet.writeU16(pseudoHeader, 10, built.size)
        System.arraycopy(built, 0, pseudoHeader, 12, built.size)
        assertEquals(0, IpV4Packet.checksum(pseudoHeader))
    }
}
