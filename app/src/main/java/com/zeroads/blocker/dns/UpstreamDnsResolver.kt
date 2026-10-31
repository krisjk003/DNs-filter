package com.zeroads.blocker.dns

import android.net.VpnService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

interface SocketProtector {
    fun protect(socket: DatagramSocket): Boolean
    fun protect(socket: Socket): Boolean
}

class VpnSocketProtector(private val vpnService: VpnService) : SocketProtector {
    override fun protect(socket: DatagramSocket): Boolean {
        return vpnService.protect(socket)
    }

    override fun protect(socket: Socket): Boolean {
        return vpnService.protect(socket)
    }
}

/**
 * Forwards allowed DNS queries to an upstream resolver (e.g., 1.1.1.1).
 */
class UpstreamDnsResolver(
    private val protector: SocketProtector,
    private val upstreamAddress: String = "1.1.1.1",
    private val upstreamPort: Int = 53,
    private val timeoutMs: Int = 3000
) {
    /**
     * Resolves the query over UDP (with TCP fallback if truncated).
     * Returns null if resolution fails or response validation fails.
     */
    fun resolve(queryData: ByteArray): ByteArray? {
        if (queryData.size < 12) return null

        val queryId = ((queryData[0].toInt() and 0xFF) shl 8) or (queryData[1].toInt() and 0xFF)

        var udpSocket: DatagramSocket? = null
        try {
            udpSocket = DatagramSocket()
            if (!protector.protect(udpSocket)) {
                return null
            }
            udpSocket.soTimeout = timeoutMs

            val address = InetAddress.getByName(upstreamAddress)
            val sendPacket = DatagramPacket(queryData, queryData.size, address, upstreamPort)
            udpSocket.send(sendPacket)

            val receiveBuffer = ByteArray(4096) // Up to 4096 bytes EDNS0 response
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            udpSocket.receive(receivePacket)

            // Validate source address and port
            if (receivePacket.address?.hostAddress != upstreamAddress || receivePacket.port != upstreamPort) {
                return null
            }

            if (receivePacket.length < 12) return null

            val response = ByteArray(receivePacket.length)
            System.arraycopy(receivePacket.data, receivePacket.offset, response, 0, receivePacket.length)

            val respId = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
            val flags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)

            // Validate transaction ID and QR flag
            val isQrSet = (flags and 0x8000) != 0
            if (respId != queryId || !isQrSet) {
                return null
            }

            // Check TC (Truncated) flag
            val isTruncated = (flags and 0x0200) != 0
            if (isTruncated) {
                // Fallback to TCP
                return resolveTcp(queryData, queryId) // Return null if TCP fallback fails
            }

            return response
        } catch (e: SocketTimeoutException) {
            return null
        } catch (e: Exception) {
            return null
        } finally {
            udpSocket?.close()
        }
    }

    private fun resolveTcp(queryData: ByteArray, expectedQueryId: Int): ByteArray? {
        var tcpSocket: Socket? = null
        try {
            tcpSocket = Socket()
            if (!protector.protect(tcpSocket)) {
                return null
            }
            tcpSocket.soTimeout = timeoutMs
            tcpSocket.connect(InetSocketAddress(upstreamAddress, upstreamPort), timeoutMs)

            val outputStream = DataOutputStream(tcpSocket.getOutputStream())
            outputStream.writeShort(queryData.size)
            outputStream.write(queryData)
            outputStream.flush()

            val inputStream = DataInputStream(tcpSocket.getInputStream())
            val responseLength = inputStream.readUnsignedShort()
            if (responseLength < 12 || responseLength > 4096) return null

            val responseData = ByteArray(responseLength)
            inputStream.readFully(responseData)

            val respId = ((responseData[0].toInt() and 0xFF) shl 8) or (responseData[1].toInt() and 0xFF)
            val flags = ((responseData[2].toInt() and 0xFF) shl 8) or (responseData[3].toInt() and 0xFF)
            val isQrSet = (flags and 0x8000) != 0

            if (respId == expectedQueryId && isQrSet) {
                return responseData
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            try {
                tcpSocket?.close()
            } catch (_: Exception) {}
        }
    }
}