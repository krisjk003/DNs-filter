package com.zeroads.blocker.vpn

import com.zeroads.blocker.dns.DnsPacketParser
import com.zeroads.blocker.dns.DnsResponseBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class DnsResponseBuilderTest {

    private val parser = DnsPacketParser()
    private val builder = DnsResponseBuilder()

    @Test
    fun testNxDomainResponse() {
        val originalPacket = buildQueryPacket("ads.doubleclick.net")
        val parsedQuery = parser.parse(originalPacket)!!

        val response = builder.buildErrorResponse(parsedQuery, DnsResponseBuilder.RCODE_NXDOMAIN)

        val buffer = ByteBuffer.wrap(response)

        // 1. Transaction ID should match
        assertEquals(1234.toShort(), buffer.short)

        // 2. Flags: QR=1, RCODE=3 (NXDOMAIN)
        val flags = buffer.short.toInt() and 0xFFFF
        assertEquals(0x8183, flags)

        // 3. QDCOUNT = 1, others 0
        assertEquals(1.toShort(), buffer.short)
        assertEquals(0.toShort(), buffer.short)
        assertEquals(0.toShort(), buffer.short)
        assertEquals(0.toShort(), buffer.short)

        // 4. Question should be preserved
        // Just verify length for simplicity
        assertEquals(originalPacket.size, response.size)
    }

    @Test
    fun testServFailResponse() {
        val originalPacket = buildQueryPacket("example.com")
        val parsedQuery = parser.parse(originalPacket)!!

        val response = builder.buildErrorResponse(parsedQuery, DnsResponseBuilder.RCODE_SERVFAIL)

        val buffer = ByteBuffer.wrap(response)
        buffer.short // Skip ID

        val flags = buffer.short.toInt() and 0xFFFF
        assertEquals(0x8182, flags) // RCODE 2
    }

    private fun buildQueryPacket(domain: String): ByteArray {
        val buffer = ByteBuffer.allocate(512)
        buffer.putShort(1234) // ID
        buffer.putShort(0x0100) // Flags (RD=1)
        buffer.putShort(1) // qdcount
        buffer.putShort(0) // ancount
        buffer.putShort(0) // nscount
        buffer.putShort(0) // arcount

        domain.split(".").forEach { label ->
            buffer.put(label.length.toByte())
            buffer.put(label.toByteArray(Charsets.US_ASCII))
        }
        buffer.put(0) // root
        buffer.putShort(1) // type A
        buffer.putShort(1) // class IN

        val result = ByteArray(buffer.position())
        buffer.position(0)
        buffer.get(result)
        return result
    }
}