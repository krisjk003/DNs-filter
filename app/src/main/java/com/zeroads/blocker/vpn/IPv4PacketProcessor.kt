package com.zeroads.blocker.vpn

import java.nio.ByteBuffer

/**
 * Handles raw IPv4/UDP packets.
 */
class IPv4PacketProcessor {

    data class ParsedPacket(
        val sourceIp: Int,
        val destIp: Int,
        val sourcePort: Int,
        val destPort: Int,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ParsedPacket

            if (sourceIp != other.sourceIp) return false
            if (destIp != other.destIp) return false
            if (sourcePort != other.sourcePort) return false
            if (destPort != other.destPort) return false
            if (!payload.contentEquals(other.payload)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = sourceIp
            result = 31 * result + destIp
            result = 31 * result + sourcePort
            result = 31 * result + destPort
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    fun parseUdpPacket(packet: ByteArray, length: Int): ParsedPacket? {
        if (length < 28) return null // Min IP (20) + UDP (8)

        val buffer = ByteBuffer.wrap(packet, 0, length)

        val versionAndIhl = buffer.get().toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return null

        val ihl = versionAndIhl and 0x0F
        val ipHeaderLength = ihl * 4
        if (ipHeaderLength < 20 || ipHeaderLength > length) return null

        // Total IP length field at bytes 2-3
        val totalIpLength = buffer.getShort(2).toInt() and 0xFFFF
        if (totalIpLength < ipHeaderLength + 8 || totalIpLength > length) return null

        // Fragment Offset & Flags at bytes 6-7
        val flagsAndOffset = buffer.getShort(6).toInt() and 0xFFFF
        val isFragmented = (flagsAndOffset and 0x3FFF) != 0
        if (isFragmented) {
            // Reject fragmented IPv4 packets
            return null
        }

        val protocol = buffer.get(9).toInt() and 0xFF
        if (protocol != 17) return null // 17 is UDP

        buffer.position(12)
        val sourceIp = buffer.int
        val destIp = buffer.int

        buffer.position(ipHeaderLength)
        if (length - ipHeaderLength < 8) return null

        val sourcePort = buffer.short.toInt() and 0xFFFF
        val destPort = buffer.short.toInt() and 0xFFFF
        val udpLength = buffer.short.toInt() and 0xFFFF

        if (udpLength < 8 || ipHeaderLength + udpLength > totalIpLength) return null

        val payloadLength = udpLength - 8
        val payload = ByteArray(payloadLength)

        buffer.position(ipHeaderLength + 8)
        buffer.get(payload)

        return ParsedPacket(sourceIp, destIp, sourcePort, destPort, payload)
    }

    fun buildUdpResponse(originalPacket: ParsedPacket, responsePayload: ByteArray): ByteArray {
        val ipHeaderLength = 20
        val udpHeaderLength = 8
        val totalLength = ipHeaderLength + udpHeaderLength + responsePayload.size

        val response = ByteArray(totalLength)
        val buffer = ByteBuffer.wrap(response)

        // IPv4 Header
        buffer.put(0x45.toByte()) // Version 4, IHL 5
        buffer.put(0x00.toByte()) // TOS
        buffer.putShort(totalLength.toShort()) // Total length
        buffer.putShort(0x0000.toShort()) // ID
        buffer.putShort(0x0000.toShort()) // Flags & Fragment Offset
        buffer.put(64.toByte()) // TTL
        buffer.put(17.toByte()) // Protocol (UDP)
        buffer.putShort(0) // Checksum (calculated later)
        buffer.putInt(originalPacket.destIp) // Swap source and dest
        buffer.putInt(originalPacket.sourceIp)

        // Calculate IP checksum over IPv4 header
        buffer.putShort(10, calculateIpChecksum(response, ipHeaderLength))

        // UDP Header
        buffer.position(ipHeaderLength)
        buffer.putShort(originalPacket.destPort.toShort()) // Swap ports
        buffer.putShort(originalPacket.sourcePort.toShort())
        val udpLength = udpHeaderLength + responsePayload.size
        buffer.putShort(udpLength.toShort())
        buffer.putShort(0) // UDP Checksum (0 is allowed in IPv4)

        // Payload
        buffer.put(responsePayload)

        return response
    }

    fun calculateIpChecksum(data: ByteArray, headerLength: Int): Short {
        var sum = 0
        var i = 0
        while (i < headerLength) {
            if (i == 10) { // Skip checksum field itself
                i += 2
                continue
            }
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }

        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return (sum.inv()).toShort()
    }
}