package com.zeroads.blocker.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class UpstreamDnsResolverTest {

    private val dummyProtector = object : SocketProtector {
        override fun protect(socket: DatagramSocket): Boolean = true
        override fun protect(socket: Socket): Boolean = true
    }

    @Test
    fun testResolverRejectsShortQuery() {
        val resolver = UpstreamDnsResolver(dummyProtector)
        val result = resolver.resolve(ByteArray(5)) // Less than 12 bytes
        assertNull(result)
    }

    @Test
    fun testFailedSocketProtection() {
        val failingProtector = object : SocketProtector {
            override fun protect(socket: DatagramSocket): Boolean = false
            override fun protect(socket: Socket): Boolean = false
        }
        val resolver = UpstreamDnsResolver(failingProtector)
        val query = ByteArray(12) { 0 }
        val result = resolver.resolve(query)
        assertNull(result)
    }

    @Test
    fun testValidUdpResponse() {
        val serverSocket = DatagramSocket(0)
        val port = serverSocket.localPort

        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            serverSocket.receive(packet)

            val queryId = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)

            // Send back valid response
            val response = ByteArray(12)
            response[0] = (queryId shr 8).toByte()
            response[1] = (queryId and 0xFF).toByte()
            response[2] = 0x81.toByte() // QR=1
            response[3] = 0x80.toByte()

            val sendPacket = DatagramPacket(response, response.size, packet.address, packet.port)
            serverSocket.send(sendPacket)
            serverSocket.close()
        }

        val resolver = UpstreamDnsResolver(dummyProtector, "127.0.0.1", port, 1000)
        val query = ByteArray(12) { 0 }
        query[0] = 0x12
        query[1] = 0x34

        val result = resolver.resolve(query)
        assertNotNull(result)
        assertEquals(0x1234, ((result!![0].toInt() and 0xFF) shl 8) or (result[1].toInt() and 0xFF))

        executor.shutdown()
    }

    @Test
    fun testIncorrectTransactionIdRejected() {
        val serverSocket = DatagramSocket(0)
        val port = serverSocket.localPort

        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            serverSocket.receive(packet)

            // Send back invalid ID
            val response = ByteArray(12)
            response[0] = 0x99.toByte()
            response[1] = 0x99.toByte()
            response[2] = 0x81.toByte() // QR=1
            response[3] = 0x80.toByte()

            val sendPacket = DatagramPacket(response, response.size, packet.address, packet.port)
            serverSocket.send(sendPacket)
            serverSocket.close()
        }

        val resolver = UpstreamDnsResolver(dummyProtector, "127.0.0.1", port, 1000)
        val query = ByteArray(12) { 0 }
        query[0] = 0x12
        query[1] = 0x34

        val result = resolver.resolve(query)
        assertNull(result) // Should reject due to ID mismatch

        executor.shutdown()
    }

    @Test
    fun testInvalidFlagsRejected() {
        val serverSocket = DatagramSocket(0)
        val port = serverSocket.localPort

        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            serverSocket.receive(packet)

            val queryId = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)

            // Send back without QR flag (0x0100 instead of 0x8100)
            val response = ByteArray(12)
            response[0] = (queryId shr 8).toByte()
            response[1] = (queryId and 0xFF).toByte()
            response[2] = 0x01.toByte() // QR=0
            response[3] = 0x00.toByte()

            val sendPacket = DatagramPacket(response, response.size, packet.address, packet.port)
            serverSocket.send(sendPacket)
            serverSocket.close()
        }

        val resolver = UpstreamDnsResolver(dummyProtector, "127.0.0.1", port, 1000)
        val query = ByteArray(12) { 0 }
        query[0] = 0x12
        query[1] = 0x34

        val result = resolver.resolve(query)
        assertNull(result) // Should reject due to QR=0

        executor.shutdown()
    }

    @Test
    fun testTcpFallbackSuccess() {
        val udpSocket = DatagramSocket(0)
        val port = udpSocket.localPort

        val tcpServerSocket = ServerSocket(port)

        val executor = Executors.newFixedThreadPool(2)

        // UDP Server (Sends Truncated)
        executor.execute {
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            udpSocket.receive(packet)

            val queryId = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
            val response = ByteArray(12)
            response[0] = (queryId shr 8).toByte()
            response[1] = (queryId and 0xFF).toByte()
            response[2] = 0x83.toByte() // QR=1, TC=1 (0x02)
            response[3] = 0x80.toByte()

            val sendPacket = DatagramPacket(response, response.size, packet.address, packet.port)
            udpSocket.send(sendPacket)
            udpSocket.close()
        }

        // TCP Server
        executor.execute {
            val clientSocket = tcpServerSocket.accept()
            val input = DataInputStream(clientSocket.getInputStream())
            val output = DataOutputStream(clientSocket.getOutputStream())

            val len = input.readUnsignedShort()
            val queryBytes = ByteArray(len)
            input.readFully(queryBytes)

            val queryId = ((queryBytes[0].toInt() and 0xFF) shl 8) or (queryBytes[1].toInt() and 0xFF)

            val response = ByteArray(12)
            response[0] = (queryId shr 8).toByte()
            response[1] = (queryId and 0xFF).toByte()
            response[2] = 0x81.toByte() // QR=1, TC=0
            response[3] = 0x80.toByte()

            output.writeShort(response.size)
            output.write(response)
            output.flush()

            clientSocket.close()
            tcpServerSocket.close()
        }

        val resolver = UpstreamDnsResolver(dummyProtector, "127.0.0.1", port, 2000)
        val query = ByteArray(12) { 0 }
        query[0] = 0x12
        query[1] = 0x34

        val result = resolver.resolve(query)
        assertNotNull(result)

        // Ensure the returned response doesn't have the TC flag anymore (since it's from TCP)
        val flags = ((result!![2].toInt() and 0xFF) shl 8) or (result[3].toInt() and 0xFF)
        assertEquals(0, flags and 0x0200)

        executor.shutdown()
    }

    @Test
    fun testTcpFallbackFailure() {
        val udpSocket = DatagramSocket(0)
        val port = udpSocket.localPort

        val tcpServerSocket = ServerSocket(port)

        val executor = Executors.newFixedThreadPool(2)

        // UDP Server (Sends Truncated)
        executor.execute {
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            udpSocket.receive(packet)

            val queryId = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
            val response = ByteArray(12)
            response[0] = (queryId shr 8).toByte()
            response[1] = (queryId and 0xFF).toByte()
            response[2] = 0x83.toByte() // QR=1, TC=1
            response[3] = 0x80.toByte()

            val sendPacket = DatagramPacket(response, response.size, packet.address, packet.port)
            udpSocket.send(sendPacket)
            udpSocket.close()
        }

        // TCP Server (Simulate abrupt failure)
        executor.execute {
            val clientSocket = tcpServerSocket.accept()
            clientSocket.close() // Close immediately without responding
            tcpServerSocket.close()
        }

        val resolver = UpstreamDnsResolver(dummyProtector, "127.0.0.1", port, 2000)
        val query = ByteArray(12) { 0 }
        query[0] = 0x12
        query[1] = 0x34

        val result = resolver.resolve(query)
        assertNull(result) // If TCP fails, we expect null, NOT the truncated UDP packet

        executor.shutdown()
    }
}