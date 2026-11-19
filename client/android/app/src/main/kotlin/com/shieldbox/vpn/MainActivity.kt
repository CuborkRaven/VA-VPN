package com.shieldbox.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val METHOD_CHANNEL = "com.shieldbox.vpn/control"
        private const val EVENT_CHANNEL = "com.shieldbox.vpn/events"
        private const val PREFS_NAME = "shieldbox_prefs"
        private const val KEY_CONNECT_ON_LAUNCH = "connect_on_app_launch"
        private const val REQ_VPN_PREPARE = 1001
        private const val REQ_POST_NOTIFICATIONS = 1002
    }

    private var eventSink: EventChannel.EventSink? = null
    private var pendingStartResult: MethodChannel.Result? = null
    private var pendingConfigJson: String? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CoreVpnService.ACTION_STATUS_UPDATE) return
            val payload = mapOf(
                "status" to (intent.getStringExtra(CoreVpnService.EXTRA_STATUS) ?: "DISCONNECTED"),
                "uplinkSpeed" to intent.getLongExtra(CoreVpnService.EXTRA_UPLINK_SPEED, 0L),
                "downlinkSpeed" to intent.getLongExtra(CoreVpnService.EXTRA_DOWNLINK_SPEED, 0L),
                "uplinkTotal" to intent.getLongExtra(CoreVpnService.EXTRA_UPLINK_TOTAL, 0L),
                "downlinkTotal" to intent.getLongExtra(CoreVpnService.EXTRA_DOWNLINK_TOTAL, 0L),
                "error" to intent.getStringExtra(CoreVpnService.EXTRA_ERROR)
            )
            runOnUiThread { eventSink?.success(payload) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        val filter = IntentFilter(CoreVpnService.ACTION_STATUS_UPDATE)
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startVpn" -> {
                        val configJson = call.argument<String>("config")
                        if (configJson.isNullOrBlank()) {
                            result.error("INVALID_CONFIG", "VPN config JSON is empty", null)
                            return@setMethodCallHandler
                        }
                        handleStartVpn(configJson, result)
                    }

                    "stopVpn" -> {
                        val intent = Intent(this, CoreVpnService::class.java).apply {
                            action = CoreVpnService.ACTION_STOP
                        }
                        startService(intent)
                        result.success(true)
                    }

                    "getStatus" -> {
                        result.success(CoreVpnService.currentStatePayload())
                    }

                    "isBatteryOptimizationIgnored" -> {
                        result.success(isBatteryOptimizationIgnored())
                    }

                    "openBatteryOptimizationSettings" -> {
                        openBatteryOptimizationSettings()
                        result.success(true)
                    }

                    "getConnectOnAppLaunch" -> {
                        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        result.success(prefs.getBoolean(KEY_CONNECT_ON_LAUNCH, false))
                    }

                    "setConnectOnAppLaunch" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_CONNECT_ON_LAUNCH, enabled)
                            .apply()
                        result.success(true)
                    }

                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                    events?.success(CoreVpnService.currentStatePayload())
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })
    }

    private fun handleStartVpn(configJson: String, result: MethodChannel.Result) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingStartResult = result
            pendingConfigJson = configJson
            @Suppress("DEPRECATION")
            startActivityForResult(prepareIntent, REQ_VPN_PREPARE)
        } else {
            launchVpnService(configJson)
            result.success(true)
        }
    }

    @Deprecated("Required for FlutterActivity compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PREPARE) {
            val result = pendingStartResult
            val config = pendingConfigJson
            pendingStartResult = null
            pendingConfigJson = null

            if (resultCode == Activity.RESULT_OK && !config.isNullOrBlank()) {
                launchVpnService(config)
                result?.success(true)
            } else {
                result?.error("VPN_PERMISSION_DENIED", "User denied VPN permission", null)
            }
        }
    }

    private fun launchVpnService(configJson: String) {
        val intent = Intent(this, CoreVpnService::class.java).apply {
            action = CoreVpnService.ACTION_START
            putExtra(CoreVpnService.EXTRA_CONFIG_JSON, configJson)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        }
        val fallbackIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(fallbackIntent)
    }
}
