package com.zeroads.blocker.vpn

import com.zeroads.blocker.dns.DnsPacketParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class DnsPacketParserTest {

    private val parser = DnsPacketParser()

    @Test
    fun testValidAQuery() {
        val packet = buildQueryPacket("example.com", 1) // A record
        val parsed = parser.parse(packet)
        assertNotNull(parsed)
        assertEquals("example.com", parsed?.queryDomain)
        assertEquals(1.toShort(), parsed?.queryType) // A
        assertEquals(1.toShort(), parsed?.questionCount)
    }

    @Test
    fun testValidAAAAQuery() {
        val packet = buildQueryPacket("ipv6.google.com", 28) // AAAA record
        val parsed = parser.parse(packet)
        assertNotNull(parsed)
        assertEquals("ipv6.google.com", parsed?.queryDomain)
        assertEquals(28.toShort(), parsed?.queryType) // AAAA
    }

    @Test
    fun testValidCNAMEQuery() {
        val packet = buildQueryPacket("cname.example.com", 5) // CNAME record
        val parsed = parser.parse(packet)
        assertNotNull(parsed)
        assertEquals("cname.example.com", parsed?.queryDomain)
        assertEquals(5.toShort(), parsed?.queryType) // CNAME
    }

    @Test
    fun testInvalidHeader() {
        val packet = ByteArray(10) // Too short
        assertNull(parser.parse(packet))
    }

    @Test
    fun testTruncatedPacket() {
        val packet = buildQueryPacket("example.com", 1).copyOf(15) // Cut off question
        assertNull(parser.parse(packet))
    }

    @Test
    fun testInvalidLabelLength() {
        val packet = buildQueryPacket("example.com", 1)
        packet[12] = 70.toByte() // Over 63 limit. Index 12 is the first label length
        assertNull(parser.parse(packet))
    }

    @Test
    fun testMalformedCompressionPointerOutOfBounds() {
        val buffer = ByteBuffer.allocate(512)
        buffer.putShort(1234) // ID
        buffer.putShort(0x0100) // Flags
        buffer.putShort(1) // qdcount
        buffer.putShort(0) // ancount
        buffer.putShort(0) // nscount
        buffer.putShort(0) // arcount

        // Add a compression pointer pointing to offset 500 (out of bounds)
        buffer.put(0xC0.toByte())
        buffer.put(0xF4.toByte()) // 0xC1F4 = offset 500
        buffer.putShort(1) // Type
        buffer.putShort(1) // Class

        val packet = ByteArray(buffer.position())
        buffer.position(0)
        buffer.get(packet)

        assertNull(parser.parse(packet))
    }

    @Test
    fun testPointerLoop() {
        val buffer = ByteBuffer.allocate(512)
        buffer.putShort(1234) // ID
        buffer.putShort(0x0100) // Flags
        buffer.putShort(1) // qdcount
        buffer.putShort(0) // ancount
        buffer.putShort(0) // nscount
        buffer.putShort(0) // arcount

        // Position 12: compression pointer pointing to itself (offset 12)
        buffer.put(0xC0.toByte())
        buffer.put(0x0C.toByte())
        buffer.putShort(1) // Type
        buffer.putShort(1) // Class

        val packet = ByteArray(buffer.position())
        buffer.position(0)
        buffer.get(packet)

        assertNull(parser.parse(packet))
    }

    @Test
    fun testMultipleQuestionsRejected() {
        val buffer = ByteBuffer.allocate(512)
        buffer.putShort(1234) // ID
        buffer.putShort(0x0100) // Flags
        buffer.putShort(2) // qdcount = 2 (Multiple questions)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)

        val packet = ByteArray(buffer.position())
        buffer.position(0)
        buffer.get(packet)

        assertNull(parser.parse(packet))
    }

    @Test
    fun testInvalidQueryFlagsResponsePacket() {
        val buffer = ByteBuffer.allocate(512)
        buffer.putShort(1234) // ID
        buffer.putShort(0x8180.toShort()) // QR bit set (0x8000), it's a response packet!
        buffer.putShort(1) // qdcount
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)

        val packet = ByteArray(buffer.position())
        buffer.position(0)
        buffer.get(packet)

        assertNull(parser.parse(packet))
    }

    @Test
    fun testMaxDomainLengthExceeded() {
        // Construct a domain > 253 chars e.g. "a" * 60 + "." + "a" * 60 + "." + "a" * 60 + "." + "a" * 60 + "." + "a" * 20
        val longDomain = "a".repeat(60) + "." + "a".repeat(60) + "." + "a".repeat(60) + "." + "a".repeat(60) + "." + "a".repeat(20)
        val packet = buildQueryPacket(longDomain, 1)
        assertNull(parser.parse(packet))
    }

    private fun buildQueryPacket(domain: String, type: Short): ByteArray {
        val buffer = ByteBuffer.allocate(512)
        buffer.putShort(1234) // ID
        buffer.putShort(0x0100) // Flags
        buffer.putShort(1) // qdcount
        buffer.putShort(0) // ancount
        buffer.putShort(0) // nscount
        buffer.putShort(0) // arcount

        domain.split(".").forEach { label ->
            buffer.put(label.length.toByte())
            buffer.put(label.toByteArray(Charsets.US_ASCII))
        }
        buffer.put(0) // root
        buffer.putShort(type) // type
        buffer.putShort(1) // class

        val result = ByteArray(buffer.position())
        buffer.position(0)
        buffer.get(result)
        return result
    }
}