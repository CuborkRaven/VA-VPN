package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.VpnConnectionState
import com.example.model.VpnTelemetry
import com.example.ui.components.formatBytesPerSec
import com.example.ui.components.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

object VpnStateHolder {
    private val _telemetry = MutableStateFlow(VpnTelemetry())
    val telemetry: StateFlow<VpnTelemetry> = _telemetry.asStateFlow()

    fun update(transform: (VpnTelemetry) -> VpnTelemetry) {
        _telemetry.update(transform)
    }
}

class ShieldVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.vpn.START"
        const val ACTION_STOP = "com.example.vpn.STOP"
        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_NODE_LABEL = "node_label"
        const val EXTRA_PROTOCOL_LABEL = "protocol_label"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_EXCLUDED_PACKAGES = "excluded_packages"

        private const val CHANNEL_ID = "shieldbox_vpn_status"
        private const val NOTIFICATION_ID = 4201
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var monitorJob: Job? = null
    private var tunReaderJob: Job? = null

    private var activeNodeLabel: String = "Германия"
    private var activeProtocolLabel: String = "Reality"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: "de-fra-1"
                activeNodeLabel = intent.getStringExtra(EXTRA_NODE_LABEL) ?: "Германия"
                activeProtocolLabel = intent.getStringExtra(EXTRA_PROTOCOL_LABEL) ?: "Reality"
                val mtu = intent.getIntExtra(EXTRA_MTU, 1400)
                val excluded = intent.getStringArrayListExtra(EXTRA_EXCLUDED_PACKAGES).orEmpty()
                startTunnel(nodeId, activeNodeLabel, activeProtocolLabel, mtu, excluded)
            }

            ACTION_STOP -> {
                stopTunnel()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startTunnel(
        nodeId: String,
        nodeLabel: String,
        protocolLabel: String,
        mtu: Int,
        excludedPackages: List<String>
    ) {
        startForegroundCompat(
            buildNotification(
                title = "ShieldBox · Подключение",
                subtitle = "$nodeLabel · $protocolLabel"
            )
        )
        VpnStateHolder.update {
            it.copy(
                connectionState = VpnConnectionState.CONNECTING,
                activeNodeId = nodeId,
                activeProtocolLabel = protocolLabel,
                errorMessage = null
            )
        }

        scope.launch {
            try {
                delay(450)
                tunInterface?.close()

                val builder = Builder()
                    .setSession("ShieldBox")
                    .setMtu(mtu)
                    .addAddress("172.19.0.1", 30)
                    .addRoute("172.19.0.0", 30)
                    .addDnsServer("1.1.1.1")

                runCatching { builder.addDisallowedApplication(packageName) }
                excludedPackages.forEach { pkg ->
                    if (pkg.isNotBlank() && pkg != packageName) {
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                }

                val pfd = builder.establish()
                if (pfd == null) {
                    VpnStateHolder.update {
                        it.copy(
                            connectionState = VpnConnectionState.DISCONNECTED,
                            tunActive = false,
                            errorMessage = "Система отклонила создание TUN-интерфейса"
                        )
                    }
                    stopSelf()
                    return@launch
                }

                tunInterface = pfd
                updateNotification(
                    title = "ShieldBox · Защищено",
                    subtitle = "$nodeLabel · $protocolLabel"
                )

                VpnStateHolder.update {
                    it.copy(
                        connectionState = VpnConnectionState.CONNECTED,
                        activeNodeId = nodeId,
                        activeProtocolLabel = protocolLabel,
                        connectedSeconds = 0L,
                        tunActive = true,
                        errorMessage = null
                    )
                }

                startTunPacketReader(pfd)
                startTelemetryLoop()
            } catch (t: Throwable) {
                VpnStateHolder.update {
                    it.copy(
                        connectionState = VpnConnectionState.DISCONNECTED,
                        tunActive = false,
                        errorMessage = t.message ?: "Ошибка запуска VPN-сервиса"
                    )
                }
                stopTunnel()
            }
        }
    }

    private fun startTunPacketReader(pfd: ParcelFileDescriptor) {
        tunReaderJob?.cancel()
        tunReaderJob = scope.launch {
            val buffer = ByteArray(32767)
            runCatching {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    while (isActive) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                    }
                }
            }
        }
    }

    private fun startTelemetryLoop() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var prevRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            var prevTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            var sessionRx = 0L
            var sessionTx = 0L
            var elapsed = 0L
            var lastPingMs = measureLatencyMs()

            while (isActive) {
                delay(1000)
                elapsed += 1

                val nowRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
                val nowTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
                val deltaRx = (nowRx - prevRx).coerceAtLeast(0L)
                val deltaTx = (nowTx - prevTx).coerceAtLeast(0L)
                prevRx = nowRx
                prevTx = nowTx

                sessionRx += deltaRx
                sessionTx += deltaTx

                if (elapsed % 5L == 0L || lastPingMs == null) {
                    lastPingMs = measureLatencyMs()
                }

                if (elapsed % 2L == 0L) {
                    updateNotification(
                        title = "ShieldBox · ${formatDuration(elapsed)} · $activeNodeLabel",
                        subtitle = "↓ ${formatBytesPerSec(deltaRx)}  ↑ ${formatBytesPerSec(deltaTx)} · $activeProtocolLabel"
                    )
                }

                VpnStateHolder.update { current ->
                    val nextDownHist = (current.downloadHistory.drop(1) + (deltaRx / 1024f))
                    val nextUpHist = (current.uploadHistory.drop(1) + (deltaTx / 1024f))
                    current.copy(
                        connectedSeconds = elapsed,
                        downloadBytesPerSec = deltaRx,
                        uploadBytesPerSec = deltaTx,
                        totalDownloadBytes = sessionRx,
                        totalUploadBytes = sessionTx,
                        livePingMs = lastPingMs,
                        downloadHistory = nextDownHist,
                        uploadHistory = nextUpHist
                    )
                }
            }
        }
    }

    private fun measureLatencyMs(): Int? {
        return runCatching {
            val start = System.nanoTime()
            val conn = (URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                requestMethod = "HEAD"
            }
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..499) {
                ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
            } else {
                null
            }
        }.getOrNull()
    }

    private fun stopTunnel() {
        monitorJob?.cancel()
        monitorJob = null
        tunReaderJob?.cancel()
        tunReaderJob = null
        runCatching { tunInterface?.close() }
        tunInterface = null

        VpnStateHolder.update {
            VpnTelemetry(
                connectionState = VpnConnectionState.DISCONNECTED,
                activeProtocolLabel = it.activeProtocolLabel,
                activeNodeId = it.activeNodeId,
                tunActive = false
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянное уведомление активного VPN-туннеля ShieldBox"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ShieldVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Отключить",
                disconnectIntent
            )
            .build()
    }

    private fun updateNotification(title: String, subtitle: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(title, subtitle))
    }
}
