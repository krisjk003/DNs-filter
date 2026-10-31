package com.zeroads.blocker.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class IPv4PacketProcessorTest {

    private val processor = IPv4PacketProcessor()

    @Test
    fun testValidIPv4UdpPacket() {
        val payload = "Hello DNS".toByteArray()
        val packet = buildIpUdpPacket(17, 12345, 53, payload)

        val parsed = processor.parseUdpPacket(packet, packet.size)
        assertNotNull(parsed)
        assertEquals(12345, parsed?.sourcePort)
        assertEquals(53, parsed?.destPort)
        assertArrayEquals(payload, parsed?.payload)
    }

    @Test
    fun testInvalidIpHeader() {
        val payload = "Hello".toByteArray()
        val packet = buildIpUdpPacket(17, 12345, 53, payload)
        packet[0] = 0x55.toByte() // Version 5 instead of 4

        val parsed = processor.parseUdpPacket(packet, packet.size)
        assertNull(parsed)
    }

    @Test
    fun testNonUdpPacket() {
        val payload = "TCP Syn".toByteArray()
        val packet = buildIpUdpPacket(6, 12345, 80, payload) // TCP is 6

        val parsed = processor.parseUdpPacket(packet, packet.size)
        assertNull(parsed) // Should reject non-UDP
    }

    @Test
    fun testInvalidTotalLength() {
        val payload = "Hello".toByteArray()
        val packet = buildIpUdpPacket(17, 12345, 53, payload)

        // Put a total length shorter than header length at bytes 2-3
        val buffer = ByteBuffer.wrap(packet)
        buffer.putShort(2, 10.toShort())

        val parsed = processor.parseUdpPacket(packet, packet.size)
        assertNull(parsed)
    }

    @Test
    fun testFragmentedPackets() {
        val payload = "Fragment".toByteArray()
        val packet = buildIpUdpPacket(17, 12345, 53, payload)

        // Set More Fragments (MF) bit in flags at bytes 6-7 (0x2000)
        val buffer = ByteBuffer.wrap(packet)
        buffer.putShort(6, 0x2000.toShort())

        val parsed = processor.parseUdpPacket(packet, packet.size)
        assertNull(parsed)
    }

    @Test
    fun testIncorrectUdpLength() {
        val payload = "Hello".toByteArray()
        val packet = buildIpUdpPacket(17, 12345, 53, payload)

        // Modify UDP length field to be < 8 at bytes 24-25
        val buffer = ByteBuffer.wrap(packet)
        buffer.putShort(24, 4.toShort())

        val parsed = processor.parseUdpPacket(packet, packet.size)
        assertNull(parsed)
    }

    @Test
    fun testIpAddressAndPortReversal() {
        val reqPayload = "Request".toByteArray()
        val originalPacket = buildIpUdpPacket(17, 50000, 53, reqPayload)

        val parsed = processor.parseUdpPacket(originalPacket, originalPacket.size)!!
        assertEquals(0x0A000002, parsed.sourceIp)
        assertEquals(0x0A000001, parsed.destIp)

        val respPayload = "Response".toByteArray()
        val response = processor.buildUdpResponse(parsed, respPayload)

        val parsedResp = processor.parseUdpPacket(response, response.size)!!

        // Source and destination IP/ports should be reversed
        assertEquals(0x0A000001, parsedResp.sourceIp)
        assertEquals(0x0A000002, parsedResp.destIp)
        assertEquals(53, parsedResp.sourcePort)
        assertEquals(50000, parsedResp.destPort)
        assertArrayEquals(respPayload, parsedResp.payload)
    }

    @Test
    fun testHeaderChecksumCorrectness() {
        val payload = "Test Checksum".toByteArray()
        val responsePacket = processor.buildUdpResponse(
            IPv4PacketProcessor.ParsedPacket(0x0A000002, 0x0A000001, 50000, 53, payload),
            "Resp".toByteArray()
        )

        // Verify checksum over the 20-byte IPv4 header
        var sum = 0
        var i = 0
        while (i < 20) {
            val word = ((responsePacket[i].toInt() and 0xFF) shl 8) or (responsePacket[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        // Sum of all 16-bit words including valid checksum should equal 0xFFFF
        assertEquals(0xFFFF, sum)
    }

    private fun buildIpUdpPacket(protocol: Int, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val ipHeaderLength = 20
        val udpHeaderLength = 8
        val totalLength = ipHeaderLength + udpHeaderLength + payload.size

        val response = ByteArray(totalLength)
        val buffer = ByteBuffer.wrap(response)

        // IPv4 Header
        buffer.put(0x45.toByte()) // Version 4, IHL 5
        buffer.put(0x00.toByte())
        buffer.putShort(totalLength.toShort())
        buffer.putShort(0x0000.toShort())
        buffer.putShort(0x0000.toShort())
        buffer.put(64.toByte())
        buffer.put(protocol.toByte()) // Protocol
        buffer.putShort(0)
        buffer.putInt(0x0A000002) // Src IP: 10.0.0.2
        buffer.putInt(0x0A000001) // Dest IP: 10.0.0.1

        // UDP Header
        buffer.putShort(srcPort.toShort())
        buffer.putShort(dstPort.toShort())
        buffer.putShort((udpHeaderLength + payload.size).toShort())
        buffer.putShort(0)

        // Payload
        buffer.put(payload)

        return response
    }
}