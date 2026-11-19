package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.DefaultNodes
import com.example.model.RoutingSettings
import com.example.model.VpnConnectionState
import com.example.model.VpnProtocolMode
import com.example.model.VpnServerNode
import com.example.model.VpnTelemetry
import com.example.model.buildSingBoxPreviewJson
import com.example.model.findFastestNode
import com.example.vpn.AppSplitTunnelHelper
import com.example.vpn.BatteryOptimizationHelper
import com.example.vpn.LaunchableAppInfo
import com.example.vpn.NetworkDiagnostics
import com.example.vpn.NetworkDiagnosticsResult
import com.example.vpn.ShieldVpnService
import com.example.vpn.VpnStateHolder
import com.example.vpn.VpnUriParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VpnAppUiState(
    val telemetry: VpnTelemetry = VpnTelemetry(),
    val nodes: List<VpnServerNode> = DefaultNodes.list,
    val selectedNodeId: String = DefaultNodes.list.first().id,
    val protocolMode: VpnProtocolMode = VpnProtocolMode.AUTO_FALLBACK,
    val routing: RoutingSettings = RoutingSettings(),
    val isTestingPings: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
    val connectOnAppLaunch: Boolean = false,
    val isRunningDiagnostics: Boolean = false,
    val diagnosticsResult: NetworkDiagnosticsResult? = null,
    val lastBenchmarkTimestampMs: Long? = null,
    val installedApps: List<LaunchableAppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
    val importFeedback: String? = null
) {
    val selectedNode: VpnServerNode
        get() = nodes.firstOrNull { it.id == selectedNodeId } ?: nodes.first()

    val fastestNode: VpnServerNode?
        get() = findFastestNode(nodes)
}

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val PREFS_NAME = "shieldbox_prefs"
        const val KEY_CONNECT_ON_LAUNCH = "connect_on_app_launch"
        const val KEY_CUSTOM_NODES = "custom_nodes_json"
        const val KEY_EXCLUDED_APPS = "excluded_apps"
        const val KEY_MTU = "vpn_mtu"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var hasAttemptedAutoConnect = false

    private val initialCustomNodes = VpnUriParser.decodeCustomNodes(
        prefs.getString(KEY_CUSTOM_NODES, null)
    )

    private val _uiState = MutableStateFlow(
        VpnAppUiState(
            nodes = DefaultNodes.list + initialCustomNodes,
            selectedNodeId = prefs.getString("selected_node_id", "de-fra-1") ?: "de-fra-1",
            protocolMode = VpnProtocolMode.entries.getOrElse(
                prefs.getInt("protocol_ordinal", 0)
            ) { VpnProtocolMode.AUTO_FALLBACK },
            routing = RoutingSettings(
                bypassDomesticRu = prefs.getBoolean("bypass_ru", true),
                bypassPrivateNetworks = prefs.getBoolean("bypass_private", true),
                blockDnsLeaksDoH = prefs.getBoolean("doh_dns", true),
                strictKillSwitch = prefs.getBoolean("kill_switch", true),
                mtu = prefs.getInt(KEY_MTU, 1400),
                excludedPackages = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet()).orEmpty().toSet()
            ),
            isBatteryOptimizationIgnored =
                BatteryOptimizationHelper.isIgnoringBatteryOptimizations(application),
            connectOnAppLaunch = prefs.getBoolean(KEY_CONNECT_ON_LAUNCH, false)
        )
    )
    val uiState: StateFlow<VpnAppUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            VpnStateHolder.telemetry.collect { telemetry ->
                _uiState.update { it.copy(telemetry = telemetry) }
            }
        }
    }

    fun refreshBatteryOptimizationStatus() {
        val ignored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(getApplication())
        _uiState.update { it.copy(isBatteryOptimizationIgnored = ignored) }
    }

    fun setConnectOnAppLaunch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONNECT_ON_LAUNCH, enabled).apply()
        _uiState.update { it.copy(connectOnAppLaunch = enabled) }
    }

    fun consumeAutoConnectIfNeeded(): Boolean {
        if (hasAttemptedAutoConnect) return false
        hasAttemptedAutoConnect = true
        val state = _uiState.value
        return state.connectOnAppLaunch &&
            state.telemetry.connectionState == VpnConnectionState.DISCONNECTED
    }

    fun selectNode(nodeId: String) {
        prefs.edit().putString("selected_node_id", nodeId).apply()
        _uiState.update { it.copy(selectedNodeId = nodeId) }
        if (_uiState.value.telemetry.connectionState == VpnConnectionState.CONNECTED) {
            startVpnService()
        }
    }

    fun selectProtocolMode(mode: VpnProtocolMode) {
        prefs.edit().putInt("protocol_ordinal", mode.ordinal).apply()
        _uiState.update { it.copy(protocolMode = mode) }
        if (_uiState.value.telemetry.connectionState == VpnConnectionState.CONNECTED) {
            startVpnService()
        }
    }

    fun updateRouting(transform: (RoutingSettings) -> RoutingSettings) {
        _uiState.update { state ->
            val updated = transform(state.routing)
            prefs.edit()
                .putBoolean("bypass_ru", updated.bypassDomesticRu)
                .putBoolean("bypass_private", updated.bypassPrivateNetworks)
                .putBoolean("doh_dns", updated.blockDnsLeaksDoH)
                .putBoolean("kill_switch", updated.strictKillSwitch)
                .putInt(KEY_MTU, updated.mtu)
                .putStringSet(KEY_EXCLUDED_APPS, updated.excludedPackages)
                .apply()
            state.copy(routing = updated)
        }
        if (_uiState.value.telemetry.connectionState == VpnConnectionState.CONNECTED) {
            startVpnService()
        }
    }

    fun setMtu(mtu: Int) {
        updateRouting { it.copy(mtu = mtu) }
    }

    fun toggleExcludedApp(packageName: String) {
        updateRouting { current ->
            val next = current.excludedPackages.toMutableSet()
            if (!next.add(packageName)) {
                next.remove(packageName)
            }
            current.copy(excludedPackages = next)
        }
    }

    fun loadInstalledAppsIfNeeded() {
        if (_uiState.value.installedApps.isNotEmpty() || _uiState.value.isLoadingApps) return
        _uiState.update { it.copy(isLoadingApps = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val apps = AppSplitTunnelHelper.loadLaunchableApps(getApplication())
            _uiState.update { it.copy(installedApps = apps, isLoadingApps = false) }
        }
    }

    fun importNodesFromUri(rawInput: String): Int {
        val parsed = VpnUriParser.parseInput(rawInput)
        if (parsed.isEmpty()) {
            _uiState.update {
                it.copy(importFeedback = "Не удалось распознать ссылку vless:// или hy2://")
            }
            return 0
        }
        addCustomNodes(parsed)
        _uiState.update {
            it.copy(importFeedback = "Добавлено серверов: ${parsed.size}")
        }
        return parsed.size
    }

    fun addManualNode(
        label: String,
        host: String,
        port: Int,
        uuidOrPassword: String,
        sni: String,
        publicKey: String,
        shortId: String,
        isHysteria2: Boolean
    ) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return
        val node = VpnServerNode(
            id = "custom-${java.util.UUID.randomUUID().toString().take(8)}",
            countryCode = "VPS",
            countryName = label.trim().ifEmpty { cleanHost },
            city = if (isHysteria2) "Hysteria 2 · Custom" else "VLESS Reality · Custom",
            flagEmoji = "🛰️",
            endpointHost = cleanHost,
            port = port.coerceIn(1, 65535),
            region = "CUSTOM",
            loadPercent = 10,
            defaultPingMs = 42,
            supportsReality = !isHysteria2,
            supportsHysteria2 = isHysteria2,
            supportsCdn = false,
            uuid = if (!isHysteria2) uuidOrPassword.trim() else "",
            sni = sni.trim().ifEmpty { if (isHysteria2) "bing.com" else "www.microsoft.com" },
            publicKey = publicKey.trim(),
            shortId = shortId.trim(),
            hy2Password = if (isHysteria2) uuidOrPassword.trim() else "",
            isCustom = true
        )
        addCustomNodes(listOf(node))
        _uiState.update { it.copy(importFeedback = "Сервер ${node.countryName} добавлен") }
    }

    private fun addCustomNodes(newNodes: List<VpnServerNode>) {
        _uiState.update { state ->
            val combined = state.nodes + newNodes
            prefs.edit()
                .putString(KEY_CUSTOM_NODES, VpnUriParser.encodeCustomNodes(combined))
                .apply()
            val nextSelected = newNodes.firstOrNull()?.id ?: state.selectedNodeId
            prefs.edit().putString("selected_node_id", nextSelected).apply()
            state.copy(nodes = combined, selectedNodeId = nextSelected)
        }
    }

    fun deleteCustomNode(nodeId: String) {
        _uiState.update { state ->
            val filtered = state.nodes.filterNot { it.id == nodeId && it.isCustom }
            prefs.edit()
                .putString(KEY_CUSTOM_NODES, VpnUriParser.encodeCustomNodes(filtered))
                .apply()
            val nextSelected = if (state.selectedNodeId == nodeId) {
                filtered.firstOrNull()?.id ?: "de-fra-1"
            } else {
                state.selectedNodeId
            }
            prefs.edit().putString("selected_node_id", nextSelected).apply()
            state.copy(nodes = filtered, selectedNodeId = nextSelected)
        }
    }

    fun clearImportFeedback() {
        _uiState.update { it.copy(importFeedback = null) }
    }

    fun startVpnService() {
        val state = _uiState.value
        val node = state.selectedNode
        val activeProto = when (state.protocolMode) {
            VpnProtocolMode.AUTO_FALLBACK -> "Reality · Auto"
            VpnProtocolMode.VLESS_REALITY -> "VLESS Reality"
            VpnProtocolMode.HYSTERIA2 -> "Hysteria 2"
            VpnProtocolMode.VLESS_CDN -> "VLESS CDN"
        }
        val intent = Intent(getApplication(), ShieldVpnService::class.java).apply {
            action = ShieldVpnService.ACTION_START
            putExtra(ShieldVpnService.EXTRA_NODE_ID, node.id)
            putExtra(ShieldVpnService.EXTRA_NODE_LABEL, "${node.countryName} · ${node.city}")
            putExtra(ShieldVpnService.EXTRA_PROTOCOL_LABEL, activeProto)
            putExtra(ShieldVpnService.EXTRA_MTU, state.routing.mtu)
            putStringArrayListExtra(
                ShieldVpnService.EXTRA_EXCLUDED_PACKAGES,
                ArrayList(state.routing.excludedPackages)
            )
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun stopVpnService() {
        val intent = Intent(getApplication<Application>(), ShieldVpnService::class.java).apply {
            action = ShieldVpnService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    fun measureAllServerPings() {
        if (_uiState.value.isTestingPings) return
        _uiState.update { it.copy(isTestingPings = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val currentNodes = _uiState.value.nodes
            val baseRttDeferred = async { NetworkDiagnostics.measureHttpLatencyMs() ?: 38 }
            val linkMbpsDeferred = async { NetworkDiagnostics.measureQuickBandwidthMbps() }
            val baseRtt = baseRttDeferred.await()
            val linkMbps = linkMbpsDeferred.await()

            val updatedNodes = coroutineScope {
                currentNodes.mapIndexed { idx, node ->
                    async {
                        val directTcp = NetworkDiagnostics.measureTcpPingMs(node.endpointHost, node.port)
                        val measuredPing = directTcp ?: run {
                            val jitter = (node.defaultPingMs - 38).coerceAtLeast(-8)
                            (baseRtt + jitter + (idx * 3)).coerceAtLeast(14)
                        }
                        val measuredMbps = NetworkDiagnostics.estimateNodeBandwidthMbps(
                            baselineMbps = linkMbps,
                            latencyMs = measuredPing,
                            loadPercent = node.loadPercent
                        )
                        node.copy(
                            measuredPingMs = measuredPing,
                            measuredBandwidthMbps = measuredMbps
                        )
                    }
                }.awaitAll()
            }
            _uiState.update {
                it.copy(
                    nodes = updatedNodes,
                    isTestingPings = false,
                    lastBenchmarkTimestampMs = System.currentTimeMillis()
                )
            }
        }
    }

    fun selectFastestNode() {
        val fastest = _uiState.value.fastestNode ?: return
        selectNode(fastest.id)
    }

    fun runNetworkDiagnostics() {
        if (_uiState.value.isRunningDiagnostics) return
        _uiState.update { it.copy(isRunningDiagnostics = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val node = _uiState.value.selectedNode
            val result = NetworkDiagnostics.runFullCheck(node.endpointHost, node.port)
            _uiState.update {
                it.copy(
                    isRunningDiagnostics = false,
                    diagnosticsResult = result
                )
            }
        }
    }

    fun generatedConfigJson(): String {
        val s = _uiState.value
        return buildSingBoxPreviewJson(s.selectedNode, s.protocolMode, s.routing)
    }
}
