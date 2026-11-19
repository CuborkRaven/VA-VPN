package com.shieldbox.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface

class CoreVpnService : VpnService(), PlatformInterface, CommandServerHandler, CommandClientHandler {

    companion object {
        private const val TAG = "CoreVpnService"

        const val ACTION_START = "com.shieldbox.vpn.START"
        const val ACTION_STOP = "com.shieldbox.vpn.STOP"
        const val ACTION_STATUS_UPDATE = "com.shieldbox.vpn.STATUS_UPDATE"

        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_STATUS = "status"
        const val EXTRA_UPLINK_SPEED = "uplink_speed"
        const val EXTRA_DOWNLINK_SPEED = "downlink_speed"
        const val EXTRA_UPLINK_TOTAL = "uplink_total"
        const val EXTRA_DOWNLINK_TOTAL = "downlink_total"
        const val EXTRA_ERROR = "error"

        private const val NOTIFICATION_CHANNEL_ID = "shieldbox_vpn_channel"
        private const val NOTIFICATION_ID = 101

        @Volatile
        private var currentStatus: String = "DISCONNECTED"

        @Volatile
        private var currentUplinkSpeed: Long = 0L

        @Volatile
        private var currentDownlinkSpeed: Long = 0L

        @Volatile
        private var currentUplinkTotal: Long = 0L

        @Volatile
        private var currentDownlinkTotal: Long = 0L

        fun currentStatePayload(): Map<String, Any?> = mapOf(
            "status" to currentStatus,
            "uplinkSpeed" to currentUplinkSpeed,
            "downlinkSpeed" to currentDownlinkSpeed,
            "uplinkTotal" to currentUplinkTotal,
            "downlinkTotal" to currentDownlinkTotal,
            "error" to null
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var boxService: BoxService? = null
    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var tunPfd: ParcelFileDescriptor? = null

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        val workDir = File(filesDir, "sing-box").apply { mkdirs() }
        val tempDir = File(cacheDir, "sing-box-tmp").apply { mkdirs() }
        Libbox.setup(
            SetupOptions().apply {
                basePath = filesDir.absolutePath
                workingPath = workDir.absolutePath
                tempPath = tempDir.absolutePath
                fixAndroidStack = true
            }
        )
        runCatching {
            Libbox.redirectStderr(File(workDir, "stderr.log").absolutePath)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
                if (configJson.isNullOrBlank()) {
                    broadcastState("DISCONNECTED", error = "Missing VPN configuration")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundCompat()
                startCore(configJson)
            }

            ACTION_STOP -> {
                stopCore()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopCore()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopCore()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Подключение к защищённому туннелю...")
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

    private fun startCore(configJson: String) {
        broadcastState("CONNECTING")
        scope.launch {
            try {
                stopInternal()

                // Validate config via libbox before starting TUN
                Libbox.checkConfig(configJson)

                val server = Libbox.newCommandServer(this@CoreVpnService, 300)
                server.start()
                commandServer = server

                // Create BoxService and invoke openTun(options) inside service.start()
                val service = Libbox.newService(configJson, this@CoreVpnService)
                service.start()
                boxService = service
                server.setService(service)

                val clientOptions = CommandClientOptions().apply {
                    command = Libbox.CommandStatus
                    statusInterval = 1_000_000_000L // 1 second
                }
                val client = Libbox.newCommandClient(this@CoreVpnService, clientOptions)
                client.connect()
                commandClient = client

                updateNotification("VPN активен")
                broadcastState("CONNECTED")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start libbox service", t)
                broadcastState("DISCONNECTED", error = t.message ?: "Failed to start sing-box")
                stopCore()
            }
        }
    }

    private fun stopCore() {
        scope.launch {
            stopInternal()
            broadcastState("DISCONNECTED")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopInternal() {
        runCatching { commandClient?.disconnect() }
        commandClient = null

        runCatching { boxService?.close() }
        boxService = null

        runCatching { commandServer?.close() }
        commandServer = null

        runCatching { tunPfd?.close() }
        tunPfd = null

        currentUplinkSpeed = 0L
        currentDownlinkSpeed = 0L
        currentUplinkTotal = 0L
        currentDownlinkTotal = 0L
    }

    // --- libbox PlatformInterface implementation ---

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) {
            Log.w(TAG, "VpnService.protect($fd) returned false")
        }
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) {
            throw SecurityException("VPN permission not granted")
        }

        val builder = Builder()
            .setSession("ShieldBox")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Assign IPv4 TUN addresses from sing-box config
        val inet4Addr = options.inet4Address
        while (inet4Addr.hasNext()) {
            val prefix = inet4Addr.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        // Assign IPv6 TUN addresses from sing-box config (prevents IPv6 leaks)
        val inet6Addr = options.inet6Address
        while (inet6Addr.hasNext()) {
            val prefix = inet6Addr.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        if (options.autoRoute) {
            val dnsServer = options.dnsServerAddress.value
            if (!dnsServer.isNullOrBlank()) {
                builder.addDnsServer(dnsServer)
            } else {
                builder.addDnsServer("172.19.0.2")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val route4 = options.inet4RouteAddress
                if (route4.hasNext()) {
                    while (route4.hasNext()) {
                        val p = route4.next()
                        builder.addRoute(p.address(), p.prefix())
                    }
                } else {
                    builder.addRoute("0.0.0.0", 0)
                }

                val exclude4 = options.inet4RouteExcludeAddress
                while (exclude4.hasNext()) {
                    val p = exclude4.next()
                    runCatching {
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(p.address()), p.prefix()))
                    }
                }

                val route6 = options.inet6RouteAddress
                if (route6.hasNext()) {
                    while (route6.hasNext()) {
                        val p = route6.next()
                        builder.addRoute(p.address(), p.prefix())
                    }
                } else {
                    builder.addRoute("::", 0)
                }

                val exclude6 = options.inet6RouteExcludeAddress
                while (exclude6.hasNext()) {
                    val p = exclude6.next()
                    runCatching {
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(p.address()), p.prefix()))
                    }
                }
            } else {
                val route4 = options.inet4RouteAddress
                if (route4.hasNext()) {
                    while (route4.hasNext()) {
                        val p = route4.next()
                        builder.addRoute(p.address(), p.prefix())
                    }
                } else {
                    builder.addRoute("0.0.0.0", 0)
                }

                val route6 = options.inet6RouteAddress
                if (route6.hasNext()) {
                    while (route6.hasNext()) {
                        val p = route6.next()
                        builder.addRoute(p.address(), p.prefix())
                    }
                } else {
                    builder.addRoute("::", 0)
                }
            }

            val includePackages = options.includePackage
            while (includePackages.hasNext()) {
                runCatching { builder.addAllowedApplication(includePackages.next()) }
            }

            val excludePackages = options.excludePackage
            while (excludePackages.hasNext()) {
                runCatching { builder.addDisallowedApplication(excludePackages.next()) }
            }

            // Exclude our own app so libbox outbound sockets never loop back into TUN
            runCatching { builder.addDisallowedApplication(packageName) }
        }

        val pfd = builder.establish()
            ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
        tunPfd = pfd
        // Pass live TUN file descriptor directly to libbox
        return pfd.fd
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return runCatching {
            connectivityManager.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(InetAddress.getByName(sourceAddress), sourcePort),
                InetSocketAddress(InetAddress.getByName(destinationAddress), destinationPort)
            )
        }.getOrDefault(-1)
    }

