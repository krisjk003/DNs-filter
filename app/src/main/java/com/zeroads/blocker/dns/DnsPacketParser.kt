package com.zeroads.blocker.dns

import java.nio.ByteBuffer

/**
 * Represents a parsed DNS query.
 */
data class DnsQuery(
    val transactionId: Short,
    val flags: Short,
    val questionCount: Short,
    val queryDomain: String,
    val queryType: Short,
    val queryClass: Short,
    val rawPacket: ByteArray,
    val questionLength: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DnsQuery

        if (transactionId != other.transactionId) return false
        if (flags != other.flags) return false
        if (questionCount != other.questionCount) return false
        if (queryDomain != other.queryDomain) return false
        if (queryType != other.queryType) return false
        if (queryClass != other.queryClass) return false
        if (!rawPacket.contentEquals(other.rawPacket)) return false
        if (questionLength != other.questionLength) return false

        return true
    }

    override fun hashCode(): Int {
        var result = transactionId.toInt()
        result = 31 * result + flags.toInt()
        result = 31 * result + questionCount.toInt()
        result = 31 * result + queryDomain.hashCode()
        result = 31 * result + queryType.toInt()
        result = 31 * result + queryClass.toInt()
        result = 31 * result + rawPacket.contentHashCode()
        result = 31 * result + questionLength
        return result
    }
}

/**
 * Parses raw UDP DNS packets.
 */
class DnsPacketParser {

    /**
     * Parses a DNS query packet.
     * Returns null if the packet is malformed, is a response, or has invalid fields.
     */
    fun parse(packet: ByteArray): DnsQuery? {
        if (packet.size < 17) return null // 12-byte header + min 5-byte question section

        try {
            val buffer = ByteBuffer.wrap(packet)
            val id = buffer.short
            val flags = buffer.short
            val qdcount = buffer.short
            buffer.short // ancount
            buffer.short // nscount
            buffer.short // arcount

            val flagsInt = flags.toInt() and 0xFFFF
            val isResponse = (flagsInt and 0x8000) != 0
            if (isResponse) {
                // Reject response packets presented as queries
                return null
            }

            // Require exactly 1 question for standard queries
            if (qdcount.toInt() != 1) return null

            val questionStart = buffer.position()
            val queryDomain = readDomain(buffer, packet.size) ?: return null

            // Ensure buffer has at least 4 bytes remaining for QTYPE and QCLASS
            if (buffer.remaining() < 4) return null

            val qtype = buffer.short
            val qclass = buffer.short
            val questionLength = buffer.position() - questionStart

            return DnsQuery(
                transactionId = id,
                flags = flags,
                questionCount = qdcount,
                queryDomain = queryDomain.lowercase(),
                queryType = qtype,
                queryClass = qclass,
                rawPacket = packet,
                questionLength = questionLength
            )
        } catch (e: Exception) {
            // Malformed packet
            return null
        }
    }

    private fun readDomain(buffer: ByteBuffer, packetSize: Int): String? {
        val domainBuilder = StringBuilder()
        var jumps = 0
        var pos = buffer.position()
        val visitedOffsets = HashSet<Int>()
        var totalDomainLength = 0

        while (true) {
            if (pos < 0 || pos >= packetSize) return null

            val len = buffer.get(pos).toInt() and 0xFF

            if (len == 0) {
                pos++
                if (jumps == 0) {
                    buffer.position(pos)
                }
                break
            }

            // Pointer detection (top 2 bits set: 0xC0)
            if ((len and 0xC0) == 0xC0) {
                if (pos + 1 >= packetSize) return null

                val offset = ((len and 0x3F) shl 8) or (buffer.get(pos + 1).toInt() and 0xFF)

                // Compression pointer validation
                if (offset < 12 || offset >= packetSize || !visitedOffsets.add(offset)) {
                    // Loop detected or out-of-bounds pointer or pointing to header
                    return null
                }

                if (jumps == 0) {
                    buffer.position(pos + 2)
                }

                pos = offset
                jumps++
                if (jumps > 10) return null // Max pointer jumps threshold
                continue
            }

            // Reserved label bits check (bits 0x80 or 0x40 without being 0xC0)
            if ((len and 0xC0) != 0) {
                return null
            }

            // Label length validation (max 63)
            if (len > 63) return null

            pos++
            if (pos + len > packetSize) return null

            if (domainBuilder.isNotEmpty()) {
                domainBuilder.append('.')
                totalDomainLength++
            }

            totalDomainLength += len
            if (totalDomainLength > 253) return null // Max domain name length is 253 chars (255 bytes on wire)

            val labelBytes = ByteArray(len)
            for (i in 0 until len) {
                labelBytes[i] = buffer.get(pos + i)
            }

            domainBuilder.append(String(labelBytes, Charsets.US_ASCII))
            pos += len

            if (jumps == 0) {
                buffer.position(pos)
            }
        }

        val domain = domainBuilder.toString()
        if (domain.isEmpty()) return null
        return domain
    }
}