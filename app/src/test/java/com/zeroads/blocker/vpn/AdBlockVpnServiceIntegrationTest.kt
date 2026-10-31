package com.zeroads.blocker.vpn

import com.zeroads.blocker.dns.DnsPacketParser
import com.zeroads.blocker.dns.DnsResponseBuilder
import com.zeroads.blocker.dns.DomainFilter
import com.zeroads.blocker.dns.SocketProtector
import com.zeroads.blocker.dns.UpstreamDnsResolver
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AdBlockVpnServiceIntegrationTest {

    private val dummyProtector = object : SocketProtector {
        override fun protect(socket: DatagramSocket): Boolean = true
        override fun protect(socket: Socket): Boolean = true
    }

    @Test
    fun testBlockedDnsProducesNxDomain() {
        val domainFilter = DomainFilter()
        domainFilter.loadEntries(listOf("ads.example.com"))

        val resolver = UpstreamDnsResolver(dummyProtector) // won't actually be called
        val stats = MutableStateFlow(VpnStats())

        val handler = VpnPacketHandler(
            AtomicBoolean(true),
            IPv4PacketProcessor(),
            DnsPacketParser(),
            DnsResponseBuilder(),
            domainFilter,
            resolver,
            stats,
            Any()
        )

        val queryPacket = buildIpUdpDnsPacket("ads.example.com")
        val outputStream = ByteArrayOutputStream()

        handler.handlePacket(queryPacket, queryPacket.size, outputStream)

        val responseBytes = outputStream.toByteArray()
        assertTrue(responseBytes.isNotEmpty())

        val parser = IPv4PacketProcessor()
        val parsedIp = parser.parseUdpPacket(responseBytes, responseBytes.size)!!

        // 10.111.0.2 is the client source IP, so dest should be 10.111.0.2
        assertEquals(0x0A6F0002, parsedIp.destIp)

        // DNS Response Check
        val dnsBuffer = ByteBuffer.wrap(parsedIp.payload)
        dnsBuffer.short // skip ID
        val flags = dnsBuffer.short.toInt() and 0xFFFF
        assertEquals(3, flags and 0x000F) // NXDOMAIN (RCODE 3)

        assertEquals(1L, stats.value.blockedRequests)
    }

    @Test
    fun testAllowedDnsProducesServfailIfUpstreamFails() {
        val domainFilter = DomainFilter()
        val resolver = UpstreamDnsResolver(dummyProtector, "127.0.0.1", 1, 100) // Port 1 should fail
        val stats = MutableStateFlow(VpnStats())

        val handler = VpnPacketHandler(
            AtomicBoolean(true),
            IPv4PacketProcessor(),
            DnsPacketParser(),
            DnsResponseBuilder(),
            domainFilter,
            resolver,
            stats,
            Any()
        )

        val queryPacket = buildIpUdpDnsPacket("google.com")
        val outputStream = ByteArrayOutputStream()

        handler.handlePacket(queryPacket, queryPacket.size, outputStream)

        val responseBytes = outputStream.toByteArray()
        assertTrue(responseBytes.isNotEmpty())

        val parser = IPv4PacketProcessor()
        val parsedIp = parser.parseUdpPacket(responseBytes, responseBytes.size)!!

        val dnsBuffer = ByteBuffer.wrap(parsedIp.payload)
        dnsBuffer.short // skip ID
        val flags = dnsBuffer.short.toInt() and 0xFFFF
        assertEquals(2, flags and 0x000F) // SERVFAIL (RCODE 2)

        assertEquals(1L, stats.value.failedUpstreamRequests)
        assertEquals(0L, stats.value.allowedRequests) // Not allowed fully because upstream failed
    }

    @Test
    fun testQueueOverflowProducesServfail() {
        val domainFilter = DomainFilter()
        val resolver = UpstreamDnsResolver(dummyProtector)
        val stats = MutableStateFlow(VpnStats())

        val handler = VpnPacketHandler(
            AtomicBoolean(true),
            IPv4PacketProcessor(),
            DnsPacketParser(),
            DnsResponseBuilder(),
            domainFilter,
            resolver,
            stats,
            Any()
        )

        val queryPacket = buildIpUdpDnsPacket("safe.com")
        val outputStream = ByteArrayOutputStream()

        // Call the rejection handler directly
        handler.handleRejectedPacket(queryPacket, queryPacket.size, outputStream)

        val responseBytes = outputStream.toByteArray()
        val parsedIp = IPv4PacketProcessor().parseUdpPacket(responseBytes, responseBytes.size)!!
        val dnsBuffer = ByteBuffer.wrap(parsedIp.payload)
        dnsBuffer.short // skip ID
        val flags = dnsBuffer.short.toInt() and 0xFFFF
        assertEquals(2, flags and 0x000F) // SERVFAIL

        assertEquals(1L, stats.value.overloadedRequests)
    }

    private fun buildIpUdpDnsPacket(domain: String): ByteArray {
        val dnsBuffer = ByteBuffer.allocate(512)
        dnsBuffer.putShort(1234) // ID
        dnsBuffer.putShort(0x0100) // Flags
        dnsBuffer.putShort(1) // qdcount
        dnsBuffer.putShort(0)
        dnsBuffer.putShort(0)
        dnsBuffer.putShort(0)

        domain.split(".").forEach { label ->
            dnsBuffer.put(label.length.toByte())
            dnsBuffer.put(label.toByteArray(Charsets.US_ASCII))
        }
        dnsBuffer.put(0) // root
        dnsBuffer.putShort(1) // type A
        dnsBuffer.putShort(1) // class IN

        val payload = ByteArray(dnsBuffer.position())
        dnsBuffer.position(0)
        dnsBuffer.get(payload)

        val ipHeaderLength = 20
        val udpHeaderLength = 8
        val totalLength = ipHeaderLength + udpHeaderLength + payload.size

        val response = ByteArray(totalLength)
        val buffer = ByteBuffer.wrap(response)

        buffer.put(0x45.toByte()) // Version 4, IHL 5
        buffer.put(0x00.toByte())
        buffer.putShort(totalLength.toShort())
        buffer.putShort(0x0000.toShort())
        buffer.putShort(0x0000.toShort())
        buffer.put(64.toByte())
        buffer.put(17.toByte()) // Protocol (UDP)
        buffer.putShort(0)
        buffer.putInt(0x0A6F0002) // Src IP: 10.111.0.2
        buffer.putInt(0x0A6F0001) // Dest IP: 10.111.0.1

        buffer.putShort(12345.toShort()) // Src Port
        buffer.putShort(53.toShort()) // Dest Port
        buffer.putShort((udpHeaderLength + payload.size).toShort())
        buffer.putShort(0)

        buffer.put(payload)

        return response
    }
}