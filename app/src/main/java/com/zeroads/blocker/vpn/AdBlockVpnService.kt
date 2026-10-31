package com.zeroads.blocker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.zeroads.blocker.dns.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AdBlockVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)

    private val queue = ArrayBlockingQueue<Runnable>(100)
    private val dnsExecutor = ThreadPoolExecutor(
        2, 8, 60L, TimeUnit.SECONDS, queue, ThreadPoolExecutor.AbortPolicy()
    )

    private var readerThread: Thread? = null
    private val outputStreamLock = Any()

    private lateinit var blocklistManager: BlocklistManager
    private lateinit var domainFilter: DomainFilter
    private lateinit var packetProcessor: IPv4PacketProcessor
    private lateinit var dnsPacketParser: DnsPacketParser
    private lateinit var dnsResponseBuilder: DnsResponseBuilder
    private lateinit var upstreamResolver: UpstreamDnsResolver

    private lateinit var packetHandler: VpnPacketHandler

    override fun onCreate() {
        super.onCreate()
        domainFilter = DomainFilter()
        blocklistManager = BlocklistManager(this, domainFilter)
        packetProcessor = IPv4PacketProcessor()
        dnsPacketParser = DnsPacketParser()
        dnsResponseBuilder = DnsResponseBuilder()
        upstreamResolver = UpstreamDnsResolver(VpnSocketProtector(this))

        packetHandler = VpnPacketHandler(
            isRunning,
            packetProcessor,
            dnsPacketParser,
            dnsResponseBuilder,
            domainFilter,
            upstreamResolver,
            stats,
            outputStreamLock
        )

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            startVpn()
        }

        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning.getAndSet(true)) return

        val loadResult = blocklistManager.loadFromAssets("blocklist.txt")
        if (loadResult.isFailure) {
            val errMsg = loadResult.exceptionOrNull()?.message ?: "Failed to load blocklist"
            stats.update { it.copy(lastError = errMsg, isVpnConnected = false) }
            isRunning.set(false)
            stopSelf()
            return
        }

        try {
            val builder = Builder()
                .setSession("ZeroAds")
                .addAddress("10.111.0.2", 32)
                .addDnsServer("10.111.0.1")
                .addRoute("10.111.0.1", 32) // DNS-only routing

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                stats.update { it.copy(lastError = "Failed to establish VPN interface", isVpnConnected = false) }
                isRunning.set(false)
                stopSelf()
                return
            }

            val notification = createNotification()
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, 0)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            stats.update { it.copy(isVpnConnected = true, lastError = null) }

            startReaderThread()
        } catch (e: Exception) {
            e.printStackTrace()
            stats.update { it.copy(lastError = e.message ?: "VPN startup error", isVpnConnected = false) }
            stopVpn()
        }
    }

    private fun startReaderThread() {
        readerThread = Thread({
            processPackets()
        }, "ZeroAds-TUN-Reader")
        readerThread?.start()
    }

    private fun processPackets() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(fd)
        val outputStream = FileOutputStream(fd)
        val packet = ByteArray(32767)

        try {
            while (isRunning.get()) {
                val length = inputStream.read(packet)
                if (length > 0) {
                    val packetCopy = packet.copyOf(length)
                    try {
                        dnsExecutor.execute {
                            packetHandler.handlePacket(packetCopy, length, outputStream)
                        }
                    } catch (e: RejectedExecutionException) {
                        packetHandler.handleRejectedPacket(packetCopy, length, outputStream)
                    }
                }
            }
        } catch (e: IOException) {
            if (isRunning.get()) {
                e.printStackTrace()
            }
        } finally {
            stopVpn()
        }
    }

    private fun stopVpn() {
        if (!isRunning.getAndSet(false)) return

        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        vpnInterface = null

        stats.update { it.copy(isVpnConnected = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }

    override fun onDestroy() {
        stopVpn()
        dnsExecutor.shutdownNow()
        readerThread?.interrupt()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ZeroAds VPN Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, AdBlockVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ZeroAds Active")
            .setContentText("DNS filtering is enabled.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.zeroads.blocker.START_VPN"
        const val ACTION_STOP = "com.zeroads.blocker.STOP_VPN"
        private const val CHANNEL_ID = "ZeroAdsVpnChannel"
        private const val NOTIFICATION_ID = 1

        val stats = MutableStateFlow(VpnStats())
    }
}

data class VpnStats(
    val isVpnConnected: Boolean = false,
    val totalRequests: Long = 0,
    val blockedRequests: Long = 0,
    val allowedRequests: Long = 0,
    val failedUpstreamRequests: Long = 0,
    val overloadedRequests: Long = 0,
    val lastError: String? = null
)