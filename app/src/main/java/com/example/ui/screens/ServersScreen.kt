package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.VpnServerNode
import com.example.ui.components.AddServerDialog
import com.example.ui.theme.AmberConnecting
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyanUpload
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.VpnAppUiState
import kotlinx.coroutines.delay

@Composable
fun ServersScreen(
    uiState: VpnAppUiState,
    onSelectNode: (String) -> Unit,
    onMeasurePings: () -> Unit,
    onImportUri: (String) -> Int = { 0 },
    onAddManualNode: (
        label: String,
        host: String,
        port: Int,
        uuidOrPassword: String,
        sni: String,
        publicKey: String,
        shortId: String,
        isHysteria2: Boolean
    ) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onDeleteCustomNode: (String) -> Unit = {},
    onClearImportFeedback: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var selectedRegion by remember { mutableStateOf("ALL") }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.importFeedback) {
        if (uiState.importFeedback != null) {
            delay(3200)
            onClearImportFeedback()
        }
    }

    val filteredNodes = remember(uiState.nodes, selectedRegion) {
        when (selectedRegion) {
            "EU" -> uiState.nodes.filter { it.region == "EU" }
            "US" -> uiState.nodes.filter { it.region == "US" }
            "ASIA" -> uiState.nodes.filter { it.region == "ASIA" }
            "CUSTOM" -> uiState.nodes.filter { it.isCustom }
            "FAST" -> uiState.nodes.sortedBy { it.measuredPingMs ?: it.defaultPingMs }
            else -> uiState.nodes
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Локации и ноды",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Серверы sing-box / Xray и импорт vless://, hy2://",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showAddDialog = true
                        },
                        border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.55f)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("add_server_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.cd_add_server),
                            tint = EmeraldAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Импорт",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldAccent
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onMeasurePings()
                        },
                        border = BorderStroke(1.dp, ObsidianBorder),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("ping_test_button")
                    ) {
                        if (uiState.isTestingPings) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = EmeraldAccent
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Замерить пинг",
                                tint = EmeraldAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Пинг",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary
                        )
                    }
                }
            }

            uiState.importFeedback?.let { feedback ->
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = EmeraldAccent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = feedback,
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmeraldAccent,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf(
                    "ALL" to "Все",
                    "FAST" to "Быстрые",
                    "EU" to "Европа",
                    "US" to "США",
                    "ASIA" to "Азия",
                    "CUSTOM" to "Мои"
                ).forEach { (key, title) ->
                    val active = selectedRegion == key
                    FilterChip(
                        selected = active,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedRegion = key
                        },
                        label = { Text(title, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ObsidianCard,
                            labelColor = TextSecondary,
                            selectedContainerColor = EmeraldAccent.copy(alpha = 0.16f),
                            selectedLabelColor = EmeraldAccent
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = active,
                            borderColor = ObsidianBorder,
                            selectedBorderColor = EmeraldAccent.copy(alpha = 0.55f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (filteredNodes.isEmpty()) {
                Surface(
                    color = ObsidianCard,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Нет пользовательских серверов",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Нажмите «Импорт», чтобы добавить vless://, hy2:// или свой VPS.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredNodes, key = { it.id }) { node ->
                        ServerNodeRow(
                            node = node,
                            isSelected = node.id == uiState.selectedNodeId,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectNode(node.id)
                            },
                            onDelete = if (node.isCustom) {
                                {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDeleteCustomNode(node.id)
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddServerDialog(
            onImportUri = onImportUri,
            onAddManual = onAddManualNode,
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
private fun ServerNodeRow(
    node: VpnServerNode,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val ping = node.measuredPingMs ?: node.defaultPingMs
    Surface(
        color = if (isSelected) EmeraldAccent.copy(alpha = 0.1f) else ObsidianCard,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            1.dp,
            if (isSelected) EmeraldAccent.copy(alpha = 0.65f) else ObsidianBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("server_row_${node.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = node.flagEmoji, fontSize = 26.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${node.countryName} · ${node.city}",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        if (node.isCustom) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = CyanUpload.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "MY VPS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanUpload,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "${node.endpointHost}:${node.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "$ping ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ping < 60) EmeraldAccent else AmberConnecting
                    )
                    node.measuredBandwidthMbps?.let { mbps ->
                        Text(
                            text = String.format("%.1f Mbps", mbps),
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanUpload
                        )
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = stringResource(R.string.cd_delete_server),
                            tint = CoralAlert.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (isSelected) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (node.supportsReality) ProtocolTag("Reality")
                    if (node.supportsHysteria2) ProtocolTag("Hy2")
                    if (node.supportsCdn) ProtocolTag("CDN")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Load ${node.loadPercent}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { node.loadPercent / 100f },
                        modifier = Modifier
                            .width(56.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (node.loadPercent < 50) EmeraldAccent else AmberConnecting,
                        trackColor = ObsidianBorder
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtocolTag(text: String) {
    Surface(
        color = ObsidianBg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