    override fun packageNameByUid(uid: Int): String {
        val packages = packageManager.getPackagesForUid(uid)
        return packages?.firstOrNull().orEmpty()
    }

    override fun uidByPackageName(packageName: String): Int {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageUid(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageUid(packageName, 0)
            }
        }.getOrDefault(-1)
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                notifyInterfaceUpdate(network, listener)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                notifyInterfaceUpdate(network, listener)
            }

            override fun onLost(network: Network) {
                listener.updateDefaultInterface("", -1, false, false)
            }
        }
        networkCallback = callback
        connectivityManager.registerNetworkCallback(request, callback)
    }

    private fun notifyInterfaceUpdate(network: Network, listener: InterfaceUpdateListener) {
        val lp = connectivityManager.getLinkProperties(network) ?: return
        val ifaceName = lp.interfaceName ?: return
        val ifaceIndex = runCatching { JavaNetworkInterface.getByName(ifaceName)?.index ?: -1 }.getOrDefault(-1)
        val caps = connectivityManager.getNetworkCapabilities(network)
        val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        val isConstrained = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) == false
        listener.updateDefaultInterface(ifaceName, ifaceIndex, isExpensive, isConstrained)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        val javaIfaces = JavaNetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (iface in javaIfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val libIface = io.nekohasekai.libbox.NetworkInterface().apply {
                index = iface.index
                mtu = iface.mtu
                name = iface.name
                val addrs = iface.interfaceAddresses.map { ia ->
                    "${ia.address.hostAddress?.substringBefore('%')}/${ia.networkPrefixLength}"
                }
                addresses = object : StringIterator {
                    private var idx = 0
                    override fun hasNext(): Boolean = idx < addrs.size
                    override fun next(): String = addrs[idx++]
                    override fun len(): Int = addrs.size
                }
            }
            interfaces.add(libIface)
        }
        return object : NetworkInterfaceIterator {
            private var idx = 0
            override fun hasNext(): Boolean = idx < interfaces.size
            override fun next(): io.nekohasekai.libbox.NetworkInterface = interfaces[idx++]
        }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {}

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun sendNotification(notification: LibboxNotification) {
        updateNotification("${notification.title}: ${notification.body}")
    }

    // --- CommandServerHandler / CommandClientHandler ---

    override fun serviceReload() {}

    override fun postServiceClose() {
        stopCore()
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? = null

    override fun setSystemProxyEnabled(isEnabled: Boolean) {}

    override fun connected() {}

    override fun disconnected(message: String?) {}

    override fun clearLogs() {}

    override fun writeLogs(messageList: StringIterator?) {}

    private var lastNotificationUpdateMs: Long = 0L

    override fun writeStatus(message: StatusMessage?) {
        if (message == null) return
        currentUplinkSpeed = message.uplink
        currentDownlinkSpeed = message.downlink
        currentUplinkTotal = message.uplinkTotal
        currentDownlinkTotal = message.downlinkTotal
        broadcastState(currentStatus)

        val nowMs = System.currentTimeMillis()
        if (currentStatus == "CONNECTED" && nowMs - lastNotificationUpdateMs >= 2000L) {
            lastNotificationUpdateMs = nowMs
            updateNotification(
                "↓ ${formatSpeed(currentDownlinkSpeed)}  ↑ ${formatSpeed(currentUplinkSpeed)}"
            )
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 KB/s"
        val kb = bytesPerSec / 1024.0
        return if (kb < 1024.0) String.format("%.0f KB/s", kb)
        else String.format("%.2f MB/s", kb / 1024.0)
    }

    override fun writeGroups(message: OutboundGroupIterator?) {}

    override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}

    override fun updateClashMode(newMode: String?) {}

    override fun writeConnections(message: Connections?) {}

    // --- Notifications & Event Broadcasting ---

    private fun broadcastState(status: String, error: String? = null) {
        currentStatus = status
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_UPLINK_SPEED, currentUplinkSpeed)
            putExtra(EXTRA_DOWNLINK_SPEED, currentDownlinkSpeed)
            putExtra(EXTRA_UPLINK_TOTAL, currentUplinkTotal)
            putExtra(EXTRA_DOWNLINK_TOTAL, currentDownlinkTotal)
            if (error != null) putExtra(EXTRA_ERROR, error)
        }
        sendBroadcast(intent)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "ShieldBox VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянное уведомление активного VPN-туннеля ShieldBox"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CoreVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ShieldBox VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openAppIntent)
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

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
