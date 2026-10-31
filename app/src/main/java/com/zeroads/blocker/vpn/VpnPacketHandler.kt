package com.zeroads.blocker.vpn

import com.zeroads.blocker.dns.DnsPacketParser
import com.zeroads.blocker.dns.DnsResponseBuilder
import com.zeroads.blocker.dns.DomainFilter
import com.zeroads.blocker.dns.UpstreamDnsResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class VpnPacketHandler(
    private val isRunning: AtomicBoolean,
    private val packetProcessor: IPv4PacketProcessor,
    private val dnsPacketParser: DnsPacketParser,
    private val dnsResponseBuilder: DnsResponseBuilder,
    private val domainFilter: DomainFilter,
    private val upstreamResolver: UpstreamDnsResolver,
    private val stats: MutableStateFlow<VpnStats>,
    private val outputStreamLock: Any
) {

    fun handlePacket(packet: ByteArray, length: Int, outputStream: OutputStream) {
        if (!isRunning.get()) return

        val parsedIp = packetProcessor.parseUdpPacket(packet, length) ?: return
        // 10.111.0.1 = 0x0A6F0001
        if (parsedIp.destIp != 0x0A6F0001 || parsedIp.destPort != 53) return

        val dnsQuery = dnsPacketParser.parse(parsedIp.payload) ?: return

        stats.update { it.copy(totalRequests = it.totalRequests + 1) }

        val responsePayload = if (domainFilter.isBlocked(dnsQuery.queryDomain)) {
            stats.update { it.copy(blockedRequests = it.blockedRequests + 1) }
            dnsResponseBuilder.buildErrorResponse(dnsQuery, DnsResponseBuilder.RCODE_NXDOMAIN)
        } else {
            val upstreamResponse = upstreamResolver.resolve(dnsQuery.rawPacket)
            if (upstreamResponse != null) {
                stats.update { it.copy(allowedRequests = it.allowedRequests + 1) }
                upstreamResponse
            } else {
                stats.update { it.copy(failedUpstreamRequests = it.failedUpstreamRequests + 1) }
                dnsResponseBuilder.buildErrorResponse(dnsQuery, DnsResponseBuilder.RCODE_SERVFAIL)
            }
        }

        val responsePacket = packetProcessor.buildUdpResponse(parsedIp, responsePayload)

        synchronized(outputStreamLock) {
            try {
                if (isRunning.get()) {
                    outputStream.write(responsePacket)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun handleRejectedPacket(packet: ByteArray, length: Int, outputStream: OutputStream) {
        if (!isRunning.get()) return

        val parsedIp = packetProcessor.parseUdpPacket(packet, length) ?: return
        if (parsedIp.destIp != 0x0A6F0001 || parsedIp.destPort != 53) return

        val dnsQuery = dnsPacketParser.parse(parsedIp.payload) ?: return

        stats.update {
            it.copy(
                totalRequests = it.totalRequests + 1,
                overloadedRequests = it.overloadedRequests + 1
            )
        }

        val responsePayload = dnsResponseBuilder.buildErrorResponse(dnsQuery, DnsResponseBuilder.RCODE_SERVFAIL)
        val responsePacket = packetProcessor.buildUdpResponse(parsedIp, responsePayload)

        synchronized(outputStreamLock) {
            try {
                if (isRunning.get()) {
                    outputStream.write(responsePacket)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}