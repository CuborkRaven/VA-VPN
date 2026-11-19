package com.example.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.model.DefaultNodes
import com.example.model.VpnConnectionState
import com.example.model.VpnProtocolMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ShieldQuickSettingsTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        collectJob?.cancel()
        collectJob = scope.launch {
            VpnStateHolder.telemetry.collect { telemetry ->
                updateTileUi(telemetry.connectionState, telemetry.activeProtocolLabel)
            }
        }
    }

    override fun onStopListening() {
        collectJob?.cancel()
        collectJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val state = VpnStateHolder.telemetry.value.connectionState
        if (state != VpnConnectionState.DISCONNECTED) {
            val stopIntent = Intent(this, ShieldVpnService::class.java).apply {
                action = ShieldVpnService.ACTION_STOP
            }
            startService(stopIntent)
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    201,
                    openAppIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(openAppIntent)
            }
            return
        }

        val prefs = getSharedPreferences("shieldbox_prefs", Context.MODE_PRIVATE)
        val customNodes = VpnUriParser.decodeCustomNodes(prefs.getString("custom_nodes_json", null))
        val allNodes = DefaultNodes.list + customNodes
        val selectedId = prefs.getString("selected_node_id", "de-fra-1") ?: "de-fra-1"
        val node = allNodes.firstOrNull { it.id == selectedId } ?: allNodes.first()
        val protoMode = VpnProtocolMode.entries.getOrElse(
            prefs.getInt("protocol_ordinal", 0)
        ) { VpnProtocolMode.AUTO_FALLBACK }
        val activeProto = when (protoMode) {
            VpnProtocolMode.AUTO_FALLBACK -> "Reality · Auto"
            VpnProtocolMode.VLESS_REALITY -> "VLESS Reality"
            VpnProtocolMode.HYSTERIA2 -> "Hysteria 2"
            VpnProtocolMode.VLESS_CDN -> "VLESS CDN"
        }
        val mtu = prefs.getInt("vpn_mtu", 1400)
        val excluded = prefs.getStringSet("excluded_apps", emptySet()).orEmpty()

        val startIntent = Intent(this, ShieldVpnService::class.java).apply {
            action = ShieldVpnService.ACTION_START
            putExtra(ShieldVpnService.EXTRA_NODE_ID, node.id)
            putExtra(ShieldVpnService.EXTRA_NODE_LABEL, "${node.countryName} · ${node.city}")
            putExtra(ShieldVpnService.EXTRA_PROTOCOL_LABEL, activeProto)
            putExtra(ShieldVpnService.EXTRA_MTU, mtu)
            putStringArrayListExtra(ShieldVpnService.EXTRA_EXCLUDED_PACKAGES, ArrayList(excluded))
        }
        ContextCompat.startForegroundService(this, startIntent)
    }

    private fun updateTileUi(connectionState: VpnConnectionState, protocolLabel: String) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.qs_tile_label)
        when (connectionState) {
            VpnConnectionState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = protocolLabel
                }
            }

            VpnConnectionState.CONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.vpn_status_connecting)
                }
            }

            VpnConnectionState.DISCONNECTED -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.vpn_status_disconnected)
                }
            }
        }
        tile.updateTile()
    }
}
