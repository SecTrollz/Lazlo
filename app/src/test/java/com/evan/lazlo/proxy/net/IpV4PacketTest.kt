package com.evan.lazlo.proxy.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpV4PacketTest {

    private val source = byteArrayOf(10, 0, 0, 2)
    private val destination = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)

    @Test
    fun `build then parse round-trips every field`() {
        val payload = "hello".toByteArray()
        val built = IpV4Packet.build(source, destination, IpV4Packet.PROTOCOL_TCP, payload)

        val parsed = IpV4Packet.parse(built, built.size)!!

        assertEquals(20, parsed.headerLength)
        assertEquals(IpV4Packet.PROTOCOL_TCP, parsed.protocol)
        assertArrayEquals(source, parsed.sourceAddress)
        assertArrayEquals(destination, parsed.destinationAddress)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `built header checksum is internally consistent`() {
        val built = IpV4Packet.build(source, destination, IpV4Packet.PROTOCOL_UDP, ByteArray(0))
        // The Internet checksum's defining property: summing a buffer that
        // already contains its own correct checksum field yields exactly 0.
        assertEquals(0, IpV4Packet.checksum(built, 0, 20))
    }

    @Test
    fun `parse rejects a buffer shorter than a minimal header`() {
        assertNull(IpV4Packet.parse(ByteArray(10), 10))
    }

    @Test
    fun `parse rejects a non-IPv4 version nibble`() {
        val built = IpV4Packet.build(source, destination, IpV4Packet.PROTOCOL_TCP, ByteArray(0))
        built[0] = 0x65 // version 6, IHL 5 — an IPv6 packet would never carry this shape, but exercises the guard
        assertNull(IpV4Packet.parse(built, built.size))
    }
}
