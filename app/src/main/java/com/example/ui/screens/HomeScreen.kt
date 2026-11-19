package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.VpnConnectionState
import com.example.ui.components.AnimatedConnectOrb
import com.example.ui.components.CountryPickerBottomSheet
import com.example.ui.components.NetworkDiagnosticsSheet
import com.example.ui.components.SingBoxConfigDialog
import com.example.ui.components.ThroughputSparklineCard
import com.example.ui.components.formatBytesPerSec
import com.example.ui.components.formatTotalBytes
import com.example.ui.theme.AmberConnecting
import com.example.ui.theme.CyanUpload
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.VpnAppUiState

@Composable
fun HomeScreen(
    uiState: VpnAppUiState,
    configJsonPreview: String,
    onToggleConnection: () -> Unit,
    onSelectNode: (String) -> Unit,
    onAutoConnect: () -> Unit = {},
    onRunDiagnostics: () -> Unit = {},
    onRunPreConnectTest: () -> Unit = {},
    onSelectFastestNode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var showCountrySheet by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }

    val telemetry = uiState.telemetry

    LaunchedEffect(Unit) {
        if (uiState.connectOnAppLaunch && telemetry.connectionState == VpnConnectionState.DISCONNECTED) {
            onAutoConnect()
        }
    }
    val node = uiState.selectedNode
    val stateColor = when (telemetry.connectionState) {
        VpnConnectionState.CONNECTED -> EmeraldAccent
        VpnConnectionState.CONNECTING -> AmberConnecting
        VpnConnectionState.DISCONNECTED -> TextMuted
    }

    val stateLabel = when (telemetry.connectionState) {
        VpnConnectionState.CONNECTED -> "ТУННЕЛЬ ЗАЩИЩЁН"
        VpnConnectionState.CONNECTING -> "УСТАНОВКА TUN..."
        VpnConnectionState.DISCONNECTED -> "ПОДКЛЮЧЕНИЕ ОТКЛЮЧЕНО"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        if (telemetry.connectionState == VpnConnectionState.CONNECTED) {
                            EmeraldAccent.copy(alpha = 0.08f)
                        } else {
                            ObsidianBg
                        },
                        ObsidianBg
                    )
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: SHIELDBOX + TUN mode pill + JSON viewer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(stateColor)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "SHIELDBOX",
                        style = MaterialTheme.typography.titleLarge.copy(
                            letterSpacing = 2.2.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = ObsidianCard,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(
                            1.dp,
                            if (telemetry.tunActive) EmeraldAccent.copy(alpha = 0.5f) else ObsidianBorder
                        )
                    ) {
                        Text(
                            text = if (telemetry.tunActive) "TUN ACTIVE" else "DPI SHIELD",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (telemetry.tunActive) EmeraldAccent else AmberConnecting,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showConfigDialog = true
                        },
                        modifier = Modifier.testTag("open_config_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Code,
                            contentDescription = stringResource(R.string.cd_view_config),
                            tint = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Active Protocol & Ping pill
            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = stateColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.protocolMode.shortTag,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = if (telemetry.connectionState == VpnConnectionState.CONNECTED) {
                                EmeraldAccent
                            } else {
                                TextSecondary
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val shownPing = telemetry.livePingMs ?: node.measuredPingMs
                        Text(
                            text = if (telemetry.connectionState == VpnConnectionState.CONNECTED && shownPing != null) {
                                "$shownPing ms"
                            } else {
                                "${node.defaultPingMs} ms"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Main Animated Connect Button
            AnimatedConnectOrb(
                connectionState = telemetry.connectionState,
                connectedSeconds = telemetry.connectedSeconds,
                onClick = onToggleConnection
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.8.sp),
                color = stateColor
            )

            telemetry.errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Country / Server Selector Card
            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showCountrySheet = true
                    }
                    .testTag("country_selector_card")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = node.flagEmoji, fontSize = 26.sp)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${node.countryName} · ${node.city}",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val bwSummary = node.measuredBandwidthMbps?.let {
                            " · ${String.format("%.1f Mbps", it)}"
                        }.orEmpty()
                        Text(
                            text = "${node.endpointHost} · ${node.effectivePingMs} ms$bwSummary · нагрузка ${node.loadPercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.cd_country_selector),
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pre-Connect Latency & Bandwidth Test Card
            val fastest = uiState.fastestNode
            val hasFasterAlternative = fastest != null &&
                fastest.id != node.id &&
                (fastest.effectivePingMs < node.effectivePingMs ||
                    ((fastest.measuredBandwidthMbps ?: 0.0) > (node.measuredBandwidthMbps ?: 0.0) + 5.0))
            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pre_connect_benchmark_card")
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ТЕСТ УЗЛА ДО ПОДКЛЮЧЕНИЯ",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val latencyLabel = node.measuredPingMs?.let { "$it ms" } ?: "~${node.defaultPingMs} ms"
                            val speedLabel = node.measuredBandwidthMbps?.let {
                                String.format("%.1f Mbps", it)
                            } ?: "не измерено"
                            Text(
                                text = "Пинг: $latencyLabel · Канал: $speedLabel",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = !uiState.isTestingPings,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRunPreConnectTest()
                            },
                            border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.55f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("pre_connect_speed_test_button")
                        ) {
                            if (uiState.isTestingPings) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                    color = EmeraldAccent
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Bolt,
                                    contentDescription = null,
                                    tint = EmeraldAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (uiState.isTestingPings) "Тест…" else "Замер",
                                style = MaterialTheme.typography.labelMedium,
                                color = EmeraldAccent
                            )
                        }
                    }

                    if (hasFasterAlternative && fastest != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val fastBw = fastest.measuredBandwidthMbps?.let {
                            " · ${String.format("%.1f Mbps", it)}"
                        }.orEmpty()
                        Surface(
                            color = EmeraldAccent.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectFastestNode()
                                }
                                .testTag("select_fastest_node_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Быстрее: ${fastest.flagEmoji} ${fastest.countryName} (${fastest.effectivePingMs} ms$fastBw)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldAccent,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "Выбрать",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Download & Upload real-time cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TelemetryMetricCard(
                    label = "DOWNLOAD",
                    speed = formatBytesPerSec(telemetry.downloadBytesPerSec),
                    total = "Всего: ${formatTotalBytes(telemetry.totalDownloadBytes)}",
                    icon = Icons.Rounded.South,
                    accent = EmeraldAccent,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricCard(
                    label = "UPLOAD",
                    speed = formatBytesPerSec(telemetry.uploadBytesPerSec),
                    total = "Всего: ${formatTotalBytes(telemetry.totalUploadBytes)}",
                    icon = Icons.Rounded.North,
                    accent = CyanUpload,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live Sparkline Activity Graph
            ThroughputSparklineCard(
                downloadHistory = telemetry.downloadHistory,
                uploadHistory = telemetry.uploadHistory
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Network / IP / DoH Leak Diagnostics Card
            val diag = uiState.diagnosticsResult
            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showDiagnosticsSheet = true
                        if (diag == null && !uiState.isRunningDiagnostics) {
                            onRunDiagnostics()
                        }
                    }
                    .testTag("network_diagnostics_card")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = null,
                        tint = if (diag?.dohReachable == true) EmeraldAccent else CyanUpload,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (diag?.publicIp != null) {
                                "IP: ${diag.publicIp} · ${diag.countryCode ?: "—"}"
                            } else {
                                "Проверка IP и утечек DNS (DoH)"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = when {
                                uiState.isRunningDiagnostics -> "Выполняется проверка сети и DoH…"
                                diag?.dohReachable == true ->
                                    "DoH 1.1.1.1 активен (${diag.dohLatencyMs ?: 0} ms) · MTU ${uiState.routing.mtu}"
                                else -> "Проверить внешний IPv4, DoH и доступность ноды"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    if (showCountrySheet) {
        CountryPickerBottomSheet(
            nodes = uiState.nodes,
            selectedNodeId = uiState.selectedNodeId,
            onSelect = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelectNode(it)
            },
            onDismiss = { showCountrySheet = false }
        )
    }

    if (showConfigDialog) {
        SingBoxConfigDialog(
            configJson = configJsonPreview,
            onDismiss = { showConfigDialog = false }
        )
    }

    if (showDiagnosticsSheet) {
        NetworkDiagnosticsSheet(
            node = node,
            isRunning = uiState.isRunningDiagnostics,
            result = uiState.diagnosticsResult,
            onRunDiagnostics = onRunDiagnostics,
            onDismiss = { showDiagnosticsSheet = false }
        )
    }
}

@Composable
private fun TelemetryMetricCard(
    label: String,
    speed: String,
    total: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, ObsidianBorder),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = speed,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = total,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}
