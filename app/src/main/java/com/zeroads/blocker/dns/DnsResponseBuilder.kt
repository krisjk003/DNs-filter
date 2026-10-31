package com.zeroads.blocker.dns

import java.nio.ByteBuffer

/**
 * Builds DNS responses, such as NXDOMAIN or SERVFAIL.
 */
class DnsResponseBuilder {

    companion object {
        const val RCODE_SERVFAIL: Short = 2
        const val RCODE_NXDOMAIN: Short = 3
    }

    /**
     * Builds a simple DNS response for the given query.
     *
     * @param query The original parsed query
     * @param rcode The response code (e.g., 3 for NXDOMAIN)
     */
    fun buildErrorResponse(query: DnsQuery, rcode: Short): ByteArray {
        val questionSize = query.questionLength
        val response = ByteArray(12 + questionSize)
        val buffer = ByteBuffer.wrap(response)

        buffer.putShort(query.transactionId)

        // Flags: QR=1 (Response), Opcode from query, AA=0, TC=0, RD=from query, RA=1, Z=0, RCODE=rcode
        val originalFlags = query.flags.toInt() and 0xFFFF
        val qr = 0x8000
        val opcode = originalFlags and 0x7800
        val rd = originalFlags and 0x0100
        val ra = 0x0080

        val flags = (qr or opcode or rd or ra or rcode.toInt()).toShort()
        buffer.putShort(flags)

        buffer.putShort(1) // qdcount (1 question)
        buffer.putShort(0) // ancount (0 answers)
        buffer.putShort(0) // nscount (0 authorities)
        buffer.putShort(0) // arcount (0 additional)

        // Copy the original question section
        val originalBuffer = ByteBuffer.wrap(query.rawPacket)
        originalBuffer.position(12) // Skip 12-byte header

        val questionData = ByteArray(questionSize)
        originalBuffer.get(questionData)
        buffer.put(questionData)

        return response
    }
}